package cn.hip.qualitycare.web;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.core.common.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 十期：随访、满意度、院内会诊、临床路径、健康体检 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE','QUALITY')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class PatientCareController {

    private final JdbcTemplate jdbc;
    private final cn.hip.qualitycare.service.PatientCareService patientCareService;
    private final cn.hip.qualitycare.service.PathwayService pathwayService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---- 随访 ----
    public record FollowupReq(Long patientId, String topic, String channel, String dueDate) {}

    @PostMapping("/api/patientcare/followups")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")
    public R<Void> createFollowup(@RequestBody FollowupReq req, Authentication auth) {
        patientCareService.createFollowup(req.patientId(),req.topic(),req.channel(),req.dueDate());
        return R.ok();
    }

    @GetMapping("/api/patientcare/followups")
    public R<List<Map<String, Object>>> followups(@RequestParam(defaultValue = "PENDING") String status) {
        return R.ok(patientCareService.followups(status));
    }

    @PutMapping("/api/patientcare/followups/{id}/done")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")
    public R<Void> doneFollowup(@PathVariable Long id, @RequestParam String note) {
        patientCareService.doneFollowup(id,note);return R.ok();
    }

    // ---- 满意度 ----
    public record SatisfactionReq(Long patientId, String source, Integer score, String comment) {}

    @PostMapping("/api/patientcare/satisfaction")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")
    public R<Void> submitSatisfaction(@RequestBody SatisfactionReq req) {
        patientCareService.submitSatisfaction(req.patientId(),req.source(),req.score(),req.comment());
        return R.ok();
    }

    @GetMapping("/api/patientcare/satisfaction/stats")
    public R<Map<String, Object>> satisfactionStats() {
        return R.ok(patientCareService.satisfactionStats());
    }

    // ---- 院内会诊 ----
    public record ConsultReq(Long admissionId, Long toDeptId, String question) {}

    @PostMapping("/api/inpatient/consults")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Void> requestConsult(@RequestBody ConsultReq req, Authentication auth) {
        patientCareService.requestConsult(req.admissionId(),req.toDeptId(),req.question());return R.ok();
    }

    @GetMapping("/api/inpatient/consults")
    public R<List<Map<String, Object>>> consults() {
        return R.ok(patientCareService.consults());
    }

    @PutMapping("/api/inpatient/consults/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Void> completeConsult(@PathVariable Long id, @RequestParam String opinion, Authentication auth) {
        patientCareService.completeConsult(id,opinion);return R.ok();
    }

    // ---- 临床路径 ----
    public record PathwayReq(String name, String icdPrefix, String description,
                             List<InpatientService.OrderLine> items) {}

    @PostMapping("/api/pathways")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")
    @SneakyThrows
    public R<Void> createPathway(@RequestBody PathwayReq req) {
        jdbc.update("insert into cp_pathway_template(name, icd_prefix, description, items) values (?,?,?,?)",
                req.name(), req.icdPrefix(), req.description(), objectMapper.writeValueAsString(req.items()));
        return R.ok();
    }

    @GetMapping("/api/pathways")
    public R<List<Map<String, Object>>> pathways() {
        return R.ok(jdbc.queryForList("select id, name, icd_prefix, description from cp_pathway_template order by id"));
    }

    /** 入径：按模板批量开立住院医嘱 */
    @PostMapping("/api/pathways/{id}/apply/{admissionId}")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Object> applyPathway(@PathVariable Long id, @PathVariable Long admissionId, Authentication auth) {
        try {
            return R.ok(Map.of("orders", pathwayService.apply(id,admissionId)));
        } catch (cn.hip.platform.core.common.HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ---- 三十期：路径执行与变异分析 ----

    @GetMapping("/api/pathways/enrollments")
    public R<List<Map<String, Object>>> enrollments() {
        var args=new java.util.ArrayList<Object>();
        var sql=new StringBuilder("""
                select e.*, t.name as pathway_name, a.admission_no, a.doctor_id, p.name as patient_name,
                       (select count(*) from cp_variance v where v.enrollment_id = e.id) as variances
                from cp_enrollment e
                join cp_pathway_template t on t.id = e.template_id
                join inp_admission a on a.id = e.admission_id
                join empi_patient p on p.id = a.patient_id
                where 1=1
                """);
        pathwayService.readScope().appendSql(sql,args);
        sql.append(" order by e.id desc limit 100");
        return R.ok(jdbc.queryForList(sql.toString(),args.toArray()));
    }

    public record VarianceReq(Integer dayNo, String varType, String reason) {}

    /** 变异登记：EXIT 同步退出路径 */
    @PostMapping("/api/pathways/enrollments/{id}/variances")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Void> addVariance(@PathVariable Long id, @RequestBody VarianceReq req) {
        if (!List.of("DELAY", "EXIT", "OTHER").contains(req.varType())) return R.fail(9907, "类型只能为 DELAY/EXIT/OTHER");
        try {pathwayService.addVariance(id,req.dayNo(),req.varType(),req.reason());return R.ok();}
        catch(cn.hip.platform.core.common.HipBizException e){return R.fail(e.code,e.getMessage());}
    }

    @PutMapping("/api/pathways/enrollments/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Void> completeEnrollment(@PathVariable Long id) {
        try {pathwayService.complete(id);return R.ok();}
        catch(cn.hip.platform.core.common.HipBizException e){return R.fail(e.code,e.getMessage());}
    }

    /** 路径变异统计：完成率 / 退出率 / 变异率（有变异记录的入径占比） */
    @GetMapping("/api/pathways/variance-stats")
    public R<List<Map<String, Object>>> varianceStats() {
        var args=new java.util.ArrayList<Object>();
        var sql=new StringBuilder("""
                select t.name as pathway_name, count(*) as enrolled,
                       count(*) filter (where e.status = 'COMPLETED') as completed,
                       count(*) filter (where e.status = 'EXITED') as exited,
                       count(*) filter (where exists (select 1 from cp_variance v where v.enrollment_id = e.id)) as varied,
                       round(count(*) filter (where exists (select 1 from cp_variance v where v.enrollment_id = e.id))::numeric
                             / count(*) * 100, 1) as variance_rate
                from cp_enrollment e
                join cp_pathway_template t on t.id = e.template_id
                join inp_admission a on a.id=e.admission_id
                where 1=1
                """);
        pathwayService.readScope().appendSql(sql,args);
        sql.append(" group by t.name order by enrolled desc");
        return R.ok(jdbc.queryForList(sql.toString(),args.toArray()));
    }

    // ---- 健康体检 ----
    public record PackageReq(String name, java.math.BigDecimal price, String items, String hiddenItems) {}

    @PostMapping("/api/exam/packages")
    @PreAuthorize("hasAnyRole('ADMIN','QUALITY')")
    public R<Void> createPackage(@RequestBody PackageReq req) {
        // 1.0.1（1938）：hiddenItems 为不进入总检结果的项目（逗号分隔），须为套餐项目子集
        jdbc.update("insert into pe_exam_package(name, price, items, hidden_items) values (?,?,?,?)",
                req.name(), req.price(), req.items(), req.hiddenItems() == null ? "" : req.hiddenItems());
        return R.ok();
    }

    @GetMapping("/api/exam/packages")
    public R<List<Map<String, Object>>> packages() {
        return R.ok(jdbc.queryForList("select * from pe_exam_package where enabled order by id"));
    }

    @PostMapping("/api/exam/records")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")
    public R<Void> registerExam(@RequestParam Long patientId, @RequestParam Long packageId,
                                @RequestParam(required = false) Long examinerId) {
        patientCareService.registerExam(patientId,packageId,examinerId);
        return R.ok();
    }

    @PutMapping("/api/exam/records/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Void> completeExam(@PathVariable Long id, @RequestParam String summary,
                                @RequestParam(defaultValue = "false") boolean abnormal) {
        patientCareService.completeExam(id,summary,abnormal);return R.ok();
    }

    public record ExaminerReq(Long examinerId) {}
    @PutMapping("/api/exam/records/{id}/examiner")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> assignExaminer(@PathVariable Long id,@RequestBody ExaminerReq req){
        patientCareService.assignExaminer(id,req.examinerId());return R.ok();
    }

    // ---- 三十期：团检 ----
    public record GroupReq(String unitName, String contact, Long packageId, List<String> memberNames,Long examinerId) {}

    /**
     * 单位团检建档：批量建患者 + 体检记录。
     *
     * <p>有意保持单事务（1.2.0 审阅裁决）：成员无证件号、建档不幂等，
     * 分批部分提交会让"中断重跑"产生重复患者档案；全回滚则整批重试安全。
     * 单事务代价可控（2000 人约数千条简单 insert），风险只在超大名单——上限拦截。
     */
    @PostMapping("/api/exam/groups")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")
    public R<Map<String, Object>> createGroup(@RequestBody GroupReq req) {
        return R.ok(patientCareService.createGroup(req.unitName(),req.contact(),req.packageId(),req.memberNames(),req.examinerId()));
    }

    /** 团检汇总：进度与异常率横向对比 */
    @GetMapping("/api/exam/groups")
    public R<List<Map<String, Object>>> groups() {
        return R.ok(patientCareService.groups());
    }

    @GetMapping("/api/exam/groups/{id}/records")
    public R<List<Map<String, Object>>> groupRecords(@PathVariable Long id) {
        return R.ok(patientCareService.groupRecords(id));
    }

    @GetMapping("/api/exam/records")
    public R<List<Map<String, Object>>> examRecords() {
        return R.ok(patientCareService.exams());
    }
}
