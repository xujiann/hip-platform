package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.integration.ofd.OfdPreviewWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real Tomcat HTTP (including /error dispatch), real signed JWTs, committed synthetic records.
 * Only writer failure/revocation boundaries are controlled; normal success uses the real writer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "hip.audit.anon-throttle-ms=0"})
@Import(V167EmrOfdStrictAcceptHttpTest.WriterConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class V167EmrOfdStrictAcceptHttpTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired ControlledWriter writer;
    @LocalServerPort int port;
    static HttpClient client;
    record Staff(long id, String name, String token) {}
    final List<Staff> staff = new ArrayList<>();
    String tag, patientName, raw, currentBody;
    Long dept, patient, bed, admission, record;
    Staff owner, other;

    @BeforeAll static void httpClient() {
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterAll static void closeHttpClient() { client.close(); }

    @BeforeEach void fixture() throws Exception {
        writer.reset();
        tag = "V167" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag, tag);
        owner = employee("owner");
        other = employee("other");
        patientName = tag + "模拟患者";
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",
                Long.class, tag + "P", patientName);
        bed = jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id", Long.class,
                dept, tag.substring(Math.max(0, tag.length() - 16)));
        admission = jdbc.queryForObject("""
                insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status)
                values (?,?,?,?,?,?,'IN_HOSPITAL') returning id
                """, Long.class, tag, patient, dept, dept, bed, owner.id());
        currentBody = tag + "当前乙正文不可替代历史甲";
        record = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id,record_type,title,content,doctor_id)
                values (?,'PROGRESS',?,?,?) returning id
                """, Long.class, admission, tag + "当前乙标题", currentBody, owner.id());
        raw = mapper.writeValueAsString(Map.of("title", tag + "历史甲标题", "content", tag + "历史甲正文龘"));
        jdbc.update("""
                insert into emr_version(emr_type,emr_id,version_no,source,content,content_len,content_hash,saved_by,saved_at,saved_on)
                values ('INP',?,1,'MANUAL',?,?,?,?,clock_timestamp(),current_date)
                """, record, raw, raw.length(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8))), owner.id());
    }

    Staff employee(String suffix) {
        String name = tag + suffix;
        long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id",
                Long.class, name, name, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='DOCTOR_OUTP'", id);
        long stamp = jdbc.queryForObject("select password_updated_at from sys_user where id=?",
                (rs, n) -> rs.getTimestamp(1).toInstant().getEpochSecond(), id);
        Staff value = new Staff(id, name, jwt.issue(name, stamp));
        staff.add(value);
        return value;
    }

    String preview(int version) { return "/api/emr/versions/INP/" + record + "/versions/" + version + "/ofd-preview"; }

    HttpResponse<byte[]> get(String path, Staff actor, String accept) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60)).header("Accept", accept).GET();
        if (actor != null) request.header("Authorization", "Bearer " + actor.token());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    void denied(HttpResponse<byte[]> response, int status, int code) throws Exception {
        String text = new String(response.body(), StandardCharsets.UTF_8);
        JsonNode body = mapper.readTree(response.body());
        System.out.println("V167 realHTTP expected=" + status + "/" + code + " actual="
                + response.statusCode() + "/" + body.path("code").asInt());
        assertEquals(status, response.statusCode(), text);
        assertEquals(code, body.path("code").asInt(), text);
        assertEquals(Set.of("code", "message", "data"), mapper.convertValue(body, Map.class).keySet());
        assertTrue(body.path("data").isNull());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(response.headers().firstValue("Content-Disposition").isEmpty());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        for (String secret : List.of(patientName, currentBody, raw, tag + "历史甲正文", "V167_PRIVATE_FAILURE")) {
            assertFalse(text.contains(secret), "Error response leaked clinical or internal failure text");
        }
    }

    void stillAuthenticated(Staff actor) throws Exception {
        var response = get("/api/auth/me", actor, "application/json");
        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(0, body.path("code").asInt());
        assertEquals(actor.id(), body.path("data").path("id").asLong());
    }

    @Test void strictOfdNonOwnerKeeps4036AndTheSameToken() throws Exception {
        var before = facts();
        denied(get(preview(1), other, "application/ofd"), 200, 4036);
        stillAuthenticated(other);
        assertEquals(0, writer.calls.get());
        assertEquals(before, facts());
    }

    @Test void strictOfdMissingVersionKeeps5703() throws Exception {
        var before = facts();
        denied(get(preview(999), owner, "application/ofd"), 200, 5703);
        stillAuthenticated(owner);
        assertEquals(0, writer.calls.get());
        assertEquals(before, facts());
    }

    @Test void strictOfdGenerationFailureKeeps5709() throws Exception {
        writer.next.set((id, title, body, metadata) -> { throw new IOException("V167_PRIVATE_FAILURE " + patientName); });
        var before = facts();
        denied(get(preview(1), owner, "application/ofd"), 200, 5709);
        stillAuthenticated(owner);
        assertEquals(1, writer.calls.get());
        assertEquals(before, facts());
    }

    @Test void strictOfdUnknownFailureIs500WithoutInternalDetails() throws Exception {
        writer.next.set((id, title, body, metadata) -> { throw new IllegalStateException("V167_PRIVATE_FAILURE " + patientName); });
        var before = facts();
        denied(get(preview(1), owner, "application/ofd"), 500, 5000);
        stillAuthenticated(owner);
        assertEquals(1, writer.calls.get());
        assertEquals(before, facts());
    }

    @Test void strictOfdBadPathParameterKeeps4000() throws Exception {
        var before = facts();
        denied(get("/api/emr/versions/INP/not-a-long/versions/1/ofd-preview", owner, "application/ofd"), 200, 4000);
        assertEquals(0, writer.calls.get());
        assertEquals(before, facts());
    }

    @Test void strictOfdAnonymousControlStays401() throws Exception {
        var before = facts();
        denied(get(preview(1), null, "application/ofd"), 401, 1000);
        assertEquals(0, writer.calls.get());
        assertEquals(before, facts());
    }

    void removeDoctorRole() {
        jdbc.update("delete from sys_user_role where user_id=?", owner.id());
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='NURSE'", owner.id());
    }

    @Test void strictOfdLostRoleStays403InsteadOf401() throws Exception {
        removeDoctorRole();
        var before = facts();
        denied(get(preview(1), owner, "application/ofd"), 403, 1005);
        stillAuthenticated(owner);
        assertEquals(0, writer.calls.get());
        assertEquals(before, facts());
    }

    @Test void strictOfdRoleLostDuringGenerationDoesNotSendPreparedBytes() throws Exception {
        AtomicReference<Map<String, List<String>>> afterRevocation = new AtomicReference<>();
        writer.next.set((id, title, body, metadata) -> {
            removeDoctorRole(); // JdbcTemplate auto-commit: final source SQL must see the committed loss.
            afterRevocation.set(facts());
            return new byte[]{'P', 'K', 3, 4};
        });
        denied(get(preview(1), owner, "application/ofd"), 403, 1005);
        assertEquals(1, writer.calls.get());
        assertNotNull(afterRevocation.get());
        assertEquals(afterRevocation.get(), facts());
        stillAuthenticated(owner);
    }

    @Test void strictOfdSuccessIsStillTheRealHistoricalArchive() throws Exception {
        var before = facts();
        var response = get(preview(1), owner, "application/ofd");
        assertEquals(200, response.statusCode());
        assertEquals("application/ofd", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals("attachment; filename=\"emr-INP-" + record + "-v1-preview.ofd\"",
                response.headers().firstValue("Content-Disposition").orElse(""));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.body()), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertNull(entries.put(entry.getName(), zip.readAllBytes())); // Reading to EOF validates each CRC.
            }
        }
        assertTrue(entries.containsKey("OFD.xml"));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".otf")));
        var metadata = mapper.readTree(entries.get("Doc_0/Attachs/Attach_0.json"));
        assertEquals(raw, metadata.path("source").path("snapshotRaw").asText());
        assertEquals(1, metadata.path("source").path("versionNo").asInt());
        assertEquals("UNSIGNED_PREVIEW", metadata.path("document").path("status").asText());
        assertFalse(metadata.toString().contains(currentBody));
        assertEquals(1, writer.calls.get());
        assertEquals(before, facts());
    }

    @Test void ordinaryJsonVersionRoutesKeepTheirOriginalContract() throws Exception {
        var before = facts();
        var detail = get("/api/emr/versions/INP/" + record + "/versions/1", owner, "application/json");
        assertEquals(200, detail.statusCode());
        JsonNode body = mapper.readTree(detail.body());
        assertEquals(0, body.path("code").asInt());
        assertEquals(raw, body.path("data").path("content").asText());
        assertTrue(detail.headers().firstValue("Content-Disposition").isEmpty());
        denied(get("/api/emr/versions/INP/" + record + "/versions/999", owner, "application/json"), 200, 5703);
        assertEquals(0, writer.calls.get());
        assertEquals(before, facts());
    }

    Map<String, List<String>> facts() {
        Map<String, List<String>> value = new LinkedHashMap<>();
        for (String table : List.of("empi_patient", "inp_admission", "inp_medical_record", "emr_version", "sys_user")) {
            value.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by id", String.class));
        }
        value.put("roles", jdbc.queryForList("select to_jsonb(t)::text from sys_user_role t order by user_id,role_id", String.class));
        return value;
    }

    @AfterEach void cleanup() {
        writer.reset();
        if (record != null) {
            jdbc.update("delete from sys_audit_log where path like ?", "/api/emr/versions/INP/" + record + "%");
            jdbc.update("delete from emr_version where emr_type='INP' and emr_id=?", record);
            jdbc.update("delete from inp_medical_record where id=?", record);
        }
        if (admission != null) jdbc.update("delete from inp_admission where id=?", admission);
        if (bed != null) jdbc.update("delete from inp_bed where id=?", bed);
        if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
        for (var actor : staff) {
            jdbc.update("delete from sys_audit_log where username=?", actor.name());
            jdbc.update("delete from sys_user_role where user_id=?", actor.id());
            jdbc.update("delete from sys_user where id=?", actor.id());
        }
        if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
    }

    @FunctionalInterface interface Generation { byte[] write(String id, String title, String body, byte[] metadata) throws IOException; }
    static class ControlledWriter extends OfdPreviewWriter {
        final AtomicReference<Generation> next = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        void reset() { next.set(null); calls.set(0); }
        @Override public byte[] write(String id, String title, String text, byte[] metadata) throws IOException {
            calls.incrementAndGet();
            var action = next.getAndSet(null);
            return action == null ? super.write(id, title, text, metadata) : action.write(id, title, text, metadata);
        }
    }
    @TestConfiguration(proxyBeanMethods = false) static class WriterConfiguration {
        @Bean @Primary ControlledWriter v167ControlledWriter() { return new ControlledWriter(); }
    }
}
