package cn.hip.server;

import cn.hip.medtech.service.EmrTemplateService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Real JWT requests cross a committed staff change before their employee profile query. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(V159EmrScopedCreateActorTest.ActorJdbcConfiguration.class)
class V159EmrScopedCreateActorTest {
    private static final String CREATE = "/api/emr-templates/scoped";
    private static final List<String> DUTIES = List.of("ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN");

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired EmrTemplateService templates;

    private record Staff(Long id, String username, String token) {}
    private record Reply(int status, JsonNode body) {
        int code() { return body.path("code").asInt(); }
    }
    private enum Target { OMITTED, OLD_DEPARTMENT, NEW_DEPARTMENT }
    private record Scenario(String label, List<String> initialRoles, List<String> currentRoles,
                            boolean transfer, boolean disabled, String scope, Target target, int status, int code) {}

    private String prefix;
    private Long oldDept, newDept;
    private Staff actor, administrator;

    @BeforeEach
    void setUp() {
        prefix = "V159" + Long.toUnsignedString(System.nanoTime(), 36);
        oldDept = department("old");
        newDept = department("new");
        administrator = employee("control", List.of("ADMIN"));
    }

    private Long department(String suffix) {
        return jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, prefix + suffix, prefix + suffix);
    }

    private Staff employee(String suffix, List<String> roles) {
        String username = prefix + suffix;
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, username, username, oldDept);
        for (String role : roles) {
            assertEquals(1, jdbc.update("""
                    insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?
                    """, id, role));
        }
        return new Staff(id, username, jwt.issue(username));
    }

    private static Scenario scenario(String label, String initial, String current, boolean transfer,
                                     boolean disabled, String scope, Target target, int status, int code) {
        return new Scenario(label, List.of(initial), List.of(current), transfer, disabled, scope, target, status, code);
    }

    static Stream<Arguments> requests() {
        var cases = new ArrayList<Scenario>();
        for (String role : DUTIES) {
            cases.add(scenario("lost-duty-" + role, role, "CASHIER", false, false,
                    "PERSONAL", Target.OMITTED, 403, 1005));
            cases.add(scenario("legal-personal-" + role, role, role, false, false,
                    "PERSONAL", Target.OLD_DEPARTMENT, 200, 0));
            cases.add(scenario("legal-department-" + role, role, role, false, false,
                    "DEPT", Target.OMITTED, 200, 0));
        }
        for (String role : List.of("ADMIN", "QUALITY")) {
            cases.add(scenario("disabled-" + role, role, role, false, true,
                    "PERSONAL", Target.OMITTED, 403, 1005));
        }
        for (String scope : List.of("HOSPITAL", "GLOBAL")) {
            cases.add(scenario("downgrade-" + scope, "ADMIN", "QUALITY", false, false,
                    scope, Target.OMITTED, 200, 4066));
        }
        cases.add(scenario("downgrade-personal", "ADMIN", "QUALITY", false, false,
                "PERSONAL", Target.OMITTED, 200, 0));
        cases.add(scenario("downgrade-department", "ADMIN", "QUALITY", false, false,
                "DEPT", Target.OLD_DEPARTMENT, 200, 0));
        cases.add(scenario("transfer-omitted-department", "QUALITY", "QUALITY", true, false,
                "DEPT", Target.OMITTED, 200, 0));
        cases.add(scenario("transfer-old-department-denied", "QUALITY", "QUALITY", true, false,
                "DEPT", Target.OLD_DEPARTMENT, 200, 4066));
        cases.add(scenario("transfer-new-department", "QUALITY", "QUALITY", true, false,
                "DEPT", Target.NEW_DEPARTMENT, 200, 0));
        cases.add(new Scenario("mixed-current-maintenance-duty", List.of("ADMIN"), List.of("CASHIER", "DOCTOR_OUTP"),
                false, false, "DEPT", Target.OMITTED, 200, 0));
        cases.add(new Scenario("mixed-admin-global", List.of("ADMIN", "CASHIER"), List.of("ADMIN", "CASHIER"),
                false, false, "GLOBAL", Target.OMITTED, 200, 0));
        cases.add(scenario("admin-hospital", "ADMIN", "ADMIN", false, false,
                "HOSPITAL", Target.OMITTED, 200, 0));
        return cases.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void scopedCreationUsesOneCurrentEmployeeProfile(Scenario scenario) throws Exception {
        actor = employee("actor", scenario.initialRoles());
        Long requestedDepartment = switch (scenario.target()) {
            case OMITTED -> null;
            case OLD_DEPARTMENT -> oldDept;
            case NEW_DEPARTMENT -> newDept;
        };
        var body = request(prefix, prefix + "正文", scenario.scope(), requestedDepartment, "CONSENT", "PROGRESS");
        var before = allTemplateRows();
        var clinical = clinicalRows();
        var gap = new Gap();
        var worker = Executors.newSingleThreadExecutor();
        var pending = worker.submit(() -> {
            Gap.TRACE.set(gap);
            try {
                return call(post(CREATE), body, actor);
            } finally {
                Gap.TRACE.remove();
            }
        });

        Reply result;
        Long currentDepartment = scenario.transfer() ? newDept : oldDept;
        String staffPath = "/api/system/users/" + actor.id();
        try {
            gap.await();
            assertEquals(scenario.initialRoles().stream().map(role -> "ROLE_" + role).sorted().toList(), gap.authentication);
            assertEquals(List.of("BEFORE"), gap.positions);
            assertEquals(List.of(actor.id()), gap.parameters);
            if (gap.oldProfile) {
                assertEquals(List.of(oldDept), gap.oldDepartmentResult,
                        "The old two-query path must have already read the original department");
            } else {
                assertNull(gap.oldDepartmentResult, "The new path must read its profile in one statement");
            }
            Reply changed = scenario.disabled()
                    ? call(put(staffPath + "/enabled").queryParam("enabled", "false"), null, administrator)
                    : call(put(staffPath), Map.of("username", actor.username(), "realName", actor.username(),
                            "deptId", currentDepartment, "roleCodes", scenario.currentRoles()), administrator);
            assertEquals(200, changed.status(), changed.body().toString());
            assertEquals(0, changed.code(), changed.body().toString());
            assertEquals(!scenario.disabled(), jdbc.queryForObject("select enabled from sys_user where id=?", Boolean.class, actor.id()));
            assertEquals(currentDepartment, jdbc.queryForObject("select dept_id from sys_user where id=?", Long.class, actor.id()));
            assertEquals(scenario.currentRoles().stream().sorted().toList(), jdbc.queryForList("""
                    select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=? order by r.code
                    """, String.class, actor.id()));
            assertFalse(pending.isDone(), "The create request must still be paused after the staff change commits");
            gap.release();
            result = pending.get(20, TimeUnit.SECONDS);
        } finally {
            gap.release();
            worker.shutdown();
            assertTrue(worker.awaitTermination(25, TimeUnit.SECONDS));
        }

        Long destination = scenario.scope().equals("DEPT")
                ? requestedDepartment == null ? currentDepartment : requestedDepartment
                : null;
        assertEquals(scenario.code() == 0 ? List.of("BEFORE", "AFTER", "BEFORE", "AFTER")
                : List.of("BEFORE", "AFTER"), gap.positions);
        assertEquals(clinical, clinicalRows());
        System.out.println("V159 " + scenario.label() + " actual=" + result.status() + "/" + result.code());
        assertAll(
                () -> assertEquals(scenario.status(), result.status(), result.body().toString()),
                () -> assertEquals(scenario.code(), result.code(), result.body().toString()),
                () -> assertAudit(staffPath + (scenario.disabled() ? "/enabled?enabled=false" : ""), scenario.status()),
                () -> {
                    if (scenario.code() == 0) {
                        assertTrue(result.body().path("data").isIntegralNumber());
                        assertCreated(result.body().path("data").longValue(), scenario.scope(), destination,
                                prefix, prefix + "正文", "CONSENT", "PROGRESS", before);
                    } else {
                        assertTrue(result.body().path("data").isNull());
                        assertEquals(before, allTemplateRows(), "Refused creation must write no header or grant");
                    }
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {" \t ", " ris "})
    void scopedInputNormalizationKeepsOriginalContract(String type) throws Exception {
        actor = employee("actor", List.of("QUALITY"));
        var before = allTemplateRows();
        String name = "  " + "名".repeat(70) + "  ";
        Reply result = call(post(CREATE),
                request(name, "  正文 \n", " personal ", newDept, type, "  abcdefghijklmnopq  "), actor);
        assertEquals(200, result.status(), result.body().toString());
        assertEquals(0, result.code(), result.body().toString());
        assertCreated(result.body().path("data").longValue(), "PERSONAL", null, "名".repeat(64),
                "  正文 \n", type == null || type.isBlank() ? "EMR" : "RIS", "ABCDEFGHIJKLMNOP", before);
    }

    static Stream<Arguments> invalidInput() {
        return Stream.of(
                Arguments.of(" ", "正文", "PERSONAL", 4061),
                Arguments.of("模板", " ", "PERSONAL", 4062),
                Arguments.of("模板", "正文", "UNKNOWN", 4065));
    }

    @ParameterizedTest
    @MethodSource("invalidInput")
    void scopedInputErrorsKeepOriginalCodes(String name, String content, String scope, int expected) throws Exception {
        actor = employee("actor", List.of("QUALITY"));
        var before = allTemplateRows();
        Reply result = call(post(CREATE), request(name, content, scope, null, null, null), actor);
        assertEquals(200, result.status());
        assertEquals(expected, result.code());
        assertTrue(result.body().path("data").isNull());
        assertEquals(before, allTemplateRows());
    }

    @Test
    void missingRequesterKeeps4066BeforeAnyTemplateWrite() {
        var before = allTemplateRows();
        HipBizException error = assertThrows(HipBizException.class, () ->
                templates.create(null, new EmrTemplateService.TemplateReq(prefix, "正文", "EMR", "PERSONAL", null, null)));
        assertEquals(4066, error.code);
        assertEquals(before, allTemplateRows());
    }

    private Map<String, Object> request(String name, String content, String scope, Long department,
                                        String type, String recordType) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("content", content);
        body.put("scope", scope);
        body.put("deptId", department);
        body.put("templateType", type);
        body.put("recordType", recordType);
        assertEquals(6, body.size());
        return body;
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body, Staff who) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            if (body != null) request.contentType("application/json").content(mapper.writeValueAsBytes(body));
            var response = mvc.perform(request.header("Authorization", "Bearer " + who.token())).andReturn().getResponse();
            JsonNode envelope = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            var keys = new java.util.HashSet<String>();
            envelope.fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("code", "message", "data"), keys);
            return new Reply(response.getStatus(), envelope);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Map<String, List<String>> allTemplateRows() {
        return Map.of("headers", jdbc.queryForList("select to_jsonb(t)::text from emr_template t order by id", String.class),
                "grants", jdbc.queryForList("select to_jsonb(g)::text from emr_template_grant g order by id", String.class));
    }

    private Map<String, List<String>> clinicalRows() {
        return Map.of("outpatient", jdbc.queryForList("select to_jsonb(e)::text from outp_emr e order by id", String.class),
                "inpatient", jdbc.queryForList("select to_jsonb(r)::text from inp_medical_record r order by id", String.class));
    }

    private void assertCreated(Long id, String scope, Long department, String name, String content, String type,
                               String recordType, Map<String, List<String>> before) {
        var headers = jdbc.queryForList("select * from emr_template where owner_id=?", actor.id());
        assertEquals(1, headers.size());
        var header = new LinkedHashMap<>(headers.getFirst());
        assertEquals(id, header.remove("id"));
        assertNotNull(header.remove("created_at"));
        var expected = new LinkedHashMap<String, Object>();
        expected.put("dept_id", department);
        expected.put("name", name);
        expected.put("content", content);
        expected.put("template_type", type);
        expected.put("enabled", true);
        expected.put("scope", scope);
        expected.put("owner_id", actor.id());
        expected.put("record_type", recordType);
        expected.put("is_default", false);
        expected.put("created_by", actor.id());
        assertEquals(expected, header);
        var grants = jdbc.queryForList("select * from emr_template_grant where template_id=?", id);
        boolean hasGrant = scope.equals("DEPT") || scope.equals("PERSONAL");
        assertEquals(hasGrant ? 1 : 0, grants.size());
        if (hasGrant) {
            var grant = new LinkedHashMap<>(grants.getFirst());
            assertNotNull(grant.remove("id"));
            assertNotNull(grant.remove("granted_at"));
            assertEquals(Map.of("template_id", id, "grantee_type", scope.equals("DEPT") ? "DEPT" : "USER",
                    "grantee_id", scope.equals("DEPT") ? department : actor.id(), "granted_by", actor.id()), grant);
        }
        assertEquals(before.get("headers"), jdbc.queryForList("select to_jsonb(t)::text from emr_template t where id<>? order by id", String.class, id));
        assertEquals(before.get("grants"), jdbc.queryForList("select to_jsonb(g)::text from emr_template_grant g where template_id<>? order by id", String.class, id));
    }

    private void assertAudit(String changedPath, int createStatus) {
        var rows = jdbc.queryForList("""
                select id,username,method,path,http_status,client_ip,created_at from sys_audit_log
                where username in (?,?) order by id
                """, administrator.username(), actor.username());
        assertEquals(2, rows.size());
        for (var row : rows) {
            assertEquals(Set.of("id", "username", "method", "path", "http_status", "client_ip", "created_at"), row.keySet());
            assertNotNull(row.get("id"));
            assertNotNull(row.get("created_at"));
            assertEquals("127.0.0.1", row.get("client_ip"));
        }
        assertEquals(administrator.username(), rows.getFirst().get("username"));
        assertEquals("PUT", rows.getFirst().get("method"));
        assertEquals(changedPath, rows.getFirst().get("path"));
        assertEquals(200, rows.getFirst().get("http_status"));
        assertEquals(actor.username(), rows.getLast().get("username"));
        assertEquals("POST", rows.getLast().get("method"));
        assertEquals(CREATE, rows.getLast().get("path"));
        assertEquals(createStatus, rows.getLast().get("http_status"));
    }

    static final class Gap {
        static final ThreadLocal<Gap> TRACE = new ThreadLocal<>();
        final CountDownLatch reached = new CountDownLatch(1), resume = new CountDownLatch(1);
        final List<String> positions = new ArrayList<>();
        List<Object> parameters;
        List<?> oldDepartmentResult;
        List<String> authentication;
        boolean oldProfile;

        void observe(String sql, Object[] arguments, boolean after, List<?> result) {
            String normalized = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("select dept_id from sys_user where id = ?")) {
                if (after) oldDepartmentResult = result;
                return;
            }
            boolean old = normalized.startsWith("select r.code from sys_user_role ur join sys_role r on r.id = ur.role_id ")
                    && normalized.endsWith("where ur.user_id = ?");
            boolean current = normalized.startsWith("select u.id, u.dept_id, u.enabled, r.code from sys_user u ")
                    && normalized.endsWith("where u.id = ?");
            if (!old && !current) return;
            positions.add(after ? "AFTER" : "BEFORE");
            if (!after && positions.size() == 3) {
                // Creation rereads the actor after the insert and auto-grant. Only the initial read is gated.
                assertEquals(List.of("BEFORE", "AFTER", "BEFORE"), positions);
                assertTrue(current);
                assertEquals(parameters, List.of(arguments));
                return;
            }
            if (!after) {
                assertEquals(List.of("BEFORE"), positions);
                oldProfile = old;
                parameters = List.of(arguments);
                authentication = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .map(authority -> authority.getAuthority()).sorted().toList();
                reached.countDown();
                try {
                    assertTrue(resume.await(20, TimeUnit.SECONDS), "The employee query was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }

        void await() throws InterruptedException {
            assertTrue(reached.await(15, TimeUnit.SECONDS), "The actual scoped employee query was not reached");
        }

        void release() { resume.countDown(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ActorJdbcConfiguration {
        @Bean @Primary
        JdbcTemplate scopedActorJdbcTemplate(DataSource actual) {
            return new JdbcTemplate(new TransactionAwareDataSourceProxy(actual)) {
                @Override
                public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                    Gap gap = Gap.TRACE.get();
                    if (gap != null) gap.observe(sql, args, false, null);
                    List<T> rows = super.queryForList(sql, elementType, args);
                    if (gap != null) gap.observe(sql, args, true, rows);
                    return rows;
                }

                @Override
                public List<Map<String, Object>> queryForList(String sql, Object... args) {
                    Gap gap = Gap.TRACE.get();
                    if (gap != null) gap.observe(sql, args, false, null);
                    List<Map<String, Object>> rows = super.queryForList(sql, args);
                    if (gap != null) gap.observe(sql, args, true, rows);
                    return rows;
                }
            };
        }
    }

    @AfterEach
    void cleanUp() {
        Gap.TRACE.remove();
        SecurityContextHolder.clearContext();
        if (actor != null) {
            jdbc.update("delete from emr_template_grant where template_id in (select id from emr_template where owner_id=?)", actor.id());
            jdbc.update("delete from emr_template where owner_id=?", actor.id());
        }
        for (Staff staff : new Staff[]{actor, administrator}) {
            if (staff != null) {
                jdbc.update("delete from sys_user_role where user_id=?", staff.id());
                jdbc.update("delete from sys_user where id=?", staff.id());
            }
        }
        jdbc.update("delete from sys_dept where id in (?,?)", oldDept, newDept);
    }
}
