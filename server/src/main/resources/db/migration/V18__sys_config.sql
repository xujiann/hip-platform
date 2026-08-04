-- 十八期：机构参数化（通用平台：院名等机构信息全部走配置，不再硬编码）

create table sys_config (
    cfg_key    varchar(64)  primary key,
    cfg_value  varchar(255) not null,
    remark     varchar(255),
    updated_at timestamptz  not null default now()
);

insert into sys_config (cfg_key, cfg_value, remark) values
    ('hospital_name',  '示范医院',         '医院全称（登录页/单据抬头）'),
    ('hospital_short', 'HIP 示范医院',     '医院简称'),
    ('receipt_title',  '示范医院收费专用', '票据抬头'),
    ('contact_phone',  '0000-0000000',     '咨询电话');
