package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real JWT requests and committed PostgreSQL state; no test transaction can hide a partial write. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(V160EmrLegacyCreateAtomicityTest.GrantFaultConfiguration.class)
class V160EmrLegacyCreateAtomicityTest {
    private static final String CREATE = "/api/emr-templates";
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    private record Staff(Long id, String username, String token) {}
    private record Reply(int status, JsonNode body) {}
    private String prefix;
    private Long ownDept, otherDept;
    private Staff actor, priorOwner;

    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        prefix = "V160" + Long.toUnsignedString(System.nanoTime(), 36);
        ownDept = department("own");
        otherDept = department("other");
        priorOwner = employee("prior", "ADMIN");
        Long priorTemplate = jdbc.queryForObject("""
                insert into emr_template(dept_id,name,content,template_type,scope,owner_id,created_by)
                values (?,?,?,'EMR','DEPT',?,?) returning id
                """, Long.class, otherDept, prefix + "原有模板", "原有正文", priorOwner.id(), priorOwner.id());
        assertEquals(1, jdbc.update("""
                insert into emr_template_grant(template_id,grantee_type,grantee_id,granted_by)
                values (?,'DEPT',?,?)
                """, priorTemplate, otherDept, priorOwner.id()));
    }

    private Long department(String suffix) {
        return jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, prefix + suffix, prefix + suffix);
    }

    private Staff employee(String suffix, String role) {
        String name = prefix + suffix;
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, name, name, ownDept);
        assertEquals(1, jdbc.update("""
                insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?
                """, id, role));
        return new Staff(id, name, jwt.issue(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN"})
    void failedAutomaticGrantRollsBackRealHeaderAndOneRetryCreatesCompleteTemplate(String role) throws Exception {
        actor = employee("actor", role);
        var body = request(ownDept, prefix + "失败后恢复", "真实头写入后授权失败", "CONSENT");
        var before = templates();
        var clinicalBefore = clinicalRows();
        var auditBefore = allAuditRows();
        Probe fault = new Probe();
        Reply failed;
        Probe.ARMED.set(fault);
        try {
            failed = call(body);
        } finally {
            Probe.ARMED.remove();
        }
        assertEquals(200, failed.status(), failed.body().toString());
        assertEquals(4091, failed.body().path("code").asInt(), failed.body().toString());
        assertTrue(failed.body().path("data").isNull());
        assertEquals(1, fault.grantAttempts, "The single automatic grant must reach the actual database");
        assertEquals("23502", fault.sqlState, "PostgreSQL must reject the real grant INSERT, not a Java mock");
        assertNotNull(fault.autoCommit);
        assertNotNull(fault.backendPid);
        assertNotNull(fault.headerId);
        JsonNode inserted = mapper.readTree(fault.headerJson);
        assertEquals(fault.headerId.longValue(), inserted.path("id").longValue());
        assertEquals(actor.id().longValue(), inserted.path("owner_id").longValue());
        assertEquals(ownDept.longValue(), inserted.path("dept_id").longValue());
        assertEquals(body.get("name"), inserted.path("name").textValue());
        assertEquals(body.get("content"), inserted.path("content").textValue());
        assertEquals("DEPT", inserted.path("scope").textValue());
        assertEquals("CONSENT", inserted.path("template_type").textValue());
        assertEquals(actor.id().longValue(), inserted.path("created_by").longValue());
        assertTrue(inserted.path("enabled").booleanValue());
        assertFalse(inserted.path("is_default").booleanValue());
        assertTrue(inserted.path("record_type").isNull());
        assertFalse(inserted.path("created_at").isNull());
        System.out.printf("V160 PG_GRANT_FAILURE role=%s header=%d backend=%d autoCommit=%s sqlState=%s%n",
                role, fault.headerId, fault.backendPid, fault.autoCommit, fault.sqlState);
        assertEquals(before, templates(), "A failed automatic grant must not publish a partial template");
        assertEquals(clinicalBefore, clinicalRows());
        assertAudit(auditBefore, 1);

        assertSuccess(call(body));
        assertCreated(ownDept, (String) body.get("name"), (String) body.get("content"), "CONSENT", before);
        assertEquals(clinicalBefore, clinicalRows());
        assertAudit(auditBefore, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN"})
    void legitimateMaintenanceDutiesKeepDepartmentCreationAndAutomaticGrant(String role) throws Exception {
        actor = employee("actor", role);
        var before = templates();
        var clinicalBefore = clinicalRows();
        var auditBefore = allAuditRows();
        String name = prefix + "合法" + role;
        assertSuccess(call(request(ownDept, name, "合法科室模板", "EMR")));
        assertCreated(ownDept, name, "合法科室模板", "EMR", before);
        assertEquals(clinicalBefore, clinicalRows());
        assertAudit(auditBefore, 1);
    }

    @Test
    void administratorKeepsHospitalCreationWithoutSyntheticGrant() throws Exception {
        actor = employee("actor", "ADMIN");
        var before = templates();
        var clinicalBefore = clinicalRows();
        var auditBefore = allAuditRows();
        String name = prefix + "全院";
        assertSuccess(call(request(null, name, "全院正文", "RIS")));
        assertCreated(null, name, "全院正文", "RIS", before);
        assertEquals(clinicalBefore, clinicalRows());
        assertAudit(auditBefore, 1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {" \t ", " ris "})
    void frozenFourFieldInputKeepsBlankNameContentAndOriginalTypeRule(String type) throws Exception {
        actor = employee("actor", "QUALITY");
        var before = templates();
        var auditBefore = allAuditRows();
        assertSuccess(call(request(ownDept, "", "", type)));
        assertCreated(ownDept, "", "", type == null || type.isBlank() ? "EMR" : type, before);
        assertAudit(auditBefore, 1);
    }

    private Map<String, Object> request(Long department, String name, String content, String type) {
        var body = new LinkedHashMap<String, Object>();
        body.put("deptId", department);
        body.put("name", name);
        body.put("content", content);
        body.put("templateType", type);
        assertEquals(4, body.size());
        return body;
    }

    private Reply call(Object body) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var response = mvc.perform(post(CREATE).contentType("application/json")
                    .header("Authorization", "Bearer " + actor.token())
                    .content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
            JsonNode envelope = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            var fields = new java.util.HashSet<String>();
            envelope.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("code", "message", "data"), fields);
            return new Reply(response.getStatus(), envelope);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void assertSuccess(Reply reply) {
        assertEquals(200, reply.status(), reply.body().toString());
        assertEquals(0, reply.body().path("code").asInt(), reply.body().toString());
        assertTrue(reply.body().path("data").isNull(), "The frozen legacy endpoint returns no template identifier");
    }

    private Map<String, List<String>> templates() {
        return Map.of("headers", jdbc.queryForList("select to_jsonb(t)::text from emr_template t order by id", String.class),
                "grants", jdbc.queryForList("select to_jsonb(g)::text from emr_template_grant g order by id", String.class));
    }

    private Map<String, List<String>> clinicalRows() {
        return Map.of("outpatient", jdbc.queryForList("select to_jsonb(e)::text from outp_emr e order by id", String.class),
                "inpatient", jdbc.queryForList("select to_jsonb(r)::text from inp_medical_record r order by id", String.class));
    }

    private List<String> allAuditRows() {
        return jdbc.queryForList("select to_jsonb(a)::text from sys_audit_log a order by id", String.class);
    }

    private void assertCreated(Long department, String name, String content, String type,
                               Map<String, List<String>> before) {
        var headers = jdbc.queryForList("select * from emr_template where owner_id=?", actor.id());
        assertEquals(1, headers.size());
        var header = new LinkedHashMap<>(headers.getFirst());
        Long id = (Long) header.remove("id");
        assertNotNull(header.remove("created_at"));
        var expected = new LinkedHashMap<String, Object>();
        expected.put("dept_id", department);
        expected.put("name", name);
        expected.put("content", content);
        expected.put("template_type", type);
        expected.put("enabled", true);
        expected.put("scope", department == null ? "HOSPITAL" : "DEPT");
        expected.put("owner_id", actor.id());
        expected.put("record_type", null);
        expected.put("is_default", false);
        expected.put("created_by", actor.id());
        assertEquals(expected, header);
        var grants = jdbc.queryForList("select * from emr_template_grant where template_id=?", id);
        assertEquals(department == null ? 0 : 1, grants.size());
        if (department != null) {
            var grant = new LinkedHashMap<>(grants.getFirst());
            assertNotNull(grant.remove("id"));
            assertNotNull(grant.remove("granted_at"));
            assertEquals(Map.of("template_id", id, "grantee_type", "DEPT", "grantee_id", department,
                    "granted_by", actor.id()), grant);
        }
        assertEquals(before.get("headers"), jdbc.queryForList(
                "select to_jsonb(t)::text from emr_template t where id<>? order by id", String.class, id));
        assertEquals(before.get("grants"), jdbc.queryForList(
                "select to_jsonb(g)::text from emr_template_grant g where template_id<>? order by id", String.class, id));
    }

    private void assertAudit(List<String> before, int requests) {
        var rows = jdbc.queryForList("""
                select id,username,method,path,http_status,client_ip,created_at from sys_audit_log
                where username=? order by id
                """, actor.username());
        assertEquals(requests, rows.size());
        for (var row : rows) {
            assertEquals(Set.of("id", "username", "method", "path", "http_status", "client_ip", "created_at"), row.keySet());
            assertNotNull(row.get("id"));
            assertNotNull(row.get("created_at"));
            assertEquals(actor.username(), row.get("username"));
            assertEquals("POST", row.get("method"));
            assertEquals(CREATE, row.get("path"));
            assertEquals(200, row.get("http_status"));
            assertEquals("127.0.0.1", row.get("client_ip"));
        }
        assertEquals(before, jdbc.queryForList("""
                select to_jsonb(a)::text from sys_audit_log a where username is distinct from ? order by id
                """, String.class, actor.username()));
    }

    static final class Probe {
        static final ThreadLocal<Probe> ARMED = new ThreadLocal<>();
        int grantAttempts;
        Long headerId;
        Integer backendPid;
        Boolean autoCommit;
        String headerJson, sqlState;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GrantFaultConfiguration {
        @Bean @Primary
        JdbcTemplate grantFaultJdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource) {
                @Override
                public int update(String sql, Object... arguments) {
                    Probe probe = Probe.ARMED.get();
                    String normalized = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
                    if (probe == null || !normalized.startsWith("insert into emr_template_grant(")) {
                        return super.update(sql, arguments);
                    }
                    assertEquals(1, ++probe.grantAttempts, "Only the legacy request's automatic grant is armed");
                    assertEquals(3, arguments.length);
                    probe.headerId = ((Number) arguments[0]).longValue();
                    return super.execute((ConnectionCallback<Integer>) connection -> {
                        probe.autoCommit = connection.getAutoCommit();
                        try (var statement = connection.prepareStatement("select pg_backend_pid()" );
                             var result = statement.executeQuery()) {
                            assertTrue(result.next());
                            probe.backendPid = result.getInt(1);
                        }
                        try (var statement = connection.prepareStatement("select to_jsonb(t)::text from emr_template t where id=?")) {
                            statement.setLong(1, probe.headerId);
                            try (var result = statement.executeQuery()) {
                                assertTrue(result.next(), "The actual template INSERT must have completed before the grant fails");
                                probe.headerJson = result.getString(1);
                                assertFalse(result.next());
                            }
                        }
                        // The same actual connection observes the inserted header and executes the
                        // original grant SQL. A single test-only null binding forces real PG23502;
                        // neither a mocked update result nor an exception thrown in Java can pass.
                        try (var statement = connection.prepareStatement(sql)) {
                            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
                            statement.setNull(1, Types.BIGINT);
                            try {
                                return statement.executeUpdate();
                            } catch (SQLException databaseFailure) {
                                probe.sqlState = databaseFailure.getSQLState();
                                throw databaseFailure;
                            }
                        }
                    });
                }
            };
        }
    }

    @AfterEach
    void cleanUp() {
        Probe.ARMED.remove();
        SecurityContextHolder.clearContext();
        for (Staff staff : Arrays.asList(actor, priorOwner)) {
            if (staff == null) continue;
            jdbc.update("delete from emr_template_grant where template_id in (select id from emr_template where owner_id=?)", staff.id());
            jdbc.update("delete from emr_template where owner_id=?", staff.id());
            jdbc.update("delete from sys_audit_log where username=?", staff.username());
            jdbc.update("delete from sys_user_role where user_id=?", staff.id());
            jdbc.update("delete from sys_user where id=?", staff.id());
        }
        jdbc.update("delete from sys_dept where id in (?,?)", ownDept, otherDept);
    }
}
