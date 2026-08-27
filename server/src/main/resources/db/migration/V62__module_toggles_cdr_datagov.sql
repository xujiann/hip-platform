-- v27-B 模块开关扩表：cdr / datagov（注册表见 platform/core ModuleGate）
-- 两者均为独占 API 前缀、无跨模块页面调用，经侦察确认可安全整段开关。
-- 缺省'1'=启用——ModuleGate 对缺省本就视为启用，插种子是为了让配置管理页可见可关。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('module.cdr.enabled',     '1', '模块开关：临床数据中心（患者360）'),
    ('module.datagov.enabled', '1', '模块开关：数据治理与上报')
on conflict (cfg_key) do nothing;
