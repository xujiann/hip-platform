package cn.hip.server.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.function.BooleanSupplier;

/**
 * 1.0.5：健康告警定时评估（原仅拉式——打开运维页才评估，无人看就无人知道）。
 * 每小时跑与健康概览同口径的检查，异常自动开故障工单（同题未处理不重复开单）。
 * 1.2.14 巡检扩面：新增连接池耗尽/集成积压/堆内存三条盲区检查；并把外发从"外部条件项"
 * 落地为可插拔 webhook——配了 sys_config.ops_alert_webhook 则 HIGH 工单 POST 外发（钉钉/企业微信），
 * 未配则仅开单（向后兼容）；外发失败一律吞掉，绝不拖累巡检主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsHealthScheduler {

    private final JdbcTemplate jdbc;
    private final cn.hip.platform.core.service.JobLockService jobLock;
    /** 连接池指标来源：注入 DataSource 后解包为 HikariDataSource 读活跃/上限（Hikari 是 Boot 默认池） */
    private final javax.sql.DataSource dataSource;

    /** 磁盘告警监测路径（逗号分隔多卷）；缺省查工作目录——试点应配数据库卷与备份卷所在分区 */
    @org.springframework.beans.factory.annotation.Value("${hip.ops.disk-paths:}")
    private String diskPaths;

    /** 连接池使用率(活跃/上限)告警阈值：逼近上限时新请求排队 → 全站阻塞，比慢接口更靠前的死因 */
    @org.springframework.beans.factory.annotation.Value("${hip.ops.pool-usage-threshold:0.9}")
    private double poolUsageThreshold;

    /** JVM 堆使用率(已用/最大)告警阈值：过高 → GC 频繁/停顿 → 迟早 OOM 进程猝死 */
    @org.springframework.beans.factory.annotation.Value("${hip.ops.heap-usage-threshold:0.9}")
    private double heapUsageThreshold;

    /** 集成报文近 1 小时 ERROR 条数阈值：超阈值说明 LIS/PACS 报文处理在堆积（结果回不来） */
    @org.springframework.beans.factory.annotation.Value("${hip.ops.queue-error-threshold:100}")
    private int queueErrorThreshold;

    @Scheduled(cron = "0 0 * * * *", zone = cn.hip.platform.core.config.HipProfiles.ZONE)
    public void hourlyHealthCheck() {
        runInspection();
    }

    /**
     * 执行一轮巡检并返回本轮开单数（1.2.6 v24-A）。
     * 提为 public 供运维手动触发——原先只有 cron 一条路径：
     * 试点现场"巡检到底有没有在工作"无法当场验证，只能等一小时或翻日志。
     */
    public int runInspection() {
        // 局部计数经 lambda 捕获：实例字段会在 cron 与手动触发并发时互相串扰
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        jobLock.runExclusively("ops-health-check", () -> {
            var rows = jdbc.queryForList(
                    "select cfg_value from sys_config where cfg_key = 'ops_auto_health_enabled'");
            if (!rows.isEmpty() && !"1".equals(rows.get(0).get("cfg_value"))) {
                return;
            }
            try {
                check(opened, "24 小时内无成功备份", "HIGH", () -> {
                    Integer n = jdbc.queryForObject("""
                            select count(*) from ops_backup_log
                            where status in ('SUCCESS','VERIFIED') and created_at > now() - interval '24 hours'
                            """, Integer.class);
                    return n == null || n == 0;
                });
                check(opened, "今日慢接口超过阈值 50", "MEDIUM", () -> {
                    Integer n = jdbc.queryForObject(
                            "select count(*) from ops_slow_api where occurred_at >= current_date and occurred_at < current_date + 1", Integer.class);
                    return n != null && n > 50;
                });
                String paths = diskPaths == null || diskPaths.isBlank()
                        ? System.getProperty("user.dir") : diskPaths;
                for (String p : paths.split(",")) {
                    File vol = new File(p.trim());
                    check(opened, "磁盘可用空间不足 10GB：" + p.trim(), "HIGH", () ->
                            vol.exists() && vol.getFreeSpace() / 1024 / 1024 / 1024 < 10);
                    check(opened, "磁盘监测路径不存在（配置错误）：" + p.trim(), "MEDIUM", () -> !vol.exists());
                }
                // 1.2.14 巡检扩面：三条新盲区。连接池耗尽/堆压力是"全站级"死因（比慢接口更靠前），
                // 集成积压是"临床级"降级（检验/影像结果回不来）。阈值均可配（hip.ops.*）。
                check(opened, "数据库连接池接近耗尽", "HIGH", () -> poolUsageRatio() >= poolUsageThreshold);
                check(opened, "集成报文近 1 小时错误积压", "MEDIUM", () -> {
                    Integer n = jdbc.queryForObject("""
                            select count(*) from int_message_log
                            where status = 'ERROR' and created_at > now() - interval '1 hour'
                            """, Integer.class);
                    return n != null && n > queueErrorThreshold;
                });
                check(opened, "JVM 堆内存使用率过高", "HIGH", () -> heapUsageRatio() >= heapUsageThreshold);
                // default 分区恒空是分区改造（V100）的不变量（第七轮审阅 P2-2）：
                // hip_purge_observability 维护落后超 2 月时新数据会落 default，后续建分区将失败、
                // 表膨胀重现且只有一行日志。任一观测表 default 非空即开单，逼运维尽快补分区
                check(opened, "分区表 default 分区非空（月度维护可能已落后，须尽快补分区）", "HIGH",
                        this::anyDefaultPartitionNonEmpty);
                // 观测表归档：三张只增不减的表若无清理，ops_slow_api 还会自我放大
                // （DB 慢 → 更多请求超阈值 → 每个都同步 insert → 更慢）。
                // 小时判断必须与 cron 同时区：LocalTime.now() 用 JVM 默认时区，
                // 容器 TZ 未生效时与 cron 的 Asia/Shanghai 永不相交，归档一次都不会执行（B-7）
                if (java.time.LocalTime.now(java.time.ZoneId.of(
                        cn.hip.platform.core.config.HipProfiles.ZONE)).getHour() == 3) {
                    jdbc.execute("select hip_purge_observability()");
                }
            } catch (Exception e) {
                log.error("自动巡检失败", e);
            }
        });
        return opened.get();
    }

    private void check(java.util.concurrent.atomic.AtomicInteger opened,
                       String issue, String level, BooleanSupplier firing) {
        if (!firing.getAsBoolean()) {
            return;
        }
        String title = "[自动巡检] " + issue;
        Integer open = jdbc.queryForObject(
                "select count(*) from ops_fault_ticket where title = ? and status = 'OPEN'", Integer.class, title);
        if (open != null && open > 0) {
            return;
        }
        jdbc.update("insert into ops_fault_ticket(title, level, reporter) values (?,?,'system')", title, level);
        opened.incrementAndGet();
        log.warn("自动巡检开单: {} ({})", title, level);
        // 可插拔外发：仅"刚开单"这一次触发（同题已 OPEN 会在上面 return，天然不重复外发）
        dispatchWebhook(title, level);
    }

    /**
     * 连接池使用率 = 活跃连接 / 池上限（0~1）。取指标失败或非 Hikari 池返回 0（不误报）。
     * Hikari 是 Spring Boot 默认连接池；池满时新请求进入 awaiting 队列直至超时，是全站阻塞的先兆。
     */
    private double poolUsageRatio() {
        try {
            var hikari = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
            var mx = hikari.getHikariPoolMXBean();
            int max = hikari.getMaximumPoolSize();
            return max <= 0 ? 0.0 : (double) mx.getActiveConnections() / max;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** JVM 堆使用率 = 已用 / 最大（0~1）。getMax() 未定义(-1) 时返回 0（不误报）。 */
    private double heapUsageRatio() {
        var h = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = h.getMax();
        return max <= 0 ? 0.0 : (double) h.getUsed() / max;
    }

    /**
     * V100 三张分区表的 default 分区是否有任一非空（第七轮审阅 P2-2）。
     * default 恒空是设计不变量；非空意味着某月无分区、月度维护落后，须尽快补分区。
     * 取指标失败（如表还没分区化的旧库）返回 false，不误报。
     */
    private boolean anyDefaultPartitionNonEmpty() {
        for (String def : new String[]{"sys_audit_log_pdefault", "int_message_log_pdefault", "ops_slow_api_pdefault"}) {
            try {
                if (jdbc.queryForObject("select exists(select 1 from " + def + " limit 1)", Boolean.class)) {
                    return true;
                }
            } catch (Exception ignore) {
                // 该 default 分区不存在（未分区化的库）——跳过，不误报
            }
        }
        return false;
    }

    /**
     * 告警外发（可插拔）：配了 sys_config.ops_alert_webhook 且为 HIGH 级工单，则 POST 到该 webhook
     * （钉钉/企业微信 text 型格式）；未配置=保持原行为（仅开单，向后兼容）。
     * 外发绝不能影响巡检主流程——读配置/建连/发送任一环节异常一律吞掉，最多记一条 warn。
     */
    private void dispatchWebhook(String title, String level) {
        if (!"HIGH".equals(level)) {
            return;   // 仅高级别外发，避免刷屏；MEDIUM/LOW 仍进工单台账
        }
        String url;
        try {
            var rows = jdbc.queryForList(
                    "select cfg_value from sys_config where cfg_key = 'ops_alert_webhook'");
            if (rows.isEmpty()) {
                return;   // 键不存在
            }
            url = (String) rows.get(0).get("cfg_value");
        } catch (Exception e) {
            return;   // 读配置失败也不能拖累巡检
        }
        if (url == null || url.isBlank()) {
            return;   // 未配置=只开单（当前行为）
        }
        try {
            String content = "【HIP 自动巡检告警·" + level + "】\n" + title;
            String body = "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + jsonEscape(content) + "\"}}";
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            body, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("告警外发返回非 2xx（{}）: {}", resp.statusCode(), title);
            }
        } catch (Exception e) {
            // 网络/网关/超时等一律吞掉：外发是"加分项"，挂了不能反过来搞垮巡检闸门
            log.warn("告警外发失败（不影响巡检）: {} - {}", title, e.toString());
        }
    }

    /** 最小 JSON 字符串转义：标题可能含引号/反斜杠/换行，避免拼坏 webhook body。 */
    private static String jsonEscape(String s) {
        var sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
