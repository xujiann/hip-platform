package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 三十三期：护理与院感精细化——传染病报卡闭环、体温日历/术后发热、班次字典、抽检评分、交接班 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','NURSE','QUALITY')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class NursingPlusController {

    private final JdbcTemplate jdbc;

    // ---- 传染病报卡 ----
    public record CardReq(Long patientId, String diseaseName, String cardClass, String onsetDate) {}

    @PostMapping("/api/infection/cards")
    public R<Void> reportCard(@RequestBody CardReq req, Authentication auth) {
        if (!Set.of("A", "B", "C").contains(req.cardClass())) return R.fail(4700, "报卡类别只能为 A/B/C");
        Integer p = jdbc.queryForObject("select count(*) from empi_patient where id = ?", Integer.class, req.patientId());
        if (p == null || p == 0) return R.fail(4701, "患者不存在");
        jdbc.update("""
                insert into idc_report_card(patient_id, disease_name, card_class, onset_date, reporter)
                values (?,?,?,?::date,?)
                """, req.patientId(), req.diseaseName(), req.cardClass(), req.onsetDate(), auth.getName());
        return R.ok();
    }

    /** 审核（通过/退回） */
    @PutMapping("/api/infection/cards/{id}/review")
    public R<Void> review(@PathVariable Long id, @RequestParam boolean approve,
                          @RequestParam(required = false) String note) {
        int n = jdbc.update("""
                update idc_report_card set status = ?, review_note = ?, reviewed_at = now()
                where id = ? and status = 'REPORTED'
                """, approve ? "REVIEWED" : "REJECTED", note, id);
        return n == 0 ? R.fail(4702, "报卡不存在或已审核") : R.ok();
    }

    /** 上报（甲类须 2 小时内——由前端提示，此处闭环状态机） */
    @PutMapping("/api/infection/cards/{id}/submit")
    public R<Void> submit(@PathVariable Long id) {
        int n = jdbc.update("""
                update idc_report_card set status = 'SUBMITTED', submitted_at = now()
                where id = ? and status = 'REVIEWED'
                """, id);
        return n == 0 ? R.fail(4703, "须审核通过后上报") : R.ok();
    }

    @GetMapping("/api/infection/cards")
    public R<List<Map<String, Object>>> cards() {
        return R.ok(jdbc.queryForList("""
                select c.*, p.name as patient_name from idc_report_card c
                join empi_patient p on p.id = c.patient_id order by c.id desc limit 200
                """));
    }

    // ---- 体温日历（复用 inp_vital_sign）----
    @GetMapping("/api/nursing/temp-calendar")
    public R<List<Map<String, Object>>> tempCalendar(@RequestParam Long admissionId) {
        return R.ok(jdbc.queryForList("""
                select measured_at::date as day, max(temperature) as max_temp,
                       max(temperature) >= 37.3 as fever
                from inp_vital_sign
                where admission_id = ? and temperature is not null
                group by measured_at::date order by day
                """, admissionId));
    }

    /** 术后发热监测：手术完成后 72h 内体温 ≥38 的在院患者 */
    @GetMapping("/api/nursing/postop-fever")
    public R<List<Map<String, Object>>> postopFever() {
        return R.ok(jdbc.queryForList("""
                select s.id as surgery_id, s.procedure_name, p.name as patient_name, a.admission_no,
                       v.temperature, v.measured_at
                from inp_surgery s
                join inp_admission a on a.id = s.admission_id and a.status = 'IN_HOSPITAL'
                join empi_patient p on p.id = a.patient_id
                join inp_vital_sign v on v.admission_id = a.id
                where s.status = 'DONE' and v.temperature >= 38
                  -- 5 分钟容差：术毕即测体温也计入术后；同时抵御应用/库时钟偏移
                  and v.measured_at > coalesce(s.scheduled_at, s.created_at) - interval '5 minutes'
                  and v.measured_at < coalesce(s.scheduled_at, s.created_at) + interval '72 hours'
                order by v.measured_at desc limit 100
                """));
    }

    // ---- 班次字典 ----
    public record ShiftTypeReq(String code, String name, String startTime, String endTime) {}

    @PostMapping("/api/nursing/shift-types")
    public R<Void> addShiftType(@RequestBody ShiftTypeReq req) {
        jdbc.update("""
                insert into nur_shift_type(code, name, start_time, end_time) values (?,?,?,?)
                on conflict (code) do update set name = excluded.name,
                    start_time = excluded.start_time, end_time = excluded.end_time
                """, req.code(), req.name(), req.startTime(), req.endTime());
        return R.ok();
    }

    @GetMapping("/api/nursing/shift-types")
    public R<List<Map<String, Object>>> shiftTypes() {
        return R.ok(jdbc.queryForList("select * from nur_shift_type order by start_time"));
    }

    // ---- 质控抽检 ----
    public record PlanReq(String title, String standard, Boolean adHoc) {}

    @PostMapping("/api/qc-check/plans")
    public R<Void> addPlan(@RequestBody PlanReq req) {
        jdbc.update("insert into qc_check_plan(title, standard, ad_hoc) values (?,?,?)",
                req.title(), req.standard(), req.adHoc() != null && req.adHoc());
        return R.ok();
    }

    @GetMapping("/api/qc-check/plans")
    public R<List<Map<String, Object>>> plans() {
        return R.ok(jdbc.queryForList("""
                select p.*, (select count(*) from qc_check_score s where s.plan_id = p.id) as scores
                from qc_check_plan p order by p.id desc limit 100
                """));
    }

    public record ScoreReq(Long planId, String target, Double score, String note) {}

    @PostMapping("/api/qc-check/scores")
    public R<Void> addScore(@RequestBody ScoreReq req) {
        if (req.score() == null || req.score() < 0 || req.score() > 100) return R.fail(4704, "评分范围 0-100");
        Integer plan = jdbc.queryForObject("select count(*) from qc_check_plan where id = ?", Integer.class, req.planId());
        if (plan == null || plan == 0) return R.fail(4705, "抽检计划不存在");
        jdbc.update("insert into qc_check_score(plan_id, target, score, note) values (?,?,?,?)",
                req.planId(), req.target(), req.score(), req.note());
        return R.ok();
    }

    /** 评分曲线：按时间序列 */
    @GetMapping("/api/qc-check/scores")
    public R<List<Map<String, Object>>> scores(@RequestParam Long planId) {
        return R.ok(jdbc.queryForList(
                "select * from qc_check_score where plan_id = ? order by checked_at", planId));
    }

    // ---- 交接班 ----
    public record HandoverReq(Long deptId, String shiftType, String summary, String todo) {}

    @PostMapping("/api/nursing/handovers")
    public R<Void> addHandover(@RequestBody HandoverReq req, Authentication auth) {
        if (req.summary() == null || req.summary().isBlank()) return R.fail(4706, "当班情况必填");
        jdbc.update("""
                insert into shift_handover(dept_id, shift_type, summary, todo, author) values (?,?,?,?,?)
                """, req.deptId(), req.shiftType(), req.summary(), req.todo(), auth.getName());
        return R.ok();
    }

    @GetMapping("/api/nursing/handovers")
    public R<List<Map<String, Object>>> handovers() {
        return R.ok(jdbc.queryForList("""
                select h.*, d.name as dept_name from shift_handover h
                join sys_dept d on d.id = h.dept_id order by h.id desc limit 100
                """));
    }
}
