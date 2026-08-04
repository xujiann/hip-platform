-- 门诊挂号：排班（号源）与挂号记录

create table outp_schedule (
    id            bigserial primary key,
    dept_id       bigint        not null references sys_dept (id),
    doctor_id     bigint        references sys_user (id),
    schedule_date date          not null,
    shift         varchar(4)    not null default 'FULL',
    reg_type      varchar(16)   not null default 'GENERAL',
    fee           numeric(10,2) not null default 0,
    capacity      int           not null,
    booked        int           not null default 0,
    enabled       boolean       not null default true,
    created_at    timestamptz   not null default now(),
    constraint chk_booked check (booked >= 0 and booked <= capacity)
);

create index idx_schedule_date_dept on outp_schedule (schedule_date, dept_id);

create table outp_registration (
    id           bigserial primary key,
    reg_no       int           not null,
    patient_id   bigint        not null references empi_patient (id),
    schedule_id  bigint        not null references outp_schedule (id),
    dept_id      bigint        not null references sys_dept (id),
    doctor_id    bigint        references sys_user (id),
    visit_date   date          not null,
    fee          numeric(10,2) not null default 0,
    status       varchar(16)   not null default 'REGISTERED',
    created_at   timestamptz   not null default now(),
    cancelled_at timestamptz
);

create index idx_registration_visit_date on outp_registration (visit_date);
create index idx_registration_patient on outp_registration (patient_id);

-- 菜单：门诊业务
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (7, null, '门诊业务', 'DIR',  '/outpatient',           null,              'FirstAidKit', 20),
    (8, 7,    '门诊挂号', 'MENU', '/outpatient/register',  'outp:reg',        'Tickets', 1),
    (9, 7,    '医生排班', 'MENU', '/outpatient/schedules', 'outp:schedule',   'Calendar', 2);

-- ADMIN 全部；收费员可用门诊挂号
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (7, 8, 9);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'CASHIER' and m.id in (7, 8);
