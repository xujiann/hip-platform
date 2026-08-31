-- v33：检验/影像双通道危急值闭环。
-- 原 outp_critical_alert 只有 NEW→HANDLED（LabResultController.handle，处置人是随手点的技师，
-- 非开单医师，无通知/接收确认/时限/处置留痕），与《危急值报告制度》要求的
-- "报告→通知开单医师→限时接收确认→处置留痕"落差大。扩展为可闭环、可追溯、可超期看板。
alter table outp_critical_alert add column source            varchar(8)   not null default 'LAB';  -- LAB/RIS 双通道
alter table outp_critical_alert add column notify_to_user_id bigint references sys_user (id);       -- 应通知的开单医师
alter table outp_critical_alert add column notified_at       timestamptz;                            -- 生成即通知时刻
alter table outp_critical_alert add column deadline_at       timestamptz;                            -- 应确认时限（生成+配置分钟）
alter table outp_critical_alert add column ack_by            bigint references sys_user (id);        -- 接收确认人（开单医师）
alter table outp_critical_alert add column ack_at            timestamptz;                            -- 接收确认时刻
alter table outp_critical_alert add column disposition       varchar(512);                           -- 处置措施留痕
-- 未确认（NEW）的按医师/超期查询走索引
create index idx_critical_alert_open on outp_critical_alert (notify_to_user_id, status) where status = 'NEW';

-- 影像危急值（气胸/夹层/颅内出血等）：RIS 报告侧标记，复用同一告警闭环
alter table ris_exam add column critical_flag boolean       not null default false;
alter table ris_exam add column critical_note varchar(512);

-- 危急值应确认时限（分钟），各院按《危急值报告制度》配（默认 10 分钟）
insert into sys_config (cfg_key, cfg_value, remark)
values ('critical_ack_deadline_minutes', '10', '危急值应接收确认时限（分钟），超时进超期看板')
on conflict (cfg_key) do nothing;

-- 菜单：危急值确认台（开单医师接收确认+处置），挂 门诊业务 DIR=7，授 ADMIN + 门诊医生
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (99, 7, '危急值确认', 'MENU', '/outpatient/critical-alerts', 'outp:critical:ack', 'Warning', 6);
insert into sys_role_menu (role_id, menu_id)
select r.id, 99 from sys_role r where r.code in ('ADMIN', 'DOCTOR_OUTP');
