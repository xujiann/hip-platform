package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运营统计（聚合层，跨模块只读查询） */
@RestController
@RequestMapping("/api/stats")
@PreAuthorize("hasAnyRole('ADMIN','OPERATION','QUALITY')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class StatsController {

    private final JdbcTemplate jdbc;

    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        var m = new LinkedHashMap<String, Object>();
        m.put("todayRegistrations", jdbc.queryForObject(
                "select count(*) from outp_registration where visit_date = current_date and status <> 'CANCELLED'", Long.class));
        m.put("todayOutpRevenue", jdbc.queryForObject(
                "select coalesce(sum(total_amount), 0) from outp_charge where status = 'PAID' and created_at::date = current_date", Double.class));
        m.put("todayInpRevenue", jdbc.queryForObject(
                "select coalesce(sum(total_amount), 0) from inp_settlement where created_at::date = current_date", Double.class));
        m.put("inHospitalCount", jdbc.queryForObject(
                "select count(*) from inp_admission where status = 'IN_HOSPITAL'", Long.class));
        m.put("bedTotal", jdbc.queryForObject("select count(*) from inp_bed", Long.class));
        m.put("bedOccupied", jdbc.queryForObject(
                "select count(*) from inp_bed where status = 'OCCUPIED'", Long.class));
        m.put("pendingCharges", jdbc.queryForObject(
                "select count(distinct registration_id) from outp_order where status = 'CREATED'", Long.class));
        m.put("pendingDispense", jdbc.queryForObject(
                "select count(distinct registration_id) from outp_order where status = 'CHARGED' and order_type = 'DRUG'", Long.class));
        m.put("pendingExec", jdbc.queryForObject(
                "select count(*) from outp_order where status = 'CHARGED' and order_type in ('LAB','EXAM','TREAT')", Long.class));
        m.put("pendingInpOrders", jdbc.queryForObject(
                "select count(*) from inp_order where status = 'CREATED'", Long.class));
        m.put("lowStockDrugs", jdbc.queryForObject(
                "select count(*) from md_drug where enabled and stock < 100", Long.class));
        m.put("pendingCriticalAlerts", jdbc.queryForObject(
                "select count(*) from outp_critical_alert where status = 'NEW'", Long.class));
        return R.ok(m);
    }

    /** 运营指标：药占比、均次费用、平均住院日、病组分析（DRG 雏形） */
    @GetMapping("/operation")
    public R<Map<String, Object>> operation() {
        var m = new LinkedHashMap<String, Object>();
        Double drugRevenue = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from outp_order
                where order_type = 'DRUG' and status in ('CHARGED','DISPENSED')
                """, Double.class);
        Double totalRevenue = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from outp_order
                where status in ('CHARGED','DISPENSED','EXECUTED')
                """, Double.class);
        m.put("drugRevenue", drugRevenue);
        m.put("outpOrderRevenue", totalRevenue);
        m.put("drugRatio", totalRevenue == 0 ? 0 : Math.round(drugRevenue / totalRevenue * 1000) / 10.0);
        m.put("avgOutpCost", jdbc.queryForObject("""
                select coalesce(round(sum(total_amount) / nullif(count(distinct registration_id), 0), 2), 0)
                from outp_charge where status = 'PAID'
                """, Double.class));
        m.put("dischargedCount", jdbc.queryForObject(
                "select count(*) from inp_admission where status = 'DISCHARGED'", Long.class));
        m.put("avgInpDays", jdbc.queryForObject("""
                select coalesce(round(avg(extract(epoch from (discharged_at - admit_at)) / 86400)::numeric, 1), 0)
                from inp_admission where status = 'DISCHARGED'
                """, Double.class));
        m.put("avgInpCost", jdbc.queryForObject(
                "select coalesce(round(avg(total_amount), 2), 0) from inp_settlement", Double.class));
        m.put("diagnosisGroups", jdbc.queryForList("""
                select coalesce(substring(a.admit_diag_icd, 1, 3), '未编码') as icd_group,
                       max(coalesce(a.admit_diag_name, '未填写诊断')) as sample_name,
                       count(*) as cases,
                       coalesce(round(avg(s.total_amount), 2), 0) as avg_cost
                from inp_admission a
                left join inp_settlement s on s.admission_id = a.id
                where a.status = 'DISCHARGED'
                group by substring(a.admit_diag_icd, 1, 3)
                order by cases desc
                limit 20
                """));
        return R.ok(m);
    }

    /** 近 N 日门诊挂号量与收入 */
    @GetMapping("/daily")
    public R<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "7") int days) {
        return R.ok(jdbc.queryForList("""
                select d.day::date as day,
                       coalesce(r.cnt, 0)   as registrations,
                       coalesce(c.amt, 0)   as revenue
                from generate_series(current_date - (? - 1) * interval '1 day', current_date, interval '1 day') as d(day)
                left join (select visit_date, count(*) cnt from outp_registration
                           where status <> 'CANCELLED' group by visit_date) r on r.visit_date = d.day::date
                left join (select created_at::date cd, sum(total_amount) amt from outp_charge
                           where status = 'PAID' group by created_at::date) c on c.cd = d.day::date
                order by d.day
                """, days));
    }
}
