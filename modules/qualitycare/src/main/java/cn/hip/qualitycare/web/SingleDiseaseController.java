package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 二十三期：单病种管理——病种定义与上报数据集 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/quality/single-disease")
public class SingleDiseaseController {

    private final JdbcTemplate jdbc;

    @GetMapping("/defs")
    public R<List<Map<String, Object>>> defs() {
        return R.ok(jdbc.queryForList("select * from sd_disease_def order by code"));
    }

    /** 入组建议：出院诊断 ICD 前缀命中病种、尚未建卡的住院 */
    @GetMapping("/candidates")
    public R<List<Map<String, Object>>> candidates() {
        return R.ok(jdbc.queryForList("""
                select a.id as admission_id, a.admission_no, p.name as patient_name,
                       coalesce(a.discharge_diag_icd, a.admit_diag_icd) as admit_diag_icd, a.admit_diag_name, d.code as disease_code, d.name as disease_name
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                join sd_disease_def d on coalesce(a.discharge_diag_icd, a.admit_diag_icd) like d.icd_prefix || '%'
                where not exists (select 1 from sd_case_report c
                                  where c.admission_id = a.id and c.disease_code = d.code)
                order by a.id desc limit 100
                """));
    }

    public record CaseReq(Long admissionId, String diseaseCode, String dataset) {}

    @PostMapping("/cases")
    public R<Void> createCase(@RequestBody CaseReq req) {
        Integer dup = jdbc.queryForObject(
                "select count(*) from sd_case_report where admission_id = ? and disease_code = ?",
                Integer.class, req.admissionId(), req.diseaseCode());
        if (dup != null && dup > 0) return R.fail(4540, "该住院已建此病种上报卡");
        jdbc.update("insert into sd_case_report(admission_id, disease_code, dataset) values (?,?,?)",
                req.admissionId(), req.diseaseCode(), req.dataset());
        return R.ok();
    }

    @PutMapping("/cases/{id}/report")
    public R<Void> report(@PathVariable Long id) {
        int n = jdbc.update("""
                update sd_case_report set status = 'REPORTED', reported_at = now()
                where id = ? and status = 'DRAFT' and dataset is not null and dataset <> ''
                """, id);
        return n == 0 ? R.fail(4541, "上报卡不存在/已上报/数据集为空") : R.ok();
    }

    @GetMapping("/cases")
    public R<List<Map<String, Object>>> cases() {
        return R.ok(jdbc.queryForList("""
                select c.*, d.name as disease_name, p.name as patient_name, a.admission_no
                from sd_case_report c
                join sd_disease_def d on d.code = c.disease_code
                join inp_admission a on a.id = c.admission_id
                join empi_patient p on p.id = a.patient_id
                order by c.id desc limit 100
                """));
    }
}
