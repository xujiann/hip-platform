package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cn.hip.platform.core.config.BusinessDates;

/** 三十六期：院感专项——三管监测（置管→日评估→拔管/感染）与千日感染率、现患率调查 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/infection-plus")
@PreAuthorize("hasAnyRole('ADMIN','QUALITY','NURSE')")   // 1.0.9：权限清点补齐
public class InfectionPlusController {

    private final JdbcTemplate jdbc;

    private static final Set<String> LINE_TYPES = Set.of("VENT", "CVC", "URINARY");

    // ---- 置管监测 ----
    public record LineReq(Long admissionId, String lineType, String note) {}

    @PostMapping("/catheters")
    public R<Void> insertLine(@RequestBody LineReq req) {
        if (!LINE_TYPES.contains(req.lineType())) return R.fail(4750, "置管类型只能为 VENT/CVC/URINARY");
        Integer inHosp = jdbc.queryForObject(
                "select count(*) from inp_admission where id = ? and status = 'IN_HOSPITAL'",
                Integer.class, req.admissionId());
        if (inHosp == null || inHosp == 0) return R.fail(4751, "住院记录不存在或已出院");
        Integer active = jdbc.queryForObject("""
                select count(*) from inf_catheter where admission_id = ? and line_type = ? and status = 'ACTIVE'
                """, Integer.class, req.admissionId(), req.lineType());
        if (active != null && active > 0) return R.fail(4752, "该类型置管已在监测中");
        jdbc.update("insert into inf_catheter(admission_id, line_type, note) values (?,?,?)",
                req.admissionId(), req.lineType(), req.note());
        return R.ok();
    }

    /** 日评估（每日一次，评估是否可拔管） */
    @PostMapping("/catheters/{id}/assess")
    public R<Void> assess(@PathVariable Long id, @RequestParam boolean keepLine,
                          @RequestParam(required = false) String note) {
        Integer active = jdbc.queryForObject(
                "select count(*) from inf_catheter where id = ? and status = 'ACTIVE'", Integer.class, id);
        if (active == null || active == 0) return R.fail(4753, "置管不存在或已结束");
        int n = jdbc.update("""
                insert into inf_catheter_assess(catheter_id, keep_line, note) values (?,?,?)
                on conflict (catheter_id, assess_date) do update set keep_line = excluded.keep_line,
                    note = excluded.note
                """, id, keepLine, note);
        return n == 0 ? R.fail(4754, "评估失败") : R.ok();
    }

    /** 拔管 */
    @PutMapping("/catheters/{id}/remove")
    public R<Void> remove(@PathVariable Long id) {
        int n = jdbc.update(
                "update inf_catheter set status = 'REMOVED', end_at = now() where id = ? and status = 'ACTIVE'", id);
        return n == 0 ? R.fail(4753, "置管不存在或已结束") : R.ok();
    }

    /** 判定器械相关感染（VAP/CLABSI/CAUTI），同步登记院感病例 */
    @PutMapping("/catheters/{id}/infect")
    @Transactional
    public R<Void> infect(@PathVariable Long id, @RequestParam(required = false) String pathogen) {
        var rows = jdbc.queryForList("select * from inf_catheter where id = ? and status = 'ACTIVE'", id);
        if (rows.isEmpty()) return R.fail(4753, "置管不存在或已结束");
        var c = rows.get(0);
        jdbc.update("""
                update inf_catheter set status = 'INFECTED', infect_date = current_date, end_at = now() where id = ?
                """, id);
        String site = switch ((String) c.get("line_type")) {
            case "VENT" -> "呼吸机相关肺炎(VAP)";
            case "CVC" -> "血管内导管相关血流感染(CLABSI)";
            default -> "导尿管相关泌尿系感染(CAUTI)";
        };
        jdbc.update("""
                insert into qc_infection_case(admission_id, site, pathogen, confirmed_on, note)
                values (?,?,?,current_date,'三管监测判定')
                """, c.get("admission_id"), site, pathogen);
        return R.ok();
    }

    @GetMapping("/catheters")
    public R<List<Map<String, Object>>> catheters() {
        return R.ok(jdbc.queryForList("""
                select c.*, p.name as patient_name, a.admission_no,
                       round(greatest(extract(epoch from (coalesce(c.end_at, now()) - c.start_at)) / 86400, 1)::numeric, 1)
                           as line_days,
                       (select count(*) from inf_catheter_assess x where x.catheter_id = c.id) as assesses
                from inf_catheter c
                join inp_admission a on a.id = c.admission_id
                join empi_patient p on p.id = a.patient_id
                order by c.id desc limit 100
                """));
    }

    /** 千日感染率：感染例次 / 置管日数 × 1000（按类型；置管不足 1 日按 1 日计，符合按日历日登记惯例） */
    @GetMapping("/rate")
    public R<List<Map<String, Object>>> rate() {
        return R.ok(jdbc.queryForList("""
                select line_type,
                       count(*) as lines,
                       round(sum(greatest(extract(epoch from (coalesce(end_at, now()) - start_at)) / 86400, 1))::numeric, 1)
                           as line_days,
                       count(*) filter (where status = 'INFECTED') as infections,
                       round(count(*) filter (where status = 'INFECTED') * 1000
                             / sum(greatest(extract(epoch from (coalesce(end_at, now()) - start_at)) / 86400, 1))::numeric, 2)
                           as per_1000_days
                from inf_catheter group by line_type order by line_type
                """));
    }

    /** 现患率调查表：在院患者感染现患快照 */
    @GetMapping("/prevalence")
    public R<Map<String, Object>> prevalence() {
        var rows = jdbc.queryForList("""
                select a.admission_no, p.name as patient_name, d.name as dept_name, a.admit_diag_name,
                       exists (select 1 from qc_infection_case ic where ic.admission_id = a.id) as infected,
                       (select string_agg(ic.site, '；') from qc_infection_case ic where ic.admission_id = a.id) as sites
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                where a.status = 'IN_HOSPITAL' order by d.name, a.admission_no
                """);
        long infected = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("infected"))).count();
        var m = new LinkedHashMap<String, Object>();
        m.put("surveyDate", cn.hip.platform.core.config.BusinessDates.today().toString());
        m.put("inHospital", rows.size());
        m.put("infected", infected);
        m.put("prevalenceRate", rows.isEmpty() ? 0
                : Math.round(infected * 10000.0 / rows.size()) / 100.0);
        m.put("rows", rows);
        return R.ok(m);
    }
}
