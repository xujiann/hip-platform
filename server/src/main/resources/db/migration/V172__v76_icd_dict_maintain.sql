-- v76 车道 A：诊断字典（md_icd10）在线维护——停用标记 + 更新时间 + 检索索引
-- 来源：偏离表 993★ ①「诊断字典的独立浏览与在线维护界面」（docs/下一步开发规划.md v76 节）。
--
-- 【历史行默认启用】enabled 用 `not null default true` 加列：库内既有的 20 行常见病种子
-- 自动成为启用状态，没有任何 update 回填语句——零回填。updated_at 同理取加列时刻的 now()，
-- 它表示"本行最近一次经维护页/导入被改动的时间"，历史行的该值只是迁移时刻，不代表人工维护过。
--
-- 【停用只影响检索下拉】GET /masterdata/icd10（医生站/入院登记检索）加 `and enabled` 过滤；
-- 不级联改 outp_diagnosis / inp_diagnosis 历史行（那两张表只存 icd 文本，与本表无外键）。
--
-- 【索引取舍】全量国临版约 3 万行，名称用 `like '%kw%'` 包含匹配，btree 帮不上、顺扫可接受
-- （不引入 pg_trgm：它需要 contrib 扩展与超级用户，院内部署环境不保证）。
-- 能走索引的是前缀检索：编码前缀、拼音前缀（查询侧一律 upper(...) 比较，表达式索引同形），
-- 以及名称前缀。code 是主键，但其默认排序规则下的 btree 不能服务 `like 'x%'`，故另建
-- text_pattern_ops 索引。

alter table md_icd10
    add column enabled    boolean     not null default true,
    add column updated_at timestamptz not null default now();

create index idx_md_icd10_code_prefix   on md_icd10 (code text_pattern_ops);
create index idx_md_icd10_pinyin_prefix on md_icd10 (upper(pinyin) text_pattern_ops);
create index idx_md_icd10_name_prefix   on md_icd10 (name text_pattern_ops);
