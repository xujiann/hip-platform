-- v36 LIS 质控轮②：室内质控 IQC（Westgard 多规则 + Levey-Jennings）。独立表，与患者结果链无耦合。
create table lab_qc_record (
    id             bigserial primary key,
    item_code      varchar(32)   not null,       -- 质控项目
    level          varchar(16)   not null,       -- 质控水平（L1/L2 或 低/中/高）
    lot_no         varchar(32)   not null,       -- 质控品批号
    target_value   numeric(12,4) not null,       -- 靶值
    sd             numeric(12,4) not null check (sd > 0),
    measured_value numeric(12,4) not null,       -- 实测值
    z_score        numeric(8,3),                 -- 派生 (measured-target)/sd
    rule_broken    varchar(32),                  -- 命中的失控规则（1-3s/2-2s/...；null=在控）
    in_control     boolean       not null,
    operator_id    bigint references sys_user (id),
    measured_at    timestamptz   not null default now()
);
create index idx_qc_item_level on lab_qc_record (item_code, level, measured_at);
