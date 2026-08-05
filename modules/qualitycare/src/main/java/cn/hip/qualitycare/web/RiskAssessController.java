package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 三十九期：护理风险评估——Braden 压力性损伤 / Morse 跌倒，阈值自动分级，多次评估成趋势 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/nursing/risk-assess")
public class RiskAssessController {

    private final JdbcTemplate jdbc;

    public record AssessReq(Long admissionId, String assessType, Integer score, String note) {}

    /** Braden 6-23 分（≤12 高危 / 13-14 中危 / ≥15 低危）；Morse 0-125（≥45 高危 / 25-44 中危 / <25 低危） */
    @PostMapping
    public R<Map<String, Object>> assess(@RequestBody AssessReq req, Authentication auth) {
        Integer inHosp = jdbc.queryForObject(
                "select count(*) from inp_admission where id = ? and status = 'IN_HOSPITAL'",
                Integer.class, req.admissionId());
        if (inHosp == null || inHosp == 0) return R.fail(4780, "住院记录不存在或已出院");
        String level;
        if ("BRADEN".equals(req.assessType())) {
            if (req.score() == null || req.score() < 6 || req.score() > 23) return R.fail(4781, "Braden 评分范围 6-23");
            level = req.score() <= 12 ? "HIGH" : req.score() <= 14 ? "MID" : "LOW";
        } else if ("MORSE".equals(req.assessType())) {
            if (req.score() == null || req.score() < 0 || req.score() > 125) return R.fail(4782, "Morse 评分范围 0-125");
            level = req.score() >= 45 ? "HIGH" : req.score() >= 25 ? "MID" : "LOW";
        } else {
            return R.fail(4783, "评估类型只能为 BRADEN/MORSE");
        }
        jdbc.update("""
                insert into nur_risk_assess(admission_id, assess_type, score, risk_level, note, assessor)
                values (?,?,?,?,?,?)
                """, req.admissionId(), req.assessType(), req.score(), level, req.note(), auth.getName());
        return R.ok(Map.of("riskLevel", level));
    }

    /** 评估趋势（按时间正序，前端画风险变化） */
    @GetMapping
    public R<List<Map<String, Object>>> trend(@RequestParam Long admissionId,
                                              @RequestParam(required = false) String assessType) {
        String base = "select * from nur_risk_assess where admission_id = ? ";
        return R.ok(assessType == null
                ? jdbc.queryForList(base + " order by assessed_at", admissionId)
                : jdbc.queryForList(base + " and assess_type = ? order by assessed_at", admissionId, assessType));
    }

    /** 高危预警清单：在院患者最近一次评估为高危 */
    @GetMapping("/alerts")
    public R<List<Map<String, Object>>> alerts() {
        return R.ok(jdbc.queryForList("""
                select distinct on (n.admission_id, n.assess_type)
                       n.admission_id, n.assess_type, n.score, n.risk_level, n.assessed_at,
                       a.admission_no, p.name as patient_name, d.name as dept_name
                from nur_risk_assess n
                join inp_admission a on a.id = n.admission_id and a.status = 'IN_HOSPITAL'
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                order by n.admission_id, n.assess_type, n.assessed_at desc
                """).stream().filter(r -> "HIGH".equals(r.get("risk_level"))).toList());
    }
}
