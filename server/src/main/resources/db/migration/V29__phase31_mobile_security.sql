-- 三十一期：移动端与等保强化（密码有效期 / 数据脱敏在应用层实现，无新表）

-- 密码最近修改时间（等保：定期更换口令）
alter table sys_user add column password_updated_at timestamptz not null default now();

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (55, 18, '移动工作台', 'MENU', '/m', 'mobile:work', 'Cellphone', 9);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (55);
