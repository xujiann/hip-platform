package cn.hip.server;

import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import cn.hip.platform.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 账号停用后，已签发的未过期令牌应立即失效（教材抽查发现的漏洞，固化为回归测试） */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DisabledAccountTokenTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired SysUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void tokenOfDisabledAccountIsRejected() throws Exception {
        SysUser u = new SysUser();
        u.setUsername("disabled1");
        u.setPassword(passwordEncoder.encode("Abcd1234"));
        u.setRealName("停用测试");
        userRepository.save(u);
        String token = jwtService.issue("disabled1");

        // 启用状态：令牌可正常访问院内接口
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 停用账号后：同一未过期令牌应被拒（401）
        u.setEnabled(false);
        userRepository.save(u);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
