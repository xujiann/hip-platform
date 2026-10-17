package cn.hip.inpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.inpatient.service.InpatientDiagnosisService;
import cn.hip.inpatient.service.InpatientReadAccess;
import cn.hip.inpatient.service.InpatientService.InpException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** DRG 深化：住院其他诊断（出院诊断/并发症合并症）补录——DRG 细分组严重程度的判定依据 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/inpatient/diagnoses")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")   // 1.0.9：权限清点补齐
public class DiagnosisController {

    private final JdbcTemplate jdbc;
    private final InpatientDiagnosisService diagnoses;
    private final InpatientReadAccess readAccess;

    public record DiagReq(Long admissionId, String icd, String name) {}

    @PostMapping
    public R<Void> add(@RequestBody DiagReq req, Authentication auth) {
        if (req.icd() == null || req.icd().isBlank()) return R.fail(9014, "ICD 编码必填");
        try {
            return diagnoses.add(req.admissionId(), req.icd(), req.name())
                    ? R.ok() : R.fail(9015, "该诊断已录入");
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam Long admissionId) {
        readAccess.assertCanReadCoding(admissionId);
        return R.ok(jdbc.queryForList(
                "select * from inp_diagnosis where admission_id = ? order by id", admissionId));
    }

    // 删除诊断影响 DRG 严重度子分组与医保支付权重（上线前审查 P2-3）：
    // 收窄到管理员+编码组，移除门诊医生的删除权。删除经 AuditLogFilter 自动留痕。
    // 删除本就限 ADMIN/QUALITY（编码组横切），无 DOCTOR_OUTP 触达，故不需 add() 的对象级归属校验。
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','QUALITY')")
    public R<Void> remove(@PathVariable Long id) {
        int n = jdbc.update("delete from inp_diagnosis where id = ?", id);
        return n == 0 ? R.fail(9016, "诊断不存在") : R.ok();
    }
}
