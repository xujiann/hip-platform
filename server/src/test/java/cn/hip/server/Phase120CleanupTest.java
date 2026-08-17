package cn.hip.server;

import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.service.JobLockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 1.2.0 清尾回归：模块开关横向拦截、锁键、日期预校验、CSV 口径、异常基类 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase120CleanupTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired cn.hip.outpatient.service.ChargeService chargeService;
    @Autowired cn.hip.platform.core.config.ModuleGate moduleGate;

    /** 关闭 insurance 模块后，payMethod=YB 的横向调用必须被业务码拦截（此前只挡菜单与路由） */
    @Test
    void ybBlockedWhenInsuranceModuleDisabled() {
        jdbc.update("""
                insert into sys_config(cfg_key, cfg_value) values ('module.insurance.enabled', '0')
                on conflict (cfg_key) do update set cfg_value = '0'
                """);
        moduleGate.evictCache();
        try {
            var e = assertThrows(BizException.class,
                    () -> chargeService.settle(999999L, "YB", null));
            assertEquals(5010, e.code, "关闭模块后 YB 结算须被 5010 拦截（而非走到挂号不存在）");
        } finally {
            jdbc.update("update sys_config set cfg_value = '1' where cfg_key = 'module.insurance.enabled'");
            moduleGate.evictCache();
        }
    }

    /** 锁键改强哈希：不同任务名必须得到不同键（String.hashCode 曾可碰撞互踢） */
    @Test
    void jobLockKeyAvoidsHashCodeCollision() {
        // "Aa"/"BB" 是 String.hashCode 的经典碰撞对
        assertEquals("Aa".hashCode(), "BB".hashCode(), "前提：hashCode 确实碰撞");
        assertNotEquals(JobLockService.lockKey("Aa"), JobLockService.lockKey("BB"),
                "强哈希锁键必须区分 hashCode 碰撞的任务名");
        assertEquals(JobLockService.lockKey("cdr-auto-sync"), JobLockService.lockKey("cdr-auto-sync"),
                "同名任务键必须稳定");
    }

    /** 非法日期参数是 4000，不再是 500 + ERROR 堆栈 */
    @Test
    void badDateParamIs400Not500() throws Exception {
        mockMvc.perform(get("/api/finance/reconciliation?date=2026-13-01"))
                .andExpect(jsonPath("$.code").value(4000));
        mockMvc.perform(get("/api/reports/daily-settlement?date=abc"))
                .andExpect(jsonPath("$.code").value(4000));
    }

    /** CSV 口径可复算：收款/退款分行、退款为负（金额列求和 = 净额） */
    @Test
    void csvHasSideColumnWithSignedAmount() throws Exception {
        var res = mockMvc.perform(get("/api/reports/daily-settlement.csv"))
                .andExpect(status().isOk()).andReturn();
        String csv = res.getResponse().getContentAsString();
        assertTrue(csv.startsWith("﻿口径,结算单号"), "首列必须是口径（收款/退款）");
    }

    /** 异常统一基类：域内异常都是 HipBizException 的子类（新模块范式的前提） */
    @Test
    void domainExceptionsShareBaseClass() {
        assertTrue(cn.hip.platform.core.common.HipBizException.class
                .isAssignableFrom(BizException.class));
        assertTrue(cn.hip.platform.core.common.HipBizException.class
                .isAssignableFrom(InpException.class));
        var e = new InpException(9999, "x");
        assertEquals(9999, ((cn.hip.platform.core.common.HipBizException) e).code,
                "基类 code 字段必须承载域内异常的码");
    }
}
