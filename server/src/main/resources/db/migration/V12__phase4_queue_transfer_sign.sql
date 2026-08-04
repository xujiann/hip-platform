-- 第四期：叫号、转科记录、病历电子签名

-- 叫号：挂号状态增加 CALLED（无需 DDL，状态值扩展），叫号记录表
create table outp_call_log (
    id              bigserial primary key,
    registration_id bigint      not null references outp_registration (id),
    dept_id         bigint      not null,
    reg_no          int         not null,
    called_at       timestamptz not null default now()
);

-- 转科记录
create table inp_transfer_log (
    id            bigserial primary key,
    admission_id  bigint      not null references inp_admission (id),
    from_dept_id  bigint      not null,
    to_dept_id    bigint      not null,
    from_bed_id   bigint      not null,
    to_bed_id     bigint      not null,
    operator_id   bigint references sys_user (id),
    created_at    timestamptz not null default now()
);

-- 病历签名：签名串 + 签名时间（签名后冻结）
alter table outp_emr add column signature varchar(128);
alter table outp_emr add column signed_at timestamptz;

insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (27, 7, '叫号大屏', 'MENU', '/outpatient/queue', 'outp:queue', 'Bell', 5);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'DOCTOR_OUTP') and m.id = 27;
