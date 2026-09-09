-- v58 车道 A：取材字段级存储 + 大体所见修订留痕（技术偏离表 2530★ 核账复核坐实的地基缺表）。
--
-- ============ 这条迁移只补两处「落库即丢失」 ============
-- 1. 取材端点（PathologyProcessController.grossing）收的是有序的「字段名 → 值」（GrossingReq.gross），
--    但 assembleGross 把它拼成「大小：3×2×1cm；切面：灰白」一段文本写进 path_specimen.gross_finding
--    就完了——**字段级信息在落库那一刻就丢了**。反驳者原话：「确保描述内容的结构化存储落空」。
--    本迁移新建 path_gross_field：每个非空字段一行，seq 按入参顺序 1..n，label / value 列长与
--    PathologyProcessController.GROSS_LABEL_MAX(32) / GROSS_VALUE_MAX(300) 逐字一致。
-- 2. path_specimen.gross_finding 是「与首次报告共用、可被整体覆盖且无任何历史留存的单列」（反驳者原话）：
--    取材端点只在为空时写，而既有 diagnose 端点非空即覆盖——覆盖前的原文没有任何地方留着。
--    本迁移新建 path_gross_revision：每次写入 / 覆盖一行，old_text 是覆盖前的原值（首次为 NULL）、
--    new_text 是写入后的值、source 标明来自取材（GROSSING）还是诊断（DIAGNOSE）。
--    **不往 path_process 的节点白名单里加节点**：修订不是流转环节，且那条 check 约束正在被别的车道改。
--
-- ============ 纪律：零条 update，历史标本永远没有字段行、没有修订行 ============
-- **本迁移不含任何 update 语句，也不从 gross_finding 反解析出任何字段行。**
-- V164 之前取材的标本，gross_finding 里只有一段拼好的文本；「大小：2×1cm；切面：灰白」这种文本
-- 按「；」「：」切开去猜字段，猜对了是巧合、猜错了就是假结构化——同一段自由描述里出现一个冒号
-- 就会被当成字段名。所以后果就摆在这里：
--   · 历史标本在 path_gross_field 里**零行**，在 path_gross_revision 里**零行**；
--   · 读端点（GET /api/pathology/process/grossing/{specimenId}）必须显式返回 fieldsAvailable:false，
--     revisions 为空数组，gross_finding 原样回出——**不许拿文本反解析出来的东西冒充字段**；
--   · 历史标本之后被 diagnose 覆盖时，修订行的 old_text 就是覆盖前的那段文本（那是真实事实），
--     但它之前的历史仍然是空——当时根本没采集。**宁可少算，不可假算。**

create table path_gross_field (
    id          bigserial    primary key,
    specimen_id bigint       not null references path_specimen (id),
    seq         smallint     not null,                       -- 入参顺序 1..n（不是字母序）
    label       varchar(32)  not null,                       -- = GROSS_LABEL_MAX
    value       varchar(300) not null,                       -- = GROSS_VALUE_MAX；值为空的字段整条不落
    created_at  timestamptz  not null default now(),
    operator_id bigint references sys_user (id),
    constraint uq_path_gross_field_seq unique (specimen_id, seq)
);
create index idx_path_gross_field_specimen on path_gross_field (specimen_id);

comment on table path_gross_field is
    'v58：取材大体描述的字段级存储（一字段一行，seq 按入参顺序）。V164 之前的标本零行——零回填，不从 gross_finding 反解析';
comment on column path_gross_field.seq is 'v58：入参顺序 1..n，读端点按它排序，不按 label 排';
comment on column path_gross_field.label is 'v58：字段名，上限 32 字（= PathologyProcessController.GROSS_LABEL_MAX）';
comment on column path_gross_field.value is 'v58：字段值，上限 300 字（= GROSS_VALUE_MAX）；值为空的字段不落行';

create table path_gross_revision (
    id          bigserial    primary key,
    specimen_id bigint       not null references path_specimen (id),
    seq         smallint     not null,                       -- 标本内修订序号 1..n
    old_text    text,                                        -- 覆盖前的原值；首次写入为 NULL
    new_text    text         not null,                       -- 写入后的值
    source      varchar(16)  not null,                       -- GROSSING / DIAGNOSE
    changed_by  bigint references sys_user (id),
    changed_at  timestamptz  not null default now(),
    constraint uq_path_gross_revision_seq unique (specimen_id, seq),
    constraint chk_path_gross_revision_source check (source in ('GROSSING', 'DIAGNOSE'))
);
create index idx_path_gross_revision_specimen on path_gross_revision (specimen_id);

comment on table path_gross_revision is
    'v58：path_specimen.gross_finding 的修订留痕（取材首写 + 诊断覆盖各一行）。V164 之前的写入与覆盖零行——零回填';
comment on column path_gross_revision.old_text is 'v58：覆盖前的原值；首次写入（无原值）为 NULL';
comment on column path_gross_revision.source is 'v58：GROSSING = 取材端点写入；DIAGNOSE = 诊断端点覆盖（空白保留、相同不写）';
