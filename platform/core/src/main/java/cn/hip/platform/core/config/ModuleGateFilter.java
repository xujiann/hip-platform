package cn.hip.platform.core.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 模块开关 API 拦截：被停用模块的接口统一 404（只藏菜单不拦接口是伪关闭） */
@Component
@RequiredArgsConstructor
public class ModuleGateFilter extends OncePerRequestFilter {

    private final ModuleGate moduleGate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/") && moduleGate.isApiDisabled(uri)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":404,\"message\":\"该功能模块未启用\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
