package cn.hip.inpatient.service;

import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.JobLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * v39：长期医嘱每日执行行生成（每日 06:30，多实例仅一台执行）。
 * 为在院+未停嘱+未作废的 LONG 医嘱按频次生成当日 PENDING 执行行（幂等 on conflict skip）。
 * 手动补生成走 POST /api/inpatient/exec-lines/generate（停机跨日恢复后运维可补）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LongOrderScheduler {

    private final InpatientService inpatientService;
    private final JobLockService jobLock;

    @Scheduled(cron = "0 30 6 * * *", zone = cn.hip.platform.core.config.HipProfiles.ZONE)
    public void dailyGenerate() {
        jobLock.runExclusively("inp-long-order-gen", () -> {
            try {
                int n = inpatientService.generateDailyExecLines(BusinessDates.today());
                log.info("长期医嘱执行行生成完成：活跃长期医嘱 {} 条", n);
            } catch (Exception e) {
                log.error("长期医嘱执行行生成失败", e);
            }
        });
    }
}
