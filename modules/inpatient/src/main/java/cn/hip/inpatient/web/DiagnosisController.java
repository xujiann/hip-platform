package cn.hip.inpatient.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** DRG 深化：住院其他诊断（出院诊断/并发症合并症）补录——DRG 细分组严重程度的判定依据 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/inpatient/diagnoses")
public class DiagnosisController {

    private final JdbcTemplate jdbc;

    public record DiagReq(Long admissionId, String icd, String name) {}

    @PostMapping
    public R<Void> add(@RequestBody DiagReq req) {
        if (req.icd() == null || req.icd().isBlank()) return R.fail(9014, "ICD 编码必填");
        Integer exists = jdbc.queryForObject("select count(*) from inp_admission where id = ?",
                Integer.class, req.admissionId());
        if (exists == null || exists == 0) return R.fail(9003, "住院记录不存在");
        try {
            jdbc.update("insert into inp_diagnosis(admission_id, icd, name) values (?,?,?)",
                    req.admissionId(), req.icd(), req.name());
        } catch (DuplicateKeyException e) {
            return R.fail(9015, "该诊断已录入");
        }
        return R.ok();
    }

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam Long admissionId) {
        return R.ok(jdbc.queryForList(
                "select * from inp_diagnosis where admission_id = ? order by id", admissionId));
    }

    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        int n = jdbc.update("delete from inp_diagnosis where id = ?", id);
        return n == 0 ? R.fail(9016, "诊断不存在") : R.ok();
    }
}
