package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车道B 临床收尾②：病历复印（病案室对患者的法定职能，此前零实现）。
 * 生命周期：受理(APPLIED) → 登记(REGISTERED，生成复印登记号) → 出复印件(ISSUED，打印时盖"复印件"水印)。
 * 法定留痕：谁申请(applicant_*)、复印了什么范围(copy_scope)、用途(purpose)、谁经办(operator_id)、何时(时间戳)。
 * 复印件打印数据集复用住院病案组装（病案首页头 + 病历正文），叠加复印登记号与"复印件"水印文案。
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','QUALITY')")   // 病案室：管理员 + 质控角色
@RequiredArgsConstructor
public class EmrCopyController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public record ApplyReq(Long patientId, Long admissionId, String applicantName, String applicantRelation,
                           String applicantIdNo, String copyScope, String purpose, Integer copies) {}

    /** 受理：登记复印申请（患者/申请人/复印范围/用途/份数），状态 APPLIED。
     *  病案室按住院病案受理时通常只选到 admission（前端 /quality/med-records 无 patient_id），
     *  故 patientId 可空、由 admissionId 反查；两者皆空才报错。 */
    @PostMapping("/api/quality/emr-copy")
    public R<Map<String, Object>> apply(@RequestBody ApplyReq req, Authentication auth) {
        Long patientId = req.patientId();
        // 只给了住院病案：由病案反查患者（病案室常见入口）
        if (patientId == null && req.admissionId() != null) {
            var rows = jdbc.queryForList("select patient_id from inp_admission where id = ?", req.admissionId());
            if (rows.isEmpty()) return R.fail(9810, "住院病案不存在");
            patientId = ((Number) rows.get(0).get("patient_id")).longValue();
        }
        if (patientId == null) return R.fail(9810, "患者或住院病案必填");
        Integer pe = jdbc.queryForObject("select count(*) from empi_patient where id = ?", Integer.class, patientId);
        if (pe == null || pe == 0) return R.fail(9810, "患者不存在");
        // 患者与病案都给了时校验一致性（防选错病案）
        if (req.patientId() != null && req.admissionId() != null) {
            Integer ae = jdbc.queryForObject(
                    "select count(*) from inp_admission where id = ? and patient_id = ?",
                    Integer.class, req.admissionId(), req.patientId());
            if (ae == null || ae == 0) return R.fail(9810, "住院病案不存在或与患者不匹配");
        }
        if (isBlank(req.applicantName()) || isBlank(req.copyScope()) || isBlank(req.purpose())) {
            return R.fail(9813, "申请人、复印范围、用途均必填");
        }
        int copies = req.copies() == null ? 1 : req.copies();
        if (copies < 1) return R.fail(9814, "份数须≥1");
        Long id = jdbc.queryForObject("""
                insert into emr_copy_request(patient_id, admission_id, applicant_name, applicant_relation,
                        applicant_id_no, copy_scope, purpose, copies, applied_by)
                values (?,?,?,?,?,?,?,?,?)
                returning id
                """, Long.class, patientId, req.admissionId(), req.applicantName(), req.applicantRelation(),
                req.applicantIdNo(), req.copyScope(), req.purpose(), copies, currentUserService.idOf(auth));
        return R.ok(Map.of("id", id, "status", "APPLIED"));
    }

    /** 登记/审批：生成复印登记号（reg_no），状态 APPLIED → REGISTERED */
    @PutMapping("/api/quality/emr-copy/{id}/register")
    public R<Map<String, Object>> register(@PathVariable Long id, Authentication auth) {
        // 登记号 = FY + 业务日期(会话时区=北京) + 6 位补零 id，含 id 天然唯一（uq_emr_copy_reg_no）
        int n = jdbc.update("""
                update emr_copy_request
                set status = 'REGISTERED', registered_at = now(), operator_id = ?,
                    reg_no = 'FY' || to_char(now(), 'YYYYMMDD') || '-' || lpad(id::text, 6, '0')
                where id = ? and status = 'APPLIED'
                """, currentUserService.idOf(auth), id);
        if (n == 0) return notFoundOrState(id, "仅受理状态可登记");
        String regNo = jdbc.queryForObject("select reg_no from emr_copy_request where id = ?", String.class, id);
        return R.ok(Map.of("regNo", regNo, "status", "REGISTERED"));
    }

    /** 出复印件：状态 REGISTERED → ISSUED，落经办人与出件时间（供打印复印件后确认出件） */
    @PutMapping("/api/quality/emr-copy/{id}/issue")
    public R<Void> issue(@PathVariable Long id, Authentication auth) {
        int n = jdbc.update("""
                update emr_copy_request set status = 'ISSUED', issued_at = now(),
                       operator_id = coalesce(operator_id, ?)
                where id = ? and status = 'REGISTERED'
                """, currentUserService.idOf(auth), id);
        if (n == 0) {
            Integer exists = jdbc.queryForObject(
                    "select count(*) from emr_copy_request where id = ?", Integer.class, id);
            return (exists == null || exists == 0) ? R.fail(9811, "复印申请不存在")
                    : R.fail(9812, "仅登记后可出复印件");
        }
        return R.ok();
    }

    /** 病案室复印队列（可按状态过滤） */
    @GetMapping("/api/quality/emr-copy")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        String st = status == null ? "" : status.trim();
        return R.ok(jdbc.queryForList("""
                select c.id, c.reg_no, c.status, c.applicant_name, c.applicant_relation, c.copy_scope,
                       c.purpose, c.copies, c.applied_at, c.registered_at, c.issued_at,
                       p.name as patient_name, a.admission_no
                from emr_copy_request c
                join empi_patient p on p.id = c.patient_id
                left join inp_admission a on a.id = c.admission_id
                where (? = '' or c.status = ?)
                order by c.id desc limit 200
                """, st, st));
    }

    /**
     * 复印件打印数据集：申请留痕(登记号/申请人/范围/用途/经办) + 病案首页头 + 病历正文，
     * 叠加"复印件"水印文案。须已登记(REGISTERED/ISSUED)——未登记不出件。允许重复取（补打）。
     */
    @GetMapping("/api/quality/emr-copy/{id}/document")
    public R<Map<String, Object>> document(@PathVariable Long id) {
        var reqRows = jdbc.queryForList("""
                select c.id, c.reg_no, c.status, c.applicant_name, c.applicant_relation, c.applicant_id_no,
                       c.copy_scope, c.purpose, c.copies, c.applied_at, c.registered_at, c.issued_at,
                       c.patient_id, c.admission_id,
                       p.name as patient_name, u.real_name as operator_name, ap.real_name as applied_by_name
                from emr_copy_request c
                join empi_patient p on p.id = c.patient_id
                left join sys_user u on u.id = c.operator_id
                left join sys_user ap on ap.id = c.applied_by
                where c.id = ?
                """, id);
        if (reqRows.isEmpty()) return R.fail(9811, "复印申请不存在");
        var reqRow = reqRows.get(0);
        if ("APPLIED".equals(reqRow.get("status"))) return R.fail(9812, "请先登记后再出复印件");

        var doc = new LinkedHashMap<String, Object>();
        doc.put("watermark", "复印件");   // 法定水印文案，前端叠加打印
        doc.put("request", reqRow);
        Object admObj = reqRow.get("admission_id");
        if (admObj != null) {
            Long admissionId = ((Number) admObj).longValue();
            var head = jdbc.queryForList("""
                    select a.admission_no, a.admit_at, a.discharged_at, a.status,
                           a.admit_diag_name, a.discharge_diag_name,
                           p.name as patient_name, p.patient_no, p.sex, p.birth_date,
                           d.name as dept_name, w.name as ward_name, b.bed_no
                    from inp_admission a
                    join empi_patient p on p.id = a.patient_id
                    left join sys_dept d on d.id = a.dept_id
                    left join sys_dept w on w.id = a.ward_id
                    left join inp_bed b on b.id = a.bed_id
                    where a.id = ?
                    """, admissionId);
            if (!head.isEmpty()) doc.put("admission", head.get(0));
            // 病历正文（复印内容）——住院病历按时间正序
            doc.put("records", jdbc.queryForList("""
                    select record_type, title, content, created_at, (signature is not null) as signed
                    from inp_medical_record where admission_id = ? order by id
                    """, admissionId));
        }
        return R.ok(doc);
    }

    /** 更新失败时区分"不存在"与"状态不允许" */
    private R<Map<String, Object>> notFoundOrState(Long id, String stateMsg) {
        Integer exists = jdbc.queryForObject("select count(*) from emr_copy_request where id = ?", Integer.class, id);
        return (exists == null || exists == 0) ? R.fail(9811, "复印申请不存在") : R.fail(9812, stateMsg);
    }
}
