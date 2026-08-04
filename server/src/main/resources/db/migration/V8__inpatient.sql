-- 住院线：病区床位、入院、押金、住院医嘱、出院结算

create table inp_bed (
    id           bigserial primary key,
    ward_id      bigint      not null references sys_dept (id),
    bed_no       varchar(16) not null,
    status       varchar(16) not null default 'FREE',
    admission_id bigint,
    unique (ward_id, bed_no)
);

create table inp_admission (
    id              bigserial primary key,
    admission_no    varchar(32) not null unique,
    patient_id      bigint      not null references empi_patient (id),
    dept_id         bigint      not null references sys_dept (id),
    ward_id         bigint      not null references sys_dept (id),
    bed_id          bigint      not null references inp_bed (id),
    doctor_id       bigint references sys_user (id),
    admit_diag_icd  varchar(16),
    admit_diag_name varchar(128),
    status          varchar(16) not null default 'IN_HOSPITAL',
    admit_at        timestamptz not null default now(),
    discharged_at   timestamptz
);

create table inp_deposit (
    id           bigserial primary key,
    admission_id bigint        not null references inp_admission (id),
    amount       numeric(12,2) not null,
    pay_method   varchar(16)   not null,
    operator_id  bigint references sys_user (id),
    created_at   timestamptz   not null default now()
);

create table inp_order (
    id            bigserial primary key,
    admission_id  bigint        not null references inp_admission (id),
    group_no      varchar(32)   not null,
    order_type    varchar(8)    not null,
    item_id       bigint        not null,
    item_code     varchar(32)   not null,
    item_name     varchar(128)  not null,
    spec          varchar(64),
    unit          varchar(8)    not null,
    qty           int           not null,
    unit_price    numeric(10,2) not null,
    amount        numeric(12,2) not null,
    usage_route   varchar(32),
    frequency     varchar(16),
    dose_per_time varchar(32),
    status        varchar(16)   not null default 'CREATED',
    doctor_id     bigint references sys_user (id),
    executor_id   bigint references sys_user (id),
    executed_at   timestamptz,
    created_at    timestamptz   not null default now()
);

create index idx_inp_order_adm on inp_order (admission_id);
create index idx_inp_order_status on inp_order (status);

create table inp_settlement (
    id             bigserial primary key,
    settle_no      varchar(32)   not null unique,
    admission_id   bigint        not null unique references inp_admission (id),
    total_amount   numeric(12,2) not null,
    deposit_amount numeric(12,2) not null,
    balance        numeric(12,2) not null,
    cashier_id     bigint references sys_user (id),
    created_at     timestamptz   not null default now()
);

-- 种子：病区与床位
insert into sys_dept (parent_id, name, code, type, sort_no) values
    (null, '内科病区', 'W_IM',   'NURSING', 11),
    (null, '外科病区', 'W_SURG', 'NURSING', 12);

insert into inp_bed (ward_id, bed_no)
select d.id, b.bed_no
from sys_dept d
cross join (values ('01'), ('02'), ('03'), ('04'), ('05')) as b(bed_no)
where d.code in ('W_IM', 'W_SURG');

-- 菜单：住院业务
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (18, null, '住院业务', 'DIR',  '/inpatient',           null,            'House', 30),
    (19, 18,   '住院登记', 'MENU', '/inpatient/admission', 'inp:admission', 'Notebook', 1),
    (20, 18,   '住院医生站', 'MENU', '/inpatient/doctor',  'inp:doctor',    'Stethoscope', 2),
    (21, 18,   '护士执行', 'MENU', '/inpatient/nurse',     'inp:nurse',     'Checked', 3),
    (22, 18,   '出院结算', 'MENU', '/inpatient/discharge', 'inp:discharge', 'Wallet', 4);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (18, 19, 20, 21, 22);
