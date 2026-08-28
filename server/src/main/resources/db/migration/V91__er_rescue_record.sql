-- 上线检查单·车道B 临床收尾③：急诊抢救记录
-- 背景：急诊侧此前无抢救记录实体（GCS/出入量仅住院 ICU 有 inp_icu_record）。
-- 本表独立于住院 ICU：面向急诊留观/急诊分诊患者，记录抢救全过程——法定文书。
-- 字段风格参照 IcuRecordController（体征 + GCS + 出入量），但表与代码均独立，不与住院 ICU 复用。

create table er_rescue_record (
    id             bigserial   primary key,
    -- 关联急诊分诊（每个急诊患者都有分诊记录）；抢救台以此识别患者身份
    triage_id      bigint      not null references outp_triage (id),
    -- 若抢救发生在留观期间，关联留观记录（可空：分诊后未入留观即抢救的场景）
    observation_id bigint      references er_observation (id),
    -- 冗余患者名（取自分诊，便于抢救台快速识别，避免每次 join）
    patient_name   varchar(64),
    rescue_start   timestamptz not null default now(),   -- 抢救开始时间
    rescue_end     timestamptz,                          -- 抢救结束时间（进行中为空）
    -- 生命体征（抢救时关键值；参照 ICU 风格但独立）
    temperature    numeric(4,1),
    pulse          int,
    respiration    int,
    sbp            int,
    dbp            int,
    spo2           int,
    gcs            int,                                  -- 格拉斯哥昏迷评分 3-15
    -- 出入量（抢救液体复苏记录）
    intake_ml      int,
    output_ml      int,
    measures       varchar(2000),                        -- 抢救措施（心肺复苏/气管插管/电除颤/抢救用药…）
    participants   varchar(500),                         -- 参与抢救人员（法定留痕，含医生/护士）
    outcome        varchar(16),                          -- 转归 ONGOING/SUCCESS/DEATH/TRANSFERRED
    note           varchar(1000),
    recorder_id    bigint      references sys_user (id),
    created_at     timestamptz not null default now(),
    -- GCS 与住院 ICU 同校验区间；结束时间不得早于开始时间
    constraint chk_rescue_gcs  check (gcs is null or gcs between 3 and 15),
    constraint chk_rescue_time check (rescue_end is null or rescue_end >= rescue_start),
    constraint chk_rescue_outcome check (outcome is null or outcome in ('ONGOING','SUCCESS','DEATH','TRANSFERRED'))
);
create index idx_er_rescue_triage on er_rescue_record (triage_id, id desc);
create index idx_er_rescue_obs on er_rescue_record (observation_id) where observation_id is not null;
