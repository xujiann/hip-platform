package cn.hip.platform.masterdata.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.entity.InvStockIn;
import cn.hip.platform.masterdata.entity.InvStockTake;
import cn.hip.platform.masterdata.entity.InvTransaction;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.repository.InvStockInRepository;
import cn.hip.platform.masterdata.repository.InvTransactionRepository;
import cn.hip.platform.masterdata.service.ExpiryAlertScheduler;
import cn.hip.platform.masterdata.service.InventoryService;
import cn.hip.platform.masterdata.service.InventoryService.InventoryException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
@PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")   // 1.0.6：库存出入与盘点限药师
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final ExpiryAlertScheduler expiryAlertScheduler;
    private final InvStockInRepository stockInRepository;
    private final InvTransactionRepository transactionRepository;
    private final DrugItemRepository drugRepository;
    private final CurrentUserService currentUserService;

    // ===== 入库登记 + 验收（药事全链③）=====

    public record StockInRequest(Long drugId, Integer qty, String batchNo, LocalDate expireDate,
                                 String supplier, String purchaseNo) {}

    /** 入库登记：落待验收单，验收通过才加库存 */
    @PostMapping("/stock-in")
    public R<Object> stockIn(@RequestBody StockInRequest req, Authentication auth) {
        try {
            return R.ok(inventoryService.stockIn(req.drugId(), req.qty() == null ? 0 : req.qty(),
                    req.batchNo(), req.expireDate(), req.supplier(), req.purchaseNo(),
                    currentUserService.idOf(auth)));
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 待验收入库单列表 */
    @GetMapping("/stock-ins/pending")
    public R<List<Map<String, Object>>> pendingStockIns() {
        return R.ok(inventoryService.pendingStockIns().stream().map(this::stockInRow).toList());
    }

    @PostMapping("/stock-in/{id}/accept")
    public R<Object> acceptStockIn(@PathVariable Long id, Authentication auth) {
        try {
            inventoryService.acceptStockIn(id, currentUserService.idOf(auth));
            return R.ok();
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record RejectRequest(String reason) {}

    @PostMapping("/stock-in/{id}/reject")
    public R<Object> rejectStockIn(@PathVariable Long id, @RequestBody RejectRequest req, Authentication auth) {
        try {
            inventoryService.rejectStockIn(id, req == null ? null : req.reason(), currentUserService.idOf(auth));
            return R.ok();
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping("/stock-ins")
    public R<List<Map<String, Object>>> stockIns() {
        return R.ok(stockInRepository.findTop50ByOrderByIdDesc().stream().map(this::stockInRow).toList());
    }

    private Map<String, Object> stockInRow(InvStockIn s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", s.getId());
        m.put("inNo", s.getInNo());
        m.put("drugId", s.getDrugId());
        m.put("drugName", drugName(s.getDrugId()));
        m.put("qty", s.getQty());
        m.put("batchNo", s.getBatchNo());
        m.put("expireDate", s.getExpireDate());
        m.put("supplier", s.getSupplier());
        m.put("purchaseNo", s.getPurchaseNo());
        m.put("acceptStatus", s.getAcceptStatus());
        m.put("rejectReason", s.getRejectReason());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    // ===== 单药快速盘点调整（保留原入口）=====

    public record AdjustRequest(Long drugId, Integer newStock, String reason) {}

    @PostMapping("/adjust")
    public R<Object> adjust(@RequestBody AdjustRequest req, Authentication auth) {
        try {
            inventoryService.adjust(req.drugId(), req.newStock(), req.reason(), currentUserService.idOf(auth));
            return R.ok();
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ===== 库存盘点单（药事全链①）=====

    public record CreateTakeRequest(List<Long> drugIds, String remark) {}

    @PostMapping("/stock-take")
    public R<Object> createStockTake(@RequestBody CreateTakeRequest req, Authentication auth) {
        try {
            return R.ok(inventoryService.createStockTake(
                    req.drugIds(), req.remark(), currentUserService.idOf(auth)));
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record CountsRequest(List<InventoryService.CountEntry> entries) {}

    @PostMapping("/stock-take/{id}/counts")
    public R<Object> enterCounts(@PathVariable Long id, @RequestBody CountsRequest req) {
        try {
            return R.ok(inventoryService.enterCounts(id, req == null ? null : req.entries()));
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PostMapping("/stock-take/{id}/confirm")
    public R<Object> confirmStockTake(@PathVariable Long id, Authentication auth) {
        try {
            return R.ok(inventoryService.confirmStockTake(id, currentUserService.idOf(auth)));
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PostMapping("/stock-take/{id}/cancel")
    public R<Object> cancelStockTake(@PathVariable Long id) {
        try {
            return R.ok(inventoryService.cancelStockTake(id));
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping("/stock-take/{id}")
    public R<Object> getStockTake(@PathVariable Long id) {
        try {
            return R.ok(inventoryService.getStockTake(id));
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping("/stock-takes")
    public R<List<Map<String, Object>>> stockTakes() {
        return R.ok(inventoryService.recentStockTakes().stream().map(t -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", t.getId());
            m.put("takeNo", t.getTakeNo());
            m.put("status", t.getStatus());
            m.put("remark", t.getRemark());
            m.put("createdAt", t.getCreatedAt());
            m.put("confirmedAt", t.getConfirmedAt());
            return (Map<String, Object>) m;
        }).toList());
    }

    // ===== 效期预警（药事全链②）=====

    @GetMapping("/expiry-warning")
    public R<List<InventoryService.ExpiryWarning>> expiryWarning(@RequestParam(defaultValue = "90") int days) {
        return R.ok(inventoryService.expiryWarnings(days));
    }

    /** 手动触发一轮近效期巡检（返回本轮开单数），供运维/E2E 当场验证 */
    @PostMapping("/expiry-scan")
    public R<Map<String, Object>> expiryScan() {
        int opened = expiryAlertScheduler.runScan();
        return R.ok(Map.of("opened", opened));
    }

    // ===== 流水 / 低库存（原有）=====

    @GetMapping("/transactions")
    public R<List<Map<String, Object>>> transactions(@RequestParam(required = false) Long drugId) {
        List<InvTransaction> list = drugId == null
                ? transactionRepository.findTop100ByOrderByIdDesc()
                : transactionRepository.findTop100ByDrugIdOrderByIdDesc(drugId);
        return R.ok(list.stream().map(t -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("drugName", drugName(t.getDrugId()));
            m.put("type", t.getType());
            m.put("qty", t.getQty());
            m.put("stockAfter", t.getStockAfter());
            m.put("refNo", t.getRefNo());
            m.put("createdAt", t.getCreatedAt());
            return (Map<String, Object>) m;
        }).toList());
    }

    @GetMapping("/low-stock")
    public R<List<DrugItem>> lowStock(@RequestParam(defaultValue = "100") int threshold) {
        return R.ok(inventoryService.lowStock(threshold));
    }

    private String drugName(Long drugId) {
        return drugRepository.findById(drugId).map(DrugItem::getName).orElse("");
    }
}
