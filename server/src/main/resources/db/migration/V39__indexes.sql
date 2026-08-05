-- 技术债二：索引审计补缺（只加不删）
-- 依据：EXPLAIN ANALYZE 实测 E2E 常用查询，补高频点查/时序查询缺失索引。
-- outp_charge/pay_order 的 created_at::date 过滤：timestamptz 转 date 非 immutable 无法建函数索引，
-- 日结/对账为小结果集日报，试点量级可接受，范围改写记交接单待议区。

-- 收费票据明细、退费联动按结算单反查订单行
create index if not exists idx_order_charge on outp_order (charge_id);
-- 药事分析/库存流水按项目关联
create index if not exists idx_order_item on outp_order (item_id);
-- 待收明细热路径：挂号单内按状态取订单行（现有单列索引保留）
create index if not exists idx_order_reg_status on outp_order (registration_id, status);

-- 医保对账 msgExists 逐单点查（channel+status+ref_no）
create index if not exists idx_int_log_ref_no on int_message_log (ref_no);

-- 结算/退费/医保按挂号单反查结算单
create index if not exists idx_charge_reg on outp_charge (registration_id);

-- 患者端缴费闭环按挂号单+状态点查支付单
create index if not exists idx_pay_reg_status on pay_order (registration_id, status);

-- 体温单/生命体征趋势按入院时序拉取
create index if not exists idx_inp_vital_adm_time on inp_vital_sign (admission_id, measured_at);

-- 审计按用户查最近操作（order by id desc limit 200）
create index if not exists idx_audit_user_id on sys_audit_log (username, id desc);
