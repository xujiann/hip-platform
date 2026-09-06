-- v51 车道 D：① 给药途径与溶媒配伍（5660–5679）  ② 盘点批次层收敛（5680–5699）
--
-- 两件事放同一个迁移，是因为它们同属本车道且都不与他人共表；表名前缀分别为
-- `cdss_route_*` / `cdss_solvent_*` 与 `inv_stock_take_batch_check`，互不交叉。
--
-- =====================================================================================
-- 【开工前的现状核实——先说清楚哪些是实测，哪些是本迁移新造的】
-- =====================================================================================
-- 逐文件读过 V4__masterdata.sql:3 起的 md_drug 全部 alter（V22/V32/V116/V132/V134/V149/V150）
-- 与 platform/masterdata/.../entity/DrugItem.java 的每一个字段，结论如下：
--
--   * `md_drug` **没有给药途径字段**。它有 `dose_form`（剂型：片剂/胶囊/口服液/注射剂），
--     那是「药做成什么样」，不是「怎么给进去」。同一个剂型可对应多条途径
--     （注射剂可静滴、可肌注、可鞘内），剂型推不出途径。
--     ——**这就是本条需求的数据源缺口，如实记在此处，不靠 dose_form 反推。**
--   * 医嘱侧**有**途径位：`outp_order.usage_route varchar(32)`（V5:49）、
--     `inp_order.usage_route varchar(32)`（V8:49）、`rx_template_line.usage_route`（V136:61）。
--     三处全是**自由文本**，无字典、无 CHECK、无默认值。
--   * 溶媒在本仓**没有任何数据位**：既没有「哪些药品是溶媒」的标记，也没有
--     「这一行主药配的是哪一袋溶媒」的关联。临床上溶媒是同一处方组里的另一条医嘱行，
--     本迁移按 `group_no` 同组这一既有事实来配对，不新造关联列。
--
-- =====================================================================================
-- 【铁律一：不内置来源不明的药学知识】
-- =====================================================================================
-- 本迁移**不写入任何一条配伍禁忌、任何一条药品适用途径**。
-- 「某药只能用葡萄糖不能用生理盐水」这类判断看起来很专业、医生会信，
-- 而它一旦是编的，后果是直接改错处方。故：
--   * `cdss_drug_route` / `cdss_solvent_dict` / `cdss_solvent_rule` 三张表**建完即空**，
--     内容由药剂科按院内用药目录与说明书维护；
--   * 规则表沿用 v51 车道 C（V154）立下的口径：`basis_source` / `basis_level` **非空且非空白**，
--     **没有出处的规则不许入库**——这条约束是数据库层面的防伪造装置，不是文档里的建议；
--   * 只有「途径词表」与「途径别名」两张**词表**各留 **1 条示例行**并以 `is_example` 标记，
--     用于示范表结构与匹配口径，上线前须由药剂科按院内《用法字典》替换。
--     词表不是药学知识（「口服」是一个词，不是一条临床判断），但仍按示例行纪律处理。
--
-- =====================================================================================
-- 【铁律二：自由文本不得脚本解析——本车道的等价物是「用法途径文本」】
-- =====================================================================================
-- 车道 A 已论证过「青霉素过敏」vs「青霉素皮试阴性」的反向拦截风险。
-- 本车道的自由文本是 `outp_order.usage_route`。**不写正则、不写关键词包含匹配**：
-- 「静滴」「静脉滴注」「静推」三个词里，前两个同义、第三个（静脉注射）是另一条途径，
-- 而「静推」包含「静」、「滴」不包含——任何 contains/正则都会在这组词上出错，
-- 而出错的方向是**把静推当静滴放行**或**把静滴当静推拦住**，两个都危险。
--
-- 正确做法：`cdss_route_alias` 是一张**院内维护的精确匹配映射表**。
-- 引擎只做一件事：把医嘱里的文本按固定口径归一化（去掉全部空白 + ASCII 转小写，
-- **不做任何语义变换**）后，去 `alias_text` 上做**等值查找**。
--   * 查得到 → 得到受控途径编码，进入判定；
--   * **查不到 → 不判定、不拦截**，只回一条「该用法文本未登记」的提示，
--     让药剂科去登记。**绝不猜**。看不懂的文本上做出的拦截，比不拦更危险。
--
-- =====================================================================================
-- 【铁律三：既有链路不许改坏】
-- =====================================================================================
-- `cdss_ddi_rule` / `cdss_dose_rule` / `cdss_age_rule` / `cdss_alert` / `cdss_suggestion`
-- 五张既有表**一个字节不动**；`CdssService` 的三类规则与 `CdssController` 的 4 个端点契约
-- 逐字不动；错误码 4015 / 4017 / 4650 原样。
-- 本版新表新端点（`/api/cdss/route/**`），留痕落**自己的** `cdss_route_alert`——
-- 沿用 V153 的判断：混进既有 `cdss_alert` 会污染既有提醒报表的口径。
--
-- =====================================================================================
-- 【铁律四：gate 三态、默认 warn】
-- =====================================================================================
-- `cdss.gate.route` / `cdss.gate.solvent` 两个键，off|warn|block，默认 warn，坏值回落 warn。
-- 为什么不 block：这两类校验此前从无，且**判定所依赖的两张表今天是空的**——
-- 直接 block 不会拦下任何东西（无规则即无判定），但一旦药剂科录了半张表就会当场
-- 拦掉大量正常处方。warn 档把提示真的给到医生（返回体 warnings + cdss_route_alert 留痕），
-- 收紧到 block 由院方在规则维护完成后自行决定。
--
-- 盘点那一条的 gate 键是 `pharm.gate.stocktake.batch`——**刻意不叫 cdss.gate.***：
-- 它是一个库存点位，与临床决策支持无关，放进 cdss 命名空间会让管理员在配置页上
-- 找不到它、也读不懂它。同族既有键是 `pharm.gate.expiry.dispense`（V150）与
-- `pharm.gate.batch.required`（V147），本键与它们同族。此处对下发的命名约定
-- 作了一次有署名的偏离，理由如上。

-- =====================================================================================
-- 第一部分：给药途径与溶媒（错误码 5660–5679）
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 表一：cdss_route_dict —— 给药途径受控词表
-- -------------------------------------------------------------------------------------
-- 为什么要有一张词表而不是直接用文本：判定必须落在**有限、可枚举、可审阅**的编码上。
-- 药剂科能在一页纸上看完全院认可的给药途径，才谈得上审规则；
-- 若判定直接跑在自由文本上，规则表里会同时出现「静滴」「静脉滴注」「iv gtt」三行同义规则，
-- 改一行漏两行，且没人看得出来漏了。
create table cdss_route_dict (
    id          bigserial    primary key,
    -- 院内受控编码。**不预置国标码**：给药途径的国标/WHO 编码本仓无授权数据源，
    -- 编一套出来会在与外部系统对接时对不上。由药剂科按院内口径定义。
    route_code  varchar(24)  not null unique,
    route_name  varchar(64)  not null,
    -- 是否静脉途径。**溶媒配伍校验只对本列为 true 的途径生效**——
    -- 口服药谈溶媒是没有意义的，把溶媒检查铺到所有途径上只会制造噪音。
    -- 本列由药剂科维护，不由 route_code 的字面猜（编码是院内自定义的，猜不了）。
    intravenous boolean      not null default false,
    -- 示例行标记。种子行为 true；药剂科替换后置 false。
    -- coverage 端点按本列分别报数，管理者一眼能看出「这张表还是出厂示例」。
    is_example  boolean      not null default false,
    enabled     boolean      not null default true,
    remark      varchar(255),
    created_by  bigint       references sys_user (id),
    -- 显式写入而非 default now()：全仓时刻一律 Instant.now().truncatedTo(MICROS)，
    -- 交由 default now() 会拿到微秒以下精度，回读后与写入值不等（本仓已因此炸过四次）。
    -- 种子行在本文件内显式给值。
    created_at  timestamptz  not null,
    constraint ck_cdss_route_dict_code check (length(btrim(route_code)) > 0 and length(btrim(route_name)) > 0)
);

comment on table cdss_route_dict is
    'v51：给药途径受控词表。md_drug 无途径字段（实测 V4/V22/V32/V116/V132/V134/V149/V150 全部 alter），本表是缺失数据源的可维护替代之一。';
comment on column cdss_route_dict.intravenous is
    '是否静脉途径；溶媒配伍校验仅对 true 的途径生效。由药剂科维护，不由编码字面推断。';
comment on column cdss_route_dict.is_example is
    'true = 随迁移下发的示例行，上线前须由药剂科按院内《用法字典》替换。coverage 端点据此如实报数。';

-- -------------------------------------------------------------------------------------
-- 表二：cdss_route_alias —— 医嘱自由文本 → 途径编码 的**精确匹配**映射
-- -------------------------------------------------------------------------------------
-- 这张表是铁律二的落地物。它存在的唯一理由是：`outp_order.usage_route` 是自由文本，
-- 而判定必须跑在受控编码上，两者之间的桥**只能是人工维护的等值映射**，不能是脚本推断。
--
-- alias_text 存的是**归一化之后**的文本（去掉全部空白 + ASCII 转小写）。
-- 归一化在写入与查询两侧用同一个函数，故 unique 约束是有效的去重。
-- 归一化**只做字符级、不做语义级**：不去标点、不做同义替换、不做全角半角折叠——
-- 后两者中的任何一个都会让「静推」与「静滴」之类的组合出现意料之外的相等。
create table cdss_route_alias (
    id         bigserial   primary key,
    alias_text varchar(64) not null unique,
    route_code varchar(24) not null references cdss_route_dict (route_code),
    is_example boolean     not null default false,
    created_by bigint      references sys_user (id),
    created_at timestamptz not null,
    constraint ck_cdss_route_alias_text check (length(btrim(alias_text)) > 0)
);

comment on table cdss_route_alias is
    'v51：用法途径自由文本→受控编码的人工映射。引擎只做等值查找，**不做正则/关键词/包含匹配**；查不到即不判定，不猜。';
comment on column cdss_route_alias.alias_text is
    '已归一化文本（去全部空白 + ASCII 小写）。归一化仅字符级，不做同义替换与全角折叠。';

-- -------------------------------------------------------------------------------------
-- 表三：cdss_drug_route —— 药品适用给药途径（**本迁移建完即空**）
-- -------------------------------------------------------------------------------------
-- 这就是「md_drug 没有途径字段」这一缺口的可维护替代。
-- **刻意不在 md_drug 上加一列 route**：一个药常有多条合法途径（同一支注射剂可静滴可肌注），
-- 单列存不下；存成逗号分隔串就是把一张关联表塞进一个字符串，日后必然出现
-- 「IV,IM」与「IM,IV」两种写法而查询对不上。故独立成表，一药多行。
--
-- **零回填**：本迁移不含任何 update，也不为任何既有药品插入途径行。
-- 最诱人的歧路是「按 dose_form='注射剂' 就配上静滴」——那是在替药剂科下临床结论，
-- 而注射剂里既有只能静滴的，也有只能肌注的、还有严禁静推的。猜错就是拦错。
create table cdss_drug_route (
    id           bigserial    primary key,
    drug_id      bigint       not null references md_drug (id),
    route_code   varchar(24)  not null references cdss_route_dict (route_code),
    -- 依据必填（同 V154 车道 C 口径）：没有出处的规则不许入库。
    -- 医生看到「途径不符」的提示时要能回答「凭什么」，否则整类提示会被无视。
    basis_source varchar(255) not null,
    enabled      boolean      not null default true,
    created_by   bigint       references sys_user (id),
    created_at   timestamptz  not null,
    unique (drug_id, route_code),
    constraint ck_cdss_drug_route_basis check (length(btrim(basis_source)) > 0)
);

comment on table cdss_drug_route is
    'v51：药品适用给药途径（一药多行）。**建完即空、零回填**——不得按 dose_form 推断，注射剂里可静滴/仅肌注/禁静推三类都有。';
comment on column cdss_drug_route.basis_source is
    '依据出处：说明书版本 / 院内用药目录 / 指南名称+年份。非空白约束是防伪造的数据库兜底。';

create index idx_cdss_drug_route_drug on cdss_drug_route (drug_id) where enabled;

-- -------------------------------------------------------------------------------------
-- 表四：cdss_solvent_dict —— 院内在用溶媒目录（**建完即空**）
-- -------------------------------------------------------------------------------------
-- 「哪一条医嘱行是溶媒」在本仓没有任何数据位。**不按药名判**：
-- 「0.9%氯化钠注射液」是溶媒，「复方氯化钠注射液」多数场景是治疗用液而非溶媒，
-- 「氯化钠注射液(冲管用)」院内还可能单列一个品规。按名字里有没有「氯化钠」来判，
-- 三者会被一视同仁，而它们的配伍结论并不相同。故由药剂科逐个品规登记。
--
-- solvent_code 是**分组键**：一家医院常有多个厂家的 0.9%NS 品规，
-- 配伍规则应当写在「NS」这一层而不是逐品规重复写。编码由药剂科自定义。
create table cdss_solvent_dict (
    id           bigserial    primary key,
    -- 一个药品品规只能是一种溶媒，故 unique
    drug_id      bigint       not null unique references md_drug (id),
    solvent_code varchar(24)  not null,
    solvent_name varchar(64)  not null,
    basis_source varchar(255) not null,
    enabled      boolean      not null default true,
    created_by   bigint       references sys_user (id),
    created_at   timestamptz  not null,
    constraint ck_cdss_solvent_dict_code
        check (length(btrim(solvent_code)) > 0 and length(btrim(solvent_name)) > 0
               and length(btrim(basis_source)) > 0)
);

comment on table cdss_solvent_dict is
    'v51：院内在用溶媒品规目录（药品 → 溶媒分组编码）。**建完即空**——不得按药名判定「这是不是溶媒」。';

create index idx_cdss_solvent_dict_code on cdss_solvent_dict (solvent_code) where enabled;

-- -------------------------------------------------------------------------------------
-- 表五：cdss_solvent_rule —— 溶媒配伍规则（**建完即空**）
-- -------------------------------------------------------------------------------------
-- 判定语义（写在这里，因为它决定了「表没维护完时会发生什么」，这是最容易出事的地方）：
--   * 该药**一条规则行都没有** → 未维护 → **不判定**，只回一条提示。
--   * 该药有规则行，且命中 (drug_id, solvent_code)：
--       verdict = 'FORBID' → 违规（warn 档回提示、block 档拦截 5662）
--       verdict = 'ALLOW'  → 通过
--   * 该药有规则行，但**本次用的溶媒不在其中** → **不判定**，回一条「该溶媒未登记」提示。
--
-- 最后一条是本表最要紧的一个决定：**未登记 ≠ 禁用**。
-- 若把「不在白名单」当作禁配，那么药剂科每录一条 ALLOW 就等于同时禁掉了所有没录的溶媒，
-- 一张录到一半的表会把大批正常医嘱判成配伍禁忌——**用表的不完整去制造拦截**，
-- 是把「我们还没维护完」伪装成「这条医嘱有问题」。宁可漏报，也不拿空白当结论。
create table cdss_solvent_rule (
    id           bigserial    primary key,
    drug_id      bigint       not null references md_drug (id),
    solvent_code varchar(24)  not null,
    verdict      varchar(8)   not null,
    basis_source varchar(255) not null,
    basis_level  varchar(64)  not null,
    message      varchar(512) not null,
    enabled      boolean      not null default true,
    created_by   bigint       references sys_user (id),
    created_at   timestamptz  not null,
    unique (drug_id, solvent_code),
    constraint ck_cdss_solvent_rule_verdict check (verdict in ('ALLOW', 'FORBID')),
    constraint ck_cdss_solvent_rule_basis
        check (length(btrim(basis_source)) > 0 and length(btrim(basis_level)) > 0
               and length(btrim(message)) > 0)
);

comment on table cdss_solvent_rule is
    'v51：静脉用药溶媒配伍规则。**建完即空**，内容由药剂科维护。未登记的溶媒组合视为「未判定」而非「禁配」——用表的不完整制造拦截是把维护缺口伪装成临床问题。';
comment on column cdss_solvent_rule.verdict is
    'ALLOW 可配 / FORBID 禁配。无匹配行 = 未判定，不等于 FORBID。';

create index idx_cdss_solvent_rule_drug on cdss_solvent_rule (drug_id) where enabled;

-- -------------------------------------------------------------------------------------
-- 表六：cdss_route_alert —— 途径/溶媒提示留痕
-- -------------------------------------------------------------------------------------
-- **不写既有 cdss_alert**：那是 DDI/疗程提醒的留痕表，GET /api/cdss/alerts 与既有报表
-- 按它的 rule_type 口径统计。混进两类新提示会把既有口径改掉而调用方毫无察觉（同 V153 判断）。
--
-- warn 档必须留痕，否则「提示过」这件事在事后无从证明——
-- 而 warn 档的全部价值就在于「先让问题可见可统计」。
create table cdss_route_alert (
    id              bigserial    primary key,
    registration_id bigint,
    patient_id      bigint,
    order_id        bigint,
    drug_id         bigint       not null references md_drug (id),
    -- ROUTE_MISMATCH 途径不符 / SOLVENT_FORBID 溶媒禁配 / ROUTE_UNRECOGNIZED 用法文本未登记
    kind            varchar(24)  not null,
    -- 命中当时的 gate 档位；日后有人把档位从 warn 调到 block，历史留痕仍能还原当时口径
    gate            varchar(8)   not null,
    blocked         boolean      not null,
    -- 原始文本与归一化后文本都留：药剂科要据此补别名，光看归一化结果补不准
    route_text_raw  varchar(64),
    route_text_norm varchar(64),
    route_code      varchar(24),
    solvent_code    varchar(24),
    message         varchar(512) not null,
    operator_id     bigint       references sys_user (id),
    created_at      timestamptz  not null,
    constraint ck_cdss_route_alert_kind
        check (kind in ('ROUTE_MISMATCH', 'SOLVENT_FORBID', 'ROUTE_UNRECOGNIZED'))
);

comment on table cdss_route_alert is
    'v51：途径/溶媒提示留痕。刻意与既有 cdss_alert 分表——混表会改掉既有提醒报表的口径。';

create index idx_cdss_route_alert_reg  on cdss_route_alert (registration_id, id desc);
create index idx_cdss_route_alert_kind on cdss_route_alert (kind, id desc);

-- -------------------------------------------------------------------------------------
-- 词表示例行（各 1 条，`is_example = true`）
-- -------------------------------------------------------------------------------------
-- 只给词表示例，**不给任何药品适用途径、不给任何溶媒、不给任何配伍规则**——
-- 后三者是药学结论，编一条出来就会有人当真。
--
-- 示例行留 enabled = true 是安全的：判定要同时满足「别名查得到」与
-- 「该药维护了适用途径」两个条件，而 cdss_drug_route 是空表，故本版不会产生任何途径判定。
-- coverage 端点会把这一事实明说出来，不让人误以为「校验已经在跑了」。
insert into cdss_route_dict (route_code, route_name, intravenous, is_example, enabled, remark, created_at) values
    ('ORAL', '口服', false, true, true,
     '示例行，上线前须由药剂科按院内《用法字典》替换。intravenous 由药剂科逐条维护，不由编码字面推断。',
     timestamp with time zone '2026-01-01 00:00:00+08');

insert into cdss_route_alias (alias_text, route_code, is_example, created_at) values
    ('口服', 'ORAL', true, timestamp with time zone '2026-01-01 00:00:00+08');

-- -------------------------------------------------------------------------------------
-- gate 配置键
-- -------------------------------------------------------------------------------------
insert into sys_config (cfg_key, cfg_value, remark) values
    ('cdss.gate.route', 'warn',
     '给药途径校验：off 旁路 / warn 放行并回带 warnings 且留痕（默认）/ block 拒绝开单（5661）。'
     '坏值回落 warn 不回落 off。用法文本未登记在 cdss_route_alias 时**任何档位都不拦**——看不懂的文本上不做拦截。')
on conflict (cfg_key) do nothing;

insert into sys_config (cfg_key, cfg_value, remark) values
    ('cdss.gate.solvent', 'warn',
     '溶媒配伍校验：off 旁路 / warn 放行并回带 warnings 且留痕（默认）/ block 拒绝开单（5662）。'
     '仅对 cdss_route_dict.intravenous = true 的途径生效；该药无规则行或该溶媒未登记时任何档位都不拦。')
on conflict (cfg_key) do nothing;

-- =====================================================================================
-- 第二部分：盘点批次层收敛（错误码 5680–5699）
-- =====================================================================================
--
-- 【欠账原文】v50 由 V50PharmacyTest 的 DECLARED_OUT_OF_SCOPE「ADJ 盘点调整」署名登记：
--   「盘点仍只盘 md_drug.stock，不盘批次余额。发药/退药已收敛进批次账后两本账日常一致；
--     但一旦出现任何来源的漂移，盘点会把汇总拉到实存、把差额**永久抹平**，
--     而批次层的错账原地不动、无人知晓——『看起来一直对得上、实际每次靠盘点擦屁股』。」
--
-- 【实测：漂移确实还在源源不断地产生，不是理论风险】
-- v50 之后 `DispenseService`（发药/退药）已双写批次账，但仍有三条路径只动汇总不动批次层：
--   ① `PharmOpsService.dispenseSplit` 拆零开盒 —— 直接
--      `update md_drug set stock = stock - ? ...`（PharmOpsService.java:201），批次层不动；
--   ② `InventoryService.acceptStockIn` 走 V82 待验收页入库 —— 只加汇总、不建批次
--      （PharmStockService.coverage() 的 note 已自陈这一点）；
--   ③ `InventoryService.adjust` 单药直调 —— 直接把汇总设成新值。
-- 三条路径每走一次，已纳管药品的「汇总 vs 批次层」就多一分偏差。
--
-- 【两条修法，本迁移选后者，理由写全】
--   甲、盘点单加批次维度（盘「这个批号还剩几盒」）：最彻底。
--       **但本版不能选**，两个原因：
--       (1) 它要重写 `createStockTake` / `enterCounts` / `buildTakeView` / `StockTakeView`，
--           而下发口径明确「InventoryService 只改盘点确认那一段，其余逐字不动」；
--       (2) 更实质的一条：批次管理是**逐药逐批增量启用**的，V147 零回填，
--           绝大多数药品今天在批次层一粒都没有。给盘点单强上批次维度，
--           等于让未纳管药品**无法被盘点**——盘不了的账不会变对，只会变成盘不了。
--   乙、盘点确认时同步核对批次层，有差异就报出来（本迁移所选）：
--       改动小，且**把问题暴露出来而不是抹平**，正是欠账要求的方向。
--       核对**只对已纳管药品生效**（有 pharm_batch 记录，口径与 DispenseService.batchManaged
--       和 PharmStockService.coverage() 逐字一致）；未纳管药品行为逐字不变。
--
-- 【无论哪一档，都不静默】
--   * block 档：拒绝确认（5680/5681），差异明细写进异常消息，药师当场看得见；
--   * warn 档（默认）：确认照常，但
--       - 每一条被核对的行都落一行 `inv_stock_take_batch_check`（**含核对通过的行**，
--         这样「没有行」只可能意味着「该药未纳管」，不会与「查过了、没问题」混淆）；
--       - 差异摘要随 `confirmStockTake` 的返回体下发（StockTakeView 新增 batchCheckWarnings）。
--     批次层的数字**一个都不改**——盘点不去动它，也不假装它不存在。
--   * off 档：完全旁路。留给「本院尚未启用批次管理」的场景，且是**显式选择**，不是默认。
--
-- 【为什么盘盈盘亏本身也要报（5681），而不是只报事前漂移】
-- 已纳管药品盘出差额时，把差额只写进汇总，就是当场**制造**一笔新的漂移。
-- 而把差额摊到某个批次上是不可以的：盘亏时「少的是哪一批」没人知道，
-- 按 FEFO 挑一批扣掉，总数对得上但**批次追溯从此是错的且看不出来**——
-- 召回时会照着错批号去下架真药（这正是 V151 拒绝为历史订单补分配明细的同一条理由）。
-- 正确的落账路径本仓已有且是批次感知的：盘亏走 `POST /api/pharm/stock/scrap`（按批号报损，
-- 批次与汇总同事务双扣），盘盈走 `POST /api/pharm/stock/stock-in`（按批号入库，双加）。
-- 故本表把「已纳管药品的盘盈盘亏」如实记成 UNAPPLIED_DIFF：**汇总已调、批次层未调**，
-- 差额多少、该走哪个端点补，一行看清。

create table inv_stock_take_batch_check (
    id          bigserial   primary key,
    take_id     bigint      not null references inv_stock_take (id) on delete cascade,
    -- 一条盘点行只会被确认一次（confirmStockTake 入口 claimStatus 抢占 DRAFT→CONFIRMED），
    -- 故 line_id 唯一。重复行意味着有人绕开了抢占，那本身就是要查的事故。
    line_id     bigint      not null unique references inv_stock_take_line (id) on delete cascade,
    drug_id     bigint      not null references md_drug (id),
    -- 汇总账面（= 建行时对 md_drug.stock 的快照，确认时条件更新已保证它仍等于当前汇总）
    book_qty    int         not null,
    actual_qty  int         not null,
    -- 批次层合计 sum(pharm_stock.qty)，跨全部库房/药柜
    batch_qty   int         not null,
    -- 事前漂移 = batch_qty - book_qty。**与 GET /api/pharm/stock/balance 的 drift 同号同义**，
    -- 两处口径必须一致，否则药师对着两个都叫 drift、符号相反的数会得出相反结论
    pre_drift   int         not null,
    -- 本次盘盈(+)/盘亏(-) = actual_qty - book_qty
    count_delta int         not null,
    -- OK 无差异 / DRIFT 仅事前漂移 / UNAPPLIED_DIFF 仅盘盈亏未落批次层 / DRIFT_AND_DIFF 两者都有
    verdict     varchar(20) not null,
    gate        varchar(8)  not null,
    -- block 档下本行是否就是拒绝确认的原因（warn 档恒为 false）。
    -- 注意：block 档一旦拒绝，整个事务回滚，**这些 blocked=true 的行不会留下**——
    -- 拒绝本身已经把明细写进异常消息交到药师手上了。库里能查到的 blocked=true 只可能来自
    -- 「写完这行之后又被别的原因回滚过又重跑」之类的边角情形，正常口径下应为空集。
    -- 真正长期留在这张表里的是 warn 档的逐行留痕（含 verdict='OK'）。
    blocked     boolean     not null,
    note        varchar(512) not null,
    operator_id bigint      references sys_user (id),
    checked_at  timestamptz not null,
    constraint ck_inv_take_batch_check_verdict
        check (verdict in ('OK', 'DRIFT', 'UNAPPLIED_DIFF', 'DRIFT_AND_DIFF'))
);

comment on table inv_stock_take_batch_check is
    'v51：盘点确认时对批次层的核对留痕。**只对已纳管药品（有 pharm_batch 记录）建行**；'
    '未纳管药品无行，故「无行」= 未纳管，不会与「核对通过」混淆（核对通过写 verdict=OK 的行）。';
comment on column inv_stock_take_batch_check.pre_drift is
    '= batch_qty - book_qty，与 GET /api/pharm/stock/balance 的 drift 同号同义。>0 批次层偏高，<0 汇总偏高。';
comment on column inv_stock_take_batch_check.verdict is
    'UNAPPLIED_DIFF：盘盈亏已调汇总但**未落批次层**——差额该按批号走 /scrap 或 /stock-in 补，不得由盘点猜批次。';

create index idx_inv_take_batch_check_take on inv_stock_take_batch_check (take_id);
create index idx_inv_take_batch_check_drug on inv_stock_take_batch_check (drug_id, id desc);

insert into sys_config (cfg_key, cfg_value, remark) values
    ('pharm.gate.stocktake.batch', 'warn',
     '盘点确认时的批次层核对（仅对已纳管药品生效）：off 旁路 / '
     'warn 确认照常但逐行留痕 inv_stock_take_batch_check 并随返回体下发 batchCheckWarnings（默认）/ '
     'block 有差异即拒绝确认（5680 事前漂移 / 5681 盘盈亏未落批次层）。坏值回落 warn。'
     '**任何档位下盘点都不改动批次层数字**——差额按批号走 /api/pharm/stock/scrap 或 /stock-in 补。')
on conflict (cfg_key) do nothing;

-- =====================================================================================
-- 【本迁移刻意不做的事】
-- =====================================================================================
-- * **不插 sys_menu**：frontend/shell/src 是共用目录，本车道按分工不改（同 V150 判断）。
--   先插菜单等于给药剂科一个点进去 404 的死链。页面需求已写进 cross_lane。
-- * **不给 md_drug 加 route 列**：一药多途径，单列存不下；逗号串是把关联表塞进字符串。
-- * **不改 outp_order / inp_order 的 usage_route**：那是既有开单列，被收费/发药/执行三条线共用，
--   加 CHECK 或改类型会让全部历史行与所有既有写路径同时爆掉。
-- * **不回填一条 cdss_drug_route / cdss_solvent_dict / cdss_solvent_rule**：见铁律一。
-- * **不改 inv_stock_take / inv_stock_take_line 的任何既有列**：盘点单结构一字未动，
--   本部分只加一张旁挂的核对留痕表。
-- * **不建 int_route_rule 的同名近义物**：V32 已有 `int_route_rule`，那是**集成消息路由**规则
--   （keyword → adapter_id），与给药途径毫无关系，仅名字相近。本迁移的表一律 cdss_route_ 前缀，
--   不去碰它、也不复用它——同名近义表是本仓四次撞码的同源坑。
