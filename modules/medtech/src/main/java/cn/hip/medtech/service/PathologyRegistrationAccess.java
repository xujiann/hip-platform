package cn.hip.medtech.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/** Shared by both specimen producers; refund holds C then O, registration holds O before numbering. */
@Service
@RequiredArgsConstructor
public class PathologyRegistrationAccess {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public Long operator(Authentication supplied) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var roles = Set.of("ROLE_ADMIN", "ROLE_TECHNICIAN", "ROLE_DOCTOR_OUTP");
        if (auth == null || !auth.isAuthenticated()
                || auth.getAuthorities().stream().noneMatch(a -> roles.contains(a.getAuthority()))
                || (supplied != null && !Objects.equals(auth.getName(), supplied.getName())))
            throw new AccessDeniedException("无病理登记权限");
        var ids = jdbc.queryForList("""
                select u.id from sys_user u where u.username=? and u.enabled=true
                  and exists (select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id
                              where ur.user_id=u.id and r.code in ('ADMIN','TECHNICIAN','DOCTOR_OUTP'))
                """, Long.class, auth.getName());
        if (ids.isEmpty()) throw new AccessDeniedException("病理登记操作人不存在、已停用或岗位已变化");
        return ids.getFirst();
    }

    /** Fresh SQL after flush/lock, never use a previously managed source entity for this decision. */
    public String lockSource(boolean outpatient, Long id, Authentication supplied) {
        operator(supplied);
        entityManager.flush();
        var statuses = jdbc.queryForList(outpatient
                        ? "select status from outp_order where id=? for update"
                        : "select status from inp_order where id=? for update",
                String.class, id);
        operator(supplied);
        return statuses.isEmpty() ? null : statuses.getFirst();
    }
}
