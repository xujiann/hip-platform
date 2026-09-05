-- v48 病理 PIS 地基：一次把全部表结构落定，五条车道只加 Java/Vue，不再各自加列。
--
-- 【为什么整块由主控写】v44 曾因两条车道各自申请号段而撞码；表结构同理——
-- 并行车道各加各的列，迁移号会抢、外键会重、口径会分叉。故地基一次写完。
--
-- 【零回填纪律】本文件 **零条 update 语句**。历史标本的新字段永远是 NULL，
-- 那就是事实：当时根本没采集。绝不拿 collected_at 去填 received_at
-- （前者「取材时刻」、后者「病理科签收时刻」，混为一谈会让签收及时率恒等于 100%），
-- 也绝不拿 diagnosed_at 反推报告签发时间。同 v41 床位效率、v46 手术时间点：
-- **宁可少算，不可假算**。

-- ============================================================
-- 一、path_specimen 扩列：双来源 + 多部位 + 病理号 + 拒收
-- ============================================================

-- 双来源。既有 order_id 是 `not null unique references outp_order`——
-- **住院开的病理申请今天根本挂不上**，这是地基缺陷不是遗漏功能。
alter table path_specimen add column inp_order_id bigint references inp_order (id);
alter table path_specimen alter column order_id drop not null;

-- 多部位取材：一次手术/一份申请常有多个部位分别送检，
-- 既有 unique(order_id) 让第二个部位写不进去。改为 (来源, 部位序号) 唯一。
alter table path_specimen add column part_no smallint not null default 1;
alter table path_specimen drop constraint if exists path_specimen_order_id_key;
create unique index uq_path_specimen_outp_part on path_specimen (order_id, part_no)
    where order_id is not null;
create unique index uq_path_specimen_inp_part on path_specimen (inp_order_id, part_no)
    where inp_order_id is not null;

-- 恰有一个来源。两个都填或都不填都是脏数据——
-- 前者会让同一份标本在门诊与住院两个工作台各出现一次。
alter table path_specimen add constraint chk_path_specimen_source
    check ((order_id is not null) <> (inp_order_id is not null));

-- 病理号：与 barcode 是两回事。barcode 是院内流转条码（PB+序列，既有），
-- 病理号是**对外出报告用的法定编号**（按年+类别连续，如 2026-C-000123），
-- 患者与外院会诊都按它索引。既有 barcode 不动，新号独立成列。
alter table path_specimen add column path_no varchar(32) unique;

-- 标本类别决定走哪条时限：冰冻要求 30 分钟内出报告，常规是 5 个工作日。
-- 不设 default——历史标本的类别是未知，不是「常规」。
alter table path_specimen add column specimen_type varchar(16);
alter table path_specimen add constraint chk_path_specimen_type
    check (specimen_type is null or specimen_type in
           ('ROUTINE', 'FROZEN', 'CYTOLOGY', 'CONSULT', 'MOLECULAR'));

alter table path_specimen add column sampling_site varchar(128);          -- 取材部位
alter table path_specimen add column clinical_diagnosis varchar(500);     -- 临床诊断
alter table path_specimen add column fixative varchar(32);                -- 固定液
alter table path_specimen add column fixed_at timestamptz;                -- 离体固定时刻
alter table path_specimen add column urgent boolean not null default false;

-- 拒收：**拒收不删记录**。删了行就永远统计不出「送检了多少、拒了多少」，
-- 标本固定规范率也就没了分母。同 v46「取消手术不删记录」。
alter table path_specimen add column reject_reason varchar(255);
alter table path_specimen add column rejected_at timestamptz;
alter table path_specimen add column rejected_by bigint references sys_user (id);

-- 双签：初诊—复诊两级签发是病理报告的法定要求，一个人签不算数。
alter table path_specimen add column first_signer_id bigint references sys_user (id);
alter table path_specimen add column first_signed_at timestamptz;
alter table path_specimen add column second_signer_id bigint references sys_user (id);
alter table path_specimen add column second_signed_at timestamptz;
alter table path_specimen add column report_issued_at timestamptz;        -- 正式签发时刻

comment on column path_specimen.inp_order_id is 'v48：住院来源医嘱；与 order_id 恰有其一';
comment on column path_specimen.part_no is 'v48：同一申请下的部位序号，多部位分别送检';
comment on column path_specimen.path_no is 'v48：对外法定病理号，与院内流转条码 barcode 不是一回事';
comment on column path_specimen.report_issued_at is
    'v48：正式签发时刻。**不是** diagnosed_at——后者是写完诊断，前者是双签完成对外发布';

-- ============================================================
-- 二、path_block 蜡块 / path_slide 切片
-- ============================================================

-- 取材产出蜡块，蜡块产出切片，是一对多再一对多。
-- 编号按标本内序号（1,2,3…）而非全局序列——病理报告上写的是「3 号蜡块」。
create table path_block (
    id            bigserial primary key,
    specimen_id   bigint       not null references path_specimen (id),
    block_no      smallint     not null,                    -- 标本内蜡块序号
    block_code    varchar(40)  not null unique,             -- 完整编码：病理号-块号
    tissue_desc   varchar(500),                             -- 该块的取材组织描述
    embedded_at   timestamptz,                              -- 包埋完成时刻
    embedded_by   bigint references sys_user (id),
    dehydrate_batch varchar(32),                            -- 脱水篮批次号（逻辑分组）
    created_at    timestamptz  not null default now(),
    created_by    bigint references sys_user (id),
    constraint uq_path_block_no unique (specimen_id, block_no)
);
create index idx_path_block_specimen on path_block (specimen_id);
create index idx_path_block_batch on path_block (dehydrate_batch) where dehydrate_batch is not null;

create table path_slide (
    id            bigserial primary key,
    block_id      bigint       not null references path_block (id),
    slide_no      smallint     not null,                    -- 蜡块内切片序号
    slide_code    varchar(48)  not null unique,             -- 病理号-块号-片号
    stain_type    varchar(16)  not null default 'HE',       -- HE/IHC/SPECIAL/MOLECULAR
    stain_item    varchar(64),                              -- 具体染色项目（如 CK7、PAS）
    stained_at    timestamptz,
    stained_by    bigint references sys_user (id),
    quality       varchar(8),                               -- GOOD/FAIR/POOR，染色切片优良率的数据源
    created_at    timestamptz  not null default now(),
    constraint uq_path_slide_no unique (block_id, slide_no),
    constraint chk_path_slide_stain check (stain_type in ('HE', 'IHC', 'SPECIAL', 'MOLECULAR')),
    constraint chk_path_slide_quality check (quality is null or quality in ('GOOD', 'FAIR', 'POOR'))
);
create index idx_path_slide_block on path_slide (block_id);

-- ============================================================
-- 三、path_process 流转节点
-- ============================================================

-- 【同表按 node 区分，不为每个环节各开一张表】——沿用 v42 nur_record、v46 surg_event 的手法。
-- 分表会让「各环节耗时」的质控统计要 join 六张表。
create table path_process (
    id          bigserial primary key,
    specimen_id bigint      not null references path_specimen (id),
    node        varchar(16) not null,
    occurred_at timestamptz not null,
    operator_id bigint references sys_user (id),
    remark      varchar(255),
    created_at  timestamptz not null default now(),
    constraint chk_path_process_node check (node in
        ('RECEIVE', 'REJECT', 'GROSSING', 'DEHYDRATE', 'EMBED', 'SECTION',
         'STAIN', 'READ', 'FIRST_SIGN', 'SECOND_SIGN', 'ISSUE', 'SUPPLEMENT'))
);
create index idx_path_process_specimen on path_process (specimen_id, occurred_at);

-- ============================================================
-- 四、path_tech_order 特检技术医嘱
-- ============================================================

-- 病理医师看完 HE 片后加做深切/重切/补取/免疫组化/特殊染色，是诊断环节的正常延伸，
-- 不是新开一次申请——所以挂在 specimen 上而不是新建 order。
create table path_tech_order (
    id          bigserial primary key,
    specimen_id bigint      not null references path_specimen (id),
    block_id    bigint references path_block (id),           -- 针对某个蜡块，可空（如补取材）
    tech_type   varchar(16) not null,
    tech_item   varchar(64),                                 -- 具体项目（如 CK7、Ki-67）
    reason      varchar(255),
    status      varchar(16) not null default 'ORDERED',      -- ORDERED/DONE/CANCELLED
    ordered_by  bigint references sys_user (id),
    ordered_at  timestamptz not null default now(),
    done_at     timestamptz,
    done_by     bigint references sys_user (id),
    constraint chk_path_tech_type check (tech_type in
        ('DEEP_CUT', 'RECUT', 'RESAMPLE', 'IHC', 'SPECIAL_STAIN', 'MOLECULAR')),
    constraint chk_path_tech_status check (status in ('ORDERED', 'DONE', 'CANCELLED'))
);
create index idx_path_tech_specimen on path_tech_order (specimen_id);

-- ============================================================
-- 五、path_report 补充报告
-- ============================================================

-- 【补充报告绝不改原报告】：path_specimen 上的 gross/micro/diagnosis 三列是**首次报告**，
-- 由既有 PUT /specimens/{barcode}/diagnose 写入，本版一字不动。
-- 补充报告（免疫组化结果回来后的补充诊断、会诊意见）单独成行并保留全部历史版本——
-- 覆盖原报告会让「当时医生看到的是什么」永久不可考，这在纠纷里是致命的。
create table path_report (
    id          bigserial primary key,
    specimen_id bigint      not null references path_specimen (id),
    seq_no      smallint    not null,                        -- 该标本第几份补充报告，从 1 起
    content     text        not null,
    reason      varchar(255),
    signer_id   bigint references sys_user (id),
    signed_at   timestamptz,
    created_at  timestamptz not null default now(),
    created_by  bigint references sys_user (id),
    constraint uq_path_report_seq unique (specimen_id, seq_no)
);
create index idx_path_report_specimen on path_report (specimen_id);

-- ============================================================
-- 六、配置与菜单
-- ============================================================

insert into sys_config (cfg_key, cfg_value, remark) values
    ('path.no.pattern', '{yyyy}-{type}-{seq:6}',
     '病理号生成规则。{yyyy} 年份、{type} 类别码（C常规/F冰冻/Y细胞学/H会诊/M分子）、{seq:n} 按年+类别连续补零'),
    ('path.report.routine_hours', '120',
     '常规病理报告时限（小时，默认 5 个工作日），报告及时率的判定基准'),
    ('path.report.frozen_minutes', '30',
     '冰冻病理报告时限（分钟），报告及时率的判定基准'),
    ('emr.gate.pathology.doublesign', 'warn',
     '病理报告双签 gate：off 不校验 / warn 提示但放行（默认）/ block 未双签不得签发。'
     '默认 warn——存量流程可能只有一名病理医师，直接 block 会让报告发不出去')
on conflict (cfg_key) do nothing;

-- 挂「门诊业务」(DIR=7) 之下：本仓没有独立的「医技」目录，检验质控(101)、医技执行站(16)、
-- 预约与叫号(44) 一律挂在 7 下，病理沿用同一归属以免多出一个只装两项的孤零目录。
-- **但病理本身是双来源域**（门诊 + 住院），菜单位置不代表数据范围——
-- 工作台默认查全部来源，不按父菜单过滤成只看门诊。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (168, 7, '病理工作台', 'MENU', '/pathology/workbench', 'path:work', 'Files', 30),
    (169, 7, '病理质控',   'MENU', '/pathology/qc',        'path:qc',   'DataAnalysis', 31)
on conflict (id) do nothing;
