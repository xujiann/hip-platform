-- 医保完善批次三：目录生效日期 + 每日自动对账开关
alter table yb_catalog_map add column effective_date date not null default current_date;

insert into sys_config (cfg_key, cfg_value, remark) values
    ('yb_auto_recon_enabled', '1', '医保每日自动对账（01:30 跑前一日，差异>0 自动开运维工单；0=停用）');
