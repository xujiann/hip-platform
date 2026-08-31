-- v37 门诊分时段预约挂号：排班下分时段（slot）+ 预约单（BOOKED→签到转正式挂号/取消）。
-- walk-in 挂号 register() 完全不动；预约与 walk-in 共享排班容量池（两级占号，事务内一致）。
create table outp_schedule_slot (
    id          bigserial primary key,
    schedule_id bigint  not null references outp_schedule (id),
    time_begin  time    not null,
    time_end    time    not null,
    seq_no      int     not null default 0,
    capacity    int     not null,
    booked      int     not null default 0,
    enabled     boolean not null default true,
    constraint chk_slot_booked check (booked >= 0 and booked <= capacity),
    constraint uq_slot unique (schedule_id, time_begin)
);
create index idx_slot_schedule on outp_schedule_slot (schedule_id);

create table outp_appointment (
    id           bigserial primary key,
    slot_id      bigint      not null references outp_schedule_slot (id),
    schedule_id  bigint      not null references outp_schedule (id),
    patient_id   bigint      not null references empi_patient (id),
    appt_no      int         not null,               -- 时段内序号
    status       varchar(16) not null default 'BOOKED',   -- BOOKED / CHECKED_IN / CANCELLED
    source       varchar(16),                        -- 来源（窗口/门户）
    registration_id bigint references outp_registration (id),   -- 签到转挂号后回填
    created_at   timestamptz not null default now(),
    cancelled_at timestamptz,
    constraint ck_appt_status check (status in ('BOOKED', 'CHECKED_IN', 'CANCELLED'))
);
-- 同患者同排班仅一条有效预约（防重复预约；取消后可再约）
create unique index uq_appt_active on outp_appointment (schedule_id, patient_id) where status = 'BOOKED';
create index idx_appt_slot on outp_appointment (slot_id);

-- 菜单：预约挂号（门诊业务 DIR=7），授 ADMIN + 收费员（挂号台）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (102, 7, '预约挂号', 'MENU', '/outpatient/appointment', 'outp:appt', 'AlarmClock', 3);
insert into sys_role_menu (role_id, menu_id)
select r.id, 102 from sys_role r where r.code in ('ADMIN', 'CASHIER');
