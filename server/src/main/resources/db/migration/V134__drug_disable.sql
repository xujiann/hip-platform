-- v43 车道C：药品启用/停用留痕（技术偏离表 1162★「药品目录的启用与停用」）
--
-- 背景（典型「有列没功能」）：md_drug.enabled 自 V4:13 建表即存在，一年来
--   * 无任何启停端点（全仓 grep 只有读侧 findTop20ByEnabledTrue... 在用它筛数据）；
--   * 无前端入口（DrugsView 只维护费用类别与自费标记）；
--   * 无停用原因、无停用人、无停用时间——停用了也说不清是谁为什么停的；
--   * 停用药品照样能开单（DoctorStationService / InpatientService 的 createOrders 只查
--     findById，从不看 enabled）——即"停用"这个状态在业务上完全不生效。
--   而 1162 在投标应答里答的是"已实现"。本迁移是该条诚信补齐的数据基础。
--
-- 【只加三列，不动 enabled 列本身】
--   * 三列**全部 nullable**：历史行（含实施期已用 SQL 手工停用的行）必然为 null，
--     严禁用 now() 或任意管理员 id 回填伪造留痕——查不出来就诚实地空着。
--   * **不加 CHECK 约束**（如 "enabled or disable_reason is not null"）：试点库与实施期
--     可能已有直接 update md_drug set enabled=false 的历史行，加约束会让本迁移在数据库层
--     直接失败；升级失败的代价远高于脏数据本身（同 V133 对 record_type 不加 CHECK 的判断）。
--   * disabled_by 走 **bigint references sys_user(id)**：不重蹈 nur_risk_assess.assessor /
--     shift_handover.author / nur_qc_score.checker 三处 varchar(64) 存用户名的老路
--     （v42 已就此立规）。
--   * **不加索引**：md_drug 是院内目录级小表（种子 10 行、实施期千行量级），
--     现有 enabled 过滤查询本就带 limit 20，加索引是纯负担。
--
-- 【范围外·明确不做】按批次禁用：批次级在库量在本平台**不落库**——md_drug.stock 是单一
--   聚合值（v42 已确认），inv_stock_in 只记录入库时的批次与效期、不维护批次余量。
--   没有「某批次还剩多少」这一数据，"停用某批次"既无法阻止发药（发药扣的是聚合 stock）、
--   也无法回答"该批次还有多少在架"。故本版**不做批次级禁用**，也不留假入口。
--   若院方确需批次召回，前置是批次级库存台账（inv_stock_batch），属独立立项。

alter table md_drug add column disable_reason varchar(200);
alter table md_drug add column disabled_at    timestamptz;
alter table md_drug add column disabled_by    bigint references sys_user(id);

comment on column md_drug.disable_reason is 'v43 停用原因（停用时必填；启用时清空）';
comment on column md_drug.disabled_at    is 'v43 停用时间；历史停用行为 null，严禁回填伪造';
comment on column md_drug.disabled_by    is 'v43 停用操作人 sys_user.id；历史停用行为 null';
