package cn.hip.cdr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** CDR 每日自动增量同步（cdr_auto_sync_enabled=0 关闭），与医保自动对账同为夜间任务 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CdrSyncScheduler {

    private final CdrSyncService syncService;
    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 10 2 * * *", zone = cn.hip.platform.core.config.HipProfiles.ZONE)
    public void autoIncremental() {
        var rows = jdbc.queryForList(
                "select cfg_value from sys_config where cfg_key = 'cdr_auto_sync_enabled'");
        if (!rows.isEmpty() && !"1".equals(rows.get(0).get("cfg_value"))) {
            return;
        }
        try {
            log.info("CDR 自动增量同步: {}", syncService.syncIncremental());
        } catch (Exception e) {
            log.error("CDR 自动增量同步失败", e);
        }
    }
}
