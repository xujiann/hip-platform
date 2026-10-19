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

/** 二十四期：急诊留观——分诊后入留观床，病情观察记录，离观去向 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/outpatient/er-observation")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")   // 1.0.9：权限清点补齐
public class ErObservationController {

    private final JdbcTemplate jdbc;
    private final cn.hip.outpatient.service.EmergencyAccess access;

    private static final Set<String> OUTCOMES = Set.of("DISCHARGED", "ADMITTED", "TRANSFERRED");

    public record StartReq(Long triageId, String bedNo) {}

    /** 入留观：分诊记录存在且未在观 */
    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    public R<Void> start(@RequestBody StartReq req) {
        access.lockTriage(req.triageId(),4560,false);
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
        // ON CONFLICT avoids catching a uniqueness error inside an already-aborted PG transaction.
        int inserted=jdbc.update("insert into er_observation(triage_id, bed_no) values (?,?) on conflict do nothing", req.triageId(), req.bedNo());
        if(inserted==0) {
            boolean same=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from er_observation where triage_id=? and status='IN')",Boolean.class,req.triageId()));
            return same?R.fail(4561,"该患者已在留观中"):R.fail(4562,"留观床已占用");
        }
        return R.ok();
    }

    /** 病情观察记录 */
    @PostMapping("/{id}/notes")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> addNote(@PathVariable Long id, @RequestParam String note, Authentication auth) {
        var row=access.observation(id,4563,true,false);
        if(!"IN".equals(row.get("status")))return R.fail(4563,"留观不存在或已结束");
        Integer active = jdbc.queryForObject(
                "select count(*) from er_observation where id = ? and status = 'IN'", Integer.class, id);
        if (active == null || active == 0) return R.fail(4563, "留观不存在或已结束");
        jdbc.update("insert into er_observation_note(observation_id, note, recorder_id) values (?,?,?)",
                id, note, access.actor().id());
        return R.ok();
    }

    /** 离观：去向必填 */
    @PutMapping("/{id}/end")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> end(@PathVariable Long id, @RequestParam String outcome) {
        access.observation(id,4563,true,true);
        if (!OUTCOMES.contains(outcome)) return R.fail(4564, "去向只能为 DISCHARGED/ADMITTED/TRANSFERRED");
        int n = jdbc.update(
                "update er_observation set status = 'OUT', outcome = ?, ended_at = now() where id = ? and status = 'IN'",
                outcome, id);
        return n == 0 ? R.fail(4563, "留观不存在或已结束") : R.ok();
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        var me=access.actor();var args=new java.util.ArrayList<Object>();
        String scope=access.scope(me,args);
        return R.ok(jdbc.queryForList("""
                select o.*, t.patient_name, t.level, t.chief_complaint, t.doctor_id,
                       (select count(*) from er_observation_note n where n.observation_id = o.id) as notes
                from er_observation o
                join outp_triage t on t.id = o.triage_id
                where
                """+scope+" order by o.id desc limit 100",args.toArray()));
    }

    @GetMapping("/{id}/notes")
    public R<List<Map<String, Object>>> notes(@PathVariable Long id) {
        access.actor();
        if(jdbc.queryForList("select id from er_observation where id=?",id).isEmpty())return R.ok(List.of());
        access.observation(id,4563,false,false);
        return R.ok(jdbc.queryForList(
                "select * from er_observation_note where observation_id = ? order by id desc", id));
    }
}
