package cn.hip.server;

import cn.hip.inpatient.entity.InpVitalSign;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpEmrController;
import cn.hip.platform.core.config.HipProfiles;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v42 车道1：体温单（三测单）出纸闭环。
 *
 * <p><b>本类的第一职责是还测试欠账</b>：体征域自 V9 建表以来是全仓零 JUnit 的空白区
 * （server/src/test grep vitals 零命中），只靠两个 E2E 脚本兜底——与 Phase113FinanceTest
 * 静默失效同一类风险。因此前两个用例<b>先锁死既有行为</b>（POST /vitals 存取往返、
 * GET /vitals 全量顺序），再测 v42 新端点；v43 收口 POST /vitals 服务端量程校验时，
 * 这两个用例就是唯一的安全网。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "NURSE"})
class V42TempSheetTest {

    private static final ZoneId ZONE = ZoneId.of(HipProfiles.ZONE);

    @Autowired InpEmrController emr;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private final Authentication auth = new UsernamePasswordAuthenticationToken("tester", "x");

    /** 建一个在院病例；backdateDays>0 时把入院时间回拨，用于构造多住院周 */
    private Long admission(int backdateDays, String allergy) {
        Patient p = new Patient();
        p.setName("三测" + System.nanoTime());
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        if (allergy != null) {
            em.flush();   // 先落库再 jdbc 改列，否则 update 命中 0 行（JPA 尚未 flush）
            assertEquals(1, jdbc.update(
                    "update empi_patient set allergy_history = ? where id = ?", allergy, pid));
        }
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        var adm = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("100"), "CASH", null);
        em.flush();
        if (backdateDays > 0) {
            jdbc.update("update inp_admission set admit_at = now() - make_interval(days => ?) where id = ?",
                    backdateDays, adm.getId());
        }
        return adm.getId();
    }

    private InpVitalSign vital(Instant at) {
        InpVitalSign v = new InpVitalSign();
        v.setMeasuredAt(at);
        return v;
    }

    private Instant at(LocalDate d, int hour, int minute) {
        return d.atTime(hour, minute).atZone(ZONE).toInstant();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> days(Map<String, Object> sheet) {
        return (List<Map<String, Object>>) sheet.get("days");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> points(Map<String, Object> day) {
        return (List<Map<String, Object>>) day.get("points");
    }

    private Map<String, Object> dayOf(Map<String, Object> sheet, LocalDate d) {
        return days(sheet).stream().filter(x -> d.toString().equals(x.get("date"))).findFirst().orElse(null);
    }

    // ===================== ① 锁定既有行为（v43 改写路径前的安全网） =====================

    /**
     * 既有行为锁定：POST /vitals 原样存下 6 个既有体征列并可原样读回。
     * v42 只扩列不动写路径——本用例若变红即说明写路径被误改。
     */
    @Test
    void postVitalsRoundTripsLegacySixFields() {
        Long admId = admission(0, null);
        Instant when = Instant.now().minusSeconds(3600);

        InpVitalSign in = vital(when);
        in.setTemperature(new BigDecimal("38.5"));
        in.setPulse(96);
        in.setRespiration(22);
        in.setSbp(132);
        in.setDbp(84);
        in.setSpo2(95);

        var saved = emr.addVital(admId, in, auth);
        assertEquals(0, saved.getCode(), "写入应成功");
        assertNotNull(saved.getData().getId());
        assertEquals(admId, saved.getData().getAdmissionId(), "admissionId 由路径覆盖");
        em.flush();
        em.clear();

        var back = emr.vitals(admId).getData().stream()
                .filter(v -> v.getId().equals(saved.getData().getId())).findFirst().orElse(null);
        assertNotNull(back, "读回应能取到刚写入的记录");
        assertEquals(0, new BigDecimal("38.5").compareTo(back.getTemperature()));
        assertEquals(96, back.getPulse());
        assertEquals(22, back.getRespiration());
        assertEquals(132, back.getSbp());
        assertEquals(84, back.getDbp());
        assertEquals(95, back.getSpo2());
        assertEquals(when.getEpochSecond(), back.getMeasuredAt().getEpochSecond(), "测量时间不被改写");
        // v42 新列未提交时必须为 null（历史行同形），不得被回填成 0 或默认值
        assertNull(back.getIntakeMl());
        assertNull(back.getOutputMl());
        assertNull(back.getStoolCount());
        assertNull(back.getWeightKg());
        assertNull(back.getHeightCm());
        assertNull(back.getMeasureSite());
        assertNull(back.getTempAfterCooling());
        assertNull(back.getNotMeasuredReason());
    }

    /**
     * 既有行为锁定：GET /vitals 返回住院全程<b>全量</b>体征，按 measuredAt 正序——
     * v42 新增的周窗口查询是<b>另一个</b> repo 方法，不得把既有全量拉取改成窗口裁剪
     * （医生站 InpDoctorView 的全程曲线直接消费此返回）。
     */
    @Test
    void getVitalsKeepsFullListInAscendingOrder() {
        Long admId = admission(20, null);
        LocalDate admitDate = LocalDate.now(ZONE).minusDays(20);
        // 故意乱序写入，且跨越第 1/2/3 住院周
        Instant t3 = at(admitDate.plusDays(15), 9, 0);
        Instant t1 = at(admitDate, 9, 0);
        Instant t2 = at(admitDate.plusDays(8), 9, 0);
        for (Instant t : List.of(t3, t1, t2)) {
            emr.addVital(admId, vital(t), auth);
        }
        em.flush();
        em.clear();

        var all = emr.vitals(admId).getData();
        assertEquals(3, all.size(), "全量返回，不受住院周窗口影响");
        assertTrue(all.get(0).getMeasuredAt().isBefore(all.get(1).getMeasuredAt()));
        assertTrue(all.get(1).getMeasuredAt().isBefore(all.get(2).getMeasuredAt()));
        assertEquals(t1.getEpochSecond(), all.get(0).getMeasuredAt().getEpochSecond());
        assertEquals(t3.getEpochSecond(), all.get(2).getMeasuredAt().getEpochSecond());
    }

    // ===================== ② v42 新端点 =====================

    /** 页眉字段齐全（姓名/性别/床号/住院号/科室/病区/入院日期/护理级别/过敏史） */
    @Test
    @SuppressWarnings("unchecked")
    void tempSheetHeaderCarriesAllPaperFields() {
        Long admId = admission(0, "青霉素过敏");
        em.flush();
        var r = emr.printTempSheet(admId, 1);
        assertEquals(0, r.getCode(), r.getMessage());
        var head = (Map<String, Object>) r.getData().get("header");
        for (String k : List.of("patient_name", "sex", "bed_no", "admission_no",
                "dept_name", "ward_name", "admit_at", "care_level", "allergy_history")) {
            assertTrue(head.containsKey(k), "页眉缺字段 " + k);
        }
        assertNotNull(head.get("patient_name"));
        assertNotNull(head.get("admission_no"));
        assertNotNull(head.get("dept_name"));
        assertNotNull(head.get("ward_name"));
        assertNotNull(head.get("bed_no"));
        assertEquals("二级", head.get("care_level"), "护理级别取 inp_admission 当前值");
        assertEquals("青霉素过敏", head.get("allergy_history"));
    }

    /** 周窗口：第 N 周 = 入院日 +(N-1)*7 起的 7 天；跨周的点不串页 */
    @Test
    void weekWindowIsSevenDaysFromAdmitDate() {
        Long admId = admission(10, null);
        LocalDate admitDate = LocalDate.now(ZONE).minusDays(10);
        emr.addVital(admId, vital(at(admitDate, 10, 0)), auth);            // 第 1 周
        emr.addVital(admId, vital(at(admitDate.plusDays(8), 10, 0)), auth); // 第 2 周
        em.flush();

        var w1 = emr.printTempSheet(admId, 1).getData();
        assertEquals(2, ((Number) w1.get("totalWeeks")).intValue(), "住院 10 天 = 2 个住院周");
        assertEquals(admitDate.toString(), w1.get("weekStart"));
        assertEquals(admitDate.plusDays(6).toString(), w1.get("weekEnd"));
        assertEquals(7, days(w1).size(), "一页固定 7 天格位");
        assertEquals(1, ((Number) days(w1).get(0).get("hospitalDay")).intValue());
        assertEquals(1, points(dayOf(w1, admitDate)).size(), "入院当日的点在第 1 周");
        assertEquals(0, points(dayOf(w1, admitDate.plusDays(6))).size(), "第 8 天的点不得串进第 1 周");

        var w2 = emr.printTempSheet(admId, 2).getData();
        assertEquals(admitDate.plusDays(7).toString(), w2.get("weekStart"));
        assertEquals(1, points(dayOf(w2, admitDate.plusDays(8))).size(), "第 8 天的点落在第 2 周");
        assertEquals(9, ((Number) dayOf(w2, admitDate.plusDays(8)).get("hospitalDay")).intValue());
    }

    /** 越界返 4821，住院记录不存在返 4820（错误码固定在 v42 分配的 4820–4839 子段内） */
    @Test
    void weekOutOfRangeReturns4821AndUnknownAdmissionReturns4820() {
        Long admId = admission(0, null);
        em.flush();
        assertEquals(4821, emr.printTempSheet(admId, 0).getCode(), "week 从 1 起");
        assertEquals(4821, emr.printTempSheet(admId, 2).getCode(), "当日入院只有第 1 周");
        assertEquals(4821, emr.printTempSheet(admId, 999).getCode());
        assertEquals(4820, emr.printTempSheet(-1L, 1).getCode(), "住院记录不存在");
    }

    /**
     * 出入量口径：读侧合并 inp_icu_record 与体征新列，<b>ICU 记录优先</b>，
     * 两表绝不合并；返回体以 intakeSource/outputSource 区分数据来源。
     */
    @Test
    void icuIntakeOutputWinsOverVitalColumns() {
        Long admId = admission(2, null);
        LocalDate admitDate = LocalDate.now(ZONE).minusDays(2);
        LocalDate today = LocalDate.now(ZONE);

        // 入院当日：只有体征新列（分两次录，验证同来源内按日求和）
        InpVitalSign d1a = vital(at(admitDate, 8, 0));
        d1a.setIntakeMl(500);
        d1a.setOutputMl(400);
        d1a.setStoolCount(1);
        d1a.setWeightKg(new BigDecimal("60.5"));
        d1a.setHeightCm(165);
        emr.addVital(admId, d1a, auth);
        InpVitalSign d1b = vital(at(admitDate, 20, 0));
        d1b.setIntakeMl(300);
        d1b.setOutputMl(250);
        emr.addVital(admId, d1b, auth);

        // 今日：体征新列有值，同日又有 ICU 记录——ICU 必须赢
        InpVitalSign d3 = vital(at(today, 10, 30));
        d3.setIntakeMl(300);
        d3.setOutputMl(200);
        emr.addVital(admId, d3, auth);
        jdbc.update("""
                insert into inp_icu_record(admission_id, recorded_at, intake_ml, output_ml)
                values (?, (?::date + time '10:00') at time zone 'Asia/Shanghai', 1200, 900)
                """, admId, today.toString());
        em.flush();

        var sheet = emr.printTempSheet(admId, 1).getData();
        var day1 = dayOf(sheet, admitDate);
        assertEquals(800L, ((Number) day1.get("intakeMl")).longValue(), "同来源内按日求和");
        assertEquals(650L, ((Number) day1.get("outputMl")).longValue());
        assertEquals("VITAL", day1.get("intakeSource"));
        assertEquals("VITAL", day1.get("outputSource"));
        assertEquals(1, ((Number) day1.get("stoolCount")).intValue());
        assertEquals(0, new BigDecimal("60.5").compareTo((BigDecimal) day1.get("weightKg")));
        assertEquals(165, ((Number) day1.get("heightCm")).intValue());

        var day3 = dayOf(sheet, today);
        assertEquals(1200L, ((Number) day3.get("intakeMl")).longValue(), "ICU 记录优先于体征新列");
        assertEquals(900L, ((Number) day3.get("outputMl")).longValue());
        assertEquals("ICU", day3.get("intakeSource"));
        assertEquals("ICU", day3.get("outputSource"));

        // 无任何出入量的日子保持 null（不得回填 0），来源亦为 null
        var day2 = dayOf(sheet, admitDate.plusDays(1));
        assertNull(day2.get("intakeMl"));
        assertNull(day2.get("intakeSource"));
    }

    /** 新列往返 + 时点归格：物理降温后体温与未测原因随格点原样出纸 */
    @Test
    void sheetPointCarriesCoolingTempAndNotMeasuredReason() {
        Long admId = admission(0, null);
        LocalDate today = LocalDate.now(ZONE);

        InpVitalSign fever = vital(at(today, 14, 0));
        fever.setTemperature(new BigDecimal("39.2"));
        fever.setTempAfterCooling(new BigDecimal("38.0"));
        fever.setMeasureSite("AXILLARY");
        emr.addVital(admId, fever, auth);

        InpVitalSign absent = vital(at(today, 18, 0));
        absent.setNotMeasuredReason("外出检查");
        emr.addVital(admId, absent, auth);
        em.flush();

        var sheet = emr.printTempSheet(admId, 1).getData();
        var pts = points(dayOf(sheet, today));
        assertEquals(2, pts.size());
        var p14 = pts.stream().filter(x -> Integer.valueOf(14).equals(x.get("slotHour"))).findFirst().orElse(null);
        assertNotNull(p14, "14:00 应归入 14 时列");
        assertEquals(0, new BigDecimal("39.2").compareTo((BigDecimal) p14.get("temperature")));
        assertEquals(0, new BigDecimal("38.0").compareTo((BigDecimal) p14.get("tempAfterCooling")));
        assertEquals("AXILLARY", p14.get("measureSite"));

        var p18 = pts.stream().filter(x -> Integer.valueOf(18).equals(x.get("slotHour"))).findFirst().orElse(null);
        assertNotNull(p18, "18:00 应归入 18 时列");
        assertNull(p18.get("temperature"), "未测点体温为 null——前端画「未测」而不断线");
        assertEquals("外出检查", p18.get("notMeasuredReason"));

        assertEquals(List.of(2, 6, 10, 14, 18, 22), sheet.get("slotHours"), "纸面标准 6 时点列");
    }
}
