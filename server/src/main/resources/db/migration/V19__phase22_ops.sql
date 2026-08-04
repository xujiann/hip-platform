-- 二十二期：运维保障体系（故障台账 / 巡检台账 / 慢接口观测 / 备份记录）

create table ops_fault_ticket (
    id            bigserial primary key,
    title         varchar(255) not null,
    level         varchar(8)   not null default 'LOW',   -- LOW/MEDIUM/HIGH
    status        varchar(16)  not null default 'OPEN',  -- OPEN/RESOLVED
    reporter      varchar(64),
    resolve_note  varchar(1000),
    created_at    timestamptz  not null default now(),
    resolved_at   timestamptz
);

create table ops_inspection (
    id         bigserial primary key,
    item       varchar(255) not null,
    result     varchar(16)  not null default 'PASS',    -- PASS/FAIL
    note       varchar(500),
    inspector  varchar(64),
    checked_at timestamptz  not null default now()
);

create table ops_slow_api (
    id          bigserial primary key,
    method      varchar(8)   not null,
    path        varchar(255) not null,
    cost_ms     int          not null,
    occurred_at timestamptz  not null default now()
);
create index idx_ops_slow_api_time on ops_slow_api (occurred_at);

create table ops_backup_log (
    id         bigserial primary key,
    file_name  varchar(255) not null,
    size_bytes bigint       not null default 0,
    status     varchar(16)  not null default 'SUCCESS', -- SUCCESS/FAILED/VERIFIED
    note       varchar(500),
    created_at timestamptz  not null default now()
);

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (42, 1, '运维中心', 'MENU', '/ops', 'ops:center', 'SetUp', 5);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (42);
