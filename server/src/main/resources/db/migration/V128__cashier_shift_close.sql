-- v41：收费员班结缴款单（收费窗口下班必做，此前零实现——只有日结/交款核查的"系统侧账"，
-- 没有"实际点钞"这一侧，差额既无处填也无处留痕，出纳与收费员对不上时只能口头扯皮）。
--
-- 口径与 FinanceController.reconciliation 完全一致（1.1.3 定案）：
--   收款按 created_at 当日 + cashier_id 归集；退费按 refunded_at 当日 + refund_by 归集。
-- 本表只存"算出来的快照 + 收费员实点数 + 确认痕迹"，不重新定义任何统计口径。
-- 快照必须落库（而非每次现算）：确认之后再发生的倒填/冲销不应让已确认的缴款单事后变脸。

create table fin_cashier_shift (
    id            bigserial     primary key,
    cashier_id    bigint        not null references sys_user (id),
    shift_date    date          not null,
    sys_paid      numeric(12,2) not null default 0,   -- 系统收款合计（当日经手收款）
    sys_refund    numeric(12,2) not null default 0,   -- 系统退费合计（当日经手退款）
    sys_net       numeric(12,2) not null default 0,   -- 系统应收净额 = sys_paid - sys_refund
    declared_cash numeric(12,2) not null default 0,   -- 实点金额（收费员点钞后填）
    diff          numeric(12,2) not null default 0,   -- 差额 = declared_cash - sys_net（长款为正、短款为负）
    status        varchar(16)   not null default 'DRAFT',   -- DRAFT/SUBMITTED/CONFIRMED
    note          varchar(500),                       -- 差额说明（有差额时应写明原因）
    submitted_at  timestamptz,
    confirmed_by  bigint        references sys_user (id),   -- 财务确认人
    confirmed_at  timestamptz,
    created_at    timestamptz   not null default now(),
    constraint ck_shift_status check (status in ('DRAFT', 'SUBMITTED', 'CONFIRMED')),
    -- 同一收费员同一天只能有一张缴款单：把"重复提交"挡在数据库层，
    -- 提交侧用 on conflict do nothing 拿返回行数判重，读-判-写的并发窗口不存在
    constraint uq_shift_cashier_date unique (cashier_id, shift_date)
);
-- 财务视角的主查询是"某日待确认的班结"
create index idx_shift_date_status on fin_cashier_shift (shift_date desc, status);

-- ===== 收费班结菜单（门诊业务 DIR=7），授 ADMIN + 收费员 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (104, 7, '收费班结', 'MENU', '/finance/shift-close', 'fin:shift', 'Wallet', 12);
insert into sys_role_menu (role_id, menu_id)
select r.id, 104 from sys_role r where r.code in ('ADMIN', 'CASHIER');

-- 序列纠偏：V1 把 sys_menu_id_seq 停在 100，其后各期一路显式插 100..104，
-- 序列却没跟着走——将来任何走 nextval 的建菜单（管理端加自定义菜单）必撞主键。
-- 一次性推到当前最大 id，与显式 id 的种子方式互不干扰。
select setval('sys_menu_id_seq', (select max(id) from sys_menu));
