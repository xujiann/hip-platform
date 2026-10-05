-- v78 合版：标本类型字典维护页登记进导航并授权（1016★ 复核仍开项"标本类型自由文本、未字典化"的收口入口）。
--
-- 【为什么由合版统一登记】沿用 V173 / V175 的纪律：车道只交前端文件与控制器，菜单 id 由合版分配。
-- 实测合版前 sys_menu 最大 id = 185（V175 占 185），本文件占用 186；187–189 仍为预留、本文件不用。
--
-- 【本文件只 insert，不 update 任何既有行】父级 13「基础数据」已用 sort_no 1–6、20、60–62，顺排 63。
--
-- 【角色码读自控制器写方法 @PreAuthorize】SpecimenTypeController 写方法 hasAnyRole('ADMIN','TECHNICIAN')：
-- 检验科维护自己的标本字典，与 185「检验流向规则」同口径；父级 13 的持有者本就含 TECHNICIAN。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (186, 13, '标本类型', 'MENU', '/masterdata/specimen-types', 'md:specimen', 'Collection', 63)
on conflict (id) do nothing;
insert into sys_role_menu (role_id, menu_id)
select r.id, 186 from sys_role r where r.code in ('ADMIN', 'TECHNICIAN')
on conflict do nothing;
