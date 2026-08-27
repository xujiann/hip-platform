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

    /**
     * insurance 只读豁免（v27-B）：1.2.0 裁决「冲销侧不设开关」，但 /api/insurance 整段 404
     * 连分割/审核/对账**查询**也关了——出现「能冲销、却查不到冲销依据」的自相矛盾。
     * 停用后：查询豁免放行，其余（如目录对照维护）仍 404。
     */
    @Test
    void insuranceReadOnlyExemptionsSurviveDisable() throws Exception {
        setModule("insurance", "0");
        assertFalse(moduleGate.isApiDisabled("/api/insurance/splits"), "分割查询是冲销依据，必须豁免");
        assertFalse(moduleGate.isApiDisabled("/api/insurance/audits"));
        assertFalse(moduleGate.isApiDisabled("/api/insurance/reconcile"));
        assertFalse(moduleGate.isApiDisabled("/api/insurance/reconcile/batches"));
        assertTrue(moduleGate.isApiDisabled("/api/insurance/catalog"), "非豁免入口（目录对照维护）仍须被拦");
        assertTrue(moduleGate.isApiDisabled("/api/insurance"));

        mockMvc.perform(get("/api/insurance/splits").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        setModule("insurance", "1");
    }

    /** v27-B 扩表：cdr / datagov 正向拦截 + 兄弟前缀反向断言（防 1.1.1 式边界误伤） */
    @Test
    void cdrAndDatagovTogglesWork() throws Exception {
        setModule("cdr", "0");
        assertTrue(moduleGate.isApiDisabled("/api/cdr/patients/1/documents"));
        assertFalse(moduleGate.isApiDisabled("/api/cdss/suggestions"), "/api/cdr 不得连带命中 /api/cdss");
        mockMvc.perform(get("/api/cdr/search?keyword=x").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + adminToken()))
                .andExpect(jsonPath("$.data.menus[?(@.path == '/cdr/patient360')]").doesNotExist());
        setModule("cdr", "1");

        setModule("datagov", "0");
        assertTrue(moduleGate.isApiDisabled("/api/datagov/standards"));
        assertFalse(moduleGate.isApiDisabled("/api/patients"), "数据治理开关不得波及患者主索引");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + adminToken()))
                .andExpect(jsonPath("$.data.menus[?(@.path == '/datagov')]").doesNotExist())
                .andExpect(jsonPath("$.data.menus[?(@.path == '/datagov/standards')]").doesNotExist());
        setModule("datagov", "1");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + adminToken()))
                .andExpect(jsonPath("$.data.menus[?(@.path == '/datagov')]").exists());
    }
}
