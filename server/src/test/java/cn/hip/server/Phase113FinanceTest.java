package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.insurance.service.InsuranceReconService;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
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

/** 1.1.3 B 批回归：账务口径（退费归集/退费操作员）、对账比金额、住院结算冲销、配置校验 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase113FinanceTest {

    @Autowired ChargeService chargeService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired InpatientService inpatientService;
    @Autowired InsuranceReconService reconService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.web.FinanceController financeController;
    @Autowired cn.hip.platform.core.web.SysConfigController sysConfigController;

    private Long newPatient(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        return patientService.register(p).getId();
    }

    /** 挂号 + 开一条药嘱，返回 registrationId */
    private Long regWithOrder(Long patientId) {
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(1L);
        sch.setScheduleDate(LocalDate.now());
        sch.setFee(BigDecimal.ZERO);
        sch.setCapacity(9);
        sch = scheduleRepository.save(sch);
        Long regId = registrationService.register(patientId, sch.getId()).getId();
        doctorStationService.startVisit(regId, null);
        Long itemId = jdbc.queryForObject(
                "select id from md_drug where enabled limit 1", Long.class);
        doctorStationService.createOrders(regId,
                List.of(new DoctorStationService.OrderLine("DRUG", itemId, 1, "口服", "qd", "1粒", null)), null);
        entityManager.flush();
        return regId;
    }

    /**
     * B-1/B-2 失败路径：D1 收费（甲）D2 退费（乙）。
     * 修复前：D1 报表事后变脸（退费被算进 D1），且退费挂在收款员甲头上。
     */
    @Test
    void refundGoesToRefundDayAndRefundOperator() {
        Long uidA = jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values ('cashier113b', 'x', '退费员乙113', true) on conflict (username) do nothing
                """);
        Long uidB = jdbc.queryForObject("select id from sys_user where username = 'cashier113b'", Long.class);

        Long regId = regWithOrder(newPatient("退费口径113"));
        var charge = chargeService.settle(regId, "CASH", uidA);
        entityManager.flush();
        // 把收费单倒填到昨天（模拟 D1 收费）
        jdbc.update("update outp_charge set created_at = now() - interval '1 day' where id = ?", charge.getId());

        chargeService.refund(charge.getId(), uidB);
        entityManager.flush();

        String today = LocalDate.now().toString();
        String yesterday = LocalDate.now().minusDays(1).toString();

        // D1 视角：甲有一笔收款、零退款（修复前这里会出现一笔退款——历史报表变脸）
        var d1 = financeController.reconciliation(yesterday).getData();
        @SuppressWarnings("unchecked")
        var d1Rows = (List<Map<String, Object>>) d1.get("byCashier");
        var rowA = d1Rows.stream()
                .filter(r -> String.valueOf(r.get("cashier")).contains("admin")
                        || String.valueOf(r.get("cashier")).contains("管理"))
                .findFirst().orElseThrow();
        assertEquals(0L, ((Number) rowA.get("refund_cnt")).longValue(), "D1 不得出现 D2 才发生的退费");
        assertTrue(((Number) rowA.get("paid_cnt")).longValue() >= 1);

        // D2 视角：退款挂在乙名下（修复前挂在收款员甲头上）
        var d2 = financeController.reconciliation(today).getData();
        @SuppressWarnings("unchecked")
        var d2Rows = (List<Map<String, Object>>) d2.get("byCashier");
        var rowB = d2Rows.stream()
                .filter(r -> String.valueOf(r.get("cashier")).contains("退费员乙113"))
                .findFirst().orElseThrow(() -> new AssertionError("退款侧必须出现退费操作员乙"));
        assertEquals(1L, ((Number) rowB.get("refund_cnt")).longValue());
        assertEquals(0L, ((Number) rowB.get("paid_cnt")).longValue());
    }

    /** B-3 失败路径：通道金额与业务金额不符必须判为不一致（修复前带着金额从不比较，100% 判一致） */
    @Test
    void reconFlagsAmountMismatch() {
        Long pid = newPatient("对账金额113");
        jdbc.update("update empi_patient set insurance_type = 'YB_STAFF' where id = ?", pid);
        Long regId = regWithOrder(pid);
        var charge = chargeService.settle(regId, "YB", null);
        entityManager.flush();

        String today = LocalDate.now().toString();
        var before = reconService.reconRows(today).stream()
                .filter(r -> charge.getChargeNo().equals(r.get("charge_no"))).findFirst().orElseThrow();
        assertTrue((Boolean) before.get("consistent"), "金额一致时应判一致");

        // 篡改业务金额模拟金额差异（真实世界：通道侧少扣/多扣）
        jdbc.update("update outp_charge set total_amount = total_amount + 1 where id = ?", charge.getId());
        var after = reconService.reconRows(today).stream()
                .filter(r -> charge.getChargeNo().equals(r.get("charge_no"))).findFirst().orElseThrow();
        assertFalse((Boolean) after.get("consistent"), "金额不符必须判不一致");
        assertEquals(false, after.get("amount_match"));
        assertTrue(String.valueOf(after.get("note")).contains("金额不符"));
    }

    /** B-5：结算冲销（出院召回）→ 可重新结算；并发/重复冲销被抢占拦截 */
    @Test
    void inpatientSettlementCancelAndResettle() {
        Long pid = newPatient("出院召回113");
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        Long admId = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
        entityManager.flush();
        var s1 = inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        assertEquals("PAID", s1.getStatus());

        var adm = inpatientService.cancelSettlement(admId, null, null);
        entityManager.flush();
        assertEquals("IN_HOSPITAL", adm.getStatus(), "召回后应恢复在院");
        assertEquals("CANCELLED", jdbc.queryForObject(
                "select status from inp_settlement where id = ?", String.class, s1.getId()));
        assertEquals("OCCUPIED", jdbc.queryForObject(
                "select status from inp_bed where id = ?", String.class, bedId), "召回后床位应重新占用");

        // 重复冲销：已无 PAID 单
        var e = assertThrows(InpException.class, () -> inpatientService.cancelSettlement(admId, null, null));
        assertEquals(9021, e.code);

        // 当日重新结算：单号不与已作废单冲突（修复前单号含 admissionId，当日重结必撞唯一约束）
        var s2 = inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        assertNotEquals(s1.getSettleNo(), s2.getSettleNo());
        assertEquals("PAID", s2.getStatus());
    }

    /** B-5 医保侧：YB 结算冲销必须回退年度累计（reverse 此前全仓只有门诊一个调用点） */
    @Test
    void inpatientCancelReversesInsurance() {
        Long pid = newPatient("住院医保冲销113");
        jdbc.update("update empi_patient set insurance_type = 'YB_STAFF' where id = ?", pid);
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        Long admId = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
        Long drugId = jdbc.queryForObject("select id from md_drug where enabled limit 1", Long.class);
        var order = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", drugId, 1, "口服", "qd", "1粒")), null).get(0);
        inpatientService.execute(order.getId(), null);
        entityManager.flush();
        var s = inpatientService.discharge(admId, null, "YB");
        entityManager.flush();

        inpatientService.cancelSettlement(admId, null, null);
        entityManager.flush();
        Boolean reversed = jdbc.queryForObject(
                "select reversed from yb_settle_split where charge_no = ?", Boolean.class, s.getSettleNo());
        assertEquals(Boolean.TRUE, reversed, "冲销后分割行必须打冲销标记（年度额度已回退）");
    }

    /** B-6 失败路径：比例超界/非数字必须被拦（修复前 1.5 会让医院倒贴、abc 让全院结算 500） */
    @Test
    void configValidationRejectsBadValues() {
        assertEquals(1402, sysConfigController.update("yb_ratio_staff", "1.5").getCode());
        assertEquals(1402, sysConfigController.update("yb_ratio_staff", "abc").getCode());
        assertEquals(1402, sysConfigController.update("yb_auto_recon_enabled", "yes").getCode());
        assertEquals(1402, sysConfigController.update("billno_prefix_charge", "过长过长过长过长过长").getCode());
        assertEquals(0, sysConfigController.update("yb_ratio_staff", "0.7").getCode(), "合法值应通过");
    }
}
