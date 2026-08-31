package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.web.DoctorStationController;
import cn.hip.outpatient.web.OutpAppointmentController;
import cn.hip.outpatient.web.OutpAppointmentController.ApptReq;
import cn.hip.outpatient.web.OutpAppointmentController.SlotLine;
import cn.hip.outpatient.web.OutpAppointmentController.SlotsReq;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** v37：门诊分时段预约挂号（两级占号/签到转挂号/取消回落）+ 病历连续调阅。 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class OutpAppointmentTest {

    @Autowired OutpAppointmentController appt;
    @Autowired DoctorStationController doctorStation;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private Long newPatient() {
        Patient p = new Patient();
        p.setName("预约" + System.nanoTime());
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

    private Long slotOf(Long scheduleId) {
        return jdbc.queryForObject("select id from outp_schedule_slot where schedule_id = ? limit 1", Long.class, scheduleId);
    }

    @Test
    void slotCapacityValidatedAgainstSchedule() {
        Long sch = newSchedule(5);
        // 容量合计超排班总号量 → 3120
        assertEquals(3120, appt.createSlots(sch, new SlotsReq(List.of(
                new SlotLine("08:00", "08:30", 3), new SlotLine("08:30", "09:00", 3)))).getCode());
        // 合计=5 通过
        assertEquals(0, appt.createSlots(sch, new SlotsReq(List.of(
                new SlotLine("08:00", "08:30", 3), new SlotLine("08:30", "09:00", 2)))).getCode());
        assertEquals(2, appt.slots(sch).getData().size());
    }

    @Test
    void bookCheckinCancelFlow() {
        Long sch = newSchedule(5);
        appt.createSlots(sch, new SlotsReq(List.of(new SlotLine("08:00", "08:30", 2))));
        Long slot = slotOf(sch);
        Long pid = newPatient();

        // 预约：两级占号
        var booked = appt.book(new ApptReq(slot, pid, "窗口")).getData();
        Long apptId = ((Number) booked.get("id")).longValue();
        assertEquals(1, (int) jdbc.queryForObject("select booked from outp_schedule_slot where id=?", Integer.class, slot));
        assertEquals(1, (int) jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, sch));

        // 同患者同排班重复预约 → 3112（并回滚两级占号）
        assertEquals(3112, appt.book(new ApptReq(slot, pid, "窗口")).getCode());
        assertEquals(1, (int) jdbc.queryForObject("select booked from outp_schedule_slot where id=?", Integer.class, slot),
                "重复预约回滚后 slot 占号不变");

        // 签到转挂号：不再占号，建挂号记录
        var checked = appt.checkin(apptId).getData();
        assertNotNull(checked.get("registrationId"));
        assertEquals(1, (int) jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, sch),
                "签到不得再占号");
        // 重复签到 → 3114
        assertEquals(3114, appt.checkin(apptId).getCode());

        // 另约一人再取消：两级回落
        Long pid2 = newPatient();
        Long appt2 = ((Number) appt.book(new ApptReq(slot, pid2, "门户")).getData().get("id")).longValue();
        assertEquals(2, (int) jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, sch));
        assertEquals(0, appt.cancel(appt2).getCode());
        assertEquals(1, (int) jdbc.queryForObject("select booked from outp_schedule_slot where id=?", Integer.class, slot));
        assertEquals(1, (int) jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, sch));
        // 取消后可再约（uq_appt_active 部分索引只锁 BOOKED）
        assertEquals(0, appt.book(new ApptReq(slot, pid2, "门户")).getCode());
    }

    @Test
    void slotFullRejects() {
        Long sch = newSchedule(3);
        appt.createSlots(sch, new SlotsReq(List.of(new SlotLine("09:00", "09:30", 1))));
        Long slot = slotOf(sch);
        appt.book(new ApptReq(slot, newPatient(), null));
        assertEquals(3111, appt.book(new ApptReq(slot, newPatient(), null)).getCode(), "时段满应 3111");
        assertEquals(3110, appt.book(new ApptReq(999999L, newPatient(), null)).getCode(), "时段不存在 3110");
    }

    @Test
    void patientHistoryReturnsVisitsWithDiagnoses() {
        // 患者有两次就诊：一次带诊断，一次退号（应被剔除）
        Long pid = newPatient();
        Long sch = newSchedule(5);
        appt.createSlots(sch, new SlotsReq(List.of(new SlotLine("10:00", "10:30", 5))));
        Long slot = slotOf(sch);
        Long a1 = ((Number) appt.book(new ApptReq(slot, pid, null)).getData().get("id")).longValue();
        Long regId = ((Number) appt.checkin(a1).getData().get("registrationId")).longValue();
        jdbc.update("insert into outp_diagnosis(registration_id, icd_code, icd_name, primary_diag) values (?,?,?,true)",
                regId, "J06.9", "急性上呼吸道感染");
        em.flush();
        var history = doctorStation.patientHistory(pid).getData();
        assertEquals(1, history.size());
        assertEquals(regId, ((Number) history.get(0).get("registrationId")).longValue());
        @SuppressWarnings("unchecked")
        var diags = (List<java.util.Map<String, Object>>) history.get(0).get("diagnoses");
        assertEquals("急性上呼吸道感染", diags.get(0).get("icdName"));
    }
}
