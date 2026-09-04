package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.medtech.web.SurgeryIntraopController;
import cn.hip.medtech.web.SurgeryIntraopController.EventReq;
import cn.hip.medtech.web.SurgeryIntraopController.TransfusionReq;
import cn.hip.medtech.web.SurgeryIntraopController.TubeReq;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v46 车道L 回归：术中记录闭环（管路 1413★ / 术中输血 1432★–1435★ / 术中事件 1437★1446★–1449★）。
 *
 * <p>立项依据：{@code grep 镇痛泵|自体血} <b>全仓零命中</b>，全仓没有任何术中记录表，
 * 而 1432★–1449★ 那批统计已答「平台已实现」。本类是那批统计**数据源**的兑现证据。
 *
 * <p><b>本类最要紧的三组断言，不是「能录一条记录」：</b>
 * <ol>
 *   <li><b>§③ 自体血必须能被单独统计。</b>1433★ 要「输注自体血的人数」、1434★ 要
 *       「输血患者数 / 自体血患者数 / 非自体血患者数」。用例按 {@code is_auto} 做
 *       {@code count(distinct surgery_id) filter} —— 这正是车道 M 要写的统计口径，
 *       并额外钉死「自体洗涤红细胞按 RBC + is_auto 录同样计入自体血」，
 *       防止统计退化成 {@code product_type = 'AUTO'} 的字符串比对而漏统计。</li>
 *   <li><b>§⑤ 事件类型白名单必须齐全。</b>11 个 event_type 逐个录一遍——
 *       白名单漏定义一个，对应的 1437★/1446★–1449★ 就是一条**永远算出 0** 的指标，
 *       页面上看不出来，只有这条用例能当场拍死。</li>
 *   <li><b>§⑥ 历史手术不能因为没有时间点而被拒绝录入。</b>4925 只在手术真有时间点时生效；
 *       V17 建表至今的存量手术全部没有入室/出室时间，一刀切校验等于让老手术一条都补录不进来。</li>
 * </ol>
 *
 * <p><b>4925 的上下界口径</b>（比「一律落在时间窗内」更细，见控制器 javadoc）：
 * 下界 {@code in_room_at} 对全部事件生效；上界 {@code out_room_at} 只对必然在室内发生的
 * {@code INTUBATE_OR}/{@code INVASIVE} 生效——拆镇痛泵常在术后 48 小时、苏醒室插管必在出室之后，
 * 一刀切按出室卡会把真实记录全拦掉。§⑥ 的三条用例把这个口径逐条钉死。
 *
 * <p>入室/出室时间点是车道 K（V140）加在 {@code inp_surgery} 上的列，本车道<b>一列都不改它</b>，
 * 只在时间窗用例里写值以构造场景。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "DOCTOR_OUTP", "NURSE"})
class V46IntraopTest {

    @Autowired SurgeryIntraopController controller;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private final Authentication auth = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    // ===== 造数 =====

    /** 造一台手术（连带患者与住院），返回 inp_surgery.id */
    private Long surgery() {
        Patient p = new Patient();
        p.setName("术中" + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        Long admId = inpatientService.admit(pid, 1L, bedId, null, "K35.8", "急性阑尾炎",
                new BigDecimal("5000"), "CASH", null).getId();
        entityManager.flush();
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, status)
                values (?, '阑尾切除术', 'SCHEDULED') returning id
                """, Long.class, admId);
    }

    /** 只写 V140 的时间点列（本车道<b>一列都不改 inp_surgery</b>，写值是为了构造时间窗用例） */
    private void setWindow(Long surgeryId, String inRoom, String outRoom) {
        jdbc.update("update inp_surgery set in_room_at = ?::timestamptz, out_room_at = ?::timestamptz where id = ?",
                inRoom, outRoom, surgeryId);
    }

    private long addTube(Long sid, String type, String inserted, String removed) {
        var r = controller.addTube(new TubeReq(sid, type, "右颈内静脉", new BigDecimal("13.5"),
                inserted, removed, "术中固定并标识"), auth);
        assertEquals(0, r.getCode(), r.getMessage());
        return ((Number) r.getData().get("id")).longValue();
    }

    private long addTransfusion(Long sid, String product, int ml, Boolean isAuto) {
        var r = controller.addTransfusion(new TransfusionReq(sid, product, ml, isAuto, null), auth);
        assertEquals(0, r.getCode(), r.getMessage());
        return ((Number) r.getData().get("id")).longValue();
    }

    private long addEvent(Long sid, String type, String time, Boolean planned) {
        var r = controller.addEvent(new EventReq(sid, type, time, "用例明细", planned, null), auth);
        assertEquals(0, r.getCode(), r.getMessage());
        return ((Number) r.getData().get("id")).longValue();
    }

    private long num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryBlock(Long sid, String key) {
        var r = controller.summary(sid);
        assertEquals(0, r.getCode(), r.getMessage());
        return (Map<String, Object>) r.getData().get(key);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> summaryList(Long sid, String key) {
        var r = controller.summary(sid);
        assertEquals(0, r.getCode(), r.getMessage());
        return (List<Map<String, Object>>) r.getData().get(key);
    }

    // ===== ① 管路（1413★）=====

    @Test
    void tubeCrud() {
        Long sid = surgery();
        long id = addTube(sid, "中心静脉导管", "2026-09-04 08:30:00", null);

        var rows = controller.tubes(sid).getData();
        assertEquals(1, rows.size());
        assertEquals("中心静脉导管", rows.get(0).get("tube_type"));
        assertEquals("右颈内静脉", rows.get(0).get("position"));
        assertEquals(0, new BigDecimal("13.5").compareTo((BigDecimal) rows.get(0).get("depth_cm")));
        assertNull(rows.get(0).get("removed_at"), "未填拔除时间即为未拔除");
        // 记录人落到 sys_user 外键上：麻醉医生录的还是护士录的，靠 operator_id 区分而不是分两张表
        assertNotNull(rows.get(0).get("operator_id"));

        // 汇总：未拔除计数是手术室交接的核心口径
        assertEquals(1, num(summaryBlock(sid, "tubeSummary"), "total"));
        assertEquals(1, num(summaryBlock(sid, "tubeSummary"), "unremoved"));

        // 改：补拔管时间
        assertEquals(0, controller.updateTube(id, new TubeReq(sid, "中心静脉导管", "右颈内静脉",
                new BigDecimal("13.0"), "2026-09-04 08:30:00", "2026-09-04 11:00:00", "术毕拔除")).getCode());
        assertEquals(0, num(summaryBlock(sid, "tubeSummary"), "unremoved"));

        // 拔除时间录错要能改回未拔除（故 removed_at 不能用 coalesce 保留旧值）
        assertEquals(0, controller.updateTube(id, new TubeReq(sid, "中心静脉导管", "右颈内静脉",
                new BigDecimal("13.0"), "2026-09-04 08:30:00", null, "撤销误录的拔管")).getCode());
        assertEquals(1, num(summaryBlock(sid, "tubeSummary"), "unremoved"));

        assertEquals(0, controller.deleteTube(id).getCode());
        assertEquals(0, controller.tubes(sid).getData().size());
    }

    /** 4920：管路类型非法（新增与修改两条路径同码） */
    @Test
    void illegalTubeTypeIs4920() {
        Long sid = surgery();
        assertEquals(4920, controller.addTube(
                new TubeReq(sid, "任意管子", null, null, null, null, null), auth).getCode());
        long id = addTube(sid, "尿管", null, null);
        assertEquals(4920, controller.updateTube(id,
                new TubeReq(sid, "PICC导管", null, null, null, null, null)).getCode());
    }

    /** 4921：拔除时间早于置管时间（新增与修改两条路径同码） */
    @Test
    void removalBeforeInsertionIs4921() {
        Long sid = surgery();
        assertEquals(4921, controller.addTube(new TubeReq(sid, "动脉置管", "左桡动脉", null,
                "2026-09-04 10:00:00", "2026-09-04 09:00:00", null), auth).getCode());
        long id = addTube(sid, "动脉置管", "2026-09-04 10:00:00", null);
        assertEquals(4921, controller.updateTube(id, new TubeReq(sid, "动脉置管", "左桡动脉", null,
                "2026-09-04 10:00:00", "2026-09-04 09:59:59", null)).getCode());
    }

    // ===== ② 术中输血（1432★–1435★）=====

    @Test
    void transfusionCrud() {
        Long sid = surgery();
        long id = addTransfusion(sid, "RBC", 400, false);

        var rows = controller.transfusions(sid).getData();
        assertEquals(1, rows.size());
        assertEquals("RBC", rows.get(0).get("product_type"));
        assertEquals("红细胞", rows.get(0).get("product_name"), "列表直出中文名，前端不再各写一份映射");
        assertEquals(400, ((Number) rows.get(0).get("volume_ml")).intValue());
        assertEquals(Boolean.FALSE, rows.get(0).get("is_auto"));

        assertEquals(0, controller.updateTransfusion(id,
                new TransfusionReq(sid, "PLASMA", 200, false, null)).getCode());
        assertEquals("PLASMA", controller.transfusions(sid).getData().get(0).get("product_type"));

        assertEquals(0, controller.deleteTransfusion(id).getCode());
        assertEquals(0, controller.transfusions(sid).getData().size());
    }

    /** 4922：血制品类型非法（新增与修改两条路径同码） */
    @Test
    void illegalProductTypeIs4922() {
        Long sid = surgery();
        assertEquals(4922, controller.addTransfusion(
                new TransfusionReq(sid, "ALBUMIN", 200, false, null), auth).getCode());
        long id = addTransfusion(sid, "PLT", 100, false);
        assertEquals(4922, controller.updateTransfusion(id,
                new TransfusionReq(sid, "血浆", 100, false, null)).getCode());
    }

    /** 4923：输血量须大于 0（零、负、缺省、以及离谱的上限外全部同码） */
    @Test
    void nonPositiveVolumeIs4923() {
        Long sid = surgery();
        assertEquals(4923, controller.addTransfusion(
                new TransfusionReq(sid, "RBC", 0, false, null), auth).getCode());
        assertEquals(4923, controller.addTransfusion(
                new TransfusionReq(sid, "RBC", -200, false, null), auth).getCode());
        assertEquals(4923, controller.addTransfusion(
                new TransfusionReq(sid, "RBC", null, false, null), auth).getCode());
        // 把 500 打成 500000 会悄悄污染 1432★ 的术中输血量，同码挡住
        assertEquals(4923, controller.addTransfusion(
                new TransfusionReq(sid, "RBC", 500000, false, null), auth).getCode());
        long id = addTransfusion(sid, "RBC", 400, false);
        assertEquals(4923, controller.updateTransfusion(id,
                new TransfusionReq(sid, "RBC", 0, false, null)).getCode());
    }

    /**
     * §③ 自体血标志是 1433★/1434★ 的统计依据。
     *
     * <p>三条断言：单台手术的自体/非自体输血量分开汇总；跨手术能按 {@code is_auto} 分别数人；
     * <b>自体洗涤红细胞按 RBC + is_auto 录同样计入自体血</b>——统计一律看 is_auto，
     * 不看 product_type 字符串，否则这种录法会被漏掉。
     */
    @Test
    void autoBloodIsCountedSeparately() {
        Long s1 = surgery();
        addTransfusion(s1, "AUTO", 300, null);   // 自体血：product 为 AUTO 时 is_auto 强制置真
        addTransfusion(s1, "RBC", 400, false);   // 异体红细胞

        var sum = summaryBlock(s1, "transfusionSummary");
        assertEquals(2, num(sum, "records"));
        assertEquals(700, num(sum, "total_ml"));
        assertEquals(300, num(sum, "auto_ml"), "自体血量单独汇总");
        assertEquals(400, num(sum, "non_auto_ml"), "非自体血量单独汇总");
        assertEquals(1, num(sum, "auto_records"));
        assertTrue((Boolean) controller.transfusions(s1).getData().stream()
                        .filter(r -> "AUTO".equals(r.get("product_type"))).findFirst().orElseThrow().get("is_auto"),
                "录 AUTO 时 is_auto 必须为真，否则按 is_auto 的统计会漏掉这条");

        Long s2 = surgery();
        addTransfusion(s2, "PLASMA", 200, false); // 只输异体
        Long s3 = surgery();
        // 自体洗涤红细胞：制品形态是红细胞，但它是自体血——统计必须认 is_auto 而不是 product_type
        addTransfusion(s3, "RBC", 250, true);

        // 这就是车道 M 的 1434★ 口径：输血患者数 / 自体血患者数 / 非自体血患者数
        var stat = jdbc.queryForMap("""
                select count(distinct surgery_id)                                as any_patients,
                       count(distinct surgery_id) filter (where is_auto)         as auto_patients,
                       count(distinct surgery_id) filter (where not is_auto)     as non_auto_patients
                from surg_transfusion where surgery_id in (?, ?, ?)
                """, s1, s2, s3);
        assertEquals(3, num(stat, "any_patients"));
        assertEquals(2, num(stat, "auto_patients"), "s1 的 AUTO 与 s3 的自体洗涤红细胞都要算进来");
        assertEquals(2, num(stat, "non_auto_patients"), "s1 的异体红细胞与 s2 的血浆");
    }

    // ===== ③ 术中事件（1437★/1446★–1449★）=====

    @Test
    void eventCrud() {
        Long sid = surgery();
        long id = addEvent(sid, "PAIN_PUMP_ON", "2026-09-04 10:30:00", null);

        var rows = controller.events(sid, null).getData();
        assertEquals(1, rows.size());
        assertEquals("PAIN_PUMP_ON", rows.get(0).get("event_type"));
        assertEquals("镇痛泵新增", rows.get(0).get("event_name"));
        assertNull(rows.get(0).get("planned"), "无计划性可言的事件留 null，不逼录假值");

        // 拆泵：1437★ 要的是「新增」与「拆除」两个数，同表按 event_type 区分
        addEvent(sid, "PAIN_PUMP_OFF", "2026-09-06 09:00:00", null);
        assertEquals(1, controller.events(sid, "PAIN_PUMP_ON").getData().size());
        assertEquals(1, controller.events(sid, "PAIN_PUMP_OFF").getData().size());
        assertEquals(2, controller.events(sid, null).getData().size());

        assertEquals(0, controller.updateEvent(id,
                new EventReq(sid, "RESCUE", "2026-09-04 10:40:00", "室颤除颤一次", null, "已复律")).getCode());
        assertEquals(1, controller.events(sid, "RESCUE").getData().size());
        assertEquals(0, controller.events(sid, "PAIN_PUMP_ON").getData().size());

        assertEquals(0, controller.deleteEvent(id).getCode());
        assertEquals(1, controller.events(sid, null).getData().size());
    }

    /** 4924：事件类型非法（新增、修改、按类型筛选三条路径同码） */
    @Test
    void illegalEventTypeIs4924() {
        Long sid = surgery();
        assertEquals(4924, controller.addEvent(
                new EventReq(sid, "PAIN_PUMP", null, null, null, null), auth).getCode());
        long id = addEvent(sid, "INVASIVE", null, null);
        assertEquals(4924, controller.updateEvent(id,
                new EventReq(sid, "TO_ICU_UNPLANNED", null, null, null, null)).getCode());
        assertEquals(4924, controller.events(sid, "转入ICU").getCode());
    }

    /**
     * §④ 计划 / 非计划必须能分别计数——1446★（转入 ICU）与 1447★（拔管与再次插管）的明文要求。
     * 用可空布尔三态：不适用的事件留 null，统计显式按 {@code planned is true/false} 分组。
     */
    @Test
    void plannedAndUnplannedAreCountedSeparately() {
        Long sid = surgery();
        addEvent(sid, "TO_ICU", "2026-09-04 12:00:00", true);
        addEvent(sid, "TO_ICU", "2026-09-04 12:10:00", false);
        addEvent(sid, "TO_ICU", "2026-09-04 12:20:00", false);
        addEvent(sid, "RESCUE", "2026-09-04 12:30:00", null);

        var icu = summaryList(sid, "eventSummary").stream()
                .filter(r -> "TO_ICU".equals(r.get("event_type"))).findFirst().orElseThrow();
        assertEquals("转入 ICU", icu.get("event_name"));
        assertEquals(3, num(icu, "total"));
        assertEquals(1, num(icu, "planned_count"), "计划转入 ICU");
        assertEquals(2, num(icu, "unplanned_count"), "非计划转入 ICU —— 1446★ 单独考核的就是这个数");
        assertEquals(0, num(icu, "unspecified_count"));

        var rescue = summaryList(sid, "eventSummary").stream()
                .filter(r -> "RESCUE".equals(r.get("event_type"))).findFirst().orElseThrow();
        assertEquals(1, num(rescue, "unspecified_count"), "抢救无计划性可言，留「未区分」不算进计划或非计划");
    }

    /**
     * §⑤ 事件类型白名单齐全——<b>漏定义一个就是一条永远算出 0 的指标</b>。
     * 逐个录一遍（同时验证 DB 的 chk_surg_event_type 与控制器白名单一致），
     * 并按字面钉死这 11 个码，防止后来者悄悄删掉其中一个。
     */
    @Test
    void everyWhitelistedEventTypeIsAccepted() {
        assertEquals(List.of(
                        "PAIN_PUMP_ON", "PAIN_PUMP_OFF",
                        "TO_ICU", "TO_PACU",
                        "INTUBATE_OR", "INTUBATE_PACU", "REINTUBATE", "EXTUBATE", "OUT_WITH_TUBE",
                        "INVASIVE", "RESCUE"),
                SurgeryIntraopController.EVENT_TYPES,
                "白名单变更须同步 V141 的 chk_surg_event_type 与车道 M 的统计分组键");

        Long sid = surgery();
        for (String type : SurgeryIntraopController.EVENT_TYPES) {
            addEvent(sid, type, null, null);   // 无时间点的手术：不受 4925 约束
        }
        assertEquals(SurgeryIntraopController.EVENT_TYPES.size(), controller.events(sid, null).getData().size());
        assertEquals(SurgeryIntraopController.EVENT_TYPES.size(),
                summaryList(sid, "eventSummary").size(), "每类各一条，汇总应有同样多的分组");
        // 白名单与三个必答指标的对应关系：镇痛泵 1437★ / 有创 1448★ / 抢救 1449★
        assertEquals(1, controller.events(sid, "INVASIVE").getData().size());
        assertEquals(1, controller.events(sid, "RESCUE").getData().size());
    }

    // ===== ④ 时间窗（4925）=====

    /** 4925：事件时间早于入室时间——全部事件类型都挡 */
    @Test
    void eventBeforeInRoomTimeIs4925() {
        Long sid = surgery();
        setWindow(sid, "2026-09-04 09:00:00", "2026-09-04 11:00:00");
        var r = controller.addEvent(
                new EventReq(sid, "TO_ICU", "2026-09-04 08:00:00", null, false, null), auth);
        assertEquals(4925, r.getCode(), r.getMessage());
        assertTrue(r.getMessage().contains("入室"));
        // 窗内合法
        assertEquals(0, controller.addEvent(
                new EventReq(sid, "TO_ICU", "2026-09-04 10:00:00", null, false, null), auth).getCode());
    }

    /**
     * 4925 上界只对必然在室内发生的事件生效：
     * 室内插管晚于出室 → 挡；转入 ICU 晚于出室 → 放行（转 ICU 本来就发生在出室之后）。
     * 一刀切按出室卡，1446★/1447★ 的记录会一条都录不进来。
     */
    @Test
    void onlyInRoomEventsAreBoundedByOutRoomTime() {
        Long sid = surgery();
        setWindow(sid, "2026-09-04 09:00:00", "2026-09-04 11:00:00");

        var inRoom = controller.addEvent(
                new EventReq(sid, "INTUBATE_OR", "2026-09-04 11:30:00", null, null, null), auth);
        assertEquals(4925, inRoom.getCode(), inRoom.getMessage());
        assertTrue(inRoom.getMessage().contains("出室"));

        assertEquals(0, controller.addEvent(
                        new EventReq(sid, "TO_ICU", "2026-09-04 11:05:00", null, false, null), auth).getCode(),
                "转入 ICU 必然在出室之后");
        assertEquals(0, controller.addEvent(
                        new EventReq(sid, "INTUBATE_PACU", "2026-09-04 11:20:00", null, false, null), auth).getCode(),
                "苏醒室插管必然在出室之后");
        assertEquals(0, controller.addEvent(
                        new EventReq(sid, "PAIN_PUMP_OFF", "2026-09-06 09:00:00", null, null, null), auth).getCode(),
                "拆镇痛泵常在术后 48 小时");
    }

    /**
     * §⑥ 手术没有时间点时不校验——V17 建表至今的存量手术全部没有入室/出室时间，
     * 因此拒绝录入等于让老手术一条都补录不进来。
     */
    @Test
    void legacySurgeryWithoutTimePointsIsNotBlocked() {
        Long sid = surgery();
        // V140 的时间点列全部 nullable 且不回填：新建/存量手术都是这个形态
        assertNull(jdbc.queryForMap("select in_room_at from inp_surgery where id = ?", sid).get("in_room_at"));

        assertEquals(0, controller.addEvent(
                new EventReq(sid, "INTUBATE_OR", "2001-01-01 00:00:00", null, null, null), auth).getCode());
        assertEquals(0, controller.addEvent(
                new EventReq(sid, "TO_ICU", "2099-12-31 23:59:00", null, true, null), auth).getCode());
        assertEquals(2, controller.events(sid, null).getData().size());
    }

    /** 只填了入室时间的在术手术：下界照样生效，上界因缺 out_room_at 不校验 */
    @Test
    void openEndedWindowStillGuardsLowerBound() {
        Long sid = surgery();
        setWindow(sid, "2026-09-04 09:00:00", null);
        assertEquals(4925, controller.addEvent(
                new EventReq(sid, "INVASIVE", "2026-09-04 08:59:00", "右桡动脉穿刺置管", null, null), auth).getCode());
        assertEquals(0, controller.addEvent(
                new EventReq(sid, "INVASIVE", "2026-09-04 09:30:00", "右桡动脉穿刺置管", null, null), auth).getCode());
    }

    // ===== ⑤ 不存在与格式 =====

    /** 4926：手术记录不存在（三类新增与聚合四条路径同码） */
    @Test
    void unknownSurgeryIs4926() {
        assertEquals(4926, controller.summary(-1L).getCode());
        assertEquals(4926, controller.addTube(
                new TubeReq(-1L, "尿管", null, null, null, null, null), auth).getCode());
        assertEquals(4926, controller.addTransfusion(
                new TransfusionReq(-1L, "RBC", 200, false, null), auth).getCode());
        assertEquals(4926, controller.addEvent(
                new EventReq(-1L, "RESCUE", null, null, null, null), auth).getCode());
    }

    /** 4927：术中记录不存在（三类的改与删六条路径同码） */
    @Test
    void unknownRecordIs4927() {
        assertEquals(4927, controller.updateTube(-1L,
                new TubeReq(1L, "尿管", null, null, null, null, null)).getCode());
        assertEquals(4927, controller.deleteTube(-1L).getCode());
        assertEquals(4927, controller.updateTransfusion(-1L,
                new TransfusionReq(1L, "RBC", 200, false, null)).getCode());
        assertEquals(4927, controller.deleteTransfusion(-1L).getCode());
        assertEquals(4927, controller.updateEvent(-1L,
                new EventReq(1L, "RESCUE", null, null, null, null)).getCode());
        assertEquals(4927, controller.deleteEvent(-1L).getCode());
    }

    /** 4928：时间解析不出就报错，不静默吃成 now()——否则倒填的置管时间会悄悄变成此刻 */
    @Test
    void unparsableTimeIs4928() {
        Long sid = surgery();
        assertEquals(4928, controller.addTube(
                new TubeReq(sid, "胃管", null, null, "昨天下午", null, null), auth).getCode());
        assertEquals(4928, controller.addEvent(
                new EventReq(sid, "RESCUE", "2026-13-45", null, null, null), auth).getCode());
        assertEquals(4928, controller.addTransfusion(
                new TransfusionReq(sid, "RBC", 200, false, "不是时间"), auth).getCode());
    }

    // ===== ⑥ 字典 =====

    /** 字典端点是前端下拉的唯一取值来源：白名单在前后端各写一份必然走样 */
    @Test
    void dictExposesWhitelists() {
        var d = controller.dict();
        assertEquals(0, d.getCode());
        assertEquals(SurgeryIntraopController.TUBE_TYPES, d.getData().get("tubeTypes"));
        assertEquals(SurgeryIntraopController.PRODUCT_TYPES.size(),
                ((List<?>) d.getData().get("productTypes")).size());
        assertEquals(SurgeryIntraopController.EVENT_TYPES.size(),
                ((List<?>) d.getData().get("eventTypes")).size());
        assertFalse(SurgeryIntraopController.TUBE_TYPES.isEmpty());
        assertTrue(SurgeryIntraopController.PRODUCT_TYPES.contains("AUTO"), "自体血是 1433★ 的统计对象");
    }
}
