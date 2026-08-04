-- 患者主索引

create table empi_patient (
    id              bigserial primary key,
    patient_no      varchar(20)  unique,
    name            varchar(64)  not null,
    sex             varchar(1)   not null default 'U',
    birth_date      date,
    id_type         varchar(16),
    id_no           varchar(32),
    phone           varchar(32),
    address         varchar(255),
    insurance_type  varchar(16)  default 'SELF',
    blood_type      varchar(8),
    allergy_history varchar(512),
    active          boolean      not null default true,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now()
);

create index idx_patient_name on empi_patient (name);
create index idx_patient_idno on empi_patient (id_no);
create index idx_patient_phone on empi_patient (phone);

-- 菜单：患者服务
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (5, null, '患者服务', 'DIR',  '/patient',          null,               'Avatar', 10),
    (6, 5,    '患者建档', 'MENU', '/patient/registry', 'patient:registry', 'Postcard', 1);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (5, 6);
