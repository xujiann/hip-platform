package cn.hip.platform.core.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 审计日志查询——审计属平台横切能力，自 server 下沉 core */
@RestController
@RequiredArgsConstructor
public class AuditQueryController {

    private final JdbcTemplate jdbc;

    /**
     * 审计日志查询（sensitive=true 只看敏感操作：退费/授权/角色菜单/用户/目录维护）。
     * v32：补时间范围（from/to 半开区间）+ 分页（page/size）+ total——原先固定 limit 200，
     * 等保/病历追溯需按时间定位历史操作时，超过最新 200 条即不可检索。
     * 返回 {list, total}（原为裸 list，消费方 e2e-phase1316/2931 与 AuditView 已同步适配）。
     */
    @GetMapping("/api/audit/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> auditLogs(@RequestParam(required = false) String username,
                                            @RequestParam(defaultValue = "false") boolean sensitive,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        String sensitiveWhere = """
                (path like '%/refund%' or path like '%/roles%' or path like '%/menus%'
                 or path like '%/abx-privileges%' or path like '%/system/users%'
                 or path like '%/insurance/catalog%' or path like '%/cancel%')
                """;
        StringBuilder where = new StringBuilder(" where 1=1 ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (username != null && !username.isBlank()) {
            where.append(" and username = ? ");
            args.add(username);
        }
        if (sensitive) where.append(" and ").append(sensitiveWhere);
        // 半开区间（?::date 显式定型，避免 PG 无法推断参数类型；会话时区已钉北京，按营业日切分）
        if (from != null && !from.isBlank()) {
            where.append(" and created_at >= ?::date ");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" and created_at < (?::date + 1) ");
            args.add(to);
        }

        int sz = Math.min(Math.max(size, 1), 200);   // 单页上限仍 200，防一次拖全表
        int offset = (Math.max(page, 1) - 1) * sz;
        Integer total = jdbc.queryForObject(
                "select count(*) from sys_audit_log" + where, Integer.class, args.toArray());

        java.util.List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(sz);
        pageArgs.add(offset);
        var list = jdbc.queryForList(
                "select * from sys_audit_log" + where + " order by id desc limit ? offset ?",
                pageArgs.toArray());

        return R.ok(Map.of("list", list, "total", total == null ? 0 : total));
    }
}
