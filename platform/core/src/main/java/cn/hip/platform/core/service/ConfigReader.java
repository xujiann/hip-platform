package cn.hip.platform.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sys_config 轻量读取（键不存在回落默认值）。
 *
 * <p>30 秒缓存（1.1.4 P2）：医保比例/费率在**每次结算**都会读，原先每读一次一趟 DB；
 * 与 ModuleGate 同 TTL。配置更新走 SysConfigController，双写路径少，30 秒延迟可接受。
 */
@Service
@RequiredArgsConstructor
public class ConfigReader {

    private static final long CACHE_TTL_MS = 30_000;

    private record Cached(String value, long at) {}

    private final JdbcTemplate jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public String get(String key, String defaultValue) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && now - c.at() < CACHE_TTL_MS) {
            return c.value() == null ? defaultValue : c.value();
        }
        String value;
        try {
            var rows = jdbc.queryForList("select cfg_value from sys_config where cfg_key = ?", String.class, key);
            value = rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank() ? null : rows.get(0);
        } catch (Exception e) {
            // DB 抖动沿用上次值（若有），与 ModuleGate 同语义
            return c != null && c.value() != null ? c.value() : defaultValue;
        }
        cache.put(key, new Cached(value, now));
        return value == null ? defaultValue : value;
    }

    /** 配置更新后立即失效（SysConfigController 调用；与 ModuleGate.evictCache 同理，不能等 TTL） */
    public void evict(String key) {
        cache.remove(key);
    }

    public void evictAll() {
        cache.clear();
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
