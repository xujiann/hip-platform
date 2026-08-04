-- 第五期：药师审方（合理用药拦截为代码规则，无 DDL）

alter table outp_order add column review_status varchar(16);
alter table outp_order add column review_note varchar(255);
alter table outp_order add column reviewer_id bigint references sys_user (id);

insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (28, 7, '审方工作台', 'MENU', '/outpatient/review', 'outp:review', 'Checked', 6);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'PHARMACIST') and m.id = 28;
