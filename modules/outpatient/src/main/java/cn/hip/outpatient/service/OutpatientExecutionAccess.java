package cn.hip.outpatient.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/** Shared execution/nursing duties; re-query the live employee after business-row waits. */
@Service
@RequiredArgsConstructor
public class OutpatientExecutionAccess {
    private final JdbcTemplate jdbc;

    public Long exec(Authentication supplied) {
        return require(Set.of("ROLE_ADMIN", "ROLE_TECHNICIAN", "ROLE_NURSE"), supplied);
    }
    public Long nurse(Authentication supplied) {
        return require(Set.of("ROLE_ADMIN", "ROLE_NURSE"), supplied);
    }
    public void refund(Long operatorId) {
        // Preserve trusted, unauthenticated in-process service calls. HTTP still requires its cashier role.
        if (SecurityContextHolder.getContext().getAuthentication() == null) return;
        Long me = require(Set.of("ROLE_ADMIN", "ROLE_CASHIER"), null);
        if (operatorId != null && !me.equals(operatorId))
            throw new AccessDeniedException("退费操作人与当前登录员工不一致");
    }
    private Long require(Set<String> roles, Authentication supplied) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth.getAuthorities().stream().noneMatch(a -> roles.contains(a.getAuthority()))
                || (supplied != null && !Objects.equals(auth.getName(), supplied.getName())))
            throw new AccessDeniedException("无权执行当前门诊操作");
        var ids = jdbc.queryForList("select id from sys_user where username=? and enabled=true", Long.class, auth.getName());
        if (ids.isEmpty()) throw new AccessDeniedException("当前门诊操作人不存在或已停用");
        return ids.getFirst();
    }
}
