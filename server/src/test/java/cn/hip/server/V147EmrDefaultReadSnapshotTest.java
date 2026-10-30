package cn.hip.server;

import cn.hip.medtech.service.EmrTemplateService;
import cn.hip.platform.core.common.HipBizException;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real JWT and actual JDBC execution boundaries, not PostgreSQL lock waits.
 * The private old-body cases begin with a reader who has NEVER had access. The
 * body is publicly replaced and committed BEFORE a later public grant commits.
 * No database snapshot ever contained that old private nonce and reader rights.
 * The old grant query / new complete payload query is paused before execution.
 * An independently authorized, already-executed snapshot is tested separately.
 * There is no outer rollback test transaction or production test hook. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(V147EmrDefaultReadSnapshotTest.JdbcConfiguration.class)
class V147EmrDefaultReadSnapshotTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired EmrTemplateService service;

    static final String PATH = "/api/emr-templates/default";
    static final List<String> DUTIES = List.of("ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN");
    static final List<String> TABLES = List.of("empi_patient", "outp_schedule", "outp_registration",
            "outp_emr", "outp_diagnosis", "outp_diagnosis_favorite", "outp_diagnosis_version", "emr_version",
            "inp_admission", "inp_medical_record", "outp_order", "outp_charge", "inp_order", "inp_order_exec",
            "inp_deposit", "inp_settlement", "emr_template", "emr_template_field", "emr_template_grant",
            "cdr_document", "emr_consent", "fin_print_log", "lis_sample", "ris_exam", "path_specimen");
    record Staff(Long id, String name, String token) {}
    record Reply(int http, JsonNode body) { int code() { return body.path("code").asInt(); } }
    record Audited(String method, String path, int status, String username) {}
    String tag, oldNonce, newNonce, recordType = "PROGRESS";
    Long dept, otherDept, template, replacement, grantId;
    Staff actor, owner, admin;
    final List<Staff> people = new ArrayList<>();
    final List<Long> ownTemplates = new ArrayList<>();
    final List<Map<String, Object>> maintenance = new ArrayList<>();
    final List<Map<String, Object>> internalInvocations = new ArrayList<>();
    int artifact;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext(); tag = "V147T" + Long.toUnsignedString(System.nanoTime(), 36);
        oldNonce = tag + "_OLD_NEVER_AUTHORIZED_PRIVATE_"; newNonce = tag + "_NEW_ONLY_AFTER_OLD_REMOVED_";
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag + "D", tag + "默认科");
        otherDept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag + "X", tag + "读者科");
        actor = employee("actor", "DOCTOR_OUTP", otherDept); owner = employee("owner", "DOCTOR_OUTP", dept); admin = employee("admin", "ADMIN", otherDept);
        template = newTemplate(tag + "默认", oldNonce, true); replacement = newTemplate(tag + "替换", tag + "替换正文", false);
        jdbc.update("insert into emr_template_field(template_id,field_code,label,datatype,required,sort_no) values (?,'f','原字段','TEXT',false,1)", template);
    }
    Staff employee(String suffix, String role, Long department) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id", Long.class, name, name, department);
        Staff who = new Staff(id, name, jwt.issue(name)); people.add(who); roles(who, role); return who;
    }
    void roles(Staff who, String... values) {
        jdbc.update("delete from sys_user_role where user_id=?", who.id());
        for (String role : values) assertEquals(1, jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", who.id(), role));
    }
    Long newTemplate(String name, String body, boolean isDefault) {
        Long id = jdbc.queryForObject("""
                insert into emr_template(dept_id,name,content,template_type,scope,owner_id,record_type,enabled,is_default,created_by)
                values (?,?,?,'EMR','DEPT',?,'PROGRESS',true,?,?) returning id
                """, Long.class, dept, name, body, owner.id(), isDefault, owner.id());
        ownTemplates.add(id); return id;
    }
    void configure(String scope, boolean owned) {
        jdbc.update("delete from emr_template_grant where template_id=?", template);
        // Non-DEPT+dept historical combinations are controlled Java fixtures,
        // never represented as constructible through the public create API.
        jdbc.update("update emr_template set scope=?,owner_id=? where id=?", scope, owned ? actor.id() : owner.id(), template);
    }
    Long grant(String kind, Long grantee) { return jdbc.queryForObject("insert into emr_template_grant(template_id,grantee_type,grantee_id,granted_by) values (?,?,?,?) returning id", Long.class, template, kind, grantee, owner.id()); }
    String endpoint() { return PATH + "?deptId=" + dept + "&recordType=" + URLEncoder.encode(recordType, StandardCharsets.UTF_8); }
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
    Reply read() throws Exception { return call("GET", endpoint(), null, actor.token()); }
    List<String> rows(String table, String order) { return jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by " + order, String.class); }
    Map<String, List<String>> facts() {
        var result = new LinkedHashMap<String, List<String>>(); for (String table : TABLES) result.put(table, rows(table, "id"));
        result.put("config", rows("sys_config", "cfg_key")); result.put("roles", rows("sys_user_role", "user_id,role_id"));
        result.put("users", jdbc.queryForList("select ((to_jsonb(t)-'password') || jsonb_build_object('passwordDigest',md5(t.password)))::text from sys_user t order by id", String.class)); return result;
    }
    List<Map<String, Object>> audit() {
        // Preserve the exact PostgreSQL timestamp text, including microseconds,
        // in the seven-column export; no application Date truncation here.
        return jdbc.queryForList("select id,username,method,path,http_status,client_ip,created_at::text as created_at from sys_audit_log order by id");
    }
    void audited(List<Map<String, Object>> old, Audited... events) {
        var all = audit(); var original = new HashMap<Object, Map<String, Object>>(); old.forEach(r -> original.put(r.get("id"), r)); var added = new ArrayList<Map<String, Object>>();
        for (var r : all) { if (original.containsKey(r.get("id"))) assertEquals(original.remove(r.get("id")), r); else added.add(r); }
        assertTrue(original.isEmpty()); assertEquals(events.length, added.size(), added.toString()); var remaining = new ArrayList<>(added);
        for (var e : events) {
            var matching = remaining.stream().filter(r -> Objects.equals(r.get("username"), e.username()) && Objects.equals(r.get("method"), e.method())
                    && Objects.equals(r.get("path"), e.path()) && ((Number) r.get("http_status")).intValue() == e.status()).toList();
            assertFalse(matching.isEmpty(), "Missing actual audit: " + e); var row = matching.getFirst();
            assertEquals(Set.of("id", "username", "method", "path", "http_status", "client_ip", "created_at"), row.keySet()); assertEquals("127.0.0.1", row.get("client_ip")); assertNotNull(row.get("created_at")); remaining.remove(row);
        }
        assertTrue(remaining.isEmpty());
    }
    JsonNode wire(Object value) { try { return mapper.readTree(mapper.writeValueAsBytes(value)); } catch (Exception e) { throw new AssertionError(e); } }
    JsonNode expectedCandidate() {
        var rows = jdbc.queryForList("select * from emr_template where dept_id=? and record_type=? and is_default and enabled limit 1", dept, recordType.trim().toUpperCase());
        return wire(rows.isEmpty() ? null : rows.getFirst());
    }
    void response(Reply reply, int http, int code) {
        var keys = new HashSet<String>(); reply.body().fieldNames().forEachRemaining(keys::add); assertEquals(Set.of("code", "message", "data"), keys);
        assertAll(() -> assertEquals(http, reply.http(), reply.body().toString()), () -> assertEquals(code, reply.code(), reply.body().toString()));
        if (code != 0) assertTrue(reply.body().path("data").isNull());
    }
    void evidence(String label, Map<String, List<String>> before, Map<String, List<String>> after, List<Map<String, Object>> oldAudit, Reply reply, Gap gap) {
        String folder = System.getProperty("hip.v147.evidenceDir"); if (folder == null) return;
        try {
            Path directory = Path.of(folder).toAbsolutePath().normalize(); assertFalse(directory.toString().toLowerCase(Locale.ROOT).contains("onedrive")); Files.createDirectories(directory);
            var value = new LinkedHashMap<String, Object>(); value.put("label", label); value.put("tag", tag); value.put("kind", reply == null ? "trusted-internal-service" : "real-JWT-HTTP");
            value.put("http", reply == null ? null : reply.http()); value.put("response", reply == null ? null : reply.body()); value.put("before", before); value.put("after", after);
            value.put("oldAudit", oldAudit); value.put("currentAudit", audit()); value.put("publicMaintenance", maintenance); value.put("internalInvocations", internalInvocations);
            value.put("oldNonce", oldNonce); value.put("newNonce", newNonce); if (gap != null) value.put("jdbcBoundary", gap.proof());
            Files.writeString(directory.resolve(tag + "-" + (++artifact) + ".json"), mapper.writeValueAsString(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Exception e) { throw new AssertionError("Cannot preserve original complete case", e); }
    }
    void frozen(String label, Reply reply, int http, int code, JsonNode wanted, Map<String, List<String>> before, List<Map<String, Object>> oldAudit, Gap gap, Audited... extra) {
        var after = facts(); evidence(label, before, after, oldAudit, reply, gap);
        var events = new ArrayList<>(Arrays.asList(extra)); if (http != 200) events.add(new Audited("GET", PATH, http, http == 401 ? null : actor.name()));
        System.out.println("V147 CASE " + label + " actual=" + reply.http() + "/" + reply.code() + " expected=" + http + "/" + code + " allFactsFrozen=" + before.equals(after));
        assertAll(() -> {
            response(reply, http, code); if (code == 0) {
                assertEquals(wanted, reply.body().path("data"), "Default DTO must belong to one authorized statement snapshot");
                if (wanted.isObject()) { assertFalse(reply.body().path("data").has("__default_visible")); assertFalse(reply.body().path("data").has("scopeName")); }
            }
        }, () -> assertEquals(before, after, "Read cannot mutate any monitored business/auth/config fact"), () -> audited(oldAudit, events.toArray(Audited[]::new)));
    }
    void internal(String label, Long uid, Long requestedDept, String requestedType, int wantedCode, JsonNode wanted) {
        var before = facts(); var oldAudit = audit(); int actualCode = 0; Object value = null;
        try { value = service.defaultTemplate(uid, requestedDept, requestedType); } catch (HipBizException e) { actualCode = e.code; }
        var invocation = new LinkedHashMap<String, Object>(); invocation.put("userId", uid); invocation.put("deptId", requestedDept); invocation.put("recordType", requestedType);
        invocation.put("actualCode", actualCode); invocation.put("actualValue", wire(value)); internalInvocations.add(invocation);
        var after = facts(); evidence(label, before, after, oldAudit, null, null);
        assertEquals(wantedCode, actualCode); if (wantedCode == 0) assertEquals(wanted, wire(value)); assertEquals(before, after); assertEquals(oldAudit, audit());
    }

    static Stream<Arguments> matrix() { return Stream.of("GLOBAL", "HOSPITAL", "DEPT", "PERSONAL").flatMap(scope -> DUTIES.stream().map(role -> Arguments.of(scope, role))); }
    @ParameterizedTest @MethodSource("matrix") void originalFiveDutiesKeepHistoricalScopeMatrix(String scope, String role) throws Exception {
        configure(scope, false); roles(actor, role); var before = facts(); var audits = audit(); boolean visible = Set.of("GLOBAL", "HOSPITAL").contains(scope) || scope.equals("DEPT") && role.equals("ADMIN");
        frozen("matrix-" + scope + "-" + role, read(), 200, visible ? 0 : 4066, expectedCandidate(), before, audits, null);
    }
    static Stream<Arguments> owned() { return Stream.of("DEPT", "PERSONAL").flatMap(scope -> DUTIES.stream().map(role -> Arguments.of(scope, role))); }
    @ParameterizedTest @MethodSource("owned") void originalDutiesReadTheirOwnHistoricalDefaults(String scope, String role) throws Exception {
        configure(scope, true); roles(actor, role); var before = facts(); var audits = audit(); frozen("owner-" + scope + "-" + role, read(), 200, 0, expectedCandidate(), before, audits, null);
    }
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN"}) void sameDepartmentRemainsVisible(String role) throws Exception {
        roles(actor, role); jdbc.update("update sys_user set dept_id=? where id=?", dept, actor.id()); var before = facts(); var audits = audit(); frozen("same-dept-" + role, read(), 200, 0, expectedCandidate(), before, audits, null);
    }
    static Stream<Arguments> grants() { return Stream.of("DEPT", "PERSONAL").flatMap(scope -> Stream.of("USER", "DEPT").map(kind -> Arguments.of(scope, kind))); }
    @ParameterizedTest @MethodSource("grants") void additiveUserAndDepartmentGrantsKeepTheirOriginalScopePolicy(String scope, String kind) throws Exception {
        configure(scope, false); grant(kind, kind.equals("USER") ? actor.id() : otherDept); var before = facts(); var audits = audit(); frozen("grant-" + scope + "-" + kind, read(), 200, 0, expectedCandidate(), before, audits, null);
    }
    @ParameterizedTest @ValueSource(strings = {"disabled", "unset", "other-dept", "other-type", "no-row"}) void noEnabledDefaultCandidateRemainsNullEvenWhenTheTemplateWouldBeHidden(String state) throws Exception {
        switch (state) {
            case "disabled" -> jdbc.update("update emr_template set enabled=false where id=?", template);
            case "unset" -> jdbc.update("update emr_template set is_default=false where id=?", template);
            case "other-dept" -> jdbc.update("update emr_template set dept_id=? where id=?", otherDept, template);
            case "other-type" -> recordType = "NOT_FOUND";
            case "no-row" -> { jdbc.update("delete from emr_template_field where template_id=?", template); jdbc.update("delete from emr_template where id=?", template); }
            default -> fail(state);
        }
        var before = facts(); var audits = audit(); frozen("no-candidate-" + state, read(), 200, 0, wire(null), before, audits, null);
    }
    static Stream<Arguments> badServiceParams() { return Stream.of(Arguments.of(true, "PROGRESS"), Arguments.of(false, null), Arguments.of(false, ""), Arguments.of(false, "  ")); }
    @ParameterizedTest @MethodSource("badServiceParams") void serviceMissingOrBlankArgumentsKeep4067(boolean noDept, String requested) { internal("service-parameter-" + noDept + "-" + requested, actor.id(), noDept ? null : dept, requested, 4067, null); }
    @ParameterizedTest @ValueSource(strings = {"", "?deptId=1", "?recordType=PROGRESS", "?deptId=&recordType=PROGRESS"}) void missingHttpRequiredParametersKeepOriginal4000Envelope(String query) throws Exception {
        var before = facts(); var audits = audit(); frozen("http-missing-" + query, call("GET", PATH + query, null, actor.token()), 200, 4000, null, before, audits, null);
    }
    @ParameterizedTest @ValueSource(strings = {"", "  "}) void presentBlankHttpRecordTypeStillReachesService4067(String value) throws Exception { recordType = value; var before = facts(); var audits = audit(); frozen("http-blank-" + value, read(), 200, 4067, null, before, audits, null); }
    @ParameterizedTest @ValueSource(strings = {"CASHIER", "INTERFACE", "NO_ROLES", "anonymous", "disabled"}) void originalJwtAndDutyDenialsStayIntact(String who) throws Exception {
        String token = actor.token(); int http = 403, code = 1005;
        if (who.equals("anonymous")) { token = null; http = 401; code = 1000; } else if (who.equals("disabled")) { jdbc.update("update sys_user set enabled=false where id=?", actor.id()); http = 401; code = 1000; } else if (who.equals("NO_ROLES")) roles(actor); else roles(actor, who);
        var before = facts(); var audits = audit(); frozen("entry-" + who, call("GET", endpoint(), null, token), http, code, null, before, audits, null);
    }
    static Stream<Arguments> trusted() { return Stream.of("GLOBAL", "HOSPITAL", "DEPT").flatMap(scope -> Stream.of(true, false).map(missing -> Arguments.of(scope, missing))); }
    @ParameterizedTest @MethodSource("trusted") void trustedNullAndMissingActorSemanticsAreNotTightened(String scope, boolean missing) {
        configure(scope, false); internal("trusted-" + scope + "-" + missing, missing ? 899999999999999L : null, dept, recordType, scope.equals("DEPT") ? 4066 : 0, expectedCandidate());
    }
    @ParameterizedTest @ValueSource(strings = {"RIS", "CONSENT", "OTHER"}) void defaultReadingDoesNotAddATemplateTypeFilter(String type) throws Exception {
        grant("USER", actor.id()); jdbc.update("update emr_template set template_type=? where id=?", type, template); var before = facts(); var audits = audit(); frozen("template-type-" + type, read(), 200, 0, expectedCandidate(), before, audits, null);
    }
    @ParameterizedTest @ValueSource(strings = {"progress", "  pRoGrEsS  "}) void recordTypeStillTrimsAndUppercases(String value) throws Exception { grant("USER", actor.id()); recordType = value; var before = facts(); var audits = audit(); frozen("record-normalize-" + value, read(), 200, 0, expectedCandidate(), before, audits, null); }
    @Test void defaultReadDoesNotTruncateLongRecordTypeLikeTheWriteNormalizer() throws Exception {
        jdbc.update("update emr_template set record_type='ABCDEFGHIJKLMNOP' where id=?", template); recordType = "ABCDEFGHIJKLMNOPQ"; var before = facts(); var audits = audit(); frozen("long-record-type", read(), 200, 0, wire(null), before, audits, null);
    }
    void mutation(String verb, String path, Object body, Staff who) throws Exception {
        var reply = call(verb, path, body, who.token()); var event = new LinkedHashMap<String, Object>(); event.put("method", verb); event.put("path", path); event.put("actor", who.name()); event.put("body", body); event.put("http", reply.http()); event.put("response", reply.body()); maintenance.add(event); response(reply, 200, 0);
    }
    void publicBody() throws Exception { mutation("PUT", "/api/emr-templates/" + template, Map.of("name", tag + "默认", "content", newNonce, "templateType", "EMR", "scope", "DEPT", "deptId", dept, "recordType", "PROGRESS"), owner); }
    void publicGrant(String kind) throws Exception { mutation("POST", "/api/emr-templates/" + template + "/grants", Map.of("granteeType", kind, "granteeId", kind.equals("USER") ? actor.id() : otherDept), owner); }
    void publicActor(String role, Long department) throws Exception { mutation("PUT", "/api/system/users/" + actor.id(), Map.of("username", actor.name(), "realName", actor.name(), "deptId", department, "roleCodes", List.of(role)), admin); }
    Audited[] committedEvents() { return maintenance.stream().map(e -> new Audited((String)e.get("method"), (String)e.get("path"), 200, (String)e.get("actor"))).toArray(Audited[]::new); }
    @Test void ordinaryPublicDepartmentDefaultCreationAndSelectionKeepTheirFullRowContract() throws Exception {
        jdbc.update("update sys_user set dept_id=? where id=?", dept, actor.id()); var audits = audit();
        mutation("POST", "/api/emr-templates/scoped", Map.of("name", tag + "公开默认", "content", "公开默认正文", "templateType", "EMR", "scope", "DEPT", "deptId", dept, "recordType", "PUBLIC"), owner);
        Long id = maintenance.getLast().get("response") instanceof JsonNode n ? n.path("data").asLong() : fail("Missing public create reply"); ownTemplates.add(id);
        mutation("PUT", "/api/emr-templates/" + id + "/default", null, owner); recordType = "PUBLIC"; var before = facts(); frozen("public-normal-default", read(), 200, 0, expectedCandidate(), before, audits, null, committedEvents());
    }
    @ParameterizedTest @ValueSource(strings = {"enable", "set-default"}) void publicRestorationKeepsSelectionUsable(String op) throws Exception {
        jdbc.update("update sys_user set dept_id=? where id=?", dept, actor.id());
        if (op.equals("enable")) jdbc.update("update emr_template set enabled=false where id=?", template); else jdbc.update("update emr_template set is_default=false where id=?", template);
        var audits = audit(); mutation("PUT", "/api/emr-templates/" + template + (op.equals("enable") ? "/enable" : "/default"), null, owner);
        var before = facts(); frozen("public-restore-" + op, read(), 200, 0, expectedCandidate(), before, audits, null, committedEvents());
    }

    static Stream<Arguments> privateGaps() { return Stream.of("DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN").flatMap(role -> Stream.of("USER", "DEPT").map(kind -> Arguments.of(role, kind))); }
    @ParameterizedTest @MethodSource("privateGaps") void removedPrivateBodyMustNeverBeCombinedWithLaterPublicGrant(String role, String kind) throws Exception {
        roles(actor, role); assertEquals(0, jdbc.queryForObject("select count(*) from emr_template_grant where template_id=?", Integer.class, template));
        assertNotEquals(actor.id(), owner.id()); assertNotEquals(dept, otherDept); var audits = audit(); var gap = new Gap(template, dept, GapMode.BEFORE_OLD_GRANT_OR_NEW_PAYLOAD); var pool = Executors.newSingleThreadExecutor();
        try {
            var worker = pool.submit(() -> tracedRead(gap)); gap.awaitBoundary();
            if (gap.oldGrantBoundary()) assertTrue(gap.observedContents.contains(oldNonce), "The old ungranted private body was actually consumed before the grant SELECT gap");
            publicBody(); assertEquals(newNonce, jdbc.queryForObject("select content from emr_template where id=?", String.class, template));
            assertEquals(0, jdbc.queryForObject("select count(*) from emr_template_grant where template_id=?", Integer.class, template), "No access existed while old private text existed");
            publicGrant(kind); var committed = facts(); var wanted = expectedCandidate(); gap.release(); var reply = finish(worker);
            frozen("private-body-then-grant-" + role + "-" + kind, reply, 200, 0, wanted, committed, audits, gap, committedEvents());
            assertFalse(reply.body().toString().contains(oldNonce), "This reader never had any authorized snapshot/cache containing the removed old nonce");
        } finally { gap.release(); close(pool); }
    }
    static Stream<Arguments> beforeChanges() { return Stream.of(Arguments.of("actorDept", "DOCTOR_OUTP"), Arguments.of("actorAdmin", "DOCTOR_OUTP"), Arguments.of("actorAdmin", "NURSE"), Arguments.of("actorAdmin", "QUALITY"), Arguments.of("actorAdmin", "TECHNICIAN"), Arguments.of("disable", "DOCTOR_OUTP"), Arguments.of("unset", "DOCTOR_OUTP"), Arguments.of("replace", "DOCTOR_OUTP"), Arguments.of("scope", "DOCTOR_OUTP"), Arguments.of("revoke", "DOCTOR_OUTP"), Arguments.of("add", "DOCTOR_OUTP")); }
    void prepareChange(String change) {
        if (Set.of("actorDept", "disable", "unset", "replace", "scope", "body").contains(change)) jdbc.update("update sys_user set dept_id=? where id=?", dept, actor.id());
        if (change.equals("actorAdmin")) roles(actor, "ADMIN");
        if (change.equals("revoke")) grantId = grant("USER", actor.id());
    }
    void publicChange(String change, String role) throws Exception {
        switch (change) {
            case "actorDept", "actorAdmin" -> { publicActor(role, otherDept); publicBody(); }
            case "disable" -> mutation("PUT", "/api/emr-templates/" + template + "/disable", null, owner);
            case "unset" -> mutation("DELETE", "/api/emr-templates/" + template + "/default", null, owner);
            case "replace" -> mutation("PUT", "/api/emr-templates/" + replacement + "/default?replace=true", null, owner);
            case "scope" -> mutation("PUT", "/api/emr-templates/" + template, Map.of("name", tag + "默认", "content", newNonce, "templateType", "EMR", "scope", "PERSONAL", "recordType", "PROGRESS"), owner);
            case "revoke" -> mutation("DELETE", "/api/emr-templates/" + template + "/grants/" + grantId, null, owner);
            case "add" -> { publicBody(); publicGrant("USER"); }
            case "body" -> publicBody();
            default -> fail(change);
        }
    }
    @ParameterizedTest @MethodSource("beforeChanges") void commitsBeforeCandidateSelectUseTheSameActorAndCandidateSnapshot(String change, String role) throws Exception {
        prepareChange(change); var audits = audit(); var gap = new Gap(template, dept, GapMode.BEFORE_PAYLOAD); var pool = Executors.newSingleThreadExecutor();
        try {
            var worker = pool.submit(() -> tracedRead(gap)); gap.awaitBoundary(); publicChange(change, role); var committed = facts(); var wanted = expectedCandidate(); gap.release(); var reply = finish(worker);
            int code = Set.of("actorDept", "actorAdmin", "revoke").contains(change) ? 4066 : 0;
            frozen("before-candidate-" + change + "-" + role, reply, 200, code, wanted, committed, audits, gap, committedEvents());
        } finally { gap.release(); close(pool); }
    }
    @ParameterizedTest @ValueSource(strings = {"grantUSER", "grantDEPT", "actorDept", "actorAdmin", "disable", "unset", "replace"}) void rolledBackPublicChangesDoNotAlterTheOriginalReadResult(String change) throws Exception {
        boolean grants = change.startsWith("grant"); if (!grants) prepareChange(change); var before = facts(); var audits = audit(); var wanted = expectedCandidate();
        var gap = new Gap(template, dept, grants ? GapMode.BEFORE_OLD_GRANT_OR_NEW_PAYLOAD : GapMode.BEFORE_PAYLOAD); var pool = Executors.newSingleThreadExecutor();
        try {
            var worker = pool.submit(() -> tracedRead(gap)); gap.awaitBoundary(); var tx = new TransactionTemplate(transactions); tx.setTimeout(20);
            tx.executeWithoutResult(status -> { try { if (grants) { publicBody(); publicGrant(change.equals("grantUSER") ? "USER" : "DEPT"); } else publicChange(change, "DOCTOR_OUTP"); entityManager.flush(); entityManager.clear(); status.setRollbackOnly(); } catch (Exception e) { throw new RuntimeException(e); } });
            assertEquals(before, facts(), "The original public maintenance transaction actually rolled back"); gap.release();
            frozen("rollback-" + change, finish(worker), 200, grants ? 4066 : 0, wanted, before, audits, gap);
        } finally { gap.release(); close(pool); }
    }
    @ParameterizedTest @ValueSource(strings = {"disable", "unset", "replace", "body", "actorDept"}) void alreadyExecutedAuthorizedCandidateMayReturnItsCompleteOriginalSnapshot(String change) throws Exception {
        prepareChange(change); var wanted = expectedCandidate(); var audits = audit(); var gap = new Gap(template, dept, GapMode.AFTER_PAYLOAD); var pool = Executors.newSingleThreadExecutor();
        try {
            var worker = pool.submit(() -> tracedRead(gap)); gap.awaitBoundary(); publicChange(change, "DOCTOR_OUTP"); var committed = facts(); gap.release();
            frozen("authorized-started-snapshot-" + change, finish(worker), 200, 0, wanted, committed, audits, gap, committedEvents());
        } finally { gap.release(); close(pool); }
    }
    Reply tracedRead(Gap gap) throws Exception { Gap.TRACE.set(gap); try { return read(); } finally { Gap.TRACE.remove(); } }
    Reply finish(Future<Reply> worker) throws Exception { try { return worker.get(20, TimeUnit.SECONDS); } catch (ExecutionException e) { return fail("Unexpected request/fixture exception", e.getCause()); } }
    void close(ExecutorService pool) throws Exception { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }

    enum GapMode { BEFORE_OLD_GRANT_OR_NEW_PAYLOAD, BEFORE_PAYLOAD, AFTER_PAYLOAD }
    static final class Gap {
        static final ThreadLocal<Gap> TRACE = new ThreadLocal<>();
        final Long target, department; final GapMode mode; final CountDownLatch reached = new CountDownLatch(1), resume = new CountDownLatch(1); final AtomicInteger hits = new AtomicInteger();
        final List<Map<String, Object>> statements = new ArrayList<>(); final List<String> observedContents = new ArrayList<>(); volatile Map<String, Object> boundary;
        Gap(Long target, Long department, GapMode mode) { this.target = target; this.department = department; this.mode = mode; }
        static String normal(String sql) { return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT); }
        boolean payload(String sql, Map<Integer, Object> params) { String n = normal(sql); return n.contains("from emr_template") && n.contains("is_default") && n.contains("enabled") && n.contains("limit 1") && params.values().stream().anyMatch(v -> v instanceof Number num && num.longValue() == department) && params.containsValue("PROGRESS"); }
        boolean oldGrant(String sql, Map<Integer, Object> params) { return normal(sql).startsWith("select count(*) from emr_template_grant") && params.values().stream().anyMatch(v -> v instanceof Number num && num.longValue() == target); }
        boolean oldGrantBoundary() { return boundary != null && boundary.get("selectedBoundary").equals("old-grant-query-before"); }
        void observe(String sql, Map<Integer, Object> params, boolean after, int fetchSize) {
            if (!sql.toLowerCase(Locale.ROOT).contains("emr_template")) return;
            var event = new LinkedHashMap<String, Object>(); event.put("sql", sql); event.put("parameters", new TreeMap<>(params)); event.put("position", after ? "AFTER" : "BEFORE"); event.put("time", Instant.now().toString());
            event.put("threadName", Thread.currentThread().getName()); event.put("threadId", Thread.currentThread().threadId()); event.put("fetchSize", fetchSize); event.put("statementOrdinal", statements.size() + 1); statements.add(event);
            String selected = null;
            if (mode == GapMode.BEFORE_OLD_GRANT_OR_NEW_PAYLOAD && !after) {
                if (oldGrant(sql, params)) selected = "old-grant-query-before";
                else if (payload(sql, params) && sql.contains("__default_visible")) selected = "new-complete-payload-before";
            } else if (payload(sql, params) && (mode == GapMode.AFTER_PAYLOAD) == after && mode != GapMode.BEFORE_OLD_GRANT_OR_NEW_PAYLOAD) selected = after ? "payload-after-real-executeQuery" : "payload-before";
            if (selected != null) {
                assertEquals(1, hits.incrementAndGet()); assertEquals(0, fetchSize); event.put("selectedBoundary", selected); boundary = new LinkedHashMap<>(event); reached.countDown();
                try { assertTrue(resume.await(20, TimeUnit.SECONDS), "Exact JDBC boundary was not released"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
        }
        void awaitBoundary() throws InterruptedException { assertTrue(reached.await(15, TimeUnit.SECONDS), "Expected exact JDBC SQL/args boundary did not occur"); assertEquals(1, hits.get()); System.out.println("V147 JDBC_BOUNDARY mode=" + mode + " proof=" + boundary + " observedContents=" + observedContents + " pgLockWaitClaim=false"); }
        void release() { resume.countDown(); }
        Map<String, Object> proof() { return Map.of("kind", "test-only exact JDBC statement boundary", "postgresLockWaitClaim", false, "mode", mode, "hits", hits.get(), "boundary", boundary, "statements", List.copyOf(statements), "observedOriginalPayloadContents", List.copyOf(observedContents)); }
    }
    @TestConfiguration(proxyBeanMethods = false) static class JdbcConfiguration {
        @Bean @Primary JdbcTemplate v147BoundaryJdbcTemplate(DataSource actual) { return new JdbcTemplate(new BoundaryDataSource(actual)); }
    }
    static final class BoundaryDataSource extends DelegatingDataSource {
        BoundaryDataSource(DataSource actual) { super(new TransactionAwareDataSourceProxy(actual)); }
        @Override public Connection getConnection() throws SQLException { return connection(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return connection(super.getConnection(user, password)); }
        Connection connection(Connection actual) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                Object value = invoke(actual, method, args); if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql && value instanceof PreparedStatement ps) return statement(ps, sql); return value;
            });
        }
        PreparedStatement statement(PreparedStatement actual, String sql) {
            var parameters = new TreeMap<Integer, Object>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                if (method.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) parameters.put(index, method.getName().equals("setNull") ? null : args[1]); if (method.getName().equals("clearParameters")) parameters.clear();
                var trace = Gap.TRACE.get(); boolean query = method.getName().equals("executeQuery") && (args == null || args.length == 0);
                if (query && trace != null) trace.observe(sql, parameters, false, actual.getFetchSize()); Object value = invoke(actual, method, args);
                if (query && trace != null) { trace.observe(sql, parameters, true, actual.getFetchSize()); if (value instanceof ResultSet rs && trace.payload(sql, parameters)) return result(rs, trace); } return value;
            });
        }
        ResultSet result(ResultSet actual, Gap trace) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                Object value = invoke(actual, method, args);
                if (method.getName().equals("getObject") && args != null && args.length == 1 && args[0] instanceof Integer index && actual.getMetaData().getColumnLabel(index).equals("content") && value instanceof String text) trace.observedContents.add(text);
                return value;
            });
        }
        static Object invoke(Object actual, Method method, Object[] args) throws Throwable { try { return method.invoke(actual, args); } catch (InvocationTargetException e) { throw e.getCause(); } }
    }
    @AfterEach void cleanup() {
        Gap.TRACE.remove(); SecurityContextHolder.clearContext();
        for (Long id : ownTemplates) { jdbc.update("delete from emr_template_field where template_id=?", id); jdbc.update("delete from emr_template_grant where template_id=?", id); jdbc.update("delete from emr_template where id=?", id); }
        for (var who : people) { jdbc.update("delete from sys_user_role where user_id=?", who.id()); jdbc.update("delete from sys_user where id=?", who.id()); }
        if (dept != null) jdbc.update("delete from sys_dept where id in (?,?)", dept, otherDept);
    }
}
