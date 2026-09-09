-- v58 车道 B：特检技术医嘱进流转节点 + 完成校验 gate 登记（技术偏离表 2563 三次核账：
-- 「当前执行进度是手工三态标记而非执行进度」「DONE 仅靠手点且不校验任何切片存在」）。
--
-- ============ 这条迁移只做两件事 ============
-- 1. 放开 chk_path_process_node 白名单：V144:125-127 的 12 档**一个不删**，之后追加 TECH_ORDER / TECH_DONE / TECH_CANCEL。
--    此前技术医嘱的下达 / 完成 / 取消（PathologyReportController.createTechOrder / doneTechOrder / cancelTechOrder）
--    只写 path_tech_order 自己那一行，**path_process 里零行**——同一标本的流转轨迹里看不到「谁在什么时候加做了 CK7、
--    谁确认做完、谁为什么取消」。v57（V163）补了取消三列，但那是医嘱行上的字段，不是流转节点；
--    往 path_process 写 'TECH_ORDER' 会直接撞 CHECK（V144 的白名单是硬约束），所以先放开白名单。
-- 2. 登记 gate emr.gate.pathology.techdone（默认 warn）：完成确认此前不校验任何切片事实。本版 doneTechOrder 先算
--    挂接切片数 / 已染色数（任何档位都算、返回体都带），block 档缺口返 5273，warn 档放行但返回体 warnings 回带并写进
--    TECH_DONE 节点，off 档不判。**必须 seed**：PUT /api/config/{key} 只改既有行不插新行，不 seed 则 block 与 off 两档
--    不可达且系统表现得一切正常（v53 emr.gate.timeliness 的教训，V161 补登记；
--    ReachabilityTest.everyGateKeyIsRegisteredInSysConfig 会红）。
--
-- ============ 为什么默认 warn 而不是 block ============
-- V163 之前的历史医嘱普遍没有挂接切片（path_slide.tech_order_id 零回填，永远 NULL），直接 block 会让存量 ORDERED 医嘱
-- **永远完不成**——技师做完了也点不了完成。warn 先让缺口当场可见（返回体 + 流转节点留痕），院方待挂接数据沉淀后再改
-- block。坏配置回落 warn 而非 off（回落 off 等于把一个笔误变成静默关闭校验）。
--
-- ============ 纪律：零条 update ============
-- **本迁移不含任何 update 语句。** 历史技术医嘱不补 TECH_ORDER / TECH_DONE / TECH_CANCEL 节点——当时根本没打点，
-- 补了就是伪造「谁在何时打的点」（operator_id 只能瞎填、occurred_at 只能拿 ordered_at / done_at / cancelled_at 冒充）。
-- 流转轨迹里没有这三类节点的老医嘱，就是事实：**宁可少算，不可假算**。
-- 执行进度（待切片 / 切片中 / 已染色待确认 / 已完成 / 已取消）由 path_slide.tech_order_id + stained_at 只读派生，
-- **不加状态列**——加了列就得回填，回填就得猜。

alter table path_process drop constraint chk_path_process_node;
alter table path_process add constraint chk_path_process_node check (node in
    ('RECEIVE', 'REJECT', 'GROSSING', 'DEHYDRATE', 'EMBED', 'SECTION',
     'STAIN', 'READ', 'FIRST_SIGN', 'SECOND_SIGN', 'ISSUE', 'SUPPLEMENT',
     'TECH_ORDER', 'TECH_DONE', 'TECH_CANCEL'));

comment on constraint chk_path_process_node on path_process is
    'v58：V144 原 12 档 + TECH_ORDER（下达特检医嘱）/ TECH_DONE（确认完成）/ TECH_CANCEL（取消）；历史医嘱不回填节点';

-- 注意 remark 列是 varchar(255)（V161 第一版写超了直接把 Flyway 打红）——详细口径写在上面的注释与 docs/配置手册.md 里。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('emr.gate.pathology.techdone', 'warn',
     '特检技术医嘱完成校验 gate（v58）：off 旁路 / warn 无已染色挂接切片仍放行但回带 warnings（默认）/ block 以 5273 拦完成。'
     || '默认 warn：历史医嘱普遍无挂接切片，直接 block 会让存量医嘱永远完不成；坏配置回落 warn。')
on conflict (cfg_key) do nothing;

-- 零回填：不改任何既有行，on conflict do nothing 保证重复执行安全。
