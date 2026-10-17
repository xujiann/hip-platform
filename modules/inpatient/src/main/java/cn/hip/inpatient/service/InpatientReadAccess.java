package cn.hip.inpatient.service;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;

/** Current attending scope, with explicit exceptions for existing nursing, billing and coding duties. */
@Service
@RequiredArgsConstructor
public class InpatientReadAccess {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    /** SQL consumers use the fixed admission alias a; values are always bound parameters. */
    public record Scope(boolean all, Long doctorId) {
        public boolean includes(Long owner) {
            return all || (doctorId != null && (owner == null || doctorId.equals(owner)));
        }
        public void appendSql(StringBuilder sql, List<Object> args) {
            if (all) return;
            if (doctorId == null) {sql.append(" and 1 = 0");return;}
            sql.append(" and (a.doctor_id is null or a.doctor_id = ?)");args.add(doctorId);
        }
    }

    public Scope clinicalScope() {
        return scope(Set.of("ROLE_ADMIN", "ROLE_NURSE"));
    }

    private Scope scope(Set<String> crossAdmissionRoles) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return new Scope(false, null);
        if (auth.getAuthorities().stream().anyMatch(a -> crossAdmissionRoles.contains(a.getAuthority()))) {
            return new Scope(true, null);
        }
        boolean doctor = auth.getAuthorities().stream().anyMatch(a -> "ROLE_DOCTOR_OUTP".equals(a.getAuthority()));
        return new Scope(false, doctor ? currentUserService.idOf(auth) : null);
    }

    public void assertCanRead(Long admissionId) {
        assertScope(admissionId, clinicalScope());
    }

    public void assertCanReadBilling(Long admissionId) {
        assertScope(admissionId, scope(Set.of("ROLE_ADMIN", "ROLE_NURSE", "ROLE_CASHIER")));
    }

    public void assertCanReadCoding(Long admissionId) {
        assertScope(admissionId, scope(Set.of("ROLE_ADMIN", "ROLE_QUALITY")));
    }

    private void assertScope(Long admissionId, Scope scope) {
        var rows = jdbc.queryForList("select doctor_id from inp_admission where id = ?", admissionId);
        if (rows.isEmpty()) throw new HipBizException(9003, "住院记录不存在");
        Object owner = rows.getFirst().get("doctor_id");
        if (scope.includes(owner == null ? null : ((Number) owner).longValue())) return;
        throw new org.springframework.security.access.AccessDeniedException("无权查阅该住院记录（非本人主管）");
    }
}
