-- 三十三期：护理与院感精细化（传染病报卡闭环 / 班次字典 / 抽检评分 / 交接班）

-- 传染病报卡：报卡 → 审核 → 上报（全流程闭环）
create table idc_report_card (
    id           bigserial primary key,
    patient_id   bigint      not null references empi_patient (id),
    disease_name varchar(128) not null,
    card_class   varchar(4)  not null,                  -- A 甲类 / B 乙类 / C 丙类
    onset_date   date,
    status       varchar(16) not null default 'REPORTED', -- REPORTED/REVIEWED/SUBMITTED/REJECTED
    reporter     varchar(64),
    review_note  varchar(255),
    reported_at  timestamptz not null default now(),
    reviewed_at  timestamptz,
    submitted_at timestamptz,
    constraint chk_idc_class check (card_class in ('A', 'B', 'C'))
);

-- 护理标准班次类型字典
create table nur_shift_type (
    code       varchar(16) primary key,
    name       varchar(32) not null,
    start_time varchar(8)  not null,
    end_time   varchar(8)  not null
);

insert into nur_shift_type (code, name, start_time, end_time) values
    ('DAY',   '白班', '08:00', '16:00'),
    ('MID',   '中班', '16:00', '00:00'),
    ('NIGHT', '夜班', '00:00', '08:00');

-- 质控抽检：计划 + 评分（支持临时表单，同计划多次评分成曲线）
create table qc_check_plan (
    id         bigserial primary key,
    title      varchar(128) not null,
    standard   varchar(255) not null,                   -- 质控标准（临时表单=计划下即席标准）
    ad_hoc     boolean      not null default false,
    created_at timestamptz  not null default now()
);

create table qc_check_score (
    id         bigserial primary key,
    plan_id    bigint       not null references qc_check_plan (id),
    target     varchar(64)  not null,                   -- 抽检对象（科室/病区）
    score      numeric(5,1) not null,
    note       varchar(255),
    checked_at timestamptz  not null default now(),
    constraint chk_qcc_score check (score between 0 and 100)
);

-- 交接班记录
create table shift_handover (
    id         bigserial primary key,
    dept_id    bigint      not null references sys_dept (id),
    shift_date date        not null default current_date,
    shift_type varchar(16) not null,
    summary    varchar(1000) not null,                  -- 当班情况
    todo       varchar(500),                            -- 交接待办
    author     varchar(64),
    created_at timestamptz not null default now()
);

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (57, 25, '护理院感精细', 'MENU', '/nursing-plus', 'nur:plus', 'FirstAidKit', 9);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (57);
