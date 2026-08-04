package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 二十二期：运维保障——健康概览与告警、故障台账、巡检台账、慢接口、备份记录 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/ops")
public class OpsController {

    private final JdbcTemplate jdbc;

    /** 健康概览：数据库连通、运行时长、磁盘、告警清单 */
    @GetMapping("/health-overview")
    public R<Map<String, Object>> healthOverview() {
        var m = new LinkedHashMap<String, Object>();
        boolean dbUp;
        try {
            jdbc.queryForObject("select 1", Integer.class);
            dbUp = true;
        } catch (Exception e) {
            dbUp = false;
        }
        m.put("dbUp", dbUp);
        m.put("uptimeMinutes", ManagementFactory.getRuntimeMXBean().getUptime() / 60000);
        File root = new File(System.getProperty("user.dir"));
        m.put("diskFreeGb", root.getFreeSpace() / 1024 / 1024 / 1024);
        m.put("heapUsedMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024);

        List<String> alerts = new ArrayList<>();
        if (!dbUp) alerts.add("数据库连接不可用");
        Integer openHigh = jdbc.queryForObject(
                "select count(*) from ops_fault_ticket where status = 'OPEN' and level = 'HIGH'", Integer.class);
        if (openHigh != null && openHigh > 0) alerts.add("存在 " + openHigh + " 条未处理高级别故障");
        Integer recentBackup = jdbc.queryForObject(
                "select count(*) from ops_backup_log where status in ('SUCCESS','VERIFIED') and created_at > now() - interval '24 hours'",
                Integer.class);
        if (recentBackup == null || recentBackup == 0) alerts.add("24 小时内无成功备份");
        Integer slowToday = jdbc.queryForObject(
                "select count(*) from ops_slow_api where occurred_at::date = current_date", Integer.class);
        if (slowToday != null && slowToday > 50) alerts.add("今日慢接口 " + slowToday + " 次，超过阈值 50");
        m.put("slowApiToday", slowToday);
        m.put("openFaults", jdbc.queryForObject("select count(*) from ops_fault_ticket where status = 'OPEN'", Integer.class));
        m.put("alerts", alerts);
        return R.ok(m);
    }

    // ---- 故障台账 ----
    public record FaultReq(String title, String level) {}

    @PostMapping("/faults")
    public R<Void> createFault(@RequestBody FaultReq req, Authentication auth) {
        jdbc.update("insert into ops_fault_ticket(title, level, reporter) values (?,?,?)",
                req.title(), req.level() == null ? "LOW" : req.level(), auth.getName());
        return R.ok();
    }

    @GetMapping("/faults")
    public R<List<Map<String, Object>>> faults() {
        return R.ok(jdbc.queryForList("select * from ops_fault_ticket order by id desc limit 200"));
    }

    @PutMapping("/faults/{id}/resolve")
    public R<Void> resolveFault(@PathVariable Long id, @RequestParam String note) {
        int n = jdbc.update(
                "update ops_fault_ticket set status = 'RESOLVED', resolve_note = ?, resolved_at = now() where id = ? and status = 'OPEN'",
                note, id);
        return n == 0 ? R.fail(9950, "故障不存在或已处理") : R.ok();
    }

    // ---- 巡检台账 ----
    public record InspectionReq(String item, String result, String note) {}

    @PostMapping("/inspections")
    public R<Void> addInspection(@RequestBody InspectionReq req, Authentication auth) {
        jdbc.update("insert into ops_inspection(item, result, note, inspector) values (?,?,?,?)",
                req.item(), req.result() == null ? "PASS" : req.result(), req.note(), auth.getName());
        return R.ok();
    }

    @GetMapping("/inspections")
    public R<List<Map<String, Object>>> inspections() {
        return R.ok(jdbc.queryForList("select * from ops_inspection order by id desc limit 200"));
    }

    // ---- 慢接口观测 ----
    @GetMapping("/slow-apis")
    public R<List<Map<String, Object>>> slowApis() {
        return R.ok(jdbc.queryForList("""
                select method, path, count(*) as times, max(cost_ms) as max_ms, round(avg(cost_ms)) as avg_ms,
                       max(occurred_at) as last_at
                from ops_slow_api where occurred_at > now() - interval '7 days'
                group by method, path order by times desc limit 100
                """));
    }

    // ---- 备份记录（备份由 tools/db-backup.ps1 执行，执行后调用本接口留痕） ----
    public record BackupReq(String fileName, Long sizeBytes, String status, String note) {}

    @PostMapping("/backups")
    public R<Void> recordBackup(@RequestBody BackupReq req) {
        jdbc.update("insert into ops_backup_log(file_name, size_bytes, status, note) values (?,?,?,?)",
                req.fileName(), req.sizeBytes() == null ? 0 : req.sizeBytes(),
                req.status() == null ? "SUCCESS" : req.status(), req.note());
        return R.ok();
    }

    @GetMapping("/backups")
    public R<List<Map<String, Object>>> backups() {
        return R.ok(jdbc.queryForList("select * from ops_backup_log order by id desc limit 100"));
    }
}
