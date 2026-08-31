-- v39 住院医嘱长期/临时模型（隔离末版）。
-- 关键口径设计：LONG 医嘱 amount = 累计已执行金额、执行过即 EXECUTED——出院汇总/医保上传/DRG/押金
-- 等下游读 "EXECUTED 的 amount" 的既有口径零改动天然正确。存量与新开缺省 TEMP，行为逐字节不变。
-- 唯一真冲突：中间结算打标后 LONG 继续执行会让已结算单金额漂移——认领 SQL 排除未停嘱 LONG（见 service）。
alter table inp_order add column order_nature   varchar(8) not null default 'TEMP';   -- TEMP 临时 / LONG 长期
alter table inp_order add column stop_at        timestamptz;                           -- 停嘱时刻（仅 LONG）
alter table inp_order add column stop_doctor_id bigint references sys_user (id);

-- 长期医嘱每日执行行：按频次逐日展开（qd=1/bid=2/tid=3/qid=4 行/日），护士按行执行、按行计费
create table inp_order_exec (
    id          bigserial primary key,
    order_id    bigint        not null references inp_order (id),
    exec_date   date          not null,
    seq_no      int           not null default 1,             -- 当日第几次
    status      varchar(8)    not null default 'PENDING',     -- PENDING / DONE / SKIPPED
    amount      numeric(12,2) not null,                       -- 本次执行金额（unit_price × qty）
    executor_id bigint references sys_user (id),
    executed_at timestamptz,
    created_at  timestamptz   not null default now(),
    constraint ck_exec_status check (status in ('PENDING', 'DONE', 'SKIPPED')),
    constraint uq_order_exec unique (order_id, exec_date, seq_no)
);
create index idx_exec_pending on inp_order_exec (exec_date, status) where status = 'PENDING';
