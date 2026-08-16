package cn.hip.inpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 二十三期：合理用血——申请 → 审批 → 发血 → 输血记录 全流程状态机 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/inpatient/blood")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")   // 1.0.9：权限清点补齐
public class BloodController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    private static final Set<String> PRODUCTS = Set.of("RBC", "PLASMA", "PLT");

    public record ApplyReq(Long admissionId, String product, Integer volumeMl, String reason) {}

    @PostMapping("/applies")
    public R<Void> apply(@RequestBody ApplyReq req, Authentication auth) {
        if (!PRODUCTS.contains(req.product())) return R.fail(4530, "血制品只能为 RBC/PLASMA/PLT");
        if (req.volumeMl() == null || req.volumeMl() <= 0) return R.fail(4530, "用血量必须大于 0");
        Integer inHosp = jdbc.queryForObject(
                "select count(*) from inp_admission where id = ? and status = 'IN_HOSPITAL'",
                Integer.class, req.admissionId());
        if (inHosp == null || inHosp == 0) return R.fail(4530, "住院记录不存在或已出院");
        jdbc.update("""
                insert into blood_apply(admission_id, product, volume_ml, reason, apply_doctor_id) values (?,?,?,?,?)
                """, req.admissionId(), req.product(), req.volumeMl(), req.reason(), currentUserService.idOf(auth));
        return R.ok();
    }

    @PutMapping("/applies/{id}/review")
    public R<Void> review(@PathVariable Long id, @RequestParam boolean approve,
                          @RequestParam(required = false) String note) {
        int n = jdbc.update("""
                update blood_apply set status = ?, review_note = ?, approved_at = now()
                where id = ? and status = 'APPLIED'
                """, approve ? "APPROVED" : "REJECTED", note, id);
        return n == 0 ? R.fail(4531, "申请不存在或已审批") : R.ok();
    }

    @PutMapping("/applies/{id}/issue")
    public R<Void> issue(@PathVariable Long id) {
        int n = jdbc.update(
                "update blood_apply set status = 'ISSUED', issued_at = now() where id = ? and status = 'APPROVED'", id);
        return n == 0 ? R.fail(4532, "须审批通过后方可发血") : R.ok();
    }

    @PutMapping("/applies/{id}/transfuse")
    public R<Void> transfuse(@PathVariable Long id, @RequestParam String note,
                             @RequestParam(required = false) String reactionPlan) {
        // 1.0.1（1814）：不良反应处置方案从字典选择留痕
        int n = jdbc.update("""
                update blood_apply set status = 'TRANSFUSED', transfusion_note = ?, reaction_plan = ?, transfused_at = now()
                where id = ? and status = 'ISSUED'
                """, note, reactionPlan, id);
        return n == 0 ? R.fail(4533, "须发血后方可记录输血") : R.ok();
    }

    /** 1.0.1（1814）：输血不良反应处置方案字典 */
    @GetMapping("/reaction-plans")
    public R<List<Map<String, Object>>> reactionPlans() {
        return R.ok(jdbc.queryForList("select id, name from blood_reaction_plan where enabled order by id"));
    }

    @GetMapping("/applies")
    public R<List<Map<String, Object>>> applies() {
        return R.ok(jdbc.queryForList("""
                select b.*, p.name as patient_name, a.admission_no
                from blood_apply b
                join inp_admission a on a.id = b.admission_id
                join empi_patient p on p.id = a.patient_id
                order by b.id desc limit 100
                """));
    }
}
