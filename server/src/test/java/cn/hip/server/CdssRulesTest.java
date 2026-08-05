package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 二十八期：CDSS 处方审查规则（DDI 禁用/疗程提醒/年龄禁忌） */
@SpringBootTest
@Transactional
class CdssRulesTest {

    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void grantAbxLevel2() {
        // 头孢/左氧为限制级抗菌药，先授 2 级处方权让 CDSS 规则可被触达
        jdbc.update("""
                insert into med_abx_privilege(user_id, level) values (1, 2)
                on conflict (user_id) do update set level = 2
                """);
    }

    private Long visit(LocalDate birthDate) {
        Patient p = new Patient();
        p.setName("CDSS测试");
        p.setSex("U");
        p.setBirthDate(birthDate);
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

    /** 头孢类 + 含乙醇制剂（藿香正气）→ FORBID 4015 拦截 */
    @Test
    void ddiForbidBlocksCephalosporinWithAlcohol() {
        Long rid = visit(null);
        var e = assertThrows(BizException.class, () -> doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", drugId("藿香正气"), 1, "口服", "tid", "1支", 3),
                new OrderLine("DRUG", drugId("头孢克肟"), 1, "口服", "bid", "1片", 3)), 1L));
        assertEquals(4015, e.code);
    }

    /** 布洛芬疗程超 5 天 → 不拦截但 DOSE 提醒留痕 */
    @Test
    void doseOverLimitLogsCautionWithoutBlocking() {
        Long rid = visit(null);
        assertDoesNotThrow(() -> doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", drugId("布洛芬"), 1, "口服", "bid", "1粒", 7)), 1L));
        entityManager.flush();
        Integer n = jdbc.queryForObject(
                "select count(*) from cdss_alert where registration_id = ? and rule_type = 'DOSE'",
                Integer.class, rid);
        assertNotNull(n);
        assertTrue(n >= 1);
    }

    /** 未成年人开喹诺酮 → AGE 4017 拦截；成人放行 */
    @Test
    void quinoloneBlockedForMinorAllowedForAdult() {
        Long minor = visit(LocalDate.now().minusYears(10));
        var e = assertThrows(BizException.class, () -> doctorStationService.createOrders(minor, List.of(
                new OrderLine("DRUG", drugId("左氧氟沙星"), 1, "口服", "qd", "1片", 7)), 1L));
        assertEquals(4017, e.code);

        Long adult = visit(LocalDate.now().minusYears(30));
        assertDoesNotThrow(() -> doctorStationService.createOrders(adult, List.of(
                new OrderLine("DRUG", drugId("左氧氟沙星"), 1, "口服", "qd", "1片", 7)), 1L));
    }
}
