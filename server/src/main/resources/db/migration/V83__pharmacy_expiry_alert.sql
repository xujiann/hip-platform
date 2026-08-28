-- 1.2.13 车道A 药事全链②：效期预警
-- 近效期为「只读估算口径」（详见 InventoryService.expiryWarnings 注释）：不新增批次在库量表，
-- 也不改动发药/退药的扣减路径（那分散在 outpatient/inpatient 多处直接扣 md_drug.stock，
-- 引入精确批次追溯需跨模块大改，风险高、收益低）。故仅登记开关与阈值配置。
-- 每日巡检开提醒复用 ops_fault_ticket 落点（与 OpsHealthScheduler 同一模式）。

insert into sys_config (cfg_key, cfg_value, remark) values
    ('inv_expiry_alert_enabled', '1',  '效期预警每日巡检开关：1 开 / 0 关'),
    ('inv_expiry_warn_days',     '90', '近效期预警天数阈值（默认 90 天内到期即预警，估算在库量>0 才报）')
on conflict (cfg_key) do nothing;

-- 菜单：近效期预警页，挂「基础数据」(id=13) 下，限 ADMIN/PHARMACIST
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (66, 13, '近效期预警', 'MENU', '/masterdata/expiry-warning', 'md:expiry', 'AlarmClock', 6);
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'PHARMACIST') and m.id = 66;
