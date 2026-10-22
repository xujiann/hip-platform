package cn.hip.server;

import cn.hip.platform.core.config.BusinessDates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Read-only MockMvc contracts. Real commit/rollback races are in V112ReturnInfusionRaceTest. */
@SpringBootTest @AutoConfigureMockMvc @Transactional
class V112PharmacyReadAccessTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    static final String BASE = "/api/outpatient/dispense";

    String employee(boolean enabled) {
        String name = "P112" + System.nanoTime();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,?)", name, name, enabled);
        return name;
    }
    JsonNode request(String method, String path, String who, String role) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req = ("POST".equals(method) ? post(path) : get(path)).with(user(who).roles(role));
            if ("POST".equals(method) && path.equals("/api/outpatient/registrations"))
                req.contentType("application/json").content("{\"patientId\":1,\"scheduleId\":1}");
            var response = mvc.perform(req).andReturn().getResponse();
            assertTrue(response.getStatus() < 500, "unexpected server failure: " + response.getStatus());
            return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        } finally { SecurityContextHolder.clearContext(); }
    }
    String selector() { return BASE + "/registrations?date=" + BusinessDates.today(); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "PHARMACIST"})
    void enabledPharmacyCanReadSharedWorklistAndHistory(String role) throws Exception {
        String who = employee(true);
        for (String path : new String[]{BASE + "/worklist", BASE + "/dispensed?registrationId=9223372036854775807"}) {
            JsonNode body = request("GET", path, who, role);
            assertEquals(0, body.path("code").asInt(), body.toString());
            assertTrue(body.path("data").isArray());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "PHARMACIST"})
    void disabledOrUnknownPharmacyCannotReadOrWrite(String role) throws Exception {
        for (String who : new String[]{employee(false), "v112_nonexistent_employee"}) {
            for (String path : new String[]{BASE + "/worklist", BASE + "/dispensed?registrationId=1", selector()})
                assertEquals(1005, request("GET", path, who, role).path("code").asInt(), path);
            for (String path : new String[]{BASE + "/9223372036854775807", BASE + "/orders/9223372036854775807/return"})
                assertEquals(1005, request("POST", path, who, role).path("code").asInt(), path);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"CASHIER", "NURSE", "TECHNICIAN", "DOCTOR_OUTP", "PORTAL"})
    void otherDutiesCannotReadOrWritePharmacy(String role) throws Exception {
        String who = employee(true);
        for (String path : new String[]{BASE + "/worklist", BASE + "/dispensed?registrationId=1", selector()})
            assertEquals(1005, request("GET", path, who, role).path("code").asInt(), path);
        for (String path : new String[]{BASE + "/1", BASE + "/orders/1/return"})
            assertEquals(1005, request("POST", path, who, role).path("code").asInt(), path);
    }
    long registration(LocalDate date, String suffix) {
        String tag = "R112" + System.nanoTime() + suffix;
        long dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag, tag);
        long patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id", Long.class, tag, tag);
        long schedule = jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,fee) values (?,?,5,1,0) returning id", Long.class, dept, date);
        return jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (1,?,?,?,?,0,'VISITED') returning id", Long.class, patient, schedule, dept, date);
    }
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "PHARMACIST"})
    void selectorUsesExistingMinimalKeysAndDateAcrossDepartments(String role) throws Exception {
        LocalDate date = BusinessDates.today().minusDays(7);
        long a = registration(date, "a"), b = registration(date, "b"), other = registration(date.plusDays(1), "c");
        var response = request("GET", BASE + "/registrations?date=" + date, employee(true), role);
        assertEquals(0, response.path("code").asInt(), response.toString());
        Set<Long> ids = new HashSet<>();
        for (JsonNode row : response.path("data")) {
            ids.add(row.path("id").asLong());
            if (row.path("id").asLong() == a || row.path("id").asLong() == b) {
                Set<String> keys = new HashSet<>(); row.fieldNames().forEachRemaining(keys::add);
                assertEquals(Set.of("id", "patientNo", "patientName", "deptName"), keys);
                assertFalse(row.path("patientName").asText().isBlank());
                assertFalse(row.path("patientNo").asText().isBlank());
                assertFalse(row.path("deptName").asText().isBlank());
            }
        }
        assertTrue(ids.containsAll(Set.of(a, b))); assertFalse(ids.contains(other));
    }
    @Test void pharmacySelectorDoesNotOpenRegistrationDesk() throws Exception {
        String who = employee(true);
        assertEquals(1005, request("GET", "/api/outpatient/registrations?date=" + BusinessDates.today(), who, "PHARMACIST").path("code").asInt());
        assertEquals(1005, request("POST", "/api/outpatient/registrations", who, "PHARMACIST").path("code").asInt());
    }
}
