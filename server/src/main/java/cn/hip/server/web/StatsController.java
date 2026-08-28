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
// 驾驶舱是所有角色的登录首页：限成三个角色会让其余角色一进系统就满屏 403。
// 这里放行全体在职员工（PORTAL 已在 SecurityConfig 整体隔离），敏感明细各自在专页限权。
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class StatsController {

    private final JdbcTemplate jdbc;

    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        var m = new LinkedHashMap<String, Object>();
        m.put("todayRegistrations", jdbc.queryForObject(
                "select count(*) from outp_registration where visit_date = current_date and status <> 'CANCELLED'", Long.class));
        m.put("todayOutpRevenue", jdbc.queryForObject(
                "select coalesce(sum(total_amount), 0) from outp_charge where status = 'PAID' and created_at >= current_date and created_at < current_date + 1", java.math.BigDecimal.class));
        m.put("todayInpRevenue", jdbc.queryForObject(
                "select coalesce(sum(total_amount), 0) from inp_settlement where settle_type = 'FINAL' and created_at >= current_date and created_at < current_date + 1", java.math.BigDecimal.class));
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
        // 金额一律 BigDecimal（1.1.4 B-9）：sum(numeric(12,2)) 走 Double 会出现
        // 12345.670000000001 一类数值，且与按 BigDecimal 计算的日结对不上账
        var drugRevenue = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from outp_order
                where order_type = 'DRUG' and status in ('CHARGED','DISPENSED')
                """, java.math.BigDecimal.class);
        var totalRevenue = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from outp_order
                where status in ('CHARGED','DISPENSED','EXECUTED')
                """, java.math.BigDecimal.class);
        m.put("drugRevenue", drugRevenue);
        m.put("outpOrderRevenue", totalRevenue);
        m.put("drugRatio", totalRevenue.signum() == 0 ? java.math.BigDecimal.ZERO
                : drugRevenue.multiply(new java.math.BigDecimal(100))
                        .divide(totalRevenue, 1, java.math.RoundingMode.HALF_UP));
        m.put("avgOutpCost", jdbc.queryForObject("""
                select coalesce(round(sum(total_amount) / nullif(count(distinct registration_id), 0), 2), 0)
                from outp_charge where status = 'PAID'
                """, java.math.BigDecimal.class));
        m.put("dischargedCount", jdbc.queryForObject(
                "select count(*) from inp_admission where status = 'DISCHARGED'", Long.class));
        m.put("avgInpDays", jdbc.queryForObject("""
                select coalesce(round(avg(extract(epoch from (discharged_at - admit_at)) / 86400)::numeric, 1), 0)
                from inp_admission where status = 'DISCHARGED'
                """, java.math.BigDecimal.class));
        m.put("avgInpCost", jdbc.queryForObject(
                "select coalesce(round(avg(total_amount), 2), 0) from inp_settlement where status = 'PAID' and settle_type = 'FINAL'",
                java.math.BigDecimal.class));
        m.put("diagnosisGroups", jdbc.queryForList("""
                select coalesce(substring(a.admit_diag_icd, 1, 3), '未编码') as icd_group,
                       max(coalesce(a.admit_diag_name, '未填写诊断')) as sample_name,
                       count(*) as cases,
                       coalesce(round(avg(s.total_amount), 2), 0) as avg_cost
                from inp_admission a
                left join inp_settlement s on s.admission_id = a.id and s.status = 'PAID' and s.settle_type = 'FINAL'
                where a.status = 'DISCHARGED'
                group by substring(a.admit_diag_icd, 1, 3)
                order by cases desc
                limit 20
                """));
        return R.ok(m);
    }

    /** 近 N 日门诊挂号量与收入（上限 366：generate_series 无界时可被一个参数打出上亿行，B-17） */
    @GetMapping("/daily")
    public R<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 366) {
            return R.fail(4001, "days 须在 1–366 之间");
        }
        return R.ok(jdbc.queryForList("""
                select d.day::date as day,
                       coalesce(r.cnt, 0)   as registrations,
                       coalesce(c.amt, 0)   as revenue
                from generate_series(current_date - (? - 1) * interval '1 day', current_date, interval '1 day') as d(day)
                left join (select visit_date, count(*) cnt from outp_registration
                           where status <> 'CANCELLED'
                             and visit_date >= current_date - (? - 1) * interval '1 day'
                           group by visit_date) r on r.visit_date = d.day::date
                left join (select created_at::date cd, sum(total_amount) amt from outp_charge
                           where status = 'PAID'
                             and created_at >= current_date - (? - 1) * interval '1 day'
                           group by created_at::date) c on c.cd = d.day::date
                order by d.day
                """, days, days, days));
        // 子查询带日期下界（1.1.7）：原先要 7 天数据把整张挂号/收费表聚合一遍——
        // 这是工作台图表端点，数据过百万行后每次数百毫秒的两次全表聚合
    }
}
