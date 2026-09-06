package cn.hip.outpatient.service;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.masterdata.entity.InvStockIn;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * v50 车道 A：门诊药房库存地基（批次 / 效期 / 批次级出入库流水）。
 *
 * <h2>先纠一条事实：本仓不是「没有批次、没有效期、没有出入库流水」</h2>
 * 下发口径如此写，实测不成立。{@code inv_stock_in}（V7 建表）自建表起就有
 * {@code batch_no / expire_date / supplier}，V82 还给它加了三态验收工作流；
 * {@code inv_transaction}（同 V7）是一条 IN/OUT/RET/ADJ/STOCKTAKE 五型齐全的
 * **药品级**出入库流水；V81 有盘点单；V83 的 {@code InventoryService.expiryWarnings}
 * 已在跑近效期扫描。按错的现状建表，产物会是**第二套同名同义的入库单与流水**。
 *
 * <p>真正缺的是**批次维度的余额**：流水只有 {@code drug_id}、没有 {@code batch_id}，
 * 于是 {@code md_drug.stock} 这个整数拆不开。V83 自己在注释里认了后果——近效期预警只能做
 * 「只读估算口径」，按假设把净出量分摊到批次上倒推剩余。**今天报的是估计值，不是账。**
 * 本类补的就是断掉的那一环，详见 {@code V147__pharmacy_inventory.sql} 的迁移注释。
 *
 * <h2>与 md_drug.stock 的关系（一句话版）</h2>
 * <ul>
 *   <li>{@code md_drug.stock} = 全院可发药量，**权威总账**，既有发药链路继续扣它，本版不动；
 *   <li>{@code pharm_stock} = 已启用批次管理部分的**批次级子账**，不是第二本总账。
 * </ul>
 * 入库端点**不自己写一套加库存的 SQL**，而是委托既有
 * {@link InventoryService#stockIn} + {@link InventoryService#acceptStockIn} 去走旧口径
 * （建 inv_stock_in 单、原子加 md_drug.stock、写 inv_transaction 的 IN 流水），
 * 然后才落自己的三张表，并用 {@code pharm_stock_flow.src_stock_in_id} 把两本账勾起来。
 * 好处：加库存仍走那段已被测试覆盖的原子条件更新，全仓不出现第二套扣加库存的实现。
 *
 * <h2>已知漂移（drift）——公开标注，不掩盖</h2>
 * 出账今天只补了一半：入库、报损双写；**发药出库仍只扣 md_drug.stock**，
 * 因为 {@code DispenseService} 是既有链路、本车道不许改（改坏发药的代价远高于一个可标注的偏差）。
 * 于是随发药发生，批次层账面**单向偏高**。处理方式沿用 v46/v48 的诚实标注：
 * {@code /balance} 每行回带 {@code drift}，返回体顶层带 {@code coverage}，
 * 报损撞上漂移时报 {@link #E_CONCURRENT} 并直指 {@code /reconcile}，
 * **不静默把汇总扣成负数**。收敛路径见迁移注释第三节。
 *
 * <h2>不变量：流水是账，库存是余额</h2>
 * 对任意 {@code (batch_id, location_code)} 恒有
 * {@code pharm_stock.qty == sum(pharm_stock_flow.qty_delta)}。两者永远同事务写入；
 * {@link #reconcile} 把这条等式逐行验一遍，V50PharmStockTest 以它作断言。
 *
 * <h2>错误码段 5400–5419</h2>
 * 实测用掉 12 个：5400 药品、5401 数量、5402 批号必填、5403 效期必填、5404 效期不合格、
 * 5405 生产日期晚于效期、5406 同批号效期冲突、5407 批次不存在、5408 余额不足、
 * 5409 报损原因必填、5410 并发/漂移、5411 进价非法、5412 地点编码非法、5413 查询参数非法。
 * 5414–5419 空置且**不预留登记**（不写代码就不占码，沿用 v48 口径）。
 *
 * <h2>gate 与配置</h2>
 * {@code pharm.gate.batch.required}、{@code pharm.gate.expiry.stock_in} 三态
 * **默认 warn、坏值回落 warn 不回落 off**；warn 档**不返错误码**，改以返回体
 * {@code warnings} 数组回带、记录照常落库（与 v47 {@code emr.gate.vital.range} 同范式）。
 * {@code pharm.batch.pick_rule} 不是 gate 而是模式，坏值回落 FEFO。
 * 近效期天数**复用 V83 已有的 {@code inv_expiry_warn_days}**，不另开键——
 * 同一个业务概念在系统里只能有一个数。
 */
@Service
@RequiredArgsConstructor
public class PharmStockService {

    // ===================== 错误码（5400–5419，见 docs/错误码分段.md） =====================

    /** 药品不存在或已停用 */
    public static final int E_DRUG        = 5400;
    /** 入库/报损数量非法（非正、超单次上限） */
    public static final int E_QTY         = 5401;
    /** 批号必填（仅 block 档） */
    public static final int E_BATCH_NO    = 5402;
    /** 有效期至必填（仅 block 档） */
    public static final int E_EXPIRE_REQ  = 5403;
    /** 效期不合格入库：已过期 / 剩余效期不足（仅 block 档） */
    public static final int E_EXPIRE_BAD  = 5404;
    /** 生产日期晚于有效期至 */
    public static final int E_DATE_ORDER  = 5405;
    /** 同一药品同一批号与既有批次的效期/生产日期冲突 */
    public static final int E_BATCH_CONF  = 5406;
    /** 批次不存在 */
    public static final int E_BATCH_404   = 5407;
    /** 批次可用余额不足 */
    public static final int E_SHORT       = 5408;
    /** 报损原因必填 */
    public static final int E_REASON      = 5409;
    /** 并发变化 / 总账子账漂移导致无法入账 */
    public static final int E_CONCURRENT  = 5410;
    /** 进价非法 */
    public static final int E_PRICE       = 5411;
    /** 库房/药柜编码非法 */
    public static final int E_LOCATION    = 5412;
    /** 查询参数非法 */
    public static final int E_QUERY       = 5413;

    // ===================== 配置键与默认值 =====================

    public static final String CFG_GATE_BATCH   = "pharm.gate.batch.required";
    public static final String CFG_GATE_EXPIRY  = "pharm.gate.expiry.stock_in";
    public static final String CFG_PICK_RULE    = "pharm.batch.pick_rule";
    public static final String CFG_MIN_SHELF    = "pharm.batch.min_shelf_life_days";
    /** 近效期天数：**复用 V83 已有键**，不另开 pharm.* 同义键 */
    public static final String CFG_NEAR_EXPIRY  = "inv_expiry_warn_days";

    public static final String DEFAULT_LOCATION = "OUTP_PHARM";

    /**
     * 单次入库/报损数量上限。不是业务规则，是**误操作护栏**：
     * 药品数量输入框敲多一个 0 是真实高频事故，而入库一旦入账就要走报损才能冲掉。
     * 取 1_000_000 足够覆盖任何真实到货批量（最大包装单位下的整车到货也远低于此），
     * 同时挡住 int 溢出——qty_after 是 int，累计溢出会让余额翻负并撞 CHECK，
     * 那时报的是 4091 数据完整性，看不出根因。
     */
    public static final int MAX_QTY = 1_000_000;

    /** 地点编码字符集：大写字母/数字/下划线/短横，1–32 位。留白与中文会让编码不可比对 */
    private static final Pattern LOCATION_PATTERN = Pattern.compile("^[A-Z0-9_-]{1,32}$");

    /** 流水查询单页上限：穿透明细逐条带患者无关的库存数据，但仍要挡住整表拉取 */
    public static final int MAX_PAGE_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;
    private final DrugItemRepository drugRepository;
    private final InventoryService inventoryService;

    // ===================== 返回体记录 =====================

    /** 入库结果：批次 + 余额 + 两本账的勾稽键 + warn 档回带的 warnings */
    public record StockInResult(Long batchId, String batchNo, Long drugId, String drugName,
                                String locationCode, int qty, int batchQtyAfter,
                                Long stockInId, String stockInNo, String flowNo,
                                LocalDate producedOn, LocalDate expireOn, Long daysToExpire,
                                BigDecimal purchasePrice, String supplier,
                                List<String> warnings) {}

    /** 取批分配的一行（FEFO/FIFO 规则的输出） */
    public record PickLine(Long batchId, String batchNo, LocalDate expireOn,
                           String locationCode, int available, int picked) {}

    /** 取批结果：规则 + 分配明细 + 是否配齐 */
    public record PickResult(String rule, int requested, int allocated, int shortfall,
                             boolean satisfied, List<PickLine> lines, String caveat) {}

    /** 报损结果 */
    public record ScrapResult(Long batchId, String batchNo, Long drugId, int qty,
                              int batchQtyAfter, int aggregateStockAfter,
                              String flowNo, String reason) {}

    /** 对账一行：余额 vs 流水累计 */
    public record ReconcileLine(Long batchId, String batchNo, Long drugId, String drugName,
                                String locationCode, int balanceQty, int flowSumQty,
                                int diff, int flowCount, boolean balanced) {}

    // ===================== 1. 入库登记 =====================

    /**
     * 批次入库登记（**一步式：登记即入账**）。
     *
     * <p>本端点是「验收后的批次入账」入口。需要质检环节先挡不合格批次的院区，仍走 V82 的
     * 待验收页（{@code InventoryService.stockIn} → 药师在验收页 {@code acceptStockIn}）——
     * 那条路径**不产生批次行**，其入库量只进 md_drug.stock，因此不计入
     * {@link #coverage()} 的已覆盖药品。两道门并存、覆盖率如实报数，就是迁移路径本身。
     *
     * <p>入账顺序刻意是「先旧口径、后批次层」：{@code src_stock_in_id} 是外键，
     * 必须先拿到 inv_stock_in 的 id；且旧口径那段的原子条件更新若失败（并发验收），
     * 批次层一行都不该落下。整个方法一个事务，任一步抛出全部回滚。
     */
    @Transactional
    public StockInResult stockIn(Long drugId, int qty, String batchNo, LocalDate producedOn,
                                 LocalDate expireOn, String supplier, BigDecimal purchasePrice,
                                 String purchaseNo, String locationCode, Long operatorId) {
        // ---- 恒定校验（与 gate 无关，任何档位都拦）----
        Map<String, Object> drug = requireEnabledDrug(drugId);
        requireQty(qty);
        String location = normalizeLocation(locationCode);
        if (purchasePrice != null && purchasePrice.signum() < 0) {
            throw new HipBizException(E_PRICE, "进价不能为负");
        }
        String batch = batchNo == null ? null : batchNo.trim();
        if (batch != null && batch.isEmpty()) batch = null;
        if (producedOn != null && expireOn != null && producedOn.isAfter(expireOn)) {
            // 恒定校验而非 gate：生产日期晚于效期是**录反了**，不是宽严尺度问题。
            // 放过去会让 FEFO 的取批顺序整个颠倒，那比拦住一次入库危险得多
            throw new HipBizException(E_DATE_ORDER,
                    "生产日期（" + producedOn + "）不得晚于有效期至（" + expireOn + "）");
        }

        // ---- gate 判定（warn 档只收集 warnings、不抛）----
        List<String> warnings = new ArrayList<>();
        evaluateBatchGate(batch, expireOn, warnings);
        evaluateExpiryGate(expireOn, warnings);

        // ---- 批次主数据 upsert：同药同批号唯一，效期/生产日期冲突即顶回去 ----
        Long batchId = resolveOrCreateBatch(drugId, batch, producedOn, expireOn,
                supplier, purchasePrice, operatorId);

        // ---- 旧口径入账：委托既有链路，不复制一套加库存 SQL ----
        InvStockIn in;
        try {
            in = inventoryService.stockIn(drugId, qty, batch, expireOn, supplier, purchaseNo, operatorId);
            in = inventoryService.acceptStockIn(in.getId(), operatorId);
        } catch (InventoryService.InventoryException e) {
            // 8001/8002 已被上面的恒定校验先行拦下，走到这里只可能是 8010/8011 的并发路径。
            // 不把 8xxx 透传出去：本端点的错误码契约是 5400 段，混段会让调用方两套码都要认
            throw new HipBizException(E_CONCURRENT,
                    "入库单已被并发处理，请刷新后重试（底层：" + e.getMessage() + "）");
        }

        // ---- 批次层入账：原子 upsert 余额 + 写流水，两者同事务 ----
        Instant now = nowMicros();
        int qtyAfter = applyDelta(batchId, location, qty);
        String flowNo = writeFlow(batchId, location, "IN", qty, qtyAfter,
                null, in.getInNo(), in.getId(), operatorId, now);

        return new StockInResult(batchId, batch, drugId, (String) drug.get("name"),
                location, qty, qtyAfter, in.getId(), in.getInNo(), flowNo,
                producedOn, expireOn, daysToExpire(expireOn), purchasePrice, supplier,
                List.copyOf(warnings));
    }

    // ===================== 2. 报损 =====================

    /**
     * 报损（破损 / 过期 / 丢失）：批次余额与 md_drug.stock **同时**扣减——货是真的没了。
     *
     * <p>扣减顺序为「先批次子账、后总账」，两步都是带谓词的原子条件更新，同一事务，
     * 任一步失败整体回滚，不会留下只扣了一边的半截账。
     *
     * <p><b>总账扣不动时报 {@link #E_CONCURRENT} 而不是硬扣</b>：这正是漂移的表现——
     * 批次层认为这批还有 100，而 md_drug.stock 因为期间的发药只剩 20。此时把汇总扣成负数
     * 会让发药端从此拿不到正确的可发量，比报一个错严重得多。消息里直指 /reconcile。
     */
    @Transactional
    public ScrapResult scrap(Long batchId, String locationCode, int qty, String reason, Long operatorId) {
        requireQty(qty);
        if (reason == null || reason.isBlank()) {
            // 报损是**减少资产**且不可逆的动作，原因是审计与药监检查的唯一线索。
            // 与 V82 拒收原因必填（8012）同一纪律
            throw new HipBizException(E_REASON, "报损原因必填");
        }
        String location = normalizeLocation(locationCode);
        Map<String, Object> b = findBatch(batchId);
        Long drugId = ((Number) b.get("drug_id")).longValue();

        // 批次子账：条件更新带 qty >= ?，扣不动即余额不足（或被并发取走）
        int qtyAfter = applyDelta(batchId, location, -qty);

        // 总账：复用既有的原子条件扣减（DispenseService 同一方法），不另写 SQL
        if (drugRepository.deductStock(drugId, qty) == 0) {
            throw new HipBizException(E_CONCURRENT,
                    "批次余额可扣但药品汇总库存（md_drug.stock）不足，无法报损。"
                    + "这是总账与批次子账的已知漂移：发药出库目前只扣汇总、不扣批次余额，"
                    + "批次层账面会单向偏高。请先查 GET /api/pharm/stock/reconcile 与 /balance 的 drift 字段核对实物。");
        }

        String flowNo = writeFlow(batchId, location, "SCRAP", -qty, qtyAfter,
                reason.trim(), null, null, operatorId, nowMicros());
        int aggAfter = aggregateStock(drugId);
        return new ScrapResult(batchId, (String) b.get("batch_no"), drugId, qty,
                qtyAfter, aggAfter, flowNo, reason.trim());
    }

    // ===================== 3. 收敛接缝：OUT / RET 的批次账写入 =====================
    //
    // 下面两个方法**本版没有调用方**，是给「发药/退药接入批次账」那一步预留的接缝。
    //
    // 为什么现在就写、而不是等到那一版再写：漂移的收敛卡在 DispenseService，而那是既有链路、
    // 不在本车道名下。若只在注释里写一句「后续把发药改成扣批次」，接手的人要同时设计
    // 取批、扣减、流水、并发四件事，等于把整个地基重做一遍。把这两个方法**连测试一起**做完，
    // 收敛就退化成 DispenseService 里加一次调用——一个可评审的小改动，而不是一次重新设计。
    //
    // 两个方法都**刻意不碰 md_drug.stock**：调用方（DispenseService）已经在扣/回补汇总，
    // 这里再动一次就是双扣。职责切分是「谁扣的汇总谁负责，批次子账由本类负责」。

    /**
     * 发药出库：按 {@code pick_rule} 取批、扣批次余额、逐批写 OUT 流水。**不动 md_drug.stock。**
     *
     * <p>全有或全无：配不齐直接抛 {@link #E_SHORT}，不做部分出库——
     * 半张处方发出去而另一半挂在那里，是药房最不愿意收拾的状态。
     *
     * <p>并发：{@link #pick} 是只读快照，取到的批次可能在扣减前被他人取走；
     * 此时 {@link #applyDelta} 的条件更新影响 0 行并抛 {@link #E_SHORT}，整个事务回滚。
     * **宁可整单失败让药师重试，也不能扣穿某个批次**——扣穿会让批次账从此对不回来。
     */
    @Transactional
    public PickResult consumeForDispense(Long drugId, String locationCode, int qty,
                                         String refNo, Long operatorId) {
        String location = normalizeLocation(locationCode);
        PickResult plan = pick(drugId, location, qty);
        if (!plan.satisfied()) {
            throw new HipBizException(E_SHORT, "批次层可用量不足：需 " + qty
                    + "，" + location + " 仅可配 " + plan.allocated() + "（缺 " + plan.shortfall() + "）");
        }
        Instant now = nowMicros();
        List<PickLine> done = new ArrayList<>();
        for (PickLine line : plan.lines()) {
            int after = applyDelta(line.batchId(), location, -line.picked());
            writeFlow(line.batchId(), location, "OUT", -line.picked(), after,
                    null, refNo, null, operatorId, now);
            done.add(new PickLine(line.batchId(), line.batchNo(), line.expireOn(),
                    location, line.available(), line.picked()));
        }
        return new PickResult(plan.rule(), qty, qty, 0, true, List.copyOf(done),
                "已按 " + plan.rule() + " 扣减批次余额并写 OUT 流水；md_drug.stock 由调用方负责，本方法不动它。");
    }

    /**
     * 退药回补到**原批次**：+qty 并写 RET 流水。**不动 md_drug.stock。**
     *
     * <p>必须由调用方传入原发药批次，本方法不猜。退回的药实际来自哪一批，只有发药那一刻知道；
     * 事后按 FEFO 猜一个批次回补，会把 A 批的药记到 B 批账上——**批次追溯从此是错的，
     * 而且错得看不出来**（总数仍然对得上）。召回时按错的批次去找，找不到该找的那盒。
     *
     * <p>前置条件：发药时的批次分配必须**落库**（本版尚无该存储位，见 cross_lane）。
     * 没有那张关联表，本方法拿不到 batchId，退药就只能停在汇总层——
     * 这正是「退药可追溯到原发药批次」这条不变式今天不成立的根因。
     */
    @Transactional
    public int restoreToBatch(Long batchId, String locationCode, int qty, String refNo, Long operatorId) {
        requireQty(qty);
        findBatch(batchId);
        String location = normalizeLocation(locationCode);
        int after = applyDelta(batchId, location, qty);
        writeFlow(batchId, location, "RET", qty, after, null, refNo, null, operatorId, nowMicros());
        return after;
    }

    // ===================== 4. 取批规则（FEFO / FIFO） =====================

    /**
     * 按 {@code pharm.batch.pick_rule} 取批（**只读，不扣减**）。
     *
     * <ul>
     *   <li>{@code FEFO}（默认）先效期先出：效期升序、效期为空排最后、同效期按入库先后；
     *   <li>{@code FIFO} 先进先出：按批次建档顺序（id 升序）。
     * </ul>
     * 坏值（含空、大小写混写以外的任何未知值）**回落 FEFO**，不回落 FIFO——
     * FEFO 让近效期先用完，减少过期报废与过期药发出，临床上是更安全的那个方向。
     *
     * <p>配齐不了不抛错，而是返回 {@code satisfied=false} 与 {@code shortfall}：
     * 取批预览是给药师看的**决策信息**，「差 20 盒」比一个 5408 更有用。
     * 真正的硬拦在写路径（{@link #scrap} 的 5408）。
     */
    public PickResult pick(Long drugId, String locationCode, int qty) {
        requireEnabledDrug(drugId);
        if (qty <= 0) throw new HipBizException(E_QTY, "取批数量必须大于 0");
        String location = normalizeLocation(locationCode);
        String rule = pickRule();
        String order = "FIFO".equals(rule)
                ? "b.id asc"
                // 效期为空的批次排最后：没有效期就无法判定「先到期」，让有效期的先出
                : "case when b.expire_on is null then 1 else 0 end, b.expire_on asc, b.id asc";

        // `order by` 与 order 变量**分两条字符串拼**，不写成文本块尾部 `order by """ + order：
        // Java 文本块会剥掉每行的行尾空白，`order by ` 的尾空格被吃掉后拼出 `order byb.id asc`，
        // 报的是 BadSqlGrammar，看不出根因（本车道实测踩到，由 pickRuleFefo 用例抓出）
        String sql = "select b.id as batch_id, b.batch_no, b.expire_on, s.location_code, s.qty"
                + " from pharm_stock s join pharm_batch b on b.id = s.batch_id"
                + " where b.drug_id = ? and s.location_code = ? and s.qty > 0"
                + " order by " + order;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, drugId, location);

        List<PickLine> lines = new ArrayList<>();
        int remaining = qty;
        for (Map<String, Object> r : rows) {
            if (remaining <= 0) break;
            int available = ((Number) r.get("qty")).intValue();
            int take = Math.min(available, remaining);
            remaining -= take;
            lines.add(new PickLine(((Number) r.get("batch_id")).longValue(),
                    (String) r.get("batch_no"), toLocalDate(r.get("expire_on")),
                    (String) r.get("location_code"), available, take));
        }
        int allocated = qty - remaining;
        return new PickResult(rule, qty, allocated, remaining, remaining == 0, lines,
                "取批预览为**只读**结果，不占用也不预留库存；下达时批次余额可能已被他人取走。"
                + "本版发药链路尚未按批次扣减（见 /balance 的 drift 与 coverage），"
                + "该结果目前仅供药师人工按批次备药与核对，不是自动扣减依据。");
    }

    // ===================== 5. 查询：批次 / 余额 / 流水 / 对账 =====================

    /** 批次查询：可按药品、批号（前缀）、效期上界过滤 */
    public List<Map<String, Object>> batches(Long drugId, String batchNo, Integer expiringInDays, int limit) {
        int size = clampPageSize(limit);
        StringBuilder sql = new StringBuilder("""
                select b.id, b.drug_id, d.name as drug_name, d.code as drug_code, b.batch_no,
                       b.produced_on, b.expire_on, b.supplier, b.purchase_price, b.created_at,
                       coalesce((select sum(s.qty) from pharm_stock s where s.batch_id = b.id), 0) as on_hand
                  from pharm_batch b join md_drug d on d.id = b.drug_id
                 where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (drugId != null) { sql.append(" and b.drug_id = ?"); args.add(drugId); }
        if (batchNo != null && !batchNo.isBlank()) {
            // 前缀匹配而非全模糊：批号是编码，`%X%` 会把 X 出现在中间的无关批次全捞出来，
            // 且用不上 idx_pharm_batch_no
            sql.append(" and b.batch_no like ?"); args.add(batchNo.trim() + "%");
        }
        if (expiringInDays != null) {
            if (expiringInDays < 0 || expiringInDays > 3650) {
                throw new HipBizException(E_QUERY, "效期天数窗口须在 0–3650 之间");
            }
            sql.append(" and b.expire_on is not null and b.expire_on <= ?");
            args.add(java.sql.Date.valueOf(BusinessDates.today().plusDays(expiringInDays)));
        }
        sql.append(" order by case when b.expire_on is null then 1 else 0 end, b.expire_on asc, b.id asc limit ?");
        args.add(size);

        LocalDate today = BusinessDates.today();
        return jdbc.queryForList(sql.toString(), args.toArray()).stream().map(r -> {
            var m = new LinkedHashMap<>(r);
            LocalDate exp = toLocalDate(r.get("expire_on"));
            m.put("daysToExpire", exp == null ? null : ChronoUnit.DAYS.between(today, exp));
            m.put("expiryStatus", expiryStatus(exp, today));
            return (Map<String, Object>) m;
        }).toList();
    }

    /**
     * 按药品的库存视图：**同时给出总账、子账与两者的差**。
     *
     * <p>只给一个数是本车道最容易犯的错。给 batchStock 会让人以为那是可发量，
     * 给 aggregateStock 又丢掉了批次信息。三个数一起给，drift≠0 时带 driftNote 说明成因，
     * 才是这一版能诚实交付的东西。
     */
    public Map<String, Object> balanceByDrug(Long drugId, String locationCode, int limit) {
        int size = clampPageSize(limit);
        StringBuilder sql = new StringBuilder("""
                select d.id as drug_id, d.code as drug_code, d.name as drug_name, d.unit,
                       d.stock as aggregate_stock,
                       coalesce(sum(s.qty), 0)                                as batch_stock,
                       count(distinct case when s.qty > 0 then b.id end)      as live_batches,
                       min(case when s.qty > 0 then b.expire_on end)          as earliest_expire
                  from md_drug d
                  left join pharm_batch b on b.drug_id = d.id
                  left join pharm_stock s on s.batch_id = b.id
                """);
        List<Object> args = new ArrayList<>();
        if (locationCode != null && !locationCode.isBlank()) {
            // 地点过滤放 join 条件而非 where：放 where 会把「该药在本地点无批次」的行整条滤掉，
            // 于是页面上看不见那些只有旧口径库存的药——恰恰是最需要被看见的那批
            sql.append(" and s.location_code = ?");
            args.add(normalizeLocation(locationCode));
        }
        sql.append(" where d.enabled = true");
        if (drugId != null) { sql.append(" and d.id = ?"); args.add(drugId); }
        sql.append(" group by d.id, d.code, d.name, d.unit, d.stock");
        if (drugId == null) {
            // 列表态滤掉两本账都为零的药（全院药典动辄数千条，零库存行会把有货的淹掉）。
            // **必须放 having 而不是拿出来在 Java 里 filter**：Java 侧过滤发生在 limit 之后，
            // 会让一页 100 条返回 7 条，翻页逻辑再也算不对
            sql.append(" having coalesce(sum(s.qty), 0) > 0 or d.stock > 0");
        }
        sql.append(" order by d.code limit ?");
        args.add(size);

        LocalDate today = BusinessDates.today();
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(r -> {
                    int agg = ((Number) r.get("aggregate_stock")).intValue();
                    int bat = ((Number) r.get("batch_stock")).intValue();
                    var m = new LinkedHashMap<String, Object>(r);
                    m.put("drift", bat - agg);
                    m.put("batchManaged", ((Number) r.get("live_batches")).intValue() > 0);
                    LocalDate exp = toLocalDate(r.get("earliest_expire"));
                    m.put("earliestDaysToExpire", exp == null ? null : ChronoUnit.DAYS.between(today, exp));
                    if (bat > agg) {
                        m.put("driftNote", "批次层账面高于药品汇总库存 " + (bat - agg) + "：本版发药出库只扣汇总、"
                                + "不扣批次余额，批次层会单向偏高。可发量以 aggregateStock 为准。");
                    } else if (bat < agg && bat > 0) {
                        m.put("driftNote", "汇总库存高于批次层账面 " + (agg - bat) + "：该药存在未纳入批次管理的存量库存"
                                + "（旧口径入库或期初库存），本版不反推初始批次（那会捏造批号与效期）。");
                    }
                    return (Map<String, Object>) m;
                }).toList();

        var body = new LinkedHashMap<String, Object>();
        body.put("rows", rows);
        body.put("coverage", coverage());
        body.put("caveats", List.of(
                "可发药量的**权威口径仍是 aggregateStock（md_drug.stock）**，发药端 6002 判的是它；"
                + "batchStock 是批次级子账，本版尚未接入发药扣减。",
                "drift = batchStock - aggregateStock。drift>0 是已知的单向偏高（发药只扣汇总）；"
                + "drift<0 是该药还有未纳入批次管理的存量库存。两种都不是数据错误。",
                "**批次层不含任何历史回填**：V147 迁移零条 update/insert，新表建完即空。"
                + "拿 md_drug.stock 反推初始批次会凭空捏造批号与效期，召回时会让人照着假批号下架真药。"));
        return body;
    }

    /** 按批次的余额明细（批次追溯页） */
    public List<Map<String, Object>> balanceByBatch(Long drugId, Long batchId, String locationCode, int limit) {
        int size = clampPageSize(limit);
        StringBuilder sql = new StringBuilder("""
                select s.id, s.batch_id, b.batch_no, b.drug_id, d.name as drug_name,
                       b.produced_on, b.expire_on, b.supplier, b.purchase_price,
                       s.location_code, s.qty, s.updated_at
                  from pharm_stock s
                  join pharm_batch b on b.id = s.batch_id
                  join md_drug d on d.id = b.drug_id
                 where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (drugId != null)  { sql.append(" and b.drug_id = ?"); args.add(drugId); }
        if (batchId != null) { sql.append(" and s.batch_id = ?"); args.add(batchId); }
        if (locationCode != null && !locationCode.isBlank()) {
            sql.append(" and s.location_code = ?"); args.add(normalizeLocation(locationCode));
        }
        sql.append(" order by case when b.expire_on is null then 1 else 0 end, b.expire_on asc, s.id asc limit ?");
        args.add(size);

        LocalDate today = BusinessDates.today();
        return jdbc.queryForList(sql.toString(), args.toArray()).stream().map(r -> {
            var m = new LinkedHashMap<>(r);
            LocalDate exp = toLocalDate(r.get("expire_on"));
            m.put("daysToExpire", exp == null ? null : ChronoUnit.DAYS.between(today, exp));
            m.put("expiryStatus", expiryStatus(exp, today));
            return (Map<String, Object>) m;
        }).toList();
    }

    /** 流水查询：按批次/药品/类型/时间窗 */
    public List<Map<String, Object>> flows(Long drugId, Long batchId, String flowType,
                                           LocalDate from, LocalDate to, int limit) {
        int size = clampPageSize(limit);
        if (from != null && to != null && from.isAfter(to)) {
            throw new HipBizException(E_QUERY, "查询起止日期倒置");
        }
        if (flowType != null && !flowType.isBlank()
                && !List.of("IN", "OUT", "RET", "ADJ", "SCRAP").contains(flowType.toUpperCase(Locale.ROOT))) {
            throw new HipBizException(E_QUERY, "流水类型非法（IN/OUT/RET/ADJ/SCRAP）");
        }
        StringBuilder sql = new StringBuilder("""
                select f.id, f.flow_no, f.batch_id, b.batch_no, b.drug_id, d.name as drug_name,
                       b.expire_on, f.location_code, f.flow_type, f.qty_delta, f.qty_after,
                       f.reason, f.ref_no, f.src_stock_in_id, f.operator_id, f.occurred_at
                  from pharm_stock_flow f
                  join pharm_batch b on b.id = f.batch_id
                  join md_drug d on d.id = b.drug_id
                 where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (drugId != null)  { sql.append(" and b.drug_id = ?"); args.add(drugId); }
        if (batchId != null) { sql.append(" and f.batch_id = ?"); args.add(batchId); }
        if (flowType != null && !flowType.isBlank()) {
            sql.append(" and f.flow_type = ?"); args.add(flowType.toUpperCase(Locale.ROOT));
        }
        // 日窗口用 `>= 起始日 00:00` 与 `< 次日 00:00`：写成 `<= 结束日` 会漏掉当天的全部流水
        if (from != null) { sql.append(" and f.occurred_at >= ?"); args.add(java.sql.Date.valueOf(from)); }
        if (to != null)   { sql.append(" and f.occurred_at <  ?"); args.add(java.sql.Date.valueOf(to.plusDays(1))); }
        sql.append(" order by f.id desc limit ?");
        args.add(size);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 对账：逐 {@code (批次, 地点)} 校验 {@code pharm_stock.qty == sum(flow.qty_delta)}。
     *
     * <p>这是本车道的**自证端点**——「流水是账、库存是余额，余额必须能由流水推出来」
     * 若只写在注释里而没有一条查询去验，它就只是一句话。full outer join 两侧都取，
     * 才能同时抓出「有余额无流水」（余额凭空出现）与「有流水无余额行」（余额行被删）。
     */
    public Map<String, Object> reconcile(Long drugId, int limit) {
        int size = clampPageSize(limit);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select coalesce(s.batch_id, f.batch_id)           as batch_id,
                       coalesce(s.location_code, f.location_code) as location_code,
                       coalesce(s.qty, 0)                         as balance_qty,
                       coalesce(f.flow_sum, 0)                    as flow_sum,
                       coalesce(f.flow_count, 0)                  as flow_count
                  from pharm_stock s
                  full outer join (
                        select batch_id, location_code, sum(qty_delta) as flow_sum, count(*) as flow_count
                          from pharm_stock_flow group by batch_id, location_code
                  ) f on f.batch_id = s.batch_id and f.location_code = s.location_code
                """);
        if (drugId != null) {
            sql.append("""
                     where coalesce(s.batch_id, f.batch_id) in (select id from pharm_batch where drug_id = ?)
                    """);
            args.add(drugId);
        }
        sql.append(" order by 1, 2 limit ?");
        args.add(size);

        List<ReconcileLine> lines = new ArrayList<>();
        int mismatched = 0;
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), args.toArray())) {
            Long bId = r.get("batch_id") == null ? null : ((Number) r.get("batch_id")).longValue();
            int bal = ((Number) r.get("balance_qty")).intValue();
            int sum = ((Number) r.get("flow_sum")).intValue();
            Map<String, Object> b = bId == null ? Map.of() : findBatchOrEmpty(bId);
            boolean ok = bal == sum;
            if (!ok) mismatched++;
            lines.add(new ReconcileLine(bId, (String) b.get("batch_no"),
                    b.get("drug_id") == null ? null : ((Number) b.get("drug_id")).longValue(),
                    (String) b.get("drug_name"), (String) r.get("location_code"),
                    bal, sum, bal - sum, ((Number) r.get("flow_count")).intValue(), ok));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("checked", lines.size());
        body.put("mismatched", mismatched);
        body.put("balanced", mismatched == 0);
        body.put("rows", lines);
        body.put("invariant", "对任意 (batch_id, location_code)：pharm_stock.qty == sum(pharm_stock_flow.qty_delta)。"
                + "本端点校验的是**批次子账内部自洽**，不校验它与 md_drug.stock 的差——"
                + "那个差是已知的设计漂移，见 /balance 的 drift 与 driftNote。");
        return body;
    }

    /**
     * 批次管理覆盖率（v46/v48 的诚实标注三件套之一）。
     *
     * <p>没有这一段，「批次库存都对得上」会被读成「全院库存都对得上」，
     * 而它很可能只是「全院 3 个药启用了批次管理」。
     */
    public Map<String, Object> coverage() {
        Integer enabled = jdbc.queryForObject("select count(*) from md_drug where enabled = true", Integer.class);
        Integer withBatch = jdbc.queryForObject("""
                select count(distinct b.drug_id) from pharm_batch b
                  join md_drug d on d.id = b.drug_id where d.enabled = true
                """, Integer.class);
        Integer withLive = jdbc.queryForObject("""
                select count(distinct b.drug_id) from pharm_batch b
                  join pharm_stock s on s.batch_id = b.id
                  join md_drug d on d.id = b.drug_id
                 where d.enabled = true and s.qty > 0
                """, Integer.class);
        int total = enabled == null ? 0 : enabled;
        int batched = withBatch == null ? 0 : withBatch;
        int live = withLive == null ? 0 : withLive;

        var m = new LinkedHashMap<String, Object>();
        m.put("enabledDrugs", total);
        m.put("drugsWithBatchRecord", batched);
        m.put("drugsWithLiveBatchStock", live);
        m.put("legacyOnlyDrugs", total - batched);
        // 键名带 Rate：这是一个**比率**，不是一个计数。叫 batchCoveragePct 时读的人要先想一下
        // 分母是什么；叫 CoverageRate 就直说了「已纳管药品 / 全部启用药品」
        m.put("batchCoverageRatePct", total == 0 ? 0.0
                : Math.round(batched * 10000.0 / total) / 100.0);
        m.put("rateFormula", "batchCoverageRatePct = drugsWithBatchRecord / enabledDrugs × 100");
        m.put("note", "批次管理是**逐药逐批增量启用**的：只有经 POST /api/pharm/stock/stock-in 入库的批次才有批次层数据。"
                + "V147 迁移零回填，存量库存一粒都不在批次层。走 V82 待验收页入库的量只进 md_drug.stock，"
                + "同样不计入本覆盖率。覆盖率低不代表数据错，代表迁移还没走完。");
        return m;
    }

    /** 当前生效的 gate 与规则配置（前端据此决定是否显示必填星号，避免两边口径各写一份） */
    public Map<String, Object> settings() {
        var m = new LinkedHashMap<String, Object>();
        m.put("batchGate", gate(CFG_GATE_BATCH));
        m.put("expiryGate", gate(CFG_GATE_EXPIRY));
        m.put("pickRule", pickRule());
        m.put("minShelfLifeDays", minShelfLifeDays());
        m.put("nearExpiryDays", nearExpiryDays());
        m.put("defaultLocation", DEFAULT_LOCATION);
        m.put("note", "gate 三态 off|warn|block，**默认 warn、坏值回落 warn**（不回落 off）。"
                + "warn 档不返错误码，问题以返回体 warnings 数组回带、记录照常落库。"
                + "pickRule 不是 gate 而是模式，坏值回落 FEFO。"
                + "nearExpiryDays 复用 V83 的 inv_expiry_warn_days，与近效期预警页同一个数。");
        return m;
    }

    // ===================== gate 判定 =====================

    private void evaluateBatchGate(String batchNo, LocalDate expireOn, List<String> warnings) {
        String g = gate(CFG_GATE_BATCH);
        if ("off".equals(g)) return;
        if (batchNo == null) {
            if ("block".equals(g)) throw new HipBizException(E_BATCH_NO, "批号必填");
            warnings.add("未录批号：该批次无法参与召回追溯与批次级效期预警（gate "
                    + CFG_GATE_BATCH + "=warn，已放行）");
        }
        if (expireOn == null) {
            if ("block".equals(g)) throw new HipBizException(E_EXPIRE_REQ, "有效期至必填");
            warnings.add("未录有效期至：该批次在 FEFO 取批中排到最后、且不进近效期预警（gate "
                    + CFG_GATE_BATCH + "=warn，已放行）");
        }
    }

    private void evaluateExpiryGate(LocalDate expireOn, List<String> warnings) {
        String g = gate(CFG_GATE_EXPIRY);
        if ("off".equals(g) || expireOn == null) return;   // 效期未录由 batch gate 管，此处不重复报
        LocalDate today = BusinessDates.today();
        if (!expireOn.isAfter(today)) {
            // 已过期：含「今天到期」——到期日当天即视为不可用，药事管理上不按当天 23:59 算
            if ("block".equals(g)) {
                throw new HipBizException(E_EXPIRE_BAD, "批次已过期（有效期至 " + expireOn + "），不得入库");
            }
            warnings.add("批次已过期（有效期至 " + expireOn + "）仍被入库（gate "
                    + CFG_GATE_EXPIRY + "=warn，已放行）");
            return;
        }
        int min = minShelfLifeDays();
        long left = ChronoUnit.DAYS.between(today, expireOn);
        if (min > 0 && left < min) {
            if ("block".equals(g)) {
                throw new HipBizException(E_EXPIRE_BAD,
                        "剩余效期 " + left + " 天低于入库最低要求 " + min + " 天（有效期至 " + expireOn + "）");
            }
            warnings.add("剩余效期仅 " + left + " 天，低于入库最低要求 " + min + " 天（gate "
                    + CFG_GATE_EXPIRY + "=warn，已放行）");
        }
    }

    /**
     * gate 三态读取：**坏值回落 warn，不回落 off**。
     *
     * <p>回落 off 会让一处配置笔误（"WARN"、"true"、"1"）静默关掉整段校验，
     * 而 warn 只是多几条提示——两种回落的代价不对称，取代价小的那个。
     */
    public String gate(String key) {
        String v = configReader.get(key, "warn");
        if (v == null) return "warn";
        String n = v.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "off", "warn", "block" -> n;
            default -> "warn";
        };
    }

    /** 取批规则：坏值回落 FEFO（临床更安全的方向），不回落 FIFO */
    public String pickRule() {
        String v = configReader.get(CFG_PICK_RULE, "FEFO");
        if (v == null) return "FEFO";
        String n = v.trim().toUpperCase(Locale.ROOT);
        return "FIFO".equals(n) ? "FIFO" : "FEFO";
    }

    public int minShelfLifeDays() {
        int v = configReader.getInt(CFG_MIN_SHELF, 180);
        // 负值无意义；ConfigReader.getInt 只挡非数字，不挡负数
        return Math.max(0, v);
    }

    public int nearExpiryDays() {
        int v = configReader.getInt(CFG_NEAR_EXPIRY, 90);
        return v <= 0 ? 90 : v;
    }

    // ===================== 内部：批次 upsert 与余额原子更新 =====================

    /**
     * 取或建批次。同一药品同一批号唯一——第二次入同一批号时**比对效期与生产日期**，
     * 不一致直接报 5406 而不是悄悄再建一个批次。
     *
     * <p>「同一批号两个效期」在召回时会让人下架错货，必须在入库那一刻顶回去；
     * 而如果放任它建两个批次，FEFO 会把同一批实物拆成两段按不同效期出库，账再也对不回来。
     *
     * <p>批号为空（batch gate warn 档放行）时**不建批次主数据没法落**——余额与流水都要 batch_id。
     * 故用哨兵批号 {@code NOBATCH-yyyyMMdd}：按入库日归集，同一天的无批号入库并成一个批次。
     * 这不是编造批号（它明写自己不是批号），而是让无批号入库仍能进账并被看见；
     * 其效期为空，FEFO 中排最后，且在批次页以 expiryStatus=UNKNOWN 显示。
     */
    private Long resolveOrCreateBatch(Long drugId, String batchNo, LocalDate producedOn, LocalDate expireOn,
                                      String supplier, BigDecimal purchasePrice, Long operatorId) {
        String key = batchNo != null ? batchNo
                : "NOBATCH-" + BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE);

        List<Map<String, Object>> hit = jdbc.queryForList(
                "select id, produced_on, expire_on from pharm_batch where drug_id = ? and batch_no = ?",
                drugId, key);
        if (!hit.isEmpty()) {
            Map<String, Object> row = hit.get(0);
            LocalDate exExpire = toLocalDate(row.get("expire_on"));
            LocalDate exProduced = toLocalDate(row.get("produced_on"));
            if (batchNo != null && expireOn != null && exExpire != null && !expireOn.equals(exExpire)) {
                throw new HipBizException(E_BATCH_CONF,
                        "批号 " + key + " 已存在且有效期至为 " + exExpire + "，与本次录入的 " + expireOn
                        + " 不一致。同一药品同一批号只能有一个效期——请核对实物包装，"
                        + "若确为不同批次请使用不同批号。");
            }
            if (batchNo != null && producedOn != null && exProduced != null && !producedOn.equals(exProduced)) {
                throw new HipBizException(E_BATCH_CONF,
                        "批号 " + key + " 已存在且生产日期为 " + exProduced + "，与本次录入的 " + producedOn + " 不一致");
            }
            // 已存在批次**不覆盖**效期/生产日期/供应商/进价：批次属性以首次建档为准。
            // 后到货的同批号若属性缺失，覆盖会把已有的正确值抹成 null；若属性冲突，上面已报 5406。
            // 进价随到货波动是常态，历史进价留在流水侧（IN 行的 ref_no 可回溯到 inv_stock_in），
            // 批次主数据上只保留首次建档价，避免"批次成本"这个数在两次到货之间无声地变了
            return ((Number) row.get("id")).longValue();
        }
        jdbc.update("""
                insert into pharm_batch (drug_id, batch_no, produced_on, expire_on, supplier,
                                         purchase_price, created_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (drug_id, batch_no) do nothing
                """, drugId, key,
                producedOn == null ? null : java.sql.Date.valueOf(producedOn),
                expireOn == null ? null : java.sql.Date.valueOf(expireOn),
                supplier, purchasePrice, java.sql.Timestamp.from(nowMicros()), operatorId);
        // on conflict do nothing + 回查：并发同批号入库时，两个事务都可能走到 insert，
        // 让唯一约束去裁决胜负，输的那个回查拿到赢的那行 id——比先查后插的 TOCTOU 窗口可靠
        return jdbc.queryForObject(
                "select id from pharm_batch where drug_id = ? and batch_no = ?", Long.class, drugId, key);
    }

    /**
     * 原子调整 {@code (批次, 地点)} 余额并回读结存。
     *
     * <p>入（delta&gt;0）走 {@code on conflict do update} 原子累加——先查后插在并发下会两行都插、
     * 撞唯一约束报 4090，把一次正常入库变成一个看不懂的错误。
     *
     * <p>出（delta&lt;0）走带 {@code qty >= ?} 谓词的条件更新——读-判-写会让两次并发报损
     * 各扣一次同一批余额（扣穿），与既有 {@code DispenseService.claimDispense} 是同一条纪律。
     *
     * <p>两条路径都用 {@code RETURNING qty} 回读结存：由数据库在更新的同一语句里给出，
     * 而不是应用层自己算。应用层算出来的 qty_after 在并发下会记出一串错误的结存——
     * 余额本身还是对的，账面对而流水错，比两个都错更难查。
     */
    private int applyDelta(Long batchId, String location, int delta) {
        if (delta > 0) {
            List<Integer> after = jdbc.queryForList("""
                    insert into pharm_stock (batch_id, location_code, qty, updated_at)
                    values (?, ?, ?, ?)
                    on conflict (batch_id, location_code)
                    do update set qty = pharm_stock.qty + excluded.qty, updated_at = excluded.updated_at
                    returning qty
                    """, Integer.class, batchId, location, delta, java.sql.Timestamp.from(nowMicros()));
            if (after.isEmpty()) throw new HipBizException(E_CONCURRENT, "批次余额更新失败，请重试");
            return after.get(0);
        }
        int need = -delta;
        List<Integer> after = jdbc.queryForList("""
                update pharm_stock set qty = qty - ?, updated_at = ?
                 where batch_id = ? and location_code = ? and qty >= ?
                returning qty
                """, Integer.class, need, java.sql.Timestamp.from(nowMicros()), batchId, location, need);
        if (after.isEmpty()) {
            Integer cur = jdbc.queryForList(
                    "select qty from pharm_stock where batch_id = ? and location_code = ?",
                    Integer.class, batchId, location).stream().findFirst().orElse(null);
            throw new HipBizException(E_SHORT, cur == null
                    ? "该批次在库房/药柜 " + location + " 没有库存记录"
                    : "批次余额不足：库房/药柜 " + location + " 现存 " + cur + "，本次需 " + need
                      + "（也可能已被并发操作取走，请刷新）");
        }
        return after.get(0);
    }

    /** 写一行批次流水。流水号取序列——nanoTime%1e6 会撞唯一约束并以裸 500 暴露（1.1.7 B-8 学费） */
    private String writeFlow(Long batchId, String location, String type, int delta, int qtyAfter,
                             String reason, String refNo, Long srcStockInId, Long operatorId, Instant at) {
        long seq = jdbc.queryForObject("select nextval('pharm_stock_flow_seq')", Long.class);
        String flowNo = "PF" + BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + seq;
        jdbc.update("""
                insert into pharm_stock_flow (flow_no, batch_id, location_code, flow_type, qty_delta,
                                              qty_after, reason, ref_no, src_stock_in_id, operator_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, flowNo, batchId, location, type, delta, qtyAfter, reason, refNo,
                srcStockInId, operatorId, java.sql.Timestamp.from(at));
        return flowNo;
    }

    // ===================== 内部：校验与小工具 =====================

    private Map<String, Object> requireEnabledDrug(Long drugId) {
        if (drugId == null) throw new HipBizException(E_DRUG, "药品不能为空");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, code, name, unit, enabled, stock from md_drug where id = ?", drugId);
        if (rows.isEmpty()) throw new HipBizException(E_DRUG, "药品不存在");
        Map<String, Object> d = rows.get(0);
        if (!Boolean.TRUE.equals(d.get("enabled"))) {
            // 停用药仍可**报损**（停用往往正因为要清退），但不许再入库——
            // 故停用判定只在入库路径生效，报损路径走 findBatch 不经这里
            throw new HipBizException(E_DRUG, "药品【" + d.get("name") + "】已停用，不得入库");
        }
        return d;
    }

    private void requireQty(int qty) {
        if (qty <= 0) throw new HipBizException(E_QTY, "数量必须大于 0");
        if (qty > MAX_QTY) {
            throw new HipBizException(E_QTY, "单次数量不得超过 " + MAX_QTY + "（防手误多敲 0）");
        }
    }

    private String normalizeLocation(String code) {
        if (code == null || code.isBlank()) return DEFAULT_LOCATION;
        String n = code.trim().toUpperCase(Locale.ROOT);
        if (!LOCATION_PATTERN.matcher(n).matches()) {
            throw new HipBizException(E_LOCATION, "库房/药柜编码非法（限 1–32 位大写字母、数字、下划线或短横）");
        }
        return n;
    }

    private Map<String, Object> findBatch(Long batchId) {
        if (batchId == null) throw new HipBizException(E_BATCH_404, "批次不能为空");
        Map<String, Object> b = findBatchOrEmpty(batchId);
        if (b.isEmpty()) throw new HipBizException(E_BATCH_404, "批次不存在");
        return b;
    }

    private Map<String, Object> findBatchOrEmpty(Long batchId) {
        return jdbc.queryForList("""
                select b.id, b.drug_id, b.batch_no, b.expire_on, b.produced_on, d.name as drug_name
                  from pharm_batch b join md_drug d on d.id = b.drug_id where b.id = ?
                """, batchId).stream().findFirst().orElse(Map.of());
    }

    private int aggregateStock(Long drugId) {
        Integer v = jdbc.queryForObject("select stock from md_drug where id = ?", Integer.class, drugId);
        return v == null ? 0 : v;
    }

    private int clampPageSize(int limit) {
        if (limit <= 0) return 100;
        if (limit > MAX_PAGE_SIZE) throw new HipBizException(E_QUERY, "单页条数不得超过 " + MAX_PAGE_SIZE);
        return limit;
    }

    private Long daysToExpire(LocalDate expireOn) {
        return expireOn == null ? null : ChronoUnit.DAYS.between(BusinessDates.today(), expireOn);
    }

    private String expiryStatus(LocalDate expireOn, LocalDate today) {
        if (expireOn == null) return "UNKNOWN";
        if (!expireOn.isAfter(today)) return "EXPIRED";
        return ChronoUnit.DAYS.between(today, expireOn) <= nearExpiryDays() ? "NEAR_EXPIRY" : "OK";
    }

    /**
     * 全仓时刻纪律：{@code Instant.now().truncatedTo(MICROS)}。
     * PostgreSQL timestamptz 只存到微秒，不截断则写入值与回读值不等——本仓已因此炸过四次。
     */
    private Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof LocalDate d) return d;
        return null;
    }
}
