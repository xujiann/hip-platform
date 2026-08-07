-- 并发缺陷修复（教材撰写核查发现）
-- 1) 处方/申请单组号序列：替代服务内存自增（重启后种子回绕会撞号）
create sequence outp_order_group_seq;

-- 2) 急诊留观并发防护：同一分诊、同一床位同时至多一条在观记录
--    （原 count-then-insert 非原子，并发下可双占；部分唯一索引把约束下沉到数据库）
create unique index uk_er_obs_active_triage on er_observation (triage_id) where status = 'IN';
create unique index uk_er_obs_active_bed on er_observation (bed_no) where status = 'IN';
