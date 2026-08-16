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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/portal/login", "/api/config/public",
                                "/api/share/*", "/actuator/health").permitAll()
                        // 患者端仅能访问 portal 接口；院内接口对 PORTAL 角色完全隔离
                        .requestMatchers("/api/portal/**").hasRole("PORTAL")
                        .anyRequest().access(new WebExpressionAuthorizationManager(
                                "isAuthenticated() and !hasRole('PORTAL')")))
                // 必须区分「未认证」与「已认证但无权」：
                // 只配 entryPoint 时，方法级 @PreAuthorize 拒绝也会落到它上面变成 401，
                // 前端据此判定"登录过期"→ 清 token 跳登录页 → 有权使用系统的角色被直接踢出去。
                // 必须区分「未认证」与「已认证但无权」：只配 entryPoint 时，方法级 @PreAuthorize
                // 拒绝也会变成 401，前端据此判定"登录过期"→清 token 跳登录页→有权用系统的角色被踢出去。
                // 另：不能用 sendError——它会转发到 /error，而 /error 同样匹配 anyRequest 被安全链
                // 二次拦截，结果又变回 401。直接写响应体即可。
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, e) ->
                                writeJson(res, HttpStatus.UNAUTHORIZED.value(), 1000, "未登录或登录已过期"))
                        .accessDeniedHandler((req, res, e) ->
                                writeJson(res, HttpStatus.FORBIDDEN.value(), 1005, "无该功能权限")))
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
