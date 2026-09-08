-- v57 车道 C：特检技术医嘱取消留痕 + 切片挂接医嘱（技术偏离表 2563★ 二次核账坐实的地基缺列）。
--
-- ============ 这条迁移只补两处「互不认识」 ============
-- 1. `path_tech_order`（V144:137-153）只有 status ORDERED/DONE/CANCELLED 三档，
--    **没有 cancelled_at / cancelled_by / cancel_reason**。取消端点
--    （PathologyReportController.cancelTechOrder）此前不收 body，返回体自己写着
--    「本版无 cancel_reason 列，取消原因未留痕」——「谁、什么时候、为什么不做这个免疫组化了」
--    三个事实一个都存不下，写侧代码自己都标了「需要留痕请给主控加列」。
-- 2. `path_slide`（V144:94-109）**没有 tech_order_id**。免疫组化加做的片子与它的医嘱互不认识：
--    技师切了三张 CK7，医师端看到的医嘱仍是「待执行」，质控层也算不出「一条特检医嘱产出了几张片」。
--
-- ============ 取消原因与下达原因分列，不覆盖 reason ============
-- `reason` 是**下达原因**（当初为什么要做这个免疫组化），`cancel_reason` 是**取消原因**。
-- 往 reason 里塞取消原因会让前者永久丢失——诊断环节的决策链断掉一环。
-- 三列一起加：取消时刻（cancelled_at）、取消人（cancelled_by）、取消原因（cancel_reason），
-- 缺任何一个都不叫留痕。取消端点把三列与 status 落在同一条 update 里。
--
-- ============ 纪律：零条 update，历史 CANCELLED 行的三列永远是 NULL ============
-- **本迁移不含任何 update 语句。** V163 之前已取消的技术医嘱，cancelled_at / cancelled_by /
-- cancel_reason 一律为空——那就是事实：当时根本没采集。
-- 最诱人的歧路是拿 done_at 或 ordered_at 反推取消时刻：done_at 对取消行本来就是空，
-- ordered_at 是开单时刻与取消无关；填了只会让「取消时刻」这一列**列列有值、条条可疑**。
-- 同 V146 `inp_surgery.cancelled_at` 的处置：统计层必须显式分两档——
-- `cancelled_at is not null` 的按取消时刻归集，为空的落「历史取消（时刻未采集）」一档并在页面标注，
-- **不许把它们静默并进任何时间口径**。清单端点照常返回这些行，三列就是 NULL。**宁可少算，不可假算。**
-- 同理 path_slide.tech_order_id：历史切片一律 NULL（普通切片与「当时挂在哪条医嘱上」都无从考证），
-- 不按 stain_type / stain_item 去反猜医嘱——同一标本两条 CK7 医嘱时猜错就是挂错。
--
-- ============ 挂接语义（由 PathologyProcessController.slides 校验，库层只管外键） ============
-- tech_order_id 可空：不传即普通切片，旧契约不变。传了则医嘱须存在、须属于该蜡块所在标本、
-- 须是 ORDERED（三条路径同返 5272）。**挂接不自动把医嘱置 DONE**——完成仍走 /done 由技师确认，
-- 切了片不等于做完了（染色、质控都在后面）。

alter table path_tech_order add column cancelled_at  timestamptz;
alter table path_tech_order add column cancelled_by  bigint references sys_user (id);
alter table path_tech_order add column cancel_reason varchar(255);

comment on column path_tech_order.cancelled_at is
    'v57：取消时刻。V163 之前取消的行永远为 NULL（零回填），统计层须分「有取消时刻」与「历史取消」两档';
comment on column path_tech_order.cancelled_by is 'v57：取消人；与 cancelled_at 同生同灭';
comment on column path_tech_order.cancel_reason is
    'v57：取消原因。与 reason（下达原因）分列，取消不覆盖下达原因';

alter table path_slide add column tech_order_id bigint references path_tech_order (id);
create index idx_path_slide_tech_order on path_slide (tech_order_id) where tech_order_id is not null;

comment on column path_slide.tech_order_id is
    'v57：该切片为哪条特检技术医嘱加做；NULL = 普通切片或 V163 之前的历史切片（零回填，不按染色项目反猜）';
