package cn.hip.server;

import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import cn.hip.platform.core.web.AuthController;
import cn.hip.platform.core.web.SysUserController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 十三期等保基线：密码策略与登录防爆破 */
@SpringBootTest
@Transactional
class SecurityHardeningTest {

    @Autowired AuthController authController;
    @Autowired SysUserController userController;
    @Autowired SysUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void actAsAdmin() {
        // SysUserController 带 @PreAuthorize(ADMIN)，bean 直调需要安全上下文
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void weakPasswordsAreRejected() {
        for (String weak : List.of("1234567", "abcdefgh", "12345678")) {
            var r = userController.create(new SysUserController.SaveUserRequest(
                    "weak" + weak.hashCode(), weak, "弱密码", null, null, null, List.of()));
            assertEquals(1102, r.getCode(), weak + " 应被拒绝");
        }
        var ok = userController.create(new SysUserController.SaveUserRequest(
                "strongpwd1", "Abcd1234", "强密码", null, null, null, List.of()));
        assertEquals(0, ok.getCode());
    }

    @Test
    void fiveFailedLoginsLockAccountForFifteenMinutes() {
        SysUser u = new SysUser();
        u.setUsername("locker1");
        u.setPassword(passwordEncoder.encode("Abcd1234"));
        u.setRealName("锁定测试");
        userRepository.save(u);

        for (int i = 0; i < 5; i++) {
            assertEquals(1001, authController.login(
                    new AuthController.LoginRequest("locker1", "wrong")).getCode());
        }
        // 第 6 次即使密码正确也应被锁定
        assertEquals(1002, authController.login(
                new AuthController.LoginRequest("locker1", "Abcd1234")).getCode());
        assertNotNull(userRepository.findByUsername("locker1").orElseThrow().getLockedUntil());
    }

    @Test
    void successfulLoginResetsFailureCounter() {
        SysUser u = new SysUser();
        u.setUsername("locker2");
        u.setPassword(passwordEncoder.encode("Abcd1234"));
        u.setRealName("计数测试");
        userRepository.save(u);

        authController.login(new AuthController.LoginRequest("locker2", "wrong"));
        authController.login(new AuthController.LoginRequest("locker2", "wrong"));
        assertEquals(0, authController.login(
                new AuthController.LoginRequest("locker2", "Abcd1234")).getCode());
        assertEquals(0, userRepository.findByUsername("locker2").orElseThrow().getFailedAttempts());
    }
}
