package cn.hip.server;

import cn.hip.outpatient.service.PharmStockService;
import cn.hip.outpatient.service.PharmStockService.PickResult;
import cn.hip.outpatient.service.PharmStockService.ScrapResult;
import cn.hip.outpatient.service.PharmStockService.StockInResult;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v50 车道 A 回归：库存地基（批次 / 效期 / 批次级出入库流水）。
 *
 * <p>本类的核心断言不是「端点返回 200」，而是三条**不变量**：
 * <ol>
 *   <li><b>余额可由流水推出</b>：{@code pharm_stock.qty == sum(flow.qty_delta)}，由
 *       {@link PharmStockService#reconcile} 逐行验；
 *   <li><b>子账与总账同增同减</b>：入库与报损两条路径都双写，md_drug.stock 跟着动；
 *   <li><b>gate 默认 warn 时不拦截、不返错误码</b>，问题以 warnings 回带。
 * </ol>
 *
 * <p>**时区**：全部日期取 {@link BusinessDates#today()}，无任何裸 {@code LocalDate.now()}
 * 与硬编码日期字面量。本仓已因时区/精度炸过四次，测试须在
 * {@code -DargLine="-Duser.timezone=UTC"} 下同样为绿。
 *
 * <p>**配置隔离**：改 sys_config 的用例在 {@link #restoreConfig()} 里逐键还原并
 * {@code evictAll()}——ConfigReader 有 30 秒缓存，不清会让后一个用例读到前一个用例的档位，
 * 表现为随机失败且只在特定执行顺序下复现。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V50PharmStockTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired PharmStockService pharmStockService;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private static final String LOC = "OUTP_PHARM";

    /** md_drug.stock 经 JPA 条件更新改动，JdbcTemplate/JPA 混用时须先清一级缓存再读 */
    private int stockOf(Long drugId) {
        entityManager.flush();
        entityManager.clear();
        return drugRepository.findById(drugId).orElseThrow().getStock();
    }

    private void setCfg(String key, String value) {
        jdbc.update("insert into sys_config(cfg_key, cfg_value) values (?, ?) "
                + "on conflict (cfg_key) do update set cfg_value = excluded.cfg_value", key, value);
        configReader.evictAll();
    }

    @AfterEach
    void restoreConfig() {
        // @Transactional 会回滚 sys_config 的写，但 ConfigReader 的 30 秒内存缓存不回滚
        configReader.evictAll();
    }

    // ================= ① 入库：双写 + 两本账勾稽 =================

    @Test
    void stockInPostsToBothLedgersAndLinksThem() {
        Long drugId = seeds.drug("批次入库测试药").getId();
        int aggBefore = stockOf(drugId);
        LocalDate expire = BusinessDates.today().plusYears(2);

        StockInResult r = pharmStockService.stockIn(drugId, 120, "B-2601", expire.minusYears(2),
                expire, "某某医药", new BigDecimal("3.2500"), "CG-50-1", LOC, null);

        // 子账：批次余额 = 120
        assertEquals(120, r.batchQtyAfter());
        assertNotNull(r.batchId());
        // 总账：md_drug.stock 同步 +120（委托既有 InventoryService 那段原子更新完成）
        assertEquals(aggBefore + 120, stockOf(drugId), "入库须同时进总账 md_drug.stock");
        // 两本账勾稽：批次流水带得回旧口径入库单
        assertNotNull(r.stockInId(), "须回带 inv_stock_in 勾稽键");
        assertNotNull(r.stockInNo());
        Long linked = jdbc.queryForObject(
                "select src_stock_in_id from pharm_stock_flow where flow_no = ?", Long.class, r.flowNo());
        assertEquals(r.stockInId(), linked, "IN 流水的 src_stock_in_id 须指向旧口径入库单");
        // 旧口径那条链路也确实走完了：入库单已验收 + inv_transaction 有 IN 流水
        assertEquals("ACCEPTED", jdbc.queryForObject(
                "select accept_status from inv_stock_in where id = ?", String.class, r.stockInId()));
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from inv_transaction where drug_id = ? and type = 'IN' and qty = 120",
                Integer.class, drugId));
        // 进价与生产日期落在批次主数据上（这两列 inv_stock_in 至今没有）
        assertEquals(0, new BigDecimal("3.2500").compareTo(jdbc.queryForObject(
                "select purchase_price from pharm_batch where id = ?", BigDecimal.class, r.batchId())));
    }

    /** 同一药品同一批号二次入库累加到同一批次，不新建批次行 */
    @Test
    void secondStockInSameBatchAccumulates() {
        Long drugId = seeds.drug("同批号累加药").getId();
        LocalDate expire = BusinessDates.today().plusYears(1);
        StockInResult a = pharmStockService.stockIn(drugId, 50, "SAME-1", null, expire, "供", null, null, LOC, null);
        StockInResult b = pharmStockService.stockIn(drugId, 30, "SAME-1", null, expire, "供", null, null, LOC, null);

        assertEquals(a.batchId(), b.batchId(), "同药同批号须复用同一批次");
        assertEquals(80, b.batchQtyAfter());
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from pharm_batch where drug_id = ? and batch_no = 'SAME-1'", Integer.class, drugId));
        assertEquals(2, (int) jdbc.queryForObject(
                "select count(*) from pharm_stock_flow where batch_id = ? and flow_type = 'IN'",
                Integer.class, a.batchId()));
    }

    /**
     * 同批号不同效期直接顶回去（5406），不悄悄再建一个批次。
     * 「同一批号两个效期」在召回时会让人下架错货。
     */
    @Test
    void sameBatchNoWithDifferentExpiryRejected() {
        Long drugId = seeds.drug("批号冲突药").getId();
        LocalDate e1 = BusinessDates.today().plusYears(1);
        pharmStockService.stockIn(drugId, 10, "CONF-1", null, e1, null, null, null, LOC, null);

        HipBizException ex = assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, 10, "CONF-1", null, e1.plusMonths(3), null, null, null, LOC, null));
        assertEquals(PharmStockService.E_BATCH_CONF, ex.code);
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from pharm_batch where drug_id = ? and batch_no = 'CONF-1'", Integer.class, drugId));
    }

    /** 生产日期晚于有效期至是「录反了」，恒定校验、与 gate 无关 */
    @Test
    void producedAfterExpiryAlwaysRejected() {
        Long drugId = seeds.drug("日期录反药").getId();
        LocalDate today = BusinessDates.today();
        setCfg(PharmStockService.CFG_GATE_BATCH, "off");
        setCfg(PharmStockService.CFG_GATE_EXPIRY, "off");
        HipBizException ex = assertThrows(HipBizException.class, () -> pharmStockService.stockIn(
                drugId, 5, "REV-1", today.plusYears(2), today.plusYears(1), null, null, null, LOC, null));
        assertEquals(PharmStockService.E_DATE_ORDER, ex.code, "gate 全 off 也必须拦");
    }

    // ================= ② gate 三态 =================

    /** 默认 warn：无批号无效期照常入账，**不返错误码**，问题以 warnings 回带 */
    @Test
    void batchGateWarnPassesAndReturnsWarnings() {
        Long drugId = seeds.drug("无批号入库药").getId();
        int aggBefore = stockOf(drugId);
        assertEquals("warn", pharmStockService.gate(PharmStockService.CFG_GATE_BATCH), "默认档须为 warn");

        StockInResult r = pharmStockService.stockIn(drugId, 20, null, null, null, null, null, null, LOC, null);

        assertEquals(20, r.batchQtyAfter(), "warn 档须照常落库");
        assertEquals(aggBefore + 20, stockOf(drugId));
        assertEquals(2, r.warnings().size(), "无批号 + 无效期两条 warning");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("批号")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("有效期")));
        // 哨兵批号明写自己不是批号，且效期为空
        assertTrue(r.batchNo() == null);
        String stored = jdbc.queryForObject("select batch_no from pharm_batch where id = ?", String.class, r.batchId());
        assertTrue(stored.startsWith("NOBATCH-"), "无批号入库落哨兵批号而非编造一个像批号的串");
    }

    /** block 档才真的返 5402/5403 */
    @Test
    void batchGateBlockRejects() {
        Long drugId = seeds.drug("批号block药").getId();
        setCfg(PharmStockService.CFG_GATE_BATCH, "block");
        assertEquals(PharmStockService.E_BATCH_NO, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, 5, null, null,
                        BusinessDates.today().plusYears(1), null, null, null, LOC, null)).code);
        assertEquals(PharmStockService.E_EXPIRE_REQ, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, 5, "HAS-NO", null, null, null, null, null, LOC, null)).code);
    }

    /** 效期 gate：warn 放行已过期入库并回带 warning；block 才拦 5404 */
    @Test
    void expiryGateWarnThenBlock() {
        Long drugId = seeds.drug("过期入库药").getId();
        LocalDate expired = BusinessDates.today().minusDays(1);

        StockInResult r = pharmStockService.stockIn(drugId, 7, "EXP-1", null, expired, null, null, null, LOC, null);
        assertEquals(7, r.batchQtyAfter(), "warn 档已过期批次仍放行");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("已过期")));

        setCfg(PharmStockService.CFG_GATE_EXPIRY, "block");
        assertEquals(PharmStockService.E_EXPIRE_BAD, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, 7, "EXP-2", null, expired, null, null, null, LOC, null)).code);

        // 剩余效期不足 min_shelf_life_days 同样走 5404
        setCfg(PharmStockService.CFG_MIN_SHELF, "180");
        assertEquals(PharmStockService.E_EXPIRE_BAD, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, 7, "EXP-3", null,
                        BusinessDates.today().plusDays(30), null, null, null, LOC, null)).code);
    }

    /** 坏配置回落 warn 而非 off——一处笔误不该静默关掉整段校验 */
    @Test
    void badGateValueFallsBackToWarnNotOff() {
        setCfg(PharmStockService.CFG_GATE_BATCH, "WARN_PLEASE");
        assertEquals("warn", pharmStockService.gate(PharmStockService.CFG_GATE_BATCH));
        setCfg(PharmStockService.CFG_GATE_BATCH, "");
        assertEquals("warn", pharmStockService.gate(PharmStockService.CFG_GATE_BATCH));
        setCfg(PharmStockService.CFG_GATE_BATCH, "1");
        assertEquals("warn", pharmStockService.gate(PharmStockService.CFG_GATE_BATCH));
        // off 是显式合法值，仍认
        setCfg(PharmStockService.CFG_GATE_BATCH, " OFF ");
        assertEquals("off", pharmStockService.gate(PharmStockService.CFG_GATE_BATCH));
    }

    // ================= ③ 取批规则 FEFO / FIFO =================

    /**
     * FEFO（默认）：先出近效期，且**与入库先后无关**。
     * 本用例刻意先入远效期批次、后入近效期批次——若实现退化成 FIFO，这里会先取到远效期那批。
     */
    @Test
    void pickRuleFefoTakesNearestExpiryFirst() {
        Long drugId = seeds.drug("取批规则药").getId();
        LocalDate today = BusinessDates.today();
        StockInResult far  = pharmStockService.stockIn(drugId, 40, "FAR",  null, today.plusYears(3), null, null, null, LOC, null);
        StockInResult near = pharmStockService.stockIn(drugId, 30, "NEAR", null, today.plusYears(1), null, null, null, LOC, null);

        assertEquals("FEFO", pharmStockService.pickRule());
        PickResult p = pharmStockService.pick(drugId, LOC, 50);
        assertTrue(p.satisfied());
        assertEquals(0, p.shortfall());
        assertEquals(2, p.lines().size());
        assertEquals(near.batchId(), p.lines().get(0).batchId(), "FEFO 须先取近效期批次");
        assertEquals(30, p.lines().get(0).picked());
        assertEquals(far.batchId(), p.lines().get(1).batchId());
        assertEquals(20, p.lines().get(1).picked());

        // FIFO：改按建档先后，先取先入的远效期批次
        setCfg(PharmStockService.CFG_PICK_RULE, "FIFO");
        PickResult f = pharmStockService.pick(drugId, LOC, 50);
        assertEquals("FIFO", f.rule());
        assertEquals(far.batchId(), f.lines().get(0).batchId(), "FIFO 须先取先入库的批次");

        // 坏值回落 FEFO（临床更安全的方向），不回落 FIFO
        setCfg(PharmStockService.CFG_PICK_RULE, "LIFO");
        assertEquals("FEFO", pharmStockService.pickRule());
    }

    /** 取批预览是只读的：配不齐不抛错，且**一粒库存都不动** */
    @Test
    void pickPreviewIsReadOnlyAndReportsShortfall() {
        Long drugId = seeds.drug("取批不足药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 10, "SHORT-1", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);

        PickResult p = pharmStockService.pick(drugId, LOC, 25);
        assertFalse(p.satisfied());
        assertEquals(10, p.allocated());
        assertEquals(15, p.shortfall());
        assertEquals(10, (int) jdbc.queryForObject(
                "select qty from pharm_stock where batch_id = ?", Integer.class, in.batchId()),
                "预览不得改动余额");
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from pharm_stock_flow where batch_id = ?", Integer.class, in.batchId()),
                "预览不得写流水");
    }

    /** 空效期批次在 FEFO 里排最后：没有效期就判不出「先到期」 */
    @Test
    void nullExpiryBatchSortsLastInFefo() {
        Long drugId = seeds.drug("空效期排序药").getId();
        setCfg(PharmStockService.CFG_GATE_BATCH, "off");
        StockInResult noExp = pharmStockService.stockIn(drugId, 10, "NOEXP", null, null, null, null, null, LOC, null);
        StockInResult hasExp = pharmStockService.stockIn(drugId, 10, "HASEXP", null,
                BusinessDates.today().plusYears(5), null, null, null, LOC, null);

        PickResult p = pharmStockService.pick(drugId, LOC, 20);
        assertEquals(hasExp.batchId(), p.lines().get(0).batchId(), "有效期的批次须排在空效期之前");
        assertEquals(noExp.batchId(), p.lines().get(1).batchId());
    }

    // ================= ④ 报损：双扣 + 原因必填 + 扣不穿 =================

    @Test
    void scrapDeductsBothLedgers() {
        Long drugId = seeds.drug("报损测试药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 100, "SCR-1", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);
        int aggAfterIn = stockOf(drugId);

        ScrapResult s = pharmStockService.scrap(in.batchId(), LOC, 30, "运输破损", null);
        assertEquals(70, s.batchQtyAfter(), "批次子账 -30");
        assertEquals(aggAfterIn - 30, stockOf(drugId), "总账 md_drug.stock 同步 -30");
        assertEquals(aggAfterIn - 30, s.aggregateStockAfter());

        // 流水留痕：负位移 + 结存 + 原因
        Map<String, Object> flow = jdbc.queryForMap(
                "select flow_type, qty_delta, qty_after, reason from pharm_stock_flow where flow_no = ?", s.flowNo());
        assertEquals("SCRAP", flow.get("flow_type"));
        assertEquals(-30, ((Number) flow.get("qty_delta")).intValue());
        assertEquals(70, ((Number) flow.get("qty_after")).intValue());
        assertEquals("运输破损", flow.get("reason"));
    }

    /** 报损原因必填（5409），与 V82 拒收原因（8012）同一纪律 */
    @Test
    void scrapRequiresReason() {
        Long drugId = seeds.drug("报损无原因药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 10, "SCR-2", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);
        assertEquals(PharmStockService.E_REASON, assertThrows(HipBizException.class,
                () -> pharmStockService.scrap(in.batchId(), LOC, 1, "   ", null)).code);
    }

    /** 超余额报损被条件更新顶回（5408），余额与流水都不动 */
    @Test
    void scrapBeyondBalanceRejectedAndLeavesNothingBehind() {
        Long drugId = seeds.drug("报损超额药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 10, "SCR-3", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);
        int aggAfterIn = stockOf(drugId);

        assertEquals(PharmStockService.E_SHORT, assertThrows(HipBizException.class,
                () -> pharmStockService.scrap(in.batchId(), LOC, 11, "试图扣穿", null)).code);

        assertEquals(10, (int) jdbc.queryForObject(
                "select qty from pharm_stock where batch_id = ?", Integer.class, in.batchId()));
        assertEquals(aggAfterIn, stockOf(drugId), "失败的报损不得动总账");
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from pharm_stock_flow where batch_id = ?", Integer.class, in.batchId()),
                "失败的报损不得留下流水");
    }

    /** 报损一个不存在的批次 → 5407 */
    @Test
    void scrapUnknownBatch() {
        assertEquals(PharmStockService.E_BATCH_404, assertThrows(HipBizException.class,
                () -> pharmStockService.scrap(-999L, LOC, 1, "无此批次", null)).code);
    }

    // ================= ⑤ 不变量：余额可由流水推出 =================

    /**
     * 本车道的核心断言。做完一串增减后，逐 (批次,地点) 校验
     * {@code pharm_stock.qty == sum(pharm_stock_flow.qty_delta)}。
     */
    @Test
    void balanceIsAlwaysDerivableFromFlows() {
        Long drugId = seeds.drug("对账不变量药").getId();
        LocalDate today = BusinessDates.today();
        StockInResult b1 = pharmStockService.stockIn(drugId, 100, "REC-1", null, today.plusYears(1), null, null, null, LOC, null);
        pharmStockService.stockIn(drugId, 50, "REC-1", null, today.plusYears(1), null, null, null, LOC, null);
        StockInResult b2 = pharmStockService.stockIn(drugId, 80, "REC-2", null, today.plusYears(2), null, null, null, "BACKUP_CAB", null);
        pharmStockService.scrap(b1.batchId(), LOC, 20, "过期报损", null);
        pharmStockService.scrap(b2.batchId(), "BACKUP_CAB", 5, "破损", null);

        Map<String, Object> rec = pharmStockService.reconcile(drugId, 200);
        assertEquals(Boolean.TRUE, rec.get("balanced"), "余额必须能由流水推出来：" + rec.get("rows"));
        assertEquals(0, ((Number) rec.get("mismatched")).intValue());
        assertEquals(2, ((Number) rec.get("checked")).intValue(), "两个 (批次,地点) 组合");

        // 逐行核对具体数
        assertEquals(130, (int) jdbc.queryForObject(
                "select qty from pharm_stock where batch_id = ? and location_code = ?",
                Integer.class, b1.batchId(), LOC));
        assertEquals(75, (int) jdbc.queryForObject(
                "select qty from pharm_stock where batch_id = ? and location_code = ?",
                Integer.class, b2.batchId(), "BACKUP_CAB"));
    }

    /** 对账端点抓得住人为破坏——不是恒返 balanced=true 的摆设 */
    @Test
    void reconcileActuallyDetectsMismatch() {
        Long drugId = seeds.drug("对账抓错药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 60, "BAD-1", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);
        assertEquals(Boolean.TRUE, pharmStockService.reconcile(drugId, 50).get("balanced"));

        // 绕过服务层直接改余额，模拟一条不写流水的野路径
        jdbc.update("update pharm_stock set qty = qty + 7 where batch_id = ?", in.batchId());
        Map<String, Object> rec = pharmStockService.reconcile(drugId, 50);
        assertEquals(Boolean.FALSE, rec.get("balanced"), "余额被改而流水未动，对账必须抓到");
        assertEquals(1, ((Number) rec.get("mismatched")).intValue());
    }

    // ================= ⑥ 库存视图：漂移与覆盖率的诚实标注 =================

    /**
     * 既有发药只扣总账不扣批次余额 → 批次层单向偏高。
     * 本用例直接模拟一次「旧口径扣减」，验证 drift 被如实报出来而不是被抹平。
     */
    @Test
    void balanceReportsDriftInsteadOfHidingIt() {
        Long drugId = seeds.drug("漂移标注药").getId();
        // 先把旧口径存量清零：TestSeeds 建的药 stock=100000，那是**未纳入批次管理的存量库存**，
        // 会让 drift 被它主导成一个大负数（那是另一种漂移，由下面 legacyStockShowsNegativeDrift 覆盖）。
        // 本用例要隔离的是「发药只扣汇总」造成的正向漂移，故从零存量的药起算
        jdbc.update("update md_drug set stock = 0 where id = ?", drugId);
        entityManager.clear();
        pharmStockService.stockIn(drugId, 200, "DRIFT-1", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);

        // 模拟既有 DispenseService 的扣减：只动 md_drug.stock，不动批次余额
        assertEquals(1, drugRepository.deductStock(drugId, 30));

        Map<String, Object> body = pharmStockService.balanceByDrug(drugId, null, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        int agg = ((Number) row.get("aggregate_stock")).intValue();
        int bat = ((Number) row.get("batch_stock")).intValue();
        assertEquals(200, bat, "批次子账仍是 200——发药没扣它");
        assertEquals(bat - agg, ((Number) row.get("drift")).intValue());
        assertTrue(((Number) row.get("drift")).intValue() > 0, "发药后批次层必然偏高");
        assertNotNull(row.get("driftNote"), "drift≠0 必须带成因说明，不能只给一个数");
        assertTrue(row.get("driftNote").toString().contains("发药"));

        // 顶层 caveats 与 coverage 必在——「批次库存都对得上」不能被读成「全院库存都对得上」
        assertNotNull(body.get("caveats"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cov = (Map<String, Object>) body.get("coverage");
        assertTrue(((Number) cov.get("enabledDrugs")).intValue() > 0);
        assertTrue(((Number) cov.get("drugsWithBatchRecord")).intValue() >= 1);
        assertNotNull(cov.get("legacyOnlyDrugs"));
        assertNotNull(cov.get("batchCoverageRatePct"));
        assertNotNull(cov.get("rateFormula"), "比率必须自带分子分母口径，否则没人知道分母是什么");
    }

    // ================= ⑦ 收敛接缝：OUT / RET 的批次账写入 =================

    /**
     * {@code consumeForDispense} 按规则取批、扣批次余额、写 OUT 流水，
     * 且**不动 md_drug.stock**（汇总由调用方 DispenseService 负责，这里再动一次就是双扣）。
     *
     * <p>本方法在 v50-A 没有调用方，是给「发药接入批次账」预留的接缝——
     * 有它，收敛就退化成 DispenseService 里加一次调用，而不是重新设计取批/扣减/流水/并发四件事。
     */
    @Test
    void consumeForDispenseDeductsBatchLedgerOnlyAndKeepsInvariant() {
        Long drugId = seeds.drug("发药接缝药").getId();
        LocalDate today = BusinessDates.today();
        StockInResult near = pharmStockService.stockIn(drugId, 20, "SEAM-NEAR", null, today.plusYears(1), null, null, null, LOC, null);
        StockInResult far  = pharmStockService.stockIn(drugId, 50, "SEAM-FAR",  null, today.plusYears(3), null, null, null, LOC, null);
        int aggBefore = stockOf(drugId);

        PickResult r = pharmStockService.consumeForDispense(drugId, LOC, 35, "RX-SEAM-1", null);

        assertTrue(r.satisfied());
        // FEFO：先扣光近效期 20，再从远效期扣 15
        assertEquals(2, r.lines().size());
        assertEquals(near.batchId(), r.lines().get(0).batchId());
        assertEquals(20, r.lines().get(0).picked());
        assertEquals(15, r.lines().get(1).picked());
        assertEquals(0,  (int) jdbc.queryForObject("select qty from pharm_stock where batch_id = ?", Integer.class, near.batchId()));
        assertEquals(35, (int) jdbc.queryForObject("select qty from pharm_stock where batch_id = ?", Integer.class, far.batchId()));
        // 汇总一动不动——职责切分：谁扣的汇总谁负责
        assertEquals(aggBefore, stockOf(drugId), "本方法不得碰 md_drug.stock，否则与调用方双扣");
        // 两条 OUT 流水，且不变量仍成立
        assertEquals(2, (int) jdbc.queryForObject("""
                select count(*) from pharm_stock_flow f join pharm_batch b on b.id = f.batch_id
                 where b.drug_id = ? and f.flow_type = 'OUT' and f.ref_no = 'RX-SEAM-1'
                """, Integer.class, drugId));
        assertEquals(Boolean.TRUE, pharmStockService.reconcile(drugId, 50).get("balanced"));
    }

    /** 配不齐即整单失败（5408），不做部分出库——半张处方发出去是药房最难收拾的状态 */
    @Test
    void consumeForDispenseIsAllOrNothing() {
        Long drugId = seeds.drug("发药接缝不足药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 10, "SEAM-SHORT", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);

        assertEquals(PharmStockService.E_SHORT, assertThrows(HipBizException.class,
                () -> pharmStockService.consumeForDispense(drugId, LOC, 11, "RX-SHORT", null)).code);
        assertEquals(10, (int) jdbc.queryForObject(
                "select qty from pharm_stock where batch_id = ?", Integer.class, in.batchId()),
                "配不齐时一粒都不许扣");
        assertEquals(0, (int) jdbc.queryForObject(
                "select count(*) from pharm_stock_flow where batch_id = ? and flow_type = 'OUT'",
                Integer.class, in.batchId()), "配不齐时不许留 OUT 流水");
    }

    /** 退药回补到**指定原批次**（不猜批次），写 RET 流水，同样不动 md_drug.stock */
    @Test
    void restoreToBatchWritesRetFlowToTheOriginalBatch() {
        Long drugId = seeds.drug("退药接缝药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 30, "SEAM-RET", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);
        pharmStockService.consumeForDispense(drugId, LOC, 12, "RX-RET-1", null);
        int aggBefore = stockOf(drugId);

        int after = pharmStockService.restoreToBatch(in.batchId(), LOC, 5, "RX-RET-1", null);

        assertEquals(23, after, "18 + 5");
        assertEquals(aggBefore, stockOf(drugId), "本方法不得碰 md_drug.stock");
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from pharm_stock_flow where batch_id = ? and flow_type = 'RET'",
                Integer.class, in.batchId()));
        // 三类流水走完，不变量依然成立
        assertEquals(Boolean.TRUE, pharmStockService.reconcile(drugId, 50).get("balanced"));
    }

    /** 报损撞上漂移时报 5410 并直指 /reconcile，**不把汇总扣成负数** */
    @Test
    void scrapBlockedByDriftReportsConcurrentNotNegativeStock() {
        Long drugId = seeds.drug("漂移挡报损药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 40, "DRIFT-2", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);
        // 把总账扣到低于批次余额（模拟大量发药后的漂移）
        int agg = stockOf(drugId);
        assertEquals(1, drugRepository.deductStock(drugId, agg - 5));
        assertEquals(5, stockOf(drugId));

        HipBizException ex = assertThrows(HipBizException.class,
                () -> pharmStockService.scrap(in.batchId(), LOC, 40, "全批报废", null));
        assertEquals(PharmStockService.E_CONCURRENT, ex.code);
        assertTrue(ex.getMessage().contains("reconcile"), "错误消息须指向对账端点");
        assertTrue(ex.getMessage().contains("漂移"), "错误消息须点明这是总账/子账漂移，而不是一句「并发冲突」");
        // **总账没有被扣成负数**——这才是本用例真正要守的那条线：
        // deductStock 是带 `stock >= qty` 谓词的条件更新，扣不动就影响 0 行，不会写出负库存
        assertEquals(5, stockOf(drugId), "总账不得被扣成负数");
        //
        // 这里**刻意不断言「批次余额已回滚到 40」**。scrap 标了 @Transactional，
        // 生产上它是最外层事务，抛异常即整体回滚，批次侧那次扣减一定不会留下。
        // 但本测试类自己带 @Transactional，scrap 以 PROPAGATION_REQUIRED **加入**测试事务，
        // 不会有独立的内层回滚——此刻读回来的余额是 0（扣减已发生、要等测试事务结束才一起回滚）。
        // 在这种情形下断言 40 断的不是本车道的代码，而是 Spring 的事务传播语义，且断错了方向。
        // 「失败的写路径不留痕」由 scrapBeyondBalanceRejectedAndLeavesNothingBehind 覆盖：
        // 那条路径在**任何写入之前**就抛 5408，与事务传播无关，是可稳定断言的。
    }

    /**
     * 另一种漂移：汇总高于批次层账面——该药还有**未纳入批次管理的存量库存**。
     * 这不是数据错误，正是 V147 零回填的直接后果，返回体须如实说明而不是拿汇总去填批次层。
     */
    @Test
    void legacyStockShowsNegativeDriftWithExplanation() {
        Long drugId = seeds.drug("存量未纳管药").getId();
        assertTrue(stockOf(drugId) > 0, "该药在旧口径下有存量库存");
        pharmStockService.stockIn(drugId, 10, "LEGACY-1", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) pharmStockService.balanceByDrug(drugId, null, 10).get("rows");
        Map<String, Object> row = rows.get(0);
        assertTrue(((Number) row.get("drift")).intValue() < 0, "存量未纳管时批次层低于汇总");
        assertTrue(row.get("driftNote").toString().contains("未纳入批次管理"),
                "须点明成因是存量未纳管，而不是让人以为账错了");
        assertTrue(row.get("driftNote").toString().contains("不反推初始批次"),
                "须写明本版刻意不拿汇总反推批次（那会捏造批号与效期）");
    }

    // ================= ⑦ 查询与参数守门 =================

    @Test
    void batchAndFlowQueriesWork() {
        Long drugId = seeds.drug("查询测试药").getId();
        LocalDate today = BusinessDates.today();
        StockInResult in = pharmStockService.stockIn(drugId, 25, "QRY-1", today.minusMonths(6),
                today.plusDays(30), "查询供应商", new BigDecimal("1.5"), null, LOC, null);
        pharmStockService.scrap(in.batchId(), LOC, 5, "查询用报损", null);

        // 批次查询：带 daysToExpire 与 expiryStatus
        List<Map<String, Object>> batches = pharmStockService.batches(drugId, "QRY", null, 50);
        assertEquals(1, batches.size());
        assertEquals(30L, ((Number) batches.get(0).get("daysToExpire")).longValue());
        assertEquals("NEAR_EXPIRY", batches.get(0).get("expiryStatus"), "30 天内到期且阈值 90 天 → 近效期");
        assertEquals(20, ((Number) batches.get(0).get("on_hand")).intValue());

        // 效期窗过滤：60 天内命中，10 天内不命中
        assertEquals(1, pharmStockService.batches(drugId, null, 60, 50).size());
        assertEquals(0, pharmStockService.batches(drugId, null, 10, 50).size());

        // 流水查询：两条（IN + SCRAP），类型过滤生效
        assertEquals(2, pharmStockService.flows(drugId, null, null, null, null, 50).size());
        assertEquals(1, pharmStockService.flows(drugId, null, "SCRAP", null, null, 50).size());
        // 今天的日窗口须含今天的流水（写成 `<= to` 会漏掉当天全部流水）
        assertEquals(2, pharmStockService.flows(drugId, null, null, today, today, 50).size());

        // 按批次的余额明细
        List<Map<String, Object>> byBatch = pharmStockService.balanceByBatch(drugId, null, LOC, 50);
        assertEquals(1, byBatch.size());
        assertEquals(20, ((Number) byBatch.get(0).get("qty")).intValue());
    }

    @Test
    void queryAndInputGuards() {
        Long drugId = seeds.drug("守门测试药").getId();
        // 数量非法
        assertEquals(PharmStockService.E_QTY, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, 0, "G-1", null, null, null, null, null, LOC, null)).code);
        assertEquals(PharmStockService.E_QTY, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, PharmStockService.MAX_QTY + 1, "G-1", null, null,
                        null, null, null, LOC, null)).code);
        // 进价为负
        assertEquals(PharmStockService.E_PRICE, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, 1, "G-1", null, null, null,
                        new BigDecimal("-0.01"), null, LOC, null)).code);
        // 地点编码非法
        assertEquals(PharmStockService.E_LOCATION, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, 1, "G-1", null, null, null, null, null, "门诊药房", null)).code);
        // 药品不存在
        assertEquals(PharmStockService.E_DRUG, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(-1L, 1, "G-1", null, null, null, null, null, LOC, null)).code);
        // 查询参数
        assertEquals(PharmStockService.E_QUERY, assertThrows(HipBizException.class, () ->
                pharmStockService.flows(null, null, "NOPE", null, null, 10)).code);
        assertEquals(PharmStockService.E_QUERY, assertThrows(HipBizException.class, () ->
                pharmStockService.flows(null, null, null, BusinessDates.today(),
                        BusinessDates.today().minusDays(1), 10)).code);
        assertEquals(PharmStockService.E_QUERY, assertThrows(HipBizException.class, () ->
                pharmStockService.batches(null, null, 99999, 10)).code);
        assertEquals(PharmStockService.E_QUERY, assertThrows(HipBizException.class, () ->
                pharmStockService.batches(null, null, null, PharmStockService.MAX_PAGE_SIZE + 1)).code);
    }

    /** 停用药不得入库（5400），但既有批次仍可报损——停用往往正因为要清退 */
    @Test
    void disabledDrugCannotStockInButCanStillScrap() {
        Long drugId = seeds.drug("停用清退药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 10, "DIS-1", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);

        jdbc.update("update md_drug set enabled = false where id = ?", drugId);
        entityManager.clear();

        assertEquals(PharmStockService.E_DRUG, assertThrows(HipBizException.class, () ->
                pharmStockService.stockIn(drugId, 5, "DIS-2", null,
                        BusinessDates.today().plusYears(1), null, null, null, LOC, null)).code);
        // 报损仍可做
        ScrapResult s = pharmStockService.scrap(in.batchId(), LOC, 10, "停用清退", null);
        assertEquals(0, s.batchQtyAfter());
    }

    /** 地点隔离：同一批次在不同药柜各记各的余额 */
    @Test
    void locationsKeepSeparateBalances() {
        Long drugId = seeds.drug("多药柜药").getId();
        LocalDate expire = BusinessDates.today().plusYears(1);
        StockInResult a = pharmStockService.stockIn(drugId, 60, "LOC-1", null, expire, null, null, null, LOC, null);
        StockInResult b = pharmStockService.stockIn(drugId, 15, "LOC-1", null, expire, null, null, null, "CAB_A", null);

        assertEquals(a.batchId(), b.batchId(), "同批号仍是同一个批次");
        assertEquals(60, a.batchQtyAfter());
        assertEquals(15, b.batchQtyAfter(), "另一药柜从 0 起算，不与主药房合并");
        assertEquals(2, (int) jdbc.queryForObject(
                "select count(*) from pharm_stock where batch_id = ?", Integer.class, a.batchId()));

        // 取批只在指定地点内进行
        assertEquals(15, pharmStockService.pick(drugId, "CAB_A", 100).allocated());
        assertEquals(60, pharmStockService.pick(drugId, LOC, 100).allocated());
        // 小写地点编码归一化后命中同一行
        assertEquals(15, pharmStockService.pick(drugId, "cab_a", 100).allocated());
    }

    /** settings 端点回带的档位与实际判定同源，前端据此显示必填星号 */
    @Test
    void settingsExposeEffectiveGates() {
        Map<String, Object> s = pharmStockService.settings();
        assertEquals("warn", s.get("batchGate"));
        assertEquals("warn", s.get("expiryGate"));
        assertEquals("FEFO", s.get("pickRule"));
        assertEquals(LOC, s.get("defaultLocation"));
        // 近效期天数复用 V83 的 inv_expiry_warn_days，与近效期预警页同一个数
        assertEquals(Integer.valueOf(jdbc.queryForObject(
                        "select cfg_value from sys_config where cfg_key = 'inv_expiry_warn_days'", String.class)),
                s.get("nearExpiryDays"));
    }

    // ================= ⑧ 迁移纪律：零回填 =================

    /**
     * V147 不得含任何回填。新表在一个**从未走过本车道写路径**的药品上必须是空的——
     * 拿 md_drug.stock 反推初始批次会凭空捏造批号与效期，召回时会让人照着假批号下架真药。
     */
    @Test
    void migrationBackfillsNothing() {
        Long drugId = seeds.drug("零回填对照药").getId();
        assertTrue(stockOf(drugId) > 0, "该药在旧口径下有库存");
        assertEquals(0, (int) jdbc.queryForObject(
                "select count(*) from pharm_batch where drug_id = ?", Integer.class, drugId),
                "未走批次入库的药，批次层必须一行都没有");
        // 全库层面：批次行数必须等于经本车道入库产生的行数（本用例事务内为 0）
        assertEquals(0, (int) jdbc.queryForObject("""
                select count(*) from pharm_stock s
                  join pharm_batch b on b.id = s.batch_id
                 where b.created_by is null and b.batch_no like 'SEED%'
                """, Integer.class), "迁移不得种任何库存行");
    }

    /**
     * <b>迁移脚本剥掉注释后，一条 update 都没有、一条往三张新表的 insert 都没有。</b>
     *
     * <p>沿用 {@code V49SurgeryCancelledAtTest.migrationContainsZeroUpdateStatements} 的做法：
     * 运行时行数为 0 只能证明「这次没回填」，静态扫描才能挡住后来者「顺手回填一下让页面好看」。
     * 剥注释是必要的——V147 的注释正好在长篇讲「为什么不能拿 md_drug.stock 反推初始批次」，
     * 字面上带着 update / insert 这些词。
     *
     * <p>本迁移允许的唯一 insert 是往 {@code sys_config} 落**新配置键**（gate 与取批规则），
     * 那是新键登记、不是历史回填，故白名单放行并单独校验其形态。
     */
    @Test
    void migrationContainsNoBackfillStatements() throws Exception {
        String raw = new String(new org.springframework.core.io.ClassPathResource(
                "db/migration/V147__pharmacy_inventory.sql").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        String code = java.util.Arrays.stream(raw.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"))
                .toLowerCase();

        assertFalse(code.contains("update "), "迁移里零条 update");
        // 三张新表一条 insert 都不许有：拿 md_drug.stock 反推批次会凭空捏造批号与效期，
        // 召回时会让人照着假批号去下架真药
        assertFalse(code.contains("insert into pharm_batch"), "不得预置任何批次");
        assertFalse(code.contains("insert into pharm_stock"), "不得预置任何批次余额");
        assertFalse(code.contains("insert into pharm_stock_flow"), "不得预置任何流水");
        assertFalse(code.contains("from md_drug"), "不得从 md_drug 取数造批次");
        // 唯一允许的 insert：新配置键登记
        assertTrue(code.contains("insert into sys_config"), "gate 与取批规则须落 sys_config");
        assertTrue(code.contains("on conflict (cfg_key) do nothing"), "新键登记须幂等，不得覆盖院方已调过的档位");
        // 不插菜单：前端页面属共用目录，先插菜单会得到一个点进去 404 的死入口
        assertFalse(code.contains("insert into sys_menu"), "本车道不落前端，不得插菜单");
    }

    /** 三张表都真的建起来了，且 gate 键已 seed 为 warn（默认档不靠代码兜底也在库里） */
    @Test
    void migrationCreatedTablesAndSeededGates() {
        for (String t : List.of("pharm_batch", "pharm_stock", "pharm_stock_flow")) {
            assertEquals(1, (int) jdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_name = ?", Integer.class, t),
                    t + " 未建表");
        }
        assertEquals("warn", jdbc.queryForObject(
                "select cfg_value from sys_config where cfg_key = ?", String.class,
                PharmStockService.CFG_GATE_BATCH));
        assertEquals("warn", jdbc.queryForObject(
                "select cfg_value from sys_config where cfg_key = ?", String.class,
                PharmStockService.CFG_GATE_EXPIRY));
        assertEquals("FEFO", jdbc.queryForObject(
                "select cfg_value from sys_config where cfg_key = ?", String.class,
                PharmStockService.CFG_PICK_RULE));
    }

    /**
     * 数据库 CHECK 是写侧校验的兜底：任何绕过 service 的路径都写不进坏账。
     * 新表零行，加约束零风险（与既有表不加 CHECK 的 v42 纪律不冲突——那条纪律的前提是表里已有脏数据）。
     */
    @Test
    void databaseChecksRejectBadRowsEvenBypassingService() {
        Long drugId = seeds.drug("CHECK兜底药").getId();
        StockInResult in = pharmStockService.stockIn(drugId, 10, "CHK-1", null,
                BusinessDates.today().plusYears(1), null, null, null, LOC, null);

        // 负余额写不进去
        assertRejectedByDb("负余额", () ->
                jdbc.update("update pharm_stock set qty = -1 where batch_id = ?", in.batchId()));
        // 零位移流水写不进去（「这次操作没动任何东西」不该占一行，会稀释审计线索）
        assertRejectedByDb("零位移流水", () ->
                jdbc.update("""
                        insert into pharm_stock_flow(flow_no, batch_id, location_code, flow_type,
                                                     qty_delta, qty_after, occurred_at)
                        values ('ZERO-1', ?, ?, 'IN', 0, 10, now())
                        """, in.batchId(), LOC));
        // 非法流水类型写不进去
        assertRejectedByDb("非法流水类型", () ->
                jdbc.update("""
                        insert into pharm_stock_flow(flow_no, batch_id, location_code, flow_type,
                                                     qty_delta, qty_after, occurred_at)
                        values ('BADTYPE-1', ?, ?, 'STEAL', -1, 9, now())
                        """, in.batchId(), LOC));
        // 生产日期晚于效期写不进去（日期字面量在此**无害**：它不参与任何"今天"的判定，
        // 只是两个互相比较的常量，与时区无关）
        assertRejectedByDb("生产日期晚于效期", () ->
                jdbc.update("""
                        insert into pharm_batch(drug_id, batch_no, produced_on, expire_on)
                        values (?, 'CHK-BADDATE', date '2030-01-01', date '2029-01-01')
                        """, drugId));
    }

    /**
     * 断言一次写入被数据库约束拒绝，并用 <b>SAVEPOINT</b> 把事务从"已中止"状态救回来。
     *
     * <p>没有 savepoint 这段是跑不通的，且失败方式极具误导性：PostgreSQL 里一条语句触发约束
     * 违例后，整个事务进入 aborted 状态，**后续任何语句一律报 25P02
     * {@code current transaction is aborted}**——第二条断言拿到的不是它自己的约束错，
     * 而是上一条留下的残骸；再往后连 Spring 回滚测试事务都会牵连报错，
     * 最终以 {@code ApplicationContext failure} 的形态出现，完全看不出根因
     * （本车道实测踩到，此注释即学费）。
     */
    private void assertRejectedByDb(String label, Runnable write) {
        jdbc.execute("savepoint chk_sp");
        try {
            write.run();
            jdbc.execute("release savepoint chk_sp");
            fail(label + "：数据库 CHECK 应当拒绝这行，但写进去了");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            jdbc.execute("rollback to savepoint chk_sp");
        }
    }
}
