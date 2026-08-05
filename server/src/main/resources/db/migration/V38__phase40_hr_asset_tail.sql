-- 四十期：人事与资产收尾（继续教育 / 考勤 / 资产价值调整与附件）

-- 继续教育项目（学分台账）
create table hr_cme (
    id              bigserial primary key,
    employee_id     bigint       not null references hr_employee (id),
    project_no      varchar(64),
    project_name    varchar(255) not null,
    organizer       varchar(128),
    leader          varchar(64),
    credit          numeric(5,1) not null default 0,
    cme_year        int          not null,
    conclusion      varchar(64),
    attachment_name varchar(255),
    reimbursed      boolean      not null default false,
    created_at      timestamptz  not null default now()
);

-- 考勤（打卡 / 补卡）
create table hr_attendance (
    id          bigserial primary key,
    employee_id bigint      not null references hr_employee (id),
    work_date   date        not null default current_date,
    check_in    varchar(8),
    check_out   varchar(8),
    att_type    varchar(8)  not null default 'NORMAL',  -- NORMAL 打卡 / MAKEUP 补卡
    note        varchar(255),
    created_at  timestamptz not null default now(),
    constraint uq_hr_att unique (employee_id, work_date)
);

-- 资产价值调整（增值 / 折旧补录）
create table as_value_adjust (
    id          bigserial primary key,
    asset_id    bigint        not null references hrp_asset (id),
    adjust_type varchar(16)   not null,                 -- APPRECIATION 增值 / DEP_FIX 折旧补录
    amount      numeric(12,2) not null,
    reason      varchar(255)  not null,
    created_at  timestamptz   not null default now(),
    constraint chk_adj_type check (adjust_type in ('APPRECIATION', 'DEP_FIX'))
);

-- 资产附件文档台账（档案柜编号制）
create table as_asset_doc (
    id         bigserial primary key,
    asset_id   bigint       not null references hrp_asset (id),
    doc_name   varchar(255) not null,
    remark     varchar(255),
    created_at timestamptz  not null default now()
);
