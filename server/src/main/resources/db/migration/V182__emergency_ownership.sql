-- v98: no guessed historical ownership; unnamed emergency patients still supported.
alter table outp_triage add column doctor_id bigint references sys_user(id);
create index idx_triage_doctor on outp_triage(doctor_id,level,id desc);
create index idx_ae_reporter on qc_adverse_event(reporter_id,id desc) where not anonymous;

-- Existing entries only: doctors triage, nurses emergency workbench and incident reporting.
insert into sys_role_menu(role_id,menu_id)
select r.id,m.id from sys_role r cross join sys_menu m
where ((r.code='DOCTOR_OUTP' and m.id=31) or (r.code='NURSE' and m.id in (47,33)))
and not exists(select 1 from sys_role_menu x where x.role_id=r.id and x.menu_id=m.id);
insert into sys_role_menu(role_id,menu_id)
select distinct rm.role_id,p.id from sys_role_menu rm join sys_menu m on m.id=rm.menu_id
join sys_menu p on p.id=m.parent_id
where not exists(select 1 from sys_role_menu x where x.role_id=rm.role_id and x.menu_id=p.id);
