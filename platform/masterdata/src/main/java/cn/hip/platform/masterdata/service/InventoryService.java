package cn.hip.platform.masterdata.service;

import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.entity.InvStockIn;
import cn.hip.platform.masterdata.entity.InvStockTake;
import cn.hip.platform.masterdata.entity.InvStockTakeLine;
import cn.hip.platform.masterdata.entity.InvTransaction;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.repository.InvStockInRepository;
import cn.hip.platform.masterdata.repository.InvStockTakeLineRepository;
import cn.hip.platform.masterdata.repository.InvStockTakeRepository;
import cn.hip.platform.masterdata.repository.InvTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import cn.hip.platform.core.config.BusinessDates;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final DrugItemRepository drugRepository;
    private final InvStockInRepository stockInRepository;
    private final InvTransactionRepository transactionRepository;
    private final InvStockTakeRepository stockTakeRepository;
    private final InvStockTakeLineRepository stockTakeLineRepository;
    private final jakarta.persistence.EntityManager entityManager;

    public static class InventoryException extends RuntimeException {
        public final int code;
        public InventoryException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ================= 入库验收（药事全链③）=================

    /**
     * 入库登记：只落一条待验收单，**不加库存、不写流水**。
     * 真正入账在验收通过（acceptStockIn）时发生——这样质检/验收环节才能挡住不合格批次，
     * 而不是入库即无条件加库存。与采购勾稽可选：purchaseNo 有则关联，无则允许直接入库验收。
     */
    @Transactional
    public InvStockIn stockIn(Long drugId, int qty, String batchNo, LocalDate expireDate,
                              String supplier, String purchaseNo, Long operatorId) {
        if (qty <= 0) throw new InventoryException(8001, "入库数量必须大于 0");
        drugRepository.findById(drugId)
                .orElseThrow(() -> new InventoryException(8002, "药品不存在"));

        InvStockIn in = new InvStockIn();
        in.setInNo("RK" + BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + nextInSeq());
        in.setDrugId(drugId);
        in.setQty(qty);
        in.setBatchNo(batchNo);
        in.setExpireDate(expireDate);
        in.setSupplier(supplier);
        in.setPurchaseNo(purchaseNo);
        in.setOperatorId(operatorId);
        in.setAcceptStatus("PENDING_ACCEPT");   // 显式待验收，验收前不入账
        return stockInRepository.save(in);
    }

    /** 验收通过：条件更新置 ACCEPTED，只有第一次影响 1 行才真正加库存并写 IN 流水（防重复入账） */
    @Transactional
    public InvStockIn acceptStockIn(Long stockInId, Long operatorId) {
        InvStockIn in = stockInRepository.findById(stockInId)
                .orElseThrow(() -> new InventoryException(8010, "入库单不存在"));
        if (!"PENDING_ACCEPT".equals(in.getAcceptStatus())) {
            throw new InventoryException(8011, "入库单不是待验收状态，无法验收");
        }
        // 先捕获再更新：markAccepted 带 clearAutomatically 会清持久化上下文，之后 in 即失效
        Long drugId = in.getDrugId();
        int qty = in.getQty();
        String inNo = in.getInNo();
        if (stockInRepository.markAccepted(stockInId, operatorId, Instant.now()) == 0) {
            throw new InventoryException(8011, "入库单已被处理（并发验收/拒收），请刷新");
        }
        // 条件更新已保证唯一入账，这里原子加库存 + 留痕
        drugRepository.restoreStock(drugId, qty);
        int stockAfter = drugRepository.findById(drugId).map(DrugItem::getStock).orElse(0);
        log(drugId, "IN", qty, stockAfter, inNo, operatorId);
        return stockInRepository.findById(stockInId).orElseThrow();
    }

    /** 拒收：条件更新置 REJECTED（带原因），不动库存 */
    @Transactional
    public InvStockIn rejectStockIn(Long stockInId, String reason, Long operatorId) {
        if (reason == null || reason.isBlank()) throw new InventoryException(8012, "拒收原因必填");
        InvStockIn in = stockInRepository.findById(stockInId)
                .orElseThrow(() -> new InventoryException(8010, "入库单不存在"));
        if (!"PENDING_ACCEPT".equals(in.getAcceptStatus())) {
            throw new InventoryException(8011, "入库单不是待验收状态，无法拒收");
        }
        if (stockInRepository.markRejected(stockInId, operatorId, Instant.now(), reason.trim()) == 0) {
            throw new InventoryException(8011, "入库单已被处理（并发验收/拒收），请刷新");
        }
        return stockInRepository.findById(stockInId).orElseThrow();
    }

    public List<InvStockIn> pendingStockIns() {
        return stockInRepository.findByAcceptStatusOrderByIdDesc("PENDING_ACCEPT");
    }

    // ================= 出/退库留痕（原有）=================

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

    /** 单药盘点调整（保留：快速直调入口，与盘点单并存）：直接设定库存 */
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

    // ================= 库存盘点（药事全链①）=================

    public record StockTakeLineView(Long lineId, Long drugId, String drugName,
                                    int bookQty, Integer actualQty, Integer diff) {}

    public record StockTakeView(Long id, String takeNo, String status, String remark,
                                Instant createdAt, Instant confirmedAt,
                                List<StockTakeLineView> lines,
                                int lineCount, int countedLines, int gainLines, int lossLines, int netDiff) {}

    public record CountEntry(Long drugId, Integer actualQty) {}

    /** 建盘点单：对给定药品逐一快照当前库存为账面数，落草稿单 */
    @Transactional
    public StockTakeView createStockTake(List<Long> drugIds, String remark, Long operatorId) {
        if (drugIds == null || drugIds.isEmpty()) throw new InventoryException(8009, "盘点单至少需选择一种药品");
        InvStockTake take = new InvStockTake();
        take.setTakeNo("PD" + BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + nextTakeSeq());
        take.setStatus("DRAFT");
        take.setRemark(remark);
        take.setOperatorId(operatorId);
        take = stockTakeRepository.save(take);
        for (Long drugId : drugIds.stream().distinct().toList()) {
            DrugItem drug = drugRepository.findById(drugId)
                    .orElseThrow(() -> new InventoryException(8002, "药品不存在"));
            InvStockTakeLine line = new InvStockTakeLine();
            line.setTakeId(take.getId());
            line.setDrugId(drugId);
            line.setBookQty(drug.getStock());   // 账面数快照
            stockTakeLineRepository.save(line);
        }
        return buildTakeView(take.getId());
    }

    /** 批量录入实盘数：命中行更新实盘数，未建行的药品按当前库存补建行后再录（upsert） */
    @Transactional
    public StockTakeView enterCounts(Long takeId, List<CountEntry> entries) {
        InvStockTake take = stockTakeRepository.findById(takeId)
                .orElseThrow(() -> new InventoryException(8005, "盘点单不存在"));
        if (!"DRAFT".equals(take.getStatus())) throw new InventoryException(8006, "盘点单非草稿状态，不能录入实盘数");
        if (entries != null) {
            for (CountEntry e : entries) {
                if (e.actualQty() != null && e.actualQty() < 0) throw new InventoryException(8007, "实盘数不能为负");
                InvStockTakeLine line = stockTakeLineRepository.findByTakeIdAndDrugId(takeId, e.drugId())
                        .orElseGet(() -> {
                            DrugItem drug = drugRepository.findById(e.drugId())
                                    .orElseThrow(() -> new InventoryException(8002, "药品不存在"));
                            InvStockTakeLine nl = new InvStockTakeLine();
                            nl.setTakeId(takeId);
                            nl.setDrugId(e.drugId());
                            nl.setBookQty(drug.getStock());
                            return nl;
                        });
                line.setActualQty(e.actualQty());
                stockTakeLineRepository.save(line);
            }
        }
        return buildTakeView(takeId);
    }

    /**
     * 确认盘点：对每条有实盘数且盈亏≠0 的行，按「账面=快照」条件更新到实盘数并写 STOCKTAKE 流水。
     * 任一药品账面在盘点期间被并发出入库改动（条件更新影响 0 行）即整单回滚、报 8008，
     * 迫使重新盘点——保证盈亏账实对账口径不被期间正常业务污染。
     */
    @Transactional
    public StockTakeView confirmStockTake(Long takeId, Long operatorId) {
        InvStockTake take = stockTakeRepository.findById(takeId)
                .orElseThrow(() -> new InventoryException(8005, "盘点单不存在"));
        String takeNo = take.getTakeNo();
        // 抢占 DRAFT→CONFIRMED（第七轮审阅 P2-3）：先原子占状态，防 confirm×cancel 并发下
        // "作废单却动了库存"。占不到即已被他方确认/作废
        if (stockTakeRepository.claimStatus(takeId, "CONFIRMED") == 0) {
            throw new InventoryException(8006, "盘点单非草稿状态，不能确认");
        }
        List<InvStockTakeLine> lines = stockTakeLineRepository.findByTakeIdOrderById(takeId);
        List<InvStockTakeLine> counted = lines.stream().filter(l -> l.getActualQty() != null).toList();
        if (counted.isEmpty()) throw new InventoryException(8009, "盘点单没有已录实盘数的盘点行，无法确认");
        for (InvStockTakeLine line : counted) {
            int book = line.getBookQty();
            int actual = line.getActualQty();
            int delta = actual - book;
            if (delta == 0) {
                // 账实相符行也要校验账面未在盘点期间漂移（第七轮审阅 P2-2）：
                // 原先 delta=0 直接跳过，会把"窗口内被并发发药改动过"的行记成"账实相符"，
                // 破坏审计口径且违背 javadoc 承诺。用零位移条件更新做存在性校验（不改库存）
                if (drugRepository.adjustStock(line.getDrugId(), book, book) == 0) {
                    String name = drugRepository.findById(line.getDrugId()).map(DrugItem::getName).orElse("");
                    throw new InventoryException(8008, "药品【" + name + "】账面已变化（盘点期间有出入库），请重新盘点");
                }
                continue;   // 账实相符且无漂移，无需调库也无需流水
            }
            if (drugRepository.adjustStock(line.getDrugId(), book, actual) == 0) {
                String name = drugRepository.findById(line.getDrugId()).map(DrugItem::getName).orElse("");
                throw new InventoryException(8008, "药品【" + name + "】账面已变化（盘点期间有出入库），请重新盘点");
            }
            log(line.getDrugId(), "STOCKTAKE", delta, actual, takeNo, operatorId);
        }
        // 状态已由入口 claimStatus 原子置 CONFIRMED；此处仅补确认时刻
        InvStockTake fresh = stockTakeRepository.findById(takeId).orElseThrow();
        fresh.setConfirmedAt(Instant.now());
        stockTakeRepository.saveAndFlush(fresh);
        return buildTakeView(takeId);
    }

    /** 作废盘点单（仅草稿可作废） */
    @Transactional
    public StockTakeView cancelStockTake(Long takeId) {
        stockTakeRepository.findById(takeId)
                .orElseThrow(() -> new InventoryException(8005, "盘点单不存在"));
        // 抢占 DRAFT→CANCELLED（第七轮审阅 P2-3）：与 confirm 同一抢占纪律，防并发下作废与确认互相覆盖
        if (stockTakeRepository.claimStatus(takeId, "CANCELLED") == 0) {
            throw new InventoryException(8006, "盘点单非草稿状态，不能作废");
        }
        return buildTakeView(takeId);
    }

    public StockTakeView getStockTake(Long takeId) {
        return buildTakeView(takeId);
    }

    public List<InvStockTake> recentStockTakes() {
        return stockTakeRepository.findTop50ByOrderByIdDesc();
    }

    private StockTakeView buildTakeView(Long takeId) {
        InvStockTake take = stockTakeRepository.findById(takeId)
                .orElseThrow(() -> new InventoryException(8005, "盘点单不存在"));
        List<InvStockTakeLine> lines = stockTakeLineRepository.findByTakeIdOrderById(takeId);
        List<StockTakeLineView> lineViews = new ArrayList<>();
        int countedLines = 0, gainLines = 0, lossLines = 0, netDiff = 0;
        for (InvStockTakeLine l : lines) {
            String name = drugRepository.findById(l.getDrugId()).map(DrugItem::getName).orElse("");
            Integer diff = l.getActualQty() == null ? null : l.getActualQty() - l.getBookQty();
            if (diff != null) {
                countedLines++;
                netDiff += diff;
                if (diff > 0) gainLines++;
                else if (diff < 0) lossLines++;
            }
            lineViews.add(new StockTakeLineView(l.getId(), l.getDrugId(), name, l.getBookQty(), l.getActualQty(), diff));
        }
        return new StockTakeView(take.getId(), take.getTakeNo(), take.getStatus(), take.getRemark(),
                take.getCreatedAt(), take.getConfirmedAt(), lineViews,
                lines.size(), countedLines, gainLines, lossLines, netDiff);
    }

    // ================= 效期预警（药事全链②，估算口径）=================

    public record ExpiryWarning(Long drugId, String drugName, Long stockInId, String batchNo,
                                LocalDate expireDate, long daysToExpire, int batchQty,
                                int estimatedRemaining, String status) {}

    /**
     * 近效期预警（**只读估算口径**）。
     *
     * <p>背景：库存是 md_drug.stock 单一聚合值，发药/退药直接扣聚合、不落批次级在库量，
     * 且扣减分散在 outpatient/inpatient 多处。要做精确批次追溯须跨模块改扣减路径，风险高。
     * 故此处采用估算：把该药「发药净出量」按 FEFO（先到期先出）从最早效期批次起分摊消耗，
     * 各批次入库量减去被分摊的消耗即为**估算在库量**；效期在阈值内且估算在库量>0 的批次报警。
     *
     * <p>已知偏差（明确不追求精确）：①实际发药未按批次记录，消耗按假设分摊；
     * ②盘点前的期初/种子库存不属任何入库批次，不参与分摊；③ADJ/STOCKTAKE 调整不计入消耗。
     *
     * <p><b>分摊方向刻意取"消耗先扣远效期批次"（第七轮审阅 P2-1 修正）</b>：
     * 若按 FEFO（消耗先扣近效期批次）分摊，会把与该批次无关的期初/非 FEFO 消耗算到近效期批次头上，
     * 把仍在架的近效期药估算成 0 在库而**漏报**——这是偏乐观、临床上更危险的方向。
     * 改为从**最晚效期批次**起扣减消耗，让近效期批次的估算在库量偏大，宁可多报不漏报（真正的偏保守）。
     * 仍供药师人工复核，不作精确批次结论；根治需批次级出库记录。
     */
    public List<ExpiryWarning> expiryWarnings(int days) {
        LocalDate today = BusinessDates.today();
        LocalDate threshold = today.plusDays(days);
        // 候选：效期 <= 阈值 的已验收批次，取其涉及的药品集合
        List<InvStockIn> candidates = stockInRepository.findAcceptedNearExpiry(threshold);
        List<Long> drugIds = candidates.stream().map(InvStockIn::getDrugId).distinct().toList();

        List<ExpiryWarning> result = new ArrayList<>();
        for (Long drugId : drugIds) {
            List<InvStockIn> batches = stockInRepository.findAcceptedBatchesByDrugFefo(drugId);   // 效期升序
            long netConsumed = Math.max(0, -transactionRepository.sumOutReturnQty(drugId));
            String name = drugRepository.findById(drugId).map(DrugItem::getName).orElse("");
            // 消耗从最晚效期批次起扣（batches 是效期升序，故逆序遍历分摊）——
            // 使近效期批次尽量不被"消耗光"，估算偏保守（宁可多报，防漏报近效期药）
            long remainingConsume = netConsumed;
            var estRemainingById = new java.util.HashMap<Long, Integer>();
            for (int i = batches.size() - 1; i >= 0; i--) {
                InvStockIn b = batches.get(i);
                long alloc = Math.min(b.getQty(), remainingConsume);
                estRemainingById.put(b.getId(), (int) (b.getQty() - alloc));
                remainingConsume -= alloc;
            }
            for (InvStockIn b : batches) {
                int batchQty = b.getQty();
                int estRemaining = estRemainingById.get(b.getId());
                if (b.getExpireDate() == null || b.getExpireDate().isAfter(threshold)) continue;   // 只报阈值内批次
                if (estRemaining <= 0) continue;
                long d = ChronoUnit.DAYS.between(today, b.getExpireDate());
                String status = b.getExpireDate().isBefore(today) ? "EXPIRED" : "NEAR_EXPIRY";
                result.add(new ExpiryWarning(drugId, name, b.getId(), b.getBatchNo(),
                        b.getExpireDate(), d, batchQty, estRemaining, status));
            }
        }
        result.sort((a, b) -> a.expireDate().compareTo(b.expireDate()));
        return result;
    }

    // ================= 内部工具 =================

    /** 入库单号取序列：nanoTime%1e6 会碰撞唯一约束（裸 500） */
    private long nextInSeq() {
        return ((Number) entityManager.createNativeQuery("select nextval('inv_stock_in_seq')")
                .unwrap(org.hibernate.query.NativeQuery.class)
                .addSynchronizedQuerySpace("")   // 取号不触发全会话 flush（1.1.7 B-8）
                .getSingleResult()).longValue();
    }

    /** 盘点单号取序列（同 nextInSeq 防碰撞/防 flush 思路） */
    private long nextTakeSeq() {
        return ((Number) entityManager.createNativeQuery("select nextval('inv_stock_take_seq')")
                .unwrap(org.hibernate.query.NativeQuery.class)
                .addSynchronizedQuerySpace("")
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
