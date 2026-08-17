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

/**
 * 等保审计：记录全部写操作与登录行为（谁/何时/何接口/结果）。
 *
 * <p>401/403 不在这里记：本过滤器注册序 30，在 Spring Security 链（-100）**之后**，
 * 链内拦下的请求根本到不了这里；方法级拒绝虽会路过本过滤器的 finally，但那一刻
 * accessDeniedHandler 尚未写 403，按 response.getStatus() 记会得到一行误导性的 200。
 * 故安全拒绝统一由 SecurityAuditWriter 在 handler 内留痕，本过滤器检测到在途的
 * 安全异常即跳过（否则同一次拒绝记两行）。
 */
@org.springframework.core.annotation.Order(30)   // 审计最外层：请求进出都要留痕
@Component
@RequiredArgsConstructor
public class AuditLogFilter extends OncePerRequestFilter {

    private static final Set<String> AUDITED_METHODS = Set.of("POST", "PUT", "DELETE");

    private final JdbcTemplate jdbc;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean securityDenied = false;
        try {
            chain.doFilter(request, response);
        } catch (org.springframework.security.access.AccessDeniedException
                 | org.springframework.security.core.AuthenticationException e) {
            securityDenied = true;   // 由 SecurityAuditWriter 留痕（带准确的 401/403 状态）
            throw e;
        } finally {
            String path = request.getRequestURI();
            if (!securityDenied && AUDITED_METHODS.contains(request.getMethod()) && path.startsWith("/api/")) {
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
