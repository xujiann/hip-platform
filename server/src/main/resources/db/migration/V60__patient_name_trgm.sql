-- 患者姓名模糊检索百万级收口（v27-C，功能完成度矩阵边界 ③）
--
-- 患者检索是 name like '%kw%' OR patient_no/id_no/phone 精确匹配的四臂 OR：
-- 只要 name 一臂不可走索引，planner 无法 BitmapOr，整条查询退化——
-- 百万级实测连**精确患者号**检索都要 115ms（倒序主键扫全表）。
--
-- pg_trgm 自 PG13 起为 trusted 扩展，库 owner（应用账号 hip）可直接安装，
-- 已在 WSL PG16 以非超级用户实测验证。
create extension if not exists pg_trgm;

-- GIN trgm 索引后 planner 对四臂做 BitmapOr（百万级实测）：
--   精确患者号 115ms → 0.11ms；三字名 infix 54ms → 0.34ms；无命中三字词 0.46ms
-- 残余边界（如实记录）：**二字**中文检索提不出完整 trigram 仍走不了本索引——
--   有命中时倒序扫早停 9-13ms，无命中最坏并行全扫 ~104ms。可接受，不再优化；
--   引导用户输入更长关键词或用患者号/手机号是更实际的路径。
create index if not exists idx_patient_name_trgm on empi_patient using gin (name gin_trgm_ops);

-- 原 btree(name) 只服务精确/前缀匹配，检索场景是 infix，与 trgm 并存无冲突，保留。
