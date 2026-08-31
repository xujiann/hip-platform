package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpatientController;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.web.OutpAppointmentController;
import cn.hip.outpatient.web.OutpAppointmentController.SlotLine;
import cn.hip.outpatient.web.OutpAppointmentController.SlotsReq;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.portal.web.PortalController;
import cn.hip.portal.web.PortalController.PortalApptRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v40 患者端两项功能：① 分时段预约（复用院内 v37 两级占号）② 住院费用/押金余额。
 *
 * <p>重点固化两条越权断言：患者 A 的令牌既不能取消 B 的预约，也不能读 B 的住院费用——
 * 患者端历史上出过「归属以前端传参为准」的事故，这两条是回归防线。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V40PortalTest {

    @Autowired PortalController portal;
    @Autowired OutpAppointmentController appt;
    @Autowired InpatientController inpatient;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    /** 患者端令牌主体：portal:{patientId} + ROLE_PORTAL（与 PortalController.patientId() 的取法一致） */
    private Authentication tokenOf(Long patientId) {
        return new UsernamePasswordAuthenticationToken("portal:" + patientId, null,
                List.of(new SimpleGrantedAuthority("ROLE_PORTAL")));
    }

    private Long newPatient(String prefix) {
        Patient p = new Patient();
        p.setName(prefix + System.nanoTime());
        p.setSex("F");
        return patientService.register(p).getId();
    }

    private Long newSchedule(int capacity) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(capacity);
        return scheduleRepository.save(s).getId();
    }

    /** 建排班 + 一个时段，返回 [scheduleId, slotId] */
    private long[] scheduleWithSlot(int capacity, int slotCapacity, String begin, String end) {
        Long sch = newSchedule(capacity);
        assertEquals(0, appt.createSlots(sch, new SlotsReq(List.of(new SlotLine(begin, end, slotCapacity)))).getCode());
        Long slot = jdbc.queryForObject(
                "select id from outp_schedule_slot where schedule_id = ? order by time_begin limit 1", Long.class, sch);
        return new long[]{sch, slot};
    }

    private int bookedOfSlot(long slotId) {
        return jdbc.queryForObject("select booked from outp_schedule_slot where id = ?", Integer.class, slotId);
    }

    private int bookedOfSchedule(long scheduleId) {
        return jdbc.queryForObject("select booked from outp_schedule where id = ?", Integer.class, scheduleId);
    }

    private Long admit(Long patientId, BigDecimal deposit) {
        em.flush();   // admit 走 JPA，取空闲床位前必须先落库，否则读到上一次已占的床
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        Long id = inpatientService.admit(patientId, 1L, bedId, null, "J18.9", "肺炎", deposit, "CASH", null).getId();
        em.flush();
        return id;
    }

    /** 直接落一条已执行医嘱（执行时刻定在给定日期 10 点，避开业务日期/时区带来的不确定） */
    private void executedOrder(Long admissionId, String date, String itemName, String amount) {
        jdbc.update("""
                insert into inp_order(admission_id, group_no, order_type, item_id, item_code, item_name,
                                      unit, qty, unit_price, amount, status, executed_at)
                values (?, 'V40', 'DRUG', 0, 'V40', ?, '次', 1, ?::numeric, ?::numeric, 'EXECUTED',
                        ?::date + interval '10 hours')
                """, admissionId, itemName, amount, amount, date);
    }

    // ===== ① 分时段预约 =====

    @Test
    void bookListCancelReleasesBothLevels() {
        long[] ids = scheduleWithSlot(5, 2, "08:00", "08:30");
        long sch = ids[0], slot = ids[1];
        Long pid = newPatient("门户预约");

        // 时段列表：患者端看到余号
        var slots = portal.scheduleSlots(sch).getData();
        assertEquals(1, slots.size());
        assertEquals(2, ((Number) slots.get(0).get("remaining")).intValue());

        // 预约：两级占号 + source 记 PORTAL + patient_id 取自令牌
        var booked = portal.bookAppointment(new PortalApptRequest(slot), tokenOf(pid));
        assertEquals(0, booked.getCode());
        Long apptId = ((Number) booked.getData().get("id")).longValue();
        assertEquals(1, bookedOfSlot(slot), "slot 占号");
        assertEquals(1, bookedOfSchedule(sch), "schedule 同池占号");
        var row = jdbc.queryForMap("select patient_id, source, status from outp_appointment where id = ?", apptId);
        assertEquals(pid, ((Number) row.get("patient_id")).longValue(), "患者身份必须来自令牌");
        assertEquals("PORTAL", row.get("source"));

        // 我的预约：只列本人
        var mine = portal.myAppointments(tokenOf(pid)).getData();
        assertEquals(1, mine.size());
        assertEquals(apptId, ((Number) mine.get(0).get("id")).longValue());
        assertTrue(portal.myAppointments(tokenOf(newPatient("旁人"))).getData().isEmpty(), "他人预约不得出现在我的列表");

        // 同排班重复预约 → 3112（院内同码，且不重复占号）
        assertEquals(3112, portal.bookAppointment(new PortalApptRequest(slot), tokenOf(pid)).getCode());
        assertEquals(1, bookedOfSlot(slot), "重复预约不得再占号");

        // 自助取消：两级释放
        assertEquals(0, portal.cancelAppointment(apptId, tokenOf(pid)).getCode());
        assertEquals(0, bookedOfSlot(slot), "取消后 slot 号源释放");
        assertEquals(0, bookedOfSchedule(sch), "取消后 schedule 号源释放");
        assertEquals("CANCELLED",
                jdbc.queryForObject("select status from outp_appointment where id = ?", String.class, apptId));

        // 取消后可再约（uq_appt_active 只锁 BOOKED）
        assertEquals(0, portal.bookAppointment(new PortalApptRequest(slot), tokenOf(pid)).getCode());
        assertEquals(1, bookedOfSlot(slot));

        // 时段满 → 3111（口径与院内一致）
        assertEquals(0, portal.bookAppointment(new PortalApptRequest(slot), tokenOf(newPatient("次位"))).getCode());
        assertEquals(3111, portal.bookAppointment(new PortalApptRequest(slot), tokenOf(newPatient("满员"))).getCode());
        assertEquals(3110, portal.bookAppointment(new PortalApptRequest(9999999L), tokenOf(pid)).getCode());
    }

    /** 越权重点①：患者 A 的令牌不能取消患者 B 的预约 */
    @Test
    void cannotCancelOtherPatientsAppointment() {
        long[] ids = scheduleWithSlot(5, 2, "09:00", "09:30");
        long sch = ids[0], slot = ids[1];
        Long pidB = newPatient("被害人B");
        Long pidA = newPatient("攻击者A");

        Long apptB = ((Number) portal.bookAppointment(new PortalApptRequest(slot), tokenOf(pidB))
                .getData().get("id")).longValue();

        var denied = portal.cancelAppointment(apptB, tokenOf(pidA));
        assertEquals(9506, denied.getCode(), "他人预约必须拒绝取消");
        assertEquals("BOOKED",
                jdbc.queryForObject("select status from outp_appointment where id = ?", String.class, apptB),
                "越权取消不得改动状态");
        assertEquals(1, bookedOfSlot(slot), "越权取消不得释放号源");
        assertEquals(1, bookedOfSchedule(sch));

        // 本人取消照常成功
        assertEquals(0, portal.cancelAppointment(apptB, tokenOf(pidB)).getCode());
    }

    // ===== ② 住院患者端 =====

    /** 越权重点②：患者 A 的令牌不能查患者 B 的住院费用/余额，也不会在自己列表里看到 B 的住院 */
    @Test
    void cannotReadOtherPatientsAdmission() {
        Long pidB = newPatient("住院B");
        Long pidA = newPatient("旁人A");
        Long admB = admit(pidB, new BigDecimal("1000"));
        String day = LocalDate.now().toString();
        executedOrder(admB, day, "输液", "120.00");

        assertEquals(9505, portal.myDailyFees(admB, LocalDate.parse(day), tokenOf(pidA)).getCode(),
                "他人住院费用清单必须拒绝");
        assertEquals(9505, portal.myAdmissionAccount(admB, tokenOf(pidA)).getCode(),
                "他人住院账户余额必须拒绝");
        assertTrue(portal.myAdmissions(tokenOf(pidA)).getData().stream()
                        .noneMatch(m -> ((Number) m.get("id")).longValue() == admB),
                "他人住院记录不得出现在我的列表");

        // 本人可读
        assertEquals(0, portal.myDailyFees(admB, LocalDate.parse(day), tokenOf(pidB)).getCode());
        assertEquals(0, portal.myAdmissionAccount(admB, tokenOf(pidB)).getCode());
        assertEquals(1, portal.myAdmissions(tokenOf(pidB)).getData().stream()
                .filter(m -> ((Number) m.get("id")).longValue() == admB).count());
    }

    /** 住院费用与余额口径与院内 InpatientController 完全一致（逐项比对，防口径漂移） */
    @Test
    void dailyFeesAndBalanceMatchWardSideFigures() {
        Long pid = newPatient("住院口径");
        Long admId = admit(pid, new BigDecimal("500"));
        String day = LocalDate.now().toString();
        executedOrder(admId, day, "抗生素", "300.00");
        executedOrder(admId, day, "床位费", "50.00");
        // 未执行医嘱：只作预判展示，不进余额
        jdbc.update("""
                insert into inp_order(admission_id, group_no, order_type, item_id, item_code, item_name,
                                      unit, qty, unit_price, amount, status)
                values (?, 'V40', 'DRUG', 0, 'V40', '待执行药', '次', 1, 77.00, 77.00, 'CREATED')
                """, admId);

        var mine = portal.myDailyFees(admId, LocalDate.parse(day), tokenOf(pid)).getData();
        var ward = inpatient.dailyFees(admId, day).getData();
        assertEquals(0, ((BigDecimal) mine.get("total")).compareTo((BigDecimal) ward.get("total")),
                "每日费用合计须与院内一致");
        assertEquals(0, ((BigDecimal) mine.get("total")).compareTo(new BigDecimal("350.00")));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) mine.get("rows");
        assertEquals(2, rows.size(), "只列当日已执行医嘱");

        var acc = portal.myAdmissionAccount(admId, tokenOf(pid)).getData();
        var wardAcc = inpatient.account(admId).getData();
        for (String k : new String[]{"depositTotal", "executedAmount", "pendingAmount", "balance"}) {
            assertEquals(0, ((BigDecimal) acc.get(k)).compareTo((BigDecimal) wardAcc.get(k)), k + " 口径须与院内一致");
        }
        assertEquals(wardAcc.get("owed"), acc.get("owed"));
        assertEquals(0, ((BigDecimal) acc.get("balance")).compareTo(new BigDecimal("150.00")), "500 押金 - 350 已发生");
        assertEquals(false, acc.get("owed"));

        // 欠费：余额为负且 owed=true（前端据此标红提示）
        executedOrder(admId, day, "手术费", "1000.00");
        var owedAcc = portal.myAdmissionAccount(admId, tokenOf(pid)).getData();
        assertTrue(((BigDecimal) owedAcc.get("balance")).signum() < 0, "欠费时余额为负");
        assertEquals(true, owedAcc.get("owed"));
        assertEquals(0, ((BigDecimal) owedAcc.get("balance"))
                .compareTo((BigDecimal) inpatient.account(admId).getData().get("balance")), "欠费口径同院内");
    }
}
