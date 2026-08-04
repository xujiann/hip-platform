-- 药库进销存：入库单、库存流水

create table inv_stock_in (
    id          bigserial primary key,
    in_no       varchar(32) not null unique,
    drug_id     bigint      not null references md_drug (id),
    qty         int         not null,
    batch_no    varchar(32),
    expire_date date,
    supplier    varchar(128),
    operator_id bigint references sys_user (id),
    created_at  timestamptz not null default now()
);

create table inv_transaction (
    id          bigserial primary key,
    drug_id     bigint      not null references md_drug (id),
    type        varchar(8)  not null,
    qty         int         not null,
    stock_after int         not null,
    ref_no      varchar(64),
    operator_id bigint references sys_user (id),
    created_at  timestamptz not null default now()
);

create index idx_inv_txn_drug on inv_transaction (drug_id);

insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (17, 13, '药库管理', 'MENU', '/masterdata/inventory', 'md:inventory', 'Box', 3);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'PHARMACIST') and m.id = 17;
