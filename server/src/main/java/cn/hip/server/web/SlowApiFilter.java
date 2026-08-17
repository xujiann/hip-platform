package cn.hip.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 二十二期：慢接口观测——超过阈值的 API 调用落台账，供运维中心分析 */
@org.springframework.core.annotation.Order(40)   // 慢接口观测在审计之内
@Component
@RequiredArgsConstructor
public class SlowApiFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbc;

    @Value("${hip.ops.slow-ms:500}")
    private long slowMs;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            String path = request.getRequestURI();
            if (cost >= slowMs && path.startsWith("/api/")) {
                try {
                    // 参数归一化（1.1.4 B-14）：/api/patients/1、/2… 各成一行会让
                    // 运维中心的 group by path 毫无聚合价值
                    jdbc.update("insert into ops_slow_api(method, path, cost_ms) values (?,?,?)",
                            request.getMethod(), path.replaceAll("/\\d+", "/{id}"), (int) cost);
                } catch (Exception ignored) {
                    // 观测失败不影响业务
                }
            }
        }
    }
}
