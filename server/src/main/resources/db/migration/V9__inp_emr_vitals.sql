-- 住院病历记录与生命体征

create table inp_medical_record (
    id           bigserial primary key,
    admission_id bigint        not null references inp_admission (id),
    record_type  varchar(16)   not null,
    title        varchar(100)  not null,
    content      varchar(4000) not null,
    doctor_id    bigint references sys_user (id),
    created_at   timestamptz   not null default now()
);

create index idx_inp_record_adm on inp_medical_record (admission_id);

create table inp_vital_sign (
    id           bigserial primary key,
    admission_id bigint      not null references inp_admission (id),
    measured_at  timestamptz not null default now(),
    temperature  numeric(4,1),
    pulse        int,
    respiration  int,
    sbp          int,
    dbp          int,
    spo2         int,
    recorder_id  bigint references sys_user (id)
);

create index idx_inp_vital_adm on inp_vital_sign (admission_id);
