package cn.hip.cdr.service;

import cn.hip.cdr.entity.CdrDocument;
import cn.hip.cdr.repository.CdrDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/**
 * 存量切换：老 HIS 历史文书导入 CDR（操作指南 §六.4，1.1.5）。
 *
 * <p>幂等：以「类别 + 老系统单号」为键——cdr_legacy_ref 把老单号映射成平台内稳定 ref_id，
 * 重复导入（断点续跑、修正后重导）命中同一行做覆盖更新，不产生重复文书。
 * 患者定位优先证件号（跨系统最稳），其次平台患者号。
 */
@Service
@RequiredArgsConstructor
public class CdrLegacyService {

    /** 类别 → doc_type；LEGACY_ 前缀与平台自产文书（OUTP_ENCOUNTER 等）严格区分 */
    private static final Set<String> CATEGORIES = Set.of("OUTP", "INP", "REPORT", "OTHER");

    private final CdrDocumentRepository docRepository;
    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public record LegacyDoc(String idNo, String patientNo, String category, String legacyKey,
                            String title, String docDate, String content) {}

    /** 域内保留类型（现有 catch 不动），实质语义在基类（1.1.9） */
    public static class LegacyException extends cn.hip.platform.core.common.HipBizException {
        public LegacyException(int code, String message) {
            super(code, message);
        }
    }

    @Transactional
    public Map<String, Object> importDocument(LegacyDoc req) {
        // 上限 = 列宽 128 - 最长前缀 "LEGACY_REPORT:"(14)：校验放行而落库炸约束会让批量导入
        // 中断在无法定位的 4091 上（1.1.6 B-15）
        if (req.legacyKey() == null || req.legacyKey().isBlank() || req.legacyKey().length() > 114) {
            throw new LegacyException(4681, "老系统单号必填且不超过 114 字符（幂等键）");
        }
        if (req.category() == null || !CATEGORIES.contains(req.category())) {
            throw new LegacyException(4682, "类别须为 OUTP/INP/REPORT/OTHER 之一");
        }
        if (req.title() == null || req.title().isBlank()
                || req.content() == null || req.content().isBlank()) {
            throw new LegacyException(4683, "标题与正文不能为空");
        }
        Long patientId = resolvePatient(req.idNo(), req.patientNo());
        Instant docTime = parseDate(req.docDate());
        String docType = "LEGACY_" + req.category();

        // 老单号 → 稳定 ref_id（并发/重跑都拿到同一个号）
        jdbc.update("insert into cdr_legacy_ref(legacy_key) values (?) on conflict (legacy_key) do nothing",
                docType + ":" + req.legacyKey());
        Long refId = jdbc.queryForObject("select id from cdr_legacy_ref where legacy_key = ?",
                Long.class, docType + ":" + req.legacyKey());

        boolean existed = docRepository.findByDocTypeAndRefId(docType, refId).isPresent();
        CdrDocument doc = docRepository.findByDocTypeAndRefId(docType, refId).orElseGet(CdrDocument::new);
        doc.setPatientId(patientId);
        doc.setDocType(docType);
        doc.setRefId(refId);
        doc.setTitle(req.title().length() > 128 ? req.title().substring(0, 128) : req.title());
        doc.setDocTime(docTime);
        try {
            doc.setContent(objectMapper.writeValueAsString(Map.of(
                    "来源", "老系统迁移",
                    "老单号", req.legacyKey(),
                    "正文", req.content())));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LegacyException(4684, "正文序列化失败：" + e.getOriginalMessage());
        }
        doc.setSyncedAt(Instant.now());
        docRepository.save(doc);
        return Map.of("docType", docType, "refId", refId, "updated", existed);
    }

    private Long resolvePatient(String idNo, String patientNo) {
        if (idNo != null && !idNo.isBlank()) {
            var rows = jdbc.queryForList("select id from empi_patient where id_no = ?", Long.class, idNo.trim());
            if (rows.size() == 1) return rows.get(0);
            if (rows.size() > 1) throw new LegacyException(4685, "证件号命中多名患者，请改用平台患者号定位：" + idNo);
        }
        if (patientNo != null && !patientNo.isBlank()) {
            var rows = jdbc.queryForList("select id from empi_patient where patient_no = ?",
                    Long.class, patientNo.trim());
            if (!rows.isEmpty()) return rows.get(0);
        }
        throw new LegacyException(4686, "患者不存在（证件号/患者号均未命中）——请先经 import-patients.py 建档");
    }

    private Instant parseDate(String docDate) {
        if (docDate == null || docDate.isBlank()) {
            throw new LegacyException(4687, "文书日期必填（YYYY-MM-DD）");
        }
        try {
            return LocalDate.parse(docDate.trim())
                    .atStartOfDay(ZoneId.of(cn.hip.platform.core.config.HipProfiles.ZONE)).toInstant();
        } catch (DateTimeParseException e) {
            throw new LegacyException(4687, "文书日期格式须为 YYYY-MM-DD：" + docDate);
        }
    }
}
