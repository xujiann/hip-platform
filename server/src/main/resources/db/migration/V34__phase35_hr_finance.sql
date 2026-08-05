-- 三十五期：人财物管理完整化（HR 人事 / 资产处置 / 房屋 / 价格规则留痕）

-- 员工档案
create table hr_employee (
    id         bigserial primary key,
    emp_no     varchar(32)  not null unique,
    name       varchar(64)  not null,
    sex        varchar(2)   not null default 'U',
    dept_id    bigint references sys_dept (id),
    title      varchar(64),                              -- 职称/岗位
    phone      varchar(32),
    email      varchar(128),
    marital    varchar(8),
    birth_date date,
    hire_date  date,
    status     varchar(16)  not null default 'ACTIVE',   -- ACTIVE/LEFT
    created_at timestamptz  not null default now()
);

-- 培训台账
create table hr_training (
    id          bigserial primary key,
    employee_id bigint       not null references hr_employee (id),
    category    varchar(64)  not null,
    start_date  date,
    end_date    date,
    org         varchar(128),
    content     varchar(500),
    cert_name   varchar(128),
    cert_no     varchar(64),
    created_at  timestamptz  not null default now()
);

-- 工资（批量导入 + 个人按月查询）
create table hr_salary (
    id          bigserial primary key,
    employee_id bigint        not null references hr_employee (id),
    month       varchar(7)    not null,                  -- YYYY-MM
    base_pay    numeric(12,2) not null default 0,
    bonus       numeric(12,2) not null default 0,
    deduction   numeric(12,2) not null default 0,
    total       numeric(12,2) not null default 0,
    created_at  timestamptz   not null default now(),
    constraint uq_hr_salary unique (employee_id, month)
);

-- 招聘面试登记
create table hr_recruit (
    id             bigserial primary key,
    candidate_name varchar(64)  not null,
    position       varchar(64)  not null,
    interview_date date,
    result         varchar(16)  not null default 'PENDING', -- PENDING/PASS/FAIL
    note           varchar(255),
    created_at     timestamptz  not null default now()
);

-- 资产处置：调拨/移交留痕
create table as_transfer_log (
    id          bigserial primary key,
    asset_id    bigint      not null references hrp_asset (id),
    action      varchar(16) not null,                    -- TRANSFER 调拨 / HANDOVER 移交
    from_dept_id bigint references sys_dept (id),
    to_dept_id   bigint references sys_dept (id),
    from_person varchar(64),
    to_person   varchar(64),
    note        varchar(255),
    created_at  timestamptz not null default now()
);

-- 报废申请与审核
create table as_scrap (
    id         bigserial primary key,
    asset_id   bigint       not null references hrp_asset (id),
    reason     varchar(500) not null,
    status     varchar(16)  not null default 'APPLIED',  -- APPLIED/APPROVED/REJECTED
    review_note varchar(255),
    created_at timestamptz  not null default now(),
    decided_at timestamptz
);

-- 房屋建筑台账
create table hrp_building (
    id          bigserial primary key,
    building_no varchar(32)  not null unique,
    name        varchar(128) not null,
    address     varchar(255),
    area_sqm    numeric(12,2),
    usage_type  varchar(64),                             -- 门诊/住院/行政/后勤
    remark      varchar(255),
    created_at  timestamptz  not null default now()
);

-- 价格规则调整留痕
create table price_change_log (
    id         bigserial primary key,
    item_id    bigint        not null references md_charge_item (id),
    old_price  numeric(10,2) not null,
    new_price  numeric(10,2) not null,
    reason     varchar(255)  not null,
    changed_by varchar(64),
    created_at timestamptz   not null default now()
);

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (60, 29, '人事管理', 'MENU', '/hr', 'hr:center', 'User', 6);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (60);
