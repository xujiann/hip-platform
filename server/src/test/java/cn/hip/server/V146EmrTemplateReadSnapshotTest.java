package cn.hip.server;

import cn.hip.medtech.service.EmrTemplateService;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Real JWT / real PostgreSQL, independently committed requests, no outer test
 * transaction. The test-only JDBC decorator pauses exactly the payload SELECT
 * before executeQuery, or after the real executeQuery has returned its buffered
 * ResultSet. A second authenticated public maintenance request commits there.
 *
 * This is an observable statement-boundary interleaving, NOT a PostgreSQL row
 * lock wait. The decorator does not alter SQL, parameters, returned rows or the
 * production service. Original ACL statements and the exact selected SQL,
 * arguments, thread, execution position and public maintenance replies are
 * archived; a Future being incomplete is never called database contention.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(V146EmrTemplateReadSnapshotTest.JdbcConfiguration.class)
class V146EmrTemplateReadSnapshotTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired EmrTemplateService templates;

    static final List<String> ENTRIES = List.of("use", "grants");
    static final List<String> DUTIES = List.of("ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN");
    static final List<String> TABLES = List.of("empi_patient", "outp_schedule", "outp_registration",
            "outp_emr", "outp_diagnosis", "outp_diagnosis_favorite", "outp_diagnosis_version", "emr_version",
            "inp_admission", "inp_medical_record", "outp_order", "outp_charge", "inp_order", "inp_order_exec",
            "inp_deposit", "inp_settlement", "emr_template", "emr_template_field", "emr_template_grant",
            "cdr_document", "emr_consent", "fin_print_log", "lis_sample", "ris_exam", "path_specimen");
    static final Set<String> GRANT_KEYS = Set.of("id", "template_id", "grantee_type", "grantee_id",
            "granted_at", "grantee_name", "granted_by_name");
    record Staff(Long id, String name, String token) {}
    record Reply(int http, JsonNode body) { int code() { return body.path("code").asInt(); } }
    record Audited(String method, String path, int status, String username) {}
    String tag;
    Long dept, otherDept, template, grantId;
    Staff actor, owner, admin;
    final List<Staff> people = new ArrayList<>();
    final List<Map<String, Object>> maintenance = new ArrayList<>();
    int artifact;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        tag = "V146T" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag + "D", tag + "本科");
        otherDept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag + "X", tag + "他科");
        actor = employee("actor", "DOCTOR_OUTP", dept);
        owner = employee("owner", "DOCTOR_OUTP", otherDept);
        admin = employee("admin", "ADMIN", dept);
        template = jdbc.queryForObject("""
                insert into emr_template(dept_id,name,content,template_type,scope,owner_id,record_type,enabled,created_by)
                values (?,?,'原可见模板正文','EMR','DEPT',?,'PROGRESS',true,?) returning id
                """, Long.class, dept, tag + "模板", owner.id(), owner.id());
        jdbc.update("insert into emr_template_field(template_id,field_code,label,datatype,required,sort_no) values (?,'f','原字段','TEXT',false,1)", template);
    }

    Staff employee(String suffix, String role, Long department) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id", Long.class, name, name, department);
        Staff staff = new Staff(id, name, jwt.issue(name)); people.add(staff); roles(staff, role); return staff;
    }
    void roles(Staff who, String... values) {
        jdbc.update("delete from sys_user_role where user_id=?", who.id());
        for (String role : values) assertEquals(1, jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", who.id(), role));
    }
    void configure(String scope, boolean owned) {
        jdbc.update("delete from emr_template_grant where template_id=?", template);
        jdbc.update("update emr_template set scope=?,owner_id=?,dept_id=?,enabled=true,content='原可见模板正文' where id=?",
                scope, owned ? actor.id() : owner.id(), scope.equals("DEPT") ? dept : null, template);
    }
    Long grant(String type, Long id) {
        return jdbc.queryForObject("insert into emr_template_grant(template_id,grantee_type,grantee_id,granted_by) values (?,?,?,?) returning id", Long.class, template, type, id, owner.id());
    }
    String endpoint(String entry) { return "/api/emr-templates/" + template + (entry.equals("grants") ? "/grants" : ""); }
    Reply call(String verb, String path, Object payload, String token) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            MockHttpServletRequestBuilder request = switch (verb) { case "PUT" -> put(path); case "POST" -> post(path); case "DELETE" -> delete(path); default -> get(path); };
            if (payload != null) request.contentType("application/json").content(mapper.writeValueAsString(payload));
            if (token != null) request.header("Authorization", "Bearer " + token);
            var response = mvc.perform(request).andReturn().getResponse();
            return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        } finally { SecurityContextHolder.clearContext(); }
    }
    Reply read(String entry) throws Exception { return call("GET", endpoint(entry), null, actor.token()); }
    List<String> rows(String table, String order) { return jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by " + order, String.class); }
    Map<String, List<String>> facts() {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : TABLES) result.put(table, rows(table, "id"));
        result.put("config", rows("sys_config", "cfg_key")); result.put("roles", rows("sys_user_role", "user_id,role_id"));
        result.put("users", jdbc.queryForList("select ((to_jsonb(t)-'password') || jsonb_build_object('passwordDigest',md5(t.password)))::text from sys_user t order by id", String.class));
        return result;
    }
    List<Map<String, Object>> audit() { return jdbc.queryForList("select * from sys_audit_log order by id"); }
    void audited(List<Map<String, Object>> old, Audited... events) {
        var all = audit(); var original = new HashMap<Object, Map<String, Object>>(); old.forEach(r -> original.put(r.get("id"), r));
        var added = new ArrayList<Map<String, Object>>();
        for (var r : all) { if (original.containsKey(r.get("id"))) assertEquals(original.remove(r.get("id")), r, "Complete old audit row frozen"); else added.add(r); }
        assertTrue(original.isEmpty()); assertEquals(events.length, added.size(), added.toString());
        var remaining = new ArrayList<>(added);
        for (var e : events) {
            var matching = remaining.stream().filter(r -> Objects.equals(r.get("username"), e.username())
                    && Objects.equals(r.get("method"), e.method()) && Objects.equals(r.get("path"), e.path())
                    && ((Number) r.get("http_status")).intValue() == e.status()).toList();
            assertFalse(matching.isEmpty(), "No actual audit for " + e);
            var r = matching.getFirst(); assertEquals(Set.of("id", "username", "method", "path", "http_status", "client_ip", "created_at"), r.keySet());
            assertEquals("127.0.0.1", r.get("client_ip")); assertNotNull(r.get("created_at")); remaining.remove(r);
        }
        assertTrue(remaining.isEmpty());
    }
    void response(Reply reply, int http, int code) {
        var keys = new HashSet<String>(); reply.body().fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("code", "message", "data"), keys);
        assertAll(() -> assertEquals(http, reply.http(), reply.body().toString()), () -> assertEquals(code, reply.code(), reply.body().toString()));
        if (code != 0) assertTrue(reply.body().path("data").isNull());
    }
    JsonNode expected(String entry) {
        if (entry.equals("use")) {
            var row = new LinkedHashMap<>(jdbc.queryForMap("select * from emr_template where id=?", template));
            row.put("scopeName", EmrTemplateService.scopeName((String) row.get("scope")));
            return mapper.valueToTree(row);
        }
        return mapper.valueToTree(jdbc.queryForList("""
                select g.id,g.template_id,g.grantee_type,g.grantee_id,g.granted_at,
                       case g.grantee_type when 'DEPT' then d.name else u.real_name end as grantee_name,
                       b.real_name as granted_by_name
                from emr_template_grant g
                left join sys_dept d on g.grantee_type='DEPT' and d.id=g.grantee_id
                left join sys_user u on g.grantee_type='USER' and u.id=g.grantee_id
                left join sys_user b on b.id=g.granted_by
                where g.template_id=? order by g.grantee_type,g.id
                """, template));
    }
    void data(String entry, Reply reply, JsonNode wanted) {
        response(reply, 200, 0); assertEquals(wanted, reply.body().path("data"), "Returned body/list must match the same authorized snapshot");
        if (entry.equals("grants")) for (var grant : reply.body().path("data")) {
            var keys = new HashSet<String>(); grant.fieldNames().forEachRemaining(keys::add); assertEquals(GRANT_KEYS, keys);
        }
    }
    void evidence(String label, Map<String, List<String>> before, Map<String, List<String>> after,
                  List<Map<String, Object>> oldAudit, Reply reply, Gap gap) {
        String folder = System.getProperty("hip.v146.evidenceDir"); if (folder == null) return;
        try {
            var directory = Path.of(folder).toAbsolutePath().normalize(); assertFalse(directory.toString().toLowerCase(Locale.ROOT).contains("onedrive")); Files.createDirectories(directory);
            var value = new LinkedHashMap<String, Object>(); value.put("label", label); value.put("tag", tag);
            value.put("http", reply.http()); value.put("response", reply.body()); value.put("before", before); value.put("after", after);
            value.put("oldAudit", oldAudit); value.put("currentAudit", audit()); value.put("publicMaintenance", maintenance);
            if (gap != null) value.put("jdbcBoundary", gap.proof());
            Files.writeString(directory.resolve(tag + "-" + (++artifact) + ".json"), mapper.writeValueAsString(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Exception e) { throw new AssertionError("Cannot preserve original case proof", e); }
    }
    void frozenRead(String label, String entry, Reply reply, int http, int code, JsonNode wanted,
                    Map<String, List<String>> before, List<Map<String, Object>> oldAudit, Gap gap, Audited... extra) {
        var after = facts(); evidence(label, before, after, oldAudit, reply, gap);
        System.out.println("V146 CASE " + label + " actual=" + reply.http() + "/" + reply.code() + " expected=" + http + "/" + code + " allFactsFrozen=" + before.equals(after));
        // The original AuditLogFilter deliberately does not match /emr-templates
        // against /emr (the next character is '-'). Successful/business-refused
        // HEAD reads add no audit. Security handlers still audit 401/403.
        var events = new ArrayList<>(Arrays.asList(extra));
        if (http != 200) events.add(new Audited("GET", endpoint(entry), http, http == 401 ? null : actor.name()));
        assertAll(() -> { if (code == 0) data(entry, reply, wanted); else response(reply, http, code); },
                () -> assertEquals(before, after, "Read must not alter clinical/history/field/template/grant/config/auth facts"),
                () -> audited(oldAudit, events.toArray(Audited[]::new)));
    }

    static Stream<Arguments> matrix() { return ENTRIES.stream().flatMap(entry -> Stream.of("GLOBAL", "HOSPITAL", "DEPT", "PERSONAL").flatMap(scope -> DUTIES.stream().map(role -> Arguments.of(entry, scope, role)))); }
    @ParameterizedTest @MethodSource("matrix")
    void fiveOriginalDutiesKeepTheFourScopeReadMatrix(String entry, String scope, String role) throws Exception {
        configure(scope, false); roles(actor, role); var before = facts(); var oldAudit = audit(); var wanted = expected(entry);
        boolean visible = !scope.equals("PERSONAL");
        frozenRead("matrix-" + entry + "-" + scope + "-" + role, entry, read(entry), 200, visible ? 0 : 4066, wanted, before, oldAudit, null);
    }
    static Stream<Arguments> own() { return ENTRIES.stream().flatMap(entry -> DUTIES.stream().map(role -> Arguments.of(entry, role))); }
    @ParameterizedTest @MethodSource("own")
    void everyOriginalDutyCanReadItsOwnPersonalTemplate(String entry, String role) throws Exception {
        configure("PERSONAL", true); roles(actor, role); var before = facts(); var oldAudit = audit();
        frozenRead("own-" + entry + "-" + role, entry, read(entry), 200, 0, expected(entry), before, oldAudit, null);
    }
    static Stream<Arguments> grantKinds() { return ENTRIES.stream().flatMap(entry -> Stream.of("USER", "DEPT").map(kind -> Arguments.of(entry, kind))); }
    @ParameterizedTest @MethodSource("grantKinds")
    void userAndDepartmentGrantsRemainAdditiveOnPersonalTemplates(String entry, String kind) throws Exception {
        configure("PERSONAL", false); grant(kind, kind.equals("USER") ? actor.id() : dept); var before = facts(); var oldAudit = audit();
        frozenRead("grant-" + entry + "-" + kind, entry, read(entry), 200, 0, expected(entry), before, oldAudit, null);
    }
    @ParameterizedTest @ValueSource(strings = {"GLOBAL", "HOSPITAL", "DEPT", "PERSONAL"})
    void visibleParentWithNoGrantsHasExactlyAnEmptyList(String scope) throws Exception {
        configure(scope, scope.equals("PERSONAL")); var before = facts(); var oldAudit = audit();
        frozenRead("empty-grants-" + scope, "grants", read("grants"), 200, 0, mapper.createArrayNode(), before, oldAudit, null);
    }
    @ParameterizedTest @ValueSource(strings = {"use", "grants"})
    void disabledTemplateRejectsUseButKeepsHistoricalGrantReading(String entry) throws Exception {
        configure("PERSONAL", true); grant("USER", actor.id()); jdbc.update("update emr_template set enabled=false where id=?", template);
        var before = facts(); var oldAudit = audit(); frozenRead("disabled-" + entry, entry, read(entry), 200, entry.equals("use") ? 4066 : 0, expected(entry), before, oldAudit, null);
    }
    @ParameterizedTest @ValueSource(strings = {"use", "grants"})
    void missingTargetKeepsTheSame4066Envelope(String entry) throws Exception {
        Long original = template; template = 899999999999999L; var before = facts(); var oldAudit = audit();
        try { frozenRead("missing-" + entry, entry, read(entry), 200, 4066, null, before, oldAudit, null); } finally { template = original; }
    }
    static Stream<Arguments> deniedEntries() { return ENTRIES.stream().flatMap(entry -> Stream.of("CASHIER", "INTERFACE", "anonymous", "disabled").map(who -> Arguments.of(entry, who))); }
    @ParameterizedTest @MethodSource("deniedEntries")
    void originalJwtAndFiveDutyEntryDenialsStayReadable(String entry, String who) throws Exception {
        configure("PERSONAL", true); String token = actor.token(); int http = 403, code = 1005;
        if (who.equals("anonymous")) { token = null; http = 401; code = 1000; }
        else if (who.equals("disabled")) { jdbc.update("update sys_user set enabled=false where id=?", actor.id()); http = 401; code = 1000; }
        else roles(actor, who);
        var before = facts(); var oldAudit = audit(); frozenRead("entry-" + entry + "-" + who, entry, call("GET", endpoint(entry), null, token), http, code, null, before, oldAudit, null);
    }
    @ParameterizedTest @ValueSource(strings = {"use", "grants"})
    void trustedNullOrMissingActorKeepsOriginalGlobalReadSemantics(String entry) {
        configure("GLOBAL", false); var before = facts(); var oldAudit = audit(); var wanted = expected(entry);
        for (Long user : Arrays.asList(null, 899999999999999L)) {
            Object value = entry.equals("use") ? templates.use(user, template) : templates.grants(user, template);
            assertEquals(wanted, mapper.valueToTree(value));
        }
        assertEquals(before, facts()); assertEquals(oldAudit, audit());
    }

    void prepareChange(String change) {
        configure(Set.of("USER", "DEPT_GRANT").contains(change) ? "PERSONAL" : "DEPT", false);
        if (change.equals("USER")) grantId = grant("USER", actor.id());
        if (change.equals("DEPT_GRANT")) grantId = grant("DEPT", dept);
        if (change.equals("actorAdmin")) { roles(actor, "ADMIN"); jdbc.update("update sys_user set dept_id=? where id=?", otherDept, actor.id()); }
    }
    void mutation(String verb, String path, Object body, Staff who) throws Exception {
        var reply = call(verb, path, body, who.token());
        maintenance.add(Map.of("method", verb, "path", path, "actor", who.name(), "http", reply.http(), "response", reply.body()));
        response(reply, 200, 0);
    }
    void publicChange(String change) throws Exception {
        switch (change) {
            case "scope", "visibleScope" -> mutation("PUT", "/api/emr-templates/" + template,
                    Map.of("name", tag + "私密变更", "content", tag + "_NEW_PRIVATE_BODY_", "templateType", "EMR",
                            "scope", change.equals("scope") ? "PERSONAL" : "HOSPITAL", "recordType", "PROGRESS"), admin);
            case "disable" -> mutation("PUT", "/api/emr-templates/" + template + "/disable", null, admin);
            case "USER", "DEPT_GRANT" -> mutation("DELETE", "/api/emr-templates/" + template + "/grants/" + grantId, null, owner);
            case "actorDept", "actorAdmin" -> mutation("PUT", "/api/system/users/" + actor.id(), Map.of("username", actor.name(), "realName", actor.name(),
                    "deptId", change.equals("actorDept") ? otherDept : otherDept, "roleCodes", List.of("DOCTOR_OUTP")), admin);
            default -> fail(change);
        }
    }
    Audited[] committedEvents() { return maintenance.stream().map(r -> new Audited((String) r.get("method"), (String) r.get("path"), 200, (String) r.get("actor"))).toArray(Audited[]::new); }
    static Stream<Arguments> changes() { return ENTRIES.stream().flatMap(entry -> Stream.of("scope", "USER", "DEPT_GRANT", "disable", "actorDept", "actorAdmin", "visibleScope").map(change -> Arguments.of(entry, change))); }
    @ParameterizedTest @MethodSource("changes")
    void committedPublicChangeBeforePayloadSelectCannotLeakANewUnauthorizedSnapshot(String entry, String change) throws Exception {
        prepareChange(change); var oldAudit = audit(); var gap = new Gap(entry, template, Position.BEFORE);
        var pool = Executors.newSingleThreadExecutor(); Future<Reply> worker = null;
        try {
            worker = pool.submit(() -> tracedRead(entry, gap)); gap.awaitBoundary(); assertFalse(worker.isDone());
            publicChange(change); var committed = facts(); var wanted = expected(entry); gap.release(); var reply = finish(worker);
            int code = Set.of("visibleScope").contains(change) || entry.equals("grants") && change.equals("disable") ? 0 : 4066;
            frozenRead("committed-" + entry + "-" + change, entry, reply, 200, code, wanted, committed, oldAudit, gap, committedEvents());
            if (code != 0) assertFalse(reply.body().toString().contains(tag + "_NEW_PRIVATE_BODY_"), "A refusal must not expose the new private text");
        } finally { gap.release(); close(pool); }
    }
    static Stream<Arguments> rollbacks() { return ENTRIES.stream().flatMap(entry -> Stream.of("scope", "USER", "disable", "actorDept", "actorAdmin").map(change -> Arguments.of(entry, change))); }
    @ParameterizedTest @MethodSource("rollbacks")
    void rolledBackPublicChangesKeepTheOriginalVisibleSnapshot(String entry, String change) throws Exception {
        prepareChange(change); var before = facts(); var oldAudit = audit(); var wanted = expected(entry); var gap = new Gap(entry, template, Position.BEFORE);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var worker = pool.submit(() -> tracedRead(entry, gap)); gap.awaitBoundary();
            var tx = new TransactionTemplate(transactions); tx.setTimeout(20);
            tx.executeWithoutResult(status -> { try { publicChange(change); entityManager.flush(); entityManager.clear(); status.setRollbackOnly(); } catch (Exception e) { throw new RuntimeException(e); } });
            assertEquals(before, facts(), "The public maintenance transaction really rolled back");
            gap.release(); var reply = finish(worker);
            frozenRead("rollback-" + entry + "-" + change, entry, reply, 200, 0, wanted, before, oldAudit, gap);
        } finally { gap.release(); close(pool); }
    }
    static Stream<Arguments> oldSnapshots() { return ENTRIES.stream().flatMap(entry -> Stream.of("scope", "USER", "actorDept").map(change -> Arguments.of(entry, change))); }
    @ParameterizedTest @MethodSource("oldSnapshots")
    void alreadyExecutedAuthorizedSelectMayReturnItsCompleteOriginalSnapshot(String entry, String change) throws Exception {
        prepareChange(change); var oldAudit = audit(); var wanted = expected(entry); var gap = new Gap(entry, template, Position.AFTER);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var worker = pool.submit(() -> tracedRead(entry, gap)); gap.awaitBoundary(); assertFalse(worker.isDone());
            publicChange(change); var committed = facts(); gap.release(); var reply = finish(worker);
            frozenRead("started-snapshot-" + entry + "-" + change, entry, reply, 200, 0, wanted, committed, oldAudit, gap, committedEvents());
        } finally { gap.release(); close(pool); }
    }
    Reply tracedRead(String entry, Gap gap) throws Exception { Gap.TRACE.set(gap); try { return read(entry); } finally { Gap.TRACE.remove(); } }
    Reply finish(Future<Reply> worker) throws Exception { try { return worker.get(20, TimeUnit.SECONDS); } catch (ExecutionException e) { return fail("Unexpected fixture/request exception", e.getCause()); } }
    void close(ExecutorService pool) throws Exception { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }

    enum Position { BEFORE, AFTER }
    static final class Gap {
        static final ThreadLocal<Gap> TRACE = new ThreadLocal<>();
        final String entry; final Long target; final Position position;
        final CountDownLatch reached = new CountDownLatch(1), resume = new CountDownLatch(1);
        final AtomicInteger hits = new AtomicInteger();
        final List<Map<String, Object>> statements = new ArrayList<>();
        volatile Map<String, Object> boundary;
        Gap(String entry, Long target, Position position) { this.entry = entry; this.target = target; this.position = position; }
        boolean payload(String sql, Map<Integer, Object> params) {
            String n = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            boolean shape = entry.equals("use") ? n.contains("select * from emr_template where id = ?") || n.contains("select t.* from emr_template t")
                    : n.contains("select g.id") && n.contains("g.grantee_type") && n.contains("granted_by_name");
            return shape && params.values().stream().anyMatch(v -> v instanceof Number number && number.longValue() == target);
        }
        void observe(String sql, Map<Integer, Object> params, Position at, int fetchSize) {
            if (!sql.toLowerCase(Locale.ROOT).contains("emr_template")) return;
            var event = new LinkedHashMap<String, Object>(); event.put("sql", sql); event.put("parameters", new TreeMap<>(params));
            event.put("position", at.name()); event.put("threadName", Thread.currentThread().getName());
            event.put("threadId", Thread.currentThread().threadId()); event.put("time", Instant.now().toString()); event.put("fetchSize", fetchSize);
            event.put("statementOrdinal", statements.size() + 1); statements.add(event);
            if (at == position && payload(sql, params)) {
                assertEquals(1, hits.incrementAndGet(), "Exactly one selected payload statement may hit the test-only gap");
                assertEquals(0, fetchSize, "PG JDBC default buffered results establish the AFTER execution snapshot");
                boundary = new LinkedHashMap<>(event); reached.countDown();
                try { assertTrue(resume.await(20, TimeUnit.SECONDS), "No release of the exact JDBC boundary"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
        }
        void awaitBoundary() throws InterruptedException { assertTrue(reached.await(15, TimeUnit.SECONDS), "Exact payload SQL/target did not reach the test-only boundary"); assertNotNull(boundary); assertEquals(1, hits.get()); System.out.println("V146 JDBC_BOUNDARY entry=" + entry + " at=" + position + " target=" + target + " proof=" + boundary + " pgLockWaitClaim=false"); }
        void release() { resume.countDown(); }
        Map<String, Object> proof() { return Map.of("kind", "test-only exact JDBC statement boundary", "postgresLockWaitClaim", false, "entry", entry, "target", target, "position", position, "hits", hits.get(), "boundary", boundary, "statements", List.copyOf(statements)); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class JdbcConfiguration {
        @Bean @Primary JdbcTemplate v146BoundaryJdbcTemplate(DataSource actual) { return new JdbcTemplate(new BoundaryDataSource(actual)); }
    }
    static final class BoundaryDataSource extends DelegatingDataSource {
        // Keep JpaTransactionManager's original DataSource resource identity:
        // a test decorator must not accidentally put maintenance JDBC on an
        // independent connection outside the real @Transactional boundary.
        BoundaryDataSource(DataSource actual) { super(new TransactionAwareDataSourceProxy(actual)); }
        @Override public Connection getConnection() throws SQLException { return connection(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return connection(super.getConnection(user, password)); }
        Connection connection(Connection actual) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                Object result = invoke(actual, method, args);
                if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql && result instanceof PreparedStatement statement) return statement(statement, sql);
                return result;
            });
        }
        PreparedStatement statement(PreparedStatement actual, String sql) {
            var parameters = new TreeMap<Integer, Object>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                if (method.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) parameters.put(index, method.getName().equals("setNull") ? null : args[1]);
                if (method.getName().equals("clearParameters")) parameters.clear();
                var trace = Gap.TRACE.get(); boolean query = method.getName().equals("executeQuery") && (args == null || args.length == 0);
                if (query && trace != null) trace.observe(sql, parameters, Position.BEFORE, actual.getFetchSize());
                Object result = invoke(actual, method, args);
                if (query && trace != null) trace.observe(sql, parameters, Position.AFTER, actual.getFetchSize());
                return result;
            });
        }
        static Object invoke(Object actual, Method method, Object[] args) throws Throwable {
            try { return method.invoke(actual, args); } catch (InvocationTargetException e) { throw e.getCause(); }
        }
    }
    @AfterEach void cleanup() {
        Gap.TRACE.remove(); SecurityContextHolder.clearContext();
        if (template != null) {
            jdbc.update("delete from emr_template_field where template_id=?", template);
            jdbc.update("delete from emr_template_grant where template_id=?", template);
            jdbc.update("delete from emr_template where id=?", template);
        }
        for (var who : people) { jdbc.update("delete from sys_user_role where user_id=?", who.id()); jdbc.update("delete from sys_user where id=?", who.id()); }
        if (dept != null) jdbc.update("delete from sys_dept where id in (?,?)", dept, otherDept);
    }
}
