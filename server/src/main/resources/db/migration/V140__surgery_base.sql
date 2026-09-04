-- v46 车道K：手术域地基（技术偏离表 1397★ 手术间为核心维度的排程视图 / 1426★ 取消手术四阶段 /
-- 1428★ 手术类别分布 / 1429★ ASA 分级与死亡关联 / 1438★ 手术级别 / 1439★ 手术分级构成 /
-- 1441★ 非计划再次手术）。
--
-- ============ 为什么这是"地基"而不是"又一批统计" ============
-- inp_surgery 自 V17:45-56 建表起**只有 11 列**（+V47:63 的 op_icd），此外一列未动：
--   **无手术间、无入室/开台/结束/出室四个时间点、无手术级别、无 ASA 分级、无切口等级、
--     无手术类别（择期/急诊/日间）、无取消阶段。**
-- 手术域全部实现是 3 个端点 + 109 行前端；`ASA / 接台 / 手术间 / 切口` 四个关键词全仓零命中。
-- 而偏离表 1424–1450 那 27 条麻醉与手术质控指标**全部**要读这些列。
-- 先做统计层等于拿空表算指标——本迁移就是那批指标的数据前提，先有真数据再有真指标。
--
-- ============ 纪律一（最要紧）：既有 11 列一字不动，既有三个端点契约逐字不变 ============
-- `POST /api/inpatient/surgeries`、`GET /api/inpatient/surgeries`、
-- `PUT /api/inpatient/surgeries/{id}/complete` 的请求体、返回体、状态机口径**一个字节没改**。
-- 尤其是 **status 与 op_note 的语义**：v35 出院/归档完整性 gate
-- （EmrIntegrityService:93-100）判"手术病例是否缺手术记录"用的正是
--   `select count(*) from inp_surgery where admission_id = ? and status = 'DONE' and op_note is not null`
-- 与 `status <> 'CANCELLED'` 两条 SQL。**动 status 取值集合或 op_note 语义 = 连带改出院 gate**，
-- 本迁移与本版所有新端点因此一律**只加可空列、只走新端点**，不碰 complete 的那条 update。
-- （V46SurgeryBaseTest §① 把这条钉死：既有三端点不传新字段时逐字不变，且 gate 行为不变。）
--
-- ============ 纪律二：全部 nullable、不加 CHECK、严禁回填伪造 ============
-- 新列**全部可空、无 CHECK 约束、不做任何历史行回填**。历史手术行的手术间、四个时间点、
-- 手术级别、ASA、切口等级、手术类别必然是 NULL——那就是事实：这些数据当时根本没采集。
--
-- **严禁拿 scheduled_at 之类去猜填 start_at**。看上去只是"让老数据好看一点"，
-- 实际后果是把 1424★ 首台准点开台率、1425★ 接台时长直接算成假数据：
-- scheduled_at 是"计划几点开"，start_at 是"实际几点开"，用前者填后者等于让准点率恒等于 100%。
-- 统计层（车道 M）因此必须**显式排除 NULL 并在页面标注分母**，同 v41 床位效率趋势、
-- v42 archived_at 的做法：**宁可少算，不可假算。**
--
-- 不加 CHECK 同 V133 对 record_type 的取舍：试点库若已有实施期落数，历史脏值会挡住 Flyway，
-- 迁移失败的代价远高于脏数据。取值白名单一律**落在写侧**（SurgeryService，非法各返 4903–4906），
-- 读侧统计遇到白名单外的值一律归入"其他"而不是报错。

-- ===== 1) 手术间（1397★ 排程视图的核心维度） =====
-- 院方手术间编号形如 "OR-01"/"手术间3"/"DSA-2"，varchar(16) 足够；
-- 可空 = "尚未分配手术间"，排程视图把这一档单列成「未排手术间」桶，不当成脏数据藏起来。
alter table inp_surgery add column room_no varchar(16);

-- ===== 2) 四个时间点（1424★/1425★/1427★ 与整个接台口径的唯一数据源） =====
-- 入室 → 开台 → 结束 → 出室。四列**互相独立可空**：
--   · 历史行四个全空；
--   · 进行中的手术只有前面几个有值；
--   · 顺序校验在写侧做（SurgeryService.registerTimepoint，颠倒返 4902），
--     **不做成数据库 CHECK**——CHECK 会在"先补录出室、再补录入室"这种真实补录顺序上误伤。
-- 时区一律 timestamptz，与既有 scheduled_at/created_at 同型（会话时区由 Hikari
-- connection-init-sql 钉死 Asia/Shanghai，见 application.yml）。
alter table inp_surgery add column in_room_at  timestamptz;
alter table inp_surgery add column start_at    timestamptz;
alter table inp_surgery add column end_at      timestamptz;
alter table inp_surgery add column out_room_at timestamptz;

-- ===== 3) 手术级别（1438★/1439★） =====
-- 《医疗机构手术分级管理办法》四级：一级 / 二级 / 三级 / 四级。
alter table inp_surgery add column surgery_level varchar(8);

-- ===== 4) ASA 分级（1429★ ASA 分级与死亡关联） =====
-- 美国麻醉医师协会 ASA 分级 I–VI（罗马数字，ASCII 大写字母，**不用全角**：
-- 它是统计分组键，ASCII 才能保证 CSV 导出与外部对账时不因全半角错位对不上账）。
alter table inp_surgery add column asa_grade varchar(8);

-- ===== 5) 切口等级 =====
-- Ⅰ类（清洁）/ Ⅱ类（清洁-污染）/ Ⅲ类（污染）/ Ⅳ类（感染），
-- 与院感的切口感染率分母口径一致，取值用国标写法的全角罗马数字 + "类"。
alter table inp_surgery add column incision_type varchar(8);

-- ===== 6) 手术类别（1428★ 手术类别分布） =====
-- ELECTIVE 择期 / EMERGENCY 急诊 / DAY 日间。**存英文码不存中文**：
-- 它同时是 1424★ 首台准点率的过滤条件（只统计择期首台），码值参与 SQL 分支，
-- 中文值在实施期极易被写成"择期手术"/"择期 "各种变体。中文名在写侧与前端各一份映射。
alter table inp_surgery add column surgery_kind varchar(16);

-- ===== 7) 取消手术四阶段（1426★） =====
-- APPLY 申请阶段取消 / SCHEDULE 排程阶段取消 / PRE_IN 入室前取消 / IN_OP 术中取消。
-- **取消 = 置 status='CANCELLED' + 记阶段与原因，绝不删行**——1426★ 要的就是
-- "按阶段分别计数"，删了行就永远统计不出"排程后取消了几台"。
-- 注意 status='CANCELLED' 本就是既有读侧口径（EmrIntegrityService:94、
-- MedRecordStatsController:45/88 都在 `status <> 'CANCELLED'` 上过滤），
-- 本版只是第一次给它补上**写路径**，语义与既有读侧完全一致，未新增任何 status 取值。
-- 写侧另有一条硬规矩：**已 DONE 的手术不可取消**（返 4900），
-- 否则一台已完成手术被事后取消，会让 EmrIntegrityService 的手术病例判定当场失效。
alter table inp_surgery add column cancel_stage  varchar(16);
alter table inp_surgery add column cancel_reason varchar(200);

-- ===== 8) 非计划再次手术（1441★） =====
-- 默认 false = **"未标记"，不是"已确认不是"**。PG 11+ 加带默认值的列不重写全表，
-- 历史行统一落 false；统计层据此计数时须在页面标注"仅统计已标记的病例"，
-- 与纪律二同口径：宁可少算，不可假算。
alter table inp_surgery add column is_unplanned_reop boolean default false;

-- ===== 9) 排程视图索引 =====
-- 1397★ 的手术间视图按 (手术间, 时间) 取当日台次；inp_surgery 自建表起除主键外无任何索引。
create index idx_inp_surgery_room_sched on inp_surgery (room_no, scheduled_at);
