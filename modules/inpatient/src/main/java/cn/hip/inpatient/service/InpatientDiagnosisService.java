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

    /** Keep delete in the same parent-before-child lock order as insertion and attending handoff. */
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','QUALITY')")
    public boolean remove(Long diagnosisId) {
        var admissions = jdbc.queryForList("select admission_id from inp_diagnosis where id = ?", Long.class, diagnosisId);
        if (admissions.isEmpty()) return false;
        Long admissionId = admissions.getFirst();
        access.assertCanCodeDiagnosis(admissionId);
        return jdbc.update("delete from inp_diagnosis where id = ? and admission_id = ?", diagnosisId, admissionId) == 1;
    }
}
