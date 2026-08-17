package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.integration.mllp.MllpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 1.1.4 C 批回归：404/405 语义、days 上限、分享上限与吊销、INTERFACE 角色、报表关键字、MSH-18 字符集、敏感读审计 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase114EngineeringTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private String tokenFor(String username, String roleCode) {
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values (?, '$2a$10$abcdefghijklmnopqrstuv', ?, true)
                on conflict (username) do nothing
                """, username, username);
        jdbc.update("""
                insert into sys_user_role(user_id, role_id)
                select u.id, r.id from sys_user u, sys_role r
                where u.username = ? and r.code = ? on conflict do nothing
                """, username, roleCode);
        return "Bearer " + jwtService.issue(username);
    }

    /** B-8：拼错的 URL 是 404，不是 500 + ERROR 堆栈（扫描器一次探测就污染错误告警） */
    @Test
    void unknownPathIs404Not500() throws Exception {
        String admin = tokenFor("eng114admin", "ADMIN");
        mockMvc.perform(get("/api/no-such-endpoint-114").header("Authorization", admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4041));
        // 405 同理
        mockMvc.perform(get("/api/auth/login").header("Authorization", admin))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(4050));
    }

    /** B-17：days 无上限曾可让 generate_series 生成上亿行 */
    @Test
    void statsDailyCapsDays() throws Exception {
        String admin = tokenFor("eng114admin2", "ADMIN");
        mockMvc.perform(get("/api/stats/daily?days=100000000").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(4001));
        mockMvc.perform(get("/api/stats/daily?days=7").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** B-10：百年外链被拒；吊销后链接立即失效 */
    @Test
    void shareLinkCapAndRevoke() throws Exception {
        String admin = tokenFor("eng114admin3", "ADMIN");
        mockMvc.perform(post("/api/ris/exams/1/share?expireMinutes=52560000").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(4663));

        // 造一条已审核报告的分享，再吊销
        var exams = jdbc.queryForList("select id from ris_exam where status = 'VERIFIED' limit 1", Long.class);
        org.junit.jupiter.api.Assumptions.assumeFalse(exams.isEmpty(), "库中无已审核报告，跳过吊销链路");
        Long examId = exams.get(0);
        var mvcRes = mockMvc.perform(post("/api/ris/exams/" + examId + "/share").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
                mvcRes.getResponse().getContentAsString(), "$.data.token");
        mockMvc.perform(get("/api/share/" + token)).andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(delete("/api/ris/exams/" + examId + "/share").header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/share/" + token))
                .andExpect(jsonPath("$.code").value(4662));   // 已过期
    }

    /** B-16：INTERFACE 角色可进 HL7 端点但进不了任何业务端点 */
    @Test
    void interfaceRoleIsMachineOnly() throws Exception {
        String iface = tokenFor("lis-gateway114", "INTERFACE");
        mockMvc.perform(post("/api/integration/hl7/oru").contentType("text/plain")
                        .content("MSH|^~\\&|LIS|LAB|HIP|HOSP|20260817||ORU^R01|X|P|2.5\r")
                        .header("Authorization", iface))
                .andExpect(status().isOk());   // 进得来（业务码可能非 0，但不是 403）
        mockMvc.perform(get("/api/patients?keyword=x").header("Authorization", iface))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/hr/salaries?empNo=E001").header("Authorization", iface))
                .andExpect(status().isForbidden());
    }

    /** B-11：事务关键字被词边界拦截（一旦执行会脱掉 set local role 沙箱） */
    @Test
    void reportEngineRejectsTransactionKeywords() throws Exception {
        String admin = tokenFor("eng114admin4", "ADMIN");
        for (String sql : new String[]{
                "select 1 as a from outp_charge commit",
                "select 1 as a from outp_charge where rollback is null",
                "select begin from outp_charge"}) {
            mockMvc.perform(post("/api/datagov/reports").contentType("application/json")
                            .content("{\"name\":\"t\",\"sqlText\":\"" + sql + "\"}")
                            .header("Authorization", admin))
                    .andExpect(jsonPath("$.code").value(4634));
        }
    }

    /** P2：MSH-18 声明 GBK/GB18030 时按声明解码（硬编码 UTF-8 曾让中文结果乱码入库） */
    @Test
    void mllpCharsetFollowsMsh18() {
        String gbkMsg = "MSH|^~\\&|LIS|LAB|HIP|HOSP|20260817||ORU^R01|1|P|2.5||||||GBK\rOBX|1|ST|WBC^白细胞||10.2\r";
        Charset cs = MllpServer.declaredCharset(gbkMsg.getBytes(Charset.forName("GBK")));
        assertEquals(Charset.forName("GB18030"), cs);
        // 未声明 → UTF-8
        String plain = "MSH|^~\\&|LIS|LAB|HIP|HOSP|20260817||ORU^R01|1|P|2.5\r";
        assertEquals(StandardCharsets.UTF_8, MllpServer.declaredCharset(plain.getBytes(StandardCharsets.UTF_8)));
        // GBK 字节以声明字符集往返不失真
        byte[] bytes = gbkMsg.getBytes(Charset.forName("GBK"));
        assertTrue(new String(bytes, MllpServer.declaredCharset(bytes)).contains("白细胞"),
                "GBK 报文按 MSH-18 解码后中文完好");
    }

    /** B-15：敏感读（GET 患者检索）须留审计痕 */
    @Test
    void sensitiveReadIsAudited() throws Exception {
        String admin = tokenFor("eng114admin5", "ADMIN");
        mockMvc.perform(get("/api/patients?keyword=张").header("Authorization", admin))
                .andExpect(status().isOk());
        Integer n = jdbc.queryForObject("""
                select count(*) from sys_audit_log
                where username = 'eng114admin5' and method = 'GET' and path like '/api/patients%'
                """, Integer.class);
        assertTrue(n != null && n >= 1, "患者检索是敏感读，必须留痕（修复前只审计写操作）");
        // 1.2.0：敏感读要记"查的是谁"——query string 必须入痕
        Integer withQs = jdbc.queryForObject("""
                select count(*) from sys_audit_log
                where username = 'eng114admin5' and path like '/api/patients?keyword=%'
                """, Integer.class);
        assertTrue(withQs != null && withQs >= 1, "审计必须包含查询串（此前只剩路径，'查了谁'丢失）");
    }
}
