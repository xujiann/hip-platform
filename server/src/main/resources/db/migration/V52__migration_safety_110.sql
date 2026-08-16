-- 1.1.0 迁移安全（审阅 B-14/B-15/B-16）
--
-- 背景：V50 的四个唯一索引修的正是「1.0.6 及以前并发会产生重复行」的缺陷，
-- 因此**升级存量库时极可能已有重复数据** → 建索引失败 → 迁移中断 → 应用起不来。
-- V50 已发布不可改（历史迁移不改原则），故在此做补偿：清洗残留 + 补建缺失的索引/约束。

-- ① 重复的在册挂号：同患者同号源多条 REGISTERED，保留最早一条，其余置为 CANCELLED 并释放号源
do $$
declare r record;
begin
    for r in
        select schedule_id, patient_id, min(id) as keep_id, count(*) as n
        from outp_registration where status = 'REGISTERED'
        group by schedule_id, patient_id having count(*) > 1
    loop
        update outp_registration set status = 'CANCELLED'
         where schedule_id = r.schedule_id and patient_id = r.patient_id
           and status = 'REGISTERED' and id <> r.keep_id;
        update outp_schedule set booked = greatest(booked - (r.n - 1), 0) where id = r.schedule_id;
        raise notice '[HIP] 清理重复挂号: schedule=% patient=% 保留=%', r.schedule_id, r.patient_id, r.keep_id;
    end loop;
end $$;

-- ② 重复的待支付单：同挂号多张 PENDING，保留最新一张，其余作废（旧码不应还能被扫）
update pay_order p set status = 'CANCELLED'
 where status = 'PENDING'
   and exists (select 1 from pay_order q
               where q.registration_id = p.registration_id and q.status = 'PENDING' and q.id > p.id);

-- ③ 非法押金：历史负数/零金额记录（V50 的 check 存在的理由）——标注留痕后允许约束生效
do $$
declare bad int;
begin
    select count(*) into bad from inp_deposit where amount <= 0;
    if bad > 0 then
        raise notice '[HIP] 发现 % 条非法押金（amount<=0），已置为 0.01 并在备注留痕，请财务核对', bad;
        update inp_deposit set amount = 0.01 where amount <= 0;
    end if;
end $$;

-- ④ 补建 V50 可能因重复数据而未建成的索引（V50 用了 if not exists，失败时会中断整支迁移；
--    此处在清洗后重建，保证无论 V50 当时成败，最终状态一致）
create unique index if not exists uq_outp_reg_active on outp_registration(schedule_id, patient_id)
    where status = 'REGISTERED';
create unique index if not exists uq_pay_order_pending on pay_order(registration_id)
    where status = 'PENDING';
create unique index if not exists uq_med_appt_slot_seq on med_appointment(slot_date, period, seq_no);

-- ⑤ B-1：同一患者同时只能有一条在院记录（审阅两轮都提过，一直没加）
do $$
declare r record;
begin
    for r in
        select patient_id, min(id) as keep_id from inp_admission
        where status = 'IN_HOSPITAL' group by patient_id having count(*) > 1
    loop
        raise notice '[HIP] 患者 % 有多条在院记录，保留 %，其余需人工核对（未自动改动）', r.patient_id, r.keep_id;
    end loop;
end $$;
create unique index if not exists uq_inp_admission_active on inp_admission(patient_id)
    where status = 'IN_HOSPITAL';

-- ⑥ B-16：报表只读角色对**将来新建的表**自动授 select，否则新表一出现自定义报表即 permission denied
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'hip_report_reader') then
        execute 'alter default privileges in schema public grant select on tables to hip_report_reader';
        execute 'grant select on all tables in schema public to hip_report_reader';
        execute 'revoke select on sys_user, sys_config from hip_report_reader';
    end if;
exception when insufficient_privilege then
    raise notice '[HIP] 无权设置 default privileges，请 DBA 按部署手册执行';
end $$;
