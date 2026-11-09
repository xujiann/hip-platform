package cn.hip.outpatient.service;

import cn.hip.platform.core.common.HipBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Append-only retention of unsigned generated files; never changes clinical or archival state. */
@Service
@RequiredArgsConstructor
public class EmrOfdDocumentService {
    private static final int MAX_DOCUMENT_BYTES = 32 * 1024 * 1024;
    private static final int PAGE_SIZE = 50;
    private static final Pattern DOCUMENT_ID = Pattern.compile("[0-9a-f]{32}");
    private static final String STATUS = "UNSIGNED_PREVIEW";

    private final JdbcTemplate jdbc;
    private final EmrOfdPreviewService previews;
    private final PlatformTransactionManager transactionManager;

    // No source body is selected for retained-file reads. Employee, source and artifact share one snapshot.
    private static final String DOCUMENT_SCOPE = EmrOfdPreviewService.SCOPE + """
            , document_scope as (
                select s.*, v.id as version_id, v.version_no,
                       v.content_hash as current_source_hash
                from preview_scope s left join emr_version v
                  on v.emr_type='INP' and v.emr_id=s.record_id and v.version_no=?
                     and s.actor_allowed and s.source_allowed
            )
            """;

    private static final String METADATA_COLUMNS = """
            d.document_id, d.generated_at, d.generated_by, d.status, d.byte_length, d.sha256
            """;

    private static final String MATCH_ORIGINAL = """
            d.source_version_id=s.version_id and d.source_hash=lower(s.current_source_hash)
            and d.admission_id=s.admission_id and d.patient_id=s.patient_id
            """;

    public Map<String, Object> create(Long emrId, Integer versionNo) {
        // Suspending a caller transaction also keeps rendering outside transactions for internal callers.
        var rendering = new TransactionTemplate(transactionManager);
        rendering.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var generated = Objects.requireNonNull(rendering.execute(status -> previews.generate(emrId, versionNo)));
        byte[] bytes = generated.bytes();
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new HipBizException(5713, "生成文件超过 32 MiB，未留存");
        }
        String sha256 = sha256(bytes);
        Map<String, Object> source = generated.source();
        var saving = new TransactionTemplate(transactionManager);
        saving.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // A fresh post-INSERT statement must observe changes committed during a foreign-key wait.
        saving.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return saving.execute(status -> {
            jdbc.update("""
                    insert into emr_ofd_document
                      (document_id, emr_id, version_no, source_version_id, source_hash,
                       admission_id, patient_id, generated_by, generated_at, status,
                       byte_length, sha256, payload)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, generated.documentId(), emrId, versionNo, source.get("version_id"),
                    source.get("content_hash").toString().toLowerCase(java.util.Locale.ROOT),
                    source.get("admission_id"), source.get("patient_id"), source.get("actor_id"),
                    source.get("captured_at"), STATUS, bytes.length, sha256, bytes);

            var current = jdbc.queryForMap(DOCUMENT_SCOPE + "select * from document_scope",
                    generated.username(), emrId, versionNo);
            previews.requireOriginalScope(source, current);
            if (!Objects.equals(source.get("version_id"), current.get("version_id"))
                    || !(current.get("current_source_hash") instanceof String currentHash)
                    || !source.get("content_hash").toString().equalsIgnoreCase(currentHash)) {
                throw new HipBizException(5708, "源版本已变化，生成件未留存，请重新选择版本");
            }
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("documentId", generated.documentId());
            metadata.put("generatedAt", ((Timestamp) source.get("captured_at")).toInstant().toString());
            metadata.put("generatedBy", source.get("actor_id"));
            metadata.put("status", STATUS);
            metadata.put("byteLength", bytes.length);
            metadata.put("sha256", sha256);
            return metadata;
        });
    }

    public record DocumentPage(List<Map<String, Object>> items, String nextCursor) {}

    public DocumentPage list(Long emrId, Integer versionNo, String before) {
        previews.requireTarget(emrId, versionNo);
        String username = previews.username();
        if (before != null && !DOCUMENT_ID.matcher(before).matches()) {
            throw new IllegalArgumentException("生成件翻页游标格式错误");
        }
        // Resolve the cursor inside the same authorized source snapshot used for the page.
        String boundary = before == null ? "" : "and (d.generated_at, d.document_id) < (s.before_at, s.before_id)\n";
        var rows = jdbc.queryForList(DOCUMENT_SCOPE + """
                , page_scope as (
                    select s.*, d.generated_at as before_at, d.document_id as before_id
                    from document_scope s left join emr_ofd_document d
                      on d.document_id=? and s.actor_allowed and s.source_allowed
                         and d.emr_id=s.record_id and d.version_no=s.version_no and
                """ + MATCH_ORIGINAL + """
                )
                select s.*,
                """ + METADATA_COLUMNS + """
                from page_scope s left join lateral (
                    select d.document_id, d.generated_at, d.generated_by, d.status, d.byte_length, d.sha256
                    from emr_ofd_document d
                    where s.actor_allowed and s.source_allowed
                      and d.emr_id=s.record_id and d.version_no=s.version_no and
                """ + MATCH_ORIGINAL + boundary + """
                    order by d.generated_at desc, d.document_id desc limit ?
                ) d on true
                order by d.generated_at desc, d.document_id desc
                """, username, emrId, versionNo, before, PAGE_SIZE + 1);
        // LEFT JOIN preserves a status row for empty sets, so invalid employees cannot get an empty success.
        previews.requireVersion(rows.get(0));
        recheckRead(rows.get(0), username, emrId, versionNo);
        if (before != null && rows.get(0).get("before_id") == null) throw missingDocument();
        var documents = new ArrayList<Map<String, Object>>();
        for (var row : rows.subList(0, Math.min(rows.size(), PAGE_SIZE))) {
            if (row.get("document_id") != null) documents.add(metadata(row));
        }
        String nextCursor = rows.size() > PAGE_SIZE ? (String) rows.get(PAGE_SIZE - 1).get("document_id") : null;
        return new DocumentPage(documents, nextCursor);
    }

    public byte[] content(Long emrId, Integer versionNo, String documentId) {
        previews.requireTarget(emrId, versionNo);
        String username = previews.username();
        var row = jdbc.queryForMap(DOCUMENT_SCOPE + "select s.*, " + METADATA_COLUMNS + """
                , d.source_version_id, d.source_hash,
                  d.admission_id as document_admission_id, d.patient_id as document_patient_id,
                  case when
                """ + MATCH_ORIGINAL + """
                  then d.payload end as payload
                from document_scope s left join emr_ofd_document d
                  on s.actor_allowed and s.source_allowed and d.emr_id=s.record_id
                     and d.version_no=s.version_no and d.document_id=?
                """, username, emrId, versionNo, documentId);
        previews.requireVersion(row);
        if (row.get("document_id") == null) throw missingDocument();
        if (!Objects.equals(row.get("admission_id"), row.get("document_admission_id"))
                || !Objects.equals(row.get("patient_id"), row.get("document_patient_id"))) {
            throw new HipBizException(4036, "生成件原患者或住院关联已变化，禁止取回");
        }
        if (!Objects.equals(row.get("version_id"), row.get("source_version_id"))
                || !(row.get("current_source_hash") instanceof String currentHash)
                || !currentHash.equalsIgnoreCase((String) row.get("source_hash"))) {
            throw missingDocument();
        }
        byte[] bytes = row.get("payload") instanceof byte[] payload ? payload : null;
        boolean intact = bytes != null && row.get("byte_length") instanceof Number length
                && bytes.length == length.intValue() && row.get("sha256") instanceof String hash
                && sha256(bytes).equals(hash);
        // Fetching a large bytea and hashing it may take time; reject changes committed before this SQL.
        recheckRead(row, username, emrId, versionNo);
        if (!intact) {
            throw new HipBizException(5712, "留存文件完整性校验失败，禁止下载");
        }
        return bytes;
    }

    private void recheckRead(Map<String, Object> original, String username, Long emrId, Integer versionNo) {
        var current = jdbc.queryForMap(DOCUMENT_SCOPE + "select * from document_scope",
                username, emrId, versionNo);
        previews.requireOriginalScope(original, current);
        if (!Objects.equals(original.get("version_id"), current.get("version_id"))
                || !(current.get("current_source_hash") instanceof String currentHash)
                || !currentHash.equalsIgnoreCase((String) original.get("current_source_hash"))) {
            throw missingDocument();
        }
    }

    private HipBizException missingDocument() {
        return new HipBizException(5711, "生成件不存在或与所选病历版本不匹配");
    }

    private Map<String, Object> metadata(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("documentId", row.get("document_id"));
        result.put("generatedAt", ((Timestamp) row.get("generated_at")).toInstant().toString());
        result.put("generatedBy", row.get("generated_by"));
        result.put("status", row.get("status"));
        result.put("byteLength", row.get("byte_length"));
        result.put("sha256", row.get("sha256"));
        return result;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 缺少 SHA-256", error);
        }
    }
}
