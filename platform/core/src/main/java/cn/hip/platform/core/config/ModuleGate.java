package cn.hip.platform.core.config;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产品化一期：模块级功能开关。
 * 医院未采购/不用的模块经 sys_config `module.<key>.enabled`（'1'=启用）整体关闭，
 * 双路生效：菜单树过滤（AuthController.me）+ API 前缀拦截（ModuleGateFilter 返回 404）。
 * 注册表在此集中维护——key → 菜单 path + API 前缀。
 */
@Component
@RequiredArgsConstructor
public class ModuleGate {

    public record ModuleDef(String menuPath, List<String> apiPrefixes) {}

    /** 可开关模块注册表（均为独立菜单 + 独立 API 前缀的功能域） */
    public static final Map<String, ModuleDef> MODULES = Map.of(
            "drg",       new ModuleDef("/drg",              List.of("/api/drg")),
            "cdss",      new ModuleDef("/cdss",             List.of("/api/cdss")),
            "insurance", new ModuleDef("/insurance",        List.of("/api/insurance")),
            "blood",     new ModuleDef("/inpatient/blood",  List.of("/api/inpatient/blood")),
            "hr",        new ModuleDef("/hr",               List.of("/api/hr")),
            "surgery",   new ModuleDef("/surgery",          List.of("/api/inpatient/surgeries", "/api/anes")));

    private final JdbcTemplate jdbc;

    /** 30 秒本地缓存：过滤器每个请求都会问一次，无缓存等于给每次调用加一次 DB 往返 */
    private volatile Set<String> cachedDisabled = Set.of();
    private volatile long cachedAt = 0L;
    private static final long CACHE_TTL_MS = 30_000;

    /**
     * 当前被停用的模块 key 集合（配置缺省视为启用）。
     *
     * <p>查询失败时返回**上一次的结果**而非抛异常：该方法由过滤器在 DispatcherServlet 之外调用，
     * 抛出的异常会直落 Tomcat 错误页——客户端拿到 HTML 而不是 {code,message}，
     * 连专门用来报告 dbUp=false 的运维健康页自己都打不开。
     */
    public Set<String> disabledModules() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_TTL_MS) {
            return cachedDisabled;
        }
        try {
            var disabled = new HashSet<String>();
            for (var row : jdbc.queryForList(
                    "select cfg_key, cfg_value from sys_config where cfg_key like 'module.%.enabled'")) {
                String key = (String) row.get("cfg_key");
                if (!"1".equals(row.get("cfg_value"))) {
                    disabled.add(key.substring("module.".length(), key.length() - ".enabled".length()));
                }
            }
            disabled.retainAll(MODULES.keySet());
            cachedDisabled = Set.copyOf(disabled);
            cachedAt = now;
        } catch (Exception e) {
            // 保持上次结果（初始为空=全部启用），让请求继续走到能正常报错的业务层
            cachedAt = now;
        }
        return cachedDisabled;
    }

    /** 配置变更后立即生效（模块开关改动走 sys_config 更新，调用方可主动失效缓存） */
    public void evictCache() {
        cachedAt = 0L;
    }

    public Set<String> disabledMenuPaths() {
        var paths = new HashSet<String>();
        for (String key : disabledModules()) {
            paths.add(MODULES.get(key).menuPath());
        }
        return paths;
    }

    /**
     * 请求 URI 是否命中被停用模块的 API 前缀。
     *
     * <p>前缀匹配必须带路径段边界：裸 startsWith 会让 `/api/hr`（人事）连带命中
     * `/api/hrp/**`（资产/物资/供应商/资质），关闭人事即整片 HRP 404。
     */
    public boolean isApiDisabled(String uri) {
        for (String key : disabledModules()) {
            for (String prefix : MODULES.get(key).apiPrefixes()) {
                if (uri.equals(prefix) || uri.startsWith(prefix + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isEnabled(String key) {
        return !disabledModules().contains(key);
    }
}
