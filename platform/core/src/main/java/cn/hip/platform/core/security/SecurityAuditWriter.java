package cn.hip.platform.core.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 越权/未认证访问留痕（等保"失败访问留痕"）。
 *
 * <p>为什么写在安全层而不是审计过滤器：审计过滤器注册序为 30，而 Spring Security
 * 过滤器链是 -100——被链内拦下的 401/403 根本走不到审计过滤器（1.1.2 审阅 A-4）。
 * entryPoint/accessDeniedHandler 是每一次拒绝（过滤器级与方法级）的必经点，在此留痕不漏。
 * 不限方法（GET 也记）：被拒绝的读尝试正是"谁试图翻谁的病历"要回答的问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityAuditWriter {

    private final JdbcTemplate jdbc;

    /** 匿名 401 节流窗口：token 过期后的前端轮询、扫描器探测都会以请求速率写审计表（1.2.0）；测试置 0 */
    @org.springframework.beans.factory.annotation.Value("${hip.audit.anon-throttle-ms:10000}")
    private long anonThrottleMs;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> anonLastWrite =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void recordDenied(HttpServletRequest request, int httpStatus) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String username = auth == null || "anonymousUser".equals(auth.getName()) ? null : auth.getName();
            // 带身份的拒绝（403/过期 token 的 401）全量留痕；匿名 401 按 IP 每 10 秒一条
            if (username == null && httpStatus == 401) {
                String ip = request.getRemoteAddr();
                long now = System.currentTimeMillis();
                Long last = anonLastWrite.get(ip);
                if (anonThrottleMs > 0 && last != null && now - last < anonThrottleMs) {
                    return;
                }
                anonLastWrite.put(ip, now);
                if (anonLastWrite.size() > 10_000) {
                    anonLastWrite.clear();   // 简单防膨胀：清空后最多多记一轮
                }
            }
            jdbc.update("""
                    insert into sys_audit_log(username, method, path, http_status, client_ip)
                    values (?,?,?,?,?)
                    """, username, request.getMethod(), request.getRequestURI(), httpStatus,
                    request.getRemoteAddr());
        } catch (Exception e) {
            // 留痕失败不能反过来影响拒绝响应本身
            log.warn("越权访问留痕失败: {}", e.getMessage());
        }
    }
}
