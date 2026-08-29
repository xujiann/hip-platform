package cn.hip.server;

import cn.hip.outpatient.entity.OutpCharge;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RefundApprovalService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 退费审批链（v30）：大额退费须先审批后执行。
 * 用极低阈值让普通单也触发审批闸，覆盖：超阈值直退被拒 → 申请 → 通过 → 可退 → 审批不可复用。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class RefundApprovalTest {

    @Autowired ChargeService chargeService;
    @Autowired RefundApprovalService refundApprovalService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired PatientService patientService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @AfterEach
    void restore() {
        // 直改配置须失效缓存（方法论⑤），否则毒缓存污染后续测试
        jdbc.update("update sys_config set cfg_value = '500' where cfg_key = 'refund_approval_threshold'");
        configReader.evict("refund_approval_threshold");
    }

    private void setThreshold(String v) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = 'refund_approval_threshold'", v);
        configReader.evict("refund_approval_threshold");
    }

    private OutpCharge makeCharge() {
        Patient p = new Patient();
        p.setName("退费审批");
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(1L);
        sch.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        sch.setFee(BigDecimal.ZERO);
        sch.setCapacity(9);
        sch = scheduleRepository.save(sch);
        Long regId = registrationService.register(pid, sch.getId()).getId();
        doctorStationService.startVisit(regId, null);
        doctorStationService.createOrders(regId, List.of(new DoctorStationService.OrderLine(
                "DRUG", seeds.anyDrug().getId(), 1, "口服", "qd", "1粒", null)), null);
        entityManager.flush();
        var charge = chargeService.settle(regId, "CASH", null);
        entityManager.flush();
        return charge;
    }

    /** 阈值 0 = 不设闸：任何金额直接退，向后兼容 */
    @Test
    void thresholdZeroAllowsDirectRefund() {
        setThreshold("0");
        var charge = makeCharge();
        var refunded = chargeService.refund(charge.getId(), null);
        assertEquals("REFUNDED", refunded.getStatus());
    }

    /** 超阈值无审批 → 拒绝并引导走审批（5011） */
    @Test
    void overThresholdWithoutApprovalIsRejected() {
        setThreshold("0.01");   // 任何单都超阈值
        var charge = makeCharge();
        var ex = assertThrows(BizException.class, () -> chargeService.refund(charge.getId(), null));
        assertEquals(5011, ex.code, ex.getMessage());
        // 单据仍为 PAID——审批未过不触碰状态
        assertEquals("PAID", jdbc.queryForObject(
                "select status from outp_charge where id = ?", String.class, charge.getId()));
    }

    /** 申请 → 通过 → 可退；且审批单被 EXECUTED 消费 */
    @Test
    void approvedRefundSucceedsAndConsumesApproval() {
        setThreshold("0.01");
        var charge = makeCharge();
        Long approvalId = refundApprovalService.apply(charge.getId(), "患者取消就诊", null);
        refundApprovalService.decide(approvalId, true, "同意", null);

        var refunded = chargeService.refund(charge.getId(), null);
        assertEquals("REFUNDED", refunded.getStatus());
        assertEquals("EXECUTED", jdbc.queryForObject(
                "select status from outp_refund_approval where id = ?", String.class, approvalId));
    }

    /** 驳回的审批不能用于退费 */
    @Test
    void rejectedApprovalDoesNotAllowRefund() {
        setThreshold("0.01");
        var charge = makeCharge();
        Long approvalId = refundApprovalService.apply(charge.getId(), "试图退费", null);
        refundApprovalService.decide(approvalId, false, "金额存疑，不批", null);

        var ex = assertThrows(BizException.class, () -> chargeService.refund(charge.getId(), null));
        assertEquals(5011, ex.code);
    }

    /** 同一结算单不能重复提交待审批申请（5013） */
    @Test
    void duplicatePendingApplicationRejected() {
        setThreshold("0.01");
        var charge = makeCharge();
        refundApprovalService.apply(charge.getId(), "第一次", null);
        var ex = assertThrows(BizException.class,
                () -> refundApprovalService.apply(charge.getId(), "第二次", null));
        assertEquals(5013, ex.code);
    }

    /** 第七轮审阅 P2-4：审批人不能是申请人（职责分离，防自申自批） */
    @Test
    void selfApprovalIsRejected() {
        setThreshold("0.01");
        var charge = makeCharge();
        Long applicantId = 42L;
        Long approvalId = refundApprovalService.apply(charge.getId(), "自己申请", applicantId);
        // 同一人审批自己的申请 → 5015
        var ex = assertThrows(BizException.class,
                () -> refundApprovalService.decide(approvalId, true, "自己批", applicantId));
        assertEquals(5015, ex.code, ex.getMessage());
        // 他人审批则放行
        refundApprovalService.decide(approvalId, true, "他人复核", 99L);
        assertEquals("APPROVED", jdbc.queryForObject(
                "select status from outp_refund_approval where id = ?", String.class, approvalId));
    }

    /** 第七轮审阅 P3-2：阈值配置脏值不应让退费全线 500，回落默认 */
    @Test
    void dirtyThresholdFallsBackToDefault() {
        setThreshold("not-a-number");
        // needsApproval 不抛异常，按默认 500 判定
        assertTrue(refundApprovalService.needsApproval(new java.math.BigDecimal("600")));
        assertFalse(refundApprovalService.needsApproval(new java.math.BigDecimal("100")));
    }
}
