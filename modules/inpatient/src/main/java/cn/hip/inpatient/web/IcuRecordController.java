package cn.hip.inpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 二十四期：ICU 密集护理记录——高频体征 + GCS + 出入量 + 呼吸机标记 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/inpatient/icu-records")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")   // 1.0.9：权限清点补齐
public class IcuRecordController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    public record IcuReq(Long admissionId, Double temperature, Integer pulse, Integer respiration,
                         Integer sbp, Integer dbp, Integer spo2, Integer gcs,
                         Integer intakeMl, Integer outputMl, Boolean ventilator, String note) {}

    @PostMapping
    public R<Void> record(@RequestBody IcuReq req, Authentication auth) {
        Integer inHosp = jdbc.queryForObject(
                "select count(*) from inp_admission where id = ? and status = 'IN_HOSPITAL'",
                Integer.class, req.admissionId());
        if (inHosp == null || inHosp == 0) return R.fail(4570, "住院记录不存在或已出院");
        if (req.gcs() != null && (req.gcs() < 3 || req.gcs() > 15)) return R.fail(4571, "GCS 评分范围 3-15");
        jdbc.update("""
                insert into inp_icu_record(admission_id, temperature, pulse, respiration, sbp, dbp, spo2,
                                           gcs, intake_ml, output_ml, ventilator, note, recorder_id)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, req.admissionId(), req.temperature(), req.pulse(), req.respiration(), req.sbp(), req.dbp(),
                req.spo2(), req.gcs(), req.intakeMl(), req.outputMl(),
                req.ventilator() != null && req.ventilator(), req.note(), currentUserService.idOf(auth));
        return R.ok();
    }

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam Long admissionId) {
        return R.ok(jdbc.queryForList("""
                select * from inp_icu_record where admission_id = ? order by recorded_at desc limit 200
                """, admissionId));
    }

    /** 24 小时出入量汇总 */
    @GetMapping("/balance")
    public R<Map<String, Object>> balance(@RequestParam Long admissionId) {
        return R.ok(jdbc.queryForMap("""
                select coalesce(sum(intake_ml), 0) as intake, coalesce(sum(output_ml), 0) as output,
                       coalesce(sum(intake_ml), 0) - coalesce(sum(output_ml), 0) as balance
                from inp_icu_record
                where admission_id = ? and recorded_at > now() - interval '24 hours'
                """, admissionId));
    }
}
