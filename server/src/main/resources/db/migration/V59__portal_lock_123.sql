-- 1.2.3 五轮 P1-4：门户防爆破双层锁
--
-- ① 列宽：键为「患者号@IP」，IPv6 来源（最长 45 字符）加患者号必超 varchar(32)——
--    此前 IPv6/超长 XFF 客户端的失败计数一直插不进去（4091），防爆破对其完全失效
alter table portal_login_attempt alter column patient_no type varchar(128);

-- ② 粗锁行复用本表：键 = 患者号@ANY（跨 IP 聚合计数），无需新表
