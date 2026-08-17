package cn.hip.platform.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 定时任务选主：多实例部署时同一任务只允许一个实例执行。
 *
 * <p>用 PostgreSQL 会话级 advisory lock。**加锁、任务体、解锁必须发生在同一条物理连接上**：
 * advisory lock 属于会话（连接），而 JdbcTemplate 的每次独立调用都会从连接池另借一条连接——
 * 若分三次调用，锁留在 A 连接、解锁打在 C 连接上必然失败，锁随 A 归还连接池后**不会释放**，
 * 下一次取锁被误判为"其他实例正在执行"，任务从此静默停摆（1.1.2 审阅 A-1，已实测复现）。
 * 故整个过程包在一个 ConnectionCallback 里钉住同一连接；任务体内部的 SQL 仍可正常
 * 另借连接，不受影响。
 *
 * <p>拿不到锁说明别的实例正在跑，直接跳过即可（这些任务都是幂等或按日切的）。
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
        Boolean ran = jdbc.execute((ConnectionCallback<Boolean>) con -> {
            boolean locked = false;
            try (var lock = con.prepareStatement("select pg_try_advisory_lock(?)")) {
                lock.setLong(1, key);
                try (var rs = lock.executeQuery()) {
                    locked = rs.next() && rs.getBoolean(1);
                }
                if (!locked) {
                    log.info("定时任务 {} 已由其他实例执行，本实例跳过", jobName);
                    return false;
                }
                body.run();
                return true;
            } finally {
                if (locked) {
                    // 解锁必须在取锁的同一连接上，且必须核对返回值——失败即锁泄漏，
                    // 该任务在本实例存活期间将永远无法再次执行
                    try (var unlock = con.prepareStatement("select pg_advisory_unlock(?)")) {
                        unlock.setLong(1, key);
                        try (var rs = unlock.executeQuery()) {
                            if (!rs.next() || !rs.getBoolean(1)) {
                                log.error("定时任务 {} 解锁失败（advisory lock 泄漏），请排查", jobName);
                            }
                        }
                    }
                }
            }
        });
        return Boolean.TRUE.equals(ran);
    }
}
