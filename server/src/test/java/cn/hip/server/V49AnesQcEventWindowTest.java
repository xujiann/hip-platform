package cn.hip.server;

import cn.hip.medtech.web.AnesQcController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v49 车道 Q2 回归：<b>事件类指标的时间归集口径（{@code by} 参数 / 错误码 4943）</b>。
 *
 * <p>修的是 v46 的一处「时间窗挂错锚点」：1437★/1446★/1447★/1448★/1449★/1450★ 六条<b>事件类</b>
 * 指标，汇总与明细都挂在手术锚点日期上，而条款原文写的是「统计<b>指定时间段内</b>……转入 ICU /
 * 苏醒室的数据」。拔管可能在 ICU 里几天之后、拆镇痛泵常在术后 48 小时——<b>术后延迟发生的事件
 * 会落进手术当天的桶</b>，管理者按事件发生的那个月去查就查不到。
 *
 * <p>本类的夹具就是为这条缺陷造的：一台手术锚点在 D 日，而拔管落在 D+3、拆泵落在 D+2。
 * 于是「查 D+2 到 D+4 这个窗」这一个动作，就能把两套口径的差别当场量出来：
 * <ul>
 *   <li><b>§① 缺陷本身</b>——{@code by=surgery}（v46 旧口径）在 [D+2, D+4] 窗里<b>一条也查不到</b>，
 *       {@code by=event}（本版默认）查得到拔管与拆泵。这就是「按月查会漏」的机械证据。
 *   <li><b>§② 反向</b>——{@code by=surgery} 在 [D, D] 窗里把 D+3 才发生的拔管算进手术当天，
 *       {@code by=event} 不算。两套口径都不是错的，错的是<b>只有一套且默认挂错</b>。
 *   <li><b>§③ 汇总与明细同一个 by</b>——六条指标逐条断言「汇总各行事件数之和 == 穿透明细条数」。
 *       两边不同窗就是 v46 在 1437★ 上已经发生过的事故：汇总按 event_time 归日、明细按手术锚点落窗，
 *       点开穿透当场对不上账。
 *   <li><b>§④ 4943 不静默回落</b>——非法取值必须报错。回落默认值等于把打错的参数当成另一套口径算，
 *       而调用方以为自己查的还是刚才指定的那套。
 *   <li><b>§⑤ 口径变更必须随体下发</b>——默认值变了同一区间的数就会变，返回体与 CSV 尾注里
 *       必须写清「改了什么 / 为什么改 / 旧口径怎么取」。
 *   <li><b>§⑥ 1450★ 不许假装参数生效</b>——qc_adverse_event 无 surgery_id，两种 by 返回同一批数，
 *       note 里必须直说，而不是让人以为切换 by 会得到不同的口径。
 * </ul>
 *
 * <p>时间窗刻意取 300 天前那一天：与 V46AnesQcTest 的 200 天前夹具错开，两套用例互不搅扰。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "QUALITY"})
class V49AnesQcEventWindowTest {

    @Autowired
    private AnesQcController anesQc;

    @Autowired
    private JdbcTemplate jdbc;

    /** D：手术锚点日 */
    private String opDay;
    /** D+2：拆镇痛泵（术后 48 小时，代码注释自己就是这么写的） */
    private String d2;
    /** D+3：ICU 内拔管 */
    private String d3;
    /** D+4：晚窗右端 */
    private String d4;

    private Long surgeryId;

    private static long seq = 0;

    private static String uniq(String prefix) {
        return prefix + (System.nanoTime() % 100000000L) + (seq++);
    }

    @BeforeEach
    void setUp() {
        opDay = date(300);
        d2 = date(298);
        d3 = date(297);
        d4 = date(296);

        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'M', current_date - make_interval(years => 62))
                returning id
                """, Long.class, uniq("V49P"), "口径归集" + uniq(""));
        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        Long bedId = jdbc.queryForObject("select id from inp_bed order by id limit 1", Long.class);
        assertNotNull(bedId, "测试库须有床位种子");
        Long admissionId = jdbc.queryForObject("""
                insert into inp_admission(admission_no, patient_id, dept_id, ward_id, bed_id, status, admit_at)
                values (?, ?, ?, ?, ?, 'IN_HOSPITAL', now())
                returning id
                """, Long.class, uniq("V49A"), patientId, deptId, deptId, bedId);

        // 一台锚点在 D 日的手术：四时间点全录，锚点必然落在 D
        surgeryId = jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status,
                                        scheduled_at, room_no, in_room_at, start_at, end_at, out_room_at,
                                        surgery_level, asa_grade, surgery_kind, op_icd)
                values (?, 'V49 择期开腹术', '全身麻醉', 'DONE',
                        ?::timestamptz, 'V49-OR-1', ?::timestamptz, ?::timestamptz,
                        ?::timestamptz, ?::timestamptz, '三级', 'III', 'ELECTIVE', '54.19')
                returning id
                """, Long.class, admissionId,
                at(opDay, "07:40"), at(opDay, "07:50"), at(opDay, "08:00"),
                at(opDay, "10:00"), at(opDay, "10:10"));

        // ---- 术中发生的事件：两种口径几乎无差 ----
        event("INTUBATE_OR", at(opDay, "08:05"), Boolean.TRUE);
        event("INVASIVE", at(opDay, "08:30"), null);
        event("RESCUE", at(opDay, "09:00"), null);
        event("TO_ICU", at(opDay, "10:20"), Boolean.FALSE);
        event("PAIN_PUMP_ON", at(opDay, "10:30"), null);

        // ---- 术后延迟发生的事件：缺陷就藏在这两条上 ----
        event("PAIN_PUMP_OFF", at(d2, "10:00"), null);   // 术后 48 小时拆泵
        event("EXTUBATE", at(d3, "09:00"), null);        // ICU 里躺了三天才拔管
    }

    private String date(int daysAgo) {
        return jdbc.queryForObject("select (current_date - " + daysAgo + ")::text", String.class);
    }

    /** 刻意避开 00:00 前后：时间字面量在时区上炸过四次，落在白天时段两侧都留足余量 */
    private static String at(String day, String hhmm) {
        return day + " " + hhmm;
    }

    private void event(String type, String timestamp, Boolean planned) {
        jdbc.update("""
                insert into surg_event(surgery_id, event_type, event_time, planned, detail)
                values (?, ?, ?::timestamptz, ?, 'V49 归集口径用例')
                """, surgeryId, type, timestamp, planned);
    }

    // ================= 取数小工具 =================

    @SuppressWarnings("unchecked")
    private Map<String, Object> indicator(String code, String from, String to, String by) {
        var r = anesQc.indicators(from, to, code, by);
        assertEquals(0, r.getCode(), r.getMessage());
        var list = (List<Map<String, Object>>) r.getData().get("indicators");
        assertEquals(1, list.size(), "按编码筛应只回一条");
        return list.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String code, String from, String to, String by) {
        return (List<Map<String, Object>>) indicator(code, from, to, by).get("rows");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String code, String from, String to, String by) {
        var r = anesQc.detail(code, from, to, null, by);
        assertEquals(0, r.getCode(), r.getMessage());
        return (List<Map<String, Object>>) r.getData().get("items");
    }

    /** 汇总口径下该窗内的事件总数（六条指标的 rows 都有 events 列，1437 例外，单独走 added/removed） */
    private long events(String code, String from, String to, String by) {
        return rows(code, from, to, by).stream()
                .mapToLong(m -> m.get("events") == null ? 0 : ((Number) m.get("events")).longValue())
                .sum();
    }

    /**
     * 明细条数。<b>刻意不按本用例的台次过滤</b>：汇总侧的 events 列是全库口径，
     * 明细侧若只数自己那台，这条「汇总 == 明细」的对账断言本身就不同源了。
     * 用例的时间窗取 300 天前，与其它夹具错开，全库计数即等于本用例造的数据。
     */
    private static long count(List<Map<String, Object>> rows) {
        return rows.size();
    }

    private long col(String code, String from, String to, String by, String column) {
        return rows(code, from, to, by).stream()
                .mapToLong(m -> m.get(column) == null ? 0 : ((Number) m.get(column)).longValue())
                .sum();
    }

    // ================= §① 缺陷本身：术后延迟发生的事件按月查会漏 =================

    /**
     * v46 旧口径（{@code by=surgery}）在「拔管与拆泵真正发生的那个窗」里一条也查不到——
     * 这正是偏离表条款「统计指定时间段内……的数据」要的东西被漏掉的机械证据。
     */
    @Test
    void postOpDelayedEventsAreInvisibleUnderSurgeryAnchor() {
        // 晚窗 [D+2, D+4] 内真实发生了：拆泵（D+2）、拔管（D+3）
        assertEquals(1, events("1447", d2, d4, "event"), "by=event 应查到 D+3 在 ICU 里的拔管");
        assertEquals(1, col("1437", d2, d4, "event", "removed"), "by=event 应查到 D+2 的拆泵");

        // 旧口径把它们算在了手术当天 D，于是这个窗里什么都没有
        assertEquals(0, events("1447", d2, d4, "surgery"), "by=surgery（v46 旧口径）在事件真实发生的窗里查不到拔管");
        assertEquals(0, col("1437", d2, d4, "surgery", "removed"), "by=surgery 在拆泵当天查不到拆泵");

        // 明细与汇总同口径，两边一起漏 / 一起查得到
        assertEquals(1, count(items("1447", d2, d4, "event")));
        assertEquals(0, count(items("1447", d2, d4, "surgery")));
    }

    /** 反向：旧口径把 D+3 才发生的拔管算进手术当天，新默认口径不算 */
    @Test
    void surgeryAnchorPullsLateEventsIntoTheOperationDay() {
        // [D, D] 窗：术中插管 1 条落在当天；拔管发生在 D+3
        assertEquals(1, events("1447", opDay, opDay, "event"), "by=event 当天只有插管");
        assertEquals(2, events("1447", opDay, opDay, "surgery"), "by=surgery 把 D+3 的拔管也算进手术当天");

        var eventTypes = rows("1447", opDay, opDay, "surgery").stream()
                .map(m -> String.valueOf(m.get("event_type"))).toList();
        assertTrue(eventTypes.contains("EXTUBATE"), "旧口径下拔管落在手术当天这一桶里");
        assertFalse(rows("1447", opDay, opDay, "event").stream()
                        .anyMatch(m -> "EXTUBATE".equals(m.get("event_type"))),
                "新默认口径下拔管落在它真正发生的 D+3，不在手术当天");
    }

    /** 1448★ 1449★ 是术中发生的事件，两种口径应当一致——一致本身也要被钉住 */
    @Test
    void intraOpEventsAreIdenticalUnderBothCalibers() {
        for (String code : new String[]{"1448", "1449"}) {
            assertEquals(1, events(code, opDay, opDay, "event"), code + "★ by=event");
            assertEquals(1, events(code, opDay, opDay, "surgery"), code + "★ by=surgery");
            assertEquals(count(items(code, opDay, opDay, "event")),
                    count(items(code, opDay, opDay, "surgery")),
                    code + "★ 术中事件两种口径的明细应当一致");
        }
        // 1446★ 转入 ICU 也发生在手术当天，两种口径同值
        assertEquals(1, events("1446", opDay, opDay, "event"));
        assertEquals(1, events("1446", opDay, opDay, "surgery"));
    }

    // ================= §② 默认值就是 event，且大小写不敏感 =================

    @Test
    void defaultCaliberIsEventAndParsingIsCaseInsensitive() {
        assertEquals(events("1447", d2, d4, "event"), events("1447", d2, d4, null),
                "不传 by = by=event（本版默认口径）");
        assertEquals(events("1447", d2, d4, "event"), events("1447", d2, d4, "  EVENT "),
                "取值大小写与首尾空白不敏感");
        assertEquals("event", indicator("1447", d2, d4, null).get("windowBy"));
        assertEquals("surgery", indicator("1447", d2, d4, "Surgery").get("windowBy"));

        // 兼容重载：v46 的三参 / 四参调用等价于新默认口径，不是旧口径
        assertEquals(0, anesQc.indicators(d2, d4, "1447").getCode());
        assertEquals(0, anesQc.detail("1447", d2, d4, null).getCode());
    }

    // ================= §③ 汇总与明细必须用同一个 by =================

    /**
     * 逐条断言「汇总各行事件数之和 == 穿透明细条数」。
     * v46 在 1437★ 上就栽过：汇总按 event_time 归日、明细按手术锚点落窗，点开穿透当场对不上账。
     */
    @Test
    void summaryAndDetailShareTheSameCaliber() {
        for (String by : new String[]{"event", "surgery"}) {
            for (String window : new String[]{opDay + "|" + opDay, d2 + "|" + d4}) {
                String from = window.split("\\|")[0];
                String to = window.split("\\|")[1];
                for (String code : new String[]{"1446", "1447", "1448", "1449"}) {
                    assertEquals(events(code, from, to, by), count(items(code, from, to, by)),
                            code + "★ by=" + by + " 窗[" + from + "," + to + "]：汇总与明细须对得上账");
                }
                // 1437★ 的汇总是「日期 × 度量」，明细是 ON/OFF 事件流水，两者比 added + removed
                assertEquals(col("1437", from, to, by, "added") + col("1437", from, to, by, "removed"),
                        count(items("1437", from, to, by)),
                        "1437★ by=" + by + " 窗[" + from + "," + to + "]：新增 + 拆泵须等于明细条数");
            }
        }
    }

    /** 汇总返回体给出的穿透入口，必须把同一个 by 带上——否则点开就换了口径 */
    @Test
    void detailEndpointHintCarriesTheSameBy() {
        assertTrue(String.valueOf(indicator("1447", d2, d4, "surgery").get("detailEndpoint"))
                        .contains("by=surgery"),
                "事件类指标的 detailEndpoint 须带 by");
        assertFalse(String.valueOf(indicator("1428", d2, d4, "surgery").get("detailEndpoint"))
                        .contains("by="),
                "非事件类指标不受 by 影响，穿透入口不该带一个不起作用的参数");
        assertFalse(indicator("1428", d2, d4, "surgery").containsKey("windowBy"),
                "非事件类指标不标 windowBy，免得让人以为它按事件时间统计");
    }

    // ================= §④ 4943：非法取值报错，不静默回落 =================

    @Test
    void illegalCaliberReturns4943() {
        assertEquals(4943, anesQc.indicators(opDay, opDay, "1447", "surgey").getCode(), "拼错的取值");
        assertEquals(4943, anesQc.indicators(opDay, opDay, null, "both").getCode(), "不支持的取值");
        assertEquals(4943, anesQc.detail("1447", opDay, opDay, null, "op_day").getCode(), "穿透同样校验");
        assertTrue(anesQc.indicatorsCsv("1447", opDay, opDay, "surgey").contains("4943"),
                "CSV 无 code 字段承载错误，须把错误码写进正文，不导一张看不出真假的表");
        assertTrue(anesQc.detailCsv("1447", opDay, opDay, "surgey").contains("4943"));

        String msg = anesQc.indicators(opDay, opDay, "1447", "surgey").getMessage();
        assertTrue(msg.contains("event") && msg.contains("surgery"),
                "报错须把两个合法取值直接给出来，不让调用方去猜：" + msg);

        // 校验先于取数：非法 by 时绝不返回一份「按默认口径算好的」数据
        assertNull(anesQc.indicators(opDay, opDay, "1447", "surgey").getData(),
                "非法参数不许带回一份数据——回落默认值等于把打错的参数算成另一套口径");
    }

    private static void assertNull(Object v, String msg) {
        assertTrue(v == null, msg);
    }

    // ================= §⑤ 口径变更必须随体下发 =================

    @Test
    void caliberChangeIsDisclosedInEveryChannel() {
        var body = anesQc.indicators(opDay, opDay, "1447", null).getData();
        assertEquals("event", body.get("by"));
        String change = (String) body.get("eventWindowChangeNote");
        assertNotNull(change, "口径变更说明必须随汇总返回体下发");
        assertTrue(change.contains("口径变更"), "须点明这是口径变更而不是新功能");
        assertTrue(change.contains("by=surgery"), "须写明旧口径怎么取");
        assertTrue(change.contains("48"), "须写明为什么改（拆泵常在术后 48 小时）");
        assertNotNull(body.get("eventWindowNote"), "by 的取值含义须随体下发");
        // 既有的三条口径说明一条不许少
        assertNotNull(body.get("anchorNote"));
        assertNotNull(body.get("standardNote"));
        assertNotNull(body.get("timepointCaveat"));

        var detailBody = anesQc.detail("1447", opDay, opDay, null, "surgery").getData();
        assertEquals("surgery", detailBody.get("by"));
        assertNotNull(detailBody.get("eventWindowChangeNote"), "穿透返回体同样要给口径变更说明");
        assertTrue(String.valueOf(detailBody.get("note")).contains("by=surgery"),
                "指标 note 须写明本次生效的是哪一套口径");

        // CSV 尾注三处同源
        String csv = anesQc.indicatorsCsv("1447", opDay, opDay, "surgery");
        assertTrue(csv.contains("本次导出 by=surgery"), "CSV 须写明本次导出用的是哪套口径");
        assertTrue(csv.contains("口径变更"), "CSV 尾注同样要带口径变更说明");
        assertTrue(anesQc.detailCsv("1447", opDay, opDay, "event").contains("本次导出 by=event"));

        // 非事件类指标不该被塞进一段与它无关的 by 尾注
        assertFalse(anesQc.indicatorsCsv("1428", opDay, opDay, null).contains("本次导出 by="),
                "1428★ 不是事件类指标，不该出现 by 尾注");
    }

    // ================= §⑥ 1450★：无 surgery_id 可挂，不许假装参数生效 =================

    @Test
    void adverseEventsReturnTheSameRowsUnderBothCalibers() {
        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        jdbc.update("""
                insert into qc_adverse_event(type, level, occurred_on, dept_id, description, status)
                values ('V49 苏醒延迟', 2, ?::date, ?, 'V49 归集口径用例', 'NEW')
                """, d3, deptId);

        assertEquals(events("1450", d2, d4, "event"), events("1450", d2, d4, "surgery"),
                "1450★ 无 surgery_id 关联列，两种 by 必然返回同一批数");
        assertEquals(items("1450", d2, d4, "event").size(), items("1450", d2, d4, "surgery").size());
        assertTrue(events("1450", d2, d4, "event") >= 1, "本用例造的那条不良事件应查得到");

        String note = String.valueOf(indicator("1450", d2, d4, "surgery").get("note"));
        assertTrue(note.contains("全院口径"), "既有的全院口径标注不许被顺手抹掉");
        assertTrue(note.contains("不改变结果"),
                "两种 by 同值这件事必须直说，不许让人以为切换 by 能得到另一套口径");
        // 取值仍要校验：不生效不等于不校验
        assertEquals(4943, anesQc.indicators(d2, d4, "1450", "occurred").getCode());
    }

    // ================= §⑦ 只读：加了参数也不许写一行 =================

    @Test
    void caliberSwitchingStaysStrictlyReadOnly() {
        Map<String, Object> before = counts();
        for (String by : new String[]{null, "event", "surgery"}) {
            for (String code : new String[]{"1437", "1446", "1447", "1448", "1449", "1450"}) {
                assertEquals(0, anesQc.indicators(opDay, d4, code, by).getCode());
                assertEquals(0, anesQc.detail(code, opDay, d4, null, by).getCode());
                assertNotNull(anesQc.indicatorsCsv(code, opDay, d4, by));
                assertNotNull(anesQc.detailCsv(code, opDay, d4, by));
            }
        }
        assertEquals(before, counts(), "口径切换全程不得写任何一行");
    }

    private Map<String, Object> counts() {
        var m = new java.util.LinkedHashMap<String, Object>();
        for (String t : new String[]{"inp_surgery", "surg_event", "qc_adverse_event", "inp_admission"}) {
            m.put(t, jdbc.queryForObject("select count(*) from " + t, Long.class));
        }
        return m;
    }
}
