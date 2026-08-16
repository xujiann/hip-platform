package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 三十期：麻醉记录单——术中生命体征时间轴 + PACU 苏醒记录（Steward 评分） */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/anes")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','TECHNICIAN')")   // 1.0.9：权限清点补齐
public class AnesController {

    private final JdbcTemplate jdbc;

    public record AnesReq(Long surgeryId, String phase, Integer hr, Integer sbp, Integer dbp,
                          Integer spo2, Integer stewardScore, String note) {}

    @PostMapping("/records")
    public R<Void> record(@RequestBody AnesReq req) {
        if (!"INTRA".equals(req.phase()) && !"PACU".equals(req.phase())) return R.fail(4670, "阶段只能为 INTRA/PACU");
        Integer surgery = jdbc.queryForObject("select count(*) from inp_surgery where id = ?",
                Integer.class, req.surgeryId());
        if (surgery == null || surgery == 0) return R.fail(4671, "手术记录不存在");
        if (req.stewardScore() != null && (req.stewardScore() < 0 || req.stewardScore() > 6)) {
            return R.fail(4672, "Steward 苏醒评分范围 0-6");
        }
        jdbc.update("""
                insert into anes_record(surgery_id, phase, hr, sbp, dbp, spo2, steward_score, note)
                values (?,?,?,?,?,?,?,?)
                """, req.surgeryId(), req.phase(), req.hr(), req.sbp(), req.dbp(), req.spo2(),
                req.stewardScore(), req.note());
        return R.ok();
    }

    /** 麻醉记录时间轴 */
    @GetMapping("/records")
    public R<List<Map<String, Object>>> records(@RequestParam Long surgeryId) {
        return R.ok(jdbc.queryForList(
                "select * from anes_record where surgery_id = ? order by recorded_at", surgeryId));
    }

    /** 出复苏室判定：最近一次 PACU 记录 Steward ≥ 4 可出室 */
    @GetMapping("/records/pacu-status")
    public R<Map<String, Object>> pacuStatus(@RequestParam Long surgeryId) {
        var rows = jdbc.queryForList("""
                select steward_score from anes_record
                where surgery_id = ? and phase = 'PACU' and steward_score is not null
                order by recorded_at desc limit 1
                """, surgeryId);
        Integer score = rows.isEmpty() ? null : ((Number) rows.get(0).get("steward_score")).intValue();
        return R.ok(Map.of("latestScore", score == null ? -1 : score,
                "canLeave", score != null && score >= 4));
    }
}
