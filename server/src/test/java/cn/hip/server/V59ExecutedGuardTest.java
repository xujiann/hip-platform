package cn.hip.server;

import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyController.DiagnoseReq;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyRegistryController;
import cn.hip.medtech.web.PathologyRegistryController.RejectReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.outpatient.entity.OutpCharge;
import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpOrderReportRepository;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.outpatient.web.ExecStationController;
import cn.hip.outpatient.web.ExecStationController.ExecuteRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v59 审阅补（车道 F）：<b>EXECUTED 挪到签发后，两个既有依赖方失守</b>。
 *
 * <h2>背景与反向事实</h2>
 * v59 车道 C（2576-③）把门诊病理申请置 EXECUTED 的时点从 diagnose 挪到正式签发（{@code PathologyReportController.issue}，
 * 仍只认 {@code status='CHARGED'}）。于是「已取材 / 已制片 / 已写诊断、未签发」窗口内该申请<b>仍是 CHARGED</b>，
 * 两个只看 status 的依赖方在此窗口失守（审阅者原话，主控核过源码属实）：
 * <ol>
 *   <li><b>退费</b>：{@code ChargeService.refund} 的「EXECUTED 不可退费」（5005）拦不住——修复前，
 *       已取材、甚至写完诊断的病理申请在签发前可<b>整单退费成功</b>（{@link #refundRefusedOnceSpecimenRegisteredUntilRejected}
 *       里那个 {@code assertThrows} 修复前必红：refund 正常返回 REFUNDED）。</li>
 *   <li><b>医技执行站</b>：{@code OutpOrderRepository.chargedExecutables()}（{@code status='CHARGED' and orderType in LAB/EXAM/TREAT}）
 *       把该病理申请一直列在待执行队列直到签发，技师可在那里 {@code POST /exec/{orderId}} 抢先置 EXECUTED，随后 issue 的
 *       {@code update … and status='CHARGED'} 命中 0 行、orderExecuted=false（修复前
 *       {@link #registeredSpecimenLeavesExecQueueUntilRejected} 的「队列不含它」与
 *       {@link #execStationRefusesRegisteredPathologyOrderWith7004} 的 7004 都必红）。</li>
 * </ol>
 *
 * <h2>口径（已登记）</h2>
 * <b>登记了病理标本（{@code path_specimen.order_id} = 该申请 且 {@code rejected_at is null}）的申请视为已进入病理科流程</b>：
 * 退费复用 <b>5005</b>（消息改为「病理标本已登记，不可退费」）、执行站队列排除、执行端点拒 <b>7004</b>
 * （病理申请已登记标本，由病理科签发后自动置执行）。守卫只认<b>未拒收</b>标本：拒收即退出流程（可退费、可重送、重回队列）。
 * 三处判定都是只读且前置于任何写入 / 抢占——被拒路径不碰单据与明细状态。
 *
 * <h2>夹具</h2>
 * 病理申请建法照抄 {@code V57GrossKeepTest}（SQL 建科室 / 排班 / 患者 / 挂号 + outp_order，既有 collect → receive →
 * 真实 grossing → diagnose）；结算 / 退费照抄 {@code Phase113FinanceTest} / {@code V41ShiftCloseTest}
 * （{@code chargeService.settle(regId, "CASH", cashierId)} + flush，操作员用当次事务内新建的专属账号）。
 * 不写任何时间字面量；事务回滚，库里不留痕。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V59ExecutedGuardTest {

    @Autowired PathologyController pathology;
    @Autowired PathologyProcessController process;
    @Autowired PathologyRegistryController registry;
    @Autowired PathologyReportController report;
    @Autowired ExecStationController exec;
    @Autowired OutpOrderRepository orderRepository;
    @Autowired OutpOrderReportRepository reportRepository;
    @Autowired ChargeService chargeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private String tag;
    private Long regId;
    private Long cashierId;
    private Authentication doc;
    private Authentication tech;

    @BeforeEach
    void setUp() {
        tag = "V59F" + Long.toHexString(System.nanoTime());
        doc = userAuth(tag + "d1", "DOCTOR_OUTP");
        tech = userAuth(tag + "t1", "TECHNICIAN");
        cashierId = userId(userAuth(tag + "c1", "CASHIER").getName());

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "执行守卫测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "执行守卫患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
    }

    // =====================================================================================
    // (a) 队列：登记标本后不再列出（修复前含）；拒收后重新出现
    // =====================================================================================

    @Test
    void registeredSpecimenLeavesExecQueueUntilRejected() {
        Long p = pathOrder("A", "CHARGED");
        Long ct = ctOrder("A");
        assertTrue(queueIds().containsAll(List.of(p, ct)), "前提：登记标本前，两条已收费检查申请都在待执行队列");
        assertTrue(worklistIds().containsAll(List.of(p, ct)), "前提：GET /worklist 同源");

        long sid = collect(p, "标本A");
        assertTrue(orderRepository.hasRegisteredSpecimen(p), "已登记（未拒收）标本 → true");
        assertFalse(orderRepository.hasRegisteredSpecimen(ct), "普通检查申请从无标本 → false");

        var queue = queueIds();
        assertFalse(queue.contains(p),
                "修复前：chargedExecutables 按 status='CHARGED' and orderType in (...) 仍把已登记标本的病理申请列在队列里");
        assertTrue(queue.contains(ct), "普通检查申请照常在队列");
        var wl = worklistIds();
        assertFalse(wl.contains(p), "GET /worklist 同源，不得列出已登记标本的病理申请");
        assertTrue(wl.contains(ct));

        // 拒收（真实端点：不删记录、不改 status）→ 退出病理科流程，重新出现在队列
        var rj = registry.reject(sid, new RejectReq("固定液不规范 " + tag, null), tech);
        assertEquals(0, rj.getCode(), rj.getMessage());
        assertNotNull(jdbc.queryForObject("select rejected_at from path_specimen where id = ?", Object.class, sid));
        assertEquals("COLLECTED", specimenStatus(sid), "拒收不改 status（v48 口径）——守卫看的是 rejected_at");
        assertFalse(orderRepository.hasRegisteredSpecimen(p), "守卫只认未拒收标本");
        assertTrue(queueIds().contains(p), "拒收后重新出现在待执行队列");
        assertTrue(worklistIds().contains(p));
        assertEquals("CHARGED", orderStatus(p), "拒收不动申请状态");
    }

    // =====================================================================================
    // (b) 执行端点：已登记标本 → 7004 且不写任何东西；普通检查照常执行；7002 仍在 7004 之前
    // =====================================================================================

    @Test
    void execStationRefusesRegisteredPathologyOrderWith7004() {
        Long p = pathOrder("B", "CHARGED");
        Long ct = ctOrder("B");
        collect(p, "标本B");

        var r = exec.execute(p, new ExecuteRequest("技师抢先执行 " + tag), tech);
        entityManager.flush();
        assertEquals(7004, r.getCode(), "修复前：已登记标本的病理申请仍是 CHARGED，执行站直接置 EXECUTED（返回 0）");
        assertEquals("病理申请已登记标本，由病理科签发后自动置执行", r.getMessage());
        assertEquals("CHARGED", orderStatus(p), "被拒路径不写状态");
        assertTrue(reportRepository.findByOrderIdIn(List.of(p)).isEmpty(), "被拒路径不落执行报告");

        // 普通检查申请照常执行
        var ok = exec.execute(ct, new ExecuteRequest("CT 已完成 " + tag), tech);
        entityManager.flush();
        assertEquals(0, ok.getCode(), ok.getMessage());
        assertEquals("EXECUTED", orderStatus(ct));
        assertEquals(1, reportRepository.findByOrderIdIn(List.of(ct)).size(), "正常执行落一条报告");
        assertEquals(7002, exec.execute(ct, new ExecuteRequest("再来一次"), tech).getCode(), "已执行的再执行仍是 7002");

        // 判定次序：7002 先于 7004——未收费申请上已有标本（标本实物先送达时走 /specimens/manual 登记的口径）
        Long unpaid = pathOrder("B2", "CREATED");
        jdbc.update("insert into path_specimen(order_id, barcode, specimen_desc) values (?, ?, ?)",
                unpaid, "PB" + tag + "B2", "未收费先送达的标本");
        assertTrue(orderRepository.hasRegisteredSpecimen(unpaid));
        assertEquals(7002, exec.execute(unpaid, new ExecuteRequest("x"), tech).getCode(), "7002 仍在 7004 之前");
        assertEquals("CREATED", orderStatus(unpaid));
    }

    // =====================================================================================
    // (c) 退费：结算后登记标本 → 5005 且消息含「病理」、结算单未动；SQL 置拒收后退费放行（守卫只认未拒收标本）
    // =====================================================================================

    /**
     * 修复前放行：settle 后的申请是 CHARGED，走完 collect → receive → grossing → diagnose 仍是 CHARGED（v59 口径），
     * 旧守卫只看 {@code status == EXECUTED}，于是下面第一个 {@code assertThrows} 处 refund 会正常返回 REFUNDED——
     * 已取材制片、写完诊断的申请单被整单退费。
     */
    @Test
    void refundRefusedOnceSpecimenRegisteredUntilRejected() {
        Long p = pathOrder("C", "CREATED");
        OutpCharge charge = settled(p);
        Long chargeId = charge.getId();
        assertFalse(orderRepository.hasRegisteredSpecimen(p), "前提：结算时尚无标本");

        long sid = diagnosed(p, "C");   // 已取材、已写诊断、未签发——审阅者点名的窗口
        var e = assertThrows(BizException.class, () -> chargeService.refund(chargeId, cashierId),
                "修复前放行：申请仍是 CHARGED，「EXECUTED 不可退费」拦不住，整单退费成功");
        assertEquals(5005, e.code, "复用 5005，不新占码");
        assertTrue(e.getMessage().contains("病理"), "消息须点明是病理标本：" + e.getMessage());
        assertTrue(e.getMessage().contains("病理标本已登记") && e.getMessage().contains("病理检查"),
                "消息=「病理标本已登记，不可退费: <项目名>」：" + e.getMessage());
        entityManager.flush();
        var ch = jdbc.queryForMap("select status, refunded_at, refund_by from outp_charge where id = ?", chargeId);
        assertEquals("PAID", ch.get("status"), "只读校验前置于抢占：结算单状态未变");
        assertNull(ch.get("refunded_at"), "未抢占单据：退费时刻未写");
        assertNull(ch.get("refund_by"), "未抢占单据：退费人未写");
        var od = jdbc.queryForMap("select status, charge_id from outp_order where id = ?", p);
        assertEquals("CHARGED", od.get("status"), "未抢占明细：申请仍挂在结算单上");
        assertEquals(chargeId.longValue(), ((Number) od.get("charge_id")).longValue());
        assertEquals("DIAGNOSED", specimenStatus(sid), "标本一字未动");

        // 守卫只认「未拒收标本」：已诊断的标本走不了拒收端点（5208），直接一条 SQL 置拒收 → 退费放行
        assertEquals(5208, registry.reject(sid, new RejectReq("已诊断不能拒收", null), tech).getCode());
        jdbc.update("update path_specimen set rejected_at = now(), reject_reason = ? where id = ?",
                "测试置拒收 " + tag, sid);
        assertFalse(orderRepository.hasRegisteredSpecimen(p));
        var refunded = chargeService.refund(chargeId, cashierId);
        entityManager.flush();
        assertEquals("REFUNDED", refunded.getStatus(), "拒收后退费照常");
        assertEquals("REFUNDED", jdbc.queryForObject("select status from outp_charge where id = ?", String.class, chargeId));
        var od2 = jdbc.queryForMap("select status, charge_id from outp_order where id = ?", p);
        assertEquals("CREATED", od2.get("status"), "退费后申请退回已开立");
        assertNull(od2.get("charge_id"));
    }

    // =====================================================================================
    // (d) 兜底：正式签发后 EXECUTED（既有路径，V59TechConsistencyTest 已钉）；此后两个守卫都由原状态判定先拦
    // =====================================================================================

    @Test
    void issueStillMovesOrderToExecutedAndStatusGuardsTakeOverAfterwards() {
        Long p = pathOrder("D", "CREATED");
        Long chargeId = settled(p).getId();
        long sid = diagnosed(p, "D");
        assertFalse(queueIds().contains(p), "签发前：已登记标本 → 不在队列（没有技师能抢先）");

        var issued = report.issue(sid, doc);
        assertEquals(0, issued.getCode(), issued.getMessage());
        assertEquals(Boolean.TRUE, issued.getData().get("orderExecuted"),
                "签发本次真的把 CHARGED 置成了 EXECUTED——没有被执行站抢先命中 0 行");
        assertEquals("EXECUTED", orderStatus(p), "既有路径：正式签发后才 EXECUTED");
        assertFalse(queueIds().contains(p), "签发后：已执行 → 仍不在队列");

        entityManager.clear();   // 保险：清一级缓存，让执行站按库里的 EXECUTED 判，不依赖 claimCharge 的 clearAutomatically
        assertEquals(7002, exec.execute(p, new ExecuteRequest("x"), tech).getCode(), "签发后执行站按状态拒（7002），轮不到 7004");
        var e = assertThrows(BizException.class, () -> chargeService.refund(chargeId, cashierId));
        assertEquals(5005, e.code);
        assertTrue(e.getMessage().contains("已执行"), "签发后由原「已执行项目不可退费」先拦：" + e.getMessage());
        assertEquals("PAID", jdbc.queryForObject("select status from outp_charge where id = ?", String.class, chargeId));
    }

    // ==================== 夹具与取值 ====================

    /** 一条门诊病理申请（EXAM / PATH「病理检查」，100 元 < 退费审批阈值 500）；status 由调用方给 */
    private Long pathOrder(String suffix, String status) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, ?) returning id
                """, Long.class, regId, "G" + tag + suffix, status);
    }

    /** 一条普通检查申请（EXAM / CT），永远不会有病理标本——对照组 */
    private Long ctOrder(String suffix) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'CT', '胸部CT平扫', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "X" + tag + suffix);
    }

    /** 既有 collect 登记打码（要求申请 CHARGED）→ 标本 id */
    private long collect(Long orderId, String desc) {
        var c = pathology.collect(orderId, desc);
        assertEquals(0, c.getCode(), c.getMessage());
        String barcode = (String) c.getData().get("barcode");
        assertNotNull(barcode);
        return jdbc.queryForObject("select id from path_specimen where barcode = ?", Long.class, barcode);
    }

    /** collect → receive → 真实 grossing → diagnose：把申请带到「已写诊断、未签发」窗口，且申请仍 CHARGED */
    private long diagnosed(Long orderId, String suffix) {
        long sid = collect(orderId, "标本" + suffix);
        String barcode = jdbc.queryForObject("select barcode from path_specimen where id = ?", String.class, sid);
        var rc = pathology.receive(barcode);
        assertEquals(0, rc.getCode(), rc.getMessage());
        var gr = process.grossing(new GrossingReq(sid, null, null, "灰白组织一块 " + tag, false, null,
                List.of(new BlockReq("肿物中心"))), doc);
        assertEquals(0, gr.getCode(), gr.getMessage());
        var dx = pathology.diagnose(barcode, new DiagnoseReq(null, "镜下见慢性炎细胞浸润 " + tag, "慢性炎症 " + tag), doc);
        assertEquals(0, dx.getCode(), dx.getMessage());
        assertEquals("DIAGNOSED", specimenStatus(sid));
        assertEquals("CHARGED", orderStatus(orderId), "前提（v59 口径）：写完诊断、未签发的申请仍是 CHARGED");
        return sid;
    }

    /** 结算夹具（照抄 Phase113FinanceTest / V41ShiftCloseTest）：本挂号下全部 CREATED 申请一次结清 */
    private OutpCharge settled(Long... orderIds) {
        var charge = chargeService.settle(regId, "CASH", cashierId);
        entityManager.flush();
        assertEquals("PAID", charge.getStatus());
        for (Long id : orderIds) {
            assertEquals("CHARGED", orderStatus(id), "结算后申请应被抢占成 CHARGED");
        }
        return charge;
    }

    private List<Long> queueIds() {
        return orderRepository.chargedExecutables().stream().map(OutpOrder::getId).toList();
    }

    private List<Long> worklistIds() {
        var r = exec.worklist();
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData().stream().map(m -> ((Number) m.get("orderId")).longValue()).toList();
    }

    private String orderStatus(Long orderId) {
        return jdbc.queryForObject("select status from outp_order where id = ?", String.class, orderId);
    }

    private String specimenStatus(long specimenId) {
        return jdbc.queryForObject("select status from path_specimen where id = ?", String.class, specimenId);
    }

    private Authentication userAuth(String username, String role) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "用户");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private Long userId(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }
}
