package cn.hip.cdr.service;

import cn.hip.cdr.entity.CdrDocument;
import cn.hip.cdr.repository.CdrDocumentRepository;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** CDR reads use live source encounter ownership, never patient-level or snapshot-level permission. */
@Service
@RequiredArgsConstructor
public class CdrReadService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final CdrSyncService syncService;
    private final CdrDocumentRepository docRepository;

    // Matches the encounter ownership policy of clinical references/version reads.
    // EXISTS also checks source patient identity; missing/unknown/legacy sources fail closed.
    private static final String SOURCE_ACCESS = """
            ((d.doc_type = 'OUTP_ENCOUNTER' and exists (
                select 1 from outp_registration r
                where r.id = d.ref_id and r.patient_id = d.patient_id
                  and (r.doctor_id is null or r.doctor_id = :doctorId)))
             or (d.doc_type = 'LAB_REPORT' and exists (
                select 1 from outp_order o join outp_registration r on r.id = o.registration_id
                where o.id = d.ref_id and o.order_type = 'LAB' and r.patient_id = d.patient_id
                  and (r.doctor_id is null or r.doctor_id = :doctorId)))
             or (d.doc_type = 'INP_SUMMARY' and exists (
                select 1 from inp_admission a
                where a.id = d.ref_id and a.patient_id = d.patient_id
                  and (a.doctor_id is null or a.doctor_id = :doctorId))))
            """;

    public List<Map<String, Object>> patientDocuments(Long patientId, String docType, Authentication auth) {
        if (crossDomain(auth)) return syncService.patientDocuments(patientId, docType);
        Long doctorId = doctorId(auth);
        if (doctorId == null) return List.of();
        var params = new MapSqlParameterSource("doctorId", doctorId)
                .addValue("patientId", patientId).addValue("docType", docType, java.sql.Types.VARCHAR);
        return new NamedParameterJdbcTemplate(jdbc).queryForList("""
                select d.id, d.patient_id as "patientId", d.doc_type as "docType", d.ref_id as "refId",
                       d.title, d.doc_time as "docTime", d.synced_at as "syncedAt"
                from cdr_document d where
                """ + SOURCE_ACCESS + """
                  and d.patient_id = :patientId and (:docType is null or d.doc_type = :docType)
                order by d.doc_time desc limit 200
                """, params);
    }

    public List<CdrDocument> search(String keyword, Authentication auth) {
        if (crossDomain(auth)) return docRepository.search(keyword, org.springframework.data.domain.PageRequest.of(0, 50));
        Long doctorId = doctorId(auth);
        if (doctorId == null) return List.of();
        return new NamedParameterJdbcTemplate(jdbc).query(
                "select d.* from cdr_document d where " + SOURCE_ACCESS + """
                  and (d.title like :pattern or d.content like :pattern)
                order by d.doc_time desc limit 50
                """, Map.of("doctorId", doctorId, "pattern", "%" + keyword + "%"), CdrReadService::mapDocument);
    }

    /** Returns only authorized content; existence is distinguished by the controller without reading its body. */
    public CdrDocument document(Long id, Authentication auth) {
        if (crossDomain(auth)) return syncService.document(id);
        Long doctorId = doctorId(auth);
        if (doctorId == null) return null;
        var rows = new NamedParameterJdbcTemplate(jdbc).query(
                "select d.* from cdr_document d where d.id = :id and " + SOURCE_ACCESS,
                Map.of("id", id, "doctorId", doctorId), CdrReadService::mapDocument);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long doctorId(Authentication auth) {
        return auth == null ? null : currentUserService.idOf(auth);
    }

    private static boolean crossDomain(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_QUALITY".equals(a.getAuthority()));
    }

    private static CdrDocument mapDocument(ResultSet rs, int rowNum) throws SQLException {
        var doc = new CdrDocument();
        doc.setId(rs.getLong("id"));
        doc.setPatientId(rs.getLong("patient_id"));
        doc.setDocType(rs.getString("doc_type"));
        doc.setRefId(rs.getLong("ref_id"));
        doc.setTitle(rs.getString("title"));
        doc.setDocTime(rs.getTimestamp("doc_time").toInstant());
        doc.setContent(rs.getString("content"));
        doc.setSyncedAt(rs.getTimestamp("synced_at").toInstant());
        return doc;
    }
}
