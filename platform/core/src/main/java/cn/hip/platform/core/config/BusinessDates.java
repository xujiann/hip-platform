package cn.hip.platform.core.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务"今天"统一口径（1.1.9）：裸 `LocalDate.now()` 用 JVM 默认时区，容器 TZ 未生效时
 * Java 侧单号日戳/排班"今天"要到北京时间 08:00 才切日，而 SQL `current_date` 按 DB 时区切——
 * 三套口径互相漂移且只在跨 00:00–08:00 的班次暴露（B-7 归档一例已实付学费）。
 * 全仓业务代码一律经此取"今天"，禁止裸 `LocalDate.now()`。
 */
public final class BusinessDates {

    private static final ZoneId ZONE = ZoneId.of(HipProfiles.ZONE);

    private BusinessDates() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
