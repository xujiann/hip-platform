package cn.hip.server;

import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysRoleRepository;
import cn.hip.platform.core.repository.SysUserRepository;
import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.core.web.AuthController;
import cn.hip.platform.core.web.SysUserController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v27-A 自助改密 + 首次登录强制改密 + 改密后旧 token 失效（等保必查项）。
 * 改密相关测试不能用 @WithMockUser：它造的 principal 在 DB 无行，
 * findByUsername 直接 orElseThrow（测试方法论——需要真实用户的路径造真实用户）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase129PasswordSelfServiceTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired SysUserRepository userRepository;
    @Autowired SysRoleRepository roleRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AuthController authController;
    @Autowired SysUserController userController;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * passwordUpdatedAt 故意放到 1 小时前：口令戳按秒比对，
     * 若建号与改密发生在同一秒内，戳相同会让"旧 token 失效"断言假红。
     */
    private SysUser newUser(String username, String roleCode, boolean mustChange) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("Abcd1234"));
        u.setRealName("改密测试");
        u.setPasswordUpdatedAt(Instant.now().minusSeconds(3600));
        u.setMustChangePassword(mustChange);
        if (roleCode != null) {
            roleRepository.findByCode(roleCode).ifPresent(u.getRoles()::add);
        }
        return userRepository.save(u);
    }

    private Authentication authOf(String username) {
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    // ---------- 失败路径（方法论：失败路径必测） ----------

    @Test
    void wrongOldPasswordRejectedWith1006() {
        newUser("pwd129a", null, false);
        var r = authController.changePassword(
                new AuthController.ChangePasswordRequest("WrongOld1", "Xyzw5678"), authOf("pwd129a"));
        assertEquals(1006, r.getCode());
        // 口令未被改动：原密码仍可登录
        assertEquals(0, authController.login(new AuthController.LoginRequest("pwd129a", "Abcd1234")).getCode());
    }

    @Test
    void weakNewPasswordRejectedWith1007() {
        newUser("pwd129b", null, false);
        for (String weak : List.of("Ab1", "abcdefgh", "12345678")) {
            var r = authController.changePassword(
                    new AuthController.ChangePasswordRequest("Abcd1234", weak), authOf("pwd129b"));
            assertEquals(1007, r.getCode(), weak + " 应被强度校验拒绝");
        }
    }

    @Test
    void samePasswordRejectedWith1008() {
        newUser("pwd129c", null, false);
        var r = authController.changePassword(
                new AuthController.ChangePasswordRequest("Abcd1234", "Abcd1234"), authOf("pwd129c"));
        assertEquals(1008, r.getCode());
    }

    // ---------- 成功路径：新口令生效、旧口令作废、强制标志清除 ----------

    @Test
    void successfulChangeRotatesCredential() {
        newUser("pwd129d", null, true);
        var r = authController.changePassword(
                new AuthController.ChangePasswordRequest("Abcd1234", "Xyzw5678"), authOf("pwd129d"));
        assertEquals(0, r.getCode());

        SysUser u = userRepository.findByUsername("pwd129d").orElseThrow();
        assertFalse(u.getMustChangePassword(), "改密成功应清除强制改密标志");
        assertEquals(0, authController.login(new AuthController.LoginRequest("pwd129d", "Xyzw5678")).getCode());
        assertEquals(1001, authController.login(new AuthController.LoginRequest("pwd129d", "Abcd1234")).getCode(),
                "旧口令应立即作废");
    }

    @Test
    void loginAndMeExposeMustChangeFlag() {
        newUser("pwd129e", null, true);
        var login = authController.login(new AuthController.LoginRequest("pwd129e", "Abcd1234"));
        assertEquals(0, login.getCode());
        assertEquals(Boolean.TRUE, login.getData().get("mustChangePassword"));
        // 前端刷新后靠 /auth/me 恢复强制改密态
        var me = authController.me(authOf("pwd129e"));
        assertEquals(Boolean.TRUE, me.getData().get("mustChangePassword"));
    }

    // ---------- 改密后旧 token 失效（照 DisabledAccountTokenTest 的 200→401 范式） ----------

    @Test
    void oldTokenInvalidatedAfterPasswordChange() throws Exception {
        SysUser u = newUser("pwd129f", null, false);
        String token = jwtService.issue("pwd129f", u.getPasswordUpdatedAt().getEpochSecond());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertEquals(0, authController.changePassword(
                new AuthController.ChangePasswordRequest("Abcd1234", "Xyzw5678"), authOf("pwd129f")).getCode());

        // 同一未过期 token：口令戳对不上，按未认证处理 → 401
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyTokenWithoutPwdClaimStillAccepted() throws Exception {
        newUser("pwd129g", null, false);
        // 改造前签发的 token 没有 pwd claim：必须放行，否则升级瞬间全院被踢下线
        String legacy = jwtService.issue("pwd129g");
        assertEquals(0, authController.changePassword(
                new AuthController.ChangePasswordRequest("Abcd1234", "Xyzw5678"), authOf("pwd129g")).getCode());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + legacy))
                .andExpect(status().isOk());
    }

    // ---------- 强制改密服务端兜底：前端弹窗拦不住 curl ----------

    @Test
    void mustChangeGateBlocksBusinessApisUntilChanged() throws Exception {
        SysUser u = newUser("pwd129h", "CASHIER", true);
        String token = jwtService.issue("pwd129h", u.getPasswordUpdatedAt().getEpochSecond());

        // 业务接口被 1009 拦下（CASHIER 本有权访问 /api/patients，被拦证明是改密闸而非权限闸）
        mockMvc.perform(get("/api/patients").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1009));

        // 白名单放行：看自己是谁 + 改密本身
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"Abcd1234\",\"newPassword\":\"Xyzw5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 改密后旧 token 已失效，换新戳 token：业务接口恢复正常
        SysUser after = userRepository.findByUsername("pwd129h").orElseThrow();
        String fresh = jwtService.issue("pwd129h", after.getPasswordUpdatedAt().getEpochSecond());
        mockMvc.perform(get("/api/patients").header("Authorization", "Bearer " + fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------- 管理员设初始口令 → 置强制改密标志 ----------

    @Test
    void adminSetPasswordMarksMustChange() {
        // SysUserController 带 @PreAuthorize(ADMIN)，bean 直调需要安全上下文（照 SecurityHardeningTest）
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        var created = userController.create(new SysUserController.SaveUserRequest(
                "pwd129i", "Abcd1234", "初始口令", null, null, null, List.of()));
        assertEquals(0, created.getCode());
        assertTrue(userRepository.findByUsername("pwd129i").orElseThrow().getMustChangePassword(),
                "新建用户的初始口令须强制修改");

        // 本人改密后标志清除
        assertEquals(0, authController.changePassword(
                new AuthController.ChangePasswordRequest("Abcd1234", "Xyzw5678"), authOf("pwd129i")).getCode());
        assertFalse(userRepository.findByUsername("pwd129i").orElseThrow().getMustChangePassword());

        Long id = created.getData().id();
        // 管理员编辑但不重置口令：不应误伤标志
        assertEquals(0, userController.update(id, new SysUserController.SaveUserRequest(
                "pwd129i", null, "初始口令", null, null, null, List.of())).getCode());
        assertFalse(userRepository.findByUsername("pwd129i").orElseThrow().getMustChangePassword());

        // 管理员重置口令：重新进入强制改密态
        assertEquals(0, userController.update(id, new SysUserController.SaveUserRequest(
                "pwd129i", "Efgh5678", "初始口令", null, null, null, List.of())).getCode());
        assertTrue(userRepository.findByUsername("pwd129i").orElseThrow().getMustChangePassword(),
                "管理员重置口令后须再次强制改密");
    }
}
