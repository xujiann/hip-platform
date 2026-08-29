-- v31 交付一致性：退费审批菜单入口 + 前端 BUTTON 权限种子
-- 二者都是 v1.3.0 落地后"前端已用、后端没种"的缺口。

-- 1) 退费审批台菜单（挂门诊业务 parent=7，仅管理员）——此前只有 ChargeView 一个临时按钮，
--    ADMIN 只能手输 URL。路由 outpatient/refund-approval 已存在。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (91, 7, '退费审批台', 'MENU', '/outpatient/refund-approval', 'outp:refund:approve', 'Checked', 20);
insert into sys_role_menu (role_id, menu_id) values (1, 91);   -- ADMIN

-- 2) BUTTON 级权限种子（v1.3.0 前端 hasPerm 已引用这 6 个 perm，但 sys_menu 无 BUTTON 行，
--    致 hasPerm 走"未启用即恒放行"的安全降级——权限基建休眠。种上 BUTTON 行让它真生效。
--    perm 命名 module:resource:action，与前端 hasPerm(...) 参数逐一对齐。
--    授权：按各操作的正当角色给（收费/退费→CASHIER，发药/退药→PHARMACIST，停用用户→ADMIN）。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (92, 11, '收费结算按钮',  'BUTTON', null, 'outp:charge:settle',    null, 100),
    (93, 11, '退费按钮',      'BUTTON', null, 'outp:charge:refund',    null, 101),
    (94, 11, '扫码作废按钮',  'BUTTON', null, 'outp:pay:cancel',       null, 102),
    (95, 12, '发药按钮',      'BUTTON', null, 'outp:pharmacy:dispense',null, 103),
    (96, 12, '退药按钮',      'BUTTON', null, 'outp:pharmacy:return',  null, 104),
    (97, 8,  '用户停用按钮',  'BUTTON', null, 'sys:user:toggle',       null, 105);

-- 授权（BUTTON 只授给该操作的正当角色；ADMIN 全授，保持全能）
insert into sys_role_menu (role_id, menu_id) values
    (1, 92), (1, 93), (1, 94), (1, 95), (1, 96), (1, 97),   -- ADMIN 全部
    (3, 92), (3, 93), (3, 94),                              -- CASHIER：收费/退费/扫码作废
    (4, 95), (4, 96);                                       -- PHARMACIST：发药/退药
-- sys:user:toggle(97) 仅 ADMIN——用户停用是系统管理动作。
