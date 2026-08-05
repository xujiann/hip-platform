package cn.hip.outpatient.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 二十八期：CDSS——规则库查询、提醒留痕、诊断建议（真实 CDSS 知识库接入后替换规则数据源） */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cdss")
public class CdssController {

    private final JdbcTemplate jdbc;

    /** 规则库总览 */
    @GetMapping("/rules")
    public R<Map<String, Object>> rules() {
        var m = new LinkedHashMap<String, Object>();
        m.put("ddi", jdbc.queryForList("select * from cdss_ddi_rule order by severity, id"));
        m.put("dose", jdbc.queryForList("select * from cdss_dose_rule order by id"));
        m.put("age", jdbc.queryForList("select * from cdss_age_rule order by id"));
        m.put("suggestions", jdbc.queryForList("select * from cdss_suggestion order by icd_prefix"));
        return R.ok(m);
    }

    public record DdiReq(String drugA, String drugB, String severity, String message) {}

    /** 维护 DDI 规则 */
    @PostMapping("/ddi-rules")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> addDdi(@RequestBody DdiReq req) {
        if (!"FORBID".equals(req.severity()) && !"CAUTION".equals(req.severity())) {
            return R.fail(4650, "级别只能为 FORBID/CAUTION");
        }
        jdbc.update("insert into cdss_ddi_rule(drug_a, drug_b, severity, message) values (?,?,?,?)",
                req.drugA(), req.drugB(), req.severity(), req.message());
        return R.ok();
    }

    /** 提醒留痕 */
    @GetMapping("/alerts")
    public R<List<Map<String, Object>>> alerts() {
        return R.ok(jdbc.queryForList("""
                select al.*, p.name as patient_name from cdss_alert al
                join outp_registration r on r.id = al.registration_id
                join empi_patient p on p.id = r.patient_id
                order by al.id desc limit 200
                """));
    }

    /** 诊断建议：按主诊断 ICD 前缀 */
    @GetMapping("/suggestions")
    public R<List<Map<String, Object>>> suggestions(@RequestParam String icd) {
        return R.ok(jdbc.queryForList("""
                select * from cdss_suggestion where ? like icd_prefix || '%' order by icd_prefix
                """, icd));
    }
}
