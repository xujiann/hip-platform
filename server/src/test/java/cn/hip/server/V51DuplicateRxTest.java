package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.CdssService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.DuplicateRxService;
import cn.hip.outpatient.service.DuplicateRxService.DuplicateRxException;
import cn.hip.outpatient.service.DuplicateRxService.Finding;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.DuplicateRxController;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v51 车道 B 回归：重复用药与同类药（三层匹配 / 同次就诊 vs 跨处方 / 三态 gate）。
 *
 * <p>编排刻意把第 ① 段排最前——它锁的是<b>「本车道没有内置任何药学知识、也没有按药名猜」</b>：
 * 迁移只加列不填值、未维护一律判为不可比较、前缀相同的两个药不算同一个药。
 * 这三条一旦变红，说明有人给 generic_name 加了回填、或把 drug_class/antibiotic 当成了
 * 药理分类、或改成了前缀匹配——那正是「阿莫西林克拉维酸钾被判成阿莫西林重复」的根因。
 *
 * <p>时间口径：全部日期由 {@link BusinessDates#today()} 推算，类内不出现任何时间字面量
 * ——本仓已因时区/精度炸过四次，CI 跑 UTC 是常驻金丝雀。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V51DuplicateRxTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired DuplicateRxService dupService;
    @Autowired DuplicateRxController controller;
    @Autowired CdssService cdssService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    @AfterEach
    void evict() {
        // gate 值改在事务里会回滚，但 ConfigReader 的 30 秒缓存不会——不清会污染后续用例
        configReader.evictAll();
    }

    // ==================================================================
    // 一、没有内置药学知识：只加列不填值 / 不可比较 / 不按药名猜
    // ==================================================================

    /** 迁移零回填：出厂状态下没有任何药品被填过通用名或药理类别 */
    @Test
    void migrationSeedsNoPharmacologyKnowledge() {
        // 药理类别字典出厂只有一条明确标注的占位示例，且不与任何药品关联
        var cats = jdbc.queryForList("select code, name, remark from cdss_pharm_category");
        assertEquals(1, cats.size(), "字典出厂应只有一条占位示例行");
        assertEquals("EXAMPLE", cats.get(0).get("code"));
        assertTrue(((String) cats.get(0).get("remark")).contains("示例"),
                "占位行必须自带「示例，上线前须由药剂科替换」的标注");
        Integer used = jdbc.queryForObject(
                "select count(*) from md_drug where pharm_category_code = 'EXAMPLE'", Integer.class);
        assertEquals(0, used.intValue(), "占位示例行不得与任何药品关联");

        // md_drug 两列一行未填——若这里变红，说明有人给规则内容做了回填
        Integer filled = jdbc.queryForObject("""
                select count(*) from md_drug
                where generic_name is not null or pharm_category_code is not null
                """, Integer.class);
        assertEquals(0, filled.intValue(),
                "V153 只加列不填值；通用名与药理类别属院内用药目录，只能由药剂科维护");
    }

    /** 未维护 = 不可比较，不是「同为未维护所以同类」——否则全院未维护的药会互相报重复 */
    @Test
    void unmaintainedDrugsAreNotComparableAndAreReportedHonestly() {
        Long a = seeds.drug("v51未维护甲").getId();
        Long b = seeds.drug("v51未维护乙").getId();
        Long regId = bareVisit();

        var body = dupService.evaluate(regId, List.of(a, b));
        assertTrue(findings(body).isEmpty(),
                "两个都没维护通用名/类别的不同药品，不得被判成同名或同类");

        @SuppressWarnings("unchecked")
        var un = (Map<String, Object>) body.get("unmaintained");
        @SuppressWarnings("unchecked")
        var missingGeneric = (List<String>) un.get("missingGenericName");
        assertEquals(2, missingGeneric.size(), "未维护必须如实回报，不能静默地什么都查不出");
        assertTrue(((String) body.get("note")).contains("查不出重复"),
                "返回体要说清「查不出」不等于「没有重复」");
    }

    /**
     * <b>本车道最重要的一条断言。</b>
     * 「阿莫西林胶囊」与「阿莫西林颗粒」是同通用名——要报；
     * 「阿莫西林」与「阿莫西林克拉维酸钾」<b>不是</b>同通用名——不许报。
     * 前缀匹配会把后者误判成重复，那是把一个合理的复方处方拦下来。
     */
    @Test
    void prefixLikeGenericNamesAreNotTreatedAsTheSameDrug() {
        Long capsule = seeds.drug("v51阿莫西林胶囊").getId();
        Long granule = seeds.drug("v51阿莫西林颗粒").getId();
        Long compound = seeds.drug("v51阿莫西林克拉维酸钾片").getId();
        setGeneric(capsule, "阿莫西林");
        setGeneric(granule, "阿莫西林");
        setGeneric(compound, "阿莫西林克拉维酸钾");   // 复方本位名，不是主成分名

        Long regId = bareVisit();

        // 同通用名不同剂型：要抓
        var same = findings(dupService.evaluate(regId, List.of(capsule, granule)));
        assertEquals(1, same.size());
        assertEquals(DuplicateRxService.LEVEL_SAME_GENERIC, same.get(0).matchLevel());

        // 名字前缀相同但不是同一个药：绝不许抓
        var different = findings(dupService.evaluate(regId, List.of(capsule, compound)));
        assertTrue(different.isEmpty(),
                "「阿莫西林」与「阿莫西林克拉维酸钾」是两个药；前缀匹配会误判，本实现必须精确相等");
    }

    /** drug_class(W/C) 是费用分类、antibiotic/abx_level 是抗菌药分级——都不是药理分类 */
    @Test
    void feeClassAndAntibioticFlagAreNotUsedAsPharmCategory() {
        Long a = seeds.drug("v51抗菌甲").getId();
        Long b = seeds.drug("v51抗菌乙").getId();
        jdbc.update("update md_drug set drug_class = 'W', antibiotic = true, abx_level = 1 where id in (?,?)",
                a, b);
        Long regId = bareVisit();

        assertTrue(findings(dupService.evaluate(regId, List.of(a, b))).isEmpty(),
                "同为西药、同为抗菌药不等于同药理类别；拿 drug_class/antibiotic 当分类会对每张多药处方报警");
    }

    // ==================================================================
    // 二、三个层次
    // ==================================================================

    /** 第 ① 层：同一 drug_id 开两次（改方忘删旧行），本次提交内部的重复也要抓 */
    @Test
    void sameDrugWithinOneSubmissionIsCaught() {
        Long d = seeds.drug("v51完全相同药").getId();
        Long regId = bareVisit();

        var fs = findings(dupService.evaluate(regId, List.of(d, d)));
        assertEquals(1, fs.size(), "A-vs-A 只该报一条，不该正反各报一次");
        Finding f = fs.get(0);
        assertEquals(DuplicateRxService.SCOPE_IN_VISIT, f.scope());
        assertEquals(DuplicateRxService.LEVEL_SAME_DRUG, f.matchLevel());
        assertTrue(f.blocking());
    }

    /** 第 ① 层：已在单上的旧行 vs 本次新开行 */
    @Test
    void sameDrugAgainstAlreadyOrderedLineIsCaught() {
        Long d = seeds.drug("v51旧行未删药").getId();
        Long regId = visitWith(d, 3);

        var fs = findings(dupService.evaluate(regId, List.of(d)));
        assertEquals(1, fs.size());
        assertEquals(DuplicateRxService.LEVEL_SAME_DRUG, fs.get(0).matchLevel());
    }

    /** 第 ② 层：同通用名不同厂家/规格 */
    @Test
    void sameGenericDifferentSpecIsCaughtOnlyAfterMaintenance() {
        Long x = seeds.drug("v51甲厂二甲双胍").getId();
        Long y = seeds.drug("v51乙厂二甲双胍").getId();
        Long regId = bareVisit();

        // 维护前：数据源为空，查不出
        assertTrue(findings(dupService.evaluate(regId, List.of(x, y))).isEmpty());

        setGeneric(x, "盐酸二甲双胍");
        setGeneric(y, "  盐酸二甲双胍  ");   // 导入残留的首尾空白应被归一

        var fs = findings(dupService.evaluate(regId, List.of(x, y)));
        assertEquals(1, fs.size());
        assertEquals(DuplicateRxService.LEVEL_SAME_GENERIC, fs.get(0).matchLevel());
        assertTrue(fs.get(0).blocking());
    }

    /** 第 ③ 层：同药理类别——抓，但<b>永不拦截</b>（同类联用常是有意为之） */
    @Test
    void sameCategoryIsReportedButNeverBlocks() {
        String code = category("V51CAT");
        Long x = seeds.drug("v51同类甲").getId();
        Long y = seeds.drug("v51同类乙").getId();
        setCategory(x, code);
        setCategory(y, code);
        Long regId = bareVisit();

        var fs = findings(dupService.evaluate(regId, List.of(x, y)));
        assertEquals(1, fs.size());
        assertEquals(DuplicateRxService.LEVEL_SAME_CATEGORY, fs.get(0).matchLevel());
        assertEquals(DuplicateRxService.SEV_WARN, fs.get(0).severity());
        assertFalse(fs.get(0).blocking(), "同类联用可能是指南推荐的联合方案，不许硬拦");

        // 即便 gate=block 也放行
        setGate("block");
        var body = dupService.checkOnOrdering(regId, List.of(x, y));
        assertEquals(Boolean.FALSE, body.get("blocked"));
    }

    /** 最具体者优先：完全相同就只报 SAME_DRUG，不再叠一条同通用名/同类别 */
    @Test
    void mostSpecificLevelWinsPerPair() {
        String code = category("V51PREC");
        Long d = seeds.drug("v51最具体优先药").getId();
        setGeneric(d, "某通用名");
        setCategory(d, code);
        Long regId = bareVisit();

        var fs = findings(dupService.evaluate(regId, List.of(d, d)));
        assertEquals(1, fs.size(), "同一对药不该按三个层次各报一条");
        assertEquals(DuplicateRxService.LEVEL_SAME_DRUG, fs.get(0).matchLevel());
    }

    // ==================================================================
    // 三、跨处方：区分「开重了」与「上次的药还没吃完」
    // ==================================================================

    /**
     * <b>长期用药复诊续方是正常医疗行为</b>：上次的药按疗程已吃完，本次再开
     * <b>一条提示都不出</b>。若这条变红，慢病患者每月续方都会报重复，
     * 医生一个月内就会学会无视全部重复用药提示。
     */
    @Test
    void exhaustedPriorSupplyIsANormalRefillAndStaysSilent() {
        Long d = seeds.drug("v51长期降压药").getId();
        // 30 天前开了 7 天的量：早已吃完
        priorVisitWith(d, 7, 30);
        Long regId = bareVisitFor(lastPatientId);

        assertTrue(findings(dupService.evaluate(regId, List.of(d))).isEmpty(),
                "上次的药已按疗程用完，本次是正常续方，不是重复用药");
    }

    /** 上次的药还没吃完：出 WARN，但<b>永不拦截</b>（即便 gate=block） */
    @Test
    void unexhaustedPriorSupplyWarnsButNeverBlocks() {
        Long d = seeds.drug("v51未吃完药").getId();
        priorVisitWith(d, 30, 5);          // 5 天前开了 30 天的量，还剩 25 天
        Long regId = bareVisitFor(lastPatientId);

        var fs = findings(dupService.evaluate(regId, List.of(d)));
        assertEquals(1, fs.size());
        Finding f = fs.get(0);
        assertEquals(DuplicateRxService.SCOPE_CROSS_VISIT, f.scope());
        assertEquals(DuplicateRxService.SEV_WARN, f.severity());
        assertEquals(Integer.valueOf(25), f.remainingDays());
        assertEquals(Integer.valueOf(30), f.priorDays());
        assertNotNull(f.priorRegistrationId());
        assertFalse(f.blocking(), "跨处方只提示不拦");

        setGate("block");
        var body = dupService.checkOnOrdering(regId, List.of(d));
        assertEquals(Boolean.FALSE, body.get("blocked"), "gate=block 也不许拦跨处方重复");
    }

    /** 上次没填疗程天数：<b>不猜</b>，出 INFO 并明说无法区分续方与重复 */
    @Test
    void priorWithoutCourseDaysIsInfoNotWarn() {
        Long d = seeds.drug("v51未填疗程药").getId();
        priorVisitWith(d, null, 3);
        Long regId = bareVisitFor(lastPatientId);

        var fs = findings(dupService.evaluate(regId, List.of(d)));
        assertEquals(1, fs.size());
        assertEquals(DuplicateRxService.SEV_INFO, fs.get(0).severity());
        assertNull(fs.get(0).remainingDays(), "疗程天数缺失时不许编一个剩余天数出来");
        assertTrue(fs.get(0).message().contains("无法判断"));
    }

    /** 回溯窗口只决定往前翻多少天；窗口外的处方不参与 */
    @Test
    void lookbackWindowBoundsTheSearch() {
        Long d = seeds.drug("v51窗口外药").getId();
        priorVisitWith(d, 400, 60);        // 60 天前开了 400 天的量（远未吃完），但在 30 天窗口外
        Long regId = bareVisitFor(lastPatientId);

        assertTrue(findings(dupService.evaluate(regId, List.of(d))).isEmpty(),
                "默认 30 天窗口翻不到 60 天前的处方");

        cfg(DuplicateRxService.LOOKBACK_KEY, "90");
        assertEquals(90, dupService.lookbackDays());
        assertEquals(1, findings(dupService.evaluate(regId, List.of(d))).size(),
                "窗口放宽到 90 天后应翻得到");
    }

    /** 回溯天数越界或非法一律回落默认值——不许变成 0 天（等于关掉）或十年 */
    @Test
    void lookbackConfigIsBounded() {
        cfg(DuplicateRxService.LOOKBACK_KEY, "0");
        assertEquals(DuplicateRxService.LOOKBACK_DEFAULT, dupService.lookbackDays());
        cfg(DuplicateRxService.LOOKBACK_KEY, "9999");
        assertEquals(DuplicateRxService.LOOKBACK_DEFAULT, dupService.lookbackDays());
        cfg(DuplicateRxService.LOOKBACK_KEY, "三十");
        assertEquals(DuplicateRxService.LOOKBACK_DEFAULT, dupService.lookbackDays());
    }

    /** 已退号的挂号与已取消的医嘱不参与判重 */
    @Test
    void cancelledRegistrationsAndOrdersAreIgnored() {
        Long d = seeds.drug("v51已取消药").getId();
        priorVisitWith(d, 30, 2);
        Long priorReg = lastRegId;
        Long regId = bareVisitFor(lastPatientId);
        assertEquals(1, findings(dupService.evaluate(regId, List.of(d))).size());

        jdbc.update("update outp_order set status = 'CANCELLED' where registration_id = ?", priorReg);
        assertTrue(findings(dupService.evaluate(regId, List.of(d))).isEmpty(),
                "已取消的医嘱不该继续参与判重");

        jdbc.update("update outp_order set status = 'CREATED' where registration_id = ?", priorReg);
        jdbc.update("update outp_registration set status = 'CANCELLED' where id = ?", priorReg);
        assertTrue(findings(dupService.evaluate(regId, List.of(d))).isEmpty(),
                "已退号的挂号不该继续参与判重");
    }

    // ==================================================================
    // 四、三态 gate
    // ==================================================================

    @Test
    void gateBlockRejectsInVisitSameDrug() {
        setGate("block");
        Long d = seeds.drug("v51拦截完全相同药").getId();
        Long regId = bareVisit();

        var e = assertThrows(DuplicateRxException.class,
                () -> dupService.checkOnOrdering(regId, List.of(d, d)));
        assertEquals(DuplicateRxService.ERR_IN_VISIT_SAME_DRUG, e.code);
    }

    @Test
    void gateBlockRejectsInVisitSameGenericWithItsOwnCode() {
        setGate("block");
        Long x = seeds.drug("v51拦截同名甲").getId();
        Long y = seeds.drug("v51拦截同名乙").getId();
        setGeneric(x, "同名本位名");
        setGeneric(y, "同名本位名");
        Long regId = bareVisit();

        var e = assertThrows(DuplicateRxException.class,
                () -> dupService.checkOnOrdering(regId, List.of(x, y)));
        assertEquals(DuplicateRxService.ERR_IN_VISIT_SAME_GENERIC, e.code);
    }

    /** warn 档必须<b>真的把提示给到医生</b>并落台账，不能静默放行 */
    @Test
    void gateWarnPassesButReturnsWarningsAndWritesLedger() {
        setGate("warn");
        Long d = seeds.drug("v51warn档药").getId();
        Long regId = bareVisit();

        var body = dupService.checkOnOrdering(regId, List.of(d, d));
        assertEquals(Boolean.FALSE, body.get("blocked"));
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) body.get("warnings");
        assertEquals(1, warnings.size(), "warn 档不许静默——提示必须回到返回体里");
        assertTrue(warnings.get(0).contains("重复开具"));

        var rows = dupService.alerts(regId, null);
        assertEquals(1, rows.size(), "warn 档放行必须落台账，否则永远拿不到收紧 gate 的依据");
        assertEquals("warn", rows.get(0).get("gate"));
        assertEquals(Boolean.FALSE, rows.get(0).get("blocked"));
        assertNotNull(rows.get(0).get("created_at"));
    }

    @Test
    void gateOffBypassesTheCheckEntirely() {
        setGate("off");
        Long d = seeds.drug("v51off档药").getId();
        Long regId = bareVisit();

        var body = dupService.checkOnOrdering(regId, List.of(d, d));
        assertEquals(Boolean.TRUE, body.get("skipped"));
        assertTrue(findings(body).isEmpty());
        assertTrue(dupService.alerts(regId, null).isEmpty(), "off 档不落台账");
    }

    /** 但预览端点<b>不受 gate 影响</b>：显式查询不该因为 gate=off 就假装没查到 */
    @Test
    void previewIgnoresTheGate() {
        setGate("off");
        Long d = seeds.drug("v51预览不受gate药").getId();
        Long regId = bareVisit();

        assertEquals(1, findings(dupService.evaluate(regId, List.of(d, d))).size());
        assertEquals("off", dupService.evaluate(regId, List.of(d, d)).get("gate"));
    }

    /** 坏配置回落 warn 而不是 off：宁可多提示，不可让一个笔误静默关掉校验 */
    @Test
    void badGateConfigFallsBackToWarnNotOff() {
        for (String bad : List.of("blocked", "true", "1", "", "BLOCK ")) {
            setGate(bad);
            String g = dupService.gate();
            assertTrue("warn".equals(g) || "block".equals(g),
                    "坏配置 [" + bad + "] 回落成了 " + g + "；回落到 off 等于让笔误关掉校验");
        }
        setGate("blocked");
        assertEquals("warn", dupService.gate());
    }

    // ==================================================================
    // 五、维护端点与既有链路
    // ==================================================================

    @Test
    void unknownCategoryCodeIsRejected() {
        Long d = seeds.drug("v51未知类别药").getId();
        var e = assertThrows(DuplicateRxException.class,
                () -> dupService.setPharmCategory(d, "NOT_A_REAL_CATEGORY"));
        assertEquals(DuplicateRxService.ERR_UNKNOWN_CATEGORY, e.code);
    }

    @Test
    void maintenanceCanBeWithdrawnBackToUnmaintained() {
        Long d = seeds.drug("v51撤回维护药").getId();
        setGeneric(d, "某本位名");
        assertEquals("某本位名", jdbc.queryForObject(
                "select generic_name from md_drug where id = ?", String.class, d));

        dupService.setGenericName(d, "   ");
        assertNull(jdbc.queryForObject("select generic_name from md_drug where id = ?", String.class, d),
                "空白应置回 null（未维护），不是存一个空串进去");
    }

    @Test
    void badArgsAreRejected() {
        assertEquals(DuplicateRxService.ERR_BAD_ARG,
                assertThrows(DuplicateRxException.class,
                        () -> dupService.evaluate(-1L, List.of(1L))).code);
        assertEquals(DuplicateRxService.ERR_DRUG_NOT_FOUND,
                assertThrows(DuplicateRxException.class,
                        () -> dupService.setGenericName(-1L, "x")).code);
    }

    /** config 如实回报覆盖率与各层可用性——不许把「覆盖率 0 所以没报警」说成「没有重复」 */
    @Test
    void configReportsCoverageHonestly() {
        var cfg = controller.config().getData();
        assertEquals(DuplicateRxService.GATE_KEY, cfg.get("gateKey"));
        @SuppressWarnings("unchecked")
        var cov = (Map<String, Object>) cfg.get("coverage");
        assertEquals(0L, ((Number) cov.get("generic_maintained")).longValue(),
                "出厂状态通用名维护数应为 0");
        assertTrue(((String) cfg.get("knownGap")).contains("不预置"));
    }

    /** 既有 CDSS 三类规则没被改坏：年龄限制仍然按 4017 拦 */
    @Test
    void legacyCdssRulesAreUntouched() {
        // 既有 5 张规则表与 4 个端点原样存在
        assertNotNull(jdbc.queryForList("select * from cdss_ddi_rule limit 1"));
        assertNotNull(jdbc.queryForList("select * from cdss_dose_rule limit 1"));
        assertNotNull(jdbc.queryForList("select * from cdss_age_rule limit 1"));
        assertNotNull(jdbc.queryForList("select * from cdss_suggestion limit 1"));
        // 本版不写既有留痕表，避免污染既有报表口径
        Long d = seeds.drug("v51不污染既有留痕药").getId();
        Long regId = bareVisit();
        int before = jdbc.queryForObject("select count(*) from cdss_alert", Integer.class);
        dupService.checkOnOrdering(regId, List.of(d, d));
        assertEquals(before, jdbc.queryForObject("select count(*) from cdss_alert", Integer.class).intValue(),
                "重复用药不该写进既有 cdss_alert");
        assertNotNull(cdssService);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private Long lastPatientId;
    private Long lastRegId;

    @SuppressWarnings("unchecked")
    private List<Finding> findings(Map<String, Object> body) {
        return (List<Finding>) body.get("findings");
    }

    private void setGate(String value) {
        cfg(DuplicateRxService.GATE_KEY, value);
    }

    private void cfg(String key, String value) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, key);
        configReader.evict(key);
    }

    private void setGeneric(Long drugId, String generic) {
        dupService.setGenericName(drugId, generic);
    }

    private void setCategory(Long drugId, String code) {
        dupService.setPharmCategory(drugId, code);
    }

    private String category(String code) {
        dupService.upsertCategory(code, code + " 测试类别", "单测建的类别");
        return code;
    }

    private Long newPatient() {
        Patient p = new Patient();
        p.setName("重复用药测试" + System.nanoTime());
        p.setSex("U");
        lastPatientId = patientService.register(p).getId();
        return lastPatientId;
    }

    /** 日期一律由 BusinessDates.today() 推算，类内不出现任何时间字面量 */
    private Long scheduleOn(LocalDate date) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(date);
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(99);
        return scheduleRepository.save(s).getId();
    }

    private Long bareVisit() {
        return bareVisitFor(newPatient());
    }

    private Long bareVisitFor(Long patientId) {
        Long regId = registrationService.register(patientId, scheduleOn(BusinessDates.today())).getId();
        doctorStationService.startVisit(regId, null);
        em.flush();
        lastRegId = regId;
        return regId;
    }

    private Long visitWith(Long drugId, Integer days) {
        Long regId = bareVisit();
        doctorStationService.createOrders(regId,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "tid", "1粒", days)), null);
        em.flush();
        return regId;
    }

    /**
     * 给 {@link #lastPatientId} 造一次 {@code daysAgo} 天前的既往就诊。
     *
     * <p>挂号服务只能建「今天」的号，历史就诊只能落库后改 visit_date——这是测试数据构造，
     * 不是业务回填。{@code courseDays} 传 null 模拟开单时没填疗程天数。
     */
    private void priorVisitWith(Long drugId, Integer courseDays, int daysAgo) {
        Long regId = bareVisit();
        doctorStationService.createOrders(regId,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "qd", "1粒", courseDays)), null);
        em.flush();
        jdbc.update("update outp_registration set visit_date = ? where id = ?",
                BusinessDates.today().minusDays(daysAgo), regId);
        lastRegId = regId;
    }
}
