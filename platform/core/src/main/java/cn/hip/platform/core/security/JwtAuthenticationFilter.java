package cn.hip.platform.core.security;

import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 从 Authorization: Bearer <token> 解析登录态 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final DbUserDetailsService userDetailsService;
    private final SysUserRepository userRepository;

    /** 强制改密期间仍放行的接口：登录、看自己是谁、改密本身、登录页要用的公共配置 */
    private static final Set<String> MUST_CHANGE_ALLOWED = Set.of(
            "/api/auth/login", "/api/auth/me", "/api/auth/change-password", "/api/config/public");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            SysUser user = null;
            try {
                Claims claims = jwtService.verifyClaims(header.substring(7));
                String subject = claims.getSubject();
                UsernamePasswordAuthenticationToken auth;
                if (subject.startsWith("portal:")) {
                    // 患者端令牌：主体为 portal:{patientId}，不关联员工账号
                    auth = new UsernamePasswordAuthenticationToken(subject, null,
                            List.of(new SimpleGrantedAuthority("ROLE_PORTAL")));
                } else {
                    user = userRepository.findByUsername(subject)
                            .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + subject));
                    if (!user.getEnabled()) {
                        // 账号停用后，已签发的未过期令牌一并失效
                        throw new DisabledException("账号已停用: " + subject);
                    }
                    // v27-A：改密后旧 token 失效。签发时写入的口令戳（pwd claim）与库中
                    // passwordUpdatedAt 不一致即视同无效 token（走既有的未认证 401 路径）。
                    // claim 缺失则放行——兼容改造前签发的旧 token 与 issue(String) 的全部测试调用。
                    Object pwdStamp = claims.get("pwd");
                    if (pwdStamp instanceof Number n
                            && n.longValue() != user.getPasswordUpdatedAt().getEpochSecond()) {
                        throw new BadCredentialsException("口令已修改，原令牌失效: " + subject);
                    }
                    UserDetails userDetails = userDetailsService.buildUserDetails(user);
                    auth = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                }
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // token 无效则按匿名处理，由授权规则拦截
            }
            // v27-A 服务端兜底：强制改密未完成的用户只许走白名单接口。
            // 只靠前端弹窗拦不住 curl/脚本——初始口令持有者可能不是本人。
            // 过滤器层不能用 R.fail/sendError（/error 会被安全链二次拦截变 401，
            // 见 SecurityConfig 注释），照 ModuleGateFilter 手写 R 信封。
            // 必须确认登录态已建立：token 校验失败（如管理员重置口令后的旧 token）
            // 要走 401 让前端跳登录页，而不是 1009——两个分支的用户动作完全不同。
            if (SecurityContextHolder.getContext().getAuthentication() != null
                    && user != null && Boolean.TRUE.equals(user.getMustChangePassword())
                    && request.getRequestURI().startsWith("/api/")
                    && !MUST_CHANGE_ALLOWED.contains(request.getRequestURI())) {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":1009,\"message\":\"请先修改初始密码\",\"data\":null}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
