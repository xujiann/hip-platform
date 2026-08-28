package cn.hip.outpatient.web;

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

/**
 * 车道B 临床收尾③：急诊抢救记录。
 * 面向急诊留观/急诊分诊患者，记录抢救全过程（体征 + GCS + 出入量 + 抢救措施 + 参与人员 + 转归）——法定文书。
 * 字段风格参照住院 IcuRecordController 的 GCS/出入量，但表(er_rescue_record)与本控制器均独立于住院 ICU。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/outpatient/er-rescue")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")   // 与急诊留观同权限
public class ErRescueController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    private static final Set<String> OUTCOMES = Set.of("ONGOING", "SUCCESS", "DEATH", "TRANSFERRED");

    /** 录入抢救记录：留观患者传 observationId（自动带出分诊/患者名）；未入留观的急诊患者可直接传 triageId */
    public record RescueReq(Long observationId, Long triageId,
                            Double temperature, Integer pulse, Integer respiration,
                            Integer sbp, Integer dbp, Integer spo2, Integer gcs,
                            Integer intakeMl, Integer outputMl,
                            String measures, String participants, String outcome, String note) {}

    @PostMapping
    public R<Map<String, Object>> record(@RequestBody RescueReq req, Authentication auth) {
        Long triageId = req.triageId();
        Long observationId = req.observationId();
        // 留观入口：由 observation 反查其 triage_id（前端留观页只持有 observationId）
        if (observationId != null) {
            var obs = jdbc.queryForList("select triage_id from er_observation where id = ?", observationId);
            if (obs.isEmpty()) return R.fail(4565, "留观记录不存在");
            triageId = ((Number) obs.get(0).get("triage_id")).longValue();
        }
        if (triageId == null) return R.fail(4565, "须指定留观记录或分诊记录");
        // 患者名取自分诊（冗余入抢救记录，便于抢救台快速识别）
        var tri = jdbc.queryForList("select patient_name from outp_triage where id = ?", triageId);
        if (tri.isEmpty()) return R.fail(4565, "分诊记录不存在");
        String patientName = (String) tri.get(0).get("patient_name");
        if (req.gcs() != null && (req.gcs() < 3 || req.gcs() > 15)) return R.fail(4567, "GCS 评分范围 3-15");
        String outcome = req.outcome() == null ? "ONGOING" : req.outcome();
        if (!OUTCOMES.contains(outcome)) return R.fail(4568, "转归只能为 ONGOING/SUCCESS/DEATH/TRANSFERRED");

        Long id = jdbc.queryForObject("""
                insert into er_rescue_record(triage_id, observation_id, patient_name,
                        temperature, pulse, respiration, sbp, dbp, spo2, gcs, intake_ml, output_ml,
                        measures, participants, outcome, note, recorder_id)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                returning id
                """, Long.class, triageId, observationId, patientName,
                req.temperature(), req.pulse(), req.respiration(), req.sbp(), req.dbp(), req.spo2(),
                req.gcs(), req.intakeMl(), req.outputMl(), req.measures(), req.participants(),
                outcome, req.note(), currentUserService.idOf(auth));
        return R.ok(Map.of("id", id));
    }

    public record EndReq(String outcome, String note) {}

    /** 抢救结束：置结束时间 + 终态转归（结束时转归不得为 ONGOING） */
    @PutMapping("/{id}/end")
    public R<Void> end(@PathVariable Long id, @RequestBody EndReq req) {
        String outcome = req.outcome();
        if (outcome == null || !OUTCOMES.contains(outcome) || "ONGOING".equals(outcome)) {
            return R.fail(4568, "结束时转归须为 SUCCESS/DEATH/TRANSFERRED");
        }
        // 仅未结束的抢救可结束（rescue_end is null）；结束时间由 DB now() 落，早于开始时间被 chk_rescue_time 兜底
        int n = jdbc.update("""
                update er_rescue_record set rescue_end = now(), outcome = ?, note = coalesce(?, note)
                where id = ? and rescue_end is null
                """, outcome, req.note(), id);
        return n == 0 ? R.fail(4566, "抢救记录不存在或已结束") : R.ok();
    }

    /** 查看抢救记录：按留观/分诊过滤；均不传则返回近期全部（抢救台总览） */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long observationId,
                                             @RequestParam(required = false) Long triageId) {
        if (observationId != null) {
            return R.ok(jdbc.queryForList(
                    "select * from er_rescue_record where observation_id = ? order by id desc", observationId));
        }
        if (triageId != null) {
            return R.ok(jdbc.queryForList(
                    "select * from er_rescue_record where triage_id = ? order by id desc", triageId));
        }
        return R.ok(jdbc.queryForList("select * from er_rescue_record order by id desc limit 100"));
    }
}
