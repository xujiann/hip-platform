package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.AllergyRuleService;
import cn.hip.outpatient.service.AllergyRuleService.AllergyInput;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
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
 * v51 车道 A：CDSS 过敏规则。
 *
 * <p>本测试的重点不是「跑通接口」，是钉住四条<b>一旦松动就会出人命</b>的口径：
 * <ol>
 *   <li>结构化过敏原能命中开单（此前系统对过敏完全无感）；</li>
 *   <li><b>交叉命中在 block 档也不拦</b>——防的是假阳性泛滥把真警告一起废掉；</li>
 *   <li><b>覆盖不全时必须明说</b>——「没查到过敏」不等于「没有过敏」；</li>
 *   <li><b>自由文本零自动解析、原文零覆盖</b>——「皮试阴性」不得被当成过敏。</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V51AllergyRuleTest {

    @Autowired AllergyRuleService allergyService;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private static final Long OPERATOR = 1L;   // V1 种子管理员

    @AfterEach
    void resetGateCache() {
        // sys_config 的改动随事务回滚，但 ConfigReader 有 30 秒缓存，
        // 不清掉会把 block 档漏给下一个测试（本仓已有 gate 类不稳定测试的先例）
        configReader.evict(AllergyRuleService.GATE_KEY);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private Long patient(String allergyHistory) {
        Patient p = new Patient();
        p.setName("过敏测试");
        p.setSex("U");
        p.setAllergyHistory(allergyHistory);
        return patientService.register(p).getId();
    }

    private Long drugId(String keyword) {
        return drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(keyword).get(0).getId();
    }

    private Long allergen(String code, String name) {
        return (Long) allergyService.addAllergen(code, name, "DRUG", "INGREDIENT", null, OPERATOR).get("id");
    }

    private Long group(String code, String name) {
        return (Long) allergyService.addGroup(code, name, null, OPERATOR).get("id");
    }

    private void setGate(String value) {
        jdbc.update("""
                insert into sys_config(cfg_key, cfg_value) values (?, ?)
                on conflict (cfg_key) do update set cfg_value = excluded.cfg_value
                """, AllergyRuleService.GATE_KEY, value);
        configReader.evict(AllergyRuleService.GATE_KEY);
    }

    private Long registration(Long patientId) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        return registrationService.register(patientId, s.getId()).getId();
    }

    @SuppressWarnings("unchecked")
    private List<String> warnings(Map<String, Object> r) {
        return (List<String>) r.get("warnings");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hits(Map<String, Object> r, String key) {
        return (List<Map<String, Object>>) r.get(key);
    }

    // ==================================================================
    // 一、迁移与种子纪律
    // ==================================================================

    /**
     * V152 零回填：迁移跑完后患者过敏记录、映射、族成员、交叉关系全空，
     * 且两条示例行 enabled=false（enabled 的示例行会伪装成可用规则，也可能真的命中）。
     */
    @Test
    void migrationSeedsNothingButTwoDisabledSamples() {
        assertEquals(0, count("select count(*) from cdss_allergen_drug"),
                "V152 不得预置任何过敏原-药品映射：药学知识属硬边界");
        assertEquals(0, count("select count(*) from cdss_allergen_group_member"));
        assertEquals(0, count("select count(*) from cdss_allergen_cross"));
        assertEquals(0, count("select count(*) from cdss_allergy_text_review"),
                "自由文本核对记录必须由人产生，迁移不得代劳");

        var samples = jdbc.queryForList(
                "select code, enabled from cdss_allergen where code = 'SAMPLE-ALLERGEN'");
        assertEquals(1, samples.size());
        assertEquals(Boolean.FALSE, samples.get(0).get("enabled"),
                "示例过敏原必须 enabled=false，否则它会被当成一条真规则");
        assertEquals(Boolean.FALSE, jdbc.queryForList(
                "select enabled from cdss_allergen_group where code = 'SAMPLE-GROUP'").get(0).get("enabled"));
    }

    /** gate 默认 warn；坏配置回落 warn 而非 off（一个笔误不该静默关掉全院过敏审查） */
    @Test
    void gateDefaultsAndFallsBackToWarn() {
        assertEquals("warn", allergyService.gate());
        setGate("blocked");     // 常见笔误
        assertEquals("warn", allergyService.gate(), "坏配置必须回落 warn，不能当成 off");
        setGate("BLOCK");
        assertEquals("block", allergyService.gate(), "大小写与空白应被规范化");
        setGate("off");
        assertEquals("off", allergyService.gate());
    }

    // ==================================================================
    // 二、开单命中（本版的正题）
    // ==================================================================

    /**
     * 三级映射的意义：患者对「青霉素」（成分）过敏，开的是<b>名字毫不相干</b>的阿莫西林，
     * 靠药名子串匹配一个字都碰不上——这正是本版之前那个漏拦。显式映射把它拦住了。
     */
    @Test
    void ingredientLevelMappingCatchesDrugWithUnrelatedName() {
        Long pid = patient(null);
        Long ag = allergen("T-PCN", "测试过敏原甲");
        Long amox = drugId("阿莫西林");
        allergyService.mapDrug(ag, amox, "INGREDIENT", "测试映射", OPERATOR);
        allergyService.addPatientAllergy(pid, ag, "SEVERE", "喉头水肿", "TEST", null, null, OPERATOR);

        var r = allergyService.evaluate(pid, List.of(amox));
        assertEquals(1, hits(r, "directHits").size());
        assertFalse(String.valueOf(hits(r, "directHits").get(0).get("allergenName")).isBlank());
        // 提示必须把「凭什么」说全，否则医生只会盲目点继续
        String msg = String.valueOf(hits(r, "directHits").get(0).get("message"));
        assertTrue(msg.contains("重度"), "提示要带严重程度：" + msg);
        assertTrue(msg.contains("皮试"), "提示要带来源（自述 vs 皮试证实可信度差一个量级）：" + msg);
        assertTrue(msg.contains("喉头水肿"), "提示要带表现：" + msg);
        assertTrue(msg.contains("成分"), "提示要说明映射级别：" + msg);
    }

    /** warn 档（默认）：不拦，但提示必须真的到手——warnings + cdss_alert 双留痕 */
    @Test
    void warnGatePassesButActuallyDeliversTheWarning() {
        Long pid = patient(null);
        Long ag = allergen("T-W", "测试过敏原乙");
        Long amox = drugId("阿莫西林");
        allergyService.mapDrug(ag, amox, "PRODUCT", null, OPERATOR);
        allergyService.addPatientAllergy(pid, ag, "MODERATE", "皮疹", "SELF_REPORT", null, null, OPERATOR);
        Long rid = registration(pid);

        var r = allergyService.enforceOnOrdering(pid, rid, List.of(amox), OPERATOR);
        assertEquals("warn", r.get("gate"));
        assertEquals(Boolean.FALSE, r.get("blocked"));
        assertFalse(warnings(r).isEmpty(), "warn 档必须回带 warnings，静默放行等于没做");
        assertEquals(1, count(
                "select count(*) from cdss_alert where registration_id = " + rid + " and rule_type = 'ALLERGY'"),
                "warn 档必须落 cdss_alert，否则提示只活在一次 HTTP 响应里");
        assertEquals(1, count("select count(*) from cdss_allergy_gate_log where patient_id = " + pid));
    }

    /** block 档：直接命中拦截 5612 */
    @Test
    void blockGateRejectsDirectHit() {
        Long pid = patient(null);
        Long ag = allergen("T-B", "测试过敏原丙");
        Long amox = drugId("阿莫西林");
        allergyService.mapDrug(ag, amox, "PRODUCT", null, OPERATOR);
        allergyService.addPatientAllergy(pid, ag, "SEVERE", null, "CLINICAL", null, null, OPERATOR);
        setGate("block");

        var e = assertThrows(BizException.class,
                () -> allergyService.enforceOnOrdering(pid, null, List.of(amox), OPERATOR));
        assertEquals(5612, e.code);
    }

    /** off 档：旁路的是「拦不拦」，不是「记不记」——台账照样有，否则关掉这段时间的漏拦永远算不出来 */
    @Test
    void offGateStillLeavesATrail() {
        Long pid = patient(null);
        Long ag = allergen("T-O", "测试过敏原丁");
        Long amox = drugId("阿莫西林");
        allergyService.mapDrug(ag, amox, "PRODUCT", null, OPERATOR);
        allergyService.addPatientAllergy(pid, ag, "MILD", null, "SELF_REPORT", null, null, OPERATOR);
        setGate("off");

        var r = allergyService.enforceOnOrdering(pid, null, List.of(amox), OPERATOR);
        assertEquals(Boolean.FALSE, r.get("blocked"));
        assertEquals(1, count("select count(*) from cdss_allergy_gate_log where patient_id = " + pid
                + " and gate = 'off' and direct_hits = 1"));
    }

    /**
     * <b>本版最关键的一条设计断言</b>：交叉命中在 <b>block 档也不拦</b>。
     *
     * <p>交叉过敏是概率性的。把它做成硬拦，青霉素过敏的患者就用不了任何头孢，
     * 大量合理处方被拦 → 医生养成无脑点「继续」的习惯 → 真正的直接命中也一起被点掉。
     * 这条一旦被「顺手改成也拦」，本模块的临床价值会在一个月内归零。
     */
    @Test
    void crossHitNeverBlocksEvenUnderBlockGate() {
        Long pid = patient(null);
        Long agA = allergen("T-CA", "测试过敏原戊");
        Long agB = allergen("T-CB", "测试过敏原己");
        Long gA = group("T-GA", "测试族甲");
        Long gB = group("T-GB", "测试族乙");
        allergyService.addGroupMember(gA, agA, OPERATOR);
        allergyService.addGroupMember(gB, agB, OPERATOR);
        allergyService.addCross(gA, gB, "MEDIUM", "测试交叉", OPERATOR);
        Long cefix = drugId("头孢克肟");
        allergyService.mapDrug(agB, cefix, "CLASS", null, OPERATOR);
        // 患者只对 A 过敏；B 与 A 同属有交叉风险的两个族
        allergyService.addPatientAllergy(pid, agA, "SEVERE", null, "TEST", null, null, OPERATOR);
        setGate("block");

        var r = allergyService.enforceOnOrdering(pid, null, List.of(cefix), OPERATOR);
        assertEquals("block", r.get("gate"));
        assertTrue(hits(r, "directHits").isEmpty());
        assertEquals(1, hits(r, "crossHits").size(), "交叉风险必须被识别出来");
        assertEquals(Boolean.FALSE, r.get("blocked"), "交叉命中在 block 档也不得拦截");
        assertEquals(Boolean.FALSE, hits(r, "crossHits").get(0).get("blocking"));
        assertTrue(warnings(r).stream().anyMatch(w -> w.contains("交叉")),
                "不拦不等于不说：交叉提示必须回带");
    }

    /** 同一个药既直接命中又交叉命中时只报直接命中——重复提示是噪音的源头之一 */
    @Test
    void directHitSuppressesTheDuplicateCrossHit() {
        Long pid = patient(null);
        Long agA = allergen("T-DA", "测试过敏原庚");
        Long agB = allergen("T-DB", "测试过敏原辛");
        Long gA = group("T-GC", "测试族丙");
        Long gB = group("T-GD", "测试族丁");
        allergyService.addGroupMember(gA, agA, OPERATOR);
        allergyService.addGroupMember(gB, agB, OPERATOR);
        allergyService.addCross(gA, gB, "HIGH", null, OPERATOR);
        Long amox = drugId("阿莫西林");
        allergyService.mapDrug(agA, amox, "PRODUCT", null, OPERATOR);
        allergyService.mapDrug(agB, amox, "CLASS", null, OPERATOR);
        allergyService.addPatientAllergy(pid, agA, "SEVERE", null, "TEST", null, null, OPERATOR);

        var r = allergyService.evaluate(pid, List.of(amox));
        assertEquals(1, hits(r, "directHits").size());
        assertTrue(hits(r, "crossHits").isEmpty(), "同一个药不应同时报直接命中与交叉命中");
    }

    /** 撤销后立即不再命中；撤销必须留原因（5609） */
    @Test
    void revokedAllergyStopsHittingAndRequiresReason() {
        Long pid = patient(null);
        Long ag = allergen("T-R", "测试过敏原壬");
        Long amox = drugId("阿莫西林");
        allergyService.mapDrug(ag, amox, "PRODUCT", null, OPERATOR);
        Long aid = (Long) allergyService.addPatientAllergy(
                pid, ag, "MODERATE", null, "SELF_REPORT", null, null, OPERATOR).get("id");
        assertEquals(1, hits(allergyService.evaluate(pid, List.of(amox)), "directHits").size());

        var e = assertThrows(BizException.class,
                () -> allergyService.revokePatientAllergy(pid, aid, "  ", OPERATOR));
        assertEquals(5609, e.code);

        allergyService.revokePatientAllergy(pid, aid, "皮试阴性，排除过敏", OPERATOR);
        assertTrue(hits(allergyService.evaluate(pid, List.of(amox)), "directHits").isEmpty());
        // 撤销后可重新登记（唯一索引只约束生效记录）
        assertNotNull(allergyService.addPatientAllergy(
                pid, ag, "SEVERE", null, "CLINICAL", null, null, OPERATOR).get("id"));
    }

    /**
     * 停用字典条目<b>不得</b>让既有患者记录停止参与审查。
     * 否则「整理一下过敏原目录」就能静默关掉一批过敏拦截，而且谁也不会发现。
     */
    @Test
    void disablingAllergenDoesNotSilenceExistingPatientRecords() {
        Long pid = patient(null);
        Long ag = allergen("T-D", "测试过敏原癸");
        Long amox = drugId("阿莫西林");
        allergyService.mapDrug(ag, amox, "PRODUCT", null, OPERATOR);
        allergyService.addPatientAllergy(pid, ag, "SEVERE", null, "TEST", null, null, OPERATOR);

        allergyService.setAllergenEnabled(ag, false);
        assertEquals(1, hits(allergyService.evaluate(pid, List.of(amox)), "directHits").size(),
                "停用只挡新登记，不得让已确认的过敏静默失效");
        // 但新登记要挡住
        Long other = patient(null);
        var e = assertThrows(BizException.class, () -> allergyService.addPatientAllergy(
                other, ag, "MILD", null, "SELF_REPORT", null, null, OPERATOR));
        assertEquals(5601, e.code);
    }

    // ==================================================================
    // 三、覆盖度诚实（对付假阴性的主要手段）
    // ==================================================================

    /**
     * 患者有自由文本过敏史但没人核对过 → 即便零命中，也必须<b>明说覆盖不全</b>。
     * 沉默会被读成「查过了，没有过敏」，那是本模块最危险的一种输出。
     */
    @Test
    void unreviewedFreeTextIsSurfacedEvenWithZeroHits() {
        Long pid = patient("青霉素过敏");
        var r = allergyService.evaluate(pid, List.of(drugId("阿莫西林")));
        assertTrue(hits(r, "directHits").isEmpty());
        @SuppressWarnings("unchecked")
        var cov = (Map<String, Object>) r.get("coverage");
        assertEquals(Boolean.TRUE, cov.get("unreviewedText"));
        assertEquals(Boolean.FALSE, cov.get("trustworthy"));
        assertTrue(warnings(r).stream().anyMatch(w -> w.contains("覆盖不全")),
                "零命中 + 未核对原文时必须回带覆盖不全提示，warnings=" + warnings(r));
    }

    /** 过敏原登记了却没有任何药品映射 → 它永远不会命中，这件事必须说出来 */
    @Test
    void unmappedAllergenIsReportedAsACoverageHole() {
        Long pid = patient(null);
        Long ag = allergen("T-U", "测试过敏原子");
        var add = allergyService.addPatientAllergy(pid, ag, "SEVERE", null, "TEST", null, null, OPERATOR);
        assertEquals(0, add.get("mappedDrugCount"));
        @SuppressWarnings("unchecked")
        var addWarn = (List<String>) add.get("warnings");
        assertFalse(addWarn.isEmpty(), "登记一个无映射的过敏原时就该当场告诉登记人它拦不住任何药");

        var r = allergyService.evaluate(pid, List.of(drugId("阿莫西林")));
        @SuppressWarnings("unchecked")
        var cov = (Map<String, Object>) r.get("coverage");
        assertEquals(1, ((List<?>) cov.get("unmappedAllergens")).size());
        assertEquals(Boolean.FALSE, cov.get("trustworthy"));
        assertTrue(warnings(r).stream().anyMatch(w -> w.contains("尚未映射")), "warnings=" + warnings(r));
    }

    /** 核对结论 UNCLEAR 不等于「无过敏」：仍算覆盖不全 */
    @Test
    void unclearReviewIsNotTreatedAsCleared() {
        Long pid = patient("既往输液后皮疹，具体药物不详");
        allergyService.reviewText(pid, "既往输液后皮疹，具体药物不详", "UNCLEAR",
                List.of(), "需再问患者", OPERATOR);
        var r = allergyService.evaluate(pid, List.of(drugId("阿莫西林")));
        @SuppressWarnings("unchecked")
        var cov = (Map<String, Object>) r.get("coverage");
        assertEquals("UNCLEAR", cov.get("latestTextResolution"));
        assertEquals(Boolean.FALSE, cov.get("trustworthy"), "「说不准」不能被当成「已排除」");
        assertTrue(warnings(r).stream().anyMatch(w -> w.contains("无法判定")), "warnings=" + warnings(r));
    }

    // ==================================================================
    // 四、自由文本：零自动解析、原文零覆盖
    // ==================================================================

    /**
     * <b>反向拦截的反例</b>：「青霉素皮试阴性」与「青霉素过敏」文本相近而语义相反。
     * 系统不得从任何一段原文自动生出结构化过敏原——这里断言的是「什么都没发生」。
     */
    @Test
    void freeTextIsNeverParsedIntoStructuredAllergens() {
        Long negative = patient("青霉素皮试阴性");
        Long positive = patient("青霉素过敏");
        Long denied = patient("否认药物过敏史");
        for (Long pid : List.of(negative, positive, denied)) {
            assertEquals(0, count("select count(*) from cdss_patient_allergy where patient_id = " + pid),
                    "自由文本不得被脚本解析成结构化过敏原（解析错即反向拦截）");
            // 工作台也不得给出任何「建议过敏原」
            var wl = allergyService.migrationWorklist(50, false);
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) wl.get("items");
            var mine = items.stream()
                    .filter(i -> pid.equals(((Number) i.get("patient_id")).longValue())).findFirst();
            assertTrue(mine.isPresent(), "有原文未核对的患者必须出现在待办里");
            assertFalse(mine.get().containsKey("suggested_allergen_id"),
                    "工作台不得预填建议过敏原：预填会把人工确认降级成人工点确定");
        }
    }

    /** 人工判 NO_ALLERGY：留痕、离开待办、且不产生任何结构化过敏原 */
    @Test
    void skinTestNegativeCanBeResolvedAsNoAllergy() {
        String text = "青霉素皮试阴性";
        Long pid = patient(text);
        var r = allergyService.reviewText(pid, text, "NO_ALLERGY", List.of(), "皮试阴性，可用", OPERATOR);
        assertEquals("NO_ALLERGY", r.get("resolution"));
        assertEquals(0, count("select count(*) from cdss_patient_allergy where patient_id = " + pid));

        @SuppressWarnings("unchecked")
        var cov = (Map<String, Object>) r.get("coverage");
        assertEquals(Boolean.TRUE, cov.get("trustworthy"), "看过且确认无过敏，本次审查才配称可信");

        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) allergyService.migrationWorklist(200, false).get("items");
        assertTrue(items.stream().noneMatch(i -> pid.equals(((Number) i.get("patient_id")).longValue())),
                "已核对的患者应离开待办");
    }

    /** 原文<b>永远保留不覆盖</b>：核对并登记结构化过敏原之后，allergy_history 一字未动 */
    @Test
    void sourceTextIsNeverOverwritten() {
        String text = "青霉素过敏（皮疹）";
        Long pid = patient(text);
        Long ag = allergen("T-K", "测试过敏原丑");
        var r = allergyService.reviewText(pid, text, "STRUCTURED",
                List.of(new AllergyInput(ag, "MODERATE", "皮疹", "TEXT_REVIEW", null)), null, OPERATOR);
        assertEquals(1, ((List<?>) r.get("createdAllergies")).size());

        String after = jdbc.queryForObject(
                "select allergy_history from empi_patient where id = ?", String.class, pid);
        assertEquals(text, after, "结构化提取绝不能改写原文——事后追责看的正是原文");
        // 结构化记录里存的是原文快照，不是原文本体
        assertEquals(text, jdbc.queryForObject(
                "select source_text from cdss_patient_allergy where patient_id = ?", String.class, pid));
    }

    /**
     * 原文在核对期间被改过 → 拒收旧快照（5613）。
     * 不校验就会把按旧原文做的判断记成对新原文的核对，新写进去的过敏史从此再不进待办。
     */
    @Test
    void staleSourceTextSnapshotIsRejected() {
        Long pid = patient("青霉素皮试阴性");
        jdbc.update("update empi_patient set allergy_history = ? where id = ?", "青霉素过敏", pid);
        var e = assertThrows(BizException.class, () -> allergyService.reviewText(
                pid, "青霉素皮试阴性", "NO_ALLERGY", List.of(), null, OPERATOR));
        assertEquals(5613, e.code);
    }

    /** 结论与所选过敏原自相矛盾要挡住（5610）；原文为空时无可核对（5611） */
    @Test
    void contradictoryReviewIsRejected() {
        String text = "青霉素过敏";
        Long pid = patient(text);
        Long ag = allergen("T-X", "测试过敏原寅");

        var e1 = assertThrows(BizException.class, () -> allergyService.reviewText(
                pid, text, "STRUCTURED", List.of(), null, OPERATOR));
        assertEquals(5610, e1.code, "判定为「已确认」却一个过敏原都没选");

        var e2 = assertThrows(BizException.class, () -> allergyService.reviewText(
                pid, text, "NO_ALLERGY",
                List.of(new AllergyInput(ag, "MILD", null, null, null)), null, OPERATOR));
        assertEquals(5610, e2.code, "判定为「无过敏」却同时登记了过敏原");

        Long blank = patient(null);
        var e3 = assertThrows(BizException.class, () -> allergyService.reviewText(
                blank, null, "NO_ALLERGY", List.of(), null, OPERATOR));
        assertEquals(5611, e3.code);
    }

    // ==================================================================
    // 五、参数与唯一性
    // ==================================================================

    @Test
    void duplicateActiveAllergyIsRejected() {
        Long pid = patient(null);
        Long ag = allergen("T-DUP", "测试过敏原卯");
        allergyService.addPatientAllergy(pid, ag, "MILD", null, "SELF_REPORT", null, null, OPERATOR);
        var e = assertThrows(BizException.class, () -> allergyService.addPatientAllergy(
                pid, ag, "SEVERE", null, "TEST", null, null, OPERATOR));
        assertEquals(5605, e.code);
    }

    @Test
    void anonymousConfirmationIsRejected() {
        Long pid = patient(null);
        Long ag = allergen("T-ANON", "测试过敏原辰");
        var e = assertThrows(BizException.class, () -> allergyService.addPatientAllergy(
                pid, ag, "MILD", null, "SELF_REPORT", null, null, null));
        assertEquals(5606, e.code, "会拦处方的记录必须有人签字，不兜匿名");
    }

    /** 送审清单为空/药品不存在都要明确报错，不能静默不审却报「已审」 */
    @Test
    void badCheckInputIsRejectedNotSilentlySkipped() {
        Long pid = patient(null);
        assertEquals(5614, assertThrows(BizException.class,
                () -> allergyService.evaluate(pid, List.of())).code);
        assertEquals(5614, assertThrows(BizException.class,
                () -> allergyService.evaluate(pid, List.of(-999L))).code);
        assertEquals(5600, assertThrows(BizException.class,
                () -> allergyService.evaluate(-999L, List.of(drugId("阿莫西林")))).code);
    }

    /** 交叉风险是无序对：A-B 与 B-A 是同一条，不得存两行（存两行迟早只删一半） */
    @Test
    void crossRiskIsAnUnorderedPair() {
        Long gA = group("T-GE", "测试族戊");
        Long gB = group("T-GF", "测试族己");
        allergyService.addCross(gA, gB, "HIGH", null, OPERATOR);
        var e = assertThrows(BizException.class, () -> allergyService.addCross(gB, gA, "LOW", null, OPERATOR));
        assertEquals(5603, e.code);
        assertEquals(5603, assertThrows(BizException.class,
                () -> allergyService.addCross(gA, gA, "HIGH", null, OPERATOR)).code);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
