-- 1.1.6 第四轮审阅方向 A（docs/代码审阅-20260817-第四轮.md）

-- B-2：同一病案只允许入组一次。V54 允许一次入院多行结算后，DRG pending 查询曾可能
-- 对同一 admission 出多行（冲销再出院场景）。先清重（保留最新一行）再建唯一索引——
-- 存量库直接建索引会因历史重复行而失败（1.1.0 迁移安全教训）。
delete from drg_case a using drg_case b
 where a.admission_id = b.admission_id and a.id < b.id;
create unique index if not exists uq_drg_case_admission on drg_case(admission_id);

-- B-9：yb_settle_split 半开区间改了（1.1.3）、索引没建——注释自己承认全表扫。
-- yb_audit_log 同（/summary 今日计数）。
create index if not exists idx_yb_split_created on yb_settle_split(created_at);
create index if not exists idx_yb_audit_created on yb_audit_log(created_at);
