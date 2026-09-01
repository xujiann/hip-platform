-- v42 车道1：体温单（三测单）出纸闭环——inp_vital_sign 自 V9 建表后首次扩列。
--
-- 纸面三测单的法定格位在建表时全缺：24h 出入量、大便次数、体重/身高、测量部位、
-- 物理降温后体温（纸面要求虚线回连原体温点）、未测原因（外出/拒测——三测单要能画
-- 「未测」而不是把曲线断开）。本迁移只补这 8 列。
--
-- 纪律（v42 全版零核心写路径改动）：
--   * 全部 nullable——历史行必然为 null，前端按「未测」渲染，不回填任何伪造值；
--   * 不加 CHECK——服务端量程/取值校验是 v43「写入校验收口版」的事，本版一行不动写路径，
--     在库层先立约束会让既有 POST /vitals 在数据库层失败（与本版隔离承诺相悖）；
--   * 不动既有 6 个体征列（temperature/pulse/respiration/sbp/dbp/spo2）；
--   * 不新建索引——读侧唯一形状是 (admission_id, measured_at) 窗口查询，
--     V39__indexes.sql:23 的 idx_inp_vital_adm_time 已完全覆盖。
--
-- 出入量口径（写在这里以免后人误合表）：inp_icu_record 亦有 intake_ml/output_ml
-- （V21__phase24_specialty.sql:54-55），两表**绝不合并**（ICU 有独立写路径与
-- gcs/ventilator 独有语义）。合并只发生在读侧、且只在体温单端点内，ICU 记录优先。

alter table inp_vital_sign add column if not exists intake_ml           int;
alter table inp_vital_sign add column if not exists output_ml           int;
alter table inp_vital_sign add column if not exists stool_count         int;
alter table inp_vital_sign add column if not exists weight_kg           numeric(5,1);
alter table inp_vital_sign add column if not exists height_cm           int;
alter table inp_vital_sign add column if not exists measure_site        varchar(8);
alter table inp_vital_sign add column if not exists temp_after_cooling  numeric(4,1);
alter table inp_vital_sign add column if not exists not_measured_reason varchar(32);

comment on column inp_vital_sign.intake_ml           is '入量 ml（该次记录）；日汇总时 ICU 记录优先，见体温单端点';
comment on column inp_vital_sign.output_ml           is '出量 ml（该次记录）；日汇总时 ICU 记录优先，见体温单端点';
comment on column inp_vital_sign.stool_count         is '大便次数（当日）';
comment on column inp_vital_sign.weight_kg           is '体重 kg';
comment on column inp_vital_sign.height_cm           is '身高 cm';
comment on column inp_vital_sign.measure_site        is '体温测量部位 ORAL 口温 / AXILLARY 腋温 / RECTAL 肛温';
comment on column inp_vital_sign.temp_after_cooling  is '物理降温后体温 ℃——纸面三测单以虚线回连原体温点';
comment on column inp_vital_sign.not_measured_reason is '未测原因（外出/拒测/手术中等）——三测单画「未测」而不断线';
