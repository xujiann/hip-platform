-- 三十期：病案与临床专科深化（路径变异 / 麻醉记录单 / 体检团检）

-- 临床路径：入径登记（此前 apply 只开医嘱不留痕）
create table cp_enrollment (
    id           bigserial primary key,
    admission_id bigint      not null references inp_admission (id),
    template_id  bigint      not null references cp_pathway_template (id),
    status       varchar(16) not null default 'ACTIVE',   -- ACTIVE/COMPLETED/EXITED
    enrolled_at  timestamptz not null default now(),
    ended_at     timestamptz,
    constraint uq_cp_enroll unique (admission_id, template_id)
);

-- 路径变异记录（超期/退出等偏差）
create table cp_variance (
    id            bigserial primary key,
    enrollment_id bigint       not null references cp_enrollment (id),
    day_no        int,
    var_type      varchar(8)   not null,                  -- DELAY 超期 / EXIT 退出 / OTHER
    reason        varchar(500) not null,
    created_at    timestamptz  not null default now()
);

-- 麻醉记录单：术中生命体征时间轴 + PACU 苏醒记录
create table anes_record (
    id            bigserial primary key,
    surgery_id    bigint      not null references inp_surgery (id),
    phase         varchar(8)  not null default 'INTRA',   -- INTRA 术中 / PACU 复苏
    recorded_at   timestamptz not null default now(),
    hr            int,
    sbp           int,
    dbp           int,
    spo2          int,
    steward_score int,                                     -- PACU 苏醒评分 0-6
    note          varchar(255),
    constraint chk_steward check (steward_score is null or steward_score between 0 and 6)
);
create index idx_anes_surgery on anes_record (surgery_id, recorded_at);

-- 体检团检：单位团体
create table pe_group (
    id         bigserial primary key,
    unit_name  varchar(128) not null,
    contact    varchar(64),
    package_id bigint       not null references pe_exam_package (id),
    created_at timestamptz  not null default now()
);

alter table pe_exam_record add column group_id bigint references pe_group (id);
alter table pe_exam_record add column abnormal boolean not null default false;

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (54, 25, '病案统计', 'MENU', '/mrstats', 'mr:stats', 'Histogram', 8);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (54);
