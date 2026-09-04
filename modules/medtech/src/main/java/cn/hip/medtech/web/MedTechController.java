package cn.hip.medtech.web;

import cn.hip.medtech.service.EmrTemplateService;
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
    /** v45 车道H：病历模板体系（作用范围/授权/编辑停用/科室默认/存为模板），见文件末尾那一组端点 */
    private final cn.hip.medtech.service.EmrTemplateService emrTemplateService;

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
                -- v44 合版补：V137 给 outp_order 加了标本类型/采样部位/加急/备注，
                -- 但本队列是显式列清单、原先取不到——字段建了采样台却看不见，等于半截功能。
                -- 纯补 select 列，join 与 where 一字未动。
                select o.id as order_id, o.group_no, o.item_name, p.name as patient_name, p.sex,
                       o.specimen_type, o.sampling_site, o.urgent, o.remark
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
                       o.specimen_type, o.sampling_site, o.urgent, o.remark,   -- v44 合版补
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
                -- v44 合版补：临床摘要/检查目的/注意事项/加急是技师写报告的依据，
                -- V137 已建列但本队列原先不取（同 lis/pending）。
                select e.id, e.status, e.modality, e.findings, e.impression, o.item_name, o.group_no,
                       o.clinical_summary, o.exam_purpose, o.notice, o.urgent,
                       p.name as patient_name
                from ris_exam e
                join outp_order o on o.id = e.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where e.status <> 'VERIFIED'
                """ + filter + " order by e.id";
        return R.ok(modality == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, modality));
    }

    public record RisReportReq(String findings, String impression) {}

    /** v38 到检登记：患者到检 REGISTERED→ARRIVED（与预约叫号联动的现场登记态） */
    @PutMapping("/api/ris/exams/{id}/arrive")
    public R<Void> arrive(@PathVariable Long id) {
        int n = jdbc.update(
                "update ris_exam set status = 'ARRIVED', arrived_at = now() where id = ? and status = 'REGISTERED'", id);
        return n == 0 ? R.fail(9944, "检查不存在或状态不允许到检登记") : R.ok();
    }

    /**
     * v38 结果互认提醒：该患者 N 天内已出报告（VERIFIED）的同名检查（只提示不阻断，控费/医保飞检项）。
     * 开单或登记时前端调用，命中即提示"可复用近期结果"。
     */
    @GetMapping("/api/ris/recent-exams")
    public R<List<Map<String, Object>>> recentExams(@RequestParam Long patientId,
                                                    @RequestParam(required = false) String itemName) {
        int days = configReader.getInt("ris.mutual.days", 30);
        String nameFilter = itemName == null || itemName.isBlank() ? "" : " and o.item_name = ? ";
        String sql = """
                select e.id, o.item_name, e.modality, e.impression, e.verified_at
                from ris_exam e
                join outp_order o on o.id = e.order_id
                join outp_registration r on r.id = o.registration_id
                where r.patient_id = ? and e.status = 'VERIFIED'
                  and e.verified_at > now() - make_interval(days => ?)
                """ + nameFilter + " order by e.verified_at desc limit 20";
        return R.ok(itemName == null || itemName.isBlank()
                ? jdbc.queryForList(sql, patientId, days)
                : jdbc.queryForList(sql, patientId, days, itemName));
    }

    @PutMapping("/api/ris/exams/{id}/report")
    public R<Void> writeReport(@PathVariable Long id, @RequestBody RisReportReq req, Authentication auth) {
        int n = jdbc.update("""
                update ris_exam set findings = ?, impression = ?, reporter_id = ?, reported_at = now(), status = 'REPORTED'
                where id = ? and status in ('REGISTERED', 'ARRIVED', 'REPORTED')
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
    public R<Map<String, Object>> requestSurgery(@RequestBody SurgeryReq req, Authentication auth) {
        String status = req.scheduledAt() == null ? "REQUESTED" : "SCHEDULED";
        // v34 知情同意 gate（试点期可配 emr.gate.consent.surgery：off 旁路 / warn 警告放行 / block 硬拦）。
        // 默认 warn——不挡历史不合规流，仅提示；院内规范落地后改 block 硬拦（改 sys_config 即时生效）。
        String warning = null;
        String mode = configReader.get("emr.gate.consent.surgery", "warn");
        if (!"off".equals(mode) && !hasSignedConsent(req.admissionId(), "SURGERY")) {
            if ("block".equals(mode)) return R.fail(9116, "无有效手术知情同意书，不能申请手术");
            warning = "未查到有效手术知情同意书，请及时补录并医患双签";
        }
        // 1.0.4：op_icd 手术操作编码（ICD-9-CM-3），病案首页与 DRG 手术组入组数据基础
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, scheduled_at, surgeon_id, status, op_icd)
                values (?,?,?,?::timestamptz,?,?,?)
                """, req.admissionId(), req.procedureName(), req.anesthesiaType(),
                req.scheduledAt(), currentUserService.idOf(auth), status, req.opIcd());
        return warning == null ? R.ok(Map.of()) : R.ok(Map.of("warning", warning));
    }

    /** v34：该住院是否已有指定类型的有效（SIGNED、未作废）知情同意书 */
    private boolean hasSignedConsent(Long admissionId, String consentType) {
        if (admissionId == null) return false;
        Integer n = jdbc.queryForObject("""
                select count(*) from emr_consent
                where admission_id = ? and consent_type = ? and status = 'SIGNED' and revoked_at is null
                """, Integer.class, admissionId, consentType);
        return n != null && n > 0;
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

    // ==================================================================
    // 病历模板（v45 车道H：987★/988★/1073★/1078★/1079★/1095★）
    //
    // 端点分两层，**刻意不合并**：
    //  · 下面这两条（POST/GET /api/emr-templates）是 v17 起的**冻结契约**——
    //    RisView.vue:67（type=RIS）、住院医生站模板下拉（InpDoctorView.vue:579）、
    //    v42 维护页、e2e-phase1316.py、e2e-outp-appt.py、V38RisExpTest、V42EmrTemplateTest
    //    全都在用它，连方法签名都被单测按 2 参/1 参写死。**本版一个参数、一行取数口径都没动。**
    //  · 其余 /api/emr-templates/** 是本版新开的通道，四级作用范围（1073）、授权（1078）、
    //    编辑停用（v42 起欠了三版的账）、科室默认模板（988）、存为模板（1095）、
    //    按既往病历取正文（1079）都落在那里。
    //
    // 权限：类级 @PreAuthorize 是 ADMIN/TECHNICIAN/DOCTOR_OUTP/NURSE，**不含 QUALITY**，
    // 而 V133:29-32 把病历模板菜单授给了 ADMIN/DOCTOR_OUTP/**QUALITY**——质控点得进菜单、
    // 调接口却吃 1005。这是 v42 遗留的一处菜单与接口权限对不齐，本版在端点级补齐（只放宽本组端点，
    // 不动类级注解，免得把 LIS/RIS/手麻端点一并对质控开放）。
    // ==================================================================

    /** 本组端点的权限门槛（角色只是门槛，可见/可维护按四级作用范围逐条判，见 EmrTemplateService） */
    private static final String EMR_TPL_ROLES =
            "hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY','NURSE','TECHNICIAN')";

    /**
     * <b>冻结契约</b>：分量集合自 v17 起未变，v38 加 templateType 后再未动。
     * 加一个分量就会让 V38RisExpTest / V42EmrTemplateTest 当场编译失败，
     * 带作用范围的建模板另走 {@link EmrTemplateService.TemplateReq}。
     */
    public record EmrTemplateReq(Long deptId, String name, String content, String templateType) {}

    /**
     * 建模板（<b>冻结契约</b>：请求体、返回体、校验口径一律照旧——它历来不校验名称与正文，
     * 本版也不补，补了就会打断 v42 维护页与两条 E2E；写入校验收口是 v43b 的事）。
     *
     * <p>v45 只在**落库时补齐新列**，对调用方完全透明：
     * <ul>
     *   <li>{@code owner_id}/{@code created_by} 取当前登录人（取不到就留空，不报错）；</li>
     *   <li>{@code scope} 按既有语义推定——<b>dept_id 有值 = 科室专属（DEPT），为空 = 全院通用
     *       （HOSPITAL）</b>，这正是下面 GET 的过滤口径，推定结果与旧行为完全一致；</li>
     *   <li>DEPT 的模板顺手写一条自动授权（1078 参数原话「新建的时候自动完成授权给构建科室」）。</li>
     * </ul>
     * <b>刻意不加范围权限判定</b>：本端点历来允许任何持有本控制器权限的角色建全院可见的模板，
     * 加判定就是改契约。带判定的通道是新端点 {@code POST /api/emr-templates/scoped}。
     */
    @PostMapping("/api/emr-templates")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Void> createTemplate(@RequestBody EmrTemplateReq req) {
        // 签名被单测按 1 参写死，当前用户只能从 SecurityContext 里取（不能加 Authentication 形参）
        var ctxAuth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        Long uid = ctxAuth == null ? null : currentUserService.idOf(ctxAuth);
        String scope = req.deptId() == null ? "HOSPITAL" : "DEPT";
        Long id = jdbc.queryForObject("""
                insert into emr_template(dept_id, name, content, template_type, scope, owner_id, created_by)
                values (?,?,?,?,?,?,?) returning id
                """, Long.class, req.deptId(), req.name(), req.content(),
                req.templateType() == null || req.templateType().isBlank() ? "EMR" : req.templateType(),
                scope, uid, uid);
        if (req.deptId() != null) {
            jdbc.update("""
                    insert into emr_template_grant(template_id, grantee_type, grantee_id, granted_by)
                    values (?, 'DEPT', ?, ?) on conflict (template_id, grantee_type, grantee_id) do nothing
                    """, id, req.deptId(), uid);
        }
        return R.ok();
    }

    /**
     * v38：type 过滤（EMR 病历 / CONSENT 同意书 / RIS 报告模板），缺省全量向后兼容。
     *
     * <p><b>冻结契约</b>：参数、dept_id 过滤口径（本科室 <b>或</b> 全院通用）、
     * {@code select *}、{@code order by id} 一律照旧，返回体的既有 5 个键
     * （id/dept_id/name/content/template_type）逐字不变——V45TemplateScopeTest §① 逐键钉死。
     *
     * <p><b>唯一的行为变化，是这一版补 enabled 的全部意义所在</b>：已停用的模板不再返回。
     * 若不加这一句，"停用"就只是维护页上的一个标记——RisView 与住院医生站的下拉照样把停用模板
     * 端给医生（v44 建 rx_template 时把这条写成了建表纪律："停用了还能被套用等于没做"）。
     * 影响面为零：{@code enabled} 默认 true，升级前的每一行都是启用态，
     * 既有单测、两条 E2E、三处前端消费方的行数与内容<b>一行不差</b>；只有本版新开的
     * 「停用」动作才会让某一行消失，而那正是维护人按下那个按钮时想要的结果。
     */
    @GetMapping("/api/emr-templates")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<List<Map<String, Object>>> templates(@RequestParam(required = false) Long deptId,
                                                  @RequestParam(required = false) String type) {
        var where = new StringBuilder(" where enabled ");
        var args = new java.util.ArrayList<Object>();
        if (deptId != null) {
            where.append(" and (dept_id = ? or dept_id is null) ");
            args.add(deptId);
        }
        if (type != null && !type.isBlank()) {
            where.append(" and template_type = ? ");
            args.add(type);
        }
        return R.ok(jdbc.queryForList("select * from emr_template" + where + " order by id", args.toArray()));
    }

    // ---------- v45 新通道：作用范围 / 授权 / 编辑停用 / 默认模板 / 存为模板 ----------

    /** 当前登录人的判权画像：建模板对话框据此决定哪些作用范围可选、科室默认填谁 */
    @GetMapping("/api/emr-templates/actor")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Map<String, Object>> templateActor(Authentication auth) {
        return R.ok(emrTemplateService.actorInfo(currentUserService.idOf(auth)));
    }

    /**
     * 按登录人可见范围列模板（1073★）：GLOBAL/HOSPITAL 人人可见；DEPT 本科室 + 被授权科室；
     * PERSONAL 本人 + 被授权个人。行内附 {@code editable}/{@code scopeName}/{@code grant_count}。
     */
    @GetMapping("/api/emr-templates/visible")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<List<Map<String, Object>>> visibleTemplates(@RequestParam(required = false) String type,
                                                         @RequestParam(required = false) String recordType,
                                                         @RequestParam(required = false) String scope,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(defaultValue = "false") boolean includeDisabled,
                                                         Authentication auth) {
        return R.ok(emrTemplateService.listVisible(currentUserService.idOf(auth),
                type, recordType, scope, keyword, includeDisabled));
    }

    /** 套用：按 id 取模板正文。不可见或已停用返 4066。**只读**——写病历仍走既有病历保存端点。 */
    @GetMapping("/api/emr-templates/{id}")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Map<String, Object>> useTemplate(@PathVariable Long id, Authentication auth) {
        return R.ok(emrTemplateService.use(currentUserService.idOf(auth), id));
    }

    /** 带作用范围与自动授权的建模板（新通道；老通道 POST /api/emr-templates 保持原样） */
    @PostMapping("/api/emr-templates/scoped")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Long> createScopedTemplate(@RequestBody EmrTemplateService.TemplateReq req, Authentication auth) {
        return R.ok(emrTemplateService.create(currentUserService.idOf(auth), req));
    }

    /**
     * 改模板 —— <b>v42 发现、v43 确认、欠了三个版本的那件事</b>。
     * 与下面的 disable/enable 是同批交付的，不留"下一版再补编辑"的尾巴。
     */
    @PutMapping("/api/emr-templates/{id}")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Void> updateTemplate(@PathVariable Long id,
                                  @RequestBody EmrTemplateService.TemplateReq req, Authentication auth) {
        emrTemplateService.update(currentUserService.idOf(auth), id, req);
        return R.ok();
    }

    /** 停用（软开关，不删行）：停用后不再出现在任何下拉，按 id 套用也返 4066 */
    @PutMapping("/api/emr-templates/{id}/disable")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Void> disableTemplate(@PathVariable Long id, Authentication auth) {
        emrTemplateService.setEnabled(currentUserService.idOf(auth), id, false);
        return R.ok();
    }

    /** 启用 */
    @PutMapping("/api/emr-templates/{id}/enable")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Void> enableTemplate(@PathVariable Long id, Authentication auth) {
        emrTemplateService.setEnabled(currentUserService.idOf(auth), id, true);
        return R.ok();
    }

    // ----- 授权（1078★） -----

    @GetMapping("/api/emr-templates/{id}/grants")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<List<Map<String, Object>>> templateGrants(@PathVariable Long id, Authentication auth) {
        return R.ok(emrTemplateService.grants(currentUserService.idOf(auth), id));
    }

    public record GrantReq(String granteeType, Long granteeId) {}

    /** 授予到科室或个人。重复授予幂等（"再授权一次"业务上就是无操作，不该弹错误框）。 */
    @PostMapping("/api/emr-templates/{id}/grants")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Void> grantTemplate(@PathVariable Long id, @RequestBody GrantReq req, Authentication auth) {
        emrTemplateService.grant(currentUserService.idOf(auth), id, req.granteeType(), req.granteeId());
        return R.ok();
    }

    /** 撤销授权。建模板时自动写的那条撤不掉（撤了模板对自己科室/自己都不可见，是自伤路径）。 */
    @DeleteMapping("/api/emr-templates/{id}/grants/{grantId}")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Void> revokeTemplateGrant(@PathVariable Long id, @PathVariable Long grantId, Authentication auth) {
        emrTemplateService.revoke(currentUserService.idOf(auth), id, grantId);
        return R.ok();
    }

    /** 授权对象候选字典（/api/system/users 是 ADMIN 专属，质控与医生取不到人员字典） */
    @GetMapping("/api/emr-templates/grantee-candidates")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<List<Map<String, Object>>> granteeCandidates(@RequestParam(defaultValue = "DEPT") String granteeType,
                                                          @RequestParam(required = false) String keyword) {
        return R.ok(emrTemplateService.granteeCandidates(granteeType, keyword));
    }

    // ----- 科室默认模板（988★） -----

    /**
     * 设为科室默认模板。唯一性由 {@code uq_emr_tpl_default} 部分唯一索引在数据库层保证，
     * 不是应用层读-判-写（两位质控同时点会双双通过，之后医生站随机取到其中一张）。
     *
     * @param replace true=先让出原默认位再设（同一事务内，无并发窗口）；false=撞车返 4067
     */
    @PutMapping("/api/emr-templates/{id}/default")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Void> setTemplateDefault(@PathVariable Long id,
                                      @RequestParam(defaultValue = "false") boolean replace,
                                      Authentication auth) {
        emrTemplateService.setDefault(currentUserService.idOf(auth), id, replace);
        return R.ok();
    }

    /** 取消默认 */
    @DeleteMapping("/api/emr-templates/{id}/default")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Void> unsetTemplateDefault(@PathVariable Long id, Authentication auth) {
        emrTemplateService.unsetDefault(currentUserService.idOf(auth), id);
        return R.ok();
    }

    /** 按科室 + 病历类型取默认模板（988★ 的取数口径）。没设默认返回 data=null，不报错。 */
    @GetMapping("/api/emr-templates/default")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Map<String, Object>> defaultTemplate(@RequestParam Long deptId,
                                                  @RequestParam String recordType,
                                                  Authentication auth) {
        return R.ok(emrTemplateService.defaultTemplate(currentUserService.idOf(auth), deptId, recordType));
    }

    // ----- 病历存为模板（1095★）/ 按既往病历建新病历（1079★） -----

    /**
     * 把一份既有病历的正文存成模板。返回 {@code {id, truncated, contentLength, sourceLength}}——
     * {@code emr_template.content} 仍是 varchar(4000)（本版刻意不动列宽），
     * 超长时截断并显式回 {@code truncated=true}，<b>不做无声截断</b>。
     */
    @PostMapping("/api/emr-templates/from-record")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Map<String, Object>> saveRecordAsTemplate(@RequestBody EmrTemplateService.FromRecordReq req,
                                                       Authentication auth) {
        return R.ok(emrTemplateService.saveAsTemplate(currentUserService.idOf(auth), req));
    }

    /**
     * 该患者的既往病历清单（1079★ 的选择列表）。
     *
     * <p><b>这里只有"取正文"这半条路</b>：医生挑一份 → 取正文 → 在编辑器里改 →
     * <b>仍走既有病历保存端点写入</b>。本车道没有、也不许有任何新的写病历路径
     * （零核心写路径改动是 v45 的硬约束）。
     */
    @GetMapping("/api/emr-templates/prior-records")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<List<Map<String, Object>>> priorRecords(@RequestParam Long patientId,
                                                     @RequestParam(required = false) Integer limit) {
        return R.ok(emrTemplateService.priorRecords(patientId, limit));
    }

    /** 取一份既往病历的正文。{@code patientId} 是归属校验参数不是过滤条件：不属于该患者一律 4068。 */
    @GetMapping("/api/emr-templates/prior-records/content")
    @PreAuthorize(EMR_TPL_ROLES)
    public R<Map<String, Object>> priorRecordContent(@RequestParam Long patientId,
                                                     @RequestParam String source,
                                                     @RequestParam Long recordId) {
        return R.ok(emrTemplateService.priorRecordContent(patientId, source, recordId));
    }
}
