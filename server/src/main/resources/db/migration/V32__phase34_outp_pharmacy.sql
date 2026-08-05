-- 三十四期：门诊体验与药事分析（过号/改约 / 药品使用分析 DDD / 集成适配器雏形）

-- 药品分类（W 西药 / C 中成药）与抗菌药 DDD 折算（每销售单位所含 DDD 数）
alter table md_drug add column drug_class varchar(2) not null default 'W';
alter table md_drug add column ddd_per_unit numeric(8,2);
update md_drug set drug_class = 'C' where name like '%藿香%' or dose_form = '口服液';
update md_drug set ddd_per_unit = 4.00  where name like '%阿莫西林%';   -- 0.25g×24 / DDD 1.5g
update md_drug set ddd_per_unit = 1.50  where name like '%头孢克肟%';   -- 50mg×12 / DDD 0.4g
update md_drug set ddd_per_unit = 7.00  where name like '%左氧氟沙星%'; -- 0.5g×7 / DDD 0.5g

-- 集成适配器注册表（雏形：文件/数据库两类；重型 ESB 参数属配套产品）
create table int_adapter (
    id         bigserial primary key,
    name       varchar(64)  not null unique,
    type       varchar(16)  not null,                  -- FILE/DB
    config     varchar(500) not null,                  -- 文件目录 / 数据源标识
    enabled    boolean      not null default true,
    created_at timestamptz  not null default now()
);

-- 内容路由规则：报文含关键字 → 路由到适配器
create table int_route_rule (
    id         bigserial primary key,
    keyword    varchar(64) not null,
    adapter_id bigint      not null references int_adapter (id),
    remark     varchar(255)
);

insert into int_adapter (name, type, config) values
    ('本地文件落地', 'FILE', 'outbox/'),
    ('备用库直写',   'DB',   'int_message_log');
insert into int_route_rule (keyword, adapter_id, remark)
select 'ORU', id, '检验结果类报文走文件落地' from int_adapter where name = '本地文件落地';

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (58, 29, '药事分析',   'MENU', '/drug-analysis', 'drug:ana', 'TrendCharts', 5),
    (59, 23, '适配器路由', 'MENU', '/int-adapters',  'int:adapter', 'Share', 3);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (58, 59);
