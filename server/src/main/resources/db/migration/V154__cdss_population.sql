-- v51 车道 C：CDSS 特殊人群用药规则（妊娠 / 哺乳 / 肝功能不全 / 肾功能不全）。
-- 错误码子段 5640–5659。gate：cdss.gate.population，三态，默认 warn。
--
-- ======================================================================
-- 〇、先说数据源核实结果——本车道最重要的产出是这一段，不是这几张表
-- ======================================================================
--
-- ① 妊娠 / 哺乳状态：**全仓无任何数据源**（实测，不是推测）。
--    `empi_patient`（V2）列全集：patient_no / name / sex / birth_date / id_type / id_no /
--    phone / address / insurance_type / blood_type / allergy_history / active / 时间戳，
--    **无孕产字段**；`outp_registration`（V3）与 `inp_admission`（V8）同样没有。
--    全仓 migration 与 java 对 `妊娠|孕|怀孕|pregnan|gravid|lactat|哺乳` 的检索为**零命中**。
--    所以本迁移必须**新建采集表** `cdss_population_status`，且状态只能**人工申报**。
--
--    **不许按性别 + 年龄猜「育龄女性即可能妊娠」**。那样做的后果不是"稍微吵一点"：
--    门诊育龄女性占比极高，每张处方都弹一次妊娠警告，医生三天之内就会把整个 CDSS
--    的提示条一起无视掉——**连真正该看的那条也一起无视**。用假阳性换覆盖率，
--    换来的是把已有的真阳性也一并作废，净效果为负。
--
--    因此本表的第三态是**「未采集」而不是「非妊娠」**：查不到有效申报时，
--    妊娠/哺乳两类规则**整类不评估**，并在返回体里明说"本患者妊娠状态未采集、
--    该维度本次未评估"。既不假装安全，也不制造噪音。
--
-- ② 肝肾功能：**有真实数据源，但只覆盖门诊**（实测）。
--    可达路径：`outp_lab_result`（V10）--order_id--> `outp_order`（V5）
--             --registration_id--> `outp_registration`（V3）--patient_id--> `empi_patient`。
--    写入方是 `LabResultListener`（LIS 回传落库）。
--    **住院侧无检验结果表**：`outp_lab_result.order_id` 外键指向 `outp_order`，
--    `inp_order`（V8）的检验结果全仓**没有任何落库表**——住院患者的肝肾功能规则
--    取不到数，这是如实标注的缺口，不是本版能补的。
--
-- ③ **eGFR 一律不自算**。CKD-EPI / MDRD 有多个版本（含/不含种族系数、2021 去种族版），
--    系数记错不会报错，只会**静默算出一个偏高的 eGFR**，把该减量的患者判成肾功能正常。
--    本引擎因此不含任何公式：由药剂科在规则行里配 `lab_item_code + comparator + threshold`，
--    LIS 若直接回报 eGFR 就比 eGFR，不回报就比药剂科配的肌酐阈值。
--    引擎只负责"取最近一次数值型结果、和配置的阈值比大小"，**不做任何医学换算**。
--
-- ④ 年龄维度**不碰**：既有 `cdss_age_rule` 与错误码 4017 已覆盖，本版一个字节不动。
--
-- ======================================================================
-- 一、本迁移的硬边界：不内置任何来源不明的药学知识
-- ======================================================================
-- 「哪个药孕妇禁用」「肌酐高到多少要减量」属**药学知识**，由药剂科按院内用药目录维护。
-- 本迁移只建**规则引擎的数据结构**，种子里只有**一条 enabled=false 的示例行**，
-- 且示例行的 drug_keyword / lab_item_code 都是占位文字、threshold 取永不成立的值——
-- 三重保险，保证它在被药剂科替换前**不可能命中任何真实药品**。
--
-- 编出来的规则看起来非常专业，医生会信，然后出事。宁可空表。
--
-- ======================================================================
-- 二、零回填
-- ======================================================================
-- 本迁移**不含任何 update**。历史患者没有妊娠状态申报——那就是事实。
-- 最诱人的歧路是"扫 allergy_history 或病历文本，把'孕 12 周'之类抓出来回填"，
-- 那正是车道 A 已经论证过的反向拦截风险（「青霉素过敏」vs「青霉素皮试阴性」）在
-- 本车道的等价物：「否认妊娠」与「妊娠」文本高度相近而语义相反。**不做。**

-- ======================================================================
-- 表一：妊娠 / 哺乳状态申报台账（本系统内唯一的妊娠状态数据源）
-- ======================================================================
create table cdss_population_status (
    id            bigserial primary key,
    patient_id    bigint      not null references empi_patient (id) on delete cascade,
    -- PREGNANT 妊娠 / LACTATING 哺乳 / NEITHER 均否。
    -- NEITHER 必须能记：否则无法区分「问过了、不是」与「根本没问」，
    -- 而这两者在提示策略上完全不同（前者静默放行，后者要提示"该维度未评估"）。
    status        varchar(16) not null,
    -- 来源分级。医生看到警告时要能判断这条状态可不可信：
    -- SELF_REPORT 患者自述 / CLINICIAN_CONFIRMED 医师确认 / LAB_CONFIRMED 检验证实。
    source        varchar(24) not null,
    asserted_by   bigint      references sys_user (id),
    asserted_at   timestamptz not null,
    -- 失效日（预产期 / 预计哺乳结束日）。PREGNANT / LACTATING **必填**。
    -- 没有失效日的妊娠标记会在产后继续拦截该患者的所有处方，且没人会想起来去清它——
    -- 这是"一次录入、永久误拦"，比不记更糟。过期即回落「未采集」。
    valid_until   date,
    note          varchar(255),
    -- 录错了走软撤销：不删行、不改语义列，历史留痕原样保留。
    revoked_at    timestamptz,
    revoked_by    bigint      references sys_user (id),
    revoke_reason varchar(255),
    constraint ck_cdss_pop_status_value  check (status in ('PREGNANT', 'LACTATING', 'NEITHER')),
    constraint ck_cdss_pop_status_source check (source in ('SELF_REPORT', 'CLINICIAN_CONFIRMED', 'LAB_CONFIRMED')),
    constraint ck_cdss_pop_status_until  check (status = 'NEITHER' or valid_until is not null)
);

comment on table cdss_population_status is
    'v51：妊娠/哺乳状态人工申报台账。全仓此前无此数据源，且**禁止按性别+年龄推定**。追加写，最新有效行胜出。';
comment on column cdss_population_status.status is
    'PREGNANT/LACTATING/NEITHER。查不到有效行 = 未采集（UNKNOWN），与 NEITHER 语义不同：未采集要提示"该维度未评估"，NEITHER 静默放行。';
comment on column cdss_population_status.valid_until is
    'PREGNANT/LACTATING 必填（ck_cdss_pop_status_until）。过期即回落未采集——无失效日的妊娠标记会在产后永久误拦。';
comment on column cdss_population_status.revoked_at is
    '软撤销（录错）。撤销后该行不再参与判定，但不删不改，留痕可查。';

create index idx_cdss_pop_status_patient on cdss_population_status (patient_id, id desc);

-- ======================================================================
-- 表二：特殊人群用药规则（药剂科维护，内容不由本迁移提供）
-- ======================================================================
create table cdss_population_rule (
    id            bigserial primary key,
    -- 药品名关键字，contains 匹配。与既有 cdss_ddi_rule / cdss_dose_rule / cdss_age_rule
    -- **同口径**，不另发明匹配语义（药剂科维护三张表时心智一致）。
    drug_keyword  varchar(128) not null,
    -- PREGNANCY 妊娠 / LACTATION 哺乳 / HEPATIC 肝功能不全 / RENAL 肾功能不全
    population    varchar(16)  not null,
    severity      varchar(8)   not null,          -- FORBID / CAUTION
    -- ---- 肝肾功能判定条件：由药剂科配，引擎只做数值比较 ----
    -- 本院 LIS 的项目代码（如肌酐/eGFR/转氨酶各院代码不同，故不预置）
    lab_item_code varchar(64),
    comparator    varchar(4),                     -- LT / LTE / GT / GTE
    threshold     numeric(12,3),
    -- 阈值所用单位。**必填**：肌酐 mg/dL 与 μmol/L 相差约 88 倍，
    -- 拿 2.4 mg/dL 的结果去比 177 μmol/L 的阈值会**静默判成正常**。
    -- 引擎发现结果单位与本列不一致时**跳过该规则并提示**，绝不做换算。
    lab_unit      varchar(32),
    -- 检验结果最长可用天数。三年前的肌酐不能代表今天的肾功能；超期视为无数据，不外推。
    lab_max_age_days integer   not null default 90,
    -- ---- 依据：命中提示必须能回答"凭什么" ----
    -- 妊娠用药警告的假阳性代价极高（不必要的恐慌与自行停药）。只说"孕妇慎用"
    -- 而不给出处与级别，医生无从判断该不该采纳，最终结果是整类提示被无视。
    -- 两列 not null 且非空白（ck_cdss_pop_rule_basis），**没有依据的规则不许入库**。
    basis_source  varchar(255) not null,          -- 出处：说明书版本 / 院内用药目录 / 指南名称+年份
    basis_level   varchar(64)  not null,          -- 级别：按本院药剂科口径填（禁用/慎用/证据等级…）
    message       varchar(512) not null,
    enabled       boolean      not null default true,
    created_by    bigint       references sys_user (id),
    created_at    timestamptz  not null,
    constraint ck_cdss_pop_rule_population check (population in ('PREGNANCY', 'LACTATION', 'HEPATIC', 'RENAL')),
    constraint ck_cdss_pop_rule_severity   check (severity in ('FORBID', 'CAUTION')),
    constraint ck_cdss_pop_rule_comparator check (comparator is null or comparator in ('LT', 'LTE', 'GT', 'GTE')),
    constraint ck_cdss_pop_rule_maxage     check (lab_max_age_days > 0),
    constraint ck_cdss_pop_rule_basis      check (length(btrim(basis_source)) > 0 and length(btrim(basis_level)) > 0),
    constraint ck_cdss_pop_rule_keyword    check (length(btrim(drug_keyword)) > 0 and length(btrim(message)) > 0),
    -- 妊娠/哺乳规则不得带检验条件；肝肾规则四列必须齐全。
    -- 缺一列就等于"条件写了一半"，引擎要么误判要么静默跳过——不如让它插不进来。
    constraint ck_cdss_pop_rule_labcond check (
        (population in ('PREGNANCY', 'LACTATION')
            and lab_item_code is null and comparator is null and threshold is null and lab_unit is null)
        or
        (population in ('HEPATIC', 'RENAL')
            and lab_item_code is not null and comparator is not null
            and threshold is not null and lab_unit is not null
            and length(btrim(lab_item_code)) > 0 and length(btrim(lab_unit)) > 0)
    )
);

comment on table cdss_population_rule is
    'v51：特殊人群用药规则表。**规则内容属药学知识，由药剂科按院内用药目录维护**，本迁移只提供结构与一条 enabled=false 的示例行。';
comment on column cdss_population_rule.lab_unit is
    '阈值单位，必填且不做换算。结果单位与本列不一致时引擎跳过该规则并回带提示——肌酐 mg/dL 与 μmol/L 差约 88 倍，静默比较会把该减量的患者判成正常。';
comment on column cdss_population_rule.basis_source is
    '依据出处，not null 且非空白。没有依据的规则不许入库：只说"孕妇慎用"而不给出处，医生无从判断，整类提示会被无视。';
comment on column cdss_population_rule.lab_max_age_days is
    '检验结果最长可用天数。超期视为无数据（回带"未评估"提示），绝不外推陈旧结果。';

create index idx_cdss_pop_rule_pop on cdss_population_rule (population) where enabled;

-- ======================================================================
-- 表三：命中留痕（依据快照，不 join 现值）
-- ======================================================================
create table cdss_population_alert (
    id              bigserial primary key,
    registration_id bigint       references outp_registration (id) on delete set null,   -- 可空：允许无挂号上下文的预检
    patient_id      bigint       not null references empi_patient (id) on delete cascade,
    rule_id         bigint       not null references cdss_population_rule (id),
    drug_name       varchar(128) not null,
    population      varchar(16)  not null,
    severity        varchar(8)   not null,
    gate            varchar(8)   not null,   -- 当次生效档位 off/warn/block
    blocked         boolean      not null,   -- 是否真的拦下了（warn 档恒为 false）
    -- 依据**快照**：规则可被药剂科随时改，事后 join 现值会显示成"医生当时看到的是新版本"。
    -- 医疗留痕要能还原当时屏幕上的那句话，所以在这里存副本而不是外键取值。
    basis_source    varchar(255) not null,
    basis_level     varchar(64)  not null,
    message         varchar(512) not null,
    -- 命中证据：妊娠类记状态来源与申报时刻；肝肾类记检验项目、数值、单位、报告时刻。
    evidence        varchar(512),
    occurred_at     timestamptz  not null
);

comment on table cdss_population_alert is
    'v51：特殊人群规则命中留痕。basis_* 与 message 存**快照**而非外键取值——规则被改后仍要能还原医生当时看到的原话。';
comment on column cdss_population_alert.blocked is
    'gate=block 且 severity=FORBID 时才为 true。warn 档恒 false：记了一行、也把话说给了医生，但没拦。';

create index idx_cdss_pop_alert_patient on cdss_population_alert (patient_id, id desc);
create index idx_cdss_pop_alert_time on cdss_population_alert (occurred_at desc);

-- ----------------------------------------------------------------------
-- 既有表 outp_lab_result 上补一个索引（**纯增量：只加索引，不加列、不改数据、不改语义**）
-- ----------------------------------------------------------------------
-- 肝肾功能规则每次开单都要按「患者 + 项目代码 + 时间倒序」取最近一次结果。
-- 该表自 V10 起只有 idx_lab_result_order(order_id) 一个索引，按项目代码检索是全表扫。
-- 不加这个索引，本车道等于给每次开药引入一次 outp_lab_result 全表扫——
-- 那是我自己引进去的生产问题，必须在同一版里解决，不能留给运维。
--
-- **必须是表达式索引，不能是 (item_code, created_at)。** 引擎侧的谓词是
-- `upper(btrim(lr.item_code)) = upper(btrim(?))`（LIS 回传的项目代码大小写/前后空格不稳，
-- 直接等值比会漏掉本该命中的结果，这个归一化不能去掉）。列上加普通索引时，
-- 列被函数包住即**不可 sarg**，规划器照样全表扫——那就成了"建了索引、以为解决了、
-- 其实一次没用上"：比不建更糟，因为没人会再回来看。表达式必须与查询逐字一致。
create index idx_outp_lab_result_item
    on outp_lab_result (upper(btrim(item_code)), created_at desc);

-- ======================================================================
-- 四、gate 与配置（三态，默认 warn）
-- ======================================================================
-- 默认 warn 而非 block：此前系统**从无特殊人群校验**，直接 block 会让存量处方大面积失败。
-- 但 warn 档必须**真的把提示给到医生**——本车道的 warn 实现是"返回体带 warnings + 留痕落库"，
-- 不是静默放行。坏配置回落 warn 而非 off：把 'blocked'/'true'/'1' 之类笔误当成 off，
-- 等于让一个拼写错误静默关掉临床校验。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('cdss.gate.population', 'warn',
     'CDSS 特殊人群（妊娠/哺乳/肝肾功能）用药 gate：off 旁路 / warn 照常开单但返回体回带 warnings 并留痕（默认）/ block 命中 FORBID 拒绝开单（5640–5643）。坏配置回落 warn。'),
    ('cdss.population.status.stale_days', '180',
     '妊娠/哺乳状态申报的陈旧阈值（天）。超过即仍按该状态判定，但回带一条"申报于 X、距今 N 天，请核对"的提示——三年前的「否认妊娠」永久静默压制警告，是本仓反复吃过亏的静默失败形态。');

-- ======================================================================
-- 五、种子：一条示例行，enabled=false，三重保险保证不可能命中真实药品
-- ======================================================================
-- ① drug_keyword 是占位文字，不会 contains 命中任何真实药品名；
-- ② lab_item_code 是占位文字，本院 LIS 不会回报这个代码；
-- ③ threshold = 0 配 LT，检验数值不可能小于 0；
-- 再加 enabled = false。上线前须由药剂科整行替换，不是"改改就能用"。
insert into cdss_population_rule
    (drug_keyword, population, severity, lab_item_code, comparator, threshold, lab_unit,
     lab_max_age_days, basis_source, basis_level, message, enabled, created_at)
values
    ('__示例药品关键字__上线前须由药剂科替换__', 'RENAL', 'CAUTION',
     '__示例检验项目代码__', 'LT', 0, '__示例单位__', 90,
     '示例，非真实依据。上线前须由药剂科按本院用药目录/药品说明书逐条替换',
     '示例',
     '示例规则，不构成任何用药建议。本行 enabled=false 且条件永不成立，仅用于展示字段填法。',
     false, now());
