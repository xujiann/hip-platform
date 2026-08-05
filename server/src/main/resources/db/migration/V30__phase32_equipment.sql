-- 三十二期：设备全生命周期与后勤深化（维修/保养/计量/证照/采购单据链/制度文件库）

-- 设备维修工单：报修 → 派工 → 完成（院内/院外）
create table eq_repair_order (
    id          bigserial primary key,
    asset_id    bigint       not null references hrp_asset (id),
    fault_desc  varchar(500) not null,
    repair_type varchar(8)   not null default 'IN',      -- IN 院内 / OUT 院外送修
    status      varchar(16)  not null default 'REPORTED',-- REPORTED/ASSIGNED/DONE
    assignee    varchar(64),
    result_note varchar(500),
    reported_at timestamptz  not null default now(),
    assigned_at timestamptz,
    done_at     timestamptz
);

-- 保养计划与保养单
create table eq_maintain_plan (
    id         bigserial primary key,
    asset_id   bigint      not null references hrp_asset (id),
    cycle_days int         not null,
    next_date  date        not null,
    remark     varchar(255)
);

create table eq_maintain_record (
    id            bigserial primary key,
    plan_id       bigint references eq_maintain_plan (id),
    asset_id      bigint       not null references hrp_asset (id),
    maintain_date date         not null default current_date,
    content       varchar(500) not null,
    status        varchar(16)  not null default 'DONE',  -- DONE/CANCELLED
    created_at    timestamptz  not null default now()
);

-- 计量台账（强检设备定期检定）
create table eq_metrology (
    id          bigserial primary key,
    asset_id    bigint      not null references hrp_asset (id),
    cert_no     varchar(64) not null,
    checked_at  date        not null,
    valid_until date        not null,
    agency      varchar(128),
    created_at  timestamptz not null default now()
);

-- 供应商/生产商证照台账
create table hrp_supplier_cert (
    id              bigserial primary key,
    supplier_id     bigint       not null references hrp_supplier (id),
    cert_type       varchar(64)  not null,             -- 营业执照/经营许可/生产许可/授权书
    cert_no         varchar(64),
    attachment_name varchar(255),                       -- 附件文件名（档案柜编号）
    expire_date     date         not null,
    created_at      timestamptz  not null default now()
);

-- 采购单据链：备货单 → 验收（发票补录）→ 退货单
create table pur_doc (
    id          bigserial primary key,
    doc_no      varchar(32)   not null unique,
    doc_type    varchar(8)    not null,                 -- STOCK 备货 / RETURN 退货
    supplier_id bigint        not null references hrp_supplier (id),
    items       varchar(1000) not null,
    amount      numeric(12,2) not null,
    status      varchar(16)   not null default 'DRAFT', -- DRAFT/APPROVED/RECEIVED/CANCELLED
    invoice_no  varchar(64),                            -- 财务验收时补录/调整
    remark      varchar(255),
    created_at  timestamptz   not null default now()
);

-- 制度文件库
create table oa_doc_file (
    id         bigserial primary key,
    category   varchar(64)  not null,                   -- 医疗核心制度/护理/院感/行政…
    title      varchar(255) not null,
    content    text         not null,                   -- 正文（在线预览）
    version    varchar(16)  not null default 'V1.0',
    created_at timestamptz  not null default now()
);

insert into oa_doc_file (category, title, content) values
    ('医疗核心制度', '危急值报告制度', '检验检查危急值须 10 分钟内电话+系统双通道通知开单医师，处理闭环留痕。'),
    ('医疗核心制度', '三级查房制度', '主任医师每周≥2 次，主治医师每日 1 次，住院医师全日负责。');

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (56, 29, '设备管理', 'MENU', '/hrp/equipment', 'hrp:equip', 'Tools', 4);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (56);
