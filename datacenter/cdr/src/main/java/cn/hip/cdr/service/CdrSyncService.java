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
        return doSync(Instant.EPOCH);
    }

    /**
     * 增量同步（updated_at 水位）：抽取水位之后新增/变更的业务数据，跑完把水位推进到 max(updated_at)。
     * 读取时回退 5 分钟重叠窗口——updated_at 取事务开始时刻（PG now()），长事务晚提交的行时间戳
     * 早于已推进的水位，无重叠会永久漏失；upsert 幂等，重叠重抽无副作用（窗口内长事务为已知边界）。
     */
    public static final long OVERLAP_SECONDS = 300;

    @Transactional
    public Map<String, Integer> syncIncremental() {
        var rows = jdbc.queryForList("select cfg_value from sys_config where cfg_key = 'cdr_sync_watermark'");
        Instant watermark = rows.isEmpty() ? Instant.EPOCH : Instant.parse((String) rows.get(0).get("cfg_value"));
        Instant since = watermark.equals(Instant.EPOCH) ? watermark : watermark.minusSeconds(OVERLAP_SECONDS);
        var result = doSync(since);
        java.sql.Timestamp maxUpdated = jdbc.queryForObject("""
                select greatest(
                    (select max(updated_at) from outp_registration),
                    (select max(updated_at) from outp_order),
                    (select max(updated_at) from inp_admission))
                """, java.sql.Timestamp.class);
        if (maxUpdated != null) {
            jdbc.update("""
                    insert into sys_config(cfg_key, cfg_value, remark) values ('cdr_sync_watermark', ?, 'CDR 增量水位')
                    on conflict (cfg_key) do update set cfg_value = excluded.cfg_value, updated_at = now()
                    """, maxUpdated.toInstant().toString());
        }
        return result;
    }

    private Map<String, Integer> doSync(Instant since) {
        int outp = syncOutpEncounters(since);
        int lab = syncLabReports(since);
        int inp = syncInpSummaries(since);
        log.info("CDR 同步完成(since={}): 门诊 {} 检验 {} 住院 {}", since, outp, lab, inp);
        return Map.of("outpEncounters", outp, "labReports", lab, "inpSummaries", inp);
    }

    /** CDA 样式 XML（WS/T 500 结构骨架） */
    public String toCda(Long docId, boolean plainIdNo) {
        CdrDocument doc = docRepository.findById(docId).orElseThrow();
        var patient = jdbc.queryForList(
                "select name, sex, birth_date, id_no from empi_patient where id = ?", doc.getPatientId());
        var p = patient.isEmpty() ? Map.<String, Object>of() : patient.get(0);
        String idNo = String.valueOf(p.getOrDefault("id_no", ""));
        if (!plainIdNo && idNo.length() > 7) {
            idNo = idNo.substring(0, 4) + "*".repeat(idNo.length() - 7) + idNo.substring(idNo.length() - 3);
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ClinicalDocument xmlns="urn:hl7-org:v3">
                  <typeId root="2.16.840.1.113883.1.3" extension="POCD_MT000040"/>
                  <code displayName="%s"/>
                  <title>%s</title>
                  <effectiveTime value="%s"/>
                  <recordTarget><patientRole>
                    <id extension="%s"/>
                    <patient><name>%s</name><administrativeGenderCode code="%s"/><birthTime value="%s"/></patient>
                  </patientRole></recordTarget>
                  <component><structuredBody><component><section>
                    <text><![CDATA[%s]]></text>
                  </section></component></structuredBody></component>
                </ClinicalDocument>
                """.formatted(xml(doc.getDocType()), xml(doc.getTitle()), xml(String.valueOf(doc.getDocTime())),
                xml(idNo), xml(String.valueOf(p.getOrDefault("name", ""))),
                xml(String.valueOf(p.getOrDefault("sex", "U"))), xml(String.valueOf(p.getOrDefault("birth_date", ""))),
                cdata(doc.getContent()));
    }

    /** XML 文本/属性值转义 */
    private static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** CDATA 内容防提前闭合（"]]>" 拆段） */
    private static String cdata(String s) {
        return s == null ? "" : s.replace("]]>", "]]]]><![CDATA[>");
    }

    private int syncOutpEncounters(Instant since) {
        // 退号也同步（不再过滤 CANCELLED）——否则退号后 CDR 文档状态永久停留在退号前
        var regs = jdbc.queryForList("""
                select r.id, r.patient_id, r.visit_date, r.status, r.created_at, d.name as dept_name
                from outp_registration r join sys_dept d on d.id = r.dept_id
                where r.updated_at > ?
                """, java.sql.Timestamp.from(since));
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

    private int syncLabReports(Instant since) {
        var orders = jdbc.queryForList("""
                select o.id, o.item_name, o.group_no, o.created_at, r.patient_id
                from outp_order o join outp_registration r on r.id = o.registration_id
                where o.order_type = 'LAB' and o.status = 'EXECUTED' and o.updated_at > ?
                """, java.sql.Timestamp.from(since));
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

    private int syncInpSummaries(Instant since) {
        var adms = jdbc.queryForList("""
                select a.id, a.patient_id, a.admission_no, a.status, a.admit_at, a.discharged_at,
                       a.admit_diag_name, a.discharge_diag_name, d.name as dept_name
                from inp_admission a join sys_dept d on d.id = a.dept_id
                where a.updated_at > ?
                """, java.sql.Timestamp.from(since));
        for (var a : adms) {
            Long admId = ((Number) a.get("id")).longValue();
            var doc = new LinkedHashMap<String, Object>();
            doc.put("admissionNo", a.get("admission_no"));
            doc.put("dept", a.get("dept_name"));
            doc.put("status", a.get("status"));
            doc.put("admitDiagnosis", a.get("admit_diag_name"));
            doc.put("dischargeDiagnosis", a.get("discharge_diag_name"));
            doc.put("dischargedAt", String.valueOf(a.get("discharged_at")));
            doc.put("records", jdbc.queryForList(
                    "select record_type, title, content, created_at from inp_medical_record where admission_id = ? order by id", admId));
            jdbc.queryForList("""
                    select total_amount, deposit_amount, balance from inp_settlement
                    where admission_id = ? and status = 'PAID' order by id desc limit 1
                    """, admId)
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
        doc.setContent(json);   // content 已是 text（V55），不再截断——截断的 CDR 文档等于残缺病历
        doc.setSyncedAt(Instant.now());
        docRepository.save(doc);
    }

    public List<CdrDocument> patientDocuments(Long patientId, String docType) {
        return docType == null
                ? docRepository.findByPatientIdOrderByDocTimeDesc(patientId)
                : docRepository.findByPatientIdAndDocTypeOrderByDocTimeDesc(patientId, docType);
    }
}
