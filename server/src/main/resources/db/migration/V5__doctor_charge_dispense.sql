-- 门诊医生站（病历/诊断/医嘱）、收费、发药

create table outp_emr (
    id              bigserial primary key,
    registration_id bigint       not null unique references outp_registration (id),
    chief_complaint varchar(512),
    present_illness varchar(2000),
    past_history    varchar(1000),
    physical_exam   varchar(1000),
    advice          varchar(1000),
    doctor_id       bigint references sys_user (id),
    updated_at      timestamptz  not null default now()
);

create table outp_diagnosis (
    id              bigserial primary key,
    registration_id bigint       not null references outp_registration (id),
    icd_code        varchar(16)  not null,
    icd_name        varchar(128) not null,
    primary_diag    boolean      not null default false
);

create index idx_diagnosis_reg on outp_diagnosis (registration_id);

create table outp_charge (
    id              bigserial primary key,
    charge_no       varchar(32)   not null unique,
    registration_id bigint        not null references outp_registration (id),
    total_amount    numeric(12,2) not null,
    pay_method      varchar(16)   not null,
    status          varchar(16)   not null default 'PAID',
    cashier_id      bigint references sys_user (id),
    created_at      timestamptz   not null default now()
);

create table outp_order (
    id              bigserial primary key,
    registration_id bigint        not null references outp_registration (id),
    group_no        varchar(32)   not null,
    order_type      varchar(8)    not null,
    item_id         bigint        not null,
    item_code       varchar(32)   not null,
    item_name       varchar(128)  not null,
    spec            varchar(64),
    unit            varchar(8)    not null,
    qty             int           not null,
    unit_price      numeric(10,2) not null,
    amount          numeric(12,2) not null,
    usage_route     varchar(32),
    frequency       varchar(16),
    dose_per_time   varchar(32),
    days            int,
    status          varchar(16)   not null default 'CREATED',
    charge_id       bigint references outp_charge (id),
    doctor_id       bigint references sys_user (id),
    created_at      timestamptz   not null default now()
);

create index idx_order_reg on outp_order (registration_id);
create index idx_order_status on outp_order (status);

-- 菜单：医生站、收费、发药
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (10, 7, '门诊医生站', 'MENU', '/outpatient/doctor',   'outp:doctor',   'Stethoscope', 0),
    (11, 7, '门诊收费',   'MENU', '/outpatient/charge',   'outp:charge',   'Money', 3),
    (12, 7, '药房发药',   'MENU', '/outpatient/pharmacy', 'outp:dispense', 'MedicineBox', 4);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (10, 11, 12);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'DOCTOR_OUTP' and m.id in (7, 10);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'CASHIER' and m.id = 11;

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'PHARMACIST' and m.id in (7, 12);
