-- v44 车道G：开单资料提示与医嘱字段
-- （技术偏离表 1001★/1002★/1003★/1006★/1013★/1014★/1016★）
--
-- 背景（全量核对的判定）：
--   * 1006 「所有医嘱均有备注功能」——outp_order 自 V5:36 建表至今无 remark 列，
--     医生想写「饭后半小时服」「患者自备药已带」只能塞进用法字段或干脆不写。
--   * 1013 检验申请单「检验项目诊断、加急标志」——无 urgent 列，急诊标本与普通标本
--     在 LIS 工作台上完全无法区分，加急只能靠电话喊。
--   * 1014 检查申请单「临床摘要、诊断资料、检查目的、注意事项」——四项全无列。
--     诊断资料本就有（outp_diagnosis），另三项无处可落，v43 车道B 出五种单据打印时
--     只能在版式上留手填栏（PrintView.vue:212/237 的 fill-line）。
--   * 1016 检验申请「标本类型、采样部位」——无列；lis_sample 只有 barcode/status，
--     记的是"这管标本走到哪一步"，不是"这是什么标本、从哪儿采的"。
--
-- 【只加列，不动任何既有列，不加 CHECK，全部 nullable】
--   outp_order 是**收费、发药、执行三条线的共同数据源**（实体类注释即如此定义），
--   一列 not null 就会让全部历史行与所有既有写路径同时爆掉。
--   * 七列全部可空：历史医嘱行必然没有这些信息，**严禁回填任何猜测值**——
--     把既往检验单一律填成"血液/肘正中静脉"就是伪造临床记录。
--   * 不加 CHECK（如 "order_type='LAB' or specimen_type is null"）：
--     真实院内会出现 EXAM 类项目也要取标本（如穿刺后送病理）、TREAT 类也要写注意事项，
--     用约束把字段焊死在某个 order_type 上，第一个越界的真实用法就会变成升级失败。
--     字段的适用范围由界面决定（哪类医嘱显示哪几栏），不由数据库决定。
--   * 不加索引：这七列全是**随医嘱行一起取出来展示/打印**的附属信息，
--     没有任何"按加急标志筛全院医嘱"之类的检索场景。urgent 若将来要做急标工作队列，
--     再按那时的真实查询形态建部分索引（where urgent）。
--
-- 【urgent 用 boolean default false 而非 not null default false】
--   default 会让 PG 在 ADD COLUMN 时把既有行一并填成 false（"未标加急"是历史行的真实状态，
--   不算伪造），但列本身保持可空——不给既有 insert 路径任何新的失败面。
--
-- 【范围外·明确不做】
--   * 不改 DoctorStationService.createOrders 的任何一行（见下方 1003 说明）。
--   * 不做"检验项目诊断"的独立列：1013 的"检验项目诊断"即本次就诊的临床诊断，
--     outp_diagnosis 已有且打印数据集已带出（PrintReportController.clinicalDoc 的 diagnoses），
--     再建一列会出现两份可以互相矛盾的诊断。
--   * 商品名/通用名不在本迁移范围：那是 md_drug 的主数据形态问题（见回报），
--     不是医嘱行的问题，也不该由开单侧补列。

alter table outp_order add column remark           varchar(200);
alter table outp_order add column urgent           boolean default false;
alter table outp_order add column clinical_summary varchar(500);
alter table outp_order add column exam_purpose     varchar(200);
alter table outp_order add column notice           varchar(200);
alter table outp_order add column specimen_type    varchar(32);
alter table outp_order add column sampling_site    varchar(32);

comment on column outp_order.remark           is 'v44 医嘱备注（1006），全类型医嘱通用';
comment on column outp_order.urgent           is 'v44 加急标志（1013）；历史行填 false=未标加急，非伪造';
comment on column outp_order.clinical_summary is 'v44 临床/病情摘要（1014/1016），可由病历自动带入后医生改写';
comment on column outp_order.exam_purpose     is 'v44 检查目的（1014）';
comment on column outp_order.notice           is 'v44 注意事项（1014），给医技科室与患者看';
comment on column outp_order.specimen_type    is 'v44 标本类型（1016），如 血液/尿液/痰';
comment on column outp_order.sampling_site    is 'v44 采样部位（1016），如 肘正中静脉';

-- ===== 1003 缺药提醒：院级开关（三态 off|warn|block，默认 warn）=====
--
-- 【为什么是开关而不是硬拦——这一条必须写清楚，它与参数明文相反】
-- 参数要求"缺药时不准许继续开方"。全量核对发现**系统行为与该要求相反**：
-- DoctorStationService.createOrders 在库存低于开量时只把当前库存回填到非持久化字段
-- stockWarnAvailable 上（OutpOrder:98 注释「开单不因此拦截」），真正的硬拦在
-- **患者缴费之后的发药端**（撞 6002）。
--
-- 本版**刻意保留这一放行行为**，不改成硬拦。理由不是省事：
--   ① 开单与发药之间隔着一次缴费。开单时缺的药，到发药时可能已经到货
--      （门诊药房当日多次补货是常态），开单硬拦会拦掉一批本来能正常发出的处方。
--   ② md_drug.stock 是**单一聚合快照**，不是实时可用量（无批次台账、无预占）。
--      拿一个会滞后的数去做硬拦，等于用错误数据剥夺医生的处方权。
--   ③ 最要命的一条：患者**已经挂号缴费坐在诊室里**了。硬拦的结果是医生开不出药、
--      患者拿不到处方，比现在"先开出来、缴费前医生已知情、真缺货就退费重开"更糟。
-- 故：本版交付的是**开单时的缺药提示（黄字，不拦截）+ 院级开关**，
-- 是否收紧由院方改本键决定，不由开发擅自决定。
--
-- 【本键当前的真实消费面——不夸大】
--   * warn（默认）：/api/masterdata/order-hints 随药品资料返回 stock 与本开关值，
--     开单界面据此出缺药提示；createOrders 的既有 stockWarnAvailable 提示保持不变。
--   * off：界面不出提示（既有 stockWarnAvailable 仍会返回，那是 v1.2 的既有契约，不动）。
--   * block：**本版未挂任何写路径挡点**（挡点在 createOrders 内，属车道 E 的文件）。
--     置 block 当前等价于 warn。此处不留假承诺：要真硬拦，须由医生站车道在
--     createOrders 里加挡点并单独评估上面三条风险。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('outp.gate.stock.shortage', 'warn',
     '开单缺药：off 不提示 / warn 开单提示不拦截（默认）/ block 预留（v44 未挂写路径挡点）')
on conflict (cfg_key) do nothing;
