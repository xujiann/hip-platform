-- v54 合版：把三条前端车道（W1 病历版本留痕 / W2 上级审签 / W3 病历时限质控）
-- 的四个页面登记进导航，并逐条授权。
--
-- 【为什么由合版统一登记】沿用 V142 / V144 / V156 的纪律：并行车道各自插 sys_menu 必然抢
-- 同一批 id 撞主键，故车道只交前端文件，菜单 id 由合版分配。
-- 实测合版前 sys_menu 最大 id = 179（V156 显式插到 179），本文件占用 180–183。
--
-- 【本文件只 insert，不 update 任何既有行】既有菜单的 name/path/perm/sort_no 一个字段都不改。
-- 因此新菜单只能顺排在各自父级已用 sort_no 之后（见每段说明），不做重排。
--
-- 【角色码逐条读自控制器类级 @PreAuthorize，不是照抄车道自述】三处出处：
--   EmrVersionController.java:47      hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')
--   CountersignController.java:31     hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')
--   EmrTimelinessController.java:50   hasAnyRole('ADMIN','QUALITY','OPERATION','DOCTOR_OUTP','NURSE')
-- 授宽了会出现「菜单点得进、接口 403」（v42 的教训），授窄了功能等于没交付（v52 的教训）。
--
-- 【方法级收紧不缩小菜单授权范围】三个控制器里另有四处方法级 @PreAuthorize：
--   CountersignController:52    POST /records/{recordId}   收紧到 ADMIN/DOCTOR_OUTP（QUALITY 可查不可代签）
--   EmrTimelinessController:96  GET  /anchors              收紧到 ADMIN/QUALITY/OPERATION
--   EmrTimelinessController:234 PUT  /rules/{code}         收紧到 ADMIN/QUALITY
-- 这些是**页内单个动作**的收紧，不是页面级边界：前端已按 /auth/me 的 roles 把对应按钮/页签
-- 置灰或隐藏（CountersignWorkbenchView 的审签按钮、TimelinessView 的「锚点体检」页签），
-- 故菜单仍按类级角色集授权，不按最窄的那一条收。

-- =====================================================================
-- 一、W1 病历版本留痕（父级 25「数据中心」）
-- =====================================================================
-- 归口理由：与 54 病案统计 / 63 病案首页 / 90 病案复印 / 106 病案终末质控 / 109 病历模板
-- 同属病案-质控口径，父级 25 已聚了这一组。
--
-- 路径**刻意不放成 /outpatient/emr-version**：DIR 7「门诊业务」的 path 是 /outpatient，
-- router 守卫按前缀放行（to.path === p || to.path.startsWith(p + '/')），
-- 挂在 /outpatient 下会让持有 DIR 7 的 NURSE/PHARMACIST 也能点进来，然后一片 403。
-- 父级 25 的 path 是 /cdr，与本页 path 无前缀关系——这与同父级的 /mr-qc、/mrstats、
-- /emr-copy、/emr-template 一致，父级 DIR 只承载导航层级，不承载放行前缀。
--
-- sort_no：父级 25 已用 1–13（9 号重号：57 护理院感精细 与 63 病案首页），顺排 14。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (180, 25, '病历版本留痕', 'MENU', '/emr-version', 'emr:version', 'Clock', 14)
on conflict (id) do nothing;

insert into sys_role_menu (role_id, menu_id)
select r.id, 180 from sys_role r where r.code in ('ADMIN', 'DOCTOR_OUTP', 'QUALITY')
on conflict do nothing;

-- =====================================================================
-- 二、W2 上级审签两页（父级 18「住院业务」）
-- =====================================================================
-- 两条都插，不是只插父页面。
--   · 「上级审签工作台」是日常入口（待审签列表 + 审签动作 + 审签历史）；
--   · 「出院前审签检查」是出院办理前按次住院的核对页，是另一个岗位、另一个时点的动作，
--     且支持 ?admissionId= 直达——它需要自己的导航入口，不是工作台的钻取子页。
-- 机械断言层面两条都成立：ReachabilityTest §2 的 coveredByMenu 是「逐字相等 or 子路径」，
-- 只插工作台一条就已足够盖住 /inpatient/countersign/check；§3 反向要求每条菜单 path
-- 都有对应路由，/inpatient/countersign/check 在 router/index.ts 里有（v54 W2 新增），
-- 故多插这一条同样绿。
--
-- sort_no：父级 18 已用 1–7、9、40、41（6 号重号：40 手术麻醉 与 105 护理文书），顺排 42/43。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (181, 18, '上级审签工作台', 'MENU', '/inpatient/countersign',       'emr:countersign',       'Stamp',           42),
    (182, 18, '出院前审签检查', 'MENU', '/inpatient/countersign/check', 'emr:countersign:check', 'DocumentChecked', 43)
on conflict (id) do nothing;

-- QUALITY 照授：本页用到的读端点（GET /pending、/check、/records/{id}、/config）全在类级
-- 角色集内，QUALITY 进得去也取得到数；只有 POST /records/{recordId} 对它 403，
-- 而前端已按 roles 把「审签」按钮置灰并写明「QUALITY 可查不可代签」——
-- 不会出现「菜单点得进、页面一片 403」。
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'DOCTOR_OUTP', 'QUALITY') and m.id in (181, 182)
on conflict do nothing;

-- =====================================================================
-- 三、W3 病历时限质控（父级 18「住院业务」）
-- =====================================================================
-- 五个角色逐字取自 EmrTimelinessController:50 的类级 @PreAuthorize。
-- NURSE 与 DOCTOR_OUTP 拿到的是「超时率统计 / 超时清单 / 即将超时提醒 / 规则维护（只读）」，
-- 「锚点体检」页签因 GET /anchors 收紧到 ADMIN/QUALITY/OPERATION 而由前端按 roles 隐藏；
-- 「规则维护」页的保存动作因 PUT /rules/{code} 收紧到 ADMIN/QUALITY 而对其余角色置灰。
-- 两处都是页内收紧，页面本身不空——故照类级角色集授满，不缩到 ADMIN/QUALITY。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (183, 18, '病历时限质控', 'MENU', '/inpatient/timeliness', 'emr:timeliness', 'AlarmClock', 44)
on conflict (id) do nothing;

insert into sys_role_menu (role_id, menu_id)
select r.id, 183 from sys_role r
where r.code in ('ADMIN', 'QUALITY', 'OPERATION', 'DOCTOR_OUTP', 'NURSE')
on conflict do nothing;

-- =====================================================================
-- 四、父级目录补授（V36 建立、V156 第四节沿用的惯例）
-- =====================================================================
-- MainLayout 的 menuTree 先 filter(type === 'DIR') 再挂 children：
-- 角色若持有子菜单却不持有父 DIR，这些子菜单在导航里**一条都渲染不出来**。
-- 且 AuthController.me 只在「还有可见子菜单」时才下发 DIR 行，所以补授 DIR 不会
-- 给任何角色多开一个空目录。
--
-- 逐父级补，不合并成一句：180 在 DIR 25 下，181–183 在 DIR 18 下，
-- 合并会把「只拿到 /emr-version 的角色」也授上 DIR 18。
insert into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, 25 from sys_role_menu rm where rm.menu_id = 180
on conflict do nothing;

insert into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, 18 from sys_role_menu rm where rm.menu_id in (181, 182, 183)
on conflict do nothing;

-- =====================================================================
-- 五、序列纠偏
-- =====================================================================
-- sys_menu 一路显式插 id。显式插了却不推进 sys_menu_id_seq，后续任何走 nextval 的
-- 建菜单累计上来就会撞主键——正是 V128 踩过、V156 第五节补过的那个坑。
-- 幂等、无副作用，每份插显式 id 的迁移都要带这一句。
select setval('sys_menu_id_seq', (select max(id) from sys_menu));
