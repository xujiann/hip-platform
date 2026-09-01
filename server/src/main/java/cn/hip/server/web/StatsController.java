package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.hip.platform.core.config.BusinessDates;

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

    // ==================== v41 科室月度经营报表 + 医生工作量 ====================

    /**
     * 科室月报口径（门诊 outp_charge×挂号科室 / 住院 inp_settlement×入院科室）。
     *
     * <p><b>住院侧只认 settle_type = 'FINAL'</b>：INTERIM 中间结算是住院期间对"已发生费用"
     * 开的阶段性凭据，其金额恒为出院结算总额的**子集**（V90/InpatientService.interimSettle：
     * discharge 的总额始终按医嘱台账现算，从不读结算行相加）。因此把 INTERIM 与 FINAL 一起 sum
     * 就是同一笔费用被计两次——收入凭空翻番。这是 v30 已付学费的坑，此处与 /operation 的
     * avgInpCost、/overview 的 todayInpRevenue 保持同一条过滤。
     * status = 'PAID' 同时排除已冲销（CANCELLED）的出院结算。
     *
     * <p>月份一律半开区间 [月初, 次月初)：`to_char(created_at,'YYYY-MM') = ?` 或 `created_at::date`
     * 把函数套在列上会废掉 created_at 索引（1.1.3 起全库口径）。
     */
    private static final String DEPT_MONTHLY_SQL = """
            with outp as (
                select r.dept_id,
                       sum(c.total_amount)              as revenue,
                       count(distinct c.registration_id) as visits
                from outp_charge c
                join outp_registration r on r.id = c.registration_id
                where c.status = 'PAID'
                  and c.created_at >= ?::date and c.created_at < (?::date + interval '1 month')
                group by r.dept_id
            ), inp as (
                select a.dept_id,
                       sum(s.total_amount) as revenue,
                       count(*)            as discharges
                from inp_settlement s
                join inp_admission a on a.id = s.admission_id
                where s.settle_type = 'FINAL' and s.status = 'PAID'
                  and s.created_at >= ?::date and s.created_at < (?::date + interval '1 month')
                group by a.dept_id
            )
            select d.id as dept_id, d.name as dept_name,
                   coalesce(o.revenue, 0)    as outp_revenue,
                   coalesce(o.visits, 0)     as outp_visits,
                   coalesce(round(o.revenue / nullif(o.visits, 0), 2), 0) as outp_avg_cost,
                   coalesce(i.revenue, 0)    as inp_revenue,
                   coalesce(i.discharges, 0) as inp_discharges,
                   coalesce(round(i.revenue / nullif(i.discharges, 0), 2), 0) as inp_avg_cost,
                   coalesce(o.revenue, 0) + coalesce(i.revenue, 0) as total_revenue
            from sys_dept d
            left join outp o on o.dept_id = d.id
            left join inp  i on i.dept_id = d.id
            where o.dept_id is not null or i.dept_id is not null
            order by total_revenue desc, d.id
            """;

    /** 医生工作量：接诊量按挂号医生，处方金额按该医生名下挂号已收费的医嘱行合计（未收费/已退不计） */
    private static final String BY_DOCTOR_SQL = """
            select r.doctor_id,
                   coalesce(u.real_name, '未指定医生') as doctor_name,
                   coalesce(d.name, '-')              as dept_name,
                   count(distinct r.id)               as visits,
                   coalesce(sum(o.amount) filter (
                       where o.status in ('CHARGED', 'DISPENSED', 'EXECUTED')), 0) as order_amount
            from outp_registration r
            left join sys_user u    on u.id = r.doctor_id
            left join sys_dept d    on d.id = r.dept_id
            left join outp_order o  on o.registration_id = r.id
            where r.visit_date >= ?::date and r.visit_date < (?::date + interval '1 month')
              and r.status <> 'CANCELLED'
            group by r.doctor_id, u.real_name, d.name
            order by visits desc, order_amount desc
            limit 50
            """;

    /**
     * 科室月度经营报表（门诊/住院收入、人次、均次费用）+ 医生工作量排行。
     * 管理决策数据（含科室收入与医生处方金额排行），限管理/运营/质控角色——
     * 类上的 isAuthenticated() 是驾驶舱口径，方法级注解在此收窄。
     */
    @GetMapping("/dept-monthly")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATION','QUALITY')")
    public R<Map<String, Object>> deptMonthly(@RequestParam(required = false) String month) {
        String start = monthStart(month);
        List<Map<String, Object>> depts = jdbc.queryForList(DEPT_MONTHLY_SQL, start, start, start, start);

        // 合计在 Java 侧按 BigDecimal 累加（1.1.4 B-9：金额不过 Double），省一次全表聚合
        BigDecimal outpRevenue = BigDecimal.ZERO, inpRevenue = BigDecimal.ZERO;
        long outpVisits = 0, inpDischarges = 0;
        for (var d : depts) {
            outpRevenue = outpRevenue.add((BigDecimal) d.get("outp_revenue"));
            inpRevenue = inpRevenue.add((BigDecimal) d.get("inp_revenue"));
            outpVisits += ((Number) d.get("outp_visits")).longValue();
            inpDischarges += ((Number) d.get("inp_discharges")).longValue();
        }
        var totals = new LinkedHashMap<String, Object>();
        totals.put("outpRevenue", outpRevenue);
        totals.put("outpVisits", outpVisits);
        totals.put("inpRevenue", inpRevenue);
        totals.put("inpDischarges", inpDischarges);
        totals.put("totalRevenue", outpRevenue.add(inpRevenue));

        var m = new LinkedHashMap<String, Object>();
        m.put("month", start.substring(0, 7));
        m.put("depts", depts);
        m.put("byDoctor", jdbc.queryForList(BY_DOCTOR_SQL, start, start));
        m.put("totals", totals);
        m.put("note", "住院收入只计出院结算（FINAL），中间结算（INTERIM）为其子集，计入即重复计费");
        return R.ok(m);
    }

    /** 科室月报 CSV 导出（与 /dept-monthly 同口径同 SQL；医生排行为另一张表，留在页面上不混排） */
    @GetMapping(value = "/dept-monthly.csv", produces = "text/csv;charset=UTF-8")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATION','QUALITY')")
    public String deptMonthlyCsv(@RequestParam(required = false) String month) {
        String start = monthStart(month);
        var rows = jdbc.queryForList(DEPT_MONTHLY_SQL, start, start, start, start);
        var sb = new StringBuilder("﻿月份,科室,门诊收入,门诊人次,门诊均次费用,住院收入,出院人次,住院均次费用,合计收入\n");
        for (var r : rows) {
            sb.append("%s,%s,%s,%s,%s,%s,%s,%s,%s\n".formatted(
                    csv(start.substring(0, 7)), csv(r.get("dept_name")),
                    csv(r.get("outp_revenue")), csv(r.get("outp_visits")), csv(r.get("outp_avg_cost")),
                    csv(r.get("inp_revenue")), csv(r.get("inp_discharges")), csv(r.get("inp_avg_cost")),
                    csv(r.get("total_revenue"))));
        }
        return sb.toString();
    }

    /** 月份 → 月初日期串；非法入参抛 DateTimeParseException 由全局处理器转 4000（同 PrintReportController 预校验） */
    private static String monthStart(String month) {
        YearMonth ym = month == null || month.isBlank()
                ? YearMonth.from(BusinessDates.today())
                : YearMonth.parse(month.strip());
        return ym.atDay(1).toString();
    }

    /**
     * CSV 字段转义 —— 与 PrintReportController.csv 逐字同款（含公式注入守卫）：
     * 科室/医生名是维护端可写入的自由文本，以 = + - @ 开头会被 Excel 当公式执行；含逗号会串列。
     * 数值不加 ' 前缀（1.2.3 五轮 P1-3：加了在 Excel 里变文本，SUM 跳过，金额列合计对不上）。
     * 两处各自私有：本轮只读报表不改 PrintReportController，抽公共工具类留作后续小重构。
     */
    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (!(v instanceof Number) && !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n")
                ? "\"" + s.replace("\"", "\"\"") + "\""
                : s;
    }
}
