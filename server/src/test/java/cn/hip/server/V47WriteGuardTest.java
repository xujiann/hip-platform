package cn.hip.server;

import cn.hip.inpatient.entity.InpVitalSign;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.VitalValidator;
import cn.hip.inpatient.web.InpEmrController;
import cn.hip.platform.core.config.HipProfiles;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v47 写入校验收口版：{@code POST /vitals} 服务端校验 + {@code POST /records} 病历类型白名单。
 *
 * <p><b>这是全仓唯一一批「改既有写路径」的改动</b>，因此本类的编排刻意分成两段，
 * 且第 ① 段是<b>在动实现之前先写、先跑绿</b>的：
 * <ul>
 *   <li><b>① 安全网</b>——锁定既有合法行为（体征往返、measuredAt 缺省补 now()、v42 八列、
 *       体温单打印端点取数、recordType 缺省兜底 PROGRESS）。这一段任何一条变红，
 *       都说明写路径被误改，而不是新校验没写好。</li>
 *   <li><b>② 新校验</b>——量程 4824 / 时序 4825 / 测量部位 4822 / 未测原因 4823 / 病历类型 9129，
 *       且逐档验证 gate 三态。</li>
 * </ul>
 *
 * <p><b>gate 默认 warn 必须真的放行</b>：{@link #warnGateReallyPassesThroughAndReturnsWarnings()}
 * 断言 999℃ 在 warn 档<b>确实落了库</b>（不是「偷偷 block 再假装成功」），只是返回体多带 warnings。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "NURSE", "DOCTOR_OUTP"})
class V47WriteGuardTest {

    private static final ZoneId ZONE = ZoneId.of(HipProfiles.ZONE);
    private static final String GATE = "emr.gate.vital.range";

    @Autowired InpEmrController emr;
    @Autowired VitalValidator validator;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private final Authentication auth = new UsernamePasswordAuthenticationToken("tester", "x");

    /** gate 是进程内 30 秒缓存（ConfigReader），事务回滚清不掉——必须显式失效，否则串味到别的用例 */
    @AfterEach
    void evict() {
        configReader.evictAll();
    }

    private void gate(String value) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, GATE);
        configReader.evict(GATE);
    }

    private Long admission() {
        Patient p = new Patient();
        p.setName("v47" + System.nanoTime());
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        var adm = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("100"), "CASH", null);
        em.flush();
        return adm.getId();
    }

    /** 六项体征齐全的合法记录（不带 v42 扩列） */
    private InpVitalSign legal() {
        InpVitalSign v = new InpVitalSign();
        v.setTemperature(new BigDecimal("36.8"));
        v.setPulse(78);
        v.setRespiration(18);
        v.setSbp(120);
        v.setDbp(80);
        v.setSpo2(98);
        return v;
    }

    @SuppressWarnings("unchecked")
    private List<String> warningsOf(InpVitalSign saved) {
        // warnings 只在有告警时以子类形态回带（无告警时返回体逐字节不变，见 InpEmrController.VitalSaved）
        if (!(saved instanceof InpEmrController.VitalSaved vs)) return List.of();
        return (List<String>) (List<?>) vs.getWarnings();
    }

    // ==================== ① 安全网：锁定既有合法行为（先于实现写、先跑绿） ====================

    /**
     * 合法体征存取往返 + v42 八列（出入量/大便/体重身高/测量部位/降温后体温/未测原因）逐列取回。
     * 本用例是本版全部改动的地基：只要它绿，就说明合法写入路径没有被新校验改变。
     */
    @Test
    void legalVitalRoundTripsAllColumns() {
        Long admId = admission();
        Instant when = Instant.now();

        InpVitalSign in = legal();
        in.setMeasuredAt(when);
        in.setIntakeMl(500);
        in.setOutputMl(400);
        in.setStoolCount(1);
        in.setWeightKg(new BigDecimal("60.5"));
        in.setHeightCm(165);
        in.setMeasureSite("AXILLARY");
        in.setTempAfterCooling(new BigDecimal("37.2"));
        in.setNotMeasuredReason(null);

        var r = emr.addVital(admId, in, auth);
        assertEquals(0, r.getCode(), r.getMessage());
        assertTrue(warningsOf(r.getData()).isEmpty(), "合法值不得产生任何 warnings");
        Long id = r.getData().getId();
        assertNotNull(id);
        assertEquals(admId, r.getData().getAdmissionId(), "admissionId 由路径覆盖");
        em.flush();
        em.clear();

        var back = emr.vitals(admId).getData().stream()
                .filter(v -> v.getId().equals(id)).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("36.8").compareTo(back.getTemperature()));
        assertEquals(78, back.getPulse());
        assertEquals(18, back.getRespiration());
        assertEquals(120, back.getSbp());
        assertEquals(80, back.getDbp());
        assertEquals(98, back.getSpo2());
        assertEquals(when.getEpochSecond(), back.getMeasuredAt().getEpochSecond(), "测量时间不被改写");
        assertEquals(500, back.getIntakeMl());
        assertEquals(400, back.getOutputMl());
        assertEquals(1, back.getStoolCount());
        assertEquals(0, new BigDecimal("60.5").compareTo(back.getWeightKg()));
        assertEquals(165, back.getHeightCm());
        assertEquals("AXILLARY", back.getMeasureSite());
        assertEquals(0, new BigDecimal("37.2").compareTo(back.getTempAfterCooling()));
        assertNull(back.getNotMeasuredReason());
    }

    /** 既有行为：measuredAt 不传时服务端补 now()（护士站/移动端都不传这个字段） */
    @Test
    void measuredAtDefaultsToNowWhenAbsent() {
        Long admId = admission();
        Instant before = Instant.now().minusSeconds(2);

        var r = emr.addVital(admId, legal(), auth);
        assertEquals(0, r.getCode(), r.getMessage());
        Instant saved = r.getData().getMeasuredAt();
        assertNotNull(saved, "measuredAt 不传时必须补 now()，不得落 null");
        assertFalse(saved.isBefore(before));
        assertFalse(saved.isAfter(Instant.now().plusSeconds(2)));
    }

    /**
     * 三项都不传的极简报文（历史对接方形态）仍然成立：整行全空 + 未测原因，v42 之前就允许。
     *
     * <p><b>显式置 warn 而不是靠「默认就是 warn」</b>：本类别的用例会把 gate 改到 block，
     * 而 {@code ConfigReader} 是进程内缓存、事务回滚清不掉——合并跑时本用例会读到上一个
     * 用例留下的 block 档，于是「带了未测原因就不该告警」这条断言随机失败
     * （单跑必绿、合并跑偶红，最容易被误当成偶发而重跑过关）。
     * <b>依赖隐式默认值的测试，在共享进程内缓存的场景下就是不稳定测试。</b>
     */
    @Test
    void bareRecordWithOnlyNotMeasuredReasonStillSaves() {
        gate("warn");
        Long admId = admission();
        InpVitalSign v = new InpVitalSign();
        v.setNotMeasuredReason("外出检查");

        var r = emr.addVital(admId, v, auth);
        assertEquals(0, r.getCode(), r.getMessage());
        assertTrue(warningsOf(r.getData()).isEmpty(), "带了未测原因就不该告警");
        em.flush();
        em.clear();
        assertEquals("外出检查",
                emr.vitals(admId).getData().get(0).getNotMeasuredReason());
    }

    /** v42 体温单打印端点仍能取到刚写入的体征（读侧不受写侧收口影响） */
    @Test
    @SuppressWarnings("unchecked")
    void tempSheetStillReadsFreshVitals() {
        Long admId = admission();
        InpVitalSign v = legal();
        v.setMeasureSite("ORAL");
        assertEquals(0, emr.addVital(admId, v, auth).getCode());
        em.flush();

        var sheet = emr.printTempSheet(admId, 1);
        assertEquals(0, sheet.getCode(), sheet.getMessage());
        var days = (List<Map<String, Object>>) sheet.getData().get("days");
        long points = days.stream()
                .mapToLong(d -> ((List<Map<String, Object>>) d.get("points")).size()).sum();
        assertEquals(1, points, "刚写入的体征必须出现在体温单第 1 周格位里");
    }

    /** 既有行为：POST /records 不传 recordType 仍兜底 PROGRESS（v47 明确不许改这条） */
    @Test
    void recordTypeDefaultsToProgressWhenAbsent() {
        Long admId = admission();
        var r = emr.addRecord(admId,
                new InpEmrController.SaveRecordRequest(null, null, "今日精神可。"), auth);
        assertEquals(0, r.getCode(), r.getMessage());
        assertEquals("PROGRESS", r.getData().getRecordType(), "不传时的兜底值不许改");
        assertEquals("病程记录", r.getData().getTitle(), "标题兜底同样不许改");
    }

    /** 既有行为：内容为空仍返 9101，且顺序在类型校验之后不得被改成别的码 */
    @Test
    void emptyContentStillReturns9101() {
        Long admId = admission();
        assertEquals(9101, emr.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "t", "  "), auth).getCode());
    }

    // ==================== ② POST /vitals 服务端校验 ====================

    /**
     * <b>warn 档必须真的放行</b>：999℃ 在默认 warn 下照样落库（返回 code 0 且能读回），
     * 只是返回体多带 warnings 让护士当场看见。这条用例是「有没有偷偷 block」的唯一证据。
     */
    @Test
    void warnGateReallyPassesThroughAndReturnsWarnings() {
        gate("warn");
        Long admId = admission();
        InpVitalSign v = legal();
        v.setTemperature(new BigDecimal("999.0"));
        v.setPulse(9999);

        var r = emr.addVital(admId, v, auth);
        assertEquals(0, r.getCode(), "warn 档不得拦截");
        Long id = r.getData().getId();
        assertNotNull(id, "warn 档必须真的落库，不能返回 code 0 却不写");

        var w = warningsOf(r.getData());
        assertEquals(2, w.size(), "两项越界应各出一条告警，实际：" + w);
        assertTrue(w.stream().anyMatch(s -> s.contains("体温") && s.contains("30") && s.contains("45")),
                "告警须带项目名与合法区间，实际：" + w);
        assertTrue(w.stream().anyMatch(s -> s.contains("脉搏") && s.contains("250")), "实际：" + w);

        em.flush();
        em.clear();
        assertEquals(0, new BigDecimal("999.0").compareTo(
                emr.vitals(admId).getData().get(0).getTemperature()), "warn 档 999℃ 必须真的存进去");
    }

    /** off 档全放行且<b>不带 warnings</b>——返回体与 v46 逐字节相同 */
    @Test
    void offGateIsByteIdenticalToLegacy() {
        gate("off");
        Long admId = admission();
        InpVitalSign v = legal();
        v.setTemperature(new BigDecimal("999.0"));
        v.setMeasureSite("随便写的");

        var r = emr.addVital(admId, v, auth);
        assertEquals(0, r.getCode());
        assertFalse(r.getData() instanceof InpEmrController.VitalSaved,
                "off 档返回体不得多出 warnings 字段（逐字节不变）");
        assertNotNull(r.getData().getId());
    }

    /** block 档：六项量程各自越界都返 4824，消息带项目名与合法区间；边界值恰好放行 */
    @Test
    void blockGateRejectsOutOfRangeWith4824() {
        gate("block");
        Long admId = admission();

        record Case(String field, java.util.function.Consumer<InpVitalSign> set, String label) {}
        List<Case> bad = List.of(
                new Case("temperature", v -> v.setTemperature(new BigDecimal("999.0")), "体温"),
                new Case("pulse", v -> v.setPulse(9999), "脉搏"),
                new Case("respiration", v -> v.setRespiration(4), "呼吸"),
                new Case("sbp", v -> v.setSbp(301), "收缩压"),
                new Case("dbp", v -> v.setDbp(19), "舒张压"),
                new Case("spo2", v -> v.setSpo2(49), "血氧"));
        for (Case c : bad) {
            InpVitalSign v = legal();
            c.set().accept(v);
            var r = emr.addVital(admId, v, auth);
            assertEquals(4824, r.getCode(), c.field() + " 越界应返 4824");
            assertTrue(r.getMessage().contains(c.label()), c.field() + " 消息须带项目名，实际：" + r.getMessage());
            assertNull(r.getData(), "block 档不得落库");
        }

        // 边界值（闭区间两端）必须放行——不许把合法极端值（低温治疗 30℃ / 室上速 250）误杀
        InpVitalSign lo = new InpVitalSign();
        lo.setTemperature(new BigDecimal("30.0"));
        lo.setPulse(20);
        lo.setRespiration(5);
        lo.setSbp(40);
        lo.setDbp(20);
        lo.setSpo2(50);
        assertEquals(0, emr.addVital(admId, lo, auth).getCode(), "下界必须放行");

        InpVitalSign hi = new InpVitalSign();
        hi.setTemperature(new BigDecimal("45.0"));
        hi.setPulse(250);
        hi.setRespiration(60);
        hi.setSbp(300);
        hi.setDbp(200);
        hi.setSpo2(100);
        assertEquals(0, emr.addVital(admId, hi, auth).getCode(), "上界必须放行");
    }

    /** block 档时序 4825：晚于当前时刻 / 早于入院 / 晚于出院；5 分钟时钟容差内放行 */
    @Test
    void blockGateRejectsIllegalMeasuredAtWith4825() {
        gate("block");
        Long admId = admission();
        Instant admitAt = jdbc.queryForObject(
                "select admit_at from inp_admission where id = ?", java.sql.Timestamp.class, admId).toInstant();

        InpVitalSign future = legal();
        future.setMeasuredAt(Instant.now().plus(1, ChronoUnit.HOURS));
        var f = emr.addVital(admId, future, auth);
        assertEquals(4825, f.getCode(), "未来时间应拒");
        assertTrue(f.getMessage().contains("当前"), f.getMessage());

        // 时钟容差：5 分钟内的轻微超前放行（PDA/床旁设备时钟偏差是常态，不能因此写不进体征）
        InpVitalSign skew = legal();
        skew.setMeasuredAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        assertEquals(0, emr.addVital(admId, skew, auth).getCode(), "5 分钟容差内的时钟偏差须放行");

        InpVitalSign early = legal();
        early.setMeasuredAt(admitAt.minus(1, ChronoUnit.HOURS));
        var e = emr.addVital(admId, early, auth);
        assertEquals(4825, e.getCode(), "早于入院时间应拒");
        assertTrue(e.getMessage().contains("入院"), e.getMessage());

        // 已出院：不得补录到出院之后
        Long discharged = admission();
        jdbc.update("update inp_admission set status = 'DISCHARGED', discharged_at = now() - interval '2 hour', "
                + "admit_at = now() - interval '2 day' where id = ?", discharged);
        InpVitalSign afterOut = legal();
        afterOut.setMeasuredAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        var a = emr.addVital(discharged, afterOut, auth);
        assertEquals(4825, a.getCode(), "出院后补录应拒");
        assertTrue(a.getMessage().contains("出院"), a.getMessage());

        // 出院之前的补录仍然合法（病案补录是日常操作，不许一刀切禁）
        InpVitalSign beforeOut = legal();
        beforeOut.setMeasuredAt(Instant.now().minus(1, ChronoUnit.DAYS));
        assertEquals(0, emr.addVital(discharged, beforeOut, auth).getCode(), "出院前时段的补录须放行");
    }

    /** block 档 4822：measure_site 白名单（v42 预留码本版启用）；不传仍合法 */
    @Test
    void blockGateRejectsBadMeasureSiteWith4822() {
        gate("block");
        Long admId = admission();

        InpVitalSign bad = legal();
        bad.setMeasureSite("腋下");
        var r = emr.addVital(admId, bad, auth);
        assertEquals(4822, r.getCode());
        assertTrue(r.getMessage().contains("ORAL"), r.getMessage());

        for (String site : List.of("ORAL", "AXILLARY", "RECTAL")) {
            InpVitalSign ok = legal();
            ok.setMeasureSite(site);
            assertEquals(0, emr.addVital(admId, ok, auth).getCode(), site + " 是白名单值");
        }
        assertEquals(0, emr.addVital(admId, legal(), auth).getCode(), "不传测量部位仍合法");
    }

    /** block 档 4823：六项体征全空时未测原因必填（三测单要画「未测」而不是断线） */
    @Test
    void blockGateRequiresNotMeasuredReasonWhenNothingMeasuredWith4823() {
        gate("block");
        Long admId = admission();

        var r = emr.addVital(admId, new InpVitalSign(), auth);
        assertEquals(4823, r.getCode(), "六项全空且无未测原因应拒");

        InpVitalSign withReason = new InpVitalSign();
        withReason.setNotMeasuredReason("外出检查");
        assertEquals(0, emr.addVital(admId, withReason, auth).getCode());

        // 只要有任一项体征就不要求未测原因
        InpVitalSign onlyTemp = new InpVitalSign();
        onlyTemp.setTemperature(new BigDecimal("36.8"));
        assertEquals(0, emr.addVital(admId, onlyTemp, auth).getCode());
    }

    /**
     * <b>只录出入量/体重/大便的行不得被 4823 误伤</b>——夜班液体平衡记录就是这样一条，
     * 护士本来就不该在这个时点测体征。口径若停在"六项体征全空"，院方一旦切到 block，
     * 这些正常记录会直接写不进去（V42TempSheetTest 的 d1b 正是这样一条真实数据）。
     */
    @Test
    void fluidBalanceOnlyRowIsNotFlaggedAs4823() {
        gate("block");   // 用最严档位测：block 下不拒，warn 下自然也不提示
        Long admId = admission();

        for (var row : java.util.List.<java.util.function.Consumer<InpVitalSign>>of(
                v -> v.setIntakeMl(1500),
                v -> v.setOutputMl(1200),
                v -> v.setStoolCount(1),
                v -> v.setWeightKg(new BigDecimal("62.5")))) {
            InpVitalSign v = new InpVitalSign();
            row.accept(v);
            var r = emr.addVital(admId, v, auth);
            assertEquals(0, r.getCode(),
                    "只录出入量/体重/大便的护理记录不是漏测，不得要求未测原因：" + r.getMessage());
        }

        // 真正什么都没记的行仍然要原因——收窄不等于把这条校验废掉
        assertEquals(4823, emr.addVital(admId, new InpVitalSign(), auth).getCode(),
                "整行全空仍应要求未测原因");
    }

    /**
     * <b>降温后体温同样受量程约束</b>——它印在三测单上（TempSheetSvg 画降温后的下降箭头），
     * 写进 999 一样会上法定护理文书。它是 v47 收口时容易漏掉的一项，故单独钉死。
     */
    @Test
    void tempAfterCoolingIsRangeCheckedToo() {
        gate("block");
        Long admId = admission();

        InpVitalSign bad = new InpVitalSign();
        bad.setTemperature(new BigDecimal("39.5"));
        bad.setTempAfterCooling(new BigDecimal("999"));
        var r = emr.addVital(admId, bad, auth);
        assertEquals(4824, r.getCode(), "降温后体温 999 应被拦：" + r.getMessage());
        assertTrue(r.getMessage().contains("降温后"), "消息应指明是降温后体温而非体温：" + r.getMessage());

        InpVitalSign good = new InpVitalSign();
        good.setTemperature(new BigDecimal("39.5"));
        good.setTempAfterCooling(new BigDecimal("38.2"));
        assertEquals(0, emr.addVital(admId, good, auth).getCode(), "正常降温后体温应放行");
    }

    /**
     * <b>RANGES 与 values() 必须同序等长</b>——量程校验靠下标把两个列表对齐，
     * 长度或顺序一旦分叉，报出来的会是"血氧超范围"而值其实是体温，且**不会有任何测试自然失败**。
     * v47 加降温后体温时就同时动了这两处，这条守的正是下次再加字段的人。
     */
    @Test
    void rangeListAndValueListStayAligned() throws Exception {
        var rangesField = VitalValidator.class.getDeclaredField("RANGES");
        rangesField.setAccessible(true);
        int rangeCount = ((java.util.List<?>) rangesField.get(null)).size();

        var valuesMethod = VitalValidator.class.getDeclaredMethod("values", InpVitalSign.class);
        valuesMethod.setAccessible(true);
        InpVitalSign probe = new InpVitalSign();
        probe.setTemperature(new BigDecimal("36.5"));
        probe.setPulse(80);
        probe.setRespiration(18);
        probe.setSbp(120);
        probe.setDbp(75);
        probe.setSpo2(98);
        probe.setTempAfterCooling(new BigDecimal("36.0"));
        var vals = (java.util.List<?>) valuesMethod.invoke(validator, probe);

        assertEquals(rangeCount, vals.size(),
                "RANGES 与 values() 长度必须一致，否则量程按下标会错位到别的字段");
        assertTrue(vals.stream().noneMatch(java.util.Objects::isNull),
                "探针把每一项都填了值，若仍有 null 说明 values() 漏取了某个字段");
    }

    /** 坏配置回落 warn（而不是 off）：gate 值写错时宁可多提示，不可静默失效 */
    @Test
    void unknownGateValueFallsBackToWarn() {
        gate("BLOCK-ish 乱填");
        Long admId = admission();
        InpVitalSign v = legal();
        v.setTemperature(new BigDecimal("999.0"));

        var r = emr.addVital(admId, v, auth);
        assertEquals(0, r.getCode(), "坏配置不得变成 block");
        assertFalse(warningsOf(r.getData()).isEmpty(), "坏配置不得静默失效成 off");
    }

    /**
     * <b>返回体逐字节不变的直接证据</b>：合法写入序列化出来的 JSON 里<b>连 warnings 这个 key
     * 都不存在</b>，与 v46 完全同形；只有真出告警时才多这一个字段。
     * 存量对接方按固定字段解析的报文不会因本版变形。
     */
    @Test
    void wireFormatGainsWarningsKeyOnlyWhenWarned() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        gate("warn");
        Long admId = admission();

        String clean = mapper.writeValueAsString(emr.addVital(admId, legal(), auth).getData());
        assertFalse(clean.contains("warnings"), "合法写入的返回体不得多出 warnings 字段：" + clean);

        InpVitalSign bad = legal();
        bad.setTemperature(new BigDecimal("999.0"));
        String warned = mapper.writeValueAsString(emr.addVital(admId, bad, auth).getData());
        assertTrue(warned.contains("warnings"), "告警时须回带 warnings：" + warned);
        assertTrue(warned.contains("temperature"), "warnings 是追加字段，既有字段一个不少：" + warned);
    }

    /** 迁移 seed 到位：新 gate 键必须在库里，且默认值是 warn */
    @Test
    void gateSeedIsWarnByDefault() {
        assertEquals("warn", jdbc.queryForObject(
                "select cfg_value from sys_config where cfg_key = ?", String.class, GATE),
                "V143 必须 seed emr.gate.vital.range = warn");
    }

    // ==================== ③ POST /records 的 recordType 白名单 ====================

    /** 六种合法类型逐字落库，不被兜底改写 */
    @Test
    void allWhitelistedRecordTypesArePersistedVerbatim() {
        Long admId = admission();
        for (String t : List.of("ADMISSION", "FIRST_PROGRESS", "PROGRESS", "ROUND", "DISCHARGE", "PREOP")) {
            var r = emr.addRecord(admId, new InpEmrController.SaveRecordRequest(t, t, "正文"), auth);
            assertEquals(0, r.getCode(), t + " 应是白名单值：" + r.getMessage());
            assertEquals(t, r.getData().getRecordType(), t + " 须逐字落库");
        }
    }

    /** 非法类型返 9129 且一条都不落库（此前任意字符串直落库，质控 100% 漏判且零报错） */
    @Test
    void illegalRecordTypeReturns9129AndPersistsNothing() {
        Long admId = admission();
        for (String t : List.of("随便写的", "progress", "  ", "TRANSFER")) {
            var r = emr.addRecord(admId, new InpEmrController.SaveRecordRequest(t, "t", "正文"), auth);
            assertEquals(9129, r.getCode(), "「" + t + "」不在白名单");
            assertTrue(r.getMessage().contains("PROGRESS"), "消息须列出白名单，实际：" + r.getMessage());
        }
        em.flush();
        assertEquals(0, (int) jdbc.queryForObject(
                "select count(1) from inp_medical_record where admission_id = ?", Integer.class, admId),
                "非法类型一条都不该落库");
    }

    /** 白名单挡在结构化渲染之前：类型非法时不该先去做模板校验（避免报错码抢跑） */
    @Test
    void recordTypeWhitelistIsCheckedBeforeStructuredRendering() {
        Long admId = admission();
        var r = emr.addRecord(admId, new InpEmrController.SaveRecordRequest(
                "不存在的类型", "t", "正文", -1L, Map.of("ghost", "x")), auth);
        assertEquals(9129, r.getCode(), "类型非法应先于模板校验返回 9129");
    }

    /** POST /records/round 走的是 ROUND 常量，不受白名单影响（既有契约逐字不动） */
    @Test
    void roundEndpointUnaffectedByWhitelist() {
        Long admId = admission();
        var r = emr.addRound(admId,
                new InpEmrController.RoundRequest("ATTENDING", "查房意见：病情平稳。", null, null), auth);
        assertEquals(0, r.getCode(), r.getMessage());
        assertEquals("ROUND", r.getData().getRecordType());
    }
}
