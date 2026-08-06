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

    /** 当前被停用的模块 key 集合（配置缺省视为启用） */
    public Set<String> disabledModules() {
        var disabled = new HashSet<String>();
        for (var row : jdbc.queryForList(
                "select cfg_key, cfg_value from sys_config where cfg_key like 'module.%.enabled'")) {
            String key = (String) row.get("cfg_key");
            if (!"1".equals(row.get("cfg_value"))) {
                disabled.add(key.substring("module.".length(), key.length() - ".enabled".length()));
            }
        }
        disabled.retainAll(MODULES.keySet());
        return disabled;
    }

    public Set<String> disabledMenuPaths() {
        var paths = new HashSet<String>();
        for (String key : disabledModules()) {
            paths.add(MODULES.get(key).menuPath());
        }
        return paths;
    }

    /** 请求 URI 是否命中被停用模块的 API 前缀 */
    public boolean isApiDisabled(String uri) {
        for (String key : disabledModules()) {
            for (String prefix : MODULES.get(key).apiPrefixes()) {
                if (uri.startsWith(prefix)) {
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
