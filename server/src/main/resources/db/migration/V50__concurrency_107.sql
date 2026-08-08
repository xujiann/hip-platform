-- 1.0.7 并发与数据正确性（docs/代码审阅-20260808.md P1）

-- P1-A：内存自造单号 → 数据库序列（重启回绕撞号；V45 门诊处方号已用同一手法）
create sequence inp_order_group_seq  start 1;
create sequence inv_stock_in_seq     start 1;
create sequence pay_order_seq        start 1;
create sequence lis_barcode_seq      start 1;
create sequence path_specimen_seq    start 1;

-- 撞号静默入库的兜底：住院医嘱组号唯一性由序列保证，这里补索引便于按组检索
create index if not exists idx_inp_order_group on inp_order(group_no);

-- P1-19：重复挂号防线由 find-then-insert 改为唯一约束（并发下两条 REGISTERED、两笔挂号费）
create unique index uq_outp_reg_active on outp_registration(schedule_id, patient_id)
    where status = 'REGISTERED';

-- P1-3：同一挂号同时只允许一张待支付单（并发出码→两张 PENDING，两笔钱只有一笔能结算）
create unique index uq_pay_order_pending on pay_order(registration_id)
    where status = 'PENDING';

-- P1-30：单病种候选改看出院诊断（入院 J06 出院 I21 的病例原本漏出）；补建卡唯一约束
create unique index if not exists uq_sd_case_report on sd_case_report(admission_id, disease_code);

-- P1-31：退费按退费日归集（原按结算单创建日：D1 收费 D2 退费 → D2 报表看不到、D1 历史报表变脸）
alter table outp_charge add column refunded_at timestamptz;
update outp_charge set refunded_at = created_at where status = 'REFUNDED';

-- P1-14/15：医保分割冲销幂等（并发退费重复冲销年度额度；补跑重复吃起付线）
alter table yb_settle_split add column reversed boolean not null default false;
update yb_settle_split set reversed = true
 where charge_no in (select charge_no from outp_charge where status = 'REFUNDED');

-- P1-25：押金金额非法值（负数冲减押金总额）
alter table inp_deposit add constraint ck_inp_deposit_amount check (amount > 0);

-- P1-29：CDR 增量水位上推链补漏
-- ① 门诊医嘱变更须 touch 挂号（就诊文档内嵌 orders 段，追加/作废医嘱原本抓不到）
create or replace function hip_touch_reg_from_order() returns trigger as $$
begin
    update outp_registration set updated_at = now()
     where id = coalesce(new.registration_id, old.registration_id);
    return coalesce(new, old);
end;
$$ language plpgsql;
create trigger trg_outp_order_touch_reg after insert or update or delete on outp_order
    for each row execute function hip_touch_reg_from_order();

-- ② 子表 DELETE 也须上推（原触发器只声明 insert or update，删诊断后 CDR 残留已删内容）
create or replace function hip_touch_outp_registration() returns trigger as $$
begin
    update outp_registration set updated_at = now()
     where id = coalesce(new.registration_id, old.registration_id);
    return coalesce(new, old);
end;
$$ language plpgsql;
create or replace function hip_touch_outp_order() returns trigger as $$
begin
    update outp_order set updated_at = now() where id = coalesce(new.order_id, old.order_id);
    return coalesce(new, old);
end;
$$ language plpgsql;
create or replace function hip_touch_inp_admission() returns trigger as $$
begin
    update inp_admission set updated_at = now()
     where id = coalesce(new.admission_id, old.admission_id);
    return coalesce(new, old);
end;
$$ language plpgsql;

drop trigger if exists trg_outp_emr_touch on outp_emr;
create trigger trg_outp_emr_touch after insert or update or delete on outp_emr
    for each row execute function hip_touch_outp_registration();
drop trigger if exists trg_outp_diagnosis_touch on outp_diagnosis;
create trigger trg_outp_diagnosis_touch after insert or update or delete on outp_diagnosis
    for each row execute function hip_touch_outp_registration();
drop trigger if exists trg_outp_lab_result_touch on outp_lab_result;
create trigger trg_outp_lab_result_touch after insert or update or delete on outp_lab_result
    for each row execute function hip_touch_outp_order();
drop trigger if exists trg_inp_medical_record_touch on inp_medical_record;
create trigger trg_inp_medical_record_touch after insert or update or delete on inp_medical_record
    for each row execute function hip_touch_inp_admission();
drop trigger if exists trg_inp_settlement_touch on inp_settlement;
create trigger trg_inp_settlement_touch after insert or update or delete on inp_settlement
    for each row execute function hip_touch_inp_admission();
drop trigger if exists trg_inp_diagnosis_touch on inp_diagnosis;
create trigger trg_inp_diagnosis_touch after insert or update or delete on inp_diagnosis
    for each row execute function hip_touch_inp_admission();
