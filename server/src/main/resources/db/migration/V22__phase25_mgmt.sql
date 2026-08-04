-- 二十五期：管理与院感补缺（护理排班/质控评分、医务台账、抗菌分级管控、消毒供应追溯、预防保健、评审指标）

-- 护理排班
create table nur_shift (
    id         bigserial primary key,
    dept_id    bigint      not null references sys_dept (id),
    nurse_name varchar(64) not null,
    shift_date date        not null,
    shift_type varchar(8)  not null,                          -- DAY/NIGHT/MID
    created_at timestamptz not null default now(),
    constraint uq_nur_shift unique (nurse_name, shift_date, shift_type)
);

-- 护理质控评分
create table nur_qc_score (
    id         bigserial primary key,
    dept_id    bigint       not null references sys_dept (id),
    item       varchar(128) not null,                         -- 检查项目（基础护理/消毒隔离/急救物品…）
    score      numeric(5,1) not null,
    checker    varchar(64),
    check_date date         not null default current_date,
    note       varchar(500),
    constraint chk_nur_score check (score between 0 and 100)
);

-- 医务管理台账（医师定期考核 / 处方权 / 手术授权等资质）
create table med_staff_credential (
    id          bigserial primary key,
    staff_name  varchar(64)  not null,
    cert_type   varchar(32)  not null,                        -- 执业证/定期考核/处方权/手术授权/麻醉处方权
    cert_no     varchar(64),
    issued_at   date,
    expire_date date         not null,
    note        varchar(255),
    created_at  timestamptz  not null default now()
);

-- 抗菌药物分级：0 非管控 / 1 非限制 / 2 限制 / 3 特殊
alter table md_drug add column abx_level smallint not null default 0;
update md_drug set abx_level = 1 where antibiotic;
update md_drug set abx_level = 2 where name like '%头孢克肟%' or name like '%左氧氟沙星%';

-- 医师抗菌处方权（缺省 1 级：可开非限制级）
create table med_abx_privilege (
    user_id    bigint   primary key references sys_user (id),
    level      smallint not null default 1,
    granted_at timestamptz not null default now(),
    constraint chk_abx_level check (level between 1 and 3)
);

-- 消毒供应追溯（包-灭菌-发放-使用条码链）
create table cssd_package (
    id            bigserial primary key,
    pkg_no        varchar(32) not null unique,
    name          varchar(128) not null,
    status        varchar(16) not null default 'PACKED',      -- PACKED/STERILIZED/ISSUED/USED
    sterile_batch varchar(64),
    dest_dept_id  bigint references sys_dept (id),
    packed_at     timestamptz not null default now(),
    sterilized_at timestamptz,
    issued_at     timestamptz,
    used_at       timestamptz
);

create table cssd_trace_log (
    id       bigserial primary key,
    pkg_id   bigint      not null references cssd_package (id),
    action   varchar(16) not null,
    operator varchar(64),
    at       timestamptz not null default now()
);

-- 预防保健（疫苗接种 / 健康体检 / 健康宣教）
create table phc_record (
    id            bigserial primary key,
    patient_id    bigint      not null references empi_patient (id),
    record_type   varchar(16) not null,                       -- VACCINATION/PHYSICAL/EDUCATION
    content       varchar(500) not null,
    occurred_date date        not null default current_date,
    created_at    timestamptz not null default now()
);

-- 公立医院评审指标集扩充（在指标引擎上追加内置指标）
insert into dg_metric_def (code, name, unit, target, builtin_key) values
    ('M006', '平均住院日',       '天', 9.00,  'avg_los'),
    ('M007', '抗菌药处方占比',   '%',  20.00, 'abx_rx_ratio'),
    ('M008', '门诊病历签名率',   '%',  null,  'emr_sign_ratio'),
    ('M009', '检查报告审核率',   '%',  null,  'ris_verified_ratio');

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (48, 25, '管理与院感', 'MENU', '/mgmt', 'mgmt:center', 'Umbrella', 5);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (48);
