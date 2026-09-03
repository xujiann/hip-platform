-- v44 车道E：门诊诊断域完整化
-- 技术偏离表 977★（ICD-10 标准编码 + 自定义临床诊断名称描述）/ 979★（诊断助手：历史·常用·高频）/
--            982★（诊断前缀后缀）/ 983★（确诊·疑诊标记）/ 984★（医保特殊病种录入）/
--            1084★（中西医诊断兼容 + 前后缀 + 存储常用诊断并可挑选）
--
-- 背景：outp_diagnosis 自 V5:15 建表起只有 5 列（id / registration_id / icd_code / icd_name /
-- primary_diag），上述 6 条在投标应答里全部答"已实现"，实际连字段都没有。
--
-- 【第一部分：outp_diagnosis 只加 5 列，既有 5 列一列不动】
--   * 5 列**全部 nullable、全部不加 CHECK**：
--     - 历史行（试点库既有门诊诊断）必然为 null，**严禁回填伪造**——历史病历里
--       医生到底写的是"确诊"还是"疑诊"、是中医还是西医，数据里查不出来，就诚实地空着；
--     - 不加 CHECK 是同 V133/V134 的一贯判断：试点库可能已有 SQL 手工写入的脏行，
--       约束会让 Flyway 在数据库层直接失败，升级失败的代价远高于脏数据本身。
--       取值合法性由 DoctorStationService 的写入前置校验（4033/4034）保证。
--   * custom_name **与 icd_name 并存不替代**（977 的原话是"ICD-10 标准编码**及**自定义
--     临床诊断名称描述"）：icd_name 仍是唯一的展示名，下游 CdrSyncService:129 与
--     PrintReportController:225 读的都是它，改成"有 custom_name 就顶掉 icd_name"会
--     悄悄改掉处方笺与 CDR 的诊断文本。custom_name 只是医生的补充描述。
--   * diag_system 历史行留 null：读侧按"西医（ICD10）"解释即可，**但不回填**——
--     null 表示"这条数据产生时系统还没有中西医之分"，与显式选了 ICD10 是两回事。
--
-- 【中医诊断的诚实边界（1084）】本仓**不预置任何中医诊断码表**：GB/T 15657 中医病证分类
--   与代码属国标码表，本仓无授权副本，v44 范围外文档已明确"字典表 std_code/std_system 留空
--   由实施期填，本仓不预置任何国标码值"。故 diag_system='TCM' 时 icd_code 写空串
--   （既有列 not null，不动其非空约束），诊断名走 icd_name/custom_name 自由录入。
--   **严禁为了让中医诊断"看起来规范"而编造中医编码。**
--
-- 【第二部分：outp_diagnosis_favorite 常用诊断】
--   979 的"常用诊断"与 1084 的"存储常用诊断并可从中挑选"是**同一份数据**，只建一张表。
--   * 唯一约束按规划定为 (user_id, icd_code)。icd_code **可空**：中医/自定义诊断没有编码，
--     PostgreSQL 唯一约束默认 NULLS DISTINCT，故同一医生可以有多条无编码常用诊断；
--     再用一条**部分唯一索引** (user_id, icd_name) where icd_code is null 给这些无编码行
--     按名称去重。这样既保住规划要求的唯一键形状，又不必为了凑键去编造中医编码。
--   * user_id **不加外键**：常用诊断是纯个人偏好数据，累加发生在 saveEmr 这条既有写路径的
--     尾部；一旦外键失败就会把整张病历的保存事务一起回滚——用一条偏好数据的完整性去
--     换病历写不进去，这笔账划不来。服务层已对 doctorId 为 null 的调用直接跳过。
--
-- 【第三部分：outp_special_disease 特殊病种「院内登记」】
--   ★★ 边界声明（984）：本表是**院内登记**，不是医保特殊病种备案报送。
--   "向医保经办机构备案/报送并取得备案号"必须走医保接口，属外部条件（同 v44 范围外
--   已声明的"医保结算清单不做"红线）。本仓**没有**也**不假装有**报送能力：
--   本表刻意**不设** approve_status / filing_no / reported_at 之类字段——留一个永远
--   停在"待报送"的状态列，比不留更像假实现。
--   本表的真实用途：医生在诊间登记患者的特殊病种（慢特病）及院内认定的有效期，
--   开单与诊断时在界面上给出提示，避免开出不在特殊病种范围内的处方。

alter table outp_diagnosis add column prefix      varchar(32);
alter table outp_diagnosis add column suffix      varchar(32);
alter table outp_diagnosis add column certainty   varchar(16);
alter table outp_diagnosis add column diag_system varchar(8);
alter table outp_diagnosis add column custom_name varchar(128);

comment on column outp_diagnosis.prefix      is 'v44/982 诊断前缀（如「疑似」「陈旧性」「复发性」）；历史行为 null';
comment on column outp_diagnosis.suffix      is 'v44/982 诊断后缀（如「急性发作期」「术后」）；历史行为 null';
comment on column outp_diagnosis.certainty   is 'v44/983 CONFIRMED 确诊 / SUSPECTED 疑诊；历史行为 null，严禁回填';
comment on column outp_diagnosis.diag_system is 'v44/1084 ICD10 西医 / TCM 中医；历史行为 null（读侧按西医解释，但不回填）';
comment on column outp_diagnosis.custom_name is 'v44/977 自定义临床诊断名称描述；与 icd_name 并存，不替代 icd_name';

create table outp_diagnosis_favorite (
    id           bigserial primary key,
    user_id      bigint       not null,
    icd_code     varchar(16),
    icd_name     varchar(128) not null,
    diag_system  varchar(8),
    use_count    integer      not null default 1,
    last_used_at timestamptz  not null default now(),
    constraint uk_diag_fav_user_code unique (user_id, icd_code)
);

-- 无编码（中医/自定义）常用诊断按名称去重；ON CONFLICT 需要与之谓词一致的部分唯一索引
create unique index uk_diag_fav_user_name_noncode
    on outp_diagnosis_favorite (user_id, icd_name) where icd_code is null;
create index idx_diag_fav_user on outp_diagnosis_favorite (user_id, use_count desc);

comment on table  outp_diagnosis_favorite         is 'v44/979+1084 医生个人常用诊断（保存病历时自动累加使用次数）';
comment on column outp_diagnosis_favorite.icd_code is '诊断编码；中医/自定义诊断无编码时为 null（本仓不编造中医编码）';

create table outp_special_disease (
    id           bigserial primary key,
    patient_id   bigint       not null references empi_patient (id),
    disease_code varchar(32),
    disease_name varchar(128) not null,
    insurance_type varchar(32),
    start_date   date         not null,
    end_date     date,
    remark       varchar(200),
    created_by   bigint       references sys_user (id),
    created_at   timestamptz  not null default now()
);

create index idx_special_disease_patient on outp_special_disease (patient_id);

comment on table  outp_special_disease              is 'v44/984 医保特殊病种（慢特病）【院内登记】——不是向医保局备案报送，报送属外部医保接口边界，本仓不实现';
comment on column outp_special_disease.disease_code is '病种编码：院内自定义或实施期由院方填入当地医保码，本仓**不预置任何国标/地方医保病种码**';
comment on column outp_special_disease.insurance_type is '医保类别（职工/居民等），自由文本，本仓不预置码表';
comment on column outp_special_disease.end_date     is '院内认定有效期止；为 null 表示长期有效';
