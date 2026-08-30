package cn.hip.inpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
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
    private final CurrentUserService currentUserService;

    public record DiagReq(Long admissionId, String icd, String name) {}

    @PostMapping
    public R<Void> add(@RequestBody DiagReq req, Authentication auth) {
        if (req.icd() == null || req.icd().isBlank()) return R.fail(9014, "ICD 编码必填");
        Integer exists = jdbc.queryForObject("select count(*) from inp_admission where id = ?",
                Integer.class, req.admissionId());
        if (exists == null || exists == 0) return R.fail(9003, "住院记录不存在");
        // v32：对象级归属校验（收尾 P2-3 残留的"记后续"项——doctor_id 早在 V8 已具备，
        // 责任医生模型不再是延后理由）。诊断直接影响 DRG 严重度子分组与医保支付权重，
        // 此前门诊医生 DOCTOR_OUTP 可给**任意**住院病案补录 → 水平越权。
        // ADMIN/QUALITY（编码组）按业务横切所有病案；仅具 DOCTOR_OUTP 的医生只能补录
        // 主管医生为本人的病案。doctor_id 为空（未采集主管）时放行——不能强推一个
        // 从未采集的归属，否则误拒历史/测试数据（admit 的 doctorId 可空）。
        if (!hasCrossDomainRole(auth)) {
            Long ownerId = jdbc.queryForObject(
                    "select doctor_id from inp_admission where id = ?", Long.class, req.admissionId());
            Long selfId = currentUserService.idOf(auth);
            if (ownerId != null && !ownerId.equals(selfId)) {
                return R.fail(9018, "无权操作该病案（非本人主管）");
            }
        }
        try {
            jdbc.update("insert into inp_diagnosis(admission_id, icd, name) values (?,?,?)",
                    req.admissionId(), req.icd(), req.name());
        } catch (DuplicateKeyException e) {
            return R.fail(9015, "该诊断已录入");
        }
        return R.ok();
    }

    /** 跨域角色：ADMIN 与病案编码组 QUALITY 按业务横切所有病案，不受主管归属约束 */
    private static boolean hasCrossDomainRole(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .anyMatch(r -> "ROLE_ADMIN".equals(r) || "ROLE_QUALITY".equals(r));
    }

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam Long admissionId) {
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
