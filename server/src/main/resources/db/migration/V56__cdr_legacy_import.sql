-- 1.1.5 存量切换：老 HIS 历史文书入 CDR（多医院部署操作指南 §六.4）
--
-- cdr_document 的幂等键是 unique(doc_type, ref_id)，ref_id 在平台内指源表行号；
-- 遗留文书没有源表行，这里给「老系统单号」发平台内稳定数字号，重复导入命中同一 ref_id。
create table if not exists cdr_legacy_ref (
    id         bigserial primary key,
    legacy_key varchar(128) not null unique,
    created_at timestamptz  not null default now()
);
