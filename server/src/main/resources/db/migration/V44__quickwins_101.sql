-- 1.0.1 快赢包（部分响应筛选 8 条，docs/验收/部分响应筛选.md）

-- 505 药师单次待审列表上限（0=不限）
insert into sys_config (cfg_key, cfg_value, remark) values
    ('review_pending_limit',    '0',  '审方工作台单次待审列表上限（0=不限）'),
-- 1749 单据编号前缀配置化（默认与既有硬编码一致，行为零变化）
    ('billno_prefix_charge',    'SJ', '门诊结算单号前缀'),
    ('billno_prefix_admission', 'ZY', '住院号前缀'),
    ('billno_prefix_settle',    'CY', '出院结算单号前缀'),
    ('billno_prefix_rx',        'CF', '处方号前缀'),
    ('billno_prefix_req',       'SQ', '检查检验申请单号前缀');

-- 1814 输血不良反应处置方案字典
create table blood_reaction_plan (
    id      bigserial primary key,
    name    varchar(200) not null unique,
    enabled boolean      not null default true
);
insert into blood_reaction_plan(name) values
    ('立即停止输血，保持静脉通路，通知医师'),
    ('抗过敏处理（地塞米松/异丙嗪），密切观察'),
    ('保留血袋与输血器送血库，采样送检'),
    ('对症退热处理，监测生命体征');
alter table blood_apply add column reaction_plan varchar(200);

-- 1028 死亡登记卡（病案）
create table mr_death_card (
    id               bigserial primary key,
    patient_id       bigint       not null,
    admission_id     bigint,
    died_at          timestamptz  not null,
    direct_cause     varchar(200) not null,
    direct_cause_icd varchar(16),
    chain_b          varchar(200),
    chain_c          varchar(200),
    chain_d          varchar(200),
    place            varchar(64),
    registrar        varchar(64),
    created_at       timestamptz  not null default now()
);

-- 1938 体检套餐中不进入总检结果的项目（逗号分隔）
alter table pe_exam_package add column hidden_items varchar(500) not null default '';
