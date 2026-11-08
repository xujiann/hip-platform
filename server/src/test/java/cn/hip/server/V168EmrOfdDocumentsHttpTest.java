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

/** Retained OFD behavior through real Tomcat HTTP, signed JWTs and committed PostgreSQL fixtures.
 * Only writer failure/revocation boundaries are controlled; normal success uses the real writer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "hip.audit.anon-throttle-ms=0"})
@Import(V168EmrOfdDocumentsHttpTest.WriterConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class V168EmrOfdDocumentsHttpTest {
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
        tag = "V168" + Long.toUnsignedString(System.nanoTime(), 36);
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
        System.out.println("V168 realHTTP expected=" + status + "/" + code + " actual="
                + response.statusCode() + "/" + body.path("code").asInt());
        assertEquals(status, response.statusCode(), text);
        assertEquals(code, body.path("code").asInt(), text);
        assertEquals(Set.of("code", "message", "data"), mapper.convertValue(body, Map.class).keySet());
        assertTrue(body.path("data").isNull());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(response.headers().firstValue("Content-Disposition").isEmpty());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        for (String secret : List.of(patientName, currentBody, raw, tag + "历史甲正文", "V168_PRIVATE_FAILURE")) {
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

    String documents(int version) { return "/api/emr/versions/INP/" + record + "/versions/" + version + "/ofd-documents"; }
    String content(int version, String id) { return documents(version) + "/" + id + "/content"; }

    HttpResponse<byte[]> post(String path, Staff actor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(90)).header("Accept", "application/json")
                .header("Authorization", "Bearer " + actor.token()).POST(HttpRequest.BodyPublishers.noBody());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    JsonNode success(HttpResponse<byte[]> response) throws Exception {
        String text = new String(response.body(), StandardCharsets.UTF_8);
        assertEquals(200, response.statusCode(), text);
        JsonNode result = mapper.readTree(response.body());
        assertEquals(0, result.path("code").asInt(), text);
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return result.path("data");
    }

    JsonNode create(Staff actor) throws Exception { return success(post(documents(1), actor)); }

    @Test void repeatedGenerationAppendsDistinctDocumentsAndDownloadReturnsOriginalBytes() throws Exception {
        var before = facts();
        JsonNode first = create(owner);
        JsonNode second = create(owner);
        assertNotEquals(first.path("documentId").asText(), second.path("documentId").asText());
        assertEquals(2, writer.calls.get());
        JsonNode items = success(get(documents(1), owner, "application/json")).path("items");
        assertEquals(2, items.size());
        assertEquals(second, items.get(0));
        assertEquals(first, items.get(1));
        for (JsonNode document : items) {
            assertEquals(Set.of("documentId", "generatedAt", "generatedBy", "status", "byteLength", "sha256"),
                    mapper.convertValue(document, Map.class).keySet());
            String id = document.path("documentId").asText();
            assertTrue(id.matches("[0-9a-f]{32}"));
            assertEquals(owner.id(), document.path("generatedBy").asLong());
            assertEquals("UNSIGNED_PREVIEW", document.path("status").asText());
            java.time.Instant.parse(document.path("generatedAt").asText());
            byte[] stored = jdbc.queryForObject("select payload from emr_ofd_document where document_id=?", byte[].class, id);
            var response = get(content(1, id), owner, "application/ofd");
            assertEquals(200, response.statusCode());
            assertEquals("application/ofd", response.headers().firstValue("Content-Type").orElse(""));
            assertTrue(response.headers().firstValue("Content-Disposition").orElse("").contains(id));
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            assertArrayEquals(stored, response.body());
            assertEquals(document.path("byteLength").asInt(), response.body().length);
            assertEquals(document.path("sha256").asText(), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(response.body())));
            try (var zip = new ZipInputStream(new ByteArrayInputStream(response.body()), StandardCharsets.UTF_8)) {
                boolean linked = false;
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    byte[] payload = zip.readAllBytes();
                    if (entry.getName().equals("Doc_0/Attachs/Attach_0.json")) {
                        var metadata = mapper.readTree(payload);
                        assertEquals(raw, metadata.path("source").path("snapshotRaw").asText());
                        assertEquals(id, metadata.path("document").path("id").asText());
                        assertEquals("UNSIGNED_PREVIEW", metadata.path("document").path("status").asText());
                        linked = true;
                    }
                }
                assertTrue(linked);
            }
        }
        assertEquals(2, writer.calls.get(), "List and content must not call the writer again");
        assertEquals(before, facts(), "Retention must preserve clinical, version and employee rows");
    }

    @Test void unknownDocumentReturnsJsonForStrictOfdAcceptWithoutLeakingSource() throws Exception {
        denied(get(content(1, "0".repeat(32)), owner, "application/ofd"), 200, 5711);
        assertEquals(0, writer.calls.get());
    }

    @Test void nonAttendingCannotGenerateListOrFetchEvenWhenDocumentIdIsKnown() throws Exception {
        String id = create(owner).path("documentId").asText();
        denied(post(documents(1), other), 200, 4036);
        denied(get(documents(1), other, "application/json"), 200, 4036);
        denied(get(content(1, id), other, "application/ofd"), 200, 4036);
        stillAuthenticated(other);
        assertEquals(1, writer.calls.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from emr_ofd_document where emr_id=?", Integer.class, record));
    }

    @Test void lostRealRoleDeniesAllThreeRoutesAndPreservesAuthentication() throws Exception {
        String id = create(owner).path("documentId").asText();
        jdbc.update("delete from sys_user_role where user_id=?", owner.id());
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='NURSE'", owner.id());
        denied(post(documents(1), owner), 403, 1005);
        denied(get(documents(1), owner, "application/json"), 403, 1005);
        denied(get(content(1, id), owner, "application/ofd"), 403, 1005);
        stillAuthenticated(owner);
        assertEquals(1, writer.calls.get());
    }

    @Test void writerFailureRollsBackRetentionAndKeeps5709() throws Exception {
        writer.next.set((id, title, body, metadata) -> { throw new IOException("V168_PRIVATE_FAILURE " + patientName); });
        denied(post(documents(1), owner), 200, 5709);
        assertEquals(0, jdbc.queryForObject("select count(*) from emr_ofd_document where emr_id=?", Integer.class, record));
        assertEquals(1, writer.calls.get());
    }

    @Test void committedDisableWhileTheActualDocumentInsertWaitsRollsBackThePreparedFile() throws Exception {
        writer.next.set((id, title, body, metadata) -> new byte[]{'P', 'K', 3, 4});
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
             var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            java.util.concurrent.Future<HttpResponse<byte[]>> pending;
            try {
                int holderPid;
                try (var lock = holder.prepareStatement("select pg_backend_pid() from sys_user where id=? for update")) {
                    lock.setLong(1, owner.id());
                    lock.setQueryTimeout(10);
                    try (var row = lock.executeQuery()) {
                        assertTrue(row.next());
                        holderPid = row.getInt(1);
                    }
                }
                pending = worker.submit(() -> post(documents(1), owner));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                Map<String, Object> observed = null;
                while (System.nanoTime() < deadline) {
                    if (pending.isDone()) fail("Document request completed without FK contention: "
                            + new String(pending.get().body(), StandardCharsets.UTF_8));
                    var waits = jdbc.queryForList("""
                            select pid,wait_event,query from pg_stat_activity
                            where datname=current_database() and wait_event_type='Lock'
                              and ?=any(pg_blocking_pids(pid))
                            """, holderPid);
                    if (!waits.isEmpty()) {
                        assertEquals(1, waits.size());
                        observed = waits.getFirst();
                        assertTrue(observed.get("query").toString().stripLeading()
                                .toLowerCase(Locale.ROOT).startsWith("insert into emr_ofd_document"),
                                observed.get("query").toString());
                        break;
                    }
                    Thread.sleep(25);
                }
                assertNotNull(observed, "pg_stat_activity must prove the document INSERT waits on the held FK parent");
                assertEquals(1, writer.calls.get(), "Generation must already have completed before the FK wait");
                assertEquals(0, jdbc.queryForObject("select count(*) from emr_ofd_document where emr_id=?",
                        Integer.class, record), "Uncommitted generated files must be invisible to other sessions");
                try (var disable = holder.prepareStatement("update sys_user set enabled=false where id=?")) {
                    disable.setLong(1, owner.id());
                    assertEquals(1, disable.executeUpdate());
                }
                assertFalse(pending.isDone(), "The held parent must still block the original INSERT");
                System.out.printf("V168 FK_WAIT holder=%d waiter=%s event=%s revoke=DISABLED%n",
                        holderPid, observed.get("pid"), observed.get("wait_event"));
                holder.commit(); // A real commit releases the INSERT and makes employee loss visible.
            } finally {
                holder.rollback();
            }
            denied(pending.get(20, java.util.concurrent.TimeUnit.SECONDS), 403, 1005);
        }
        assertEquals(false, jdbc.queryForObject("select enabled from sys_user where id=?", Boolean.class, owner.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from emr_ofd_document where emr_id=?", Integer.class, record),
                "The post-INSERT employee check must roll back the retained document");
        assertEquals(1, writer.calls.get());
    }

    JsonNode createSmall(Staff actor) throws Exception {
        writer.next.set((id, title, body, metadata) -> new byte[]{80, 75, 3, 4, 7, 8});
        return create(actor);
    }

    @Test void retainedBytesDoNotChangeWhenCurrentPatientNameAndCurrentClinicalTextChange() throws Exception {
        String id = createSmall(owner).path("documentId").asText();
        byte[] before = get(content(1, id), owner, "application/ofd").body();
        jdbc.update("update empi_patient set name=? where id=?", tag + "更名", patient);
        jdbc.update("update inp_medical_record set content=? where id=?", tag + "新正文", record);
        assertArrayEquals(before, get(content(1, id), owner, "application/ofd").body());
        assertEquals(1, writer.calls.get());
    }

    @Test void changedOriginalPatientIsFilteredFromListAndCannotBeDownloadedEvenByCurrentOwner() throws Exception {
        String id = createSmall(owner).path("documentId").asText();
        long replacement = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",
                Long.class, tag + "P2", tag + "另一患者");
        try {
            jdbc.update("update inp_admission set patient_id=? where id=?", replacement, admission);
            assertTrue(success(get(documents(1), owner, "application/json")).path("items").isEmpty());
            denied(get(content(1, id), owner, "application/ofd"), 200, 4036);
            assertEquals(1, writer.calls.get());
        } finally {
            jdbc.update("update inp_admission set patient_id=? where id=?", patient, admission);
            jdbc.update("delete from empi_patient where id=?", replacement);
        }
    }

    @Test void nullAttendingDoesNotGiveAnyDoctorAccessButBothBroadRolesCanUseAllRoutes() throws Exception {
        jdbc.update("update inp_admission set doctor_id=null where id=?", admission);
        denied(post(documents(1), owner), 200, 4036);
        denied(get(documents(1), owner, "application/json"), 200, 4036);
        for (String role : List.of("ADMIN", "QUALITY")) {
            jdbc.update("delete from sys_user_role where user_id=?", other.id());
            jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", other.id(), role);
            String id = createSmall(other).path("documentId").asText();
            assertFalse(success(get(documents(1), other, "application/json")).path("items").isEmpty());
            assertEquals(200, get(content(1, id), other, "application/ofd").statusCode());
            denied(get(content(1, id), owner, "application/ofd"), 200, 4036);
        }
    }

    @Test void oversizedAndRevokedGenerationLeavesNoRetainedRow() throws Exception {
        writer.next.set((id, title, body, metadata) -> new byte[32 * 1024 * 1024 + 1]);
        denied(post(documents(1), owner), 200, 5713);
        writer.next.set((id, title, body, metadata) -> {
            jdbc.update("update inp_admission set doctor_id=? where id=?", other.id(), admission);
            return new byte[]{80, 75, 3, 4};
        });
        denied(post(documents(1), owner), 200, 4036);
        assertEquals(0, jdbc.queryForObject("select count(*) from emr_ofd_document where emr_id=?", Integer.class, record));
    }

    @Test void immutableGuardRejectsOrdinaryUpdateAndDeleteAndDamagedBytesAreNotRegenerated() throws Exception {
        String id = createSmall(owner).path("documentId").asText();
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("update emr_ofd_document set sha256=sha256 where document_id=?", id));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("delete from emr_ofd_document where document_id=?", id));
        fixtureMutation("update emr_ofd_document set payload=set_byte(payload,0,81) where document_id=?", id);
        denied(get(content(1, id), owner, "application/ofd"), 200, 5712);
        assertEquals(1, writer.calls.get());
    }

    @Test void newestFiftyMetadataRowsAreBoundedWithoutReadingOrRenderingPayload() throws Exception {
        String id = createSmall(owner).path("documentId").asText();
        jdbc.update("""
                insert into emr_ofd_document(document_id,emr_id,version_no,source_version_id,source_hash,
                  admission_id,patient_id,generated_by,generated_at,status,byte_length,sha256,payload)
                select md5(? || g::text),emr_id,version_no,source_version_id,source_hash,
                  admission_id,patient_id,generated_by,generated_at + g * interval '1 second',status,byte_length,sha256,payload
                from emr_ofd_document cross join generate_series(1,51) g where document_id=?
                """, tag, id);
        var rows = success(get(documents(1), owner, "application/json")).path("items");
        assertEquals(50, rows.size());
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(java.time.Instant.parse(rows.get(i - 1).path("generatedAt").asText())
                    .isAfter(java.time.Instant.parse(rows.get(i).path("generatedAt").asText())));
        }
        assertEquals(1, writer.calls.get());
    }

    Map<String, List<String>> facts() {
        Map<String, List<String>> value = new LinkedHashMap<>();
        for (String table : List.of("empi_patient", "inp_admission", "inp_medical_record", "emr_version", "sys_user")) {
            value.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by id", String.class));
        }
        value.put("roles", jdbc.queryForList("select to_jsonb(t)::text from sys_user_role t order by user_id,role_id", String.class));
        return value;
    }

    @Autowired javax.sql.DataSource dataSource;

    void fixtureMutation(String sql, Object... values) {
        // The table lock and trigger change live in the same transaction; other sessions never
        // observe a globally disabled immutable guard. Only this test record is touched.
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var guard = connection.createStatement()) {
                guard.execute("alter table emr_ofd_document disable trigger user");
                try (var statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                    statement.executeUpdate();
                } finally {
                    guard.execute("alter table emr_ofd_document enable trigger user");
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        } catch (Exception error) {
            throw new AssertionError("Isolated retained-document fixture mutation failed", error);
        }
    }

    @AfterEach void cleanup() {
        writer.reset();
        if (record != null) {
            fixtureMutation("delete from emr_ofd_document where emr_id=?", record);
            jdbc.update("delete from sys_audit_log where path like ?", "/api/emr/versions/INP/" + record + "/%");
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
        @Bean @Primary ControlledWriter v168ControlledWriter() { return new ControlledWriter(); }
    }
}

