-- 门诊/病历侧收尾环（阻塞4 + 阻塞7）：
--   1) 病历签名后合规补正（emr_amendment）——签名冻结的病历不改原文，追加法定可追溯补正记录
--   2) 病案首页查看/打印入口菜单（复用 qualitycare 既有 front-page 组装端点的增强版）
-- 断货医生开单感知（阻塞6）无新表：仅在开单返回值上带库存预警标志，不落库。

-- ===== 补正记录（门诊 outp_emr / 住院 inp_medical_record 共用一张） =====
-- 签名冻结后医生发现错字：原文保留 + 补正内容 + 补正人 + 补正时间 + 补正原因，
-- 形成法定"留痕修改"痕迹，而非直接改原文。
create table emr_amendment (
    id            bigserial   primary key,
    -- 病历类型：OUTP 门诊病历(outp_emr) / INP 住院病历(inp_medical_record)
    emr_type      varchar(8)  not null,
    -- 关联病历主键：emr_type=OUTP → outp_emr.id；emr_type=INP → inp_medical_record.id
    emr_id        bigint      not null,
    -- 补正时原文快照（留痕，事后原文若再被系统改动仍可回看补正当时的原文）
    original_text text,
    -- 补正内容（追加的正确表述）
    amend_text    text        not null,
    -- 补正原因（法定要求）
    reason        varchar(500) not null,
    -- 补正人 sys_user.id
    amended_by    bigint,
    amended_at    timestamptz not null default now(),
    constraint chk_emr_amend_type check (emr_type in ('OUTP', 'INP'))
);
-- 按病历取补正历史（时间正序展示）
create index idx_emr_amendment_ref on emr_amendment (emr_type, emr_id, id);

-- ===== 病案首页查看/打印菜单 =====
-- 归属"病案/质控"目录（parent_id=25，与病案统计 54 同组）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (63, 25, '病案首页', 'MENU', '/mrfront', 'mr:front', 'Tickets', 9);

-- 授权：管理员 + 质控（病案室）角色
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'QUALITY') and m.id = 63
  and not exists (select 1 from sys_role_menu x where x.role_id = r.id and x.menu_id = m.id);
