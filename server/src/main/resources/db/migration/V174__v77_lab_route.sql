-- v77 车道 A：检验申请按流向动态确定执行科室（偏离表 1016★「根据流向自动获取执行科室」）。
-- 来源：docs/下一步开发规划.md v77 节「前置发号」。
--
-- 【outp_order.exec_dept_id：医嘱级执行科室快照】可空、**不回填**：历史行保持 null。
-- 三条共享读路径（打印 DOC_ORDER_SQL、/api/lis/pending、/api/lis/samples）一律
-- `coalesce(o.exec_dept_id, ci.exec_dept_id)` 回落收费项目字典，输出列名 exec_dept_name 不变——
-- 历史医嘱与 EXAM/TREAT 行（本轮不落值）的输出与 v76 逐行相同。
-- 落值即快照：开单时按当时的规则定下科室，此后改规则不回改已开医嘱（与"已开出单据可追溯"一致）。
-- 不加外键：它是开单那一刻的快照值，不随字典/科室维护联动。
--
-- 【lab_route_rule：检验流向规则】键三元组 (charge_item_id, specimen_type, order_dept_id) 全部可空，
-- 空 = 通配；匹配时"所有非空键全相等"的规则为候选，按具体度（项目 4 + 标本 2 + 开单科室 1）降序、
-- priority 升序、id 升序取首条。specimen_type 存**规范化值**（去首尾与内部空白、大写），
-- 匹配时对医嘱填写值做同样规范化后等值比较——医生手填"全 血"与规则"全血"视为同一标本。
-- item_category 本轮恒为 'LAB'（EXAM/TREAT 不参与分流），列先建好不另起表。零种子：各院流向不同，无通用默认。
--
-- 【lab.route.enabled】总开关，默认开；关闭后开单不查规则、执行科室仍按收费项目字典带出（on conflict do nothing，照 V121）。

alter table outp_order add column exec_dept_id bigint;

create table lab_route_rule (
    id             bigserial    primary key,
    name           varchar(64)  not null,
    item_category  varchar(16)  not null default 'LAB',
    charge_item_id bigint       references md_charge_item (id),
    specimen_type  varchar(32),
    order_dept_id  bigint       references sys_dept (id),
    exec_dept_id   bigint       not null references sys_dept (id),
    priority       int          not null default 100,
    enabled        boolean      not null default true,
    remark         varchar(255),
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);

create index idx_lab_route_rule_match on lab_route_rule (item_category, enabled, priority);
create index idx_lab_route_rule_item  on lab_route_rule (charge_item_id);

insert into sys_config (cfg_key, cfg_value, remark) values
    ('lab.route.enabled', '1', '检验流向规则开关：关闭后开单不查规则、执行科室仍按收费项目字典带出')
on conflict (cfg_key) do nothing;
