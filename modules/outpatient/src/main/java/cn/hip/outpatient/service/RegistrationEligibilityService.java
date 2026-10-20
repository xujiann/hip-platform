package cn.hip.outpatient.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creation paths share this transaction lock; cancellation never waits for it. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationEligibilityService {
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    public void lock(Long scheduleId, Long patientId) {
        em.flush();
        // bigint IDs remain intact in the key; a hash collision only serializes unrelated requests.
        // Do not decode the void result of pg_advisory_xact_lock through a JDBC column mapper.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSetExtractor<Void>) rs -> null,
                "hip:outpatient:eligibility:" + scheduleId + ":" + patientId);
    }

    /** Read after acquiring the lock, before touching capacity, numbers or registration fees. */
    public boolean hasPending(Long scheduleId, Long patientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from outp_registration
                    where schedule_id = ? and patient_id = ? and status in ('REGISTERED','CALLED','PASSED')
                    union all
                    select 1 from outp_appointment
                    where schedule_id = ? and patient_id = ? and status = 'BOOKED'
                )
                """, Boolean.class, scheduleId, patientId, scheduleId, patientId));
    }
}
