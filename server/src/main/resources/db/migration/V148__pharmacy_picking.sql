-- v50 车道B 摆药与预调剂（偏离表 46★ 之「摆药单 / 发药窗口 / 预调剂 / 摆药工作台」四个子项）
--
-- 【为什么要有这张表】现状是**整单一步发药**：`DispenseController` 4 个端点 +
-- `DispenseService` 63 行，从「已收费」一步跳到「已发药」。真实药房是
-- 收费 → 待摆药队列 → 药师摆药（逐项取药、打摆药单）→ 预调剂完成（药配好等患者来取）
-- → 患者到窗口 → 发药核对 → 交付，中间三步本仓**一行代码都没有**：
-- 「摆药」「预调剂」「摆药单」「发药窗口」四个业务名词全仓零命中。
-- 本迁移只补**发药之前**的环节。
--
-- 【既有链路一字不动】`outp_order` 的四态（CREATED/CHARGED/DISPENSED/CANCELLED）、
-- `DispenseService` 的 `claimDispense`/`claimReturn` 抢占写法、错误码 6001–6006、
-- 发药与退药的 4 个既有端点，本文件与本车道**一个字节都没有改**。摆药单是**旁挂**的一层：
-- 它记录「这批药是谁、在哪个窗口、什么时刻配好的」，扣库存与置 DISPENSED 仍然只由发药那一步做。
--
-- 【零回填纪律：本文件 0 条 update】V148 之前发出去的药**没有摆药单**，那就是事实——
-- 当时根本没有这个环节。绝不拿 `outp_order.status = 'DISPENSED'` 反推出一张
-- 「已发药的摆药单」：摆药人、开始摆药时刻、配好时刻、发药窗口四列全部无从得知，
-- 补出来的单子会让「预调剂及时率」「摆药差错率」凭空有了分母，那是假数据不是迁移。
-- 同 v41 床位效率、v46 手术时间点、v48 病理签收时刻：**宁可少算，不可假算**。
--
-- 【错误码】本车道占 5420–5439（docs/错误码分段.md 已先于编码登记），实测用掉 13 个：
--   5420 摆药单不存在                       5421 该挂号已有在途摆药单
--   5422 无待摆药的已收费处方               5423 摆药单状态不允许该操作（并发抢占失败同码）
--   5424 摆药明细未逐项确认（仅 block 档）  5425 未分配发药窗口（仅 block 档）
--   5426 发药窗口不存在或已停用             5427 摆药明细不存在或状态不符
--   5428 实摆数量非法                       5429 作废原因非法（空/超长）
--   5430 无法识别当前登录用户               5431 工作台检索条件非法
--   5432 发药窗口配置非法
-- 5433–5439 空置。

-- ============================================================
-- 一、pharm_window 发药窗口
-- ============================================================

-- 【为什么是表不是 sys_config】任务允许用 sys_config 塞一行 CSV，但窗口要被
-- 「按窗口过滤工作台」「按窗口统计在途负载」「停用某个窗口」三处消费，
-- CSV 到这三处都得在应用层现拆现解析，且拆错了没有任何约束会拦。一张四列小表更便宜。
--
-- 【窗口只停用不删除】`pharm_picking_order.window_code` 外键到 code：
-- 删掉一个窗口会让历史摆药单的「当时在哪个窗口配的」永久不可考。
create table pharm_window (
    id         bigserial   primary key,
    code       varchar(16) not null unique,           -- 外键目标，故须唯一
    name       varchar(64) not null,
    enabled    boolean     not null default true,
    sort_no    smallint    not null default 0,
    remark     varchar(255),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

comment on table pharm_window is
    'v50 车道B：发药窗口字典。**车道C 的「发药窗口叫号」直接复用本表，不要再建第二张**';

-- 默认窗口是**配置默认值**（同 sys_config 的种子），不是业务数据：
-- 实施期由院方按实际窗口数增删改。给 3 个而不是 0 个的理由是——一个窗口都没有时
-- 「分配窗口」端点无从演示、E2E 无从跑通，而窗口数是每家医院都会先配的第一项。
insert into pharm_window (code, name, sort_no, remark) values
    ('W1', '1号窗口', 1, '默认配置，实施期按院方实际窗口替换'),
    ('W2', '2号窗口', 2, '默认配置，实施期按院方实际窗口替换'),
    ('W3', '3号窗口', 3, '默认配置，实施期按院方实际窗口替换')
on conflict (code) do nothing;

-- ============================================================
-- 二、pharm_picking_order 摆药单
-- ============================================================

-- 一次挂号的全部待摆药品汇成一张单。**单据粒度是挂号而不是处方**：
-- 患者一次就诊开了三张处方，药师是一次性把三张的药一起配好交给他，
-- 按处方拆成三张摆药单会让同一个人在队列里出现三次、也无法回答「他的药配齐了没有」。
create table pharm_picking_order (
    id              bigserial   primary key,
    pick_no         varchar(32) not null unique,                  -- BY+日戳+序列，见 pharm_picking_seq
    registration_id bigint      not null references outp_registration (id),

    -- 预调剂状态机：PENDING 待摆药 → PICKING 摆药中 → PREPARED 已配好
    --                             → DISPENSED 已发药 / CANCELLED 已作废
    -- 存英文码不存中文（同 v46 4906 手术类别）：它是工作台过滤与并发抢占的 where 条件，要进 SQL 分支。
    status          varchar(16) not null default 'PENDING',

    -- 发药窗口：**可空**。建单时未必知道去哪个窗口（有的院是配好后按队列就近分配），
    -- 故不设 not null；「配好之前必须已分配」由三态 gate pharm.gate.picking.window 管，不焊死在库里。
    window_code     varchar(16) references pharm_window (code),

    -- 建单那一刻的事实快照，供工作台免 join 展示；**不随后续退费/退药回改**
    -- （改了就答不出「当时药师要配几样、共多少」）。
    item_count      smallint    not null default 0,
    total_qty       int         not null default 0,
    urgent          boolean     not null default false,            -- 任一行 outp_order.urgent 即为真

    picker_id       bigint      references sys_user (id),          -- 摆药人（开始摆药时写入）
    picking_at      timestamptz,                                   -- 开始摆药时刻
    prepared_by     bigint      references sys_user (id),
    prepared_at     timestamptz,                                   -- 预调剂完成（药已配好）时刻
    dispensed_at    timestamptz,                                   -- 交付时刻，由发药环节回写
    cancelled_by    bigint      references sys_user (id),
    cancelled_at    timestamptz,
    cancel_reason   varchar(255),

    remark          varchar(255),
    created_at      timestamptz not null default now(),
    created_by      bigint      references sys_user (id),

    constraint chk_pharm_picking_status check (status in
        ('PENDING', 'PICKING', 'PREPARED', 'DISPENSED', 'CANCELLED'))
);

-- 【并发建单的硬闸】同一次挂号同时只能有一张在途摆药单。
-- 两个药师同时点「建摆药单」时，晚到的那笔在这里撞唯一约束整单回滚（应用层转 5421），
-- 而不是各建一张、把同一批药配两遍。DISPENSED/CANCELLED 不在索引里，故收口后可再建新单。
create unique index uq_pharm_picking_live_reg on pharm_picking_order (registration_id)
    where status in ('PENDING', 'PICKING', 'PREPARED');

create index idx_pharm_picking_status on pharm_picking_order (status, created_at desc);
create index idx_pharm_picking_window on pharm_picking_order (window_code, status)
    where window_code is not null;
create index idx_pharm_picking_reg on pharm_picking_order (registration_id);

comment on column pharm_picking_order.prepared_at is
    'v50：预调剂完成时刻（药已配好等患者来取）。**不是** dispensed_at——后者是交到患者手里，'
    '两者混为一谈会让「配好到取药的等候时长」恒等于 0';

-- ============================================================
-- 三、pharm_picking_line 摆药明细
-- ============================================================

-- 一行对应一条 outp_order 药品医嘱。**qty 是建单时的快照**（应摆量），
-- picked_qty 是药师实际摆出的量（实摆量）——两者不等是真实存在的（缺货部分摆），
-- 合并成一列就再也答不出「哪一行没摆齐」。
create table pharm_picking_line (
    id         bigserial   primary key,
    picking_id bigint      not null references pharm_picking_order (id),
    order_id   bigint      not null references outp_order (id),
    qty        int         not null,                      -- 应摆量：建单时 outp_order.qty 的快照
    picked_qty int,                                       -- 实摆量：null = 尚未逐项确认
    picked_at  timestamptz,
    picked_by  bigint      references sys_user (id),
    remark     varchar(255),

    -- 【为什么要这个冗余列】「同一条医嘱不能同时挂在两张在途摆药单上」是跨表条件
    -- （在途与否写在单头上），而 PG 的部分唯一索引只能引用本表列。
    --
    -- 【语义是「已释放」不是「已作废」】单据**收口**（已发药）与**作废**都要置 true：
    -- 收口时不释放的话，这条医嘱就被永久占住了——而退药会把 outp_order 打回 CHARGED，
    -- 那批药要重新进待摆药队列、重新建单，届时会被这条陈旧的占用挡在门外，
    -- 表现为「退了药以后这个患者再也建不了摆药单」。
    released   boolean     not null default false,
    created_at timestamptz not null default now(),

    constraint uq_pharm_pick_line unique (picking_id, order_id),
    constraint chk_pharm_pick_qty check (qty > 0),
    constraint chk_pharm_pick_picked_qty check (picked_qty is null or picked_qty > 0)
);

-- 【并发建单的第二道闸】同一条医嘱在全库只能有一条未释放的摆药明细。
-- uq_pharm_picking_live_reg 挡的是「同一次挂号建两张单」，这一条挡的是
-- 「同一条医嘱被两张不同的单收走」（补开处方后重复建单的路径）。两道闸都要。
create unique index uq_pharm_pick_line_live on pharm_picking_line (order_id)
    where released = false;

comment on column pharm_picking_line.released is
    'v50：该明细是否已释放对医嘱的占用。摆药单收口（已发药）与作废时一并置 true——'
    '收口时不释放会让退药后重新收费的医嘱永远建不了新摆药单';

create index idx_pharm_pick_line_picking on pharm_picking_line (picking_id);

-- ============================================================
-- 四、单号序列
-- ============================================================

-- 取序列不取 nanoTime%1e6（后者会碰撞唯一约束，见 InventoryService.nextInSeq 的教训）。
create sequence pharm_picking_seq start 1;

-- ============================================================
-- 五、三态 gate 配置
-- ============================================================

-- 【为什么默认 warn 而不是 block】这两条校验此前**从来不存在**——存量流程是整单一步发药，
-- 既没有「逐项确认」也没有「窗口」。直接 block 会让药房当天大面积配不出药。
-- 坏配置一律回落 warn 而非 off：把一个写错的配置值变成静默关闭校验，是最坏的一种失效。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('pharm.gate.picking.line_confirm', 'warn',
     '预调剂完成前明细须逐项确认：off 不校验 / warn 放行但回带 warnings（默认）/ block 未逐项确认不得置已配好'),
    ('pharm.gate.picking.window', 'warn',
     '预调剂完成前须已分配发药窗口：off 不校验 / warn 放行但回带 warnings（默认）/ block 未分配窗口不得置已配好'),
    ('pharm.picking.auto_window', 'off',
     '建摆药单未指定窗口时是否自动分配：off 不分配（默认，留给人工）/ least_load 分配到在途单最少的启用窗口')
on conflict (cfg_key) do nothing;

-- 【本文件不插 sys_menu】前端 frontend/shell 是共用目录，本车道不改；
-- 插一条指向不存在路由的菜单只会让点进去是白屏。菜单与页面一并由主控收口（见 cross_lane）。
