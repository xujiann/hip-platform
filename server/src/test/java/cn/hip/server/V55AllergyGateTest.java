package cn.hip.server;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.AllergyRuleService;
import cn.hip.outpatient.service.AllergyRuleService.AllergyInput;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v55 车道 R3（偏离表 217★）：过敏关键词闸收敛——纳入 {@code cdss.gate.allergy} 管辖、让位给结构化引擎。
 *
 * <p>被核账坐实的缺陷：{@code DoctorStationService.checkRationalDrugUse} 的自由文本关键词闸
 * 排在 v51 结构化过敏引擎之前执行、命中即 4012 回滚整单，且不受 gate 任何档位管辖。
 * 于是结构化引擎对「原文含青霉素/头孢/磺胺/阿司匹林」的患者根本轮不到执行，
 * 而 gate=off 对过敏审查是假的。
 *
 * <p>本测试钉住收敛后的四条口径，每条都对应一种「松动就会出事」的方向：
 * <ol>
 *   <li><b>出厂态不裸奔</b>——结构化目录为空、原文未核对时，关键词闸仍抛既有 4012（§1）；</li>
 *   <li><b>三档管辖</b>——off 让路；warn/block 在覆盖不可信时照旧硬拦；坏配置回落 warn 仍值守（§2）；</li>
 *   <li><b>结构化覆盖可信时以结构化判定为准</b>——人工核对为无过敏则放行，
 *       核对并映射后命中则走 5612/warnings，而不是 4012（§3）；</li>
 *   <li><b>让路是可撤销的</b>——原文一改、映射缺失、结论 UNCLEAR，闸都重新值守（§4）。</li>
 * </ol>
 * 既有契约（RationalDrugRulesTest 与 V51CdssTest 钉的 4012、V51AllergyRuleTest 钉的 5612）
 * 本文件一条不改、一条不重写，只补它们之间的接缝。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V55AllergyGateTest {

    @Autowired DoctorStationService doctorStationService;
    @Autowired AllergyRuleService allergyService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private static final Long OPERATOR = 1L;   // V1 种子管理员

    /** 关键词表命中的典型原文：含「青霉素」，而阿莫西林含「西林」——两个条件都成立，闸值守时必拦 */
    private static final String PCN_TEXT = "青霉素过敏";

    @AfterEach
    void resetGateCache() {
        // sys_config 随事务回滚，但 ConfigReader 有 30 秒缓存；不清会把档位漏给下一个测试
        configReader.evict(AllergyRuleService.GATE_KEY);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private Long patient(String allergyHistory) {
        Patient p = new Patient();
        p.setName("v55过敏闸");
        p.setSex("U");
        p.setAllergyHistory(allergyHistory);
        return patientService.register(p).getId();
    }

    /** 已接诊的挂号。每次新建排班：重复挂号拦截（3002）按「同一号源」判，同患者可再挂新号源 */
    private Long visitedRegistration(Long patientId) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(patientId, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    private Long drugId(String keyword) {
        return drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(keyword).get(0).getId();
    }

    private void setGate(String value) {
        jdbc.update("""
                insert into sys_config(cfg_key, cfg_value) values (?, ?)
                on conflict (cfg_key) do update set cfg_value = excluded.cfg_value
                """, AllergyRuleService.GATE_KEY, value);
        configReader.evict(AllergyRuleService.GATE_KEY);
    }

    /** 过敏原编码唯一约束：用序列号而不是时间戳做后缀，不引入任何墙钟字面量 */
    private String uniqCode() {
        return "V55-" + jdbc.queryForObject("select nextval('outp_order_group_seq')", Long.class);
    }

    private Long allergen(String name) {
        return (Long) allergyService.addAllergen(uniqCode(), name, "DRUG", "INGREDIENT", null, OPERATOR).get("id");
    }

    /** 开一行药：返回 0 = 放行，否则为 BizException 码 */
    private int orderCode(Long rid, Long drugId) {
        try {
            order(rid, drugId);
            return 0;
        } catch (BizException e) {
            return e.code;
        }
    }

    private List<OutpOrder> order(Long rid, Long drugId) {
        return doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "tid", "1粒", 3)), OPERATOR);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> coverage(Long patientId) {
        return (Map<String, Object>) allergyService.patientProfile(patientId).get("coverage");
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // ==================================================================
    // §1 出厂态：结构化目录为空时关键词闸仍然生效
    // ==================================================================

    /**
     * V152 零回填：任何带原文的患者在出厂态都是「原文未核对、结构化过敏原 0 条」，
     * 此时收敛<b>一个字节都不改</b>行为——默认档（warn）下关键词闸照旧抛 4012。
     * 这条是「不许直接删闸」的机械化表达：删了闸，这里就是 0。
     */
    @Test
    void factoryStateKeywordGateStillBlocks() {
        Long pid = patient(PCN_TEXT);
        var cov = coverage(pid);
        assertEquals(Boolean.TRUE, cov.get("unreviewedText"), "出厂态：原文必然未核对");
        assertEquals(0, ((Number) cov.get("structuredActiveCount")).intValue(), "出厂态：结构化过敏原为 0");
        assertEquals(Boolean.FALSE, cov.get("trustworthy"), "出厂态：结构化引擎自认覆盖不可信");

        assertEquals(4012, orderCode(visitedRegistration(pid), drugId("阿莫西林")),
                "结构化目录为空、原文未核对——关键词闸必须继续值守，否则出厂态裸奔");
    }

    // ==================================================================
    // §2 三档管辖
    // ==================================================================

    /** off：关键词闸让路。结构化引擎在 off 档照样留痕不拦（V51 已钉），闸从此与它同进退 */
    @Test
    void gateOffMakesKeywordGateYield() {
        Long pid = patient(PCN_TEXT);
        Long rid = visitedRegistration(pid);
        setGate("off");

        assertEquals(0, orderCode(rid, drugId("阿莫西林")),
                "gate=off 下关键词闸仍抛 4012——「off」对过敏审查就是假的，管理员关不掉它");
        assertEquals(1, count("select count(*) from cdss_allergy_gate_log where patient_id = ? and gate = 'off'", pid),
                "闸让路不等于什么都没发生：结构化引擎在 off 档必须仍留一行台账（旁路的是拦不拦，不是记不记）");
    }

    /** warn（默认）：覆盖不可信时关键词闸照旧硬拦——它是出厂态唯一防线，在这里降成提示等于撤防 */
    @Test
    void gateWarnKeepsKeywordGateArmedWhenStructuredCannotCover() {
        Long pid = patient(PCN_TEXT);
        setGate("warn");
        assertEquals(4012, orderCode(visitedRegistration(pid), drugId("阿莫西林")));
    }

    /** block：结构化目录里什么都没有时，拦下来的仍是关键词闸（4012），不是结构化引擎（5612） */
    @Test
    void gateBlockKeepsKeywordGateArmedWhenStructuredCannotCover() {
        Long pid = patient(PCN_TEXT);
        setGate("block");
        assertEquals(4012, orderCode(visitedRegistration(pid), drugId("阿莫西林")),
                "block 档、结构化无任何映射：能拦住的只有关键词闸，它必须还在");
    }

    /** 坏配置回落 warn（不是 off）：一个笔误不能同时静默关掉两道闸 */
    @Test
    void badGateValueFallsBackToWarnAndKeepsKeywordGateArmed() {
        Long pid = patient(PCN_TEXT);
        setGate("blocked");     // 典型笔误
        assertEquals("warn", allergyService.gate());
        assertEquals(4012, orderCode(visitedRegistration(pid), drugId("阿莫西林")),
                "坏配置必须回落 warn 并让关键词闸继续值守；把它当 off 会让一个笔误关掉全院过敏拦截");
    }

    /** off 只旁路过敏，同诊重复用药 4013 不归过敏 gate 管 */
    @Test
    void gateOffDoesNotDisarmDuplicateRxCheck() {
        Long pid = patient(PCN_TEXT);
        Long rid = visitedRegistration(pid);
        Long ibu = drugId("布洛芬");
        setGate("off");

        assertEquals(0, orderCode(rid, ibu));
        assertEquals(4013, orderCode(rid, ibu), "重复用药拦截与过敏 gate 无关，off 档必须照常 4013");
    }

    // ==================================================================
    // §3 结构化覆盖可信时以结构化判定为准
    // ==================================================================

    /**
     * 人工核对结论为「无过敏」：原文「青霉素过敏（家族史）」含关键词，但护士逐字读过并签了字——
     * 患者本人不过敏。此时关键词闸必须让路，<b>即便 gate=block</b>（结构化无命中 → 放行）。
     * 这正是 v51 那条「不做半吊子 NLP，让人来判断」的兑现：人判过的，机器不再靠猜否决。
     */
    @Test
    void humanReviewedNoAllergyDisarmsKeywordGate() {
        String text = "青霉素过敏（家族史）";
        Long pid = patient(text);
        Long amox = drugId("阿莫西林");
        // 让路前先证明闸确实会拦：同一原文在核对前是 4012
        assertEquals(4012, orderCode(visitedRegistration(pid), amox), "核对前关键词闸应值守");

        allergyService.reviewText(pid, text, "NO_ALLERGY", null, "家族史，患者本人否认既往用药反应", OPERATOR);
        assertEquals(Boolean.TRUE, coverage(pid).get("trustworthy"), "已核对且无过敏原 → 覆盖可信");

        setGate("block");
        assertEquals(0, orderCode(visitedRegistration(pid), amox),
                "人工核对为无过敏后，关键词闸仍按原文里的「青霉素」三字硬拦——人判过的被机器猜否决了");
    }

    /**
     * 核对并登记了结构化过敏原、且映射到本次所开药：拦下来的必须是<b>结构化引擎的 5612</b>，
     * 不是关键词闸的 4012。两个码分得开，才证明说了算的换成了结构化路径。
     */
    @Test
    void structuredHitDecidesUnderBlock() {
        Long pid = patient(PCN_TEXT);
        Long amox = drugId("阿莫西林");
        Long ag = allergen("v55青霉素");
        allergyService.mapDrug(ag, amox, "INGREDIENT", "青霉素母核", OPERATOR);
        allergyService.reviewText(pid, PCN_TEXT, "STRUCTURED",
                List.of(new AllergyInput(ag, "SEVERE", "皮疹", null, null)), null, OPERATOR);
        assertEquals(Boolean.TRUE, coverage(pid).get("trustworthy"));

        setGate("block");
        assertEquals(5612, orderCode(visitedRegistration(pid), amox),
                "覆盖可信 + block：应由结构化引擎以 5612 拦截；返回 4012 说明关键词闸仍抢在引擎前面");
    }

    /**
     * 同一患者在 warn 档：放行，但结构化引擎的提示必须真的到医生手里——
     * 随返回体的 cdssWarnings 里有它、cdss_alert 里有它。
     * 这条在收敛前<b>永远不可能出现</b>：关键词闸在 warn 档也是 4012 硬拦，引擎没机会出声。
     */
    @Test
    void structuredHitDecidesUnderWarnAndTheWarningReachesTheDoctor() {
        Long pid = patient(PCN_TEXT);
        Long amox = drugId("阿莫西林");
        Long ag = allergen("v55青霉素W");
        allergyService.mapDrug(ag, amox, "INGREDIENT", null, OPERATOR);
        allergyService.reviewText(pid, PCN_TEXT, "STRUCTURED",
                List.of(new AllergyInput(ag, "MODERATE", "荨麻疹", null, null)), null, OPERATOR);
        Long rid = visitedRegistration(pid);
        setGate("warn");

        List<OutpOrder> created = assertDoesNotThrow(() -> order(rid, amox),
                "覆盖可信 + warn：应放行并回带提示，而不是 4012");
        assertEquals(1, created.size());
        List<String> warnings = created.get(0).getCdssWarnings();
        assertNotNull(warnings, "warn 档结构化命中必须随返回体下发 warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("v55青霉素W")),
                "warnings 里必须有结构化引擎的命中提示（带过敏原名）：" + warnings);
        assertEquals(1, count("select count(*) from cdss_alert where registration_id = ? and rule_type = 'ALLERGY'", rid),
                "warn 档命中必须落 cdss_alert，否则提示只活在一次 HTTP 响应里");
    }

    // ==================================================================
    // §4 让路是可撤销的：覆盖一旦不可信，闸立刻重新值守
    // ==================================================================

    /** 登记了结构化过敏原但一条药品映射都没有：结构化永远不会命中 → 覆盖不可信 → 关键词闸兜底 */
    @Test
    void unmappedAllergenKeepsKeywordGateArmed() {
        Long pid = patient(PCN_TEXT);
        Long ag = allergen("v55无映射过敏原");
        allergyService.reviewText(pid, PCN_TEXT, "STRUCTURED",
                List.of(new AllergyInput(ag, "UNKNOWN", null, null, null)), null, OPERATOR);
        assertEquals(Boolean.FALSE, coverage(pid).get("trustworthy"), "有未映射过敏原 → 不可信");

        setGate("block");
        assertEquals(4012, orderCode(visitedRegistration(pid), drugId("阿莫西林")),
                "过敏原没有映射时结构化引擎拦不住任何药，关键词闸必须继续兜底");
    }

    /**
     * <b>D1（v55 独立复核实测）：映射不全时，未映射的同类药不得静默放行。</b>
     *
     * <p>复现链：患者原文「青霉素过敏」→ 药剂科只把「阿莫西林」映射到过敏原 → 人工核对 STRUCTURED
     * → 患者级 {@code coverage.trustworthy=true}。此时开一支<b>未映射</b>的氨苄西林（同为青霉素类）：
     * 引擎查不到映射所以不命中，而患者级让路又已把关键词闸撤了——<b>静默放行，比收敛前更松</b>。
     * 关键词闸原本按药名族「西林」覆盖整类，引擎只命中显式映射的药；
     * 让路的前提必须是「这一行引擎必定会说话」，患者级判据保证不了。
     *
     * <p>本用例故意让两支药命运不同：已映射的走引擎（warn 放行 + 提示），
     * 未映射的仍由关键词闸 4012 拦下。若有人把裁决改回患者级，第二个断言立刻变红。
     */
    @Test
    void unmappedSameClassDrugKeepsKeywordGateArmedEvenWhenCoverageTrustworthy() {
        Long pid = patient(PCN_TEXT);
        Long amox = drugId("阿莫西林");
        // 一味未映射的同类药。名字含「西林」，关键词闸值守时必拦；md_drug 非空列只有 code/name/unit/price
        Long ampicillin = jdbc.queryForObject(
                "insert into md_drug(code, name, unit, price, stock) values (?, ?, '盒', 10.00, 100) returning id",
                Long.class, uniqCode(), "氨苄西林胶囊(v55未映射)");
        Long ag = allergen("v55青霉素D1");
        allergyService.mapDrug(ag, amox, "INGREDIENT", "青霉素母核", OPERATOR);   // **只**映射阿莫西林
        allergyService.reviewText(pid, PCN_TEXT, "STRUCTURED",
                List.of(new AllergyInput(ag, "SEVERE", "皮疹", null, null)), null, OPERATOR);
        assertEquals(Boolean.TRUE, coverage(pid).get("trustworthy"),
                "只映射一支药，患者级覆盖仍算可信——这正是 D1 漏洞的前提");

        setGate("warn");
        assertEquals(0, orderCode(visitedRegistration(pid), amox),
                "已映射的阿莫西林在 warn 档应由引擎放行并提示（不是 4012）");
        assertEquals(4012, orderCode(visitedRegistration(pid), ampicillin),
                "未映射的氨苄西林：引擎不命中、关键词闸必须继续值守——"
                + "映射不全时让路等于对整类药撤防，那比收敛前更松");

        setGate("block");
        assertEquals(4012, orderCode(visitedRegistration(pid), ampicillin),
                "block 档同理：未映射药仍由关键词闸 4012 拦下");
    }

    /** 核对结论 UNCLEAR：人看过但说不准——不等于无过敏，闸不能让路 */
    @Test
    void unclearReviewKeepsKeywordGateArmed() {
        Long pid = patient(PCN_TEXT);
        allergyService.reviewText(pid, PCN_TEXT, "UNCLEAR", null, "患者说不清是哪种药", OPERATOR);
        assertEquals(Boolean.FALSE, coverage(pid).get("trustworthy"), "UNCLEAR 不算可信覆盖");

        assertEquals(4012, orderCode(visitedRegistration(pid), drugId("阿莫西林")),
                "「看过了还说不准」的患者恰恰最需要那道闸");
    }

    /**
     * 原文被修订后核对记录对不上（source_text 逐字比对）→ 患者自动重回「未核对」→ 闸重新值守。
     * 没有这条，「核对过一次就永远放行」会让医生后来补写的过敏史永远进不了任何审查。
     */
    @Test
    void editedAllergyTextReturnsPatientToUnreviewedAndRearmsKeywordGate() {
        String text = "青霉素过敏（家族史）";
        Long pid = patient(text);
        Long amox = drugId("阿莫西林");
        allergyService.reviewText(pid, text, "NO_ALLERGY", null, null, OPERATOR);
        assertEquals(0, orderCode(visitedRegistration(pid), amox), "核对为无过敏 → 放行");

        // 医生随后把过敏史改成了本人过敏。jdbc 直写后清一级缓存，否则 JPA 侧仍持旧快照
        jdbc.update("update empi_patient set allergy_history = ? where id = ?", "青霉素过敏，皮疹", pid);
        entityManager.flush();
        entityManager.clear();
        assertEquals(Boolean.TRUE, coverage(pid).get("unreviewedText"), "原文改了，旧核对记录必须对不上");

        assertEquals(4012, orderCode(visitedRegistration(pid), amox),
                "原文修订后关键词闸必须重新值守——让路只对「当时读过的那段原文」成立");
    }
}
