-- v50 车道 A：门诊药房调剂 —— 库存地基（批次 / 效期 / 批次级出入库流水）
-- 偏离表 46★ 的地基层；错误码子段 5400–5419（见 docs/错误码分段.md）。
--
-- ============================================================================
-- 零、先纠一条事实：本仓**不是**「没有批次、没有效期、没有出入库流水」
-- ============================================================================
-- 下发口径写的是「药品侧只有 md_drug 一张表，没有批次、没有效期、没有出入库流水」。
-- 实测不成立，证据在库里：
--   * V7__inventory.sql  建 `inv_stock_in`，**自建表起就有 batch_no / expire_date / supplier**；
--     同文件建 `inv_transaction`（type / qty / stock_after / ref_no / operator_id），
--     那就是一条**药品级出入库流水**，IN / OUT / RET / ADJ 四型齐全。
--   * V81 加 `inv_stock_take` 盘点单（「药品盘点」并非零命中）、把流水 type 加宽到 STOCKTAKE。
--   * V82 给 `inv_stock_in` 加 `accept_status` 验收工作流（PENDING_ACCEPT/ACCEPTED/REJECTED）。
--   * V83 落效期预警配置，`InventoryService.expiryWarnings` 已在跑近效期扫描。
--
-- 按错的现状去建表，最可能的产物是**第二套同名同义的入库单与流水表**——
-- 那不是补地基，那是给同一笔账开第二本。故本迁移先把边界划清楚。
--
-- ============================================================================
-- 一、那么真正缺的是什么：**批次维度的余额**
-- ============================================================================
-- 缺口不在「有没有批号」，而在「批号后面挂不挂得住数」。今天的账是这样的：
--
--   inv_stock_in   ：一次到货的**单据**，带批号效期。是「进了什么」。
--   inv_transaction：药品级**流水**，只有 drug_id，**没有 batch_id**。是「动了多少」。
--   md_drug.stock  ：药品级**余额**，一个整数。是「还剩多少」。
--
-- 三者之间断在中间那一环：流水不带批次，于是**余额无法按批次拆开**。
-- 后果不是理论上的，V83 自己写在注释里认了 —— `expiryWarnings` 只能做「只读估算口径」：
-- 把该药的净出量按假设分摊到各批次上倒推剩余，且刻意把分摊方向调成「先扣远效期批次」
-- 以求宁可多报不漏报。一句话：**近效期预警今天报的是估计值，不是账。**
--
-- 本迁移补的就是断掉的那一环：
--   pharm_batch      批次主数据（药品 + 批号 + 生产日期 + 有效期至 + 供应商 + 进价）
--   pharm_stock      按 (批次, 库房/药柜) 的**实时余额**
--   pharm_stock_flow 按 (批次, 库房/药柜) 的**流水**——每一次增减一行
--
-- 纪律「**流水是账，库存是余额**」在这里是硬约束，不是口号：
--   对任意 (batch_id, location_code)，恒有
--       pharm_stock.qty == sum(pharm_stock_flow.qty_delta)
--   两者永远同事务写入；`GET /api/pharm/stock/reconcile` 就是把这条等式逐行验一遍的端点，
--   V50PharmStockTest 把它作为断言。余额推不出来的流水是流水账，不是账。
--
-- ============================================================================
-- 二、与 md_drug.stock 的关系（本车道最难的一处，必须写死在这里）
-- ============================================================================
-- md_drug.stock 是**没有批次维度的汇总数**，且既有 DispenseService、住院执行等多处直接扣它。
--
-- 【绝不做的事】**不拿 md_drug.stock 反推初始批次库存。**
-- 现有 stock 是一个汇总整数，硬拆成批次等于凭空捏造批号与效期。药品批号要对得上
-- 药监追溯与院内召回两条线，编出来的批号在召回时会让人**照着假批号去下架真药**。
-- 那不是迁移，是假数据。故本迁移**零条 update、零条 insert 去填历史**——
-- 新表建完就是空的，历史库存一粒都不进来。
--
-- 【也不做的事】**不废掉 md_drug.stock、不改它的语义、不给它加触发器。**
-- 它仍是全仓唯一的「可发药量」权威口径，既有发药链路逐字不动。
--
-- 【于是两者并存，关系如下】
--   md_drug.stock  = 全院该药可发药量（**权威**，发药端 6002 判它，本版不变）
--   pharm_stock    = 已启用批次管理部分的**批次级明细账**（**子账，不是总账**）
--
-- 入账方向：本车道的入库端点 `POST /api/pharm/stock/stock-in` 是**一步式双写**——
-- 它不自己写一套加库存的 SQL，而是**委托既有 `InventoryService.stockIn` + `acceptStockIn`**
-- 去走旧口径（建 inv_stock_in 单、加 md_drug.stock、写 inv_transaction 的 IN 流水），
-- 然后才落自己的 pharm_batch / pharm_stock / pharm_stock_flow，
-- 并用 `pharm_stock_flow.src_stock_in_id` 把两本账勾在一起。这样：
--   - 加库存仍走既有那段被测试覆盖过的原子条件更新，不出现第二套扣加库存的实现；
--   - 旧口径的入库单与流水不缺行，V83 的效期预警反而因为多看见批次而变准；
--   - 新旧两本账之间有可追的勾稽键，不是两个各说各话的数。
--
-- ============================================================================
-- 三、必然存在的漂移（drift）——本版**公开标注，不掩盖**
-- ============================================================================
-- 出账方向今天只补上了一半：入库、报损两条路径双写；
-- **发药出库仍只扣 md_drug.stock，不扣批次余额**——因为 DispenseService 是既有链路，
-- 本车道不许改它（改坏发药的代价远高于一个可标注的统计偏差）。
--
-- 直接后果：随着发药发生，
--     sum(pharm_stock.qty)  >=  该药实际在架的批次管理部分
-- 也就是**批次层账面会单向偏高**。这是设计已知的，不是 bug。
--
-- 处理方式沿用 v46 麻醉质控 / v48 病理质控的诚实标注做法，而不是让它安静地错下去：
--   1. `GET /balance` 每一行都回带 `aggregateStock`（md_drug.stock）、`batchStock`
--      （批次层合计）与 `drift = batchStock - aggregateStock`，drift≠0 的行带 `driftNote`；
--   2. 返回体顶层带 `coverage` 段：多少药品已启用批次管理、多少仍在旧口径、覆盖率多少——
--      「批次库存 100% 准确」很可能只是「全院 3 个药启用了批次管理」；
--   3. 报损时若批次余额扣得动而 md_drug.stock 扣不动（正是漂移的表现），
--      报 5410 并在消息里直指漂移与 /reconcile，**不静默把汇总扣成负数**。
--
-- 收敛路径（留给后续车道，本版不做）：把 DispenseService 的扣减改成
-- 「先按 pick_rule 取批、扣批次余额并写 OUT 流水，再扣 md_drug.stock」，
-- 届时 drift 恒为 0，本节连同 driftNote 一起删除。那一步要动既有发药链路与并发抢占模式，
-- 必须单独评估、单独回归，不该塞进地基这一版。
--
-- ============================================================================
-- 四、CHECK 约束：这里加，是因为**新表零行**
-- ============================================================================
-- 全仓纪律是既有表不加 CHECK（v42 口径：试点库历史脏值会挡住 Flyway，代价高于收益）。
-- 该纪律的前提是「表里已经有数据、且数据不干净」。本迁移三张表**建完即空，且永不回填**，
-- 不存在任何一行历史数据能挡住 Flyway。约束在这里是零风险的，且拦的都是会让账彻底失真的输入
-- （负余额、零位移流水、非法流水类型）。写侧 PharmStockService 有同名校验并返 5400 段错误码，
-- CHECK 是兜底那一层——写侧漏一条路径时，坏数据进不了库，而不是进库之后靠对账去发现。

-- ============================================================================
-- 表一：pharm_batch —— 批次主数据
-- ============================================================================
-- 与 inv_stock_in 的分工：inv_stock_in 是**一次到货的单据**（同一批号可以到货多次，
-- 每次一张单）；pharm_batch 是**批次本身**（同一药品同一批号只有一个实体，效期唯一确定）。
-- 二者是多对一，不是同一张表的两个副本。
--
-- 本表新增了 inv_stock_in 至今没有的两列：
--   produced_on    生产日期——效期核对与召回定位都要它，inv_stock_in 只有 expire_date；
--   purchase_price 进价——inv_stock_in 全表无价格列，报损金额、库存金额、批次成本今天算不出来。
create table pharm_batch (
    id             bigserial     primary key,
    drug_id        bigint        not null references md_drug (id),
    -- 批号取生产厂家原始批号，varchar(64)：inv_stock_in.batch_no 是 varchar(32)，
    -- 进口药与生物制品批号常超 32 位（含分装/亚批后缀），本表按 64 建，不跟旧列的窄口径
    batch_no       varchar(64)   not null,
    produced_on    date,
    expire_on      date,
    supplier       varchar(128),
    -- 进价按**含税单价**记，numeric(12,4)：md_drug.price 是 numeric(10,2) 的售价，
    -- 进价常有四位小数（拆零后的单支/单片成本），两位会把成本抹平成 0.00
    purchase_price numeric(12,4),
    created_at     timestamptz   not null default now(),
    created_by     bigint        references sys_user (id),
    -- 同一药品同一批号唯一：批号是厂家赋予的批次身份，同药同批号必然同效期。
    -- 第二次入同一批号时写侧比对效期/生产日期，不一致直接报 5406 而不是悄悄再建一个批次——
    -- 「同一批号两个效期」在召回时会让人下架错货，必须在入库那一刻就顶回去。
    unique (drug_id, batch_no),
    -- 生产日期不得晚于有效期至（写侧 5405 同判；录反了的批次会让 FEFO 取批顺序整个颠倒）
    constraint ck_pharm_batch_date_order
        check (produced_on is null or expire_on is null or produced_on <= expire_on),
    -- 进价非负（写侧 5411 同判）
    constraint ck_pharm_batch_price check (purchase_price is null or purchase_price >= 0)
);

comment on table  pharm_batch is 'v50 批次主数据：药品+批号唯一；与 inv_stock_in（到货单据）多对一';
comment on column pharm_batch.purchase_price is '含税进价单价，numeric(12,4)——拆零成本需四位小数';

-- 效期查询（近效期预警、FEFO 取批）按药品 + 效期升序，效期为空的排最后。
-- 部分索引没有意义（绝大多数批次都有效期），建普通复合索引
create index idx_pharm_batch_drug_expire on pharm_batch (drug_id, expire_on);
-- 按批号反查（召回、追溯）：跨药品查同一批号是召回场景的真实形态
create index idx_pharm_batch_no on pharm_batch (batch_no);

-- ============================================================================
-- 表二：pharm_stock —— (批次, 库房/药柜) 实时余额
-- ============================================================================
-- **刻意不冗余 drug_id**。批次已经确定了药品，再放一份 drug_id 就多出一条
-- 「两处不一致」的可能；按药品查一律 join pharm_batch。这个 join 的代价是一次索引查找，
-- 而一个能对不上的冗余列的代价是一整类查不出来的错账。
create table pharm_stock (
    id            bigserial   primary key,
    batch_id      bigint      not null references pharm_batch (id),
    -- 库房/药柜编码。**本版刻意不建地点主数据表**：「药柜 / 发药窗口」属 5440–5459 子段，
    -- 是另一条车道的题目，在这里先建一张只有一行种子的地点表，等于替别人把表结构定死。
    -- 故此处是自由编码 + 默认 'OUTP_PHARM'（门诊药房），写侧只校验长度与字符集（5412）。
    -- 待地点主数据落地后，本列改为外键（改法：加列 location_id、双写一版、切读、删本列）。
    location_code varchar(32) not null default 'OUTP_PHARM',
    qty           int         not null default 0,
    updated_at    timestamptz not null default now(),
    -- 一个批次在一个地点只有一行余额；入库走 on conflict do update 原子累加
    unique (batch_id, location_code),
    -- 余额不得为负。写侧用带 `qty >= ?` 谓词的条件更新拦（5408），CHECK 是兜底：
    -- 任何一条绕过写侧的路径把余额扣穿，都会在这里失败回滚，而不是留下一行负库存
    constraint ck_pharm_stock_qty check (qty >= 0)
);

comment on table pharm_stock is 'v50 批次级余额（子账）；总账仍是 md_drug.stock，二者关系与已知漂移见 V147 迁移注释';

-- 按地点列全部在库批次（药柜盘点、窗口备药）
create index idx_pharm_stock_loc on pharm_stock (location_code);
-- 取批只关心还有货的行：在库批次远少于历史批次，部分索引体积是全索引的零头
create index idx_pharm_stock_positive on pharm_stock (batch_id) where qty > 0;

-- ============================================================================
-- 表三：pharm_stock_flow —— 批次级出入库流水（账）
-- ============================================================================
-- 与 inv_transaction 的分工：inv_transaction 是**药品级**流水（无 batch_id），记的是
-- md_drug.stock 这本总账的每一次变动，既有链路一直在写，本版不动它；
-- pharm_stock_flow 是**批次级**流水，记的是 pharm_stock 这本子账。
-- 同一笔入库两本账各有一行，靠 src_stock_in_id 勾稽。
create table pharm_stock_flow (
    id             bigserial   primary key,
    -- 流水号取序列而非 nanoTime：nanoTime%1e6 会撞唯一约束并以裸 500 暴露（1.1.7 B-8 学费）
    flow_no        varchar(32) not null unique,
    batch_id       bigint      not null references pharm_batch (id),
    location_code  varchar(32) not null,
    -- 五型齐全是**表结构层面的完整性**，不代表五型本版都有写入路径：
    --   IN    入库      —— 本版已实现（POST /stock-in）
    --   SCRAP 报损      —— 本版已实现（POST /scrap）
    --   OUT   发药出库  —— **本版无写入路径**，发药仍走 md_drug.stock（见迁移注释第三节漂移）
    --   RET   退药回补  —— 本版无写入路径，退药仍走既有 DispenseService.returnDrug
    --   ADJ   盘点调整  —— 本版无写入路径，盘点属 5500–5519 子段，另一条车道
    -- 先把类型域定全，是为了后续车道接进来时不必再 alter 一次约束、不必二次评审取值集合。
    flow_type      varchar(16) not null,
    -- 位移量：入为正、出为负。**不拆成 in_qty/out_qty 两列**——
    -- 一列带符号，余额就是 sum(qty_delta)，对账是一条 group by；两列则每次求和都要写减法，
    -- 迟早有一处写反，且写反了不会报错，只会让账悄悄差一截
    qty_delta      int         not null,
    -- 该 (批次,地点) 在本行之后的余额。由带 RETURNING 的原子更新回读，不是应用层算出来的——
    -- 应用层「读余额→加减→写回」在并发下会记出一串错误的 qty_after，而余额本身还是对的，
    -- 结果是账面对、流水错，比两个都错更难查
    qty_after      int         not null,
    reason         varchar(255),
    -- 关联单据号：入库为 inv_stock_in.in_no，报损为报损原因单号，后续 OUT 为处方组号
    ref_no         varchar(64),
    -- 与旧口径入库单的勾稽键：本版 IN 流水必填，其余类型为空
    src_stock_in_id bigint     references inv_stock_in (id),
    operator_id    bigint      references sys_user (id),
    -- 显式写入而非 default now()：全仓时刻一律 Instant.now().truncatedTo(MICROS)，
    -- 交由 default now() 会拿到微秒以下精度，回读后与写入值不等（本仓已因此炸过四次）
    occurred_at    timestamptz not null,
    -- 流水类型域（新表零行，加约束零风险；写侧同判）
    constraint ck_pharm_flow_type check (flow_type in ('IN', 'OUT', 'RET', 'ADJ', 'SCRAP')),
    -- 零位移不是流水。允许 0 会让「这次操作没动任何东西」也占一行，
    -- 对账时看不出差别，却把审计线索稀释掉
    constraint ck_pharm_flow_delta check (qty_delta <> 0),
    -- 结存不得为负（与 pharm_stock 同一不变量的另一侧）
    constraint ck_pharm_flow_after check (qty_after >= 0)
);

comment on table  pharm_stock_flow is 'v50 批次级流水（账）；不变量：sum(qty_delta) over (batch_id,location_code) == pharm_stock.qty';
comment on column pharm_stock_flow.flow_type is 'IN 入库 / OUT 发药出库 / RET 退药回补 / ADJ 盘点调整 / SCRAP 报损；本版仅 IN 与 SCRAP 有写入路径';

-- 对账与批次流水页：按 (批次,地点) 聚合求和，这是不变量校验的主查询形态
create index idx_pharm_flow_batch_loc on pharm_stock_flow (batch_id, location_code, id);
-- 流水查询页按时刻倒序翻页
create index idx_pharm_flow_occurred on pharm_stock_flow (occurred_at desc);
-- 按单据反查（入库单 → 批次流水）
create index idx_pharm_flow_src_in on pharm_stock_flow (src_stock_in_id) where src_stock_in_id is not null;

-- 流水号序列（同 inv_stock_in_seq / inv_stock_take_seq 的防碰撞思路）
create sequence if not exists pharm_stock_flow_seq start 1;

-- ============================================================================
-- 配置项（**新键 insert，不是历史回填**；均 on conflict do nothing）
-- ============================================================================
-- 全仓 gate 纪律：三态 off|warn|block，**默认 warn**，坏配置回落 warn 不回落 off。
-- 此前药房从无批次/效期校验，直接 block 会让存量入库流程瞬间大面积失败
-- （历史到货单据常无批号、进口药效期录入口径不一），warn 先让问题当场可见。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('pharm.gate.batch.required', 'warn',
     '入库批号/效期必填 gate：off 不校验 / warn 提示但放行、返回体带 warnings（默认）/ block 硬拦 5402、5403'),
    ('pharm.gate.expiry.stock_in', 'warn',
     '入库效期合格性 gate：off 不校验 / warn 提示但放行（默认）/ block 硬拦 5404。判两件事：已过期批次不得入库、剩余效期不足 pharm.batch.min_shelf_life_days 不得入库'),
    ('pharm.batch.pick_rule', 'FEFO',
     '取批规则：FEFO 先效期先出（默认）/ FIFO 先进先出。**非 gate，坏值回落 FEFO**——FEFO 临床更安全（近效期先用完，减少过期报废与过期药发出）'),
    ('pharm.batch.min_shelf_life_days', '180',
     '入库最低剩余效期天数（受 pharm.gate.expiry.stock_in 管辖）：剩余效期低于此值视为效期不合格入库。0 表示只拦已过期批次')
on conflict (cfg_key) do nothing;

-- 近效期天数阈值**刻意复用 V83 已有的 `inv_expiry_warn_days`（默认 90）**，不另开新键：
-- 同一个「多少天算近效期」的业务概念在系统里只能有一个数。开第二个键的结局是
-- 预警页报 90 天、批次页报 60 天，两个页面都"对"，药师不知道该信哪个。

-- ============================================================================
-- 菜单：本迁移**不插 sys_menu**
-- ============================================================================
-- frontend/shell/src/** 是共用目录，本车道不改前端、也就没有可路由的页面。
-- 先插菜单会得到一个点进去 404 的死入口——比没有入口更坏。
-- 菜单与前端页面一并交主控（见 cross_lane），由落前端的那一版一起插。
