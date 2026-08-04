package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class RegistrationServiceTest {

    @Autowired RegistrationService registrationService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired OutpOrderRepository orderRepository;
    @Autowired PatientService patientService;

    private Long newPatient(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("U");
        return patientService.register(p).getId();
    }

    private OutpSchedule newSchedule(int capacity, String fee) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(LocalDate.now());
        s.setFee(new BigDecimal(fee));
        s.setCapacity(capacity);
        return scheduleRepository.save(s);
    }

    @Test
    void registerAssignsSequentialRegNoAndCreatesFeeOrder() {
        var schedule = newSchedule(2, "10");
        var reg1 = registrationService.register(newPatient("测试A"), schedule.getId());
        var reg2 = registrationService.register(newPatient("测试B"), schedule.getId());
        assertEquals(1, reg1.getRegNo());
        assertEquals(2, reg2.getRegNo());
        var feeOrders = orderRepository.findByRegistrationIdOrderByIdAsc(reg1.getId());
        assertEquals(1, feeOrders.size());
        assertEquals("REG", feeOrders.get(0).getOrderType());
        assertEquals(0, feeOrders.get(0).getAmount().compareTo(new BigDecimal("10")));
    }

    @Test
    void duplicateRegistrationIsRejected() {
        var schedule = newSchedule(5, "10");
        Long pid = newPatient("测试C");
        registrationService.register(pid, schedule.getId());
        var e = assertThrows(BizException.class, () -> registrationService.register(pid, schedule.getId()));
        assertEquals(3002, e.code);
    }

    @Test
    void fullScheduleIsRejected() {
        var schedule = newSchedule(1, "10");
        registrationService.register(newPatient("测试D"), schedule.getId());
        var e = assertThrows(BizException.class,
                () -> registrationService.register(newPatient("测试E"), schedule.getId()));
        assertEquals(3003, e.code);
    }

    @Test
    void cancelReleasesSlotAndCancelsUnchargedFee() {
        var schedule = newSchedule(1, "10");
        var reg = registrationService.register(newPatient("测试F"), schedule.getId());
        registrationService.cancel(reg.getId());
        assertEquals(0, scheduleRepository.findById(schedule.getId()).orElseThrow().getBooked());
        var orders = orderRepository.findByRegistrationIdOrderByIdAsc(reg.getId());
        assertEquals("CANCELLED", orders.get(0).getStatus());
        // 退号后号源释放，他人可挂
        assertDoesNotThrow(() -> registrationService.register(newPatient("测试G"), schedule.getId()));
    }

    @Test
    void zeroFeeScheduleCreatesNoFeeOrder() {
        var schedule = newSchedule(1, "0");
        var reg = registrationService.register(newPatient("测试H"), schedule.getId());
        assertTrue(orderRepository.findByRegistrationIdOrderByIdAsc(reg.getId()).isEmpty());
    }
}
