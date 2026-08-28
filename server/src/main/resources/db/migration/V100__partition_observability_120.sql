-- 1.2.14 上线检查单 P2：观测/审计三大表按月 RANGE 分区
--
-- 背景：sys_audit_log / int_message_log / ops_slow_api 三张"只增不减"的表，
--   此前靠 V53 hip_purge_observability 的 DELETE 清理。DELETE 的两个致命面：
--   ① 不回收物理空间——死元组累积 → 表膨胀 → 索引膨胀 → 顺序扫越来越慢；
--   ② 清理本身是全表条件扫 + 大批量删，与在线写入争锁。ops_slow_api 还有放大效应
--   （DB 慢 → 更多请求超阈值 → 每个都同步 insert → 更慢）。
--   改按月分区后，过期数据用 DETACH + DROP PARTITION 整月丢弃：元数据操作、近零成本、
--   无表/索引膨胀、不与在线写入长时间争锁。
--
-- 高风险说明：PG 原生分区**不能把已有普通表就地 ALTER 成分区表**，须走
--   建分区新表 → 迁存量 → 改名 三步（参照 V50/V52 的"有数据的表改造"手法）。
--   本迁移设计为在**有存量数据**的库上安全前滚：全程单事务（PG 支持事务内 DDL），
--   任一步失败整体回滚，不会留下半改造的中间态。三表均无外键依赖（审计/日志表），
--   不会被其他表的 FK 挡住 rename/drop。
--
-- 分区键必须进主键：PG 要求分区表的唯一约束(含主键)包含分区键列，
--   故主键由原来的 (id) 改为 (id, 时间列)。id 仍由原序列保证全局唯一（业务上足够）。


-- ========== 通用函数一：按月建分区（幂等；供本迁移与 hip_purge_observability 复用） ==========
create or replace function hip_ensure_month_partition(p_parent text, p_month date) returns void as $$
declare
    v_start date := date_trunc('month', p_month)::date;
    v_end   date := (date_trunc('month', p_month) + interval '1 month')::date;
    v_part  text := p_parent || '_p' || to_char(v_start, 'YYYYMM');
begin
    -- to_regclass 判存在：已建过就跳过，可反复调用不报错
    if to_regclass(v_part) is null then
        execute format('create table %I partition of %I for values from (%L) to (%L)',
                       v_part, p_parent, v_start, v_end);
    end if;
end;
$$ language plpgsql;

comment on function hip_ensure_month_partition(text, date) is
    '为分区表按给定月份建立 <表>_pYYYYMM 月分区（已存在则跳过）。';


-- ========== 通用函数二：丢弃早于保留期的整月分区（DETACH 后 DROP，替代 DELETE） ==========
create or replace function hip_drop_old_partitions(p_parent text, p_retention interval) returns void as $$
declare
    r        record;
    v_month  date;
    v_cutoff date := (now() - p_retention)::date;
begin
    for r in
        select c.relname
        from pg_inherits i
        join pg_class c on c.oid = i.inhrelid
        join pg_class p on p.oid = i.inhparent
        where p.relname = p_parent
          and c.relname ~ ('^' || p_parent || '_p[0-9]{6}$')   -- 仅月分区，绕开 default 兜底分区
    loop
        v_month := to_date(right(r.relname, 6), 'YYYYMM');
        -- 分区覆盖 [v_month, v_month+1)；仅当整月上界都早于保留期才丢，
        -- 绝不误删仍在保留期内的数据（保守：跨保留期边界的当月分区留到整月过期）
        if (v_month + interval '1 month')::date <= v_cutoff then
            execute format('alter table %I detach partition %I', p_parent, r.relname);
            execute format('drop table %I', r.relname);
            raise notice '[HIP] 已丢弃过期分区 %（保留期 %）', r.relname, p_retention;
        end if;
    end loop;
end;
$$ language plpgsql;

comment on function hip_drop_old_partitions(text, interval) is
    '丢弃整月都早于保留期的月分区：先 DETACH 再 DROP，替代 DELETE，近零成本无膨胀。';


-- ========== 表一：sys_audit_log（时间列 created_at，保留 180 天，等保≥6 月） ==========
-- 旧表 PK 约束须先改名，否则新表内联 PK 自动生成的 sys_audit_log_pkey 与旧表撞名
alter table sys_audit_log rename to sys_audit_log_old;
alter table sys_audit_log_old rename constraint sys_audit_log_pkey to sys_audit_log_old_pkey;

create table sys_audit_log (
    id          bigint       not null default nextval('sys_audit_log_id_seq'),
    username    varchar(64),
    method      varchar(8)   not null,
    path        varchar(255) not null,
    http_status int          not null,
    client_ip   varchar(64),
    created_at  timestamptz  not null default now(),
    primary key (id, created_at)          -- 分区键 created_at 必须进 PK
) partition by range (created_at);
-- 序列改归新表所属：drop 旧表时不会连带删除序列，且当前值已≥max(id)，新写入不撞号
alter sequence sys_audit_log_id_seq owned by sys_audit_log.id;

-- 覆盖 [存量最早月, 当前月+2] 的月分区，保证迁存量与近期写入都有落点
do $$
declare m date;
begin
    for m in select generate_series(
                 date_trunc('month', coalesce((select min(created_at) from sys_audit_log_old), now())),
                 date_trunc('month', now()) + interval '2 months',
                 interval '1 month')::date
    loop
        perform hip_ensure_month_partition('sys_audit_log', m);
    end loop;
end $$;
-- 兜底分区：防未来极端时间戳/维护漏建导致写入失败（正常运行应恒为空）
create table sys_audit_log_pdefault partition of sys_audit_log default;

-- 迁存量（列顺序与旧表一致，含 id/created_at → 路由到对应月分区、id 不变）
insert into sys_audit_log select * from sys_audit_log_old;
drop table sys_audit_log_old;

-- 索引重建（旧表已 drop，可复用原索引名）
create index idx_audit_created on sys_audit_log (created_at desc);
create index idx_audit_user_id on sys_audit_log (username, id desc);


-- ========== 表二：int_message_log（时间列 created_at，保留 90 天） ==========
-- 现行 schema：payload 已由 V55 改为 text
alter table int_message_log rename to int_message_log_old;
alter table int_message_log_old rename constraint int_message_log_pkey to int_message_log_old_pkey;

create table int_message_log (
    id         bigint       not null default nextval('int_message_log_id_seq'),
    direction  varchar(4)   not null,
    channel    varchar(16)  not null,
    ref_no     varchar(64),
    payload    text         not null,
    status     varchar(8)   not null,
    error      varchar(512),
    created_at timestamptz  not null default now(),
    primary key (id, created_at)
) partition by range (created_at);
alter sequence int_message_log_id_seq owned by int_message_log.id;

do $$
declare m date;
begin
    for m in select generate_series(
                 date_trunc('month', coalesce((select min(created_at) from int_message_log_old), now())),
                 date_trunc('month', now()) + interval '2 months',
                 interval '1 month')::date
    loop
        perform hip_ensure_month_partition('int_message_log', m);
    end loop;
end $$;
create table int_message_log_pdefault partition of int_message_log default;

insert into int_message_log select * from int_message_log_old;
drop table int_message_log_old;

-- 索引重建（保留现行全部：V10 channel + V39 ref_no + V55 ref 偏索引/created）
create index idx_int_log_channel on int_message_log (channel);
create index idx_int_log_ref_no  on int_message_log (ref_no);
create index idx_int_log_ref     on int_message_log (ref_no) where ref_no is not null;
create index idx_int_log_created on int_message_log (created_at);


-- ========== 表三：ops_slow_api（时间列 occurred_at，保留 90 天） ==========
alter table ops_slow_api rename to ops_slow_api_old;
alter table ops_slow_api_old rename constraint ops_slow_api_pkey to ops_slow_api_old_pkey;

create table ops_slow_api (
    id          bigint       not null default nextval('ops_slow_api_id_seq'),
    method      varchar(8)   not null,
    path        varchar(255) not null,
    cost_ms     int          not null,
    occurred_at timestamptz  not null default now(),
    primary key (id, occurred_at)          -- 分区键 occurred_at 必须进 PK
) partition by range (occurred_at);
alter sequence ops_slow_api_id_seq owned by ops_slow_api.id;

do $$
declare m date;
begin
    for m in select generate_series(
                 date_trunc('month', coalesce((select min(occurred_at) from ops_slow_api_old), now())),
                 date_trunc('month', now()) + interval '2 months',
                 interval '1 month')::date
    loop
        perform hip_ensure_month_partition('ops_slow_api', m);
    end loop;
end $$;
create table ops_slow_api_pdefault partition of ops_slow_api default;

insert into ops_slow_api select * from ops_slow_api_old;
drop table ops_slow_api_old;

create index idx_ops_slow_api_time on ops_slow_api (occurred_at);


-- ========== 归档函数改造：DELETE → 维护未来分区 + DETACH/DROP 过期分区 ==========
create or replace function hip_purge_observability() returns void as $$
declare
    v_tbl text;
    n     int;
begin
    -- ① 维护未来分区：当月 + 未来 2 月，保证下月起写入有落点，兜底分区保持为空
    foreach v_tbl in array array['sys_audit_log','int_message_log','ops_slow_api'] loop
        for n in 0..2 loop
            perform hip_ensure_month_partition(
                v_tbl, (date_trunc('month', now()) + (n || ' months')::interval)::date);
        end loop;
    end loop;
    -- ② 丢弃过期整月分区（DETACH+DROP 替代 DELETE）。保留期不变：审计 180 天，其余 90 天
    perform hip_drop_old_partitions('sys_audit_log',   interval '180 days');
    perform hip_drop_old_partitions('int_message_log', interval '90 days');
    perform hip_drop_old_partitions('ops_slow_api',    interval '90 days');
end;
$$ language plpgsql;

comment on function hip_purge_observability() is
    '观测/审计三表按月分区维护：先补当月+未来2月分区，再 DETACH/DROP 过期整月分区'
    '（审计留 180 天、慢接口/集成报文留 90 天）。由 OpsHealthScheduler 每日 03 点调用。';


-- ========== 告警外发 webhook 配置键（留空=向后兼容，仅开单不外发） ==========
insert into sys_config (cfg_key, cfg_value, remark) values
    ('ops_alert_webhook', '',
     '告警外发 webhook（钉钉/企业微信）；配置后 HIGH 级工单 POST 通知，留空=仅开单不外发')
on conflict (cfg_key) do nothing;
