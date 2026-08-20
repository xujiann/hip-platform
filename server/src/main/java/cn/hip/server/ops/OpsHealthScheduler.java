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
 * 每小时跑与健康概览同口径的检查，异常自动开故障工单（同题未处理不重复开单）；
 * 外发通道（短信/IM）依赖网关资质，接入点即本类 check 的告警落点，属外部条件项。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsHealthScheduler {

    private final JdbcTemplate jdbc;
    private final cn.hip.platform.core.service.JobLockService jobLock;

    /** 磁盘告警监测路径（逗号分隔多卷）；缺省查工作目录——试点应配数据库卷与备份卷所在分区 */
    @org.springframework.beans.factory.annotation.Value("${hip.ops.disk-paths:}")
    private String diskPaths;

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
    }
}
