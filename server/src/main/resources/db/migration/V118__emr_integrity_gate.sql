-- v35 EMR：出院/归档病历完整性 gate（EmrIntegrityService 出缺项清单，试点期可配 off|warn|block）。
-- 默认 warn：discharge 静默放行（缺项由只读预检端点暴露）、archive 返回 R.ok(warning) 放行，运行时零打断。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('emr.gate.discharge',            'warn', '出院结算病历不完整：off 旁路 / warn 警告放行 / block 硬拦(9124)'),
    ('emr.gate.archive',              'warn', '病案归档病历不完整：off / warn 返回警告放行 / block 硬拦(9820)'),
    ('emr.integrity.min_progress_notes', '1', '病历完整性：病程记录最少条数（PROGRESS/ROUND/FIRST_PROGRESS 合计）')
on conflict (cfg_key) do nothing;
