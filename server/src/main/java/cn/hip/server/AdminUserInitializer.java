package cn.hip.server;

import cn.hip.platform.core.entity.SysRole;
import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

/** 首次启动时创建默认管理员 admin/admin123（生产环境须立即修改密码） */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements ApplicationRunner {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setRealName("系统管理员");
        admin.setTitle("管理员");
        SysRole adminRole = entityManager
                .createQuery("from SysRole where code = 'ADMIN'", SysRole.class)
                .getSingleResult();
        admin.getRoles().add(adminRole);
        // v32 P2：生产形态下内置 admin 也走首登强制改密（1009 闸）。此前只靠 log.warn 口头提醒，
        // 而 admin 持全院最高权限、口令是代码/文档公开的 admin123——裸启动跳过 init-hospital
        // 末步改密时，公开默认口令可无限期使用。isProduction 是全站生产安全姿态的唯一闸
        // （fail-closed：空/未知 profile 也算生产），dev/test/ci 显式非生产不置位——E2E 用
        // admin/admin123 直登、单测 test profile 均不受影响。机器账号无此路径（admin 是人账号）。
        boolean production = cn.hip.platform.core.config.HipProfiles.isProduction(environment);
        admin.setMustChangePassword(production);
        userRepository.save(admin);
        if (production) {
            log.warn("已创建默认管理员账号 admin/admin123（生产形态：首登将被强制改密，见 1009 闸）");
        } else {
            log.warn("已创建默认管理员账号 admin/admin123，请尽快登录修改密码！");
        }
    }
}
