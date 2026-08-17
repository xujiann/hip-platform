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
import java.util.List;
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

    /**
     * 敏感读留痕（1.1.4 B-15）：患者列表/病历调阅/CDR 检索/打印/工资全是 GET，
     * 只审计写操作时"谁查过谁的病历"永远查不出来。按前缀圈定，避免审计表被普通读刷爆。
     */
    private static final List<String> AUDITED_READ_PREFIXES = List.of(
            "/api/patients", "/api/cdr", "/api/emr", "/api/print", "/api/reports",
            "/api/hr/salaries", "/api/datagov/reports");

    private final JdbcTemplate jdbc;

    private static boolean auditedRead(String method, String path) {
        if (!"GET".equals(method)) {
            return false;
        }
        for (String p : AUDITED_READ_PREFIXES) {
            if (path.equals(p) || path.startsWith(p + "/") || path.startsWith(p + "?") || path.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

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
            boolean audited = AUDITED_METHODS.contains(request.getMethod())
                    || auditedRead(request.getMethod(), path);
            if (!securityDenied && audited && path.startsWith("/api/")) {
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
