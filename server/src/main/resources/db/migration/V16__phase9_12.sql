-- 九至十二期：护理质控 / 患者管理 / HRP 扩展 / 数据中台

-- ===== 九期：护理与质控 =====
alter table inp_admission add column care_level varchar(8) not null default '二级';
alter table inp_admission add column archived boolean not null default false;

create table qc_adverse_event (
    id          bigserial primary key,
    type        varchar(32)  not null,
    level       int          not null,
    occurred_on date         not null,
    dept_id     bigint references sys_dept (id),
    description varchar(1000) not null,
    anonymous   boolean      not null default false,
    reporter_id bigint references sys_user (id),
    status      varchar(16)  not null default 'NEW',
    handle_note varchar(500),
    handler_id  bigint references sys_user (id),
    created_at  timestamptz  not null default now(),
    constraint chk_ae_level check (level between 1 and 4)
);

create table qc_infection_case (
    id           bigserial primary key,
    admission_id bigint      not null references inp_admission (id),
    site         varchar(64) not null,
    pathogen     varchar(64),
    confirmed_on date        not null,
    note         varchar(500),
    created_at   timestamptz not null default now()
);

-- ===== 十期：患者管理 =====
create table pc_followup (
    id          bigserial primary key,
    patient_id  bigint       not null references empi_patient (id),
    topic       varchar(128) not null,
    channel     varchar(16)  not null default 'PHONE',
    due_date    date         not null,
    status      varchar(16)  not null default 'PENDING',
    result_note varchar(500),
    creator_id  bigint references sys_user (id),
    done_at     timestamptz,
    created_at  timestamptz  not null default now()
);

create table pc_satisfaction (
    id         bigserial primary key,
    patient_id bigint references empi_patient (id),
    source     varchar(16) not null default 'OUTP',
    score      int         not null,
    comment    varchar(500),
    created_at timestamptz not null default now(),
    constraint chk_sat_score check (score between 1 and 5)
);

create table inp_consult (
    id             bigserial primary key,
    admission_id   bigint       not null references inp_admission (id),
    from_doctor_id bigint references sys_user (id),
    to_dept_id     bigint       not null references sys_dept (id),
    question       varchar(500) not null,
    status         varchar(16)  not null default 'REQUESTED',
    opinion        varchar(1000),
    consultant_id  bigint references sys_user (id),
    created_at     timestamptz  not null default now(),
    done_at        timestamptz
);

create table cp_pathway_template (
    id          bigserial primary key,
    name        varchar(128)  not null,
    icd_prefix  varchar(8)    not null,
    description varchar(255),
    items       varchar(4000) not null
);

create table pe_exam_package (
    id      bigserial primary key,
    name    varchar(128)  not null,
    price   numeric(10,2) not null,
    items   varchar(2000) not null,
    enabled boolean       not null default true
);

create table pe_exam_record (
    id         bigserial primary key,
    patient_id bigint      not null references empi_patient (id),
    package_id bigint      not null references pe_exam_package (id),
    status     varchar(16) not null default 'REGISTERED',
    summary    varchar(2000),
    created_at timestamptz not null default now()
);

-- ===== 十一期：HRP 扩展 =====
create table hrp_material (
    id       bigserial primary key,
    code     varchar(32)   not null unique,
    name     varchar(128)  not null,
    category varchar(24)   not null,
    unit     varchar(8)    not null,
    price    numeric(10,2) not null,
    stock    int           not null default 0,
    enabled  boolean       not null default true
);

create table hrp_material_txn (
    id          bigserial primary key,
    material_id bigint      not null references hrp_material (id),
    type        varchar(8)  not null,
    qty         int         not null,
    stock_after int         not null,
    ref_no      varchar(64),
    operator_id bigint references sys_user (id),
    created_at  timestamptz not null default now()
);

create table hrp_supplier (
    id      bigserial primary key,
    name    varchar(128) not null,
    contact varchar(64),
    phone   varchar(32),
    scope   varchar(255),
    enabled boolean      not null default true
);

create table oa_request (
    id           bigserial primary key,
    type         varchar(16)  not null,
    title        varchar(128) not null,
    content      varchar(1000) not null,
    applicant_id bigint references sys_user (id),
    status       varchar(16)  not null default 'PENDING',
    approver_id  bigint references sys_user (id),
    approve_note varchar(255),
    created_at   timestamptz  not null default now(),
    decided_at   timestamptz
);

-- ===== 十二期：数据中台 =====
create table dg_metric_def (
    code        varchar(32)  primary key,
    name        varchar(64)  not null,
    unit        varchar(16),
    target      numeric(12,2),
    builtin_key varchar(32)  not null
);

create table dg_metric_snapshot (
    id        bigserial primary key,
    code      varchar(32)   not null references dg_metric_def (code),
    value     numeric(14,2) not null,
    snap_date date          not null,
    created_at timestamptz  not null default now(),
    unique (code, snap_date)
);

create table dg_report_task (
    id         bigserial primary key,
    title      varchar(128)  not null,
    due_date   date          not null,
    fields     varchar(2000) not null,
    status     varchar(16)   not null default 'OPEN',
    created_at timestamptz   not null default now()
);

create table dg_report_submission (
    id           bigserial primary key,
    task_id      bigint        not null references dg_report_task (id),
    dept_id      bigint references sys_dept (id),
    content      varchar(4000) not null,
    submitter_id bigint references sys_user (id),
    status       varchar(16)   not null default 'SUBMITTED',
    review_note  varchar(255),
    created_at   timestamptz   not null default now()
);

insert into dg_metric_def (code, name, unit, target, builtin_key) values
    ('M001', '当日门诊人次', '人次', null, 'outp_reg_today'),
    ('M002', '药占比',       '%',   30.00, 'drug_ratio'),
    ('M003', '床位使用率',   '%',   85.00, 'bed_occupancy'),
    ('M004', '门诊均次费用', '元',  null,  'avg_outp_cost'),
    ('M005', '在院患者数',   '人',  null,  'in_hospital');

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (32, 18, '护理白板', 'MENU', '/inpatient/board',  'inp:board',   'Monitor', 5),
    (33, 25, '质控中心', 'MENU', '/quality',          'qc:center',   'Warning', 2),
    (34, 5,  '患者管理', 'MENU', '/patientcare',      'pc:center',   'Service', 2),
    (35, 29, '物资与OA', 'MENU', '/hrp/ops',          'hrp:ops',     'Box', 2),
    (36, 25, '数据治理', 'MENU', '/datagov',          'dg:center',   'DataLine', 3);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (32, 33, 34, 35, 36);
