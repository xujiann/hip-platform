package cn.hip.outpatient.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.Set;

/** Today's shared outpatient board; writes belong to the outpatient clinical duty. */
@Service
@RequiredArgsConstructor
public class QueueAccess {
    private final JdbcTemplate jdbc;
    public void read() { require(Set.of("ROLE_ADMIN", "ROLE_DOCTOR_OUTP", "ROLE_NURSE", "ROLE_TECHNICIAN", "ROLE_CASHIER")); }
    public void write() { require(Set.of("ROLE_ADMIN", "ROLE_DOCTOR_OUTP")); }
    private void require(Set<String> roles) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth.getAuthorities().stream().noneMatch(a -> roles.contains(a.getAuthority()))
                || !Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from sys_user where username=? and enabled=true)", Boolean.class, auth.getName())))
            throw new AccessDeniedException("无权访问门诊叫号队列");
    }
}
