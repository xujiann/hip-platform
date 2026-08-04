package cn.hip.cdr.service;

import cn.hip.cdr.entity.CdrDocument;
import cn.hip.cdr.repository.CdrDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CDR 抽取同步：从业务库读取（只读），构建患者维度文档快照。
 * MVP 为全量拉取（幂等覆盖）；数据量大后改为按更新时间增量 + CDC。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CdrSyncService {

    private final JdbcTemplate jdbc;
    private final CdrDocumentRepository docRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public Map<String, Integer> syncAll() {
        int outp = syncOutpEncounters();
        int lab = syncLabReports();
        int inp = syncInpSummaries();
        log.info("CDR 同步完成: 门诊 {} 检验 {} 住院 {}", outp, lab, inp);
        return Map.of("outpEncounters", outp, "labReports", lab, "inpSummaries", inp);
    }

    private int syncOutpEncounters() {
        var regs = jdbc.queryForList("""
                select r.id, r.patient_id, r.visit_date, r.status, r.created_at, d.name as dept_name
                from outp_registration r join sys_dept d on d.id = r.dept_id
                where r.status <> 'CANCELLED'
                """);
        for (var r : regs) {
            Long regId = ((Number) r.get("id")).longValue();
            var doc = new LinkedHashMap<String, Object>();
            doc.put("visitDate", String.valueOf(r.get("visit_date")));
            doc.put("dept", r.get("dept_name"));
            doc.put("status", r.get("status"));
            jdbc.queryForList("select chief_complaint, present_illness, advice from outp_emr where registration_id = ?", regId)
                    .stream().findFirst().ifPresent(e -> doc.put("emr", e));
            doc.put("diagnoses", jdbc.queryForList(
                    "select icd_code, icd_name, primary_diag from outp_diagnosis where registration_id = ? order by primary_diag desc", regId));
            doc.put("orders", jdbc.queryForList(
                    "select order_type, item_name, qty, amount, status from outp_order where registration_id = ? and status <> 'CANCELLED'", regId));
            upsert(((Number) r.get("patient_id")).longValue(), "OUTP_ENCOUNTER", regId,
                    "门诊就诊 · " + r.get("dept_name"), ((java.sql.Timestamp) r.get("created_at")).toInstant(), doc);
        }
        return regs.size();
    }

    private int syncLabReports() {
        var orders = jdbc.queryForList("""
                select o.id, o.item_name, o.group_no, o.created_at, r.patient_id
                from outp_order o join outp_registration r on r.id = o.registration_id
                where o.order_type = 'LAB' and o.status = 'EXECUTED'
                """);
        int n = 0;
        for (var o : orders) {
            Long orderId = ((Number) o.get("id")).longValue();
            var results = jdbc.queryForList("""
                    select item_name, result_value, unit, ref_range, abnormal_flag
                    from outp_lab_result where order_id = ? order by id
                    """, orderId);
            var doc = new LinkedHashMap<String, Object>();
            doc.put("groupNo", o.get("group_no"));
            doc.put("results", results);
            upsert(((Number) o.get("patient_id")).longValue(), "LAB_REPORT", orderId,
                    "检验报告 · " + o.get("item_name"), ((java.sql.Timestamp) o.get("created_at")).toInstant(), doc);
            n++;
        }
        return n;
    }

    private int syncInpSummaries() {
        var adms = jdbc.queryForList("""
                select a.id, a.patient_id, a.admission_no, a.status, a.admit_at, a.discharged_at,
                       a.admit_diag_name, d.name as dept_name
                from inp_admission a join sys_dept d on d.id = a.dept_id
                """);
        for (var a : adms) {
            Long admId = ((Number) a.get("id")).longValue();
            var doc = new LinkedHashMap<String, Object>();
            doc.put("admissionNo", a.get("admission_no"));
            doc.put("dept", a.get("dept_name"));
            doc.put("status", a.get("status"));
            doc.put("admitDiagnosis", a.get("admit_diag_name"));
            doc.put("dischargedAt", String.valueOf(a.get("discharged_at")));
            doc.put("records", jdbc.queryForList(
                    "select record_type, title, content, created_at from inp_medical_record where admission_id = ? order by id", admId));
            jdbc.queryForList("select total_amount, deposit_amount, balance from inp_settlement where admission_id = ?", admId)
                    .stream().findFirst().ifPresent(s -> doc.put("settlement", s));
            upsert(((Number) a.get("patient_id")).longValue(), "INP_SUMMARY", admId,
                    "住院摘要 · " + a.get("dept_name"), ((java.sql.Timestamp) a.get("admit_at")).toInstant(), doc);
        }
        return adms.size();
    }

    @SneakyThrows
    private void upsert(Long patientId, String docType, Long refId, String title, Instant docTime, Object content) {
        CdrDocument doc = docRepository.findByDocTypeAndRefId(docType, refId).orElseGet(CdrDocument::new);
        doc.setPatientId(patientId);
        doc.setDocType(docType);
        doc.setRefId(refId);
        doc.setTitle(title);
        doc.setDocTime(docTime);
        String json = objectMapper.writeValueAsString(content);
        doc.setContent(json.length() > 7900 ? json.substring(0, 7900) : json);
        doc.setSyncedAt(Instant.now());
        docRepository.save(doc);
    }

    public List<CdrDocument> patientDocuments(Long patientId, String docType) {
        return docType == null
                ? docRepository.findByPatientIdOrderByDocTimeDesc(patientId)
                : docRepository.findByPatientIdAndDocTypeOrderByDocTimeDesc(patientId, docType);
    }
}
