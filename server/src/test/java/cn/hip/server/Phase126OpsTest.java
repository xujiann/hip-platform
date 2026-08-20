package cn.hip.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 1.2.6 v24-A：巡检手动触发端点——运行态验证的自动化覆盖 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase126OpsTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired cn.hip.server.ops.OpsHealthScheduler scheduler;

    /** 注入"无备份"故障 → 巡检必须开单；再跑一次不得重复开（同题幂等） */
    @Test
    void inspectionOpensTicketAndIsIdempotent() {
        // 清掉可能存在的同题 OPEN 单与近期备份记录，构造"24 小时内无成功备份"
        jdbc.update("update ops_fault_ticket set status = 'RESOLVED' where title like '[自动巡检]%'");
        jdbc.update("delete from ops_backup_log where created_at > now() - interval '24 hours'");

        int first = scheduler.runInspection();
        assertTrue(first >= 1, "无备份故障必须开出工单，实际开单数=" + first);
        Integer open = jdbc.queryForObject("""
                select count(*) from ops_fault_ticket
                where status = 'OPEN' and title = '[自动巡检] 24 小时内无成功备份'
                """, Integer.class);
        assertEquals(1, open, "同题工单应为 1 张");

        int second = scheduler.runInspection();
        assertEquals(0, second, "同题未处理时不得重复开单（否则运维被刷屏）");
    }

    /** 手动触发端点：返回本轮开单数与可读提示（运维当场验证巡检是否在工作） */
    @Test
    void manualTriggerEndpointReturnsCount() throws Exception {
        mockMvc.perform(post("/api/ops/inspections/run"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.openedTickets").exists())
                .andExpect(jsonPath("$.data.hint").exists());
    }
}
