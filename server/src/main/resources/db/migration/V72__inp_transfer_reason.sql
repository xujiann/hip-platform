-- 转科原因（收尾环·阻塞3）：转科 UI 需采集"为何转科"，此前 inp_transfer_log 无处落地。
-- 可空——历史转科记录无原因、接口机批量转科也可不填，不给既有数据加约束。
alter table inp_transfer_log add column reason varchar(200);
