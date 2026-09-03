-- v44 车道F：处方模板与协定处方（技术偏离表 999★ 模板+模板编辑 / 1000★ 协定处方）。
--
-- 立项依据：`grep 处方模板|协定处方|rx_template` **全仓零命中**，而 999/1000 两条都已答
-- 「平台已实现」——与 v29 病案首页、v41 床位效率趋势、v42 那 18 条同性质的假实现，本版补真。
--
-- ============ 三条建表纪律（都是踩过坑才写下来的） ============
-- 1) **建表即带 enabled**。v42 发现 emr_template（V17:59-64）没有 enabled/status 列，
--    于是「停用模板」做不了、维护页只能退化成「复制为新模板」；v43 又确认编辑/停用仍未做。
--    同一个坑不踩第三次：本表出生就带 enabled，停用是软开关不是删行
--    （模板被停用后，历史处方仍能解释「当时是照哪张模板开的」）。
-- 2) **明细列与 outp_order 的开单列逐字段同名同型**（order_type/item_id/qty/usage_route/
--    frequency/dose_per_time/days，长度也照抄 V5__doctor_charge_dispense.sql:40-52）。
--    模板存的字段名一旦与开单对不上，套用时前端就得手工转换，转换表迟早与后端漂移。
--    V44RxTemplateTest 有一条纪律用例直接查 information_schema 比对两表这几列的类型与长度，
--    将来任何一侧改了列宽，用例先红。
-- 3) **明细不引用 md_drug / md_charge_item 的外键**：模板行的 item_id 按 order_type 分别指向
--    两张不同的主数据表（DRUG→md_drug，LAB/EXAM/TREAT→md_charge_item），一个列做不出两个外键；
--    存在性由写侧校验（4062），读侧回显 itemExists/itemEnabled 提示。
--
-- ============ 安全口径（最要紧的一条，代码侧同步注释） ============
-- **套用模板只是"填充开单表单"，绝不绕过任何既有开单校验。** 医生套用后仍走原有
-- `POST /api/outpatient/doctor/{registrationId}/orders`，皮试/过敏（4012）、重复用药（4013）、
-- 抗菌药分级（4014）、CDSS（4015/4017）、v43 停用药预检（8016）全部照常执行。
-- 本迁移与配套端点**不提供任何批量开单/直接落 outp_order 的路径**——那会让模板变成
-- 绕过用药安全的后门，"提高配方速度"不能以此为代价。

-- ===== 模板头 =====
create table rx_template (
    id         bigserial     primary key,
    name       varchar(64)   not null,
    -- 作用范围三级：PERSONAL 个人（仅本人可见可改）/ DEPT 科室（本科室医生可见，创建者与 ADMIN 可改）
    --             / HOSPITAL 全院（所有医生可见，仅 ADMIN 可改）
    scope      varchar(16)   not null default 'PERSONAL',
    -- 归属人：PERSONAL 的所有者 / DEPT·HOSPITAL 的创建者（可改性判定用它）
    owner_id   bigint        references sys_user (id),
    -- 科室模板的归属科室（scope='DEPT' 时必填，由写侧 4063 守）
    dept_id    bigint        references sys_dept (id),
    -- RX 普通处方模板（使用者可增删改明细后再开单）/ AGREED 协定处方（药事委员会审定的固定组合，
    -- 明细任何人都不可就地修改，见 4064；要改就停用旧版另建新版，保证历史处方可追溯到当时的版本）
    category   varchar(16)   not null default 'RX',
    enabled    boolean       not null default true,
    remark     varchar(255),
    created_by bigint        references sys_user (id),
    created_at timestamptz   not null default now(),
    constraint ck_rx_tpl_scope    check (scope in ('PERSONAL', 'DEPT', 'HOSPITAL')),
    constraint ck_rx_tpl_category check (category in ('RX', 'AGREED'))
);
-- 医生站取「我可见的模板」：先按 enabled 过滤，再按 scope 分三支或。三个索引各服务一支。
create index idx_rx_tpl_scope on rx_template (scope, enabled);
create index idx_rx_tpl_owner on rx_template (owner_id) where owner_id is not null;
create index idx_rx_tpl_dept  on rx_template (dept_id)  where dept_id is not null;

-- ===== 模板明细行 =====
-- 列名/列型与 outp_order 的开单列一一对应（见上文纪律 2）
create table rx_template_line (
    id            bigserial   primary key,
    template_id   bigint      not null references rx_template (id) on delete cascade,
    order_type    varchar(8)  not null,   -- DRUG/LAB/EXAM/TREAT，与 outp_order.order_type 同域
    item_id       bigint      not null,   -- DRUG→md_drug.id；LAB/EXAM/TREAT→md_charge_item.id
    qty           int         not null default 1,
    usage_route   varchar(32),
    frequency     varchar(16),
    dose_per_time varchar(32),
    days          int,
    sort_no       int         not null default 0,
    constraint ck_rx_tpl_line_type check (order_type in ('DRUG', 'LAB', 'EXAM', 'TREAT')),
    constraint ck_rx_tpl_line_qty  check (qty > 0)
);
create index idx_rx_tpl_line on rx_template_line (template_id, sort_no, id);

-- ===== 菜单：处方模板维护（门诊业务 DIR=7，sort_no 15 为该目录下首个空位） =====
-- 授权 ADMIN + 门诊医生（个人/科室模板的实际维护者）+ 药师（协定处方的药事管理责任人，
-- 与 v43 药品启停「授权给真正的责任人而非只锁 ADMIN」同口径）。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (110, 7, '处方模板', 'MENU', '/outpatient/rx-template', 'outp:rxtpl', 'Collection', 15);
insert into sys_role_menu (role_id, menu_id)
select r.id, 110 from sys_role r where r.code in ('ADMIN', 'DOCTOR_OUTP', 'PHARMACIST');

-- 序列纠偏（V128:44-47 同款）：sys_menu 一路显式插 id，sys_menu_id_seq 不会自己跟上，
-- 将来任何走 nextval 的建菜单必撞主键。每次显式插菜单后都要推到当前 max(id)。
select setval('sys_menu_id_seq', (select max(id) from sys_menu));
