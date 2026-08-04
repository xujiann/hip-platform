-- 二十六期：数据治理完整化（数据元管理 / 术语管理 / 服务订阅 / 自定义报表引擎 / ODR）

-- 数据标准/数据元（对齐 WS/T 303 卫生信息数据元规范思路）
create table dg_data_element (
    id        bigserial primary key,
    code      varchar(32)  not null unique,      -- 数据元标识（如 DE02.01.039.00）
    name      varchar(128) not null,
    datatype  varchar(16)  not null,             -- S 字符/N 数值/D 日期/B 布尔/C 编码
    format    varchar(64),                       -- 表示格式（如 AN..18 / YYYYMMDD）
    value_set varchar(255),                      -- 允许值/值域说明
    std_ref   varchar(128),                      -- 引用标准（WS/T 303、WS 364…）
    remark    varchar(255),
    created_at timestamptz not null default now()
);

-- 术语标准化（本地术语 → 标准编码映射）
create table dg_term (
    id         bigserial primary key,
    category   varchar(32)  not null,            -- DIAG 诊断/DRUG 药品/LAB 检验/EXAM 检查/PROC 手术
    local_name varchar(128) not null,
    std_code   varchar(64)  not null,            -- ICD-10 / LOINC / ATC…
    std_name   varchar(128),
    std_system varchar(32)  not null,            -- ICD10/LOINC/ATC/ICD9CM3
    created_at timestamptz  not null default now(),
    constraint uq_dg_term unique (category, local_name, std_system)
);

-- 服务订阅（数据变更订阅推送）
create table dg_subscription (
    id         bigserial primary key,
    event_type varchar(32)  not null,            -- PATIENT_CREATED/LAB_PUBLISHED/DISCHARGE…
    subscriber varchar(64)  not null,
    target_url varchar(255) not null,
    enabled    boolean      not null default true,
    created_at timestamptz  not null default now()
);

create table dg_push_log (
    id              bigserial primary key,
    subscription_id bigint      not null references dg_subscription (id),
    payload         varchar(2000),
    result          varchar(16) not null,        -- SUCCESS/FAILED/MOCK
    pushed_at       timestamptz not null default now()
);

-- 自定义报表引擎（只读 SELECT，运行时白名单校验）
create table dg_report_def (
    id         bigserial primary key,
    name       varchar(128) not null,
    sql_text   text         not null,
    remark     varchar(255),
    created_at timestamptz  not null default now()
);

-- 内置报表样例
insert into dg_report_def (name, sql_text, remark) values
    ('科室门诊量统计', 'select d.name as 科室, count(*) as 门诊量 from outp_registration r join sys_dept d on d.id = r.dept_id where r.status <> ''CANCELLED'' group by d.name order by 门诊量 desc', '按科室汇总有效挂号'),
    ('在院患者一览',   'select a.admission_no as 住院号, p.name as 患者, d.name as 科室 from inp_admission a join empi_patient p on p.id = a.patient_id join sys_dept d on d.id = a.dept_id where a.status = ''IN_HOSPITAL''', '当前在院');

-- 数据元种子（示例：对齐国标常用项）
insert into dg_data_element (code, name, datatype, format, value_set, std_ref) values
    ('DE02.01.039.00', '本人姓名',     'S', 'A..50',    null,                 'WS 363.2'),
    ('DE02.01.040.00', '性别代码',     'C', 'N1',       'GB/T 2261.1（0-9）', 'WS 363.2'),
    ('DE02.01.005.01', '出生日期',     'D', 'YYYYMMDD', null,                 'WS 363.2'),
    ('DE08.10.052.00', '危急值标识',   'B', 'T/F',      '是/否',              'WS/T 303');

insert into dg_term (category, local_name, std_code, std_name, std_system) values
    ('DIAG', '急性上呼吸道感染', 'J06.900', '急性上呼吸道感染', 'ICD10'),
    ('LAB',  '血红蛋白',         '718-7',   'Hemoglobin',        'LOINC'),
    ('DRUG', '阿莫西林胶囊',     'J01CA04', 'Amoxicillin',       'ATC');

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (49, 25, '数据标准与报表', 'MENU', '/datagov/standards', 'dg:std', 'Collection', 6);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (49);
