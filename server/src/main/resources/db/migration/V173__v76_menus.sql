-- v76 合版：诊断字典维护页登记进导航并授权（993★ ①「诊断字典独立浏览与在线维护界面」）。
--
-- 【为什么由合版统一登记】沿用 V142 / V144 / V156 / V162 的纪律：车道只交前端文件与控制器，
-- 菜单 id 由合版分配，避免并行车道抢同一批 id 撞主键。
-- 实测合版前 sys_menu 最大 id = 183（V162 占到 183），本文件占用 184；185–189 为 v76 预留、本文件不用。
--
-- 【本文件只 insert，不 update 任何既有行】父级 13「基础数据」已用 sort_no 1–6、20、60，顺排 61。
--
-- 【角色码读自控制器类级 @PreAuthorize】IcdDictController 照 MasterDataController 的主数据维护口径
-- hasRole('ADMIN')（收费项目页 15 亦只授 ADMIN）。父级 13 的持有者有 ADMIN / PHARMACIST / TECHNICIAN，
-- 后两者点不到本菜单（未授权即不渲染），路由守卫按 /masterdata 前缀放行但接口 403 的情形只在
-- 直接敲 URL 时出现，与收费项目页现状一致。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (184, 13, '诊断字典', 'MENU', '/masterdata/icd-dict', 'md:icd', 'Notebook', 61)
on conflict (id) do nothing;
insert into sys_role_menu (role_id, menu_id)
select r.id, 184 from sys_role r where r.code in ('ADMIN')
on conflict do nothing;
