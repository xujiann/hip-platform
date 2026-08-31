-- v34 EMR：病历书写时限质控阈值（纯 sys_config，emrTimeliness 只读报表，不 gate 任何写路径）。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('emr.timeliness.first_progress_hours',   '8',   '首程记录书写时限（小时）'),
    ('emr.timeliness.round_interval_hours',   '48',  '三级查房记录最长间隔（小时）'),
    ('emr.timeliness.progress_gap_days',      '3',   '病程记录相邻最长间隔（天，一般患者）'),
    ('emr.timeliness.progress_gap_crit_days', '1',   '病程记录相邻最长间隔（天，危重 care_level 特级/一级）'),
    ('emr.timeliness.rescue_record_hours',    '6',   '抢救记录超时闭合时限（小时）'),
    ('emr.timeliness.round_check.enabled',    'off', '三级查房时限核查开关：依赖 ward-round 落 ROUND 数据后置 on，否则历史在院全量误报')
on conflict (cfg_key) do nothing;
