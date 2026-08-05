-- 三十九期：护理风险评估单（Braden/Morse）+ 检验替检管理

-- 护理风险评估（压力性损伤 Braden / 跌倒 Morse），同患者多次评估成趋势
create table nur_risk_assess (
    id           bigserial primary key,
    admission_id bigint      not null references inp_admission (id),
    assess_type  varchar(8)  not null,                 -- BRADEN / MORSE
    score        int         not null,
    risk_level   varchar(8)  not null,                 -- HIGH/MID/LOW（按量表阈值自动判定）
    note         varchar(255),
    assessor     varchar(64),
    assessed_at  timestamptz not null default now(),
    constraint chk_assess_type check (assess_type in ('BRADEN', 'MORSE'))
);
create index idx_risk_assess on nur_risk_assess (admission_id, assess_type, assessed_at);

-- 检验替检：参数化开关 + 标本替检人标识（分检醒目提示）
alter table lis_sample add column substitute boolean not null default false;
alter table lis_sample add column substitute_name varchar(64);

insert into sys_config (cfg_key, cfg_value, remark) values
    ('lis_allow_substitute', 'true', '检验是否允许替检（false 时替检采样被拦截）');
