-- v60 车道 B：蜡块回挂补取材（RESAMPLE）特检医嘱（技术偏离表 2563 v59 复核打回清单）。
--
-- ============ 反驳者坐实的缺口（主控实测） ============
-- 「补取材 RESAMPLE 医嘱的『关联蜡块编号』与『当前执行进度』不派生自事实：取材 append 路径不读不写 path_tech_order，
--  新蜡块永远挂不到医嘱上、进度恒『待切片』」——V163 给 path_slide 补了 tech_order_id（切片认医嘱），
-- 但补取材的产物是**蜡块**不是切片：病理医师下了「补取材」，取材员补出两块，这两块与那条医嘱在库里互不认识，
-- 医嘱行的 block_id 只能由下达时手填（下达时块还不存在，必然为空），清单「蜡块」列永远「—」、进度永远「待切片」。
--
-- ============ 这条迁移只做一件事 ============
-- path_block 加 tech_order_id（可空外键 → path_tech_order）+ 部分索引。
-- 写侧：取材端点 append=true 带 techOrderId 时校验（存在 / RESAMPLE / 同标本 / ORDERED，任一不过 5275、任何写入之前返回），
-- 通过则本次新蜡块 insert 带 tech_order_id，医嘱 block_id 为空则回写为本次首块（PathologyProcessController.grossing）。
-- 读侧：执行进度新态 SAMPLED（ORDERED、无挂接切片、但有 tech_order_id = 本医嘱的蜡块 = 已补取材待切片），
-- 清单「蜡块」列在 block_id 为空时按「挂接蜡块 + 挂接切片所在块」派生（blocks_derived）。
--
-- ============ 纪律：零条 update ============
-- **本迁移不含任何 update 语句。** 后果如实写在这里：
-- V167 之前所有补取材产出的蜡块，tech_order_id **永远为 NULL**——当时取材端点根本不收医嘱号，
-- 「这块是为哪条补取材医嘱补的」这一事实库里从未采集，按时间先后或 tissue_desc 关键词反猜就是编造挂接
-- （同一标本两条 RESAMPLE 医嘱时猜错就是挂错）。历史 RESAMPLE 医嘱清单上「蜡块」列仍是「—」、进度仍是「待切片」，
-- 那就是事实：**宁可少算，不可假算**。同理不回写历史医嘱的 block_id。

alter table path_block add column tech_order_id bigint references path_tech_order (id);
create index idx_path_block_tech_order on path_block (tech_order_id) where tech_order_id is not null;

comment on column path_block.tech_order_id is
    'v60：该蜡块为哪条补取材（RESAMPLE）特检医嘱补出；NULL = 首次取材的块或 V167 之前的历史补取材块（零回填，不按时间/描述反猜）';
