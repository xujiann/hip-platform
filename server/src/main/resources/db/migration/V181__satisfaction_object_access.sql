-- Do not infer historical collectors from patients, channels or clinical encounters.
alter table pc_satisfaction add column creator_id bigint references sys_user(id);
create index idx_satisfaction_creator_recent on pc_satisfaction(creator_id,id desc);
