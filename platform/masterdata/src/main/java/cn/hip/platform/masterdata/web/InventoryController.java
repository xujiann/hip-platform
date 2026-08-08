package cn.hip.platform.masterdata.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.entity.InvTransaction;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.repository.InvStockInRepository;
import cn.hip.platform.masterdata.repository.InvTransactionRepository;
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
    private final InvStockInRepository stockInRepository;
    private final InvTransactionRepository transactionRepository;
    private final DrugItemRepository drugRepository;
    private final CurrentUserService currentUserService;

    public record StockInRequest(Long drugId, Integer qty, String batchNo, LocalDate expireDate, String supplier) {}

    @PostMapping("/stock-in")
    public R<Object> stockIn(@RequestBody StockInRequest req, Authentication auth) {
        try {
            return R.ok(inventoryService.stockIn(req.drugId(), req.qty() == null ? 0 : req.qty(),
                    req.batchNo(), req.expireDate(), req.supplier(), currentUserService.idOf(auth)));
        } catch (InventoryException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

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

    @GetMapping("/stock-ins")
    public R<List<Map<String, Object>>> stockIns() {
        return R.ok(stockInRepository.findTop50ByOrderByIdDesc().stream().map(s -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("inNo", s.getInNo());
            m.put("drugName", drugName(s.getDrugId()));
            m.put("qty", s.getQty());
            m.put("batchNo", s.getBatchNo());
            m.put("expireDate", s.getExpireDate());
            m.put("supplier", s.getSupplier());
            m.put("createdAt", s.getCreatedAt());
            return (Map<String, Object>) m;
        }).toList());
    }

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
