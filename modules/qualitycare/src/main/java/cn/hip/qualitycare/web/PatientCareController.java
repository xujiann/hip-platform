package cn.hip.qualitycare.web;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 十期：随访、满意度、院内会诊、临床路径、健康体检 */
@RestController
@RequiredArgsConstructor
public class PatientCareController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final InpatientService inpatientService;
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
    public R<Object> applyPathway(@PathVariable Long id, @PathVariable Long admissionId, Authentication auth) {
        var rows = jdbc.queryForList("select items from cp_pathway_template where id = ?", id);
        if (rows.isEmpty()) return R.fail(9904, "路径模板不存在");
        List<InpatientService.OrderLine> lines = objectMapper.readValue(
                (String) rows.get(0).get("items"), new TypeReference<>() {});
        try {
            var orders = inpatientService.createOrders(admissionId, lines, currentUserService.idOf(auth));
            return R.ok(Map.of("orders", orders.size()));
        } catch (InpatientService.InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ---- 健康体检 ----
    public record PackageReq(String name, java.math.BigDecimal price, String items) {}

    @PostMapping("/api/exam/packages")
    public R<Void> createPackage(@RequestBody PackageReq req) {
        jdbc.update("insert into pe_exam_package(name, price, items) values (?,?,?)",
                req.name(), req.price(), req.items());
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
    public R<Void> completeExam(@PathVariable Long id, @RequestParam String summary) {
        int n = jdbc.update(
                "update pe_exam_record set status = 'DONE', summary = ? where id = ? and status = 'REGISTERED'",
                summary, id);
        return n == 0 ? R.fail(9905, "体检记录不存在或已完成") : R.ok();
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
