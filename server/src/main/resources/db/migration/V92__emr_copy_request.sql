-- 上线检查单·车道B 临床收尾②：病历复印（病案室对患者的法定职能，此前零实现）
-- 法定要求全程留痕：谁申请、复印了什么范围、用途、谁经办、何时出件。
-- 生命周期：APPLIED 受理 → REGISTERED 登记(生成复印登记号) → ISSUED 出复印件(打印时盖"复印件"水印)。

create table emr_copy_request (
    id                 bigserial    primary key,
    patient_id         bigint       not null references empi_patient (id),
    -- 复印的住院病案（本期以住院病案为主，门诊复印可空，留待实施期扩展）
    admission_id       bigint       references inp_admission (id),
    applicant_name     varchar(64)  not null,          -- 申请人（患者本人/家属/医保/商保/司法机关）
    applicant_relation varchar(32),                    -- 与患者关系 SELF/FAMILY/INSURER/LEGAL
    applicant_id_no    varchar(32),                    -- 申请人证件号（法定留痕，核验身份）
    copy_scope         varchar(500) not null,          -- 复印范围（病案首页/出院记录/全部病历/检查报告…）
    purpose            varchar(128) not null,          -- 用途（医保报销/商业保险/法律诉讼/转诊）
    copies             int          not null default 1,-- 份数
    status             varchar(16)  not null default 'APPLIED',  -- APPLIED/REGISTERED/ISSUED
    reg_no             varchar(32),                    -- 复印登记号（登记环节生成，出复印件时打印在件上）
    applied_by         bigint       references sys_user (id),     -- 受理人
    operator_id        bigint       references sys_user (id),     -- 经办人（出复印件）
    applied_at         timestamptz  not null default now(),
    registered_at      timestamptz,
    issued_at          timestamptz,
    constraint chk_copy_status check (status in ('APPLIED', 'REGISTERED', 'ISSUED')),
    constraint chk_copy_copies check (copies >= 1)
);
create index idx_emr_copy_patient on emr_copy_request (patient_id, id desc);
-- 复印登记号唯一（生成后不可重号），部分索引放过尚未登记的 null
create unique index uq_emr_copy_reg_no on emr_copy_request (reg_no) where reg_no is not null;

-- ===== 病案室复印菜单 =====
-- 归"数据中心/病案质控"目录（parent_id=25，与病案首页 63、病案统计 54 同组）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (90, 25, '病案复印', 'MENU', '/emr-copy', 'mr:copy', 'DocumentCopy', 11);
-- 授权：管理员 + 质控（病案室）角色
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code in ('ADMIN', 'QUALITY') and m.id = 90
  and not exists (select 1 from sys_role_menu x where x.role_id = r.id and x.menu_id = m.id);
