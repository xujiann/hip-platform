package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.integration.ofd.OfdPreviewWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Synthetic committed fixtures, actual JWT route, original version bytes and read-only database effects. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(V166EmrOfdPreviewTest.WriterConfiguration.class)
class V166EmrOfdPreviewTest {
    private static final Instant SAVED_AT = Instant.parse("2026-11-01T01:02:03Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired ControlledWriter writer;

    private record Staff(long id, String username, String token) {}
    private final List<Staff> staff = new ArrayList<>();
    private String tag, raw, currentBody, currentTitle, patientName, patientNo;
    private Long dept, patient, bed, admission, record, version;
    private Long alternatePatient, alternateBed, alternateAdmission, alternateRecord;
    private Staff owner, other, administrator, quality;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        writer.reset();
        tag = "V166" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag, tag);
        owner = employee("owner", "DOCTOR_OUTP");
        other = employee("other", "DOCTOR_OUTP");
        administrator = employee("admin", "ADMIN");
        quality = employee("quality", "QUALITY");
        patientName = tag + "模拟患者";
        patientNo = tag + "P";
        patient = jdbc.queryForObject(
                "insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",
                Long.class, patientNo, patientName);
        bed = jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id",
                Long.class, dept, tag);
        admission = jdbc.queryForObject("""
                insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status)
                values (?,?,?,?,?,?,'IN_HOSPITAL') returning id
                """, Long.class, tag, patient, dept, dept, bed, owner.id());
        currentBody = tag + "当前正文禁止替代历史内容";
        currentTitle = tag + "当前标题";
        record = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id,record_type,title,content,doctor_id)
                values (?,'PROGRESS',?,?,?) returning id
                """, Long.class, admission, currentTitle, currentBody, owner.id());
        var fields = new LinkedHashMap<String, String>();
        fields.put("title", tag + "历史标题");
        fields.put("content", tag + "历史正文\n第二行 <医嘱> & 观察");
        fields.put("roundLevel", null);
        fields.put("roundOpinion", "原始查房意见");
        fields.put("superiorCorrection", null);
        // Preserve formatting too. Export may not canonicalize or replace this stored snapshot.
        raw = " \n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fields) + "\n ";
        version = jdbc.queryForObject("""
                insert into emr_version(emr_type,emr_id,version_no,source,content,content_len,content_hash,
                                        saved_by,saved_at,saved_on)
                values ('INP',?,1,'MANUAL',?,?,?,?,?,date '2026-11-01') returning id
                """, Long.class, record, raw, raw.length(), sha256(raw),
                owner.id(), Timestamp.from(SAVED_AT));
        String later = mapper.writeValueAsString(Map.of("content", tag + "另一历史版本正文"));
        assertEquals(1, jdbc.update("""
                insert into emr_version(emr_type,emr_id,version_no,source,content,content_len,content_hash,
                                        saved_by,saved_at,saved_on)
                values ('INP',?,2,'SUBMIT',?,?,?,?,?,date '2026-11-01')
                """, record, later, later.length(), sha256(later), other.id(),
                Timestamp.from(SAVED_AT.plusSeconds(60))));
    }

    private Staff employee(String suffix, String role) {
        String username = tag + suffix;
        long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, username, username, dept);
        assertEquals(1, jdbc.update("""
                insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?
                """, id, role));
        var result = new Staff(id, username, jwt.issue(username));
        staff.add(result);
        return result;
    }

    private String path(long recordId, int versionNo) {
        return "/api/emr/versions/INP/" + recordId + "/versions/" + versionNo + "/ofd-preview";
    }

    private String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private MvcResult call(Staff actor) throws Exception {
        return call(get(path(record, 1)), actor, false);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, Staff actor, boolean staleAuthentication)
            throws Exception {
        SecurityContextHolder.clearContext();
        try {
            if (staleAuthentication) {
                var stale = new UsernamePasswordAuthenticationToken(actor.username(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                request.with(authentication(stale));
            } else {
                request.header("Authorization", "Bearer " + actor.token());
            }
            return mvc.perform(request).andReturn();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void denied(MvcResult result, int status, int code) throws Exception {
        var response = result.getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
        assertNull(response.getHeader("Content-Disposition"), "An error must not be named as an OFD download");
        assertFalse(String.valueOf(response.getContentType()).contains("application/ofd"));
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        var keys = new java.util.HashSet<String>();
        body.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("code", "message", "data"), keys);
        assertEquals(code, body.path("code").asInt(), body.toString());
        assertTrue(body.path("data").isNull(), body.toString());
        String errorText = response.getContentAsString(StandardCharsets.UTF_8);
        for (String secret : List.of(patientName, patientNo, currentBody, tag + "历史正文")) {
            assertFalse(errorText.contains(secret), "An error response exposed clinical identity or body text");
        }
    }

    private Map<String, byte[]> archive(MvcResult result) throws Exception {
        return archive(result, record);
    }

    private Map<String, byte[]> archive(MvcResult result, long requestedRecord) throws Exception {
        var response = result.getResponse();
        assertEquals(200, response.getStatus(), () -> "Expected an OFD response, status=" + response.getStatus());
        assertEquals("application/ofd", response.getContentType());
        assertEquals("attachment; filename=\"emr-INP-" + requestedRecord + "-v1-preview.ofd\"",
                response.getHeader("Content-Disposition"));
        assertTrue(String.valueOf(response.getHeader("Cache-Control")).contains("no-store"));
        byte[] bytes = response.getContentAsByteArray();
        assertTrue(bytes.length > 4);
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
        var files = new LinkedHashMap<String, byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                assertFalse(entry.getName().startsWith("/"));
                assertFalse(entry.getName().contains(".."));
                assertNull(files.put(entry.getName(), input.readAllBytes()), "Duplicate ZIP entry");
            }
        }
        assertTrue(files.containsKey("OFD.xml"));
        assertTrue(files.keySet().stream().anyMatch(name -> name.endsWith(".ttf") || name.endsWith(".otf")),
                "A generated Chinese preview must carry its font resource");
        for (var entry : files.entrySet()) {
            if (entry.getKey().endsWith(".xml") || entry.getKey().endsWith(".json")) {
                String text = new String(entry.getValue(), StandardCharsets.UTF_8);
                assertFalse(text.contains(currentBody), "The current clinical body leaked into a historical preview");
                assertFalse(text.contains(currentTitle), "The current title replaced the selected historical title");
            }
        }
        return files;
    }

    private JsonNode metadata(Map<String, byte[]> files) throws Exception {
        var matches = new ArrayList<JsonNode>();
        for (var entry : files.entrySet()) {
            if (!entry.getKey().endsWith(".json")) continue;
            JsonNode value = mapper.readTree(entry.getValue());
            if (value.has("header") && value.has("source")) matches.add(value);
        }
        assertEquals(1, matches.size(), "Exactly one source-linked metadata document is required");
        return matches.getFirst();
    }

    @Test
    void realArchiveUsesTheExplicitHistoricalSnapshotAndLabelsCurrentContext() throws Exception {
        var before = facts();
        JsonNode metadata = metadata(archive(call(owner)));
        assertEquals("UNSIGNED_PREVIEW", metadata.path("document").path("status").asText());
        assertEquals("医疗敏感", metadata.path("document").path("confidentiality").asText());
        assertEquals(mapper.valueToTree(List.of("住院病历", "历史版本", mapper.readTree(raw).path("title").asText())),
                metadata.path("document").path("keywords"));
        assertFalse(metadata.path("document").path("id").asText().isBlank());
        assertFalse(metadata.path("header").path("schemaVersion").asText().isBlank());
        assertFalse(metadata.path("header").path("generationTime").asText().isBlank());
        assertFalse(metadata.path("header").path("modifiedTime").asText().isBlank());
        JsonNode source = metadata.path("source");
        assertEquals("INP", source.path("emrType").asText());
        assertEquals(record.longValue(), source.path("emrId").asLong());
        assertEquals(version.longValue(), source.path("versionId").asLong());
        assertEquals(1, source.path("versionNo").asInt());
        assertEquals(owner.id(), source.path("savedBy").asLong());
        assertEquals(SAVED_AT, Instant.parse(source.path("savedAt").asText()));
        assertEquals("MANUAL", source.path("sourceAction").asText());
        assertEquals(raw, source.path("snapshotRaw").asText());
        assertEquals(mapper.readTree(raw), source.path("snapshotFields"));
        assertEquals(raw.length(), source.path("contentLen").asInt());
        assertEquals(sha256(raw), source.path("contentHash").asText());
        JsonNode context = metadata.path("currentContext");
        assertEquals(patient.longValue(), context.path("patientId").asLong());
        assertEquals(patientNo, context.path("patientNo").asText());
        assertEquals(patientName, context.path("patientName").asText());
        assertEquals(admission.longValue(), context.path("admissionId").asLong());
        assertEquals(tag, context.path("admissionNo").asText());
        assertFalse(context.path("capturedAt").asText().isBlank());
        assertEquals(owner.id(), metadata.path("creator").path("id").asLong());
        assertTrue(metadata.path("pendingChecks").isArray());
        assertFalse(metadata.path("pendingChecks").isEmpty());
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "QUALITY"})
    void currentPrivilegedEmployeesCanReadTheSameSelectedVersion(String role) throws Exception {
        var before = facts();
        var actor = role.equals("ADMIN") ? administrator : quality;
        assertEquals(raw, metadata(archive(call(actor))).path("source").path("snapshotRaw").asText());
        assertEquals(before, facts());
    }

    @Test
    void historicMissingSaverIsPreservedInsteadOfAttributingItToTheExporter() throws Exception {
        jdbc.update("update emr_version set saved_by=null where id=?", version);
        var before = facts();
        JsonNode meta = metadata(archive(call(administrator)));
        assertTrue(meta.path("source").path("savedBy").isNull());
        assertEquals(administrator.id(), meta.path("creator").path("id").asLong());
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO", "SUBMIT"})
    void everyHistoricalSaveActionStillProducesAnUnsignedPreview(String action) throws Exception {
        jdbc.update("update emr_version set source=? where id=?", action, version);
        var before = facts();
        var files = archive(call(owner));
        JsonNode meta = metadata(files);
        assertEquals(action, meta.path("source").path("sourceAction").asText());
        assertEquals("UNSIGNED_PREVIEW", meta.path("document").path("status").asText());
        assertTrue(files.keySet().stream().noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("signature")));
        assertEquals(before, facts());
    }

    @Test
    void legacyContentOnlySnapshotDoesNotSubstituteTheCurrentTitle() throws Exception {
        String legacy = mapper.writeValueAsString(Map.of("content", tag + "历史正文无标题"));
        replaceSnapshot(legacy, sha256(legacy), legacy.length());
        var before = facts();
        JsonNode meta = metadata(archive(call(owner)));
        assertEquals(legacy, meta.path("source").path("snapshotRaw").asText());
        assertEquals(mapper.readTree(legacy), meta.path("source").path("snapshotFields"));
        assertEquals(before, facts());
    }

    @Test
    void anEmptyHistoricalClinicalBodyStaysEmptyInsteadOfUsingCurrentContent() throws Exception {
        String empty = "{\"content\":\"\"}";
        replaceSnapshot(empty, sha256(empty), empty.length());
        var before = facts();
        JsonNode meta = metadata(archive(call(owner)));
        assertEquals(empty, meta.path("source").path("snapshotRaw").asText());
        assertEquals("", meta.path("source").path("snapshotFields").path("content").asText());
        assertEquals(before, facts());
    }

    @Test
    void anonymousRequestCannotReceiveAnArchive() throws Exception {
        var before = facts();
        denied(mvc.perform(get(path(record, 1))).andReturn(), 401, 1000);
        assertEquals(before, facts());
    }

    @Test
    void theInpatientPreviewEndpointDoesNotExportAnOutpatientTarget() throws Exception {
        var before = facts();
        var result = call(get("/api/emr/versions/OUTP/" + record + "/versions/1/ofd-preview"), owner, false);
        assertFalse(String.valueOf(result.getResponse().getContentType()).contains("application/ofd"));
        assertNull(result.getResponse().getHeader("Content-Disposition"));
        if (result.getResponse().getStatus() == 200) {
            assertNotEquals(0, mapper.readTree(result.getResponse().getContentAsByteArray()).path("code").asInt());
        }
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NURSE", "TECHNICIAN", "CASHIER"})
    void unrelatedDutiesCannotDownloadClinicalDocuments(String role) throws Exception {
        var actor = employee("denied", role);
        var before = facts();
        denied(call(actor), 403, 1005);
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "roleLost", "missingEmployee"})
    void aStaleAuthenticatedAdminCannotOverrideTheCurrentEmployeeGate(String change) throws Exception {
        if (change.equals("disabled")) jdbc.update("update sys_user set enabled=false where id=?", owner.id());
        if (change.equals("roleLost")) {
            jdbc.update("delete from sys_user_role where user_id=?", owner.id());
            jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='CASHIER'",
                    owner.id());
        }
        Staff requested = owner;
        if (change.equals("missingEmployee")) requested = new Staff(-1, tag + "missing", "unused");
        var before = facts();
        denied(call(get(path(record, 1)), requested, true), 403, 1005);
        assertEquals(before, facts());
    }

    @Test
    void currentAdmissionOwnerControlsReadingEvenWhenTheVersionWasSavedByThisDoctor() throws Exception {
        jdbc.update("update inp_admission set doctor_id=? where id=?", other.id(), admission);
        var before = facts();
        denied(call(owner), 200, 4036);
        assertEquals(before, facts());
    }

    @Test
    void nullCurrentAttendingDoesNotGrantDoctorAccess() throws Exception {
        jdbc.update("update inp_admission set doctor_id=null where id=?", admission);
        var before = facts();
        denied(call(owner), 200, 4036);
        assertEquals(before, facts());
    }

    @Test
    void unauthorizedDoctorCannotProbeMissingVersionOrBadStoredHash() throws Exception {
        jdbc.update("update emr_version set content_hash=? where id=?", "0".repeat(64), version);
        var before = facts();
        denied(call(other), 200, 4036);
        denied(call(get(path(record, 999)), other, false), 200, 4036);
        assertEquals(before, facts());
    }

    @Test
    void aCurrentRecordIsRequiredToEstablishTheDocumentScope() throws Exception {
        jdbc.update("delete from inp_medical_record where id=?", record);
        var before = facts();
        denied(call(administrator), 200, 5707);
        denied(call(owner), 200, 4036);
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidRecordIdKeepsTheExistingErrorCode(long invalid) throws Exception {
        var before = facts();
        denied(call(get(path(invalid, 1)), administrator, false), 200, 5701);
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidVersionNumberKeepsTheExistingErrorCode(int invalid) throws Exception {
        var before = facts();
        denied(call(get(path(record, invalid)), administrator, false), 200, 5706);
        assertEquals(before, facts());
    }

    @Test
    void anAuthorizedExplicitMissingVersionIsNotSynthesizedFromTheCurrentBody() throws Exception {
        var before = facts();
        denied(call(get(path(record, 999)), owner, false), 200, 5703);
        assertEquals(before, facts());
    }

    @Test
    void anOutpatientVersionWithTheSameNumericKeyCannotReplaceTheRequestedInpatientVersion() throws Exception {
        jdbc.update("delete from emr_version where id=?", version);
        String decoy = mapper.writeValueAsString(Map.of("content", tag + "门诊版本不可串档"));
        Long decoyId = jdbc.queryForObject("""
                insert into emr_version(emr_type,emr_id,version_no,source,content,content_len,content_hash,
                                        saved_by,saved_at,saved_on)
                values ('OUTP',?,1,'MANUAL',?,?,?,?,?,date '2026-11-01') returning id
                """, Long.class, record, decoy, decoy.length(), sha256(decoy), owner.id(), Timestamp.from(SAVED_AT));
        try {
            var before = facts();
            denied(call(owner), 200, 5703);
            assertEquals(before, facts());
        } finally {
            jdbc.update("delete from emr_version where id=?", decoyId);
        }
    }

    @Test
    void equalVersionNumbersFromDifferentRecordsStaySeparate() throws Exception {
        alternateRecord = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id,record_type,title,content,doctor_id)
                values (?,'PROGRESS',?,?,?) returning id
                """, Long.class, admission, currentTitle, currentBody, owner.id());
        String selected = mapper.writeValueAsString(Map.of("content", tag + "另一病历的指定版本"));
        jdbc.update("""
                insert into emr_version(emr_type,emr_id,version_no,source,content,content_len,content_hash,
                                        saved_by,saved_at,saved_on)
                values ('INP',?,1,'MANUAL',?,?,?,?,?,date '2026-11-01')
                """, alternateRecord, selected, selected.length(), sha256(selected), owner.id(), Timestamp.from(SAVED_AT));
        var before = facts();
        var result = call(get(path(alternateRecord, 1)), owner, false);
        JsonNode source = metadata(archive(result, alternateRecord)).path("source");
        assertEquals(alternateRecord.longValue(), source.path("emrId").asLong());
        assertEquals(selected, source.path("snapshotRaw").asText());
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{\"content\":3}", "{\"title\":\"x\"}",
            "{\"content\":null}", "{\"content\":\"x\",\"unknownClinicalField\":\"y\"}",
            "{\"content\":\"x\",\"roundOpinion\":false}", "{\"content\":\"x\"} {\"content\":\"y\"}"})
    void internallyConsistentButInvalidStoredSnapshotsAreRefused(String invalid) throws Exception {
        replaceSnapshot(invalid, sha256(invalid), invalid.length());
        var before = facts();
        denied(call(owner), 200, 5708);
        assertEquals(before, facts());
    }

    @Test
    void aHashMismatchIsNotExportedOrRepaired() throws Exception {
        replaceSnapshot(raw, "0".repeat(64), raw.length());
        var before = facts();
        denied(call(owner), 200, 5708);
        assertEquals(before, facts());
    }

    @Test
    void aCharacterLengthMismatchIsNotExportedOrRepaired() throws Exception {
        replaceSnapshot(raw, sha256(raw), raw.length() + 1);
        var before = facts();
        denied(call(owner), 200, 5708);
        assertEquals(before, facts());
    }

    @Test
    void aNullStoredBodyIsAnIntegrityError() throws Exception {
        replaceSnapshot(null, sha256(""), 0);
        var before = facts();
        denied(call(owner), 200, 5708);
        assertEquals(before, facts());
    }

    @Test
    void aMissingStoredHashIsAnIntegrityError() throws Exception {
        replaceSnapshot(raw, null, raw.length());
        var before = facts();
        denied(call(owner), 200, 5708);
        assertEquals(before, facts());
    }

    @Test
    void anOversizedSnapshotIsRefusedBeforeCreatingAFile() throws Exception {
        String oversized = mapper.writeValueAsString(Map.of("content", "测".repeat(500_001)));
        replaceSnapshot(oversized, sha256(oversized), oversized.length());
        var before = facts();
        denied(call(owner), 200, 5710);
        assertEquals(before, facts());
    }

    @Test
    void generationFailureReturnsAnErrorEnvelopeAndLeavesTheSourceUntouched() throws Exception {
        var before = facts();
        writer.next.set((id, title, text, metadata) -> {
            throw new IOException("V166 controlled OFD generation failure");
        });
        denied(call(owner), 200, 5709);
        assertEquals(1, writer.calls.get(), "The failure must come from the real writer boundary");
        assertEquals(before, facts());
    }

    @Test
    void unsupportedSupplementaryTextFailsExplicitlyWithoutChangingTheRawSnapshot() throws Exception {
        String rareName = new String(Character.toChars(0x20BB7));
        String unsupported = mapper.writeValueAsString(Map.of("content", tag + "历史正文" + rareName));
        replaceSnapshot(unsupported, sha256(unsupported), unsupported.length());
        var before = facts();
        denied(call(owner), 200, 5709);
        assertEquals(1, writer.calls.get());
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "roleLost", "attendingChanged", "patientChanged", "admissionChanged"})
    void committedRevocationDuringGenerationPreventsThePreparedBytesFromLeaving(String change) throws Exception {
        if (change.equals("patientChanged")) {
            alternatePatient = jdbc.queryForObject("insert into empi_patient(name,sex) values (?,'U') returning id",
                    Long.class, tag + "另一模拟患者");
        }
        if (change.equals("admissionChanged")) {
            alternateBed = jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id",
                    Long.class, dept, tag + "2");
            alternateAdmission = jdbc.queryForObject("""
                    insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status)
                    values (?,?,?,?,?,?,'DISCHARGED') returning id
                    """, Long.class, tag + "2", patient, dept, dept, alternateBed, owner.id());
        }
        var before = facts();
        var afterChange = new AtomicReference<Map<String, List<String>>>();
        writer.next.set((id, title, text, metadataBytes) -> {
            assertEquals(before, facts(), "Preparing the document may not write clinical or version rows");
            try {
                assertEquals(raw, mapper.readTree(metadataBytes).path("source").path("snapshotRaw").asText());
                try (var worker = Executors.newSingleThreadExecutor()) {
                    var changed = worker.submit(() -> { committedAccessChange(change); return null; });
                    changed.get(15, TimeUnit.SECONDS);
                }
                afterChange.set(facts());
            } catch (Exception error) {
                throw new IOException("Controlled public change failed", error);
            }
            return new byte[]{'P', 'K', 3, 4};
        });
        boolean employeeFailure = Set.of("disabled", "roleLost").contains(change);
        denied(call(owner), employeeFailure ? 403 : 200, employeeFailure ? 1005 : 4036);
        assertEquals(1, writer.calls.get());
        assertNotNull(afterChange.get(), "The public mutation must commit after source loading");
        assertEquals(afterChange.get(), facts(), "Only the explicit public mutation may commit");
    }

    private void committedAccessChange(String change) throws Exception {
        // Clinical links are not edited by a production route. These committed fixture changes
        // verify that the prepared document remains bound to its original patient/admission.
        if (change.equals("patientChanged")) {
            assertEquals(1, jdbc.update("update inp_admission set patient_id=? where id=?", alternatePatient, admission));
            return;
        }
        if (change.equals("admissionChanged")) {
            assertEquals(1, jdbc.update("update inp_medical_record set admission_id=? where id=?", alternateAdmission, record));
            return;
        }
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request;
        Object body = null;
        if (change.equals("attendingChanged")) {
            request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/inpatient/admissions/" + admission + "/attending-doctor");
            body = Map.of("doctorId", other.id());
        } else if (change.equals("disabled")) {
            request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/system/users/" + owner.id() + "/enabled").queryParam("enabled", "false");
        } else {
            request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/system/users/" + owner.id());
            body = Map.of("username", owner.username(), "realName", owner.username(),
                    "deptId", dept, "roleCodes", List.of("CASHIER"));
        }
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsBytes(body));
        var response = call(request, administrator, false).getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(0, mapper.readTree(response.getContentAsByteArray()).path("code").asInt());
        if (change.equals("attendingChanged")) {
            assertEquals(other.id(), jdbc.queryForObject("select doctor_id from inp_admission where id=?",
                    Long.class, admission).longValue());
        } else if (change.equals("disabled")) {
            assertEquals(false, jdbc.queryForObject("select enabled from sys_user where id=?", Boolean.class, owner.id()));
        } else {
            assertEquals(List.of("CASHIER"), jdbc.queryForList("""
                    select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=?
                    """, String.class, owner.id()));
        }
    }

    @FunctionalInterface
    interface Generation {
        byte[] create(String id, String title, String text, byte[] metadata) throws IOException;
    }

    static class ControlledWriter extends OfdPreviewWriter {
        final AtomicReference<Generation> next = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        void reset() {
            next.set(null);
            calls.set(0);
        }

        @Override
        public byte[] write(String id, String title, String text, byte[] metadata) throws IOException {
            calls.incrementAndGet();
            var controlled = next.getAndSet(null);
            return controlled == null ? super.write(id, title, text, metadata)
                    : controlled.create(id, title, text, metadata);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WriterConfiguration {
        @Bean @Primary
        ControlledWriter controlledOfdPreviewWriter() { return new ControlledWriter(); }
    }

    private void replaceSnapshot(String body, String hash, int length) {
        assertEquals(1, jdbc.update("update emr_version set content=?,content_hash=?,content_len=? where id=?",
                body, hash, length, version));
    }

    private Map<String, List<String>> facts() {
        var facts = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("empi_patient", "inp_admission", "inp_medical_record", "emr_version",
                "emr_template", "emr_template_grant", "sys_user")) {
            facts.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by id", String.class));
        }
        facts.put("roles", jdbc.queryForList("select to_jsonb(t)::text from sys_user_role t order by user_id,role_id",
                String.class));
        facts.put("config", jdbc.queryForList("select to_jsonb(t)::text from sys_config t order by cfg_key", String.class));
        return facts;
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        writer.reset();
        if (record != null) {
            jdbc.update("delete from sys_audit_log where path like ?",
                    "/api/emr/versions/INP/" + record + "/versions/%/ofd-preview");
            jdbc.update("delete from emr_version where emr_type='INP' and emr_id=?", record);
            jdbc.update("delete from inp_medical_record where id=?", record);
        }
        if (alternateRecord != null) {
            jdbc.update("delete from emr_version where emr_type='INP' and emr_id=?", alternateRecord);
            jdbc.update("delete from inp_medical_record where id=?", alternateRecord);
        }
        if (admission != null) jdbc.update("delete from inp_admission where id=?", admission);
        if (alternateAdmission != null) jdbc.update("delete from inp_admission where id=?", alternateAdmission);
        if (bed != null) jdbc.update("delete from inp_bed where id=?", bed);
        if (alternateBed != null) jdbc.update("delete from inp_bed where id=?", alternateBed);
        if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
        if (alternatePatient != null) jdbc.update("delete from empi_patient where id=?", alternatePatient);
        for (var actor : staff) {
            jdbc.update("delete from sys_audit_log where username=?", actor.username());
            jdbc.update("delete from sys_user_role where user_id=?", actor.id());
            jdbc.update("delete from sys_user where id=?", actor.id());
        }
        if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
    }
}
