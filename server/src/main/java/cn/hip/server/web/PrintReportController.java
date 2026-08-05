package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 十三期：打印数据集与日结报表 */
@RestController
@RequiredArgsConstructor
public class PrintReportController {

    private final JdbcTemplate jdbc;

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

    /** 日结报表：按收费方式与状态汇总当日结算 */
    @GetMapping("/api/reports/daily-settlement")
    public R<Map<String, Object>> dailySettlement(@RequestParam(required = false) String date) {
        String d = date == null ? LocalDate.now().toString() : date;
        List<Map<String, Object>> byMethod = jdbc.queryForList("""
                select pay_method, status, count(*) as cnt, sum(total_amount) as amount
                from outp_charge where created_at::date = ?::date
                group by pay_method, status order by pay_method
                """, d);
        var total = jdbc.queryForList("""
                select coalesce(sum(total_amount) filter (where status = 'PAID'), 0) as paid,
                       coalesce(sum(total_amount) filter (where status = 'REFUNDED'), 0) as refunded,
                       count(*) as bills
                from outp_charge where created_at::date = ?::date
                """, d).get(0);
        return R.ok(Map.of("date", d, "byMethod", byMethod, "total", total));
    }

    /** 日结报表 CSV 导出 */
    @GetMapping(value = "/api/reports/daily-settlement.csv", produces = "text/csv;charset=UTF-8")
    public String dailySettlementCsv(@RequestParam(required = false) String date) {
        String d = date == null ? LocalDate.now().toString() : date;
        var rows = jdbc.queryForList("""
                select c.charge_no, p.name, c.total_amount, c.pay_method, c.status, c.created_at
                from outp_charge c
                join outp_registration r on r.id = c.registration_id
                join empi_patient p on p.id = r.patient_id
                where c.created_at::date = ?::date order by c.id
                """, d);
        StringBuilder sb = new StringBuilder("﻿结算单号,患者,金额,方式,状态,时间\n");
        for (var r : rows) {
            sb.append("%s,%s,%s,%s,%s,%s\n".formatted(r.get("charge_no"), r.get("name"),
                    r.get("total_amount"), r.get("pay_method"), r.get("status"), r.get("created_at")));
        }
        return sb.toString();
    }

    /** 审计日志查询（sensitive=true 只看敏感操作：退费/授权/角色菜单/用户/目录维护） */
    @GetMapping("/api/audit/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<Map<String, Object>>> auditLogs(@RequestParam(required = false) String username,
                                                  @RequestParam(defaultValue = "false") boolean sensitive) {
        String sensitiveWhere = """
                (path like '%/refund%' or path like '%/roles%' or path like '%/menus%'
                 or path like '%/abx-privileges%' or path like '%/system/users%'
                 or path like '%/insurance/catalog%' or path like '%/cancel%')
                """;
        StringBuilder sql = new StringBuilder("select * from sys_audit_log where 1=1 ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (username != null) {
            sql.append(" and username = ? ");
            args.add(username);
        }
        if (sensitive) sql.append(" and ").append(sensitiveWhere);
        sql.append(" order by id desc limit 200");
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }
}
