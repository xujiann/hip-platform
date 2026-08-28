-- 1.2.13 车道A 药事全链③：入库验收
-- 入库先记 PENDING_ACCEPT 且「不加库存、不写流水」；验收通过 ACCEPTED 才真正加库存并写 IN 流水；
-- 可 REJECTED 拒收（带原因）。与采购单勾稽为可选：有采购单号则 purchase_no 关联，无则允许直接入库验收。

alter table inv_stock_in
    add column accept_status varchar(16) not null default 'ACCEPTED',   -- 见下：存量行回填 ACCEPTED
    add column purchase_no   varchar(32),
    add column accepted_by   bigint references sys_user (id),
    add column accepted_at   timestamptz,
    add column reject_reason varchar(255);

-- 存量入库行在老逻辑下「入库即加库存」，早已入账，一律视为 ACCEPTED（default 已为其回填），
-- 避免历史数据被新的验收页误当「待验收」重复加库存。新入库由应用层显式写 PENDING_ACCEPT。
comment on column inv_stock_in.accept_status is 'PENDING_ACCEPT 待验收 / ACCEPTED 已验收入账 / REJECTED 已拒收';

-- 待验收列表查询：按状态过滤，命中率高，建部分索引只覆盖待处理行
create index idx_inv_stock_in_pending on inv_stock_in (accept_status) where accept_status = 'PENDING_ACCEPT';

-- 菜单：入库验收页，挂「基础数据」(id=13) 下，限 ADMIN/PHARMACIST
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (65, 13, '入库验收', 'MENU', '/masterdata/stock-in-accept', 'md:stockaccept', 'CircleCheck', 5);
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'PHARMACIST') and m.id = 65;
