package cn.hip.platform.core.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityAuditWriter securityAuditWriter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/portal/login", "/api/config/public",
                                "/api/share/*", "/actuator/health",
                                // build-info 版本自证：升级验收 curl 比对构建时间，不应要求登录（1.2.4 彩排）
                                "/actuator/info").permitAll()
                        // 患者端仅能访问 portal 接口；院内接口对 PORTAL 角色完全隔离
                        .requestMatchers("/api/portal/**").hasRole("PORTAL")
                        // 接口机（LIS 中间件）只许进集成域：该角色的假想敌就是凭据泄漏，
                        // 若不在链上隔离，任何无注解业务端点（入院/出院/冲销）都对它开放——
                        // 1.1.6 审阅 A-1：两轮修复（冲销端点、INTERFACE 角色）曾在此盲区互相放大
                        .requestMatchers("/api/integration/**").hasAnyRole("ADMIN", "INTERFACE")
                        .anyRequest().access(new WebExpressionAuthorizationManager(
                                "isAuthenticated() and !hasRole('PORTAL') and !hasRole('INTERFACE')")))
                // 必须区分「未认证」与「已认证但无权」：只配 entryPoint 时，方法级 @PreAuthorize
                // 拒绝也会变成 401，前端据此判定"登录过期"→清 token 跳登录页→有权用系统的角色被踢出去。
                // 另：不能用 sendError——它会转发到 /error，而 /error 同样匹配 anyRequest 被安全链
                // 二次拦截，结果又变回 401。直接写响应体即可。
                // 401/403 留痕在此（而非审计过滤器）：安全链在审计过滤器之前，被拦下的请求到不了它。
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, e) -> {
                            securityAuditWriter.recordDenied(req, HttpStatus.UNAUTHORIZED.value());
                            writeJson(res, HttpStatus.UNAUTHORIZED.value(), 1000, "未登录或登录已过期");
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            securityAuditWriter.recordDenied(req, HttpStatus.FORBIDDEN.value());
                            writeJson(res, HttpStatus.FORBIDDEN.value(), 1005, "无该功能权限");
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 统一以 R 信封回写安全异常，前端拿到的是与业务接口一致的 {code,message} */
    private static void writeJson(jakarta.servlet.http.HttpServletResponse res, int status,
                                  int code, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
