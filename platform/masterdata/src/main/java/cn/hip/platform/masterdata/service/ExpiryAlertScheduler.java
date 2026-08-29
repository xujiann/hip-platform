package cn.hip.platform.masterdata.service;

import cn.hip.platform.core.service.JobLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 效期预警每日巡检（药事全链②）。
 *
 * <p>复用 OpsHealthScheduler 模式：{@link JobLockService} 保证多实例只一台执行；
 * sys_config 开关 inv_expiry_alert_enabled 控制启停；预警天数取 inv_expiry_warn_days（默认 90）；
 * 命中的近效期批次落 ops_fault_ticket（同题未处理不重复开单），与运维告警同一落点，
 * 药师/管理员在运维台账即可看到，外发短信/IM 接入点同 OpsHealthScheduler 为外部条件项。
 *
 * <p>近效期在库量为估算口径，详见 {@link InventoryService#expiryWarnings(int)}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiryAlertScheduler {

    private final InventoryService inventoryService;
    private final JdbcTemplate jdbc;
    private final JobLockService jobLock;

    /** 每日 07:00 巡检（与 cron 同时区，避免容器 TZ 未生效时判断错位——见 OpsHealthScheduler B-7） */
    @Scheduled(cron = "0 0 7 * * *", zone = cn.hip.platform.core.config.HipProfiles.ZONE)
    public void dailyExpiryScan() {
        runScan();
    }

    /**
     * 执行一轮近效期巡检并返回本轮开单数。提为 public 供运维/E2E 手动触发，
     * 与 OpsHealthScheduler.runInspection 同理——否则只有 cron 一条路径无法当场验证。
     */
    public int runScan() {
        AtomicInteger opened = new AtomicInteger();
        jobLock.runExclusively("inv-expiry-scan", () -> {
            if (!enabled()) return;
            int days = warnDays();
            try {
                for (InventoryService.ExpiryWarning w : inventoryService.expiryWarnings(days)) {
                    String label = "EXPIRED".equals(w.status()) ? "已过期" : "近效期";
                    // title 必须**稳定**（第七轮审阅）：原先含"估算在库 N"，而 N 随发药变化，
                    // 同一批次每次巡检 title 都不同 → 去重失效 → 每天一张工单风暴。
                    // 去重键只用批号+效期（同一批次唯一），估算量进日志不进 title
                    String title = String.format("[效期预警] %s 批号%s 于 %s 到期（%s）",
                            w.drugName(), w.batchNo() == null ? "-" : w.batchNo(), w.expireDate(), label);
                    String level = "EXPIRED".equals(w.status()) ? "HIGH" : "MEDIUM";
                    Integer open = jdbc.queryForObject(
                            "select count(*) from ops_fault_ticket where title = ? and status = 'OPEN'",
                            Integer.class, title);
                    if (open != null && open > 0) continue;   // 同题未处理不重复开单（title 已稳定）
                    jdbc.update("insert into ops_fault_ticket(title, level, reporter) values (?,?,'system')",
                            title, level);
                    opened.incrementAndGet();
                    log.warn("效期预警开单: {}（估算在库 {}，{}）", title, w.estimatedRemaining(), level);
                }
            } catch (Exception e) {
                log.error("效期预警巡检失败", e);
            }
        });
        return opened.get();
    }

    private boolean enabled() {
        var rows = jdbc.queryForList("select cfg_value from sys_config where cfg_key = 'inv_expiry_alert_enabled'");
        return rows.isEmpty() || "1".equals(rows.get(0).get("cfg_value"));
    }

    private int warnDays() {
        var rows = jdbc.queryForList("select cfg_value from sys_config where cfg_key = 'inv_expiry_warn_days'");
        if (rows.isEmpty()) return 90;
        try {
            return Integer.parseInt(String.valueOf(rows.get(0).get("cfg_value")));
        } catch (NumberFormatException e) {
            return 90;   // 配置被写坏时退默认，不因脏配置停摆
        }
    }
}
