package cn.hip.platform.masterdata.service;

import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.entity.InvStockIn;
import cn.hip.platform.masterdata.entity.InvTransaction;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.repository.InvStockInRepository;
import cn.hip.platform.masterdata.repository.InvTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final DrugItemRepository drugRepository;
    private final InvStockInRepository stockInRepository;
    private final InvTransactionRepository transactionRepository;

    public static class InventoryException extends RuntimeException {
        public final int code;
        public InventoryException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 入库：加库存并留痕 */
    @Transactional
    public InvStockIn stockIn(Long drugId, int qty, String batchNo, LocalDate expireDate,
                              String supplier, Long operatorId) {
        if (qty <= 0) throw new InventoryException(8001, "入库数量必须大于 0");
        DrugItem drug = drugRepository.findById(drugId)
                .orElseThrow(() -> new InventoryException(8002, "药品不存在"));
        drug.setStock(drug.getStock() + qty);
        drugRepository.save(drug);

        InvStockIn in = new InvStockIn();
        in.setInNo("RK" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + System.nanoTime() % 1000000);
        in.setDrugId(drugId);
        in.setQty(qty);
        in.setBatchNo(batchNo);
        in.setExpireDate(expireDate);
        in.setSupplier(supplier);
        in.setOperatorId(operatorId);
        in = stockInRepository.save(in);
        log(drugId, "IN", qty, drug.getStock(), in.getInNo(), operatorId);
        return in;
    }

    /** 出库留痕（发药/病区执行调用，扣减已由调用方原子完成） */
    @Transactional
    public void logOut(Long drugId, int qty, String refNo, Long operatorId) {
        int stockAfter = drugRepository.findById(drugId).map(DrugItem::getStock).orElse(0);
        log(drugId, "OUT", -qty, stockAfter, refNo, operatorId);
    }

    /** 退药回补留痕（库存已由调用方回加） */
    @Transactional
    public void logReturn(Long drugId, int qty, String refNo, Long operatorId) {
        int stockAfter = drugRepository.findById(drugId).map(DrugItem::getStock).orElse(0);
        log(drugId, "RET", qty, stockAfter, refNo, operatorId);
    }

    /** 盘点调整：直接设定库存 */
    @Transactional
    public void adjust(Long drugId, int newStock, String reason, Long operatorId) {
        if (newStock < 0) throw new InventoryException(8003, "库存不能为负");
        DrugItem drug = drugRepository.findById(drugId)
                .orElseThrow(() -> new InventoryException(8002, "药品不存在"));
        int delta = newStock - drug.getStock();
        drug.setStock(newStock);
        drugRepository.save(drug);
        log(drugId, "ADJ", delta, newStock, reason, operatorId);
    }

    public List<DrugItem> lowStock(int threshold) {
        return drugRepository.findAll().stream()
                .filter(d -> d.getEnabled() && d.getStock() < threshold)
                .toList();
    }

    private void log(Long drugId, String type, int qty, int stockAfter, String refNo, Long operatorId) {
        InvTransaction t = new InvTransaction();
        t.setDrugId(drugId);
        t.setType(type);
        t.setQty(qty);
        t.setStockAfter(stockAfter);
        t.setRefNo(refNo);
        t.setOperatorId(operatorId);
        transactionRepository.save(t);
    }
}
