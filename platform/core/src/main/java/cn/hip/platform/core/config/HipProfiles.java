package cn.hip.platform.core.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

/**
 * 生产形态判定的**唯一**入口。
 *
 * <p>此前 JwtService / MockAdapterGuard / PortalController 各写一份
 * `getActiveProfiles() 含 pilot|prod` 的判断，任何一种不经 spring.profiles.active 的部署方式
 * 都会让三道安全闸门同时静默失效（审阅 A-1 的根因之一）。集中到一处后，
 * 新增闸门只需调用本类，且加新的生产 profile 名只改一个地方。
 */
public final class HipProfiles {

    /** 视为「生产形态」的 profile 名 */
    public static final List<String> PRODUCTION_PROFILES = List.of("pilot", "prod");

    /** 显式开发/测试形态的 profile 名——只有这些才允许弱密钥/Mock/占位口令 */
    public static final List<String> NON_PRODUCTION_PROFILES = List.of("dev", "test", "ci");

    private HipProfiles() {
    }

    /**
     * 是否为试点/生产运行（安全闸门、Mock 禁用、患者端自确认支付禁用等据此判定）。
     *
     * <p><b>fail-closed（上线前审查 P2-1）</b>：此前只在 profile 含 pilot/prod 时返回 true——
     * 运维漏配 `spring.profiles.active`（忘记 -D、容器环境变量丢失）就整体回落到编译进包的
     * 公开占位密钥，可零成本伪造 ADMIN 令牌完全接管。改为：**只有显式声明 dev/test/ci
     * 才算非生产**，其余（含空 profile、未知 profile）一律按生产处理，缺配朝安全侧倒。
     */
    public static boolean isProduction(Environment environment) {
        String[] active = environment.getActiveProfiles();
        // 显式声明的开发/测试形态：放开占位密钥与 Mock
        if (Arrays.stream(active).anyMatch(NON_PRODUCTION_PROFILES::contains)) {
            return false;
        }
        // 其余一切（含空 profile、拼错的 profile）都当生产——缺配不能成为绕过闸门的路径
        return true;
    }

    /** 定时任务统一时区：容器默认 UTC 会让 cron 触发时刻偏 8 小时 */
    public static final String ZONE = "Asia/Shanghai";
}
