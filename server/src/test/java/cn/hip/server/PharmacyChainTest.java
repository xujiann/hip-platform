package cn.hip.server;

import cn.hip.platform.masterdata.entity.InvStockIn;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.repository.InvTransactionRepository;
import cn.hip.platform.masterdata.service.ExpiryAlertScheduler;
import cn.hip.platform.masterdata.service.InventoryService;
import cn.hip.platform.masterdata.service.InventoryService.InventoryException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.2.13 车道A 药事全链回归：库存盘点 / 效期预警（估算口径）/ 入库验收。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class PharmacyChainTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired InventoryService inventoryService;
    @Autowired ExpiryAlertScheduler expiryAlertScheduler;
    @Autowired DrugItemRepository drugRepository;
    @Autowired InvTransactionRepository transactionRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private int stockOf(Long drugId) {
        entityManager.clear();
        return drugRepository.findById(drugId).orElseThrow().getStock();
    }

    // ========== ③ 入库验收 ==========

    @Test
    void stockInPendingUntilAccepted() {
        Long drugId = seeds.drug("验收测试药").getId();
        int before = stockOf(drugId);

        InvStockIn in = inventoryService.stockIn(drugId, 30, "BACC", LocalDate.now().plusYears(1),
                "验收供应商", "CG-001", null);
        // 待验收：库存不动、无 IN 流水
        assertEquals("PENDING_ACCEPT", in.getAcceptStatus());
        assertEquals(before, stockOf(drugId), "验收前库存不得变化");

        // 验收通过：+30 且写 IN 流水
        inventoryService.acceptStockIn(in.getId(), null);
        assertEquals(before + 30, stockOf(drugId), "验收后库存 +30");
        var txns = transactionRepository.findTop100ByDrugIdOrderByIdDesc(drugId);
        assertTrue(txns.stream().anyMatch(t -> "IN".equals(t.getType()) && t.getQty() == 30));

        // 重复验收被拒（8011）
        assertEquals(8011, assertThrows(InventoryException.class,
                () -> inventoryService.acceptStockIn(in.getId(), null)).code);
    }

    @Test
    void stockInRejectKeepsStockAndRequiresReason() {
        Long drugId = seeds.drug("拒收测试药").getId();
        int before = stockOf(drugId);
        InvStockIn in = inventoryService.stockIn(drugId, 50, "BREJ", LocalDate.now().plusYears(1), "供", null, null);

        // 拒收原因必填（8012）
        assertEquals(8012, assertThrows(InventoryException.class,
                () -> inventoryService.rejectStockIn(in.getId(), "  ", null)).code);

        InvStockIn rejected = inventoryService.rejectStockIn(in.getId(), "外包装破损", null);
        assertEquals("REJECTED", rejected.getAcceptStatus());
        assertEquals("外包装破损", rejected.getRejectReason());
        assertEquals(before, stockOf(drugId), "拒收不加库存");

        // 拒收后不能再验收（8011）
        assertEquals(8011, assertThrows(InventoryException.class,
                () -> inventoryService.acceptStockIn(in.getId(), null)).code);
    }

    // ========== ① 库存盘点 ==========

    @Test
    void stockTakeConfirmAdjustsStockAndWritesTxn() {
        Long drugId = seeds.drug("盘点测试药").getId();
        int book = stockOf(drugId);

        var view = inventoryService.createStockTake(java.util.List.of(drugId), "月度盘点", null);
        assertEquals("DRAFT", view.status());
        assertEquals(1, view.lineCount());
        assertEquals(book, view.lines().get(0).bookQty());

        // 实盘比账面多 5（盘盈）
        var counted = inventoryService.enterCounts(view.id(),
                java.util.List.of(new InventoryService.CountEntry(drugId, book + 5)));
        assertEquals(1, counted.countedLines());
        assertEquals(5, counted.netDiff());
        assertEquals(1, counted.gainLines());
        assertEquals(Integer.valueOf(5), counted.lines().get(0).diff());

        // 确认调库：库存变为实盘数，且写 STOCKTAKE 流水（delta=+5）
        var confirmed = inventoryService.confirmStockTake(view.id(), null);
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(book + 5, stockOf(drugId), "确认后库存=实盘数");
        var txns = transactionRepository.findTop100ByDrugIdOrderByIdDesc(drugId);
        assertTrue(txns.stream().anyMatch(t -> "STOCKTAKE".equals(t.getType()) && t.getQty() == 5),
                "应写一条 STOCKTAKE 盈亏流水");

        // 已确认不能再录/再确认（8006）
        assertEquals(8006, assertThrows(InventoryException.class,
                () -> inventoryService.confirmStockTake(view.id(), null)).code);
    }

    @Test
    void stockTakeConfirmDetectsBookDrift() {
        Long drugId = seeds.drug("盘点漂移药").getId();
        int book = stockOf(drugId);
        var view = inventoryService.createStockTake(java.util.List.of(drugId), null, null);
        inventoryService.enterCounts(view.id(),
                java.util.List.of(new InventoryService.CountEntry(drugId, book + 2)));

        // 盘点期间账面被并发改动（模拟发药扣减）→ 确认时条件更新影响 0 行 → 8008
        drugRepository.deductStock(drugId, 1);
        entityManager.flush();
        entityManager.clear();
        assertEquals(8008, assertThrows(InventoryException.class,
                () -> inventoryService.confirmStockTake(view.id(), null)).code);
    }

    /**
     * 第七轮审阅 P2-2 回归：账实相符（delta=0）的行也要校验账面未漂移。
     * 原先 delta=0 直接跳过条件更新，会把"窗口内被并发发药改动过"的行记成账实相符。
     */
    @Test
    void stockTakeConfirmZeroDeltaLineStillDetectsDrift() {
        Long drugId = seeds.drug("零差漂移药").getId();
        int book = stockOf(drugId);
        var view = inventoryService.createStockTake(java.util.List.of(drugId), null, null);
        // 实盘=账面（delta 0）
        inventoryService.enterCounts(view.id(),
                java.util.List.of(new InventoryService.CountEntry(drugId, book)));
        // 盘点期间账面漂移
        drugRepository.deductStock(drugId, 1);
        entityManager.flush();
        entityManager.clear();
        assertEquals(8008, assertThrows(InventoryException.class,
                () -> inventoryService.confirmStockTake(view.id(), null)).code,
                "delta=0 行也须检出账面漂移，不能记成账实相符");
    }

    // ========== ② 效期预警（估算口径）==========

    @Test
    void expiryWarningEstimatesRemainingByFefo() {
        Long drugId = seeds.drug("效期测试药" + System.nanoTime()).getId();
        // 一个 30 天后到期的已验收批次，入库量 100
        InvStockIn in = inventoryService.stockIn(drugId, 100, "BEXP", LocalDate.now().plusDays(30), "供", null, null);
        inventoryService.acceptStockIn(in.getId(), null);

        var warns = inventoryService.expiryWarnings(90);
        var mine = warns.stream().filter(w -> w.drugId().equals(drugId)).findFirst().orElseThrow();
        assertEquals("NEAR_EXPIRY", mine.status());
        assertEquals(100, mine.estimatedRemaining(), "无消耗时估算在库=入库量");

        // 发药净出 30（只写 OUT 流水，估算口径按 FEFO 分摊消耗）
        inventoryService.logOut(drugId, 30, "RX-EXP", null);
        var warns2 = inventoryService.expiryWarnings(90);
        var mine2 = warns2.stream().filter(w -> w.drugId().equals(drugId)).findFirst().orElseThrow();
        assertEquals(70, mine2.estimatedRemaining(), "FEFO 扣 30 后估算在库=70");

        // 超出预警窗（120 天后到期）的批次不报
        Long farDrug = seeds.drug("远效期药" + System.nanoTime()).getId();
        InvStockIn far = inventoryService.stockIn(farDrug, 10, "BFAR", LocalDate.now().plusDays(120), "供", null, null);
        inventoryService.acceptStockIn(far.getId(), null);
        assertTrue(inventoryService.expiryWarnings(90).stream().noneMatch(w -> w.drugId().equals(farDrug)),
                "120 天后到期不应进 90 天预警");
    }

    @Test
    void expiryScanOpensFaultTicket() {
        Long drugId = seeds.drug("效期开单药" + System.nanoTime()).getId();
        InvStockIn in = inventoryService.stockIn(drugId, 40, "BTICK", LocalDate.now().plusDays(10), "供", null, null);
        inventoryService.acceptStockIn(in.getId(), null);

        int opened = expiryAlertScheduler.runScan();
        assertTrue(opened >= 1, "近效期批次应至少开一张提醒单");
        Integer tickets = jdbc.queryForObject(
                "select count(*) from ops_fault_ticket where title like '[效期预警]%' and status = 'OPEN'",
                Integer.class);
        assertNotNull(tickets);
        assertTrue(tickets >= 1);
    }
}
