package cn.hip.server;

import cn.hip.platform.core.config.BusinessDates;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A grant-only PostgreSQL holder exposes update's scope/department cleanup wait.
 * Fixtures commit before requests; real JWT requests have no outer test transaction.
 * Employee API changes commit on the test thread while the separate holder still
 * owns its grant lock. This is not a claim about revocation after every actor read.
 * No replacement JdbcTemplate, production test hook, sleep barrier or mocked lock.
 */
@SpringBootTest
@AutoConfigureMockMvc
class V155EmrTemplateUpdateGrantWaitTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;

    record Staff(Long id, String name, String token) {}
    record Reply(int http, JsonNode body) { int code() { return body.path("code").asInt(); } }
    record Audit(String path, int status, String username) {}
    record Wait(int holder, int worker, String query, String event, boolean headerDmlLock) {}

    static final List<String> DUTIES = List.of("ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN");
    static final List<String> CLINICAL = List.of("empi_patient", "outp_schedule", "outp_registration",
            "outp_diagnosis", "outp_order", "outp_charge", "outp_emr", "inp_bed", "inp_admission",
            "inp_order", "inp_medical_record", "lis_sample", "lab_micro_result", "lab_micro_ast",
            "ris_exam", "path_specimen", "path_process", "emr_consent", "cdr_document", "fin_print_log");
    String tag;
    Long dept, otherDept, template, otherTemplate, extraGrant, automaticGrant;
    Long patient, schedule, registration, bed, admission;
    Staff actor, owner, admin, viewer;
    final List<Staff> people = new ArrayList<>();
    final List<Map<String, Object>> employeeCalls = new ArrayList<>();
    Instant auditLower;
    int artifact;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        tag = "V155G" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag + "D", tag + "本科");
        otherDept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag + "X", tag + "新科");
        actor = employee("actor", "ADMIN", dept);
        owner = employee("owner", "DOCTOR_OUTP", dept);
        admin = employee("admin", "ADMIN", dept);
        viewer = employee("viewer", "NURSE", otherDept);
        template = newTemplate("target", owner.id());
        otherTemplate = newTemplate("other", owner.id());
        jdbc.update("""
                insert into emr_template_field(template_id,field_code,label,datatype,required,sort_no,placeholder,unit)
                values (?,'BASE','原标签','TEXT',true,7,'原提示','℃'),(?,'OTHER','对照字段','NUMBER',false,9,null,null)
                """, template, otherTemplate);
        configure("DEPT", false);
        // Nonempty clinical records retain both the old text and structured sidecar.
        patient = jdbc.queryForObject("insert into empi_patient(name,sex) values (?,'U') returning id",
                Long.class, tag + "patient");
        schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,doctor_id,schedule_date,capacity,booked)
                values (?,?,?,10,1) returning id
                """, Long.class, dept, owner.id(), BusinessDates.today().minusDays(4));
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,status)
                values (1,?,?,?,?,?,'VISITED') returning id
                """, Long.class, patient, schedule, dept, owner.id(), BusinessDates.today().minusDays(4));
        jdbc.update("""
                insert into outp_emr(registration_id,doctor_id,chief_complaint,present_illness,past_history,
                                     physical_exam,advice,template_id,content_json)
                values (?,?,'原主诉','原现病史','原既往史','原体检','原处理',?,'{"BASE":"旧门诊侧车"}')
                """, registration, owner.id(), template);
        bed = jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id",
                Long.class, dept, tag);
        admission = jdbc.queryForObject("""
                insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status)
                values (?,?,?,?,?,?,'DISCHARGED') returning id
                """, Long.class, tag, patient, dept, dept, bed, owner.id());
        jdbc.update("""
                insert into inp_medical_record(admission_id,record_type,title,content,doctor_id,template_id,content_json)
                values (?,'PROGRESS',?,'原住院正文',?,?,'{"BASE":"旧住院侧车"}')
                """, admission, tag + "原标题", owner.id(), template);
        auditLower = clock();
    }

    Staff employee(String suffix, String duty, Long department) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, name, name, department);
        Staff staff = new Staff(id, name, jwt.issue(name));
        people.add(staff);
        roles(staff, duty);
        return staff;
    }

    void roles(Staff staff, String... duties) {
        jdbc.update("delete from sys_user_role where user_id=?", staff.id());
        for (String duty : duties) assertEquals(1, jdbc.update("""
                insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?
                """, staff.id(), duty));
    }

    Long newTemplate(String suffix, Long who) {
        return jdbc.queryForObject("""
                insert into emr_template(dept_id,name,content,template_type,scope,owner_id,record_type,enabled,created_by)
                values (?,?,?,'EMR','DEPT',?,'PROGRESS',true,?) returning id
                """, Long.class, dept, tag + suffix, "原模板正文", who, who);
    }

    Long grant(String type, Long grantee) {
        return jdbc.queryForObject("""
                insert into emr_template_grant(template_id,grantee_type,grantee_id,granted_by)
                values (?,?,?,?) returning id
                """, Long.class, template, type, grantee, admin.id());
    }

    void configure(String scope, boolean own) {
        jdbc.update("""
                update emr_template set scope=?,owner_id=?,dept_id=?,is_default=true,enabled=true where id=?
                """, scope, own ? actor.id() : owner.id(), scope.equals("DEPT") ? dept : null, template);
        jdbc.update("delete from emr_template_grant where template_id=?", template);
        automaticGrant = grant(scope.equals("PERSONAL") ? "USER" : "DEPT",
                scope.equals("PERSONAL") ? (own ? actor.id() : owner.id()) : dept);
        extraGrant = grant("USER", viewer.id());
    }

    String path() { return "/api/emr-templates/" + template; }
    String employeePath(String change) {
        return "/api/system/users/" + actor.id() + (change.equals("disabled") ? "/enabled?enabled=false" : "");
    }

    Map<String, Object> body(String scope, Long department) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", tag + "改");
        body.put("content", "新模板正文");
        body.put("templateType", "EMR");
        body.put("recordType", "PROGRESS");
        if (scope != null) body.put("scope", scope);
        if (department != null) body.put("deptId", department);
        return body;
    }

    Reply call(String endpoint, Object body, String token) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var request = put(endpoint);
            if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
            if (token != null) request.header("Authorization", "Bearer " + token);
            var response = mvc.perform(request).andReturn().getResponse();
            return new Reply(response.getStatus(),
                    mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    Reply update(Map<String, Object> body) throws Exception { return call(path(), body, actor.token()); }

    void publicEmployee(String change, Long department) {
        try {
            Object request = change.equals("disabled") ? null : Map.of("username", actor.name(),
                    "realName", actor.name(), "deptId", department,
                    "roleCodes", change.equals("NO_ROLES") ? List.of() : List.of(change));
            Reply reply = call(employeePath(change), request, admin.token());
            var event = new LinkedHashMap<String, Object>();
            event.put("path", employeePath(change));
            event.put("request", request);
            event.put("http", reply.http());
            event.put("response", reply.body());
            employeeCalls.add(event);
            assertEquals(200, reply.http(), reply.body().toString());
            assertEquals(0, reply.code(), reply.body().toString());
            if (!change.equals("disabled")) assertEquals(actor.id().longValue(), reply.body().path("data").path("id").asLong());
        } catch (Exception error) {
            throw new IllegalStateException("Public employee API failed", error);
        }
    }

    TransactionTemplate tx() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(60);
        return transaction;
    }

    /** Only this independent transaction owns GR; it never locks a parent T or calls a maintenance API. */
    final class GrantHolder implements AutoCloseable {
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Integer> started = new CompletableFuture<>();
        final Future<?> holder;
        final int pid;

        GrantHolder(Long grantId) throws Exception {
            holder = pool.submit(() -> {
                try {
                    tx().executeWithoutResult(status -> {
                        assertEquals(1, jdbc.queryForList("""
                                select id from emr_template_grant where template_id=? and id=? for update
                                """, template, grantId).size());
                        started.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                        try {
                            if (!release.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("Grant holder lease expired");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                    });
                } catch (RuntimeException | Error error) {
                    started.completeExceptionally(error);
                    throw error;
                }
            });
            try {
                pid = started.get(12, TimeUnit.SECONDS);
            } catch (Exception error) {
                release.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
                throw error;
            }
        }

        Future<Reply> submit(Map<String, Object> request) {
            return pool.submit(() -> update(request));
        }

        void unlock() { release.countDown(); }

        void stillHeld() {
            assertEquals(1L, release.getCount());
            assertFalse(holder.isDone(), "Independent GR holder must remain open after the public employee API committed");
        }

        @Override
        public void close() throws Exception {
            unlock();
            try {
                holder.get(20, TimeUnit.SECONDS);
            } finally {
                pool.shutdown();
                assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Own holder/request threads must actually finish");
            }
        }
    }

    Wait awaitGrantWait(GrantHolder holder, Future<Reply> pending) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < end) {
            jdbc.execute("select pg_stat_clear_snapshot()");
            var waits = jdbc.queryForList("""
                    select a.pid, a.query, a.wait_event,
                           exists(select 1 from pg_locks l where l.pid=a.pid and l.granted
                                  and l.relation='emr_template'::regclass and l.mode='RowExclusiveLock') as header_dml
                    from pg_stat_activity a where a.datname=current_database() and a.pid<>pg_backend_pid()
                      and a.wait_event_type='Lock' and ?=any(pg_blocking_pids(a.pid))
                      and lower(a.query) like '%emr_template_grant%'
                    """, holder.pid);
            if (waits.size() == 1) {
                var row = waits.getFirst();
                Wait wait = new Wait(holder.pid, ((Number) row.get("pid")).intValue(),
                        row.get("query").toString(), row.get("wait_event").toString(),
                        Boolean.TRUE.equals(row.get("header_dml")));
                holder.stillHeld();
                System.out.println("V155 GRANT_WAIT " + mapper.writeValueAsString(wait));
                return wait;
            }
            if (pending.isDone()) fail("JWT update completed without the named grant-only PostgreSQL blocker");
            Thread.sleep(15);
        }
        return fail("No direct emr_template_grant wait on the exact independent PostgreSQL holder");
    }

    Reply finish(Future<Reply> pending) throws Exception {
        try {
            return pending.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            return fail("Unexpected real JWT request exception", error.getCause());
        }
    }

    Map<String, List<Map<String, Object>>> facts() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("emr_template", "emr_template_field", "emr_template_grant")) {
            result.put(table, jdbc.queryForList("select * from " + table + " order by id"));
        }
        for (String table : CLINICAL) result.put(table, jdbc.queryForList("select * from " + table + " order by id"));
        result.put("sys_config", jdbc.queryForList("select * from sys_config order by cfg_key"));
        return result;
    }

    Map<String, List<Map<String, Object>>> authFacts() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        result.put("users", jdbc.queryForList("select * from sys_user where username like ? order by id", tag + "%"));
        result.put("roles", jdbc.queryForList("""
                select ur.* from sys_user_role ur join sys_user u on u.id=ur.user_id
                where u.username like ? order by ur.user_id,ur.role_id
                """, tag + "%"));
        result.put("role_dictionary", jdbc.queryForList("select * from sys_role order by id"));
        return result;
    }

    Map<String, List<Map<String, Object>>> copy(Map<String, List<Map<String, Object>>> original) {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        original.forEach((key, rows) -> {
            var copied = new ArrayList<Map<String, Object>>();
            rows.forEach(row -> copied.add(new LinkedHashMap<>(row)));
            result.put(key, copied);
        });
        return result;
    }

    Map<String, Object> row(Map<String, List<Map<String, Object>>> facts, String table, Long id) {
        return facts.get(table).stream().filter(value -> id.equals(value.get("id"))).findFirst().orElseThrow();
    }

    void changedAuth(Map<String, List<Map<String, Object>>> before, String change, Long department) {
        var expected = copy(before);
        if (change.equals("disabled")) {
            row(expected, "users", actor.id()).put("enabled", false);
        } else {
            row(expected, "users", actor.id()).put("dept_id", department);
            expected.get("roles").removeIf(value -> actor.id().equals(value.get("user_id")));
            if (!change.equals("NO_ROLES")) {
                Object roleId = expected.get("role_dictionary").stream()
                        .filter(role -> change.equals(role.get("code"))).findFirst().orElseThrow().get("id");
                expected.get("roles").add(new LinkedHashMap<>(Map.of("user_id", actor.id(), "role_id", roleId)));
                expected.get("roles").sort(Comparator.comparingLong((Map<String, Object> value) ->
                        ((Number) value.get("user_id")).longValue()).thenComparingLong(value ->
                        ((Number) value.get("role_id")).longValue()));
            }
        }
        assertEquals(expected, authFacts(), "Only the requested public employee change may commit");
    }

    List<Map<String, Object>> audit() { return jdbc.queryForList("select * from sys_audit_log order by id"); }

    void audited(List<Map<String, Object>> before, Audit... wanted) {
        var old = new HashMap<Object, Map<String, Object>>();
        before.forEach(value -> old.put(value.get("id"), value));
        var added = new ArrayList<Map<String, Object>>();
        for (var value : audit()) {
            if (old.containsKey(value.get("id"))) assertEquals(old.remove(value.get("id")), value);
            else added.add(value);
        }
        assertTrue(old.isEmpty(), "Existing audit rows may not disappear");
        assertEquals(wanted.length, added.size(), added.toString());
        Instant upper = clock();
        for (int i = 0; i < wanted.length; i++) {
            var actual = added.get(i);
            var expected = wanted[i];
            assertEquals(Set.of("id", "username", "method", "path", "http_status", "client_ip", "created_at"),
                    actual.keySet());
            assertEquals("PUT", actual.get("method"));
            assertEquals(expected.status() == 403 ? expected.path().split("\\?", 2)[0] : expected.path(), actual.get("path"));
            assertEquals(expected.status(), ((Number) actual.get("http_status")).intValue());
            assertEquals(expected.username(), actual.get("username"));
            assertEquals("127.0.0.1", actual.get("client_ip"));
            assertInstanceOf(Timestamp.class, actual.get("created_at"));
            Instant at = ((Timestamp) actual.get("created_at")).toInstant();
            assertFalse(at.isBefore(auditLower));
            assertFalse(at.isAfter(upper));
        }
    }

    void code(Reply reply, int status, int expected) {
        var keys = new HashSet<String>();
        reply.body().fieldNames().forEachRemaining(keys::add);
        assertAll(() -> assertEquals(Set.of("code", "message", "data"), keys),
                () -> assertEquals(status, reply.http(), reply.body().toString()),
                () -> assertEquals(expected, reply.code(), reply.body().toString()),
                () -> assertTrue(reply.body().path("data").isNull(), reply.body().toString()));
    }

    Instant clock() { return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant(); }

    void accepted(Reply reply, Map<String, List<Map<String, Object>>> before, String scope, Long department,
                  boolean changed, boolean keepDefault, Instant lower, String type, String recordType) {
        code(reply, 200, 0);
        var after = facts();
        var expected = copy(before);
        var header = row(expected, "emr_template", template);
        header.put("name", tag + "改");
        header.put("content", "新模板正文");
        header.put("scope", scope);
        header.put("dept_id", department);
        header.put("is_default", keepDefault);
        header.put("template_type", type);
        header.put("record_type", recordType);
        if (changed) {
            expected.get("emr_template_grant").removeIf(value -> template.equals(value.get("template_id")));
            var actual = after.get("emr_template_grant").stream()
                    .filter(value -> template.equals(value.get("template_id"))).toList();
            boolean auto = scope.equals("PERSONAL") || scope.equals("DEPT");
            assertEquals(auto ? 1 : 0, actual.size());
            if (auto) {
                var grant = actual.getFirst();
                Timestamp at = (Timestamp) grant.get("granted_at");
                assertFalse(at.toInstant().isBefore(lower));
                assertFalse(at.toInstant().isAfter(clock()));
                var oldIds = before.get("emr_template_grant").stream().map(value -> value.get("id")).toList();
                assertFalse(oldIds.contains(grant.get("id")), "Scope cleanup replaces old grants rather than keeping their IDs");
                Object beneficiary = scope.equals("DEPT") ? department : header.get("owner_id");
                Object by = header.get("owner_id") == null ? actor.id() : header.get("owner_id");
                assertEquals(Map.of("id", grant.get("id"), "template_id", template,
                        "grantee_type", scope.equals("DEPT") ? "DEPT" : "USER", "grantee_id", beneficiary,
                        "granted_by", by, "granted_at", at), grant);
                expected.get("emr_template_grant").add(new LinkedHashMap<>(grant));
                expected.get("emr_template_grant").sort(Comparator.comparingLong(value -> ((Number) value.get("id")).longValue()));
            }
        }
        assertEquals(expected, after, "Exact header/default/grant delta only; all fields, clinical rows and config unchanged");
    }

    void accepted(Reply reply, Map<String, List<Map<String, Object>>> before, String scope, Long department,
                  boolean changed, boolean keepDefault, Instant lower) {
        accepted(reply, before, scope, department, changed, keepDefault, lower, "EMR", "PROGRESS");
    }

    void evidence(String label, Reply reply, Map<String, List<Map<String, Object>>> before,
                  List<Map<String, Object>> oldAudit, Wait wait, boolean rollback) throws Exception {
        System.out.println("V155 RESULT " + label + " actual=" + reply.http() + "/" + reply.code()
                + " wait=" + (wait == null ? "not-required" : wait.worker()));
        String folder = System.getProperty("hip.v155.evidenceDir");
        if (folder == null) return;
        Path directory = Path.of(folder).toAbsolutePath().normalize();
        assertTrue(directory.startsWith(Path.of("C:/Users/drxuj/Projects").toAbsolutePath().normalize()),
                "Project-specific evidence must stay under Projects");
        assertFalse(directory.toString().toLowerCase(Locale.ROOT).contains("onedrive"));
        Files.createDirectories(directory);
        var out = new LinkedHashMap<String, Object>();
        out.put("label", label); out.put("tag", tag); out.put("http", reply.http()); out.put("response", reply.body());
        out.put("before", before); out.put("after", facts()); out.put("oldAudit", oldAudit);
        out.put("currentAudit", audit()); out.put("employeeCalls", employeeCalls); out.put("grantWait", wait);
        out.put("employeeChangeRolledBack", rollback); out.put("authAfter", authFacts());
        Files.writeString(directory.resolve(tag + "-" + (++artifact) + ".json"),
                mapper.writeValueAsString(exactEvidence(out)), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** Keep PostgreSQL timestamp fractions in evidence; the shared mapper's Date milliseconds would lose them. */
    Object exactEvidence(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof java.sql.Date date) return date.toString();
        if (value instanceof Map<?, ?> values) {
            var exact = new LinkedHashMap<String, Object>();
            values.forEach((key, item) -> exact.put(key.toString(), exactEvidence(item)));
            return exact;
        }
        if (value instanceof Collection<?> values) return values.stream().map(this::exactEvidence).toList();
        return value;
    }

    static Stream<Arguments> revokedDuringCleanup() {
        return Stream.of("PERSONAL", "OTHER_DEPT").flatMap(target ->
                Stream.of("QUALITY", "TECHNICIAN", "CASHIER", "NO_ROLES", "disabled")
                        .map(change -> Arguments.of(target, change)));
    }

    @ParameterizedTest
    @MethodSource("revokedDuringCleanup")
    void committedDutyLossDuringScopeOrDepartmentGrantWaitRejectsTheWholeUpdate(String target, String change)
            throws Exception {
        // A nonowner ADMIN is initially allowed on DEPT, unlike another person's PERSONAL template.
        var before = facts(); var auth = authFacts(); var audits = audit();
        try (var holder = new GrantHolder(extraGrant)) {
            var pending = holder.submit(body(target.equals("PERSONAL") ? "PERSONAL" : "DEPT",
                    target.equals("PERSONAL") ? null : otherDept));
            Wait wait = awaitGrantWait(holder, pending);
            publicEmployee(change, dept); // No outer transaction: the public API has actually committed.
            changedAuth(auth, change, dept);
            Wait afterCommit = awaitGrantWait(holder, pending);
            assertEquals(wait.worker(), afterCommit.worker());
            holder.unlock();
            Reply reply = finish(pending);
            evidence("revoked-" + target + "-" + change, reply, before, audits, wait, false);
            boolean entryDenied = Set.of("CASHIER", "NO_ROLES", "disabled").contains(change);
            assertAll(() -> code(reply, entryDenied ? 403 : 200, entryDenied ? 1005 : 4066),
                    () -> assertEquals(before, facts(), "Denied update must roll back header/default/grants and preserve clinical rows"),
                    () -> changedAuth(auth, change, dept),
                    () -> audited(audits, new Audit(employeePath(change), 200, admin.name()),
                            new Audit(path(), entryDenied ? 403 : 200, actor.name())));
        }
    }

    @Test
    void theAutomaticGrantHolderAlsoRequiresFreshObjectPermission() throws Exception {
        var before = facts(); var auth = authFacts(); var audits = audit();
        try (var holder = new GrantHolder(automaticGrant)) {
            var pending = holder.submit(body("PERSONAL", null));
            Wait wait = awaitGrantWait(holder, pending);
            publicEmployee("QUALITY", dept); changedAuth(auth, "QUALITY", dept);
            awaitGrantWait(holder, pending); holder.unlock();
            Reply reply = finish(pending);
            evidence("automatic-grant-revocation", reply, before, audits, wait, false);
            assertAll(() -> code(reply, 200, 4066), () -> assertEquals(before, facts()),
                    () -> audited(audits, new Audit(employeePath("QUALITY"), 200, admin.name()),
                            new Audit(path(), 200, actor.name())));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUALITY", "TECHNICIAN"})
    void anOwnerWhoKeepsMaintenanceDutiesCannotGainHospitalScopeAfterLosingAdmin(String duty) throws Exception {
        configure("PERSONAL", true);
        var before = facts(); var auth = authFacts(); var audits = audit();
        try (var holder = new GrantHolder(extraGrant)) {
            var pending = holder.submit(body("HOSPITAL", null));
            Wait wait = awaitGrantWait(holder, pending);
            publicEmployee(duty, dept); changedAuth(auth, duty, dept);
            awaitGrantWait(holder, pending); holder.unlock();
            Reply reply = finish(pending);
            evidence("scope-grantability-" + duty, reply, before, audits, wait, false);
            assertAll(() -> code(reply, 200, 4066), () -> assertEquals(before, facts()),
                    () -> audited(audits, new Audit(employeePath(duty), 200, admin.name()), new Audit(path(), 200, actor.name())));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUALITY", "TECHNICIAN"})
    void anOwnerMustStillHaveTheRequestedDepartmentAfterLosingAdmin(String duty) throws Exception {
        configure("DEPT", true);
        var before = facts(); var auth = authFacts(); var audits = audit();
        try (var holder = new GrantHolder(extraGrant)) {
            var pending = holder.submit(body("DEPT", otherDept));
            Wait wait = awaitGrantWait(holder, pending);
            publicEmployee(duty, dept); changedAuth(auth, duty, dept);
            awaitGrantWait(holder, pending); holder.unlock();
            Reply reply = finish(pending);
            evidence("department-grantability-" + duty, reply, before, audits, wait, false);
            assertAll(() -> code(reply, 200, 4066), () -> assertEquals(before, facts()),
                    () -> audited(audits, new Audit(employeePath(duty), 200, admin.name()), new Audit(path(), 200, actor.name())));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN"})
    void aPersonalOwnerKeepsTheScopeUpdateWithinAllOriginalFiveDuties(String duty) throws Exception {
        configure("PERSONAL", true);
        var before = facts(); var auth = authFacts(); var audits = audit(); Instant lower = clock();
        try (var holder = new GrantHolder(extraGrant)) {
            var pending = holder.submit(body("DEPT", dept));
            Wait wait = awaitGrantWait(holder, pending);
            publicEmployee(duty, dept); changedAuth(auth, duty, dept);
            awaitGrantWait(holder, pending); holder.unlock();
            Reply reply = finish(pending);
            evidence("legal-owner-" + duty, reply, before, audits, wait, false);
            accepted(reply, before, "DEPT", dept, true, false, lower);
            changedAuth(auth, duty, dept);
            audited(audits, new Audit(employeePath(duty), 200, admin.name()), new Audit(path(), 200, actor.name()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUALITY", "TECHNICIAN"})
    void omittedDepartmentForPersonalToDepartmentUsesTheOwnersNewCommittedDepartment(String duty) throws Exception {
        configure("PERSONAL", true);
        var before = facts(); var auth = authFacts(); var audits = audit(); Instant lower = clock();
        try (var holder = new GrantHolder(extraGrant)) {
            var pending = holder.submit(body("DEPT", null));
            Wait wait = awaitGrantWait(holder, pending);
            publicEmployee(duty, otherDept); changedAuth(auth, duty, otherDept);
            awaitGrantWait(holder, pending); holder.unlock();
            Reply reply = finish(pending);
            evidence("fresh-omitted-department-" + duty, reply, before, audits, wait, false);
            accepted(reply, before, "DEPT", otherDept, true, false, lower);
            changedAuth(auth, duty, otherDept);
            audited(audits, new Audit(employeePath(duty), 200, admin.name()), new Audit(path(), 200, actor.name()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUALITY", "CASHIER", "disabled"})
    void rolledBackPublicEmployeeChangesDoNotRevokeTheOriginalScopeUpdate(String change) throws Exception {
        var before = facts(); var auth = authFacts(); var audits = audit(); Instant lower = clock();
        try (var holder = new GrantHolder(extraGrant)) {
            var pending = holder.submit(body("PERSONAL", null));
            Wait wait = awaitGrantWait(holder, pending);
            tx().executeWithoutResult(status -> {
                publicEmployee(change, dept);
                em.flush(); em.clear();
                changedAuth(auth, change, dept); // The change really reached PG inside its own transaction.
                status.setRollbackOnly();
            });
            assertEquals(auth, authFacts(), "Rolled-back public change must be absent on a new read");
            awaitGrantWait(holder, pending); holder.unlock();
            Reply reply = finish(pending);
            evidence("rollback-" + change, reply, before, audits, wait, true);
            accepted(reply, before, "PERSONAL", null, true, false, lower);
            assertEquals(auth, authFacts());
            audited(audits, new Audit(path(), 200, actor.name()));
        }
    }

    @Test
    void scopeCleanupAcquiresItsGrantWaitBeforeAnyHeaderOrDefaultDml() throws Exception {
        var before = facts(); var auth = authFacts(); var audits = audit(); Instant lower = clock();
        try (var holder = new GrantHolder(extraGrant)) {
            var pending = holder.submit(body("PERSONAL", null));
            Wait wait = awaitGrantWait(holder, pending);
            assertEquals(before, facts(), "Other transactions continue to see the complete committed baseline");
            holder.unlock();
            Reply reply = finish(pending);
            evidence("grant-before-header-dml", reply, before, audits, wait, false);
            assertAll(() -> assertFalse(wait.headerDmlLock(),
                            "A RowExclusiveLock on emr_template proves header/default DML ran before the grant barrier"),
                    () -> accepted(reply, before, "PERSONAL", null, true, false, lower),
                    () -> assertEquals(auth, authFacts()),
                    () -> audited(audits, new Audit(path(), 200, actor.name())));
        }
    }

    static Stream<Arguments> unchanged() {
        return DUTIES.stream().flatMap(duty -> Stream.of("explicit", "omitted").map(form -> Arguments.of(duty, form)));
    }

    @ParameterizedTest
    @MethodSource("unchanged")
    void unchangedScopeAndDepartmentDoNotWaitOnGrantsOrClearDefaults(String duty, String form) throws Exception {
        configure("DEPT", true); roles(actor, duty);
        var before = facts(); var auth = authFacts(); var audits = audit(); Instant lower = clock();
        try (var holder = new GrantHolder(extraGrant)) {
            Reply reply = holder.submit(body(form.equals("explicit") ? "DEPT" : null,
                    form.equals("explicit") ? dept : null)).get(8, TimeUnit.SECONDS);
            holder.stillHeld(); // Returning while GR is held proves this path did not add a grant wait.
            evidence("unchanged-" + duty + "-" + form, reply, before, audits, null, false);
            accepted(reply, before, "DEPT", dept, false, true, lower);
            assertEquals(auth, authFacts()); audited(audits, new Audit(path(), 200, actor.name()));
        }
    }

    @Test
    void omittedDepartmentOnAnExistingDepartmentTemplateRetainsTheTemplateDepartment() throws Exception {
        jdbc.update("update sys_user set dept_id=? where id=?", otherDept, actor.id());
        var before = facts(); var auth = authFacts(); var audits = audit(); Instant lower = clock();
        try (var holder = new GrantHolder(extraGrant)) {
            Reply reply = holder.submit(body("DEPT", null)).get(8, TimeUnit.SECONDS);
            holder.stillHeld();
            evidence("existing-department-is-not-actor-default", reply, before, audits, null, false);
            accepted(reply, before, "DEPT", dept, false, true, lower);
            assertEquals(auth, authFacts()); audited(audits, new Audit(path(), 200, actor.name()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "content", "scope", "scope-permission", "no-department",
            "foreign-department", "ownerless", "invisible", "missing"})
    void originalValidationAndObjectErrorsReturnBeforeTheGrantWait(String invalid) throws Exception {
        var request = body("PERSONAL", null);
        int expected = switch (invalid) {
            case "name" -> 4061;
            case "content" -> 4062;
            case "scope", "no-department" -> 4065;
            default -> 4066;
        };
        switch (invalid) {
            case "name" -> { request.put("name", " "); request.put("content", " "); request.put("scope", "BAD"); }
            case "content" -> { request.put("content", " "); request.put("scope", "BAD"); }
            case "scope" -> request.put("scope", "BAD");
            case "scope-permission" -> {
                configure("PERSONAL", true); roles(actor, "QUALITY");
                request.put("scope", "HOSPITAL"); request.put("deptId", otherDept);
            }
            case "no-department" -> {
                configure("PERSONAL", true); jdbc.update("update sys_user set dept_id=null where id=?", actor.id());
                request.put("scope", "DEPT");
            }
            case "foreign-department" -> {
                configure("PERSONAL", true); roles(actor, "NURSE");
                request.put("scope", "DEPT"); request.put("deptId", otherDept);
            }
            case "ownerless" -> jdbc.update("update emr_template set owner_id=null where id=?", template);
            case "invisible" -> { configure("PERSONAL", false); request.put("name", " "); }
            case "missing" -> request.put("name", " ");
            default -> fail(invalid);
        }
        var before = facts(); var auth = authFacts(); var audits = audit();
        try (var holder = new GrantHolder(extraGrant)) {
            Future<Reply> pending = invalid.equals("missing")
                    ? holder.pool.submit(() -> call("/api/emr-templates/899999999999999", request, actor.token()))
                    : holder.submit(request);
            Reply reply = pending.get(8, TimeUnit.SECONDS);
            holder.stillHeld();
            evidence("validation-" + invalid, reply, before, audits, null, false);
            code(reply, 200, expected); assertEquals(before, facts()); assertEquals(auth, authFacts());
            audited(audits, new Audit(invalid.equals("missing") ? "/api/emr-templates/899999999999999" : path(),
                    200, actor.name()));
        }
    }

    @Test
    void openTypeAndRecordNormalizationRemainCompatibleWithoutAnUnchangedGrantWait() throws Exception {
        var request = body(null, null);
        request.put("templateType", " experimental ");
        request.put("recordType", " abcdefghijklmnopqrst ");
        var before = facts(); var auth = authFacts(); var audits = audit(); Instant lower = clock();
        try (var holder = new GrantHolder(extraGrant)) {
            Reply reply = holder.submit(request).get(8, TimeUnit.SECONDS);
            holder.stillHeld();
            evidence("open-type-and-record-normalization", reply, before, audits, null, false);
            accepted(reply, before, "DEPT", dept, false, false, lower, "EXPERIMENTAL", "ABCDEFGHIJKLMNOP");
            assertEquals(auth, authFacts()); audited(audits, new Audit(path(), 200, actor.name()));
        }
    }

    @Test
    void scopeChangeWithoutExistingGrantsStillRebuildsTheOwnersAutomaticGrant() throws Exception {
        jdbc.update("delete from emr_template_grant where template_id=?", template);
        var before = facts(); var auth = authFacts(); var audits = audit(); Instant lower = clock();
        Reply reply = update(body("PERSONAL", null));
        evidence("empty-grants-scope-change", reply, before, audits, null, false);
        accepted(reply, before, "PERSONAL", null, true, false, lower);
        assertEquals(auth, authFacts()); audited(audits, new Audit(path(), 200, actor.name()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        if (registration != null) {
            jdbc.update("delete from outp_emr where registration_id=?", registration);
            jdbc.update("delete from outp_registration where id=?", registration);
        }
        if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
        if (admission != null) {
            jdbc.update("delete from inp_medical_record where admission_id=?", admission);
            jdbc.update("delete from inp_admission where id=?", admission);
        }
        if (bed != null) jdbc.update("delete from inp_bed where id=?", bed);
        if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
        if (template != null) {
            jdbc.update("delete from emr_template_field where template_id in (?,?)", template, otherTemplate);
            jdbc.update("delete from emr_template_grant where template_id in (?,?)", template, otherTemplate);
            jdbc.update("delete from emr_template where id in (?,?)", template, otherTemplate);
        }
        for (Staff staff : people) {
            jdbc.update("delete from sys_user_role where user_id=?", staff.id());
            jdbc.update("delete from sys_user where id=?", staff.id());
        }
        if (dept != null) jdbc.update("delete from sys_dept where id in (?,?)", dept, otherDept);
        people.clear();
    }
}
