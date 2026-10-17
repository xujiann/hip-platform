package cn.hip.inpatient.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InpatientDiagnosisService {
    private final JdbcTemplate jdbc;
    private final InpatientWriteAccess access;

    /** Authorize after waiting for a concurrent handoff, and hold the row lock through insertion. */
    @Transactional
    public boolean add(Long admissionId, String icd, String name) {
        access.assertCanCodeDiagnosis(admissionId);
        // A duplicate is a normal business result, not an aborted PostgreSQL transaction.
        return jdbc.update("""
            insert into inp_diagnosis(admission_id, icd, name) values (?,?,?)
            on conflict (admission_id, icd) do nothing
            """, admissionId, icd, name) == 1;
    }
}
