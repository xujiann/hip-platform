package cn.hip.server;

import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.core.web.AuthController;
import cn.hip.platform.core.web.SysUserController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第六轮审阅 域① 发现的回归测试（F1 机器账号砖化 / F2 改密无节流）。
 * 原为"证明缺陷存在"的只读验证，修复后改写为锁定修复行为——防回退。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReviewR6PasswordAuditTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired SysUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AuthController authController;
    @Autowired SysUserController userController;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private Authentication authOf(String u) {
        return new UsernamePasswordAuthenticationToken(u, null, List.of());
    }

    private void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // ============================================================
    // 发现 F1：管理员经用户管理建立的 INTERFACE 机器账号被 1009 闸永久砖化
    // SysUserController.create 无条件置 mustChangePassword=true；机器账号无人
    // 去走 /auth/change-password，其 /api/integration/** 调用全被 1009 拦。
    // ============================================================
    @Test
    void interfaceMachineAccountBrickedByMustChangeGate() throws Exception {
        asAdmin();
        // 生产里 INTERFACE 账号只能经此唯一建号入口创建
        var created = userController.create(new SysUserController.SaveUserRequest(
                "lis-gw-r6", "Abcd1234", "LIS网关", null, null, null, List.of("INTERFACE")));
        assertEquals(0, created.getCode(), "建号应成功");

        SysUser u = userRepository.findByUsername("lis-gw-r6").orElseThrow();
        // 修复后防线 (a)：纯 INTERFACE 机器账号不置位——机器没有"人"去改密
        assertFalse(u.getMustChangePassword(),
                "纯 INTERFACE 机器账号不得被置强制改密标志（F1 修复）");
        // 防线 (b) 单独验证：即使被误置位（如直改库），/api/integration/** 也豁免 1009 闸
        u.setMustChangePassword(true);
        userRepository.save(u);

        // 关键：清掉 asAdmin() 遗留在 ThreadLocal 的登录态，否则 MockMvc 复用同线程时
        // JwtAuthenticationFilter 的外层门（getAuthentication()==null）不成立，直接跳过
        // token 解析与 1009 闸——生产是无状态的，必须还原成"进来时无登录态"
        SecurityContextHolder.clearContext();

        // 机器账号带戳 token（等同它登录后拿到的 token）
        String token = jwtService.issue("lis-gw-r6", u.getPasswordUpdatedAt().getEpochSecond());

        // 修复后：HL7 进站端点绝不能被 1009 拦——机器域投递失败是静默的（HTTP 200 +
        // 陌生业务码不触发中间件重连告警），检验结果会无声丢失
        mockMvc.perform(post("/api/integration/hl7/oru").contentType("text/plain")
                        .content("MSH|^~\\&|LIS|LAB|HIP|HOSP|20260827||ORU^R01|X|P|2.5\r")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(1009)));
    }

    // ============================================================
    // 发现 F2：change-password 端点无锁定/无频率限制——被盗有效 token 可无限爆破旧口令
    // login 有 bumpFailedAttempts+锁定；change-password 的旧密校验(1006)零计数零锁定。
    // 且账号即便处于登录锁定态(lockedUntil 未来)，持 token 仍可改密。
    // ============================================================
    @Test
    void changePasswordHasNoBruteForceProtection() {
        SysUser u = new SysUser();
        u.setUsername("victim-r6");
        u.setPassword(passwordEncoder.encode("Abcd1234"));
        u.setRealName("受害者");
        u.setPasswordUpdatedAt(Instant.now().minusSeconds(3600));
        // 账号已因登录爆破被锁定 15 分钟
        u.setFailedAttempts(5);
        u.setLockedUntil(Instant.now().plusSeconds(900));
        userRepository.save(u);

        // 修复后：登录锁定态（lockedUntil 在未来）下改密一律 1002，与 /login 口径统一——
        // 正确旧密也不例外，锁定就是锁定
        var locked = authController.changePassword(
                new AuthController.ChangePasswordRequest("Abcd1234", "Xyzw5678"), authOf("victim-r6"));
        assertEquals(1002, locked.getCode(), "锁定期间持 token 也不得改密（F2 修复）");

        // 解锁后：旧密爆破计入与登录同一套失败计数，5 次触发锁定
        SysUser u2 = userRepository.findByUsername("victim-r6").orElseThrow();
        u2.setFailedAttempts(0);
        u2.setLockedUntil(null);
        userRepository.save(u2);
        for (int i = 0; i < 5; i++) {
            var r = authController.changePassword(
                    new AuthController.ChangePasswordRequest("guess" + i + "abc", "Xyzw5678"),
                    authOf("victim-r6"));
            assertEquals(1006, r.getCode());
        }
        var afterBurst = authController.changePassword(
                new AuthController.ChangePasswordRequest("Abcd1234", "Xyzw5678"), authOf("victim-r6"));
        assertEquals(1002, afterBurst.getCode(),
                "5 次旧密爆破后必须触发锁定——正确旧密也进不来（无节流预言机已封）");
    }
}
