package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DRG 分组器（CHS-DRG 简化）：
 * ADRG = 主诊断 ICD 前缀 + 手术操作分列；细分组尾码按其他诊断严重程度（1 伴MCC / 3 伴CC / 5 不伴），
 * 终权重 = 基础权重 × 严重程度系数（MCC 1.3 / CC 1.15）。支付模拟：标杆支付 = 终权重 × 费率（sys_config drg_rate）。
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/drg")
public class DrgController {

    private final JdbcTemplate jdbc;

    private static final BigDecimal MCC_FACTOR = new BigDecimal("1.30");
    private static final BigDecimal CC_FACTOR = new BigDecimal("1.15");

    @GetMapping("/groups")
    public R<List<Map<String, Object>>> groups() {
        return R.ok(jdbc.queryForList("select * from drg_group_def order by mdc_code, adrg_code"));
    }

    @GetMapping("/cc-list")
    public R<List<Map<String, Object>>> ccList() {
        return R.ok(jdbc.queryForList("select * from drg_cc_list order by level, icd_prefix"));
    }

    /** 批量入组：已出院且未入组 */
    @PostMapping("/group-all")
    @Transactional
    public R<Map<String, Object>> groupAll() {
        return R.ok(doGroup());
    }

    /** 重新入组：规则/诊断变更后全量重算 */
    @PostMapping("/regroup-all")
    @Transactional
    public R<Map<String, Object>> regroupAll() {
        jdbc.update("delete from drg_case");
        return R.ok(doGroup());
    }

    /**
     * 单例重算：补录出院诊断/其他诊断后调用。
     * 原先只能全量 regroup-all（丢历史入组时间且大数据量下代价高），
     * 而 group-all 会跳过已入组病例——补录的编码永远不生效。
     */
    @PostMapping("/regroup/{admissionId}")
    @Transactional
    public R<Map<String, Object>> regroupOne(@PathVariable Long admissionId) {
        Integer discharged = jdbc.queryForObject(
                "select count(*) from inp_admission where id = ? and status = 'DISCHARGED'",
                Integer.class, admissionId);
        if (discharged == null || discharged == 0) return R.fail(9930, "住院记录不存在或未出院");
        jdbc.update("delete from drg_case where admission_id = ?", admissionId);
        return R.ok(doGroup());
    }

    private Map<String, Object> doGroup() {
        var pending = jdbc.queryForList("""
                select a.id, coalesce(a.discharge_diag_icd, a.admit_diag_icd) as admit_diag_icd,
                       coalesce(s.total_amount, 0) as total_cost,
                       round((extract(epoch from (a.discharged_at - a.admit_at)) / 86400)::numeric, 1) as inp_days,
                       exists (select 1 from inp_surgery g where g.admission_id = a.id and g.status = 'DONE') as has_surgery
                from inp_admission a
                left join inp_settlement s on s.admission_id = a.id
                where a.status = 'DISCHARGED'
                  and not exists (select 1 from drg_case c where c.admission_id = a.id)
                """);
        var defs = jdbc.queryForList("select * from drg_group_def");
        var ccList = jdbc.queryForList("select * from drg_cc_list");
        int grouped = 0, ambiguous = 0;
        for (var adm : pending) {
            Long admissionId = ((Number) adm.get("id")).longValue();
            String icd = (String) adm.get("admit_diag_icd");
            boolean hasSurgery = Boolean.TRUE.equals(adm.get("has_surgery"));
            Map<String, Object> hit = match(defs, icd, hasSurgery);
            String adrg, drgCode, drgName, severity;
            BigDecimal baseWeight, weight;
            if (hit == null) {
                adrg = drgCode = "QY";
                drgName = "歧义病例（未入组）";
                severity = "NONE";
                baseWeight = weight = BigDecimal.ZERO;
            } else {
                adrg = (String) hit.get("adrg_code");
                severity = severityOf(admissionId, ccList);
                String suffix = switch (severity) {
                    case "MCC" -> "1";
                    case "CC" -> "3";
                    default -> "5";
                };
                BigDecimal factor = switch (severity) {
                    case "MCC" -> MCC_FACTOR;
                    case "CC" -> CC_FACTOR;
                    default -> BigDecimal.ONE;
                };
                drgCode = adrg + suffix;
                drgName = hit.get("drg_name") + switch (severity) {
                    case "MCC" -> "，伴严重并发症";
                    case "CC" -> "，伴并发症";
                    default -> "，不伴并发症";
                };
                baseWeight = (BigDecimal) hit.get("weight");
                weight = baseWeight.multiply(factor).setScale(4, RoundingMode.HALF_UP);
            }
            jdbc.update("""
                    insert into drg_case(admission_id, adrg_code, drg_code, drg_name, severity,
                                         base_weight, weight, total_cost, inp_days)
                    values (?,?,?,?,?,?,?,?,?)
                    """, admissionId, adrg, drgCode, drgName, severity, baseWeight, weight,
                    adm.get("total_cost"), adm.get("inp_days"));
            if (hit == null) ambiguous++; else grouped++;
        }
        return Map.of("grouped", grouped, "ambiguous", ambiguous);
    }

    /** 严重程度：其他诊断命中 MCC 目录 > CC 目录 > 无 */
    private String severityOf(Long admissionId, List<Map<String, Object>> ccList) {
        var diags = jdbc.queryForList(
                "select icd from inp_diagnosis where admission_id = ?", String.class, admissionId);
        boolean cc = false;
        for (String d : diags) {
            for (var rule : ccList) {
                if (d.startsWith((String) rule.get("icd_prefix"))) {
                    if ("MCC".equals(rule.get("level"))) return "MCC";
                    cc = true;
                }
            }
        }
        return cc ? "CC" : "NONE";
    }

    /** ADRG 命中：前缀匹配中手术标记精确优先，内科组兜底 */
    private Map<String, Object> match(List<Map<String, Object>> defs, String icd, boolean hasSurgery) {
        if (icd == null || icd.isBlank()) return null;
        Map<String, Object> fallback = null;
        for (var d : defs) {
            boolean prefixHit = false;
            for (String p : ((String) d.get("icd_prefixes")).split(",")) {
                if (icd.startsWith(p.trim())) {
                    prefixHit = true;
                    break;
                }
            }
            if (!prefixHit) continue;
            boolean surgical = Boolean.TRUE.equals(d.get("surgical"));
            if (surgical == hasSurgery) return d;
            if (!surgical) fallback = d;
        }
        return fallback;
    }

    private BigDecimal rate() {
        var rows = jdbc.queryForList("select cfg_value from sys_config where cfg_key = 'drg_rate'", String.class);
        return rows.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rows.get(0));
    }

    /** 病组分析：例数/权重/费用/住院日 + CMI、费用消耗指数、时间消耗指数、支付模拟（标杆/结余） */
    @GetMapping("/analysis")
    public R<Map<String, Object>> analysis() {
        BigDecimal rate = rate();
        var groups = jdbc.queryForList("""
                select drg_code, max(drg_name) as drg_name, max(weight) as weight, max(severity) as severity,
                       count(*) as cases, round(avg(total_cost), 2) as avg_cost, round(avg(inp_days), 1) as avg_days,
                       round(sum(total_cost), 2) as sum_cost
                from drg_case group by drg_code order by cases desc, drg_code
                """);
        var totals = jdbc.queryForMap("""
                select count(*) as cases, coalesce(sum(weight), 0) as total_weight,
                       coalesce(sum(total_cost), 0) as total_cost, coalesce(sum(inp_days), 0) as total_days,
                       count(*) filter (where drg_code = 'QY') as ambiguous
                from drg_case
                """);
        BigDecimal totalWeight = (BigDecimal) totals.get("total_weight");
        long cases = ((Number) totals.get("cases")).longValue();
        BigDecimal totalCost = (BigDecimal) totals.get("total_cost");
        BigDecimal totalDays = (BigDecimal) totals.get("total_days");
        BigDecimal costPerWeight = totalWeight.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(totalWeight, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal daysPerWeight = totalWeight.compareTo(BigDecimal.ZERO) > 0
                ? totalDays.divide(totalWeight, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal totalBenchmark = BigDecimal.ZERO;
        for (var g : groups) {
            BigDecimal w = (BigDecimal) g.get("weight");
            BigDecimal avgCost = (BigDecimal) g.get("avg_cost");
            BigDecimal avgDays = (BigDecimal) g.get("avg_days");
            long groupCases = ((Number) g.get("cases")).longValue();
            if (w.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal benchmark = w.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                g.put("benchmark_pay", benchmark);
                g.put("balance", benchmark.subtract(avgCost).setScale(2, RoundingMode.HALF_UP));
                totalBenchmark = totalBenchmark.add(benchmark.multiply(BigDecimal.valueOf(groupCases)));
                g.put("cost_index", costPerWeight.compareTo(BigDecimal.ZERO) > 0
                        ? avgCost.divide(w.multiply(costPerWeight), 2, RoundingMode.HALF_UP) : null);
                g.put("time_index", daysPerWeight.compareTo(BigDecimal.ZERO) > 0
                        ? avgDays.divide(w.multiply(daysPerWeight), 2, RoundingMode.HALF_UP) : null);
            } else {
                g.put("benchmark_pay", null);
                g.put("balance", null);
                g.put("cost_index", null);
                g.put("time_index", null);
            }
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("groups", groups);
        m.put("cases", cases);
        m.put("ambiguous", totals.get("ambiguous"));
        m.put("totalWeight", totalWeight);
        m.put("cmi", cases == 0 ? 0 : totalWeight.divide(BigDecimal.valueOf(cases), 4, RoundingMode.HALF_UP));
        m.put("rate", rate);
        m.put("totalBenchmark", totalBenchmark.setScale(2, RoundingMode.HALF_UP));
        m.put("totalCost", totalCost);
        m.put("totalBalance", totalBenchmark.subtract(totalCost).setScale(2, RoundingMode.HALF_UP));
        return R.ok(m);
    }

    /** 科室 CMI 对比 */
    @GetMapping("/dept-analysis")
    public R<List<Map<String, Object>>> deptAnalysis() {
        return R.ok(jdbc.queryForList("""
                select d.name as dept_name, count(*) as cases,
                       round(sum(c.weight), 4) as total_weight,
                       round(sum(c.weight) / count(*), 4) as cmi,
                       round(avg(c.total_cost), 2) as avg_cost
                from drg_case c
                join inp_admission a on a.id = c.admission_id
                join sys_dept d on d.id = a.dept_id
                group by d.name order by cmi desc
                """));
    }

    /** 入组明细：含严重程度、标杆支付、结余、高低倍率标记 */
    @GetMapping("/cases")
    public R<List<Map<String, Object>>> cases() {
        BigDecimal rate = rate();
        var rows = jdbc.queryForList("""
                select c.*, a.admission_no, a.admit_diag_icd, a.admit_diag_name, p.name as patient_name,
                       (select count(*) from inp_diagnosis x where x.admission_id = a.id) as other_diags
                from drg_case c
                join inp_admission a on a.id = c.admission_id
                join empi_patient p on p.id = a.patient_id
                order by c.id desc limit 200
                """);
        for (var r : rows) {
            BigDecimal w = (BigDecimal) r.get("weight");
            BigDecimal cost = (BigDecimal) r.get("total_cost");
            if (w.compareTo(BigDecimal.ZERO) > 0 && rate.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal benchmark = w.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                r.put("benchmark_pay", benchmark);
                r.put("balance", benchmark.subtract(cost).setScale(2, RoundingMode.HALF_UP));
                BigDecimal ratio = cost.divide(benchmark, 2, RoundingMode.HALF_UP);
                // 高/低倍率病例（费用极端值，医保单议）：<0.3 低倍率，>3 高倍率
                r.put("pay_flag", ratio.compareTo(new BigDecimal("0.3")) < 0 ? "LOW"
                        : ratio.compareTo(new BigDecimal("3")) > 0 ? "HIGH" : "NORMAL");
            } else {
                r.put("benchmark_pay", null);
                r.put("balance", null);
                r.put("pay_flag", null);
            }
        }
        return R.ok(rows);
    }
}
