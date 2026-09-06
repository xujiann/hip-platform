package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DispenseService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.PharmPickService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.PharmPickController;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v50 车道B 回归：摆药单 / 窗口分配 / 预调剂状态机 / 摆药工作台。
 *
 * <p><b>本类断言的三件事</b>：
 * <ol>
 *   <li><b>状态流转是原子抢占</b>——每一档的第二次调用都必须落空返 5423，而不是把同一批药配两遍。
 *       真并发（多线程）在 {@code V50PharmPickingConcurrencyTest} 里另测，
 *       这里测的是条件更新本身的语义。</li>
 *   <li><b>两道 gate 默认 warn</b>——未逐项确认、未分配窗口都<b>不拦截</b>，只回带 warnings；
 *       只有显式配成 block 才返 5424/5425；<b>配置写坏时回落 warn 而不是 off</b>
 *       （{@link #badGateValueFallsBackToWarnNotOff()} 专测这一条：
 *       回落成 off 等于把写错的配置值变成静默关闭校验）。</li>
 *   <li><b>既有整单一步发药链路没被改坏</b>——{@link #legacyDispenseStillDeductsStockWithPickingOrderPresent()}
 *       在有摆药单在途的情况下走既有 {@code DispenseService.dispense}，
 *       库存照扣、医嘱照样 DISPENSED、摆药单不受影响。</li>
 * </ol>
 *
 * <p><b>夹具刻意绕开 ChargeService</b>：直接 {@code update outp_order set status='CHARGED'}。
 * 本车道测的是摆药，收费链路有自己的回归（ChargeAndDispenseTest），
 * 借道 settle() 只会让本类在收费侧一改动就跟着红，指不到根因。
 *
 * <p><b>JPA↔JdbcTemplate 混用</b>：服务层走 JdbcTemplate，夹具走 JPA，
 * 故建完医嘱必须 {@code flush()}（JPA→JDBC 可见）、jdbc 改完状态必须 {@code clear()}（JDBC→JPA 可见）。
 * 这是本仓的老坑。
 */
@SpringBootTest
@Transactional
@WithMockUser(username = "admin", roles = {"ADMIN", "PHARMACIST"})
class V50PharmPickingTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired PharmPickController controller;
    @Autowired PharmPickService service;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired DispenseService dispenseService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired OutpOrderRepository orderRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Long regId;
    private Long drugAId;
    private Long drugBId;
    private int qtyA;
    private int qtyB;
    private Authentication auth;

    @BeforeEach
    void setUpChargedPrescription() {
        auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals(1, jdbc.queryForObject(
                        "select count(*) from sys_user where username = 'admin'", Integer.class),
                "夹具前提：admin 账号必须存在，否则一切写动作都会返 5430 而不是被测的码");

        Patient p = new Patient();
        p.setName("摆药测试");
        p.setSex("U");
        Long pid = patientService.register(p).getId();

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());   // 禁用裸 LocalDate.now()
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(9);
        s = scheduleRepository.save(s);

        regId = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(regId, null);

        drugAId = seeds.drug("阿莫西林").getId();
        drugBId = seeds.drug("摆药测试用药B").getId();
        qtyA = 2;
        qtyB = 3;
        doctorStationService.createOrders(regId, List.of(
                new OrderLine("DRUG", drugAId, qtyA, "口服", "tid", "1粒", 3),
                new OrderLine("DRUG", drugBId, qtyB, "口服", "bid", "1片", 3)), null);

        em.flush();   // JPA→JDBC 可见
        jdbc.update("update outp_order set status = 'CHARGED' where registration_id = ?", regId);
        em.clear();   // JDBC→JPA 可见
    }

    @AfterEach
    void resetConfigCache() {
        // 事务回滚会还原 sys_config 的行，但 ConfigReader 有 30 秒缓存，不清会污染后续用例
        configReader.evictAll();
    }

    // ================= 一、队列与建单 =================

    @Test
    void queueListsRegistrationUntilPickingOrderTakesIt() {
        var before = ok(controller.queue(regId, null));
        assertEquals(1, rows(before).size(), "已收费未建单的挂号应在待摆药队列里");
        var row = rows(before).get(0);
        assertEquals(2L, num(row.get("item_count")), "两条药品医嘱");
        assertEquals((long) (qtyA + qtyB), num(row.get("total_qty")));

        Long pickingId = createPicking(null);
        assertNotNull(pickingId);

        assertEquals(0, rows(ok(controller.queue(regId, null))).size(),
                "建单后这批药已被收走，不该再出现在待摆药队列（否则两个药师会各配一遍）");
    }

    @Test
    void createSnapshotsLinesAndStartsAtPending() {
        var body = ok(controller.create(new PharmPickController.CreateReq(regId, null), auth));
        assertEquals("PENDING", body.get("status"));
        assertEquals("待摆药", body.get("statusName"));
        assertTrue(String.valueOf(body.get("pick_no")).startsWith("BY"), "单号 BY+日戳+序列");
        assertEquals(2, ((List<?>) body.get("lines")).size());

        @SuppressWarnings("unchecked")
        var lines = (List<Map<String, Object>>) body.get("lines");
        // 应摆量是建单时对 outp_order.qty 的快照；实摆量此刻必须为空（还没摆）
        for (var l : lines) {
            assertNull(l.get("picked_qty"), "刚建单不该有实摆量");
            assertNull(l.get("picked_at"));
            assertEquals("CHARGED", l.get("order_status"), "摆药不改医嘱状态");
        }
        assertEquals((long) (qtyA + qtyB),
                lines.stream().mapToLong(l -> num(l.get("qty"))).sum());
    }

    @Test
    void duplicateCreateOnSameRegistrationReturns5421() {
        createPicking(null);
        var dup = controller.create(new PharmPickController.CreateReq(regId, null), auth);
        assertEquals(5421, dup.getCode());
        assertTrue(dup.getMessage().contains("在途摆药单"), dup.getMessage());
    }

    @Test
    void createWithoutChargedDrugsReturns5422() {
        jdbc.update("update outp_order set status = 'CREATED' where registration_id = ?", regId);
        assertEquals(5422, controller.create(
                new PharmPickController.CreateReq(regId, null), auth).getCode());
        // 挂号不存在同码（纸面上都是「这张单建不成立」）
        assertEquals(5422, controller.create(
                new PharmPickController.CreateReq(-999L, null), auth).getCode());
    }

    // ================= 二、状态机原子抢占 =================

    @Test
    void happyPathPendingToDispensed() {
        Long id = createPicking("W1");
        assertEquals("PICKING", ok(controller.start(id, auth)).get("status"));

        for (Long lineId : lineIds(id)) {
            assertEquals(0, pick(id, lineId, null).getCode());
        }
        var prepared = ok(controller.prepared(id, auth));
        assertEquals("PREPARED", prepared.get("status"));
        assertEquals("已配好", prepared.get("statusName"));
        assertNotNull(prepared.get("prepared_at"));
        assertEquals(List.of(), prepared.get("warnings"), "逐项确认齐、窗口已分配，不该有告警");

        var dispensed = ok(controller.markDispensed(id, auth));
        assertEquals("DISPENSED", dispensed.get("status"));
        assertNotNull(dispensed.get("dispensed_at"));
        assertEquals(2L, count("select count(*) from pharm_picking_line "
                + "where picking_id = ? and released", id),
                "收口必须一并释放明细对医嘱的占用，否则退药后再也建不了新单");

        // 收口后该挂号可以再建新单（部分唯一索引只约束在途三档）
        jdbc.update("update outp_order set status = 'CHARGED' where registration_id = ?", regId);
        assertEquals(0, controller.create(
                new PharmPickController.CreateReq(regId, null), auth).getCode());
    }

    /**
     * <b>本类抓出来的真缺陷的回归</b>：收口时若不释放明细对医嘱的占用，
     * 退药（{@code DispenseService.returnDrug} 把 outp_order 打回 CHARGED）之后
     * 那批药重新进待摆药队列，却会被 {@code uq_pharm_pick_line_live} 上那条陈旧占用挡住，
     * 表现为「这个患者退过药以后再也建不了摆药单」——而且是<b>静默</b>的：
     * 队列里根本看不到他，不是报错而是人间蒸发。
     */
    @Test
    void returnedDrugReentersQueueAfterCollectionReleasedTheLine() {
        Long id = createPicking("W1");
        controller.start(id, auth);
        pickAll(id);
        controller.prepared(id, auth);

        em.clear();
        dispenseService.dispense(regId);
        assertEquals(1, service.markDispensedByRegistration(regId, uid()));

        em.clear();
        Long firstOrderId = orderRepository.findByRegistrationIdOrderByIdAsc(regId).get(0).getId();
        dispenseService.returnDrug(firstOrderId, null);   // 既有退药链路，本车道一字未改

        var q = ok(controller.queue(regId, null));
        assertEquals(1, rows(q).size(), "退回来的药必须重新出现在待摆药队列");
        assertEquals(1L, num(rows(q).get(0).get("item_count")), "只有退回的那一条");
        assertEquals(0, controller.create(
                new PharmPickController.CreateReq(regId, null), auth).getCode(),
                "退回来的药必须能重新建摆药单");
    }

    @Test
    void everyTransitionIsAnAtomicClaimAndSecondCallFails() {
        Long id = createPicking("W1");

        // 未开始就置已配好
        assertEquals(5423, controller.prepared(id, auth).getCode());
        // 未配好就标记已发药
        assertEquals(5423, controller.markDispensed(id, auth).getCode());

        assertEquals(0, controller.start(id, auth).getCode());
        assertEquals(5423, controller.start(id, auth).getCode(), "重复开始摆药必须落空");

        pickAll(id);
        assertEquals(0, controller.prepared(id, auth).getCode());
        assertEquals(5423, controller.prepared(id, auth).getCode(), "重复置已配好必须落空");

        assertEquals(0, controller.markDispensed(id, auth).getCode());
        assertEquals(5423, controller.markDispensed(id, auth).getCode());
        // 已收口的单不再改派窗口——那是改历史
        assertEquals(5423, controller.assignWindow(id,
                new PharmPickController.WindowAssignReq("W2"), auth).getCode());
    }

    @Test
    void unknownPickingOrderReturns5420() {
        assertEquals(5420, controller.detail(-999L).getCode());
        assertEquals(5420, controller.start(-999L, auth).getCode());
        assertEquals(5420, controller.prepared(-999L, auth).getCode());
        assertEquals(5420, controller.cancel(-999L,
                new PharmPickController.CancelReq("测试"), auth).getCode());
    }

    // ================= 三、逐项确认 =================

    @Test
    void pickedQtyMustBeWithinOrderedQty() {
        Long id = createPicking(null);
        controller.start(id, auth);
        Long lineId = lineIds(id).get(0);
        int ordered = (int) num(lineOf(id, lineId).get("qty"));

        assertEquals(5428, pick(id, lineId, 0).getCode());
        assertEquals(5428, pick(id, lineId, -1).getCode());
        assertEquals(5428, pick(id, lineId, ordered + 1).getCode(),
                "实摆量大于应摆量是配错药，必须拦");

        // 缺货部分摆是合法的：实摆量允许小于应摆量
        assertEquals(0, pick(id, lineId, 1).getCode());
        assertEquals(1L, num(lineOf(id, lineId).get("picked_qty")));
    }

    @Test
    void repeatedPickOnSameLineReturns5427AndUnpickAllowsRedo() {
        Long id = createPicking(null);
        controller.start(id, auth);
        Long lineId = lineIds(id).get(0);

        assertEquals(0, pick(id, lineId, null).getCode());
        assertEquals(5427, pick(id, lineId, null).getCode(),
                "同一行不能确认两次");

        assertEquals(0, controller.unpickLine(id, lineId, auth).getCode());
        assertNull(lineOf(id, lineId).get("picked_at"), "撤销后实摆信息应清空");
        assertEquals(5427, controller.unpickLine(id, lineId, auth).getCode(),
                "未确认的行不能撤销");
        assertEquals(0, pick(id, lineId, null).getCode());
    }

    @Test
    void lineActionsRequirePickingStatus() {
        Long id = createPicking(null);
        Long lineId = lineIds(id).get(0);
        // 单头还在 PENDING（没点「开始摆药」），逐项确认应被拒
        var r = pick(id, lineId, null);
        assertEquals(5423, r.getCode(), r.getMessage());
        assertEquals(5427, pick(id, -999L, null).getCode(),
                "不存在的明细返 5427");
    }

    // ================= 四、gate 三态 =================

    @Test
    void defaultGatesAreWarnAndDoNotBlock() {
        assertEquals("warn", service.gate(PharmPickService.GATE_LINE_CONFIRM));
        assertEquals("warn", service.gate(PharmPickService.GATE_WINDOW));

        Long id = createPicking(null);          // 不给窗口
        controller.start(id, auth);             // 一条明细都不确认
        var body = ok(controller.prepared(id, auth));
        assertEquals("PREPARED", body.get("status"), "warn 档必须放行");

        @SuppressWarnings("unchecked")
        var warnings = (List<String>) body.get("warnings");
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("未逐项确认")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("发药窗口")), warnings.toString());
    }

    @Test
    void blockGateRejectsWith5424Then5425() {
        setGate(PharmPickService.GATE_LINE_CONFIRM, "block");
        setGate(PharmPickService.GATE_WINDOW, "block");

        Long id = createPicking(null);
        controller.start(id, auth);
        assertEquals(5424, controller.prepared(id, auth).getCode(), "未逐项确认");

        pickAll(id);
        assertEquals(5425, controller.prepared(id, auth).getCode(), "未分配窗口");

        assertEquals(0, controller.assignWindow(id,
                new PharmPickController.WindowAssignReq("W2"), auth).getCode());
        assertEquals("PREPARED", ok(controller.prepared(id, auth)).get("status"));
    }

    @Test
    void offGateSkipsBothChecksEntirely() {
        setGate(PharmPickService.GATE_LINE_CONFIRM, "off");
        setGate(PharmPickService.GATE_WINDOW, "off");

        Long id = createPicking(null);
        controller.start(id, auth);
        var body = ok(controller.prepared(id, auth));
        assertEquals("PREPARED", body.get("status"));
        assertEquals(List.of(), body.get("warnings"), "off 档整段旁路，连告警都不产生");
    }

    /**
     * <b>本类最重要的一条</b>：坏配置回落 warn 而不是 off。
     * 回落成 off 等于把一个写错的配置值变成「静默关闭法定校验」——
     * 运维从 sys_config 看到的是 BLOCKK，以为拦着，实际一条都不拦、连告警都没有。
     */
    @Test
    void badGateValueFallsBackToWarnNotOff() {
        setGate(PharmPickService.GATE_LINE_CONFIRM, "BLOCKK");
        setGate(PharmPickService.GATE_WINDOW, "");
        assertEquals("warn", service.gate(PharmPickService.GATE_LINE_CONFIRM));
        assertEquals("warn", service.gate(PharmPickService.GATE_WINDOW));

        Long id = createPicking(null);
        controller.start(id, auth);
        var body = ok(controller.prepared(id, auth));
        assertEquals("PREPARED", body.get("status"), "warn 回落必须放行");
        assertEquals(2, ((List<?>) body.get("warnings")).size(), "回落成 off 的话这里会是 0 条");

        // /gates 端点回的是真正生效的档位，不是 sys_config 里那个写坏的字面量
        var gates = ok(controller.gates());
        assertEquals("warn", gates.get(PharmPickService.GATE_LINE_CONFIRM));
        assertEquals("warn", gates.get(PharmPickService.GATE_WINDOW));
    }

    // ================= 五、窗口 =================

    @Test
    void windowMustExistAndBeEnabled() {
        Long id = createPicking(null);
        assertEquals(5426, controller.assignWindow(id,
                new PharmPickController.WindowAssignReq("NO_SUCH"), auth).getCode());
        assertEquals(5426, controller.assignWindow(id,
                new PharmPickController.WindowAssignReq(null), auth).getCode());

        service.saveWindow("W3", "3号窗口", false, 3, "本用例停用");
        assertEquals(5426, controller.assignWindow(id,
                new PharmPickController.WindowAssignReq("W3"), auth).getCode(),
                "已停用的窗口不能再被分配");

        assertEquals("W1", ok(controller.assignWindow(id,
                new PharmPickController.WindowAssignReq("W1"), auth)).get("window_code"));
        // 建单时指定不存在的窗口同样返 5426（不静默吞成不分配）
        assertEquals(5426, controller.create(
                new PharmPickController.CreateReq(regId, "NO_SUCH"), auth).getCode());
    }

    @Test
    void windowMaintenanceValidatesCodeAndName() {
        assertEquals(5432, controller.saveWindow(
                new PharmPickController.WindowReq(null, "x", null, null, null)).getCode());
        assertEquals(5432, controller.saveWindow(
                new PharmPickController.WindowReq("  ", "x", null, null, null)).getCode());
        assertEquals(5432, controller.saveWindow(
                new PharmPickController.WindowReq("W 1", "x", null, null, null)).getCode(),
                "编码是外键目标又会进 URL，含空格须拒");
        assertEquals(5432, controller.saveWindow(
                new PharmPickController.WindowReq("WT", null, null, null, null)).getCode());
        assertEquals(0, controller.saveWindow(
                new PharmPickController.WindowReq("WT", "测试窗口", true, 9, null)).getCode());

        var list = ok(controller.windows(false));
        assertTrue(list.stream().anyMatch(w -> "WT".equals(w.get("code"))));
        assertTrue(list.stream().allMatch(w -> Boolean.TRUE.equals(w.get("enabled"))));
    }

    @Test
    void windowLoadCountsOnlyLiveOrders() {
        long before = liveCountOf("W1");
        Long id = createPicking("W1");
        assertEquals(before + 1, liveCountOf("W1"));

        controller.cancel(id, new PharmPickController.CancelReq("测试作废"), auth);
        assertEquals(before, liveCountOf("W1"), "作废后不再计入窗口在途负载");
    }

    // ================= 六、作废 =================

    @Test
    void cancelRequiresReasonAndReleasesOrdersForRebuild() {
        Long id = createPicking(null);
        assertEquals(5429, controller.cancel(id, null, auth).getCode());
        assertEquals(5429, controller.cancel(id,
                new PharmPickController.CancelReq("   "), auth).getCode());
        assertEquals(5429, controller.cancel(id,
                new PharmPickController.CancelReq("x".repeat(256)), auth).getCode());

        var body = ok(controller.cancel(id, new PharmPickController.CancelReq("患者放弃取药"), auth));
        assertEquals("CANCELLED", body.get("status"));
        assertEquals("患者放弃取药", body.get("cancel_reason"));
        // 作废不删记录：明细还在，只是置了 released
        assertEquals(2, ((List<?>) body.get("lines")).size());
        assertEquals(2L, num(jdbc.queryForObject(
                "select count(*) from pharm_picking_line where picking_id = ? and released", Long.class, id)));

        assertEquals(5423, controller.cancel(id,
                new PharmPickController.CancelReq("再废一次"), auth).getCode());

        // 医嘱被释放，可以重新建单
        assertEquals(1, rows(ok(controller.queue(regId, null))).size());
        assertEquals(0, controller.create(
                new PharmPickController.CreateReq(regId, null), auth).getCode());
    }

    // ================= 七、工作台检索 =================

    @Test
    void searchFindsByPickNoAndValidatesParams() {
        Long id = createPicking("W1");
        String pickNo = String.valueOf(ok(controller.detail(id)).get("pick_no"));

        var hit = ok(controller.search(null, null, null, null, pickNo, null));
        assertEquals(1, rows(hit).size());
        assertEquals(pickNo, rows(hit).get(0).get("pick_no"));
        assertEquals(0L, num(rows(hit).get(0).get("picked_line_count")));
        assertEquals(2L, num(rows(hit).get(0).get("line_count")));
        assertFalse((Boolean) hit.get("truncated"));

        String today = BusinessDates.today().toString();
        assertEquals(1, rows(ok(controller.search(
                List.of("PENDING"), "W1", today, today, pickNo, 50))).size());
        assertEquals(0, rows(ok(controller.search(
                List.of("DISPENSED"), null, null, null, pickNo, null))).size());

        assertEquals(5431, controller.search(List.of("NOPE"), null, null, null, null, null).getCode());
        assertEquals(5431, controller.search(null, null, "2026/09/06", null, null, null).getCode());
        assertEquals(5431, controller.search(null, null, today, "2020-01-01", null, null).getCode());
        assertEquals(5431, controller.search(null, null, null, null, null, 0).getCode());
        assertEquals(5431, controller.search(null, null, null, null, null, 201).getCode());
        assertEquals(5431, controller.search(null, null, null, null, "x".repeat(65), null).getCode());
        assertEquals(5426, controller.search(null, "NO_SUCH", null, null, null, null).getCode(),
                "按不存在的窗口过滤返回空列表是在骗人，直接报错");
    }

    @Test
    void keywordWildcardsAreMatchedLiterally() {
        createPicking(null);
        // % 未转义的话这一查会命中全库；转义后按字面量匹配，命中 0 条
        assertEquals(0, rows(ok(controller.search(null, null, null, null, "%", null))).size());
    }

    // ================= 八、与既有发药链路的边界 =================

    /**
     * <b>既有链路不许被改坏</b>：摆药单在途时走既有 {@code DispenseService.dispense}，
     * 库存照扣、医嘱照样 DISPENSED，摆药单本身不受影响（本车道不碰库存也不改医嘱状态）。
     */
    @Test
    void legacyDispenseStillDeductsStockWithPickingOrderPresent() {
        Long id = createPicking("W1");
        controller.start(id, auth);
        pickAll(id);
        controller.prepared(id, auth);

        int stockBeforeA = drugRepository.findById(drugAId).orElseThrow().getStock();
        em.clear();
        var dispensed = dispenseService.dispense(regId);

        assertEquals(2, dispensed.size());
        assertEquals(stockBeforeA - qtyA,
                drugRepository.findById(drugAId).orElseThrow().getStock(), "既有扣库存逻辑必须原样生效");
        assertTrue(orderRepository.findByRegistrationIdOrderByIdAsc(regId).stream()
                .allMatch(o -> "DISPENSED".equals(o.getStatus())));
        assertEquals("PREPARED", ok(controller.detail(id)).get("status"),
                "发药那一步不该反向改摆药单——衔接靠显式调用 markDispensedByRegistration");
    }

    /**
     * 发药环节的收口钩子：{@code PENDING/PICKING/PREPARED} 三档都收，且<b>不补时刻</b>。
     * 走既有整单一步发药时摆药单可能还停在 PENDING，卡着不收口会让
     * {@code uq_pharm_picking_live_reg} 永久挡住该挂号的下一张单。
     */
    @Test
    void markDispensedByRegistrationClosesLivePickingOrderWithoutFakingTimestamps() {
        Long id = createPicking(null);
        assertEquals(1, service.markDispensedByRegistration(regId, uid()));

        var body = ok(controller.detail(id));
        assertEquals("DISPENSED", body.get("status"));
        assertNull(body.get("picking_at"), "没走摆药就是没走，不补时刻");
        assertNull(body.get("prepared_at"), "没走预调剂就是没走，不假装走过");
        assertNotNull(body.get("dispensed_at"));

        assertEquals(0, service.markDispensedByRegistration(regId, uid()),
                "没有在途单返 0 而不是抛异常——既有发药链路不能因此失败");
        assertEquals(0, service.markDispensedByRegistration(null, uid()));

        jdbc.update("update outp_order set status = 'CHARGED' where registration_id = ?", regId);
        assertEquals(0, controller.create(
                new PharmPickController.CreateReq(regId, null), auth).getCode(), "收口后可再建单");
    }

    /** 工作台点的「标记已发药」只收 PREPARED——跳过预调剂直接点是绕过流程。 */
    @Test
    void manualMarkDispensedOnlyAcceptsPrepared() {
        Long id = createPicking(null);
        assertEquals(5423, controller.markDispensed(id, auth).getCode());
        controller.start(id, auth);
        assertEquals(5423, controller.markDispensed(id, auth).getCode());
    }

    @Test
    void missingOperatorReturns5430() {
        Long id = createPicking(null);
        var e = assertThrows(HipBizException.class, () -> service.start(id, null));
        assertEquals(5430, e.code);
        assertEquals(5430, assertThrows(HipBizException.class,
                () -> service.cancel(id, "原因", null)).code);
        // 建单也一样：created_by 是作业留痕列，识别不出人就不该落一张无主的单
        assertEquals(5430, assertThrows(HipBizException.class,
                () -> service.create(regId, null, null)).code);
    }

    // ================= 工具 =================

    private Long createPicking(String windowCode) {
        var r = controller.create(new PharmPickController.CreateReq(regId, windowCode), auth);
        assertEquals(0, r.getCode(), r.getMessage());
        return ((Number) r.getData().get("id")).longValue();
    }

    private R<Map<String, Object>> pick(Long pickingId, Long lineId, Integer qty) {
        return controller.pickLine(pickingId, lineId,
                new PharmPickController.PickReq(qty, null), auth);
    }

    private void pickAll(Long pickingId) {
        for (Long lineId : lineIds(pickingId)) {
            var r = pick(pickingId, lineId, null);
            assertEquals(0, r.getCode(), r.getMessage());
        }
    }

    private List<Long> lineIds(Long pickingId) {
        return jdbc.queryForList(
                "select id from pharm_picking_line where picking_id = ? order by id",
                Long.class, pickingId);
    }

    private Map<String, Object> lineOf(Long pickingId, Long lineId) {
        return jdbc.queryForMap(
                "select * from pharm_picking_line where picking_id = ? and id = ?", pickingId, lineId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private long liveCountOf(String windowCode) {
        Long n = jdbc.queryForObject("select count(*) from pharm_picking_order "
                + "where window_code = ? and status in ('PENDING','PICKING','PREPARED')",
                Long.class, windowCode);
        return n == null ? 0 : n;
    }

    private Long uid() {
        return jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
    }

    private void setGate(String key, String value) {
        jdbc.update("insert into sys_config (cfg_key, cfg_value) values (?, ?) "
                + "on conflict (cfg_key) do update set cfg_value = excluded.cfg_value", key, value);
        configReader.evict(key);
    }

    private static <T> T ok(R<T> r) {
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("rows");
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }
}
