-- v52 合版：前端补齐四条车道（F1 病理 / F2 药房调剂 / F3 CDSS / F4 开单页警告）的菜单与授权。
--
-- 【为什么由合版统一登记】四条车道并行开发，各自插 sys_menu 必然抢同一批 id 撞主键。
-- 同 V142（v46 三车道）与 V144 的纪律：车道只交前端文件，菜单 id 由合版分配。
-- 实测合版前 sys_menu 最大 id = 169（V144 显式插的 168/169），故本文件占用 170–179。
--
-- 【本文件只 insert，不 update 任何既有行】168/169 的菜单行 V144 已插好，本文件
-- 只补它们缺的 sys_role_menu 授权行，一个字段都不改。
--
-- 【角色码逐条读自控制器的 @PreAuthorize，不是照抄车道自述】比对清单见每段注释。
-- 授宽了会出现「菜单点得进、接口 403」（v42 的教训），授窄了功能等于没交付。

-- =====================================================================
-- 一、V144 已 seed 但一行授权都没有的两个病理菜单（168 / 169）
-- =====================================================================
-- 实测：V144 只 insert sys_menu、没有 insert sys_role_menu，且 V1 的
-- 「ADMIN cross join sys_menu」只覆盖它执行当时存在的菜单 1–4，不会追认后来的菜单。
-- 结果是 admin 调 /auth/me 拿到的 89 条菜单里 168/169 一条都没有，
-- 而 router 守卫按「/auth/me 下发的菜单」放行——手输 /pathology/workbench 会被踢回 /dashboard。
-- 也就是说 v48 交付的病理 PIS 至今没有任何人能点进去。

-- 病理工作台：PathologyRegistry / PathologyProcess / PathologyReport 三个控制器
-- 类级 @PreAuthorize 逐字都是 hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP')。
insert into sys_role_menu (role_id, menu_id)
select r.id, 168 from sys_role r where r.code in ('ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP')
on conflict do nothing;

-- 病理质控：PathQcController 类级 @PreAuthorize("hasAnyRole('ADMIN','QUALITY')")。
-- **刻意不给 TECHNICIAN**——技师能进工作台但进不了质控页，这与后端一致；
-- 给了就是「菜单点得进、接口 403」。
insert into sys_role_menu (role_id, menu_id)
select r.id, 169 from sys_role r where r.code in ('ADMIN', 'QUALITY')
on conflict do nothing;

-- =====================================================================
-- 二、v50 药房调剂四个页面（F2 车道）
-- =====================================================================
-- PharmStockController / PharmPickController / DispenseCheckController / PharmOpsController
-- 四个类级 @PreAuthorize 逐字都是 hasAnyRole('ADMIN','PHARMACIST')，与既有 DispenseController 同口径。
-- （PharmPickController 里另有一个 @PreAuthorize("hasRole('ADMIN')") 的方法级端点，
--   那是页内单个动作的收紧，不影响菜单授权范围。）
--
-- sort_no 说明：父级 7 下既有 sort_no 已多处重号（3/3、5/5、6/6、7/7、12/12），
-- 且病理已占 30/31。要把这四条排到「药房发药」(sort 4) 之后就得改既有行的 sort_no——
-- 本文件不做 update，故顺排在 32–35，导航里位于病理之后。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (170, 7, '药房批次库存',   'MENU', '/outpatient/pharmacy/stock',   'outp:pharm:stock',   'Box',         32),
    (171, 7, '摆药与预调剂',   'MENU', '/outpatient/pharmacy/picking', 'outp:pharm:picking', 'Sort',        33),
    (172, 7, '发药核对',       'MENU', '/outpatient/pharmacy/check',   'outp:pharm:check',   'Checked',     34),
    (173, 7, '拆零与效期盘点', 'MENU', '/outpatient/pharmacy/ops',     'outp:pharm:ops',     'Scissor',     35)
on conflict (id) do nothing;

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'PHARMACIST') and m.id in (170, 171, 172, 173)
on conflict do nothing;

-- =====================================================================
-- 三、v51 CDSS 六个页面（F3 车道）
-- =====================================================================
-- 注意：既有菜单 52（'/cdss'）配合 router 守卫的前缀匹配
-- （to.path === p || to.path.startsWith(p + '/')）本就把 /cdss/** 全部放行，
-- 所以这六条菜单**不构成新的访问限制**，只是把入口放进导航。
-- 真正的边界是后端各方法的 @PreAuthorize，下面逐页对齐的是「进去之后不会一片 403」。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (174, 7, '过敏史人工核对', 'MENU', '/cdss/allergy-review',  'cdss:allergy:review',  'EditPen',     36),
    (175, 7, '患者过敏档',     'MENU', '/cdss/allergy-profile', 'cdss:allergy:profile', 'Notebook',    37),
    (176, 7, '过敏规则维护',   'MENU', '/cdss/allergy-rules',   'cdss:allergy:rules',   'SetUp',       38),
    (177, 7, '重复用药规则',   'MENU', '/cdss/duplicate',       'cdss:duplicate',       'DocumentCopy', 39),
    (178, 7, '特殊人群用药',   'MENU', '/cdss/population',      'cdss:population',      'Avatar',      40),
    (179, 7, '给药途径与溶媒', 'MENU', '/cdss/route',           'cdss:route',           'Connection',  41)
on conflict (id) do nothing;

-- 174 过敏史人工核对：GET /migration/worklist、POST /migration/patients/{id}/review、GET /rules
--                     全部是 hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')。
-- 175 患者过敏档：GET /patients/{id}、POST /patients/{id}/allergies、/revoke、POST /preview、GET /rules
--                 同样是这四个角色。护士要能登记过敏史，必须给 NURSE。
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'PHARMACIST', 'DOCTOR_OUTP', 'NURSE') and m.id in (174, 175)
on conflict do nothing;

-- 176 过敏规则维护：**只给 ADMIN 与 PHARMACIST**。
-- 页面上的目录/映射/交叉族全部维护动作是 hasAnyRole('ADMIN','PHARMACIST')，
-- 且该页还要读 GET /allergy/gate-log——那一条同样只开给 ADMIN/PHARMACIST。
-- 给 DOCTOR_OUTP/NURSE 会让他们一进页面就吃台账的 403。
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'PHARMACIST') and m.id = 176
on conflict do nothing;

-- 177 重复用药规则：DuplicateRxController 的读端点（/config、/check、/alerts、/categories）
-- 一律 hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST')；写端点（/drug-generic、/drug-category、
-- POST /categories）收紧到 ADMIN/PHARMACIST。**全控制器不含 NURSE**。
-- 179 给药途径与溶媒：RouteRuleController 类级就是 hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST')，
-- 同样不含 NURSE。
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'DOCTOR_OUTP', 'PHARMACIST') and m.id in (177, 179)
on conflict do nothing;

-- 178 特殊人群用药：这一页的角色集**在控制器内部就不统一**，逐条列出（PopulationRuleController）：
--     POST /status、/status/{id}/revoke      ADMIN DOCTOR_OUTP NURSE
--     GET  /status、/status/history          ADMIN DOCTOR_OUTP NURSE PHARMACIST
--     POST /evaluate、GET /gate              ADMIN DOCTOR_OUTP NURSE PHARMACIST
--     GET  /rules                            ADMIN DOCTOR_OUTP PHARMACIST            ← 无 NURSE
--     GET  /alerts                           ADMIN DOCTOR_OUTP PHARMACIST QUALITY    ← 无 NURSE
-- 妊娠/哺乳状态申报是护理采集，且这是全平台唯一的申报入口，故必须给 NURSE。
-- 护士进去后「规则维护」「命中留痕」两个页签取不到数——前端已按角色不再发那两个请求，
-- 并在页签里写明「空表是因为没取数，不是因为没有规则/没有命中」（见 PopulationView.vue）。
-- QUALITY 只有 GET /alerts 一条端点可用，进去后四个页签三个空——不授，留痕由质控走别的报表口径。
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'DOCTOR_OUTP', 'PHARMACIST', 'NURSE') and m.id = 178
on conflict do nothing;

-- =====================================================================
-- 四、父级目录补授（V36 已建立的惯例）
-- =====================================================================
-- MainLayout 的 menuTree 先 filter(type==='DIR') 再挂 children：
-- 角色若持有子菜单却不持有父 DIR，这些子菜单在导航里**一条都渲染不出来**。
-- 本次新授的 10 条与补授的 168/169 全部挂在 DIR 7（门诊业务）下，
-- 这里只为「本次拿到子菜单、却还没有 7」的角色补 7 这一条，不动别的目录。
insert into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, 7 from sys_role_menu rm
where rm.menu_id in (168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179)
on conflict do nothing;

-- =====================================================================
-- 五、序列纠偏
-- =====================================================================
-- sys_menu 一路显式插 id，V144 插了 168/169 却漏了这一步（V136/V138/V142 都做了），
-- 于是 sys_menu_id_seq 至今停在 112。后续任何走 nextval 的建菜单累计到 168 就会撞主键——
-- 正是 V128 踩过的那个坑。这里一次补齐（幂等、无副作用）。
select setval('sys_menu_id_seq', (select max(id) from sys_menu));
