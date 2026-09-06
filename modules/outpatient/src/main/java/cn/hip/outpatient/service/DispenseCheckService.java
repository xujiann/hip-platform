package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.config.HipProfiles;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v50 车道 C：门诊发药核对——双人核对 / 高危药品单独确认 / 看似听似（LASA）提示 / 退回摆药。
 *
 * <p><b>与既有发药链路的边界（一个字节没改）</b>：{@link DispenseService} 的
 * {@code dispense}/{@code returnDrug} 与 {@code DispenseController} 的 4 个端点逐字不动，
 * 错误码 6001–6006 原样保留。本类只<b>调用</b> {@code DispenseService.dispense}，
 * 不改它、不复制它的扣库存逻辑——那段「先抢占状态再扣库存」的并发写法是本仓的既有资产
 * （读-判-写会让并发发药把同一条医嘱扣两次库存），重写一遍只会多出第二个并发缺陷。
 *
 * <p><b>三条口径，请勿在没有数据支撑时改掉</b>：
 * <ul>
 *   <li><b>摆药人 ≠ 核对人（5443），与 gate 无关、永远生效</b>。gate 管的是
 *       「未核对能不能发药」，不是「一个人能不能把摆药与核对两个位置都占了」。
 *       照 v48 病理 5263 的口径：单人药房的正确用法是<b>不建核对单直接发药</b>
 *       （warn 档放行并回带 warnings、台账留痕），而不是自己摆完自己核。
 *       两者混为一谈会让 warn 档把双人核对整个架空。</li>
 *   <li><b>高危药品必须逐行单独确认</b>（5445/5446），且确认人必须就是本次核对人。
 *       做成核对单上一个总的复选框，等于一次点头把五种高危药一起认了，与没确认没区别。
 *       这条<b>不另设 gate</b>：它只拦本版新增的核对流程（此前无人调用），
 *       拦不到任何存量链路，不存在「直接 block 让存量瞬间大面积失败」的风险；
 *       给它加 gate 只会让「核对通过」四个字变得可以打折。</li>
 *   <li><b>退回摆药不要求换人</b>。双人核对约束的是<b>放行</b>（不能自己批准自己），
 *       退回是不予放行，自查自退没有放行风险。反过来若也要求换人，单人药房的药师
 *       摆错了药却无人可退，处方会被 {@code uq_pharm_check_line_active} 永久锁死。</li>
 * </ul>
 *
 * <p><b>高危与看似听似都不猜</b>：{@code md_drug.high_alert} 是可空三态属性位
 * （true 是 / false 否 / <b>null 未维护</b>），{@code pharm_lasa_pair} 是药剂科逐条维护的对照表。
 * 不按药名匹配、不用字符串相似度自动生成——理由见 V149 头注释。
 * 未维护的行在返回体里以「本单 N 行药品高危属性未维护」明说提示可能不完整，<b>不假装安全</b>。
 *
 * <p><b>本版如实留的缺口</b>：
 * <ul>
 *   <li>既有端点 {@code POST /api/outpatient/dispense/{registrationId}} 不经过本 gate，
 *       走那条路仍可零核对发药，台账也记不到。把 gate 接进既有链路要改
 *       {@code DispenseService}（车道 C 名下没有那个文件），已写进 cross_lane。</li>
 *   <li><b>不做发药窗口叫号</b>（5440–5459 段里提到的第三件事）：窗口是<b>物理资源</b>，
 *       全仓既无窗口/药柜主数据，也无叫号屏对接。凭空造一张
 *       {@code pharm_window(id, name)} 再让人手选，只是把一个下拉框叫做「窗口管理」，
 *       既排不了队也叫不了号。留给车道 B 的摆药与预调剂一并规划（cross_lane 已列）。</li>
 *   <li><b>不碰麻精药品</b>：{@code md_drug} 无「精麻毒放」属性位，按药名猜是危险假实现。</li>
 * </ul>
 *
 * <p><b>错误码 5440–5451</b>（本车道子段 5440–5459，5452–5459 空置未启用）：
 * <ul>
 *   <li>5440 核对单不存在</li>
 *   <li>5441 核对单状态不允许该操作（已核对 / 已退回 / 并发抢写，归并同码、消息区分）</li>
 *   <li>5442 该挂号没有待核对的已收费药品处方</li>
 *   <li>5443 摆药人与核对人不得为同一人（<b>与 gate 无关</b>，含无法核验摆药人的情形）</li>
 *   <li>5444 无法识别当前登录用户，不能摆药 / 核对 / 确认</li>
 *   <li>5445 存在未单独确认的高危药品，不能通过核对</li>
 *   <li>5446 高危确认不成立（行不存在 / 不属于该单 / 该行不是高危药 / 重复确认）</li>
 *   <li>5447 退回摆药必须填写原因</li>
 *   <li>5448 未通过核对不得发药（<b>只有 block 档才返</b>，warn 档改走 warnings）</li>
 *   <li>5449 药品高危属性维护参数非法（药品不存在 / 取值缺失）</li>
 *   <li>5450 看似听似对照参数非法（两药相同 / 药品不存在 / 重复登记 / 对照不存在）</li>
 *   <li>5451 该挂号的待发处方已挂在其他未结束的核对单上</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DispenseCheckService {

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;
    /** 既有发药链路：只调用，不改动（见类注释） */
    private final DispenseService dispenseService;

    /** 发药核对 gate 配置键（V149 已 seed，默认值 warn） */
    public static final String GATE_KEY = "pharm.gate.dispense.check";

    private static final java.time.ZoneId ZONE = java.time.ZoneId.of(HipProfiles.ZONE);

    /** 检索类端点硬上限：超限回 truncated=true，不做翻页也不静默截断（照抄 v43 医嘱检索纪律） */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;

    private static final int REASON_MAX = 255;
    private static final int NOTE_MAX = 200;
    private static final int LASA_NOTE_MAX = 500;

    // ==================================================================
    // gate
    // ==================================================================

    /**
     * 发药核对 gate 三态解析。
     *
     * <p><b>坏配置回落 warn 而非 off</b>：把 'blocked'、'true'、'1' 这类写错的值当成 off，
     * 等于让一个笔误静默关掉核对；回落 warn 至少还会在返回体里喊一声、台账里留一行。
     */
    public String gate() {
        String v = configReader.get(GATE_KEY, "warn");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
    }

    // ==================================================================
    // 一、建核对单（摆药完成，提交核对）
    // ==================================================================

    /**
     * 待核对预览：该挂号下已收费待发、且尚未挂在任何生效核对单上的药品处方，带高危与 LASA 提示。
     * 建单前给摆药人看，不落任何数据。
     */
    public Map<String, Object> preview(Long registrationId) {
        var pending = pendingOrders(registrationId);
        var body = new LinkedHashMap<String, Object>();
        body.put("registrationId", registrationId);
        body.put("lines", decorate(pending));
        body.put("unmaintainedCount", unmaintainedCount(pending));
        body.put("note", "unmaintainedCount>0 表示这些药品的高危属性尚未维护（md_drug.high_alert 为空），"
                + "高危提示不完整——不代表它们不是高危药");
        return body;
    }

    /**
     * 某挂号的核对总览（只读）：待核对行 + gate 评估 + 该挂号既有的核对单。
     * 前端进核对页的第一跳；把三次往返并成一次，也让「能不能发药」只有一个答案来源。
     */
    public Map<String, Object> overview(Long registrationId) {
        var body = new LinkedHashMap<String, Object>(preview(registrationId));
        body.putAll(evaluate(registrationId));
        body.put("checks", jdbc.queryForList("""
                select c.id, c.status, c.picker_id, pu.real_name as picker_name, c.picked_at,
                       c.checker_id, cu.real_name as checker_name, c.checked_at,
                       c.returned_at, c.return_reason,
                       (select count(*) from pharm_dispense_check_line l
                        where l.check_id = c.id and l.voided is false) as line_count
                from pharm_dispense_check c
                left join sys_user pu on pu.id = c.picker_id
                left join sys_user cu on cu.id = c.checker_id
                where c.registration_id = ?
                order by c.id desc
                limit ?
                """, registrationId, MAX_LIMIT));
        return body;
    }

    /**
     * 建核对单：把当前待发处方整单快照成核对单行。
     *
     * <p>高危与 LASA 在<b>建单时刻快照</b>进行（{@code high_alert}/{@code lasa_note} 两列）：
     * 药剂科事后修订目录不回改历史行——事后追责看的是「当时屏幕上有没有那句提示」。
     */
    @Transactional
    public Map<String, Object> create(Long registrationId, Long pickerId) {
        if (pickerId == null) throw new BizException(5444, "无法识别当前登录用户，不能提交摆药核对");

        // 先看这个挂号到底有没有待发药，再看是不是已经被别的单占了——两种情形的处置完全不同
        var charged = jdbc.queryForList("""
                select id from outp_order
                where registration_id = ? and order_type = 'DRUG' and status = 'CHARGED'
                """, registrationId);
        if (charged.isEmpty()) throw new BizException(5442, "该挂号没有待核对的已收费药品处方");

        var pending = pendingOrders(registrationId);
        if (pending.isEmpty()) {
            var held = jdbc.queryForList("""
                    select distinct c.id, c.status from pharm_dispense_check c
                    join pharm_dispense_check_line l on l.check_id = c.id
                    join outp_order o on o.id = l.order_id
                    where o.registration_id = ? and o.order_type = 'DRUG' and o.status = 'CHARGED'
                      and l.voided is false
                    order by c.id desc
                    """, registrationId);
            throw new BizException(5451, "该挂号的待发处方已挂在核对单 "
                    + held.stream().map(r -> String.valueOf(r.get("id"))).toList()
                    + " 上（状态 " + held.stream().map(r -> String.valueOf(r.get("status"))).toList()
                    + "），请先完成或退回该单");
        }

        Long checkId;
        try {
            var head = jdbc.queryForList("""
                    insert into pharm_dispense_check(registration_id, status, picker_id, picked_at)
                    values (?, 'PREPARED', ?, now())
                    returning id, picked_at
                    """, registrationId, pickerId);
            checkId = asLong(head.get(0).get("id"));

            for (var line : decorate(pending)) {
                jdbc.update("""
                        insert into pharm_dispense_check_line(check_id, order_id, drug_id, high_alert, lasa_note)
                        values (?, ?, ?, ?::boolean, ?)
                        """, checkId, line.get("orderId"), line.get("drugId"),
                        line.get("highAlert"), trimTo((String) line.get("lasaNote"), LASA_NOTE_MAX));
            }
        } catch (DuplicateKeyException e) {
            // uq_pharm_check_line_active 兜底：两个药师同时点「提交核对」，
            // 应用层那次 pendingOrders 各自读到的都是空占用（读-判-写窗口）
            throw new BizException(5451, "该挂号的待发处方已被另一张核对单占用（并发提交），请刷新后重试");
        }
        return detail(checkId);
    }

    // ==================================================================
    // 二、高危单独确认
    // ==================================================================

    /**
     * 高危药品单独确认：逐行、独立时刻、独立签名人。
     *
     * <p>确认人同样受 <b>5443</b> 约束（不得是摆药人）——确认是核对动作的一部分，
     * 摆药人自己确认自己摆的高危药，与没确认没有区别。
     */
    @Transactional
    public Map<String, Object> confirmHighAlert(Long checkId, Long lineId, Long uid) {
        if (uid == null) throw new BizException(5444, "无法识别当前登录用户，不能确认高危药品");
        var head = head(checkId);
        if (head == null) throw new BizException(5440, "核对单不存在：" + checkId);
        if (!"PREPARED".equals(head.get("status"))) {
            throw new BizException(5441, "核对单已" + statusText(head.get("status")) + "，不能再确认高危药品");
        }
        requireNotPicker(head, uid, "确认高危药品");

        var updated = jdbc.queryForList("""
                update pharm_dispense_check_line
                set high_alert_confirmed_at = now(), high_alert_confirmed_by = ?
                where id = ? and check_id = ? and voided is false
                  and high_alert is true and high_alert_confirmed_at is null
                returning high_alert_confirmed_at
                """, uid, lineId, checkId);
        if (updated.isEmpty()) {
            var rows = jdbc.queryForList("""
                    select l.high_alert, l.high_alert_confirmed_at, d.name
                    from pharm_dispense_check_line l join md_drug d on d.id = l.drug_id
                    where l.id = ? and l.check_id = ?
                    """, lineId, checkId);
            if (rows.isEmpty()) throw new BizException(5446, "核对单行不存在或不属于该核对单：" + lineId);
            var r = rows.get(0);
            if (r.get("high_alert_confirmed_at") != null) {
                throw new BizException(5446, "该行已于 " + r.get("high_alert_confirmed_at") + " 确认过，不可重复确认");
            }
            Boolean ha = asBool(r.get("high_alert"));
            throw new BizException(5446, "「" + r.get("name") + "」"
                    + (ha == null ? "建单时高危属性未维护" : "不是高危药品")
                    + "，无需单独确认（单独确认只对建单时已标记为高危的药品成立）");
        }
        return detail(checkId);
    }

    // ==================================================================
    // 三、核对通过 / 退回摆药
    // ==================================================================

    /**
     * 核对通过。
     *
     * <p>三道闸：核对人 ≠ 摆药人（5443，恒定）→ 高危逐行已由<b>本人</b>确认（5445）→
     * 条件更新抢占 PREPARED（5441，防并发双核对）。
     * DB 侧 {@code chk_pharm_check_two_person} 再兜一层，挡直连改库。
     */
    @Transactional
    public Map<String, Object> pass(Long checkId, Long uid) {
        if (uid == null) throw new BizException(5444, "无法识别当前登录用户，不能核对");
        var head = head(checkId);
        if (head == null) throw new BizException(5440, "核对单不存在：" + checkId);
        if (!"PREPARED".equals(head.get("status"))) {
            throw new BizException(5441, "核对单已" + statusText(head.get("status")) + "，不能重复核对");
        }
        requireNotPicker(head, uid, "核对");

        // 未确认的高危行：既包括「压根没确认」，也包括「被别人确认了」——
        // 后者同样不成立：核对通过的责任人是本次核对人，签名必须落在同一个人身上。
        var unconfirmed = jdbc.queryForList("""
                select d.name, l.high_alert_confirmed_by
                from pharm_dispense_check_line l join md_drug d on d.id = l.drug_id
                where l.check_id = ? and l.voided is false and l.high_alert is true
                  and (l.high_alert_confirmed_at is null or l.high_alert_confirmed_by is distinct from ?::bigint)
                order by l.id
                """, checkId, uid);
        if (!unconfirmed.isEmpty()) {
            boolean byOther = unconfirmed.stream().anyMatch(r -> r.get("high_alert_confirmed_by") != null);
            throw new BizException(5445, "存在未单独确认的高危药品：「"
                    + String.join("、", unconfirmed.stream().map(r -> String.valueOf(r.get("name"))).toList())
                    + "」" + (byOther ? "（其中有行由他人确认——核对通过的签名必须落在同一个人身上）" : "")
                    + "，请逐行确认后再通过核对");
        }

        var updated = jdbc.queryForList("""
                update pharm_dispense_check
                set status = 'CHECKED', checker_id = ?, checked_at = now()
                where id = ? and status = 'PREPARED' and picker_id <> ?
                returning checked_at
                """, uid, checkId, uid);
        if (updated.isEmpty()) {
            throw new BizException(5441, "核对单状态已变化（并发核对或已退回），本次核对未生效");
        }
        return detail(checkId);
    }

    /**
     * 核对不通过，退回摆药：整单行 voided，处方回到「未核对」状态可重新摆药建单。
     *
     * <p><b>不删记录</b>——删了行就再也统计不出「核对退回率」，也查不到退了什么原因
     * （同 v46 取消手术不删记录、v48 拒收标本不删记录）。
     *
     * <p><b>不要求换人</b>：见类注释第三条口径。
     */
    @Transactional
    public Map<String, Object> returnToPicking(Long checkId, Long uid, String reason) {
        if (uid == null) throw new BizException(5444, "无法识别当前登录用户，不能退回摆药");
        String r = trim(reason);
        if (r == null) throw new BizException(5447, "退回摆药必须填写原因（退回率与退回原因是药房质控的分母）");
        if (r.length() > REASON_MAX) {
            throw new BizException(5447, "退回原因过长（最多 " + REASON_MAX + " 字，当前 " + r.length() + " 字）");
        }
        var head = head(checkId);
        if (head == null) throw new BizException(5440, "核对单不存在：" + checkId);
        if (!"PREPARED".equals(head.get("status"))) {
            throw new BizException(5441, "核对单已" + statusText(head.get("status")) + "，不能退回摆药");
        }

        var updated = jdbc.queryForList("""
                update pharm_dispense_check
                set status = 'RETURNED', returned_by = ?, returned_at = now(), return_reason = ?
                where id = ? and status = 'PREPARED'
                returning returned_at
                """, uid, r, checkId);
        if (updated.isEmpty()) {
            throw new BizException(5441, "核对单状态已变化（并发核对或已退回），本次退回未生效");
        }
        // 作废行：让处方脱离 uq_pharm_check_line_active，重新摆药后可以再建单
        jdbc.update("update pharm_dispense_check_line set voided = true where check_id = ?", checkId);
        return detail(checkId);
    }

    // ==================================================================
    // 四、发药前 gate 评估 / 核对后发药
    // ==================================================================

    /**
     * 发药前评估：该挂号下每一条待发处方是否都被<b>已通过</b>的核对单覆盖。
     *
     * <p><b>按处方行判，不按挂号判</b>：同一挂号上午发过药、下午又开一张新处方，
     * 若按挂号判「已核对过」，上午那张单会替下午的新处方背书——
     * 与既有 dispense「整表回查会把上午已发的药也列进下午的发药凭条」是同一个坑。
     */
    public Map<String, Object> evaluate(Long registrationId) {
        var missing = jdbc.queryForList("""
                select o.id, o.item_name, o.qty from outp_order o
                where o.registration_id = ? and o.order_type = 'DRUG' and o.status = 'CHARGED'
                  and not exists (
                      select 1 from pharm_dispense_check_line l
                      join pharm_dispense_check c on c.id = l.check_id
                      where l.order_id = o.id and l.voided is false and c.status = 'CHECKED')
                order by o.id
                """, registrationId);

        String gate = gate();
        boolean passed = missing.isEmpty();
        var warnings = new ArrayList<String>();
        if (!passed && !"off".equals(gate)) {
            warnings.add("未通过发药核对即发药：" + missing.size() + " 条处方无已核对记录（"
                    + String.join("、", missing.stream().map(m -> String.valueOf(m.get("item_name"))).toList())
                    + "）（gate=" + gate + (("warn".equals(gate)) ? " 已放行，本次发药已记入核对台账）" : "）"));
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("registrationId", registrationId);
        body.put("gate", gate);
        body.put("checkPassed", passed);
        body.put("missing", missing.stream().map(m -> {
            var x = new LinkedHashMap<String, Object>();
            x.put("orderId", m.get("id"));
            x.put("itemName", m.get("item_name"));
            x.put("qty", m.get("qty"));
            return x;
        }).toList());
        body.put("warnings", warnings);
        return body;
    }

    /**
     * 核对后发药：gate 校验通过后<b>委托既有 {@link DispenseService#dispense}</b>。
     *
     * <p>既有实现一行未改也未复制——扣库存的并发写法（先抢占状态再扣）留在原处，
     * 6001–6003 也原样透出。本方法只在它前面加一道 gate、后面加一行台账。
     *
     * <p>三档都记台账（含 passed=true 的）：只记未通过的那部分，
     * 「未核对发药率」的分母就是假的，也就没法拿数据决定何时收紧到 block。
     */
    @Transactional
    public Map<String, Object> dispenseWithCheck(Long registrationId, Long operatorId) {
        var eval = evaluate(registrationId);
        String gate = (String) eval.get("gate");
        boolean passed = (boolean) eval.get("checkPassed");

        if (!passed && "block".equals(gate)) {
            @SuppressWarnings("unchecked")
            var missing = (List<Map<String, Object>>) eval.get("missing");
            throw new BizException(5448, "未通过发药核对不得发药：" + missing.size() + " 条处方无已核对记录（"
                    + String.join("、", missing.stream().map(m -> String.valueOf(m.get("itemName"))).toList())
                    + "）（gate " + GATE_KEY + "=block）");
        }

        @SuppressWarnings("unchecked")
        var missing = (List<Map<String, Object>>) eval.get("missing");
        jdbc.update("""
                insert into pharm_dispense_gate_log(registration_id, gate, check_passed, missing_orders,
                                                    operator_id, occurred_at)
                values (?, ?, ?, ?, ?, now())
                """, registrationId, gate, passed,
                trimTo(missing.stream().map(m -> String.valueOf(m.get("orderId")))
                        .reduce((a, b) -> a + "," + b).orElse(null), 500),
                operatorId);

        // 既有链路：BizException(6001/6002/6003) 原样上抛，契约不变
        List<OutpOrder> dispensed = dispenseService.dispense(registrationId);

        var body = new LinkedHashMap<String, Object>();
        body.put("registrationId", registrationId);
        body.put("gate", gate);
        body.put("checkPassed", passed);
        body.put("warnings", eval.get("warnings"));
        body.put("orders", dispensed);
        return body;
    }

    // ==================================================================
    // 五、查询
    // ==================================================================

    /** 待核对工作台：全部 PREPARED 核对单（按摆药时刻先进先出） */
    public Map<String, Object> worklist(Integer limit) {
        int cap = capOf(limit);
        var rows = jdbc.queryForList("""
                select c.id, c.registration_id, c.picked_at, c.picker_id, u.real_name as picker_name,
                       p.patient_no, p.name as patient_name,
                       (select count(*) from pharm_dispense_check_line l
                        where l.check_id = c.id and l.voided is false) as line_count,
                       (select count(*) from pharm_dispense_check_line l
                        where l.check_id = c.id and l.voided is false and l.high_alert is true) as high_alert_count,
                       (select count(*) from pharm_dispense_check_line l
                        where l.check_id = c.id and l.voided is false and l.high_alert is true
                          and l.high_alert_confirmed_at is null) as high_alert_pending
                from pharm_dispense_check c
                join outp_registration r on r.id = c.registration_id
                join empi_patient p on p.id = r.patient_id
                left join sys_user u on u.id = c.picker_id
                where c.status = 'PREPARED'
                order by c.picked_at, c.id
                limit ?
                """, cap + 1);
        boolean truncated = rows.size() > cap;
        var body = new LinkedHashMap<String, Object>();
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        return body;
    }

    /** 核对单详情：单头 + 行（含高危/LASA 提示与确认状态） */
    public Map<String, Object> detail(Long checkId) {
        var head = head(checkId);
        if (head == null) throw new BizException(5440, "核对单不存在：" + checkId);

        var lines = jdbc.queryForList("""
                select l.id, l.order_id, l.drug_id, l.high_alert, l.lasa_note, l.voided,
                       l.high_alert_confirmed_at, l.high_alert_confirmed_by,
                       cu.real_name as high_alert_confirmed_by_name,
                       d.name as drug_name, d.spec, d.unit, o.qty, o.usage_route, o.frequency, o.dose_per_time
                from pharm_dispense_check_line l
                join md_drug d on d.id = l.drug_id
                join outp_order o on o.id = l.order_id
                left join sys_user cu on cu.id = l.high_alert_confirmed_by
                where l.check_id = ?
                order by l.id
                """, checkId);

        long unmaintained = lines.stream()
                .filter(l -> !Boolean.TRUE.equals(asBool(l.get("voided"))) && l.get("high_alert") == null)
                .count();
        long pendingHigh = lines.stream()
                .filter(l -> !Boolean.TRUE.equals(asBool(l.get("voided")))
                        && Boolean.TRUE.equals(asBool(l.get("high_alert")))
                        && l.get("high_alert_confirmed_at") == null)
                .count();

        var warnings = new ArrayList<String>();
        if (unmaintained > 0) {
            warnings.add("本单 " + unmaintained + " 行药品的高危属性尚未维护（md_drug.high_alert 为空），"
                    + "高危提示不完整——这不代表它们不是高危药");
        }
        if (pendingHigh > 0) {
            warnings.add("本单尚有 " + pendingHigh + " 行高危药品未单独确认，不能通过核对");
        }

        var body = new LinkedHashMap<String, Object>(head);
        body.put("lines", lines);
        body.put("highAlertPending", pendingHigh);
        body.put("unmaintainedCount", unmaintained);
        body.put("warnings", warnings);
        body.put("gate", gate());
        return body;
    }

    /**
     * 核对记录查询（默认今天）。
     *
     * <p>日窗口用<b>业务时区</b>的半开区间：裸 {@code checked_at::date} 依赖会话时区，
     * JVM/容器时区一漂移就整体偏 8 小时（本仓已因此炸过四次）。
     * 「今天」一律走 {@link BusinessDates#today()}，绝不用裸 {@code LocalDate.now()}。
     */
    public Map<String, Object> records(LocalDate date, String status, Integer limit) {
        LocalDate d = date == null ? BusinessDates.today() : date;
        int cap = capOf(limit);
        String tz = "'" + ZONE.getId() + "'";
        String st = trim(status);
        // 时区写死为业务时区常量（非用户输入，无注入面）：AT TIME ZONE 的 zone 位置用绑定参数时
        // PG 无法推断参数类型，且此处口径必须与 Java 侧 ZONE 同源（照抄 InpEmrController 写法）。
        var rows = jdbc.queryForList("""
                select c.id, c.registration_id, c.status, c.picker_id, pu.real_name as picker_name, c.picked_at,
                       c.checker_id, cu.real_name as checker_name, c.checked_at,
                       c.returned_by, ru.real_name as returned_by_name, c.returned_at, c.return_reason,
                       (select count(*) from pharm_dispense_check_line l where l.check_id = c.id) as line_count
                from pharm_dispense_check c
                left join sys_user pu on pu.id = c.picker_id
                left join sys_user cu on cu.id = c.checker_id
                left join sys_user ru on ru.id = c.returned_by
                where c.picked_at >= (?::date at time zone %1$s)
                  and c.picked_at <  ((?::date + interval '1 day') at time zone %1$s)
                  and (?::varchar is null or c.status = ?)
                order by c.picked_at desc, c.id desc
                limit ?
                """.formatted(tz), d.toString(), d.toString(), st, st, cap + 1);
        boolean truncated = rows.size() > cap;
        var body = new LinkedHashMap<String, Object>();
        body.put("date", d.toString());
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        return body;
    }

    /** 未核对发药台账（默认今天）：warn 档到底放行了多少次、是谁放的 */
    public Map<String, Object> gateLog(LocalDate date, Boolean onlyBypassed, Integer limit) {
        LocalDate d = date == null ? BusinessDates.today() : date;
        int cap = capOf(limit);
        String tz = "'" + ZONE.getId() + "'";
        var rows = jdbc.queryForList("""
                select g.id, g.registration_id, g.gate, g.check_passed, g.missing_orders,
                       g.operator_id, u.real_name as operator_name, g.occurred_at
                from pharm_dispense_gate_log g
                left join sys_user u on u.id = g.operator_id
                where g.occurred_at >= (?::date at time zone %1$s)
                  and g.occurred_at <  ((?::date + interval '1 day') at time zone %1$s)
                  and (?::boolean is false or g.check_passed is false)
                order by g.occurred_at desc, g.id desc
                limit ?
                """.formatted(tz), d.toString(), d.toString(),
                Boolean.TRUE.equals(onlyBypassed), cap + 1);
        boolean truncated = rows.size() > cap;
        var body = new LinkedHashMap<String, Object>();
        body.put("date", d.toString());
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        body.put("note", "只记经 /dispense-check/registrations/{id}/dispense 发出的药；"
                + "既有 /dispense/{registrationId} 端点不经过本 gate，记不到（见 cross_lane）");
        return body;
    }

    // ==================================================================
    // 六、属性位维护（高危 / 看似听似）——靠维护不靠猜
    // ==================================================================

    /**
     * 维护高危药品属性位。{@code value} 只接受 true/false，<b>不接受把已维护的行清回 null</b>：
     * null 的语义是「从未维护」，人工写回去等于伪造一条「没人看过」的历史。
     */
    @Transactional
    public Map<String, Object> setHighAlert(Long drugId, Boolean value, Long uid) {
        if (uid == null) throw new BizException(5444, "无法识别当前登录用户，不能维护高危属性");
        if (value == null) {
            throw new BizException(5449, "必须明确指定 true/false；清空回「未维护」是伪造维护历史，不予支持");
        }
        var rows = jdbc.queryForList("select id, name from md_drug where id = ?", drugId);
        if (rows.isEmpty()) throw new BizException(5449, "药品不存在：" + drugId);

        jdbc.update("""
                update md_drug set high_alert = ?, high_alert_at = now(), high_alert_by = ?
                where id = ?
                """, value, uid, drugId);
        var after = jdbc.queryForList(
                "select id, name, high_alert, high_alert_at, high_alert_by from md_drug where id = ?", drugId);
        return after.get(0);
    }

    /** 已标记为高危的药品清单 */
    public Map<String, Object> highAlertDrugs(Integer limit) {
        int cap = capOf(limit);
        var rows = jdbc.queryForList("""
                select d.id, d.code, d.name, d.spec, d.unit, d.enabled,
                       d.high_alert_at, u.real_name as high_alert_by_name
                from md_drug d left join sys_user u on u.id = d.high_alert_by
                where d.high_alert is true
                order by d.code
                limit ?
                """, cap + 1);
        return paged(rows, cap);
    }

    /**
     * 高危属性<b>尚未维护</b>的药品清单——这张表就是药剂科的待办。
     * 把它做出来，是为了让「维护完成度」看得见；不做，null 就永远静静地等于「不提示」。
     */
    public Map<String, Object> unmaintainedDrugs(Integer limit) {
        int cap = capOf(limit);
        var rows = jdbc.queryForList("""
                select id, code, name, spec, unit from md_drug
                where high_alert is null and enabled is true
                order by code
                limit ?
                """, cap + 1);
        var body = paged(rows, cap);
        body.put("totalUnmaintained", jdbc.queryForObject(
                "select count(*) from md_drug where high_alert is null and enabled is true", Long.class));
        return body;
    }

    /**
     * 登记看似听似对照。无序对归一化为 (lo, hi) 只存一行——
     * 存两行迟早只删一半，变成「从 A 查得到、从 B 查不到」的静默漏提示。
     */
    @Transactional
    public Map<String, Object> addLasaPair(Long drugIdA, Long drugIdB, String note, Long uid) {
        if (uid == null) throw new BizException(5444, "无法识别当前登录用户，不能维护看似听似对照");
        if (drugIdA == null || drugIdB == null) throw new BizException(5450, "两个药品 id 都必须指定");
        if (drugIdA.equals(drugIdB)) throw new BizException(5450, "看似听似是两个药之间的关系，不能与自己成对");

        long lo = Math.min(drugIdA, drugIdB);
        long hi = Math.max(drugIdA, drugIdB);
        var drugs = jdbc.queryForList("select id, name from md_drug where id in (?, ?)", lo, hi);
        if (drugs.size() < 2) throw new BizException(5450, "药品不存在：" + drugIdA + " / " + drugIdB);

        // 先 select 再 insert，不是「先 try 再 catch 唯一冲突」：PostgreSQL 里一条语句撞了唯一约束，
        // **整个事务当场作废**（25P02），之后任何语句都只会回「current transaction is aborted」。
        // 单请求单事务时用户看到的还是 5450，但只要调用方在同一事务里还想干别的（本仓的测试就是），
        // 后面全炸。故常规路径走 select 判重，catch 只留作并发兜底。
        var dup = jdbc.queryForList(
                "select id from pharm_lasa_pair where drug_id_lo = ? and drug_id_hi = ?", lo, hi);
        if (!dup.isEmpty()) {
            throw new BizException(5450, "这一对已登记过（无序对只存一行，A-B 与 B-A 是同一条）");
        }

        try {
            var ins = jdbc.queryForList("""
                    insert into pharm_lasa_pair(drug_id_lo, drug_id_hi, note, created_by, created_at)
                    values (?, ?, ?, ?, now())
                    returning id, drug_id_lo, drug_id_hi, note, created_at
                    """, lo, hi, trimTo(note, NOTE_MAX), uid);
            return ins.get(0);
        } catch (DuplicateKeyException e) {
            // 并发兜底：两个人同时登记同一对。走到这里事务已被 PG 判死，调用方只能整请求重来
            throw new BizException(5450, "这一对已被并发登记（无序对只存一行），请刷新后确认");
        }
    }

    /** 撤销看似听似对照（维护字典的纠错，不是业务记录，可硬删） */
    @Transactional
    public Map<String, Object> removeLasaPair(Long pairId) {
        int n = jdbc.update("delete from pharm_lasa_pair where id = ?", pairId);
        if (n == 0) throw new BizException(5450, "看似听似对照不存在：" + pairId);
        var body = new LinkedHashMap<String, Object>();
        body.put("id", pairId);
        body.put("deleted", true);
        return body;
    }

    /** 看似听似对照查询：不带 drugId 列全表，带 drugId 只列与该药相关的对 */
    public Map<String, Object> lasaPairs(Long drugId, Integer limit) {
        int cap = capOf(limit);
        var rows = jdbc.queryForList("""
                select p.id, p.drug_id_lo, dl.name as drug_name_lo, p.drug_id_hi, dh.name as drug_name_hi,
                       p.note, p.created_at, u.real_name as created_by_name
                from pharm_lasa_pair p
                join md_drug dl on dl.id = p.drug_id_lo
                join md_drug dh on dh.id = p.drug_id_hi
                left join sys_user u on u.id = p.created_by
                where (?::bigint is null or p.drug_id_lo = ? or p.drug_id_hi = ?)
                order by p.id desc
                limit ?
                """, drugId, drugId, drugId, cap + 1);
        return paged(rows, cap);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 该挂号下已收费待发、且未挂在任何生效核对单上的药品处方 */
    private List<Map<String, Object>> pendingOrders(Long registrationId) {
        return jdbc.queryForList("""
                select o.id, o.item_id, o.item_name, o.spec, o.unit, o.qty,
                       o.usage_route, o.frequency, o.dose_per_time,
                       d.high_alert
                from outp_order o
                left join md_drug d on d.id = o.item_id
                where o.registration_id = ? and o.order_type = 'DRUG' and o.status = 'CHARGED'
                  and not exists (select 1 from pharm_dispense_check_line l
                                  where l.order_id = o.id and l.voided is false)
                order by o.id
                """, registrationId);
    }

    /**
     * 给处方行挂上高危与 LASA 提示。
     *
     * <p>LASA 提示<b>回带对方药名</b>：只说「本药易混淆」，药师看完还是不知道跟谁混，
     * 真正救命的是那句「注意别拿成 XXX」。同单同时出现两个互为 LASA 的药是最高危场景，
     * 单独标出来（{@code lasaSameSheet}）。
     */
    private List<Map<String, Object>> decorate(List<Map<String, Object>> orders) {
        var drugIds = orders.stream().map(o -> asLong(o.get("item_id"))).filter(java.util.Objects::nonNull)
                .distinct().toList();
        // drugId -> [{otherId, otherName, note}]
        var lasa = new LinkedHashMap<Long, List<Map<String, Object>>>();
        if (!drugIds.isEmpty()) {
            String ph = String.join(",", java.util.Collections.nCopies(drugIds.size(), "?"));
            var args = new ArrayList<Object>(drugIds);
            args.addAll(drugIds);
            var pairs = jdbc.queryForList("""
                    select p.drug_id_lo, p.drug_id_hi, p.note,
                           dl.name as name_lo, dh.name as name_hi
                    from pharm_lasa_pair p
                    join md_drug dl on dl.id = p.drug_id_lo
                    join md_drug dh on dh.id = p.drug_id_hi
                    where p.drug_id_lo in (%s) or p.drug_id_hi in (%s)
                    """.formatted(ph, ph), args.toArray());
            for (var p : pairs) {
                Long lo = asLong(p.get("drug_id_lo"));
                Long hi = asLong(p.get("drug_id_hi"));
                addLasa(lasa, drugIds, lo, hi, (String) p.get("name_hi"), (String) p.get("note"));
                addLasa(lasa, drugIds, hi, lo, (String) p.get("name_lo"), (String) p.get("note"));
            }
        }

        var out = new ArrayList<Map<String, Object>>();
        for (var o : orders) {
            Long drugId = asLong(o.get("item_id"));
            var m = new LinkedHashMap<String, Object>();
            m.put("orderId", o.get("id"));
            m.put("drugId", drugId);
            m.put("drugName", o.get("item_name"));
            m.put("spec", o.get("spec"));
            m.put("unit", o.get("unit"));
            m.put("qty", o.get("qty"));
            m.put("usageRoute", o.get("usage_route"));
            m.put("frequency", o.get("frequency"));
            m.put("dosePerTime", o.get("dose_per_time"));
            m.put("highAlert", asBool(o.get("high_alert")));   // null = 未维护，原样透出不折叠成 false
            var hits = lasa.getOrDefault(drugId, List.of());
            m.put("lasa", hits);
            boolean sameSheet = hits.stream().anyMatch(h -> drugIds.contains(asLong(h.get("otherId"))));
            m.put("lasaSameSheet", sameSheet);
            m.put("lasaNote", hits.isEmpty() ? null : lasaText(hits, drugIds));
            out.add(m);
        }
        return out;
    }

    private void addLasa(Map<Long, List<Map<String, Object>>> acc, List<Long> inSheet,
                         Long self, Long other, String otherName, String note) {
        if (!inSheet.contains(self)) return;
        var m = new LinkedHashMap<String, Object>();
        m.put("otherId", other);
        m.put("otherName", otherName);
        m.put("note", note);
        acc.computeIfAbsent(self, k -> new ArrayList<>()).add(m);
    }

    private String lasaText(List<Map<String, Object>> hits, List<Long> inSheet) {
        var parts = new ArrayList<String>();
        for (var h : hits) {
            boolean same = inSheet.contains(asLong(h.get("otherId")));
            parts.add(h.get("otherName") + (same ? "（本单同时出现，务必分清）" : "")
                    + (h.get("note") == null ? "" : "：" + h.get("note")));
        }
        return trimTo("看似听似，注意别拿成 " + String.join("；", parts), LASA_NOTE_MAX);
    }

    private long unmaintainedCount(List<Map<String, Object>> orders) {
        return orders.stream().filter(o -> o.get("high_alert") == null).count();
    }

    private Map<String, Object> head(Long checkId) {
        if (checkId == null) return null;
        var rows = jdbc.queryForList("""
                select c.id, c.registration_id, c.status,
                       c.picker_id, pu.real_name as picker_name, c.picked_at,
                       c.checker_id, cu.real_name as checker_name, c.checked_at,
                       c.returned_by, ru.real_name as returned_by_name, c.returned_at, c.return_reason
                from pharm_dispense_check c
                left join sys_user pu on pu.id = c.picker_id
                left join sys_user cu on cu.id = c.checker_id
                left join sys_user ru on ru.id = c.returned_by
                where c.id = ?
                """, checkId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 双人核对恒定校验（5443）：摆药人不得兼任核对人。
     * 摆药人未知（历史/直连改库造出的空值）同样返 5443——无从核验就不能盖章放行（同 v48 5263）。
     */
    private void requireNotPicker(Map<String, Object> head, Long uid, String action) {
        Long picker = asLong(head.get("picker_id"));
        if (picker == null) {
            throw new BizException(5443, "该核对单的摆药人未知，无法核验核对人与摆药人是否为同一人，不能" + action);
        }
        if (picker.equals(uid)) {
            throw new BizException(5443, "摆药人不能兼任核对人（一个人核两次不构成双人核对）；"
                    + "单人药房请不建核对单直接发药，由 gate 记录并提示，不要自摆自核");
        }
    }

    private static Map<String, Object> paged(List<Map<String, Object>> rows, int cap) {
        boolean truncated = rows.size() > cap;
        var body = new LinkedHashMap<String, Object>();
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        return body;
    }

    private static String statusText(Object status) {
        return switch (String.valueOf(status)) {
            case "CHECKED" -> "核对通过";
            case "RETURNED" -> "退回摆药";
            default -> "处于 " + status + " 状态";
        };
    }

    private static int capOf(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 仅用于本类自己生成的提示文案与留痕，绝不用于截断调用方送来的业务内容 */
    private static String trimTo(String s, int max) {
        String t = trim(s);
        return t == null || t.length() <= max ? t : t.substring(0, max);
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static Boolean asBool(Object o) {
        return o instanceof Boolean b ? b : null;
    }
}
