-- v100: real actors; historical rows remain unassigned, never inferred from patients.
alter table phc_record add column recorder_id bigint references sys_user(id);
create index idx_phc_recorder on phc_record(recorder_id, id desc);
alter table qc_infection_case add column reporter_id bigint references sys_user(id);
create index idx_infection_reporter on qc_infection_case(reporter_id);
-- Existing preventive-care entry and its parent for the legitimate recording role.
insert into sys_role_menu(role_id,menu_id)
select r.id,m.id from sys_role r cross join sys_menu m
where r.code='NURSE' and m.id in (48,25)
on conflict do nothing;
