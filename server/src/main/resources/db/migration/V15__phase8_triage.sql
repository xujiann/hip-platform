-- 第八期：急诊预检分诊

create table outp_triage (
    id              bigserial primary key,
    patient_id      bigint references empi_patient (id),
    patient_name    varchar(64)  not null,
    level           int          not null,
    chief_complaint varchar(255) not null,
    temperature     numeric(4,1),
    pulse           int,
    respiration     int,
    sbp             int,
    dbp             int,
    spo2            int,
    dest_dept_id    bigint references sys_dept (id),
    status          varchar(16)  not null default 'TRIAGED',
    nurse_id        bigint references sys_user (id),
    created_at      timestamptz  not null default now(),
    constraint chk_triage_level check (level between 1 and 4)
);

create index idx_triage_created on outp_triage (created_at desc);

insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (31, 7, '急诊预检分诊', 'MENU', '/outpatient/triage', 'outp:triage', 'FirstAidKit', 7);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id = 31;
