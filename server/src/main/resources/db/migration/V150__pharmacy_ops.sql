-- v50 车道 D：门诊药房日常三件事——拆零发药 / 近效期预警 / 拆零余量盘点
-- 错误码子段 5460–5519（docs/错误码分段.md 已先于编码登记：5460–5479 拆零、5480–5499 效期、5500–5519 盘点）。
--
-- =====================================================================================
-- 【开工前的现状复核：任务书里三条前提有两条与代码不符，本迁移按代码实测的现状建】
-- =====================================================================================
-- 任务书称「药品侧只有 md_drug 一张表，没有批次、没有效期、没有出入库流水；药品盘点全仓零命中」。
-- 实测（V7 / V81 / V82 / V83 + platform/masterdata/service/InventoryService.java）：
--   * `inv_stock_in` 自 V7 起就带 batch_no / expire_date，V82 又加了验收三态——**批次与效期是有的**，
--     只是批次没有「在库量」维度（入库量记了，出库不按批次记）；
--   * `inv_transaction` 自 V7 起就是出入库流水表（IN/OUT/RET/ADJ/STOCKTAKE）——**流水是有的**；
--   * `inv_stock_take` / `inv_stock_take_line`（V81）已是完整的盘点单 + 明细（账面 vs 实盘 vs 差异）
--     + 确认时条件更新调库存并写 STOCKTAKE 流水——**整包盘点已有且实现质量不差**。
-- 因此本迁移**刻意不建第二套盘点单、不建第二张流水表**：账实两套真相是本仓四次撞码同源的坑。
-- 本迁移只补真正零命中的那一块——**拆零**，以及它自己带出来的余量盘点。
--
-- =====================================================================================
-- 【为什么拆零必须有自己的余量表，而不是往 md_drug.stock 上凑】
-- =====================================================================================
-- 处方开「1 盒」发「10 片」时，盒被拆开就回不去了：24 片装的盒拆出 10 片，剩下的 14 片
-- 既不在 md_drug.stock（那是整盒数），也不在任何现有表里。没有余量表就只有两条路，都是错的：
--   ① 每次拆零扣 1 盒、余量丢弃 —— 同一盒发两次 10 片要扣 2 盒，账面凭空少一盒；
--   ② 把 stock 当成最小单位 —— 与既有发药/退药/盘点/入库四条路径的单位口径直接冲突。
-- 所以另立**最小单位**的余量池 pharm_split_stock + 余量流水 pharm_split_txn。
-- 整盒出库仍走既有路径（md_drug.stock 条件扣减 + inv_transaction 写 OUT），
-- 单位口径不混：inv_transaction 里永远是**销售单位（盒）**，pharm_split_txn 里永远是**最小单位（片）**。
-- 混单位记一张表会让 InventoryService.expiryWarnings 的 sumOutReturnQty(OUT/RET 合计) 立刻算错。
--
-- =====================================================================================
-- 【零条 update：换算系数靠维护不靠猜】
-- =====================================================================================
-- 本迁移不含任何 update / 不给新列加 default。md_drug 的 pack_size / min_unit 对存量药品**全为 NULL**，
-- 那就是事实——本版之前根本没有这个采集位。**尤其不拿 spec 文本（如「0.25g*24粒/盒」）正则出 24**：
-- 规格文本是自由录入，「10ml:0.1g*5支/盒」「24片×2板」「1g/瓶」各家写法都不同，
-- 猜错一位就是把「1 盒」当「1 片」发出去，或把 1 片当 1 盒扣。
-- 未维护换算系数的药品由服务端返 5460 拒绝拆零（见 PharmOpsService 注释：此处**刻意不设 warn 档 gate**）。

-- ---------------------------------------------------------------------------
-- ① 主数据补拆零换算属性位（只加列，不填值）
-- ---------------------------------------------------------------------------
alter table md_drug
    add column pack_size int,
    add column min_unit  varchar(8);

comment on column md_drug.pack_size is
    '拆零换算系数：一个销售单位(unit)含多少个最小单位(min_unit)，如「盒」含 24「片」即填 24。'
    'NULL = 未维护 = 该药不允许拆零（PharmOpsService 返 5460），**不得按 1 或按 spec 文本推断**';
comment on column md_drug.min_unit is
    '最小发放单位：片/粒/支/ml。NULL = 未维护 = 不允许拆零。与 pack_size 必须成对维护';

-- ---------------------------------------------------------------------------
-- ② 拆零余量池（最小单位）
-- ---------------------------------------------------------------------------
-- 一药一行，首次开包时才建行（不预置 0 行：没拆过的药不该出现在余量报表里）。
create table pharm_split_stock (
    id         bigserial   primary key,
    drug_id    bigint      not null unique references md_drug (id),
    -- 已拆盒的在架散装数量，单位 = md_drug.min_unit。允许 0（拆完了但盒已拆，行保留作审计线索）
    remain_qty int         not null default 0,
    updated_at timestamptz not null default now(),
    constraint ck_pharm_split_stock_nonneg check (remain_qty >= 0)
);

-- ---------------------------------------------------------------------------
-- ③ 拆零余量流水（最小单位）——余量的每一次变动都必须落这里
-- ---------------------------------------------------------------------------
-- 与 inv_transaction 分表的理由见文件头；两者靠 ref_no（拆零单号）互相勾稽：
-- 开包那一盒在 inv_transaction 里是一条 OUT -1（盒），在本表里是一条 OPEN +24（片），同一个 ref_no。
create table pharm_split_txn (
    id           bigserial   primary key,
    drug_id      bigint      not null references md_drug (id),
    -- OPEN 开包入池 / OUT 拆零发出 / RET 拆零退回 / TAKEADJ 拆零盘点差异调整
    type         varchar(16) not null,
    -- 正入负出，单位 = 最小单位
    qty          int         not null,
    -- 变动后余量，与 pharm_split_stock.remain_qty 同刻
    remain_after int         not null,
    -- 当次换算系数快照：主数据日后改规格（24 片装换 12 片装），历史流水仍能还原当时口径
    pack_size    int         not null,
    min_unit     varchar(8),
    -- 拆零单号 / 盘点单号
    ref_no       varchar(64),
    dispense_id  bigint,
    operator_id  bigint      references sys_user (id),
    created_at   timestamptz not null default now()
);

create index idx_pharm_split_txn_drug on pharm_split_txn (drug_id, id desc);
create index idx_pharm_split_txn_ref on pharm_split_txn (ref_no);

-- ---------------------------------------------------------------------------
-- ④ 拆零发药记录（退回的依据）
-- ---------------------------------------------------------------------------
-- registration_id / order_id 可空：拆零也用于「非处方直接发放」与病区备用药补充，
-- 强制挂处方会让这两类真实场景无处可落；挂了处方的走 order_id 便于与 outp_order 对账。
create table pharm_split_dispense (
    id              bigserial   primary key,
    dispense_no     varchar(32) not null unique,
    registration_id bigint,
    order_id        bigint,
    drug_id         bigint      not null references md_drug (id),
    -- 本次发出的最小单位数量
    qty_min_unit    int         not null,
    -- 本次为凑够数量而开的整盒数（0 表示全部由余量池支出，未动 md_drug.stock）
    packs_opened    int         not null,
    pack_size       int         not null,
    min_unit        varchar(8),
    -- DISPENSED 已发 / PART_RETURNED 部分退回 / RETURNED 已全退
    status          varchar(16) not null default 'DISPENSED',
    returned_qty    int         not null default 0,
    -- 发药当时效期 gate 是否报了警（warn 档放行但留痕，便于事后统计「带警发出」比例）
    expiry_warned   boolean     not null default false,
    expiry_note     varchar(255),
    operator_id     bigint      references sys_user (id),
    created_at      timestamptz not null default now(),
    constraint ck_pharm_split_dispense_qty check (qty_min_unit > 0 and returned_qty >= 0 and returned_qty <= qty_min_unit)
);

create index idx_pharm_split_dispense_reg on pharm_split_dispense (registration_id);
create index idx_pharm_split_dispense_drug on pharm_split_dispense (drug_id, id desc);

-- ---------------------------------------------------------------------------
-- ⑤ 拆零余量盘点单 + 明细
-- ---------------------------------------------------------------------------
-- **整盒盘点不在这里做**：V81 的 inv_stock_take 已经在盘 md_drug.stock，再盘一遍就是两套账。
-- 本单只盘「已拆盒的散装余量」——这一块是本版新造出来的库存形态，V81 盘不到它，
-- 不给它配盘点等于新增一个永远对不上账的口袋。
-- 账面数在**加行时**快照（与 V81 同口径）：盘点的意义是账实对账，账面必须是开盘那一刻的值；
-- 确认时若余量已被并发拆零改动，条件更新即拒绝（5504），迫使重新盘点——
-- 否则期间正常发出的散装会被误记成盘亏。
create table pharm_split_take (
    id           bigserial   primary key,
    take_no      varchar(32) not null unique,
    status       varchar(16) not null default 'DRAFT',   -- DRAFT / CONFIRMED / CANCELLED
    remark       varchar(255),
    operator_id  bigint      references sys_user (id),
    created_at   timestamptz not null default now(),
    confirmed_at timestamptz
);

create table pharm_split_take_line (
    id         bigserial   primary key,
    take_id    bigint      not null references pharm_split_take (id) on delete cascade,
    drug_id    bigint      not null references md_drug (id),
    -- 账面余量快照（最小单位）。药品从未拆过（无 pharm_split_stock 行）时为 0——
    -- 允许盘出「账面 0 但架上有散装」的盘盈，这正是拆零场景最常见的账实不符形态
    book_qty   int         not null,
    -- 实盘数：录入前为 null；差异 = actual - book 由查询计算，不落冗余列（与 V81 同）
    actual_qty int,
    created_at timestamptz not null default now(),
    unique (take_id, drug_id)
);

create index idx_pharm_split_take_line_take on pharm_split_take_line (take_id);

-- 单号取库序列（与 inv_stock_in_seq / inv_stock_take_seq 同一防碰撞思路：
-- nanoTime%1e6 会撞唯一约束并以裸 500 暴露，本仓已有先例）
create sequence if not exists pharm_split_dispense_seq start 1;
create sequence if not exists pharm_split_take_seq start 1;

-- ---------------------------------------------------------------------------
-- ⑥ 配置键
-- ---------------------------------------------------------------------------
-- 【效期预警天数】刻意 seed 成**空串**而不是 90。
-- 本仓已有 inv_expiry_warn_days（V83，默认 90）在驱动药库每日巡检 ExpiryAlertScheduler。
-- 再 seed 一个 90 就是同一概念两处真相：管理员改了药库那个、药房窗口这个纹丝不动，
-- 而两处都显示「90 天」，排查时没人看得出来。
-- 空串在 ConfigReader 里等价于「键不存在」（isBlank → 回落 defaultValue），
-- 于是**默认跟随 inv_expiry_warn_days，只有一处真相**；
-- 又因为行是存在的，管理员在配置页上能看见、能填数字来单独覆盖药房窗口阈值（PUT /api/config 只能改已存在的键）。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('pharm.expiry.warn_days', '',
     '门诊药房窗口近效期预警天数。**留空即跟随 inv_expiry_warn_days（药库巡检阈值，默认 90）**；'
     '填 1–3650 的整数则药房窗口单独用该阈值。填非法值时服务端回落 90 并在返回体 caveats 说明')
on conflict (cfg_key) do nothing;

-- 【效期发药 gate】三态，默认 warn（v47 立的口径，v50 沿用）。
-- 为什么不是 block：本平台此前发药**从不看效期**，且 inv_stock_in.expire_date 对存量批次
-- 大量为 NULL（V7 起就可空，没有任何强制）。直接 block 会让「历史批次没填效期 / 早年过期批次
-- 从未做报损」的药一律发不出去，门诊药房当场停摆。
-- warn 档照常发药，只把「该药存在已过期且估算仍在架的批次」以 warnings 回带并在
-- pharm_split_dispense.expiry_warned 留痕，先让问题可见可统计，收紧到 block 由院方按自家数据质量决定。
-- 坏配置回落 warn 而不是 off：宁可多提示，不可静默失效。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('pharm.gate.expiry.dispense', 'warn',
     '拆零发药前的过期批次校验：off 旁路 / warn 放行并回带 warnings（默认）/ block 拒发（5481）')
on conflict (cfg_key) do nothing;

-- ---------------------------------------------------------------------------
-- ⑦ 刻意不做的事
-- ---------------------------------------------------------------------------
-- * **不插 sys_menu**：前端 frontend/shell/src 是共用目录，本车道按分工不改；
--   先插菜单会给药师一个点进去 404 的死链，比没有菜单更坏。菜单与页面需求已写进 cross_lane。
-- * **不给 md_drug 加「精麻毒放」属性位**：那是 5520–5539 段与 Lane A 的事，
--   按药名猜管制属性是危险假实现（管制药品台账要对上药监与卫健两条线）。
-- * **不改 inv_transaction / inv_stock_take / md_drug.stock 的任何既有语义**，
--   既有发药链路（DispenseService 的 claimDispense/claimReturn 抢占模式）一字未动。
