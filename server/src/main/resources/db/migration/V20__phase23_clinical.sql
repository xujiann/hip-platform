-- 二十三期：临床闭环补缺（门诊护士站 / 检查检验时段预约 / 叫号扩展 / 合理用血 / 单病种）

-- 门诊护士站：皮试登记
create table outp_skin_test (
    id              bigserial primary key,
    registration_id bigint      not null references outp_registration (id),
    drug_name       varchar(128) not null,
    result          varchar(8)  not null default 'PENDING',  -- PENDING/NEG/POS
    nurse_id        bigint references sys_user (id),
    tested_at       timestamptz,
    created_at      timestamptz not null default now()
);

-- 门诊护士站：输液执行单（来源=已收费/已发药的药品医嘱）
create table outp_infusion (
    id          bigserial primary key,
    order_id    bigint      not null unique references outp_order (id),
    status      varchar(8)  not null default 'PENDING',      -- PENDING/RUNNING/DONE
    nurse_id    bigint references sys_user (id),
    started_at  timestamptz,
    finished_at timestamptz,
    created_at  timestamptz not null default now()
);

-- 输液巡视记录
create table outp_infusion_check (
    id          bigserial primary key,
    infusion_id bigint       not null references outp_infusion (id),
    note        varchar(500) not null,
    nurse_id    bigint references sys_user (id),
    checked_at  timestamptz  not null default now()
);

-- 检查/检验时段预约
create table med_appointment (
    id         bigserial primary key,
    order_id   bigint      not null unique references outp_order (id),
    slot_date  date        not null,
    period     varchar(4)  not null,                          -- AM/PM
    seq_no     int         not null,
    status     varchar(8)  not null default 'BOOKED',         -- BOOKED/CALLED/DONE
    created_at timestamptz not null default now()
);

-- 通用叫号流水（药房取药 / 检查 / 检验）
create table queue_call_log (
    id           bigserial primary key,
    biz_type     varchar(16) not null,                        -- PHARMACY/EXAM/LAB
    biz_key      varchar(64) not null,                        -- 处方号或预约 id
    patient_name varchar(64),
    called_at    timestamptz not null default now()
);
create index idx_queue_call_log_type_time on queue_call_log (biz_type, called_at);

-- 合理用血
create table blood_apply (
    id               bigserial primary key,
    admission_id     bigint       not null references inp_admission (id),
    product          varchar(16)  not null,                   -- RBC/PLASMA/PLT
    volume_ml        int          not null,
    reason           varchar(500),
    status           varchar(16)  not null default 'APPLIED', -- APPLIED/APPROVED/REJECTED/ISSUED/TRANSFUSED
    apply_doctor_id  bigint references sys_user (id),
    review_note      varchar(500),
    transfusion_note varchar(1000),
    approved_at      timestamptz,
    issued_at        timestamptz,
    transfused_at    timestamptz,
    created_at       timestamptz  not null default now()
);

-- 单病种
create table sd_disease_def (
    code       varchar(16)  primary key,
    name       varchar(128) not null,
    icd_prefix varchar(16)  not null
);

create table sd_case_report (
    id           bigserial primary key,
    admission_id bigint      not null references inp_admission (id),
    disease_code varchar(16) not null references sd_disease_def (code),
    dataset      text,
    status       varchar(8)  not null default 'DRAFT',        -- DRAFT/REPORTED
    reported_at  timestamptz,
    created_at   timestamptz not null default now()
);

insert into sd_disease_def (code, name, icd_prefix) values
    ('AMI',    '急性心肌梗死', 'I21'),
    ('STROKE', '急性脑梗死',   'I63'),
    ('CAP',    '社区获得性肺炎', 'J15'),
    ('HF',     '心力衰竭',     'I50');

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (43, 7,  '门诊护士站', 'MENU', '/outpatient/nurse',     'outp:nurse',  'FirstAidKit', 10),
    (44, 7,  '预约与叫号', 'MENU', '/medtech/appointments', 'med:appt',    'AlarmClock', 11),
    (45, 18, '用血管理',   'MENU', '/inpatient/blood',      'inp:blood',   'Opportunity', 7),
    (46, 25, '单病种上报', 'MENU', '/quality/single-disease', 'qc:sd',     'Memo', 4);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (43, 44, 45, 46);
