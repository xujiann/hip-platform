package cn.hip.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.2.14 上线检查单 P2：巡检扩面（连接池/集成积压/堆内存）+ 告警外发 webhook + 大表按月分区。
 * 队列阈值降到 3 便于用少量数据触发（默认 100）。
 */
@SpringBootTest(properties = "hip.ops.queue-error-threshold=3")
@AutoConfigureMockMvc
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase130OpsMonitorPartitionTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired cn.hip.server.ops.OpsHealthScheduler scheduler;

    /** 集成报文近 1h ERROR 超阈值 → 开单；同题不重复开（幂等） */
    @Test
    void integrationBacklogOpensTicketAndIsIdempotent() {
        jdbc.update("update ops_fault_ticket set status='RESOLVED' where title like '[自动巡检]%'");
        // 阈值 3，插 4 条近 1h 的 ERROR 报文
        for (int i = 0; i < 4; i++) {
            jdbc.update("""
                    insert into int_message_log(direction, channel, payload, status, created_at)
                    values ('IN','LIS','{"t":%d}','ERROR', now() - interval '10 minutes')
                    """.formatted(i));
        }
        scheduler.runInspection();
        String title = "[自动巡检] 集成报文近 1 小时错误积压";
        Integer open1 = jdbc.queryForObject(
                "select count(*) from ops_fault_ticket where status='OPEN' and title=?", Integer.class, title);
        assertEquals(1, open1, "集成积压必须开出 1 张工单");

        scheduler.runInspection();
        Integer open2 = jdbc.queryForObject(
                "select count(*) from ops_fault_ticket where status='OPEN' and title=?", Integer.class, title);
        assertEquals(1, open2, "同题未处理不得重复开单");
    }

    /** 配置了 webhook 且命中 HIGH 工单 → POST 到该 webhook（钉钉/企业微信 text 格式，含标题+级别） */
    @Test
    void webhookDispatchedForHighTicket() throws Exception {
        var received = Collections.synchronizedList(new ArrayList<String>());
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", ex -> {
            received.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] ok = "{\"errcode\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
            jdbc.update("""
                    insert into sys_config(cfg_key,cfg_value,remark) values('ops_alert_webhook',?, 'test')
                    on conflict (cfg_key) do update set cfg_value = excluded.cfg_value
                    """, url);
            // 构造必现的 HIGH：清近期备份 + 复位同题工单
            jdbc.update("update ops_fault_ticket set status='RESOLVED' where title like '[自动巡检]%'");
            jdbc.update("delete from ops_backup_log where created_at > now() - interval '24 hours'");

            scheduler.runInspection();

            assertFalse(received.isEmpty(), "配置 webhook 后 HIGH 工单必须外发");
            boolean hit = received.stream().anyMatch(b -> b.contains("HIGH") && b.contains("自动巡检告警"));
            assertTrue(hit, "外发 body 应含级别 HIGH 与告警标记，实际=" + received);
        } finally {
            server.stop(0);
        }
    }

    /** 未配置 webhook（空值）→ 保持原行为：仍正常开单、不外发、不抛异常（向后兼容） */
    @Test
    void noWebhookConfiguredIsBackwardCompatible() {
        jdbc.update("""
                insert into sys_config(cfg_key,cfg_value,remark) values('ops_alert_webhook','', 'test')
                on conflict (cfg_key) do update set cfg_value = ''
                """);
        jdbc.update("update ops_fault_ticket set status='RESOLVED' where title like '[自动巡检]%'");
        jdbc.update("delete from ops_backup_log where created_at > now() - interval '24 hours'");

        int opened = scheduler.runInspection();
        assertTrue(opened >= 1, "无 webhook 时巡检仍应正常开单");
        Integer high = jdbc.queryForObject("""
                select count(*) from ops_fault_ticket
                where status='OPEN' and title='[自动巡检] 24 小时内无成功备份'
                """, Integer.class);
        assertEquals(1, high, "无备份 HIGH 工单应照常开出");
    }

    /** 三张观测/审计表已改为分区表；归档函数在有存量的库上可执行；分区表仍可正常写入 */
    @Test
    void observabilityTablesArePartitionedAndPurgeRuns() {
        for (String t : List.of("sys_audit_log", "int_message_log", "ops_slow_api")) {
            String kind = jdbc.queryForObject(
                    "select relkind::text from pg_class where relname = ?", String.class, t);
            assertEquals("p", kind, t + " 应为分区表(relkind=p)");
        }
        // 归档函数：维护未来分区 + DETACH/DROP 过期分区，在 hip_test（有数据）上应能执行
        assertDoesNotThrow(() -> jdbc.execute("select hip_purge_observability()"));
        // 分区表写入路由到当月分区
        jdbc.update("insert into ops_slow_api(method, path, cost_ms) values ('GET','/part-smoke',123)");
        Integer n = jdbc.queryForObject(
                "select count(*) from ops_slow_api where path='/part-smoke'", Integer.class);
        assertEquals(1, n, "分区表应可正常写入并读回");
    }
}
