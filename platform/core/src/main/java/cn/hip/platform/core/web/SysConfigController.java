package cn.hip.platform.core.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 机构参数：公开配置供登录页/单据读取，修改仅管理员 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class SysConfigController {

    private final JdbcTemplate jdbc;

    /** 公开键白名单：仅机构展示信息；医保比例(yb_*)、模块开关等业务参数不得经此外泄 */
    private static final List<String> PUBLIC_KEYS =
            List.of("hospital_name", "hospital_short", "receipt_title", "contact_phone");

    /** 公开配置（未登录可读：院名/电话等非敏感信息） */
    @GetMapping("/public")
    public R<Map<String, String>> publicConfig() {
        var m = new LinkedHashMap<String, String>();
        String placeholders = String.join(",", Collections.nCopies(PUBLIC_KEYS.size(), "?"));
        jdbc.queryForList("select cfg_key, cfg_value from sys_config where cfg_key in (" + placeholders + ")",
                PUBLIC_KEYS.toArray()).forEach(row ->
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
