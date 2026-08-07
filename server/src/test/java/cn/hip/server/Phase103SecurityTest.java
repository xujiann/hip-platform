package cn.hip.server;

import cn.hip.cdr.entity.CdrDocument;
import cn.hip.cdr.repository.CdrDocumentRepository;
import cn.hip.cdr.service.CdrSyncService;
import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 1.0.3 安全加固批回归：身份证校验位 / CDA 角色与脱敏与转义 / 报表引擎白名单 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase103SecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired SysUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PatientService patientService;
    @Autowired CdrSyncService cdrSyncService;
    @Autowired CdrDocumentRepository cdrDocumentRepository;

    private String adminToken() {
        return jwtService.issue("admin");
    }

    // ---- 身份证校验位（GB 11643） ----

    @Test
    void idCardChecksumAlgorithm() {
        assertTrue(PatientService.idCardChecksumOk("510181198812150515"));
        assertTrue(PatientService.idCardChecksumOk("11010519491231002X"));
        assertTrue(PatientService.idCardChecksumOk("11010519491231002x")); // 小写 x 等价
        assertFalse(PatientService.idCardChecksumOk("510181198812150512")); // 校验位错
        assertFalse(PatientService.idCardChecksumOk("51018119881215051"));  // 17 位
        assertFalse(PatientService.idCardChecksumOk(null));
    }

    @Test
    void registerRejectsBadChecksumAndAcceptsValid() throws Exception {
        mockMvc.perform(post("/api/patients")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"校验测试\",\"idType\":\"ID_CARD\",\"idNo\":\"510181198812150512\"}"))
                .andExpect(jsonPath("$.code").value(2003));

        mockMvc.perform(post("/api/patients")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"校验测试\",\"idType\":\"ID_CARD\",\"idNo\":\"510181198812150515\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sex").value("M"));
    }

    // ---- CDA：角色限制 + 证件号脱敏 + XML 转义 ----

    private Long cdaDocFor(String patientName, String idNo, String title, String content) {
        Patient p = new Patient();
        p.setName(patientName);
        p.setSex("M");
        p.setIdType("ID_CARD");
        p.setIdNo(idNo);
        Long pid = patientService.register(p).getId();
        CdrDocument doc = new CdrDocument();
        doc.setPatientId(pid);
        doc.setDocType("OUTP_ENCOUNTER");
        doc.setRefId(-System.nanoTime());
        doc.setTitle(title);
        doc.setDocTime(Instant.now());
        doc.setContent(content);
        return cdrDocumentRepository.save(doc).getId();
    }

    @Test
    void cdaRequiresPrivilegedRole() throws Exception {
        Long docId = cdaDocFor("角色测试", "510181198812150515", "t", "c");
        SysUser u = new SysUser();
        u.setUsername("norole103");
        u.setPassword(passwordEncoder.encode("Abcd1234"));
        u.setRealName("无角色");
        userRepository.save(u);
        mockMvc.perform(get("/api/cdr/documents/" + docId + "/cda")
                        .header("Authorization", "Bearer " + jwtService.issue("norole103")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/cdr/documents/" + docId + "/cda")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void cdaEscapesXmlAndMasksIdNo() {
        Long docId = cdaDocFor("转义<测&试>", "510181198812150515",
                "标题<script>&\"注入\"", "内容 ]]> 提前闭合尝试");
        String masked = cdrSyncService.toCda(docId, false);
        assertFalse(masked.contains("510181198812150515"), "非 ADMIN 不应见明文证件号");
        assertTrue(masked.contains("5101***********515"));
        assertTrue(masked.contains("标题&lt;script&gt;&amp;&quot;注入&quot;"));
        assertTrue(masked.contains("转义&lt;测&amp;试&gt;"));
        assertFalse(masked.contains("内容 ]]> 提前闭合尝试"), "CDATA 内 ]]&gt; 应被拆段");

        String plain = cdrSyncService.toCda(docId, true);
        assertTrue(plain.contains("510181198812150515"), "ADMIN 应见明文证件号");
    }

    // ---- 报表引擎：词边界 + 白名单 + 敏感对象 ----

    private void assertReportValidation(String sql, boolean shouldPass) throws Exception {
        mockMvc.perform(post("/api/datagov/reports")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"校验\",\"sqlText\":" +
                                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sql) + "}"))
                .andExpect(jsonPath("$.code").value(shouldPass ? 0 : 4634));
    }

    @Test
    void reportWhitelistRules() throws Exception {
        // 词边界：updated_at 不再被 update 子串误伤
        assertReportValidation("select id, created_at as updated_at from empi_patient", true);
        // 业务表 + sys_dept 精确放行
        assertReportValidation("select d.name from sys_dept d join outp_registration r on r.dept_id = d.id", true);
        // sys_user 任意位置出现即拒（含逗号连接别名绕过）
        assertReportValidation("select * from sys_user", false);
        assertReportValidation("select u.password from sys_dept d, sys_user u", false);
        // 系统目录与迁移史
        assertReportValidation("select * from pg_tables", false);
        assertReportValidation("select * from flyway_schema_history", false);
        // 白名单外表
        assertReportValidation("select * from unknown_table", false);
        // 写操作与分号
        assertReportValidation("delete from outp_order", false);
        assertReportValidation("select 1; drop table outp_order", false);
    }
}
