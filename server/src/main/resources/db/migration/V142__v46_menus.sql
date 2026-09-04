-- v46 合版：手术麻醉两个新页面的菜单与授权。
--
-- 为什么单独一个迁移：v46 三条车道（K 手术地基 / L 术中记录 / M 质控统计）并行开发，
-- 各自插 sys_menu 必然抢同一批 id 撞主键——三条车道都刻意没插，由合版统一登记。
-- 这与 v42「菜单 id 由合版统一加」是同一条纪律。
--
-- 菜单 id 111/112 续 110（v44 处方模板）。父级取住院业务目录，与既有「手术麻醉」页同组。

insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no)
select 111, m.parent_id, '术中记录', 'MENU', '/inpatient/surgery-intraop', 'surg:intraop', 'Edit',
       coalesce(m.sort_no, 0) + 1
from sys_menu m where m.path = '/surgery' limit 1;

insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no)
select 112, m.parent_id, '麻醉质控', 'MENU', '/anes-qc', 'anes:qc', 'DataAnalysis',
       coalesce(m.sort_no, 0) + 2
from sys_menu m where m.path = '/surgery' limit 1;

-- 授权：术中记录给手术室护士与医师，麻醉质控给管理侧
insert into sys_role_menu (role_id, menu_id)
select r.id, 111 from sys_role r where r.code in ('ADMIN', 'NURSE', 'DOCTOR_OUTP')
on conflict do nothing;

insert into sys_role_menu (role_id, menu_id)
select r.id, 112 from sys_role r where r.code in ('ADMIN', 'QUALITY')
on conflict do nothing;

-- 显式插自增主键后必须纠偏，否则后续走 nextval 的建菜单会撞主键（V128 的教训）
select setval('sys_menu_id_seq', (select max(id) from sys_menu));

-- ===== 合版补：首台准点阈值（车道M 已登记进配置手册但迁移里没 seed）=====
--
-- 键不存在时端点会回落代码默认值 30，功能不受影响；但"配置手册列了、库里查不到"会让
-- 实施人员以为漏配。同 v45 车道J 的 emr.copy.cross_patient——**新键必须同时落
-- 迁移 seed 与配置手册两处**，只做一处就是文档与实现对不上。
insert into sys_config (cfg_key, cfg_value, remark)
values ('anes.qc.ontime_minutes', '30',
        '首台手术准点开台阈值（分钟）：实际开台不晚于计划开台+该阈值即算准点。麻醉质控 1424★ 口径')
on conflict (cfg_key) do nothing;
