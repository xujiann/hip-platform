package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DispenseService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.outpatient.web.ErObservationController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 并发缺陷修复回归：处方号序列化、退药原子回补、留观占床唯一约束 */
/**
 * ⚠ 名不副实警示（1.1.9）：本类是 @Transactional **单线程**测试，断言的是"单号来自 DB 序列
 * 且单调"——这是并发安全的必要非充分证据。**不要模仿本类在 @Transactional 里写并发测试**：
 * 事务参与者语义下回滚/提交时序全被掩盖。真并发/真提交范式见 StockRestoreConcurrencyTest
 * 与 Phase116ChannelOrderTest（多线程各自提交 / 有意不加 @Transactional）。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class ConcurrencyFixTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired DispenseService dispenseService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired OutpOrderRepository orderRepository;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ErObservationController erObservationController;
    @Autowired JdbcTemplate jdbc;

    private Long newVisitedRegistration(String patientName) {
        Patient p = new Patient();
        p.setName(patientName);
        p.setSex("U");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(9);
        s = scheduleRepository.save(s);
        Long regId = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(regId, null);
        return regId;
    }

    private long groupSeqOf(String groupNo) {
        return Long.parseLong(groupNo.substring(groupNo.lastIndexOf('-') + 1));
    }

    @Test
    void groupNoComesFromDbSequenceAndNeverRepeats() {
        Long drugId = seeds.drug("阿莫西林").getId();
        Long reg1 = newVisitedRegistration("序列测试一");
        Long reg2 = newVisitedRegistration("序列测试二");
        var line = new OrderLine("DRUG", drugId, 1, "口服", "tid", "1粒", 3);

        String no1 = doctorStationService.createOrders(reg1, List.of(line), null).get(0).getGroupNo();
        String no2 = doctorStationService.createOrders(reg2, List.of(line), null).get(0).getGroupNo();

        String stamp = cn.hip.platform.core.config.BusinessDates.today().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        assertTrue(no1.startsWith("CF" + stamp + "-"));
        assertNotEquals(no1, no2);
        // 序列单调递增：与服务实例内存状态无关，重启/多实例不撞号
        assertTrue(groupSeqOf(no2) > groupSeqOf(no1));
        Long dbNext = jdbc.queryForObject("select nextval('outp_order_group_seq')", Long.class);
        assertTrue(dbNext > groupSeqOf(no2));
    }

    @Test
    void returnDrugFailsWhenDrugMissingViaAtomicUpdate() {
        Long regId = newVisitedRegistration("退药兜底");
        OutpOrder o = new OutpOrder();
        o.setRegistrationId(regId);
        o.setGroupNo("CF-TEST-0");
        o.setOrderType("DRUG");
        o.setItemId(999_999_999L);
        o.setItemCode("X");
        o.setItemName("不存在的药");
        o.setUnit("盒");
        o.setQty(1);
        o.setUnitPrice(BigDecimal.ONE);
        o.setAmount(BigDecimal.ONE);
        o.setStatus("DISPENSED");
        Long orderId = orderRepository.save(o).getId();

        var e = assertThrows(BizException.class, () -> dispenseService.returnDrug(orderId, null));
        assertEquals(6005, e.code);
    }

    private Long insertTriage(String name) {
        return jdbc.queryForObject(
                "insert into outp_triage(patient_name, level, chief_complaint) values (?, 3, '测试') returning id",
                Long.class, name);
    }

    @Test
    void erObservationPreChecksStillReturnFriendlyErrors() {
        Long triageA = insertTriage("留观甲");
        Long triageB = insertTriage("留观乙");
        assertEquals(0, erObservationController.start(
                new ErObservationController.StartReq(triageA, "E01")).getCode());
        // 同一分诊重复入观
        assertEquals(4561, erObservationController.start(
                new ErObservationController.StartReq(triageA, "E02")).getCode());
        // 床位已占
        assertEquals(4562, erObservationController.start(
                new ErObservationController.StartReq(triageB, "E01")).getCode());
    }

    @Test
    void erObservationDuplicateActiveTriageBlockedAtDatabase() {
        Long triage = insertTriage("留观丙");
        jdbc.update("insert into er_observation(triage_id, bed_no) values (?, 'E11')", triage);
        // 绕过应用层预检查直接并发写入的情形：部分唯一索引在数据库层拦截
        assertThrows(DuplicateKeyException.class, () ->
                jdbc.update("insert into er_observation(triage_id, bed_no) values (?, 'E12')", triage));
    }

    @Test
    void erObservationDuplicateActiveBedBlockedAtDatabase() {
        Long triageA = insertTriage("留观丁");
        Long triageB = insertTriage("留观戊");
        jdbc.update("insert into er_observation(triage_id, bed_no) values (?, 'E21')", triageA);
        assertThrows(DuplicateKeyException.class, () ->
                jdbc.update("insert into er_observation(triage_id, bed_no) values (?, 'E21')", triageB));
    }

    @Test
    void erObservationBedReusableAfterEnd() {
        Long triageA = insertTriage("留观己");
        Long triageB = insertTriage("留观庚");
        jdbc.update("insert into er_observation(triage_id, bed_no) values (?, 'E31')", triageA);
        jdbc.update("update er_observation set status = 'OUT', outcome = 'DISCHARGED', ended_at = now() where triage_id = ?", triageA);
        // 离观后床位与患者都可再次入观（部分唯一索引只约束 status='IN'）
        assertEquals(0, erObservationController.start(
                new ErObservationController.StartReq(triageB, "E31")).getCode());
        assertEquals(0, erObservationController.start(
                new ErObservationController.StartReq(triageA, "E32")).getCode());
    }
}
