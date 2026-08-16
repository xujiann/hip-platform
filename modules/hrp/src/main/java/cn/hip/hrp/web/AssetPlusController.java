package cn.hip.hrp.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 三十五期：资产处置——调拨/移交留痕、报废申请审核、房屋建筑台账 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/asset-plus")
@PreAuthorize("hasAnyRole('ADMIN','OPERATION')")   // 1.0.9：权限清点补齐
public class AssetPlusController {

    private final JdbcTemplate jdbc;

    // ---- 调拨/移交 ----
    public record TransferReq(Long assetId, Long toDeptId, String fromPerson, String toPerson, String note) {}

    /** 调拨：变更资产归属科室并留痕 */
    @PostMapping("/transfers")
    @Transactional
    public R<Void> transfer(@RequestBody TransferReq req) {
        var rows = jdbc.queryForList("select dept_id, status from hrp_asset where id = ?", req.assetId());
        if (rows.isEmpty()) return R.fail(4730, "资产不存在");
        if ("SCRAPPED".equals(rows.get(0).get("status"))) return R.fail(4731, "已报废资产不可调拨");
        jdbc.update("""
                insert into as_transfer_log(asset_id, action, from_dept_id, to_dept_id, note)
                values (?,'TRANSFER',?,?,?)
                """, req.assetId(), rows.get(0).get("dept_id"), req.toDeptId(), req.note());
        jdbc.update("update hrp_asset set dept_id = ? where id = ?", req.toDeptId(), req.assetId());
        return R.ok();
    }

    /** 移交登记（人到人） */
    @PostMapping("/handovers")
    public R<Void> handover(@RequestBody TransferReq req) {
        Integer asset = jdbc.queryForObject("select count(*) from hrp_asset where id = ?", Integer.class, req.assetId());
        if (asset == null || asset == 0) return R.fail(4730, "资产不存在");
        jdbc.update("""
                insert into as_transfer_log(asset_id, action, from_person, to_person, note)
                values (?,'HANDOVER',?,?,?)
                """, req.assetId(), req.fromPerson(), req.toPerson(), req.note());
        return R.ok();
    }

    @GetMapping("/transfers")
    public R<List<Map<String, Object>>> transfers() {
        return R.ok(jdbc.queryForList("""
                select l.*, a.asset_no, a.name as asset_name,
                       fd.name as from_dept, td.name as to_dept
                from as_transfer_log l
                join hrp_asset a on a.id = l.asset_id
                left join sys_dept fd on fd.id = l.from_dept_id
                left join sys_dept td on td.id = l.to_dept_id
                order by l.id desc limit 100
                """));
    }

    // ---- 报废 ----
    @PostMapping("/scraps")
    public R<Void> applyScrap(@RequestParam Long assetId, @RequestParam String reason) {
        Integer pending = jdbc.queryForObject(
                "select count(*) from as_scrap where asset_id = ? and status = 'APPLIED'", Integer.class, assetId);
        if (pending != null && pending > 0) return R.fail(4732, "已有待审报废申请");
        jdbc.update("insert into as_scrap(asset_id, reason) values (?,?)", assetId, reason);
        return R.ok();
    }

    /** 报废审核：通过则资产置 SCRAPPED */
    @PutMapping("/scraps/{id}/review")
    @Transactional
    public R<Void> reviewScrap(@PathVariable Long id, @RequestParam boolean approve,
                               @RequestParam(required = false) String note) {
        int n = jdbc.update("""
                update as_scrap set status = ?, review_note = ?, decided_at = now()
                where id = ? and status = 'APPLIED'
                """, approve ? "APPROVED" : "REJECTED", note, id);
        if (n == 0) return R.fail(4733, "申请不存在或已审核");
        if (approve) {
            jdbc.update("""
                    update hrp_asset set status = 'SCRAPPED'
                    where id = (select asset_id from as_scrap where id = ?)
                    """, id);
        }
        return R.ok();
    }

    @GetMapping("/scraps")
    public R<List<Map<String, Object>>> scraps() {
        return R.ok(jdbc.queryForList("""
                select s.*, a.asset_no, a.name as asset_name from as_scrap s
                join hrp_asset a on a.id = s.asset_id order by s.id desc limit 100
                """));
    }

    // ---- 房屋建筑 ----
    public record BuildingReq(String buildingNo, String name, String address, Double areaSqm, String usageType, String remark) {}

    @PostMapping("/buildings")
    public R<Void> addBuilding(@RequestBody BuildingReq req) {
        jdbc.update("""
                insert into hrp_building(building_no, name, address, area_sqm, usage_type, remark)
                values (?,?,?,?,?,?)
                on conflict (building_no) do update set name = excluded.name, address = excluded.address,
                    area_sqm = excluded.area_sqm, usage_type = excluded.usage_type, remark = excluded.remark
                """, req.buildingNo(), req.name(), req.address(), req.areaSqm(), req.usageType(), req.remark());
        return R.ok();
    }

    @GetMapping("/buildings")
    public R<List<Map<String, Object>>> buildings() {
        return R.ok(jdbc.queryForList("select * from hrp_building order by building_no"));
    }
}
