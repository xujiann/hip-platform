package cn.hip.outpatient.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Ordinary registration is a shared desk for enabled administrators and cashiers. */
@Service @RequiredArgsConstructor
public class RegistrationAccess {
    private final JdbcTemplate jdbc;
    public void staff() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_CASHIER"))
                && !jdbc.queryForList("select id from sys_user where username=? and enabled=true", Long.class, auth.getName()).isEmpty()) return;
        throw new AccessDeniedException("无挂号台操作权限或操作人已停用");
    }
}
