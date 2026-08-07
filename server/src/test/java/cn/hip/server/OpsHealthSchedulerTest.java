package cn.hip.server;

import cn.hip.server.ops.OpsHealthScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.0.5：自动巡检——异常开工单、同题未处理不重复、开关可关 */
@SpringBootTest
@Transactional
class OpsHealthSchedulerTest {

    @Autowired OpsHealthScheduler scheduler;
    @Autowired JdbcTemplate jdbc;

    private static final String TITLE = "[自动巡检] 24 小时内无成功备份";

    private Integer openTickets() {
        return jdbc.queryForObject(
                "select count(*) from ops_fault_ticket where title = ? and status = 'OPEN'", Integer.class, TITLE);
    }

    @Test
    void firesOnceAndDeduplicates() {
        jdbc.update("delete from ops_backup_log");
        jdbc.update("delete from ops_fault_ticket where title = ?", TITLE);

        scheduler.hourlyHealthCheck();
        assertEquals(1, openTickets(), "无备份记录应自动开单");

        scheduler.hourlyHealthCheck();
        assertEquals(1, openTickets(), "同题未处理不应重复开单");
    }

    @Test
    void disabledByConfig() {
        jdbc.update("delete from ops_backup_log");
        jdbc.update("delete from ops_fault_ticket where title = ?", TITLE);
        jdbc.update("update sys_config set cfg_value = '0' where cfg_key = 'ops_auto_health_enabled'");

        scheduler.hourlyHealthCheck();
        assertEquals(0, openTickets(), "开关关闭不应开单");
    }
}
