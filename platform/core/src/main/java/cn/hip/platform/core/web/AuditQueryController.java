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

    /** 审计日志查询（sensitive=true 只看敏感操作：退费/授权/角色菜单/用户/目录维护） */
    @GetMapping("/api/audit/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<Map<String, Object>>> auditLogs(@RequestParam(required = false) String username,
                                                  @RequestParam(defaultValue = "false") boolean sensitive) {
        String sensitiveWhere = """
                (path like '%/refund%' or path like '%/roles%' or path like '%/menus%'
                 or path like '%/abx-privileges%' or path like '%/system/users%'
                 or path like '%/insurance/catalog%' or path like '%/cancel%')
                """;
        StringBuilder sql = new StringBuilder("select * from sys_audit_log where 1=1 ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (username != null) {
            sql.append(" and username = ? ");
            args.add(username);
        }
        if (sensitive) sql.append(" and ").append(sensitiveWhere);
        sql.append(" order by id desc limit 200");
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }
}
