package cn.hip.outpatient.service;

import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.config.HipProfiles;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v50 车道B：门诊摆药与预调剂。
 *
 * <h2>它补的是哪一段</h2>
 * 现状是<b>整单一步发药</b>——{@link DispenseService} 63 行，从「已收费」一步跳到「已发药」。
 * 真实药房是：收费 → <b>待摆药队列</b> → 药师<b>摆药</b>（逐项取药、打摆药单）→
 * <b>预调剂完成</b>（药已配好等患者来取）→ 患者到窗口 → 发药核对 → 交付。
 * 本类只负责<b>发药之前</b>的三步，{@code DispenseService} 与既有 4 个发药端点一个字节都没改。
 *
 * <h2>与发药的边界（这一点必须写死，否则两边都会去扣库存）</h2>
 * <ul>
 *   <li>摆药单<b>不碰库存、不改 {@code outp_order.status}</b>。扣库存与置 {@code DISPENSED}
 *       仍然只由发药那一步做，那是唯一的写入点。摆药单是旁挂的一层作业记录，
 *       回答的是「这批药是谁、在哪个窗口、什么时刻配好的」。</li>
 *   <li>摆药单自己的 {@code DISPENSED} 档是<b>作业状态</b>不是发药事实：它由发药环节回写
 *       （见 {@link #markDispensedByRegistration}），标记这张作业单已经收口。</li>
 * </ul>
 *
 * <h2>状态机与并发</h2>
 * {@code PENDING 待摆药 → PICKING 摆药中 → PREPARED 已配好 → DISPENSED 已发药 / CANCELLED 已作废}。
 * <b>每一次流转都是条件更新 + 受影响行数判定</b>（照抄 {@code DispenseService.claimDispense}
 * 的抢占写法）：先抢状态再做别的，读-判-写会让两个药师把同一批药各配一遍。
 * 建单另有两道数据库硬闸：{@code uq_pharm_picking_live_reg}（一次挂号只能有一张在途单）
 * 与 {@code uq_pharm_pick_line_live}（一条医嘱只能挂在一张在途单上）。
 *
 * <h2>三态 gate（一律默认 warn）</h2>
 * 「配好之前必须逐项确认」{@code pharm.gate.picking.line_confirm}、
 * 「配好之前必须已分配窗口」{@code pharm.gate.picking.window}：
 * {@code off} 整段旁路 / {@code warn} 不拦截但回带 {@code warnings} / {@code block} 才真的返错误码。
 * 默认 warn 的理由是这两条校验<b>此前从来不存在</b>（存量是整单一步发药），
 * 直接 block 会让药房当天大面积配不出药。<b>坏配置回落 warn 而不是 off</b>——
 * 把写错的配置值变成静默关闭校验是最坏的一种失效。
 *
 * <h2>错误码 5420–5439（docs/错误码分段.md 已先于编码登记，实测用掉 13 个）</h2>
 * <ul>
 *   <li>5420 摆药单不存在</li>
 *   <li>5421 该挂号已有在途摆药单（读到的、以及并发撞唯一索引的，两条路径同码）</li>
 *   <li>5422 无待摆药的已收费处方（挂号不存在也走这条——纸面上都是「这张单建不成立」）</li>
 *   <li>5423 摆药单状态不允许该操作（开始/配好/发药/作废/分配窗口的并发抢占失败同码）</li>
 *   <li>5424 摆药明细未逐项确认（<b>只有 block 档才返</b>）</li>
 *   <li>5425 未分配发药窗口（<b>只有 block 档才返</b>）</li>
 *   <li>5426 发药窗口不存在或已停用（建单指定/改派/工作台过滤/维护窗口同码）</li>
 *   <li>5427 摆药明细不存在或状态不符（确认与撤销确认两条路径同码）</li>
 *   <li>5428 实摆数量非法（缺省 / ≤0 / 超过应摆量，三条路径同码）</li>
 *   <li>5429 作废原因非法（空、纯空白、超 255 字）</li>
 *   <li>5430 无法识别当前登录用户，不能执行摆药动作</li>
 *   <li>5431 工作台检索条件非法（状态白名单 / 日期格式 / 起止倒置 / 关键词超长 / 必填 id 缺失）</li>
 *   <li>5432 发药窗口配置非法（编码或名称空/超长、编码含非法字符）</li>
 * </ul>
 * 5433–5439 空置。
 */
@Service
@RequiredArgsConstructor
public class PharmPickService {

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    // ---------------- 常量 ----------------

    /** 三态 gate 键（V148 seed = warn），同时登记在 docs/配置手册.md（见 cross_lane） */
    public static final String GATE_LINE_CONFIRM = "pharm.gate.picking.line_confirm";
    public static final String GATE_WINDOW = "pharm.gate.picking.window";
    /** 建单未指定窗口时的自动分配策略：off（默认）/ least_load */
    public static final String KEY_AUTO_WINDOW = "pharm.picking.auto_window";

    public static final String PENDING = "PENDING";
    public static final String PICKING = "PICKING";
    public static final String PREPARED = "PREPARED";
    public static final String DISPENSED = "DISPENSED";
    public static final String CANCELLED = "CANCELLED";

    /** 在途（未收口）三档：数据库部分唯一索引 uq_pharm_picking_live_reg 的谓词与此同源 */
    public static final List<String> LIVE_STATUSES = List.of(PENDING, PICKING, PREPARED);

    /** 检索可选的状态白名单 */
    public static final Set<String> ALL_STATUSES =
            Set.of(PENDING, PICKING, PREPARED, DISPENSED, CANCELLED);

    private static final Map<String, String> STATUS_NAMES = Map.of(
            PENDING, "待摆药", PICKING, "摆药中", PREPARED, "已配好",
            DISPENSED, "已发药", CANCELLED, "已作废");

    /** 检索类端点硬上限：超限回 truncated=true，不做翻页也不静默截断（照抄 v43/v48 检索纪律） */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;
    private static final int KEYWORD_MAX = 64;
    private static final int REASON_MAX = 255;
    private static final int WINDOW_CODE_MAX = 16;
    private static final int WINDOW_NAME_MAX = 64;

    /**
     * 时区写死为业务时区常量（非用户输入，无注入面）：{@code AT TIME ZONE} 的 zone 位置
     * 用绑定参数时 PG 推断不出参数类型，且此处口径必须与 {@link BusinessDates} 同源。
     * 照抄 {@code InpEmrController} 的既有写法。
     */
    private static final String TZ = "'" + HipProfiles.ZONE + "'";

    private static final DateTimeFormatter DAY_STAMP = DateTimeFormatter.BASIC_ISO_DATE;

    // ================================================================
    // 一、待摆药队列
    // ================================================================

    /**
     * 待摆药队列：有<b>已收费待发药药品</b>、且这些药<b>尚未被任何在途摆药单收走</b>的挂号。
     *
     * <p><b>为什么不复用 {@code DispenseController.worklist}</b>：那个端点回的是
     * 「有已收费药品的挂号 + 订单行」四个键，正被前端发药页消费，加键改行都是改契约；
     * 且它<b>不知道摆药单的存在</b>——建了摆药单的挂号仍会留在它的队列里。两个队列语义不同：
     * 那是「等发药的人」，这是「等摆药的活」。
     *
     * <p>排序：加急优先 → 收费早的优先。{@code minutesSinceCreated} 是<b>原始事实</b>
     * （距最早一条待摆医嘱开单的分钟数），不在这里判「是否超时」——时限阈值口径归质控段唯一定义。
     *
     * @param registrationId 可空。给定时只看这一次挂号——工作台点开某个患者问
     *                       「他的药建单了没有」时用，避免在全院队列里翻页找人
     */
    public Map<String, Object> queue(Long registrationId, Integer limit) {
        int cap = capOf(limit);
        var rows = jdbc.queryForList("""
                select r.id                       as registration_id,
                       r.visit_date,
                       p.id                       as patient_id,
                       p.patient_no,
                       p.name                     as patient_name,
                       p.sex,
                       count(*)                   as item_count,
                       sum(o.qty)                 as total_qty,
                       bool_or(coalesce(o.urgent, false)) as urgent,
                       min(o.created_at)          as earliest_order_at,
                       round((extract(epoch from (now() - min(o.created_at))) / 60)::numeric, 0)
                                                  as minutes_since_created
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                join empi_patient p      on p.id = r.patient_id
                where o.order_type = 'DRUG'
                  and o.status = 'CHARGED'
                  and not exists (select 1 from pharm_picking_line l
                                   where l.order_id = o.id and l.released = false)
                  and (?::bigint is null or r.id = ?::bigint)
                group by r.id, r.visit_date, p.id, p.patient_no, p.name, p.sex
                order by bool_or(coalesce(o.urgent, false)) desc, min(o.created_at) asc, r.id asc
                limit ?
                """,
                // 多取 1 条判 truncated：只取 cap 条会让「刚好第 cap 条」漏报
                registrationId, registrationId, cap + 1);
        var body = paged(rows, cap);
        body.put("registrationId", registrationId);
        return body;
    }

    // ================================================================
    // 二、建摆药单
    // ================================================================

    /**
     * 建摆药单：把该挂号下<b>全部尚未被在途单收走的已收费药品医嘱</b>汇成一张单。
     *
     * <p><b>单据粒度是挂号而不是处方</b>：患者一次就诊开了三张处方，药师是一次性把三张的药
     * 一起配好交给他；按处方拆成三张单会让同一个人在队列里出现三次，也无法回答「他的药配齐了没有」。
     *
     * <p><b>三道防重</b>：先读一次在途单给出可读的 5421；即便读过之后被并发插进来，
     * {@code uq_pharm_picking_live_reg} 与 {@code uq_pharm_pick_line_live} 两个部分唯一索引
     * 会让晚到的那笔整单回滚，在这里转成同一个 5421。<b>读只是为了消息好看，硬保证在索引上。</b>
     *
     * @param windowCode 可空。缺省且 {@code pharm.picking.auto_window=least_load} 时按在途单最少的启用窗口自动分配
     */
    @Transactional
    public Map<String, Object> create(Long registrationId, String windowCode, Long operatorId) {
        // created_by 是作业留痕列，识别不出人就不该落一张无主的单（与其余写动作同口径）
        requireOperator(operatorId);
        if (registrationId == null) {
            throw new BizException(5431, "挂号 id 必填");
        }
        String wc = requireWindowOrNull(windowCode);

        var live = jdbc.queryForList(
                "select id, pick_no, status from pharm_picking_order "
                        + "where registration_id = ? and status in ('PENDING', 'PICKING', 'PREPARED') "
                        + "order by id limit 1", registrationId);
        if (!live.isEmpty()) {
            throw new BizException(5421, "该挂号已有在途摆药单 " + live.get(0).get("pick_no")
                    + "（" + statusName(str(live.get(0).get("status"))) + "），请先完成或作废后再建单");
        }

        // 只收「已收费的药品医嘱」且未被在途单收走。**不碰 status**——摆药不改医嘱状态。
        var orders = jdbc.queryForList("""
                select o.id, o.group_no, o.item_id, o.item_code, o.item_name, o.spec, o.unit, o.qty,
                       o.usage_route, o.frequency, o.dose_per_time, o.days, o.remark,
                       coalesce(o.urgent, false) as urgent
                from outp_order o
                where o.registration_id = ?
                  and o.order_type = 'DRUG'
                  and o.status = 'CHARGED'
                  and not exists (select 1 from pharm_picking_line l
                                   where l.order_id = o.id and l.released = false)
                order by o.id
                """, registrationId);
        if (orders.isEmpty()) {
            // 【为什么这里要再查一次在途单】READ COMMITTED 下两条 select 各拿一个快照：
            // 上面那次 live 查询跑完之后、这次医嘱查询跑之前，并发的赢家可能刚好提交，
            // 于是这批药被 not exists 排除掉、orders 空了。此时若直接返 5422
            // 「没有待摆药的处方」是**归错因**——药明明在，只是刚被另一张单收走。
            // 药师看到的应当是「已有在途摆药单 BYxxx」而不是「这个患者没开药」。
            // 并发回归 V50PharmPickingConcurrencyTest 实测撞出过这一条。
            var raced = jdbc.queryForList(
                    "select pick_no, status from pharm_picking_order "
                            + "where registration_id = ? and status in ('PENDING', 'PICKING', 'PREPARED') "
                            + "order by id limit 1", registrationId);
            if (!raced.isEmpty()) {
                throw new BizException(5421, "该挂号的待摆药品已被摆药单 " + raced.get(0).get("pick_no")
                        + "（" + statusName(str(raced.get(0).get("status"))) + "）收走，请刷新后重试");
            }
            // 挂号不存在也走这条：纸面上都是「这张单建不成立」，不另开码（同 4880 的归并口径）
            throw new BizException(5422, "该挂号没有待摆药的已收费处方");
        }

        int itemCount = orders.size();
        int totalQty = 0;
        boolean urgent = false;
        for (var o : orders) {
            totalQty += intOf(o.get("qty"), 0);
            urgent |= Boolean.TRUE.equals(o.get("urgent"));
        }
        if (wc == null) {
            wc = autoWindow();
        }

        String pickNo = nextPickNo();
        Timestamp now = nowMicros();
        Long pickingId;
        try {
            pickingId = jdbc.queryForObject("""
                    insert into pharm_picking_order
                        (pick_no, registration_id, status, window_code,
                         item_count, total_qty, urgent, created_at, created_by)
                    values (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                    returning id
                    """, Long.class, pickNo, registrationId, wc, itemCount, totalQty, urgent, now, operatorId);

            for (var o : orders) {
                jdbc.update("""
                        insert into pharm_picking_line (picking_id, order_id, qty, created_at)
                        values (?, ?, ?, ?)
                        """, pickingId, ((Number) o.get("id")).longValue(), intOf(o.get("qty"), 0), now);
            }
        } catch (DataIntegrityViolationException e) {
            // 并发建单撞上两个部分唯一索引之一。转成与「读到在途单」同一个码：
            // 对调用方而言这两件事是同一件——这批药已经有人在配了。
            throw new BizException(5421, "该挂号的待摆药品已被另一张摆药单收走，请刷新后重试");
        }

        return detail(pickingId);
    }

    // ================================================================
    // 三、状态流转（每一步都是条件更新 + 受影响行数判定）
    // ================================================================

    /** 开始摆药：PENDING → PICKING。并发双摆时只有一方拿到行，另一方返 5423。 */
    @Transactional
    public Map<String, Object> start(Long pickingId, Long operatorId) {
        requireOperator(operatorId);
        int n = jdbc.update(
                "update pharm_picking_order set status = 'PICKING', picker_id = ?, picking_at = ? "
                        + "where id = ? and status = 'PENDING'", operatorId, nowMicros(), pickingId);
        if (n == 0) {
            throw notClaimable(pickingId, "开始摆药");
        }
        return detail(pickingId);
    }

    /**
     * 逐项确认摆药（药师取到这一味药）。
     *
     * <p>{@code pickedQty}（实摆量）与 {@code qty}（应摆量）<b>刻意分成两列</b>：
     * 缺货时只摆出一部分是真实存在的，合成一列就再也答不出「哪一行没摆齐」。
     * 实摆量允许小于应摆量，<b>不允许大于</b>（那是配错药，5428）。
     *
     * <p>确认动作要求单头在 {@code PICKING}——这个条件<b>写在 update 的 exists 子句里</b>而不是
     * 先读再判：否则并发的「置已配好」会在读与写之间挤进来，让一条确认落在已经配好的单子上。
     */
    @Transactional
    public Map<String, Object> pickLine(Long pickingId, Long lineId, Integer pickedQty,
                                        String remark, Long operatorId) {
        requireOperator(operatorId);
        var line = lineOf(pickingId, lineId);
        int ordered = intOf(line.get("qty"), 0);
        int actual = pickedQty == null ? ordered : pickedQty;   // 不传视为按应摆量足额摆出
        if (actual <= 0 || actual > ordered) {
            throw new BizException(5428, "实摆数量非法：应摆 " + ordered + "，实摆 "
                    + (pickedQty == null ? "(未填)" : pickedQty) + "，须为 1–" + ordered);
        }
        int n = jdbc.update("""
                update pharm_picking_line
                   set picked_qty = ?, picked_at = ?, picked_by = ?, remark = ?
                 where id = ? and picking_id = ? and released = false and picked_at is null
                   and exists (select 1 from pharm_picking_order h
                                where h.id = pharm_picking_line.picking_id and h.status = 'PICKING')
                """, actual, nowMicros(), operatorId, trimTo(remark, REASON_MAX), lineId, pickingId);
        if (n == 0) {
            throw lineNotClaimable(pickingId, lineId, "确认摆药");
        }
        return detail(pickingId);
    }

    /** 撤销逐项确认（拿错了放回去）。同样要求单头仍在 PICKING。 */
    @Transactional
    public Map<String, Object> unpickLine(Long pickingId, Long lineId, Long operatorId) {
        requireOperator(operatorId);
        lineOf(pickingId, lineId);
        int n = jdbc.update("""
                update pharm_picking_line
                   set picked_qty = null, picked_at = null, picked_by = null
                 where id = ? and picking_id = ? and released = false and picked_at is not null
                   and exists (select 1 from pharm_picking_order h
                                where h.id = pharm_picking_line.picking_id and h.status = 'PICKING')
                """, lineId, pickingId);
        if (n == 0) {
            throw lineNotClaimable(pickingId, lineId, "撤销确认");
        }
        return detail(pickingId);
    }

    /**
     * 预调剂完成：PICKING → PREPARED（药已配好，等患者来窗口取）。
     *
     * <p>两道 gate 在抢占之前评估（{@code off} 整段旁路 / {@code warn} 回带 warnings 并放行 /
     * {@code block} 返 5424、5425）。gate 是<b>只读预检</b>，失败零副作用。
     */
    @Transactional
    public Map<String, Object> prepared(Long pickingId, Long operatorId) {
        requireOperator(operatorId);
        var head = headOf(pickingId);
        if (!PICKING.equals(str(head.get("status")))) {
            throw notClaimable(pickingId, "置为已配好");
        }

        var warnings = new ArrayList<String>();

        String gLine = gate(GATE_LINE_CONFIRM);
        if (!"off".equals(gLine)) {
            Integer unconfirmed = jdbc.queryForObject(
                    "select count(*) from pharm_picking_line "
                            + "where picking_id = ? and released = false and picked_at is null",
                    Integer.class, pickingId);
            if (unconfirmed != null && unconfirmed > 0) {
                String msg = "尚有 " + unconfirmed + " 条摆药明细未逐项确认";
                if ("block".equals(gLine)) {
                    throw new BizException(5424, msg);
                }
                warnings.add(msg);
            }
        }

        String gWin = gate(GATE_WINDOW);
        if (!"off".equals(gWin) && head.get("window_code") == null) {
            String msg = "尚未分配发药窗口";
            if ("block".equals(gWin)) {
                throw new BizException(5425, msg);
            }
            warnings.add(msg);
        }

        int n = jdbc.update(
                "update pharm_picking_order set status = 'PREPARED', prepared_by = ?, prepared_at = ? "
                        + "where id = ? and status = 'PICKING'", operatorId, nowMicros(), pickingId);
        if (n == 0) {
            throw notClaimable(pickingId, "置为已配好");
        }

        var body = detail(pickingId);
        body.put("warnings", warnings);
        body.put("lineConfirmGate", gLine);
        body.put("windowGate", gWin);
        return body;
    }

    /**
     * 标记已发药（收口这张作业单）：<b>只允许 PREPARED → DISPENSED</b>，即走完了完整流程。
     *
     * <p><b>这一步不扣库存、不改 {@code outp_order.status}</b>——那是发药环节唯一的职责。
     * 本方法只把作业单从「已配好」推到「已发药」，好让下一次就诊能建新单。
     *
     * <p><b>收口同时释放明细对医嘱的占用</b>（{@code released = true}）：不释放的话，
     * 退药把 {@code outp_order} 打回 CHARGED 之后，那批药重新进待摆药队列却会被
     * {@code uq_pharm_pick_line_live} 上这条陈旧的占用挡住，表现为「退过药就再也建不了摆药单」。
     */
    @Transactional
    public Map<String, Object> markDispensed(Long pickingId, Long operatorId) {
        int n = jdbc.update(
                "update pharm_picking_order set status = 'DISPENSED', dispensed_at = ? "
                        + "where id = ? and status = 'PREPARED'", nowMicros(), pickingId);
        if (n == 0) {
            throw notClaimable(pickingId, "标记已发药");
        }
        // 抢占已成功，此刻只有本线程能动这张单的明细
        releaseLines(pickingId);
        return detail(pickingId);
    }

    /**
     * 供发药环节调用的收口钩子：把该挂号的在途摆药单一并推到 DISPENSED，返回收口的单数。
     *
     * <p><b>与 {@link #markDispensed} 的严格程度刻意不同</b>，两个调用点两条口径：
     * <ul>
     *   <li>{@code markDispensed} 是人在摆药工作台上点的，只收 {@code PREPARED}——
     *       跳过预调剂直接点「已发药」是绕过流程。</li>
     *   <li>本方法是发药那一步<b>事后</b>调的，{@code PENDING/PICKING/PREPARED} 三档都收。
     *       既有的整单一步发药端点<b>不经过摆药单</b>，药确实已经发出去了；此时把作业单
     *       卡在 PENDING 上，会让 {@code uq_pharm_picking_live_reg} 永久挡住该挂号的下一张单。
     *       收口后 {@code picking_at}/{@code prepared_at} 仍为 null——<b>那就是事实</b>：
     *       这一单没走摆药与预调剂，不补时刻、不假装走过。</li>
     * </ul>
     *
     * <p><b>不抛异常</b>：没有在途单返回 0，这是走既有发药链路时的正常情形，
     * 不能因为「没有摆药单」而让一次合法的发药失败。
     */
    @Transactional
    public int markDispensedByRegistration(Long registrationId, Long operatorId) {
        if (registrationId == null) {
            return 0;
        }
        Timestamp now = nowMicros();
        int n = jdbc.update(
                "update pharm_picking_order set status = 'DISPENSED', dispensed_at = ? "
                        + "where registration_id = ? and status in ('PENDING', 'PICKING', 'PREPARED')",
                now, registrationId);
        if (n > 0) {
            // 只释放本次刚收口的那几张（按 dispensed_at 精确定位），不误伤更早收口的单
            jdbc.update("""
                    update pharm_picking_line l set released = true
                      from pharm_picking_order h
                     where h.id = l.picking_id and h.registration_id = ? and h.dispensed_at = ?
                       and l.released = false
                    """, registrationId, now);
        }
        return n;
    }

    /**
     * 作废摆药单。<b>作废不删记录</b>——删了行就永远统计不出「建了多少张、作废了多少张」，
     * 摆药差错率也就没了分母（同 v46 取消手术、v48 拒收标本）。
     *
     * <p>先抢单头状态再释放明细：顺序反过来会让并发的第二次作废也把明细清一遍。
     */
    @Transactional
    public Map<String, Object> cancel(Long pickingId, String reason, Long operatorId) {
        requireOperator(operatorId);
        String r = trim(reason);
        if (r == null || r.length() > REASON_MAX) {
            throw new BizException(5429, r == null
                    ? "作废原因必填" : "作废原因不得超过 " + REASON_MAX + " 字");
        }
        int n = jdbc.update("""
                update pharm_picking_order
                   set status = 'CANCELLED', cancelled_by = ?, cancelled_at = ?, cancel_reason = ?
                 where id = ? and status in ('PENDING', 'PICKING', 'PREPARED')
                """, operatorId, nowMicros(), r, pickingId);
        if (n == 0) {
            throw notClaimable(pickingId, "作废");
        }
        releaseLines(pickingId);   // 放行这些医嘱重新建单
        return detail(pickingId);
    }

    /**
     * 释放该单全部明细对医嘱的占用（{@code uq_pharm_pick_line_live} 只约束 {@code released = false} 的行）。
     * <b>只在单头状态抢占成功之后调用</b>——此刻只有本线程能动这张单。
     */
    private void releaseLines(Long pickingId) {
        jdbc.update("update pharm_picking_line set released = true "
                + "where picking_id = ? and released = false", pickingId);
    }

    /**
     * 分配 / 改派发药窗口。已收口（DISPENSED/CANCELLED）的单不再改派——那是改历史。
     *
     * <p><b>本端点不提供「清空窗口」语义</b>（传 null 返 5426 而不是把 window_code 抹成 null）：
     * 工作台按 window_code 分桶展示，误清空等于让一张已配好的药从窗口面板上凭空消失，
     * 患者站在窗口前而屏上没有他。要换窗口就传新窗口。同 v46 4901「不提供清空手术间」。
     */
    @Transactional
    public Map<String, Object> assignWindow(Long pickingId, String windowCode, Long operatorId) {
        requireOperator(operatorId);
        String wc = trim(windowCode);
        if (wc == null) {
            throw new BizException(5426, "发药窗口编码必填");
        }
        requireWindowOrNull(wc);
        int n = jdbc.update(
                "update pharm_picking_order set window_code = ? "
                        + "where id = ? and status in ('PENDING', 'PICKING', 'PREPARED')",
                wc, pickingId);
        if (n == 0) {
            throw notClaimable(pickingId, "分配发药窗口");
        }
        return detail(pickingId);
    }

    // ================================================================
    // 四、工作台查询
    // ================================================================

    /**
     * 摆药工作台：按窗口、按状态、按建单日期、按关键词检索。
     *
     * <p>日期窗口用 {@code (?::date at time zone 'Asia/Shanghai')} 半开区间——
     * {@code created_at} 是 timestamptz，直接与 date 比会按会话时区切日，
     * 容器 TZ 漂移时日窗口会偏 8 小时（本仓已因时区炸过四次）。写法与 {@code InpEmrController} 同源。
     *
     * @param statuses 可空；空表示不限。取值须在白名单内，否则 5431
     * @param from     建单日 yyyy-MM-dd，含
     * @param to       建单日 yyyy-MM-dd，含（半开区间在 SQL 里 +1 天实现）
     */
    public Map<String, Object> search(List<String> statuses, String windowCode,
                                      String from, String to, String keyword, Integer limit) {
        int cap = capOf(limit);
        var norm = new ArrayList<String>();
        if (statuses != null) {
            for (String s : statuses) {
                String v = trim(s);
                if (v == null) continue;
                v = v.toUpperCase(Locale.ROOT);
                if (!ALL_STATUSES.contains(v)) {
                    throw new BizException(5431, "摆药单状态取值非法：" + v);
                }
                if (!norm.contains(v)) norm.add(v);
            }
        }
        String wc = trim(windowCode);
        if (wc != null) {
            requireWindowOrNull(wc);   // 按一个不存在的窗口过滤只会得到空列表，那是在骗人，直接 5426
        }
        LocalDate fromDay = parseDay(from, "起始日期");
        LocalDate toDay = parseDay(to, "截止日期");
        if (fromDay != null && toDay != null && fromDay.isAfter(toDay)) {
            throw new BizException(5431, "起止日期倒置：" + fromDay + " 晚于 " + toDay);
        }
        String kw = trim(keyword);
        if (kw != null && kw.length() > KEYWORD_MAX) {
            throw new BizException(5431, "关键词不得超过 " + KEYWORD_MAX + " 字");
        }

        var sql = new StringBuilder("""
                select h.id, h.pick_no, h.status, h.window_code, w.name as window_name,
                       h.item_count, h.total_qty, h.urgent,
                       h.picker_id, h.picking_at, h.prepared_by, h.prepared_at,
                       h.dispensed_at, h.cancelled_at, h.cancel_reason,
                       h.created_at, h.created_by,
                       h.registration_id, r.visit_date,
                       p.id as patient_id, p.patient_no, p.name as patient_name, p.sex,
                       (select count(*) from pharm_picking_line l
                         where l.picking_id = h.id and l.released = false) as line_count,
                       (select count(*) from pharm_picking_line l
                         where l.picking_id = h.id and l.released = false and l.picked_at is not null)
                           as picked_line_count
                from pharm_picking_order h
                left join pharm_window w      on w.code = h.window_code
                join outp_registration r      on r.id = h.registration_id
                join empi_patient p           on p.id = r.patient_id
                where 1 = 1
                """);
        var args = new ArrayList<Object>();

        if (!norm.isEmpty()) {
            // in (…) 的占位符个数由白名单校验过的元素个数决定，值仍全部走绑定参数（不拼 SQL 字面量）
            sql.append(" and h.status in (").append("?,".repeat(norm.size() - 1)).append("?) ");
            args.addAll(norm);
        }
        if (wc != null) {
            sql.append(" and h.window_code = ? ");
            args.add(wc);
        }
        if (fromDay != null) {
            sql.append(" and h.created_at >= (?::date at time zone ").append(TZ).append(") ");
            args.add(fromDay.toString());
        }
        if (toDay != null) {
            sql.append(" and h.created_at < ((?::date + interval '1 day') at time zone ")
                    .append(TZ).append(") ");
            args.add(toDay.toString());
        }
        if (kw != null) {
            sql.append(" and (h.pick_no ilike ? or p.name ilike ? or p.patient_no ilike ?) ");
            String like = "%" + escapeLike(kw) + "%";   // 通配符转义：% _ \ 一律按字面量匹配
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" order by h.urgent desc, h.created_at desc, h.id desc limit ? ");
        args.add(cap + 1);

        var rows = jdbc.queryForList(sql.toString(), args.toArray());
        var body = paged(rows, cap);
        body.put("statuses", norm);
        body.put("windowCode", wc);
        body.put("from", fromDay == null ? null : fromDay.toString());
        body.put("to", toDay == null ? null : toDay.toString());
        return body;
    }

    /** 摆药单详情（单头 + 明细，明细带医嘱当前状态便于识别陈旧单）。 */
    public Map<String, Object> detail(Long pickingId) {
        var head = headOf(pickingId);
        var body = new LinkedHashMap<String, Object>(head);
        body.put("statusName", statusName(str(head.get("status"))));
        body.put("lines", jdbc.queryForList("""
                select l.id, l.order_id, l.qty, l.picked_qty, l.picked_at, l.picked_by,
                       l.remark, l.released,
                       o.group_no, o.item_id, o.item_code, o.item_name, o.spec, o.unit,
                       o.usage_route, o.frequency, o.dose_per_time, o.days,
                       o.remark      as order_remark,
                       o.status      as order_status,
                       coalesce(o.urgent, false) as urgent
                from pharm_picking_line l
                join outp_order o on o.id = l.order_id
                where l.picking_id = ?
                order by l.id
                """, pickingId));
        return body;
    }

    // ================================================================
    // 五、发药窗口维护
    // ================================================================

    /** 窗口列表（默认只列启用的），带在途单负载——「哪个窗口最忙」是分配窗口时唯一要看的数。 */
    public List<Map<String, Object>> windows(boolean includeDisabled) {
        return jdbc.queryForList("""
                select w.id, w.code, w.name, w.enabled, w.sort_no, w.remark,
                       (select count(*) from pharm_picking_order h
                         where h.window_code = w.code
                           and h.status in ('PENDING', 'PICKING', 'PREPARED')) as live_count,
                       (select count(*) from pharm_picking_order h
                         where h.window_code = w.code and h.status = 'PREPARED') as prepared_count
                from pharm_window w
                where (?::boolean or w.enabled)
                order by w.sort_no, w.code
                """, includeDisabled);
    }

    /**
     * 新增或修改发药窗口（按 code upsert）。<b>不提供删除</b>——历史摆药单的
     * {@code window_code} 外键到本表，删掉窗口会让「当时在哪个窗口配的」永久不可考；停用即可。
     */
    @Transactional
    public Map<String, Object> saveWindow(String code, String name, Boolean enabled,
                                          Integer sortNo, String remark) {
        String c = trim(code);
        String n = trim(name);
        if (c == null || c.length() > WINDOW_CODE_MAX) {
            throw new BizException(5432, c == null
                    ? "窗口编码必填" : "窗口编码不得超过 " + WINDOW_CODE_MAX + " 字");
        }
        if (!c.matches("[A-Za-z0-9_\\-]+")) {
            // 编码是外键目标又会进 URL 查询串，限定字符集免得实施期塞进空格与中文全角
            throw new BizException(5432, "窗口编码只接受字母、数字、下划线与连字符：" + c);
        }
        if (n == null || n.length() > WINDOW_NAME_MAX) {
            throw new BizException(5432, n == null
                    ? "窗口名称必填" : "窗口名称不得超过 " + WINDOW_NAME_MAX + " 字");
        }
        Timestamp now = nowMicros();
        jdbc.update("""
                insert into pharm_window (code, name, enabled, sort_no, remark, created_at, updated_at)
                values (?, ?, coalesce(?::boolean, true), coalesce(?::smallint, 0::smallint), ?, ?, ?)
                on conflict (code) do update
                   set name = excluded.name,
                       enabled = excluded.enabled,
                       sort_no = excluded.sort_no,
                       remark = excluded.remark,
                       updated_at = excluded.updated_at
                """, c, n, enabled, sortNo, trimTo(remark, REASON_MAX), now, now);
        return jdbc.queryForMap("select * from pharm_window where code = ?", c);
    }

    // ================================================================
    // 六、内部工具
    // ================================================================

    /** 当前 gate 档位；未知值回落 warn（不是 off——宁可多提示，不可静默失效） */
    public String gate(String key) {
        String v = configReader.get(key, "warn");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
    }

    /**
     * 单号取序列（{@code nanoTime % 1e6} 会碰撞唯一约束，见 InventoryService.nextInSeq 的教训）。
     * 日戳取 {@link BusinessDates#today()}，<b>不用裸 {@code LocalDate.now()}</b>——
     * 后者跟 JVM 默认时区走，容器 TZ 未生效时单号会到北京时间 08:00 才切日。
     */
    private String nextPickNo() {
        Long seq = jdbc.queryForObject("select nextval('pharm_picking_seq')", Long.class);
        return "BY" + BusinessDates.today().format(DAY_STAMP)
                + "-" + String.format("%06d", seq == null ? 0L : seq);
    }

    /**
     * 写入前一律 {@code truncatedTo(MICROS)}：PG 的 timestamptz 只存到微秒，纳秒会被静默舍入，
     * 「写进去的值 ≠ 读出来的值」的断言当场红（本仓已因此炸过）。
     *
     * <p>包成 {@link Timestamp} 再交给 JdbcTemplate——pgjdbc 的 {@code setObject}
     * 不认 {@code java.time.Instant}，直接传会在运行期报「can't infer the SQL type」。
     * 与 {@code PathologyRegistryController} 的既有写法同源。
     */
    private static Timestamp nowMicros() {
        return Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private Map<String, Object> headOf(Long pickingId) {
        if (pickingId == null) {
            throw new BizException(5420, "摆药单不存在");
        }
        var rows = jdbc.queryForList("select * from pharm_picking_order where id = ?", pickingId);
        if (rows.isEmpty()) {
            throw new BizException(5420, "摆药单不存在：" + pickingId);
        }
        return rows.get(0);
    }

    private Map<String, Object> lineOf(Long pickingId, Long lineId) {
        if (pickingId == null || lineId == null) {
            throw new BizException(5427, "摆药明细不存在");
        }
        var rows = jdbc.queryForList(
                "select * from pharm_picking_line where id = ? and picking_id = ?", lineId, pickingId);
        if (rows.isEmpty()) {
            throw new BizException(5427, "摆药明细不存在：单 " + pickingId + " 行 " + lineId);
        }
        return rows.get(0);
    }

    /**
     * 抢占失败后的归因：单子不存在返 5420，存在则是状态不对返 5423（并带上当前状态）。
     * <b>这次读发生在抢占失败之后</b>，只用于生成可读消息，不参与并发判定。
     */
    private BizException notClaimable(Long pickingId, String action) {
        var rows = pickingId == null ? List.<Map<String, Object>>of()
                : jdbc.queryForList("select status from pharm_picking_order where id = ?", pickingId);
        if (rows.isEmpty()) {
            return new BizException(5420, "摆药单不存在：" + pickingId);
        }
        return new BizException(5423, "摆药单当前为「" + statusName(str(rows.get(0).get("status")))
                + "」，不能" + action + "（可能已被其他药师操作，请刷新后重试）");
    }

    private BizException lineNotClaimable(Long pickingId, Long lineId, String action) {
        var head = jdbc.queryForList("select status from pharm_picking_order where id = ?", pickingId);
        if (!head.isEmpty() && !PICKING.equals(str(head.get(0).get("status")))) {
            return new BizException(5423, "摆药单当前为「" + statusName(str(head.get(0).get("status")))
                    + "」，不能" + action);
        }
        return new BizException(5427, "摆药明细不存在或状态不符，不能" + action
                + "：单 " + pickingId + " 行 " + lineId);
    }

    /** 窗口校验：null 直接放行（窗口可空），非 null 则必须存在且启用 */
    private String requireWindowOrNull(String code) {
        String c = trim(code);
        if (c == null) {
            return null;
        }
        var rows = jdbc.queryForList("select code, enabled from pharm_window where code = ?", c);
        if (rows.isEmpty()) {
            throw new BizException(5426, "发药窗口不存在：" + c);
        }
        if (!Boolean.TRUE.equals(rows.get(0).get("enabled"))) {
            throw new BizException(5426, "发药窗口已停用：" + c);
        }
        return c;
    }

    /**
     * 建单未指定窗口时的自动分配。默认 {@code off} 返 null（留给人工分配）——
     * 不默认开自动分配：各院的窗口分配规则差异极大（按药品类别、按患者类型、按队列长度），
     * 猜一个当默认只会让实施期天天来改。
     */
    private String autoWindow() {
        String mode = configReader.get(KEY_AUTO_WINDOW, "off");
        if (!"least_load".equalsIgnoreCase(mode == null ? "" : mode.trim())) {
            return null;
        }
        var rows = jdbc.queryForList("""
                select w.code
                from pharm_window w
                where w.enabled
                order by (select count(*) from pharm_picking_order h
                           where h.window_code = w.code
                             and h.status in ('PENDING', 'PICKING', 'PREPARED')) asc,
                         w.sort_no asc, w.code asc
                limit 1
                """);
        return rows.isEmpty() ? null : str(rows.get(0).get("code"));
    }

    private void requireOperator(Long operatorId) {
        if (operatorId == null) {
            throw new BizException(5430, "无法识别当前登录用户，不能执行摆药动作");
        }
    }

    private LocalDate parseDay(String s, String label) {
        String v = trim(s);
        if (v == null) {
            return null;
        }
        try {
            // 解析不出即报错，不静默吃成 today()——否则查出来的是「今天」而不是用户要的那天
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new BizException(5431, label + "格式非法（须 yyyy-MM-dd）：" + v);
        }
    }

    private int capOf(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BizException(5431, "limit 须在 1–" + MAX_LIMIT + " 之间：" + limit);
        }
        return limit;
    }

    private static Map<String, Object> paged(List<Map<String, Object>> rows, int cap) {
        boolean truncated = rows.size() > cap;
        var body = new LinkedHashMap<String, Object>();
        body.put("rows", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        body.put("limit", cap);
        return body;
    }

    private static String statusName(String code) {
        return STATUS_NAMES.getOrDefault(code, code == null ? "未知" : code);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 仅用于本类留痕文案（备注），绝不用于截断调用方送来的业务内容 */
    private static String trimTo(String s, int max) {
        String t = trim(s);
        return t == null || t.length() <= max ? t : t.substring(0, max);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }
}
