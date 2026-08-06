-- 实施定制段示例迁移（ADR-0003：V10000+ 专属段，只建 impl_ 前缀自有表）
create table impl_template_note (
    id         bigserial primary key,
    content    varchar(500) not null,
    created_at timestamptz  not null default now()
);
