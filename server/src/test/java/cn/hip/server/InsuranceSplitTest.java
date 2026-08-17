package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 二十七期：医保费用分割与审核规则 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class InsuranceSplitTest {

    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ChargeItemRepository chargeItemRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired EntityManager entityManager;

    private Long ybVisit(String insuranceType) {
        Patient p = new Patient();
        p.setName("医保分割测试");
        p.setSex("U");
        p.setInsuranceType(insuranceType);
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

    /** 职工 85%：甲类全额×85% + 乙类(1-自付比)×85%，未对照项按丙类自费 */
    @Test
    void staffSplitComputesFundAndSelfPay() {
        Long rid = ybVisit("YB_STAFF");
        Long metId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("二甲双胍").get(0).getId(); // A 9.9
        Long labId = chargeItemRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("肝功能").get(0).getId(); // B 60 自付10%
        Long nebId = chargeItemRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("雾化").get(0).getId(); // 未对照 18
        jdbc.update("delete from yb_catalog_map where item_type = 'ITEM' and item_code = 'C0203'");
        doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", metId, 2, "口服", "bid", "1片", 7),
                new OrderLine("LAB", labId, 1, null, null, null, null),
                new OrderLine("TREAT", nebId, 1, null, null, null, null)), null);
        entityManager.flush(); // JPA→JDBC 可见

        var charge = chargeService.settle(rid, "YB", null);
        entityManager.flush();

        Map<String, Object> sp = jdbc.queryForMap("select * from yb_settle_split where charge_no = ?",
                charge.getChargeNo());
        assertEquals(0, new BigDecimal("19.80").compareTo((BigDecimal) sp.get("class_a")));
        assertEquals(0, new BigDecimal("60.00").compareTo((BigDecimal) sp.get("class_b")));
        assertEquals(0, new BigDecimal("18.00").compareTo((BigDecimal) sp.get("class_c")));
        // 统筹 = 19.8*0.85 + 60*0.9*0.85 = 16.83 + 45.90 = 62.73
        assertEquals(0, new BigDecimal("62.73").compareTo((BigDecimal) sp.get("fund_pay")));
        assertEquals(0, ((BigDecimal) sp.get("total")).subtract(new BigDecimal("62.73"))
                .compareTo((BigDecimal) sp.get("self_pay")));
    }

    /** 自费患者医保支付：统筹为 0，全额个人 */
    @Test
    void selfPayPatientGetsZeroFund() {
        Long rid = ybVisit("SELF");
        Long metId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("二甲双胍").get(0).getId();
        doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", metId, 1, "口服", "bid", "1片", 7)), null);
        entityManager.flush();
        var charge = chargeService.settle(rid, "YB", null);
        entityManager.flush();
        Map<String, Object> sp = jdbc.queryForMap("select * from yb_settle_split where charge_no = ?",
                charge.getChargeNo());
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) sp.get("fund_pay")));
        assertEquals(0, ((BigDecimal) sp.get("total")).compareTo((BigDecimal) sp.get("self_pay")));
    }

    /** R001 超量提醒：单品种数量 > 5 */
    @Test
    void overQuantityTriggersAuditWarn() {
        Long rid = ybVisit("YB_RESIDENT");
        Long metId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("二甲双胍").get(0).getId();
        doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", metId, 6, "口服", "bid", "1片", 30)), null);
        entityManager.flush();
        var charge = chargeService.settle(rid, "YB", null);
        entityManager.flush();
        Integer warns = jdbc.queryForObject(
                "select count(*) from yb_audit_log where charge_no = ? and rule_code = 'R001'",
                Integer.class, charge.getChargeNo());
        assertNotNull(warns);
        assertTrue(warns >= 1);
    }
}
