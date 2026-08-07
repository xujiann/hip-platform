package cn.hip.outpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 二十四期：急诊留观——分诊后入留观床，病情观察记录，离观去向 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/outpatient/er-observation")
public class ErObservationController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    private static final Set<String> OUTCOMES = Set.of("DISCHARGED", "ADMITTED", "TRANSFERRED");

    public record StartReq(Long triageId, String bedNo) {}

    /** 入留观：分诊记录存在且未在观 */
    @PostMapping
    public R<Void> start(@RequestBody StartReq req) {
        Integer triaged = jdbc.queryForObject(
                "select count(*) from outp_triage where id = ?", Integer.class, req.triageId());
        if (triaged == null || triaged == 0) return R.fail(4560, "分诊记录不存在");
        Integer active = jdbc.queryForObject(
                "select count(*) from er_observation where triage_id = ? and status = 'IN'",
                Integer.class, req.triageId());
        if (active != null && active > 0) return R.fail(4561, "该患者已在留观中");
        Integer bedBusy = jdbc.queryForObject(
                "select count(*) from er_observation where bed_no = ? and status = 'IN'",
                Integer.class, req.bedNo());
        if (bedBusy != null && bedBusy > 0) return R.fail(4562, "留观床 " + req.bedNo() + " 已占用");
        try {
            jdbc.update("insert into er_observation(triage_id, bed_no) values (?,?)", req.triageId(), req.bedNo());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发兜底：uk_er_obs_active_triage / uk_er_obs_active_bed 部分唯一索引拦截双占
            if (String.valueOf(e.getMessage()).contains("uk_er_obs_active_bed")) {
                return R.fail(4562, "留观床 " + req.bedNo() + " 已占用");
            }
            return R.fail(4561, "该患者已在留观中");
        }
        return R.ok();
    }

    /** 病情观察记录 */
    @PostMapping("/{id}/notes")
    public R<Void> addNote(@PathVariable Long id, @RequestParam String note, Authentication auth) {
        Integer active = jdbc.queryForObject(
                "select count(*) from er_observation where id = ? and status = 'IN'", Integer.class, id);
        if (active == null || active == 0) return R.fail(4563, "留观不存在或已结束");
        jdbc.update("insert into er_observation_note(observation_id, note, recorder_id) values (?,?,?)",
                id, note, currentUserService.idOf(auth));
        return R.ok();
    }

    /** 离观：去向必填 */
    @PutMapping("/{id}/end")
    public R<Void> end(@PathVariable Long id, @RequestParam String outcome) {
        if (!OUTCOMES.contains(outcome)) return R.fail(4564, "去向只能为 DISCHARGED/ADMITTED/TRANSFERRED");
        int n = jdbc.update(
                "update er_observation set status = 'OUT', outcome = ?, ended_at = now() where id = ? and status = 'IN'",
                outcome, id);
        return n == 0 ? R.fail(4563, "留观不存在或已结束") : R.ok();
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(jdbc.queryForList("""
                select o.*, t.patient_name, t.level, t.chief_complaint,
                       (select count(*) from er_observation_note n where n.observation_id = o.id) as notes
                from er_observation o
                join outp_triage t on t.id = o.triage_id
                order by o.id desc limit 100
                """));
    }

    @GetMapping("/{id}/notes")
    public R<List<Map<String, Object>>> notes(@PathVariable Long id) {
        return R.ok(jdbc.queryForList(
                "select * from er_observation_note where observation_id = ? order by id desc", id));
    }
}
