-- 1.1.4 工程与观测（docs/代码审阅-20260817.md 方向 C）

-- B-16：接口机专用角色——HL7 进站是机器对机器端点，1.0.9 权限清点统一补了 ADMIN，
-- 导致 LIS 中间件的凭据是全院最高权限，一旦泄漏等于交出整个系统
insert into sys_role(name, code, remark)
select '接口机', 'INTERFACE', 'LIS/设备中间件专用：仅 HL7 进站，不授予任何菜单'
where not exists (select 1 from sys_role where code = 'INTERFACE');

-- B-18：报文/文档列 varchar(8000) → text。PG 中两者存储代价相同，长度上限只带来截断风险：
-- 长 CDA 文档与长医保报文被截断后，留痕不可用于举证、CDR 文档残缺。
-- （varchar→text 是 metadata-only 变更，不重写表）
alter table int_message_log alter column payload type text;
alter table cdr_document alter column content type text;

-- B-12：对账按单号回查通道留痕，此前每单 1–3 次 payload LIKE 全表扫（8000 字符列）
create index if not exists idx_int_log_ref on int_message_log(ref_no) where ref_no is not null;
create index if not exists idx_int_log_created on int_message_log(created_at);
