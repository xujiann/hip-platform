-- v59 车道 A：取材字段随修订版本化 + 修订来源增「取材修订」+ 模板码落库（技术偏离表 2530 复核打回清单）。
--
-- ============ 反驳者坐实的三处缺口（主控逐条实测） ============
-- 1. 「字段行是一次性快照」：V164 的 path_gross_field 只有 insert，唯一约束 (specimen_id, seq) 让一个标本
--    只能有一套字段行——取材员敲错一个字段值只能由病理医师改扁平文本，诊断改了文本后字段行不跟着改，
--    查看大体所见与轨迹抽屉两处并排自相矛盾。本迁移给字段行加 revision_seq（= 该套字段所属的
--    path_gross_revision.seq），唯一约束改为 (specimen_id, revision_seq, seq)：每一版都可以带自己的一套字段行，
--    读端点回「最大 revision_seq 中有字段行的那一版」，并把它与文本的最新版号并排给出（fieldsCurrent）。
-- 2. 「已有大体所见后任何取材再传描述一律 5222」：已诊断标本的补取材（RESAMPLE）没有任何入口记录该次取材的
--    大体描述。本版取材端点 append=true 且带描述时出新版本（source 仍是 GROSSING），字段行落在新 revision_seq 下；
--    另开取材修订入口（PUT /grossing/{id}/fields），来源 GROSSING_EDIT——所以 source 的 check 增一档，原三值保留。
-- 3. 「模板代码不落标本」：templateCode 此前只拼进 path_process.remark。本迁移给修订行加 template_code，
--    取材首写 / 补取材 / 取材修订各自落本次用的模板码（不用模板为 NULL；诊断覆盖没有模板，恒 NULL）。
--
-- ============ revision_seq 的默认值 1 是事实，不是编造 ============
-- V164 起字段行只在「gross_finding 为空时首写」这一条路径落库（已有大体所见再传字段直接 5222，
-- 诊断端点从不写字段行），而首写时修订表里必然还没有别的版本——该列非空才会有修订行，
-- 且该列一旦非空就不会再被任何端点写回空白（诊断端点空白即保留原值）。所以既有的每一行字段
-- **确实都属于该标本的第 1 版**，default 1 写的是它们真实的版本号。历史标本（V164 之前）零字段行，无从谈起。
--
-- ============ 纪律：零条 update ============
-- **本迁移不含任何 update 语句。** 不给既有修订行猜 template_code（当时没落，就是 NULL），
-- 不从 path_process.remark 里的「（模板 XX）」反解析回填——那是备注文本，不是字段。
-- 历史标本仍然零字段行、零修订行，读端点仍显式 fieldsAvailable:false。**宁可少算，不可假算。**

alter table path_gross_field add column revision_seq smallint not null default 1;

comment on column path_gross_field.revision_seq is
    'v59：该套字段行所属的 path_gross_revision.seq；既有行默认 1 = 它们真实的版本号（V164 起字段只随首写落库）';

-- 唯一约束从「标本内 seq 唯一」改为「标本内每一版各自 seq 唯一」：先 drop V164 的同名约束再建
alter table path_gross_field drop constraint uq_path_gross_field_seq;
alter table path_gross_field add constraint uq_path_gross_field_seq unique (specimen_id, revision_seq, seq);

alter table path_gross_revision add column template_code varchar(32);

comment on column path_gross_revision.template_code is
    'v59：本次写入所用的取材模板码（GROSSING / GROSSING_EDIT），不用模板或诊断覆盖为 NULL；V166 之前的修订行不回填';

-- source 白名单增 GROSSING_EDIT（取材修订入口）；V164 的 GROSSING / DIAGNOSE 原样保留
alter table path_gross_revision drop constraint chk_path_gross_revision_source;
alter table path_gross_revision add constraint chk_path_gross_revision_source
    check (source in ('GROSSING', 'DIAGNOSE', 'GROSSING_EDIT'));

comment on column path_gross_revision.source is
    'v58/v59：GROSSING = 取材端点首写或补取材追加；DIAGNOSE = 诊断端点覆盖（空白保留、相同不写）；GROSSING_EDIT = 取材修订入口（未诊断前）';
