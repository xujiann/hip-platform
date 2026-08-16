package cn.hip.platform.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 定时任务选主：多实例部署时同一任务只允许一个实例执行。
 *
 * <p>用 PostgreSQL 会话级 advisory lock，不需要额外依赖也不需要表：
 * 拿不到锁说明别的实例正在跑，直接跳过即可（这些任务都是幂等或按日切的）。
 * 未选主时的后果不是"慢"，而是重复开工单、重复对账批次、重复推进 CDR 水位。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobLockService {

    private final JdbcTemplate jdbc;

    /** 以任务名取锁并执行；未取到锁返回 false（说明其他实例已在执行） */
    public boolean runExclusively(String jobName, Runnable body) {
        long key = jobName.hashCode();
        Boolean got = jdbc.queryForObject("select pg_try_advisory_lock(?)", Boolean.class, key);
        if (!Boolean.TRUE.equals(got)) {
            log.info("定时任务 {} 已由其他实例执行，本实例跳过", jobName);
            return false;
        }
        try {
            body.run();
            return true;
        } finally {
            jdbc.queryForObject("select pg_advisory_unlock(?)", Boolean.class, key);
        }
    }
}
