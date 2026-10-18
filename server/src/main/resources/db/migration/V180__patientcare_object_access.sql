-- v95: historical records stay unassigned; never infer a clinical examiner from another actor.
alter table pe_exam_record add column registrar_id bigint references sys_user(id);
alter table pe_exam_record add column examiner_id bigint references sys_user(id);
alter table pe_group add column creator_id bigint references sys_user(id);
create index idx_pe_exam_examiner on pe_exam_record(examiner_id,id);
create index idx_pe_exam_registrar on pe_exam_record(registrar_id,id);
create index idx_pc_followup_creator on pc_followup(creator_id,status,due_date,id);
create index idx_inp_consult_invited on inp_consult(to_dept_id,id);
insert into sys_role_menu(role_id,menu_id)
select r.id,m.id from sys_role r cross join sys_menu m
where r.code='NURSE' and m.id in (34,5)
on conflict do nothing;
