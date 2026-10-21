package cn.hip.server;

import cn.hip.outpatient.service.AppointmentService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.config.BusinessDates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Read visibility and existing desk/cancellation contracts; no claim of transaction rollback or lock serialization. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class V109PortalScheduleSlotsTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AppointmentService appointments;
    @Autowired RegistrationService registrations;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired EntityManager em;

    static final Set<String> KEYS = Set.of("id", "time_begin", "time_end", "capacity", "booked", "remaining", "enabled");

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    String unique() { return "S109" + Long.toUnsignedString(System.nanoTime(), 36); }
    long patient() {
        String name = unique();
        return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex,active) values (?,?,'U',true) returning id",
                Long.class, name, name);
    }
    String staff(String role) {
        String name = unique();
        long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",
                Long.class, name, name);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        return name;
    }
    long schedule(boolean enabled) {
        return jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,fee,capacity,enabled) values (1,?,5,20,?) returning id",
                Long.class, BusinessDates.today(), enabled);
    }
    long slot(long schedule, String begin, boolean enabled, int capacity, int booked) {
        return jdbc.queryForObject("""
                insert into outp_schedule_slot(schedule_id,time_begin,time_end,capacity,booked,enabled)
                values (?,?::time,(?::time+interval '20 minutes')::time,?,?,?) returning id
                """, Long.class, schedule, begin, begin, capacity, booked, enabled);
    }
    String portal(long patient) { return "portal:" + patient; }
    String portalSlots(long schedule) { return "/api/portal/schedules/" + schedule + "/slots"; }
    String deskSlots(long schedule) { return "/api/outpatient/schedules/" + schedule + "/slots"; }
    void parent(long schedule, boolean enabled) { jdbc.update("update outp_schedule set enabled=? where id=?", enabled, schedule); }

    JsonNode call(String method, String path, String principal, String... roles) throws Exception {
        em.flush();
        em.clear();
        SecurityContextHolder.clearContext();
        try {
            var request = (method.equals("POST") ? post(path) : get(path)).with(user(principal).roles(roles));
            var response = mvc.perform(request).andReturn().getResponse();
            assertTrue(response.getStatus() == 200 || response.getStatus() == 403, "HTTP " + response.getStatus());
            return om.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        } finally { SecurityContextHolder.clearContext(); }
    }
    JsonNode ok(String path, String principal, String... roles) throws Exception {
        var response = call("GET", path, principal, roles);
        assertEquals(0, response.path("code").asInt(), response.toString());
        return response.path("data");
    }
    static Set<String> keys(JsonNode row) {
        Set<String> keys = new HashSet<>(); row.fieldNames().forEachRemaining(keys::add); return keys;
    }
    static List<Long> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }
    static List<Long> ids(JsonNode rows) {
        List<Long> ids = new ArrayList<>(); for (JsonNode row : rows) ids.add(row.path("id").asLong()); return ids;
    }
    JsonNode row(JsonNode rows, long id) {
        for (JsonNode row : rows) if (row.path("id").asLong() == id) return row;
        fail("Missing row " + id); return null;
    }
    Map<String, Object> accounting(long schedule) {
        em.flush();
        return Map.of(
                "schedule", jdbc.queryForMap("select * from outp_schedule where id=?", schedule),
                "slots", jdbc.queryForList("select * from outp_schedule_slot where schedule_id=? order by id", schedule),
                "appointments", jdbc.queryForList("select * from outp_appointment where schedule_id=? order by id", schedule),
                "registrations", jdbc.queryForList("select * from outp_registration where schedule_id=? order by id", schedule),
                "fees", jdbc.queryForList("select * from outp_order where registration_id in (select id from outp_registration where schedule_id=?) order by id", schedule));
    }

    @Test void enabledParentProjectionRetainsKeysChronologicalOrderAndRealRemaining() throws Exception {
        long schedule = schedule(true);
        long late = slot(schedule, "10:00", true, 4, 4);
        slot(schedule, "09:00", false, 3, 1);
        long early = slot(schedule, "08:00", true, 5, 2);
        var rows = appointments.slots(schedule, true);
        assertEquals(List.of(early, late), ids(rows));
        for (var row : rows) assertEquals(KEYS, row.keySet());
        assertEquals(3, ((Number) rows.getFirst().get("remaining")).intValue());
        assertEquals(0, ((Number) rows.getLast().get("remaining")).intValue());
        assertTrue(rows.getFirst().get("time_begin").toString().startsWith("08:00"));
        var http = ok(portalSlots(schedule), portal(patient()), "PORTAL");
        assertEquals(List.of(early, late), ids(http));
        for (var row : http) assertEquals(KEYS, keys(row));
        assertEquals(2, http.get(0).path("booked").asInt());
        assertEquals(5, http.get(0).path("capacity").asInt());
        assertEquals(3, http.get(0).path("remaining").asInt());
    }

    @Test void stoppedParentHidesEvenAnEnabledAvailableSlotInTheSharedPatientService() {
        long schedule = schedule(false);
        long enabled = slot(schedule, "08:00", true, 5, 0);
        slot(schedule, "09:00", false, 5, 0);
        assertTrue(appointments.slots(schedule, true).isEmpty());
        assertTrue(ids(appointments.slots(schedule, false)).contains(enabled));
    }

    @Test void missingAndEmptySchedulesKeepSuccessfulEmptyListsForBothEntrypoints() throws Exception {
        long empty = schedule(true); String patient = portal(patient()), cashier = staff("CASHIER");
        for (long schedule : List.of(-109L, empty)) {
            assertTrue(appointments.slots(schedule, true).isEmpty());
            assertTrue(appointments.slots(schedule, false).isEmpty());
            assertTrue(ok(portalSlots(schedule), patient, "PORTAL").isEmpty());
            assertTrue(ok(deskSlots(schedule), cashier, "CASHIER").isEmpty());
        }
    }

    @Test void knownStoppedScheduleCannotBeExposedThroughCallerControlledQueryFlags() throws Exception {
        long schedule = schedule(false); slot(schedule, "08:00", true, 5, 0);
        String patient = portal(patient());
        for (String query : List.of("", "?onlyEnabled=false", "?includeDisabled=true", "?onlyEnabled=false&includeDisabled=true")) {
            var data = ok(portalSlots(schedule) + query, patient, "PORTAL");
            assertTrue(data.isArray() && data.isEmpty(), data.toString());
        }
    }

    @Test void enabledAdministratorsAndCashiersCanStillReviewEverySlotOfAStoppedSchedule() throws Exception {
        long schedule = schedule(false);
        long disabled = slot(schedule, "09:00", false, 4, 1), enabled = slot(schedule, "08:00", true, 5, 2);
        for (String role : List.of("ADMIN", "CASHIER")) {
            var rows = ok(deskSlots(schedule), staff(role), role);
            assertEquals(List.of(enabled, disabled), ids(rows));
            assertTrue(rows.get(0).path("enabled").asBoolean());
            assertFalse(rows.get(1).path("enabled").asBoolean());
            assertEquals(3, rows.get(0).path("remaining").asInt());
            assertEquals(3, rows.get(1).path("remaining").asInt());
            for (var row : rows) assertEquals(KEYS, keys(row));
        }
    }

    @Test void everyReadReevaluatesBothParentAndSlotStateIncludingReenable() throws Exception {
        long schedule = schedule(true), slot = slot(schedule, "08:00", true, 5, 2);
        String patient = portal(patient());
        for (boolean parent : List.of(true, false, true)) {
            parent(schedule, parent);
            for (boolean child : List.of(true, false)) {
                jdbc.update("update outp_schedule_slot set enabled=? where id=?", child, slot);
                assertEquals(parent && child ? List.of(slot) : List.of(), ids(appointments.slots(schedule, true)));
                assertEquals(parent && child ? List.of(slot) : List.of(), ids(ok(portalSlots(schedule), patient, "PORTAL")));
                assertEquals(List.of(slot), ids(appointments.slots(schedule, false)));
            }
        }
    }

    @Test void stopFilteringIsBoundToTheRequestedScheduleAndDoesNotMixSiblingSlots() throws Exception {
        long stopped = schedule(false), live = schedule(true);
        long stoppedSlot = slot(stopped, "08:00", true, 5, 0), liveSlot = slot(live, "09:00", true, 5, 0);
        String patient = portal(patient());
        assertTrue(ok(portalSlots(stopped), patient, "PORTAL").isEmpty());
        assertEquals(List.of(liveSlot), ids(ok(portalSlots(live), patient, "PORTAL")));
        assertEquals(List.of(stoppedSlot), ids(appointments.slots(stopped, false)));
        assertEquals(List.of(liveSlot), ids(appointments.slots(live, false)));
    }

    @Test void repeatedPortalAndDeskReadsDoNotMutateExistingAppointmentsNumbersRegistrationsOrFees() throws Exception {
        long schedule = schedule(true), slot = slot(schedule, "08:00", true, 10, 0);
        long owner = patient();
        var booked = appointments.book(slot, owner, "PORTAL"); assertTrue(booked.ok());
        var registration = registrations.register(patient(), schedule);
        parent(schedule, false);
        var before = accounting(schedule);
        assertFalse(((List<?>) before.get("appointments")).isEmpty());
        assertFalse(((List<?>) before.get("registrations")).isEmpty());
        assertFalse(((List<?>) before.get("fees")).isEmpty());
        String cashier = staff("CASHIER");
        for (int i = 0; i < 3; i++) {
            ok(portalSlots(schedule), portal(owner), "PORTAL");
            ok(deskSlots(schedule), cashier, "CASHIER");
            appointments.slots(schedule, true); appointments.slots(schedule, false);
        }
        assertEquals(before, accounting(schedule));
        assertEquals("REGISTERED", jdbc.queryForObject("select status from outp_registration where id=?", String.class, registration.getId()));
        assertEquals(2, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule));
    }

    @Test void stoppingAvailabilityDoesNotHideOrPreventCancellationOfThePatientsExistingAppointment() throws Exception {
        long schedule = schedule(true), slot = slot(schedule, "08:00", true, 5, 0), owner = patient(), other = patient();
        var booked = appointments.book(slot, owner, "PORTAL"); assertTrue(booked.ok());
        parent(schedule, false);
        String history = "/api/portal/my/appointments", cancel = history + "/" + booked.apptId() + "/cancel";
        assertEquals("BOOKED", row(ok(history, portal(owner), "PORTAL"), booked.apptId()).path("status").asText());
        assertEquals(9506, call("POST", cancel, portal(other), "PORTAL").path("code").asInt());
        assertEquals(0, call("POST", cancel, portal(owner), "PORTAL").path("code").asInt());
        assertEquals("CANCELLED", row(ok(history, portal(owner), "PORTAL"), booked.apptId()).path("status").asText());
        assertEquals(0, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(0, jdbc.queryForObject("select booked from outp_schedule_slot where id=?", Integer.class, slot));
        assertEquals(1, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule));
        assertFalse(jdbc.queryForObject("select enabled from outp_schedule where id=?", Boolean.class, schedule));
    }

    @Test void patientAndEmployeeSlotEntrypointsKeepTheirExistingRoleBoundary() throws Exception {
        long schedule = schedule(true); slot(schedule, "08:00", true, 5, 0);
        String patient = portal(patient());
        assertEquals(0, call("GET", portalSlots(schedule), patient, "PORTAL").path("code").asInt());
        assertEquals(1005, call("GET", deskSlots(schedule), patient, "PORTAL").path("code").asInt());
        for (String role : List.of("ADMIN", "CASHIER", "DOCTOR_OUTP", "NURSE", "TECHNICIAN")) {
            String actor = staff(role);
            assertEquals(1005, call("GET", portalSlots(schedule), actor, role).path("code").asInt());
            assertEquals(role.equals("ADMIN") || role.equals("CASHIER") ? 0 : 1005,
                    call("GET", deskSlots(schedule), actor, role).path("code").asInt());
        }
    }
}
