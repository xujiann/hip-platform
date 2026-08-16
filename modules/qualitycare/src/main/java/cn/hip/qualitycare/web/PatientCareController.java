package cn.hip.qualitycare.web;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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
    private final CurrentUserService currentUserService;
    private final InpatientService inpatientService;
    private final cn.hip.platform.empi.service.PatientService patientService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---- 随访 ----
    public record FollowupReq(Long patientId, String topic, String channel, String dueDate) {}

    @PostMapping("/api/patientcare/followups")
    public R<Void> createFollowup(@RequestBody FollowupReq req, Authentication auth) {
        jdbc.update("insert into pc_followup(patient_id, topic, channel, due_date, creator_id) values (?,?,?,?::date,?)",
                req.patientId(), req.topic(), req.channel() == null ? "PHONE" : req.channel(),
                req.dueDate(), currentUserService.idOf(auth));
        return R.ok();
    }

    @GetMapping("/api/patientcare/followups")
    public R<List<Map<String, Object>>> followups(@RequestParam(defaultValue = "PENDING") String status) {
        return R.ok(jdbc.queryForList("""
                select f.id, f.topic, f.channel, f.due_date, f.status, f.result_note,
                       p.name as patient_name, p.phone, p.patient_no
                from pc_followup f join empi_patient p on p.id = f.patient_id
                where f.status = ? order by f.due_date, f.id limit 200
                """, status));
    }

    @PutMapping("/api/patientcare/followups/{id}/done")
    public R<Void> doneFollowup(@PathVariable Long id, @RequestParam String note) {
        int n = jdbc.update(
                "update pc_followup set status = 'DONE', result_note = ?, done_at = now() where id = ? and status = 'PENDING'",
                note, id);
        return n == 0 ? R.fail(9901, "任务不存在或已完成") : R.ok();
    }

    // ---- 满意度 ----
    public record SatisfactionReq(Long patientId, String source, Integer score, String comment) {}

    @PostMapping("/api/patientcare/satisfaction")
    public R<Void> submitSatisfaction(@RequestBody SatisfactionReq req) {
        if (req.score() == null || req.score() < 1 || req.score() > 5) return R.fail(9902, "评分须为 1-5");
        jdbc.update("insert into pc_satisfaction(patient_id, source, score, comment) values (?,?,?,?)",
                req.patientId(), req.source() == null ? "OUTP" : req.source(), req.score(), req.comment());
        return R.ok();
    }

    @GetMapping("/api/patientcare/satisfaction/stats")
    public R<Map<String, Object>> satisfactionStats() {
        return R.ok(Map.of(
                "count", jdbc.queryForObject("select count(*) from pc_satisfaction", Long.class),
                "avgScore", jdbc.queryForObject(
                        "select coalesce(round(avg(score), 2), 0) from pc_satisfaction", Double.class),
                "recent", jdbc.queryForList(
                        "select score, comment, source, created_at from pc_satisfaction order by id desc limit 20")));
    }

    // ---- 院内会诊 ----
    public record ConsultReq(Long admissionId, Long toDeptId, String question) {}

    @PostMapping("/api/inpatient/consults")
    public R<Void> requestConsult(@RequestBody ConsultReq req, Authentication auth) {
        jdbc.update("insert into inp_consult(admission_id, from_doctor_id, to_dept_id, question) values (?,?,?,?)",
                req.admissionId(), currentUserService.idOf(auth), req.toDeptId(), req.question());
        return R.ok();
    }

    @GetMapping("/api/inpatient/consults")
    public R<List<Map<String, Object>>> consults() {
        return R.ok(jdbc.queryForList("""
                select c.id, c.question, c.status, c.opinion, c.created_at,
                       a.admission_no, p.name as patient_name, d.name as to_dept_name
                from inp_consult c
                join inp_admission a on a.id = c.admission_id
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = c.to_dept_id
                order by c.status desc, c.id desc limit 100
                """));
    }

    @PutMapping("/api/inpatient/consults/{id}/complete")
    public R<Void> completeConsult(@PathVariable Long id, @RequestParam String opinion, Authentication auth) {
        int n = jdbc.update("""
                update inp_consult set status = 'DONE', opinion = ?, consultant_id = ?, done_at = now()
                where id = ? and status = 'REQUESTED'
                """, opinion, currentUserService.idOf(auth), id);
        return n == 0 ? R.fail(9903, "会诊单不存在或已完成") : R.ok();
    }

    // ---- 临床路径 ----
    public record PathwayReq(String name, String icdPrefix, String description,
                             List<InpatientService.OrderLine> items) {}

    @PostMapping("/api/pathways")
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
    @SneakyThrows
    @Transactional
    public R<Object> applyPathway(@PathVariable Long id, @PathVariable Long admissionId, Authentication auth) {
        var rows = jdbc.queryForList("select items from cp_pathway_template where id = ?", id);
        if (rows.isEmpty()) return R.fail(9904, "路径模板不存在");
        List<InpatientService.OrderLine> lines = objectMapper.readValue(
                (String) rows.get(0).get("items"), new TypeReference<>() {});
        try {
            // 先占入径记录（借 uq_cp_enroll 唯一约束当锁）再开医嘱：
            // 反序会让双击的两次请求都把路径医嘱计了费，第二次才在约束上失败
            jdbc.update("insert into cp_enrollment(admission_id, template_id) values (?,?)", admissionId, id);
            var orders = inpatientService.createOrders(admissionId, lines, currentUserService.idOf(auth));
            return R.ok(Map.of("orders", orders.size()));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return R.fail(9906, "该住院已入此路径");
        } catch (InpatientService.InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ---- 三十期：路径执行与变异分析 ----

    @GetMapping("/api/pathways/enrollments")
    public R<List<Map<String, Object>>> enrollments() {
        return R.ok(jdbc.queryForList("""
                select e.*, t.name as pathway_name, a.admission_no, p.name as patient_name,
                       (select count(*) from cp_variance v where v.enrollment_id = e.id) as variances
                from cp_enrollment e
                join cp_pathway_template t on t.id = e.template_id
                join inp_admission a on a.id = e.admission_id
                join empi_patient p on p.id = a.patient_id
                order by e.id desc limit 100
                """));
    }

    public record VarianceReq(Integer dayNo, String varType, String reason) {}

    /** 变异登记：EXIT 同步退出路径 */
    @PostMapping("/api/pathways/enrollments/{id}/variances")
    public R<Void> addVariance(@PathVariable Long id, @RequestBody VarianceReq req) {
        if (!List.of("DELAY", "EXIT", "OTHER").contains(req.varType())) return R.fail(9907, "类型只能为 DELAY/EXIT/OTHER");
        Integer active = jdbc.queryForObject(
                "select count(*) from cp_enrollment where id = ? and status = 'ACTIVE'", Integer.class, id);
        if (active == null || active == 0) return R.fail(9908, "入径不存在或已结束");
        jdbc.update("insert into cp_variance(enrollment_id, day_no, var_type, reason) values (?,?,?,?)",
                id, req.dayNo(), req.varType(), req.reason());
        if ("EXIT".equals(req.varType())) {
            jdbc.update("update cp_enrollment set status = 'EXITED', ended_at = now() where id = ?", id);
        }
        return R.ok();
    }

    @PutMapping("/api/pathways/enrollments/{id}/complete")
    public R<Void> completeEnrollment(@PathVariable Long id) {
        int n = jdbc.update(
                "update cp_enrollment set status = 'COMPLETED', ended_at = now() where id = ? and status = 'ACTIVE'", id);
        return n == 0 ? R.fail(9908, "入径不存在或已结束") : R.ok();
    }

    /** 路径变异统计：完成率 / 退出率 / 变异率（有变异记录的入径占比） */
    @GetMapping("/api/pathways/variance-stats")
    public R<List<Map<String, Object>>> varianceStats() {
        return R.ok(jdbc.queryForList("""
                select t.name as pathway_name, count(*) as enrolled,
                       count(*) filter (where e.status = 'COMPLETED') as completed,
                       count(*) filter (where e.status = 'EXITED') as exited,
                       count(*) filter (where exists (select 1 from cp_variance v where v.enrollment_id = e.id)) as varied,
                       round(count(*) filter (where exists (select 1 from cp_variance v where v.enrollment_id = e.id))::numeric
                             / count(*) * 100, 1) as variance_rate
                from cp_enrollment e
                join cp_pathway_template t on t.id = e.template_id
                group by t.name order by enrolled desc
                """));
    }

    // ---- 健康体检 ----
    public record PackageReq(String name, java.math.BigDecimal price, String items, String hiddenItems) {}

    @PostMapping("/api/exam/packages")
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
    public R<Void> registerExam(@RequestParam Long patientId, @RequestParam Long packageId) {
        jdbc.update("insert into pe_exam_record(patient_id, package_id) values (?,?)", patientId, packageId);
        return R.ok();
    }

    @PutMapping("/api/exam/records/{id}/complete")
    public R<Void> completeExam(@PathVariable Long id, @RequestParam String summary,
                                @RequestParam(defaultValue = "false") boolean abnormal) {
        int n = jdbc.update(
                "update pe_exam_record set status = 'DONE', summary = ?, abnormal = ? where id = ? and status = 'REGISTERED'",
                summary, abnormal, id);
        return n == 0 ? R.fail(9905, "体检记录不存在或已完成") : R.ok();
    }

    // ---- 三十期：团检 ----
    public record GroupReq(String unitName, String contact, Long packageId, List<String> memberNames) {}

    /** 单位团检建档：批量建患者 + 体检记录 */
    @PostMapping("/api/exam/groups")
    @Transactional
    public R<Map<String, Object>> createGroup(@RequestBody GroupReq req) {
        if (req.memberNames() == null || req.memberNames().isEmpty()) return R.fail(9909, "团检人员名单不能为空");
        Integer pkg = jdbc.queryForObject("select count(*) from pe_exam_package where id = ? and enabled",
                Integer.class, req.packageId());
        if (pkg == null || pkg == 0) return R.fail(9912, "体检套餐不存在");
        // returning id 取主键：max(id) 在并发建档时会拿到别人的团检号，成员挂错单位
        Long groupId = jdbc.queryForObject("""
                insert into pe_group(unit_name, contact, package_id) values (?,?,?) returning id
                """, Long.class, req.unitName(), req.contact(), req.packageId());
        for (String name : req.memberNames()) {
            var p = new cn.hip.platform.empi.entity.Patient();
            p.setName(name);
            p.setSex("U");
            Long pid = patientService.register(p).getId();
            jdbc.update("insert into pe_exam_record(patient_id, package_id, group_id) values (?,?,?)",
                    pid, req.packageId(), groupId);
        }
        return R.ok(Map.of("groupId", groupId, "members", req.memberNames().size()));
    }

    /** 团检汇总：进度与异常率横向对比 */
    @GetMapping("/api/exam/groups")
    public R<List<Map<String, Object>>> groups() {
        return R.ok(jdbc.queryForList("""
                select g.id, g.unit_name, g.contact, k.name as package_name,
                       count(r.id) as members,
                       count(r.id) filter (where r.status = 'DONE') as done,
                       count(r.id) filter (where r.abnormal) as abnormal_cnt,
                       round(count(r.id) filter (where r.abnormal)::numeric
                             / nullif(count(r.id) filter (where r.status = 'DONE'), 0) * 100, 1) as abnormal_rate
                from pe_group g
                join pe_exam_package k on k.id = g.package_id
                left join pe_exam_record r on r.group_id = g.id
                group by g.id, g.unit_name, g.contact, k.name order by g.id desc
                """));
    }

    @GetMapping("/api/exam/groups/{id}/records")
    public R<List<Map<String, Object>>> groupRecords(@PathVariable Long id) {
        return R.ok(jdbc.queryForList("""
                select r.id, r.status, r.summary, r.abnormal, p.name as patient_name
                from pe_exam_record r join empi_patient p on p.id = r.patient_id
                where r.group_id = ? order by r.id
                """, id));
    }

    @GetMapping("/api/exam/records")
    public R<List<Map<String, Object>>> examRecords() {
        return R.ok(jdbc.queryForList("""
                select r.id, r.status, r.summary, r.created_at,
                       p.name as patient_name, k.name as package_name, k.price
                from pe_exam_record r
                join empi_patient p on p.id = r.patient_id
                join pe_exam_package k on k.id = r.package_id
                order by r.id desc limit 100
                """));
    }
}
