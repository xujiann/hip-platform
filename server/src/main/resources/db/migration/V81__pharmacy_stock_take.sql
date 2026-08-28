-- 1.2.13 车道A 药事全链①：库存盘点
-- 盘点单头 + 盘点行（账面数 vs 实盘数），确认后按盈亏调整 md_drug.stock 并写 STOCKTAKE 流水。
-- 为什么账面数在「加行时」快照而非确认时现取：盘点的意义是「账实对账」，
-- 账面必须是盘点开始那一刻的值；确认时若库存已被并发发药/入库改动，条件更新即拒绝，
-- 迫使重新盘点——否则盈亏数会把期间正常出入库误记成盘盈盘亏。

create table inv_stock_take (
    id           bigserial   primary key,
    take_no      varchar(32) not null unique,
    status       varchar(16) not null default 'DRAFT',   -- DRAFT 草稿 / CONFIRMED 已确认 / CANCELLED 已作废
    remark       varchar(255),
    operator_id  bigint      references sys_user (id),
    created_at   timestamptz not null default now(),
    confirmed_at timestamptz
);

create table inv_stock_take_line (
    id         bigserial   primary key,
    take_id    bigint      not null references inv_stock_take (id) on delete cascade,
    drug_id    bigint      not null references md_drug (id),
    -- 账面数：加行时对 md_drug.stock 的快照，确认时作为条件更新的期望值
    book_qty   int         not null,
    -- 实盘数：录入前为 null；盈亏 diff = actual_qty - book_qty 由应用层/查询计算，不落冗余列
    actual_qty int,
    created_at timestamptz not null default now(),
    unique (take_id, drug_id)   -- 同一盘点单一药一行，批量录实盘走 upsert 语义
);

create index idx_inv_take_line_take on inv_stock_take_line (take_id);

-- 盘点单号取库序列（与入库单号同一防碰撞思路，见 V50 inv_stock_in_seq）
create sequence if not exists inv_stock_take_seq start 1;

-- 流水类型码加宽：原 varchar(8) 放不下 STOCKTAKE(9 字符)；IN/OUT/ADJ/RET 仍兼容
alter table inv_transaction alter column type type varchar(16);
comment on column inv_transaction.type is 'IN 入库 / OUT 发药出库 / RET 退药回补 / ADJ 盘点调整 / STOCKTAKE 盘点单确认';

-- 菜单：库存盘点页，挂「基础数据」(id=13) 下，限 ADMIN/PHARMACIST（与药库管理 id=17 同权）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (64, 13, '库存盘点', 'MENU', '/masterdata/stock-take', 'md:stocktake', 'DocumentChecked', 4);
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'PHARMACIST') and m.id = 64;
