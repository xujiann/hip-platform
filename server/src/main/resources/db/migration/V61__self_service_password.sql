-- v27-A 自助改密 + 首次登录强制改密（等保必查项）
alter table sys_user add column must_change_password boolean not null default false;

-- 升级库兜底：从未改过密的 admin（password_updated_at 仍等于建号时间）补上强制改密标志。
-- 已改过密的 admin 两个时间必然不同，不会被误伤；V29 之前建号的老库 admin，
-- password_updated_at 是 V29 迁移回填时间（同样 ≠ created_at），也不会被误锁——
-- 该场景由管理员经用户管理重置口令时置位兜底。
update sys_user set must_change_password = true
 where username = 'admin' and password_updated_at = created_at;
