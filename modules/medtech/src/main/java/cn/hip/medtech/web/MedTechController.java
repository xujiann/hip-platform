package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.integration.event.LabResultReceivedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 十四/十五期：LIS 标本流转、RIS 检查报告、手术麻醉 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP','NURSE')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class MedTechController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final ApplicationEventPublisher eventPublisher;
    private final cn.hip.platform.core.service.ConfigReader configReader;

    @org.springframework.beans.factory.annotation.Value("${hip.integration.pacs-url:}")
    private String pacsUrl;

    @org.springframework.beans.factory.annotation.Value("${hip.integration.ai-url:http://127.0.0.1:8100}")
    private String aiUrl;

    /** PACS 调阅入口（云胶片链接由前端拼装 Orthanc viewer 地址） */
    @GetMapping("/api/ris/pacs-info")
    public cn.hip.platform.core.common.R<Map<String, Object>> pacsInfo() {
        return cn.hip.platform.core.common.R.ok(Map.of(
                "configured", pacsUrl != null && !pacsUrl.isBlank(),
                "pacsUrl", pacsUrl == null ? "" : pacsUrl,
                "viewerUrl", pacsUrl == null || pacsUrl.isBlank() ? "" : pacsUrl + "/app/explorer.html"));
    }

    /** AI 子服务：检验结果智能解读（子服务不可达时优雅降级） */
    @GetMapping("/api/ai/lab-advice")
    public cn.hip.platform.core.common.R<Map<String, Object>> labAdvice(@RequestParam Long orderId) {
        var results = jdbc.queryForList("""
                select item_name as name, result_value as value, abnormal_flag as flag
                from outp_lab_result where order_id = ? order by id
                """, orderId);
        // 9952：原 9950 与故障工单幂等、死亡登记必填两处撞车（三义），排障时无法按码定位（P2）
        if (results.isEmpty()) return cn.hip.platform.core.common.R.fail(9952, "该申请暂无结果");
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2)).build();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(aiUrl + "/analyze/lab"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(Map.of("results", results))))
                    .build();
            var resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = mapper.readValue(resp.body(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            body.put("source", "ai-service");
            return cn.hip.platform.core.common.R.ok(body);
        } catch (Exception e) {
            return cn.hip.platform.core.common.R.ok(Map.of(
                    "source", "fallback",
                    "advice", java.util.List.of("AI 子服务不可用，请人工判读"),
                    "error", e.getClass().getSimpleName()));
        }
    }

    // ===== LIS =====

    /** 待采样：已收费未建标本的检验申请 */
    @GetMapping("/api/lis/pending")
    public R<List<Map<String, Object>>> lisPending() {
        return R.ok(jdbc.queryForList("""
                select o.id as order_id, o.group_no, o.item_name, p.name as patient_name, p.sex
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where o.order_type = 'LAB' and o.status = 'CHARGED'
                  and not exists (select 1 from lis_sample s where s.order_id = o.id)
                order by o.id
                """));
    }

    /** 采样打码（三十九期：替检参数化——lis_allow_substitute 关闭时拦截，开启时留替检人标识供分检醒目提示） */
    @PostMapping("/api/lis/samples")
    @Transactional
    public R<Map<String, Object>> collect(@RequestParam Long orderId,
                                          @RequestParam(required = false) String substituteName) {
        boolean substitute = substituteName != null && !substituteName.isBlank();
        if (substitute) {
            var allow = jdbc.queryForList(
                    "select cfg_value from sys_config where cfg_key = 'lis_allow_substitute'", String.class);
            // 双语义（1.1.6 B-1）：历史约定 true/false，全库惯例 0/1——只认字面 false 时，
            // 管理员按惯例填 0 替检仍会放行
            if (!allow.isEmpty() && ("false".equalsIgnoreCase(allow.get(0)) || "0".equals(allow.get(0)))) {
                return R.fail(4770, "本院参数已禁止替检");
            }
        }
        String barcode = "BC" + jdbc.queryForObject("select nextval('lis_barcode_seq')", Long.class);
        int n = jdbc.update("""
                insert into lis_sample(order_id, barcode, substitute, substitute_name)
                select ?, ?, ?, ? where exists (select 1 from outp_order o where o.id = ? and o.order_type = 'LAB' and o.status = 'CHARGED')
                  and not exists (select 1 from lis_sample s where s.order_id = ?)
                """, orderId, barcode, substitute, substitute ? substituteName : null, orderId, orderId);
        return n == 0 ? R.fail(9940, "申请不存在/未收费/已采样") : R.ok(Map.of("barcode", barcode));
    }

    /** 标本核收 */
    @PutMapping("/api/lis/samples/{barcode}/receive")
    public R<Void> receive(@PathVariable String barcode) {
        int n = jdbc.update(
                "update lis_sample set status = 'RECEIVED', received_at = now() where barcode = ? and status = 'COLLECTED'",
                barcode);
        return n == 0 ? R.fail(9941, "标本不存在或状态不符") : R.ok();
    }

    /** 标本工作队列 */
    @GetMapping("/api/lis/samples")
    public R<List<Map<String, Object>>> samples() {
        return R.ok(jdbc.queryForList("""
                select s.id, s.barcode, s.status, s.collected_at, s.substitute, s.substitute_name,
                       o.id as order_id, o.item_name, o.group_no,
                       p.name as patient_name
                from lis_sample s
                join outp_order o on o.id = s.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where s.status <> 'PUBLISHED' order by s.id
                """));
    }

    public record ManualResult(String code, String name, String value, String unit, String refRange, String flag) {}
    public record PublishReq(List<ManualResult> results) {}

    /** 手工录入结果并审核发布：复用 LIS 事件链路（自动执行医嘱+落库+危急值） */
    @PostMapping("/api/lis/samples/{barcode}/publish")
    @Transactional
    public R<Void> publish(@PathVariable String barcode, @RequestBody PublishReq req, Authentication auth) {
        var rows = jdbc.queryForList("""
                select s.id, o.group_no from lis_sample s join outp_order o on o.id = s.order_id
                where s.barcode = ? and s.status = 'RECEIVED'
                """, barcode);
        if (rows.isEmpty()) return R.fail(9942, "标本不存在或未核收");
        if (req.results() == null || req.results().isEmpty()) return R.fail(9943, "结果不能为空");
        String groupNo = (String) rows.get(0).get("group_no");
        // 先抢占终态再发事件：并发 publish 会双写结果行并双发危急值告警
        if (jdbc.update("update lis_sample set status = 'PUBLISHED', published_at = now(), verifier_id = ? "
                + "where barcode = ? and status = 'RECEIVED'", currentUserService.idOf(auth), barcode) == 0) {
            return R.fail(9942, "标本不存在或未核收");
        }
        eventPublisher.publishEvent(new LabResultReceivedEvent(groupNo, req.results().stream()
                .map(x -> new LabResultReceivedEvent.Item(x.code(), x.name(), x.value(), x.unit(), x.refRange(), x.flag()))
                .toList()));
        return R.ok();
    }

    // ===== RIS =====

    /** 待报告：已收费检查申请（自动登记，按项目名归类模态：心电/内镜/超声/普放） */
    @GetMapping("/api/ris/worklist")
    @Transactional
    public R<List<Map<String, Object>>> risWorklist(@RequestParam(required = false) String modality) {
        jdbc.update("""
                insert into ris_exam(order_id, modality)
                select o.id, case when o.item_name like '%心电%' then 'ECG'
                                  when o.item_name like '%镜%' then 'ENDO'
                                  when o.item_name like '%超声%' or o.item_name like '%彩超%' then 'US'
                                  else 'GENERAL' end
                from outp_order o
                where o.order_type = 'EXAM' and o.status = 'CHARGED'
                  and not exists (select 1 from ris_exam e where e.order_id = o.id)
                """);
        String filter = modality == null ? "" : " and e.modality = ? ";
        String sql = """
                select e.id, e.status, e.modality, e.findings, e.impression, o.item_name, o.group_no, p.name as patient_name
                from ris_exam e
                join outp_order o on o.id = e.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where e.status <> 'VERIFIED'
                """ + filter + " order by e.id";
        return R.ok(modality == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, modality));
    }

    public record RisReportReq(String findings, String impression) {}

    @PutMapping("/api/ris/exams/{id}/report")
    public R<Void> writeReport(@PathVariable Long id, @RequestBody RisReportReq req, Authentication auth) {
        int n = jdbc.update("""
                update ris_exam set findings = ?, impression = ?, reporter_id = ?, reported_at = now(), status = 'REPORTED'
                where id = ? and status in ('REGISTERED', 'REPORTED')
                """, req.findings(), req.impression(), currentUserService.idOf(auth), id);
        return n == 0 ? R.fail(9944, "检查不存在或已审核") : R.ok();
    }

    /** 审核发布：医嘱转已执行。v33：审核人不得为报告医师（双签分权，评审否决项）。 */
    @PutMapping("/api/ris/exams/{id}/verify")
    @Transactional
    public R<Void> verifyReport(@PathVariable Long id, Authentication auth) {
        Long me = currentUserService.idOf(auth);
        int n = jdbc.update("""
                update ris_exam set status = 'VERIFIED', verifier_id = ?, verified_at = now()
                where id = ? and status = 'REPORTED' and reporter_id <> ?
                """, me, id, me);
        if (n == 0) {
            // 区分自审（已报告但报告人=自己）与未报告，给不同错误码
            var row = jdbc.queryForList("select status, reporter_id from ris_exam where id = ?", id);
            if (!row.isEmpty() && "REPORTED".equals(row.get(0).get("status"))
                    && me != null && me.equals(numOrNull(row.get(0).get("reporter_id")))) {
                return R.fail(9947, "审核人不得为报告医师");
            }
            return R.fail(9945, "须先书写报告");
        }
        jdbc.update("update outp_order set status = 'EXECUTED' where id = (select order_id from ris_exam where id = ?) and status = 'CHARGED'", id);
        return R.ok();
    }

    public record RisCriticalReq(String note) {}

    /**
     * v33：影像危急值上报（气胸/夹层/颅内出血等）。标记检查为危急并复用 outp_critical_alert
     * 走与检验同一条确认闭环（通知开单医师→限时接收确认→处置留痕）。须先有报告内容。
     */
    @PutMapping("/api/ris/exams/{id}/critical")
    @Transactional
    public R<Void> markRisCritical(@PathVariable Long id, @RequestBody RisCriticalReq req) {
        if (req.note() == null || req.note().isBlank()) return R.fail(9948, "危急描述必填");
        var rows = jdbc.queryForList("""
                select e.order_id, e.critical_flag, o.registration_id, o.doctor_id, o.item_name
                from ris_exam e join outp_order o on o.id = e.order_id
                where e.id = ? and e.status in ('REPORTED', 'VERIFIED')
                """, id);
        if (rows.isEmpty()) return R.fail(9945, "须先书写报告");
        var row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("critical_flag"))) return R.fail(9949, "该检查已标记危急值");
        jdbc.update("update ris_exam set critical_flag = true, critical_note = ? where id = ?", req.note(), id);
        int minutes = configReader.getInt("critical_ack_deadline_minutes", 10);
        jdbc.update("""
                insert into outp_critical_alert(order_id, registration_id, content, source,
                        notify_to_user_id, notified_at, deadline_at)
                values (?, ?, ?, 'RIS', ?, now(), now() + make_interval(mins => ?))
                """, numOrNull(row.get("order_id")), numOrNull(row.get("registration_id")),
                "【影像危急值】" + row.get("item_name") + "：" + req.note(),
                numOrNull(row.get("doctor_id")), minutes);
        return R.ok();
    }

    private static Long numOrNull(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    // ===== 手术麻醉 =====

    public record SurgeryReq(Long admissionId, String procedureName, String anesthesiaType, String scheduledAt,
                             String opIcd) {}

    @PostMapping("/api/inpatient/surgeries")
    public R<Void> requestSurgery(@RequestBody SurgeryReq req, Authentication auth) {
        String status = req.scheduledAt() == null ? "REQUESTED" : "SCHEDULED";
        // 1.0.4：op_icd 手术操作编码（ICD-9-CM-3），病案首页与 DRG 手术组入组数据基础
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, scheduled_at, surgeon_id, status, op_icd)
                values (?,?,?,?::timestamptz,?,?,?)
                """, req.admissionId(), req.procedureName(), req.anesthesiaType(),
                req.scheduledAt(), currentUserService.idOf(auth), status, req.opIcd());
        return R.ok();
    }

    @GetMapping("/api/inpatient/surgeries")
    public R<List<Map<String, Object>>> surgeries() {
        return R.ok(jdbc.queryForList("""
                select s.id, s.admission_id, s.procedure_name, s.anesthesia_type, s.scheduled_at, s.status,
                       s.op_icd, s.op_note, s.anes_note, a.admission_no, p.name as patient_name
                from inp_surgery s
                join inp_admission a on a.id = s.admission_id
                join empi_patient p on p.id = a.patient_id
                order by case s.status when 'DONE' then 1 else 0 end, s.id desc limit 100
                """));
    }

    public record SurgeryDoneReq(String opNote, String anesNote) {}

    @PutMapping("/api/inpatient/surgeries/{id}/complete")
    public R<Void> completeSurgery(@PathVariable Long id, @RequestBody SurgeryDoneReq req) {
        int n = jdbc.update("""
                update inp_surgery set status = 'DONE', op_note = ?, anes_note = ?
                where id = ? and status in ('REQUESTED', 'SCHEDULED')
                """, req.opNote(), req.anesNote(), id);
        return n == 0 ? R.fail(9946, "手术不存在或已完成") : R.ok();
    }

    // ===== 病历模板 =====

    public record EmrTemplateReq(Long deptId, String name, String content) {}

    @PostMapping("/api/emr-templates")
    public R<Void> createTemplate(@RequestBody EmrTemplateReq req) {
        jdbc.update("insert into emr_template(dept_id, name, content) values (?,?,?)",
                req.deptId(), req.name(), req.content());
        return R.ok();
    }

    @GetMapping("/api/emr-templates")
    public R<List<Map<String, Object>>> templates(@RequestParam(required = false) Long deptId) {
        return R.ok(deptId == null
                ? jdbc.queryForList("select * from emr_template order by id")
                : jdbc.queryForList("select * from emr_template where dept_id = ? or dept_id is null order by id", deptId));
    }
}
