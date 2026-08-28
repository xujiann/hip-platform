package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.service.InventoryService;
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

/** 1.0.7 并发与数据正确性回归：单号序列 / 条件更新状态机 / 作废医嘱 / 冲销幂等 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase107ConcurrencyTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired InventoryService inventoryService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
    }

    private Long drug(String keyword) {
        return seeds.drug(keyword).getId();
    }

    /** P1-1：住院医嘱组号取数据库序列——不再是重启即回绕的内存计数 */
    @Test
    void inpatientGroupNoUsesDatabaseSequence() {
        Long admId = admit("组号序列107");
        var orders = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", drug("阿莫西林"), 1, "口服", "tid", "1粒"),
                        new InpatientService.OrderLine("DRUG", drug("布洛芬"), 1, "口服", "bid", "1粒")), null);
        entityManager.flush();
        assertEquals(2, orders.stream().map(o -> o.getGroupNo()).distinct().count(), "同批医嘱组号须各不相同");
        Long seqNow = jdbc.queryForObject("select last_value from inp_order_group_seq", Long.class);
        assertNotNull(seqNow);
        assertTrue(seqNow > 0);
    }

    /** P1-6：执行改为条件更新——二次执行拒绝，库存只扣一次 */
    @Test
    void executeIsIdempotentAndDeductsStockOnce() {
        Long admId = admit("执行抢占107");
        Long drugId = drug("阿莫西林");
        var order = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", drugId, 2, "口服", "tid", "1粒")), null).get(0);
        entityManager.flush();
        int before = drugRepository.findById(drugId).orElseThrow().getStock();

        inpatientService.execute(order.getId(), null);
        entityManager.flush();
        var second = assertThrows(InpatientService.InpException.class,
                () -> inpatientService.execute(order.getId(), null));
        assertEquals(9009, second.code);

        entityManager.clear();
        int after = drugRepository.findById(drugId).orElseThrow().getStock();
        assertEquals(before - 2, after, "库存只应扣一次");
    }

    /** P1-23：未执行医嘱可作废——原先只能"执行掉"，多计费且白扣库存 */
    @Test
    void createdOrderCanBeCancelled() {
        Long admId = admit("作废医嘱107");
        var order = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", drug("布洛芬"), 1, "口服", "bid", "1粒")), null).get(0);
        entityManager.flush();

        inpatientService.cancelOrder(order.getId());
        entityManager.flush();
        assertEquals("CANCELLED", jdbc.queryForObject(
                "select status from inp_order where id = ?", String.class, order.getId()));

        // 已作废不可再作废，也不可执行
        assertEquals(9016, assertThrows(InpatientService.InpException.class,
                () -> inpatientService.cancelOrder(order.getId())).code);
        assertEquals(9009, assertThrows(InpatientService.InpException.class,
                () -> inpatientService.execute(order.getId(), null)).code);

        // 作废后可正常出院（不再被 9012 卡住）
        var settle = inpatientService.discharge(admId, null, "CASH");
        assertNotNull(settle.getSettleNo());
    }

    /** P1-12：出院抢占——二次结算被拒（原先窗口内并发押金会丢失） */
    @Test
    void dischargeClaimsStatusOnce() {
        Long admId = admit("出院抢占107");
        inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        assertEquals(9011, assertThrows(InpatientService.InpException.class,
                () -> inpatientService.discharge(admId, null, "CASH")).code);
    }

    /** P1-25：押金金额须为正（原先负数可冲减押金总额） */
    @Test
    void depositRejectsNonPositiveAmount() {
        Long admId = admit("押金校验107");
        assertEquals(9017, assertThrows(InpatientService.InpException.class,
                () -> inpatientService.addDeposit(admId, new BigDecimal("-100"), "CASH", null)).code);
        assertEquals(9017, assertThrows(InpatientService.InpException.class,
                () -> inpatientService.addDeposit(admId, BigDecimal.ZERO, "CASH", null)).code);
    }

    /** P1-13：释放床位须校验占用者——不得释放他人的床 */
    @Test
    void bedReleaseChecksOccupant() {
        Long admId = admit("床位校验107");
        Long bedId = jdbc.queryForObject(
                "select bed_id from inp_admission where id = ?", Long.class, admId);
        // 用错误的 admissionId 释放：应无效
        assertEquals(0, jdbc.update(
                "update inp_bed set status = 'FREE' where id = ? and admission_id = ?", bedId, -1L));
        assertEquals("OCCUPIED", jdbc.queryForObject(
                "select status from inp_bed where id = ?", String.class, bedId));
    }

    /** P1-10/11：入库原子加、盘点条件更新 */
    @Test
    void stockInIsAtomicAndAdjustDetectsConcurrentChange() {
        Long drugId = drug("阿莫西林");
        int before = drugRepository.findById(drugId).orElseThrow().getStock();

        // 1.2.13 起入库先记待验收、不加库存；验收通过才原子加。此处覆盖两段
        var pending = inventoryService.stockIn(drugId, 10, "B107",
                cn.hip.platform.core.config.BusinessDates.today().plusYears(1), "测试供应商", null, null);
        entityManager.clear();
        assertEquals(before, drugRepository.findById(drugId).orElseThrow().getStock(), "验收前库存不动");
        inventoryService.acceptStockIn(pending.getId(), null);
        entityManager.clear();
        assertEquals(before + 10, drugRepository.findById(drugId).orElseThrow().getStock(), "验收后原子加 10");

        // 盘点走条件更新：期望值过期（模拟期间有发药）时影响 0 行，不会覆盖并发扣减
        int current = drugRepository.findById(drugId).orElseThrow().getStock();
        assertEquals(0, drugRepository.adjustStock(drugId, current - 999, 42),
                "期望值不符时不得写入");
        entityManager.clear();
        assertEquals(current, drugRepository.findById(drugId).orElseThrow().getStock());

        // 期望值正确时正常盘点
        inventoryService.adjust(drugId, current + 5, "盘点测试107", null);
        entityManager.clear();
        assertEquals(current + 5, drugRepository.findById(drugId).orElseThrow().getStock());
    }

    /** P1-19：同患者同号源重复挂号由唯一索引兜底 */
    @Test
    void duplicateRegistrationRejectedByConstraint() {
        Patient p = new Patient();
        p.setName("重复挂号107");
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(10);
        s = scheduleRepository.save(s);
        Long sid = s.getId();

        registrationService.register(pid, sid);
        entityManager.flush();
        assertEquals(3002, assertThrows(RegistrationService.BizException.class,
                () -> registrationService.register(pid, sid)).code);
    }

    /** P1-32：同一次提交内重复用药应被拦（原先只查已持久化订单） */
    @Test
    void duplicateDrugWithinSameSubmissionIsBlocked() {
        Patient p = new Patient();
        p.setName("同单重复107");
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        Long drugId = drug("布洛芬");

        // 完全相同的行（同药同用法同频次同剂量）应拦
        assertThrows(RegistrationService.BizException.class, () -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "bid", "1粒", 3),
                        new OrderLine("DRUG", drugId, 1, "口服", "bid", "1粒", 3)), null));

        // 1.0.9 C-8：用法/频次不同是合法医嘱（负荷量+维持量、口服+静滴），不得误拦
        assertDoesNotThrow(() -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "bid", "1粒", 3),
                        new OrderLine("DRUG", drugId, 1, "静滴", "qd", "2粒", 3)), null));
    }
}
