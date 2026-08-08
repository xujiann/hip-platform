-- 1.0.6 安全批（docs/代码审阅-20260808.md P0）

-- P0-3：患者端登录失败计数与锁定（患者号为顺序号，仅凭手机号可枚举爆破）
create table portal_login_attempt (
    patient_no     varchar(32) primary key,
    failed_count   int         not null default 0,
    last_failed_at timestamptz,
    locked_until   timestamptz
);

-- P0-7：报表引擎只读角色——正则黑名单挡不住 setval()/query_to_xml() 等绕过，改由数据库授权兜底。
-- 应用账号通常无 CREATE ROLE 权限：此处尽力创建，失败不阻断迁移，改由 DBA 按部署手册执行；
-- 角色缺失时自定义报表**拒绝执行**（fail closed），不会退回到不安全的老路径。
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'hip_report_reader') then
        begin
            execute 'create role hip_report_reader nologin';
        exception when insufficient_privilege then
            raise notice '[HIP] 无权创建角色 hip_report_reader：自定义报表将拒绝执行，请 DBA 按部署手册 §报表只读角色 手工创建并授权';
            return;
        end;
    end if;

    execute 'grant usage on schema public to hip_report_reader';
    -- 应用账号须为该角色成员，否则 set role 被拒（报表将 fail closed 拒绝执行）
    execute format('grant hip_report_reader to %I', current_user);
    -- 业务表只读；排除 sys_*（含口令散列与配置）、flyway 史、登录尝试表
    declare t record;
    begin
        for t in
            select tablename from pg_tables
            where schemaname = 'public'
              and tablename not like 'sys\_%'
              and tablename not like 'flyway\_%'
              and tablename <> 'portal_login_attempt'
        loop
            execute format('grant select on public.%I to hip_report_reader', t.tablename);
        end loop;
        execute 'grant select on public.sys_dept to hip_report_reader';   -- 科室是报表常用维度
    end;
exception when insufficient_privilege then
    raise notice '[HIP] 授权 hip_report_reader 失败（权限不足），请 DBA 按部署手册手工执行';
end
$$;
