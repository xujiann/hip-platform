-- 集成引擎：消息日志、检验结果、危急值告警

create table int_message_log (
    id         bigserial primary key,
    direction  varchar(4)    not null,
    channel    varchar(16)   not null,
    ref_no     varchar(64),
    payload    varchar(8000) not null,
    status     varchar(8)    not null,
    error      varchar(512),
    created_at timestamptz   not null default now()
);

create index idx_int_log_channel on int_message_log (channel);

create table outp_lab_result (
    id            bigserial primary key,
    order_id      bigint       not null references outp_order (id),
    item_code     varchar(32),
    item_name     varchar(128) not null,
    result_value  varchar(64)  not null,
    unit          varchar(16),
    ref_range     varchar(64),
    abnormal_flag varchar(4),
    created_at    timestamptz  not null default now()
);

create index idx_lab_result_order on outp_lab_result (order_id);

create table outp_critical_alert (
    id              bigserial primary key,
    order_id        bigint       not null references outp_order (id),
    registration_id bigint       not null references outp_registration (id),
    content         varchar(512) not null,
    status          varchar(16)  not null default 'NEW',
    handler_id      bigint references sys_user (id),
    handled_at      timestamptz,
    created_at      timestamptz  not null default now()
);

create index idx_order_group_no on outp_order (group_no);

-- 菜单：集成平台
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (23, null, '集成平台', 'DIR',  '/integration',         null,          'Connection', 85),
    (24, 23,   '集成监控', 'MENU', '/integration/monitor', 'int:monitor', 'Monitor', 1);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (23, 24);
