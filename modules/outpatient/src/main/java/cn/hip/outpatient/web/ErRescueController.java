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
    private final cn.hip.outpatient.service.EmergencyAccess access;

    private static final Set<String> OUTCOMES = Set.of("ONGOING", "SUCCESS", "DEATH", "TRANSFERRED");

    /** 录入抢救记录：留观患者传 observationId（自动带出分诊/患者名）；未入留观的急诊患者可直接传 triageId */
    public record RescueReq(Long observationId, Long triageId,
                            Double temperature, Integer pulse, Integer respiration,
                            Integer sbp, Integer dbp, Integer spo2, Integer gcs,
                            Integer intakeMl, Integer outputMl,
                            String measures, String participants, String outcome, String note) {}

    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    public R<Map<String, Object>> record(@RequestBody RescueReq req, Authentication auth) {
        var me=access.actor();
        Long triageId = req.triageId();
        Long observationId = req.observationId();
        // The actual observation parent wins over any client-supplied triage id.
        if (observationId!=null) {
            var obs=access.observation(observationId,4565,true,false);
            triageId=access.number(obs.get("triage_id"));
        }
        if(triageId==null)return R.fail(4565,"须指定留观记录或分诊记录");
        var tri=access.lockTriage(triageId,4565,false);
        String patientName=(String)tri.get("patient_name");
        if (req.gcs() != null && (req.gcs() < 3 || req.gcs() > 15)) return R.fail(4567, "GCS 评分范围 3-15");
        String outcome = req.outcome() == null ? "ONGOING" : req.outcome();
        if (!OUTCOMES.contains(outcome)) return R.fail(4568, "转归只能为 ONGOING/SUCCESS/DEATH/TRANSFERRED");
        // An initial terminal outcome is also a medical decision, not a nursing bypass.
        if (!"ONGOING".equals(outcome)) access.lockTriage(triageId,4565,true);

        Long id = jdbc.queryForObject("""
                insert into er_rescue_record(triage_id, observation_id, patient_name,
                        temperature, pulse, respiration, sbp, dbp, spo2, gcs, intake_ml, output_ml,
                        measures, participants, outcome, note, recorder_id)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                returning id
                """, Long.class, triageId, observationId, patientName,
                req.temperature(), req.pulse(), req.respiration(), req.sbp(), req.dbp(), req.spo2(),
                req.gcs(), req.intakeMl(), req.outputMl(), req.measures(), req.participants(),
                outcome, req.note(), me.id());
        return R.ok(Map.of("id", id));
    }

    public record EndReq(String outcome, String note) {}

    /** 抢救结束：置结束时间 + 终态转归（结束时转归不得为 ONGOING） */
    @PutMapping("/{id}/end")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> end(@PathVariable Long id, @RequestBody EndReq req) {
        access.lockRescue(id);
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
        var me=access.actor();var args=new java.util.ArrayList<Object>();
        String sql="select r.*,t.doctor_id from er_rescue_record r join outp_triage t on t.id=r.triage_id left join er_observation o on o.id=r.observation_id where (r.observation_id is null or o.triage_id=r.triage_id) and "+access.scope(me,args);
        if(observationId!=null){sql+=" and r.observation_id=?";args.add(observationId);}
        else if(triageId!=null){sql+=" and r.triage_id=?";args.add(triageId);}
        sql+=" order by r.id desc";
        if(observationId==null&&triageId==null)sql+=" limit 100";
        return R.ok(jdbc.queryForList(sql,args.toArray()));
    }
}
