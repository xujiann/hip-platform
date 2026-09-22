-- v72 包 D（664★）：指标快照按周期自动生成的开关键。
--
-- 此前快照只能由人点「生成当日快照」触发，偏离表如实写着「按周期自动采集与自动计算尚未提供」。
-- 本迁移只种一个开关键，**不改任何指标口径、不动 dg_metric_def、不回填任何历史快照**。
--
-- 键名以 _enabled 结尾：sys_config 的保存即校验会自动把它落进 0/1 分支（已查证 validate()），
-- 不必为它改校验规则。默认 1（开），与 cdr_auto_sync_enabled / ops_auto_health_enabled 同口径。
insert into sys_config(cfg_key, cfg_value, remark) values
    ('dg_metric_auto_snapshot_enabled', '1', '每日 02:20 自动生成当日指标快照（0=关闭，仍可页面手工生成）')
on conflict (cfg_key) do nothing;
