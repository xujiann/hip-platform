-- 医保完善批次一：医保结算号持久化
-- uploadSettlement 返回的医保结算号此前只留在报文 payload 里；
-- 真实省 SDK 的冲正与对账都按医保结算号发起，必须落库可查。
alter table outp_charge add column yb_settle_no varchar(64);
alter table inp_settlement add column yb_settle_no varchar(64);
