-- 1.0.4 CDR 数据正确性专项（规划 v18 方向 B）

-- 1) 三张同步根表补 updated_at + 触发器维护（原按 created_at 过滤抓不到「创建在前、变更在后」）
alter table outp_registration add column updated_at timestamptz not null default now();
alter table outp_order        add column updated_at timestamptz not null default now();
alter table inp_admission     add column updated_at timestamptz not null default now();

create or replace function hip_touch_updated_at() returns trigger as $$
begin
    new.updated_at := now();
    return new;
end;
$$ language plpgsql;

create trigger trg_outp_registration_touch before update on outp_registration
    for each row execute function hip_touch_updated_at();
create trigger trg_outp_order_touch before update on outp_order
    for each row execute function hip_touch_updated_at();
create trigger trg_inp_admission_touch before update on inp_admission
    for each row execute function hip_touch_updated_at();

-- 2) 子表变更上推根表（病历/诊断→就诊；检验结果→医嘱；住院文书/结算/诊断→住院）
create or replace function hip_touch_outp_registration() returns trigger as $$
begin
    update outp_registration set updated_at = now() where id = new.registration_id;
    return new;
end;
$$ language plpgsql;
create trigger trg_outp_emr_touch after insert or update on outp_emr
    for each row execute function hip_touch_outp_registration();
create trigger trg_outp_diagnosis_touch after insert or update on outp_diagnosis
    for each row execute function hip_touch_outp_registration();

create or replace function hip_touch_outp_order() returns trigger as $$
begin
    update outp_order set updated_at = now() where id = new.order_id;
    return new;
end;
$$ language plpgsql;
create trigger trg_outp_lab_result_touch after insert or update on outp_lab_result
    for each row execute function hip_touch_outp_order();

create or replace function hip_touch_inp_admission() returns trigger as $$
begin
    update inp_admission set updated_at = now() where id = new.admission_id;
    return new;
end;
$$ language plpgsql;
create trigger trg_inp_medical_record_touch after insert or update on inp_medical_record
    for each row execute function hip_touch_inp_admission();
create trigger trg_inp_settlement_touch after insert or update on inp_settlement
    for each row execute function hip_touch_inp_admission();
create trigger trg_inp_diagnosis_touch after insert or update on inp_diagnosis
    for each row execute function hip_touch_inp_admission();

create index idx_outp_registration_updated on outp_registration(updated_at);
create index idx_outp_order_updated on outp_order(updated_at);
create index idx_inp_admission_updated on inp_admission(updated_at);

-- 3) 出院诊断（ICD-10）与手术操作编码（ICD-9-CM-3）：病案首页质量与 DRG 入组精度双受益
alter table inp_admission add column discharge_diag_icd  varchar(16);
alter table inp_admission add column discharge_diag_name varchar(128);
alter table inp_surgery   add column op_icd varchar(16);

-- 4) 住院病历 CA 签名（与门诊 outp_emr 齐平）
alter table inp_medical_record add column signature varchar(512);
alter table inp_medical_record add column signed_at timestamptz;

-- 5) CDR 自动增量同步开关
insert into sys_config (cfg_key, cfg_value, remark) values
    ('cdr_auto_sync_enabled', '1', '每日 02:10 自动增量同步 CDR（0=关闭）');
