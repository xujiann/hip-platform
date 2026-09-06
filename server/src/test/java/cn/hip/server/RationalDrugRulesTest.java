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
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
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
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
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

    /**
     * <b>「皮试阴性」不得被误判成过敏而拦截</b>（v51 修的反向拦截）。
     *
     * <p>缺陷实证：过敏史写「青霉素皮试阴性」的患者开阿莫西林，旧逻辑会 4012 硬拦——
     * 「青霉素皮试阴性」含「青霉素」、「阿莫西林」含「西林」，关键词匹配两条都成立。
     * 而<b>皮试阴性恰恰是可以用的证据</b>，这是彻底的反向拦截：该拦的不拦、不该拦的拦住。
     *
     * <p>假阳性的杀伤不比假阴性小：医生发现十次提示七次不成立，
     * 就会养成不读内容直接点「继续」的肌肉记忆，<b>第八次那个真警告也被一起点掉</b>。
     * 一个被无视的拦截等于没有拦截，还骗走了本可以投在别处的注意力。
     */
    @Test
    void skinTestNegativeIsNotTreatedAsAllergy() {
        Long rid = visitedRegistration("青霉素皮试阴性");
        assertDoesNotThrow(() -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId("阿莫西林"), 1, "口服", "tid", "1粒", 3)), null),
                "「青霉素皮试阴性」是可以用的证据，不是过敏——关键词匹配把它当成过敏是反向拦截");
    }

    /** 「否认药物过敏史」同理：这是最常见的过敏史写法，含药名时更不能误拦 */
    @Test
    void deniedAllergyHistoryIsNotTreatedAsAllergy() {
        Long rid = visitedRegistration("否认药物过敏史，青霉素类既往使用无不良反应");
        assertDoesNotThrow(() -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId("阿莫西林"), 1, "口服", "tid", "1粒", 3)), null),
                "「否认…过敏史」是明确的否定语境，含药名也不得按过敏拦");
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

    // ===== 二十五期：抗菌药分级处方权 =====

    @Autowired jakarta.persistence.EntityManager entityManager;

    /** 限制级抗菌药（头孢克肟 abx_level=2），无过敏史、缺省 1 级处方权 → 4014 拦截 */
    @Test
    void restrictedAbxBlockedWithoutPrivilege() {
        Long rid = visitedRegistration(null);
        var e = assertThrows(BizException.class, () -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId("头孢克肟"), 1, "口服", "bid", "1片", 3)), 1L));
        assertEquals(4014, e.code);
    }

    /** 授权 2 级后可开限制级抗菌药 */
    @Test
    void restrictedAbxAllowedAfterGrant() {
        entityManager.createNativeQuery("""
                insert into med_abx_privilege(user_id, level) values (1, 2)
                on conflict (user_id) do update set level = 2
                """).executeUpdate();
        Long rid = visitedRegistration(null);
        assertDoesNotThrow(() -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId("头孢克肟"), 1, "口服", "bid", "1片", 3)), 1L));
    }

    /** 非限制级（阿莫西林 abx_level=1）不受缺省处方权影响 */
    @Test
    void nonRestrictedAbxNotBlocked() {
        Long rid = visitedRegistration(null);
        assertDoesNotThrow(() -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId("阿莫西林"), 1, "口服", "tid", "1粒", 3)), 1L));
    }
}
