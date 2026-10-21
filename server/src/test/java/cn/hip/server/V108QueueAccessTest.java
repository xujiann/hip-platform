package cn.hip.server;

import cn.hip.platform.core.config.BusinessDates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** HTTP authorization and stable daily queue contracts; transaction rollback/concurrency live in V108QueueRaceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class V108QueueAccessTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired EntityManager em;

    static final String BASE = "/api/outpatient/queue";
    static final List<String> READERS = List.of("ADMIN", "DOCTOR_OUTP", "NURSE", "TECHNICIAN", "CASHIER");
    static final Set<String> BRIEF_KEYS = Set.of("registrationId", "regNo", "status", "patientName");
    final LocalDate today = BusinessDates.today();

    record Ticket(long id, long patient, long schedule, long slot, long dept, Long owner) {}

    String actor(boolean enabled, String... roles) {
        String name = "v108q" + System.nanoTime();
        long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,?) returning id",
                Long.class, name, name, enabled);
        for (String role : roles) jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        return name;
    }

    long uid(String name) {
        return jdbc.queryForObject("select id from sys_user where username=?", Long.class, name);
    }

    long dept() {
        String name = "V108Q" + System.nanoTime();
        return jdbc.queryForObject("insert into sys_dept(name,code,type) values (?,?,'CLINICAL') returning id", Long.class, name, name);
    }

    Ticket ticket(long dept, LocalDate day, String state, int number, Long owner) {
        String name = "Q" + System.nanoTime();
        long patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id", Long.class, name, name);
        long schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,doctor_id,schedule_date,fee,capacity,booked,last_issued_no)
                values (?,?,?,5,8,1,11) returning id
                """, Long.class, dept, owner, day);
        long slot = jdbc.queryForObject("insert into outp_schedule_slot(schedule_id,time_begin,time_end,capacity) values (?,'08:00','08:30',8) returning id",
                Long.class, schedule);
        long id = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status)
                values (?,?,?,?,?,?,5,?) returning id
                """, Long.class, number, patient, schedule, dept, owner, day, state);
        jdbc.update("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status)
                values (? ,?,'TREAT',1,'REG','挂号费','次',1,5,5,'CREATED')
                """, id, name);
        return new Ticket(id, patient, schedule, slot, dept, owner);
    }

    JsonNode call(String method, String path, String who, String... roles) throws Exception {
        em.flush();
        em.clear();
        SecurityContextHolder.clearContext();
        try {
            var request = ("POST".equals(method) ? post(path) : get(path)).with(user(who).roles(roles));
            var response = mvc.perform(request).andReturn().getResponse();
            assertTrue(response.getStatus() == 200 || response.getStatus() == 403, "HTTP " + response.getStatus());
            JsonNode body = om.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertEquals(Set.of("code", "message", "data"), keys(body), body.toString());
            if (body.path("code").asInt() == 0) em.flush();
            return body;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    JsonNode ok(String method, String path, String who, String... roles) throws Exception {
        var body = call(method, path, who, roles);
        assertEquals(0, body.path("code").asInt(), body.toString());
        return body.path("data");
    }

    void rejected(int code, String method, String path, String who, String... roles) throws Exception {
        var body = call(method, path, who, roles);
        assertEquals(code, body.path("code").asInt(), body.toString());
        assertTrue(body.path("data").isNull(), body.toString());
    }

    static Set<String> keys(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    void brief(JsonNode row, Ticket t, String state, int number) {
        assertEquals(BRIEF_KEYS, keys(row));
        assertEquals(t.id(), row.path("registrationId").asLong());
        assertEquals(state, row.path("status").asText());
        assertEquals(number, row.path("regNo").asInt());
        assertEquals(jdbc.queryForObject("select name from empi_patient where id=?", String.class, t.patient()), row.path("patientName").asText());
    }

    Map<String, Object> immutable(Ticket t) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("registration", jdbc.queryForMap("""
                select id,reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,created_at,cancelled_at
                from outp_registration where id=?
                """, t.id()));
        values.put("schedule", jdbc.queryForMap("select * from outp_schedule where id=?", t.schedule()));
        values.put("slot", jdbc.queryForMap("select * from outp_schedule_slot where id=?", t.slot()));
        values.put("orders", jdbc.queryForList("select * from outp_order where registration_id=? order by id", t.id()));
        return values;
    }

    Map<String, Object> all(Ticket t) {
        Map<String, Object> values = new LinkedHashMap<>(immutable(t));
        values.put("state", jdbc.queryForMap("select status,updated_at from outp_registration where id=?", t.id()));
        values.put("logs", jdbc.queryForList("select * from outp_call_log where registration_id=? order by id", t.id()));
        return values;
    }

    void state(Ticket t, String status) {
        assertEquals(status, jdbc.queryForObject("select status from outp_registration where id=?", String.class, t.id()));
    }

    long logs(Ticket t) {
        return jdbc.queryForObject("select count(*) from outp_call_log where registration_id=?", Long.class, t.id());
    }

    String pass(Ticket t) { return BASE + "/pass?registrationId=" + t.id(); }
    String recall(Ticket t) { return BASE + "/recall?registrationId=" + t.id(); }
    String next(long dept) { return BASE + "/call-next?deptId=" + dept; }

    @Test
    void fiveLiveReaderRolesKeepDailySharedQueueAndExactProjection() throws Exception {
        long d = dept();
        String owner = actor(true, "DOCTOR_OUTP");
        Ticket waiting = ticket(d, today, "REGISTERED", 2, uid(owner));
        Ticket called = ticket(d, today, "CALLED", 3, uid(owner));
        Ticket visiting = ticket(d, today, "VISITED", 4, uid(owner));
        Ticket passed = ticket(d, today, "PASSED", 5, uid(owner));
        ticket(d, today, "CANCELLED", 6, uid(owner));
        ticket(d, today.minusDays(1), "REGISTERED", 1, uid(owner));
        ticket(d, today.plusDays(1), "REGISTERED", 1, uid(owner));
        ticket(dept(), today, "REGISTERED", 1, uid(owner));
        var before = List.of(all(waiting), all(called), all(visiting), all(passed));
        for (String role : READERS) {
            String who = actor(true, role);
            jdbc.update("update sys_user set dept_id=? where username=?", dept(), who);
            var board = ok("GET", BASE + "?deptId=" + d, who, role);
            assertEquals(Set.of("deptName", "waiting", "called", "visiting", "passed"), keys(board));
            assertEquals(jdbc.queryForObject("select name from sys_dept where id=?", String.class, d), board.path("deptName").asText());
            for (String key : List.of("waiting", "called", "visiting", "passed")) assertEquals(1, board.path(key).size(), board.toString());
            brief(board.path("waiting").get(0), waiting, "REGISTERED", 2);
            brief(board.path("called").get(0), called, "CALLED", 3);
            brief(board.path("visiting").get(0), visiting, "VISITED", 4);
            brief(board.path("passed").get(0), passed, "PASSED", 5);
        }
        assertEquals(before, List.of(all(waiting), all(called), all(visiting), all(passed)));
    }

    @Test
    void adminAndDoctorMayCallPassRecallOtherDoctorsTodayAcrossDepartments() throws Exception {
        for (String role : List.of("ADMIN", "DOCTOR_OUTP")) {
            String who = actor(true, role), owner = actor(true, "DOCTOR_OUTP");
            jdbc.update("update sys_user set dept_id=? where username=?", dept(), who);
            Ticket t = ticket(dept(), today, "REGISTERED", 7, uid(owner));
            var before = immutable(t);
            brief(ok("POST", next(t.dept()), who, role), t, "CALLED", 7);
            assertEquals(1, logs(t));
            assertTrue(ok("POST", pass(t), who, role).isNull());
            state(t, "PASSED");
            assertEquals(1, logs(t));
            brief(ok("POST", recall(t), who, role), t, "CALLED", 7);
            state(t, "CALLED");
            assertEquals(2, logs(t));
            assertEquals(before, immutable(t), "queue transitions cannot rewrite ownership, numbers, capacity or fees");
            for (var log : jdbc.queryForList("select registration_id,dept_id,reg_no,called_at from outp_call_log where registration_id=?", t.id())) {
                assertEquals(t.id(), ((Number) log.get("registration_id")).longValue());
                assertEquals(t.dept(), ((Number) log.get("dept_id")).longValue());
                assertEquals(7, ((Number) log.get("reg_no")).intValue());
                assertNotNull(log.get("called_at"));
            }
        }
    }

    @Test
    void unassignedExistingTodayTicketRemainsOperableAfterPatientAndScheduleDisabled() throws Exception {
        String who = actor(true, "DOCTOR_OUTP");
        Ticket t = ticket(dept(), today, "REGISTERED", 9, null);
        jdbc.update("update empi_patient set active=false where id=?", t.patient());
        jdbc.update("update outp_schedule set enabled=false where id=?", t.schedule());
        var before = immutable(t);
        brief(ok("POST", next(t.dept()), who, "DOCTOR_OUTP"), t, "CALLED", 9);
        ok("POST", pass(t), who, "DOCTOR_OUTP");
        brief(ok("POST", recall(t), who, "DOCTOR_OUTP"), t, "CALLED", 9);
        assertEquals(before, immutable(t));
        assertNull(jdbc.queryForObject("select doctor_id from outp_registration where id=?", Long.class, t.id()));
        assertEquals(2, logs(t));
    }

    @Test
    void readOnlyRolesCannotReachAnyQueueWrite() throws Exception {
        Ticket waiting = ticket(dept(), today, "REGISTERED", 1, null);
        Ticket called = ticket(waiting.dept(), today, "CALLED", 2, null);
        Ticket passed = ticket(waiting.dept(), today, "PASSED", 3, null);
        var before = List.of(all(waiting), all(called), all(passed));
        for (String role : List.of("NURSE", "TECHNICIAN", "CASHIER")) {
            String who = actor(true, role);
            rejected(1005, "POST", next(waiting.dept()), who, role);
            rejected(1005, "POST", pass(called), who, role);
            rejected(1005, "POST", recall(passed), who, role);
        }
        assertEquals(before, List.of(all(waiting), all(called), all(passed)));
    }

    @Test
    void unrelatedRolesCannotReadOrMutateQueue() throws Exception {
        Ticket waiting = ticket(dept(), today, "REGISTERED", 1, null);
        Ticket called = ticket(waiting.dept(), today, "CALLED", 2, null);
        Ticket passed = ticket(waiting.dept(), today, "PASSED", 3, null);
        var before = List.of(all(waiting), all(called), all(passed));
        for (String role : List.of("QUALITY", "PHARMACIST", "OPERATION", "INTERFACE", "PORTAL")) {
            String who = actor(true, role);
            rejected(1005, "GET", BASE + "?deptId=" + waiting.dept(), who, role);
            rejected(1005, "POST", next(waiting.dept()), who, role);
            rejected(1005, "POST", pass(called), who, role);
            rejected(1005, "POST", recall(passed), who, role);
        }
        assertEquals(before, List.of(all(waiting), all(called), all(passed)));
    }

    @Test
    void mixedRolesKeepGrantedDoctorAndAdminWriteCapability() throws Exception {
        for (String writer : List.of("ADMIN", "DOCTOR_OUTP")) {
            String who = actor(true, writer, "CASHIER", "NURSE");
            Ticket t = ticket(dept(), today, "REGISTERED", 1, null);
            brief(ok("POST", next(t.dept()), who, writer, "CASHIER", "NURSE"), t, "CALLED", 1);
            ok("POST", pass(t), who, writer, "CASHIER", "NURSE");
            brief(ok("POST", recall(t), who, writer, "CASHIER", "NURSE"), t, "CALLED", 1);
            assertEquals(2, logs(t));
        }
    }

    @Test
    void missingAndDisabledActorsCannotUseAnyEndpointEvenWithAcceptedRoles() throws Exception {
        Ticket waiting = ticket(dept(), today, "REGISTERED", 1, null);
        Ticket called = ticket(waiting.dept(), today, "CALLED", 2, null);
        Ticket passed = ticket(waiting.dept(), today, "PASSED", 3, null);
        var before = List.of(all(waiting), all(called), all(passed));
        for (String role : READERS) {
            for (String who : List.of(actor(false, role), "v108missing" + System.nanoTime())) {
                rejected(1005, "GET", BASE + "?deptId=" + waiting.dept(), who, role);
                rejected(1005, "POST", next(waiting.dept()), who, role);
                rejected(1005, "POST", pass(called), who, role);
                rejected(1005, "POST", recall(passed), who, role);
            }
        }
        assertEquals(before, List.of(all(waiting), all(called), all(passed)));
    }

    @Test
    void yesterdayAndFutureObjectsCannotBePassedOrRecalledEvenByAdminOrOwner() throws Exception {
        for (String role : List.of("ADMIN", "DOCTOR_OUTP")) {
            String who = actor(true, role);
            for (LocalDate day : List.of(today.minusDays(1), today.plusDays(1))) {
                Ticket called = ticket(dept(), day, "CALLED", 1, uid(who));
                Ticket passed = ticket(called.dept(), day, "PASSED", 2, uid(who));
                var before = List.of(all(called), all(passed));
                rejected(1005, "POST", pass(called), who, role);
                rejected(1005, "POST", recall(passed), who, role);
                var board = ok("GET", BASE + "?deptId=" + called.dept(), who, role);
                for (String key : List.of("waiting", "called", "visiting", "passed")) assertTrue(board.path(key).isEmpty());
                rejected(3301, "POST", next(called.dept()), who, role);
                assertEquals(before, List.of(all(called), all(passed)));
            }
        }
    }

    @Test
    void existingMissingEmptyAndWrongStateCodesRemainSideEffectFree() throws Exception {
        String who = actor(true, "DOCTOR_OUTP");
        long d = dept();
        rejected(3301, "POST", next(d), who, "DOCTOR_OUTP");
        rejected(3302, "POST", BASE + "/pass?registrationId=-1", who, "DOCTOR_OUTP");
        rejected(3303, "POST", BASE + "/recall?registrationId=-1", who, "DOCTOR_OUTP");
        for (String status : List.of("REGISTERED", "CALLED", "PASSED", "VISITED", "CANCELLED")) {
            Ticket t = ticket(d, today, status, 3, null);
            var before = all(t);
            if (!"CALLED".equals(status)) rejected(3302, "POST", pass(t), who, "DOCTOR_OUTP");
            if (!"PASSED".equals(status)) rejected(3303, "POST", recall(t), who, "DOCTOR_OUTP");
            assertEquals(before, all(t));
        }
    }

    @Test
    void callNextUsesNumberThenIdAndTransitionsExactlyOneCandidate() throws Exception {
        String who = actor(true, "DOCTOR_OUTP");
        long d = dept();
        Ticket tieFirst = ticket(d, today, "REGISTERED", 7, null);
        Ticket tieSecond = ticket(d, today, "REGISTERED", 7, null);
        Ticket lowest = ticket(d, today, "REGISTERED", 3, null);
        var firstBefore = immutable(tieFirst);
        var secondBefore = all(tieSecond);
        var lowestBefore = immutable(lowest);
        brief(ok("POST", next(d), who, "DOCTOR_OUTP"), lowest, "CALLED", 3);
        state(tieFirst, "REGISTERED");
        state(tieSecond, "REGISTERED");
        brief(ok("POST", next(d), who, "DOCTOR_OUTP"), tieFirst, "CALLED", 7);
        assertEquals(1, logs(lowest));
        assertEquals(1, logs(tieFirst));
        assertEquals(0, logs(tieSecond));
        assertEquals(firstBefore, immutable(tieFirst));
        assertEquals(lowestBefore, immutable(lowest));
        assertEquals(secondBefore, all(tieSecond));
    }
}
