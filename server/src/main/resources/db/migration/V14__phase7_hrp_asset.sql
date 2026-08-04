-- 第七期：HRP 固定资产台账

create table hrp_asset (
    id            bigserial primary key,
    asset_no      varchar(32)   not null unique,
    name          varchar(128)  not null,
    category      varchar(32)   not null,
    dept_id       bigint references sys_dept (id),
    price         numeric(12,2) not null,
    purchase_date date          not null,
    useful_years  int           not null default 5,
    status        varchar(16)   not null default 'IN_USE',
    remark        varchar(255),
    created_at    timestamptz   not null default now()
);

insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (29, null, '运营管理', 'DIR',  '/hrp',        null,        'OfficeBuilding', 70),
    (30, 29,   '固定资产', 'MENU', '/hrp/assets', 'hrp:asset', 'Files', 1);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (29, 30);
