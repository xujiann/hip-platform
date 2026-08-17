-- 1.1.7 性能收口（第四轮审阅方向 B / P2 性能组）

-- 检验/检查工作队列按状态过滤，未终态行占比极小——部分索引最贴切
create index if not exists idx_lis_sample_pending on lis_sample(id) where status <> 'PUBLISHED';
create index if not exists idx_ris_exam_pending on ris_exam(id) where status <> 'VERIFIED';
