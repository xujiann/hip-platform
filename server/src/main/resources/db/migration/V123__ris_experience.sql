-- v38 RIS 体验轮：到检登记态 + 检查结果互认提醒阈值。
-- 状态机扩 REGISTERED→ARRIVED→REPORTED→VERIFIED（status varchar(16) 无 CHECK，扩值无需改约束）。
alter table ris_exam add column arrived_at timestamptz;

-- 结果互认：开单/登记时提示"该患者 N 天内已做同名检查"（只提示不阻断，控费/医保飞检项）
insert into sys_config (cfg_key, cfg_value, remark) values
    ('ris.mutual.days', '30', '检查结果互认提醒窗口（天）：同患者同名检查 N 天内已出报告则提示复用（只提示不阻断）')
on conflict (cfg_key) do nothing;
