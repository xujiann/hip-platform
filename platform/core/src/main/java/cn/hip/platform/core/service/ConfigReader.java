package cn.hip.platform.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** sys_config 轻量读取（键不存在回落默认值）；单据前缀等低频配置直查不缓存 */
@Service
@RequiredArgsConstructor
public class ConfigReader {

    private final JdbcTemplate jdbc;

    public String get(String key, String defaultValue) {
        var rows = jdbc.queryForList("select cfg_value from sys_config where cfg_key = ?", String.class, key);
        return rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank() ? defaultValue : rows.get(0);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
