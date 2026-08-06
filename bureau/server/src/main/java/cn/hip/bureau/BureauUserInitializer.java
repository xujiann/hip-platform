package cn.hip.bureau;

import cn.hip.platform.core.entity.SysRole;
import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 首次启动创建默认账号：admin 及三个演示岗位账号（生产环境须立即修改密码） */
@Slf4j
@Component
@RequiredArgsConstructor
public class BureauUserInitializer implements ApplicationRunner {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        create("admin", "系统管理员", "ADMIN");
        create("banban", "王办案", "HANDLER");
        create("fazhi", "李法制", "LEGAL");
        create("juzhang", "赵局长", "LEADER");
        log.warn("已创建默认账号 admin/banban/fazhi/juzhang（口令 admin123），请尽快修改！");
    }

    private void create(String username, String realName, String roleCode) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("admin123"));
        u.setRealName(realName);
        SysRole role = entityManager
                .createQuery("from SysRole where code = :c", SysRole.class)
                .setParameter("c", roleCode)
                .getSingleResult();
        u.getRoles().add(role);
        userRepository.save(u);
    }
}
