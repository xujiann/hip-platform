-- 医保局案件查办系统：平台底座表（与 platform/core 实体一致）+ 局侧种子数据

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
    id                  bigserial primary key,
    username            varchar(64)  not null unique,
    password            varchar(100) not null,
    real_name           varchar(64)  not null,
    title               varchar(32),
    dept_id             bigint references sys_dept (id),
    phone               varchar(32),
    enabled             boolean      not null default true,
    failed_attempts     int          not null default 0,
    locked_until        timestamptz,
    password_updated_at timestamptz  not null default now(),
    created_at          timestamptz  not null default now()
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

create table sys_config (
    cfg_key    varchar(64)  primary key,
    cfg_value  varchar(255) not null,
    remark     varchar(255),
    updated_at timestamptz  not null default now()
);

create table sys_audit_log (
    id          bigserial primary key,
    username    varchar(64),
    method      varchar(8)   not null,
    path        varchar(255) not null,
    http_status int          not null,
    client_ip   varchar(64),
    created_at  timestamptz  not null default now()
);
create index idx_audit_created on sys_audit_log (created_at desc);

-- 种子：角色（对应医保局令第4号中的职责分工）
insert into sys_role (name, code, remark) values
    ('系统管理员', 'ADMIN',   '全部功能权限'),
    ('办案人员',   'HANDLER', '线索核查、立案调查、取证、文书制作、执行'),
    ('法制审核',   'LEGAL',   '重大执法决定法制审核（第37-40条）'),
    ('局负责人',   'LEADER',  '立案/中止/终止/延期批准、审查决定、集体讨论');

-- 种子：机构（市局内设机构示例）
insert into sys_dept (parent_id, name, code, type, sort_no) values
    (null, '基金监督管理处', 'FUND_SUPERVISE', 'ADMIN', 1),
    (null, '政策法规处',     'LEGAL_AFFAIR',   'ADMIN', 2),
    (null, '局领导',         'LEADERSHIP',     'ADMIN', 3),
    (null, '信息中心',       'IT',             'ADMIN', 9);

-- 种子：菜单
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (10, null, '案件查办', 'DIR',  '/case',          null,              'Files',   1),
    (11, 10,   '线索管理', 'MENU', '/case/clues',    'case:clue:list',  'Search',  1),
    (12, 10,   '案件办理', 'MENU', '/case/list',     'case:file:list',  'Folder',  2),
    (13, 10,   '案由字典', 'MENU', '/case/causes',   'case:cause:list', 'Notebook',3),
    (14, 10,   '督办看板', 'MENU', '/case/supervise','case:supervise',  'AlarmClock', 4),
    (15, 10,   '统计分析', 'MENU', '/case/stats',    'case:stats',      'TrendCharts', 5),
    (1,  null, '系统管理', 'DIR',  '/system',        null,              'Setting', 90),
    (2,  1,    '用户管理', 'MENU', '/system/users',  'sys:user:list',   'User',    1),
    (3,  1,    '机构管理', 'MENU', '/system/depts',  'sys:dept:list',   'OfficeBuilding', 2),
    (4,  1,    '角色权限', 'MENU', '/system/roles',  'sys:role:list',   'Lock',    3);
select setval('sys_menu_id_seq', 100);

-- 菜单授权：ADMIN 全量；办案人员=案件查办全部；法制审核/局负责人=案件办理+督办+统计
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r cross join sys_menu m where r.code = 'ADMIN';
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r cross join sys_menu m
where r.code = 'HANDLER' and m.id in (10, 11, 12, 13, 14, 15);
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r cross join sys_menu m
where r.code in ('LEGAL', 'LEADER') and m.id in (10, 12, 14, 15);

-- 种子：机构参数与法定阈值（实施时按地市调整）
insert into sys_config (cfg_key, cfg_value, remark) values
    ('org_name',            '示范市医疗保障局', '机构全称（登录页/文书落款）'),
    ('org_short',           '示范市医保局',     '机构简称'),
    ('case_no_prefix',      '示医保案',         '立案案号前缀，如 示医保案〔2026〕1号'),
    ('decision_no_prefix',  '示医保罚',         '处罚决定书文号前缀'),
    ('legal_review_fine_threshold', '100000',   '罚款数额较大标准（元）：达到即必须法制审核（第37条）'),
    ('hearing_fine_threshold',      '100000',   '听证告知标准（元）：拟罚款达到即告知听证权利'),
    ('contact_phone',       '0000-12393',       '举报投诉电话');
