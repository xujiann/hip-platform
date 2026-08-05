package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三十期：病案统计报表——疾病谱、手术统计、科室出院构成、ICD 章节构成 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/mrstats")
public class MedRecordStatsController {

    private final JdbcTemplate jdbc;

    /** 疾病谱 TOP20（出院主诊断，ICD 前 3 位归并） */
    @GetMapping("/disease-top")
    public R<List<Map<String, Object>>> diseaseTop() {
        return R.ok(jdbc.queryForList("""
                select substring(admit_diag_icd, 1, 3) as icd_group,
                       max(coalesce(admit_diag_name, '未填写')) as sample_name,
                       count(*) as cases,
                       round(avg(extract(epoch from (discharged_at - admit_at)) / 86400)::numeric, 1) as avg_days
                from inp_admission
                where status = 'DISCHARGED' and admit_diag_icd is not null
                group by substring(admit_diag_icd, 1, 3)
                order by cases desc limit 20
                """));
    }

    /** 手术统计（例数/近30日例数） */
    @GetMapping("/surgery-stats")
    public R<List<Map<String, Object>>> surgeryStats() {
        return R.ok(jdbc.queryForList("""
                select procedure_name, count(*) as cases,
                       count(*) filter (where scheduled_at > now() - interval '30 days'
                                        or created_at > now() - interval '30 days') as recent_cases,
                       count(*) filter (where status = 'DONE') as done
                from inp_surgery where status <> 'CANCELLED'
                group by procedure_name order by cases desc limit 30
                """));
    }

    /** 科室出院构成 */
    @GetMapping("/dept-discharge")
    public R<List<Map<String, Object>>> deptDischarge() {
        return R.ok(jdbc.queryForList("""
                select d.name as dept_name, count(*) as discharged,
                       round(avg(extract(epoch from (a.discharged_at - a.admit_at)) / 86400)::numeric, 1) as avg_days,
                       round(count(*)::numeric / (select count(*) from inp_admission where status = 'DISCHARGED') * 100, 1)
                           as percentage
                from inp_admission a join sys_dept d on d.id = a.dept_id
                where a.status = 'DISCHARGED'
                group by d.name order by discharged desc
                """));
    }

    /** ICD 章节构成（首字母归并） */
    @GetMapping("/icd-composition")
    public R<List<Map<String, Object>>> icdComposition() {
        return R.ok(jdbc.queryForList("""
                select substring(admit_diag_icd, 1, 1) as chapter, count(*) as cases,
                       round(count(*)::numeric / (select count(*) from inp_admission
                                                  where status = 'DISCHARGED' and admit_diag_icd is not null) * 100, 1)
                           as percentage
                from inp_admission
                where status = 'DISCHARGED' and admit_diag_icd is not null
                group by substring(admit_diag_icd, 1, 1)
                order by cases desc
                """));
    }

    /** 汇总卡片 */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        var m = new LinkedHashMap<String, Object>();
        m.put("discharged", jdbc.queryForObject(
                "select count(*) from inp_admission where status = 'DISCHARGED'", Long.class));
        m.put("archived", jdbc.queryForObject(
                "select count(*) from inp_admission where status = 'DISCHARGED' and archived", Long.class));
        m.put("surgeries", jdbc.queryForObject(
                "select count(*) from inp_surgery where status <> 'CANCELLED'", Long.class));
        m.put("codedRate", jdbc.queryForObject("""
                select coalesce(round(count(*) filter (where admit_diag_icd is not null)::numeric
                    / nullif(count(*), 0) * 100, 1), 0)
                from inp_admission where status = 'DISCHARGED'
                """, Double.class));
        return R.ok(m);
    }
}
