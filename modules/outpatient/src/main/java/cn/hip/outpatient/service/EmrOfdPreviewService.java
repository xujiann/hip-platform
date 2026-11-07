package cn.hip.outpatient.service;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.integration.ofd.OfdPreviewWriter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only export of an explicit stored inpatient version, with no signing or archival side effects. */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmrOfdPreviewService {
    private static final int MAX_SOURCE_CHARS = 500_000;
    private static final Set<String> ROLES = Set.of("ROLE_ADMIN", "ROLE_QUALITY", "ROLE_DOCTOR_OUTP");
    private static final List<String> FIELDS = List.of("title", "content", "roundLevel", "roundOpinion", "superiorCorrection");
    private static final List<String> LABELS = List.of("文书标题", "病历正文", "查房级别", "查房意见", "上级修正意见");
    private static final ObjectMapper SOURCE_PARSER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OfdPreviewWriter writer;

    private static final String SCOPE = """
            with preview_actor as (
                select u.id as actor_id, u.real_name as actor_name,
                       coalesce(u.enabled, false) and exists (
                           select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id
                           where ur.user_id=u.id and r.code in ('ADMIN','QUALITY','DOCTOR_OUTP')
                       ) as actor_allowed,
                       exists (select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id
                               where ur.user_id=u.id and r.code in ('ADMIN','QUALITY')) as all_records,
                       exists (select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id
                               where ur.user_id=u.id and r.code='DOCTOR_OUTP') as is_doctor
                from (values (cast(? as text))) requested(username)
                left join sys_user u on u.username=requested.username
            ), preview_source as (
                select r.id as record_id, r.admission_id, a.patient_id, a.doctor_id,
                       a.admission_no, p.patient_no, p.name as patient_name
                from inp_medical_record r join inp_admission a on a.id=r.admission_id
                join empi_patient p on p.id=a.patient_id where r.id=?
            ), preview_scope as (
                select actor.*, source.*,
                       (actor.all_records or (actor.is_doctor and source.doctor_id=actor.actor_id)) as source_allowed
                from preview_actor actor left join preview_source source on true
            )
            """;

    private static final String SNAPSHOT = SCOPE + """
            select s.*, v.id as version_id, v.version_no, v.source as source_action,
                   v.content as source_content, v.content_len, v.content_hash,
                   v.saved_by, saver.real_name as saved_by_name, v.saved_at,
                   clock_timestamp() as captured_at
            from preview_scope s left join emr_version v
              on v.emr_type='INP' and v.emr_id=s.record_id and v.version_no=?
                 and s.actor_allowed and s.source_allowed
            left join sys_user saver on saver.id=v.saved_by
            """;

    public byte[] preview(Long emrId, Integer versionNo) {
        if (emrId == null || emrId <= 0) throw new HipBizException(5701, "病历 id 非法");
        if (versionNo == null || versionNo <= 0) throw new HipBizException(5706, "版本号非法");
        String username = username();
        var source = jdbc.queryForMap(SNAPSHOT, username, emrId, versionNo);
        requireScope(source);
        if (source.get("version_id") == null) throw new HipBizException(5703, "指定病历版本不存在");
        String raw = (String) source.get("source_content");
        JsonNode fields = checkedFields(source, raw);
        String documentId = UUID.randomUUID().toString().replace("-", "");
        String title = text(fields.get("title"), "住院历史病历版本预览");
        byte[] document;
        try {
            byte[] metadata = objectMapper.writeValueAsBytes(metadata(source, fields, raw, documentId, emrId));
            document = writer.write(documentId, title, displayText(source, fields, documentId), metadata);
        } catch (IOException error) {
            log.warn("OFD preview generation failed for INP#{} v{}", emrId, versionNo, error);
            throw new HipBizException(5709, "OFD 预览生成失败，请检查字符及字体支持");
        }

        // Rendering can take time. Recheck the current employee and the originally selected clinical links.
        var current = jdbc.queryForMap(SCOPE + "select * from preview_scope", username, emrId);
        requireScope(current);
        if (!source.get("actor_id").equals(current.get("actor_id"))) {
            throw new AccessDeniedException("导出期间登录员工已变化");
        }
        if (!source.get("admission_id").equals(current.get("admission_id"))
                || !source.get("patient_id").equals(current.get("patient_id"))) {
            throw new HipBizException(4036, "病历归属已变化，请重新选择版本");
        }
        return document;
    }

    private String username() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth.getAuthorities().stream().noneMatch(role -> ROLES.contains(role.getAuthority()))) {
            throw new AccessDeniedException("无病历版本导出岗位权限");
        }
        return auth.getName();
    }

    private void requireScope(Map<String, Object> row) {
        if (!Boolean.TRUE.equals(row.get("actor_allowed"))) {
            throw new AccessDeniedException("导出员工不存在、已停用或岗位已变化");
        }
        if (row.get("record_id") == null && Boolean.TRUE.equals(row.get("all_records"))) {
            throw new HipBizException(5707, "病历主记录不存在");
        }
        if (!Boolean.TRUE.equals(row.get("source_allowed"))) {
            throw new HipBizException(4036, "无权导出该病历版本（非本人主管的住院病历）");
        }
    }

    private JsonNode checkedFields(Map<String, Object> source, String raw) {
        if (raw == null) throw invalidSource();
        if (raw.length() > MAX_SOURCE_CHARS) throw new HipBizException(5710, "单次 OFD 预览限 500000 字符，请拆分文书");
        if (!(source.get("content_len") instanceof Number length) || length.intValue() != raw.length()
                || !(source.get("content_hash") instanceof String hash)
                || !EmrVersionService.sha256Hex(raw).equalsIgnoreCase(hash)) {
            throw invalidSource();
        }
        try {
            JsonNode fields = SOURCE_PARSER.readTree(raw);
            if (fields == null || !fields.isObject() || !fields.path("content").isTextual()) throw invalidSource();
            var names = fields.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode value = fields.get(name);
                if (!FIELDS.contains(name) || !(value.isNull() || value.isTextual())) throw invalidSource();
            }
            return fields;
        } catch (JsonProcessingException error) {
            throw invalidSource();
        }
    }

    private HipBizException invalidSource() {
        return new HipBizException(5708, "源版本完整性或结构校验失败，预览未生成");
    }

    private Map<String, Object> metadata(Map<String, Object> row, JsonNode fields, String raw,
                                         String documentId, Long emrId) {
        String generatedAt = instant(row, "captured_at");
        var source = new LinkedHashMap<String, Object>();
        source.put("emrType", "INP");
        source.put("emrId", emrId);
        source.put("versionId", row.get("version_id"));
        source.put("versionNo", row.get("version_no"));
        source.put("sourceAction", row.get("source_action"));
        source.put("savedBy", row.get("saved_by"));
        source.put("savedAt", instant(row, "saved_at"));
        source.put("contentHash", row.get("content_hash"));
        source.put("contentLen", row.get("content_len"));
        source.put("snapshotRaw", raw);
        source.put("snapshotFields", fields);
        var context = new LinkedHashMap<String, Object>();
        context.put("patientId", row.get("patient_id"));
        context.put("patientNo", row.get("patient_no"));
        context.put("patientName", row.get("patient_name"));
        context.put("admissionId", row.get("admission_id"));
        context.put("admissionNo", row.get("admission_no"));
        context.put("savedByCurrentName", row.get("saved_by_name"));
        context.put("capturedAt", generatedAt);
        context.put("description", "患者与姓名信息来自生成时的当前主索引，历史正文来自所选版本");
        var creator = new LinkedHashMap<String, Object>();
        creator.put("id", row.get("actor_id"));
        creator.put("currentName", row.get("actor_name"));
        return Map.of("header", Map.of("schemaVersion", "1.0", "generationTime", generatedAt, "modifiedTime", generatedAt),
                "document", Map.of("id", documentId, "type", "OFD-H", "status", "UNSIGNED_PREVIEW", "confidentiality", "医疗敏感",
                        "keywords", List.of("住院病历", "历史版本", text(fields.get("title"), "未记录标题"))),
                "source", source, "currentContext", context, "creator", creator,
                "pendingChecks", List.of("正式 OFD 签章", "独立验签与可信时间戳", "第三方标准符合性验证"));
    }

    private String displayText(Map<String, Object> row, JsonNode fields, String documentId) {
        var text = new StringBuilder("未签署预览，正式归档需完成签章与验签\n");
        text.append("文档标识：").append(documentId).append('\n');
        text.append("保密属性：医疗敏感\n");
        text.append("患者（当前主索引）：").append(row.get("patient_name")).append(" / ").append(row.get("patient_no")).append('\n');
        text.append("住院号：").append(row.get("admission_no")).append('\n');
        text.append("历史版本：v").append(row.get("version_no")).append("，版本标识：").append(row.get("version_id")).append('\n');
        text.append("版本保存人标识：").append(value(row.get("saved_by"))).append('\n');
        text.append("保存人现名：").append(value(row.get("saved_by_name"))).append('\n');
        text.append("原版本保存时间：").append(instant(row, "saved_at")).append('\n');
        text.append("生成及主索引采集时间：").append(instant(row, "captured_at")).append('\n');
        text.append("源版本 SHA-256：").append(row.get("content_hash")).append("\n\n");
        for (int index = 0; index < FIELDS.size(); index++) {
            JsonNode field = fields.get(FIELDS.get(index));
            if (field == null) continue;
            text.append(LABELS.get(index)).append('\n');
            text.append(text(field, "（未填写）")).append("\n\n");
        }
        return text.toString();
    }

    private String text(JsonNode node, String missing) {
        return node == null || node.isNull() ? missing : node.asText();
    }

    private String value(Object value) {
        return value == null ? "未记录" : value.toString();
    }

    private String instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Timestamp timestamp ? timestamp.toInstant().toString() : null;
    }
}
