package cn.hip.server;

import cn.hip.platform.core.config.HipProfiles;
import cn.hip.platform.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.9 A-1 回归：两道安全闸门必须在**生产 profile** 下真的挡住。
 *
 * <p>上一轮这两道闸门写完就标了"已修"，但部署编排从不设 profile、CI 也不设，
 * 于是它们自实现起从未被任何自动化路径执行过。本测试就是那条缺失的自动化覆盖：
 * 不依赖容器、不依赖 Spring 上下文，直接断言构造器在生产 profile + 占位密钥时抛异常。
 */
class Phase109DeploymentGuardTest {

    private JwtService build(String secret, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new JwtService(secret, 12, env);
    }

    /** pilot/prod + 开发占位密钥 → 拒绝启动 */
    @Test
    void productionProfileRejectsPlaceholderSecret() {
        for (String profile : HipProfiles.PRODUCTION_PROFILES) {
            var ex = assertThrows(IllegalStateException.class,
                    () -> build(JwtService.DEV_PLACEHOLDER, profile),
                    profile + " profile 必须拒绝占位密钥");
            assertTrue(ex.getMessage().contains("HIP_JWT_SECRET"), ex.getMessage());
        }
    }

    /** pilot/prod + 过短密钥 → 拒绝启动（32 位以下容易被暴力破解） */
    @Test
    void productionProfileRejectsShortSecret() {
        assertThrows(IllegalStateException.class, () -> build("short-secret", "pilot"));
        assertThrows(IllegalStateException.class, () -> build("0123456789012345678901234567890", "prod"));
    }

    /** pilot/prod + 合规密钥 → 正常启动并可签发校验 */
    @Test
    void productionProfileAcceptsProperSecret() {
        var svc = build("0123456789012345678901234567890123456789012345678", "pilot");
        assertEquals("someone", svc.verify(svc.issue("someone")));
    }

    /** 显式 dev profile 可用占位密钥，否则本机开发与 CI 无法启动 */
    @Test
    void devProfileAllowsPlaceholder() {
        assertDoesNotThrow(() -> build(JwtService.DEV_PLACEHOLDER, "dev"));
    }

    /**
     * fail-closed（上线前审查 P2-1）：**无 profile 一律按生产处理**，占位密钥被拒。
     * 此前无 profile 被当开发放行——运维漏配 spring.profiles.active 即回落公开占位密钥、
     * 可伪造 ADMIN 令牌完全接管。缺配必须朝安全侧倒。
     */
    @Test
    void missingProfileIsTreatedAsProductionAndRejectsPlaceholder() {
        var ex = assertThrows(IllegalStateException.class,
                () -> build(JwtService.DEV_PLACEHOLDER),
                "无 profile 必须按生产处理、拒绝占位密钥");
        assertTrue(ex.getMessage().contains("HIP_JWT_SECRET"), ex.getMessage());
        // 拼错的 profile（既非生产名也非 dev/test/ci）同样按生产处理
        assertThrows(IllegalStateException.class,
                () -> build(JwtService.DEV_PLACEHOLDER, "piloot"),
                "拼错的 profile 不能成为绕过闸门的路径");
    }

    /** 生产形态判定集中在 HipProfiles，新增闸门只需调它 */
    @Test
    void profileHelperRecognisesProductionProfiles() {
        MockEnvironment pilot = new MockEnvironment();
        pilot.setActiveProfiles("pilot");
        assertTrue(HipProfiles.isProduction(pilot));

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertTrue(HipProfiles.isProduction(prod));

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertFalse(HipProfiles.isProduction(dev));

        // fail-closed：无 profile = 生产（缺配朝安全侧倒），拼错的 profile 同理
        assertTrue(HipProfiles.isProduction(new MockEnvironment()));
        MockEnvironment typo = new MockEnvironment();
        typo.setActiveProfiles("piloot");
        assertTrue(HipProfiles.isProduction(typo));
    }
}
