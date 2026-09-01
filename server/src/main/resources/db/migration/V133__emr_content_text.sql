-- v42 第 5 项：病历模板可用化 + PREOP 幽灵类型修复 + 病历正文放宽。
-- 本迁移只做两件纯增量的事：放宽一列的类型 + 加一个维护菜单。无新表、无新约束、无数据订正。

-- ===== 1) inp_medical_record.content：varchar(4000) → text =====
-- PostgreSQL 中 varchar(n) → text 是**元数据级操作**：不重写表、不重建索引、不取表级 ACCESS
-- EXCLUSIVE 之外的额外代价（秒级 DDL）。该列**无任何索引**——V9:13 的 idx_inp_record_adm 与
-- V115:9 的查房索引都只含 admission_id / record_type，不含 content。
--
-- 动因（不是"以防万一"）：纯手打病程撞不到 4000 字符，但一旦做模板渲染（主诉 / 现病史 /
-- 既往史 / 体格检查 / 辅助检查 / 初步诊断多段拼接）必然超限——对照门诊侧 outp_emr 同类五字段
-- 合计已达 5512 字符。这是 v44 病历结构化（content_json 侧车列）的必要前置：先让文本主存储
-- 装得下，结构化才有落点。
--
-- 刻意不做：**不给 record_type 加 CHECK 约束**。试点库与实施期已入库的历史脏类型会让本迁移
-- 在数据库层直接失败，升级失败的代价远高于脏数据本身；写入侧的 recordType 白名单（改既有
-- 写路径）已明确排 v43 单独成版。
alter table inp_medical_record alter column content type text;

-- ===== 2) 病历模板维护菜单 =====
-- 归"数据中心/病案质控"目录（parent_id=25，与病案首页 63 / 病案统计 54 / 病案复印 90 同组）。
-- emr_template 的 CRUD 端点（MedTechController）与 template_type 分类（V116）早已就位，
-- 但此前唯一消费方是 RIS 报告页，病历模板既没人写也没地方维护——本菜单是它的入口。
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (109, 25, '病历模板', 'MENU', '/emr-template', 'emr:template', 'Notebook', 12);

-- 授权：管理员 + 医生（模板的实际使用者）+ 质控病案（模板的实际维护者）
insert into sys_role_menu (role_id, menu_id)
select r.id, 109 from sys_role r
where r.code in ('ADMIN', 'DOCTOR_OUTP', 'QUALITY')
  and not exists (select 1 from sys_role_menu x where x.role_id = r.id and x.menu_id = 109);

-- 补父级目录（V36:50 同款）：角色只有子菜单没有父 DIR 时前端树渲染不出——DOCTOR_OUTP 此前
-- 并不持有"数据中心"目录，不补这一句新菜单对医生角色是隐形的。
insert into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, p.id
from sys_role_menu rm
join sys_menu m on m.id = rm.menu_id
join sys_menu p on p.id = m.parent_id
where not exists (select 1 from sys_role_menu x where x.role_id = rm.role_id and x.menu_id = p.id);

-- 序列纠偏（V128 末尾同款）：sys_menu_id_seq 与显式 id 种子并行，每次显式插菜单后须推到 max(id)，
-- 否则将来任何走 nextval 的建菜单（管理端自定义菜单）必撞主键。
select setval('sys_menu_id_seq', (select max(id) from sys_menu));
