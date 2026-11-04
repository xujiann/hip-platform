package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Actual JWT creation pauses before the employee query; public staff changes commit separately. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(V158EmrLegacyCreateActorTest.JdbcConfiguration.class)
class V158EmrLegacyCreateActorTest {
    private static final String CREATE = "/api/emr-templates";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    private record Staff(Long id, String username, String token) {}
    private record Reply(int status, JsonNode body) {
        int code() { return body.path("code").asInt(); }
    }
    private enum Target { HOSPITAL, OLD_DEPARTMENT, NEW_DEPARTMENT }
    private record Scenario(String label, String initialRole, String currentRole,
                            boolean transfer, boolean disabled, Target target, int status, int code) {}

    private String prefix;
    private Long oldDept, newDept;
    private Staff actor, administrator;

    @BeforeEach
    void setUp() {
        prefix = "V158" + Long.toUnsignedString(System.nanoTime(), 36);
        oldDept = department("old");
        newDept = department("new");
        administrator = employee("control", "ADMIN");
    }

    private Long department(String suffix) {
        return jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, prefix + suffix, prefix + suffix);
    }

    private Staff employee(String suffix, String role) {
        String username = prefix + suffix;
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, username, username, oldDept);
        assertEquals(1, jdbc.update("""
                insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?
                """, id, role));
        return new Staff(id, username, jwt.issue(username));
    }

    static Stream<Arguments> requests() {
        var cases = new ArrayList<Scenario>();
        cases.add(new Scenario("downgrade-hospital", "ADMIN", "QUALITY", false, false, Target.HOSPITAL, 200, 4066));
        cases.add(new Scenario("downgrade-foreign-department", "ADMIN", "QUALITY", false, false, Target.NEW_DEPARTMENT, 200, 4066));
        cases.add(new Scenario("downgrade-and-transfer-old-department", "ADMIN", "QUALITY", true, false, Target.OLD_DEPARTMENT, 200, 4066));
        cases.add(new Scenario("admin-loses-maintenance-duty", "ADMIN", "CASHIER", false, false, Target.OLD_DEPARTMENT, 403, 1005));
        cases.add(new Scenario("quality-loses-maintenance-duty", "QUALITY", "CASHIER", false, false, Target.OLD_DEPARTMENT, 403, 1005));
        cases.add(new Scenario("admin-disabled", "ADMIN", "ADMIN", false, true, Target.OLD_DEPARTMENT, 403, 1005));
        cases.add(new Scenario("quality-disabled", "QUALITY", "QUALITY", false, true, Target.OLD_DEPARTMENT, 403, 1005));
        cases.add(new Scenario("quality-transfer-old-department", "QUALITY", "QUALITY", true, false, Target.OLD_DEPARTMENT, 200, 4066));
        cases.add(new Scenario("quality-transfer-current-department", "QUALITY", "QUALITY", true, false, Target.NEW_DEPARTMENT, 200, 0));
        cases.add(new Scenario("downgrade-and-transfer-current-department", "ADMIN", "QUALITY", true, false, Target.NEW_DEPARTMENT, 200, 0));
        cases.add(new Scenario("quality-hospital-denied", "QUALITY", "QUALITY", false, false, Target.HOSPITAL, 200, 4066));
        for (String role : List.of("ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN")) {
            cases.add(new Scenario("legal-" + role, role, role, false, false,
                    role.equals("ADMIN") ? Target.HOSPITAL : Target.OLD_DEPARTMENT, 200, 0));
        }
        return cases.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void legacyCreationUsesCurrentEmployee(Scenario scenario) throws Exception {
        actor = employee("actor", scenario.initialRole());
        Long destination = switch (scenario.target()) {
            case HOSPITAL -> null;
            case OLD_DEPARTMENT -> oldDept;
            case NEW_DEPARTMENT -> newDept;
        };
        var body = request(destination, prefix, prefix + "正文", "CONSENT");
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
        String staffPath = "/api/system/users/" + actor.id();
        try {
            gap.await();
            assertEquals(List.of("ROLE_" + scenario.initialRole()), gap.authentication);
            assertEquals(List.of("BEFORE"), gap.statements.stream().map(event -> event.get("position")).toList());
            assertEquals(Map.of(1, actor.id()), gap.statements.getFirst().get("parameters"));
            Long department = scenario.transfer() ? newDept : oldDept;
            Reply changed = scenario.disabled()
                    ? call(put(staffPath + "/enabled").queryParam("enabled", "false"), null, administrator)
                    : call(put(staffPath), Map.of("username", actor.username(), "realName", actor.username(),
                            "deptId", department, "roleCodes", List.of(scenario.currentRole())), administrator);
            assertEquals(200, changed.status(), changed.body().toString());
            assertEquals(0, changed.code(), changed.body().toString());
            assertEquals(!scenario.disabled(), jdbc.queryForObject("select enabled from sys_user where id=?", Boolean.class, actor.id()));
            assertEquals(department, jdbc.queryForObject("select dept_id from sys_user where id=?", Long.class, actor.id()));
            assertEquals(List.of(scenario.currentRole()), jdbc.queryForList("""
                    select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=?
                    """, String.class, actor.id()));
            assertFalse(pending.isDone(), "The legacy request must remain paused after the staff update commits");
            gap.release();
            result = pending.get(20, TimeUnit.SECONDS);
        } finally {
            gap.release();
            worker.shutdown();
            assertTrue(worker.awaitTermination(25, TimeUnit.SECONDS));
        }

        assertEquals(List.of("BEFORE", "AFTER"), gap.statements.stream().map(event -> event.get("position")).toList());
        assertEquals(clinical, clinicalRows());
        System.out.println("V158 " + scenario.label() + " actual=" + result.status() + "/" + result.code());
        assertAll(
                () -> assertEquals(scenario.status(), result.status(), result.body().toString()),
                () -> assertEquals(scenario.code(), result.code(), result.body().toString()),
                () -> assertAudit(staffPath + (scenario.disabled() ? "/enabled" : ""), scenario.status()),
                () -> assertTrue(result.body().path("data").isNull()),
                () -> {
                    if (scenario.code() == 0) assertCreated(destination, prefix, prefix + "正文", "CONSENT", before);
                    else assertEquals(before, allTemplateRows(), "Refused legacy creation must write no template or grant");
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {" \t ", " ris "})
    void legacyBlankFieldsAndTemplateTypeContract(String type) throws Exception {
        actor = employee("actor", "ADMIN");
        var before = allTemplateRows();
        Reply result = call(post(CREATE), request(null, "", "", type), actor);
        assertEquals(200, result.status(), result.body().toString());
        assertEquals(0, result.code(), result.body().toString());
        assertEquals(Set.of("code", "message", "data"), keys(result.body()));
        assertTrue(result.body().path("data").isNull());
        assertCreated(null, "", "", type == null || type.isBlank() ? "EMR" : type, before);
    }

    private Map<String, Object> request(Long department, String name, String content, String type) {
        var body = new LinkedHashMap<String, Object>();
        body.put("deptId", department);
        body.put("name", name);
        body.put("content", content);
        body.put("templateType", type);
        return body;
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body, Staff who) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            if (body != null) request.contentType("application/json").content(mapper.writeValueAsBytes(body));
            var response = mvc.perform(request.header("Authorization", "Bearer " + who.token())).andReturn().getResponse();
            return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Set<String> keys(JsonNode object) {
        var names = new java.util.HashSet<String>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private Map<String, List<String>> allTemplateRows() {
        return Map.of("headers", jdbc.queryForList("select to_jsonb(t)::text from emr_template t order by id", String.class),
                "grants", jdbc.queryForList("select to_jsonb(g)::text from emr_template_grant g order by id", String.class));
    }

    private Map<String, List<String>> clinicalRows() {
        return Map.of("outpatient", jdbc.queryForList("select to_jsonb(e)::text from outp_emr e order by id", String.class),
                "inpatient", jdbc.queryForList("select to_jsonb(r)::text from inp_medical_record r order by id", String.class));
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
        final List<Map<String, Object>> statements = new ArrayList<>();
        List<String> authentication;

        void observe(String sql, Map<Integer, Object> parameters, boolean after) throws InterruptedException {
            String normalized = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            boolean oldProfile = normalized.equals("select dept_id from sys_user where id = ?");
            boolean currentProfile = normalized.startsWith("select u.id, u.dept_id, u.enabled, r.code from sys_user u ")
                    && normalized.endsWith("where u.id = ?");
            if (!oldProfile && !currentProfile) return;
            statements.add(Map.of("sql", sql, "parameters", new TreeMap<>(parameters), "position", after ? "AFTER" : "BEFORE"));
            if (!after) {
                assertEquals(1, statements.size(), "Exactly one profile query must be observed");
                authentication = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .map(authority -> authority.getAuthority()).sorted().toList();
                reached.countDown();
                assertTrue(resume.await(20, TimeUnit.SECONDS), "The employee read was not released");
            }
        }

        void await() throws InterruptedException {
            assertTrue(reached.await(15, TimeUnit.SECONDS), "The actual legacy employee query was not reached");
        }

        void release() { resume.countDown(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JdbcConfiguration {
        @Bean @Primary
        JdbcTemplate legacyActorJdbcTemplate(DataSource actual) {
            return new JdbcTemplate(new BoundaryDataSource(actual));
        }
    }

    static final class BoundaryDataSource extends DelegatingDataSource {
        BoundaryDataSource(DataSource actual) { super(new TransactionAwareDataSourceProxy(actual)); }

        @Override public Connection getConnection() throws SQLException { return connection(super.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return connection(super.getConnection(username, password));
        }

        Connection connection(Connection actual) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = invoke(actual, method, args);
                        return method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql
                                && result instanceof PreparedStatement statement ? statement(statement, sql) : result;
                    });
        }

        PreparedStatement statement(PreparedStatement actual, String sql) {
            var parameters = new TreeMap<Integer, Object>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        if (method.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                            parameters.put(index, method.getName().equals("setNull") ? null : args[1]);
                        }
                        if (method.getName().equals("clearParameters")) parameters.clear();
                        Gap gap = Gap.TRACE.get();
                        boolean query = method.getName().equals("executeQuery") && (args == null || args.length == 0);
                        if (gap != null && query) gap.observe(sql, parameters, false);
                        Object result = invoke(actual, method, args);
                        if (gap != null && query) gap.observe(sql, parameters, true);
                        return result;
                    });
        }

        static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException exception) { throw exception.getCause(); }
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
