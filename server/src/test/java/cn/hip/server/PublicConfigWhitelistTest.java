package cn.hip.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 公开配置接口只返回白名单键，业务敏感参数（医保比例/模块开关等）不得外泄 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicConfigWhitelistTest {

    @Autowired MockMvc mockMvc;

    @Test
    void publicConfigContainsOnlyWhitelistedKeys() throws Exception {
        mockMvc.perform(get("/api/config/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hospital_name").exists())
                .andExpect(jsonPath("$.data.contact_phone").exists())
                // 医保业务参数与内部开关不得出现在公开接口
                .andExpect(jsonPath("$.data.yb_ratio_staff").doesNotExist())
                .andExpect(jsonPath("$.data.yb_ratio_resident").doesNotExist())
                .andExpect(jsonPath("$.data.yb_deductible_staff").doesNotExist())
                .andExpect(jsonPath("$.data.drg_rate").doesNotExist())
                .andExpect(jsonPath("$.data.lis_allow_substitute").doesNotExist())
                .andExpect(jsonPath("$.data['module.insurance.enabled']").doesNotExist());
    }
}
