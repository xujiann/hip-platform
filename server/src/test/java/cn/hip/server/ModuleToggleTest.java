package cn.hip.server;

import cn.hip.platform.core.config.ModuleGate;
import cn.hip.platform.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 产品化一期：模块级功能开关——菜单过滤 + API 404 双路生效 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ModuleToggleTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleGate moduleGate;

    private String adminToken() {
        return jwtService.issue("admin");
    }

    private void setModule(String key, String value) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, "module." + key + ".enabled");
        moduleGate.evictCache();   // 1.1.1 起 ModuleGate 有 30s 缓存，直改库须显式失效
    }

    /** 默认全部启用：注册表 6 模块均可访问 */
    @Test
    void allModulesEnabledByDefault() throws Exception {
        assertTrue(moduleGate.disabledModules().isEmpty());
        mockMvc.perform(get("/api/drg/groups").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    /** 停用后：API 前缀 404，菜单不下发；恢复后即回归 */
    @Test
    void disabledModuleBlocksApiAndHidesMenu() throws Exception {
        setModule("drg", "0");
        assertTrue(moduleGate.disabledModules().contains("drg"));
        assertTrue(moduleGate.isApiDisabled("/api/drg/analysis"));

        mockMvc.perform(get("/api/drg/groups").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menus[?(@.path == '/drg')]").doesNotExist());

        setModule("drg", "1");
        mockMvc.perform(get("/api/drg/groups").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + adminToken()))
                .andExpect(jsonPath("$.data.menus[?(@.path == '/drg')]").exists());
    }

    /** 手麻模块双前缀（/api/inpatient/surgeries + /api/anes）一起拦截 */
    @Test
    void surgeryModuleCoversBothPrefixes() {
        setModule("surgery", "0");
        assertTrue(moduleGate.isApiDisabled("/api/inpatient/surgeries"));
        assertTrue(moduleGate.isApiDisabled("/api/anes/records"));
        assertFalse(moduleGate.isApiDisabled("/api/inpatient/admissions"));
        setModule("surgery", "1");
    }
}
