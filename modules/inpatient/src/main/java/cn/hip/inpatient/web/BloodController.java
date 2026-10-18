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
    private final cn.hip.inpatient.service.InpatientReadAccess readAccess;
    private final cn.hip.inpatient.service.InpatientWriteAccess writeAccess;
    private final cn.hip.platform.core.service.ConfigReader configReader;

    private static final Set<String> PRODUCTS = Set.of("RBC", "PLASMA", "PLT");

    public record ApplyReq(Long admissionId, String product, Integer volumeMl, String reason) {}

    @PostMapping("/applies")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    @org.springframework.transaction.annotation.Transactional
    public R<Map<String, Object>> apply(@RequestBody ApplyReq req, Authentication auth) {
        if (req.product() == null || !PRODUCTS.contains(req.product())) return R.fail(4530, "血制品只能为 RBC/PLASMA/PLT");
        if (req.volumeMl() == null || req.volumeMl() <= 0) return R.fail(4530, "用血量必须大于 0");
        var admission = jdbc.queryForList("select status from inp_admission where id=? for update",req.admissionId());
        if(admission.isEmpty() || !"IN_HOSPITAL".equals(admission.getFirst().get("status"))) return R.fail(4530,"住院记录不存在或已出院");
        writeAccess.assertCanWrite(req.admissionId());
        // v34 知情同意 gate（试点期可配 emr.gate.consent.transfusion，默认 warn 警告放行）
        String warning = null;
        String mode = configReader.get("emr.gate.consent.transfusion", "warn");
        if (!"off".equals(mode)) {
            Integer c = jdbc.queryForObject("""
                    select count(*) from emr_consent where admission_id = ? and consent_type = 'TRANSFUSION'
                      and status = 'SIGNED' and revoked_at is null
                    """, Integer.class, req.admissionId());
            if (c == null || c == 0) {
                if ("block".equals(mode)) return R.fail(9117, "无有效输血知情同意书，不能申请用血");
                warning = "未查到有效输血知情同意书，请及时补录并医患双签";
            }
        }
        jdbc.update("""
                insert into blood_apply(admission_id, product, volume_ml, reason, apply_doctor_id) values (?,?,?,?,?)
                """, req.admissionId(), req.product(), req.volumeMl(), req.reason(), currentUserService.idOf(auth));
        return warning == null ? R.ok(Map.of()) : R.ok(Map.of("warning", warning));
    }

    @PutMapping("/applies/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> review(@PathVariable Long id, @RequestParam boolean approve,
                          @RequestParam(required = false) String note) {
        if(!lockApply(id))return R.fail(4531,"用血申请不存在或状态不允许");
        int n = jdbc.update("""
                update blood_apply set status = ?, review_note = ?, approved_at = now()
                where id = ? and status = 'APPLIED'
                """, approve ? "APPROVED" : "REJECTED", note, id);
        return n == 0 ? R.fail(4531, "申请不存在或已审批") : R.ok();
    }

    @PutMapping("/applies/{id}/issue")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> issue(@PathVariable Long id) {
        if(!lockApply(id))return R.fail(4532,"用血申请不存在或状态不允许");
        int n = jdbc.update(
                "update blood_apply set status = 'ISSUED', issued_at = now() where id = ? and status = 'APPROVED'", id);
        return n == 0 ? R.fail(4532, "须审批通过后方可发血") : R.ok();
    }

    @PutMapping("/applies/{id}/transfuse")
    @PreAuthorize("hasAnyRole('ADMIN','NURSE')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> transfuse(@PathVariable Long id, @RequestParam String note,
                             @RequestParam(required = false) String reactionPlan) {
        if(!lockApply(id))return R.fail(4533,"用血申请不存在或状态不允许");
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
        var sql=new StringBuilder("""
                select b.*, p.name as patient_name, a.admission_no
                from blood_apply b
                join inp_admission a on a.id = b.admission_id
                join empi_patient p on p.id = a.patient_id
                where 1=1
                """);
        var args=new java.util.ArrayList<Object>();
        readAccess.clinicalScope().appendSql(sql,args);
        sql.append(" order by b.id desc limit 100");
        return R.ok(jdbc.queryForList(sql.toString(),args.toArray()));
    }
    private boolean lockApply(Long id){
        var rows=jdbc.queryForList("select admission_id from blood_apply where id=?",id);
        if(rows.isEmpty())return false;
        writeAccess.assertCanCoordinateCare(((Number)rows.getFirst().get("admission_id")).longValue());
        return !jdbc.queryForList("select id from blood_apply where id=? for update",id).isEmpty();
    }

}
