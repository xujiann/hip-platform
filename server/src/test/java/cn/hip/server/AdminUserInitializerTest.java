package cn.hip.server;

import cn.hip.platform.core.entity.SysRole;
import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * v32 P2：内置 admin 首登强制改密闸。
 *
 * <p>AdminUserInitializer 建 admin/admin123 时，只在**生产形态**（isProduction，含空/未知 profile
 * 的 fail-closed）置 mustChangePassword=true，让 1009 闸把公开默认口令的最高权限账号拦到改密白名单；
 * dev/test/ci 显式非生产不置位——CI E2E 用 admin/admin123 直登、单测跑 test profile，均不能被拦死。
 * 用纯 Mockito 单测（不起 Spring 上下文，因 initializer 是幂等的 count()==0 才建号）。
 */
class AdminUserInitializerTest {

    private SysUser createAdminUnder(String... profiles) {
        SysUserRepository repo = mock(SysUserRepository.class);
        when(repo.count()).thenReturn(0L);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("ENC");

        EntityManager em = mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<SysRole> q = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(SysRole.class))).thenReturn(q);
        when(q.getSingleResult()).thenReturn(new SysRole());

        MockEnvironment env = new MockEnvironment();
        if (profiles.length > 0) env.setActiveProfiles(profiles);

        new AdminUserInitializer(repo, encoder, em, env).run(new DefaultApplicationArguments());

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(repo).save(captor.capture());
        SysUser saved = captor.getValue();
        assertEquals("admin", saved.getUsername());
        return saved;
    }

    @Test
    void adminForcedToChangePasswordInProduction() {
        assertTrue(createAdminUnder("pilot").getMustChangePassword(), "pilot 形态 admin 首登须强制改密");
        assertTrue(createAdminUnder("prod").getMustChangePassword(), "prod 形态 admin 首登须强制改密");
        // 空 profile：fail-closed 当生产（漏配不能成为绕过闸门的路径）
        assertTrue(createAdminUnder().getMustChangePassword(), "空 profile（fail-closed）按生产，须强制改密");
    }

    @Test
    void adminNotForcedInDevTestCi() {
        assertFalse(createAdminUnder("dev").getMustChangePassword(), "dev 不置位（E2E 用 admin/admin123 直登）");
        assertFalse(createAdminUnder("test").getMustChangePassword(), "test 不置位（单测 profile）");
        assertFalse(createAdminUnder("ci").getMustChangePassword(), "ci 不置位");
    }
}
