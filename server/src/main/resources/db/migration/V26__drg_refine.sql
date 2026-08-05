-- DRG 分组器深化：ADRG→细分组（MCC/CC 严重程度）、其他诊断、支付模拟费率

-- 分组定义改为 ADRG（3 位）：细分组尾码由分组器按并发症计算（1 伴MCC / 3 伴CC / 5 不伴）
alter table drg_group_def rename column drg_code to adrg_code;
update drg_group_def set adrg_code = substring(adrg_code, 1, 3);

-- 入组结果补充：ADRG、严重程度、基础权重（drg_code 存细分组终码，weight 存终权重）
alter table drg_case add column adrg_code   varchar(8);
alter table drg_case add column severity    varchar(4) not null default 'NONE';  -- MCC/CC/NONE
alter table drg_case add column base_weight numeric(6,4);
update drg_case set adrg_code = substring(drg_code, 1, 3), base_weight = weight;

-- 住院其他诊断（出院诊断/并发症合并症，病案补录）
create table inp_diagnosis (
    id           bigserial primary key,
    admission_id bigint       not null references inp_admission (id),
    icd          varchar(16)  not null,
    name         varchar(128) not null,
    created_at   timestamptz  not null default now(),
    constraint uq_inp_diag unique (admission_id, icd)
);

-- MCC/CC 目录（严重并发症或合并症 / 一般并发症或合并症）
create table drg_cc_list (
    id         bigserial primary key,
    icd_prefix varchar(16) not null unique,
    level      varchar(4)  not null,               -- MCC/CC
    name       varchar(128) not null,
    constraint chk_cc_level check (level in ('MCC', 'CC'))
);

insert into drg_cc_list (icd_prefix, level, name) values
    ('I50', 'MCC', '心力衰竭'),
    ('J96', 'MCC', '呼吸衰竭'),
    ('R57', 'MCC', '休克'),
    ('N17', 'MCC', '急性肾衰竭'),
    ('A41', 'MCC', '脓毒症'),
    ('I63', 'MCC', '脑梗死（作为并发症）'),
    ('E11', 'CC',  '2型糖尿病'),
    ('I10', 'CC',  '原发性高血压'),
    ('J44', 'CC',  '慢性阻塞性肺疾病'),
    ('N18', 'CC',  '慢性肾脏病'),
    ('D64', 'CC',  '贫血');

-- DRG 支付模拟费率（元/权重；演示环境费用量级较小，费率取 100）
insert into sys_config (cfg_key, cfg_value, remark) values
    ('drg_rate', '100', 'DRG 支付费率（元/权重），支付模拟用');
