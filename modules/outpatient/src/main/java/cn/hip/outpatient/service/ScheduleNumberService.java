package cn.hip.outpatient.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One schedule row serializes capacity and ticket issuance for both entry paths. */
@Service
@RequiredArgsConstructor
public class ScheduleNumberService {
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public int occupy(Long scheduleId, int unavailableCode) {
        em.flush();
        var numbers = jdbc.queryForList("""
                update outp_schedule
                set booked = booked + 1, last_issued_no = last_issued_no + 1
                where id = ? and enabled and booked < capacity and last_issued_no < 2147483647
                returning last_issued_no
                """, Integer.class, scheduleId);
        if (numbers.isEmpty()) {
            throw new RegistrationService.BizException(unavailableCode, "排班号源不可用或号码已用尽");
        }
        // As with the former repository modifying query, discard stale managed booked values.
        // The cursor itself is deliberately not mapped to the outward-facing JPA entity.
        em.clear();
        return numbers.getFirst();
    }
}
