package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 患者端令牌与院内接口的安全隔离（曾在 E2E 中发现越权漏洞，此处固化为回归测试） */
@SpringBootTest
@AutoConfigureMockMvc
class PortalSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;

    private String portalToken() {
        return jwtService.issue("portal:1");
    }

    private String staffToken() {
        return jwtService.issue("admin");
    }

    @Test
    void portalTokenCannotAccessHospitalApis() throws Exception {
        for (String path : new String[]{"/api/stats/overview", "/api/system/users",
                "/api/patients", "/api/outpatient/charges/worklist", "/api/masterdata/drugs"}) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + portalToken()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void portalTokenCanAccessPortalApis() throws Exception {
        mockMvc.perform(get("/api/portal/my/registrations")
                        .header("Authorization", "Bearer " + portalToken()))
                .andExpect(status().isOk());
    }

    @Test
    void staffTokenCannotAccessPortalApis() throws Exception {
        mockMvc.perform(get("/api/portal/my/registrations")
                        .header("Authorization", "Bearer " + staffToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/stats/overview")).andExpect(status().isUnauthorized());
    }
}
