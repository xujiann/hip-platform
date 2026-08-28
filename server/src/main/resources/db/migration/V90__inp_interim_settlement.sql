-- 上线检查单·车道B 临床收尾①：住院中间结算（长住/医保患者刚需）
-- 背景：此前住院结算只有 discharge 一条"出院即全量结算"路径。长住/医保患者需在住院期间
-- 就已发生费用做阶段性结算（不出院、不释放床位、不冲抵全部押金）。
--
-- 【不算重的关键设计——见 InpatientService.interimSettle 与 discharge 的注释】
-- 1) inp_settlement 加 settle_type 区分 INTERIM 中间结算 / FINAL 出院结算，缺省 FINAL——
--    历史所有结算行与 discharge() 新写行一律 FINAL，语义与行为完全不变。
-- 2) inp_order 加 interim_settle_id：某条已执行医嘱被哪张中间结算单"认领"。中间结算只认领
--    尚未被认领(interim_settle_id is null)的已执行医嘱并原子打标，故两张中间结算永不重叠认领同一笔费用。
-- 3) 出院结算 discharge() 的费用总额始终按【医嘱台账】(sum 已执行医嘱) 现算，从不读结算行相加——
--    因此中间结算行结构上不可能抬高出院结算总额；中间结算金额恒为出院总额的子集。
-- 4) /account 余额同样按台账(押金-已执行费用)现算，与结算行解耦，中间结算不扰动余额口径。
-- 结论：收入确认只认 FINAL（未出院前认累计已发生），INTERIM 是院内阶段性凭据，二者永不相加。

alter table inp_settlement
    add column if not exists settle_type varchar(16) not null default 'FINAL';
do $$ begin
    alter table inp_settlement add constraint ck_inp_settlement_type
        check (settle_type in ('INTERIM', 'FINAL'));
exception when duplicate_object then null; end $$;

-- 中间结算认领标记：该已执行医嘱费用被哪张中间结算单结算过（null=尚未被中间结算认领）
alter table inp_order
    add column if not exists interim_settle_id bigint references inp_settlement (id);
create index if not exists idx_inp_order_interim_settle
    on inp_order (interim_settle_id) where interim_settle_id is not null;

-- 关键：把"一次入院只允许一张有效结算"的唯一约束收窄到【仅 FINAL】。
-- 原索引(V54)是 status<>'CANCELLED' 全量唯一——若不改，第一张中间结算就会占掉这个唯一槽位，
-- 之后 discharge 插 FINAL 行必撞唯一约束。改为只对 FINAL 唯一后：一次入院仍只能有一张有效出院结算，
-- 中间结算(INTERIM)不受此约束、可多张；行为对既有 FINAL 行零变化。
drop index if exists uq_inp_settlement_active;
create unique index if not exists uq_inp_settlement_active on inp_settlement (admission_id)
    where status <> 'CANCELLED' and settle_type = 'FINAL';
