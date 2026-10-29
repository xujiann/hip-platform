package cn.hip.server;

import cn.hip.medtech.service.EmrTemplateService;
import cn.hip.medtech.web.MedTechController;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real JWT requests, independently committed fixtures and requests, no outer rollback test.
 * The named holder calls the original authenticated template controller in a real
 * transaction. Holder SQL supplies only barriers; employee changes use the public
 * system API. Full-column facts include the clinical side effects and old history.
 * A Future is not labelled as a PostgreSQL PID: reachability from the exact holder
 * and an incomplete request establish contention. */
@SpringBootTest
@AutoConfigureMockMvc
class V145StructuredEmrParentAccessTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MedTechController templates;

    record Staff(Long id, String name, String token) {}
    record Reply(int http, JsonNode body) { int code() { return body.path("code").asInt(); } }
    static final List<String> ENTRIES = List.of("OUTP", "INP");
    static final List<String> TABLES = List.of("empi_patient", "outp_schedule", "outp_registration",
            "outp_emr", "outp_diagnosis", "outp_diagnosis_favorite", "outp_diagnosis_version", "emr_version",
            "inp_admission", "inp_medical_record", "outp_order", "outp_charge", "inp_order", "inp_order_exec",
            "inp_deposit", "inp_settlement", "emr_template", "emr_template_field", "emr_template_grant",
            "cdr_document", "emr_consent", "fin_print_log", "lis_sample", "ris_exam", "path_specimen");
    String tag;
    Long dept, otherDept, patient, schedule, registration, admission, outpRecord, oldInpRecord, template, field;
    Staff actor, owner, admin;
    final List<Staff> people = new ArrayList<>();
    int artifact;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        tag = "V145T" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag + "D", tag + "本科");
        otherDept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag + "X", tag + "他科");
        actor = employee("actor", "DOCTOR_OUTP", dept);
        owner = employee("owner", "DOCTOR_OUTP", otherDept);
        admin = employee("admin", "ADMIN", dept);
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",
                Long.class, tag, tag + "模拟患者");
        schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee)
                values (?,?::date,5,1,1,0) returning id
                """, Long.class, dept, BusinessDates.today().toString());
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status)
                values (1,?,?,?,?,?::date,0,'VISITED') returning id
                """, Long.class, patient, schedule, dept, actor.id(), BusinessDates.today().toString());
        outpRecord = jdbc.queryForObject("""
                insert into outp_emr(registration_id,chief_complaint,present_illness,past_history,physical_exam,advice,doctor_id)
                values (?,'原主诉','原现病史','原既往史','原查体','原处理',?) returning id
                """, Long.class, registration, actor.id());
        jdbc.update("insert into outp_diagnosis(registration_id,icd_code,icd_name,primary_diag) values (?,'J00','旧诊断',true)", registration);
        var bed = jdbc.queryForMap("select id,ward_id from inp_bed order by id limit 1");
        admission = jdbc.queryForObject("""
                insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status,admit_at)
                values (?,?,?,?,?,?,'IN_HOSPITAL',now()) returning id
                """, Long.class, tag, patient, dept, bed.get("ward_id"), bed.get("id"), actor.id());
        oldInpRecord = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id,record_type,title,content,doctor_id,signature,signed_at)
                values (?,'PROGRESS','历史病历','原住院正文',?,'V145历史签名',now()) returning id
                """, Long.class, admission, actor.id());
        template = jdbc.queryForObject("""
                insert into emr_template(dept_id,name,content,template_type,scope,owner_id,record_type,enabled,created_by)
                values (null,?,'模板目录正文','EMR','HOSPITAL',?,'PROGRESS',true,?) returning id
                """, Long.class, tag + "模板", owner.id(), owner.id());
        field = jdbc.queryForObject("""
                insert into emr_template_field(template_id,field_code,label,datatype,required,sort_no)
                values (?,'finding','临床所见','TEXT',false,1) returning id
                """, Long.class, template);
    }

    Staff employee(String suffix, String role, Long department) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id",
                Long.class, name, name, department);
        Staff staff = new Staff(id, name, jwt.issue(name)); people.add(staff); roles(staff, role); return staff;
    }
    void roles(Staff staff, String... values) {
        jdbc.update("delete from sys_user_role where user_id=?", staff.id());
        for (String role : values) assertEquals(1, jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", staff.id(), role));
    }
    Long grant(String type, Long id) {
        return jdbc.queryForObject("insert into emr_template_grant(template_id,grantee_type,grantee_id,granted_by) values (?,?,?,?) returning id",
                Long.class, template, type, id, owner.id());
    }
    void scope(String value, Long creator, Long department) {
        jdbc.update("update emr_template set scope=?,owner_id=?,dept_id=?,enabled=true where id=?", value, creator, department, template);
        jdbc.update("delete from emr_template_grant where template_id=?", template);
    }
    String endpoint(String entry) { return entry.equals("OUTP") ? "/api/outpatient/doctor/" + registration + "/emr" : "/api/inpatient/admissions/" + admission + "/records"; }
    String method(String entry) { return entry.equals("OUTP") ? "PUT" : "POST"; }
    Object body(String entry, Long parent, Map<String, Object> values) {
        var result = new LinkedHashMap<String, Object>();
        if (entry.equals("OUTP")) {
            result.put("emr", Map.of("chiefComplaint", "新主诉", "presentIllness", "新现病史", "pastHistory", "新既往史", "physicalExam", "新查体", "advice", "新处理"));
            result.put("diagnoses", List.of(Map.of("icdCode", "J18.9", "icdName", "新诊断")));
        } else { result.put("recordType", "PROGRESS"); result.put("title", "新病程"); result.put("content", "新现病史"); }
        result.put("templateId", parent); result.put("fields", values); return result;
    }
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
    Reply save(String entry, Map<String, Object> values) throws Exception { return call(method(entry), endpoint(entry), body(entry, template, values), actor.token()); }
    List<String> rows(String table, String order) { return jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by " + order, String.class); }
    Map<String, List<String>> facts() {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : TABLES) result.put(table, rows(table, "id"));
        result.put("config", rows("sys_config", "cfg_key"));
        result.put("roles", rows("sys_user_role", "user_id,role_id"));
        result.put("users", jdbc.queryForList("select ((to_jsonb(t)-'password') || jsonb_build_object('passwordDigest',md5(t.password)))::text from sys_user t order by id", String.class));
        return result;
    }
    List<String> audit() { return rows("sys_audit_log", "id"); }
    void evidence(String label, Map<String, List<String>> before, Map<String, List<String>> after, Reply reply) {
        String folder = System.getProperty("hip.v145.evidenceDir");
        if (folder == null) return;
        try {
            Path directory = Path.of(folder).toAbsolutePath().normalize();
            assertFalse(directory.toString().toLowerCase(Locale.ROOT).contains("onedrive"));
            Files.createDirectories(directory);
            var payload = new LinkedHashMap<String, Object>(); payload.put("label", label); payload.put("tag", tag);
            payload.put("http", reply.http()); payload.put("response", reply.body()); payload.put("before", before); payload.put("after", after);
            Files.writeString(directory.resolve(tag + "-" + (++artifact) + ".json"), mapper.writeValueAsString(payload), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Exception e) { throw new AssertionError("Cannot preserve original full-column proof", e); }
    }
    void response(Reply reply, int http, int code) {
        var keys = new HashSet<String>(); reply.body().fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("code", "message", "data"), keys);
        assertAll(() -> assertEquals(http, reply.http(), reply.body().toString()), () -> assertEquals(code, reply.code(), reply.body().toString()));
        if (code != 0) assertTrue(reply.body().path("data").isNull());
    }
    void denied(String label, Reply reply, int http, int code, Map<String, List<String>> before) {
        var after = facts(); evidence(label, before, after, reply);
        System.out.println("V145 CASE " + label + " actual=" + reply.http() + "/" + reply.code() + " expected=" + http + "/" + code + " allFactsFrozen=" + before.equals(after));
        assertAll(() -> response(reply, http, code), () -> assertEquals(before, after, "A refused structured save must leave all clinical/version/diagnosis/favorite/template/config/auth facts intact"));
    }
    JsonNode json(String value) { try { return mapper.readTree(value); } catch (Exception e) { throw new AssertionError(e); } }
    void accepted(String entry, Reply reply, Map<String, List<String>> before, boolean structured) {
        response(reply, 200, 0); var after = facts(); evidence("positive-" + entry, before, after, reply);
        Set<String> allowed = entry.equals("OUTP") ? Set.of("outp_emr", "outp_diagnosis", "outp_diagnosis_favorite", "outp_diagnosis_version", "emr_version") : Set.of("inp_medical_record", "emr_version");
        for (String key : before.keySet()) if (!allowed.contains(key)) assertEquals(before.get(key), after.get(key), key);
        if (entry.equals("OUTP")) {
            var r = jdbc.queryForMap("select * from outp_emr where id=?", outpRecord);
            assertEquals("新主诉", r.get("chief_complaint")); assertEquals(actor.id(), r.get("doctor_id"));
            assertTrue(r.get("present_illness").toString().startsWith("新现病史"));
            if (structured) { assertEquals(template, r.get("template_id")); assertNotNull(r.get("content_json")); }
            assertEquals(1, jdbc.queryForObject("select count(*) from outp_diagnosis where registration_id=? and icd_code='J18.9' and icd_name='新诊断' and primary_diag", Integer.class, registration));
            assertEquals(1, jdbc.queryForObject("select use_count from outp_diagnosis_favorite where user_id=? and icd_code='J18.9'", Integer.class, actor.id()));
            assertEquals(1, jdbc.queryForObject("select count(*) from outp_diagnosis_version where registration_id=?", Integer.class, registration));
            assertEquals(1, jdbc.queryForObject("select count(*) from emr_version where emr_type='OUTP' and emr_id=? and source='MANUAL'", Integer.class, outpRecord));
            assertEquals(before.get("outp_emr").stream().filter(v -> json(v).path("id").asLong() != outpRecord).toList(), after.get("outp_emr").stream().filter(v -> json(v).path("id").asLong() != outpRecord).toList());
        } else {
            Long id = reply.body().path("data").path("id").asLong();
            var r = jdbc.queryForMap("select * from inp_medical_record where id=?", id);
            assertEquals(admission, r.get("admission_id")); assertEquals(actor.id(), r.get("doctor_id"));
            assertTrue(r.get("content").toString().startsWith("新现病史"));
            assertEquals(structured ? template : null, r.get("template_id"));
            assertEquals(structured, r.get("content_json") != null);
            assertEquals(1, jdbc.queryForObject("select count(*) from emr_version where emr_type='INP' and emr_id=? and source='MANUAL'", Integer.class, id));
            assertEquals(before.get("inp_medical_record"), after.get("inp_medical_record").stream().filter(v -> json(v).path("id").asLong() != id).toList(), "Every old record including signed history remains exact");
        }
        for (String key : List.of("emr_version", "outp_diagnosis_version", "outp_diagnosis_favorite")) {
            var beforeRows = before.get(key); var afterRows = after.get(key);
            for (String row : beforeRows) assertTrue(afterRows.contains(row), "Existing row drifted: " + key);
        }
    }
    void audited(List<String> before, String entry, int status, int extra) {
        var now = audit(); assertTrue(now.containsAll(before), "Original full audit rows changed");
        var fresh = now.stream().filter(r -> !before.contains(r)).map(this::json).toList();
        assertEquals(1 + extra, fresh.size(), fresh.toString());
        assertEquals(1, fresh.stream().filter(r -> r.path("method").asText().equals(method(entry)) && r.path("path").asText().equals(endpoint(entry)) && r.path("username").asText().equals(actor.name()) && r.path("http_status").asInt() == status && r.path("client_ip").asText().equals("127.0.0.1")).count());
    }

    static Stream<Arguments> visibleMatrix() {
        return ENTRIES.stream().flatMap(e -> Stream.of("global", "hospital", "same-dept", "own-dept", "admin-dept", "user-dept", "granted-dept", "own-personal", "user-personal", "dept-personal", "other-type", "other-record-type").map(m -> Arguments.of(e, m)));
    }
    @ParameterizedTest @MethodSource("visibleMatrix")
    void originalVisibilityGrantsAndTemplateMetadataRemainUsable(String entry, String scenario) throws Exception {
        switch (scenario) {
            case "global" -> scope("GLOBAL", owner.id(), null);
            case "hospital" -> scope("HOSPITAL", owner.id(), null);
            case "same-dept" -> scope("DEPT", owner.id(), dept);
            case "own-dept" -> scope("DEPT", actor.id(), otherDept);
            case "admin-dept" -> { scope("DEPT", owner.id(), otherDept); roles(actor, "ADMIN"); }
            case "user-dept" -> { scope("DEPT", owner.id(), otherDept); grant("USER", actor.id()); }
            case "granted-dept" -> { scope("DEPT", owner.id(), otherDept); grant("DEPT", dept); }
            case "own-personal" -> scope("PERSONAL", actor.id(), null);
            case "user-personal" -> { scope("PERSONAL", owner.id(), null); grant("USER", actor.id()); }
            case "dept-personal" -> { scope("PERSONAL", owner.id(), null); grant("DEPT", dept); }
            case "other-type" -> jdbc.update("update emr_template set template_type='RIS' where id=?", template);
            case "other-record-type" -> jdbc.update("update emr_template set record_type='PREOP' where id=?", template);
            default -> fail(scenario);
        }
        var before = facts(); var audits = audit(); accepted(entry, save(entry, Map.of("finding", "咽红")), before, true); audited(audits, entry, 200, 0);
    }
    static Stream<Arguments> invisibleMatrix() {
        return ENTRIES.stream().flatMap(e -> Stream.of("other-personal", "admin-personal", "foreign-dept", "disabled", "missing", "empty-personal", "empty-disabled", "hidden-invalid-field").map(m -> Arguments.of(e, m)));
    }
    @ParameterizedTest @MethodSource("invisibleMatrix")
    void eachStructuredSaveRejectsInvisibleMissingAndDisabledParentsBeforeAnyClinicalWrite(String entry, String scenario) throws Exception {
        switch (scenario) {
            case "other-personal", "empty-personal", "hidden-invalid-field" -> scope("PERSONAL", owner.id(), null);
            case "admin-personal" -> { roles(actor, "ADMIN"); scope("PERSONAL", owner.id(), null); }
            case "foreign-dept" -> scope("DEPT", owner.id(), otherDept);
            case "disabled", "empty-disabled" -> jdbc.update("update emr_template set enabled=false where id=?", template);
            case "missing" -> { jdbc.update("delete from emr_template_field where template_id=?", template); jdbc.update("delete from emr_template where id=?", template); }
            default -> fail(scenario);
        }
        var values = scenario.startsWith("empty") || scenario.equals("missing") ? Map.<String, Object>of() : scenario.equals("hidden-invalid-field") ? Map.<String, Object>of("unknown", "private") : Map.<String, Object>of("finding", "咽红");
        var before = facts(); var audits = audit(); denied(entry + "-" + scenario, save(entry, values), 200, 4066, before); audited(audits, entry, 200, 0);
    }
    @ParameterizedTest @ValueSource(strings = {"OUTP", "INP"})
    void anAuthorizedEmptyFieldMapIsStillStructuredAndAccepted(String entry) throws Exception {
        var before = facts(); var reply = save(entry, Map.of()); accepted(entry, reply, before, true);
        assertEquals("{}", reply.body().path("data").path("contentJson").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"OUTP", "INP"})
    void fieldsNullDoesNotConsultTheHiddenOrDisabledParentAndRetainsOldSidecars(String entry) throws Exception {
        scope("PERSONAL", owner.id(), null); jdbc.update("update emr_template set enabled=false where id=?", template);
        jdbc.update("update outp_emr set template_id=?,content_json='{\"legacy\":\"retained\"}' where id=?", template, outpRecord);
        jdbc.update("update inp_medical_record set template_id=?,content_json='{\"legacy\":\"history\"}' where id=?", template, oldInpRecord);
        var before = facts(); accepted(entry, save(entry, null), before, false);
        assertEquals(template, jdbc.queryForObject("select template_id from outp_emr where id=?", Long.class, outpRecord));
        assertEquals("{\"legacy\":\"retained\"}", jdbc.queryForObject("select content_json from outp_emr where id=?", String.class, outpRecord));
    }
    static Stream<Arguments> validations() { return ENTRIES.stream().flatMap(e -> Stream.of("missing-id", "unknown", "disabled-field", "required", "value-set", "datatype").map(v -> Arguments.of(e, v))); }
    @ParameterizedTest @MethodSource("validations")
    void originalFieldValidationCodesAndRollbackArePreserved(String entry, String scenario) throws Exception {
        Long parent = template; Map<String, Object> values = Map.of("finding", "咽红"); int expected = 4024;
        switch (scenario) {
            case "missing-id" -> parent = null;
            case "unknown" -> values = Map.of("unknown", "x");
            case "disabled-field" -> jdbc.update("update emr_template_field set enabled=false where id=?", field);
            case "required" -> { jdbc.update("update emr_template_field set required=true where id=?", field); values = Map.of(); expected = 4025; }
            case "value-set" -> { jdbc.update("update emr_template_field set datatype='RADIO',value_set='[\"轻\",\"重\"]' where id=?", field); values = Map.of("finding", "无效"); expected = 4026; }
            case "datatype" -> { jdbc.update("update emr_template_field set datatype='NUMBER' where id=?", field); values = Map.of("finding", "非数字"); expected = 4027; }
            default -> fail(scenario);
        }
        var before = facts(); denied(entry + "-validation-" + scenario, call(method(entry), endpoint(entry), body(entry, parent, values), actor.token()), 200, expected, before);
    }
    @Test void outpSignedAuthorAndRenderedLengthErrorsRetainTheirOriginalPrecedence() throws Exception {
        jdbc.update("update outp_emr set signature='signed',signed_at=now() where id=?", outpRecord);
        scope("PERSONAL", owner.id(), null); var before = facts(); denied("outp-signed", save("OUTP", Map.of("finding", "x")), 200, 4008, before);
    }
    @Test void outpStructuredRenderedLengthStillReturns4029WithoutPartialWrites() throws Exception {
        var request = new LinkedHashMap<>((Map<String, Object>) body("OUTP", template, Map.of("finding", "x".repeat(1500))));
        request.put("emr", Map.of("chiefComplaint", "主诉", "presentIllness", "x".repeat(1000)));
        var before = facts(); denied("outp-rendered-length", call("PUT", endpoint("OUTP"), request, actor.token()), 200, 4029, before);
    }
    @Test void inpRecordTypeValidationRemainsBeforeParentValidation() throws Exception {
        scope("PERSONAL", owner.id(), null); var request = new LinkedHashMap<>((Map<String, Object>) body("INP", template, Map.of("finding", "x")));
        request.put("recordType", "INVALID"); var before = facts(); denied("inp-type", call("POST", endpoint("INP"), request, actor.token()), 200, 9129, before);
    }
    static Stream<Arguments> entryDenials() { return ENTRIES.stream().flatMap(e -> Stream.of("NURSE", "QUALITY", "TECHNICIAN", "CASHIER", "disabled", "anonymous").map(d -> Arguments.of(e, d))); }
    @ParameterizedTest @MethodSource("entryDenials")
    void originalClinicalRoleAndAuthenticationBoundariesRemain(String entry, String value) throws Exception {
        String token = actor.token(); int http = 403, expected = 1005;
        if (value.equals("anonymous")) { token = null; http = 401; expected = 1000; }
        else if (value.equals("disabled")) { jdbc.update("update sys_user set enabled=false where id=?", actor.id()); http = 401; expected = 1000; }
        else roles(actor, value);
        var before = facts(); denied(entry + "-entry-" + value, call(method(entry), endpoint(entry), body(entry, template, Map.of("finding", "x")), token), http, expected, before);
    }

    TransactionTemplate tx() { var transaction = new TransactionTemplate(transactions); transaction.setTimeout(40); return transaction; }
    void lockTemplate() { assertEquals(List.of(template), jdbc.queryForList("select id from emr_template where id=? for update", Long.class, template)); }
    void awaitHolder(int holder, Future<?> worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            jdbc.execute("select pg_stat_clear_snapshot()");
            var waits = jdbc.queryForList("select pid,pg_blocking_pids(pid)::text as blockers,query,wait_event from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock'");
            var reachable = new HashSet<Integer>(); reachable.add(holder); boolean changed;
            do { changed = false; for (var row : waits) for (int pid : List.copyOf(reachable)) if (Arrays.asList(row.get("blockers").toString().replace("{", "").replace("}", "").split(",")).contains(Integer.toString(pid))) changed |= reachable.add(((Number) row.get("pid")).intValue()); } while (changed);
            if (waits.stream().anyMatch(row -> reachable.contains(((Number) row.get("pid")).intValue()))) {
                assertFalse(worker.isDone()); System.out.println("V145 PG_LOCK holder=" + holder + " waits=" + waits); return;
            }
            if (worker.isDone()) fail("Request completed without the named holder wait");
            try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("No PostgreSQL blocking graph reaches the exact named holder");
    }
    Reply finish(Future<Reply> future) throws Exception { try { return future.get(25, TimeUnit.SECONDS); } catch (ExecutionException e) { return fail("Unexpected request exception", e.getCause()); } }
    void close(ExecutorService pool) throws Exception { pool.shutdown(); if (!pool.awaitTermination(25, TimeUnit.SECONDS)) { pool.shutdownNow(); fail("Request workers did not finish"); } }
    void withActor(Staff staff, String role, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(staff.name(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        try { action.run(); } finally { SecurityContextHolder.clearContext(); }
    }
    void publicScope(String value) {
        withActor(admin, "ADMIN", () -> assertEquals(0, templates.updateTemplate(template, new EmrTemplateService.TemplateReq(tag + "模板", "模板目录正文", "EMR", value, value.equals("DEPT") ? dept : null, "PROGRESS"), new UsernamePasswordAuthenticationToken(admin.name(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))).getCode()));
    }
    void publicEmployee(String change) {
        try {
            String path = "/api/system/users/" + actor.id(); Reply result;
            if (change.equals("disabled")) result = call("PUT", path + "/enabled?enabled=false", null, admin.token());
            else result = call("PUT", path, Map.of("username", actor.name(), "realName", actor.name(), "deptId", change.equals("department") ? otherDept : dept, "roleCodes", change.equals("NO_ROLES") ? List.of() : List.of(change.equals("department") ? "DOCTOR_OUTP" : change)), admin.token());
            response(result, 200, 0);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static Stream<Arguments> waits() { return ENTRIES.stream().flatMap(e -> Stream.of("scope", "disabled-template", "USER-revoke", "DEPT-revoke", "department", "NURSE", "CASHIER", "NO_ROLES", "disabled").map(c -> Arguments.of(e, c))); }
    @ParameterizedTest @MethodSource("waits")
    void committedParentOrEmployeeChangeAfterActualWaitRejectsTheSave(String entry, String change) throws Exception {
        Long grantId = null;
        if (change.endsWith("-revoke")) { scope("PERSONAL", owner.id(), null); grantId = grant(change.startsWith("USER") ? "USER" : "DEPT", change.startsWith("USER") ? actor.id() : dept); }
        if (change.equals("department")) scope("DEPT", owner.id(), dept);
        var pool = Executors.newSingleThreadExecutor(); var worker = new AtomicReference<Future<Reply>>(); var expected = new AtomicReference<Map<String, List<String>>>();
        final Long revoked = grantId; var audits = audit();
        try {
            tx().executeWithoutResult(status -> {
                lockTemplate(); int holder = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                worker.set(pool.submit(() -> save(entry, Map.of("finding", "咽红")))); awaitHolder(holder, worker.get());
                switch (change) {
                    case "scope" -> publicScope("PERSONAL");
                    case "disabled-template" -> withActor(admin, "ADMIN", () -> assertEquals(0, templates.disableTemplate(template, SecurityContextHolder.getContext().getAuthentication()).getCode()));
                    case "USER-revoke", "DEPT-revoke" -> withActor(owner, "DOCTOR_OUTP", () -> assertEquals(0, templates.revokeTemplateGrant(template, revoked, SecurityContextHolder.getContext().getAuthentication()).getCode()));
                    default -> publicEmployee(change);
                }
                expected.set(facts());
            });
            Reply reply = finish(worker.get()); boolean dutyLoss = Set.of("NURSE", "CASHIER", "NO_ROLES", "disabled").contains(change);
            denied(entry + "-wait-" + change, reply, dutyLoss ? 403 : 200, dutyLoss ? 1005 : 4066, expected.get());
            audited(audits, entry, dutyLoss ? 403 : 200, dutyLoss || change.equals("department") ? 1 : 0);
        } finally { close(pool); }
    }
    @ParameterizedTest @CsvSource({"OUTP,true", "OUTP,false", "INP,true", "INP,false"})
    void adminBecomingDoctorAfterParentWaitMustUseCurrentClinicalObjectEligibility(String entry, boolean owns) throws Exception {
        roles(actor, "ADMIN");
        jdbc.update("update outp_registration set doctor_id=?,visit_date=?::date where id=?", owns ? actor.id() : owner.id(), BusinessDates.today().minusDays(1).toString(), registration);
        jdbc.update("update outp_emr set doctor_id=? where id=?", owns ? actor.id() : owner.id(), outpRecord);
        jdbc.update("update inp_admission set doctor_id=? where id=?", owns ? actor.id() : owner.id(), admission);
        var before = facts(); var pool = Executors.newSingleThreadExecutor(); var worker = new AtomicReference<Future<Reply>>(); var expected = new AtomicReference<Map<String, List<String>>>();
        try {
            tx().executeWithoutResult(status -> { lockTemplate(); int holder = jdbc.queryForObject("select pg_backend_pid()", Integer.class); worker.set(pool.submit(() -> save(entry, Map.of("finding", "x")))); awaitHolder(holder, worker.get()); publicEmployee("DOCTOR_OUTP"); expected.set(facts()); });
            if (owns) accepted(entry, finish(worker.get()), expected.get(), true);
            else denied(entry + "-admin-lost-foreign-object", finish(worker.get()), 200, entry.equals("OUTP") ? 4036 : 9130, expected.get());
            assertNotEquals(before.get("roles"), facts().get("roles"));
        } finally { close(pool); }
    }
    @ParameterizedTest @ValueSource(strings = {"OUTP", "INP"})
    void aRolledBackScopeChangePreservesTheOriginalSaveQualification(String entry) throws Exception {
        var before = facts(); var pool = Executors.newSingleThreadExecutor(); var worker = new AtomicReference<Future<Reply>>();
        try {
            tx().executeWithoutResult(status -> { lockTemplate(); publicScope("PERSONAL"); int holder = jdbc.queryForObject("select pg_backend_pid()", Integer.class); worker.set(pool.submit(() -> save(entry, Map.of("finding", "x")))); awaitHolder(holder, worker.get()); status.setRollbackOnly(); });
            accepted(entry, finish(worker.get()), before, true);
        } finally { close(pool); }
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if (registration != null) {
            jdbc.update("delete from emr_version where emr_type='OUTP' and emr_id in (select id from outp_emr where registration_id=?)", registration);
            jdbc.update("delete from outp_diagnosis_version where registration_id=?", registration);
            jdbc.update("delete from outp_diagnosis where registration_id=?", registration);
            jdbc.update("delete from outp_emr where registration_id=?", registration);
            jdbc.update("delete from outp_registration where id=?", registration);
        }
        if (admission != null) {
            jdbc.update("delete from emr_version where emr_type='INP' and emr_id in (select id from inp_medical_record where admission_id=?)", admission);
            jdbc.update("delete from inp_medical_record where admission_id=?", admission);
            jdbc.update("delete from inp_admission where id=?", admission);
        }
        if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
        if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
        if (template != null) {
            jdbc.update("delete from emr_template_field where template_id=?", template);
            jdbc.update("delete from emr_template_grant where template_id=?", template);
            jdbc.update("delete from emr_template where id=?", template);
        }
        for (Staff staff : people) {
            jdbc.update("delete from outp_diagnosis_favorite where user_id=?", staff.id());
            jdbc.update("delete from sys_user_role where user_id=?", staff.id()); jdbc.update("delete from sys_user where id=?", staff.id());
        }
        if (dept != null) jdbc.update("delete from sys_dept where id in (?,?)", dept, otherDept);
    }
}
