-- 1.0.9 回归修复（docs/代码审阅-20260816.md C 组）

-- C-6：患者端锁定键由「患者号」改为「患者号@来源IP」——只按患者号锁，
-- 任何人遍历顺序号段各错 5 次即可把全院患者锁在门外
alter table portal_login_attempt alter column patient_no type varchar(96);

-- C-2：支付实付与应结不符时的终态（不可重复消费），配套人工差错单
-- pay_order.status 为 varchar(16)，'MISMATCH' 放得下，此处仅登记语义
comment on column pay_order.status is 'PENDING/SUCCESS/CANCELLED/MISMATCH（实付与应结不符，已挂起转人工）';

-- C-5：日结按「当日收费的 PAID ∪ 当日退费的 REFUNDED」归集，退费日期列需要索引支撑
create index if not exists idx_outp_charge_refunded_at on outp_charge(refunded_at)
    where refunded_at is not null;
