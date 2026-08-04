-- 十三至十六期：审计与密码策略、LIS/RIS 本体、手术麻醉、病历模板

-- 十三期：操作审计 + 登录防爆破
create table sys_audit_log (
    id         bigserial primary key,
    username   varchar(64),
    method     varchar(8)   not null,
    path       varchar(255) not null,
    http_status int         not null,
    client_ip  varchar(64),
    created_at timestamptz  not null default now()
);
create index idx_audit_created on sys_audit_log (created_at desc);

alter table sys_user add column failed_attempts int not null default 0;
alter table sys_user add column locked_until timestamptz;

-- 十四期：LIS 标本流转
create table lis_sample (
    id           bigserial primary key,
    order_id     bigint      not null unique references outp_order (id),
    barcode      varchar(32) not null unique,
    status       varchar(16) not null default 'COLLECTED',
    collected_at timestamptz not null default now(),
    received_at  timestamptz,
    published_at timestamptz,
    verifier_id  bigint references sys_user (id)
);

-- 十四期：RIS 检查报告
create table ris_exam (
    id          bigserial primary key,
    order_id    bigint       not null unique references outp_order (id),
    status      varchar(16)  not null default 'REGISTERED',
    findings    varchar(2000),
    impression  varchar(1000),
    reporter_id bigint references sys_user (id),
    verifier_id bigint references sys_user (id),
    reported_at timestamptz,
    verified_at timestamptz,
    created_at  timestamptz  not null default now()
);

-- 十五期：手术麻醉基础
create table inp_surgery (
    id              bigserial primary key,
    admission_id    bigint       not null references inp_admission (id),
    procedure_name  varchar(255) not null,
    anesthesia_type varchar(32),
    scheduled_at    timestamptz,
    status          varchar(16)  not null default 'REQUESTED',
    op_note         varchar(2000),
    anes_note       varchar(2000),
    surgeon_id      bigint references sys_user (id),
    created_at      timestamptz  not null default now()
);

-- 十五期：病历段落模板
create table emr_template (
    id      bigserial primary key,
    dept_id bigint references sys_dept (id),
    name    varchar(64)   not null,
    content varchar(4000) not null
);

-- 菜单
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (37, 1,  '审计日志',  'MENU', '/system/audit',  'sys:audit',  'Document', 4),
    (38, 7,  'LIS工作台', 'MENU', '/lis',           'lis:work',   'TestTube', 8),
    (39, 7,  'RIS报告',   'MENU', '/ris',           'ris:report', 'Camera', 9),
    (40, 18, '手术麻醉',  'MENU', '/surgery',       'inp:surgery','Scissor', 6),
    (41, 29, '日结报表',  'MENU', '/reports/daily', 'rpt:daily',  'Tickets', 3);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (37, 38, 39, 40, 41);
