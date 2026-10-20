package cn.hip.outpatient.web;

import cn.hip.outpatient.service.AppointmentService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v37 门诊分时段预约挂号：排班下分时段（slot）→ 预约（两级原子占号：slot + schedule 同池）→
 * 签到转正式挂号（不再占号）/ 取消（两级回落）。walk-in register() 完全不动。
 * 错误码 3110-3120（挂号段空档，见 docs/错误码分段.md）。
 *
 * <p>v40：占号/取消的逻辑本体已抽到 {@link AppointmentService}，与患者端 /api/portal 共用同一份
 * （行为不变，本类只做入参→service→响应体的转换）——避免两个入口各写一份号源逻辑而漂移。
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
@RequiredArgsConstructor
public class OutpAppointmentController {
    private final cn.hip.outpatient.service.RegistrationEligibilityService eligibility;

    private final JdbcTemplate jdbc;
    private final RegistrationService registrationService;
    private final AppointmentService appointmentService;
    private final cn.hip.outpatient.service.AppointmentAccess access;

    public record SlotLine(String timeBegin, String timeEnd, Integer capacity) {}
    public record SlotsReq(List<SlotLine> slots) {}

    /** 建时段（ADMIN 排班管理）：校验时段容量合计 ≤ 排班容量，防预约先于 walk-in 打满容量池 */
    @PostMapping("/api/outpatient/schedules/{scheduleId}/slots")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Void> createSlots(@PathVariable Long scheduleId, @RequestBody SlotsReq req) {
        access.admin();
        if (req.slots() == null || req.slots().isEmpty()) return R.fail(3120, "时段列表不能为空");
        var cap = jdbc.queryForList("select capacity from outp_schedule where id=? for update", Integer.class, scheduleId);
        if (cap.isEmpty()) return R.fail(3001, "号源不存在");
        access.admin();
        long sum = 0;
        var begins = new java.util.HashSet<java.time.LocalTime>();
        for(var existing:jdbc.queryForList("select time_begin::text from outp_schedule_slot where schedule_id=?",String.class,scheduleId))
            begins.add(java.time.LocalTime.parse(existing));
        for (var line : req.slots()) {
            if(line==null || line.timeBegin()==null || line.timeEnd()==null || line.capacity()==null || line.capacity()<=0)
                return R.fail(3120,"时段起止时间与容量必填且容量>0");
            try {
                var begin=java.time.LocalTime.parse(line.timeBegin());
                var end=java.time.LocalTime.parse(line.timeEnd());
                if(!end.isAfter(begin) || !begins.add(begin))return R.fail(3120,"时段结束须晚于开始且开始时间不能重复");
            } catch(java.time.format.DateTimeParseException ex) {return R.fail(3120,"时段时间格式非法");}
            sum += line.capacity();
        }
        Long existing = jdbc.queryForObject("select coalesce(sum(capacity),0) from outp_schedule_slot where schedule_id=? and enabled",Long.class,scheduleId);
        if(sum+(existing==null?0:existing)>cap.getFirst())return R.fail(3120,"时段容量合计超过排班总号量");
        int seq = 0;
        for (var s : req.slots()) {
            jdbc.update("""
                    insert into outp_schedule_slot(schedule_id, time_begin, time_end, seq_no, capacity)
                    values (?, ?::time, ?::time, ?, ?)
                    """, scheduleId, s.timeBegin(), s.timeEnd(), seq++, s.capacity());
        }
        return R.ok();
    }

    /** 时段列表 + 余号（院内需看到停用时段，故 onlyEnabled=false） */
    @GetMapping("/api/outpatient/schedules/{scheduleId}/slots")
    public R<List<Map<String, Object>>> slots(@PathVariable Long scheduleId) {
        access.staff();
        return R.ok(appointmentService.slots(scheduleId, false));
    }

    public record ApptReq(Long slotId, Long patientId, String source) {}

    /** 预约：两级原子占号（slot 满 3111；schedule 池满同样 3111 回滚；同患者同排班重复 3112） */
    @PostMapping("/api/outpatient/appointments")
    public R<Map<String, Object>> book(@RequestBody ApptReq req) {
        var r = appointmentService.bookFromDesk(req.slotId(), req.patientId(), req.source());
        if (!r.ok()) return R.fail(r.code(), r.message());
        return R.ok(Map.of("id", r.apptId(), "apptNo", r.apptNo()));
    }

    /** 签到转正式挂号：抢占 BOOKED→CHECKED_IN，建挂号记录（不再占号），回填 registration_id */
    @PostMapping("/api/outpatient/appointments/{id}/checkin")
    @Transactional
    public R<Map<String, Object>> checkin(@PathVariable Long id) {
        access.staff();
        var rows = jdbc.queryForList(
                "select schedule_id, patient_id, appt_no, status from outp_appointment where id = ? for update", id);
        if (rows.isEmpty()) return R.fail(3113, "预约不存在");
        access.staff();
        if(!"BOOKED".equals(rows.getFirst().get("status")))return R.fail(3114,"预约状态不允许签到（已签到或已取消）");
        appointmentService.requireActivePatient(((Number) rows.getFirst().get("patient_id")).longValue());
        eligibility.lock(((Number) rows.getFirst().get("schedule_id")).longValue(),
                ((Number) rows.getFirst().get("patient_id")).longValue());
        access.staff();
        // 条件更新抢占：并发双签到只有一方成功
        if (jdbc.update("update outp_appointment set status = 'CHECKED_IN' where id = ? and status = 'BOOKED'", id) == 0) {
            return R.fail(3114, "预约状态不允许签到（已签到或已取消）");
        }
        var row = rows.get(0);
        var reg = registrationService.registerFromAppointment(
                ((Number) row.get("patient_id")).longValue(),
                ((Number) row.get("schedule_id")).longValue(),
                ((Number) row.get("appt_no")).intValue());
        jdbc.update("update outp_appointment set registration_id = ? where id = ?", reg.getId(), id);
        return R.ok(Map.of("registrationId", reg.getId(), "regNo", reg.getRegNo()));
    }

    /** 取消预约：抢占 BOOKED→CANCELLED，两级释放号源 */
    @PostMapping("/api/outpatient/appointments/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        var r = appointmentService.cancelFromDesk(id);
        return r.ok() ? R.ok() : R.fail(r.code(), r.message());
    }

    /** 签到台列表：某排班的预约（含患者姓名与状态） */
    @GetMapping("/api/outpatient/appointments")
    public R<List<Map<String, Object>>> list(@RequestParam Long scheduleId) {
        access.staff();
        return R.ok(jdbc.queryForList("""
                select a.id, a.appt_no, a.status, a.source, a.registration_id, a.created_at,
                       p.name as patient_name, p.patient_no,
                       s.time_begin, s.time_end
                from outp_appointment a
                join empi_patient p on p.id = a.patient_id
                join outp_schedule_slot s on s.id = a.slot_id
                where a.schedule_id = ? order by s.time_begin, a.appt_no
                """, scheduleId));
    }
}
