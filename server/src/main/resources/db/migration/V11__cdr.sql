-- CDR 临床数据中心

create table cdr_document (
    id         bigserial primary key,
    patient_id bigint        not null references empi_patient (id),
    doc_type   varchar(24)   not null,
    ref_id     bigint        not null,
    title      varchar(128)  not null,
    doc_time   timestamptz   not null,
    content    varchar(8000) not null,
    synced_at  timestamptz   not null default now(),
    unique (doc_type, ref_id)
);

create index idx_cdr_patient on cdr_document (patient_id, doc_time desc);

insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (25, null, '数据中心', 'DIR',  '/cdr',            null,        'DataAnalysis', 84),
    (26, 25,   '患者360视图', 'MENU', '/cdr/patient360', 'cdr:view360', 'View', 1);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (25, 26);
