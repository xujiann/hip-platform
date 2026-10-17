package cn.hip.inpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.integration.signature.SignatureAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v34：知情同意书 / 授权委托书。医患双签（患者签 → 医师 CA 签），SIGNED 为有效终态。
 * 手术/输血/自费开单侧按 emr.gate.consent.* 校验是否存在 SIGNED 有效同意书（默认 warn 不硬拦）。
 */
@RestController
@RequestMapping("/api/emr/consents")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")
@RequiredArgsConstructor
public class ConsentController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final SignatureAdapter signatureAdapter;
    private final cn.hip.inpatient.service.ConsentAccess consentAccess;
    private final cn.hip.inpatient.service.InpatientReadAccess readAccess;
    private final cn.hip.inpatient.service.InpatientWriteAccess writeAccess;

    private static final Set<String> TYPES = Set.of(
            "SURGERY", "TRANSFUSION", "ANESTHESIA", "SPECIAL_EXAM", "SELF_PAY", "PROXY");

    public record ConsentReq(Long admissionId, Long registrationId, String consentType, String title,
                             String content, String agentName, String agentRelation, String agentReason,
                             Long refBizId) {}

    @PostMapping
    @Transactional
    public R<Map<String, Object>> create(@RequestBody ConsentReq req, Authentication auth) {
        if (req.consentType() == null || !TYPES.contains(req.consentType())) {
            return R.fail(9110, "同意书类型非法");
        }
        if ((req.admissionId() == null) == (req.registrationId() == null)) {
            return R.fail(9110, "须且仅能指定住院号或门诊就诊");
        }
        if (req.content() == null || req.content().isBlank()) return R.fail(9111, "同意书内容不能为空");
        // 授权委托（PROXY）或委托人代签：委托人信息必填
        if ("PROXY".equals(req.consentType())
                && (blank(req.agentName()) || blank(req.agentRelation()) || blank(req.agentReason()))) {
            return R.fail(9113, "委托人姓名/关系/原因必填");
        }
        if (req.admissionId() != null) writeAccess.assertCanCoordinateCare(req.admissionId());
        var kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    insert into emr_consent(admission_id, registration_id, consent_type, title, content,
                            agent_name, agent_relation, agent_reason, ref_biz_id, created_by, status)
                    values (?,?,?,?,?,?,?,?,?,?, 'DRAFT')
                    """, new String[]{"id"});
            ps.setObject(1, req.admissionId());
            ps.setObject(2, req.registrationId());
            ps.setString(3, req.consentType());
            ps.setString(4, req.title() == null || req.title().isBlank() ? typeCn(req.consentType()) + "知情同意书" : req.title());
            ps.setString(5, req.content());
            ps.setString(6, req.agentName());
            ps.setString(7, req.agentRelation());
            ps.setString(8, req.agentReason());
            ps.setObject(9, req.refBizId());
            ps.setObject(10, currentUserService.idOf(auth));
            return ps;
        }, kh);
        return R.ok(Map.of("id", kh.getKey().longValue()));
    }

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long admissionId,
                                             @RequestParam(required = false) Long registrationId) {
        if (admissionId != null) readAccess.assertCanRead(admissionId);
        var sql = new StringBuilder("select c.* from emr_consent c left join inp_admission a on a.id=c.admission_id where 1=1");
        var args = new java.util.ArrayList<Object>();
        if (admissionId != null) {sql.append(" and c.admission_id=?");args.add(admissionId);}
        else if (registrationId != null) {sql.append(" and c.registration_id=?");args.add(registrationId);}
        var scope=readAccess.clinicalScope();
        if (!scope.all()) {
            // Outpatient-only documents retain their contract; dual-scope history is inpatient-scoped.
            sql.append(" and (c.admission_id is null or (a.id is not null");
            scope.appendSql(sql,args);
            sql.append("))");
        }
        sql.append(" order by c.id desc");
        if(admissionId==null && registrationId==null)sql.append(" limit 100");
        return R.ok(jdbc.queryForList(sql.toString(),args.toArray()));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(consentAccess.read(id));
    }

    public record PatientSignReq(String patientSign) {}

    /** 患者（或委托人）签名 */
    @PostMapping("/{id}/patient-sign")
    @Transactional
    public R<Void> patientSign(@PathVariable Long id, @RequestBody PatientSignReq req) {
        if (blank(req.patientSign())) return R.fail(9111, "患者签名不能为空");
        consentAccess.lockForWrite(id, false);
        int n = jdbc.update("""
                update emr_consent set patient_sign = ?, patient_signed_at = now(), status = 'PATIENT_SIGNED'
                where id = ? and status = 'DRAFT'
                """, req.patientSign(), id);
        if (n == 0) {
            return existsWithStatus(id) ? R.fail(9114, "当前状态不允许患者签名") : R.fail(9112, "同意书不存在");
        }
        return R.ok();
    }

    /** 医师 CA 签名（复用 SignatureAdapter）；须患者已签，双签齐全后 SIGNED 为有效终态 */
    @PostMapping("/{id}/doctor-sign")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    @Transactional
    public R<Void> doctorSign(@PathVariable Long id, Authentication auth) {
        var row = consentAccess.lockForWrite(id, true);
        if (!"PATIENT_SIGNED".equals(row.get("status"))) {
            return R.fail(9114, "须患者先签名后医师方可签名");
        }
        Long me = currentUserService.idOf(auth);
        var sig = signatureAdapter.sign((String) row.get("content"), me);
        if (!sig.ok()) return R.fail(9115, "CA 签名失败: " + sig.message());
        int n = jdbc.update("""
                update emr_consent set doctor_id = ?, doctor_sign = ?, doctor_signed_at = now(), status = 'SIGNED'
                where id = ? and status = 'PATIENT_SIGNED'
                """, me, sig.signature(), id);
        return n == 0 ? R.fail(9114, "当前状态不允许医师签名") : R.ok();
    }

    /** 作废（未生效或管理员）：SIGNED 的须管理员才能作废 */
    @PutMapping("/{id}/revoke")
    @Transactional
    public R<Void> revoke(@PathVariable Long id, Authentication auth) {
        consentAccess.lockForWrite(id, false);
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        String guard = admin ? " and status <> 'REVOKED'" : " and status in ('DRAFT','PATIENT_SIGNED')";
        int n = jdbc.update("update emr_consent set status = 'REVOKED', revoked_at = now() where id = ?" + guard, id);
        if (n == 0) {
            return existsWithStatus(id) ? R.fail(9114, "已生效同意书须管理员作废") : R.fail(9112, "同意书不存在");
        }
        return R.ok();
    }

    private boolean existsWithStatus(Long id) {
        return !jdbc.queryForList("select 1 from emr_consent where id = ?", id).isEmpty();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String typeCn(String t) {
        return switch (t) {
            case "SURGERY" -> "手术";
            case "TRANSFUSION" -> "输血";
            case "ANESTHESIA" -> "麻醉";
            case "SPECIAL_EXAM" -> "特殊检查";
            case "SELF_PAY" -> "自费";
            case "PROXY" -> "授权委托";
            default -> "";
        };
    }
}
