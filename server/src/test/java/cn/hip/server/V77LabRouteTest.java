package cn.hip.server;

import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.core.web.AuthController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.service.LabRouteService;
import cn.hip.platform.masterdata.service.LabRouteService.Resolved;
import cn.hip.platform.masterdata.service.LabRouteService.RuleReq;
import cn.hip.platform.masterdata.web.LabRouteRuleController;
import cn.hip.server.web.PrintReportController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v77 车道 A：检验申请按流向动态确定执行科室（偏离表 1016★「根据流向自动获取执行科室」）。
 *
 * <p>覆盖规划节点名的全部口径：五个错误码（5910–5914）；具体度 > priority > id；停用规则不命中；
 * 规则指向停用科室回落下一候选/字典；开关关闭回落字典；createOrders 对 LAB 落 exec_dept_id 与返回体 execDeptName、
 * 对无规则且字典无执行科室的 LAB 落 null（对照组）；导诊单/检验申请单 exec_dept_name 取规则科室优先于字典科室；
 * /lis/pending 与 /lis/samples 按 deptId 过滤；/auth/me 回 deptId/deptName。
 *
 * <p>全部 {@code @Transactional}：只断言落库值与返回码，不测回滚语义（方法论④）。
 * 造前置数据（科室、收费项目、把医嘱变成 CHARGED）走 jdbc 或既有产品路径，<b>被测写路径</b>只走
 * {@link LabRouteRuleController} / {@link DoctorStationService#createOrders}。
 * 开关用例事务内直写 sys_config 后立刻 evict，并 {@code @AfterEach evictAll}（方法论⑤：缓存不得带毒出用例）。
 * 时间一律取 {@code BusinessDates.today()}，不写字面量。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V77LabRouteTest {

    @Autowired LabRouteRuleController ruleController;
    @Autowired LabRouteService labRouteService;
    @Autowired ChargeItemRepository chargeItemRepository;
    @Autowired ConfigReader configReader;
    @Autowired PrintReportController printController;
    @Autowired MedTechController medTechController;
    @Autowired AuthController authController;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    @AfterEach
    void evictConfigCache() {
        configReader.evictAll();
    }

    // ==================== 造数 ====================

    private Long newDept(String code, boolean enabled) {
        jdbc.update("insert into sys_dept(name, code, type, sort_no, enabled) values (?, ?, 'MEDTECH', 99, ?)",
                "流向用例科室" + code, code, enabled);
        return jdbc.queryForObject("select id from sys_dept where code = ?", Long.class, code);
    }

    private String deptName(Long id) {
        return jdbc.queryForObject("select name from sys_dept where id = ?", String.class, id);
    }

    private Long newItem(String code, String category, Long dictExecDept) {
        jdbc.update("""
                insert into md_charge_item(code, name, category, unit, price, enabled, exec_dept_id)
                values (?, ?, ?, '次', 30.00, true, ?)
                """, code, "流向用例项目-" + code, category, dictExecDept);
        return jdbc.queryForObject("select id from md_charge_item where code = ?", Long.class, code);
    }

    private ChargeItem item(Long id) {
        return chargeItemRepository.findById(id).orElseThrow();
    }

    private Long rule(String name, Long itemId, String spec, Long orderDept, Long execDept, Integer priority) {
        return (Long) ruleController.create(new RuleReq(name, itemId, spec, orderDept, execDept, priority, null))
                .getData().get("id");
    }

    private int codeOf(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(HipBizException.class, e).code;
    }

    /** 建患者 → 排班（指定科室）→ 挂号 → 接诊，返回挂号 id */
    private Long visited(Long deptId) {
        Patient p = new Patient();
        p.setName("流向" + System.nanoTime() % 100000);
        p.setSex("F");
        p.setBirthDate(LocalDate.of(1990, 3, 15));
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(deptId);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, adminId());
        return rid;
    }

    private Long adminId() {
        return jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
    }

    private static OrderLine lab(Long itemId, String specimen) {
        return new OrderLine("LAB", itemId, 1, null, null, null, null,
                null, null, null, null, null, specimen, null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(String docType, Long rid) {
        return (List<Map<String, Object>>) printController.clinicalDoc(docType, rid, null).getData().get("rows");
    }

    // ==================== 规范化 ====================

    @Test
    void specimenNormalizationStripsAllWhitespaceAndUppercases() {
        assertNull(LabRouteService.normalizeSpecimen(null));
        assertNull(LabRouteService.normalizeSpecimen("   "));
        assertEquals("全血", LabRouteService.normalizeSpecimen(" 全 血 "));
        // 第二轮复核（反驳者三）：全角空格 U+3000 / NBSP U+00A0 此前不算空白
        assertEquals("血清", LabRouteService.normalizeSpecimen("血\u3000清"));
        assertEquals("血清", LabRouteService.normalizeSpecimen("血\u00a0清\u00a0"));
        assertEquals("EDTA全血", LabRouteService.normalizeSpecimen("edta 全血"));
        assertEquals("尿液", LabRouteService.normalizeSpecimen("尿\t液\n"));
    }

    // ==================== 五个错误码 ====================

    @Test
    void crudErrorCodes5910To5914AndGeneric4000() {
        Long lab = newItem("T77E1", "LAB", null);
        Long exam = newItem("T77E2", "EXAM", null);
        Long dept = newDept("T77ED1", true);
        Long off = newDept("T77ED2", false);
        Long clinic = newDept("T77ED3", true);

        // 5910 规则不存在
        RuleReq valid = new RuleReq("规则", lab, "全血", clinic, dept, null, null);
        assertEquals(5910, codeOf(() -> ruleController.update(987654321L, valid)));
        assertEquals(5910, codeOf(() -> ruleController.setEnabled(987654321L, true)));
        assertEquals(5910, codeOf(() -> ruleController.delete(987654321L)));

        // 5911 执行科室不存在 / 已停用
        assertEquals(5911, codeOf(() -> ruleController.create(new RuleReq("r", lab, null, null, 987654321L, null, null))));
        assertEquals(5911, codeOf(() -> ruleController.create(new RuleReq("r", lab, null, null, off, null, null))));

        // 5912 收费项目不存在 / 不是检验类
        assertEquals(5912, codeOf(() -> ruleController.create(new RuleReq("r", 987654321L, null, null, dept, null, null))));
        assertEquals(5912, codeOf(() -> ruleController.create(new RuleReq("r", exam, null, null, dept, null, null))));
        // resolve 端点同码
        assertEquals(5912, codeOf(() -> ruleController.resolve(987654321L, null, null)));
        assertEquals(5912, codeOf(() -> ruleController.resolve(exam, null, null)));

        // 5914 开单科室不存在
        assertEquals(5914, codeOf(() -> ruleController.create(new RuleReq("r", lab, null, 987654321L, dept, null, null))));

        // 4000 字段缺失/超长
        assertEquals(4000, codeOf(() -> ruleController.create(new RuleReq("", lab, null, null, dept, null, null))));
        assertEquals(4000, codeOf(() -> ruleController.create(new RuleReq("x".repeat(65), lab, null, null, dept, null, null))));
        assertEquals(4000, codeOf(() -> ruleController.create(new RuleReq("r", lab, null, null, null, null, null))));
        assertEquals(4000, codeOf(() -> ruleController.create(new RuleReq("r", lab, null, null, dept, null, "x".repeat(256)))));
        assertEquals(4000, codeOf(() -> ruleController.create(new RuleReq("r", lab, "x".repeat(33), null, dept, null, null))));

        // 以上全部被拒：零落库
        assertEquals(0, jdbc.queryForObject("select count(*) from lab_route_rule where exec_dept_id in (?,?)",
                Integer.class, dept, off));

        // 5913 同键重复：只在启用规则间判、标本按规范化值比、null 与 null 相等、编辑排除自身、启用停用规则也判
        Long r1 = rule("全血→科室", lab, " 全 血 ", clinic, dept, null);
        assertEquals("全血", jdbc.queryForObject("select specimen_type from lab_route_rule where id = ?", String.class, r1),
                "落库存规范化值");
        assertEquals(5913, codeOf(() -> rule("撞键", lab, "全血", clinic, dept, 5)), "同三元组（含空白差异）→ 5913");
        Long r2 = rule("项目通配", lab, null, null, dept, null);
        assertEquals(5913, codeOf(() -> rule("撞键2", lab, "  ", null, dept, null)), "空白标本 = null，与 null 相等");
        assertEquals(0, ruleController.update(r1, new RuleReq("改名不撞自己", lab, "全血", clinic, dept, 7, "备注")).getCode());
        assertEquals(5913, codeOf(() -> ruleController.update(r2, new RuleReq("改成 r1 的键", lab, "全血", clinic, dept, null, null))));
        assertEquals(0, ruleController.setEnabled(r2, false).getCode());
        Long r3 = rule("停用后键空出来", lab, null, null, dept, null);
        assertEquals(5913, codeOf(() -> ruleController.setEnabled(r2, true)), "启用一条停用规则时也判同键");
        assertEquals(0, ruleController.setEnabled(r3, false).getCode());
        assertEquals(0, ruleController.setEnabled(r2, true).getCode(), "键让出来后可以再启用");
        // 停用规则可以随意编辑成任何键（不参与匹配也不占键）
        assertEquals(0, ruleController.update(r3, new RuleReq("停用的随便改", lab, "全血", clinic, dept, null, null)).getCode());
        // 删除真删
        assertEquals(0, ruleController.delete(r3).getCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from lab_route_rule where id = ?", Integer.class, r3));

        // 列表：includeDisabled=false 只列启用；排序与匹配一致（具体度 7 的 r1 先于 4 的 r2）
        var enabledOnly = ruleController.list(false).getData().stream()
                .filter(m -> List.of(r1, r2).contains(((Number) m.get("id")).longValue())).toList();
        assertEquals(List.of(r1, r2), enabledOnly.stream().map(m -> ((Number) m.get("id")).longValue()).toList());
        assertEquals(deptName(dept), enabledOnly.get(0).get("execDeptName"));
        assertEquals(deptName(clinic), enabledOnly.get(0).get("orderDeptName"));
        assertEquals("T77E1", enabledOnly.get(0).get("chargeItemCode"));
        assertEquals(7, enabledOnly.get(0).get("priority"));
        assertEquals("备注", enabledOnly.get(0).get("remark"));
        assertEquals(0, ruleController.setEnabled(r2, false).getCode());
        assertFalse(ruleController.list(false).getData().stream().anyMatch(m -> ((Number) m.get("id")).longValue() == r2));
        assertTrue(ruleController.list(true).getData().stream().anyMatch(m -> ((Number) m.get("id")).longValue() == r2));
    }

    // ==================== 匹配口径 ====================

    @Test
    void specificityBeatsPriorityBeatsId() {
        Long lab = newItem("T77M1", "LAB", null);
        Long clinic = newDept("T77MC", true);
        Long dA = newDept("T77MA", true);
        Long dB = newDept("T77MB", true);
        Long dC = newDept("T77MD", true);

        // 项目通配 priority 1（具体度 4）vs 项目+标本 priority 100（具体度 6）→ 具体度赢
        rule("项目", lab, null, null, dA, 1);
        Long r6 = rule("项目+标本", lab, "全血", null, dB, 100);
        Resolved r = labRouteService.resolve(item(lab), "全 血", clinic);
        assertEquals(dB, r.execDeptId());
        assertEquals("RULE", r.source());
        assertEquals(r6, r.ruleId());
        assertEquals("项目+标本", r.ruleName());
        assertEquals(deptName(dB), r.execDeptName());

        // 同具体度 6 的另一组键（项目+标本=尿液）不匹配全血医嘱；标本不匹配时退到具体度 4 的项目通配
        rule("项目+尿液", lab, "尿液", null, dC, 1);
        assertEquals(dA, labRouteService.resolve(item(lab), "痰", clinic).execDeptId(), "标本不匹配 → 项目通配");
        assertEquals(dA, labRouteService.resolve(item(lab), null, clinic).execDeptId(), "医嘱无标本时带标本键的规则不匹配");

        // 具体度相同（都是 5 = 项目+开单科室）时 priority 小者先：两条不同开单科室各一条，再用 jdbc 造一条同键低优先级
        // （同键启用规则产品路径被 5913 拦，这里直写模拟历史/竞态数据，验证排序本身的确定性）
        Long rP = rule("项目+科室 p100", lab, null, clinic, dC, 100);
        jdbc.update("""
                insert into lab_route_rule(name, charge_item_id, order_dept_id, exec_dept_id, priority)
                values ('项目+科室 p10 直写', ?, ?, ?, 10)
                """, lab, clinic, dA);
        assertEquals(dA, labRouteService.resolve(item(lab), null, clinic).execDeptId(), "同具体度 → priority 小者先");
        // id 升序：再直写一条同键同 priority 指向 dB，id 更大 → 仍取先建的 dA
        jdbc.update("""
                insert into lab_route_rule(name, charge_item_id, order_dept_id, exec_dept_id, priority)
                values ('项目+科室 p10 直写2', ?, ?, ?, 10)
                """, lab, clinic, dB);
        assertEquals(dA, labRouteService.resolve(item(lab), null, clinic).execDeptId(), "同具体度同 priority → id 小者先");
        assertNotNull(rP);

        // 全三键（具体度 7）压过一切
        Long d7 = newDept("T77M7", true);
        rule("三键", lab, "全血", clinic, d7, 999);
        assertEquals(d7, labRouteService.resolve(item(lab), "全血", clinic).execDeptId());
        // 开单科室不同则三键不匹配，退回项目+标本
        Long other = newDept("T77MO", true);
        assertEquals(dB, labRouteService.resolve(item(lab), "全血", other).execDeptId());
    }

    @Test
    void disabledRuleDoesNotMatchAndRuleToDisabledDeptIsSkipped() {
        Long dict = newDept("T77DD", true);
        Long lab = newItem("T77D1", "LAB", dict);
        Long labNoDict = newItem("T77D2", "LAB", null);
        Long dOff = newDept("T77DO", true);
        Long dOk = newDept("T77DK", true);

        // 停用规则不命中 → 回落字典 ITEM
        Long rd = rule("停用的", lab, null, null, dOk, null);
        ruleController.setEnabled(rd, false);
        Resolved r = labRouteService.resolve(item(lab), "全血", null);
        assertEquals("ITEM", r.source());
        assertEquals(dict, r.execDeptId());
        assertEquals(deptName(dict), r.execDeptName());
        assertNull(r.ruleId());

        // 规则指向的科室事后停用 → 跳过该条、取下一候选
        Long rSpecific = rule("项目+标本→将停用科室", lab, "全血", null, dOff, null);
        Long rGeneric = rule("项目通配→正常科室", lab, null, null, dOk, null);
        assertEquals(rSpecific, labRouteService.resolve(item(lab), "全血", null).ruleId(), "前提：停用前命中具体规则");
        jdbc.update("update sys_dept set enabled = false where id = ?", dOff);
        r = labRouteService.resolve(item(lab), "全血", null);
        assertEquals(rGeneric, r.ruleId(), "指向停用科室的规则被跳过，取下一候选");
        assertEquals(dOk, r.execDeptId());
        // 下一候选也没有 → 回落字典；字典也没有 → NONE
        ruleController.setEnabled(rGeneric, false);
        assertEquals("ITEM", labRouteService.resolve(item(lab), "全血", null).source());
        Resolved none = labRouteService.resolve(item(labNoDict), "全血", null);
        assertEquals("NONE", none.source());
        assertNull(none.execDeptId());
        assertNull(none.execDeptName());

        // resolve 端点与 service 同一结果
        var body = ruleController.resolve(lab, "全血", null).getData();
        assertEquals("ITEM", body.get("source"));
        assertEquals(dict, body.get("execDeptId"));
        assertEquals(deptName(dict), body.get("execDeptName"));
        assertTrue(body.containsKey("ruleId") && body.get("ruleId") == null, "无值的键也在，值为 null");
    }

    @Test
    void switchOffFallsBackToDictionary() {
        Long dict = newDept("T77SD", true);
        Long lab = newItem("T77S1", "LAB", dict);
        Long dRule = newDept("T77SR", true);
        Long rid = rule("开关用例", lab, null, null, dRule, null);
        assertEquals(dRule, labRouteService.resolve(item(lab), null, null).execDeptId(), "前提：开关开时命中");

        // 事务内直写配置 + 立刻 evict（方法论⑤；@AfterEach evictAll 保证不带毒出用例）
        assertEquals(1, jdbc.update("update sys_config set cfg_value = '0' where cfg_key = 'lab.route.enabled'"),
                "V174 必须已种下该键");
        configReader.evict(LabRouteService.CFG_KEY);
        Resolved r = labRouteService.resolve(item(lab), null, null);
        assertEquals("ITEM", r.source(), "开关关闭 → 不查规则，直接回落字典");
        assertEquals(Boolean.FALSE, r.routeEnabled(), "开关关闭须在返回体上可辨（页面试算据此改口）");
        assertEquals(dict, r.execDeptId());
        assertNull(r.ruleId());

        jdbc.update("update sys_config set cfg_value = '1' where cfg_key = 'lab.route.enabled'");
        configReader.evict(LabRouteService.CFG_KEY);
        assertEquals(rid, labRouteService.resolve(item(lab), null, null).ruleId(), "开关恢复后规则重新生效");
    }

    // ==================== 开单落值 + 三条读路径 ====================

    @Test
    void createOrdersWritesSnapshotForLabOnlyAndEchoesDeptName() {
        Long clinic = newDept("T77CC", true);
        Long dict = newDept("T77CD", true);
        Long routed = newDept("T77CR", true);
        Long labRouted = newItem("T77C1", "LAB", dict);        // 字典科室 dict，规则改派 routed
        Long labBare = newItem("T77C2", "LAB", null);          // 无规则、字典也空 → null（对照组）
        Long examDict = newItem("T77C3", "EXAM", dict);        // EXAM 不落值
        rule("改派", labRouted, "全血", null, routed, null);

        Long rid = visited(clinic);
        List<OutpOrder> orders = doctorStationService.createOrders(rid, List.of(
                lab(labRouted, "全 血"),
                lab(labBare, "全血"),
                new OrderLine("EXAM", examDict, 1, null, null, null, null)), adminId());
        em.flush();

        OutpOrder a = orders.get(0);
        assertEquals(routed, a.getExecDeptId(), "LAB 命中规则 → 落规则科室（医嘱填写值带空白也按规范化匹配）");
        assertEquals(deptName(routed), a.getExecDeptName(), "返回体回显规则科室名");
        assertEquals(routed, jdbc.queryForObject("select exec_dept_id from outp_order where id = ?", Long.class, a.getId()));

        OutpOrder b = orders.get(1);
        assertNull(b.getExecDeptId(), "对照组：无规则且字典无执行科室 → null");
        assertNull(b.getExecDeptName());

        OutpOrder c = orders.get(2);
        assertNull(c.getExecDeptId(), "EXAM 本轮不落值（读路径 coalesce 回落字典）");
        assertNull(c.getExecDeptName());

        // 落值即快照：此后改规则不回改已开医嘱
        Long another = newDept("T77CX", true);
        ruleController.update((Long) ruleController.list(false).getData().stream()
                .filter(m -> "改派".equals(m.get("name"))).map(m -> ((Number) m.get("id")).longValue()).findFirst().orElseThrow(),
                new RuleReq("改派", labRouted, "全血", null, another, null, null));
        assertEquals(routed, jdbc.queryForObject("select exec_dept_id from outp_order where id = ?", Long.class, a.getId()),
                "改规则不回改已开医嘱");
    }

    @Test
    void printedSheetsPreferRuleDeptOverDictionaryDept() {
        Long clinic = newDept("T77PC", true);
        Long dict = newDept("T77PD", true);
        Long routed = newDept("T77PR", true);
        Long labRouted = newItem("T77P1", "LAB", dict);
        Long labDictOnly = newItem("T77P2", "LAB", dict);
        Long examDict = newItem("T77P3", "EXAM", dict);
        rule("导诊改派", labRouted, null, null, routed, null);

        Long rid = visited(clinic);
        doctorStationService.createOrders(rid, List.of(
                lab(labRouted, "全血"),
                lab(labDictOnly, "全血"),
                new OrderLine("EXAM", examDict, 1, null, null, null, null)), adminId());
        em.flush();

        var guide = rowsOf("guide-sheet", rid).stream().collect(java.util.stream.Collectors.toMap(
                r -> String.valueOf(r.get("item_code")), r -> r));
        assertEquals(deptName(routed), guide.get("T77P1").get("exec_dept_name"), "导诊单：规则科室优先于字典科室");
        assertEquals(deptName(dict), guide.get("T77P2").get("exec_dept_name"), "无规则的 LAB 仍回落字典科室");
        assertEquals(deptName(dict), guide.get("T77P3").get("exec_dept_name"), "EXAM 不落值，coalesce 回落字典");

        var labReq = rowsOf("lab-request", rid).stream().collect(java.util.stream.Collectors.toMap(
                r -> String.valueOf(r.get("item_code")), r -> r));
        assertEquals(2, labReq.size());
        assertEquals(deptName(routed), labReq.get("T77P1").get("exec_dept_name"), "检验申请单：规则科室优先");
        assertEquals(deptName(dict), labReq.get("T77P2").get("exec_dept_name"));
    }

    @Test
    void lisQueuesFilterByExecDeptAndCarryTwoNewColumns() {
        Long clinic = newDept("T77LC", true);
        Long dict = newDept("T77LD", true);
        Long dX = newDept("T77LX", true);
        Long dY = newDept("T77LY", true);
        Long labX = newItem("T77L1", "LAB", dict);     // 规则 → dX
        Long labY = newItem("T77L2", "LAB", null);     // 规则 → dY
        Long labDict = newItem("T77L3", "LAB", dict);  // 无规则 → 字典 dict
        Long labNone = newItem("T77L4", "LAB", null);  // 两处都没有 → null，任何 deptId 过滤都不可见
        rule("→X", labX, null, null, dX, null);
        rule("→Y", labY, null, null, dY, null);

        Long rid = visited(clinic);
        List<OutpOrder> orders = doctorStationService.createOrders(rid, List.of(
                lab(labX, "全血"), lab(labY, "尿液"), lab(labDict, null), lab(labNone, null)), adminId());
        Long oX = orders.get(0).getId(), oY = orders.get(1).getId(), oD = orders.get(2).getId(), oN = orders.get(3).getId();
        // 队列只认 CHARGED：走收费产品路径把医嘱变成已收费（造前置数据）
        chargeService.settle(rid, "CASH", null);
        em.flush();

        var all = medTechController.lisPending(null).getData();
        var ids = all.stream().map(m -> ((Number) m.get("order_id")).longValue()).toList();
        assertTrue(ids.containsAll(List.of(oX, oY, oD, oN)), "不传 deptId：四条全在");
        var rowX = all.stream().filter(m -> ((Number) m.get("order_id")).longValue() == oX).findFirst().orElseThrow();
        assertEquals(dX, rowX.get("exec_dept_id"));
        assertEquals(deptName(dX), rowX.get("exec_dept_name"));
        var rowD = all.stream().filter(m -> ((Number) m.get("order_id")).longValue() == oD).findFirst().orElseThrow();
        assertEquals(dict, rowD.get("exec_dept_id"), "历史/无规则行回落字典");
        var rowN = all.stream().filter(m -> ((Number) m.get("order_id")).longValue() == oN).findFirst().orElseThrow();
        assertTrue(rowN.containsKey("exec_dept_id") && rowN.get("exec_dept_id") == null, "两处都没有 → 列在、值 null");
        // 既有列未丢
        for (String k : List.of("order_id", "group_no", "item_name", "patient_name", "sex", "specimen_type",
                "sampling_site", "urgent", "remark")) {
            assertTrue(rowX.containsKey(k), "既有列 " + k + " 不得丢");
        }

        var onlyX = medTechController.lisPending(dX).getData().stream()
                .map(m -> ((Number) m.get("order_id")).longValue()).toList();
        assertTrue(onlyX.contains(oX));
        assertFalse(onlyX.contains(oY));
        assertFalse(onlyX.contains(oD));
        assertFalse(onlyX.contains(oN));
        var onlyDict = medTechController.lisPending(dict).getData().stream()
                .map(m -> ((Number) m.get("order_id")).longValue()).toList();
        assertTrue(onlyDict.contains(oD), "按字典科室过滤能看到回落行");
        assertFalse(onlyDict.contains(oX), "规则改派后的行不再出现在字典科室队列（快照优先）");
        assertTrue(medTechController.lisPending(987654321L).getData().isEmpty(), "不存在的科室 → 空");

        // 采样后进入标本队列，同一口径过滤
        medTechController.collect(oX, null);
        medTechController.collect(oY, null);
        var samplesX = medTechController.samples(dX).getData();
        assertTrue(samplesX.stream().anyMatch(m -> ((Number) m.get("order_id")).longValue() == oX));
        assertFalse(samplesX.stream().anyMatch(m -> ((Number) m.get("order_id")).longValue() == oY));
        var sX = samplesX.stream().filter(m -> ((Number) m.get("order_id")).longValue() == oX).findFirst().orElseThrow();
        assertEquals(deptName(dX), sX.get("exec_dept_name"));
        assertTrue(sX.containsKey("barcode") && sX.containsKey("status") && sX.containsKey("patient_name"));
        var samplesAll = medTechController.samples(null).getData();
        assertTrue(samplesAll.stream().map(m -> ((Number) m.get("order_id")).longValue()).toList().containsAll(List.of(oX, oY)));
    }

    // ==================== /auth/me ====================

    @Test
    void meCarriesDeptIdAndDeptNameNullWhenUnassigned() {
        var auth = new UsernamePasswordAuthenticationToken("admin", null, List.of());
        jdbc.update("update sys_user set dept_id = null where username = 'admin'");
        em.clear();
        var me = authController.me(auth).getData();
        assertTrue(me.containsKey("deptId") && me.get("deptId") == null, "无科室：键在、值 null");
        assertTrue(me.containsKey("deptName") && me.get("deptName") == null);
        // 既有键一个不少
        for (String k : List.of("id", "username", "realName", "roles", "mustChangePassword", "menus")) {
            assertTrue(me.containsKey(k), "既有键 " + k);
        }

        Long dept = newDept("T77ME", true);
        jdbc.update("update sys_user set dept_id = ? where username = 'admin'", dept);
        em.clear();
        me = authController.me(auth).getData();
        assertEquals(dept, me.get("deptId"));
        assertEquals(deptName(dept), me.get("deptName"));
    }
}
