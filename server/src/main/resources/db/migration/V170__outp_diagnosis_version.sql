-- v74：门诊诊断修改留痕。
--
-- 【缺陷】保存病历时诊断是**物理删除后整批重插**，而三条留痕途径一条都不覆盖它：
--   · emr_version 的门诊字段集只含五段正文（它与 CA 签名口径严格对齐，签的就是那五段）；
--   · 全仓无诊断的历史旁表；
--   · sys_audit_log 不记请求体。
-- 于是医生把诊断由 A 改成 B 再保存，A 在库里、日志里、版本表里三处都查不到。
--
-- 【为什么不并进 emr_version】
--   a) 并进门诊字段集会破坏「被签名的是什么、被快照的就是什么」这条举证口径——
--      签名只覆盖五段正文，并进去就成了「快照里有、签名没覆盖」的错位；
--      要消除错位得改 CA 签名的覆盖面，那是独立决定，不该由一个缺陷修复顺手做掉。
--   b) 给 emr_version 加第三个 emr_type 也不行：该列是 varchar(8) 放不下，
--      且注释写明沿用 emr_amendment.emr_type 的既有口径，加值会与那张表分叉。
-- 故另建本表：不碰签名对齐的版本机制，也不拉伸共用枚举。
--
-- 【口径】与 emr_version 一致，刻意不另立一套：
--   · 快照的是**保存后的新状态**——第 1 版记初始诊断，改过之后第 2 版记新诊断，旧值留在第 1 版；
--   · 同内容不落新版（content_hash 比对），医生只改正文时不刷噪音；
--   · 序列化与哈希直接复用 EmrVersionService.canonicalJson/sha256Hex，两表口径逐字节同源；
--   · 留痕开关复用 emr.version.gate，不另立配置键——两者都是「病历修改留痕」。
--
-- 【零回填】不为历史就诊伪造诊断版本：上线前改过的诊断本就没有痕迹，
-- 伪造一条从未存在过的版本比没有更糟（这句口径是 V157 立的，此处照办）。
create table outp_diagnosis_version (
    id              bigserial   primary key,

    -- 指向 outp_registration.id：诊断挂在就诊上（outp_diagnosis.registration_id 同口径）。
    -- 刻意不加外键：与 emr_version 同处置——留痕不应随主表行被级联删掉。
    registration_id bigint      not null,

    -- 同一次就诊内从 1 起连续递增。写侧在事务内取 max+1，
    -- 并发由 pg_advisory_xact_lock 串行化；下面的 unique 是最后一道防线。
    version_no      int         not null,

    -- 诊断集合的规范 JSON（EmrVersionService.canonicalJson 产出）。
    -- 用 text 存 JSON 是本仓惯例（不开 jsonb，同 V157:90）。
    content         text        not null,

    -- 规范 JSON 的 SHA-256（小写 hex）。用途只有一个：与上一版比对去重。
    -- 它不是防篡改哈希，本表不做防篡改声明（同 V157:98-102）。
    content_hash    varchar(64) not null,

    changed_by      bigint      references sys_user (id),
    changed_at      timestamptz not null default now(),

    constraint uk_outp_diag_version_no unique (registration_id, version_no)
);

create index idx_outp_diag_version_reg on outp_diagnosis_version (registration_id, version_no desc);

comment on table outp_diagnosis_version is
    'v74 门诊诊断修改留痕：每次保存后的诊断集合快照，同内容不落新版；旧诊断靠更早的版本行留存';
