-- 退费审批链（v30）：当前退费直接执行、无审批、无大额阈值。
-- 大额退费是常见舞弊/差错点，须留一道审批闸。设计为**不侵入现有 refund 抢占逻辑**——
-- 超阈值退费先申请、审批通过后才放行执行；未超阈值维持原来直接退（向后兼容）。

create table outp_refund_approval (
    id            bigserial primary key,
    charge_id     bigint not null,
    charge_no     varchar(32) not null,
    amount        numeric(12,2) not null,          -- 申请退费金额（=结算单金额）
    reason        varchar(200),                    -- 退费原因（大额退费须说明）
    status        varchar(16) not null default 'PENDING',  -- PENDING/APPROVED/REJECTED/EXECUTED
    applied_by    bigint,                          -- 申请人（收费员）
    applied_at    timestamptz not null default now(),
    approved_by   bigint,                          -- 审批人
    approved_at   timestamptz,
    approve_note  varchar(200)                     -- 审批意见
);
-- 一张结算单同时只应有一个未决审批：防重复申请。已 REJECTED/EXECUTED 的历史不占坑。
create unique index uq_refund_approval_pending
    on outp_refund_approval (charge_id) where status = 'PENDING';
create index idx_refund_approval_status on outp_refund_approval (status, applied_at);

-- 大额退费阈值（元）：0 表示不设审批闸（全部直接退，等于旧行为）。默认 500 元。
-- 各院按现金管理制度调整；改此值即时生效（ConfigReader 无缓存毒，见方法论⑤走 evict）。
insert into sys_config (cfg_key, cfg_value, remark)
values ('refund_approval_threshold', '500', '大额退费审批阈值（元），0=不审批')
on conflict (cfg_key) do nothing;
