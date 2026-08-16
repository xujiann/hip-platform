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

    @Scheduled(cron = "0 0 * * * *", zone = cn.hip.platform.core.config.HipProfiles.ZONE)
    public void hourlyHealthCheck() {
        var rows = jdbc.queryForList(
                "select cfg_value from sys_config where cfg_key = 'ops_auto_health_enabled'");
        if (!rows.isEmpty() && !"1".equals(rows.get(0).get("cfg_value"))) {
            return;
        }
        try {
            check("24 小时内无成功备份", "HIGH", () -> {
                Integer n = jdbc.queryForObject("""
                        select count(*) from ops_backup_log
                        where status in ('SUCCESS','VERIFIED') and created_at > now() - interval '24 hours'
                        """, Integer.class);
                return n == null || n == 0;
            });
            check("今日慢接口超过阈值 50", "MEDIUM", () -> {
                Integer n = jdbc.queryForObject(
                        "select count(*) from ops_slow_api where occurred_at::date = current_date", Integer.class);
                return n != null && n > 50;
            });
            check("磁盘可用空间不足 10GB", "HIGH", () ->
                    new File(System.getProperty("user.dir")).getFreeSpace() / 1024 / 1024 / 1024 < 10);
        } catch (Exception e) {
            log.error("自动巡检失败", e);
        }
    }

    private void check(String issue, String level, BooleanSupplier firing) {
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
        log.warn("自动巡检开单: {} ({})", title, level);
    }
}
