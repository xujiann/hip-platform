-- 主数据：药品目录、收费项目、ICD-10 简表

create table md_drug (
    id         bigserial primary key,
    code       varchar(32)   not null unique,
    name       varchar(128)  not null,
    spec       varchar(64),
    unit       varchar(8)    not null,
    dose_form  varchar(16),
    price      numeric(10,2) not null,
    stock      int           not null default 0,
    antibiotic boolean       not null default false,
    enabled    boolean       not null default true
);

create table md_charge_item (
    id           bigserial primary key,
    code         varchar(32)   not null unique,
    name         varchar(128)  not null,
    category     varchar(16)   not null,
    unit         varchar(8)    not null default '次',
    price        numeric(10,2) not null,
    exec_dept_id bigint,
    enabled      boolean       not null default true
);

create table md_icd10 (
    code   varchar(16)  primary key,
    name   varchar(128) not null,
    pinyin varchar(32)
);

insert into md_drug (code, name, spec, unit, dose_form, price, stock, antibiotic) values
    ('D0001', '阿莫西林胶囊',       '0.25g*24粒/盒', '盒', '胶囊',   12.50, 500, true),
    ('D0002', '布洛芬缓释胶囊',     '0.3g*20粒/盒',  '盒', '胶囊',   18.00, 300, false),
    ('D0003', '头孢克肟分散片',     '50mg*12片/盒',  '盒', '片剂',   22.00, 200, true),
    ('D0004', '藿香正气口服液',     '10ml*10支/盒',  '盒', '口服液', 15.80, 150, false),
    ('D0005', '左氧氟沙星片',       '0.5g*7片/盒',   '盒', '片剂',   25.00, 100, true),
    ('D0006', '二甲双胍缓释片',     '0.5g*60片/盒',  '盒', '片剂',    9.90, 400, false),
    ('D0007', '苯磺酸氨氯地平片',   '5mg*28片/盒',   '盒', '片剂',   14.60, 350, false),
    ('D0008', '奥美拉唑肠溶胶囊',   '20mg*14粒/盒',  '盒', '胶囊',   16.40, 260, false),
    ('D0009', '对乙酰氨基酚片',     '0.5g*16片/盒',  '盒', '片剂',    6.50, 600, false),
    ('D0010', '蒙脱石散',           '3g*10袋/盒',    '盒', '散剂',   19.20, 180, false);

insert into md_charge_item (code, name, category, unit, price, exec_dept_id) values
    ('C0001', '血常规(五分类)', 'LAB',   '次',  25.00, null),
    ('C0002', '尿常规',         'LAB',   '次',  15.00, null),
    ('C0003', '肝功能全套',     'LAB',   '次',  60.00, null),
    ('C0004', '空腹血糖',       'LAB',   '次',   8.00, null),
    ('C0005', 'C反应蛋白',      'LAB',   '次',  20.00, null),
    ('C0101', '胸部DR正侧位',   'EXAM',  '次',  80.00, null),
    ('C0102', '腹部彩超',       'EXAM',  '次', 120.00, null),
    ('C0103', '十二导联心电图', 'EXAM',  '次',  30.00, null),
    ('C0201', '静脉输液',       'TREAT', '次',  12.00, null),
    ('C0202', '肌肉注射',       'TREAT', '次',   5.00, null),
    ('C0203', '雾化吸入',       'TREAT', '次',  18.00, null);

insert into md_icd10 (code, name, pinyin) values
    ('J06.900', '急性上呼吸道感染',   'JXSHXDGR'),
    ('J02.900', '急性咽炎',           'JXYY'),
    ('J03.900', '急性扁桃体炎',       'JXBTTY'),
    ('J40.X00', '支气管炎',           'ZQGY'),
    ('J45.900', '支气管哮喘',         'ZQGXC'),
    ('R05.X00', '咳嗽',               'KS'),
    ('R51.X00', '头痛',               'TT'),
    ('R50.900', '发热',               'FR'),
    ('K29.700', '胃炎',               'WY'),
    ('K21.000', '胃食管反流病伴食管炎', 'WSGFLB'),
    ('A09.900', '感染性腹泻',         'GRXFX'),
    ('I10.X00', '原发性高血压',       'YFXGXY'),
    ('E11.900', '2型糖尿病',          'TNB'),
    ('E78.500', '高脂血症',           'GZXZ'),
    ('M54.500', '腰痛',               'YT'),
    ('N39.000', '泌尿道感染',         'MNDGR'),
    ('L50.900', '荨麻疹',             'XMZ'),
    ('H10.900', '结膜炎',             'JMY'),
    ('S93.400', '踝关节扭伤',         'HGJNS'),
    ('Z00.000', '一般性健康检查',     'YBXJKJC');

-- 菜单：基础数据
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (13, null, '基础数据', 'DIR',  '/masterdata',              null,       'Collection', 80),
    (14, 13,   '药品目录', 'MENU', '/masterdata/drugs',        'md:drug',  'MedicineBox', 1),
    (15, 13,   '收费项目', 'MENU', '/masterdata/charge-items', 'md:charge','PriceTag', 2);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (13, 14, 15);
