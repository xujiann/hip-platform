-- v77 合版：检验流向规则维护页登记进导航并授权（1016★「根据流向自动获取执行科室」的规则配置入口）。
--
-- 【为什么由合版统一登记】沿用 V142 / V144 / V156 / V162 / V173 的纪律：车道只交前端文件与控制器，
-- 菜单 id 由合版分配，避免并行车道抢同一批 id 撞主键。
-- 实测合版前 sys_menu 最大 id = 184（V173 占 184），本文件占用 185；186–189 仍为预留、本文件不用。
--
-- 【本文件只 insert，不 update 任何既有行】父级 13「基础数据」已用 sort_no 1–6、20、60、61，顺排 62。
--
-- 【角色码读自控制器写方法 @PreAuthorize】LabRouteRuleController 写方法 hasAnyRole('ADMIN','TECHNICIAN')：
-- 检验科（TECHNICIAN）要能自己配自己的分流，不能每改一条都找系统管理员；父级 13 的持有者本就含 TECHNICIAN，
-- 不会出现"看得见父级点不进子项"之外的新 403 路径（V162 教训：挂到持有者不含该角色的父级下会经前缀放行后 403）。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (185, 13, '检验流向规则', 'MENU', '/masterdata/lab-route', 'md:labroute', 'Guide', 62)
on conflict (id) do nothing;
insert into sys_role_menu (role_id, menu_id)
select r.id, 185 from sys_role r where r.code in ('ADMIN', 'TECHNICIAN')
on conflict do nothing;
