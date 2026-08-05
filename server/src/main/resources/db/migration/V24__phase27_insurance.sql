-- 二十七期：医保补缺（目录对照 / 费用分割 / 审核提醒 / 对账 / 住院医保）

-- 医保目录对照：本地项目 → 医保编码 + 甲乙丙类
create table yb_catalog_map (
    id           bigserial primary key,
    item_type    varchar(8)   not null,               -- DRUG 药品 / ITEM 诊疗项目
    item_code    varchar(32)  not null,
    item_name    varchar(128) not null,
    yb_code      varchar(64)  not null,               -- 医保国码
    charge_class char(1)      not null default 'C',   -- A 甲类 / B 乙类 / C 丙类(自费)
    self_ratio   numeric(4,2) not null default 0,     -- 乙类先行自付比例
    updated_at   timestamptz  not null default now(),
    constraint uq_yb_map unique (item_type, item_code),
    constraint chk_yb_class check (charge_class in ('A', 'B', 'C')),
    constraint chk_yb_self_ratio check (self_ratio between 0 and 1)
);

-- 结算分割结果（医保口径：甲乙丙分类 → 统筹/个人）
create table yb_settle_split (
    id             bigserial primary key,
    charge_no      varchar(32)   not null unique,
    biz_type       varchar(8)    not null default 'OUTP',  -- OUTP/INP
    insurance_type varchar(16)   not null,
    total          numeric(12,2) not null,
    class_a        numeric(12,2) not null default 0,
    class_b        numeric(12,2) not null default 0,
    class_c        numeric(12,2) not null default 0,
    fund_pay       numeric(12,2) not null default 0,  -- 统筹支付
    self_pay       numeric(12,2) not null default 0,  -- 个人支付
    detail         text,                              -- 行级分割明细 JSON
    created_at     timestamptz   not null default now()
);

-- 智能审核提醒（本地规则雏形；真实规则库接入后同表扩展）
create table yb_audit_log (
    id         bigserial primary key,
    charge_no  varchar(32) not null,
    rule_code  varchar(16) not null,
    level      varchar(8)  not null default 'WARN',   -- WARN 提醒 / BLOCK 拦截
    message    varchar(500) not null,
    created_at timestamptz not null default now()
);

-- 对账批次留痕
create table yb_recon_batch (
    id          bigserial primary key,
    recon_date  date        not null,
    total_cnt   int         not null,
    matched_cnt int         not null,
    diff_cnt    int         not null,
    detail      text,
    created_at  timestamptz not null default now()
);

-- 住院结算增加支付方式（医保通道）
alter table inp_settlement add column pay_method varchar(16) not null default 'CASH';

-- 统筹比例参数
insert into sys_config (cfg_key, cfg_value, remark) values
    ('yb_ratio_staff',    '0.85', '职工医保统筹支付比例'),
    ('yb_ratio_resident', '0.70', '居民医保统筹支付比例');

-- 目录对照种子（对齐现有药品/收费项目主数据）
insert into yb_catalog_map (item_type, item_code, item_name, yb_code, charge_class, self_ratio) values
    ('DRUG', 'D0001', '阿莫西林胶囊',     'XJ01CA04000101', 'A', 0),
    ('DRUG', 'D0002', '布洛芬缓释胶囊',   'XM01AE01000202', 'A', 0),
    ('DRUG', 'D0003', '头孢克肟分散片',   'XJ01DD08000303', 'B', 0.10),
    ('DRUG', 'D0004', '藿香正气口服液',   'ZA05BJA0000404', 'B', 0.10),
    ('DRUG', 'D0005', '左氧氟沙星片',     'XJ01MA12000505', 'B', 0.10),
    ('DRUG', 'D0006', '二甲双胍缓释片',   'XA10BA02000606', 'A', 0),
    ('DRUG', 'D0007', '苯磺酸氨氯地平片', 'XC08CA01000707', 'A', 0),
    ('ITEM', 'C0001', '血常规(五分类)',   '250203001',      'A', 0),
    ('ITEM', 'C0003', '肝功能全套',       '250403005',      'B', 0.10),
    ('ITEM', 'C0102', '腹部彩超',         '270503012',      'B', 0.10),
    ('ITEM', 'C0103', '十二导联心电图',   '260302001',      'A', 0),
    ('ITEM', 'C0201', '静脉输液',         '120400001',      'A', 0);

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (50, 23, '医保管理', 'MENU', '/insurance', 'yb:center', 'CreditCard', 2);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (50);
