-- 1.1.3 账务与口径闭环（docs/代码审阅-20260817.md 方向 B）

-- B-2：退费操作员——甲窗口收的钱乙窗口退，此前账挂在收款员甲头上，
-- 「异常交款核查」要查的正是这类差异，却因表上没有退费操作员列而查不出来
alter table outp_charge add column if not exists refund_by bigint references sys_user(id);

-- B-5：住院结算冲销（出院召回）。此前住院侧无任何退费/冲正路径，
-- 结算错误只能改库，且住院医保年度起付线/统筹额度永不回退（reverse 全仓仅门诊一个调用点）
alter table inp_settlement add column if not exists status varchar(16) not null default 'PAID';
alter table inp_settlement add column if not exists refunded_at timestamptz;
alter table inp_settlement add column if not exists refund_by bigint references sys_user(id);
do $$ begin
    alter table inp_settlement add constraint ck_inp_settlement_status check (status in ('PAID', 'CANCELLED'));
exception when duplicate_object then null; end $$;

-- 冲销后须允许重新结算：全列唯一约束换成「非作废单唯一」的部分索引
alter table inp_settlement drop constraint if exists inp_settlement_admission_id_key;
create unique index if not exists uq_inp_settlement_active on inp_settlement(admission_id)
    where status <> 'CANCELLED';

-- B-4 配套：账务查询改半开区间后这些索引才真正被用上
create index if not exists idx_inp_settlement_created on inp_settlement(created_at);
create index if not exists idx_outp_charge_refunded on outp_charge(refunded_at) where refunded_at is not null;
