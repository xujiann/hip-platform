package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.core.service.JobLockService;
import cn.hip.platform.integration.mllp.MllpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 1.1.2 A 批回归：每条都验证失败路径，不只验证正常路径（第二轮审阅教训） */
@SpringBootTest
@AutoConfigureMockMvc
class Phase112PilotBlockerTest {

    @Autowired JobLockService jobLock;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource ds;
    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;

    private int advisoryLocks() {
        Integer n = jdbc.queryForObject(
                "select count(*) from pg_locks where locktype = 'advisory'", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * A-1 失败路径：任务体把连接池里的连接借走（真实任务必然如此）。
     * 修复前：加锁/解锁各借一条连接，解锁必失败且返回值被丢弃 → 锁泄漏 →
     * 下一次同名任务被误判为"其他实例正在执行"而静默跳过。
     */
    @Test
    void jobLockSurvivesConnectionChurn() throws Exception {
        List<Connection> held = new ArrayList<>();
        boolean first = jobLock.runExclusively("test-churn", () -> {
            try {
                held.add(ds.getConnection());
                held.add(ds.getConnection());
                // 任务体内的正常 SQL 也应工作（它们各借各的连接）
                jdbc.queryForObject("select 1", Integer.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        for (Connection c : held) c.close();
        assertTrue(first, "首次执行应取到锁");
        assertEquals(0, advisoryLocks(), "任务结束后 advisory 锁必须全部释放（泄漏即任务永久停摆）");
        assertTrue(jobLock.runExclusively("test-churn", () -> {}),
                "同名任务必须能再次执行——修复前这里被误判为其他实例正在执行");
    }

    /** A-1 异常路径：任务体抛异常，锁同样必须释放 */
    @Test
    void jobLockReleasedOnBodyFailure() {
        assertThrows(RuntimeException.class,
                () -> jobLock.runExclusively("test-fail", () -> { throw new RuntimeException("boom"); }));
        assertEquals(0, advisoryLocks(), "任务体异常后锁必须释放");
        assertTrue(jobLock.runExclusively("test-fail", () -> {}), "异常后同名任务必须能再次执行");
    }

    /** A-4 失败路径①：未认证请求（过滤器级 401）必须留审计痕 */
    @Test
    @Transactional
    void unauthenticatedRequestIsAudited() throws Exception {
        mockMvc.perform(post("/api/patients").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        Integer n = jdbc.queryForObject("""
                select count(*) from sys_audit_log
                where path = '/api/patients' and http_status = 401 and username is null
                """, Integer.class);
        assertTrue(n != null && n >= 1, "401 必须入审计（修复前安全链在审计过滤器之前，走不到它）");
    }

    /** A-4 失败路径②：已认证但无权（方法级 403）必须留痕，且带用户名、恰好一行（不重复记） */
    @Test
    @Transactional
    void forbiddenRequestIsAuditedOnceWithUsername() throws Exception {
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values ('audit112', '$2a$10$abcdefghijklmnopqrstuv', 'audit112', true)
                on conflict (username) do nothing
                """);
        jdbc.update("""
                insert into sys_user_role(user_id, role_id)
                select u.id, r.id from sys_user u, sys_role r
                where u.username = 'audit112' and r.code = 'NURSE'
                on conflict do nothing
                """);
        String token = "Bearer " + jwtService.issue("audit112");
        // GET 也必须留痕：被拒绝的读尝试正是"谁试图翻谁的病历"要回答的问题
        mockMvc.perform(get("/api/hr/salaries?empNo=E001").header("Authorization", token))
                .andExpect(status().isForbidden());
        Integer n = jdbc.queryForObject("""
                select count(*) from sys_audit_log
                where path = '/api/hr/salaries' and http_status = 403 and username = 'audit112'
                """, Integer.class);
        assertEquals(1, n, "403 必须恰好留一行审计（0=没记，2=过滤器与 handler 重复记）");
    }

    /** A-3 失败路径：超长帧必须回 AE 并断连，而不是无限吃堆（测试 profile 关 MLLP，自建实例） */
    @Test
    void mllpOversizedFrameRejected() throws Exception {
        var smokeEnv = new org.springframework.mock.env.MockEnvironment();
        smokeEnv.setActiveProfiles("dev");   // fail-closed 后空 profile=生产会拒启动，冒烟用显式 dev
        var server = new MllpServer(null, smokeEnv);
        org.springframework.test.util.ReflectionTestUtils.setField(server, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(server, "port", 25998);
        org.springframework.test.util.ReflectionTestUtils.setField(server, "bindAddress", "127.0.0.1");
        org.springframework.test.util.ReflectionTestUtils.setField(server, "allowList", "");
        org.springframework.test.util.ReflectionTestUtils.setField(server, "maxFrame", 1024);
        server.start();
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), 25998)) {
            s.setSoTimeout(15000);
            OutputStream out = s.getOutputStream();
            out.write(0x0B);
            byte[] chunk = new byte[512];
            java.util.Arrays.fill(chunk, (byte) 'A');
            // 上限 1024：写 4×512 越限；服务端应中途回 AE 断连（后续写可能因对端关闭而失败，属预期）
            try {
                for (int i = 0; i < 4; i++) out.write(chunk);
                out.flush();
            } catch (java.io.IOException expectedOnServerClose) {
                // 服务端提前断连即防护生效
            }
            String reply = readAll(s.getInputStream());
            assertTrue(reply.contains("MSA|AE"), "超长帧应收到 AE 应答，实际：" + reply);
        } finally {
            server.stop();
        }
    }

    /**
     * A-2 生产闸门：pilot profile 下未配置白名单必须拒绝启动监听（fail closed）。
     * 教训：安全闸门必须有自动化覆盖——1.0.6 的两道闸门曾因无覆盖而从未在真实路径执行过。
     */
    @Test
    void mllpRefusesToStartInProductionWithoutAllowList() throws Exception {
        var env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("pilot");
        var gate = new MllpServer(null, env);   // 不会走到报文处理，oruProcessingService 可为 null
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "port", 25999);
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "bindAddress", "127.0.0.1");
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "allowList", "");
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "maxFrame", 1024);
        gate.start();
        assertThrows(java.io.IOException.class,
                () -> new Socket(InetAddress.getLoopbackAddress(), 25999).close(),
                "pilot 无白名单时 25999 不应有监听");
        // 同配置换 dev profile 则正常启动（闸门只作用于生产）
        var devEnv = new org.springframework.mock.env.MockEnvironment();
        devEnv.setActiveProfiles("dev");
        var dev = new MllpServer(null, devEnv);
        org.springframework.test.util.ReflectionTestUtils.setField(dev, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(dev, "port", 25999);
        org.springframework.test.util.ReflectionTestUtils.setField(dev, "bindAddress", "127.0.0.1");
        org.springframework.test.util.ReflectionTestUtils.setField(dev, "allowList", "");
        org.springframework.test.util.ReflectionTestUtils.setField(dev, "maxFrame", 1024);
        dev.start();
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), 25999)) {
            assertTrue(s.isConnected(), "dev profile 同配置应正常监听");
        } finally {
            dev.stop();
        }
    }

    /** A-2：白名单解析（精确 IP 与 CIDR），非法项必须拒绝启动配置 */
    @Test
    void mllpAllowListParsing() {
        assertEquals(0, MllpServer.parseAllowList("").size());
        assertEquals(2, MllpServer.parseAllowList("192.168.1.10, 10.0.0.0/8").size());
        long[] exact = MllpServer.parseAllowList("192.168.1.10").get(0);
        assertEquals(0xFFFFFFFFL, exact[1], "无掩码即精确匹配 /32");
        long[] cidr = MllpServer.parseAllowList("10.0.0.0/8").get(0);
        assertEquals(0xFF000000L, cidr[1]);
        assertThrows(IllegalArgumentException.class, () -> MllpServer.parseAllowList("not-an-ip"));
        assertThrows(IllegalArgumentException.class, () -> MllpServer.parseAllowList("10.0.0.0/abc"));
    }

    private static String readAll(InputStream in) throws Exception {
        var buf = new ByteArrayOutputStream();
        int b;
        try {
            while ((b = in.read()) != -1) {
                if (b == 0x1C) break;   // 帧尾
                buf.write(b);
            }
        } catch (java.io.IOException ignored) {
            // 对端断连即读到此为止
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
