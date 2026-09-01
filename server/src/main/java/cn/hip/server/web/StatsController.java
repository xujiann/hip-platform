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

    // ==================== v42 费别 / 费用类别金额汇总（诚信补齐 ★） ====================
    //
    // 补齐验收偏离表已答"平台已实现"而此前代码零实现的三条：
    //   1034★「拥有费别分类的金额汇总」、675「可按费用类别统计门诊总费用、退费金额」、
    //   3684「按医保政策、费用类别、诊疗项目等维度对医保患者住院费用精准统计汇总」。
    // 全部**纯只读**：不新增任何写路径、不在 inp_order/outp_order/outp_charge/inp_settlement
    // 上落费别或费用类别快照列（那要动 v39 刚重构的长期医嘱模型与挂着四处联动的 ChargeService.settle）。

    /**
     * 费别归集口径：只认 SELF / YB_STAFF / YB_RESIDENT 三个规范值，其余一律进 'OTHER'。
     *
     * <p><b>为什么不把 YB_EMPLOYEE 并进职工行</b>：它是 V53 白名单里的历史遗留值，
     * InsuranceSplitService 出于兼容把它按职工待遇处理，但报表侧并进去会把"数据质量问题"
     * 掩盖成"看起来正常的职工医保收入"。显式落在「其他」行里既不丢金额（合计仍对得上），
     * 又让实施方一眼看见还有多少患者的参保类型没订正。null 同理进「其他」而非被 join 丢弃。
     */
    private static final String INS_BUCKET =
            "case when p.insurance_type in ('SELF','YB_STAFF','YB_RESIDENT') then p.insurance_type else 'OTHER' end";

    /**
     * 按费别汇总金额（门诊 + 住院，笔数/金额/均次/占比/退费）。
     *
     * <p><b>严禁改用 yb_settle_split 出这张表</b>：该表只在 pay_method='YB' 时才写入
     * （ChargeService:65 / InpatientService:434）——自费单与"参保人用现金付"的单根本不在里面，
     * 直接汇总会漏掉绝大多数业务量。故一律 join empi_patient 取费别，与支付方式彻底解耦。
     *
     * <p>住院侧只认 settle_type='FINAL'（与 /dept-monthly 同一条过滤）：INTERIM 中间结算金额
     * 恒为出院总额的子集，一起 sum 就是同一笔费用计两次。
     */
    private static final String FEE_BY_INSURANCE_SQL = """
            with outp_paid as (
                select %1$s as ins, count(*) as bills, sum(c.total_amount) as amount
                from outp_charge c
                join outp_registration r on r.id = c.registration_id
                join empi_patient p on p.id = r.patient_id
                where c.status = 'PAID'
                  and c.created_at >= ?::date and c.created_at < (?::date + interval '1 month')
                group by 1
            ), outp_refund as (
                select %1$s as ins, count(*) as bills, sum(c.total_amount) as amount
                from outp_charge c
                join outp_registration r on r.id = c.registration_id
                join empi_patient p on p.id = r.patient_id
                where c.status = 'REFUNDED' and c.refunded_at is not null
                  and c.refunded_at >= ?::date and c.refunded_at < (?::date + interval '1 month')
                group by 1
            ), inp_paid as (
                select %1$s as ins, count(*) as bills, sum(s.total_amount) as amount
                from inp_settlement s
                join inp_admission a on a.id = s.admission_id
                join empi_patient p on p.id = a.patient_id
                where s.settle_type = 'FINAL' and s.status = 'PAID'
                  and s.created_at >= ?::date and s.created_at < (?::date + interval '1 month')
                group by 1
            ), inp_refund as (
                select %1$s as ins, count(*) as bills, sum(s.total_amount) as amount
                from inp_settlement s
                join inp_admission a on a.id = s.admission_id
                join empi_patient p on p.id = a.patient_id
                where s.settle_type = 'FINAL' and s.status = 'CANCELLED' and s.refunded_at is not null
                  and s.refunded_at >= ?::date and s.refunded_at < (?::date + interval '1 month')
                group by 1
            ), k as (
                select ins from outp_paid     union select ins from outp_refund
                union select ins from inp_paid union select ins from inp_refund
            ), agg as (
                select k.ins as insurance_type,
                       case k.ins when 'SELF' then '自费'
                                  when 'YB_STAFF' then '职工医保'
                                  when 'YB_RESIDENT' then '居民医保'
                                  else '其他（未知/历史遗留费别）' end as insurance_name,
                       coalesce(op.bills, 0)   as outp_bills,
                       coalesce(op.amount, 0)  as outp_amount,
                       coalesce(orf.bills, 0)  as outp_refund_bills,
                       coalesce(orf.amount, 0) as outp_refund_amount,
                       coalesce(ip.bills, 0)   as inp_bills,
                       coalesce(ip.amount, 0)  as inp_amount,
                       coalesce(irf.bills, 0)  as inp_refund_bills,
                       coalesce(irf.amount, 0) as inp_refund_amount,
                       coalesce(op.bills, 0) + coalesce(ip.bills, 0)   as total_bills,
                       coalesce(op.amount, 0) + coalesce(ip.amount, 0) as total_amount
                from k
                left join outp_paid   op  on op.ins  = k.ins
                left join outp_refund orf on orf.ins = k.ins
                left join inp_paid    ip  on ip.ins  = k.ins
                left join inp_refund  irf on irf.ins = k.ins
            )
            select a.*,
                   coalesce(round(a.total_amount / nullif(a.total_bills, 0), 2), 0) as avg_amount,
                   coalesce(round(a.total_amount * 100 / nullif(sum(a.total_amount) over (), 0), 1), 0) as share_pct
            from agg a
            order by a.total_amount desc, a.insurance_type
            """.formatted(INS_BUCKET);

    /**
     * 按费用类别汇总金额（门诊 + 住院）。
     *
     * <p>类别取自主数据挂类：药嘱读 md_drug.fee_category_code，非药嘱读 md_charge_item.fee_category_code。
     * 未挂类的项目**显式归入 UNCLASSIFIED「未分类」行而不是被丢弃**——否则各类别之和会静悄悄
     * 小于总收入，看报表的人无从发现主数据还没维护完。
     *
     * <p>门诊侧以"已收费单(outp_charge PAID)所辖医嘱行"为准，住院侧以"已出院结算(FINAL/PAID)
     * 那次住院的已执行医嘱行"为准——后者与 discharge() 的总额算式（sum 全部 EXECUTED 医嘱）
     * 逐字同源，故各类别之和恒等于该月住院收入。
     */
    private static final String FEE_BY_CATEGORY_SQL = """
            with outp as (
                select case when o.order_type = 'REG' then 'REG_FEE'
                            else coalesce(d.fee_category_code, ci.fee_category_code) end as cat,
                       count(*) as line_count, sum(o.amount) as amount
                from outp_order o
                join outp_charge c on c.id = o.charge_id
                left join md_drug        d  on d.id  = o.item_id and o.order_type = 'DRUG'
                left join md_charge_item ci on ci.id = o.item_id and o.order_type <> 'DRUG'
                where c.status = 'PAID'
                  and c.created_at >= ?::date and c.created_at < (?::date + interval '1 month')
                group by 1
            ), inp as (
                select coalesce(d.fee_category_code, ci.fee_category_code) as cat,
                       count(*) as line_count, sum(o.amount) as amount
                from inp_order o
                join inp_settlement s on s.admission_id = o.admission_id
                                     and s.settle_type = 'FINAL' and s.status = 'PAID'
                left join md_drug        d  on d.id  = o.item_id and o.order_type = 'DRUG'
                left join md_charge_item ci on ci.id = o.item_id and o.order_type <> 'DRUG'
                where o.status = 'EXECUTED'
                  and s.created_at >= ?::date and s.created_at < (?::date + interval '1 month')
                group by 1
            ), k as (
                select cat from outp union select cat from inp
            ), agg as (
                select coalesce(k.cat, 'UNCLASSIFIED') as category_code,
                       coalesce(fc.name, case when k.cat = 'REG_FEE'
                                              then '挂号费（无收费项目记账，按医嘱类型识别）'
                                              else '未分类（主数据未挂费用类别）' end) as category_name,
                       coalesce(fc.sort_no, 999999) as sort_no,
                       coalesce(o.line_count, 0) as outp_lines,
                       coalesce(o.amount, 0)     as outp_amount,
                       coalesce(i.line_count, 0) as inp_lines,
                       coalesce(i.amount, 0)     as inp_amount,
                       coalesce(o.amount, 0) + coalesce(i.amount, 0) as total_amount
                from k
                left join outp o on o.cat is not distinct from k.cat
                left join inp  i on i.cat is not distinct from k.cat
                left join md_fee_category fc on fc.code = k.cat
            )
            select a.*,
                   coalesce(round(a.total_amount * 100 / nullif(sum(a.total_amount) over (), 0), 1), 0) as share_pct
            from agg a
            order by a.total_amount desc, a.sort_no, a.category_code
            """;

    /**
     * 按费别（参保类型）汇总金额——兑现偏离表 1034★「拥有费别分类的金额汇总」。
     *
     * <p><b>口径近似（诚实标注，报表页 alert 同步展示同一段话）</b>：
     * <ul>
     *   <li><b>费别取自 empi_patient.insurance_type 的「当前值」，不是结算时刻的快照。</b>
     *       患者事后修改参保类型（自费转职工医保等），历史月份的报表会随之变脸——
     *       同一个月份两次查询可能给出不同结果。要严格口径须在收费/结算时落费别快照列，
     *       那属核心写路径改动（ChargeService.settle 挂着 claimCharge 防双倍扣款、医保分割、
     *       医保上传、退费冲销四处联动），本版明确不做。
     *   <li>未知/历史遗留费别值（YB_EMPLOYEE、null 等）显式归入「其他」行，**不丢弃**，
     *       故各行金额之和恒等于当月门诊+住院收入合计。
     *   <li>退费按 refunded_at 归集到退费当月（与日结/班结口径一致），不冲减原月收入。
     *   <li>住院只计 FINAL 出院结算；INTERIM 中间结算是其子集，计入即重复计费。
     * </ul>
     */
    @GetMapping("/fee-by-insurance")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATION','QUALITY')")
    public R<Map<String, Object>> feeByInsurance(@RequestParam(required = false) String month) {
        String start = monthStart(month);
        var rows = jdbc.queryForList(FEE_BY_INSURANCE_SQL, start, start, start, start, start, start, start, start);
        var m = new LinkedHashMap<String, Object>();
        m.put("month", start.substring(0, 7));
        m.put("rows", rows);
        m.put("totals", sumRows(rows, "outp_amount", "inp_amount", "outp_refund_amount", "inp_refund_amount"));
        m.put("caveat", INSURANCE_CAVEAT);
        return R.ok(m);
    }

    /**
     * 按费用类别汇总金额——兑现偏离表 675（门诊按费用类别统计费用/退费）与
     * 3684（医保患者住院费用按费用类别精准汇总；配合 insuranceType 过滤即为"按医保政策"维度）。
     *
     * <p><b>口径近似（诚实标注，报表页 alert 同步）</b>：
     * <ul>
     *   <li>费用类别取自主数据**当前**挂类（md_drug / md_charge_item 的 fee_category_code），
     *       不是开单时刻的快照——事后改挂类别会让历史报表变脸。
     *   <li>未挂类项目进「未分类」行而非丢弃；该行金额就是主数据维护欠账的量化值。
     *   <li>门诊挂号费（outp_order.order_type='REG'）自 V3 起以 item_id=0 的哨兵值记账、不指向任何
     *       收费项目，走不了主数据挂类。故**单列为「挂号费」行**（按医嘱类型识别）而不是混进「未分类」
     *       稀释其含义，也不擅自把它并进字典里的「诊查费」——那是一条未经院方确认的口径假设。
     *   <li><b>门诊退费金额无法按费用类别拆分</b>：退费时 OutpOrderRepository.claimRefund 把明细行
     *       改回 CREATED 并把 charge_id 置空（这是退费后可重新收费的既有设计），退费单与其明细的
     *       关联被结构性抹除，全库也没有退费明细台账。故退费金额只在 /fee-by-insurance 按**结算单级**
     *       给出；本表是"已收费/已结算费用的类别构成"，不含退费维度。
     * </ul>
     *
     * @param insuranceType 可选：只统计该费别患者（如 YB_STAFF）的费用类别构成。
     *                      传 null 为全院。费别口径与近似说明同 /fee-by-insurance。
     */
    @GetMapping("/fee-by-category")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATION','QUALITY')")
    public R<Map<String, Object>> feeByCategory(@RequestParam(required = false) String month,
                                                @RequestParam(required = false) String insuranceType) {
        String start = monthStart(month);
        var rows = jdbc.queryForList(categorySql(insuranceType), categoryArgs(start, insuranceType));
        var m = new LinkedHashMap<String, Object>();
        m.put("month", start.substring(0, 7));
        m.put("insuranceType", insuranceType == null || insuranceType.isBlank() ? null : insuranceType.strip());
        m.put("rows", rows);
        m.put("totals", sumRows(rows, "outp_amount", "inp_amount"));
        m.put("caveat", CATEGORY_CAVEAT);
        return R.ok(m);
    }

    /** 费别汇总 CSV 导出（与 /fee-by-insurance 同口径同 SQL） */
    @GetMapping(value = "/fee-by-insurance.csv", produces = "text/csv;charset=UTF-8")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATION','QUALITY')")
    public String feeByInsuranceCsv(@RequestParam(required = false) String month) {
        String start = monthStart(month);
        var rows = jdbc.queryForList(FEE_BY_INSURANCE_SQL, start, start, start, start, start, start, start, start);
        var sb = new StringBuilder("﻿月份,费别,门诊笔数,门诊金额,门诊退费笔数,门诊退费金额,"
                + "住院笔数,住院金额,住院冲销笔数,住院冲销金额,合计笔数,合计金额,均次费用,占比%\n");
        for (var r : rows) {
            sb.append("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n".formatted(
                    csv(start.substring(0, 7)), csv(r.get("insurance_name")),
                    csv(r.get("outp_bills")), csv(r.get("outp_amount")),
                    csv(r.get("outp_refund_bills")), csv(r.get("outp_refund_amount")),
                    csv(r.get("inp_bills")), csv(r.get("inp_amount")),
                    csv(r.get("inp_refund_bills")), csv(r.get("inp_refund_amount")),
                    csv(r.get("total_bills")), csv(r.get("total_amount")),
                    csv(r.get("avg_amount")), csv(r.get("share_pct"))));
        }
        sb.append("# ").append(INSURANCE_CAVEAT.replace(",", "，")).append("\n");
        return sb.toString();
    }

    /** 费用类别汇总 CSV 导出（与 /fee-by-category 同口径同 SQL） */
    @GetMapping(value = "/fee-by-category.csv", produces = "text/csv;charset=UTF-8")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATION','QUALITY')")
    public String feeByCategoryCsv(@RequestParam(required = false) String month,
                                   @RequestParam(required = false) String insuranceType) {
        String start = monthStart(month);
        var rows = jdbc.queryForList(categorySql(insuranceType), categoryArgs(start, insuranceType));
        var sb = new StringBuilder("﻿月份,费用类别码,费用类别,门诊行数,门诊金额,住院行数,住院金额,合计金额,占比%\n");
        for (var r : rows) {
            sb.append("%s,%s,%s,%s,%s,%s,%s,%s,%s\n".formatted(
                    csv(start.substring(0, 7)), csv(r.get("category_code")), csv(r.get("category_name")),
                    csv(r.get("outp_lines")), csv(r.get("outp_amount")),
                    csv(r.get("inp_lines")), csv(r.get("inp_amount")),
                    csv(r.get("total_amount")), csv(r.get("share_pct"))));
        }
        sb.append("# ").append(CATEGORY_CAVEAT.replace(",", "，")).append("\n");
        return sb.toString();
    }

    /** 口径近似说明——端点 javadoc、JSON 返回体与报表页 alert 三处同一段文字，避免口径注释与页面走样 */
    static final String INSURANCE_CAVEAT =
            "费别取自患者档案 insurance_type 的【当前值】而非结算时刻快照：患者事后修改参保类型会让历史月份报表变脸。"
            + "未知/历史遗留费别（YB_EMPLOYEE、未填写等）显式归入「其他」行而非丢弃，故各行合计恒等于当月收入合计。"
            + "住院只计出院结算（FINAL），中间结算（INTERIM）为其子集、计入即重复计费；退费按退费当月归集，不冲减原月收入。";

    static final String CATEGORY_CAVEAT =
            "费用类别取自主数据【当前】挂类而非开单时刻快照：事后改挂类别会让历史报表变脸。"
            + "未挂类项目归入「未分类」行而非丢弃——该行金额即主数据维护欠账的量化值。"
            + "门诊挂号费自 V3 起以 item_id=0 的哨兵值记账、不指向任何收费项目，无法走主数据挂类，"
            + "故单列为「挂号费」行（按医嘱类型 REG 识别）而不是混进「未分类」，也不擅自并入诊查费类别。"
            + "门诊退费金额无法按费用类别拆分：退费会把明细行改回未收费并清空 charge_id（既有设计），"
            + "退费单与明细的关联被结构性抹除，故退费金额只在「按费别」报表按结算单级给出。";

    /**
     * 费别过滤是可选维度：只在传了 insuranceType 时给两个 CTE 各追加一次患者 join，参数随之增减。
     * 拼接的是**固定字面量**（无用户输入进 SQL 文本），费别值本身仍走占位符，无注入面。
     */
    private static String categorySql(String insuranceType) {
        if (insuranceType == null || insuranceType.isBlank()) {
            return FEE_BY_CATEGORY_SQL;
        }
        return FEE_BY_CATEGORY_SQL
                .replace("join outp_charge c on c.id = o.charge_id",
                        "join outp_charge c on c.id = o.charge_id"
                                + " join outp_registration reg on reg.id = c.registration_id"
                                + " join empi_patient p on p.id = reg.patient_id"
                                + " and coalesce(p.insurance_type, 'OTHER') = ?")
                .replace("and s.settle_type = 'FINAL' and s.status = 'PAID'",
                        "and s.settle_type = 'FINAL' and s.status = 'PAID'"
                                + " join inp_admission adm on adm.id = s.admission_id"
                                + " join empi_patient p2 on p2.id = adm.patient_id"
                                + " and coalesce(p2.insurance_type, 'OTHER') = ?");
    }

    private static Object[] categoryArgs(String start, String insuranceType) {
        return insuranceType == null || insuranceType.isBlank()
                ? new Object[]{start, start, start, start}
                : new Object[]{insuranceType.strip(), start, start, insuranceType.strip(), start, start};
    }

    /** 指定金额列的 BigDecimal 合计（1.1.4 B-9：金额不过 Double） */
    private static Map<String, Object> sumRows(List<Map<String, Object>> rows, String... cols) {
        var totals = new LinkedHashMap<String, Object>();
        for (String col : cols) {
            BigDecimal sum = BigDecimal.ZERO;
            for (var r : rows) {
                Object v = r.get(col);
                if (v != null) sum = sum.add(new BigDecimal(String.valueOf(v)));
            }
            totals.put(col, sum);
        }
        return totals;
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
