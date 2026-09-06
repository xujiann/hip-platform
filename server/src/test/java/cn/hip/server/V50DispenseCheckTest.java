package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DispenseCheckService;
import cn.hip.outpatient.service.DispenseService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.outpatient.web.DispenseCheckController;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * v50 车道 C：门诊发药核对（双人核对 / 高危单独确认 / 看似听似 / 退回摆药 / 三态 gate）。
 *
 * <p>本类<b>一条既有断言都没改</b>：既有发药链路（{@link DispenseService}）在
 * {@link #legacyDispenseStillWorksWithoutAnyCheck()} 里原样跑一遍，确认没被本版改坏。
 *
 * <p>时间口径：全部时刻由 DB {@code now()} 写入、日窗口按业务时区半开区间取，
 * 类内不出现任何时间字面量——本仓已因时区/精度炸过四次（CI 跑 UTC 是常驻金丝雀）。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V50DispenseCheckTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired DispenseService dispenseService;
    @Autowired DispenseCheckService checkService;
    @Autowired DispenseCheckController controller;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired OutpOrderRepository orderRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    @AfterEach
    void evict() {
        // gate 值改在事务里会回滚，但 ConfigReader 的 30 秒缓存不会——不清会污染后续用例
        configReader.evictAll();
    }

    // ==================================================================
    // 一、双人核对：摆药人 ≠ 核对人（与 gate 无关，恒定生效）
    // ==================================================================

    @Test
    void pickerCannotBeTheChecker() {
        Long picker = user("v50picker");
        Long regId = visitWithDrug(seeds.drug("双人核对测试药").getId(), 2);

        var sheet = checkService.create(regId, picker);
        Long checkId = ((Number) sheet.get("id")).longValue();

        // 摆药人自己核对：一个人核两次不构成双人核对
        var e = assertThrows(BizException.class, () -> checkService.pass(checkId, picker));
        assertEquals(5443, e.code);

        // 换人即通过
        Long checker = user("v50checker");
        var passed = checkService.pass(checkId, checker);
        assertEquals("CHECKED", passed.get("status"));
        assertEquals(picker, ((Number) passed.get("picker_id")).longValue());
        assertEquals(checker, ((Number) passed.get("checker_id")).longValue());
        assertNotNull(passed.get("picked_at"));
        assertNotNull(passed.get("checked_at"));
    }

    /** 5443 与 gate 无关：off 档（gate 整段旁路）也不许一个人占两个位置 */
    @Test
    void twoPersonRuleIsNotGovernedByTheGate() {
        setGate("off");
        Long picker = user("v50picker");
        Long regId = visitWithDrug(seeds.drug("双人核对不受gate测试药").getId(), 1);
        Long checkId = ((Number) checkService.create(regId, picker).get("id")).longValue();

        assertEquals("off", checkService.gate());
        var e = assertThrows(BizException.class, () -> checkService.pass(checkId, picker));
        assertEquals(5443, e.code);
    }

    /** DB 侧兜底：直连改库把核对人写成摆药人也写不进去 */
    @Test
    void dbConstraintAlsoRejectsSamePerson() {
        Long picker = user("v50picker");
        Long regId = visitWithDrug(seeds.drug("双人核对DB兜底药").getId(), 1);
        Long checkId = ((Number) checkService.create(regId, picker).get("id")).longValue();

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update pharm_dispense_check set checker_id = ? where id = ?", picker, checkId));
    }

    // ==================================================================
    // 二、高危药品：属性位靠维护不靠猜 + 逐行单独确认
    // ==================================================================

    /**
     * <b>迁移只加列不填值</b>：既有药品的 high_alert 是 null（未维护），不是 false（已判定非高危）。
     * 两者在提示上都不弹窗，但在管理上完全不是一回事——这条断言就是防着有人后来给它补个
     * {@code default false} 把「没人看过」变成「全库都不是高危药」。
     */
    @Test
    void highAlertIsNullNotFalseBeforeMaintenance() {
        Long drugId = seeds.drug("高危未维护测试药").getId();
        assertNull(jdbc.queryForObject("select high_alert from md_drug where id = ?", Boolean.class, drugId),
                "新药品的 high_alert 必须是 null（未维护），不能是 false（已判定非高危）");

        Long regId = visitWithDrug(drugId, 1);
        var preview = checkService.preview(regId);
        assertEquals(1L, ((Number) preview.get("unmaintainedCount")).longValue());
        @SuppressWarnings("unchecked")
        var lines = (List<Map<String, Object>>) preview.get("lines");
        assertNull(lines.get(0).get("highAlert"), "未维护要原样透出 null，不能折叠成 false");
    }

    @Test
    void highAlertDrugNeedsIndividualConfirmationByTheChecker() {
        Long picker = user("v50picker");
        Long checker = user("v50checker");
        Long drugId = seeds.drug("高危已维护测试药").getId();
        checkService.setHighAlert(drugId, true, picker);

        Long regId = visitWithDrug(drugId, 1);
        var sheet = checkService.create(regId, picker);
        Long checkId = ((Number) sheet.get("id")).longValue();
        assertEquals(1L, ((Number) sheet.get("highAlertPending")).longValue());

        // 没有逐行确认，核对通不过
        var e = assertThrows(BizException.class, () -> checkService.pass(checkId, checker));
        assertEquals(5445, e.code);

        Long lineId = firstLineId(checkId);
        // 摆药人确认自己摆的高危药，同样不算（确认是核对动作的一部分）
        var e2 = assertThrows(BizException.class, () -> checkService.confirmHighAlert(checkId, lineId, picker));
        assertEquals(5443, e2.code);

        var after = checkService.confirmHighAlert(checkId, lineId, checker);
        assertEquals(0L, ((Number) after.get("highAlertPending")).longValue());
        // 重复确认不成立
        assertEquals(5446, assertThrows(BizException.class,
                () -> checkService.confirmHighAlert(checkId, lineId, checker)).code);

        assertEquals("CHECKED", checkService.pass(checkId, checker).get("status"));
        assertNotNull(jdbc.queryForObject(
                "select high_alert_confirmed_at from pharm_dispense_check_line where id = ?",
                java.sql.Timestamp.class, lineId));
    }

    /** 高危确认必须落在本次核对人身上：甲确认、乙通过不成立 */
    @Test
    void confirmationBySomeoneElseDoesNotCountForThePassingChecker() {
        Long picker = user("v50picker");
        Long checkerA = user("v50checker");
        Long checkerB = user("v50checker2");
        Long drugId = seeds.drug("高危换人确认测试药").getId();
        checkService.setHighAlert(drugId, true, picker);

        Long regId = visitWithDrug(drugId, 1);
        Long checkId = ((Number) checkService.create(regId, picker).get("id")).longValue();
        checkService.confirmHighAlert(checkId, firstLineId(checkId), checkerA);

        var e = assertThrows(BizException.class, () -> checkService.pass(checkId, checkerB));
        assertEquals(5445, e.code);
        assertTrue(e.getMessage().contains("他人确认"), "消息应指出确认人与核对人不是同一人：" + e.getMessage());

        assertEquals("CHECKED", checkService.pass(checkId, checkerA).get("status"));
    }

    /** 非高危行不需要（也不接受）单独确认——否则「单独确认」会退化成人人都点的橡皮图章 */
    @Test
    void nonHighAlertLineRejectsConfirmation() {
        Long picker = user("v50picker");
        Long checker = user("v50checker");
        Long drugId = seeds.drug("非高危测试药").getId();
        checkService.setHighAlert(drugId, false, picker);

        Long regId = visitWithDrug(drugId, 1);
        Long checkId = ((Number) checkService.create(regId, picker).get("id")).longValue();

        var e = assertThrows(BizException.class,
                () -> checkService.confirmHighAlert(checkId, firstLineId(checkId), checker));
        assertEquals(5446, e.code);
        assertEquals("CHECKED", checkService.pass(checkId, checker).get("status"));
    }

    /** 高危属性位只接受 true/false：清回 null 是伪造「没人看过」的历史 */
    @Test
    void highAlertCannotBeResetToUnmaintained() {
        Long picker = user("v50picker");
        Long drugId = seeds.drug("高危清空测试药").getId();
        assertEquals(5449, assertThrows(BizException.class,
                () -> checkService.setHighAlert(drugId, null, picker)).code);
        assertEquals(5449, assertThrows(BizException.class,
                () -> checkService.setHighAlert(-1L, true, picker)).code);
    }

    // ==================================================================
    // 三、看似听似：靠对照表，不靠字符串相似度
    // ==================================================================

    /**
     * 名字极像的两个药，<b>没登记对照就不提示</b>——这条断言就是防着有人后来加一段
     * 编辑距离「自动识别 LASA」：那会把「阿莫西林 0.25g」与「阿莫西林 0.5g」判成一对，
     * 提示一旦不可信，药师连真的那条也一起无视。
     */
    @Test
    void lasaIsNeverInferredFromNameSimilarity() {
        Long picker = user("v50picker");
        Long a = seeds.drug("看似听似测试药甲型").getId();
        Long b = seeds.drug("看似听似测试药乙型").getId();
        assertNotEquals(a, b);

        Long regId = visitWithDrugs(List.of(a, b));
        var preview = checkService.preview(regId);
        @SuppressWarnings("unchecked")
        var lines = (List<Map<String, Object>>) preview.get("lines");
        assertTrue(lines.stream().allMatch(l -> l.get("lasaNote") == null),
                "未登记对照的相似药名不得自动提示 LASA");

        // 药剂科登记后才提示，且提示里要点名对方
        checkService.addLasaPair(b, a, "包装极为相似", picker);
        @SuppressWarnings("unchecked")
        var after = (List<Map<String, Object>>) checkService.preview(regId).get("lines");
        assertTrue(after.stream().allMatch(l -> String.valueOf(l.get("lasaNote")).contains("看似听似")));
        assertTrue(after.stream().anyMatch(l -> String.valueOf(l.get("lasaNote")).contains("乙型")));
        assertTrue(after.stream().anyMatch(l -> String.valueOf(l.get("lasaNote")).contains("甲型")));
        assertTrue(after.stream().allMatch(l -> Boolean.TRUE.equals(l.get("lasaSameSheet"))),
                "同一张单同时出现互为 LASA 的两个药，是最高危场景，必须单独标出");
    }

    /** 无序对只存一行：A-B 与 B-A 是同一条，存两行迟早只删一半变成半截数据 */
    @Test
    void lasaPairIsAnUnorderedPairStoredOnce() {
        Long picker = user("v50picker");
        Long a = seeds.drug("LASA归一测试药甲").getId();
        Long b = seeds.drug("LASA归一测试药乙").getId();

        var pair = checkService.addLasaPair(a, b, "音近", picker);
        assertEquals(5450, assertThrows(BizException.class,
                () -> checkService.addLasaPair(b, a, "反向登记", picker)).code);
        assertEquals(5450, assertThrows(BizException.class,
                () -> checkService.addLasaPair(a, a, "自己和自己", picker)).code);

        @SuppressWarnings("unchecked")
        var listedFromB = (List<Map<String, Object>>) checkService.lasaPairs(b, null).get("items");
        assertEquals(1, listedFromB.size(), "从对里任一侧都要查得到，不能只从 lo 侧查得到");

        checkService.removeLasaPair(((Number) pair.get("id")).longValue());
        @SuppressWarnings("unchecked")
        var gone = (List<Map<String, Object>>) checkService.lasaPairs(b, null).get("items");
        assertTrue(gone.isEmpty());
    }

    // ==================================================================
    // 四、退回摆药
    // ==================================================================

    @Test
    void returnToPickingNeedsReasonAndAllowsRecheck() {
        Long picker = user("v50picker");
        Long checker = user("v50checker");
        Long regId = visitWithDrug(seeds.drug("退回摆药测试药").getId(), 3);
        Long checkId = ((Number) checkService.create(regId, picker).get("id")).longValue();

        assertEquals(5447, assertThrows(BizException.class,
                () -> checkService.returnToPicking(checkId, checker, "  ")).code);

        // 建单期间不许再建第二张单（一条处方同时只能挂一张生效核对单）
        assertEquals(5451, assertThrows(BizException.class,
                () -> checkService.create(regId, checker)).code);

        var returned = checkService.returnToPicking(checkId, checker, "数量与处方不符");
        assertEquals("RETURNED", returned.get("status"));
        assertEquals("数量与处方不符", returned.get("return_reason"));
        // 不删记录：退回率与退回原因是药房质控的分母
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from pharm_dispense_check where id = ?", Integer.class, checkId));

        // 行已作废 → 处方可以重新摆药建单
        var again = checkService.create(regId, picker);
        assertEquals("PREPARED", again.get("status"));
        assertNotEquals(checkId, ((Number) again.get("id")).longValue());

        // 已退回的单不能再核对
        assertEquals(5441, assertThrows(BizException.class,
                () -> checkService.pass(checkId, checker)).code);
    }

    // ==================================================================
    // 五、gate 三态 + 覆盖判定按处方行
    // ==================================================================

    @Test
    void gateDefaultsToWarnAndBadValueFallsBackToWarnNotOff() {
        assertEquals("warn", checkService.gate(), "V149 seed 的默认值必须是 warn");
        setGate("blocked");
        assertEquals("warn", checkService.gate(), "坏配置回落 warn，不能回落 off（否则笔误静默关掉核对）");
        setGate("BLOCK");
        assertEquals("block", checkService.gate(), "大小写不敏感");
        setGate("off");
        assertEquals("off", checkService.gate());
    }

    @Test
    void warnDispensesAnyhowWithWarningsAndLeavesATrail() {
        Long operator = user("v50picker");
        Long drugId = seeds.drug("warn发药测试药").getId();
        int before = drugRepository.findById(drugId).orElseThrow().getStock();
        Long regId = visitWithDrug(drugId, 2);

        var eval = checkService.evaluate(regId);
        assertFalse((Boolean) eval.get("checkPassed"));

        var res = checkService.dispenseWithCheck(regId, operator);
        assertEquals("warn", res.get("gate"));
        assertFalse((Boolean) res.get("checkPassed"));
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) res.get("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("未通过发药核对"));

        // 既有链路照常发药、照常扣库存
        assertTrue(orderRepository.findByRegistrationIdAndOrderTypeAndStatusOrderByIdAsc(regId, "DRUG", "DISPENSED")
                .size() > 0);
        assertEquals(before - 2, drugRepository.findById(drugId).orElseThrow().getStock());

        // warn 放行不等于没发生过：台账必须查得到是谁放的
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from pharm_dispense_gate_log
                where registration_id = ? and check_passed is false and operator_id = ?
                """, Integer.class, regId, operator));
    }

    @Test
    void blockRefusesDispenseUntilChecked() {
        Long picker = user("v50picker");
        Long checker = user("v50checker");
        Long drugId = seeds.drug("block发药测试药").getId();
        int before = drugRepository.findById(drugId).orElseThrow().getStock();
        Long regId = visitWithDrug(drugId, 1);
        setGate("block");

        var e = assertThrows(BizException.class, () -> checkService.dispenseWithCheck(regId, picker));
        assertEquals(5448, e.code);
        assertEquals(before, drugRepository.findById(drugId).orElseThrow().getStock(), "拦下时不许扣库存");

        Long checkId = ((Number) checkService.create(regId, picker).get("id")).longValue();
        checkService.pass(checkId, checker);
        var res = checkService.dispenseWithCheck(regId, picker);
        assertTrue((Boolean) res.get("checkPassed"));
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) res.get("warnings");
        assertTrue(warnings.isEmpty());
        assertEquals(before - 1, drugRepository.findById(drugId).orElseThrow().getStock());
        // 通过的那次也记台账：只记未通过的，「未核对发药率」的分母就是假的
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from pharm_dispense_gate_log where registration_id = ? and check_passed is true
                """, Integer.class, regId));
    }

    /**
     * <b>覆盖判定按处方行、不按挂号</b>：上午那张已核对的单不能替下午新开的处方背书。
     * 这正是既有 dispense「整表回查会把上午已发的药也列进下午的发药凭条」的同一个坑。
     */
    @Test
    void aCheckedSheetDoesNotCoverAPrescriptionAddedLater() {
        Long picker = user("v50picker");
        Long checker = user("v50checker");
        Long drugA = seeds.drug("覆盖判定甲药").getId();
        Long drugB = seeds.drug("覆盖判定乙药").getId();

        Long regId = visitWithDrug(drugA, 1);
        Long checkId = ((Number) checkService.create(regId, picker).get("id")).longValue();
        checkService.pass(checkId, checker);
        assertTrue((Boolean) checkService.evaluate(regId).get("checkPassed"));

        // 同一挂号又开一张新处方并收费
        doctorStationService.createOrders(regId,
                List.of(new OrderLine("DRUG", drugB, 1, "口服", "qd", "1粒", 1)), null);
        chargeService.settle(regId, "CASH", null);
        em.flush();

        var eval = checkService.evaluate(regId);
        assertFalse((Boolean) eval.get("checkPassed"), "新开的处方没有核对单，不能被上一张单背书");
        @SuppressWarnings("unchecked")
        var missing = (List<Map<String, Object>>) eval.get("missing");
        assertEquals(1, missing.size());
        assertEquals(drugRepository.findById(drugB).orElseThrow().getName(), missing.get(0).get("itemName"));
    }

    // ==================================================================
    // 六、既有链路未被改坏
    // ==================================================================

    /**
     * 既有 {@link DispenseService#dispense} 与它的 4 个端点一个字节没改：
     * 不建任何核对单、不过 gate，照旧整单发药并扣库存（错误码 6001 亦原样）。
     */
    @Test
    void legacyDispenseStillWorksWithoutAnyCheck() {
        Long drugId = seeds.drug("既有链路回归药").getId();
        int before = drugRepository.findById(drugId).orElseThrow().getStock();
        Long regId = visitWithDrug(drugId, 4);

        var dispensed = dispenseService.dispense(regId);
        assertEquals(1, dispensed.size());
        assertEquals("DISPENSED", dispensed.get(0).getStatus());
        assertEquals(before - 4, drugRepository.findById(drugId).orElseThrow().getStock());

        assertEquals(6001, assertThrows(BizException.class, () -> dispenseService.dispense(regId)).code);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from pharm_dispense_gate_log where registration_id = ?", Integer.class, regId),
                "既有端点不经过本 gate，也就记不到台账——这是本版如实留的缺口，不是断言写错了");
    }

    /**
     * 端点契约：Service 有而 Controller 没有等于没交付——核对单永远建不出来，
     * 「摆药人≠核对人」的行级断言就恒真而毫无意义。这条钉的就是入口本身，
     * 且按 v50 e2e 的调用形状走（GET 总览 / POST 建单 / PUT 核对）。
     */
    @Test
    void controllerExposesTheWholeFlowIncludingTheTwoPersonRule() {
        Long picker = user("v50picker");
        user("v50checker");
        Long regId = visitWithDrug(seeds.drug("端点契约测试药").getId(), 1);

        var overview = controller.overview(regId).getData();
        assertNotNull(overview);
        @SuppressWarnings("unchecked")
        var ov = (Map<String, Object>) overview;
        assertEquals("warn", ov.get("gate"));
        assertEquals(Boolean.FALSE, ov.get("checkPassed"));

        var created = controller.createByBody(
                new cn.hip.outpatient.web.DispenseCheckController.CreateReq(regId), auth("v50picker"));
        assertEquals(0, created.getCode());
        @SuppressWarnings("unchecked")
        var sheet = (Map<String, Object>) created.getData();
        Long checkId = ((Number) sheet.get("id")).longValue();
        assertEquals(picker, ((Number) sheet.get("picker_id")).longValue());

        // 摆药人自己核对 → 5443（与 gate 无关）
        assertEquals(5443, controller.pass(checkId, auth("v50picker")).getCode());
        // 换人 → 通过
        assertEquals(0, controller.pass(checkId, auth("v50checker")).getCode());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) controller.overview(regId).getData()).get("checkPassed"));
    }

    @Test
    void createRejectsRegistrationWithoutChargedDrugOrders() {
        Long picker = user("v50picker");
        Long regId = bareVisit();
        assertEquals(5442, assertThrows(BizException.class, () -> checkService.create(regId, picker)).code);
        assertEquals(5440, assertThrows(BizException.class, () -> checkService.detail(-1L)).code);
        assertEquals(5444, assertThrows(BizException.class, () -> checkService.create(regId, null)).code);
    }

    /** 记录查询默认取业务「今天」，不能用裸 LocalDate.now() 的 JVM 时区 */
    @Test
    void recordsDefaultToBusinessToday() {
        Long picker = user("v50picker");
        Long regId = visitWithDrug(seeds.drug("记录查询测试药").getId(), 1);
        checkService.create(regId, picker);

        var today = checkService.records(null, null, null);
        assertEquals(BusinessDates.today().toString(), today.get("date"));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) today.get("items");
        assertTrue(items.stream().anyMatch(i -> regId.equals(((Number) i.get("registration_id")).longValue())));

        @SuppressWarnings("unchecked")
        var yesterday = (List<Map<String, Object>>) checkService
                .records(BusinessDates.today().minusDays(1), null, null).get("items");
        assertTrue(yesterday.stream().noneMatch(i -> regId.equals(((Number) i.get("registration_id")).longValue())),
                "日窗口是半开区间，今天的单不能落进昨天");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void setGate(String value) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?",
                value, DispenseCheckService.GATE_KEY);
        configReader.evict(DispenseCheckService.GATE_KEY);
    }

    private org.springframework.security.core.Authentication auth(String username) {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                username, null, List.of());
    }

    private Long user(String name) {
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values (?, 'x', ?, true) on conflict (username) do nothing
                """, name, name);
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, name);
    }

    private Long firstLineId(Long checkId) {
        return jdbc.queryForObject(
                "select id from pharm_dispense_check_line where check_id = ? order by id limit 1",
                Long.class, checkId);
    }

    private Long bareVisit() {
        Patient p = new Patient();
        p.setName("发药核对测试" + System.nanoTime());
        p.setSex("U");
        Long pid = patientService.register(p).getId();

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(9);
        s = scheduleRepository.save(s);

        Long regId = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(regId, null);
        return regId;
    }

    private Long visitWithDrug(Long drugId, int qty) {
        Long regId = bareVisit();
        doctorStationService.createOrders(regId,
                List.of(new OrderLine("DRUG", drugId, qty, "口服", "tid", "1粒", 3)), null);
        chargeService.settle(regId, "CASH", null);
        em.flush();   // JPA 写入未刷盘时对 JdbcTemplate 不可见
        return regId;
    }

    private Long visitWithDrugs(List<Long> drugIds) {
        Long regId = bareVisit();
        doctorStationService.createOrders(regId,
                drugIds.stream().map(d -> new OrderLine("DRUG", d, 1, "口服", "bid", "1粒", 2)).toList(), null);
        chargeService.settle(regId, "CASH", null);
        em.flush();
        return regId;
    }
}
