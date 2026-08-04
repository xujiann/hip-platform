-- 平台底座核心表：组织机构、用户、角色、菜单

create table sys_dept (
    id          bigserial primary key,
    parent_id   bigint,
    name        varchar(64)  not null,
    code        varchar(32)  not null unique,
    type        varchar(16)  not null,
    sort_no     int          not null default 0,
    enabled     boolean      not null default true,
    created_at  timestamptz  not null default now()
);

create table sys_user (
    id          bigserial primary key,
    username    varchar(64)  not null unique,
    password    varchar(100) not null,
    real_name   varchar(64)  not null,
    title       varchar(32),
    dept_id     bigint references sys_dept (id),
    phone       varchar(32),
    enabled     boolean      not null default true,
    created_at  timestamptz  not null default now()
);

create table sys_role (
    id      bigserial primary key,
    name    varchar(64) not null unique,
    code    varchar(32) not null unique,
    remark  varchar(255)
);

create table sys_menu (
    id        bigserial primary key,
    parent_id bigint,
    name      varchar(64)  not null,
    type      varchar(16)  not null,
    path      varchar(128),
    perm      varchar(64),
    icon      varchar(64),
    sort_no   int          not null default 0,
    enabled   boolean      not null default true
);

create table sys_user_role (
    user_id bigint not null references sys_user (id),
    role_id bigint not null references sys_role (id),
    primary key (user_id, role_id)
);

create table sys_role_menu (
    role_id bigint not null references sys_role (id),
    menu_id bigint not null references sys_menu (id),
    primary key (role_id, menu_id)
);

-- 种子数据：角色
insert into sys_role (name, code, remark) values
    ('系统管理员', 'ADMIN',       '拥有全部功能权限'),
    ('门诊医生',   'DOCTOR_OUTP', '门诊医生站'),
    ('收费员',     'CASHIER',     '挂号收费'),
    ('药师',       'PHARMACIST',  '药房发药与审方');

-- 种子数据：示例科室
insert into sys_dept (parent_id, name, code, type, sort_no) values
    (null, '内科门诊',   'OUTP_IM',   'CLINICAL', 1),
    (null, '外科门诊',   'OUTP_SURG', 'CLINICAL', 2),
    (null, '门诊药房',   'PHAR_OUTP', 'MEDTECH',  3),
    (null, '收费处',     'CASHIER',   'ADMIN',    4),
    (null, '信息科',     'IT',        'ADMIN',    9);

-- 种子数据：菜单（第 0 迭代仅系统管理域）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (1, null, '系统管理', 'DIR',  '/system',       null,          'Setting', 90),
    (2, 1,    '用户管理', 'MENU', '/system/users', 'sys:user:list', 'User',  1),
    (3, 1,    '科室管理', 'MENU', '/system/depts', 'sys:dept:list', 'OfficeBuilding', 2),
    (4, 1,    '角色权限', 'MENU', '/system/roles', 'sys:role:list', 'Lock',  3);
select setval('sys_menu_id_seq', 100);

-- 管理员角色绑定全部菜单
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r cross join sys_menu m where r.code = 'ADMIN';
