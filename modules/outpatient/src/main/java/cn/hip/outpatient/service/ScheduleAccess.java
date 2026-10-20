package cn.hip.outpatient.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Schedule management belongs to enabled administrators; the catalog remains shared. */
@Service @RequiredArgsConstructor
public class ScheduleAccess {
    private final JdbcTemplate jdbc;
    public void admin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))
                && !jdbc.queryForList("select id from sys_user where username=? and enabled=true", Long.class, auth.getName()).isEmpty()) return;
        throw new AccessDeniedException("无排班管理权限或管理员已停用");
    }
}
