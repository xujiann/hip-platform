package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Real JWT and independently committed staff changes surround the actual payload SQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(V161EmrTemplateReadActorTest.JdbcConfiguration.class)
class V161EmrTemplateReadActorTest {
    private static final List<String> DUTIES = List.of("ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN");
    private static final Set<String> HEADER_KEYS = Set.of("id", "dept_id", "name", "content", "template_type",
            "enabled", "scope", "owner_id", "record_type", "is_default", "created_by", "created_at");
    private static final Set<String> GRANT_KEYS = Set.of("id", "template_id", "grantee_type", "grantee_id",
            "granted_at", "grantee_name", "granted_by_name");

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    private enum Entry { VISIBLE, USE, GRANTS, DEFAULT, LEGACY }
    private enum Position { BEFORE, AFTER }
    private record Staff(Long id, String username, String token) {}
    private record Reply(int http, JsonNode body) {}
    private record Scenario(String label, String initialDuty, List<String> currentDuties, boolean disable) {}
    private record Audit(String username, String method, String path, int status) {}

    private final List<Staff> people = new ArrayList<>();
    private String prefix, nonce;
    private Long department, otherDepartment, template;
    private Staff actor, owner, administrator;

    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "No outer test transaction may hide staff commits");
        SecurityContextHolder.clearContext();
        prefix = "V161" + Long.toUnsignedString(System.nanoTime(), 36);
        nonce = prefix + "_PRIVATE_TEMPLATE_BODY";
        department = department("A");
        otherDepartment = department("B");
        owner = employee("owner", List.of("DOCTOR_OUTP"), department);
        administrator = employee("control", List.of("ADMIN"), otherDepartment);
        template = jdbc.queryForObject("""
                insert into emr_template(dept_id,name,content,template_type,scope,owner_id,record_type,enabled,is_default,created_by)
                values (?,?,?,'EMR','DEPT',?,'OUTP',true,true,?) returning id
                """, Long.class, department, prefix + "模板", nonce, owner.id(), owner.id());
        assertEquals(1, jdbc.update("""
                insert into emr_template_grant(template_id,grantee_type,grantee_id,granted_by)
                values (?,'DEPT',?,?)
                """, template, department, owner.id()));
        assertEquals(1, jdbc.update("""
                insert into emr_template_field(template_id,field_code,label,datatype,required,sort_no)
                values (?,'v161_field',?,'TEXT',false,1)
                """, template, prefix + "原字段"));
    }

    private Long department(String suffix) {
        return jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, prefix + suffix, prefix + suffix);
    }

    private Staff employee(String suffix, List<String> roles, Long dept) {
        String name = prefix + suffix;
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, name, name, dept);
        for (String role : roles) assertEquals(1, jdbc.update("""
                insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?
                """, id, role));
        Staff staff = new Staff(id, name, jwt.issue(name));
        people.add(staff);
        return staff;
    }

    static Stream<Arguments> revocations() {
        List<Scenario> scenarios = List.of(
                new Scenario("admin-lost-duty", "ADMIN", List.of("CASHIER"), false),
                new Scenario("quality-lost-duty", "QUALITY", List.of("CASHIER"), false),
                new Scenario("admin-disabled", "ADMIN", List.of("ADMIN"), true),
                new Scenario("quality-disabled", "QUALITY", List.of("QUALITY"), true),
                new Scenario("all-duties-removed", "DOCTOR_OUTP", List.of(), false));
        return Arrays.stream(Entry.values()).flatMap(entry -> scenarios.stream().map(scenario -> Arguments.of(entry, scenario)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("revocations")
    void staffRevocationCommittedBeforePayloadMustDenyWithoutReturningTemplate(Entry entry, Scenario scenario) throws Exception {
        actor = employee("reader", List.of(scenario.initialDuty()), department);
        Reply baseline = read(entry, false);
        assertReply(baseline, 200, 0);
        assertPayload(entry, baseline.body().path("data"));
        window(entry, false, scenario, Position.BEFORE, 403, 1005, null);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void deniedCurrentEmployeeTakesPrecedenceOverMissingTargetOrEmptyResult(Entry entry) throws Exception {
        actor = employee("reader", List.of("QUALITY"), department);
        Reply baseline = read(entry, true);
        assertReply(baseline, 200, entry == Entry.USE || entry == Entry.GRANTS ? 4066 : 0);
        JsonNode data = baseline.body().path("data");
        assertTrue(data.isNull() || data.isArray() && data.isEmpty());
        window(entry, true, new Scenario("empty-result-lost-duty", "QUALITY", List.of("CASHIER"), false),
                Position.BEFORE, 403, 1005, null);
    }

    static Stream<Arguments> legalReaders() {
        return Arrays.stream(Entry.values()).flatMap(entry -> DUTIES.stream().map(role -> Arguments.of(entry, role)));
    }

    @ParameterizedTest
    @MethodSource("legalReaders")
    void eachOriginalMaintenanceDutyKeepsItsFullExistingReadResponse(Entry entry, String role) throws Exception {
        actor = employee("reader", List.of(role), department);
        Reply before = read(entry, false);
        assertReply(before, 200, 0);
        assertPayload(entry, before.body().path("data"));
        Map<String, List<String>> facts = facts();
        List<Map<String, Object>> audits = audit();
        Reply after = read(entry, false);
        assertReply(after, 200, 0);
        assertEquals(before.body(), after.body());
        assertEquals(facts, facts());
        assertAudit(audits);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void unrelatedDutyDoesNotRemoveTheCurrentQualityReadDuty(Entry entry) throws Exception {
        actor = employee("reader", List.of("QUALITY", "CASHIER"), department);
        Reply before = read(entry, false);
        assertReply(before, 200, 0);
        assertPayload(entry, before.body().path("data"));
        Map<String, List<String>> facts = facts();
        List<Map<String, Object>> audits = audit();
        Reply after = read(entry, false);
        assertEquals(before, after);
        assertEquals(facts, facts());
        assertAudit(audits);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void completedAuthorizedStatementMayReturnItsExistingSnapshotAfterRevocation(Entry entry) throws Exception {
        actor = employee("reader", List.of("QUALITY"), department);
        Reply baseline = read(entry, false);
        assertReply(baseline, 200, 0);
        assertPayload(entry, baseline.body().path("data"));
        window(entry, false, new Scenario("after-executed-snapshot", "QUALITY", List.of("CASHIER"), false),
                Position.AFTER, 200, 0, baseline.body());
    }

    private void window(Entry entry, boolean missing, Scenario scenario, Position position,
                        int http, int code, JsonNode expectedBody) throws Exception {
        String path = path(entry, missing);
        Map<String, List<String>> initial = facts();
        List<Map<String, Object>> oldAudit = audit();
        Gap gap = new Gap(position, actor.id());
        var pool = Executors.newSingleThreadExecutor();
        var future = pool.submit(() -> {
            Gap.TRACE.set(gap);
            try { return read(entry, missing); }
            finally { Gap.TRACE.remove(); SecurityContextHolder.clearContext(); }
        });
        Reply reply;
        String staffPath = "/api/system/users/" + actor.id();
        String changedPath = scenario.disable() ? staffPath + "/enabled?enabled=false" : staffPath;
        try {
            gap.await();
            assertEquals(List.of("ROLE_" + scenario.initialDuty()), gap.authorities,
                    "The JWT and method-entry role must have already passed before the actual payload statement");
            Object body = scenario.disable() ? null : Map.of("username", actor.username(), "realName", actor.username(),
                    "deptId", department, "roleCodes", scenario.currentDuties());
            assertReply(call(put(changedPath), body, administrator), 200, 0);
            assertEquals(!scenario.disable(), jdbc.queryForObject("select enabled from sys_user where id=?", Boolean.class, actor.id()));
            assertEquals(scenario.currentDuties().stream().sorted().toList(), jdbc.queryForList("""
                    select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id
                    where ur.user_id=? order by r.code
                    """, String.class, actor.id()));
            assertFalse(future.isDone(), "The original request must remain paused after the staff mutation committed");
            Map<String, List<String>> committed = facts();
            for (String table : initial.keySet()) {
                if (!Set.of("users", "roles").contains(table)) assertEquals(initial.get(table), committed.get(table), table);
            }
            gap.release();
            reply = future.get(20, TimeUnit.SECONDS);
            assertReply(reply, http, code);
            if (expectedBody != null) {
                assertEquals(expectedBody, reply.body(), "No later employee requery may replace the completed statement snapshot");
                assertPayload(entry, reply.body().path("data"));
            } else assertFalse(reply.body().toString().contains(nonce), "A denied read must not leak template text");
            assertEquals(committed, facts(), "The GET must not mutate any business, staff or grant state");
            List<Audit> expectedAudit = new ArrayList<>();
            expectedAudit.add(new Audit(administrator.username(), "PUT", changedPath, 200));
            if (http != 200) expectedAudit.add(new Audit(actor.username(), "GET", path.split("\\?", 2)[0], http));
            assertAudit(oldAudit, expectedAudit.toArray(Audit[]::new));
            assertEquals(1, gap.hits.get());
            System.out.println("V161 PAYLOAD_BOUNDARY entry=" + entry + " position=" + position + " scenario="
                    + scenario.label() + " actor=" + actor.id() + " proof=" + gap.boundary + " postgresLockWaitClaim=false");
        } finally {
            gap.release();
            if (!future.isDone()) future.cancel(true);
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private String path(Entry entry, boolean missing) {
        long id = missing ? 899999999999999L : template;
        return switch (entry) {
            case USE -> "/api/emr-templates/" + id;
            case GRANTS -> "/api/emr-templates/" + id + "/grants";
            case DEFAULT -> "/api/emr-templates/default?deptId=" + department + "&recordType=" + (missing ? "V161ABSENT" : "OUTP");
            case VISIBLE -> "/api/emr-templates/visible?type=EMR" + (missing ? "&keyword=V161ZZABSENT" + prefix : "");
            case LEGACY -> "/api/emr-templates?type=" + (missing ? "V161ABSENT" : "EMR");
        };
    }

    private Reply read(Entry entry, boolean missing) throws Exception { return call(get(path(entry, missing)), null, actor); }

    private Reply call(MockHttpServletRequestBuilder request, Object body, Staff staff) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            if (body != null) request.contentType("application/json").content(mapper.writeValueAsBytes(body));
            var response = mvc.perform(request.header("Authorization", "Bearer " + staff.token())).andReturn().getResponse();
            return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void assertReply(Reply reply, int http, int code) {
        assertEquals(Set.of("code", "message", "data"), keys(reply.body()));
        assertEquals(http, reply.http(), reply.body().toString());
        assertEquals(code, reply.body().path("code").asInt(), reply.body().toString());
        if (code != 0) assertTrue(reply.body().path("data").isNull());
        assertNoPrivateKeys(reply.body());
    }

    private void assertPayload(Entry entry, JsonNode data) {
        if (entry == Entry.GRANTS) {
            assertTrue(data.isArray());
            assertEquals(1, data.size());
            assertEquals(GRANT_KEYS, keys(data.get(0)));
            assertEquals(template.longValue(), data.get(0).path("template_id").asLong());
            return;
        }
        Set<String> expectedKeys = new HashSet<>(HEADER_KEYS);
        if (entry == Entry.USE) expectedKeys.add("scopeName");
        if (entry == Entry.VISIBLE) expectedKeys.addAll(Set.of("owner_name", "dept_name", "grant_count", "editable", "scopeName"));
        if (entry == Entry.VISIBLE || entry == Entry.LEGACY) {
            assertTrue(data.isArray());
            assertFalse(data.isEmpty());
            for (JsonNode row : data) assertEquals(expectedKeys, keys(row));
            assertTrue(Stream.of(data).flatMap(array -> java.util.stream.StreamSupport.stream(array.spliterator(), false))
                    .anyMatch(row -> row.path("id").asLong() == template && nonce.equals(row.path("content").asText())));
        } else {
            assertEquals(expectedKeys, keys(data));
            assertEquals(template.longValue(), data.path("id").asLong());
            assertEquals(nonce, data.path("content").asText());
        }
        assertNoPrivateKeys(data);
    }

    private Set<String> keys(JsonNode node) { Set<String> result = new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result; }
    private void assertNoPrivateKeys(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(field -> { assertFalse(field.getKey().startsWith("__"), field.getKey()); assertNoPrivateKeys(field.getValue()); });
        else if (node.isArray()) node.forEach(this::assertNoPrivateKeys);
    }

    private Map<String, List<String>> facts() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : List.of("emr_template", "emr_template_grant", "emr_template_field", "outp_emr", "inp_medical_record", "sys_dept")) {
            result.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by id", String.class));
        }
        result.put("users", jdbc.queryForList("select ((to_jsonb(u)-'password') || jsonb_build_object('passwordDigest',md5(u.password)))::text from sys_user u order by id", String.class));
        result.put("roles", jdbc.queryForList("select to_jsonb(r)::text from sys_user_role r order by user_id,role_id", String.class));
        return result;
    }

    private List<Map<String, Object>> audit() {
        return jdbc.queryForList("select id,username,method,path,http_status,client_ip,created_at::text as created_at from sys_audit_log order by id");
    }

    private void assertAudit(List<Map<String, Object>> before, Audit... expected) {
        var old = new LinkedHashMap<Object, Map<String, Object>>();
        before.forEach(row -> old.put(row.get("id"), row));
        List<Map<String, Object>> added = new ArrayList<>();
        for (var row : audit()) {
            if (old.containsKey(row.get("id"))) assertEquals(old.remove(row.get("id")), row);
            else added.add(row);
        }
        assertTrue(old.isEmpty());
        assertEquals(expected.length, added.size(), added.toString());
        for (int i = 0; i < expected.length; i++) {
            Audit event = expected[i];
            Map<String, Object> row = added.get(i);
            assertEquals(Set.of("id", "username", "method", "path", "http_status", "client_ip", "created_at"), row.keySet());
            assertEquals(event.username(), row.get("username"));
            assertEquals(event.method(), row.get("method"));
            assertEquals(event.path(), row.get("path"));
            assertEquals(event.status(), ((Number) row.get("http_status")).intValue());
            assertEquals("127.0.0.1", row.get("client_ip"));
            assertNotNull(row.get("id"));
            assertNotNull(row.get("created_at"));
        }
    }

    static final class Gap {
        static final ThreadLocal<Gap> TRACE = new ThreadLocal<>();
        final Position position;
        final Long actor;
        final CountDownLatch reached = new CountDownLatch(1), resume = new CountDownLatch(1);
        final AtomicInteger hits = new AtomicInteger();
        volatile Map<String, Object> boundary;
        volatile List<String> authorities;
        Gap(Position position, Long actor) { this.position = position; this.actor = actor; }
        void observe(String sql, Map<Integer, Object> parameters, Position at, int fetchSize) {
            String normalized = sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            boolean payload = normalized.contains("from emr_template ") || normalized.contains("join emr_template ");
            if (!payload || at != position) return;
            assertEquals(1, hits.incrementAndGet(), "Exactly one real template payload statement must reach the boundary");
            assertEquals(actor.longValue(), ((Number) parameters.get(1)).longValue());
            assertEquals(0, fetchSize, "Default buffered PG results establish the AFTER execution snapshot");
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            authorities = auth.getAuthorities().stream().map(value -> value.getAuthority()).sorted().toList();
            var proof = new LinkedHashMap<String, Object>();
            proof.put("sql", sql);
            proof.put("parameters", new TreeMap<>(parameters));
            proof.put("position", at.name());
            proof.put("time", Instant.now().toString());
            proof.put("thread", Thread.currentThread().getName());
            proof.put("fetchSize", fetchSize);
            boundary = proof;
            reached.countDown();
            try { assertTrue(resume.await(20, TimeUnit.SECONDS), "The actual payload SQL boundary was not released"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
        void await() throws InterruptedException { assertTrue(reached.await(15, TimeUnit.SECONDS), "The actual template payload SQL was not reached"); assertNotNull(boundary); }
        void release() { resume.countDown(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JdbcConfiguration {
        @Bean @Primary JdbcTemplate templateReadBoundaryJdbcTemplate(DataSource actual) { return new JdbcTemplate(new BoundaryDataSource(actual)); }
    }

    static final class BoundaryDataSource extends DelegatingDataSource {
        BoundaryDataSource(DataSource actual) { super(new TransactionAwareDataSourceProxy(actual)); }
        @Override public Connection getConnection() throws SQLException { return connection(super.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return connection(super.getConnection(username, password)); }
        private Connection connection(Connection actual) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                Object value = invoke(actual, method, arguments);
                if (method.getName().equals("prepareStatement") && arguments != null && arguments[0] instanceof String sql && value instanceof PreparedStatement statement) {
                    return statement(statement, sql);
                }
                return value;
            });
        }
        private PreparedStatement statement(PreparedStatement actual, String sql) {
            Map<Integer, Object> parameters = new TreeMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                if (method.getName().startsWith("set") && arguments != null && arguments.length >= 2 && arguments[0] instanceof Integer index) {
                    parameters.put(index, method.getName().equals("setNull") ? null : arguments[1]);
                }
                if (method.getName().equals("clearParameters")) parameters.clear();
                Gap gap = Gap.TRACE.get();
                boolean query = method.getName().equals("executeQuery") && (arguments == null || arguments.length == 0);
                if (query && gap != null) gap.observe(sql, parameters, Position.BEFORE, actual.getFetchSize());
                Object value = invoke(actual, method, arguments);
                if (query && gap != null) gap.observe(sql, parameters, Position.AFTER, actual.getFetchSize());
                return value;
            });
        }
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @AfterEach
    void cleanUp() {
        Gap.TRACE.remove();
        SecurityContextHolder.clearContext();
        if (template != null) {
            jdbc.update("delete from emr_template_field where template_id=?", template);
            jdbc.update("delete from emr_template_grant where template_id=?", template);
            jdbc.update("delete from emr_template where id=?", template);
        }
        for (Staff person : people) {
            jdbc.update("delete from sys_audit_log where username=?", person.username());
            jdbc.update("delete from sys_user_role where user_id=?", person.id());
            jdbc.update("delete from sys_user where id=?", person.id());
        }
        if (department != null) jdbc.update("delete from sys_dept where id in (?,?)", department, otherDepartment);
    }
}
