package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.*;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ChargeAndDispenseTest {

    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired DispenseService dispenseService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired OutpRegistrationRepository registrationRepository;
    @Autowired OutpOrderRepository orderRepository;
    @Autowired DrugItemRepository drugRepository;

    private Long regId;
    private Long drugId;
    private int stockBefore;
    private BigDecimal drugPrice;

    @BeforeEach
    void setupVisitWithPrescription() {
        Patient p = new Patient();
        p.setName("收费测试");
        p.setSex("U");
        Long pid = patientService.register(p).getId();

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(LocalDate.now());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(9);
        s = scheduleRepository.save(s);

        regId = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(regId, null);

        var drug = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("阿莫西林").get(0);
        drugId = drug.getId();
        stockBefore = drug.getStock();
        drugPrice = drug.getPrice();
        doctorStationService.createOrders(regId,
                List.of(new OrderLine("DRUG", drugId, 2, "口服", "tid", "1粒", 3)), null);
    }

    @Test
    void settleMarksOrdersChargedWithCorrectTotal() {
        var charge = chargeService.settle(regId, "CASH", null);
        assertEquals(0, charge.getTotalAmount().compareTo(drugPrice.multiply(BigDecimal.TWO)));
        assertTrue(orderRepository.findByRegistrationIdOrderByIdAsc(regId).stream()
                .allMatch(o -> "CHARGED".equals(o.getStatus())));
        // 空结算防护
        var e = assertThrows(BizException.class, () -> chargeService.settle(regId, "CASH", null));
        assertEquals(5001, e.code);
    }

    @Test
    void dispenseDeductsStockAndBlocksRefundUntilReturned() {
        var charge = chargeService.settle(regId, "CASH", null);
        dispenseService.dispense(regId);
        assertEquals(stockBefore - 2, drugRepository.findById(drugId).orElseThrow().getStock());

        // 已发药 → 退费拦截
        var e = assertThrows(BizException.class, () -> chargeService.refund(charge.getId()));
        assertEquals(5004, e.code);

        // 退药回补库存 → 退费放行，订单回到已开立
        Long orderId = orderRepository.findByRegistrationIdOrderByIdAsc(regId).get(0).getId();
        dispenseService.returnDrug(orderId, null);
        assertEquals(stockBefore, drugRepository.findById(drugId).orElseThrow().getStock());
        var refunded = chargeService.refund(charge.getId());
        assertEquals("REFUNDED", refunded.getStatus());
        assertEquals("CREATED", orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    void insufficientStockFailsDispenseAtomically() {
        chargeService.settle(regId, "CASH", null);
        var drug = drugRepository.findById(drugId).orElseThrow();
        drug.setStock(1);
        drugRepository.save(drug);
        var e = assertThrows(BizException.class, () -> dispenseService.dispense(regId));
        assertEquals(6002, e.code);
    }

    @Test
    void cancelRegistrationBlockedWhenCharged() {
        chargeService.settle(regId, "CASH", null);
        var e = assertThrows(BizException.class, () -> registrationService.cancel(regId));
        assertEquals(3005, e.code); // 已接诊状态不可退号（VISITED）
    }
}
