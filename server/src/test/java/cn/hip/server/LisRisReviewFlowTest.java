package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpCriticalAlertRepository;
import cn.hip.outpatient.repository.OutpLabResultRepository;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.ReviewController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.medtech.web.MedTechController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 审方状态机 + LIS 标本流转 + RIS 报告流转 */
@SpringBootTest
@Transactional
class LisRisReviewFlowTest {

    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired ChargeService chargeService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired OutpOrderRepository orderRepository;
    @Autowired OutpLabResultRepository labResultRepository;
    @Autowired OutpCriticalAlertRepository alertRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ChargeItemRepository chargeItemRepository;
    @Autowired ReviewController reviewController;
    @Autowired MedTechController medTechController;

    @Autowired jakarta.persistence.EntityManager entityManager;

    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    /** JPA 写入在事务内未 flush 时对 JdbcTemplate 不可见，混用前显式刷盘 */
    private void flush() {
        entityManager.flush();
    }

    private Long visitedRegistration() {
        Patient p = new Patient();
        p.setName("流转测试");
        p.setSex("U");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(LocalDate.now());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    @Test
    void rejectedPrescriptionIsCancelledAndCannotBeReviewedTwice() {
        Long rid = visitedRegistration();
        Long drugId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("布洛芬").get(0).getId();
        Long orderId = doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "bid", "1粒", 3)), null).get(0).getId();

        assertEquals(0, reviewController.reject(orderId, "用量不适宜", admin).getCode());
        var order = orderRepository.findById(orderId).orElseThrow();
        assertEquals("CANCELLED", order.getStatus());
        assertEquals("REJECTED", order.getReviewStatus());
        // 二次审核拦截
        assertEquals(4102, reviewController.approve(orderId, admin).getCode());
    }

    @Test
    void lisSampleFlowExecutesOrderAndRaisesCriticalAlert() {
        Long rid = visitedRegistration();
        Long labItem = chargeItemRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("血常规").get(0).getId();
        Long orderId = doctorStationService.createOrders(rid,
                List.of(new OrderLine("LAB", labItem, 1, null, null, null, null)), null).get(0).getId();
        chargeService.settle(rid, "CASH", null);
        flush();

        String barcode = (String) medTechController.collect(orderId).getData().get("barcode");
        assertEquals(0, medTechController.receive(barcode).getCode());
        assertEquals(0, medTechController.publish(barcode, new MedTechController.PublishReq(List.of(
                new MedTechController.ManualResult("HGB", "血红蛋白", "40", "g/L", "130-175", "LL"))), admin).getCode());

        assertEquals("EXECUTED", orderRepository.findById(orderId).orElseThrow().getStatus());
        assertEquals(1, labResultRepository.findByOrderIdOrderByIdAsc(orderId).size());
        assertTrue(alertRepository.findByStatusOrderByIdDesc("NEW").stream()
                .anyMatch(a -> a.getOrderId().equals(orderId)), "LL 应触发危急值");
        // 未核收标本不可重复发布
        assertEquals(9942, medTechController.publish(barcode,
                new MedTechController.PublishReq(List.of()), admin).getCode());
    }

    @Test
    void risReportMustBeWrittenBeforeVerify() {
        Long rid = visitedRegistration();
        Long examItem = chargeItemRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("心电图").get(0).getId();
        Long orderId = doctorStationService.createOrders(rid,
                List.of(new OrderLine("EXAM", examItem, 1, null, null, null, null)), null).get(0).getId();
        chargeService.settle(rid, "CASH", null);
        flush();

        // worklist 触发自动登记
        var wl = medTechController.risWorklist(null).getData();
        var mine = wl.stream().filter(w -> {
            var groupNo = orderRepository.findById(orderId).orElseThrow().getGroupNo();
            return groupNo.equals(w.get("group_no"));
        }).findFirst().orElseThrow();
        Long examId = ((Number) mine.get("id")).longValue();

        // 未写报告直接审核应拒绝
        assertEquals(9945, medTechController.verifyReport(examId, admin).getCode());
        assertEquals(0, medTechController.writeReport(examId,
                new MedTechController.RisReportReq("窦性心律", "未见明显异常"), admin).getCode());
        assertEquals(0, medTechController.verifyReport(examId, admin).getCode());
        entityManager.clear(); // JDBC 更新后清 JPA 一级缓存再读
        assertEquals("EXECUTED", orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }
}
