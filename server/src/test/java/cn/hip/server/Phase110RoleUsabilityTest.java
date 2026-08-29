package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色可用性回归：**权限收紧不能把有权使用系统的角色锁在门外**。
 *
 * <p>1.0.9 的权限批就栽在这：驾驶舱（所有角色的登录首页）被限成三个角色，
 * 而安全层又把「已认证但无权」当成 401 返回，前端据此判定"登录过期"清 token 跳登录页——
 * 护士登录后立刻被踢出，完全无法使用系统。20 套 E2E 全绿，因为它们都用 admin。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase110RoleUsabilityTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private String tokenFor(String username, String roleCode) {
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values (?, '$2a$10$abcdefghijklmnopqrstuv', ?, true)
                on conflict (username) do nothing
                """, username, username);
        Long uid = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
        Long rid = jdbc.queryForObject("select id from sys_role where code = ?", Long.class, roleCode);
        jdbc.update("insert into sys_user_role(user_id, role_id) values (?,?) on conflict do nothing", uid, rid);
        return "Bearer " + jwtService.issue(username);
    }

    /** 每个内置角色登录后都必须能打开首页（驾驶舱 + 自己的菜单） */
    @Test
    void everyRoleCanOpenDashboardAndOwnMenus() throws Exception {
        String[] roles = {"DOCTOR_OUTP", "CASHIER", "PHARMACIST", "NURSE", "TECHNICIAN", "QUALITY", "OPERATION"};
        for (String role : roles) {
            String token = tokenFor("usab_" + role.toLowerCase(), role);
            mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
            mockMvc.perform(get("/api/stats/overview").header("Authorization", token))
                    .andExpect(status().isOk());
        }
    }

    /** 「已认证但无权」必须是 403 且带 R 信封——返回 401 会被前端当成登录过期 */
    @Test
    void forbiddenIsThreeOhThreeNotUnauthorized() throws Exception {
        String nurse = tokenFor("usab_forbidden", "NURSE");
        mockMvc.perform(get("/api/insurance/summary").header("Authorization", nurse))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1005));
    }

    /** 未认证仍是 401 */
    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/stats/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1000));
    }

    /**
     * BUTTON 权限点下发（v31 V111）：前端 hasPerm 依赖 /auth/me 下发的 BUTTON 菜单项。
     * 收费员必须拿到收费/退费按钮权限、药师必须拿到发药/退药权限、且各自不越界。
     * 授权口径须与页面菜单一致——能进收费页(ADMIN,CASHIER)才有收费按钮权限。
     */
    @Test
    void buttonPermsAreDeliveredPerRole() throws Exception {
        String cashier = tokenFor("usab_btn_cashier", "CASHIER");
        mockMvc.perform(get("/api/auth/me").header("Authorization", cashier))
                .andExpect(jsonPath("$.data.menus[?(@.perm == 'outp:charge:refund')]").exists())
                .andExpect(jsonPath("$.data.menus[?(@.perm == 'outp:charge:settle')]").exists())
                // 收费员不该有发药按钮权限（那是药师的）
                .andExpect(jsonPath("$.data.menus[?(@.perm == 'outp:pharmacy:dispense')]").doesNotExist());

        String pharm = tokenFor("usab_btn_pharm", "PHARMACIST");
        mockMvc.perform(get("/api/auth/me").header("Authorization", pharm))
                .andExpect(jsonPath("$.data.menus[?(@.perm == 'outp:pharmacy:dispense')]").exists())
                .andExpect(jsonPath("$.data.menus[?(@.perm == 'outp:pharmacy:return')]").exists())
                .andExpect(jsonPath("$.data.menus[?(@.perm == 'outp:charge:refund')]").doesNotExist());
    }
}
