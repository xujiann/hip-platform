package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.repository.PatientRepository;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 合理用药前置拦截规则 */
@SpringBootTest
@Transactional
class RationalDrugRulesTest {

    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired PatientRepository patientRepository;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired DrugItemRepository drugRepository;

    private Long visitedRegistration(String allergyHistory) {
        Patient p = new Patient();
        p.setName("规则测试");
        p.setSex("U");
        p.setAllergyHistory(allergyHistory);
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

    private Long drugId(String keyword) {
        return drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(keyword).get(0).getId();
    }

    @Test
    void penicillinAllergyBlocksXilinDrugs() {
        Long rid = visitedRegistration("青霉素过敏");
        var e = assertThrows(BizException.class, () -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId("阿莫西林"), 1, "口服", "tid", "1粒", 3)), null));
        assertEquals(4012, e.code);
    }

    @Test
    void cephalosporinAllergyBlocksCefDrugs() {
        Long rid = visitedRegistration("头孢类过敏");
        var e = assertThrows(BizException.class, () -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId("头孢克肟"), 1, "口服", "bid", "1片", 3)), null));
        assertEquals(4012, e.code);
    }

    @Test
    void duplicateDrugInSameVisitBlocked() {
        Long rid = visitedRegistration(null);
        Long blf = drugId("布洛芬");
        doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", blf, 1, "口服", "bid", "1粒", 3)), null);
        var e = assertThrows(BizException.class, () -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", blf, 1, "口服", "bid", "1粒", 3)), null));
        assertEquals(4013, e.code);
    }

    @Test
    void unrelatedAllergyDoesNotBlock() {
        Long rid = visitedRegistration("磺胺过敏");
        assertDoesNotThrow(() -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId("布洛芬"), 1, "口服", "bid", "1粒", 3)), null));
    }
}
