package cn.hip.cdr.service;

import cn.hip.cdr.entity.CdrDocument;
import cn.hip.cdr.repository.CdrDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 分批大小：每批一个事务。全量同步曾是单事务 50 万+ SQL（1.1.7 B-7） */
    static final int BATCH = 500;

    public Map<String, Integer> syncAll() {
        return doSync(Instant.EPOCH);
    }

    /**
     * 增量同步（updated_at 水位）：抽取水位之后新增/变更的业务数据，跑完把水位推进到 max(updated_at)。
     * 读取时回退 5 分钟重叠窗口——updated_at 取事务开始时刻（PG now()），长事务晚提交的行时间戳
     * 早于已推进的水位，无重叠会永久漏失；upsert 幂等，重叠重抽无副作用（窗口内长事务为已知边界）。
     */
    public static final long OVERLAP_SECONDS = 300;

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
        var named = new NamedParameterJdbcTemplate(jdbc);
        for (var batch : partition(regs, BATCH)) {
            List<Long> ids = batch.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
            // 子表按 id 集合一次取回（原每行 3 条子查询，1.1.7 B-7）
            var emrByReg = groupBy(named.queryForList(
                    "select registration_id, chief_complaint, present_illness, advice from outp_emr where registration_id in (:ids)",
                    Map.of("ids", ids)), "registration_id");
            var diagByReg = groupBy(named.queryForList(
                    "select registration_id, icd_code, icd_name, primary_diag from outp_diagnosis where registration_id in (:ids) order by primary_diag desc",
                    Map.of("ids", ids)), "registration_id");
            var ordersByReg = groupBy(named.queryForList(
                    "select registration_id, order_type, item_name, qty, amount, status from outp_order where registration_id in (:ids) and status <> 'CANCELLED'",
                    Map.of("ids", ids)), "registration_id");
            txTemplate.executeWithoutResult(tx -> {
                for (var r : batch) {
                    Long regId = ((Number) r.get("id")).longValue();
                    var doc = new LinkedHashMap<String, Object>();
                    doc.put("visitDate", String.valueOf(r.get("visit_date")));
                    doc.put("dept", r.get("dept_name"));
                    doc.put("status", r.get("status"));
                    var emrs = emrByReg.get(regId);
                    if (emrs != null && !emrs.isEmpty()) doc.put("emr", stripKey(emrs.get(0)));
                    doc.put("diagnoses", stripKeys(diagByReg.get(regId)));
                    doc.put("orders", stripKeys(ordersByReg.get(regId)));
                    upsert(((Number) r.get("patient_id")).longValue(), "OUTP_ENCOUNTER", regId,
                            "门诊就诊 · " + r.get("dept_name"), ((java.sql.Timestamp) r.get("created_at")).toInstant(), doc);
                }
            });
        }
        return regs.size();
    }

    private int syncLabReports(Instant since) {
        var orders = jdbc.queryForList("""
                select o.id, o.item_name, o.group_no, o.created_at, r.patient_id
                from outp_order o join outp_registration r on r.id = o.registration_id
                where o.order_type = 'LAB' and o.status = 'EXECUTED' and o.updated_at > ?
                """, java.sql.Timestamp.from(since));
        var named = new NamedParameterJdbcTemplate(jdbc);
        for (var batch : partition(orders, BATCH)) {
            List<Long> ids = batch.stream().map(o -> ((Number) o.get("id")).longValue()).toList();
            var resultsByOrder = groupBy(named.queryForList("""
                    select order_id, item_name, result_value, unit, ref_range, abnormal_flag
                    from outp_lab_result where order_id in (:ids) order by id
                    """, Map.of("ids", ids)), "order_id");
            txTemplate.executeWithoutResult(tx -> {
                for (var o : batch) {
                    Long orderId = ((Number) o.get("id")).longValue();
                    var doc = new LinkedHashMap<String, Object>();
                    doc.put("groupNo", o.get("group_no"));
                    doc.put("results", stripKeys(resultsByOrder.get(orderId)));
                    upsert(((Number) o.get("patient_id")).longValue(), "LAB_REPORT", orderId,
                            "检验报告 · " + o.get("item_name"), ((java.sql.Timestamp) o.get("created_at")).toInstant(), doc);
                }
            });
        }
        return orders.size();
    }

    private int syncInpSummaries(Instant since) {
        var adms = jdbc.queryForList("""
                select a.id, a.patient_id, a.admission_no, a.status, a.admit_at, a.discharged_at,
                       a.admit_diag_name, a.discharge_diag_name, d.name as dept_name
                from inp_admission a join sys_dept d on d.id = a.dept_id
                where a.updated_at > ?
                """, java.sql.Timestamp.from(since));
        var named = new NamedParameterJdbcTemplate(jdbc);
        for (var batch : partition(adms, BATCH)) {
            List<Long> ids = batch.stream().map(a -> ((Number) a.get("id")).longValue()).toList();
            var recordsByAdm = groupBy(named.queryForList(
                    "select admission_id, record_type, title, content, created_at from inp_medical_record where admission_id in (:ids) order by id",
                    Map.of("ids", ids)), "admission_id");
            var settleByAdm = groupBy(named.queryForList("""
                    select distinct on (admission_id) admission_id, total_amount, deposit_amount, balance
                    from inp_settlement where admission_id in (:ids) and status = 'PAID'
                    order by admission_id, id desc
                    """, Map.of("ids", ids)), "admission_id");
            txTemplate.executeWithoutResult(tx -> {
                for (var a : batch) {
                    Long admId = ((Number) a.get("id")).longValue();
                    var doc = new LinkedHashMap<String, Object>();
                    doc.put("admissionNo", a.get("admission_no"));
                    doc.put("dept", a.get("dept_name"));
                    doc.put("status", a.get("status"));
                    doc.put("admitDiagnosis", a.get("admit_diag_name"));
                    doc.put("dischargeDiagnosis", a.get("discharge_diag_name"));
                    doc.put("dischargedAt", String.valueOf(a.get("discharged_at")));
                    doc.put("records", stripKeys(recordsByAdm.get(admId)));
                    var settles = settleByAdm.get(admId);
                    if (settles != null && !settles.isEmpty()) doc.put("settlement", stripKey(settles.get(0)));
                    upsert(((Number) a.get("patient_id")).longValue(), "INP_SUMMARY", admId,
                            "住院摘要 · " + a.get("dept_name"), ((java.sql.Timestamp) a.get("admit_at")).toInstant(), doc);
                }
            });
        }
        return adms.size();
    }

    /**
     * upsert 改单条 SQL（1.1.7 B-7）：原 JPA 版每次 find 触发 auto-flush + 对已累积托管实体
     * 的 O(N²) 脏检查，10 万文档即数百 MB 持久化上下文。on conflict 依托 V11 的
     * unique(doc_type, ref_id)。content 已是 text（V55），不截断。
     */
    @SneakyThrows
    private void upsert(Long patientId, String docType, Long refId, String title, Instant docTime, Object content) {
        String json = objectMapper.writeValueAsString(content);
        jdbc.update("""
                insert into cdr_document(patient_id, doc_type, ref_id, title, doc_time, content, synced_at)
                values (?,?,?,?,?,?, now())
                on conflict (doc_type, ref_id) do update set
                    patient_id = excluded.patient_id, title = excluded.title,
                    doc_time = excluded.doc_time, content = excluded.content, synced_at = now()
                """, patientId, docType, refId, title, java.sql.Timestamp.from(docTime), json);
    }

    // ---- 批处理辅助 ----

    private static <T> List<List<T>> partition(List<T> list, int size) {
        var out = new java.util.ArrayList<List<T>>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return out;
    }

    /** 按分组键聚合子表行；键列名不区分大小写取 Number 转 long */
    private static Map<Long, List<Map<String, Object>>> groupBy(List<Map<String, Object>> rows, String key) {
        var out = new java.util.HashMap<Long, List<Map<String, Object>>>();
        for (var row : rows) {
            Long k = ((Number) row.get(key)).longValue();
            out.computeIfAbsent(k, x -> new java.util.ArrayList<>()).add(row);
        }
        return out;
    }

    /** 去掉分组键列，文档 JSON 与原行结构一致（分组键是取数用的，不属于文档内容） */
    private static Map<String, Object> stripKey(Map<String, Object> row) {
        var m = new LinkedHashMap<>(row);
        m.remove("registration_id");
        m.remove("order_id");
        m.remove("admission_id");
        return m;
    }

    private static List<Map<String, Object>> stripKeys(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows.stream().map(CdrSyncService::stripKey).toList();
    }

    /** 列表投影不带 content（1.1.7）：慢病多年患者全文档含全文一次响应可达数 MB，明细单独取 */
    public List<Map<String, Object>> patientDocuments(Long patientId, String docType) {
        return jdbc.queryForList("""
                select id, patient_id as "patientId", doc_type as "docType", ref_id as "refId",
                       title, doc_time as "docTime", synced_at as "syncedAt"
                from cdr_document
                where patient_id = ? and (?::varchar is null or doc_type = ?)
                order by doc_time desc limit 200
                """, patientId, docType, docType);
    }

    public CdrDocument document(Long id) {
        return docRepository.findById(id).orElse(null);
    }
}
