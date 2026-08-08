package cn.hip.platform.core.repository;

import cn.hip.platform.core.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUsername(String username);

    /**
     * 原子累加登录失败次数，达阈值即锁定。
     * 读-改-写在并发猜测下所有请求都读到同一旧值，计数永远停在 1（防爆破形同虚设）。
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = """
            update sys_user set
                   failed_attempts = case when failed_attempts + 1 >= :maxFailed then 0 else failed_attempts + 1 end,
                   locked_until = case when failed_attempts + 1 >= :maxFailed then :lockUntil else locked_until end
            where username = :username
            """, nativeQuery = true)
    int bumpFailedAttempts(@org.springframework.data.repository.query.Param("username") String username,
                           @org.springframework.data.repository.query.Param("maxFailed") int maxFailed,
                           @org.springframework.data.repository.query.Param("lockUntil") java.time.Instant lockUntil);
}
