package cn.hip.hrp.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 三十二期：设备全生命周期——维修工单（报修→派工→完成）、保养计划/保养单、计量台账 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/equipment")
@PreAuthorize("hasAnyRole('ADMIN','OPERATION')")   // 1.0.9：权限清点补齐
public class EquipmentController {

    private final JdbcTemplate jdbc;

    // ---- 维修工单 ----
    public record RepairReq(Long assetId, String faultDesc, String repairType) {}

    @PostMapping("/repairs")
    public R<Void> report(@RequestBody RepairReq req) {
        Integer asset = jdbc.queryForObject("select count(*) from hrp_asset where id = ?", Integer.class, req.assetId());
        if (asset == null || asset == 0) return R.fail(4680, "资产不存在");
        if (!"IN".equals(req.repairType()) && !"OUT".equals(req.repairType())) return R.fail(4681, "维修方式只能为 IN/OUT");
        jdbc.update("insert into eq_repair_order(asset_id, fault_desc, repair_type) values (?,?,?)",
                req.assetId(), req.faultDesc(), req.repairType());
        return R.ok();
    }

    @PutMapping("/repairs/{id}/assign")
    public R<Void> assign(@PathVariable Long id, @RequestParam String assignee) {
        int n = jdbc.update("""
                update eq_repair_order set status = 'ASSIGNED', assignee = ?, assigned_at = now()
                where id = ? and status = 'REPORTED'
                """, assignee, id);
        return n == 0 ? R.fail(4682, "工单不存在或已派工") : R.ok();
    }

    @PutMapping("/repairs/{id}/done")
    public R<Void> done(@PathVariable Long id, @RequestParam String note) {
        int n = jdbc.update("""
                update eq_repair_order set status = 'DONE', result_note = ?, done_at = now()
                where id = ? and status = 'ASSIGNED'
                """, note, id);
        return n == 0 ? R.fail(4683, "须先派工后完成") : R.ok();
    }

    /** 维修台账（关键字检索资产名/故障） */
    @GetMapping("/repairs")
    public R<List<Map<String, Object>>> repairs(@RequestParam(required = false) String keyword) {
        String base = """
                select r.*, a.asset_no, a.name as asset_name from eq_repair_order r
                join hrp_asset a on a.id = r.asset_id
                """;
        return R.ok(keyword == null
                ? jdbc.queryForList(base + " order by r.id desc limit 100")
                : jdbc.queryForList(base + " where a.name ilike ? or r.fault_desc ilike ? order by r.id desc limit 100",
                        "%" + keyword + "%", "%" + keyword + "%"));
    }

    // ---- 保养 ----
    public record PlanReq(Long assetId, Integer cycleDays, String nextDate, String remark) {}

    @PostMapping("/maintain-plans")
    public R<Void> addPlan(@RequestBody PlanReq req) {
        jdbc.update("insert into eq_maintain_plan(asset_id, cycle_days, next_date, remark) values (?,?,?::date,?)",
                req.assetId(), req.cycleDays(), req.nextDate(), req.remark());
        return R.ok();
    }

    /** 保养计划（含到期标记） */
    @GetMapping("/maintain-plans")
    public R<List<Map<String, Object>>> plans() {
        return R.ok(jdbc.queryForList("""
                select p.*, a.asset_no, a.name as asset_name, p.next_date <= current_date as due
                from eq_maintain_plan p join hrp_asset a on a.id = p.asset_id order by p.next_date
                """));
    }

    /** 执行保养：留保养单并顺延下次保养日 */
    @PostMapping("/maintain-plans/{id}/execute")
    @Transactional
    public R<Void> execute(@PathVariable Long id, @RequestParam String content) {
        var rows = jdbc.queryForList("select * from eq_maintain_plan where id = ?", id);
        if (rows.isEmpty()) return R.fail(4684, "保养计划不存在");
        var p = rows.get(0);
        jdbc.update("insert into eq_maintain_record(plan_id, asset_id, content) values (?,?,?)",
                id, p.get("asset_id"), content);
        jdbc.update("update eq_maintain_plan set next_date = current_date + cycle_days where id = ?", id);
        return R.ok();
    }

    /** 保养单作废（误录还原） */
    @PutMapping("/maintain-records/{id}/cancel")
    public R<Void> cancelRecord(@PathVariable Long id) {
        int n = jdbc.update("update eq_maintain_record set status = 'CANCELLED' where id = ? and status = 'DONE'", id);
        return n == 0 ? R.fail(4685, "保养单不存在或已作废") : R.ok();
    }

    @GetMapping("/maintain-records")
    public R<List<Map<String, Object>>> records() {
        return R.ok(jdbc.queryForList("""
                select r.*, a.name as asset_name from eq_maintain_record r
                join hrp_asset a on a.id = r.asset_id order by r.id desc limit 100
                """));
    }

    // ---- 计量台账 ----
    public record MetrologyReq(Long assetId, String certNo, String checkedAt, String validUntil, String agency) {}

    @PostMapping("/metrology")
    public R<Void> addMetrology(@RequestBody MetrologyReq req) {
        jdbc.update("""
                insert into eq_metrology(asset_id, cert_no, checked_at, valid_until, agency)
                values (?,?,?::date,?::date,?)
                """, req.assetId(), req.certNo(), req.checkedAt(), req.validUntil(), req.agency());
        return R.ok();
    }

    /** 计量台账（90 天内到期/已过期标记） */
    @GetMapping("/metrology")
    public R<List<Map<String, Object>>> metrology() {
        return R.ok(jdbc.queryForList("""
                select m.*, a.asset_no, a.name as asset_name,
                       case when m.valid_until < current_date then 'EXPIRED'
                            when m.valid_until < current_date + 90 then 'EXPIRING' else 'VALID' end as validity
                from eq_metrology m join hrp_asset a on a.id = m.asset_id
                order by m.valid_until
                """));
    }
}
