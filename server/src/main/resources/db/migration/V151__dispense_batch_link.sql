-- v50 收敛：发药批次分配落库位。
--
-- ============ 为什么必须有这张表 ============
-- 车道 A 建好了批次账（pharm_batch / pharm_stock / pharm_stock_flow），也备好了两个接缝
-- （consumeForDispense 按 FEFO 取批扣减、restoreToBatch 回补），但**发药链路没有接上**，
-- 于是 V50PharmacyTest.everyStockMutatingPathWritesTheBatchLedger 实测：
-- 发药 9 盒后总账 md_drug.stock=91、批次子账合计=100，**漂移 9 盒**。
--
-- 光把 consumeForDispense 接进去还不够，还缺一个**存储位**：
-- 退药要回补到**原来发的那个批次**，而一次发药可能跨多个批次（FEFO 取批会拆）。
-- 事后按 FEFO 猜一个批次回补，总数仍然对得上，但**批次追溯从此是错的且看不出来**——
-- 药品召回时按错批次去找，会找不到该找的那盒、也会下架不该下架的。
-- 所以发药时分配到哪几个批次、各多少，必须当场记下来。
--
-- ============ 为什么不复用 inv_transaction ============
-- inv_transaction（V7）是**药品级**流水，只有 drug_id 没有 batch_id——
-- 那正是本版要补的缺口本身（v50 车道 A 实测更正了主控最初"库存侧一片空白"的错误判断：
-- inv_stock_in 自 V7 就有 batch_no/expire_date，真正缺的是批次维度的余额）。
-- 在它上面加列会改动一张被 InventoryService（382 行）与多个既有测试依赖的表，
-- 且语义是"这一笔发药拆给了哪几个批次"的**一对多明细**，本就该独立成表。

create table pharm_dispense_batch (
    id           bigserial primary key,
    order_id     bigint      not null references outp_order (id),
    drug_id      bigint      not null references md_drug (id),
    batch_id     bigint      not null references pharm_batch (id),
    location_code varchar(32) not null,
    qty          int         not null check (qty > 0),
    -- 已退回数量。退药可能分次退（开了 3 盒退 1 盒），故不是布尔位。
    returned_qty int         not null default 0 check (returned_qty >= 0),
    created_at   timestamptz not null default now(),
    -- 退不能超过发：这条约束是"退药回补不得凭空造药"这条不变式的数据库兜底，
    -- 应用层已挡一道，DB 再挡一道——账目类约束值得写两遍。
    constraint chk_pharm_disp_batch_ret check (returned_qty <= qty)
);

comment on table pharm_dispense_batch is
    'v50：一次发药按 FEFO 拆到各批次的明细。退药据此回补原批次，不猜。';
comment on column pharm_dispense_batch.returned_qty is
    '已退回数量。分次退药时累加；chk_pharm_disp_batch_ret 保证退不超发。';

create index idx_pharm_disp_batch_order on pharm_dispense_batch (order_id);
create index idx_pharm_disp_batch_batch on pharm_dispense_batch (batch_id);

-- ============ 零回填 ============
-- 本迁移**不含任何 update / insert**。历史发药记录没有批次分配明细——那就是事实，
-- 当时批次账还不存在。最诱人的歧路是"按 FEFO 给历史订单补一份分配"，
-- 那等于凭空指认"这盒药是那个批次的"，而批号要对得上药监追溯与院内召回两条线。
-- 宁可历史订单退药时回落旧口径（只回补汇总），也不可假算。
