package cn.hip.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** 等保审计：记录全部写操作与登录行为（谁/何时/何接口/结果） */
@org.springframework.core.annotation.Order(30)   // 审计最外层：请求进出都要留痕
@Component
@RequiredArgsConstructor
public class AuditLogFilter extends OncePerRequestFilter {

    private static final Set<String> AUDITED_METHODS = Set.of("POST", "PUT", "DELETE");

    private final JdbcTemplate jdbc;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI();
            if (AUDITED_METHODS.contains(request.getMethod()) && path.startsWith("/api/")) {
                try {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    String username = auth == null || "anonymousUser".equals(auth.getName())
                            ? null : auth.getName();
                    jdbc.update("""
                            insert into sys_audit_log(username, method, path, http_status, client_ip)
                            values (?,?,?,?,?)
                            """, username, request.getMethod(), path, response.getStatus(),
                            request.getRemoteAddr());
                } catch (Exception ignored) {
                    // 审计失败不影响业务
                }
            }
        }
    }
}
