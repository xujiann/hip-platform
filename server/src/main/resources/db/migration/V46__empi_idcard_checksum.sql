-- 1.0.3：身份证校验位校验开关（0=关闭，供存量迁移含历史假号的医院）
insert into sys_config (cfg_key, cfg_value, remark) values
    ('empi_idcard_checksum', '1', '建档/修改时校验身份证校验位（GB 11643 加权模 11）');
