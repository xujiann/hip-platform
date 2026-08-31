-- v33 LIS：检验参考区间/危急值阈值主数据（按性别+年龄段可配）。
-- 此前危急值判定硬编码 Set.of('HH','LL')，只信上游/录入员主观填的 flag（LabResultListener.java:24）。
-- 有此主数据后，数值型结果在 flag 缺失时按项目+性别+年龄自动判 N/H/L/HH/LL（上游给了 flag 则尊重）。
create table lab_ref_range (
    id            bigserial primary key,
    item_code     varchar(64)  not null,
    item_name     varchar(128),
    sex           varchar(1),                 -- M/F；null=通用（性别无关）
    age_low_days  integer,                     -- 适用年龄下界（天，含）；null=不限
    age_high_days integer,                     -- 适用年龄上界（天，不含）；null=不限
    ref_low       numeric(12,3),               -- 参考区间下限（低于即 L）
    ref_high      numeric(12,3),               -- 参考区间上限（高于即 H）
    crit_low      numeric(12,3),               -- 危急值下限（低于即 LL 危急）
    crit_high     numeric(12,3),               -- 危急值上限（高于即 HH 危急）
    unit          varchar(32),
    enabled       boolean      not null default true,
    created_at    timestamptz  not null default now()
);
-- 按项目取候选区间（再由服务端按性别/年龄精确匹配）
create index idx_lab_ref_range_item on lab_ref_range (item_code) where enabled;

-- 常见项目成人阈值种子（示例，各院按本院方法学/仪器在维护页调整）。
-- crit_low/crit_high 依《危急值报告制度》常用成人阈值；性别相关项分行。
insert into lab_ref_range (item_code, item_name, sex, ref_low, ref_high, crit_low, crit_high, unit) values
    ('K',   '血钾',       null, 3.5,  5.5,  2.8,   6.5,   'mmol/L'),
    ('NA',  '血钠',       null, 135,  145,  120,   160,   'mmol/L'),
    ('CA',  '血钙',       null, 2.1,  2.6,  1.5,   3.5,   'mmol/L'),
    ('GLU', '血糖',       null, 3.9,  6.1,  2.2,   22.2,  'mmol/L'),
    ('WBC', '白细胞',     null, 4.0,  10.0, 1.5,   30.0,  '10^9/L'),
    ('PLT', '血小板',     null, 100,  300,  30,    1000,  '10^9/L'),
    ('HGB', '血红蛋白',   'M',  120,  160,  50,    null,  'g/L'),
    ('HGB', '血红蛋白',   'F',  110,  150,  50,    null,  'g/L');

-- 菜单：检验参考区间维护（挂 基础数据 DIR=13），授 ADMIN + 检验（TECHNICIAN）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (98, 13, '检验参考区间', 'MENU', '/masterdata/lab-ref-range', 'lab:refrange', 'Histogram', 60);
insert into sys_role_menu (role_id, menu_id)
select r.id, 98 from sys_role r where r.code in ('ADMIN', 'TECHNICIAN');
