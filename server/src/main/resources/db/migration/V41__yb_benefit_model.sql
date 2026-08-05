-- 医保完善批次二：待遇模型（门诊/住院分比例 + 年度起付线/封顶线）
-- 起付线/封顶线默认 0 = 不启用，默认行为与既有分割算法逐分一致；政策值由实施配置（L2）。

-- 患者年度待遇累计（起付线已用额度 / 统筹已用额度）
create table yb_patient_annual (
    id              bigserial primary key,
    patient_id      bigint        not null,
    year            int           not null,
    deductible_used numeric(12,2) not null default 0,
    fund_used       numeric(12,2) not null default 0,
    updated_at      timestamptz   not null default now(),
    constraint uq_yb_annual unique (patient_id, year)
);

-- 分割结果补充：患者（退费冲销年度累计所需）与起付线自付金额
alter table yb_settle_split add column patient_id bigint;
alter table yb_settle_split add column deductible_pay numeric(12,2) not null default 0;

insert into sys_config (cfg_key, cfg_value, remark) values
    ('yb_ratio_staff_inp',         '0.85', '职工医保住院统筹支付比例'),
    ('yb_ratio_resident_inp',      '0.70', '居民医保住院统筹支付比例'),
    ('yb_deductible_staff',        '0',    '职工医保年度起付线（0=不启用）'),
    ('yb_deductible_resident',     '0',    '居民医保年度起付线（0=不启用）'),
    ('yb_cap_staff',               '0',    '职工医保年度统筹封顶线（0=不封顶）'),
    ('yb_cap_resident',            '0',    '居民医保年度统筹封顶线（0=不封顶）'),
    ('yb_audit_qty_limit',         '5',    '医保审核 R001：单品种数量上限'),
    ('yb_audit_self_ratio_limit',  '0.5',  '医保审核 R003：自费占比提醒阈值');
