package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.PopulationRuleService;
import cn.hip.outpatient.service.PopulationRuleService.RuleReq;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v51 车道 C：CDSS 特殊人群用药规则（妊娠 / 哺乳 / 肝肾功能不全）。
 *
 * <p>本类刻意<b>不</b>测"规则命中就报警"这种同义反复。真正会出事的是三类<b>静默失败</b>，
 * 每一类都单独立案：
 * <ol>
 *   <li><b>把"取不到数据"当成"没问题"</b>——未采集状态、检验缺失/超期/非数值、单位不一致；</li>
 *   <li><b>把"未采集"伪装成"确认非妊娠"</b>——过期回落到更早 NEITHER 行的陷阱；</li>
 *   <li><b>warn 档静默</b>——记了留痕却没把话给到医生，与 off 无异。</li>
 * </ol>
 * 规则数据一律测试内自建，<b>不依赖种子</b>：种子只有一条 enabled=false 的示例行，
 * 真实规则内容属药学知识、由药剂科维护。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V51PopulationRuleTest {

    @Autowired PopulationRuleService service;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private static final String DRUG = "特殊人群测试药A";
    private static final String ITEM = "TESTCREA51";

    @AfterEach
    void restoreGate() {
        // ConfigReader 有 30 秒缓存：测试改过 sys_config 后必须失效，否则脏值会漏进下一个用例。
        setGate("warn");
    }

    // ---------- 夹具 ----------

    private Long patient() {
        Patient p = new Patient();
        p.setName("特殊人群测试");
        p.setSex("F");
        p.setBirthDate(BusinessDates.today().minusYears(28));
        return patientService.register(p).getId();
    }

    private Long registration(Long patientId) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        return registrationService.register(patientId, s.getId()).getId();
    }

    private void setGate(String v) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?",
                v, PopulationRuleService.GATE_KEY);
        configReader.evict(PopulationRuleService.GATE_KEY);
    }

    private Long pregnancyRule(String severity) {
        return service.addRule(new RuleReq(DRUG, "PREGNANCY", severity,
                null, null, null, null, null,
                "某说明书 2024 版 第 3 节", "禁用", "妊娠期禁用，可致胎儿损害"), 1L);
    }

    /** 肾功能规则：项目 ITEM 高于阈值即命中。阈值与单位由"药剂科"配，引擎只比大小。 */
    private Long renalRule(BigDecimal threshold, String unit, int maxAgeDays) {
        return service.addRule(new RuleReq(DRUG, "RENAL", "CAUTION",
                ITEM, "GT", threshold, unit, maxAgeDays,
                "院内用药目录 2025", "慎用/需减量", "肾功能不全须减量"), 1L);
    }

    /** 检验结果落库：走真实可达路径 outp_lab_result -> outp_order -> outp_registration -> 患者。 */
    private void labResult(Long registrationId, String value, String unit, Instant at) {
        jdbc.update("""
                insert into outp_order (registration_id, group_no, order_type, item_id, item_code,
                                        item_name, unit, qty, unit_price, amount, doctor_id)
                values (?, 'G51', 'LAB', 1, 'LAB51', '肾功能', '次', 1, 0, 0, 1)
                """, registrationId);
        Long orderId = jdbc.queryForObject(
                "select max(id) from outp_order where registration_id = ?", Long.class, registrationId);
        jdbc.update("""
                insert into outp_lab_result (order_id, item_code, item_name, result_value, unit, created_at)
                values (?, ?, '血肌酐', ?, ?, ?)
                """, orderId, ITEM, value, unit, Timestamp.from(at.truncatedTo(ChronoUnit.MICROS)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> notices(Map<String, Object> body) {
        return (List<String>) body.get("notices");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hits(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("hits");
    }

    @SuppressWarnings("unchecked")
    private static List<String> warnings(Map<String, Object> body) {
        return (List<String>) body.get("warnings");
    }

    // ==================================================================
    // 一、"不知道" 必须与 "没问题" 区分开
    // ==================================================================

    /**
     * 状态未采集 → 妊娠规则整类不评估，且<b>必须明说未评估</b>。
     * 若这里静默返回空 hits，医生会把"没提示"读成"这药对她安全"——这正是本引擎要避免的形态。
     */
    @Test
    void unknownPregnancyStatusSuppressesRuleAndSaysSoOutLoud() {
        Long pid = patient();
        pregnancyRule("FORBID");

        var body = service.evaluate(null, pid, List.of(DRUG));

        assertTrue(hits(body).isEmpty(), "状态未采集时不得凭空判定妊娠规则命中");
        assertEquals("UNKNOWN", ((Map<?, ?>) body.get("status")).get("status"));
        assertTrue(notices(body).stream().anyMatch(n -> n.contains("未采集") && n.contains("未评估")),
                "未采集必须出提示，实际 notices=" + notices(body));
    }

    /**
     * NEITHER（问过了、不是妊娠/哺乳）→ 静默放行，且<b>不</b>出"未评估"提示。
     * 这正是 NEITHER 必须能记的理由：不能记就只剩"未采集"，于是每张处方都挂一条废话提示，
     * 医生很快连真提示一起不看。
     */
    @Test
    void neitherPassesSilentlyWithoutNoise() {
        Long pid = patient();
        pregnancyRule("FORBID");
        service.assertStatus(pid, "NEITHER", "CLINICIAN_CONFIRMED", null, "已问诊否认", 1L);

        var body = service.evaluate(null, pid, List.of(DRUG));

        assertTrue(hits(body).isEmpty());
        assertEquals("NEITHER", ((Map<?, ?>) body.get("status")).get("status"));
        assertTrue(notices(body).stream().noneMatch(n -> n.contains("未评估")),
                "NEITHER 是已知结论，不该再提示未评估：" + notices(body));
    }

    /**
     * <b>本类最重要的一条。</b> 患者先申报 NEITHER（无失效日、永久有效），后申报 PREGNANT（有失效日）。
     * PREGNANT 过期后，若"取最新<b>未过期</b>行"，就会回落到那条更早的 NEITHER，
     * 于是"未采集"被伪装成"确认非妊娠"，妊娠规则从此静默失效、永不再响。
     * 正确语义：最新那条一旦过期，整个维度回到 UNKNOWN。
     */
    @Test
    void expiredPregnancyMustNotFallBackToOlderNeither() {
        Long pid = patient();
        pregnancyRule("FORBID");

        service.assertStatus(pid, "NEITHER", "CLINICIAN_CONFIRMED", null, "首诊否认", 1L);
        Long pregId = service.assertStatus(pid, "PREGNANT", "LAB_CONFIRMED",
                BusinessDates.today().plusDays(30), "孕早期", 1L);
        // 让那条 PREGNANT 过期（仅测试内制造既成事实；服务层入口禁止写入过去的失效日）
        jdbc.update("update cdss_population_status set valid_until = ? where id = ?",
                java.sql.Date.valueOf(BusinessDates.today().minusDays(1)), pregId);

        var status = service.currentStatus(pid);
        assertEquals("UNKNOWN", status.get("status"),
                "过期的 PREGNANT 必须回落 UNKNOWN，绝不能回落到更早的 NEITHER");
        assertEquals("PREGNANT", status.get("expiredStatus"));

        var body = service.evaluate(null, pid, List.of(DRUG));
        assertTrue(notices(body).stream().anyMatch(n -> n.contains("未评估")),
                "回落 UNKNOWN 后必须提示未评估，而不是静默放行：" + notices(body));
    }

    /**
     * 非数值型结果（{@code >200}）→ 不评估并提示，<b>不做区间推测</b>。
     * ">200" 对 {@code GT 177} 是真命中、对 {@code LT 30} 是确定不命中，
     * 猜一个代表值必然在某个比较方向上错。
     */
    @Test
    void nonNumericLabResultIsNotGuessed() {
        Long pid = patient();
        Long rid = registration(pid);
        renalRule(new BigDecimal("177"), "μmol/L", 90);
        labResult(rid, ">200", "μmol/L", Instant.now());

        var body = service.evaluate(rid, pid, List.of(DRUG));

        assertTrue(hits(body).isEmpty(), "非数值结果不得被猜成命中");
        assertTrue(notices(body).stream().anyMatch(n -> n.contains("非数值") && n.contains(">200")),
                "非数值必须出提示：" + notices(body));
    }

    /**
     * 单位不一致 → 跳过该规则并提示，<b>绝不换算</b>。
     * 2.4 mg/dL 约合 212 μmol/L（高于阈值 177），但引擎不做换算：静默按 2.4 比 177
     * 会判成"正常"，把该减量的患者放过去——"看起来在保护、实际什么都没拦"的最坏形态。
     */
    @Test
    void unitMismatchSkipsRuleInsteadOfSilentlyComparing() {
        Long pid = patient();
        Long rid = registration(pid);
        renalRule(new BigDecimal("177"), "μmol/L", 90);
        labResult(rid, "2.4", "mg/dL", Instant.now());

        var body = service.evaluate(rid, pid, List.of(DRUG));

        assertTrue(hits(body).isEmpty(), "单位不一致时不得直接比数");
        assertTrue(notices(body).stream().anyMatch(n -> n.contains("单位") && n.contains("不做单位换算")),
                "单位不一致必须出提示：" + notices(body));
    }

    /** 超出可用天数的旧结果视为无数据，不外推：三年前的肌酐不能代表今天的肾功能。 */
    @Test
    void labResultOutsideMaxAgeWindowIsTreatedAsMissing() {
        Long pid = patient();
        Long rid = registration(pid);
        renalRule(new BigDecimal("177"), "μmol/L", 30);
        labResult(rid, "300", "μmol/L", Instant.now().minus(60, ChronoUnit.DAYS));

        var body = service.evaluate(rid, pid, List.of(DRUG));

        assertTrue(hits(body).isEmpty(), "超期结果不得参与判定");
        assertTrue(notices(body).stream().anyMatch(n -> n.contains("无项目")),
                "超期应按缺数据提示：" + notices(body));
    }

    /** 正例：数值、单位、时间窗都对得上时确实命中，且证据里带项目、数值、报告时间。 */
    @Test
    void renalRuleHitsCarriesEvidenceAndBasis() {
        Long pid = patient();
        Long rid = registration(pid);
        renalRule(new BigDecimal("177"), "μmol/L", 90);
        labResult(rid, "300", "μmol/L", Instant.now());

        var body = service.evaluate(rid, pid, List.of(DRUG));

        assertEquals(1, hits(body).size(), "notices=" + notices(body));
        var h = hits(body).get(0);
        assertEquals("RENAL", h.get("population"));
        // 妊娠/肝肾警告的假阳性代价极高，命中必须回答"凭什么"：依据出处 + 级别 + 证据。
        assertEquals("院内用药目录 2025", h.get("basisSource"));
        assertEquals("慎用/需减量", h.get("basisLevel"));
        assertTrue(String.valueOf(h.get("evidence")).contains("300"),
                "证据须含实测值：" + h.get("evidence"));
        assertTrue(String.valueOf(h.get("text")).contains("院内用药目录 2025"),
                "给医生看的那句话必须自带依据：" + h.get("text"));
    }

    // ==================================================================
    // 二、gate 三态
    // ==================================================================

    /** warn（默认）：不拦截，但<b>必须</b>把话给到医生（warnings 非空）并留痕。静默的 warn 等于 off。 */
    @Test
    void warnGateDoesNotBlockButStillSpeaksAndRecords() {
        Long pid = patient();
        Long rid = registration(pid);
        setGate("warn");
        pregnancyRule("FORBID");
        service.assertStatus(pid, "PREGNANT", "LAB_CONFIRMED", BusinessDates.today().plusDays(30), null, 1L);

        var body = assertDoesNotThrow(() -> service.checkPrescription(rid, pid, List.of(DRUG)));

        assertEquals(1, hits(body).size());
        assertFalse(warnings(body).isEmpty(), "warn 档必须回带 warnings，否则与 off 无异");
        assertTrue(warnings(body).get(0).contains("妊娠期"));
        Integer n = jdbc.queryForObject("""
                select count(*) from cdss_population_alert
                 where patient_id = ? and gate = 'warn' and blocked = false
                """, Integer.class, pid);
        assertEquals(1, n, "warn 档命中须留痕且 blocked=false");
    }

    /** block：FORBID 抛本车道码 5640，且异常消息自带依据——block 档留痕会随事务回滚，消息是唯一载体。 */
    @Test
    void blockGateThrowsLaneCodeWithBasisInMessage() {
        Long pid = patient();
        Long rid = registration(pid);
        setGate("block");
        pregnancyRule("FORBID");
        service.assertStatus(pid, "PREGNANT", "LAB_CONFIRMED", BusinessDates.today().plusDays(30), null, 1L);

        var e = assertThrows(BizException.class, () -> service.checkPrescription(rid, pid, List.of(DRUG)));

        assertEquals(5640, e.code, "妊娠维度应为 5640");
        assertTrue(e.getMessage().contains("某说明书 2024 版 第 3 节"),
                "block 的留痕会随事务回滚，异常消息必须自带依据：" + e.getMessage());
    }

    /** CAUTION 在 block 档也不拦——block 只升级 FORBID，不把"慎用"变成"禁用"。 */
    @Test
    void blockGateStillDoesNotBlockCautionRules() {
        Long pid = patient();
        Long rid = registration(pid);
        setGate("block");
        pregnancyRule("CAUTION");
        service.assertStatus(pid, "PREGNANT", "SELF_REPORT", BusinessDates.today().plusDays(30), null, 1L);

        var body = assertDoesNotThrow(() -> service.checkPrescription(rid, pid, List.of(DRUG)));
        assertEquals(1, hits(body).size());
    }

    /**
     * 坏配置回落 <b>warn</b>，不是 off。把 'blocked'/'true'/'1' 之类笔误当 off，
     * 等于让一个拼写错误静默关掉临床校验。
     */
    @Test
    void badGateValueFallsBackToWarnNotOff() {
        setGate("blocked");   // 典型笔误
        assertEquals("warn", service.gate());
        setGate("TRUE");
        assertEquals("warn", service.gate());
        setGate("BLOCK");     // 大小写应被接受
        assertEquals("block", service.gate());
    }

    /** off：整体旁路，但要在返回体里说清楚"本次没评估"，而不是假装评估过且没问题。 */
    @Test
    void offGateSaysItBypassedRatherThanReportingAllClear() {
        Long pid = patient();
        setGate("off");
        pregnancyRule("FORBID");
        service.assertStatus(pid, "PREGNANT", "LAB_CONFIRMED", BusinessDates.today().plusDays(30), null, 1L);

        var body = service.evaluate(null, pid, List.of(DRUG));
        assertTrue(hits(body).isEmpty());
        assertTrue(notices(body).stream().anyMatch(n -> n.contains("旁路")), "" + notices(body));
    }

    // ==================================================================
    // 三、规则与状态的入口校验
    // ==================================================================

    /** 无依据的规则不许入库：只说"孕妇慎用"而不给出处，医生无从判断该不该采纳。 */
    @Test
    void ruleWithoutBasisIsRejected() {
        var e = assertThrows(BizException.class, () -> service.addRule(new RuleReq(
                DRUG, "PREGNANCY", "FORBID", null, null, null, null, null,
                "  ", "禁用", "孕妇禁用"), 1L));
        assertEquals(5651, e.code);
    }

    /** 肝肾规则缺 labUnit → 5652。缺单位就无法核对可比性，而本引擎不换算。 */
    @Test
    void hepaticRuleWithoutUnitIsRejected() {
        var e = assertThrows(BizException.class, () -> service.addRule(new RuleReq(
                DRUG, "HEPATIC", "CAUTION", "ALT", "GT", new BigDecimal("80"), null, null,
                "说明书", "慎用", "肝功能不全慎用"), 1L));
        assertEquals(5652, e.code);
    }

    /** 妊娠规则不得夹带检验条件 → 5653。条件写一半的规则要么误判要么静默跳过。 */
    @Test
    void pregnancyRuleCarryingLabConditionIsRejected() {
        var e = assertThrows(BizException.class, () -> service.addRule(new RuleReq(
                DRUG, "PREGNANCY", "FORBID", "ALT", "GT", new BigDecimal("80"), "U/L", null,
                "说明书", "禁用", "妊娠禁用"), 1L));
        assertEquals(5653, e.code);
    }

    /**
     * PREGNANT / LACTATING 必须给失效日 → 5645。
     * 无失效日的妊娠标记会在产后继续拦该患者的所有处方，且没人会想起来清它：
     * 一次录入、永久误拦，比不记更糟。
     */
    @Test
    void pregnantStatusRequiresValidUntil() {
        Long pid = patient();
        var e = assertThrows(BizException.class,
                () -> service.assertStatus(pid, "PREGNANT", "SELF_REPORT", null, null, 1L));
        assertEquals(5645, e.code);
    }

    /** 失效日早于业务今天 → 5646（用 BusinessDates 的"今天"，不是 JVM 默认时区的今天）。 */
    @Test
    void pastValidUntilIsRejected() {
        Long pid = patient();
        var e = assertThrows(BizException.class, () -> service.assertStatus(
                pid, "PREGNANT", "SELF_REPORT", BusinessDates.today().minusDays(1), null, 1L));
        assertEquals(5646, e.code);
    }

    /** 软撤销后该行不再参与判定，但历史留痕仍查得到。 */
    @Test
    void revokedStatusStopsCountingButStaysVisible() {
        Long pid = patient();
        Long sid = service.assertStatus(pid, "PREGNANT", "SELF_REPORT",
                BusinessDates.today().plusDays(30), null, 1L);
        service.revokeStatus(sid, "录错患者", 1L);

        assertEquals("UNKNOWN", service.currentStatus(pid).get("status"));
        assertEquals(1, service.statusHistory(pid, null).size(), "撤销是软撤销，历史行不删");
    }

    // ==================================================================
    // 四、种子边界
    // ==================================================================

    /**
     * V154 的示例行必须是<b>惰性</b>的：enabled=false，且关键字/项目代码/阈值三重保险
     * 保证即便被误启用也命中不了真实药品。编出来的药学规则会被医生当真，宁可空表。
     */
    @Test
    void seededExampleRuleIsDisabledAndCannotMatchRealDrugs() {
        var rows = jdbc.queryForList(
                "select * from cdss_population_rule where drug_keyword like '\\_\\_示例%'");
        assertEquals(1, rows.size(), "种子应恰好一条示例行");
        assertEquals(false, rows.get(0).get("enabled"), "示例行必须 enabled=false");
        // 启用中的规则集里不该有它
        assertTrue(service.listRules(null, true, 200).stream()
                        .noneMatch(r -> String.valueOf(r.get("drug_keyword")).startsWith("__示例")),
                "示例行不得出现在启用规则里");
    }

    /** 状态与规则齐备但药名对不上时，不得命中（contains 口径与既有三张规则表一致）。 */
    @Test
    void unrelatedDrugDoesNotHit() {
        Long pid = patient();
        pregnancyRule("FORBID");
        service.assertStatus(pid, "PREGNANT", "LAB_CONFIRMED", BusinessDates.today().plusDays(30), null, 1L);

        var body = service.evaluate(null, pid, List.of("完全无关的另一种药"));
        assertTrue(hits(body).isEmpty());
        assertTrue(notices(body).stream().noneMatch(n -> n.contains("未评估")),
                "与本次用药无关的维度不该刷提示：" + notices(body));
    }

    /** 申报时刻回读走 toInstant()（不是 toLocalDateTime()），UTC 与业务时区下都应是"今天刚申报"。 */
    @Test
    void assertedAtRoundTripsWithoutTimezoneDrift() {
        Long pid = patient();
        service.assertStatus(pid, "PREGNANT", "LAB_CONFIRMED", BusinessDates.today().plusDays(30), null, 1L);
        var status = service.currentStatus(pid);
        assertEquals(0L, ((Number) status.get("assertedDaysAgo")).longValue(),
                "刚申报的状态不该因时区换算变成昨天/明天");
        assertEquals(Boolean.FALSE, status.get("stale"));
    }

    /** 同一药命中多条引用同一检验项目的规则时，"无结果"提示只出一次——提示刷屏本身就让人不看提示。 */
    @Test
    void repeatedMissingLabNoticeIsDeduplicated() {
        Long pid = patient();
        Long rid = registration(pid);
        renalRule(new BigDecimal("177"), "μmol/L", 90);
        renalRule(new BigDecimal("265"), "μmol/L", 90);

        var body = service.evaluate(rid, pid, List.of(DRUG));
        long missing = notices(body).stream().filter(n -> n.contains("无项目")).count();
        assertEquals(1, missing, "同一项目的缺数据提示应去重：" + notices(body));
    }
}
