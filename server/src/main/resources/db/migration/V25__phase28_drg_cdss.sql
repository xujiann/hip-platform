-- 二十八期：DRG 分组器（CHS-DRG 简化规则）+ CDSS 规则扩充（DDI/剂量/年龄/诊断建议）

-- ===== DRG =====

create table drg_group_def (
    id           bigserial primary key,
    drg_code     varchar(8)   not null unique,
    drg_name     varchar(128) not null,
    mdc_code     varchar(8)   not null,
    mdc_name     varchar(64)  not null,
    icd_prefixes varchar(255) not null,             -- 主诊断 ICD 前缀，逗号分隔
    surgical     boolean      not null default false, -- 手术操作组
    weight       numeric(6,4) not null              -- 权重 RW
);

create table drg_case (
    id           bigserial primary key,
    admission_id bigint       not null unique references inp_admission (id),
    drg_code     varchar(8)   not null,
    drg_name     varchar(128) not null,
    weight       numeric(6,4) not null,
    total_cost   numeric(12,2) not null default 0,
    inp_days     numeric(6,1) not null default 0,
    grouped_at   timestamptz  not null default now()
);

-- CHS-DRG 简化分组种子（对齐本院 ICD 简表；同 ICD 手术/内科分列）
insert into drg_group_def (drg_code, drg_name, mdc_code, mdc_name, icd_prefixes, surgical, weight) values
    ('FM19', '经皮冠状动脉介入治疗',       'MDCF', '循环系统疾病', 'I21',            true,  3.1200),
    ('FR41', '急性心肌梗死（内科治疗）',   'MDCF', '循环系统疾病', 'I21',            false, 1.3200),
    ('FZ11', '心力衰竭',                   'MDCF', '循环系统疾病', 'I50',            false, 1.1000),
    ('FV23', '高血压',                     'MDCF', '循环系统疾病', 'I10',            false, 0.7200),
    ('BR23', '脑缺血性疾患',               'MDCB', '神经系统疾病', 'I63',            false, 1.0500),
    ('ES31', '呼吸系统感染/炎症',          'MDCE', '呼吸系统疾病', 'J15,J18,J06,J02,J03,J40', false, 0.8900),
    ('EZ13', '支气管哮喘',                 'MDCE', '呼吸系统疾病', 'J45',            false, 0.8100),
    ('GD29', '胆囊切除手术',               'MDCG', '消化系统疾病', 'K80,K81,K21',    true,  1.8500),
    ('GZ15', '胃肠炎/胃炎',                'MDCG', '消化系统疾病', 'K29,A09',        false, 0.6500),
    ('KS11', '糖尿病',                     'MDCK', '内分泌代谢病', 'E11,E14',        false, 0.8800);

-- ===== CDSS =====

-- 药物相互作用（DDI）
create table cdss_ddi_rule (
    id       bigserial primary key,
    drug_a   varchar(64)  not null,                 -- 药名关键词
    drug_b   varchar(64)  not null,
    severity varchar(8)   not null default 'CAUTION', -- FORBID 禁用拦截 / CAUTION 提醒留痕
    message  varchar(255) not null
);

-- 疗程/剂量上限
create table cdss_dose_rule (
    id           bigserial primary key,
    drug_keyword varchar(64)  not null,
    max_days     int          not null,
    message      varchar(255) not null
);

-- 年龄限制
create table cdss_age_rule (
    id           bigserial primary key,
    drug_keyword varchar(64)  not null,
    min_age      int,
    max_age      int,
    message      varchar(255) not null
);

-- 诊断→临床建议（诊疗路径提示）
create table cdss_suggestion (
    id         bigserial primary key,
    icd_prefix varchar(16)  not null,
    content    varchar(500) not null
);

-- 提醒留痕（CAUTION 级不拦截、全部留痕；FORBID 直接拦截）
create table cdss_alert (
    id              bigserial primary key,
    registration_id bigint      not null references outp_registration (id),
    rule_type       varchar(8)  not null,           -- DDI/DOSE/AGE
    severity        varchar(8)  not null,
    message         varchar(255) not null,
    created_at      timestamptz not null default now()
);

insert into cdss_ddi_rule (drug_a, drug_b, severity, message) values
    ('头孢',     '藿香正气', 'FORBID',  '双硫仑样反应风险：头孢类禁与含乙醇制剂（藿香正气口服液）联用'),
    ('布洛芬',   '阿司匹林', 'CAUTION', 'NSAIDs 联用出血及消化道损伤风险增加，建议避免或加用胃黏膜保护'),
    ('左氧氟沙星', '布洛芬', 'CAUTION', '喹诺酮类与 NSAIDs 联用可能增加中枢兴奋/惊厥风险');

insert into cdss_dose_rule (drug_keyword, max_days, message) values
    ('布洛芬',     5,  '解热镇痛药连续使用不宜超过 5 天，超期请评估'),
    ('左氧氟沙星', 14, '喹诺酮类疗程超 14 天请评估耐药与不良反应风险');

insert into cdss_age_rule (drug_keyword, min_age, max_age, message) values
    ('左氧氟沙星', 18, null, '18 岁以下未成年人禁用喹诺酮类（影响软骨发育）');

insert into cdss_suggestion (icd_prefix, content) values
    ('J15', '建议：胸部DR正侧位、血常规(五分类)、C反应蛋白、痰培养+药敏；CURB-65 评分评估住院指征'),
    ('J06', '建议：对症治疗为主，血常规鉴别细菌/病毒感染；无指征不使用抗菌药物'),
    ('I21', '建议：立即心电图+肌钙蛋白，启动胸痛绿色通道，D2B 时间≤90 分钟；心内科急会诊'),
    ('I63', '建议：头颅CT平扫排除出血，NIHSS 评分，评估静脉溶栓时间窗（≤4.5h）'),
    ('I10', '建议：动态血压监测、血脂四项、肾功能+尿微量白蛋白；心血管风险分层'),
    ('E11', '建议：糖化血红蛋白、空腹+餐后血糖、眼底检查、尿微量白蛋白/肌酐比');

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (51, 25, 'DRG 分析',  'MENU', '/drg',  'drg:center',  'PieChart', 7),
    (52, 7,  'CDSS 提醒', 'MENU', '/cdss', 'cdss:center', 'MagicStick', 13);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (51, 52);
