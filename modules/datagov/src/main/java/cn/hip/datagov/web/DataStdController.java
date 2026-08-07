package cn.hip.datagov.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 二十六期：数据治理完整化——数据元管理、术语标准化、服务订阅、自定义报表引擎、ODR 汇总 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/datagov")
public class DataStdController {

    private final JdbcTemplate jdbc;

    // ---- 数据元 ----
    public record ElementReq(String code, String name, String datatype, String format,
                             String valueSet, String stdRef, String remark) {}

    @PostMapping("/elements")
    public R<Void> addElement(@RequestBody ElementReq req) {
        try {
            jdbc.update("""
                    insert into dg_data_element(code, name, datatype, format, value_set, std_ref, remark)
                    values (?,?,?,?,?,?,?)
                    """, req.code(), req.name(), req.datatype(), req.format(), req.valueSet(), req.stdRef(), req.remark());
        } catch (DuplicateKeyException e) {
            return R.fail(4630, "数据元标识已存在：" + req.code());
        }
        return R.ok();
    }

    @GetMapping("/elements")
    public R<List<Map<String, Object>>> elements(@RequestParam(required = false) String keyword) {
        return R.ok(keyword == null
                ? jdbc.queryForList("select * from dg_data_element order by code limit 200")
                : jdbc.queryForList("select * from dg_data_element where code ilike ? or name ilike ? order by code limit 200",
                        "%" + keyword + "%", "%" + keyword + "%"));
    }

    // ---- 术语 ----
    public record TermReq(String category, String localName, String stdCode, String stdName, String stdSystem) {}

    @PostMapping("/terms")
    public R<Void> addTerm(@RequestBody TermReq req) {
        try {
            jdbc.update("""
                    insert into dg_term(category, local_name, std_code, std_name, std_system) values (?,?,?,?,?)
                    """, req.category(), req.localName(), req.stdCode(), req.stdName(), req.stdSystem());
        } catch (DuplicateKeyException e) {
            return R.fail(4631, "该术语映射已存在");
        }
        return R.ok();
    }

    @GetMapping("/terms")
    public R<List<Map<String, Object>>> terms(@RequestParam(required = false) String category) {
        return R.ok(category == null
                ? jdbc.queryForList("select * from dg_term order by category, local_name limit 200")
                : jdbc.queryForList("select * from dg_term where category = ? order by local_name limit 200", category));
    }

    // ---- 服务订阅 ----
    public record SubReq(String eventType, String subscriber, String targetUrl) {}

    @PostMapping("/subscriptions")
    public R<Void> subscribe(@RequestBody SubReq req) {
        jdbc.update("insert into dg_subscription(event_type, subscriber, target_url) values (?,?,?)",
                req.eventType(), req.subscriber(), req.targetUrl());
        return R.ok();
    }

    @GetMapping("/subscriptions")
    public R<List<Map<String, Object>>> subscriptions() {
        return R.ok(jdbc.queryForList("""
                select s.*, (select count(*) from dg_push_log l where l.subscription_id = s.id) as pushes
                from dg_subscription s order by s.id desc limit 100
                """));
    }

    @PutMapping("/subscriptions/{id}/toggle")
    public R<Void> toggle(@PathVariable Long id) {
        int n = jdbc.update("update dg_subscription set enabled = not enabled where id = ?", id);
        return n == 0 ? R.fail(4632, "订阅不存在") : R.ok();
    }

    /** 测试推送（Mock：不出网，落推送流水验证链路） */
    @PostMapping("/subscriptions/{id}/test-push")
    public R<Map<String, Object>> testPush(@PathVariable Long id) {
        var subs = jdbc.queryForList("select * from dg_subscription where id = ? and enabled", id);
        if (subs.isEmpty()) return R.fail(4633, "订阅不存在或已停用");
        String payload = "{\"event\":\"" + subs.get(0).get("event_type") + "\",\"test\":true}";
        jdbc.update("insert into dg_push_log(subscription_id, payload, result) values (?,?,'MOCK')", id, payload);
        return R.ok(Map.of("pushed", true, "target", subs.get(0).get("target_url")));
    }

    @GetMapping("/subscriptions/{id}/pushes")
    public R<List<Map<String, Object>>> pushes(@PathVariable Long id) {
        return R.ok(jdbc.queryForList(
                "select * from dg_push_log where subscription_id = ? order by id desc limit 50", id));
    }

    // ---- 自定义报表引擎（只读 SELECT + 业务表白名单 + 查询超时；1.0.3 加固） ----
    /** 写操作与危险语句按词边界拦截（子串匹配曾误伤 updated_at） */
    private static final java.util.regex.Pattern FORBIDDEN_KW = java.util.regex.Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|copy|call|do|execute|vacuum|set|listen|notify)\\b");
    /** 受保护对象在 SQL 任意位置出现即拒绝（含别名/逗号连接等绕过路径）：sys_*（sys_dept 除外）、系统目录、迁移史 */
    private static final java.util.regex.Pattern SENSITIVE = java.util.regex.Pattern.compile(
            "\\b(sys_(?!dept\\b)[a-z0-9_]+|pg_[a-z0-9_]+|information_schema|flyway_[a-z0-9_]+|current_setting|set_config|dblink)\\b");
    private static final java.util.regex.Pattern TABLE_REF = java.util.regex.Pattern.compile(
            "\\b(?:from|join)\\s+([a-z_][a-z0-9_]*)");
    /** 业务表前缀白名单（另精确放行 sys_dept）；impl_ 为实施定制段 */
    private static final Set<String> ALLOWED_TABLE_PREFIX = Set.of(
            "anes_", "as_", "blood_", "cdr_", "cdss_", "cp_", "cssd_", "dg_", "drg_", "empi_", "emr_",
            "eq_", "er_", "hr_", "hrp_", "idc_", "inf_", "inp_", "int_", "inv_", "lis_", "md_", "med_",
            "mr_", "nur_", "oa_", "ops_", "outp_", "path_", "pay_", "pc_", "pe_", "phc_", "price_",
            "pur_", "qc_", "queue_", "report_", "ris_", "sd_", "shift_", "sr_", "svc_", "triage_",
            "yb_", "impl_");

    /** 报表专用模板：查询超时 5 秒，防长查询拖垮业务库 */
    private JdbcTemplate reportJdbc;

    @jakarta.annotation.PostConstruct
    void initReportJdbc() {
        reportJdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        reportJdbc.setQueryTimeout(5);
    }

    public record ReportReq(String name, String sqlText, String remark) {}

    @PostMapping("/reports")
    public R<Void> addReport(@RequestBody ReportReq req) {
        String err = validateSql(req.sqlText());
        if (err != null) return R.fail(4634, err);
        jdbc.update("insert into dg_report_def(name, sql_text, remark) values (?,?,?)",
                req.name(), req.sqlText(), req.remark());
        return R.ok();
    }

    @GetMapping("/reports")
    public R<List<Map<String, Object>>> reports() {
        return R.ok(jdbc.queryForList("select * from dg_report_def order by id desc limit 100"));
    }

    /** 运行报表：执行时再次校验，只读、限 200 行 */
    @PostMapping("/reports/{id}/run")
    public R<Map<String, Object>> runReport(@PathVariable Long id) {
        var defs = jdbc.queryForList("select sql_text from dg_report_def where id = ?", id);
        if (defs.isEmpty()) return R.fail(4635, "报表不存在");
        String sql = (String) defs.get(0).get("sql_text");
        String err = validateSql(sql);
        if (err != null) return R.fail(4634, err);
        List<Map<String, Object>> rows = reportJdbc.queryForList(
                "select * from (" + sql + ") _rpt limit 200");
        var m = new LinkedHashMap<String, Object>();
        m.put("columns", rows.isEmpty() ? List.of() : rows.get(0).keySet().stream().toList());
        m.put("rows", rows);
        return R.ok(m);
    }

    private String validateSql(String sql) {
        if (sql == null || sql.isBlank()) return "SQL 不能为空";
        String lower = sql.toLowerCase();
        if (!lower.trim().startsWith("select")) return "仅允许 SELECT 查询";
        if (lower.contains(";")) return "不允许分号";
        var kw = FORBIDDEN_KW.matcher(lower);
        if (kw.find()) return "包含禁用关键字：" + kw.group(1);
        var sens = SENSITIVE.matcher(lower);
        if (sens.find()) return "涉及受保护对象：" + sens.group(1);
        var tb = TABLE_REF.matcher(lower);
        while (tb.find()) {
            String t = tb.group(1);
            boolean allowed = "sys_dept".equals(t) || ALLOWED_TABLE_PREFIX.stream().anyMatch(t::startsWith);
            if (!allowed) return "表不在白名单：" + t;
        }
        return null;
    }

    // ---- ODR 运营数据中心汇总 ----
    @GetMapping("/odr/summary")
    public R<Map<String, Object>> odrSummary() {
        var m = new LinkedHashMap<String, Object>();
        m.put("outpToday", jdbc.queryForObject(
                "select count(*) from outp_registration where visit_date = current_date and status <> 'CANCELLED'", Integer.class));
        m.put("inHospital", jdbc.queryForObject(
                "select count(*) from inp_admission where status = 'IN_HOSPITAL'", Integer.class));
        m.put("revenueToday", jdbc.queryForObject(
                "select coalesce(sum(total_amount), 0) from outp_charge where status = 'PAID' and created_at::date = current_date",
                java.math.BigDecimal.class));
        m.put("labPending", jdbc.queryForObject(
                "select count(*) from lis_sample where status <> 'PUBLISHED'", Integer.class));
        m.put("examPending", jdbc.queryForObject(
                "select count(*) from ris_exam where status <> 'VERIFIED'", Integer.class));
        m.put("criticalOpen", jdbc.queryForObject(
                "select count(*) from outp_critical_alert where status = 'NEW'", Integer.class));
        m.put("metricSnapshots", jdbc.queryForList("""
                select d.code, d.name, d.unit, s.value from dg_metric_def d
                join lateral (select value from dg_metric_snapshot where code = d.code
                              order by snap_date desc limit 1) s on true
                order by d.code
                """));
        return R.ok(m);
    }
}
