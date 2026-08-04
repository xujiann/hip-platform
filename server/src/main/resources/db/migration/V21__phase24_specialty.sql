-- 二十四期：专科流程补缺（心电/内镜按 RIS 模式、病理复用 LIS 流转、急诊留观、ICU 记录）

-- RIS 泛化：检查模态（GENERAL 普放/US 超声/ECG 心电/ENDO 内镜），按项目名自动归类
alter table ris_exam add column modality varchar(16) not null default 'GENERAL';

-- 病理：标本流转 + 诊断报告（LIS 模式实例化）
create table path_specimen (
    id            bigserial primary key,
    order_id      bigint      not null unique references outp_order (id),
    barcode       varchar(32) not null unique,
    specimen_desc varchar(255),
    status        varchar(16) not null default 'COLLECTED',   -- COLLECTED/RECEIVED/DIAGNOSED
    gross_finding varchar(2000),
    micro_finding varchar(2000),
    diagnosis     varchar(1000),
    pathologist_id bigint references sys_user (id),
    collected_at  timestamptz not null default now(),
    received_at   timestamptz,
    diagnosed_at  timestamptz
);

-- 急诊留观
create table er_observation (
    id          bigserial primary key,
    triage_id   bigint      not null references outp_triage (id),
    bed_no      varchar(16) not null,
    status      varchar(8)  not null default 'IN',            -- IN/OUT
    outcome     varchar(16),                                  -- DISCHARGED/ADMITTED/TRANSFERRED
    started_at  timestamptz not null default now(),
    ended_at    timestamptz
);

-- 留观记录（病情观察）
create table er_observation_note (
    id             bigserial primary key,
    observation_id bigint       not null references er_observation (id),
    note           varchar(1000) not null,
    recorder_id    bigint references sys_user (id),
    created_at     timestamptz  not null default now()
);

-- ICU 密集护理记录（体征模型加高频录入 + 出入量 + GCS）
create table inp_icu_record (
    id           bigserial primary key,
    admission_id bigint       not null references inp_admission (id),
    recorded_at  timestamptz  not null default now(),
    temperature  numeric(4,1),
    pulse        int,
    respiration  int,
    sbp          int,
    dbp          int,
    spo2         int,
    gcs          int,                                          -- 格拉斯哥昏迷评分 3-15
    intake_ml    int,
    output_ml    int,
    ventilator   boolean      not null default false,
    note         varchar(500),
    recorder_id  bigint references sys_user (id),
    constraint chk_icu_gcs check (gcs is null or gcs between 3 and 15)
);
create index idx_icu_adm_time on inp_icu_record (admission_id, recorded_at desc);

-- 专科项目种子（内镜/病理，心电已有 C0103）
insert into md_charge_item (code, name, category, unit, price, exec_dept_id) values
    ('C0104', '电子胃镜检查',        'EXAM', '次', 350.00, null),
    ('C0301', '组织病理学检查(活检)', 'EXAM', '次', 260.00, null);

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (47, 7, '专科流程', 'MENU', '/specialty', 'med:specialty', 'Stethoscope', 12);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (47);
