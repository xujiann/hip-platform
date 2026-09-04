package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v46 车道L：术中记录闭环（管路 / 术中输血 / 术中事件）。
 *
 * <p><b>本控制器是 1432★–1449★ 那批统计的数据源</b>：此前全仓没有任何术中记录表
 * （「镇痛泵」「自体血」关键词零命中），术中输血量、自体血人数、镇痛泵新增拆除、
 * 转入 ICU、插管拔管、有创操作、抢救全部无从算起。车道 M 的质控指标只读这三张表。
 *
 * <p>四条口径：
 * <ul>
 *   <li><b>术中事件同表按 event_type 区分</b>（复用 v42 nur_record 的手法）——镇痛泵、转 ICU、
 *       插管拔管、有创操作、抢救五类字段完全同构，分表会让 1446–1449 的统计要 join 五张表。</li>
 *   <li><b>自体血以 is_auto 为唯一统计依据</b>：product_type 只描述制品形态，
 *       录 AUTO 时强制置真（DB 侧另有 chk_surg_transfusion_auto 兜底），
 *       自体洗涤红细胞按 RBC + is_auto 录同样能被 1433★/1434★ 统计到。</li>
 *   <li><b>时间窗校验分上下界</b>（4925）：下界 in_room_at 对全部事件生效（入室前不可能有术中事件）；
 *       上界 out_room_at <b>只对必然在室内发生的事件</b>生效（{@link #IN_ROOM_ONLY}）——
 *       拆镇痛泵常在术后 48 小时、苏醒室插管必在出室之后，一刀切按出室卡会把真实记录全拦掉。
 *       <b>时间点为空的那一侧不校验</b>：历史手术没有时间点，不能因此拒绝录入。</li>
 *   <li><b>零核心写路径改动</b>：三张全是新表，{@code inp_surgery} 一列未动
 *       （时间点由车道 K 的 V140 补），既有手术申请与完成端点契约逐字不动。</li>
 * </ul>
 *
 * <p>错误码 4920–4928（v46 术中记录段 4920–4939）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/surgery/intraop")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE','TECHNICIAN')")
public class SurgeryIntraopController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    /**
     * 管路类型白名单（院内常用值，<b>不是国标字典</b>）。
     * 收口在这里而不是 DB CHECK：各院管路命名差异大（深静脉 / CVC / 中心静脉导管 同物异名），
     * 实施期增补只改这一处，不必再发一版迁移。
     */
    public static final List<String> TUBE_TYPES = List.of(
            "静脉通道", "中心静脉导管", "动脉置管", "尿管", "胃管", "引流管", "气管导管", "硬膜外导管");

    /** 血制品类型（与 chk_surg_transfusion_product 一致） */
    public static final List<String> PRODUCT_TYPES = List.of("RBC", "PLASMA", "PLT", "CRYO", "WHOLE", "AUTO");

    private static final Map<String, String> PRODUCT_NAMES = Map.of(
            "RBC", "红细胞", "PLASMA", "血浆", "PLT", "血小板",
            "CRYO", "冷沉淀", "WHOLE", "全血", "AUTO", "自体血");

    /**
     * 术中事件类型白名单（与 chk_surg_event_type 一致，<b>车道 M 统计的分组键</b>）。
     * 顺序即前端下拉顺序：镇痛泵 → 转运 → 气道 → 有创 → 抢救。
     */
    public static final List<String> EVENT_TYPES = List.of(
            "PAIN_PUMP_ON", "PAIN_PUMP_OFF",
            "TO_ICU", "TO_PACU",
            "INTUBATE_OR", "INTUBATE_PACU", "REINTUBATE", "EXTUBATE", "OUT_WITH_TUBE",
            "INVASIVE", "RESCUE");

    private static final Map<String, String> EVENT_NAMES = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("PAIN_PUMP_ON", "镇痛泵新增"),
            Map.entry("PAIN_PUMP_OFF", "镇痛泵拆除"),
            Map.entry("TO_ICU", "转入 ICU"),
            Map.entry("TO_PACU", "转入苏醒室"),
            Map.entry("INTUBATE_OR", "手术室内插管"),
            Map.entry("INTUBATE_PACU", "苏醒室插管"),
            Map.entry("REINTUBATE", "再次插管"),
            Map.entry("EXTUBATE", "拔管"),
            Map.entry("OUT_WITH_TUBE", "带管出室"),
            Map.entry("INVASIVE", "有创操作"),
            Map.entry("RESCUE", "抢救")));

    /** 明文要求区分「计划/非计划」的事件（1446★ 转入 ICU、1447★ 拔管与再次插管） */
    private static final Set<String> PLANNED_RELEVANT = Set.of(
            "TO_ICU", "TO_PACU", "EXTUBATE", "REINTUBATE", "OUT_WITH_TUBE");

    /**
     * 必然发生在手术室内的事件——<b>只有这两类校验出室上界</b>。
     * 其余（转 ICU/苏醒室、苏醒室插管、再次插管、拔管、带管出室、镇痛泵、抢救）
     * 按临床实际可发生在出室之后，只校验入室下界。
     */
    private static final Set<String> IN_ROOM_ONLY = Set.of("INTUBATE_OR", "INVASIVE");

    /** 单条输血量上限：防止把 500 打成 500000 悄悄污染 1432★ 的术中输血量统计 */
    private static final int MAX_VOLUME_ML = 20000;

    // ==================================================================
    // 字典（前端下拉的唯一取值来源，避免白名单在前后端各写一份走样）
    // ==================================================================

    @GetMapping("/dict")
    public R<Map<String, Object>> dict() {
        var products = PRODUCT_TYPES.stream()
                .map(c -> Map.of("value", c, "label", PRODUCT_NAMES.getOrDefault(c, c),
                        "auto", "AUTO".equals(c)))
                .toList();
        var events = EVENT_TYPES.stream()
                .map(c -> Map.of("value", c, "label", EVENT_NAMES.getOrDefault(c, c),
                        "plannedRelevant", PLANNED_RELEVANT.contains(c),
                        "inRoomOnly", IN_ROOM_ONLY.contains(c)))
                .toList();
        return R.ok(Map.of("tubeTypes", TUBE_TYPES, "productTypes", products, "eventTypes", events));
    }

    // ==================================================================
    // 按手术聚合
    // ==================================================================

    /** 一次手术的术中记录全貌：抬头 + 三块明细 + 三块汇总（前端一页一请求） */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary(@RequestParam Long surgeryId) {
        var head = surgeryHead(surgeryId);
        if (head == null) return R.fail(4926, "手术记录不存在");

        var m = new LinkedHashMap<String, Object>();
        m.put("surgery", head);
        m.put("tubes", tubeRows(surgeryId));
        m.put("transfusions", transfusionRows(surgeryId));
        m.put("events", eventRows(surgeryId));
        m.put("tubeSummary", jdbc.queryForMap("""
                select count(*) as total, count(*) filter (where removed_at is null) as unremoved
                from surg_tube where surgery_id = ?
                """, surgeryId));
        // 自体血/非自体血分开汇总：1434★ 要的就是这三个数（输血、自体血、非自体血）
        m.put("transfusionSummary", jdbc.queryForMap("""
                select count(*)                                               as records,
                       coalesce(sum(volume_ml), 0)                            as total_ml,
                       coalesce(sum(volume_ml) filter (where is_auto), 0)      as auto_ml,
                       coalesce(sum(volume_ml) filter (where not is_auto), 0)  as non_auto_ml,
                       count(*) filter (where is_auto)                        as auto_records
                from surg_transfusion where surgery_id = ?
                """, surgeryId));
        var eventSummary = jdbc.queryForList("""
                select event_type,
                       count(*)                                as total,
                       count(*) filter (where planned is true)  as planned_count,
                       count(*) filter (where planned is false) as unplanned_count,
                       count(*) filter (where planned is null)  as unspecified_count
                from surg_event where surgery_id = ? group by event_type order by event_type
                """, surgeryId);
        for (var row : eventSummary) {
            row.put("event_name", EVENT_NAMES.getOrDefault(String.valueOf(row.get("event_type")),
                    String.valueOf(row.get("event_type"))));
        }
        m.put("eventSummary", eventSummary);
        return R.ok(m);
    }

    // ==================================================================
    // 管路（1413★）
    // ==================================================================

    public record TubeReq(Long surgeryId, String tubeType, String position, BigDecimal depthCm,
                          String insertedAt, String removedAt, String remark) {}

    @GetMapping("/tubes")
    public R<List<Map<String, Object>>> tubes(@RequestParam Long surgeryId) {
        return R.ok(tubeRows(surgeryId));
    }

    @PostMapping("/tubes")
    public R<Map<String, Object>> addTube(@RequestBody TubeReq req, Authentication auth) {
        if (surgeryHead(req.surgeryId()) == null) return R.fail(4926, "手术记录不存在");
        if (req.tubeType() == null || !TUBE_TYPES.contains(req.tubeType())) {
            return R.fail(4920, "管路类型非法（" + String.join("/", TUBE_TYPES) + "）");
        }
        Timestamp inserted = notBlank(req.insertedAt())
                ? parseTime(req.insertedAt()) : Timestamp.from(Instant.now());
        if (inserted == null) return R.fail(4928, "日期时间格式非法：" + req.insertedAt());
        Timestamp removed = parseTime(req.removedAt());
        if (notBlank(req.removedAt()) && removed == null) {
            return R.fail(4928, "日期时间格式非法：" + req.removedAt());
        }
        if (removed != null && removed.before(inserted)) return R.fail(4921, "拔除时间不能早于置管时间");
        Long id = jdbc.queryForObject("""
                insert into surg_tube(surgery_id, tube_type, position, depth_cm, inserted_at,
                                      removed_at, operator_id, remark)
                values (?,?,?,?,?,?,?,?) returning id
                """, Long.class, req.surgeryId(), req.tubeType(), trimToNull(req.position()),
                req.depthCm(), inserted, removed, userId(auth), trimToNull(req.remark()));
        return R.ok(Map.of("id", id));
    }

    @PutMapping("/tubes/{id}")
    public R<Void> updateTube(@PathVariable Long id, @RequestBody TubeReq req) {
        var rows = jdbc.queryForList("select inserted_at from surg_tube where id = ?", id);
        if (rows.isEmpty()) return R.fail(4927, "管路记录不存在");
        if (req.tubeType() == null || !TUBE_TYPES.contains(req.tubeType())) {
            return R.fail(4920, "管路类型非法（" + String.join("/", TUBE_TYPES) + "）");
        }
        Timestamp inserted = notBlank(req.insertedAt())
                ? parseTime(req.insertedAt()) : (Timestamp) rows.get(0).get("inserted_at");
        if (inserted == null) return R.fail(4928, "日期时间格式非法：" + req.insertedAt());
        Timestamp removed = parseTime(req.removedAt());
        if (notBlank(req.removedAt()) && removed == null) {
            return R.fail(4928, "日期时间格式非法：" + req.removedAt());
        }
        if (removed != null && removed.before(inserted)) return R.fail(4921, "拔除时间不能早于置管时间");
        // removed_at 显式置空即撤销拔管记录（录错拔管时间必须能改回未拔除），故不用 coalesce
        jdbc.update("""
                update surg_tube set tube_type = ?, position = ?, depth_cm = ?, inserted_at = ?,
                       removed_at = ?, remark = ? where id = ?
                """, req.tubeType(), trimToNull(req.position()), req.depthCm(), inserted, removed,
                trimToNull(req.remark()), id);
        return R.ok();
    }

    @DeleteMapping("/tubes/{id}")
    public R<Void> deleteTube(@PathVariable Long id) {
        return jdbc.update("delete from surg_tube where id = ?", id) == 0
                ? R.fail(4927, "管路记录不存在") : R.ok();
    }

    // ==================================================================
    // 术中输血（1432★–1435★）
    // ==================================================================

    public record TransfusionReq(Long surgeryId, String productType, Integer volumeMl,
                                 Boolean isAuto, String transfusedAt) {}

    @GetMapping("/transfusions")
    public R<List<Map<String, Object>>> transfusions(@RequestParam Long surgeryId) {
        return R.ok(transfusionRows(surgeryId));
    }

    @PostMapping("/transfusions")
    public R<Map<String, Object>> addTransfusion(@RequestBody TransfusionReq req, Authentication auth) {
        if (surgeryHead(req.surgeryId()) == null) return R.fail(4926, "手术记录不存在");
        if (req.productType() == null || !PRODUCT_TYPES.contains(req.productType())) {
            return R.fail(4922, "血制品类型非法（RBC 红细胞/PLASMA 血浆/PLT 血小板/CRYO 冷沉淀/WHOLE 全血/AUTO 自体血）");
        }
        if (req.volumeMl() == null || req.volumeMl() <= 0 || req.volumeMl() > MAX_VOLUME_ML) {
            return R.fail(4923, "输血量须大于 0（单条不超过 " + MAX_VOLUME_ML + "ml）");
        }
        Timestamp when = notBlank(req.transfusedAt())
                ? parseTime(req.transfusedAt()) : Timestamp.from(Instant.now());
        if (when == null) return R.fail(4928, "日期时间格式非法：" + req.transfusedAt());
        Long id = jdbc.queryForObject("""
                insert into surg_transfusion(surgery_id, product_type, volume_ml, is_auto,
                                             transfused_at, operator_id)
                values (?,?,?,?,?,?) returning id
                """, Long.class, req.surgeryId(), req.productType(), req.volumeMl(),
                autoFlag(req.productType(), req.isAuto()), when, userId(auth));
        return R.ok(Map.of("id", id));
    }

    @PutMapping("/transfusions/{id}")
    public R<Void> updateTransfusion(@PathVariable Long id, @RequestBody TransfusionReq req) {
        var rows = jdbc.queryForList("select transfused_at from surg_transfusion where id = ?", id);
        if (rows.isEmpty()) return R.fail(4927, "输血记录不存在");
        if (req.productType() == null || !PRODUCT_TYPES.contains(req.productType())) {
            return R.fail(4922, "血制品类型非法（RBC 红细胞/PLASMA 血浆/PLT 血小板/CRYO 冷沉淀/WHOLE 全血/AUTO 自体血）");
        }
        if (req.volumeMl() == null || req.volumeMl() <= 0 || req.volumeMl() > MAX_VOLUME_ML) {
            return R.fail(4923, "输血量须大于 0（单条不超过 " + MAX_VOLUME_ML + "ml）");
        }
        Timestamp when = notBlank(req.transfusedAt())
                ? parseTime(req.transfusedAt()) : (Timestamp) rows.get(0).get("transfused_at");
        if (when == null) return R.fail(4928, "日期时间格式非法：" + req.transfusedAt());
        jdbc.update("""
                update surg_transfusion set product_type = ?, volume_ml = ?, is_auto = ?, transfused_at = ?
                where id = ?
                """, req.productType(), req.volumeMl(),
                autoFlag(req.productType(), req.isAuto()), when, id);
        return R.ok();
    }

    @DeleteMapping("/transfusions/{id}")
    public R<Void> deleteTransfusion(@PathVariable Long id) {
        return jdbc.update("delete from surg_transfusion where id = ?", id) == 0
                ? R.fail(4927, "输血记录不存在") : R.ok();
    }

    // ==================================================================
    // 术中事件（1437★/1446★/1447★/1448★/1449★）
    // ==================================================================

    public record EventReq(Long surgeryId, String eventType, String eventTime, String detail,
                           Boolean planned, String remark) {}

    @GetMapping("/events")
    public R<List<Map<String, Object>>> events(@RequestParam Long surgeryId,
                                               @RequestParam(required = false) String eventType) {
        if (notBlank(eventType) && !EVENT_TYPES.contains(eventType)) {
            return R.fail(4924, "术中事件类型非法：" + eventType);
        }
        return R.ok(eventRows(surgeryId, eventType));
    }

    @PostMapping("/events")
    public R<Map<String, Object>> addEvent(@RequestBody EventReq req, Authentication auth) {
        if (surgeryHead(req.surgeryId()) == null) return R.fail(4926, "手术记录不存在");
        if (req.eventType() == null || !EVENT_TYPES.contains(req.eventType())) {
            return R.fail(4924, "术中事件类型非法（" + String.join("/", EVENT_TYPES) + "）");
        }
        Timestamp when = notBlank(req.eventTime())
                ? parseTime(req.eventTime()) : Timestamp.from(Instant.now());
        if (when == null) return R.fail(4928, "日期时间格式非法：" + req.eventTime());
        String violation = windowViolation(req.surgeryId(), req.eventType(), when);
        if (violation != null) return R.fail(4925, violation);
        Long id = jdbc.queryForObject("""
                insert into surg_event(surgery_id, event_type, event_time, detail, planned, operator_id, remark)
                values (?,?,?,?,?,?,?) returning id
                """, Long.class, req.surgeryId(), req.eventType(), when, trimToNull(req.detail()),
                req.planned(), userId(auth), trimToNull(req.remark()));
        return R.ok(Map.of("id", id));
    }

    @PutMapping("/events/{id}")
    public R<Void> updateEvent(@PathVariable Long id, @RequestBody EventReq req) {
        var rows = jdbc.queryForList("select surgery_id, event_time from surg_event where id = ?", id);
        if (rows.isEmpty()) return R.fail(4927, "术中事件不存在");
        Long surgeryId = ((Number) rows.get(0).get("surgery_id")).longValue();
        if (req.eventType() == null || !EVENT_TYPES.contains(req.eventType())) {
            return R.fail(4924, "术中事件类型非法（" + String.join("/", EVENT_TYPES) + "）");
        }
        Timestamp when = notBlank(req.eventTime())
                ? parseTime(req.eventTime()) : (Timestamp) rows.get(0).get("event_time");
        if (when == null) return R.fail(4928, "日期时间格式非法：" + req.eventTime());
        String violation = windowViolation(surgeryId, req.eventType(), when);
        if (violation != null) return R.fail(4925, violation);
        jdbc.update("""
                update surg_event set event_type = ?, event_time = ?, detail = ?, planned = ?, remark = ?
                where id = ?
                """, req.eventType(), when, trimToNull(req.detail()), req.planned(),
                trimToNull(req.remark()), id);
        return R.ok();
    }

    @DeleteMapping("/events/{id}")
    public R<Void> deleteEvent(@PathVariable Long id) {
        return jdbc.update("delete from surg_event where id = ?", id) == 0
                ? R.fail(4927, "术中事件不存在") : R.ok();
    }

    // ==================================================================
    // 取数与校验
    // ==================================================================

    private List<Map<String, Object>> tubeRows(Long surgeryId) {
        return jdbc.queryForList("""
                select t.id, t.surgery_id, t.tube_type, t.position, t.depth_cm, t.inserted_at,
                       t.removed_at, t.operator_id, u.real_name as operator_name, t.remark, t.created_at
                from surg_tube t
                left join sys_user u on u.id = t.operator_id
                where t.surgery_id = ? order by t.inserted_at, t.id
                """, surgeryId);
    }

    private List<Map<String, Object>> transfusionRows(Long surgeryId) {
        var rows = jdbc.queryForList("""
                select f.id, f.surgery_id, f.product_type, f.volume_ml, f.is_auto, f.transfused_at,
                       f.operator_id, u.real_name as operator_name, f.created_at
                from surg_transfusion f
                left join sys_user u on u.id = f.operator_id
                where f.surgery_id = ? order by f.transfused_at, f.id
                """, surgeryId);
        for (var row : rows) {
            row.put("product_name", PRODUCT_NAMES.getOrDefault(String.valueOf(row.get("product_type")),
                    String.valueOf(row.get("product_type"))));
        }
        return rows;
    }

    private List<Map<String, Object>> eventRows(Long surgeryId) {
        return eventRows(surgeryId, null);
    }

    private List<Map<String, Object>> eventRows(Long surgeryId, String eventType) {
        var sql = new StringBuilder("""
                select e.id, e.surgery_id, e.event_type, e.event_time, e.detail, e.planned,
                       e.operator_id, u.real_name as operator_name, e.remark, e.created_at
                from surg_event e
                left join sys_user u on u.id = e.operator_id
                where e.surgery_id = ?
                """);
        Object[] args = notBlank(eventType) ? new Object[] {surgeryId, eventType} : new Object[] {surgeryId};
        if (notBlank(eventType)) sql.append(" and e.event_type = ? ");
        sql.append(" order by e.event_time, e.id");
        var rows = jdbc.queryForList(sql.toString(), args);
        for (var row : rows) {
            row.put("event_name", EVENT_NAMES.getOrDefault(String.valueOf(row.get("event_type")),
                    String.valueOf(row.get("event_type"))));
        }
        return rows;
    }

    /** 手术抬头；不存在返 null（调用方回 4926） */
    private Map<String, Object> surgeryHead(Long surgeryId) {
        if (surgeryId == null) return null;
        var rows = jdbc.queryForList("""
                select s.id, s.admission_id, s.procedure_name, s.anesthesia_type, s.scheduled_at,
                       s.status, a.admission_no, p.name as patient_name, p.sex
                from inp_surgery s
                join inp_admission a on a.id = s.admission_id
                join empi_patient p on p.id = a.patient_id
                where s.id = ?
                """, surgeryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 手术时间窗（车道 K 的 V140 给 inp_surgery 补的入室/出室时间点） */
    private record Window(Timestamp inRoom, Timestamp outRoom) {}

    /**
     * 取手术时间窗（V140 的 in_room_at / out_room_at，<b>全部 nullable</b>）。
     * 存量手术两列必然为空，此时返回空窗即等于不校验——见 {@link #windowViolation} 的口径。
     */
    private Window windowOf(Long surgeryId) {
        var rows = jdbc.queryForList("select in_room_at, out_room_at from inp_surgery where id = ?", surgeryId);
        if (rows.isEmpty()) return new Window(null, null);
        return new Window((Timestamp) rows.get(0).get("in_room_at"), (Timestamp) rows.get(0).get("out_room_at"));
    }

    /**
     * 事件时间是否落在手术时间窗内；越界返回给用户看的原因，合法返 null。
     * 上下界各自独立：只填了入室时间的在术手术照样能挡住「入室前的术中事件」。
     */
    private String windowViolation(Long surgeryId, String eventType, Timestamp when) {
        Window w = windowOf(surgeryId);
        if (w.inRoom() != null && when.before(w.inRoom())) {
            return "事件时间不在手术时间窗内：早于入室时间 " + w.inRoom();
        }
        if (IN_ROOM_ONLY.contains(eventType) && w.outRoom() != null && when.after(w.outRoom())) {
            return "事件时间不在手术时间窗内：" + EVENT_NAMES.get(eventType)
                    + " 属室内事件，不能晚于出室时间 " + w.outRoom();
        }
        return null;
    }

    /** AUTO 制品必是自体血；其余按录入值（自体洗涤红细胞可按 RBC + is_auto 录） */
    private static boolean autoFlag(String productType, Boolean isAuto) {
        return "AUTO".equals(productType) || Boolean.TRUE.equals(isAuto);
    }

    private Long userId(Authentication auth) {
        return auth == null ? null : currentUserService.idOf(auth);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 宽容解析前端时间：ISO 带时区 / ISO 本地 / "yyyy-MM-dd HH:mm[:ss]" / 纯日期。
     * 解析不出返 null，由调用方回 4928——不静默吃成 now()，否则倒填的置管时间会悄悄变成此刻。
     */
    static Timestamp parseTime(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        try {
            return Timestamp.from(OffsetDateTime.parse(s).toInstant());
        } catch (Exception ignored) {
            // 继续尝试本地时间格式
        }
        String iso = s.contains(" ") ? s.replace(' ', 'T') : s;
        try {
            return Timestamp.from(LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception ignored) {
            // 继续尝试纯日期
        }
        try {
            return Timestamp.from(LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception ignored) {
            return null;
        }
    }
}
