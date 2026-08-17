package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.integration.mllp.MllpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 1.1.6 A 批回归：INTERFACE 全域隔离（A-1）、配置校验对齐真实键集（B-1）、CIDR 取值域（B-5）、legacy_key 上限（B-15） */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase116SecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private String tokenFor(String username, String roleCode) {
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values (?, '$2a$10$abcdefghijklmnopqrstuv', ?, true)
                on conflict (username) do nothing
                """, username, username);
        jdbc.update("""
                insert into sys_user_role(user_id, role_id)
                select u.id, r.id from sys_user u, sys_role r
                where u.username = ? and r.code = ? on conflict do nothing
                """, username, roleCode);
        return "Bearer " + jwtService.issue(username);
    }

    /**
     * A-1 失败路径：INTERFACE 在安全链上被整体隔离——不再依赖每个控制器都记得写注解。
     * 上一版测试只测了两个恰好有注解的端点，无注解的 InpatientController 全线裸奔。
     */
    @Test
    void interfaceRoleIsBlockedFromAllBusinessApis() throws Exception {
        String iface = tokenFor("lis-gw116", "INTERFACE");
        // 曾经全部放行的无注解端点（含 P0 的结算冲销）
        for (String path : new String[]{
                "/api/inpatient/admissions", "/api/inpatient/beds",
                "/api/nursing/risk-assess/1", "/api/stats/overview", "/api/cdss/rules"}) {
            mockMvc.perform(get(path).header("Authorization", iface))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/inpatient/admissions/1/cancel-settlement").header("Authorization", iface))
                .andExpect(status().isForbidden());
        // 唯一放行域：集成
        mockMvc.perform(post("/api/integration/hl7/oru").contentType("text/plain")
                        .content("MSH|^~\\&|LIS|LAB|HIP|HOSP|20260817||ORU^R01|X|P|2.5\r")
                        .header("Authorization", iface))
                .andExpect(status().isOk());
    }

    /** A-1：冲销限收费员/管理员——护士（有 Inpatient 类级权限）也不得冲销结算 */
    @Test
    void cancelSettlementRequiresCashier() throws Exception {
        String nurse = tokenFor("nurse116", "NURSE");
        mockMvc.perform(post("/api/inpatient/admissions/1/cancel-settlement").header("Authorization", nurse))
                .andExpect(status().isForbidden());
        String cashier = tokenFor("cash116", "CASHIER");
        // 收费员可过权限层（业务码 9021 无有效结算单，但不是 403）
        mockMvc.perform(post("/api/inpatient/admissions/999999/cancel-settlement").header("Authorization", cashier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9021));
    }

    /** B-1 失败路径：真实封顶线键 yb_cap_* 的坏值必须被拦——原规则校验的是不存在的幽灵键 */
    @Test
    void configValidationCoversRealKeys() throws Exception {
        String admin = tokenFor("admin116", "ADMIN");
        mockMvc.perform(put("/api/config/yb_cap_staff?value=abc").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(1402));
        mockMvc.perform(put("/api/config/yb_cap_staff?value=-1").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(1402));
        mockMvc.perform(put("/api/config/yb_audit_self_ratio_limit?value=1.5").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(1402));
        mockMvc.perform(put("/api/config/lis_allow_substitute?value=yes").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(1402));
        // 合法值通过（键存在于种子中）
        mockMvc.perform(put("/api/config/lis_allow_substitute?value=0").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** B-5 失败路径：CIDR 前缀越界必须显式拒绝——/33 曾静默放行全网 */
    @Test
    void cidrBitsOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> MllpServer.parseAllowList("192.168.10.0/33"));
        assertThrows(IllegalArgumentException.class, () -> MllpServer.parseAllowList("10.0.0.0/-1"));
        assertEquals(1, MllpServer.parseAllowList("192.168.10.0/24").size());
        assertEquals(2, MllpServer.parseAllowList("192.168.10.5, 10.1.0.0/16").size());
    }

    /** B-15 失败路径：115 字符老单号在校验层就拦住，不再落库炸约束 */
    @Test
    void legacyKeyCapMatchesColumnWidth() throws Exception {
        String admin = tokenFor("admin116b", "ADMIN");
        String longKey = "K".repeat(115);
        mockMvc.perform(post("/api/cdr/legacy-documents").contentType("application/json")
                        .header("Authorization", admin)
                        .content("""
                                {"patientNo":"P00000001","category":"REPORT","legacyKey":"%s",
                                 "title":"t","docDate":"2024-01-01","content":"c"}
                                """.formatted(longKey)))
                .andExpect(jsonPath("$.code").value(4681));
    }
}
