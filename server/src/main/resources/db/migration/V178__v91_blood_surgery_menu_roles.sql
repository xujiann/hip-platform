-- v91: align existing navigation with the scoped API duties. No new role, menu, or clinical grant.
-- Physicians: surgery and blood requests. Nursing/technicians: surgery execution only;
-- action/field authorization remains at each HTTP endpoint. Blood review/issue stays ADMIN.
insert into sys_role_menu(role_id,menu_id)
select r.id,m.id from sys_role r cross join sys_menu m
where (r.code = 'DOCTOR_OUTP' and m.id in (18,40,45))
   or (r.code in ('NURSE','TECHNICIAN') and m.id in (18,40))
on conflict do nothing;
