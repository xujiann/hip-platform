-- v40：收费/挂号凭据打印留痕（补打次数可追溯——票据重打是财务与审计关注点）。
-- 打印数据集与前端版式此前已就绪，缺的是收费员的入口与"打了几次"的痕迹。
create table fin_print_log (
    id           bigserial primary key,
    doc_type     varchar(16) not null,   -- CHARGE 收费票据 / REGISTRATION 挂号凭条
    doc_id       bigint      not null,   -- outp_charge.id 或 outp_registration.id
    operator_id  bigint references sys_user (id),
    printed_at   timestamptz not null default now()
);
create index idx_print_log_doc on fin_print_log (doc_type, doc_id);
