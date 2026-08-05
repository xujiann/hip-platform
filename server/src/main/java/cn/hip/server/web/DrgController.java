package cn.hip.server.web;

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

/** 二十八期：DRG 分组器——CHS-DRG 简化规则（主诊断前缀 + 手术操作分列），CMI/费用消耗指数分析 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/drg")
public class DrgController {

    private final JdbcTemplate jdbc;

    @GetMapping("/groups")
    public R<List<Map<String, Object>>> groups() {
        return R.ok(jdbc.queryForList("select * from drg_group_def order by mdc_code, drg_code"));
    }

    /** 批量入组：已出院且未入组的病例。规则：主诊断 ICD 前缀命中 → 有手术记录优先手术组；无命中 → QY 歧义组 */
    @PostMapping("/group-all")
    @Transactional
    public R<Map<String, Object>> groupAll() {
        var pending = jdbc.queryForList("""
                select a.id, a.admit_diag_icd,
                       coalesce(s.total_amount, 0) as total_cost,
                       round((extract(epoch from (a.discharged_at - a.admit_at)) / 86400)::numeric, 1) as inp_days,
                       exists (select 1 from inp_surgery g where g.admission_id = a.id and g.status <> 'CANCELLED') as has_surgery
                from inp_admission a
                left join inp_settlement s on s.admission_id = a.id
                where a.status = 'DISCHARGED'
                  and not exists (select 1 from drg_case c where c.admission_id = a.id)
                """);
        var defs = jdbc.queryForList("select * from drg_group_def");
        int grouped = 0, ambiguous = 0;
        for (var adm : pending) {
            String icd = (String) adm.get("admit_diag_icd");
            boolean hasSurgery = Boolean.TRUE.equals(adm.get("has_surgery"));
            Map<String, Object> hit = match(defs, icd, hasSurgery);
            String code = hit == null ? "QY" : (String) hit.get("drg_code");
            String name = hit == null ? "歧义病例（未入组）" : (String) hit.get("drg_name");
            BigDecimal weight = hit == null ? BigDecimal.ZERO : (BigDecimal) hit.get("weight");
            jdbc.update("""
                    insert into drg_case(admission_id, drg_code, drg_name, weight, total_cost, inp_days)
                    values (?,?,?,?,?,?)
                    """, adm.get("id"), code, name, weight, adm.get("total_cost"), adm.get("inp_days"));
            if (hit == null) ambiguous++; else grouped++;
        }
        return R.ok(Map.of("grouped", grouped, "ambiguous", ambiguous));
    }

    /** 命中规则：前缀匹配的组里，按手术标记精确匹配优先，其次内科组兜底 */
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
            if (!surgical) fallback = d;   // 有手术但无手术组定义时退回内科组
        }
        return fallback;
    }

    /** 病组分析：各组例数/权重/次均费用/平均住院日 + 全院 CMI、费用消耗指数 */
    @GetMapping("/analysis")
    public R<Map<String, Object>> analysis() {
        var groups = jdbc.queryForList("""
                select drg_code, drg_name, max(weight) as weight, count(*) as cases,
                       round(avg(total_cost), 2) as avg_cost, round(avg(inp_days), 1) as avg_days,
                       round(sum(total_cost), 2) as sum_cost
                from drg_case group by drg_code, drg_name order by cases desc, drg_code
                """);
        var totals = jdbc.queryForMap("""
                select count(*) as cases, coalesce(sum(weight), 0) as total_weight,
                       coalesce(sum(total_cost), 0) as total_cost,
                       count(*) filter (where drg_code = 'QY') as ambiguous
                from drg_case
                """);
        BigDecimal totalWeight = (BigDecimal) totals.get("total_weight");
        long cases = ((Number) totals.get("cases")).longValue();
        BigDecimal totalCost = (BigDecimal) totals.get("total_cost");
        // 全院费用/权重基准（每权重费用）
        BigDecimal costPerWeight = totalWeight.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(totalWeight, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        for (var g : groups) {
            BigDecimal w = (BigDecimal) g.get("weight");
            BigDecimal avgCost = (BigDecimal) g.get("avg_cost");
            // 费用消耗指数 = 组次均费用 / (权重 × 每权重基准费用)；QY 组权重 0 不计
            if (w.compareTo(BigDecimal.ZERO) > 0 && costPerWeight.compareTo(BigDecimal.ZERO) > 0) {
                g.put("cost_index", avgCost.divide(w.multiply(costPerWeight), 2, RoundingMode.HALF_UP));
            } else {
                g.put("cost_index", null);
            }
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("groups", groups);
        m.put("cases", cases);
        m.put("ambiguous", totals.get("ambiguous"));
        m.put("totalWeight", totalWeight);
        m.put("cmi", cases == 0 ? 0 : totalWeight.divide(BigDecimal.valueOf(cases), 4, RoundingMode.HALF_UP));
        return R.ok(m);
    }

    /** 入组明细 */
    @GetMapping("/cases")
    public R<List<Map<String, Object>>> cases() {
        return R.ok(jdbc.queryForList("""
                select c.*, a.admission_no, a.admit_diag_icd, a.admit_diag_name, p.name as patient_name
                from drg_case c
                join inp_admission a on a.id = c.admission_id
                join empi_patient p on p.id = a.patient_id
                order by c.id desc limit 200
                """));
    }
}
