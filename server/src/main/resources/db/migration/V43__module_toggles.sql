-- 产品化一期：模块级功能开关（'1'=启用；注册表见 platform/core ModuleGate）
-- 菜单过滤 + API 前缀 404 双路生效；医院未采购的模块实施时置 0 即整体关闭。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('module.drg.enabled',       '1', '模块开关：DRG 分析'),
    ('module.cdss.enabled',      '1', '模块开关：CDSS 提醒'),
    ('module.insurance.enabled', '1', '模块开关：医保管理'),
    ('module.blood.enabled',     '1', '模块开关：用血管理'),
    ('module.hr.enabled',        '1', '模块开关：人事管理'),
    ('module.surgery.enabled',   '1', '模块开关：手术麻醉');
