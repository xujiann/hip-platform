-- 1.0.5：健康告警定时评估开关（每小时巡检，异常自动开故障工单）
insert into sys_config (cfg_key, cfg_value, remark) values
    ('ops_auto_health_enabled', '1', '每小时自动巡检（备份时效/慢接口/磁盘），异常自动开故障工单（0=关闭）');
