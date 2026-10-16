package cn.hip.inpatient.service;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Object scope for clinical document reads; nursing retains its existing cross-admission workflow. */
@Service
@RequiredArgsConstructor
public class InpatientReadAccess {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    public void assertCanRead(Long admissionId) {
        var rows = jdbc.queryForList("select doctor_id from inp_admission where id = ?", admissionId);
        if (rows.isEmpty()) throw new HipBizException(9003, "住院记录不存在");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            boolean privileged = auth.getAuthorities().stream().anyMatch(a ->
                    "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_NURSE".equals(a.getAuthority()));
            if (privileged) return;
            boolean doctor = auth.getAuthorities().stream().anyMatch(a -> "ROLE_DOCTOR_OUTP".equals(a.getAuthority()));
            Long me = doctor ? currentUserService.idOf(auth) : null;
            Object owner = rows.getFirst().get("doctor_id");
            if (me != null && (owner == null || me.equals(((Number) owner).longValue()))) return;
        }
        throw new org.springframework.security.access.AccessDeniedException("无权查阅该住院记录（非本人主管）");
    }
}
