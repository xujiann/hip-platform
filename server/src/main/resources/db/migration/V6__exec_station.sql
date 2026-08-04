-- 医技执行结果 + 医技执行站菜单

create table outp_order_report (
    id          bigserial primary key,
    order_id    bigint      not null unique references outp_order (id),
    result_text varchar(2000),
    executor_id bigint references sys_user (id),
    executed_at timestamptz not null default now()
);

insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (16, 7, '医技执行站', 'MENU', '/outpatient/exec', 'outp:exec', 'Odometer', 5);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id = 16;
