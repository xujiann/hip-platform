-- ============================================================================
-- v51 车道 B：重复用药与同类药（CDSS 规则引擎扩充）
--
-- 解决的问题：同一次就诊/一定时间窗内开了相同或同类的药。三个层次：
--   ① 完全相同   —— 同一 md_drug.id 开两次（医生改方时忘删旧行，最常见）
--   ② 同通用名   —— 不同厂家/规格的同一个药（阿莫西林胶囊 / 阿莫西林颗粒）
--   ③ 同药理类别 —— 两个不同的药属同一药理类别
--
-- ============================================================================
-- 一、先说清楚数据源现状：② 和 ③ 在本仓【没有数据源】，本迁移只加列不填值
-- ============================================================================
-- 编码前实测 md_drug 的全部列（V4 建表 + V116/V132/V134/V149/V150 及抗菌分级的
-- 历次 alter）：
--     id code name spec unit dose_form price stock antibiotic enabled
--     self_pay fee_category_code
--     disable_reason disabled_at disabled_by
--     high_alert high_alert_at high_alert_by
--     abx_level drug_class ddd_per_unit pack_size min_unit
--
-- 结论：**没有通用名列，也没有药理分类列**。
--
-- 特别提示两个容易被误用成「其实已经有了」的列——它们都不是药理分类：
--   * drug_class varchar(2)：V132 加的是 'W' 西药 / 'C' 中成药，服务于**费用分类**
--     （fee_category_code 的映射源），与药理无关。拿它当同类药依据，等于把全部西药
--     判成同一类，第 ③ 层会对每一张多药处方报警，医生三天内就会学会无视所有提示。
--   * antibiotic / abx_level：抗菌药物**分级管理**标志（非限制/限制/特殊使用），
--     是管理属性不是药理分类；两个都是抗菌药不等于同类，头孢与喹诺酮联用是常规方案。
--
-- 【本迁移的选择】按 V149 高危属性位的既有口径：**加可空列，一行都不填**。
--   null = 未维护（不是「否」，也不是「不同类」）。服务层遇到 null 一律
--   **判为不可比较**，如实计入 unmaintained 回报，绝不按药品名做字符串匹配。
--
-- 【为什么绝不按药品名匹配】下发口径写死的那个例子就是致命处：
--     「阿莫西林胶囊」与「阿莫西林颗粒」是同通用名 —— 前缀匹配能对；
--     「阿莫西林」与「阿莫西林克拉维酸钾」**不是**同通用名 —— 前缀匹配会误判成重复。
--   后者是复方制剂，与前者联用在临床上是有意义的处方；把它拦下来，医生要么改方
--   （用错药），要么学会忽略重复用药提示（把第 ① 层这条真有用的规则一起废掉）。
--   字符串相似度同理，理由与 V149 拒绝自动生成看似听似对照表完全一致。
--   通用名一律走**精确相等**比较（仅 btrim 去导入时的首尾空白，这是归一不是猜测）。
--
-- 【为什么药理类别用外键字典而不是自由文本列】自由文本会让「质子泵抑制剂」
--   和「质子泵抑制剂类」变成两个类别，同类药检查静默失效且无人发现。
--   字典表 + 外键让写错的类别码在写入时就失败。
--
-- ============================================================================
-- 二、不内置任何来源不明的药学知识
-- ============================================================================
-- 本车道**没有规则内容**可编：通用名与药理类别是**药品主数据**，属院内用药目录的
-- 一部分，只能由药剂科按本院实际采购的品规逐条维护。本迁移因此：
--   * md_drug 两列全空（零条 update，零条回填）；
--   * cdss_pharm_category 只给一条**明确标注的占位示例行**，不与任何药品关联，
--     药剂科上线前删除或替换即可；
--   * 不 seed 任何「哪两个药是同类」的判断。
-- 服务层 GET /api/cdss/duplicate/config 如实回报维护覆盖率——覆盖率低时
-- 第 ②③ 层查不出东西，这一点必须让使用方看得见，而不是让它静默地什么都查不到。
--
-- ============================================================================
-- 三、既有链路一个字节没动
-- ============================================================================
-- CdssService 的三类规则（DDI/疗程上限/年龄限制）与 CdssController 的 4 个端点
-- 逐字未改，错误码 4015/4017/4650 原样。本版全部是新表新端点，
-- 挂在 /api/cdss/duplicate 下，与 /api/cdss 既有 4 个端点无路径冲突、无调用关系。
-- cdss_alert 也不写——那是既有 DDI/疗程提醒的留痕表，混进重复用药会污染既有报表口径。
-- ============================================================================


-- ============================================================
-- 1. md_drug：通用名与药理类别（只加列，不填值）
-- ============================================================
alter table md_drug add column if not exists generic_name        varchar(128);
alter table md_drug add column if not exists pharm_category_code varchar(32);

comment on column md_drug.generic_name is
    'v51 通用名（药品本位名）。null = 未维护，服务层判为不可比较，不是「无同名药」。'
    '由药剂科按院内用药目录逐条维护；严禁按 name 前缀/相似度自动生成——'
    '「阿莫西林」与「阿莫西林克拉维酸钾」前缀相同但不是同一个药。比较用精确相等。';

comment on column md_drug.pharm_category_code is
    'v51 药理类别码 → cdss_pharm_category.code。null = 未维护。'
    '注意 drug_class(W/C) 是西药/中成药费用分类、abx_level 是抗菌药分级管理，二者都不是药理分类。';


-- ============================================================
-- 2. 药理类别字典：空表 + 一条占位示例
-- ============================================================
create table cdss_pharm_category (
    code       varchar(32)  primary key,
    name       varchar(128) not null,
    remark     varchar(255),
    created_at timestamptz  not null default now()
);

comment on table cdss_pharm_category is
    'v51 院内药理类别字典。**本表内容属药学知识，不由本平台预置**——'
    '仅给一条占位示例，上线前须由药剂科按院内用药目录替换。';

alter table md_drug
    add constraint fk_drug_pharm_category
    foreign key (pharm_category_code) references cdss_pharm_category (code);
-- 外键此刻必然满足：上面两列全为 null，本迁移不回填。

-- 占位示例行：不与任何药品关联，仅示意字典形态。
insert into cdss_pharm_category (code, name, remark) values
    ('EXAMPLE', '示例药理类别（占位）',
     '示例，上线前须由药剂科按院内用药目录替换或删除；本行不与任何药品关联，删除不影响任何功能')
on conflict (code) do nothing;


-- ============================================================
-- 3. 三态 gate 与回溯窗口
-- ============================================================
-- gate 默认 warn：重复用药此前从无此校验，直接 block 会让存量开单大面积失败。
-- 但 warn 不等于静默——服务层 warn 档必须把 warnings 回给医生并落台账（见第 4 节）。
-- 坏配置回落 warn 而非 off：宁可多提示，不可让一个笔误静默关掉校验。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('cdss.gate.duplicate', 'warn',
     '重复用药 gate：off 旁路 / warn 照常开单但回带 warnings 并落台账（默认）/ block 同次就诊重复拦截（5620/5621）。'
     '注意：block 只升级「同一次就诊」的完全相同(5620)与同通用名(5621)；同药理类别与跨处方一律只提示不拦'),
    ('cdss.duplicate.lookback.days', '30',
     '跨处方重复用药回溯天数（1–180，越界或非法回落 30）。这是**检索**窗口，'
     '不是判定依据——是否重复由上次处方的疗程剩余量决定，见 V153 迁移注释第 5 节')
on conflict (cfg_key) do nothing;


-- ============================================================
-- 4. 重复用药台账：warn 档放行不等于没发生过
-- ============================================================
-- v48 病理双签、v50 发药核对同一条教训：warn 档「放行并提示」若不落痕，
-- 院方永远拿不到「一个月里有多少次重复开单被放行」这个数，
-- 也就永远没有依据把 gate 收紧到 block。
create table cdss_duplicate_alert (
    id                    bigserial    primary key,
    -- not null 的台账行没法 set null，用 cascade：挂号没了，针对它的审查记录一并清掉，
    -- 留一条指向不存在挂号的孤儿行没有意义。
    registration_id       bigint       not null references outp_registration (id) on delete cascade,
    patient_id            bigint       not null references empi_patient (id) on delete cascade,
    gate                  varchar(8)   not null,          -- 评估当时的 gate 值
    scope                 varchar(16)  not null,          -- IN_VISIT 同次就诊 / CROSS_VISIT 跨处方
    match_level           varchar(16)  not null,          -- SAME_DRUG / SAME_GENERIC / SAME_CATEGORY
    severity              varchar(8)   not null,          -- BLOCK 可拦 / WARN 提示 / INFO 存疑
    new_drug_id           bigint       not null references md_drug (id),
    new_drug_name         varchar(128) not null,
    prior_drug_id         bigint       not null references md_drug (id),
    prior_drug_name       varchar(128) not null,
    prior_registration_id bigint       references outp_registration (id) on delete set null,  -- IN_VISIT 时为空
    prior_visit_date      date,
    prior_days            int,          -- 上次处方疗程天数；null = 开单时未填
    remaining_days        int,          -- 上次处方按疗程推算的剩余天数；null = 无法推算
    blocked               boolean      not null,          -- 本条是否实际拦下了本次开单
    message               varchar(500) not null,
    created_at            timestamptz  not null
);

create index idx_cdss_dup_alert_reg     on cdss_duplicate_alert (registration_id);
create index idx_cdss_dup_alert_created on cdss_duplicate_alert (created_at desc);

comment on table cdss_duplicate_alert is
    'v51 重复用药提醒台账。**已知缺口**：block 档拦下的那一次由业务异常回滚，本表看不到——'
    '要统计「拦截次数」须用 REQUIRES_NEW 独立事务，而独立事务会在 @Transactional 单测里真提交、'
    '污染既有用例，须配套改测试基座，本版不做。warn 档的放行记录是完整可信的，'
    '而 warn→block 的决策依据恰恰来自 warn 档数据。';

comment on column cdss_duplicate_alert.remaining_days is
    'v51 = 上次挂号 visit_date + 上次处方 days - 今天。>0 表示上次的药还没吃完；'
    '<=0 的正常续方**根本不入库**（长期用药复诊续方是正常医疗行为，不是重复用药）；'
    'null 表示上次处方 days 未填，无法区分续方与重复，只出 INFO 不出 WARN。';


-- ============================================================
-- 5. 跨处方为什么只提示不拦（判定口径写死在这里，勿凭印象改）
-- ============================================================
-- 「同一次就诊开重了」与「上次的药还没吃完又开」是两件不同性质的事：
--   * 同次就诊重复 —— 一张处方上同一个药出现两次，几乎总是开单失误，可以拦。
--   * 跨处方重复   —— **长期用药复诊续方是正常医疗行为**。高血压患者每月来开一次
--     同一种降压药，若一律报重复，这条规则一个月内就会被医生完全无视。
--
-- 因此跨处方不按「窗口内开过就报」判定，而是按**上次处方的药有没有吃完**判定：
--     用完日 = 上次挂号 visit_date + 上次医嘱 days
--     * 今天 >= 用完日 → 药已吃完，本次是正常续方，**一条提示都不出**
--     * 今天 <  用完日 → 上次的药还剩 N 天，本次再开可能重复，出 WARN（不拦）
--     * days 为空       → 疗程天数未填，**不猜**，出 INFO 并明说无法区分续方与重复
-- lookback 天数只决定「往前翻多少天的处方」，不决定判没判重。把窗口从 30 改成 90
-- 只会多翻出更早的处方，不会把已吃完的续方误报成重复。
--
-- 同药理类别（第 ③ 层）即使在同次就诊也**不拦**：同类药联用在临床上常常是有意为之
-- （两种不同机理的降压药联合达标是指南推荐方案），把它做成硬拦截会拦掉正确处方。
