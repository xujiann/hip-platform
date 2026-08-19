-- 规模实证灌数（v22 方向 B）：10 万级门诊数据，直连 SQL 生成（走 API 太慢）。
-- 用法：在专用库（如 hip_scale，先起一次应用完成迁移）执行：
--   PGPASSWORD=hip123456 psql -h 127.0.0.1 -U hip -d hip_scale -f tools/seed-volume.sql
-- 生成：10 万患者 / 10 万挂号（摊 90 天）/ 20 万医嘱行 / 5 万结算 / 5 万病历+诊断（喂 CDR）。
-- 幂等：SV 患者号段/单号段整段清理后重灌。列名以 information_schema 实测为准（2026-08-18）。

\timing on

begin;

delete from outp_diagnosis where registration_id in
    (select r.id from outp_registration r join empi_patient p on p.id = r.patient_id where p.patient_no like 'SV%');
delete from outp_emr where registration_id in
    (select r.id from outp_registration r join empi_patient p on p.id = r.patient_id where p.patient_no like 'SV%');
delete from yb_settle_split where charge_no like 'SV%';
delete from outp_order where group_no like 'SV%';
delete from outp_charge where charge_no like 'SV%';
delete from outp_registration where id in
    (select r.id from outp_registration r join empi_patient p on p.id = r.patient_id where p.patient_no like 'SV%');
delete from outp_schedule where capacity = 2000 and fee = 10;
delete from empi_patient where patient_no like 'SV%';

-- 1) 10 万患者
insert into empi_patient(patient_no, name, sex, phone)
select 'SV' || lpad(g::text, 8, '0'), '灌数患者' || g, case when g % 2 = 0 then 'M' else 'F' end,
       '139' || lpad((g % 100000000)::text, 8, '0')
from generate_series(1, 100000) g;

-- 2) 90 天排班（每天 1 个大容量号源，capacity=2000 作为灌数标记）
insert into outp_schedule(dept_id, schedule_date, fee, capacity, booked, enabled)
select 1, current_date - g, 10, 2000, 0, true
from generate_series(0, 89) g;

-- 3) 10 万挂号：摊 90 天（reg_no 为当日号序 int）
insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status, created_at, updated_at)
select (g / 90) + 1,
       p.id, s.id, 1, s.schedule_date, 10, 'VISITED',
       s.schedule_date::timestamptz + interval '9 hours' + (g % 28800) * interval '1 second',
       s.schedule_date::timestamptz + interval '9 hours'
from generate_series(1, 100000) g
join empi_patient p on p.patient_no = 'SV' || lpad(g::text, 8, '0')
join outp_schedule s on s.schedule_date = current_date - (g % 90) and s.capacity = 2000;

-- 4) 20 万医嘱行（每挂号 2 行：1 药 1 检验），偶数挂号已收费
insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                       unit, qty, unit_price, amount, status, created_at, updated_at)
select r.id, 'SV' || r.id || '-' || t.n,
       case t.n when 1 then 'DRUG' else 'LAB' end,
       1, 'SVIT' || t.n, case t.n when 1 then '灌数药品' else '灌数检验' end,
       '次', 1, 25.00, 25.00,
       case when r.id % 2 = 0 then 'CHARGED' else 'CREATED' end,
       r.created_at, r.created_at
from outp_registration r
join empi_patient p on p.id = r.patient_id
cross join (values (1), (2)) as t(n)
where p.patient_no like 'SV%';

-- 5) 5 万结算单（偶数挂号）
insert into outp_charge(charge_no, registration_id, total_amount, pay_method, status, created_at)
select 'SV' || lpad(r.id::text, 12, '0'), r.id, 50.00,
       case when r.id % 4 = 0 then 'YB' else 'CASH' end, 'PAID', r.created_at + interval '10 minutes'
from outp_registration r
join empi_patient p on p.id = r.patient_id
where p.patient_no like 'SV%' and r.id % 2 = 0;

-- 6) 5 万病历 + 诊断（CDR 文档内容源；emr.updated_at 驱动增量水位）
insert into outp_emr(registration_id, chief_complaint, present_illness, updated_at)
select r.id, '灌数主诉：咳嗽', '灌数现病史', r.created_at
from outp_registration r join empi_patient p on p.id = r.patient_id
where p.patient_no like 'SV%' and r.id % 2 = 0;

insert into outp_diagnosis(registration_id, icd_code, icd_name, primary_diag)
select r.id, 'J06.9', '急性上呼吸道感染', true
from outp_registration r join empi_patient p on p.id = r.patient_id
where p.patient_no like 'SV%' and r.id % 2 = 0;

commit;

analyze empi_patient; analyze outp_registration; analyze outp_order; analyze outp_charge; analyze outp_emr;

select 'patients' as t, count(*) from empi_patient where patient_no like 'SV%'
union all select 'registrations', count(*) from outp_registration r
    join empi_patient p on p.id = r.patient_id where p.patient_no like 'SV%'
union all select 'orders', count(*) from outp_order where group_no like 'SV%'
union all select 'charges', count(*) from outp_charge where charge_no like 'SV%';
