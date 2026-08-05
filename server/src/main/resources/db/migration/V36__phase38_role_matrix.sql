-- 三十八期：试点就绪——多角色菜单矩阵（此前功能菜单只绑 ADMIN，无法多角色试点）

insert into sys_role (name, code, remark) values
    ('护士',     'NURSE',      '门诊/住院护理与院感上报'),
    ('医技技师', 'TECHNICIAN', '检验/检查/专科执行'),
    ('质控院感', 'QUALITY',    '质控、院感、病案与数据治理'),
    ('运营后勤', 'OPERATION',  '资产、物资、人事与药事分析');

-- 叶子菜单矩阵（按 path 归属；ADMIN 已有全量）
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'DOCTOR_OUTP' and m.path in
    ('/outpatient/doctor', '/outpatient/queue', '/cdss', '/specialty', '/patient/registry', '/cdr/patient360')
  and not exists (select 1 from sys_role_menu x where x.role_id = r.id and x.menu_id = m.id);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'NURSE' and m.path in
    ('/outpatient/nurse', '/outpatient/triage', '/inpatient/nurse', '/inpatient/board',
     '/m', '/nursing-plus', '/inpatient/blood', '/patient/registry');

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'CASHIER' and m.path in ('/outpatient/charge', '/pay', '/reports/daily')
  and not exists (select 1 from sys_role_menu x where x.role_id = r.id and x.menu_id = m.id);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'PHARMACIST' and m.path in
    ('/outpatient/pharmacy', '/outpatient/review', '/masterdata/drugs', '/masterdata/inventory', '/drug-analysis')
  and not exists (select 1 from sys_role_menu x where x.role_id = r.id and x.menu_id = m.id);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'TECHNICIAN' and m.path in
    ('/lis', '/ris', '/outpatient/exec', '/medtech/appointments', '/specialty');

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'QUALITY' and m.path in
    ('/quality', '/datagov', '/quality/single-disease', '/mrstats', '/mgmt',
     '/nursing-plus', '/infection-plus', '/drg', '/datagov/standards', '/patientcare');

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'OPERATION' and m.path in
    ('/hrp/assets', '/hrp/ops', '/reports/daily', '/hrp/equipment', '/hr', '/drug-analysis', '/misc');

-- 补父级目录（有子菜单的角色自动获得父 DIR，否则前端树渲染不出）
insert into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, p.id
from sys_role_menu rm
join sys_menu m on m.id = rm.menu_id
join sys_menu p on p.id = m.parent_id
where not exists (select 1 from sys_role_menu x where x.role_id = rm.role_id and x.menu_id = p.id);
