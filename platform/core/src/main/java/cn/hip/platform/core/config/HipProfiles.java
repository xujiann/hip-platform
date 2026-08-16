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

    private HipProfiles() {
    }

    /** 是否为试点/生产运行（安全闸门、Mock 禁用、患者端自确认支付禁用等据此判定） */
    public static boolean isProduction(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(PRODUCTION_PROFILES::contains);
    }

    /** 定时任务统一时区：容器默认 UTC 会让 cron 触发时刻偏 8 小时 */
    public static final String ZONE = "Asia/Shanghai";
}
