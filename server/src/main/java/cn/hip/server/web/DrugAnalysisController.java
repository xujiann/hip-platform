package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三十四期：药事分析——全院/中西药使用汇总、抗菌药使用强度（DDD 雏形）、经费调查表 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/drug-analysis")
public class DrugAnalysisController {

    private final JdbcTemplate jdbc;

    /** 全院药品使用汇总（门诊已收费口径，TOP30） */
    @GetMapping("/usage")
    public R<List<Map<String, Object>>> usage() {
        return R.ok(jdbc.queryForList("""
                select o.item_name, d.drug_class, d.antibiotic,
                       sum(o.qty) as total_qty, round(sum(o.amount), 2) as total_amount,
                       count(distinct o.registration_id) as visits
                from outp_order o
                join md_drug d on d.id = o.item_id
                where o.order_type = 'DRUG' and o.status in ('CHARGED', 'DISPENSED')
                group by o.item_name, d.drug_class, d.antibiotic
                order by total_amount desc limit 30
                """));
    }

    /** 中西药构成 */
    @GetMapping("/class-summary")
    public R<List<Map<String, Object>>> classSummary() {
        return R.ok(jdbc.queryForList("""
                select case d.drug_class when 'C' then '中成药' else '西药' end as drug_class,
                       count(distinct o.item_id) as varieties,
                       sum(o.qty) as total_qty, round(sum(o.amount), 2) as total_amount
                from outp_order o
                join md_drug d on d.id = o.item_id
                where o.order_type = 'DRUG' and o.status in ('CHARGED', 'DISPENSED')
                group by d.drug_class order by total_amount desc
                """));
    }

    /** 抗菌药使用强度 AUD（DDDs×100/出院患者床日数，雏形口径） */
    @GetMapping("/aud")
    public R<Map<String, Object>> aud() {
        BigDecimal ddds = jdbc.queryForObject("""
                select coalesce(sum(o.qty * d.ddd_per_unit), 0)
                from outp_order o join md_drug d on d.id = o.item_id
                where o.order_type = 'DRUG' and o.status in ('CHARGED', 'DISPENSED')
                  and d.antibiotic and d.ddd_per_unit is not null
                """, BigDecimal.class);
        BigDecimal bedDays = jdbc.queryForObject("""
                select coalesce(sum(greatest(extract(epoch from (discharged_at - admit_at)) / 86400, 1)), 0)::numeric
                from inp_admission where status = 'DISCHARGED'
                """, BigDecimal.class);
        var m = new LinkedHashMap<String, Object>();
        m.put("ddds", ddds.setScale(2, RoundingMode.HALF_UP));
        m.put("bedDays", bedDays.setScale(1, RoundingMode.HALF_UP));
        m.put("aud", bedDays.compareTo(BigDecimal.ZERO) > 0
                ? ddds.multiply(BigDecimal.valueOf(100)).divide(bedDays, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        m.put("note", "AUD = 抗菌药 DDDs×100 / 出院患者床日数（雏形口径：门诊消耗+住院床日）");
        return R.ok(m);
    }

    /** 药品经费调查表（按月汇总） */
    @GetMapping("/monthly-fund")
    public R<List<Map<String, Object>>> monthlyFund() {
        return R.ok(jdbc.queryForList("""
                select to_char(o.created_at, 'YYYY-MM') as month,
                       round(sum(o.amount) filter (where d.drug_class = 'W'), 2) as western,
                       round(sum(o.amount) filter (where d.drug_class = 'C'), 2) as chinese,
                       round(sum(o.amount) filter (where d.antibiotic), 2) as antibiotic,
                       round(sum(o.amount), 2) as total
                from outp_order o join md_drug d on d.id = o.item_id
                where o.order_type = 'DRUG' and o.status in ('CHARGED', 'DISPENSED')
                group by to_char(o.created_at, 'YYYY-MM') order by month desc limit 12
                """));
    }
}
