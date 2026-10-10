package cn.hip.server;

import cn.hip.medtech.web.AnesQcController;
import cn.hip.platform.core.service.ConfigReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v81 审阅修补：麻醉质控六条 ★ 复核审计（docs/验收/v81复核-麻醉质控/audit_81.md §七）的统计侧缺陷。
 *
 * <ul>
 *   <li><b>D1</b> 「已开展」只排除了取消：只申请、已排台未做的台次进了 1424 首台、1425 台数、1428 分类、1436 年龄。
 *       修正后已开展 = 已登记开台时间或状态为已完成（DONE）。</li>
 *   <li><b>D2</b> 1436「患者数」数的是台次：同一患者两台记 2。修正后追加按患者去重的 patients（主口径），台次列保留。</li>
 *   <li><b>D3</b> 1425 明细手术时长逐台取整、不排除结束早于开台，与汇总（按秒累加、滤非正）对不上。</li>
 *   <li><b>D4</b> 1436 明细无出生日期 / 年龄 / 性别，任何一格都核不了。</li>
 *   <li><b>D8</b> 明细 CSV 出英文码（DONE / ELECTIVE / PRE_IN）与英文表头 is_unplanned_reop。</li>
 *   <li><b>D9</b> 1425 只有按日合计，没有区间合计行。</li>
 *   <li><b>D10</b> 屏上、CSV、note 自称「国标」「2022 版」，而平台以开台时间（非麻醉开始时间）判定。</li>
 * </ul>
 *
 * <p>夹具日取距今 340 / 341 天，避开 V46（200/201）、V49 系（210/220/298–300）、V81（260/261/271/290）的夹具窗。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "QUALITY"})
class V81ReviewFixAnesQcTest {

    @Autowired AnesQcController anesQc;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private String dayC;
    private String dayD;

    @BeforeEach
    void setUp() {
        dayC = jdbc.queryForObject("select (current_date - 340)::text", String.class);
        dayD = jdbc.queryForObject("select (current_date - 341)::text", String.class);
        jdbc.update("delete from sys_config where cfg_key = 'anes.qc.ontime_minutes'");
        configReader.evict("anes.qc.ontime_minutes");
        Long others = jdbc.queryForObject("""
                select count(*) from inp_surgery s
                where coalesce(s.cancelled_at, s.start_at, s.in_room_at, s.scheduled_at, s.created_at) >= ?::date
                  and coalesce(s.cancelled_at, s.start_at, s.in_room_at, s.scheduled_at, s.created_at) <  ?::date + 1
                """, Long.class, dayD, dayC);
        assertEquals(0L, others, "夹具窗口内不得有其它手术，否则对账测的是环境");
        Long events = jdbc.queryForObject(
                "select count(*) from qc_adverse_event where occurred_on between ?::date and ?::date",
                Long.class, dayD, dayC);
        assertEquals(0L, events);
    }

    // ================= 夹具 =================

    private static long seq = 0;

    private static String uniq(String prefix) {
        return prefix + (System.nanoTime() % 100000000L) + (seq++);
    }

    /** 新患者（性别 sex，dayC 当天满 years 岁又 10 天）+ 一次住院 */
    private Long newAdmission(String sex, int years) {
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, ?, (?::date - make_interval(years => ?) - interval '10 days')::date)
                returning id
                """, Long.class, uniq("V81R"), "审阅修补" + uniq(""), sex, dayC, years);
        return admissionOf(patientId, "IN_HOSPITAL");
    }

    /** 同一患者再办一次住院（D2：同一患者跨住院也只算一人）；在院唯一约束下第二次住院记为已出院 */
    private Long admissionOf(Long patientId, String status) {
        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        Long bedId = jdbc.queryForObject("select id from inp_bed order by id limit 1", Long.class);
        return jdbc.queryForObject("""
                insert into inp_admission(admission_no, patient_id, dept_id, ward_id, bed_id, status, admit_at)
                values (?, ?, ?, ?, ?, ?, now())
                returning id
                """, Long.class, uniq("V81RA"), patientId, deptId, deptId, bedId, status);
    }

    private Long patientOf(Long admissionId) {
        return jdbc.queryForObject("select patient_id from inp_admission where id = ?", Long.class, admissionId);
    }

    /** 时间参数 "HH:mm[:ss]"，落在 day 当天（会话时区解释）；created 一并钉在 day 当天，免得锚点回落到今天 */
    private Long surgery(Long adm, String status, String day, String room, String sched, String in,
                         String start, String end, String out, String kind) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status,
                                        scheduled_at, room_no, in_room_at, start_at, end_at, out_room_at,
                                        surgery_level, asa_grade, surgery_kind, op_icd, created_at)
                values (?, 'V81审阅夹具术', '全身麻醉', ?, ?::timestamptz, ?, ?::timestamptz,
                        ?::timestamptz, ?::timestamptz, ?::timestamptz, '二级', 'II', ?, '47.09',
                        ?::timestamptz)
                returning id
                """, Long.class, adm, status, at(day, sched), room, at(day, in), at(day, start), at(day, end),
                at(day, out), kind, at(day, "06:00"));
    }

    private static String at(String day, String hhmm) {
        return hhmm == null ? null : day + " " + hhmm;
    }

    // ================= 取数 =================

    @SuppressWarnings("unchecked")
    private Map<String, Object> indicator(String code, String from, String to) {
        var r = anesQc.indicators(from, to, code);
        assertEquals(0, r.getCode(), r.getMessage());
        var list = (List<Map<String, Object>>) r.getData().get("indicators");
        assertEquals(1, list.size());
        return list.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String code, String from, String to) {
        return (List<Map<String, Object>>) indicator(code, from, to).get("rows");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(String code, String from, String to) {
        return (Map<String, Object>) indicator(code, from, to).get("summary");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String code, String from, String to) {
        var r = anesQc.detail(code, from, to, null);
        assertEquals(0, r.getCode(), r.getMessage());
        assertEquals(Boolean.FALSE, r.getData().get("truncated"));
        return (List<Map<String, Object>>) r.getData().get("items");
    }

    private static Double dbl(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static long sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToLong(r -> num(r, key)).sum();
    }

    private static Map<String, Object> bySurgery(List<Map<String, Object>> items, Long id) {
        return items.stream().filter(x -> Objects.equals(num(x, "surgery_id"), id)).findFirst()
                .orElseGet(() -> fail("明细里找不到手术 " + id));
    }

    // ================= D1 已开展口径 =================

    /**
     * 同一天四台：R1 一台已完成（08:30 入室）、R1 一台已排台未做（排在 07:30，更早）、
     * 一台只申请（无手术间、无排台）、R2 一台已开台未完成（SCHEDULED + start_at）。
     * 已开展 = 已完成 + 已开台未完成 = 2 台；未做的两台不进 1424 / 1425 / 1428 / 1436，
     * 也不能按排台时刻抢走 R1 的首台位。覆盖率段照旧数全部 4 台。
     */
    @Test
    void onlyPerformedSurgeriesCountAsPerformed() {
        Long adm = newAdmission("M", 50);
        Long done = surgery(adm, "DONE", dayC, "V81R-R1", "08:00", "08:30", "08:40", "09:40", "09:50", "ELECTIVE");
        Long notDone = surgery(adm, "SCHEDULED", dayC, "V81R-R1", "07:30", null, null, null, null, "EMERGENCY");
        surgery(adm, "REQUESTED", dayC, null, null, null, null, null, null, "DAY");
        Long inProgress = surgery(adm, "SCHEDULED", dayC, "V81R-R2", "08:00", "08:05", "08:10", null, null, "ELECTIVE");

        var r28 = rows("1428", dayC, dayC);
        assertEquals(2, sum(r28, "cases"), "1428 已开展只数已完成与已开台的两台，只申请 / 已排台未做的不算");
        assertTrue(r28.stream().noneMatch(r -> "EMERGENCY".equals(r.get("surgery_kind")) || "DAY".equals(r.get("surgery_kind"))),
                "未开展的急诊 / 日间两台不得形成分类行：" + r28);
        assertEquals(2, sum(rows("1436", dayC, dayC), "cases"), "1436 同口径");
        assertEquals(2, sum(rows("1425", dayC, dayC), "cases"), "1425 台数同口径");

        var s24 = summary("1424", dayC, dayC);
        assertEquals(2, num(s24, "first_cases"), "R1、R2 各一台首台");
        assertEquals(0, num(s24, "unjudgeable"), "排在 07:30 却没做的那台不得抢首台位（它判不了准点）");
        var firsts = items("1424", dayC, dayC).stream().map(x -> num(x, "surgery_id")).collect(Collectors.toSet());
        assertEquals(Set.of(done, inProgress), firsts);
        assertFalse(firsts.contains(notDone));

        @SuppressWarnings("unchecked")
        var cov = (Map<String, Object>) anesQc.indicators(dayC, dayC, "1428").getData().get("coverage");
        assertEquals(4, num(cov, "surgeries"), "覆盖率段不受已开展口径影响：分母仍是全部登记台次");
        for (String code : List.of("1424", "1425", "1428", "1436")) {
            String note = String.valueOf(indicator(code, dayC, dayC).get("note"));
            assertTrue(note.contains("已开展"), code + " 的 note 须写清已开展口径：" + note);
        }
    }

    /** 1426 取消与已开展口径无关：未开台就取消的台次照样按阶段计数 */
    @Test
    void cancellationCountIsUnaffectedByPerformedCaliber() {
        Long adm = newAdmission("F", 30);
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, status, scheduled_at, cancel_stage, cancel_reason,
                                        cancelled_at, created_at)
                values (?, 'V81审阅取消', 'CANCELLED', ?::timestamptz, 'SCHEDULE', '患者发热', ?::timestamptz, ?::timestamptz)
                """, adm, at(dayC, "09:00"), at(dayC, "10:00"), at(dayC, "06:00"));
        assertEquals(1, sum(rows("1426", dayC, dayC), "cases"));
    }

    // ================= D2 / D4 1436 患者数与明细 =================

    /** 甲（男 50 岁）两次住院各一台、乙（男 50 岁）一台，全在「45–59 岁 / 男」一格；返回乙那台的手术 ID */
    private Long seed1436() {
        Long admA1 = newAdmission("M", 50);
        Long admA2 = admissionOf(patientOf(admA1), "DISCHARGED");
        Long admB = newAdmission("M", 50);
        surgery(admA1, "DONE", dayC, "V81R-R3", "08:00", "08:00", "08:10", "09:00", "09:10", "ELECTIVE");
        surgery(admA2, "DONE", dayC, "V81R-R3", "10:00", "10:00", "10:10", "11:00", "11:10", "ELECTIVE");
        return surgery(admB, "DONE", dayC, "V81R-R4", "08:00", "08:00", "08:10", "09:00", "09:10", "DAY");
    }

    /** 【D2】「45–59 岁 / 男」格 patients = 2（主口径）、cases = 3（台次保留）；分桶穿透同给患者数；CSV 两列分名 */
    @Test
    void ageSexDistributionCountsPatientsNotCases() {
        seed1436();
        var r36 = rows("1436", dayC, dayC);
        assertEquals(1, r36.size(), r36.toString());
        var cell = r36.get(0);
        assertEquals("45–59 岁", cell.get("age_band"));
        assertEquals("男", cell.get("sex"));
        assertEquals(2, num(cell, "patients"), "【D2】患者数按患者去重：甲两次住院两台只算一人");
        assertEquals(3, num(cell, "cases"), "台次列保留");
        var keys = List.copyOf(cell.keySet());
        assertTrue(keys.indexOf("patients") < keys.indexOf("cases"), "患者数是主口径，排在台次之前：" + keys);

        var s36 = summary("1436", dayC, dayC);
        assertNotNull(s36, "1436 给区间合计：去重患者数与台次");
        assertEquals(2, num(s36, "patients"));
        assertEquals(3, num(s36, "cases"));

        var bk = anesQc.detail("1436", dayC, dayC, null, null, "45–59 岁 / 男");
        assertEquals(0, bk.getCode(), bk.getMessage());
        @SuppressWarnings("unchecked")
        var bItems = (List<Map<String, Object>>) bk.getData().get("items");
        assertEquals(3, ((Number) bk.getData().get("bucketSummaryCount")).intValue(), "台次 == 明细行数");
        assertEquals(3, bItems.size());
        assertEquals(2, ((Number) bk.getData().get("bucketSummaryPatients")).intValue(),
                "分桶穿透同口径：给出该格患者数");

        String csv = anesQc.indicatorsCsv("1436", dayC, dayC);
        String header = csv.lines().skip(3).findFirst().orElse("");
        assertTrue(header.contains("患者数") && header.contains("台次"), "CSV 表头分列「患者数」「台次」：" + header);
        assertFalse(header.contains("台数"), "1436 的 cases 不再叫「台数」以免读成患者数量：" + header);
    }

    /** 【D4】明细逐台带出生日期、手术时年龄、年龄段、性别与患者 ID；明细与分桶明细的去重患者数 == patients */
    @Test
    void ageSexDetailShowsBirthDateAgeAndSex() {
        Long sb = seed1436();
        var it = items("1436", dayC, dayC);
        assertEquals(3, it.size());
        var b = bySurgery(it, sb);
        assertEquals(50, num(b, "age_at_surgery"), "【D4】手术时年龄按锚点日与出生日期算：满 50 岁又 10 天");
        assertNotNull(b.get("birth_date"), "【D4】明细须带出生日期");
        assertEquals("男", b.get("sex"), "【D4】明细须带性别（与汇总同一中文名）");
        assertEquals("45–59 岁", b.get("age_band"), "明细年龄段与汇总分段同一公式");
        assertEquals(2, it.stream().map(x -> x.get("patient_id")).distinct().count(),
                "明细去重患者数 == 汇总 patients");

        var bk = anesQc.detail("1436", dayC, dayC, null, null, "45–59 岁 / 男");
        assertEquals(0, bk.getCode(), bk.getMessage());
        @SuppressWarnings("unchecked")
        var bItems = (List<Map<String, Object>>) bk.getData().get("items");
        assertEquals(2, bItems.stream().map(x -> x.get("patient_id")).distinct().count());
        assertTrue(bItems.stream().allMatch(x -> "45–59 岁".equals(x.get("age_band")) && "男".equals(x.get("sex"))),
                "分桶明细逐行可见自己落在哪一格");
    }

    // ================= D3 / D9 1425 手术时长同口径 + 区间合计 =================

    /**
     * 两台各 60 分 24 秒（非整分钟）、一台结束早于开台（录错）：
     * 汇总 120.8、明细 60.4 + 60.4（录错那台为空），明细加总 == 汇总逐位成立。
     */
    @Test
    void opMinutesDetailMatchesSummaryToTheDecimal() {
        Long adm = newAdmission("F", 40);
        Long s1 = surgery(adm, "DONE", dayD, "V81R-R5", "08:00", "07:50", "08:00:00", "09:00:24", "09:10", "ELECTIVE");
        Long s2 = surgery(adm, "DONE", dayD, "V81R-R5", "09:30", "09:40", "10:00:00", "11:00:24", "11:10", "ELECTIVE");
        Long bad = surgery(adm, "DONE", dayD, "V81R-R6", "08:00", null, "08:00", "07:00", null, "ELECTIVE");

        var row = rows("1425", dayD, dayD).get(0);
        assertEquals(2, num(row, "timed_cases"));
        assertEquals(120.8, dbl(row, "total_op_minutes"), 1e-9);

        var it = items("1425", dayD, dayD);
        assertEquals(60.4, dbl(bySurgery(it, s1), "op_minutes"), 1e-9, "【D3】逐台保留 0.1 分钟，不取整到 60");
        assertEquals(60.4, dbl(bySurgery(it, s2), "op_minutes"), 1e-9);
        assertNull(bySurgery(it, bad).get("op_minutes"), "【D3】结束早于开台不计手术时长，与汇总同过滤");
        double detailSum = it.stream().map(x -> dbl(x, "op_minutes")).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum();
        assertEquals(dbl(row, "total_op_minutes"), detailSum, 1e-9, "汇总总手术时长 == 明细逐台加总");
        assertEquals(num(row, "timed_cases"), it.stream().filter(x -> x.get("op_minutes") != null).count());
    }

    /** 两天窗口：summary 是区间合计行，与按日行逐列相加相等；首台入室时刻取区间最早 */
    @Test
    void turnoverSummaryIsTheRangeTotal() {
        Long adm = newAdmission("M", 66);
        surgery(adm, "DONE", dayD, "V81R-R7", "08:00", "08:00", "08:10", "09:00:30", "09:10", "ELECTIVE");
        surgery(adm, "DONE", dayD, "V81R-R7", "09:30", "09:40", "09:50", "10:50", "11:00", "ELECTIVE");
        surgery(adm, "DONE", dayC, "V81R-R7", "08:00", "07:45", "08:00", "08:45", "08:55", "ELECTIVE");
        surgery(adm, "DONE", dayC, "V81R-R7", "09:00", "09:30", "09:40", "10:30", "10:40", "ELECTIVE");
        surgery(adm, "DONE", dayC, "V81R-R8", "08:00", null, "08:05", "08:35", null, "DAY");

        var rs = rows("1425", dayD, dayC);
        assertEquals(2, rs.size());
        var s = summary("1425", dayD, dayC);
        assertNotNull(s, "【D9】1425 须给区间合计行");
        for (String k : List.of("cases", "timed_cases", "turnovers", "in_room_recorded")) {
            assertEquals(sum(rs, k), num(s, k), "区间合计 " + k + " == 按日之和");
        }
        assertEquals(5, num(s, "cases"));
        assertEquals(2, num(s, "turnovers"), "两天各一对接台（跨日不接）");
        assertEquals(rs.stream().mapToDouble(r -> dbl(r, "total_op_minutes")).sum(), dbl(s, "total_op_minutes"), 1e-9);
        assertEquals(rs.stream().mapToDouble(r -> dbl(r, "total_turnover_minutes")).sum(),
                dbl(s, "total_turnover_minutes"), 1e-9);
        assertEquals(30.0 + 35.0, dbl(s, "total_turnover_minutes"), 1e-9);
        assertEquals(32.5, dbl(s, "avg_turnover_minutes"), 1e-9);
        Object earliest = jdbc.queryForObject("select ?::timestamptz", Object.class, at(dayD, "08:00"));
        assertEquals(earliest, s.get("first_in_room_at"), "区间最早入室时刻");
    }

    // ================= D8 明细 CSV 中文化 =================

    @Test
    void detailCsvTranslatesCodesAndHeaders() {
        Long adm = newAdmission("F", 35);
        surgery(adm, "DONE", dayC, "V81R-R9", "08:00", "08:00", "08:10", "09:00", "09:10", "ELECTIVE");
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, status, scheduled_at, cancel_stage, cancel_reason,
                                        cancelled_at, created_at)
                values (?, 'V81审阅取消', 'CANCELLED', ?::timestamptz, 'PRE_IN', '血压高', ?::timestamptz, ?::timestamptz)
                """, adm, at(dayC, "09:00"), at(dayC, "10:00"), at(dayC, "06:00"));

        // JSON 明细保留编码（分桶键与既有调用方都按码取值）
        var it = items("1428", dayC, dayC);
        assertEquals("ELECTIVE", it.get(0).get("surgery_kind"));
        assertEquals("DONE", it.get(0).get("status"));

        String c28 = anesQc.detailCsv("1428", dayC, dayC);
        String header = c28.lines().skip(3).findFirst().orElse("");
        assertFalse(header.contains("is_unplanned_reop"), "表头不得出英文列名：" + header);
        assertTrue(header.contains("是否非计划再次手术"), header);
        String data = c28.lines().skip(4).findFirst().orElse("");
        assertTrue(data.contains("择期") && data.contains("已完成"), "类别与状态出中文：" + data);
        assertFalse(data.contains("ELECTIVE") || data.contains(",DONE"), data);

        String c26 = anesQc.detailCsv("1426", dayC, dayC);
        String d26 = c26.lines().skip(4).findFirst().orElse("");
        assertTrue(d26.contains("入室前") && d26.contains("已取消"), "取消阶段与状态出中文：" + d26);
        assertFalse(d26.contains("PRE_IN") || d26.contains("CANCELLED"), d26);
    }

    // ================= D10 不声称国标 =================

    @Test
    void noNationalStandardClaimOnScreenOrCsv() {
        Long adm = newAdmission("M", 45);
        surgery(adm, "DONE", dayC, "V81R-R10", "08:00", "08:00", "08:10", "09:00", "09:10", "ELECTIVE");
        var r = anesQc.indicators(dayC, dayC, null);
        assertEquals(0, r.getCode());
        String std = String.valueOf(r.getData().get("standardNote"));
        Set<String> texts = new HashSet<>();
        texts.add(std);
        @SuppressWarnings("unchecked")
        var inds = (List<Map<String, Object>>) r.getData().get("indicators");
        for (var ind : inds) {
            if (ind.get("note") != null) texts.add(String.valueOf(ind.get("note")));
        }
        for (String code : List.of("1424", "1429")) {
            texts.add(anesQc.indicatorsCsv(code, dayC, dayC));
            texts.add(anesQc.detailCsv(code, dayC, dayC));
        }
        for (String t : texts) {
            assertFalse(t.contains("国标"), "不得自称国标：" + t);
            assertFalse(t.contains("2022"), "不得自称按 2022 版指标：" + t);
        }
        String note1424 = String.valueOf(indicator("1424", dayC, dayC).get("note"));
        assertTrue(note1424.contains("择期首台口径"), note1424);
        assertTrue(note1424.contains("平台以开台时间判定"), note1424);
    }
}
