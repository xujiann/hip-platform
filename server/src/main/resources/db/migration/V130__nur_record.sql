-- v42 车道2：护理记录单 + 日常巡视 + 护理级别留痕（全部为新增隔离，零核心写路径改动）
--
-- 背景（诚信补齐）：技术偏离表 1268/1708/2427/2429/2431/2408/2480 已答「平台已实现」，
-- 而全仓不存在任何"按患者-时间记录护理观察与措施"的表；2074/1270/2075 日常巡视同理
-- （巡视只有门诊输液一条线）。本迁移补的是**法定病历组成部分**的落库结构。
--
-- 两条本迁移刻意立的规矩：
--   ① 巡视与护理观察**同表按 record_kind 区分**，不另开表——两者字段完全同构，
--      分表只会让打印/统计/签名三处逻辑各写两遍。
--   ② 人字段一律 **sys_user 外键 + signature/signed_at**，不再重蹈
--      nur_risk_assess.assessor / shift_handover.author / nur_qc_score.checker
--      三处 varchar(64) 存用户名的老路（改名即断链、无法做「非本人不可签」校验）。

-- ===== 护理记录单 / 日常巡视 =====
create table nur_record (
    id           bigserial     primary key,
    admission_id bigint        not null references inp_admission (id),
    record_time  timestamptz   not null default now(),   -- 护理时间（可倒填，夜班补录不吃 now()）
    record_kind  varchar(16)   not null,                 -- OBSERVE 护理观察 / ROUNDS 日常巡视 / MEASURE 护理措施
    observation  varchar(2000),                          -- 病情观察
    measure      varchar(2000),                          -- 护理措施
    effect       varchar(1000),                          -- 效果评价
    measure_code varchar(32),                            -- 可空措施码（实施期接院内护理措施字典，本仓不预置码值）
    nurse_id     bigint        references sys_user (id),
    signature    varchar(512),                           -- CA 签名值（SignatureAdapter，与病历同语义）
    signed_at    timestamptz,
    created_at   timestamptz   not null default now(),
    -- 新表零历史行，CHECK 不存在「脏类型挡住 Flyway」的风险（与 inp_medical_record.record_type 的处置不同）
    constraint chk_nur_record_kind check (record_kind in ('OBSERVE', 'ROUNDS', 'MEASURE'))
);
-- 主查询是「某次住院某时间窗的护理记录按时序」，打印与统计同用
create index idx_nur_record_adm_time on nur_record (admission_id, record_time);

-- ===== 护理级别变更留痕 =====
-- care_level 此前是 inp_admission 上被 update 直接覆盖的单列：无历史、无变更人、无原因，
-- 「按护理级别的护理天数分档」这类最基本的护理工作量统计根本做不出来。
create table nur_care_level_log (
    id             bigserial   primary key,
    admission_id   bigint      not null references inp_admission (id),
    level          varchar(8)  not null,                 -- 特级/一级/二级/三级
    effective_from timestamptz not null default now(),
    changed_by     bigint      references sys_user (id),
    reason         varchar(255),                         -- 兼容端点可空；带原因端点强制必填（4807）
    created_at     timestamptz not null default now()
);
create index idx_nur_care_level_log_adm on nur_care_level_log (admission_id, effective_from);

-- ===== 交接班双签 =====
-- shift_handover 此前只有 author（交班人），接班人是否真的接到手上无任何痕迹。
alter table shift_handover add column receiver_id bigint references sys_user (id);
alter table shift_handover add column received_at timestamptz;

-- ===== 归档时间与归档人 =====
-- 此前只有 archived 布尔位：谁在什么时候归的档不可追溯，终末质控（车道3）要读这两列。
-- **历史行必然为 null**：严禁用 discharged_at 回填——出院≠归档，回填等于伪造归档痕迹。
-- 任何基于本列的统计都必须显式排除 archived_at is null 的历史行并标注口径。
alter table inp_admission add column archived_at timestamptz;
alter table inp_admission add column archived_by bigint references sys_user (id);

-- ===== gate 开关（统一 emr.gate.<domain>.<point>，三态 off|warn|block）=====
-- 默认 **off 而非 warn**：护理记录是本版才落库的新数据，历史住院一条没有，
-- warn 会让每一个在院患者的出院提示都常亮一条无法自救的缺项，污染护士的操作提示。
-- 本版只由只读预检端点 /api/nursing/records/gate-check 消费，**不挂任何写路径挡点**。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('emr.gate.nursing.record', 'off', '护理记录缺失：off 旁路（默认，新数据未沉淀）/ warn 只读预检提示 / block 预留（v42 未挂写路径）');

-- ===== 护理文书菜单（住院业务 DIR=18，紧邻护理白板 sort 5）=====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (105, 18, '护理文书', 'MENU', '/inpatient/nursing-record', 'nur:record', 'EditPen', 6);
insert into sys_role_menu (role_id, menu_id)
select r.id, 105 from sys_role r where r.code in ('ADMIN', 'NURSE', 'QUALITY');

-- 序列纠偏（照 V128）：显式 id 建菜单不会推进序列，将来走 nextval 的自定义菜单必撞主键。
select setval('sys_menu_id_seq', (select max(id) from sys_menu));
