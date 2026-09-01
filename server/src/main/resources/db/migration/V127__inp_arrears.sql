-- v41 住院欠费挂账台账（住院线最后一块财务留痕空白）
--
-- 背景：出院结算 discharge() 算出 balance = 押金 - 费用，balance<0 即欠费出院。
-- 全仓 grep「追缴/催缴/欠费台账」零代码——InpatientController :313 明言"欠费属追缴范畴"，
-- 即欠费出院是设计内放行（不拿医疗行为当抵押），但事后追缴一直无系统内路径，只能靠 Excel。
-- 本迁移补的正是这个事后台账：谁欠、欠多少、补了多少、催了几次、核销与否，全程可查可审。
--
-- 【口径隔离——本特性最容易做错的地方】
-- 补缴是「收欠款」，不是「补押金」。若把补缴写进 inp_deposit：
--   ① /account 与 interimSettle 的余额口径(押金合计-已发生费用)会凭空变正，欠费凭空消失；
--   ② 出院结算 depositAmount/balance 的历史快照口径与台账互相污染（该患者已出院，押金账本应封存）。
-- 故补缴独立成 inp_arrears_payment 流水，与 inp_deposit 物理隔离、与 inp_settlement 只读关联。
-- 结算行(inp_settlement)自身在挂账过程中一个字节都不改——欠费台账是结算的下游派生账，不是结算的修正。

create table inp_arrears (
    -- 一次入院至多一条欠费挂账（唯一）：出院召回后重结算走 on conflict 更新，绝不重复挂账
    id               bigserial     primary key,
    admission_id     bigint        not null unique references inp_admission (id),
    settle_id        bigint        references inp_settlement (id),   -- 挂账所依据的出院结算单（只读关联）
    amount           numeric(12,2) not null,                        -- 欠费金额 = -balance，恒 > 0
    -- OPEN 待追缴 / PARTIAL 部分补缴 / CLEARED 已结清 / WRITTEN_OFF 已核销（坏账）
    status           varchar(16)   not null default 'OPEN',
    write_off_reason varchar(255),                                  -- 核销原因（法定留痕，仅 ADMIN 可写）
    write_off_by     bigint        references sys_user (id),
    written_off_at   timestamptz,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    constraint ck_inp_arrears_status check (status in ('OPEN', 'PARTIAL', 'CLEARED', 'WRITTEN_OFF')),
    constraint ck_inp_arrears_amount check (amount > 0)
);
-- 台账主查询按状态过滤（收费员日常只看 OPEN/PARTIAL），id desc 走索引免排序
create index idx_inp_arrears_status on inp_arrears (status, id desc);

-- 补缴流水：与 inp_deposit 平行但物理隔离的独立流水（见上文口径隔离说明）
create table inp_arrears_payment (
    id          bigserial     primary key,
    arrears_id  bigint        not null references inp_arrears (id),
    amount      numeric(12,2) not null,
    pay_method  varchar(16)   not null default 'CASH',   -- CASH / CARD / WECHAT / ALIPAY
    operator_id bigint        references sys_user (id),
    paid_at     timestamptz   not null default now(),
    constraint ck_inp_arrears_pay_amount check (amount > 0)
);
create index idx_inp_arrears_payment on inp_arrears_payment (arrears_id, id);

-- 催缴登记：每次电话/短信/上门都留一行，台账列出催缴次数（追缴尽责的举证材料）
create table inp_arrears_dunning (
    id          bigserial   primary key,
    arrears_id  bigint      not null references inp_arrears (id),
    method      varchar(16) not null,                    -- PHONE 电话 / SMS 短信 / VISIT 上门 / LETTER 书面
    note        varchar(500),
    operator_id bigint      references sys_user (id),
    dunned_at   timestamptz not null default now(),
    constraint ck_inp_arrears_dun_method check (method in ('PHONE', 'SMS', 'VISIT', 'LETTER'))
);
create index idx_inp_arrears_dunning on inp_arrears_dunning (arrears_id, id desc);

-- ===== 菜单：住院业务(DIR 18) 下的欠费管理 =====
-- 授 ADMIN + CASHIER：追缴是收费职能，医生/护士不该看见患者欠费台账（也不该被它影响医疗决策）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (103, 18, '欠费管理', 'MENU', '/inpatient/arrears', 'inp:arrears', 'Money', 41);
insert into sys_role_menu (role_id, menu_id)
select r.id, 103 from sys_role r where r.code in ('ADMIN', 'CASHIER')
  and not exists (select 1 from sys_role_menu x where x.role_id = r.id and x.menu_id = 103);
