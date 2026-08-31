-- v34 EMR：三级查房结构化记录。record_type 扩 'ROUND'（无 CHECK 约束，扩值无需改约束），
-- 加 4 个稀疏可空列（仅 ROUND 行使用），复用现有签名冻结/补正/病历列表/复印/CDR/首页的泛型读取。
-- 方案 A（扩列）优于建新表：新表需在诊疗经过/复印/CDR 等 4-5 处 UNION 才能纳入查房记录。
alter table inp_medical_record add column round_level        varchar(16);   -- CHIEF 主任/ATTENDING 主治/RESIDENT 住院医
alter table inp_medical_record add column round_doctor_id     bigint references sys_user (id);
alter table inp_medical_record add column round_opinion       varchar(2000); -- 查房意见
alter table inp_medical_record add column superior_correction varchar(2000); -- 上级修正意见
-- 部分索引：供查房分级查询与时限统计
create index idx_inp_record_round on inp_medical_record (admission_id, round_level)
    where record_type = 'ROUND';
