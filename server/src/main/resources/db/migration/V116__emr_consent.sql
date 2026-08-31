-- v34 EMR：知情同意书 / 授权委托书（从零建）。全仓此前零实现，评审法定否决项。
-- 医患双签（患者签 + 医师 CA 签），与手术/输血/自费医嘱开单挂"试点期可配 gate"（默认 warn 不硬拦）。
create table emr_consent (
    id                bigserial primary key,
    admission_id      bigint references inp_admission (id),      -- 住院口径
    registration_id   bigint references outp_registration (id),  -- 门诊口径（二选一）
    consent_type      varchar(16)  not null,                     -- SURGERY/TRANSFUSION/ANESTHESIA/SPECIAL_EXAM/SELF_PAY/PROXY
    title             varchar(128) not null,
    content           varchar(8000) not null,
    patient_sign      varchar(255),                              -- 患者签名（串/图引用）
    patient_signed_at timestamptz,
    agent_name        varchar(64),                               -- 委托人（患者无民事行为能力/授权委托时）
    agent_relation    varchar(32),
    agent_reason      varchar(255),
    doctor_id         bigint references sys_user (id),
    doctor_sign       varchar(512),                              -- SignatureAdapter.sign 结果
    doctor_signed_at  timestamptz,
    status            varchar(16)  not null default 'DRAFT',     -- DRAFT/PATIENT_SIGNED/SIGNED/REVOKED
    revoked_at        timestamptz,
    ref_biz_id        bigint,                                    -- 可选绑定 inp_surgery.id 等
    created_by        bigint,
    created_at        timestamptz  not null default now(),
    constraint ck_consent_scope  check (admission_id is not null or registration_id is not null),
    constraint ck_consent_status check (status in ('DRAFT', 'PATIENT_SIGNED', 'SIGNED', 'REVOKED'))
);
create index idx_consent_adm on emr_consent (admission_id, consent_type, status);

-- 模板归类：emr_template 扩 type，同意书模板与病历模板分开
alter table emr_template add column template_type varchar(16) not null default 'EMR';   -- EMR/CONSENT

-- 自费判定所需：项目/药品加自费标记（自费 gate 前置；标记数据由主数据维护补齐，默认非自费）
alter table md_charge_item add column self_pay boolean not null default false;
alter table md_drug        add column self_pay boolean not null default false;

-- gate 开关（统一 emr.gate.<domain>.<point>，三态 off|warn|block，试点默认 warn 警告放行）
insert into sys_config (cfg_key, cfg_value, remark) values
    ('emr.gate.consent.surgery',     'warn', '手术无有效知情同意书：off 旁路 / warn 警告放行 / block 硬拦'),
    ('emr.gate.consent.transfusion', 'warn', '输血无有效知情同意书：off / warn / block'),
    ('emr.gate.consent.selfpay',     'warn', '自费医嘱无有效知情同意书：off / warn / block（依赖项目 self_pay 标记）')
on conflict (cfg_key) do nothing;

-- 菜单：知情同意书（住院业务 DIR=18），授 ADMIN + 门诊医生 + 护士
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (100, 18, '知情同意书', 'MENU', '/inpatient/consent', 'emr:consent', 'Document', 40);
insert into sys_role_menu (role_id, menu_id)
select r.id, 100 from sys_role r where r.code in ('ADMIN', 'DOCTOR_OUTP', 'NURSE');
