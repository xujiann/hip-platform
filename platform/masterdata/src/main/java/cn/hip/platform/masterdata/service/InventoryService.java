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
    private final jakarta.persistence.EntityManager entityManager;

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
        // 原子加：读-改-写会覆盖期间并发发药的扣减，导致库存虚增、账实不符
        drugRepository.restoreStock(drugId, qty);

        InvStockIn in = new InvStockIn();
        in.setInNo("RK" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + nextInSeq());
        in.setDrugId(drugId);
        in.setQty(qty);
        in.setBatchNo(batchNo);
        in.setExpireDate(expireDate);
        in.setSupplier(supplier);
        in.setOperatorId(operatorId);
        in = stockInRepository.save(in);
        int stockAfter = drugRepository.findById(drugId).map(DrugItem::getStock).orElse(0);
        log(drugId, "IN", qty, stockAfter, in.getInNo(), operatorId);
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
        int expected = drug.getStock();
        int delta = newStock - expected;
        // 条件更新：盘点提交与并发发药交叠时，整行写会把发药的扣减覆盖掉
        if (drugRepository.adjustStock(drugId, expected, newStock) == 0) {
            throw new InventoryException(8004, "库存已变化（期间有发药或入库），请重新盘点");
        }
        log(drugId, "ADJ", delta, newStock, reason, operatorId);
    }

    public List<DrugItem> lowStock(int threshold) {
        return drugRepository.findByEnabledTrueAndStockLessThan(threshold);
    }

    /** 入库单号取序列：nanoTime%1e6 会碰撞唯一约束（裸 500） */
    private long nextInSeq() {
        return ((Number) entityManager.createNativeQuery("select nextval('inv_stock_in_seq')")
                .unwrap(org.hibernate.query.NativeQuery.class)
                .addSynchronizedQuerySpace("")   // 取号不触发全会话 flush（1.1.7 B-8）
                .getSingleResult()).longValue();
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
