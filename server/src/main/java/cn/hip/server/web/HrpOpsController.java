package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 十一期：物资/耗材库存、供应商、OA 审批 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class HrpOpsController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    // ---- 物资/耗材 ----
    public record MaterialReq(String name, String category, String unit, BigDecimal price) {}

    @PostMapping("/api/hrp/materials")
    public R<Void> createMaterial(@RequestBody MaterialReq req) {
        if (!List.of("MATERIAL", "DEVICE_PART", "CONSUMABLE_HIGH", "CONSUMABLE_LOW").contains(req.category())) {
            return R.fail(9910, "类别须为 MATERIAL/DEVICE_PART/CONSUMABLE_HIGH/CONSUMABLE_LOW");
        }
        jdbc.update("insert into hrp_material(code, name, category, unit, price) values (?,?,?,?,?)",
                "WZ" + System.nanoTime() % 10000000, req.name(), req.category(), req.unit(), req.price());
        return R.ok();
    }

    @GetMapping("/api/hrp/materials")
    public R<List<Map<String, Object>>> materials() {
        return R.ok(jdbc.queryForList("select * from hrp_material where enabled order by id"));
    }

    @PostMapping("/api/hrp/materials/{id}/stock-in")
    @Transactional
    public R<Void> materialStockIn(@PathVariable Long id, @RequestParam int qty,
                                   @RequestParam(required = false) String refNo, Authentication auth) {
        if (qty <= 0) return R.fail(9911, "入库数量须大于 0");
        jdbc.update("update hrp_material set stock = stock + ? where id = ?", qty, id);
        logMaterialTxn(id, "IN", qty, refNo, auth);
        return R.ok();
    }

    @PostMapping("/api/hrp/materials/{id}/stock-out")
    @Transactional
    public R<Void> materialStockOut(@PathVariable Long id, @RequestParam int qty,
                                    @RequestParam(required = false) String refNo, Authentication auth) {
        if (qty <= 0) return R.fail(9911, "出库数量须大于 0");
        int n = jdbc.update("update hrp_material set stock = stock - ? where id = ? and stock >= ?", qty, id, qty);
        if (n == 0) return R.fail(9912, "库存不足");
        logMaterialTxn(id, "OUT", -qty, refNo, auth);
        return R.ok();
    }

    private void logMaterialTxn(Long id, String type, int qty, String refNo, Authentication auth) {
        Integer after = jdbc.queryForObject("select stock from hrp_material where id = ?", Integer.class, id);
        jdbc.update("insert into hrp_material_txn(material_id, type, qty, stock_after, ref_no, operator_id) values (?,?,?,?,?,?)",
                id, type, qty, after, refNo, currentUserService.idOf(auth));
    }

    @GetMapping("/api/hrp/materials/{id}/txns")
    public R<List<Map<String, Object>>> materialTxns(@PathVariable Long id) {
        return R.ok(jdbc.queryForList(
                "select type, qty, stock_after, ref_no, created_at from hrp_material_txn where material_id = ? order by id desc limit 100", id));
    }

    // ---- 供应商 ----
    public record SupplierReq(String name, String contact, String phone, String scope) {}

    @PostMapping("/api/hrp/suppliers")
    public R<Void> createSupplier(@RequestBody SupplierReq req) {
        jdbc.update("insert into hrp_supplier(name, contact, phone, scope) values (?,?,?,?)",
                req.name(), req.contact(), req.phone(), req.scope());
        return R.ok();
    }

    @GetMapping("/api/hrp/suppliers")
    public R<List<Map<String, Object>>> suppliers() {
        return R.ok(jdbc.queryForList("select * from hrp_supplier where enabled order by id"));
    }

    // ---- OA 审批 ----
    public record OaReq(String type, String title, String content) {}

    @PostMapping("/api/oa/requests")
    public R<Void> createOa(@RequestBody OaReq req, Authentication auth) {
        if (!List.of("LEAVE", "SEAL", "PURCHASE", "OTHER").contains(req.type())) {
            return R.fail(9913, "类型须为 LEAVE/SEAL/PURCHASE/OTHER");
        }
        jdbc.update("insert into oa_request(type, title, content, applicant_id) values (?,?,?,?)",
                req.type(), req.title(), req.content(), currentUserService.idOf(auth));
        return R.ok();
    }

    @GetMapping("/api/oa/requests")
    public R<List<Map<String, Object>>> oaRequests() {
        return R.ok(jdbc.queryForList("""
                select o.id, o.type, o.title, o.content, o.status, o.approve_note, o.created_at,
                       u.real_name as applicant_name
                from oa_request o left join sys_user u on u.id = o.applicant_id
                order by case o.status when 'PENDING' then 0 else 1 end, o.id desc limit 100
                """));
    }

    @PutMapping("/api/oa/requests/{id}/decide")
    public R<Void> decideOa(@PathVariable Long id, @RequestParam boolean approve,
                            @RequestParam(required = false) String note, Authentication auth) {
        int n = jdbc.update("""
                update oa_request set status = ?, approve_note = ?, approver_id = ?, decided_at = now()
                where id = ? and status = 'PENDING'
                """, approve ? "APPROVED" : "REJECTED", note, currentUserService.idOf(auth), id);
        return n == 0 ? R.fail(9914, "申请不存在或已审批") : R.ok();
    }
}
