-- v36 LIS 质控轮③④：Delta check + TAT 阈值配置 + TAT 聚合索引。delta/tat 均只算不阻断（zero-throw）。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('lab.delta.threshold.pct', '50',  '结果 Delta check 变化率阈值（%），超阈提示复核（只提示不阻断）'),
    ('lab.tat.limit.minutes',   '120', 'TAT 周转超时阈值（分钟）：采样→发布超此值计超时')
on conflict (cfg_key) do nothing;
-- 加速 TAT 时间范围聚合
create index if not exists idx_lis_sample_published on lis_sample (published_at) where published_at is not null;

-- 菜单：检验质控（微生物药敏/室内质控/TAT），挂 门诊业务 DIR=7，授 ADMIN + 检验
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (101, 7, '检验质控', 'MENU', '/lis-qc', 'lis:qc', 'DataLine', 7);
insert into sys_role_menu (role_id, menu_id)
select r.id, 101 from sys_role r where r.code in ('ADMIN', 'TECHNICIAN');
