package cn.hip.datagov.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 指标快照每日自动生成（v72 包 D，664★）。
 *
 * <p>此前快照只能由人点「生成当日快照」触发，偏离表也如实写着「按周期自动采集与自动计算尚未提供」。
 * 本类把它按期跑起来，<b>计算口径一个字未改</b>——只是不再需要有人记得去点。
 *
 * <p>三件事照 {@code CdrSyncScheduler} 的既有写法，一件不少：
 * <ul>
 *   <li><b>多实例互斥选主</b>走既有的 {@code JobLockService}。v59 查出过「advisory lock 在连接池上
 *       不成立」那条坑（会话级锁而 JdbcTemplate 每次另借连接，任务被误判「其他实例执行中」静默跳过），
 *       选主一律用这个现成的，不自己写。</li>
 *   <li>开关键 {@code dg_metric_auto_snapshot_enabled}（0=关闭），与 CDR 同步、系统巡检同形态。</li>
 *   <li>异常只记日志不外抛——夜间任务失败不该连累调度线程。</li>
 * </ul>
 *
 * <p>时点取 02:20：排在 CDR 增量同步（02:10）之后，让当日指标算在已归集的数据上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricSnapshotScheduler {

    /** 开关键：0 = 关闭自动生成（仍可在页面上手工点）。 */
    public static final String ENABLED_KEY = "dg_metric_auto_snapshot_enabled";

    private final MetricSnapshotService snapshotService;
    private final JdbcTemplate jdbc;
    private final cn.hip.platform.core.service.JobLockService jobLock;

    @Scheduled(cron = "0 20 2 * * *", zone = cn.hip.platform.core.config.HipProfiles.ZONE)
    public void autoSnapshot() {
        jobLock.runExclusively("dg-metric-snapshot", () -> {
            var rows = jdbc.queryForList(
                    "select cfg_value from sys_config where cfg_key = ?", ENABLED_KEY);
            if (!rows.isEmpty() && !"1".equals(rows.get(0).get("cfg_value"))) {
                return;
            }
            try {
                log.info("指标快照自动生成：{} 项", snapshotService.snapshotToday());
            } catch (Exception e) {
                log.error("指标快照自动生成失败", e);
            }
        });
    }
}
