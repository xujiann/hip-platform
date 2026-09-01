-- v42 车道3：病案终末质控评分与甲乙丙评级（诚信补齐，全部新增隔离，零核心写路径改动）
--
-- 背景：技术偏离表序号 2734「支持终末质控评分，按标准自动评审与打分」、2647「按运行质控/
-- 终末质控评分类型分类汇总 + 科室维度统计 + 可视化报表与趋势分析」、序号 7「病案首页质控汇总
-- 与扣分项明细」、序号 22「病例综合评分：自定义条件查询、TOP10 排名、扣分原因、导出」
-- 四条已答「已实现」，而全仓 `create table mr_` 只有 mr_death_card 一张：既有「病案质控」
-- 全是**环节质控**（在院实时现算、不落库、无历史），mr_workqueue 是待办队列不是评分单。
-- 本迁移补的是**终末质控的落库结构**：扣分项字典 + 每份病案一张评分单 + 扣分明细。
--
-- 三条本迁移刻意立的规矩：
--   ① 一份病案一张评分单（admission_id 唯一）——终末质控是出院后一次性定级，
--      不是可重复打分的过程记录；重复评分单会让「甲级率」这个考核指标失去分母的唯一性。
--   ② 扣分明细**存 item_code 而非外键**：字典项停用/改名后，历史评分单仍要能原样复现
--      当时扣了哪一条（评分单是考核依据，回溯期以年计），外键 + 级联改名会篡改历史。
--      分值同理**冗余落在明细行**（deduct_score），字典改分不追溯既往评分单。
--   ③ 甲乙丙**只做事后管理评价，不做任何 gate**：丙级病案照样归档、照样结算。
--      本迁移不碰 emr.gate.discharge / emr.gate.archive。
--
-- **边界（明确不做）**：只做院内自定义评分表。不生成病案首页国标上报报文（HQMS）、
-- 不生成 DRG-DIP 医保结算清单、不预置任何国标码值——那需对接病案质控配套产品与本地化
-- 字典映射，属实施期工作。偏离表 2734 的「按标准自动评审」在本平台的兑现口径是
-- 「按院内配置的扣分项 auto_rule 自动预填」，**不是 NLP 病历内涵语义评审**。

-- ===== 扣分项字典 =====
create table mr_qc_item (
    id           bigserial     primary key,
    code         varchar(32)   not null unique,          -- 评分单明细按此码回溯，停用后不得复用
    category     varchar(32)   not null,                 -- 一级项：入院记录/病程记录/手术记录/知情同意/首页填写/护理文书
    name         varchar(200)  not null,
    deduct_score numeric(5,2)  not null,                 -- 标准扣分（评分单落库时冗余快照，字典改分不追溯）
    auto_rule    varchar(64),                            -- 可空：填规则码的项可被 EmrIntegrityService.checkDetailed() 自动预填
    enabled      boolean       not null default true,    -- 停用即不再出现在新评分单（历史明细不受影响）
    sort_no      int           not null default 0,
    created_at   timestamptz   not null default now(),
    constraint ck_mr_qc_item_score check (deduct_score > 0 and deduct_score <= 100)
);
create index idx_mr_qc_item_cat on mr_qc_item (category, sort_no);
-- 自动预填按 auto_rule 反查，只索引有规则的少数行
create index idx_mr_qc_item_rule on mr_qc_item (auto_rule) where auto_rule is not null and enabled;

-- ===== 评分单（一份病案一张）=====
create table mr_qc_sheet (
    id           bigserial     primary key,
    admission_id bigint        not null unique references inp_admission (id),
    base_score   numeric(5,2)  not null default 100,
    total_deduct numeric(6,2)  not null default 0,
    final_score  numeric(6,2)  not null default 100,     -- = max(base_score - total_deduct, 0)
    grade        varchar(4),                             -- 甲/乙/丙（提交时按 sys_config 阈值定级，草稿态可空）
    reviewer_id  bigint        references sys_user (id),
    reviewed_at  timestamptz,                            -- 统计的月份维度取此列（质控月，非出院月）
    status       varchar(16)   not null default 'DRAFT',
    note         varchar(500),
    created_at   timestamptz   not null default now(),
    constraint ck_mr_qc_sheet_status check (status in ('DRAFT', 'SUBMITTED')),
    constraint ck_mr_qc_sheet_grade check (grade is null or grade in ('甲', '乙', '丙'))
);
-- 主查询：某月已提交评分单按科室汇总 / 待评队列
create index idx_mr_qc_sheet_reviewed on mr_qc_sheet (reviewed_at desc) where status = 'SUBMITTED';

-- ===== 扣分明细 =====
create table mr_qc_sheet_item (
    id           bigserial     primary key,
    sheet_id     bigint        not null references mr_qc_sheet (id) on delete cascade,
    item_code    varchar(32)   not null,                 -- 冗余码：字典项改名/停用后历史评分单原样可复现
    deduct_score numeric(5,2)  not null,
    source       varchar(8)    not null,                 -- AUTO 自动预填 / MANUAL 人工加扣
    remark       varchar(255),
    created_at   timestamptz   not null default now(),
    constraint ck_mr_qc_sheet_item_source check (source in ('AUTO', 'MANUAL')),
    constraint ck_mr_qc_sheet_item_score check (deduct_score > 0 and deduct_score <= 100),
    -- 同一评分单同一扣分项只能扣一次：重复预填/重复加项在数据库层挡住，
    -- 预填侧用 delete-then-insert 重建 AUTO 行，人工加项靠本约束判重
    constraint uq_mr_qc_sheet_item unique (sheet_id, item_code)
);
create index idx_mr_qc_sheet_item_code on mr_qc_sheet_item (item_code);

-- ===== 扣分项种子（30 条，覆盖 6 个一级项）=====
-- auto_rule 取值即 EmrIntegrityService.Finding.code，只有既有完整性检查能判定的 7 项有值；
-- 其余为人工评审项（内涵质量、书写规范、首页填写等，机器判不了，也不假装能判）。
insert into mr_qc_item (code, category, name, deduct_score, auto_rule, sort_no) values
    ('ADM01', '入院记录', '缺入院记录',                       10, 'MISS_ADMISSION',        1),
    ('ADM02', '入院记录', '入院记录未在 24 小时内完成',          5, null,                   2),
    ('ADM03', '入院记录', '主诉/现病史书写不规范',              3, null,                   3),
    ('ADM04', '入院记录', '既往史、过敏史未记录',                3, null,                   4),
    ('ADM05', '入院记录', '体格检查项目缺漏',                   3, null,                   5),
    ('PRG01', '病程记录', '病程记录条数不足',                    8, 'PROGRESS_INSUFFICIENT', 1),
    ('PRG02', '病程记录', '首次病程记录未在 8 小时内完成',        5, null,                   2),
    ('PRG03', '病程记录', '上级医师查房记录缺失',                5, null,                   3),
    ('PRG04', '病程记录', '病危/病重患者病程记录间隔超时',        5, null,                   4),
    ('PRG05', '病程记录', '会诊意见未在病程记录中体现',           3, null,                   5),
    ('PRG06', '病程记录', '缺出院小结',                        10, 'MISS_DISCHARGE',        6),
    ('PRG07', '病程记录', '出院小结未 CA 签名',                  5, 'DISCHARGE_UNSIGNED',    7),
    ('PRG08', '病程记录', '抢救记录未在 6 小时内补记',            5, null,                   8),
    ('OPR01', '手术记录', '缺手术记录',                        10, 'MISS_OP_NOTE',          1),
    ('OPR02', '手术记录', '缺术前小结',                         8, 'MISS_PREOP',            2),
    ('OPR03', '手术记录', '术后首次病程记录缺失',                5, null,                   3),
    ('OPR04', '手术记录', '麻醉记录单缺失或不完整',              5, null,                   4),
    ('OPR05', '手术记录', '手术安全核查表缺失',                  3, null,                   5),
    ('CNS01', '知情同意', '缺手术知情同意书',                   10, 'MISS_SURGERY_CONSENT',  1),
    ('CNS02', '知情同意', '缺输血知情同意书',                    8, null,                   2),
    ('CNS03', '知情同意', '缺特殊检查/特殊治疗知情同意书',        5, null,                   3),
    ('CNS04', '知情同意', '知情同意书代签人关系未注明',           3, null,                   4),
    ('FRP01', '首页填写', '出院主要诊断未编码（ICD 为空）',        8, null,                   1),
    ('FRP02', '首页填写', '主要诊断选择错误',                    5, null,                   2),
    ('FRP03', '首页填写', '手术操作未填写或编码缺失',             5, null,                   3),
    ('FRP04', '首页填写', '首页费用项与结算金额不符',             3, null,                   4),
    ('FRP05', '首页填写', '离院方式/入院途径等项目漏填',          2, null,                   5),
    ('NUR01', '护理文书', '护理记录单缺失',                      5, null,                   1),
    ('NUR02', '护理文书', '体温单绘制不规范或漏测',              3, null,                   2),
    ('NUR03', '护理文书', '护理文书未签名',                      3, null,                   3);

-- ===== 甲乙丙评级阈值 =====
-- 低于 grade_b_min 即丙级。两键须满足 0 <= b_min < a_min <= 100，否则评分端点报 4845
-- （ConfigReader.getInt 对坏值静默回落默认值，会让「配置写错了」变成「悄悄按默认打分」，
--  评级是考核依据，宁可报错也不能悄悄换标准）。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('mr.qc.grade_a_min', '90', '病案终末质控甲级最低分（含）：final_score >= 该值判甲级'),
    ('mr.qc.grade_b_min', '80', '病案终末质控乙级最低分（含）：final_score >= 该值判乙级，低于即丙级')
on conflict (cfg_key) do nothing;

-- ===== 病案终末质控菜单（数据中心 DIR=25，紧邻病案首页/病案统计）=====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (106, 25, '病案终末质控', 'MENU', '/mr-qc', 'mr:qc', 'Medal', 13);
insert into sys_role_menu (role_id, menu_id)
select r.id, 106 from sys_role r where r.code in ('ADMIN', 'QUALITY');

-- 序列纠偏（照 V128）：显式 id 建菜单不推进序列，将来走 nextval 的自定义菜单必撞主键。
select setval('sys_menu_id_seq', (select max(id) from sys_menu));
