-- 1.1.1 工程基线（审阅 P2）

-- ① 状态列长度：41 个 varchar(8) 的状态列里，'REPORTED' 正好 8 字符，
--    再加一个 'CANCELLED'(9)/'SUBMITTED'(9) 就复现历史事故（值放不下 → 22001 → 500）
alter table sd_case_report  alter column status type varchar(16);
alter table outp_infusion   alter column status type varchar(16);
alter table er_observation  alter column status type varchar(16);
alter table svc_window      alter column status type varchar(16);
alter table lis_sample      alter column status type varchar(16);
alter table ris_exam        alter column status type varchar(16);
alter table path_specimen   alter column status type varchar(16);
alter table blood_apply     alter column status type varchar(16);
alter table inp_surgery     alter column status type varchar(16);

-- ② 关键枚举列加 CHECK：全库此前零个状态类约束。
--    `empi_patient.insurance_type` 无白名单最要命——未知值使医保分割比例取 0，
--    患者被静默全额自费且无任何告警。
do $$ begin
    alter table empi_patient add constraint ck_patient_sex check (sex in ('M', 'F', 'U'));
exception when duplicate_object then null; when check_violation then
    raise notice '[HIP] empi_patient.sex 存在非法值，未加约束，请先订正数据';
end $$;

do $$ begin
    alter table empi_patient add constraint ck_patient_insurance
        check (insurance_type is null or insurance_type in ('SELF', 'YB_STAFF', 'YB_EMPLOYEE', 'YB_RESIDENT'));
exception when duplicate_object then null; when check_violation then
    raise notice '[HIP] empi_patient.insurance_type 存在非法值，未加约束，请先订正数据';
end $$;

do $$ begin
    alter table outp_charge add constraint ck_charge_status
        check (status in ('PAID', 'REFUNDED'));
exception when duplicate_object then null; when check_violation then
    raise notice '[HIP] outp_charge.status 存在非法值，未加约束';
end $$;

do $$ begin
    alter table pay_order add constraint ck_pay_order_status
        check (status in ('PENDING', 'SUCCESS', 'CANCELLED', 'MISMATCH'));
exception when duplicate_object then null; when check_violation then
    raise notice '[HIP] pay_order.status 存在非法值，未加约束';
end $$;

-- ③ 索引批：审阅指出的高频过滤/连接列（试点量级尚不痛，上量必痛）
create index if not exists idx_outp_order_type_status on outp_order(order_type, status);
create index if not exists idx_inp_admission_status on inp_admission(status);
create index if not exists idx_inp_admission_patient on inp_admission(patient_id);
create index if not exists idx_inp_admission_dept on inp_admission(dept_id);
create index if not exists idx_inp_admission_ward on inp_admission(ward_id);
create index if not exists idx_inp_deposit_adm on inp_deposit(admission_id);
create index if not exists idx_inp_transfer_adm on inp_transfer_log(admission_id);
create index if not exists idx_inp_order_item on inp_order(item_id);
create index if not exists idx_outp_critical_status on outp_critical_alert(status);
create index if not exists idx_inp_bed_admission on inp_bed(admission_id);
create index if not exists idx_drg_case_admission on drg_case(admission_id);
create index if not exists idx_med_appt_order on med_appointment(order_id);
-- 日结/对账/票据打印六处此前对 outp_charge 全表扫
create index if not exists idx_outp_charge_created on outp_charge(created_at);
create index if not exists idx_outp_charge_status_created on outp_charge(status, created_at);

-- ④ 观测表归档：三张只增不减的表无任何清理策略。
--    ops_slow_api 还有放大效应（DB 变慢 → 更多请求超阈值 → 每个都同步 insert → 更慢）。
--    审计按等保要求保留 6 个月，其余保留 90 天。
create or replace function hip_purge_observability() returns void as $$
begin
    delete from ops_slow_api where occurred_at < now() - interval '90 days';
    delete from int_message_log where created_at < now() - interval '90 days';
    delete from sys_audit_log where created_at < now() - interval '180 days';
end;
$$ language plpgsql;

comment on function hip_purge_observability() is
    '观测表归档：慢接口/集成报文保留 90 天，审计保留 180 天（等保要求≥6 个月）。由 OpsHealthScheduler 每日调用。';
