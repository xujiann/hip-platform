-- 三十六期：院感专项与窗口服务（三管监测 / 窗口维护 / 科研台账）

-- 三管置管监测（呼吸机/中心静脉导管/导尿管）
create table inf_catheter (
    id           bigserial primary key,
    admission_id bigint      not null references inp_admission (id),
    line_type    varchar(8)  not null,                  -- VENT 呼吸机 / CVC 中心导管 / URINARY 导尿管
    start_at     timestamptz not null default now(),
    end_at       timestamptz,
    status       varchar(16) not null default 'ACTIVE', -- ACTIVE/REMOVED/INFECTED
    infect_date  date,                                  -- 判定感染日期（VAP/CLABSI/CAUTI）
    note         varchar(255),
    constraint chk_line_type check (line_type in ('VENT', 'CVC', 'URINARY'))
);

-- 置管日评估（每日评估是否可拔管）
create table inf_catheter_assess (
    id          bigserial primary key,
    catheter_id bigint      not null references inf_catheter (id),
    assess_date date        not null default current_date,
    keep_line   boolean     not null default true,       -- 评估结论：是否继续留置
    note        varchar(255),
    created_at  timestamptz not null default now(),
    constraint uq_cath_assess unique (catheter_id, assess_date)
);

-- 服务窗口维护（采样/发药/收费）
create table svc_window (
    id       bigserial primary key,
    win_no   varchar(16) not null unique,
    name     varchar(64) not null,
    win_type varchar(16) not null,                       -- SAMPLE/PHARMACY/CHARGE
    status   varchar(8)  not null default 'OPEN'         -- OPEN/CLOSED
);

insert into svc_window (win_no, name, win_type) values
    ('C01', '收费一号窗', 'CHARGE'),
    ('P01', '西药房发药窗', 'PHARMACY'),
    ('S01', '检验采样窗', 'SAMPLE');

-- 科研台账（项目 / 知识产权转化，含审核）
create table sr_item (
    id         bigserial primary key,
    item_type  varchar(8)   not null,                    -- PROJECT 项目 / IP 知识产权转化
    title      varchar(255) not null,
    leader     varchar(64),
    content    varchar(1000),
    amount     numeric(12,2),
    status     varchar(16)  not null default 'DRAFT',    -- DRAFT/APPROVED/REJECTED
    review_note varchar(255),
    created_at timestamptz  not null default now(),
    constraint chk_sr_type check (item_type in ('PROJECT', 'IP'))
);

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (61, 25, '院感专项', 'MENU', '/infection-plus', 'inf:plus', 'Aim', 10),
    (62, 29, '窗口与科研', 'MENU', '/misc', 'misc:center', 'Grid', 7);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (61, 62);
