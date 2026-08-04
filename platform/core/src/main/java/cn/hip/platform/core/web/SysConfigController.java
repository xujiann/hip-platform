package cn.hip.platform.core.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 机构参数：公开配置供登录页/单据读取，修改仅管理员 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class SysConfigController {

    private final JdbcTemplate jdbc;

    /** 公开配置（未登录可读：院名/电话等非敏感信息） */
    @GetMapping("/public")
    public R<Map<String, String>> publicConfig() {
        var m = new LinkedHashMap<String, String>();
        jdbc.queryForList("select cfg_key, cfg_value from sys_config").forEach(row ->
                m.put((String) row.get("cfg_key"), (String) row.get("cfg_value")));
        return R.ok(m);
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> update(@PathVariable String key, @RequestParam String value) {
        int n = jdbc.update("update sys_config set cfg_value = ?, updated_at = now() where cfg_key = ?", value, key);
        return n == 0 ? R.fail(1401, "配置项不存在") : R.ok();
    }
}
