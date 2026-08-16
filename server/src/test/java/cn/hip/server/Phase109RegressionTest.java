package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.medtech.web.AppointmentController;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DispenseService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.9 C 组回归：上一轮修复自身的缺陷。
 *
 * <p>这些用例的共同点是**验证修复后的失败路径**——上一轮只验证了正常路径，
 * 所以错误的修复（预约重试、支付回滚、陈旧快照）看起来都是对的。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase109RegressionTest {

    @Autowired AppointmentController appointmentController;
    @Autowired InpatientService inpatientService;
    @Autowired DispenseService dispenseService;
    @Autowired ChargeService chargeService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ChargeItemRepository chargeItemRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private Long visitedRegistration(String name) {
        Patient p = new Patient();
        p.setName(name);
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

    /**
     * C-1：**预约取号冲突后仍能给出业务码，而不是 500**。
     * 旧写法把「重试循环」放在 @Transactional 内，PG 唯一冲突后事务即 aborted，
     * 循环里的下一条 select 直接报 25P02，20 次重试与 4514 文案永远走不到。
     */
    @Test
    void appointmentSurvivesSeqConflict() {
        Long rid = visitedRegistration("预约冲突109");
        Long examItem = chargeItemRepository
                .findTop20ByEnabledTrueAndNameContainingOrderByCode("心电图").get(0).getId();
        var orders = doctorStationService.createOrders(rid,
                List.of(new OrderLine("EXAM", examItem, 1, null, null, null, null),
                        new OrderLine("EXAM", examItem, 1, null, null, null, null)), null);
        chargeService.settle(rid, "CASH", null);
        entityManager.flush();

        String slot = LocalDate.now().plusDays(30).toString();
        // 先占掉该时段的 1 号，制造后续取号必然撞唯一索引的场景
        jdbc.update("""
                insert into med_appointment(order_id, slot_date, period, seq_no)
                values (?, ?::date, 'AM', 1)
                """, orders.get(0).getId(), slot);

        // 第二笔预约：max+1 仍会算出 2（不冲突），但关键是**不会 500**且拿到有效号
        var r = appointmentController.book(
                new AppointmentController.BookReq(orders.get(1).getId(), slot, "AM"));
        assertEquals(0, r.getCode(), "取号冲突路径不得 500：" + r.getMessage());
        assertNotNull(r.getData().get("seqNo"));

        // 同一申请单重复预约：给业务码而非异常
        var dup = appointmentController.book(
                new AppointmentController.BookReq(orders.get(1).getId(), slot, "AM"));
        assertEquals(4511, dup.getCode());
    }

    /** C-3：出院释放床位必须用抢占后的当前床位（陈旧快照会让转床后的新床永久占用） */
    @Test
    void dischargeReleasesCurrentBedAfterTransfer() {
        Patient p = new Patient();
        p.setName("转床出院109");
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        var beds = jdbc.queryForList("select id from inp_bed where status = 'FREE' limit 2", Long.class);
        Long bedA = beds.get(0);
        Long bedB = beds.get(1);

        var adm = inpatientService.admit(pid, 1L, bedA, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null);
        entityManager.flush();
        inpatientService.transfer(adm.getId(), 1L, bedB, null);
        entityManager.flush();

        inpatientService.discharge(adm.getId(), null, "CASH");
        entityManager.flush();
        entityManager.clear();

        assertEquals("FREE", jdbc.queryForObject(
                "select status from inp_bed where id = ?", String.class, bedB), "转入的新床必须被释放");
        assertEquals("FREE", jdbc.queryForObject(
                "select status from inp_bed where id = ?", String.class, bedA), "转出的旧床应已释放");
    }

    /** C-4：发药凭条只列本次发的药（整表回查会把上午已发的也列进下午的凭条） */
    @Test
    void dispenseReturnsOnlyThisBatch() {
        Long rid = visitedRegistration("两次发药109");
        Long metId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("二甲双胍").get(0).getId();
        Long ibuId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("布洛芬").get(0).getId();

        doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", metId, 1, "口服", "bid", "1片", 7)), null);
        chargeService.settle(rid, "CASH", null);
        entityManager.flush();
        assertEquals(1, dispenseService.dispense(rid).size(), "第一次发药 1 条");

        doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", ibuId, 1, "口服", "tid", "1粒", 3)), null);
        chargeService.settle(rid, "CASH", null);
        entityManager.flush();
        var second = dispenseService.dispense(rid);
        assertEquals(1, second.size(), "第二次发药凭条只应含本次的 1 条，不得含上次已发的药");
        assertEquals(ibuId, second.get(0).getItemId());
    }

    /** B-2：退药抢占——重复退药被拒，库存只回补一次 */
    @Test
    void returnDrugIsIdempotent() {
        Long rid = visitedRegistration("重复退药109");
        Long drugId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("二甲双胍").get(0).getId();
        var order = doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId, 3, "口服", "bid", "1片", 7)), null).get(0);
        chargeService.settle(rid, "CASH", null);
        entityManager.flush();
        dispenseService.dispense(rid);
        entityManager.flush();
        entityManager.clear();

        int before = drugRepository.findById(drugId).orElseThrow().getStock();
        dispenseService.returnDrug(order.getId(), null);
        entityManager.flush();
        var again = assertThrows(RegistrationService.BizException.class,
                () -> dispenseService.returnDrug(order.getId(), null));
        assertEquals(6004, again.code);

        entityManager.clear();
        assertEquals(before + 3, drugRepository.findById(drugId).orElseThrow().getStock(),
                "库存只应回补一次");
    }

    /** C-5：日结按退费日归集——D1 收费 D2 退费，D1 报表不再被事后改写 */
    @Test
    void refundedAtIsActuallyUsedByDailyReport() {
        Long rid = visitedRegistration("退费归集109");
        Long drugId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("布洛芬").get(0).getId();
        doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "tid", "1粒", 3)), null);
        var charge = chargeService.settle(rid, "CASH", null);
        entityManager.flush();
        chargeService.refund(charge.getId());
        entityManager.flush();

        assertNotNull(jdbc.queryForObject(
                "select refunded_at from outp_charge where id = ?", java.sql.Timestamp.class, charge.getId()),
                "退费必须记录 refunded_at");

        // 把退费时间挪到明天，当日日结就不该再统计这笔退费
        jdbc.update("update outp_charge set refunded_at = now() + interval '1 day' where id = ?", charge.getId());
        var todayRefund = jdbc.queryForObject("""
                select coalesce(sum(total_amount) filter (
                    where status = 'REFUNDED' and coalesce(refunded_at, created_at)::date = current_date), 0)
                from outp_charge where id = ?
                """, BigDecimal.class, charge.getId());
        assertEquals(0, todayRefund.compareTo(BigDecimal.ZERO), "退费应按退费日归集，不再计入收费日");
    }
}
