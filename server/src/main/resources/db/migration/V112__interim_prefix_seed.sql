-- v32 交付一致性：住院中间结算单号前缀补种子行。
-- InpatientService.configReader.get("billno_prefix_interim", "ZJ") 读取此键，但 V90 引入中间结算时
-- 漏了随迁移插入默认值（违反"新键必须随迁移插种子"纪律）——代码默认 'ZJ' 兜底可用，但经管理页
-- PUT /api/config/billno_prefix_interim 改它时 SysConfigController 走 update where cfg_key=?，
-- 行不存在则影响 0 行返回 1401「配置项不存在」，院方无法定制前缀。补种子后恢复可改。
insert into sys_config (cfg_key, cfg_value, remark)
values ('billno_prefix_interim', 'ZJ', '住院中间结算单号前缀')
on conflict (cfg_key) do nothing;
