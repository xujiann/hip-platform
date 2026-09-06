-- v50 车道 C：门诊发药核对（双人核对 / 高危药品 / 看似听似 LASA）
--
-- 【现状】发药零核对：DispenseController 一个 POST /{registrationId} 点一下整单就发，
-- 全仓「双人核对」「高危药品」「看似听似」三个词零命中。本迁移落三件事：
--   ① md_drug 上补两类**属性位**（高危）与一张**对照表**（看似听似）；
--   ② 核对单主子表（摆药人 / 核对人两个人两个时刻 + 高危逐行单独确认 + 退回摆药）；
--   ③ 一个三态 gate 配置键。
--
-- ============================================================
-- 【零回填纪律】本文件 **零条 update 语句**
-- ============================================================
-- 高危与看似听似**都不猜**：
--   * 不按药名匹配（「含吗啡即高危」「含钾即高危」这类规则会漏掉全部商品名制剂，
--     又会把「吗啡缓释片包装盒」之类误判，药师一旦发现提示不可信就会全部无视——
--     **假提示比不提示更危险**）；
--   * 看似听似**不用字符串相似度自动生成**：编辑距离会把「阿莫西林胶囊 0.25g」与
--     「阿莫西林胶囊 0.5g」判成一对（同药不同规格不是 LASA），也会把
--     「氯化钾」与「氯化钠」漏掉（三字里差一字但读音天差地别是典型 LASA）。
--     相似度既误报又漏报，全院几百条提示里只要有一半是噪音，剩下一半也就废了。
-- 故本迁移**只建空的属性位与空的对照表**，由药剂科按院内《高警示药品目录》
-- 与《易混淆药品目录》逐条维护（维护端点见 DispenseCheckController）。
--
-- 【高危属性位为什么可空、不给 not null default false】
-- 三态语义：true 已确认高危 / false 已确认非高危 / **null 尚未维护**。
-- 给 default false 等于让全库药品一次性宣称「都不是高危药」——那是我们并不掌握的事实，
-- 而且此后再也分不清「药剂科看过并判定不是」与「根本没人看过」，
-- 「高危目录维护完成度」这个管理指标就永远算不出来（同 V144 病理标本类别不设 default）。
-- 核对时按 `high_alert is true` 要求单独确认；null 行在核对返回体里以
-- 「本单 N 行药品高危属性未维护」的形式**明说提示可能不完整**，而不是假装安全。

-- ============================================================
-- 一、md_drug：高危药品属性位（**只加列，不填值**）
-- ============================================================

alter table md_drug add column high_alert    boolean;
alter table md_drug add column high_alert_at timestamptz;
alter table md_drug add column high_alert_by bigint references sys_user (id);

comment on column md_drug.high_alert is 'v50 高危（高警示）药品：true 是 / false 否 / null 未维护。无 default——默认 false 等于替药剂科宣称全库无高危药，且此后分不清「已判定非高危」与「没人看过」';
comment on column md_drug.high_alert_at is 'v50 高危属性最近一次维护时刻；历史行为 null 即「从未维护」';
comment on column md_drug.high_alert_by is 'v50 高危属性维护人 sys_user.id（不存用户名，v42 已就人字段立规）';

-- ============================================================
-- 二、看似听似（LASA）对照表：**关系不是属性**
-- ============================================================
-- LASA 的本质是「A 与 B 之间」易混淆，不是「A 本身」有问题。
-- 做成 md_drug 上一个 boolean 只能提示「本药易混淆」，药师看完还是不知道跟谁混——
-- 而真正救命的提示恰恰是那句「您拿的是**优降糖**，注意别拿成**优降宁**」。
-- 故建成对表，提示里回带对方药名。
--
-- 无序对只存一行：(lo, hi) 且 lo < hi。存两行（A→B、B→A）迟早出现只删一半的半截数据，
-- 变成「从 A 查得到、从 B 查不到」的静默漏提示。
create table pharm_lasa_pair (
    id         bigserial    primary key,
    drug_id_lo bigint       not null references md_drug (id),
    drug_id_hi bigint       not null references md_drug (id),
    -- 混淆点：'看似'（包装/外观）/'听似'（口服医嘱音近）/'两者'，由维护人填写说明
    note       varchar(200),
    created_at timestamptz  not null default now(),
    created_by bigint       references sys_user (id),
    constraint chk_pharm_lasa_order check (drug_id_lo < drug_id_hi),
    constraint uq_pharm_lasa_pair unique (drug_id_lo, drug_id_hi)
);
create index idx_pharm_lasa_hi on pharm_lasa_pair (drug_id_hi);

comment on table pharm_lasa_pair is 'v50 看似听似药品对照（无序对，lo<hi 只存一行）。由药剂科逐条维护，禁止用字符串相似度自动生成';

-- ============================================================
-- 三、核对单：主表（一次摆药提交 = 一张单）
-- ============================================================
-- 为什么挂 registration_id 而不是「一个患者一张单」：既有发药就是按挂号整单发
-- （DispenseService.dispense(registrationId)），核对单与发药动作对齐才拦得住。
-- 但**覆盖判定按处方行（见子表），不按挂号**——同一挂号上午发过药、下午又开一张新处方，
-- 若按挂号判「已核对」，上午那张单会替下午的新处方背书。这正是既有 dispense
-- 「整表回查会把上午已发的药也列进下午的发药凭条」同一个坑。
create table pharm_dispense_check (
    id              bigserial   primary key,
    registration_id bigint      not null references outp_registration (id),
    -- PREPARED 摆药完成待核对 / CHECKED 核对通过 / RETURNED 核对不通过已退回摆药
    status          varchar(16) not null default 'PREPARED',
    picker_id       bigint      not null references sys_user (id),   -- 摆药人
    picked_at       timestamptz not null default now(),
    checker_id      bigint      references sys_user (id),            -- 核对人
    checked_at      timestamptz,
    returned_by     bigint      references sys_user (id),
    returned_at     timestamptz,
    return_reason   varchar(255),
    created_at      timestamptz not null default now(),
    constraint chk_pharm_check_status check (status in ('PREPARED', 'CHECKED', 'RETURNED')),
    -- 双人核对的 DB 兜底：核对人不得与摆药人同一。应用层先返 5443（消息更可读），
    -- 这条挡的是直连改库与将来新写的代码路径——一个人签两次不叫双核对（同 v48 病理 5263）。
    constraint chk_pharm_check_two_person check (checker_id is null or checker_id <> picker_id)
);
create index idx_pharm_check_reg on pharm_dispense_check (registration_id, status);
create index idx_pharm_check_status on pharm_dispense_check (status, picked_at);

comment on constraint chk_pharm_check_two_person on pharm_dispense_check is 'v50 双人核对：核对人 <> 摆药人。与 gate 无关、永远生效——gate 管「未核对能不能发药」，不管「一个人能不能占两个位置」';

-- ============================================================
-- 四、核对单行：一行 = 一条待发处方
-- ============================================================
create table pharm_dispense_check_line (
    id       bigserial primary key,
    check_id bigint    not null references pharm_dispense_check (id) on delete cascade,
    order_id bigint    not null references outp_order (id),
    drug_id  bigint    not null references md_drug (id),
    -- 建单时刻的属性**快照**（含 null=未维护）：药剂科事后修订目录，
    -- 也不能改写「当时到底提示了什么」。事后追责看的是当时屏幕上有没有那句提示。
    high_alert boolean,
    lasa_note  varchar(500),
    -- 高危单独确认：逐行、独立时刻、独立签名人。**不是核对单上一个总的复选框**——
    -- 一次点头把五种高危药一起认了，与没确认没有区别。
    high_alert_confirmed_at timestamptz,
    high_alert_confirmed_by bigint references sys_user (id),
    -- 退回摆药时置 true。作废行不再参与「该处方是否已核对」的覆盖判定，
    -- 也不再占用下面那条唯一索引，使同一处方重新摆药后可以再建单。
    voided     boolean     not null default false,
    created_at timestamptz not null default now(),
    constraint uq_pharm_check_line unique (check_id, order_id)
);

-- 一条处方同时只能挂在一张未作废的核对单上：否则两个药师各建一张单各自核对，
-- 药会被核对两次而发两次（并发建单靠这条索引兜底，应用层先返 5451）。
create unique index uq_pharm_check_line_active
    on pharm_dispense_check_line (order_id) where voided is false;
create index idx_pharm_check_line_check on pharm_dispense_check_line (check_id);

comment on column pharm_dispense_check_line.high_alert is
    'v50 建单时 md_drug.high_alert 的快照（null=当时未维护）；目录事后修订不回改本列';

-- ============================================================
-- 五、gate：未核对能不能发药（三态，默认 warn）
-- ============================================================
-- 默认 warn 而非 block 的理由与 V143 体征、V144 病理双签同：
-- 本平台此前**从无发药核对**，直接 block 会让存量发药流程（前端发药页、
-- 已对接的自助/移动端）**瞬间大面积失败**——所有历史处方一条都没有核对单。
-- warn 档照常发药、返回体回带 warnings，先让「谁在没核对的情况下发了药」可见可统计，
-- 收紧到 block 的时机由院方按自家药房实际人力决定。
-- 坏配置在服务端回落 warn 而不是 off：宁可多提示，不可让一个笔误静默关掉核对。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('pharm.gate.dispense.check', 'warn',
     '门诊发药核对 gate：off 旁路 / warn 未核对照常发药但回带 warnings（默认）/ block 未通过核对不得发药（5448）。本 gate 不管「摆药人能否兼任核对人」——那条是 5443 恒定校验，与本 gate 无关')
on conflict (cfg_key) do nothing;

-- ============================================================
-- 六、发药核对台账：warn 档放行不等于没发生过
-- ============================================================
-- v48 病理双签的教训照搬：warn 档「放行并提示」若不落痕，
-- 事后根本查不出「谁在没核对的情况下把药发了」——那样的 warn 只是一句转瞬即逝的
-- 前端提示，既谈不上可追溯，也统计不出「未核对发药率」这个收紧到 block 的决策依据。
-- 故经新端点 POST /api/outpatient/dispense-check/registrations/{id}/dispense 发药的，
-- **三档都记一行**（passed=true 的也记，否则分母是假的）。
--
-- 【如实说明覆盖面】既有端点 POST /api/outpatient/dispense/{registrationId}
-- 逐字不动、也不经过本 gate，走那条路发的药本表**记不到**。
-- 接入方式见 DispenseCheckService 头注释与本版 cross_lane。
create table pharm_dispense_gate_log (
    id              bigserial   primary key,
    registration_id bigint      not null references outp_registration (id),
    gate            varchar(8)  not null,               -- 当次生效档位 off/warn/block
    check_passed    boolean     not null,               -- 当次是否全部处方都有已通过的核对单
    missing_orders  varchar(500),                       -- 未核对的处方行 id（超长截断，仅供人看）
    operator_id     bigint      references sys_user (id),
    occurred_at     timestamptz not null default now()
);
create index idx_pharm_gate_log_time on pharm_dispense_gate_log (occurred_at);
create index idx_pharm_gate_log_reg on pharm_dispense_gate_log (registration_id);
