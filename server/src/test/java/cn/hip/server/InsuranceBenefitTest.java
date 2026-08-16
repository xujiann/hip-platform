package cn.hip.server;

import cn.hip.insurance.service.InsuranceSplitService;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
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

/** 医保完善批次二：年度起付线/封顶线待遇模型、退费冲销、住院行级分割 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class InsuranceBenefitTest {

    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired InsuranceSplitService insuranceSplitService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private Long newPatient(String insuranceType) {
        Patient p = new Patient();
        p.setName("待遇模型测试");
        p.setSex("U");
        p.setInsuranceType(insuranceType);
        return patientService.register(p).getId();
    }

    private Long ybVisit(Long patientId) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(LocalDate.now());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(patientId, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    private void setCfg(String key, String value) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, key);
    }

    /** 二甲双胍（甲类 9.9）×2 的 YB 结算单 */
    private String settleMetformin(Long rid) {
        Long metId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("二甲双胍").get(0).getId();
        doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", metId, 2, "口服", "bid", "1片", 7)), null);
        entityManager.flush();
        var charge = chargeService.settle(rid, "YB", null);
        entityManager.flush();
        return charge.getChargeNo();
    }

    /** 起付线启用：可报销基数先扣起付线，扣除额计入年度累计 */
    @Test
    void deductibleReducesFundAndAccumulates() {
        setCfg("yb_deductible_staff", "10");
        Long pid = newPatient("YB_STAFF");
        String chargeNo = settleMetformin(ybVisit(pid));

        Map<String, Object> sp = jdbc.queryForMap("select * from yb_settle_split where charge_no = ?", chargeNo);
        // eligible 19.80，扣起付线 10 → (19.80-10)×0.85 = 8.33
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) sp.get("deductible_pay")));
        assertEquals(0, new BigDecimal("8.33").compareTo((BigDecimal) sp.get("fund_pay")));

        Map<String, Object> annual = jdbc.queryForMap(
                "select * from yb_patient_annual where patient_id = ? and year = ?",
                pid, LocalDate.now().getYear());
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) annual.get("deductible_used")));
        assertEquals(0, new BigDecimal("8.33").compareTo((BigDecimal) annual.get("fund_used")));

        // 第二单：起付线已用完，全额进统筹口径
        String chargeNo2 = settleMetformin(ybVisit(pid));
        Map<String, Object> sp2 = jdbc.queryForMap("select * from yb_settle_split where charge_no = ?", chargeNo2);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) sp2.get("deductible_pay")));
        assertEquals(0, new BigDecimal("16.83").compareTo((BigDecimal) sp2.get("fund_pay")));
    }

    /** 封顶线启用：统筹支付被年度剩余额度截断 */
    @Test
    void capLimitsFund() {
        setCfg("yb_cap_staff", "5");
        Long pid = newPatient("YB_STAFF");
        String chargeNo = settleMetformin(ybVisit(pid));
        Map<String, Object> sp = jdbc.queryForMap("select * from yb_settle_split where charge_no = ?", chargeNo);
        // 19.80×0.85 = 16.83 → 封顶 5
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) sp.get("fund_pay")));
    }

    /** 退费冲销：年度起付线/统筹累计回退 */
    @Test
    void refundReversesAnnualAccumulation() {
        setCfg("yb_deductible_staff", "10");
        Long pid = newPatient("YB_STAFF");
        Long rid = ybVisit(pid);
        String chargeNo = settleMetformin(rid);
        Long chargeId = jdbc.queryForObject("select id from outp_charge where charge_no = ?", Long.class, chargeNo);
        entityManager.clear(); // JDBC→JPA 可见
        chargeService.refund(chargeId);
        entityManager.flush();

        Map<String, Object> annual = jdbc.queryForMap(
                "select * from yb_patient_annual where patient_id = ? and year = ?",
                pid, LocalDate.now().getYear());
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) annual.get("deductible_used")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) annual.get("fund_used")));
    }

    /** 住院口径：biz_type=INP 走住院比例（居民 0.70），行级分类同门诊 */
    @Test
    void inpatientSplitUsesInpRatio() {
        setCfg("yb_ratio_resident_inp", "0.60");
        Long pid = newPatient("YB_RESIDENT");
        insuranceSplitService.splitAndAudit("INP", "TESTCY-000001", pid, new BigDecimal("19.80"), List.of(
                new InsuranceSplitService.YbLine("DRUG", "D0006", "二甲双胍缓释片", new BigDecimal("19.80"), 2)));
        Map<String, Object> sp = jdbc.queryForMap("select * from yb_settle_split where charge_no = 'TESTCY-000001'");
        assertEquals("INP", sp.get("biz_type"));
        // 19.80×0.60 = 11.88（住院比例，而非门诊 0.70 口径的 13.86）
        assertEquals(0, new BigDecimal("11.88").compareTo((BigDecimal) sp.get("fund_pay")));
        assertEquals(pid, ((Number) sp.get("patient_id")).longValue());
    }
}
