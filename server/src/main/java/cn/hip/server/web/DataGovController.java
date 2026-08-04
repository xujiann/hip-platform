package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 十二期：指标标准管理与快照、数据填报与审批、数据质量核查 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class DataGovController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    /** 内置指标计算：builtin_key → SQL */
    private static final Map<String, String> BUILTIN = Map.of(
            "outp_reg_today", "select count(*) from outp_registration where visit_date = current_date and status <> 'CANCELLED'",
            "drug_ratio", """
                    select case when sum(amount) = 0 then 0 else
                        round(sum(amount) filter (where order_type = 'DRUG') / sum(amount) * 100, 2) end
                    from outp_order where status in ('CHARGED','DISPENSED','EXECUTED')
                    """,
            "bed_occupancy", "select round(count(*) filter (where status = 'OCCUPIED')::numeric / nullif(count(*), 0) * 100, 2) from inp_bed",
            "avg_outp_cost", "select coalesce(round(sum(total_amount) / nullif(count(distinct registration_id), 0), 2), 0) from outp_charge where status = 'PAID'",
            "in_hospital", "select count(*) from inp_admission where status = 'IN_HOSPITAL'");

    /** 指标定义 + 最新快照 */
    @GetMapping("/api/datagov/metrics")
    public R<List<Map<String, Object>>> metrics() {
        return R.ok(jdbc.queryForList("""
                select d.code, d.name, d.unit, d.target,
                       s.value as latest_value, s.snap_date
                from dg_metric_def d
                left join lateral (select value, snap_date from dg_metric_snapshot
                                   where code = d.code order by snap_date desc limit 1) s on true
                order by d.code
                """));
    }

    /** 生成今日指标快照（幂等覆盖） */
    @PostMapping("/api/datagov/metrics/snapshot")
    @Transactional
    public R<Map<String, Object>> snapshot() {
        var defs = jdbc.queryForList("select code, builtin_key from dg_metric_def");
        int n = 0;
        for (var d : defs) {
            String sql = BUILTIN.get((String) d.get("builtin_key"));
            if (sql == null) continue;
            Double value = jdbc.queryForObject(sql, Double.class);
            jdbc.update("""
                    insert into dg_metric_snapshot(code, value, snap_date) values (?, ?, current_date)
                    on conflict (code, snap_date) do update set value = excluded.value, created_at = now()
                    """, d.get("code"), value == null ? 0 : value);
            n++;
        }
        return R.ok(Map.of("snapshotted", n));
    }

    // ---- 数据填报 ----
    public record TaskReq(String title, String dueDate, String fields) {}

    @PostMapping("/api/datagov/report-tasks")
    public R<Void> createTask(@RequestBody TaskReq req) {
        jdbc.update("insert into dg_report_task(title, due_date, fields) values (?,?::date,?)",
                req.title(), req.dueDate(), req.fields());
        return R.ok();
    }

    @GetMapping("/api/datagov/report-tasks")
    public R<List<Map<String, Object>>> tasks() {
        return R.ok(jdbc.queryForList("""
                select t.*, (select count(*) from dg_report_submission s where s.task_id = t.id) as submissions
                from dg_report_task t order by t.id desc limit 100
                """));
    }

    public record SubmissionReq(Long taskId, Long deptId, String content) {}

    @PostMapping("/api/datagov/submissions")
    public R<Void> submit(@RequestBody SubmissionReq req, Authentication auth) {
        jdbc.update("insert into dg_report_submission(task_id, dept_id, content, submitter_id) values (?,?,?,?)",
                req.taskId(), req.deptId(), req.content(), currentUserService.idOf(auth));
        return R.ok();
    }

    @GetMapping("/api/datagov/submissions")
    public R<List<Map<String, Object>>> submissions(@RequestParam Long taskId) {
        return R.ok(jdbc.queryForList("""
                select s.id, s.content, s.status, s.review_note, s.created_at, d.name as dept_name
                from dg_report_submission s left join sys_dept d on d.id = s.dept_id
                where s.task_id = ? order by s.id desc
                """, taskId));
    }

    @PutMapping("/api/datagov/submissions/{id}/review")
    public R<Void> review(@PathVariable Long id, @RequestParam boolean approve,
                          @RequestParam(required = false) String note) {
        int n = jdbc.update("""
                update dg_report_submission set status = ?, review_note = ? where id = ? and status = 'SUBMITTED'
                """, approve ? "APPROVED" : "REJECTED", note, id);
        return n == 0 ? R.fail(9920, "填报不存在或已审") : R.ok();
    }

    /** 数据质量核查：关键完整性规则 */
    @GetMapping("/api/datagov/quality-checks")
    public R<List<Map<String, Object>>> qualityChecks() {
        return R.ok(List.of(
                check("患者缺证件号", "select count(*) from empi_patient where active and (id_no is null or id_no = '')"),
                check("患者缺手机号", "select count(*) from empi_patient where active and (phone is null or phone = '')"),
                check("门诊病历未签名", "select count(*) from outp_emr where signature is null"),
                check("出院未归档病案", "select count(*) from inp_admission where status = 'DISCHARGED' and not archived"),
                check("入院诊断未编码", "select count(*) from inp_admission where admit_diag_icd is null"),
                check("在途未审处方", "select count(*) from outp_order where order_type = 'DRUG' and status = 'CREATED' and review_status is null")));
    }

    private Map<String, Object> check(String rule, String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        return Map.of("rule", rule, "count", count, "pass", count == 0);
    }
}
