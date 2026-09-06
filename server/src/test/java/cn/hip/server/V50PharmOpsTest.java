package cn.hip.server;

import cn.hip.outpatient.service.PharmOpsService;
import cn.hip.outpatient.service.PharmOpsService.PharmOpsException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v50 车道 D 回归：拆零发药 / 近效期预警 / 拆零余量盘点。
 *
 * <p>编排分三段，第 ① 段刻意排在最前——它锁的是<b>「本车道没有伪造任何数据」</b>：
 * 迁移只加列不填值、未维护换算系数的药一律拒发。这两条一旦变红，说明有人给 pack_size 加了
 * default 或按 spec 文本猜了系数，那正是把「1 盒」当「1 片」发出去的根因。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V50PharmOpsTest {

    private static final String GATE = PharmOpsService.GATE_EXPIRY;

    @Autowired PharmOpsService pharmOps;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired cn.hip.server.support.TestSeeds seeds;

    /** ConfigReader 是 30 秒进程内缓存，事务回滚清不掉——必须显式失效，否则串味到别的用例 */
    @AfterEach
    void evict() {
        configReader.evictAll();
    }

    // ================= 工具 =================

    /** 建一味测试药并维护换算系数与整包库存 */
    private long drug(String name, Integer packSize, String minUnit, int packStock) {
        long id = seeds.drug(name).getId();
        jdbc.update("update md_drug set pack_size = ?, min_unit = ?, stock = ? where id = ?",
                packSize, minUnit, packStock, id);
        return id;
    }

    private int packStock(long drugId) {
        Integer n = jdbc.queryForObject("select stock from md_drug where id = ?", Integer.class, drugId);
        return n == null ? 0 : n;
    }

    private void gate(String v) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", v, GATE);
        configReader.evictAll();
    }

    private void cfg(String key, String v) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", v, key);
        configReader.evictAll();
    }

    /** 直插一条已验收批次（不走 InventoryService.acceptStockIn，避免顺带改库存干扰断言） */
    private void acceptedBatch(long drugId, String batchNo, java.time.LocalDate expire, int qty) {
        jdbc.update("""
                insert into inv_stock_in (in_no, drug_id, qty, batch_no, expire_date, supplier, accept_status, created_at)
                values (?, ?, ?, ?, ?, '测试供应商', 'ACCEPTED', ?)
                """, "T" + System.nanoTime(), drugId, qty, batchNo, expire,
                Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private int splitCode(Runnable r) {
        return assertThrows(PharmOpsException.class, r::run).code;
    }

    /** 盘点行入参（显式 Map&lt;String,Object&gt;：Map.of 会把 Long+Integer 推成 Map&lt;String,Number&gt;） */
    private static List<Map<String, Object>> entry(long drugId, Integer actualQty) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("drugId", drugId);
        m.put("actualQty", actualQty);
        return List.of(m);
    }

    // =====================================================================================
    // ① 没有伪造数据：迁移零 update + 未维护系数一律拒发
    // =====================================================================================

    /**
     * V150 只加列不填值：存量药品的 pack_size / min_unit <b>必须全为 NULL</b>。
     * 任何一条非空都说明有人在迁移里回填了——而回填的来源只可能是猜（spec 文本或默认 1）。
     */
    @Test
    void migrationBackfilledNothing() {
        Integer filled = jdbc.queryForObject(
                "select count(*) from md_drug where pack_size is not null or min_unit is not null", Integer.class);
        assertEquals(0, filled,
                "V150 不得回填 pack_size/min_unit：换算系数只能人工维护，按 spec 文本或默认 1 推断即是假数据");
    }

    /** 未维护换算系数 → 5460 硬拒，**不是**默认按 1 发出去 */
    @Test
    void refusesSplitWhenPackSizeNotMaintained() {
        long id = drug("拆零未维护药", null, null, 100);
        assertEquals(5460, splitCode(() -> pharmOps.dispenseSplit(id, 10, null, null, null)));

        var info = pharmOps.splitInfo(id);
        assertEquals(Boolean.FALSE, info.get("splittable"));
        assertNotNull(info.get("reason"));
    }

    /** 只维护了一半（有系数没最小单位）同样拒 5460——两列必须成对维护 */
    @Test
    void refusesSplitWhenMinUnitMissing() {
        long id = drug("拆零半维护药", 24, null, 100);
        assertEquals(5460, splitCode(() -> pharmOps.dispenseSplit(id, 10, null, null, null)));
    }

    /** 系数填 1（维护时图省事的典型）→ 5461，与不维护同样危险，不放行 */
    @Test
    void refusesSplitWhenPackSizeIsOne() {
        long id = drug("拆零系数一药", 1, "片", 100);
        assertEquals(5461, splitCode(() -> pharmOps.dispenseSplit(id, 10, null, null, null)));
    }

    @Test
    void refusesIllegalQty() {
        long id = drug("拆零数量校验药", 24, "片", 100);
        assertEquals(5462, splitCode(() -> pharmOps.dispenseSplit(id, 0, null, null, null)));
        assertEquals(5462, splitCode(() -> pharmOps.dispenseSplit(id, -3, null, null, null)));
        assertEquals(5462, splitCode(() -> pharmOps.dispenseSplit(id, null, null, null, null)));
        assertEquals(5462, splitCode(() -> pharmOps.dispenseSplit(id, 1_000_000, null, null, null)));
    }

    @Test
    void refusesDisabledOrMissingDrug() {
        long id = drug("拆零停用药", 24, "片", 100);
        jdbc.update("update md_drug set enabled = false where id = ?", id);
        assertEquals(5463, splitCode(() -> pharmOps.dispenseSplit(id, 5, null, null, null)));
        assertEquals(5463, splitCode(() -> pharmOps.dispenseSplit(-999L, 5, null, null, null)));
    }

    // =====================================================================================
    // ② 拆零记账：开一盒、余量留池、单位不混
    // =====================================================================================

    @Test
    @SuppressWarnings("unchecked")
    void splitOpensOnePackAndKeepsRemainder() {
        long id = drug("拆零记账药", 24, "片", 10);
        var out = pharmOps.dispenseSplit(id, 10, null, null, null);

        assertEquals(1, out.get("packsOpened"));
        assertEquals(14, out.get("remainQty"), "24 片装开一盒发 10 片，池里应剩 14 片");
        assertEquals(9, packStock(id), "整包库存扣 1 盒");
        assertEquals(14, pharmOps.remainQty(id));

        // inv_transaction 里那一条必须是**盒**（-1），不是片：混单位会让 sumOutReturnQty 的效期估算立刻算错
        var txn = jdbc.queryForList("""
                select type, qty, stock_after, ref_no from inv_transaction
                 where drug_id = ? order by id desc limit 1
                """, id);
        assertEquals("OUT", txn.get(0).get("type"));
        assertEquals(-1, ((Number) txn.get(0).get("qty")).intValue(), "整包出库记的是盒数");
        assertEquals(9, ((Number) txn.get(0).get("stock_after")).intValue());
        assertEquals(out.get("dispenseNo"), txn.get(0).get("ref_no"), "两张表靠拆零单号勾稽");

        // pharm_split_txn 里是**片**：OPEN +24 后 OUT -10
        var st = pharmOps.splitTxns(id, 10);
        assertEquals("OUT", st.get(0).get("type"));
        assertEquals(-10, ((Number) st.get(0).get("qty")).intValue());
        assertEquals(14, ((Number) st.get(0).get("remain_after")).intValue());
        assertEquals("OPEN", st.get(1).get("type"));
        assertEquals(24, ((Number) st.get(1).get("qty")).intValue());
        assertEquals(24, ((Number) st.get(1).get("pack_size")).intValue(), "流水快照当次系数，日后改规格仍可还原");
    }

    /** 第二次拆零从池里出，**不再开盒**——没有余量池就会每次扣一盒，账面凭空少药 */
    @Test
    void secondSplitConsumesPoolWithoutOpeningNewPack() {
        long id = drug("拆零复用池药", 24, "片", 10);
        pharmOps.dispenseSplit(id, 10, null, null, null);
        var out = pharmOps.dispenseSplit(id, 4, null, null, null);

        assertEquals(0, out.get("packsOpened"), "池里还有 14 片，不该再开盒");
        assertEquals(10, out.get("remainQty"));
        assertEquals(9, packStock(id), "整包库存仍只扣了最初那 1 盒");
    }

    /** 一次要的量跨多盒：缺口向上取整开盒 */
    @Test
    void splitAcrossMultiplePacks() {
        long id = drug("拆零跨盒药", 24, "片", 10);
        pharmOps.dispenseSplit(id, 20, null, null, null);   // 开 1 盒，余 4
        assertEquals(4, pharmOps.remainQty(id));

        var out = pharmOps.dispenseSplit(id, 50, null, null, null);   // 缺口 46 → 开 2 盒
        assertEquals(2, out.get("packsOpened"));
        assertEquals(4 + 48 - 50, out.get("remainQty"));
        assertEquals(7, packStock(id));
    }

    /** 整包库存不够开盒 → 5464，且**一行都不留**（事务整体回滚） */
    @Test
    void refusesWhenPackStockInsufficient() {
        long id = drug("拆零库存不足药", 24, "片", 1);
        assertEquals(5464, splitCode(() -> pharmOps.dispenseSplit(id, 30, null, null, null)));
        assertEquals(1, packStock(id), "拒发后整包库存不得变化");
        assertEquals(0, pharmOps.remainQty(id), "拒发后余量池不得留下开包痕迹");
        assertTrue(pharmOps.splitDispenses(null, id).isEmpty());
    }

    // =====================================================================================
    // ③ 拆零退回：只回余量池，不折算回整包
    // =====================================================================================

    @Test
    void returnGoesBackToPoolNotToPackStock() {
        long id = drug("拆零退回药", 24, "片", 10);
        var out = pharmOps.dispenseSplit(id, 10, null, null, null);
        Long dispenseId = (Long) out.get("dispenseId");

        var r1 = pharmOps.returnSplit(dispenseId, 4, null);
        assertEquals("PART_RETURNED", r1.get("status"));
        assertEquals(18, r1.get("remainQty"));
        assertEquals(9, packStock(id), "拆开的盒回不去，整包库存不得被折算回补");

        var r2 = pharmOps.returnSplit(dispenseId, 6, null);
        assertEquals("RETURNED", r2.get("status"));
        assertEquals(24, r2.get("remainQty"));

        // 全退后不能再退
        assertEquals(5467, splitCode(() -> pharmOps.returnSplit(dispenseId, 1, null)));
    }

    @Test
    void returnValidates() {
        long id = drug("拆零退回校验药", 24, "片", 10);
        var out = pharmOps.dispenseSplit(id, 10, null, null, null);
        Long dispenseId = (Long) out.get("dispenseId");

        assertEquals(5466, splitCode(() -> pharmOps.returnSplit(-999L, 1, null)));
        assertEquals(5468, splitCode(() -> pharmOps.returnSplit(dispenseId, 11, null)), "退回数不得超过本次已发");
        assertEquals(5468, splitCode(() -> pharmOps.returnSplit(dispenseId, 0, null)));
        assertEquals(5468, splitCode(() -> pharmOps.returnSplit(dispenseId, null, null)));
    }

    // =====================================================================================
    // ④ 效期：gate 三态 + 天数配置回落
    // =====================================================================================

    /** 默认 warn 必须**真的放行**（不是偷偷 block 再假装成功），只是回带 warnings 并留痕 */
    @Test
    @SuppressWarnings("unchecked")
    void expiredBatchWarnsButStillDispensesUnderDefaultGate() {
        long id = drug("过期批次警告药", 24, "片", 10);
        acceptedBatch(id, "EXP-001", BusinessDates.today().minusDays(30), 100);
        gate("warn");

        var out = pharmOps.dispenseSplit(id, 10, null, null, null);
        assertEquals("warn", out.get("expiryGate"));
        var warnings = (List<String>) out.get("warnings");
        assertFalse(warnings.isEmpty(), "warn 档必须把过期批次回带给药师");
        assertTrue(warnings.get(0).contains("EXP-001"));
        assertEquals(9, packStock(id), "warn 档照常发药");

        Boolean warned = jdbc.queryForObject(
                "select expiry_warned from pharm_split_dispense where id = ?", Boolean.class,
                ((Number) out.get("dispenseId")).longValue());
        assertEquals(Boolean.TRUE, warned, "带警发出必须留痕，否则事后统计不出「带警发出」比例");
    }

    @Test
    void expiredBatchBlocksWhenGateIsBlock() {
        long id = drug("过期批次拦截药", 24, "片", 10);
        acceptedBatch(id, "EXP-002", BusinessDates.today().minusDays(1), 100);
        gate("block");

        assertEquals(5481, splitCode(() -> pharmOps.dispenseSplit(id, 10, null, null, null)));
        assertEquals(10, packStock(id), "block 档拒发时不得留下任何半条记录");
        assertEquals(0, pharmOps.remainQty(id));
    }

    @Test
    void gateOffBypassesEntirely() {
        long id = drug("过期批次旁路药", 24, "片", 10);
        acceptedBatch(id, "EXP-003", BusinessDates.today().minusDays(1), 100);
        gate("off");

        var out = pharmOps.dispenseSplit(id, 10, null, null, null);
        assertEquals("off", out.get("expiryGate"));
        assertTrue(((List<?>) out.get("warnings")).isEmpty());
    }

    /** 坏配置回落 warn 而不是 off——宁可多提示，不可静默失效 */
    @Test
    void badGateValueFallsBackToWarnNotOff() {
        gate("BLOCKK");
        assertEquals("warn", pharmOps.gate());
        gate("");
        assertEquals("warn", pharmOps.gate());
    }

    /** 未过期的近效期批次不进发药拦截——「还有 80 天到期」也拦，warn 会刷屏、block 会停摆 */
    @Test
    void nearButNotYetExpiredDoesNotBlockDispensing() {
        long id = drug("近效期未过期药", 24, "片", 10);
        acceptedBatch(id, "NEAR-001", BusinessDates.today().plusDays(30), 100);
        gate("block");

        var out = pharmOps.dispenseSplit(id, 10, null, null, null);
        assertTrue(((List<?>) out.get("warnings")).isEmpty());
    }

    /** 效期未填的批次判不了，不得因此拦发药 */
    @Test
    void batchWithoutExpireDateDoesNotBlock() {
        long id = drug("效期未填药", 24, "片", 10);
        acceptedBatch(id, "NOEXP-001", null, 100);
        gate("block");
        assertDoesNotThrow(() -> pharmOps.dispenseSplit(id, 10, null, null, null));
    }

    /**
     * 天数配置：V150 把 pharm.expiry.warn_days seed 成<b>空串</b>，
     * 默认必须跟随药库巡检键 inv_expiry_warn_days ——同一概念只能有一处真相。
     */
    @Test
    void warnDaysFollowsInventoryKeyUntilExplicitlyOverridden() {
        var d0 = pharmOps.resolveWarnDays();
        assertEquals(PharmOpsService.KEY_INV_WARN_DAYS, d0.source(),
                "留空时必须回落到 inv_expiry_warn_days，而不是另起一个 90");

        cfg(PharmOpsService.KEY_INV_WARN_DAYS, "45");
        assertEquals(45, pharmOps.resolveWarnDays().days());

        cfg(PharmOpsService.KEY_WARN_DAYS, "7");
        var d2 = pharmOps.resolveWarnDays();
        assertEquals(7, d2.days());
        assertEquals(PharmOpsService.KEY_WARN_DAYS, d2.source());

        // 坏值回落，且把回落原因说出来，而不是静默用 90
        cfg(PharmOpsService.KEY_WARN_DAYS, "九十");
        var d3 = pharmOps.resolveWarnDays();
        assertEquals(45, d3.days());
        assertFalse(d3.caveats().isEmpty());
    }

    @Test
    void expiryWarningsRejectsIllegalDaysParam() {
        assertEquals(5480, splitCode(() -> pharmOps.expiryWarnings(0)));
        assertEquals(5480, splitCode(() -> pharmOps.expiryWarnings(4000)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiryWarningListCarriesCaveatsAndDelegatesToInventoryService() {
        long id = drug("效期列表药", 24, "片", 10);
        acceptedBatch(id, "LIST-001", BusinessDates.today().plusDays(5), 100);

        var out = pharmOps.expiryWarnings(30);
        var rows = (List<Map<String, Object>>) out.get("rows");
        assertTrue(rows.stream().anyMatch(r -> "LIST-001".equals(r.get("batchNo"))));
        assertFalse(((List<String>) out.get("caveats")).isEmpty(),
                "在库量是估算值，口径必须随返回体下发——只写在页面上，一导出就没了");
    }

    /** 独立效期校验端点：给发药主链路（Lane A/B）用，不依赖拆零 */
    @Test
    void standaloneExpiryCheckWorksForMainDispensePath() {
        long id = drug("效期独立校验药", null, null, 10);   // 刻意不维护拆零系数：这个端点与拆零无关
        acceptedBatch(id, "CHK-001", BusinessDates.today().minusDays(2), 50);
        gate("block");

        var m = pharmOps.expiryCheck(id);
        assertEquals(Boolean.TRUE, m.get("blocked"));
        assertEquals(5463, splitCode(() -> pharmOps.expiryCheck(-999L)),
                "药品不存在必须报错，不能给调用方一个「无过期批次」的假放行");
    }

    // =====================================================================================
    // ⑤ 拆零余量盘点
    // =====================================================================================

    @Test
    @SuppressWarnings("unchecked")
    void splitStockTakeAdjustsRemainAndWritesLedger() {
        long id = drug("拆零盘点药", 24, "片", 10);
        pharmOps.dispenseSplit(id, 10, null, null, null);   // 池里 14 片
        assertEquals(14, pharmOps.remainQty(id));

        var take = pharmOps.createSplitTake(List.of(id), "月末拆零盘点", null);
        Long takeId = ((Number) take.get("id")).longValue();
        assertEquals("SPLIT_REMAIN_ONLY", take.get("scope"), "范围必须自报：整包盘点走 V81，两者不重叠");
        var lines = (List<Map<String, Object>>) take.get("lines");
        assertEquals(14, ((Number) lines.get(0).get("book_qty")).intValue(), "账面数在建单时快照");

        // 实盘 12（盘亏 2）
        var entered = pharmOps.enterSplitCounts(takeId,
                entry(id, 12), null);
        assertEquals(-2, ((Number) entered.get("netDiff")).intValue());
        assertEquals(14, pharmOps.remainQty(id), "录实盘数不动余量，确认才动");

        var confirmed = pharmOps.confirmSplitTake(takeId, null);
        assertEquals("CONFIRMED", confirmed.get("status"));
        assertNotNull(confirmed.get("confirmed_at"));
        assertEquals(12, pharmOps.remainQty(id));

        // 差异调整必须留流水，否则账实不符时无从追溯
        var txn = pharmOps.splitTxns(id, 5).get(0);
        assertEquals("TAKEADJ", txn.get("type"));
        assertEquals(-2, ((Number) txn.get("qty")).intValue());
        assertEquals(12, ((Number) txn.get("remain_after")).intValue());
        assertEquals(take.get("take_no"), txn.get("ref_no"));
    }

    /** 从未拆过的药也能盘：账面 0、实盘 5 即盘盈——这正是拆零最常见的账实不符形态 */
    @Test
    void stockTakeAllowsGainOnDrugNeverSplit() {
        long id = drug("拆零盘盈药", 24, "片", 10);
        var take = pharmOps.createSplitTake(List.of(id), null, null);
        Long takeId = ((Number) take.get("id")).longValue();
        pharmOps.enterSplitCounts(takeId, entry(id, 5), null);
        pharmOps.confirmSplitTake(takeId, null);
        assertEquals(5, pharmOps.remainQty(id));
    }

    /** 盘点期间余量被并发拆零改动 → 5504 整单回滚，迫使重新盘点 */
    @Test
    void concurrentSplitDuringStockTakeForcesRecount() {
        long id = drug("拆零盘点漂移药", 24, "片", 10);
        pharmOps.dispenseSplit(id, 10, null, null, null);   // 池里 14
        var take = pharmOps.createSplitTake(List.of(id), null, null);
        Long takeId = ((Number) take.get("id")).longValue();
        pharmOps.enterSplitCounts(takeId, entry(id, 13), null);

        // 盘点窗口内又发出去 4 片：账面从 14 变 10
        pharmOps.dispenseSplit(id, 4, null, null, null);

        assertEquals(5504, splitCode(() -> pharmOps.confirmSplitTake(takeId, null)));
    }

    /**
     * 差异为 0 的行也要校验账面未漂移（V81 第七轮审阅 P2-2 的教训，本车道照抄）：
     * 建单快照 14，期间发出 4 片变 10，实盘也录 14 —— 若直接跳过零差异行，这条会被记成「账实相符」。
     */
    @Test
    void zeroDiffLineStillChecksDrift() {
        long id = drug("拆零零差异漂移药", 24, "片", 10);
        pharmOps.dispenseSplit(id, 10, null, null, null);   // 14
        var take = pharmOps.createSplitTake(List.of(id), null, null);
        Long takeId = ((Number) take.get("id")).longValue();
        pharmOps.enterSplitCounts(takeId, entry(id, 14), null);
        pharmOps.dispenseSplit(id, 4, null, null, null);    // 账面漂到 10

        assertEquals(5504, splitCode(() -> pharmOps.confirmSplitTake(takeId, null)));
    }

    @Test
    void stockTakeStateAndInputValidation() {
        long id = drug("拆零盘点校验药", 24, "片", 10);
        assertEquals(5503, splitCode(() -> pharmOps.createSplitTake(List.of(), null, null)));
        assertEquals(5503, splitCode(() -> pharmOps.createSplitTake(null, null, null)));
        assertEquals(5500, splitCode(() -> pharmOps.splitTakeView(-999L)));
        assertEquals(5500, splitCode(() -> pharmOps.confirmSplitTake(-999L, null)));

        var take = pharmOps.createSplitTake(List.of(id), null, null);
        Long takeId = ((Number) take.get("id")).longValue();

        assertEquals(5502, splitCode(() ->
                pharmOps.enterSplitCounts(takeId, entry(id, -1), null)));
        // 未录任何实盘数不得确认——否则等于用空盘点把余量清成账面
        assertEquals(5505, splitCode(() -> pharmOps.confirmSplitTake(takeId, null)));

        // 作废另起一张单：上一句的 5505 是在「已抢占 DRAFT→CONFIRMED」之后抛的，
        // 生产上它会让整个 confirm 事务回滚、单子仍是 DRAFT；但在 @Transactional 的测试里
        // 内层事务是**参与**外层而非独立事务，抛异常不会真的回滚那次抢占。
        // 这里不去断言那个差异（它是测试基架的语义，不是本车道的实现），换一张干净的单验状态机。
        var take2 = pharmOps.createSplitTake(List.of(id), null, null);
        Long takeId2 = ((Number) take2.get("id")).longValue();
        pharmOps.cancelSplitTake(takeId2);
        assertEquals("CANCELLED", pharmOps.splitTakeView(takeId2).get("status"));
        assertEquals(5501, splitCode(() -> pharmOps.enterSplitCounts(takeId2, List.of(), null)));
        assertEquals(5501, splitCode(() -> pharmOps.confirmSplitTake(takeId2, null)));
        assertEquals(5501, splitCode(() -> pharmOps.cancelSplitTake(takeId2)));
    }

    /** 已确认的单不得再录实盘、再确认、再作废——否则同一张盘点单能把余量调两次 */
    @Test
    void confirmedStockTakeIsClosed() {
        long id = drug("拆零盘点状态机药", 24, "片", 10);
        pharmOps.dispenseSplit(id, 10, null, null, null);
        var take = pharmOps.createSplitTake(List.of(id), null, null);
        Long takeId = ((Number) take.get("id")).longValue();
        pharmOps.enterSplitCounts(takeId, entry(id, 12), null);
        pharmOps.confirmSplitTake(takeId, null);

        assertEquals(12, pharmOps.remainQty(id));
        assertEquals(5501, splitCode(() -> pharmOps.enterSplitCounts(takeId, entry(id, 99), null)));
        assertEquals(5501, splitCode(() -> pharmOps.confirmSplitTake(takeId, null)));
        assertEquals(5501, splitCode(() -> pharmOps.cancelSplitTake(takeId)));
        assertEquals(12, pharmOps.remainQty(id), "重复确认不得二次调整余量");
    }

    /** 停用药的散装余量照样在架上、照样要盘——把停用药排除在外正好漏掉最容易积压的那批 */
    @Test
    void stockTakeCoversDisabledDrugs() {
        long id = drug("拆零停用盘点药", 24, "片", 10);
        pharmOps.dispenseSplit(id, 10, null, null, null);
        jdbc.update("update md_drug set enabled = false where id = ?", id);
        assertDoesNotThrow(() -> pharmOps.createSplitTake(List.of(id), null, null));
    }

    // =====================================================================================
    // ⑥ 既有发药链路未被本车道改坏
    // =====================================================================================

    /**
     * 既有整单发药走的是 md_drug.stock，与拆零余量池<b>互不干扰</b>：
     * 池里有 14 片不会让整包发药少扣，整包发药也不会动池子。
     */
    @Test
    void legacyPackDispenseIsUnaffectedBySplitPool() {
        long id = drug("拆零与整包并存药", 24, "片", 10);
        pharmOps.dispenseSplit(id, 10, null, null, null);
        int stockBefore = packStock(id);
        int poolBefore = pharmOps.remainQty(id);

        // 模拟既有 DispenseService.dispense 的那一句原子扣减（逐字同 SQL 语义）
        int n = jdbc.update("update md_drug set stock = stock - ? where id = ? and stock >= ?", 2, id, 2);
        assertEquals(1, n);
        assertEquals(stockBefore - 2, packStock(id));
        assertEquals(poolBefore, pharmOps.remainQty(id), "整包发药不得动散装余量池");
    }
}
