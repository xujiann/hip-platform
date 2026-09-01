-- v42 车道4：费用类别字典 + 费别/费用类别金额汇总（诚信补齐项 ★）
--
-- 背景（技术偏离表-v2.csv 三条已答"平台已实现"而代码零实现）：
--   1034★「拥有费别分类的金额汇总」、675「可按费用类别统计门诊总费用、退费金额」、
--   3684「按医保政策、费用类别、诊疗项目等维度对医保患者住院费用精准统计汇总」。
--   实测：StatsController 只有药占比、FinanceController 只有收款员×日期、
--   MedRecordStatsController 零费用维度、fund-monitor 按 biz_type 不按费别——
--   全库无一处按费别或费用类别分组金额。本迁移是该补齐的数据基础。
--
-- 【外部边界·禁止假实现】本表 std_code / std_system 一律留空：
--   医保"15 大类"等国标费用类别码表随各地医保局版本走，本仓无权威来源，自造即为伪造；
--   其唯一消费场景是医保结算清单与病案首页国标上报——两条明确的诚信红线。
--   码值由实施期院方或配套产品填入，本仓只提供承载列与院内常用类别。
--
-- 【零核心写路径改动】本迁移只新增字典表 + 两张主数据表的可空列，
--   绝不在 inp_order / outp_order / outp_charge / inp_settlement 上落费别或费用类别快照列——
--   那要动 InpatientService.createOrders（v39 刚重构完的长期医嘱模型）、
--   DoctorStationService.createOrders 与 ChargeService.settle（挂着 claimCharge 防双倍扣款、
--   医保分割、医保上传、退费冲销四处联动）。报表侧实时 join 已满足偏离表全部三条承诺。

create table md_fee_category (
    id         bigserial     primary key,
    code       varchar(32)   not null unique,
    name       varchar(64)   not null,
    sort_no    int           not null default 0,
    enabled    boolean       not null default true,
    -- 国标/医保费用类别对照：本仓不预置任何码值，见文件头说明
    std_code   varchar(32),
    std_system varchar(32),
    created_at timestamptz   not null default now()
);
create index idx_fee_category_enabled on md_fee_category (enabled, sort_no, code);

-- 院内常用费用类别（非国标、非医保码表；纯院内口径，可由实施期增删改）
insert into md_fee_category (code, name, sort_no) values
    ('WM',    '西药费',   10),
    ('CPM',   '中成药费', 20),
    ('CHM',   '中草药费', 30),
    ('EXAM',  '检查费',   40),
    ('LAB',   '化验费',   50),
    ('TREAT', '治疗费',   60),
    ('SURG',  '手术费',   70),
    ('BED',   '床位费',   80),
    ('NURS',  '护理费',   90),
    ('REG',   '诊查费',  100),
    ('MAT',   '材料费',  110),
    ('OTHER', '其他',    900)
on conflict (code) do nothing;

-- 主数据挂类：可空列（未挂类的项目在报表里显式归入"未分类"行，不丢弃）
alter table md_charge_item add column if not exists fee_category_code varchar(32);
alter table md_drug        add column if not exists fee_category_code varchar(32);

-- 【不给 md_charge_item.category 加 CHECK 白名单】：CSV 批量导入是实施期落数主手段，
-- 院方真实收费目录的类别值远超现有 LAB/EXAM/TREAT/MATERIAL 四种，加白名单会让整批导入
-- 在数据库层直接失败、且错误不可读。改为在 MasterDataController 做**字典软校验**：
-- 未知 fee_category_code 报行级错误计入 errors 数组、置空该列、不阻断整批。
-- 同理 fee_category_code 也不加外键——外键会把"字典还没维护好"变成导入硬失败。
create index if not exists idx_charge_item_fee_cat on md_charge_item (fee_category_code);
create index if not exists idx_drug_fee_cat        on md_drug (fee_category_code);

-- 回填：按现有 category / drug_class 做**尽力而为**的映射，映射不上的留 null（绝不瞎猜）。
-- md_charge_item.category 现仅 LAB/EXAM/TREAT 三种种子值（MATERIAL 已在实体注释里声明但无种子）。
update md_charge_item set fee_category_code = case category
        when 'LAB'      then 'LAB'
        when 'EXAM'     then 'EXAM'
        when 'TREAT'    then 'TREAT'
        when 'MATERIAL' then 'MAT'
    end
where fee_category_code is null and category in ('LAB', 'EXAM', 'TREAT', 'MATERIAL');

-- md_drug.drug_class：W 西药 / C 中成药（中草药本仓无单独类别值，不臆造映射）
update md_drug set fee_category_code = case drug_class
        when 'W' then 'WM'
        when 'C' then 'CPM'
    end
where fee_category_code is null and drug_class in ('W', 'C');

-- ===== 菜单 =====
-- 107 费用类别字典 → 基础数据（DIR=13）；108 费用分类报表 → 运营管理（DIR=29，与 41 日结报表同域）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (107, 13, '费用类别',     'MENU', '/masterdata/fee-category', 'md:feecat',   'PriceTag', 20),
    (108, 29, '费用分类报表', 'MENU', '/finance/fee-report',      'fin:feerpt',  'DataAnalysis', 6);

insert into sys_role_menu (role_id, menu_id)
select r.id, 107 from sys_role r where r.code = 'ADMIN';
insert into sys_role_menu (role_id, menu_id)
select r.id, 108 from sys_role r where r.code in ('ADMIN', 'QUALITY');

-- 序列纠偏（同 V128）：显式 id 种子不推进序列，将来走 nextval 的建菜单必撞主键
select setval('sys_menu_id_seq', (select max(id) from sys_menu));
