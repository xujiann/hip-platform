package cn.hip.hrp.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 四十期：HR 收尾——继续教育学分台账、考勤打卡/补卡；资产价值调整与附件；结账明细检索 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATION')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class HrPlusController {

    private final JdbcTemplate jdbc;

    // ---- 继续教育 ----
    public record CmeReq(Long employeeId, String projectNo, String projectName, String organizer,
                         String leader, BigDecimal credit, Integer cmeYear, String conclusion,
                         String attachmentName, Boolean reimbursed) {}

    @PostMapping("/api/hr/cme")
    public R<Void> addCme(@RequestBody CmeReq req) {
        Integer emp = jdbc.queryForObject("select count(*) from hr_employee where id = ?", Integer.class, req.employeeId());
        if (emp == null || emp == 0) return R.fail(4790, "员工不存在");
        jdbc.update("""
                insert into hr_cme(employee_id, project_no, project_name, organizer, leader, credit,
                                   cme_year, conclusion, attachment_name, reimbursed)
                values (?,?,?,?,?,?,?,?,?,?)
                """, req.employeeId(), req.projectNo(), req.projectName(), req.organizer(), req.leader(),
                req.credit() == null ? BigDecimal.ZERO : req.credit(), req.cmeYear(), req.conclusion(),
                req.attachmentName(), req.reimbursed() != null && req.reimbursed());
        return R.ok();
    }

    /** 台账 + 年度学分汇总（employeeId 为空查全院） */
    @GetMapping("/api/hr/cme")
    public R<Map<String, Object>> cme(@RequestParam(required = false) Long employeeId) {
        return R.ok(Map.of(
                "records", jdbc.queryForList("""
                        select c.*, e.name as emp_name from hr_cme c
                        join hr_employee e on e.id = c.employee_id
                        where (?::bigint is null or c.employee_id = ?)
                        order by c.id desc limit 100
                        """, employeeId, employeeId),
                "creditSummary", jdbc.queryForList("""
                        select e.name as emp_name, c.cme_year, sum(c.credit) as total_credit
                        from hr_cme c join hr_employee e on e.id = c.employee_id
                        where (?::bigint is null or c.employee_id = ?)
                        group by e.name, c.cme_year order by c.cme_year desc, total_credit desc limit 50
                        """, employeeId, employeeId)));
    }

    // ---- 考勤 ----
    public record AttReq(Long employeeId, String workDate, String checkIn, String checkOut,
                         String attType, String note) {}

    /** 打卡/补卡：同员工同日 upsert；补卡须填说明 */
    @PostMapping("/api/hr/attendance")
    public R<Void> punch(@RequestBody AttReq req) {
        boolean makeup = "MAKEUP".equals(req.attType());
        if (makeup && (req.note() == null || req.note().isBlank())) return R.fail(4791, "补卡须填写说明");
        Integer emp = jdbc.queryForObject("select count(*) from hr_employee where id = ?", Integer.class, req.employeeId());
        if (emp == null || emp == 0) return R.fail(4790, "员工不存在");
        jdbc.update("""
                insert into hr_attendance(employee_id, work_date, check_in, check_out, att_type, note)
                values (?, coalesce(?::date, current_date), ?, ?, ?, ?)
                on conflict (employee_id, work_date) do update set
                    check_in = coalesce(excluded.check_in, hr_attendance.check_in),
                    check_out = coalesce(excluded.check_out, hr_attendance.check_out),
                    att_type = excluded.att_type, note = excluded.note
                """, req.employeeId(), req.workDate(), req.checkIn(), req.checkOut(),
                makeup ? "MAKEUP" : "NORMAL", req.note());
        return R.ok();
    }

    @GetMapping("/api/hr/attendance")
    public R<List<Map<String, Object>>> attendance(@RequestParam(required = false) String date) {
        String d = date == null ? "current_date" : "'" + date.replaceAll("[^0-9-]", "") + "'::date";
        return R.ok(jdbc.queryForList("""
                select a.*, e.name as emp_name, e.emp_no from hr_attendance a
                join hr_employee e on e.id = a.employee_id
                where a.work_date = %s order by e.emp_no
                """.formatted(d)));
    }

    // ---- 资产价值调整 / 附件 ----
    public record AdjustReq(Long assetId, String adjustType, BigDecimal amount, String reason) {}

    @PostMapping("/api/asset-plus/value-adjusts")
    public R<Void> adjust(@RequestBody AdjustReq req) {
        if (!"APPRECIATION".equals(req.adjustType()) && !"DEP_FIX".equals(req.adjustType())) {
            return R.fail(4792, "类型只能为 APPRECIATION/DEP_FIX");
        }
        Integer asset = jdbc.queryForObject("select count(*) from hrp_asset where id = ?", Integer.class, req.assetId());
        if (asset == null || asset == 0) return R.fail(4730, "资产不存在");
        jdbc.update("insert into as_value_adjust(asset_id, adjust_type, amount, reason) values (?,?,?,?)",
                req.assetId(), req.adjustType(), req.amount(), req.reason());
        return R.ok();
    }

    @GetMapping("/api/asset-plus/value-adjusts")
    public R<List<Map<String, Object>>> adjusts() {
        return R.ok(jdbc.queryForList("""
                select v.*, a.asset_no, a.name as asset_name from as_value_adjust v
                join hrp_asset a on a.id = v.asset_id order by v.id desc limit 100
                """));
    }

    public record DocReq(Long assetId, String docName, String remark) {}

    @PostMapping("/api/asset-plus/docs")
    public R<Void> addDoc(@RequestBody DocReq req) {
        jdbc.update("insert into as_asset_doc(asset_id, doc_name, remark) values (?,?,?)",
                req.assetId(), req.docName(), req.remark());
        return R.ok();
    }

    @GetMapping("/api/asset-plus/docs")
    public R<List<Map<String, Object>>> docs(@RequestParam Long assetId) {
        return R.ok(jdbc.queryForList(
                "select * from as_asset_doc where asset_id = ? order by id desc", assetId));
    }

    // ---- 门诊结账明细检索（1.0.1/1227：加 orderType 即切明细行模式，按项目类型过滤） ----
    @GetMapping("/api/finance/charge-search")
    @PreAuthorize("hasAnyRole('ADMIN','CASHIER')")   // 财务端点，与 FinanceController 同口径
    public R<List<Map<String, Object>>> chargeSearch(@RequestParam String from, @RequestParam String to,
                                                     @RequestParam(required = false) String payMethod,
                                                     @RequestParam(required = false) String orderType) {
        var args = new java.util.ArrayList<Object>(List.of(from, to));
        StringBuilder sql;
        if (orderType != null && !orderType.isBlank()) {
            sql = new StringBuilder("""
                    select c.charge_no, c.pay_method, c.status, o.item_name, o.order_type,
                           o.qty, o.amount, c.created_at, p.name as patient_name
                    from outp_order o
                    join outp_charge c on c.id = o.charge_id
                    join outp_registration r on r.id = c.registration_id
                    join empi_patient p on p.id = r.patient_id
                    where c.created_at >= ?::date and c.created_at < ?::date + interval '1 day' and o.order_type = ?
                    """);
            args.add(orderType);
        } else {
            sql = new StringBuilder("""
                    select c.charge_no, c.total_amount, c.pay_method, c.status, c.created_at,
                           p.name as patient_name, coalesce(u.real_name, u.username) as cashier
                    from outp_charge c
                    join outp_registration r on r.id = c.registration_id
                    join empi_patient p on p.id = r.patient_id
                    left join sys_user u on u.id = c.cashier_id
                    where c.created_at >= ?::date and c.created_at < ?::date + interval '1 day'
                    """);
        }
        if (payMethod != null && !payMethod.isBlank()) {
            sql.append(" and c.pay_method = ? ");
            args.add(payMethod);
        }
        sql.append(" order by c.id desc limit 300");
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }
}
