-- v45 车道H：病历模板体系地基（技术偏离表 987★ 常见病模板 / 988★ 科室默认模板 /
-- 1073★ 四级作用范围 / 1078★ 模板授权 / 1079★ 按既往病历建新病历 / 1095★ 病历存为模板）。
--
-- ============ 这张表欠了三个版本的账 ============
-- emr_template 自 V17:59-64 建表起**只有 4 列**（id/dept_id/name/content），V116:29 加了
-- template_type，此外一列未动：
--   · v42 第 5 项发现它**没有 enabled/status 列** → "停用模板"做不了，维护页只能退化成
--     「复制为新模板」，页面上老老实实写着"原地修改与停用尚未开放"；
--   · v43 再次确认"编辑与停用仍未做"；
--   · v44 建 rx_template 时把这件事写进建表纪律第 1 条（V136:6-11）——**建表即带 enabled**。
-- 到本版，这是**第三次**面对同一个坑。本迁移第一件事就是补 enabled，且停用是软开关不是删行：
-- 已按某张模板写成的历史病历，必须仍能解释"当时照的是哪一张"。
--
-- ============ 既有 4 列一字不动 ============
-- id/dept_id/name/content 的类型、长度、可空性**全部原样保留**：
--   · dept_id 保持**可空**——GLOBAL/HOSPITAL 两级作用范围时它就是 null，
--     且既有 GET /api/emr-templates?deptId= 的过滤口径正是"本科室 **或** 全院通用（dept_id is null）"，
--     RisView.vue、住院医生站模板下拉、V38RisExpTest、V42EmrTemplateTest 全靠这条口径吃饭；
--   · content 仍是 varchar(4000)，**本版不动它**。v44 放宽的是 inp_medical_record.content（V133），
--     不是模板正文。"病历存为模板"遇到超长正文由写侧截断并在返回体里显式回 truncated=true，
--     不靠悄悄截字，也不为此顺手改列宽（改列宽是另一件事，要单独立项评估索引与实施期数据）。

-- ===== 1) 作用范围与生命周期列 =====
-- 四级作用范围（1073★）：
--   GLOBAL   全局：平台/集团预置模板，人人可见，仅 ADMIN 可维护，不挂科室
--   HOSPITAL 全院：本院统一模板，人人可见，仅 ADMIN 可维护，不挂科室
--   DEPT     科室：本科室 + **被授权科室**可见，创建者与 ADMIN 可维护
--   PERSONAL 个人：本人 + **被授权个人**可见，仅本人可维护（ADMIN 也看不到别人的个人模板）
-- 最后一条与 rx_template 同口径（V136 / RxTemplateService 类注释）：个人模板是医生自己的
-- 书写习惯草稿，"全院模板仅 ADMIN 可改"保护的是全院口径，不是给管理员一把翻看私人草稿的钥匙。
alter table emr_template add column enabled     boolean     not null default true;
alter table emr_template add column scope       varchar(16) not null default 'HOSPITAL';
alter table emr_template add column owner_id    bigint      references sys_user (id);
-- 绑病历类型（988★ 的"科室默认模板"按 dept_id + record_type 取）：
-- ADMISSION 入院记录 / FIRST_PROGRESS 首次病程 / PROGRESS 病程记录 / ROUND 查房记录 /
-- PREOP 术前小结 / DISCHARGE 出院记录 / OUTP 门诊病历 …
-- **刻意不加 CHECK 约束、写侧也不设白名单**，与 V133:15-18 对 inp_medical_record.record_type
-- 的取舍同口径：病历类型是开放集合，实施期院方常有自定义类型，写死白名单会把它们挡在门外，
-- 而默认模板的唯一性由下面的 (dept_id, record_type) 部分唯一索引保证，本就不依赖白名单。
alter table emr_template add column record_type varchar(16);
alter table emr_template add column is_default  boolean     not null default false;
alter table emr_template add column created_by  bigint      references sys_user (id);
alter table emr_template add column created_at  timestamptz not null default now();

alter table emr_template add constraint ck_emr_tpl_scope
    check (scope in ('GLOBAL', 'HOSPITAL', 'DEPT', 'PERSONAL'));

-- 历史行回填：**dept_id 有值的既有模板语义上就是"科室专属"**（这正是既有 GET 的过滤口径），
-- 落 scope='DEPT'；dept_id 为空的落默认值 'HOSPITAL'（= 既有的"全院通用"）。
-- 这样既有数据在新的四级可见性下的行为，与它们在旧接口下的行为**完全一致**，
-- 不会出现"升级完医生忽然看不见自己科的老模板"。owner_id/created_by 留空（历史无从考证），
-- 后果是历史科室模板只有 ADMIN 能改——这比凭空认领一个作者要诚实。
update emr_template set scope = 'DEPT' where dept_id is not null;

-- 可见性查询的三条支路各配一个索引（与 EmrTemplateService.visibleWhere 一一对应）
create index idx_emr_tpl_scope on emr_template (scope, enabled);
create index idx_emr_tpl_owner on emr_template (owner_id) where owner_id is not null;
create index idx_emr_tpl_dept  on emr_template (dept_id)  where dept_id is not null;

-- ===== 2) 科室默认模板的唯一性：部分唯一索引，不是应用层判重（988★） =====
-- "同一科室同一病历类型只能有一张默认模板"如果只在应用层做读-判-写，两位质控同时点
-- 「设为默认」就会双双通过——并发窗口就在读与写之间，且这种脏数据出来之后医生站取默认模板
-- 会随机取到其中一张，现场根本查不出所以然。故把这条规则**放进数据库**：
--   · 谓词 `is_default and enabled`：只约束"生效中的默认模板"。一张模板被停用后，
--     它占着的默认位自动让出来，不必先取消默认再停用（少一步就少一次并发窗口）；
--   · 停用的模板保留 is_default=true 是刻意的——将来重新启用时它还是那张默认模板，
--     除非期间已有别的模板顶上（那时启用会撞这个索引，写侧翻成 4067 提示先处理默认位）。
-- 注意 NULL 在唯一索引里彼此不相等：dept_id 或 record_type 为空时本索引拦不住重复，
-- 故写侧强制"设为默认必须同时绑定科室与病历类型"（4067），两道一起才闭合。
create unique index uq_emr_tpl_default on emr_template (dept_id, record_type)
    where is_default and enabled;

-- ===== 3) 模板授权（1078★） =====
-- 参数原话：「需要授权的模板在新建的时候自动完成授权给构建科室或构建人」——
-- 故 scope=DEPT 建模板时自动写一条 (DEPT, dept_id)，scope=PERSONAL 自动写一条 (USER, owner_id)，
-- 不让用户建完再手动补一次（漏补的那些模板会变成谁都看不见的孤儿）。
-- GLOBAL/HOSPITAL **不写授权行**：它们的可见范围就是全体，一条"授权给全院"的行既没有对象
-- 也没有语义，只会让授权表变成一张永远对不齐的影子权限表。
create table emr_template_grant (
    id           bigserial   primary key,
    template_id  bigint      not null references emr_template (id) on delete cascade,
    grantee_type varchar(8)  not null,   -- DEPT 授权到科室 / USER 授权到个人
    grantee_id   bigint      not null,   -- DEPT→sys_dept.id；USER→sys_user.id
    granted_by   bigint      references sys_user (id),
    granted_at   timestamptz not null default now(),
    constraint ck_emr_tpl_grant_type check (grantee_type in ('DEPT', 'USER')),
    -- 同一模板对同一对象只能有一条授权：重复授权按幂等处理（写侧 on conflict do nothing），
    -- 不是报错——"再授权一次"在业务上就是无操作，不该弹错误框
    constraint uq_emr_tpl_grant unique (template_id, grantee_type, grantee_id)
);
-- grantee_id 刻意**不建外键**：一个列按 grantee_type 分别指向 sys_dept 与 sys_user 两张表，
-- 一个列做不出两个外键（与 V136 明细行 item_id 的取舍同源）。存在性由写侧校验（4065）。
create index idx_emr_tpl_grant_target on emr_template_grant (grantee_type, grantee_id);
create index idx_emr_tpl_grant_tpl    on emr_template_grant (template_id);

-- ===== 4) 菜单：复用 v42 已建的 109，**不新建 111** =====
-- 规划文档里写的是"菜单 111"，但 V133:24 早已建过 (109, 25, '病历模板', '/emr-template',
-- 'emr:template')，前端路由 router/index.ts:70 也已指到 EmrTemplateView.vue。
-- 再插一条 111 只会得到两个指向同一个页面的菜单项，用户不知道该点哪个。**复用不新建。**
-- 本版只补一件事：v42 把菜单授给了 ADMIN/DOCTOR_OUTP/QUALITY，但 MedTechController 的类级
-- @PreAuthorize 里没有 QUALITY——菜单看得见、接口 1005。该修复在代码侧（端点级 @PreAuthorize），
-- 迁移这里不动角色授权。
--
-- 序列纠偏（V128:44-47 / V133:41-44 同款）：本迁移未插菜单，此处为纪律性兜底——
-- sys_menu 一路显式插 id，任何一次遗漏都会让后续走 nextval 的建菜单撞主键。幂等、无副作用。
select setval('sys_menu_id_seq', (select max(id) from sys_menu));
