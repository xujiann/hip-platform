-- v78 车道 A：标本类型字典化 + 流向规则同键唯一索引 + 历史科室/个人模板授权行回填。
-- 来源：docs/下一步开发规划.md v78 节「前置发号」（1016★ 第二轮复核仍开项、1073★/1078★ 复核仍开项）。
-- 三件事互不依赖，各自幂等（全新库一次跑完；历史库重跑任一段都不会多出一行）。
--
-- ===== 1) md_specimen_type：标本类型字典（1016★ "标本类型自由文本、规则只做规范化等值匹配"） =====
-- 【为什么】v77 的流向规则键 specimen_type 与医生站开单的 specimenType 都是自由文本，只靠规范化
-- （去空白、大写）等值匹配——医生填"静脉血"、规则写"全血"永远不命中，且无处可查"院内到底有哪些标本"。
-- 本表是两侧共同的取值来源：规则维护只能选字典里的启用项（LabRouteService.validate 5916/5917），
-- 医生站下拉取启用项、仍允许手工录入字典外值（字典外值自然不命中规则、回落收费项目字典）。
-- 【列】code 建档后不可改（页面不给改、PUT 不收 code）；name 唯一；sort_no 控下拉顺序；
-- enabled 停用守卫：仍被启用规则引用的项不能停用/删除（5917）。无 created_at：字典行无"建档时间"业务含义，
-- 与 md_fee_category 同形只留 updated_at。
-- 【种子 13 行】检验科最常见的标本分类，编码取英文缩写，`on conflict do nothing`——历史库若已有人工建的同码/同名行照旧。
-- 【幂等】create table if not exists + on conflict do nothing。
create table if not exists md_specimen_type (
    id         bigserial    primary key,
    code       varchar(16)  not null unique,
    name       varchar(32)  not null unique,
    sort_no    int          not null default 0,
    enabled    boolean      not null default true,
    updated_at timestamptz  not null default now()
);

insert into md_specimen_type (code, name, sort_no) values
    ('WB',  '全血',   10),
    ('SER', '血清',   20),
    ('PLA', '血浆',   30),
    ('UR',  '尿液',   40),
    ('ST',  '粪便',   50),
    ('SP',  '痰',     60),
    ('TS',  '咽拭子', 70),
    ('SEC', '分泌物', 80),
    ('CSF', '脑脊液', 90),
    ('EFF', '胸腹水', 100),
    ('BM',  '骨髓',   110),
    ('TIS', '组织',   120),
    ('OTH', '其他',   990)
on conflict do nothing;

-- ===== 2) lab_route_rule 启用行同键唯一部分索引（1016★ "同键三元组无唯一索引"） =====
-- 【为什么】v77 的 5913 同键判定是应用层读-判-写（LabRouteService.checkDuplicate），两位技师同时建同键规则
-- 会双双通过——并发窗口就在读与写之间；两条同键启用规则同时生效后匹配结果只由 priority/id 决定，
-- 现场查不出所以然。故把这条规则放进数据库做兜底：撞索引时控制器把 DuplicateKeyException 翻成同一个 5913。
-- 【口径与 checkDuplicate 完全一致】只约束启用行（`where enabled`：停用规则不参与匹配、也不占键，
-- 停用后同键可再建、再启用时再判）；键里的 null 视为相等（`nulls not distinct`，PG15+）——
-- 否则两条"项目通配"规则（charge_item_id 为 null）彼此不相等、索引拦不住，而 checkDuplicate 用 is not distinct from
-- 把它们判为同键，两边口径必须一样。item_category 一并入键：本轮恒为 'LAB'，将来 EXAM 规则不与 LAB 互斥。
-- 【前提】库里不得已有同键启用规则，否则本句失败、迁移停在这里——这是有意的：带着脏数据上索引等于把问题盖住。
-- 全新库恒无；历史库上线前用下面这句查，有则先停用/删除多余的那条再升级：
--   select item_category, charge_item_id, specimen_type, order_dept_id, count(*)
--   from lab_route_rule where enabled group by 1,2,3,4 having count(*) > 1;
-- 【幂等】create unique index if not exists。
create unique index if not exists uq_lab_route_rule_key
    on lab_route_rule (item_category, charge_item_id, specimen_type, order_dept_id)
    nulls not distinct
    where enabled;

-- ===== 3) emr_template_grant 回填（1073★/1078★ "升级前已有的科室模板无自动授权行、维护页「授权 0 个」"） =====
-- 【为什么】V138 起 scope=DEPT 建模板自动写一条 (DEPT, dept_id)、scope=PERSONAL 自动写一条 (USER, owner_id)，
-- 但 V138 之前已存在的科室模板（升级时 32 张）没有这条行：可见性靠 scope/dept_id 直判、功能上没坏，
-- 可维护页"授权 N 个"对它们恒显 0、与新建模板不同形，复核点名。补齐让授权表与 scope 对齐。
-- 【只补不删】不碰任何既有授权行（手工授权、自动授权都保留）；不改 emr_template。
-- granted_by 取模板 created_by（历史行多为 null → 授权行 granted_by 也 null，维护页显示空而非编造一个人）。
-- GLOBAL/HOSPITAL 不写（V138 纪律：全院范围没有授权对象）。owner_id 为空的 PERSONAL 行无人可授，跳过。
-- 【幂等】not exists + on conflict do nothing 双保险：重跑零新增。
-- 【回填前后计数（上线前后各跑一次，差值 = 补的行数）】
--   select count(*) from emr_template t where t.scope = 'DEPT' and t.dept_id is not null
--     and not exists (select 1 from emr_template_grant g
--                     where g.template_id = t.id and g.grantee_type = 'DEPT' and g.grantee_id = t.dept_id);
--   select count(*) from emr_template t where t.scope = 'PERSONAL' and t.owner_id is not null
--     and not exists (select 1 from emr_template_grant g
--                     where g.template_id = t.id and g.grantee_type = 'USER' and g.grantee_id = t.owner_id);
--   回填后两句都应为 0；select count(*) from emr_template_grant 的差值 = 回填前两句之和。
-- 下面两条 insert 夹在 @backfill-begin / @backfill-end 标记之间：V78SpecimenDictTest 从本文件原样截取执行两遍，
-- 证明幂等——用例跑的就是迁移里的 SQL，不是另抄一份。
-- @backfill-begin
insert into emr_template_grant (template_id, grantee_type, grantee_id, granted_by)
select t.id, 'DEPT', t.dept_id, t.created_by
from emr_template t
where t.scope = 'DEPT' and t.dept_id is not null
  and not exists (select 1 from emr_template_grant g
                  where g.template_id = t.id and g.grantee_type = 'DEPT' and g.grantee_id = t.dept_id)
on conflict (template_id, grantee_type, grantee_id) do nothing;

insert into emr_template_grant (template_id, grantee_type, grantee_id, granted_by)
select t.id, 'USER', t.owner_id, t.created_by
from emr_template t
where t.scope = 'PERSONAL' and t.owner_id is not null
  and not exists (select 1 from emr_template_grant g
                  where g.template_id = t.id and g.grantee_type = 'USER' and g.grantee_id = t.owner_id)
on conflict (template_id, grantee_type, grantee_id) do nothing;
-- @backfill-end
