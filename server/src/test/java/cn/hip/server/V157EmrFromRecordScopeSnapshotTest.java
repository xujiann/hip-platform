package cn.hip.server;

import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Real JWT requests pause before the source SQL executes, with no enclosing test transaction. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(V152EmrHistoryAccessTest.JdbcConfiguration.class)
class V157EmrFromRecordScopeSnapshotTest {
    private static final String COPY = "/api/emr-templates/from-record";

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    private record Staff(Long id, String username, String token) {}
    private record Reply(int status, JsonNode body) {
        int code() { return body.path("code").asInt(); }
    }
    private enum Scenario {
        HOSPITAL_AFTER_ADMIN_LOSS("HOSPITAL", "ADMIN", false, false, 4066),
        GLOBAL_AFTER_ADMIN_LOSS("GLOBAL", "ADMIN", false, false, 4066),
        DEPARTMENT_OMITTED_AFTER_TRANSFER("DEPT", "QUALITY", true, false, 0),
        OLD_DEPARTMENT_EXPLICIT_AFTER_TRANSFER("DEPT", "QUALITY", true, true, 4066),
        PERSONAL_AFTER_ADMIN_LOSS("PERSONAL", "ADMIN", false, false, 0);

        final String scope;
        final String oldRole;
        final boolean transfer;
        final boolean explicitOldDepartment;
        final int code;

        Scenario(String scope, String oldRole, boolean transfer, boolean explicitOldDepartment, int code) {
            this.scope = scope;
            this.oldRole = oldRole;
            this.transfer = transfer;
            this.explicitOldDepartment = explicitOldDepartment;
            this.code = code;
        }
    }

    private String prefix;
    private Long oldDept, newDept, patient, schedule, registration, outpatientRecord, bed, admission, inpatientRecord;
    private Staff actor, controller;

    @BeforeEach
    void setUp() {
        prefix = "V157" + Long.toUnsignedString(System.nanoTime(), 36);
        oldDept = department("old");
        newDept = department("new");
        controller = employee("control", "ADMIN");
        patient = jdbc.queryForObject("insert into empi_patient(name,sex) values (?,'U') returning id",
                Long.class, prefix);
        var visitDate = BusinessDates.today().minusDays(4);
        schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,doctor_id,schedule_date,capacity,booked)
                values (?,?,?,10,1) returning id
                """, Long.class, oldDept, controller.id(), visitDate);
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,status)
                values (1,?,?,?,?,?,'VISITED') returning id
                """, Long.class, patient, schedule, oldDept, controller.id(), visitDate);
        outpatientRecord = jdbc.queryForObject("""
                insert into outp_emr(registration_id,doctor_id,chief_complaint)
                values (?,?,?) returning id
                """, Long.class, registration, controller.id(), prefix);
        bed = jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id",
                Long.class, oldDept, prefix);
        admission = jdbc.queryForObject("""
                insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status)
                values (?,?,?,?,?,?,'DISCHARGED') returning id
                """, Long.class, prefix, patient, oldDept, oldDept, bed, controller.id());
        inpatientRecord = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id,record_type,title,content,doctor_id)
                values (?,'PROGRESS',?,?,?) returning id
                """, Long.class, admission, prefix, prefix, controller.id());
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
        return Stream.of("INP", "OUTP").flatMap(source -> Stream.of(Scenario.values())
                .map(scenario -> Arguments.of(source, scenario)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("requests")
    void targetUsesTheEmployeeWhoWasAuthorizedToReadTheSource(String source, Scenario scenario) throws Exception {
        actor = employee("actor", scenario.oldRole);
        boolean inpatient = source.equals("INP");
        Long recordId = inpatient ? inpatientRecord : outpatientRecord;
        var body = new LinkedHashMap<String, Object>(Map.of(
                "source", source, "recordId", recordId, "patientId", patient,
                "name", prefix, "scope", scenario.scope));
        if (scenario.explicitOldDepartment) body.put("deptId", oldDept);
        var sourceBefore = sourceRows();
        assertTrue(templates().isEmpty());
        assertTrue(grants().isEmpty());
        var gap = new V152EmrHistoryAccessTest.Gap(false);
        var worker = Executors.newSingleThreadExecutor();
        var pending = worker.submit(() -> {
            V152EmrHistoryAccessTest.Gap.TRACE.set(gap);
            try {
                return call(post(COPY), body, actor);
            } finally {
                V152EmrHistoryAccessTest.Gap.TRACE.remove();
            }
        });

        Reply copied;
        String staffPath = "/api/system/users/" + actor.id();
        try {
            gap.await();
            String sql = (String) gap.statements.getFirst().get("sql");
            assertTrue(sql.contains("selected.id as __history_row_id") && sql.contains("from history_read_scope"),
                    "The worker must pause at the single-record source query");
            assertTrue(sql.contains(inpatient ? "from inp_medical_record r" : "from outp_emr e"));
            assertEquals(List.of("BEFORE"), gap.statements.stream().map(event -> event.get("position")).toList());
            Long committedDept = scenario.transfer ? newDept : oldDept;
            Reply changed = call(put(staffPath), Map.of("username", actor.username(), "realName", actor.username(),
                    "deptId", committedDept, "roleCodes", List.of("QUALITY")), controller);
            assertEquals(200, changed.status(), changed.body().toString());
            assertEquals(0, changed.code(), changed.body().toString());
            assertEquals(actor.id().longValue(), changed.body().path("data").path("id").asLong());
            assertEquals(committedDept, jdbc.queryForObject("select dept_id from sys_user where id=?",
                    Long.class, actor.id()));
            assertEquals(List.of("QUALITY"), jdbc.queryForList("""
                    select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=?
                    """, String.class, actor.id()));
            assertFalse(pending.isDone(), "The copy request must still be paused after the employee update commits");
            gap.release();
            copied = pending.get(20, TimeUnit.SECONDS);
        } finally {
            gap.release();
            worker.shutdown();
            assertTrue(worker.awaitTermination(25, TimeUnit.SECONDS), "The copy request did not exit");
        }

        assertEquals(List.of("BEFORE", "AFTER"), gap.statements.stream().map(event -> event.get("position")).toList());
        assertEquals(List.of(
                Map.of("username", controller.username(), "method", "PUT", "path", staffPath, "http_status", 200),
                Map.of("username", actor.username(), "method", "POST", "path", COPY, "http_status", 200)),
                jdbc.queryForList("""
                        select username,method,path,http_status from sys_audit_log
                        where username in (?,?) order by id
                        """, controller.username(), actor.username()));
        assertEquals(sourceBefore, sourceRows(), "Copying must leave both clinical source records intact");
        assertAll(
                () -> assertEquals(200, copied.status(), copied.body().toString()),
                () -> assertEquals(scenario.code, copied.code(), copied.body().toString()),
                () -> {
                    if (scenario.code == 0) {
                        assertCreated(copied, scenario, inpatient);
                    } else {
                        assertAll(
                                () -> assertTrue(copied.body().path("data").isNull()),
                                () -> assertTrue(templates().isEmpty(), "A denied copy created a template"),
                                () -> assertTrue(grants().isEmpty(), "A denied copy created an authorization"));
                    }
                });
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body, Staff employee) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var response = mvc.perform(request.contentType("application/json").content(mapper.writeValueAsBytes(body))
                            .header("Authorization", "Bearer " + employee.token()))
                    .andReturn().getResponse();
            return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private List<String> sourceRows() {
        return jdbc.queryForList("""
                select to_jsonb(r)::text from inp_medical_record r where id=?
                union all
                select to_jsonb(e)::text from outp_emr e where id=?
                """, String.class, inpatientRecord, outpatientRecord);
    }

    private List<Map<String, Object>> templates() {
        return jdbc.queryForList("""
                select id,dept_id,scope,owner_id,created_by,template_type,record_type,content,enabled,is_default
                from emr_template where name=?
                """, prefix);
    }

    private List<Map<String, Object>> grants() {
        return jdbc.queryForList("""
                select template_id,grantee_type,grantee_id,granted_by from emr_template_grant
                where granted_by=? or template_id in (select id from emr_template where name=?)
                """, actor.id(), prefix);
    }

    private void assertCreated(Reply reply, Scenario scenario, boolean inpatient) throws Exception {
        var rows = templates();
        assertEquals(1, rows.size(), "The successful request must commit exactly one template");
        var template = rows.getFirst();
        Long id = (Long) template.get("id");
        boolean departmental = scenario.scope.equals("DEPT");
        String recordType = inpatient ? "PROGRESS" : "OUTP";
        String expectedContent = inpatient ? prefix : "主诉：" + prefix;
        var expected = new LinkedHashMap<String, Object>(Map.of(
                "id", id, "scope", scenario.scope, "owner_id", actor.id(), "created_by", actor.id(),
                "template_type", "EMR", "record_type", recordType, "content", expectedContent,
                "enabled", true, "is_default", false));
        expected.put("dept_id", departmental ? newDept : null);
        int length = expectedContent.length();
        var expectedData = Map.of("id", id, "scope", scenario.scope, "recordType", recordType,
                "contentLength", length, "sourceLength", length, "truncated", false, "maxContent", 4000);
        assertAll(
                () -> assertEquals(expected, template),
                () -> assertEquals(List.of(Map.of("template_id", id, "grantee_type", departmental ? "DEPT" : "USER",
                        "grantee_id", departmental ? newDept : actor.id(), "granted_by", actor.id())), grants()),
                () -> assertEquals(mapper.readTree(mapper.writeValueAsBytes(expectedData)), reply.body().path("data")));
    }

    @AfterEach
    void cleanUp() {
        if (prefix == null) return;
        jdbc.update("delete from emr_template_grant where template_id in (select id from emr_template where name=?)", prefix);
        jdbc.update("delete from emr_template where name=?", prefix);
        jdbc.update("delete from inp_medical_record where id=?", inpatientRecord);
        jdbc.update("delete from inp_admission where id=?", admission);
        jdbc.update("delete from inp_bed where id=?", bed);
        jdbc.update("delete from outp_emr where id=?", outpatientRecord);
        jdbc.update("delete from outp_registration where id=?", registration);
        jdbc.update("delete from outp_schedule where id=?", schedule);
        jdbc.update("delete from empi_patient where id=?", patient);
        jdbc.update("delete from sys_user_role where user_id in (select id from sys_user where username in (?,?))",
                prefix + "actor", prefix + "control");
        jdbc.update("delete from sys_user where username in (?,?)", prefix + "actor", prefix + "control");
        jdbc.update("delete from sys_dept where id in (?,?)", oldDept, newDept);
    }
}
