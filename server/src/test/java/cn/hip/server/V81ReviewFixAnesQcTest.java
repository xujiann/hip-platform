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

    // ================= D8 明细 CSV 中文化 =================

    // ================= D10 不声称国标 =================

}
