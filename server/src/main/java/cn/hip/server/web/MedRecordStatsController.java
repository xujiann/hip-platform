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
        // 1.0.4：真编码率=出院诊断编码填充率（原 codedRate 实为入院诊断填充率，保留兼容）
        m.put("dischargeCodedRate", jdbc.queryForObject("""
                select coalesce(round(count(*) filter (where discharge_diag_icd is not null)::numeric
                    / nullif(count(*), 0) * 100, 1), 0)
                from inp_admission where status = 'DISCHARGED'
                """, Double.class));
        return R.ok(m);
    }

    // ---- 1.0.1（1028）：死亡登记卡 ----
    public record DeathCardReq(Long patientId, Long admissionId, String diedAt, String directCause,
                               String directCauseIcd, String chainB, String chainC, String chainD,
                               String place, String registrar) {}

    @PostMapping("/death-cards")
    public R<Void> createDeathCard(@RequestBody DeathCardReq req) {
        if (req.patientId() == null || req.diedAt() == null || req.directCause() == null
                || req.directCause().isBlank()) {
            return R.fail(9950, "患者、死亡时间与直接死因必填");
        }
        jdbc.update("""
                insert into mr_death_card(patient_id, admission_id, died_at, direct_cause, direct_cause_icd,
                                          chain_b, chain_c, chain_d, place, registrar)
                values (?,?,?::timestamptz,?,?,?,?,?,?,?)
                """, req.patientId(), req.admissionId(), req.diedAt(), req.directCause(), req.directCauseIcd(),
                req.chainB(), req.chainC(), req.chainD(), req.place(), req.registrar());
        return R.ok();
    }

    @GetMapping("/death-cards")
    public R<List<Map<String, Object>>> deathCards() {
        return R.ok(jdbc.queryForList("""
                select d.*, p.name as patient_name, p.patient_no, a.admission_no
                from mr_death_card d
                join empi_patient p on p.id = d.patient_id
                left join inp_admission a on a.id = d.admission_id
                order by d.id desc limit 200
                """));
    }

    /**
     * v41 床位效率趋势（按科室 × 月）：出院人次 / 平均住院日 / 占用床日 / 床位周转次数 / 床位使用率。
     *
     * <p>补齐验收偏离表已承诺（"可自动生成床位使用率、床位周转次数等统计报表"）但此前代码不存在的项。
     * <p><b>口径与近似说明（诚实标注，报表页同步展示）</b>：
     * <ul>
     *   <li>占用床日按"出院病例的住院天数"归集到出院月，不是逐日切分——跨月长住病例的床日全部计入出院月；
     *   <li>床位数取该科室**当前**开放床位数（inp_bed 无历史快照），历史月份若增减过床位会有偏差；
     *   <li>周转次数 = 出院人次 / 床位数；使用率 = 占用床日 / (床位数 × 当月天数)。
     * </ul>
     * 这两条近似在小规模县级医院可接受；若院方要求严格口径，需另建每日床位快照表（实施期评估）。
     */
    @GetMapping("/dept-bed-trend")
    public R<List<Map<String, Object>>> deptBedTrend(@RequestParam(defaultValue = "12") int months) {
        int m = Math.min(Math.max(months, 1), 36);
        return R.ok(jdbc.queryForList("""
                with beds as (
                    select d.id as dept_id, count(b.id) as bed_count
                    from sys_dept d left join inp_bed b on b.ward_id = d.id
                    group by d.id
                ),
                disch as (
                    select a.dept_id,
                           date_trunc('month', a.discharged_at) as ym,
                           count(*) as discharges,
                           sum(extract(epoch from (a.discharged_at - a.admit_at)) / 86400.0) as bed_days
                    from inp_admission a
                    where a.status = 'DISCHARGED' and a.discharged_at is not null
                      and a.discharged_at >= date_trunc('month', now()) - make_interval(months => ?)
                    group by a.dept_id, date_trunc('month', a.discharged_at)
                )
                select to_char(x.ym, 'YYYY-MM') as month, d.name as dept_name,
                       x.discharges,
                       round((x.bed_days / nullif(x.discharges, 0))::numeric, 1) as avg_stay_days,
                       round(x.bed_days::numeric, 1) as bed_days,
                       coalesce(b.bed_count, 0) as bed_count,
                       round((x.discharges::numeric / nullif(b.bed_count, 0)), 2) as turnover,
                       round((x.bed_days::numeric
                              / nullif(b.bed_count * extract(day from (x.ym + interval '1 month' - interval '1 day')), 0)
                              * 100), 1) as occupancy_pct
                from disch x
                join sys_dept d on d.id = x.dept_id
                left join beds b on b.dept_id = x.dept_id
                order by x.ym desc, d.name
                """, m));
    }
}
