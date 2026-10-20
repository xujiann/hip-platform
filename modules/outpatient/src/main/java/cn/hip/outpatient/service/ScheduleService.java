package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import static cn.hip.outpatient.service.RegistrationService.BizException;

@Service @RequiredArgsConstructor
public class ScheduleService {
    private final JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager em;
    private final ScheduleAccess access;
    private final OutpScheduleRepository schedules;

    public record CreateRequest(Long deptId, Long doctorId, LocalDate scheduleDate,
                                String shift, String regType, BigDecimal fee, Integer capacity) {}

    @Transactional
    public OutpSchedule create(CreateRequest req) {
        access.admin();
        String shift = req.shift() == null ? "FULL" : req.shift();
        String regType = req.regType() == null ? "GENERAL" : req.regType();
        BigDecimal fee = req.fee() == null ? BigDecimal.ZERO : req.fee();
        if (req.deptId() == null || req.scheduleDate() == null || req.capacity() == null || req.capacity() <= 0
                || !Set.of("AM", "PM", "FULL").contains(shift) || !Set.of("GENERAL", "EXPERT").contains(regType)
                || fee.signum() < 0 || fee.stripTrailingZeros().scale() > 2 || fee.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new BizException(3101, "科室、日期、号源数量必填，时段、号别及挂号费须合法");
        }
        em.flush();
        references(req.deptId(), req.doctorId());
        access.admin();
        var schedule = new OutpSchedule();
        schedule.setDeptId(req.deptId());
        schedule.setDoctorId(req.doctorId());
        schedule.setScheduleDate(req.scheduleDate());
        schedule.setShift(shift);
        schedule.setRegType(regType);
        schedule.setFee(fee);
        schedule.setCapacity(req.capacity());
        // id, createdAt, enabled=true and booked=0 are never request-bound.
        return schedules.saveAndFlush(schedule);
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        access.admin();
        em.flush();
        var rows = jdbc.queryForList("select dept_id,doctor_id from outp_schedule where id=? for update", id);
        if (rows.isEmpty()) throw new BizException(3102, "排班不存在");
        access.admin();
        if (enabled) {
            var row = rows.getFirst();
            references(((Number) row.get("dept_id")).longValue(), row.get("doctor_id") == null ? null : ((Number) row.get("doctor_id")).longValue());
            access.admin();
        }
        // A single-column update is essential: saving a previously loaded entity can
        // overwrite booked after waiting for concurrent registration or cancellation.
        jdbc.update("update outp_schedule set enabled=? where id=?", enabled, id);
        em.clear();
    }

    private void references(Long deptId, Long doctorId) {
        var depts = jdbc.queryForList("select enabled,type from sys_dept where id=? for share", deptId);
        if (depts.size() != 1 || !Boolean.TRUE.equals(depts.getFirst().get("enabled")) || !"CLINICAL".equals(depts.getFirst().get("type"))) {
            throw new BizException(3103, "排班科室不存在、已停用或非临床科室");
        }
        if (doctorId != null) {
            var users = jdbc.queryForList("select enabled from sys_user where id=? for share", Boolean.class, doctorId);
            if (users.size() != 1 || !Boolean.TRUE.equals(users.getFirst()) || jdbc.queryForList("select ur.user_id from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=? and r.code='DOCTOR_OUTP'", Long.class, doctorId).isEmpty()) {
                throw new BizException(3104, "指定医生不存在、已停用或无门诊医生岗位");
            }
        }
    }
}
