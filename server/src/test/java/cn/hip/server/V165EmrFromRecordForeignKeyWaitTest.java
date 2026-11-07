package cn.hip.server;

import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Actual template INSERT contention, with source and target authorization changing before commit. */
@SpringBootTest
@AutoConfigureMockMvc
class V165EmrFromRecordForeignKeyWaitTest {
    private static final String COPY = "/api/emr-templates/from-record";
    private static final String BODY = "模拟来源旧正文";
    private static final String NEW_BODY = "等待期间另行提交的模拟正文";

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    private record Staff(long id, String username, String token) {}
    private record Reply(int status, JsonNode body) {
        int code() { return body.path("code").asInt(); }
    }
    private record Wait(int pid, String event, String query) {}

    private enum Change { ATTENDING, TECHNICIAN, DISABLED, NURSE, TRANSFER, DOWNGRADE, REMOVE_SOURCE, REVISE_SOURCE }

    private enum Case {
        INP_ATTENDING_LOSS("INP", "DEPT", "DOCTOR_OUTP", Change.ATTENDING, false, 200, 4036),
        OUTP_ATTENDING_LOSS("OUTP", "DEPT", "DOCTOR_OUTP", Change.ATTENDING, false, 200, 4036),
        OUTP_TECHNICIAN_ONLY("OUTP", "DEPT", "QUALITY", Change.TECHNICIAN, false, 403, 1005),
        INP_DISABLED("INP", "PERSONAL", "QUALITY", Change.DISABLED, false, 403, 1005),
        OUTP_NURSE_ONLY("OUTP", "DEPT", "DOCTOR_OUTP", Change.NURSE, false, 200, 4036),
        INP_NURSE_RETAINS_ACCESS("INP", "DEPT", "DOCTOR_OUTP", Change.NURSE, false, 200, 0),
        OUTP_IMPLICIT_DEPARTMENT_TRANSFER("OUTP", "DEPT", "QUALITY", Change.TRANSFER, false, 200, 4066),
        INP_EXPLICIT_DEPARTMENT_TRANSFER("INP", "DEPT", "QUALITY", Change.TRANSFER, true, 200, 4066),
        INP_ADMIN_DOWNGRADE("INP", "HOSPITAL", "ADMIN", Change.DOWNGRADE, false, 200, 4066),
        INP_SOURCE_REMOVED("INP", "DEPT", "QUALITY", Change.REMOVE_SOURCE, false, 200, 4068),
        OUTP_SOURCE_REVISED("OUTP", "PERSONAL", "QUALITY", Change.REVISE_SOURCE, false, 200, 0);

        final String source, scope, duty;
        final Change change;
        final boolean explicitDept;
        final int status, code;

        Case(String source, String scope, String duty, Change change, boolean explicitDept, int status, int code) {
            this.source = source;
            this.scope = scope;
            this.duty = duty;
            this.change = change;
            this.explicitDept = explicitDept;
            this.status = status;
            this.code = code;
        }

        boolean inpatient() { return "INP".equals(source); }
        boolean departmentLock() { return "DEPT".equals(scope); }
    }

    private String tag;
    private Long oldDept, newDept, patient, schedule, registration, outpatientRecord, bed, admission, inpatientRecord;
    private Staff actor, admin;

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void authorizesTheSourceAndTargetAfterTheTemplateInsertWait(Case scenario) throws Exception {
        seed(scenario);
        var body = new LinkedHashMap<String, Object>(Map.of("source", scenario.source,
                "recordId", scenario.inpatient() ? inpatientRecord : outpatientRecord, "patientId", patient,
                "name", tag, "scope", scenario.scope));
        if (scenario.explicitDept) body.put("deptId", oldDept);

        var originalTemplates = templateRows();
        var originalSource = sourceRows();
        Map<String, List<String>> sourceAfterChange;
        Reply copied;
        try (var workers = Executors.newFixedThreadPool(2); Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            Future<Reply> pending;
            try {
                int holderPid = lockParent(holder, scenario);
                pending = workers.submit(() -> call(post(COPY), body, actor));
                Wait wait = awaitInsertWait(holderPid, pending);
                if (scenario.departmentLock() || scenario.change == Change.REVISE_SOURCE) {
                    workers.submit(() -> { changeIndependently(scenario); return null; }).get(15, TimeUnit.SECONDS);
                } else {
                    changeInHolder(holder, scenario.change);
                }
                assertEquals(wait.pid(), awaitInsertWait(holderPid, pending).pid());
                assertEquals(originalTemplates, templateRows(), "No uncommitted template or grant may be visible");
                sourceAfterChange = sourceRows();
                assertExpectedSourceChange(scenario, originalSource, sourceAfterChange);
                System.out.printf("V165 %s FK_WAIT holder=%d waiter=%d event=%s%n",
                        scenario, holderPid, wait.pid(), wait.event());
                holder.commit();
            } finally {
                holder.rollback();
            }
            copied = pending.get(20, TimeUnit.SECONDS);
        }
        assertCurrentPermissions(scenario);
        assertEquals(sourceAfterChange, sourceRows(), "Copy must never change the clinical source");
        System.out.printf("V165 %s expected=%d/%d actual=%d/%d headerAndGrantDelta=%s%n",
                scenario, scenario.status, scenario.code, copied.status(), copied.code(),
                !originalTemplates.equals(templateRows()));
        assertAll(
                () -> assertEquals(scenario.status, copied.status(), copied.body().toString()),
                () -> assertEquals(scenario.code, copied.code(), copied.body().toString()),
                () -> assertEquals(List.of(Map.of("method", "POST", "path", COPY, "http_status", scenario.status)),
                        jdbc.queryForList("select method,path,http_status from sys_audit_log where username=?",
                                actor.username())),
                () -> {
                    if (scenario.code == 0) assertCopied(scenario, copied, originalTemplates);
                    else {
                        assertTrue(copied.body().path("data").isNull(), copied.body().toString());
                        assertEquals(originalTemplates, templateRows(), "Denial must roll back both template and grant");
                    }
                });
    }

    private void seed(Case scenario) {
        tag = "V165" + Long.toUnsignedString(System.nanoTime(), 36);
        oldDept = department("old");
        newDept = department("new");
        admin = employee("admin", "ADMIN");
        actor = employee("actor", scenario.duty);
        patient = jdbc.queryForObject("insert into empi_patient(name,sex) values (?,'U') returning id", Long.class, tag);
        var visitDate = BusinessDates.today().minusDays(4);
        schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,doctor_id,schedule_date,capacity,booked)
                values (?,?,?,10,1) returning id
                """, Long.class, oldDept, admin.id(), visitDate);
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,status)
                values (1,?,?,?,?,?,'VISITED') returning id
                """, Long.class, patient, schedule, oldDept, admin.id(), visitDate);
        outpatientRecord = jdbc.queryForObject("""
                insert into outp_emr(registration_id,doctor_id,chief_complaint) values (?,?,?) returning id
                """, Long.class, registration, admin.id(), BODY);
        bed = jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id",
                Long.class, oldDept, tag);
        admission = jdbc.queryForObject("""
                insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status)
                values (?,?,?,?,?,?,'DISCHARGED') returning id
                """, Long.class, tag, patient, oldDept, oldDept, bed, actor.id());
        inpatientRecord = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id,record_type,title,content,doctor_id)
                values (?,'PROGRESS',?,?,?) returning id
                """, Long.class, admission, tag, BODY, admin.id());
    }

    private int lockParent(Connection holder, Case scenario) throws SQLException {
        String table = scenario.departmentLock() ? "sys_dept" : "sys_user";
        try (var lock = holder.prepareStatement("select pg_backend_pid() from " + table + " where id=? for update")) {
            lock.setLong(1, scenario.departmentLock() ? oldDept : actor.id());
            lock.setQueryTimeout(10);
            try (var row = lock.executeQuery()) {
                assertTrue(row.next(), "The intended foreign-key parent must exist");
                return row.getInt(1);
            }
        }
    }

    private Wait awaitInsertWait(int holderPid, Future<Reply> pending) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            if (pending.isDone()) fail("The request completed without waiting for the held FK parent: " + pending.get());
            var waits = jdbc.query("""
                    select pid,wait_event,query from pg_stat_activity
                    where datname=current_database() and wait_event_type='Lock'
                      and ?=any(pg_blocking_pids(pid))
                    """, (rs, i) -> new Wait(rs.getInt(1), rs.getString(2), rs.getString(3)), holderPid);
            if (!waits.isEmpty()) {
                assertEquals(1, waits.size());
                Wait wait = waits.getFirst();
                assertTrue(wait.query().stripLeading().toLowerCase(Locale.ROOT).startsWith("insert into emr_template("),
                        wait.query());
                return wait;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No template INSERT waiting for holder " + holderPid);
    }

    private void changeIndependently(Case scenario) throws Exception {
        switch (scenario.change) {
            case ATTENDING -> expectOk(call(put("/api/inpatient/admissions/" + admission + "/attending-doctor"),
                    Map.of("doctorId", admin.id()), admin));
            case TECHNICIAN, NURSE, TRANSFER -> expectOk(call(put("/api/system/users/" + actor.id()),
                    Map.of("username", actor.username(), "realName", actor.username(),
                            "deptId", expectedDept(scenario), "roleCodes", List.of(expectedDuty(scenario))), admin));
            case REMOVE_SOURCE -> assertEquals(1, jdbc.update("delete from inp_medical_record where id=?", inpatientRecord));
            case REVISE_SOURCE -> assertEquals(1, jdbc.update("update outp_emr set chief_complaint=? where id=?",
                    NEW_BODY, outpatientRecord));
            default -> throw new AssertionError("Unexpected independent mutation: " + scenario);
        }
    }

    private void changeInHolder(Connection holder, Change change) throws SQLException {
        switch (change) {
            case DISABLED -> execute(holder, "update sys_user set enabled=false where id=?");
            case DOWNGRADE -> {
                execute(holder, "delete from sys_user_role where user_id=?");
                execute(holder, """
                        insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='QUALITY'
                        """);
            }
            default -> throw new AssertionError("Unexpected holder mutation: " + change);
        }
    }

    private void execute(Connection holder, String sql) throws SQLException {
        try (var statement = holder.prepareStatement(sql)) {
            statement.setLong(1, actor.id());
            statement.setQueryTimeout(10);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void assertCurrentPermissions(Case scenario) {
        assertEquals(scenario.change != Change.DISABLED,
                jdbc.queryForObject("select enabled from sys_user where id=?", Boolean.class, actor.id()));
        assertEquals(expectedDept(scenario), jdbc.queryForObject("select dept_id from sys_user where id=?",
                Long.class, actor.id()));
        assertEquals(List.of(expectedDuty(scenario)), jdbc.queryForList("""
                select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=?
                """, String.class, actor.id()));
    }

    private Long expectedDept(Case scenario) {
        return scenario.change == Change.TRANSFER ? newDept : oldDept;
    }

    private String expectedDuty(Case scenario) {
        return switch (scenario.change) {
            case TECHNICIAN -> "TECHNICIAN";
            case NURSE -> "NURSE";
            case DOWNGRADE -> "QUALITY";
            default -> scenario.duty;
        };
    }

    private void assertExpectedSourceChange(Case scenario, Map<String, List<String>> before,
                                            Map<String, List<String>> after) {
        switch (scenario.change) {
            case ATTENDING -> {
                assertEquals(admin.id(), jdbc.queryForObject("select doctor_id from inp_admission where id=?",
                        Long.class, admission));
                assertNotEquals(before.get("admission"), after.get("admission"));
            }
            case REMOVE_SOURCE -> assertTrue(after.get("inp").isEmpty());
            case REVISE_SOURCE -> assertEquals(NEW_BODY, jdbc.queryForObject(
                    "select chief_complaint from outp_emr where id=?", String.class, outpatientRecord));
            default -> assertEquals(before, after);
        }
    }

    private Map<String, List<String>> sourceRows() {
        return Map.of("admission", rows("inp_admission", admission), "inp", rows("inp_medical_record", inpatientRecord),
                "registration", rows("outp_registration", registration), "outp", rows("outp_emr", outpatientRecord));
    }

    private List<String> rows(String table, Long id) {
        return jdbc.queryForList("select to_jsonb(t)::text from " + table + " t where id=?", String.class, id);
    }

    private Map<String, List<String>> templateRows() {
        return Map.of("templates", jdbc.queryForList("select to_jsonb(t)::text from emr_template t order by id", String.class),
                "grants", jdbc.queryForList("select to_jsonb(g)::text from emr_template_grant g order by id", String.class));
    }

    private void assertCopied(Case scenario, Reply copied, Map<String, List<String>> before) throws Exception {
        long id = copied.body().path("data").path("id").longValue();
        String recordType = scenario.inpatient() ? "PROGRESS" : "OUTP";
        String originalContent = scenario.inpatient() ? BODY : "主诉：" + BODY;
        var expected = new LinkedHashMap<String, Object>(Map.of("id", id, "name", tag, "scope", scenario.scope,
                "owner_id", actor.id(), "created_by", actor.id(), "template_type", "EMR", "record_type", recordType,
                "content", originalContent, "enabled", true, "is_default", false));
        expected.put("dept_id", scenario.departmentLock() ? oldDept : null);
        var row = jdbc.queryForMap("select * from emr_template where id=?", id);
        assertNotNull(row.remove("created_at"));
        assertEquals(expected, row);
        var expectedResponse = Map.of("id", id, "scope", scenario.scope, "recordType", recordType,
                "contentLength", originalContent.length(), "sourceLength", originalContent.length(),
                "truncated", false, "maxContent", 4000);
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(expectedResponse)), copied.body().path("data"));
        assertEquals(List.of(Map.of("template_id", id, "grantee_type", scenario.departmentLock() ? "DEPT" : "USER",
                "grantee_id", scenario.departmentLock() ? oldDept : actor.id(), "granted_by", actor.id())),
                jdbc.queryForList("select template_id,grantee_type,grantee_id,granted_by from emr_template_grant where template_id=?", id));
        assertEquals(before.get("templates"), jdbc.queryForList(
                "select to_jsonb(t)::text from emr_template t where id<>? order by id", String.class, id));
        assertEquals(before.get("grants"), jdbc.queryForList(
                "select to_jsonb(g)::text from emr_template_grant g where template_id<>? order by id", String.class, id));
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body, Staff employee) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var response = mvc.perform(request.header("Authorization", "Bearer " + employee.token())
                    .contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
            return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void expectOk(Reply reply) {
        assertEquals(200, reply.status(), reply.body().toString());
        assertEquals(0, reply.code(), reply.body().toString());
    }

    private Long department(String suffix) {
        return jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag + suffix, tag + suffix);
    }

    private Staff employee(String suffix, String duty) {
        String name = tag + suffix;
        long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id
                """, Long.class, name, name, oldDept);
        assertEquals(1, jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, duty));
        return new Staff(id, name, jwt.issue(name));
    }

    @AfterEach
    void cleanUp() {
        if (actor != null) jdbc.update("delete from emr_template where owner_id=?", actor.id());
        jdbc.update("delete from inp_medical_record where id=?", inpatientRecord);
        jdbc.update("delete from inp_admission where id=?", admission);
        jdbc.update("delete from inp_bed where id=?", bed);
        jdbc.update("delete from outp_emr where id=?", outpatientRecord);
        jdbc.update("delete from outp_registration where id=?", registration);
        jdbc.update("delete from outp_schedule where id=?", schedule);
        jdbc.update("delete from empi_patient where id=?", patient);
        if (actor != null) remove(actor);
        if (admin != null) remove(admin);
        jdbc.update("delete from sys_dept where id in (?,?)", oldDept, newDept);
        SecurityContextHolder.clearContext();
    }

    private void remove(Staff staff) {
        jdbc.update("delete from sys_audit_log where username=?", staff.username());
        jdbc.update("delete from sys_user_role where user_id=?", staff.id());
        jdbc.update("delete from sys_user where id=?", staff.id());
    }
}
