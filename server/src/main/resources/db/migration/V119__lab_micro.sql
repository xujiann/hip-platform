-- v36 LIS 质控轮①：微生物培养 + 药敏结构化（手工录入；分析仪自动上机属外部联调）。
-- 原检验结果全走 outp_lab_result 单值扁平表，无菌种/药敏结构。
create table lab_micro_result (
    id           bigserial primary key,
    sample_id    bigint       not null references lis_sample (id),
    order_id     bigint       not null references outp_order (id),
    specimen     varchar(32),                 -- 标本类型（痰/血/尿…）
    organism     varchar(128) not null,       -- 菌种名
    colony_count varchar(32),                 -- 菌落计数/半定量
    gram         varchar(8),                  -- POS 阳性 / NEG 阴性
    reporter_id  bigint references sys_user (id),
    reported_at  timestamptz  not null default now()
);
create index idx_micro_order on lab_micro_result (order_id);

create table lab_micro_ast (                  -- 药敏明细，一菌多药
    id         bigserial primary key,
    micro_id   bigint      not null references lab_micro_result (id) on delete cascade,
    antibiotic varchar(64) not null,          -- 抗菌药名
    method     varchar(16),                   -- 方法（KB/MIC/E-test）
    mic_value  varchar(32),                   -- MIC 值或抑菌圈直径
    sir        varchar(2)  not null           -- S 敏感 / I 中介 / R 耐药
);
create index idx_micro_ast_mid on lab_micro_ast (micro_id);
