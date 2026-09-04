-- v46 车道L：术中记录闭环 —— 管路 / 术中输血 / 术中事件三张新表
--
-- 背景（诚信补齐）：技术偏离表 1413★（管路记录）、1432★–1435★（术中输血与自体血）、
-- 1437★（镇痛泵新增与拆除）、1446★（转入 ICU/苏醒室）、1447★（插管拔管带管出室）、
-- 1448★（有创操作）、1449★（抢救）已答「平台已实现」，而此前**全仓没有任何术中记录表**
-- （`镇痛泵` / `自体血` 两个关键词零命中）。1432★–1449★ 那批统计因此无从算起。
-- 本迁移补的就是那批统计的数据源——统计层（车道 M）只读这三张表，不再拿空表算指标。
--
-- 本迁移刻意立的四条规矩：
--   ① **术中事件同表按 event_type 区分**（复用 v42 nur_record 的手法）——镇痛泵/转 ICU/
--      插管拔管/有创操作/抢救五类字段完全同构，分表会让 1446–1449 的统计要 join 五张表。
--   ② **自体血以 is_auto 布尔位为唯一统计依据**，product_type 只描述制品形态。
--      1433★ 要「输注自体血的人数」、1434★ 要「输血患者数/自体血患者数/非自体血患者数」，
--      靠字符串比对 product_type='AUTO' 会在「自体洗涤红细胞」这类录法上漏统计。
--      CHECK 约束保证 product_type='AUTO' 时 is_auto 必为真，两者永不矛盾。
--   ③ **零核心写路径改动**：三张全是新表，外键指向 inp_surgery(id) 而**一列都不动它**
--      （手术时间点由车道 K 的 V140 补）。既有 POST /api/inpatient/surgeries 与
--      PUT /{id}/complete 契约逐字不动（v42 出院完整性 gate 依赖它们）。
--   ④ **不预置国标码表**：血制品与管路取值是院内常用值，不冒充《全国临床检验操作规程》
--      或任何国标字典。实施期按院内规范增补。

-- ===== 管路记录（1413★）=====
-- 参数原话要求「自动汇总麻醉医生创建、护士记录的管路数据」：
-- 用 operator_id 记录**是谁录的**即可，不为「麻醉医生」和「护士」分建两张表——
-- 同一根动脉置管麻醉医生穿刺、护士接管固定，分表只会让同一根管子出现两条互相不知道的记录。
create table surg_tube (
    id          bigserial   primary key,
    surgery_id  bigint      not null references inp_surgery (id),
    -- 院内常用值：静脉通道/中心静脉导管/动脉置管/尿管/胃管/引流管/气管导管/硬膜外导管。
    -- **刻意不加 CHECK**：各院管路命名差异大（"深静脉"/"CVC"/"中心静脉导管"同物异名），
    -- 白名单收口在 SurgeryIntraopController.TUBE_TYPES（非法返 4920），实施期增补只改一处。
    tube_type   varchar(32) not null,
    position    varchar(64),                            -- 放置位置：左颈内静脉 / 右桡动脉 / 腹腔引流…
    depth_cm    numeric(5,1),                           -- 插入深度（厘米，可空——尿管等无深度概念）
    inserted_at timestamptz not null default now(),     -- 置管时间（可倒填：术前病房已置的管子照录）
    removed_at  timestamptz,                            -- 拔除时间；空=尚未拔除（带管出室见 surg_event）
    operator_id bigint      references sys_user (id),   -- 记录人（麻醉医生或护士，不分表只分人）
    remark      varchar(500),
    created_at  timestamptz not null default now()
);
create index idx_surg_tube_surgery on surg_tube (surgery_id, inserted_at);

-- ===== 术中输血（1432★–1435★）=====
create table surg_transfusion (
    id            bigserial   primary key,
    surgery_id    bigint      not null references inp_surgery (id),
    -- RBC 红细胞 / PLASMA 血浆 / PLT 血小板 / CRYO 冷沉淀 / WHOLE 全血 / AUTO 自体血
    product_type  varchar(16) not null,
    volume_ml     int         not null,
    -- 是否自体血：**1433★/1434★ 的唯一统计依据**，见文件头规矩②
    is_auto       boolean     not null default false,
    transfused_at timestamptz not null default now(),
    operator_id   bigint      references sys_user (id),
    created_at    timestamptz not null default now(),
    -- 新表零历史行，CHECK 不存在「脏数据挡住 Flyway」的风险（与 inp_medical_record.record_type 的处置不同）
    constraint chk_surg_transfusion_product check (
        product_type in ('RBC', 'PLASMA', 'PLT', 'CRYO', 'WHOLE', 'AUTO')),
    constraint chk_surg_transfusion_volume check (volume_ml > 0),
    -- AUTO 制品必然是自体血：保证「按 is_auto 统计」与「按 product_type 统计」永不打架
    constraint chk_surg_transfusion_auto check (product_type <> 'AUTO' or is_auto)
);
create index idx_surg_transfusion_surgery on surg_transfusion (surgery_id, transfused_at);
-- 车道 M 的 1432★–1435★ 按时间段统计走这条索引（is_auto 进 include 免回表）
create index idx_surg_transfusion_time on surg_transfusion (transfused_at) include (is_auto, volume_ml);

-- ===== 术中事件（1437★/1446★/1447★/1448★/1449★）=====
-- 同表按 event_type 区分，见文件头规矩①。
create table surg_event (
    id          bigserial   primary key,
    surgery_id  bigint      not null references inp_surgery (id),
    event_type  varchar(24) not null,
    event_time  timestamptz not null default now(),
    detail      varchar(500),                           -- 1448★ 有创操作在此记「右桡动脉穿刺置管」等
    -- 计划/非计划：1446★（计划/非计划转入 ICU）、1447★（计划/非计划拔管与再插管）明文要求区分。
    -- **可空三态**：对抢救、有创操作这类无计划性可言的事件留 null，不逼录假值；
    -- 统计须显式按 `planned is true` / `planned is false` 分组，null 单列「未区分」。
    planned     boolean,
    operator_id bigint      references sys_user (id),
    remark      varchar(500),
    created_at  timestamptz not null default now(),
    -- 白名单与 SurgeryIntraopController.EVENT_TYPES 一致（非法返 4924）。
    -- 这里加 CHECK 而管路不加：这 11 个码是车道 M 统计的**分组键**，
    -- 混进一个拼错的码就是一条谁也发现不了的漏统计。
    constraint chk_surg_event_type check (event_type in (
        'PAIN_PUMP_ON', 'PAIN_PUMP_OFF',
        'TO_ICU', 'TO_PACU',
        'INTUBATE_OR', 'INTUBATE_PACU', 'REINTUBATE', 'EXTUBATE', 'OUT_WITH_TUBE',
        'INVASIVE', 'RESCUE'))
);
create index idx_surg_event_surgery on surg_event (surgery_id, event_time);
-- 车道 M 的 1437★/1446★–1449★ 按「事件类型 + 时间段」聚合走这条索引
create index idx_surg_event_type_time on surg_event (event_type, event_time);

-- ===== 菜单 =====
-- **本迁移刻意不插 sys_menu**：v46 三条车道（K 手术地基 / L 术中记录 / M 质控统计）并行开发，
-- 各自抢 id 必撞主键；菜单与前端路由由合版时统一登记（术中记录页建议
-- parent 18 住院业务、path /inpatient/surgery-intraop、perm surg:intraop）。
