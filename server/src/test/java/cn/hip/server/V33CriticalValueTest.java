package cn.hip.server;

import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.ReferenceRangeService;
import cn.hip.outpatient.web.LabRefRangeController;
import cn.hip.outpatient.web.LabResultController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.integration.event.LabResultReceivedEvent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v33：检验/影像双通道危急值闭环 + 参考区间自动判危 + RIS 报告双签。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V33CriticalValueTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired PatientService patientService;
    @Autowired cn.hip.outpatient.service.RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired ReferenceRangeService referenceRangeService;
    @Autowired LabResultController labResultController;
    @Autowired LabRefRangeController labRefRangeController;
    @Autowired MedTechController medTechController;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Long userId(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username);
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private Authentication authOf(String username) {
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    // ========== 参考区间自动判危 ==========

    @Test
    void referenceRangeDerivesFlagFromSeeds() {
        // 血钾 K 种子：参考 3.5-5.5，危急 <2.8(LL) / >6.5(HH)
        assertEquals("HH", referenceRangeService.evaluate("K", null, null, "7.0"));
        assertEquals("LL", referenceRangeService.evaluate("K", null, null, "2.0"));
        assertEquals("H",  referenceRangeService.evaluate("K", null, null, "6.0"));
        assertEquals("L",  referenceRangeService.evaluate("K", null, null, "3.0"));
        assertEquals("N",  referenceRangeService.evaluate("K", null, null, "4.5"));
        // 非数值/无区间：判不出返回 null（不误判）
        assertNull(referenceRangeService.evaluate("K", null, null, "阳性"));
        assertNull(referenceRangeService.evaluate("NOPE", null, null, "1"));
        // 性别相关：血红蛋白 HGB 男 120-160 / 女 110-150
        assertEquals("L", referenceRangeService.evaluate("HGB", "M", null, "115"));  // 男 115 偏低
        assertEquals("N", referenceRangeService.evaluate("HGB", "F", null, "115"));  // 女 115 正常
    }

    // ========== LAB 危急值闭环：通知开单医师 → 接收确认 → 处置留痕 ==========

    private record LabCase(Long orderId, Long doctorId, Long alertId) {}

    private LabCase raiseLabCritical(String docUser) {
        Long doctorId = userId(docUser);
        Patient p = new Patient();
        p.setName("危急闭环");
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, doctorId);
        Long labItem = seeds.chargeItem("血常规", "LAB").getId();
        var order = doctorStationService.createOrders(rid,
                List.of(new OrderLine("LAB", labItem, 1, null, null, null, null)), doctorId).get(0);
        chargeService.settle(rid, "CASH", null);
        em.flush();
        // 结果不带 flag，但血钾 2.0 会被参考区间判为 LL → 触发危急值
        eventPublisher.publishEvent(new LabResultReceivedEvent(order.getGroupNo(),
                List.of(new LabResultReceivedEvent.Item("K", "血钾", "2.0", "mmol/L", "3.5-5.5", null))));
        em.flush();
        Long alertId = jdbc.queryForObject(
                "select id from outp_critical_alert where order_id = ? and status = 'NEW'", Long.class, order.getId());
        return new LabCase(order.getId(), doctorId, alertId);
    }

    @Test
    void labCriticalNotifiesOrderingDoctorAndClosesOnAck() {
        var c = raiseLabCritical("doc_v33a");
        // 告警落 notify_to = 开单医师、有 deadline、source=LAB
        var alert = jdbc.queryForMap("select source, notify_to_user_id, deadline_at from outp_critical_alert where id = ?", c.alertId());
        assertEquals("LAB", alert.get("source"));
        assertEquals(c.doctorId(), ((Number) alert.get("notify_to_user_id")).longValue());
        assertNotNull(alert.get("deadline_at"), "应设应确认时限");

        // 开单医师看到"我的待确认"
        var pending = labResultController.myPending(authOf("doc_v33a")).getData();
        assertTrue(pending.stream().anyMatch(m -> ((Number) m.get("id")).longValue() == c.alertId()),
                "开单医师应在待确认列表看到该危急值");

        // 别的医师看不到、也不能替确认（7103）
        userId("doc_v33b");
        assertTrue(labResultController.myPending(authOf("doc_v33b")).getData().stream()
                        .noneMatch(m -> ((Number) m.get("id")).longValue() == c.alertId()),
                "非开单医师不应在自己的待确认列表看到该危急值");
        assertEquals(7103, labResultController.acknowledge(c.alertId(),
                new LabResultController.AckReq("我不是开单医师"), authOf("doc_v33b")).getCode());

        // 处置必填
        assertEquals(7102, labResultController.acknowledge(c.alertId(),
                new LabResultController.AckReq("  "), authOf("doc_v33a")).getCode());

        // 开单医师本人确认 + 处置留痕
        assertEquals(0, labResultController.acknowledge(c.alertId(),
                new LabResultController.AckReq("已电话通知患者返院复查并补钾"), authOf("doc_v33a")).getCode());
        var closed = jdbc.queryForMap("select status, ack_by, disposition from outp_critical_alert where id = ?", c.alertId());
        assertEquals("HANDLED", closed.get("status"));
        assertEquals(c.doctorId(), ((Number) closed.get("ack_by")).longValue());
        assertTrue(String.valueOf(closed.get("disposition")).contains("补钾"));

        // 重复确认被拦（已非 NEW）
        assertEquals(7102, labResultController.acknowledge(c.alertId(),
                new LabResultController.AckReq("再确认一次"), authOf("doc_v33a")).getCode());
    }

    @Test
    void overdueBoardListsUnackedPastDeadline() {
        var c = raiseLabCritical("doc_v33c");
        // 把时限拨到过去，模拟超期未确认
        jdbc.update("update outp_critical_alert set deadline_at = now() - interval '1 minute' where id = ?", c.alertId());
        assertTrue(labResultController.overdue().getData().stream()
                .anyMatch(m -> ((Number) m.get("id")).longValue() == c.alertId()), "超期未确认应进超期看板");
    }

    // ========== RIS 影像危急值：复用同一闭环 ==========

    @Test
    void risCriticalRaisesAlertOnSameLoop() {
        Long doctorId = userId("doc_ris_v33");
        Patient p = new Patient();
        p.setName("影像危急");
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, doctorId);
        Long examItem = seeds.chargeItem("心电图", "EXAM").getId();
        var order = doctorStationService.createOrders(rid,
                List.of(new OrderLine("EXAM", examItem, 1, null, null, null, null)), doctorId).get(0);
        chargeService.settle(rid, "CASH", null);
        em.flush();

        var wl = medTechController.risWorklist(null).getData();
        Long examId = wl.stream().filter(w -> order.getGroupNo().equals(w.get("group_no")))
                .map(w -> ((Number) w.get("id")).longValue()).findFirst().orElseThrow();

        // 未写报告不能标危急
        assertEquals(9945, medTechController.markRisCritical(examId, new MedTechController.RisCriticalReq("气胸")).getCode());
        assertEquals(0, medTechController.writeReport(examId,
                new MedTechController.RisReportReq("右肺压缩", "右侧气胸"), authOf("doc_ris_v33")).getCode());
        // 描述必填
        assertEquals(9948, medTechController.markRisCritical(examId, new MedTechController.RisCriticalReq("  ")).getCode());
        // 标危急 → 复用 outp_critical_alert（source=RIS），通知开单医师
        assertEquals(0, medTechController.markRisCritical(examId,
                new MedTechController.RisCriticalReq("右侧气胸，肺压缩约 40%")).getCode());
        var alert = jdbc.queryForMap("""
                select source, notify_to_user_id from outp_critical_alert
                where order_id = ? and source = 'RIS' and status = 'NEW'
                """, order.getId());
        assertEquals("RIS", alert.get("source"));
        assertEquals(doctorId, ((Number) alert.get("notify_to_user_id")).longValue());
        assertTrue((Boolean) jdbc.queryForMap("select critical_flag from ris_exam where id = ?", examId).get("critical_flag"));
        // 重复标记被拦
        assertEquals(9949, medTechController.markRisCritical(examId,
                new MedTechController.RisCriticalReq("再标一次")).getCode());
    }

    // ========== 参考区间 CRUD ==========

    @Test
    void refRangeCrud() {
        assertEquals(7104, labRefRangeController.create(new LabRefRangeController.RefRangeReq(
                "", null, null, null, null, null, null, null, null, null, null)).getCode());
        assertEquals(0, labRefRangeController.create(new LabRefRangeController.RefRangeReq(
                "TROP", "肌钙蛋白", null, null, null, new BigDecimal("0"), new BigDecimal("0.03"),
                null, new BigDecimal("0.5"), "ng/mL", true)).getCode());
        assertEquals("HH", referenceRangeService.evaluate("TROP", null, null, "1.0"));
        var mine = labRefRangeController.list("TROP").getData();
        assertEquals(1, mine.size());
        Long id = ((Number) mine.get(0).get("id")).longValue();
        assertEquals(0, labRefRangeController.remove(id).getCode());
        assertEquals(7105, labRefRangeController.remove(id).getCode());
    }
}
