package cn.hip.inpatient.service;

import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.inpatient.service.InpatientService.InpException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serialize physician decisions against attending changes on the admission row. */
@Service
@RequiredArgsConstructor
public class InpatientWriteAccess {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertCanWrite(Long admissionId) {
        assertAccess(admissionId, java.util.Set.of("ROLE_ADMIN"));
    }

    /** Ward coordination keeps the existing nursing workflow; doctors still need ownership. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertCanCoordinateCare(Long admissionId) {
        assertAccess(admissionId, java.util.Set.of("ROLE_ADMIN", "ROLE_NURSE"));
    }

    /** Interim billing additionally belongs to the cashier; it does not grant clinical writing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertCanInterimSettle(Long admissionId) {
        assertAccess(admissionId, java.util.Set.of("ROLE_ADMIN", "ROLE_NURSE", "ROLE_CASHIER"));
    }

    private void assertAccess(Long admissionId, java.util.Set<String> crossAdmissionRoles) {
        var rows = jdbc.queryForList("select doctor_id from inp_admission where id = ? for update", admissionId);
        if (rows.isEmpty()) throw new InpException(9003, "住院记录不存在");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // Trusted non-HTTP callers are preserved; HTTP entries retain their own role gates.
        if (auth == null) return;
        if (auth.getAuthorities().stream().anyMatch(a -> crossAdmissionRoles.contains(a.getAuthority()))) return;
        boolean doctor = auth.getAuthorities().stream().anyMatch(a -> "ROLE_DOCTOR_OUTP".equals(a.getAuthority()));
        Long me = doctor ? currentUserService.idOf(auth) : null;
        Object owner = rows.getFirst().get("doctor_id");
        if (me == null || (owner != null && !me.equals(((Number) owner).longValue()))) {
            throw new InpException(9018, "无权操作该病案（非本人主管）");
        }
    }

    /** Match the public attending dictionary, including the existing ADMIN compatibility. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertEligibleAttending(Long doctorId) {
        if (doctorId == null || doctorId <= 0) {
            throw new InpException(4884, "主管医生必填");
        }
        Boolean eligible = jdbc.queryForObject("""
            select exists (
                select 1 from sys_user u
                join sys_user_role ur on ur.user_id = u.id
                join sys_role r on r.id = ur.role_id
                where u.id = ? and u.enabled = true and r.code in ('DOCTOR_OUTP','ADMIN')
            )
            """, Boolean.class, doctorId);
        if (!Boolean.TRUE.equals(eligible)) {
            throw new InpException(4884, "主管医生不存在、已停用或无医生岗位");
        }
    }

    @Transactional
    public void setAttending(Long admissionId, Long doctorId) {
        assertCanWrite(admissionId);
        assertEligibleAttending(doctorId);
        jdbc.update("update inp_admission set doctor_id = ? where id = ?", doctorId, admissionId);
    }

    @Transactional
    public void setDischargeDiagnosis(Long admissionId, String icd, String name) {
        assertCanWrite(admissionId);
        jdbc.update("update inp_admission set discharge_diag_icd = ?, discharge_diag_name = ? where id = ?",
                icd, name, admissionId);
    }
}
