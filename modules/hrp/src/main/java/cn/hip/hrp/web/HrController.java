package cn.hip.hrp.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 三十五期：HR 人事——员工档案、培训台账、工资（批量导入+个人按月查询）、招聘登记、通讯录 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hr")
@PreAuthorize("hasRole('ADMIN')")   // 1.0.9：权限清点补齐
public class HrController {

    private final JdbcTemplate jdbc;

    // ---- 员工档案 ----
    public record EmpReq(String empNo, String name, String sex, Long deptId, String title,
                         String phone, String email, String marital, String birthDate, String hireDate) {}

    @PostMapping("/employees")
    public R<Void> addEmployee(@RequestBody EmpReq req) {
        try {
            jdbc.update("""
                    insert into hr_employee(emp_no, name, sex, dept_id, title, phone, email, marital, birth_date, hire_date)
                    values (?,?,?,?,?,?,?,?,?::date,?::date)
                    """, req.empNo(), req.name(), req.sex() == null ? "U" : req.sex(), req.deptId(), req.title(),
                    req.phone(), req.email(), req.marital(), req.birthDate(), req.hireDate());
        } catch (DuplicateKeyException e) {
            return R.fail(4720, "工号已存在：" + req.empNo());
        }
        return R.ok();
    }

    /** 员工列表（组织架构树过滤：deptId 可选） */
    @GetMapping("/employees")
    public R<List<Map<String, Object>>> employees(@RequestParam(required = false) Long deptId,
                                                  @RequestParam(required = false) String keyword) {
        StringBuilder sql = new StringBuilder("""
                select e.*, d.name as dept_name from hr_employee e
                left join sys_dept d on d.id = e.dept_id where e.status = 'ACTIVE'
                """);
        var args = new java.util.ArrayList<>();
        if (deptId != null) {
            sql.append(" and e.dept_id = ? ");
            args.add(deptId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (e.name ilike ? or e.emp_no ilike ?) ");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" order by e.emp_no limit 200");
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /** 通讯录（公共：全员姓名/科室/电话/邮箱） */
    @GetMapping("/directory")
    public R<List<Map<String, Object>>> directory() {
        return R.ok(jdbc.queryForList("""
                select e.name, e.title, e.phone, e.email, d.name as dept_name
                from hr_employee e left join sys_dept d on d.id = e.dept_id
                where e.status = 'ACTIVE' order by d.name, e.emp_no limit 500
                """));
    }

    // ---- 培训台账 ----
    public record TrainingReq(Long employeeId, String category, String startDate, String endDate,
                              String org, String content, String certName, String certNo) {}

    @PostMapping("/trainings")
    public R<Void> addTraining(@RequestBody TrainingReq req) {
        jdbc.update("""
                insert into hr_training(employee_id, category, start_date, end_date, org, content, cert_name, cert_no)
                values (?,?,?::date,?::date,?,?,?,?)
                """, req.employeeId(), req.category(), req.startDate(), req.endDate(), req.org(),
                req.content(), req.certName(), req.certNo());
        return R.ok();
    }

    @GetMapping("/trainings")
    public R<List<Map<String, Object>>> trainings(@RequestParam(required = false) Long employeeId) {
        String base = """
                select t.*, e.name as emp_name from hr_training t
                join hr_employee e on e.id = t.employee_id
                """;
        return R.ok(employeeId == null
                ? jdbc.queryForList(base + " order by t.id desc limit 100")
                : jdbc.queryForList(base + " where t.employee_id = ? order by t.id desc", employeeId));
    }

    // ---- 工资 ----
    public record SalaryRow(String empNo, BigDecimal basePay, BigDecimal bonus, BigDecimal deduction) {}
    public record SalaryImportReq(String month, List<SalaryRow> rows) {}

    /** 批量导入（前端解析 Excel/CSV 后成行提交；同员工同月幂等覆盖） */
    @PostMapping("/salaries/import")
    @Transactional
    public R<Map<String, Object>> importSalaries(@RequestBody SalaryImportReq req) {
        if (req.month() == null || !req.month().matches("\\d{4}-\\d{2}")) return R.fail(4721, "月份格式 YYYY-MM");
        int okCnt = 0, miss = 0;
        for (SalaryRow row : req.rows()) {
            BigDecimal base = row.basePay() == null ? BigDecimal.ZERO : row.basePay();
            BigDecimal bonus = row.bonus() == null ? BigDecimal.ZERO : row.bonus();
            BigDecimal ded = row.deduction() == null ? BigDecimal.ZERO : row.deduction();
            // 工号解析并入 upsert（1.1.7：原先每行先查 id 再写，2N 次往返）
            int n = jdbc.update("""
                    insert into hr_salary(employee_id, month, base_pay, bonus, deduction, total)
                    select e.id, ?, ?, ?, ?, ? from hr_employee e where e.emp_no = ?
                    on conflict (employee_id, month) do update set base_pay = excluded.base_pay,
                        bonus = excluded.bonus, deduction = excluded.deduction, total = excluded.total
                    """, req.month(), base, bonus, ded, base.add(bonus).subtract(ded), row.empNo());
            if (n == 0) {
                miss++;
                continue;
            }
            okCnt++;
        }
        return R.ok(Map.of("imported", okCnt, "missing", miss));
    }

    /** 个人工资按月查询 */
    @GetMapping("/salaries")
    public R<List<Map<String, Object>>> salaries(@RequestParam String empNo,
                                                 @RequestParam(required = false) String month) {
        String base = """
                select s.*, e.name as emp_name from hr_salary s
                join hr_employee e on e.id = s.employee_id where e.emp_no = ?
                """;
        return R.ok(month == null
                ? jdbc.queryForList(base + " order by s.month desc limit 24", empNo)
                : jdbc.queryForList(base + " and s.month = ?", empNo, month));
    }

    // ---- 招聘登记 ----
    public record RecruitReq(String candidateName, String position, String interviewDate, String note) {}

    @PostMapping("/recruits")
    public R<Void> addRecruit(@RequestBody RecruitReq req) {
        jdbc.update("insert into hr_recruit(candidate_name, position, interview_date, note) values (?,?,?::date,?)",
                req.candidateName(), req.position(), req.interviewDate(), req.note());
        return R.ok();
    }

    @PutMapping("/recruits/{id}/result")
    public R<Void> recruitResult(@PathVariable Long id, @RequestParam String result) {
        if (!"PASS".equals(result) && !"FAIL".equals(result)) return R.fail(4722, "结果只能为 PASS/FAIL");
        int n = jdbc.update("update hr_recruit set result = ? where id = ? and result = 'PENDING'", result, id);
        return n == 0 ? R.fail(4723, "登记不存在或已定结果") : R.ok();
    }

    @GetMapping("/recruits")
    public R<List<Map<String, Object>>> recruits() {
        return R.ok(jdbc.queryForList("select * from hr_recruit order by id desc limit 100"));
    }
}
