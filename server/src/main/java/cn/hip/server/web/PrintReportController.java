package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import cn.hip.platform.core.config.BusinessDates;

/** 十三期：打印数据集与日结报表 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','CASHIER','DOCTOR_OUTP','NURSE','TECHNICIAN')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class PrintReportController {

    private final JdbcTemplate jdbc;
    private final cn.hip.platform.core.security.CurrentUserService currentUserService;

    /** 挂号凭条数据 */
    @GetMapping("/api/print/registration/{id}")
    public R<Map<String, Object>> registrationSlip(@PathVariable Long id) {
        var rows = jdbc.queryForList("""
                select r.id, r.reg_no, r.visit_date, r.fee, r.created_at,
                       p.name as patient_name, p.patient_no, d.name as dept_name
                from outp_registration r
                join empi_patient p on p.id = r.patient_id
                join sys_dept d on d.id = r.dept_id where r.id = ?
                """, id);
        return rows.isEmpty() ? R.fail(9930, "挂号不存在") : R.ok(rows.get(0));
    }

    /** 收费票据数据（含明细行） */
    @GetMapping("/api/print/charge/{id}")
    public R<Map<String, Object>> chargeReceipt(@PathVariable Long id) {
        var rows = jdbc.queryForList("""
                select c.charge_no, c.total_amount, c.pay_method, c.created_at,
                       p.name as patient_name, p.patient_no
                from outp_charge c
                join outp_registration r on r.id = c.registration_id
                join empi_patient p on p.id = r.patient_id where c.id = ?
                """, id);
        if (rows.isEmpty()) return R.fail(9931, "结算单不存在");
        var receipt = rows.get(0);
        receipt.put("items", jdbc.queryForList(
                "select item_name, spec, qty, unit_price, amount from outp_order where charge_id = ?", id));
        return R.ok(receipt);
    }

    /**
     * v40 票据补打检索：收费员按结算单号/患者号/姓名找回历史单据补打。
     * 此前打印数据集与版式都在，但收费台只能打"刚结算的那一单"——重打无入口。
     */
    @GetMapping("/api/print/charge-search")
    public R<List<Map<String, Object>>> chargeSearch(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String date) {
        var where = new StringBuilder(" where 1=1 ");
        var args = new java.util.ArrayList<Object>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (c.charge_no ilike ? or p.patient_no ilike ? or p.name ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        // 半开区间走索引（1.1.3 起全库口径；?::date 显式定型避免 PG 推断不出参数类型）
        if (date != null && !date.isBlank()) {
            where.append(" and c.created_at >= ?::date and c.created_at < (?::date + 1) ");
            args.add(date);
            args.add(date);
        }
        return R.ok(jdbc.queryForList("""
                select c.id, c.charge_no, c.total_amount, c.pay_method, c.status, c.created_at,
                       p.name as patient_name, p.patient_no,
                       (select count(*) from fin_print_log l where l.doc_type = 'CHARGE' and l.doc_id = c.id) as print_count
                from outp_charge c
                join outp_registration r on r.id = c.registration_id
                join empi_patient p on p.id = r.patient_id
                """ + where + " order by c.id desc limit 100", args.toArray()));
    }

    /** v40 打印留痕：前端每次打开打印页记一次（补打次数可追溯，财务/审计关注） */
    @PostMapping("/api/print/log")
    public R<Void> logPrint(@RequestParam String docType, @RequestParam Long docId,
                            org.springframework.security.core.Authentication auth) {
        if (!List.of("CHARGE", "REGISTRATION").contains(docType)) return R.fail(4000, "单据类型不正确");
        jdbc.update("insert into fin_print_log(doc_type, doc_id, operator_id) values (?,?,?)",
                docType, docId, currentUserService.idOf(auth));
        return R.ok();
    }

    /** 检验报告单数据 */
    @GetMapping("/api/print/lab-report/{orderId}")
    public R<Map<String, Object>> labReport(@PathVariable Long orderId) {
        var rows = jdbc.queryForList("""
                select o.id, o.item_name, o.group_no, o.created_at,
                       p.name as patient_name, p.sex, p.patient_no
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where o.id = ? and o.order_type = 'LAB'
                """, orderId);
        if (rows.isEmpty()) return R.fail(9932, "检验申请不存在");
        var report = rows.get(0);
        report.put("results", jdbc.queryForList(
                "select item_name, result_value, unit, ref_range, abnormal_flag from outp_lab_result where order_id = ? order by id",
                orderId));
        return R.ok(report);
    }

    /** 1.0.1（1028）：死亡登记卡打印数据集 */
    @GetMapping("/api/print/death-card/{id}")
    public R<Map<String, Object>> deathCard(@PathVariable Long id) {
        var rows = jdbc.queryForList("""
                select d.*, p.name as patient_name, p.patient_no, p.sex, p.birth_date, a.admission_no
                from mr_death_card d
                join empi_patient p on p.id = d.patient_id
                left join inp_admission a on a.id = d.admission_id
                where d.id = ?
                """, id);
        return rows.isEmpty() ? R.fail(9951, "死亡登记卡不存在") : R.ok(rows.get(0));
    }

    /**
     * 日结报表：收款按收款日、退款按退费日各自归集（1.1.3 B-1）。
     * 原口径把 D2 的退费算进 D1 的报表——D1 历史日结事后变脸，D2 当天的实际出账看不到。
     * 日期一律半开区间：`created_at::date = ?` 会废掉索引（B-4）。
     */
    @GetMapping("/api/reports/daily-settlement")
    public R<Map<String, Object>> dailySettlement(@RequestParam(required = false) String date) {
        // 预校验（1.2.0）：非法日期直接 ?::date 会 500 + ERROR 堆栈污染告警；
        // DateTimeParseException 由全局处理器转 4000
        String d = date == null ? BusinessDates.today().toString() : LocalDate.parse(date).toString();
        List<Map<String, Object>> byMethod = jdbc.queryForList("""
                select pay_method, side, count(*) as cnt, sum(total_amount) as amount
                from (
                    select pay_method, 'COLLECTED' as side, total_amount from outp_charge
                    where created_at >= ?::date and created_at < ?::date + interval '1 day'
                    union all
                    select pay_method, 'REFUNDED', total_amount from outp_charge
                    where status = 'REFUNDED'
                      and refunded_at >= ?::date and refunded_at < ?::date + interval '1 day'
                ) t
                group by pay_method, side order by pay_method, side
                """, d, d, d, d);
        var total = jdbc.queryForList("""
                select (select coalesce(sum(total_amount), 0) from outp_charge
                        where created_at >= ?::date and created_at < ?::date + interval '1 day') as paid,
                       (select coalesce(sum(total_amount), 0) from outp_charge
                        where status = 'REFUNDED'
                          and refunded_at >= ?::date and refunded_at < ?::date + interval '1 day') as refunded,
                       (select count(*) from outp_charge
                        where created_at >= ?::date and created_at < ?::date + interval '1 day') as bills
                """, d, d, d, d, d, d).get(0);
        return R.ok(Map.of("date", d, "byMethod", byMethod, "total", total));
    }

    /**
     * CSV 字段转义：姓名含逗号会串列；以 = + - @ 开头的值在 Excel 里会被当公式执行（CSV 注入），
     * 而姓名是建档端可写入的自由文本。
     */
    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        // 数值不做公式守卫（1.2.3 五轮 P1-3）：退款行金额 -15.00 被加 ' 前缀后在 Excel 里
        // 是文本，SUM 跳过——"金额列求和=当日净额"的口径被守卫自己击穿。数字无公式注入风险
        if (!(v instanceof Number) && !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n")
                ? "\"" + s.replace("\"", "\"\"") + "\""
                : s;
    }

    /**
     * 日结报表 CSV 导出（1.2.0 口径可复算）：收款/退款各成一行、退款金额取负——
     * 原先一张"当日新建且当日退费"的单只出一行且无符号，按金额列求和既不等于收款也不等于净额；
     * 历史日期重导时后来才退费的单 status 显示 REFUNDED，快照"事后变脸"。
     * 现在：金额列求和 = 当日净额；按口径列分组求和 = 日结报表两侧合计；重导历史日期结果稳定。
     */
    @GetMapping(value = "/api/reports/daily-settlement.csv", produces = "text/csv;charset=UTF-8")
    public String dailySettlementCsv(@RequestParam(required = false) String date) {
        String d = date == null ? BusinessDates.today().toString() : LocalDate.parse(date).toString();
        var rows = jdbc.queryForList("""
                select t.side, t.charge_no, p.name, t.amount, t.pay_method, t.occurred_at
                from (
                    select '收款' as side, charge_no, registration_id, total_amount as amount,
                           pay_method, created_at as occurred_at, id
                    from outp_charge
                    where created_at >= ?::date and created_at < ?::date + interval '1 day'
                    union all
                    select '退款', charge_no, registration_id, -total_amount,
                           pay_method, refunded_at, id
                    from outp_charge
                    where status = 'REFUNDED'
                      and refunded_at >= ?::date and refunded_at < ?::date + interval '1 day'
                ) t
                join outp_registration r on r.id = t.registration_id
                join empi_patient p on p.id = r.patient_id
                order by t.occurred_at, t.id
                """, d, d, d, d);
        StringBuilder sb = new StringBuilder("﻿口径,结算单号,患者,金额,方式,发生时间\n");
        for (var r : rows) {
            sb.append("%s,%s,%s,%s,%s,%s\n".formatted(csv(r.get("side")), csv(r.get("charge_no")),
                    csv(r.get("name")), csv(r.get("amount")), csv(r.get("pay_method")),
                    csv(r.get("occurred_at"))));
        }
        return sb.toString();
    }
}
