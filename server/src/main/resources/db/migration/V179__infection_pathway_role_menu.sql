-- v94: existing bedside catheter and clinical pathway duties must have usable navigation.
insert into sys_role_menu(role_id,menu_id)
select r.id,m.id from sys_role r cross join sys_menu m
where ((r.code='NURSE' and m.id in (61,25))
    or (r.code='DOCTOR_OUTP' and m.id in (34,5)))
on conflict do nothing;
