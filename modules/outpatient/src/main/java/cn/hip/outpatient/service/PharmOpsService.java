package cn.hip.outpatient.service;

import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v50 车道 D：门诊药房日常三件事——<b>拆零发药 / 近效期预警 / 拆零余量盘点</b>。
 * 错误码子段 5460–5519（docs/错误码分段.md 先登记后编码）。
 *
 * <h2>开工前的现状复核：任务书三条前提有两条与代码不符</h2>
 * 任务书称「药品侧只有 md_drug 一张表，没有批次、没有效期、没有出入库流水；药品盘点全仓零命中」。
 * 逐文件实测（V7/V81/V82/V83 + {@code platform.masterdata.service.InventoryService}）后是：
 * <ul>
 *   <li>{@code inv_stock_in} 自 V7 起就带 {@code batch_no}/{@code expire_date}，V82 又加了验收三态——
 *       <b>批次与效期是有的</b>，缺的是批次级<b>在库量</b>（入库按批记了，出库不按批记）；</li>
 *   <li>{@code inv_transaction} 自 V7 起就是出入库流水表（IN/OUT/RET/ADJ/STOCKTAKE）——<b>流水是有的</b>；</li>
 *   <li>{@code inv_stock_take}/{@code inv_stock_take_line}（V81）+ {@code InventoryService.confirmStockTake}
 *       已是完整的盘点单：账面快照、实盘录入、差异计算、确认时条件更新调库存并写 STOCKTAKE 流水，
 *       连「账实相符行也要校验账面未漂移」这种细节都做了——<b>整包盘点已有且实现质量不差</b>。</li>
 * </ul>
 * 因此本类<b>刻意不重建盘点单、不另起流水表</b>：同一概念两套真相正是本仓四次撞码/账实不符的同源坑。
 * 真正零命中的只有<b>拆零</b>，以及拆零自己带出来的那个新库存形态——散装余量池的盘点。
 *
 * <h2>三件事各自的落点</h2>
 * <ol>
 *   <li><b>拆零发药</b>（5460–5479）：全新。{@code md_drug.pack_size}/{@code min_unit} 换算系数 +
 *       {@code pharm_split_stock} 余量池 + {@code pharm_split_txn} 余量流水（见 V150 文件头）。
 *       整盒出库仍走既有口径：{@code md_drug.stock} 条件扣减 + {@code inv_transaction} 写 OUT。</li>
 *   <li><b>近效期预警</b>（5480–5499）：列表<b>直接委托</b> {@code InventoryService.expiryWarnings}，
 *       不复制一份估算算法；只新增药房窗口自己的阈值键与发药前的过期批次 gate。</li>
 *   <li><b>盘点</b>（5500–5519）：只盘散装余量。整盒盘点走既有 {@code /api/masterdata/inventory/stock-take*}。</li>
 * </ol>
 *
 * <h2>为什么「未维护换算系数」是硬错误 5460，而不是一个默认 warn 的 gate</h2>
 * 本版铁律要求新增拦截一律三态 gate 默认 warn，理由是「此前从无这些校验，直接 block 会让<b>存量流程</b>
 * 瞬间大面积失败」。这条理由在这里<b>不成立</b>：拆零是全新端点，<b>不存在任何存量拆零流程</b>，
 * 拒绝一次从未被支持过的操作不会让任何既有调用方变红。
 * 反过来，warn 档在这里的语义是「按某个系数把药真的发出去」——没有系数就只能猜 1，
 * 而猜 1 正是把「1 盒」当「1 片」发出去。<b>放行比拦截危险得多的地方，不设放行档。</b>
 * 真正涉及既有流程、且数据质量不可控的那个点位（效期）才做成 gate，见 {@link #GATE_EXPIRY}。
 */
@Service
@RequiredArgsConstructor
public class PharmOpsService {

    // ================= 配置键 =================

    /**
     * 药房窗口近效期天数。V150 seed 成<b>空串</b>，等价于「未设置」，回落到药库巡检阈值
     * {@link #KEY_INV_WARN_DAYS}——默认全院只有一处真相；填了数字才是药房单独覆盖。
     */
    public static final String KEY_WARN_DAYS = "pharm.expiry.warn_days";

    /** V83 已有的药库巡检阈值（默认 90），本类作为回落源读取，不写 */
    public static final String KEY_INV_WARN_DAYS = "inv_expiry_warn_days";

    /**
     * 发药前过期批次 gate（三态，V150 seed = warn）。
     * <ul>
     *   <li>{@code off}——整段跳过，连批次查询都不发；</li>
     *   <li>{@code warn}（默认）——<b>照常发药</b>，把过期批次以 warnings 回带并在
     *       {@code pharm_split_dispense.expiry_warned} 留痕；</li>
     *   <li>{@code block}——返 5481 拒发。</li>
     * </ul>
     * 默认 warn 而非 block：本平台发药此前从不看效期，且 {@code inv_stock_in.expire_date}
     * 对存量批次大量为 NULL（V7 起就可空且无强制）。直接 block 会让「历史批次没填效期 /
     * 早年过期批次从未报损」的药一律发不出去，门诊药房当场停摆。
     * 坏配置回落 warn 而不是 off——宁可多提示，不可静默失效。
     */
    public static final String GATE_EXPIRY = "pharm.gate.expiry.dispense";

    /** 天数兜底与取值域：非法配置一律回落 90，不抛异常（配置坏掉不该让发药窗口打不开） */
    public static final int DEFAULT_WARN_DAYS = 90;
    public static final int MAX_WARN_DAYS = 3650;

    /** 单次拆零数量上限：防误输把 10 打成 100000（真实处方不会有这个量级） */
    public static final int MAX_SPLIT_QTY = 999_999;

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;
    private final cn.hip.platform.masterdata.service.InventoryService inventoryService;

    /** 与 {@code RegistrationService.BizException} 同形，由 Controller 转 {@code R.fail(code, msg)} */
    public static class PharmOpsException extends RuntimeException {
        public final int code;
        public PharmOpsException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static PharmOpsException err(int code, String message) {
        return new PharmOpsException(code, message);
    }

    /** 写入时刻统一口径：本仓已因微秒精度炸过（PG 存微秒，Java Instant 到纳秒，回读比较必不等） */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    // =====================================================================================
    // 一、拆零（5460–5479）
    // =====================================================================================

    /**
     * 拆零可行性与当前余量。
     *
     * @param drugId 药品 id
     */
    public Map<String, Object> splitInfo(Long drugId) {
        DrugSplitMeta meta = loadDrug(drugId);
        var m = new LinkedHashMap<String, Object>();
        m.put("drugId", meta.id);
        m.put("drugName", meta.name);
        m.put("unit", meta.unit);
        m.put("spec", meta.spec);
        m.put("packSize", meta.packSize);
        m.put("minUnit", meta.minUnit);
        m.put("packStock", meta.stock);
        m.put("remainQty", remainQty(drugId));
        String reason = splitBlockReason(meta);
        m.put("splittable", reason == null);
        m.put("reason", reason);
        return m;
    }

    /**
     * 拆零发药：按最小单位发出 {@code qtyMinUnit}，不足部分自动开整盒补入余量池。
     *
     * <p>记账三步，同一事务内完成，任一步失败整体回滚：
     * <ol>
     *   <li>锁余量行（{@code select ... for update}）——把同一药品的并发拆零串起来，
     *       否则「读余量→算开几盒→写余量」的读-判-写会让两个窗口各开一盒，账面凭空少一盒；</li>
     *   <li>开盒：{@code md_drug.stock} <b>条件扣减</b>（{@code where stock >= ?}，与既有
     *       {@code DrugItemRepository.deductStock} 同一纪律），并往 {@code inv_transaction} 写一条
     *       OUT（单位 = 盒），使既有效期估算 {@code sumOutReturnQty} 与库存报表口径不受影响；</li>
     *   <li>余量池 +开盒量 −发出量，每一步各写一条 {@code pharm_split_txn}（单位 = 最小单位）。</li>
     * </ol>
     * 锁顺序固定为「先 pharm_split_stock 后 md_drug」，本类各写路径一致，不会与自身死锁；
     * 既有发药路径只动 md_drug，不构成环。
     *
     * @param drugId         药品 id
     * @param qtyMinUnit     发出数量（最小单位，如「片」）
     * @param registrationId 关联挂号（可空：非处方直接发放 / 病区备用药补充是真实存在的场景）
     * @param orderId        关联处方明细（可空）
     * @param operatorId     操作人 sys_user.id（可空，与既有发药端点同）
     */
    @Transactional
    public Map<String, Object> dispenseSplit(Long drugId, Integer qtyMinUnit,
                                             Long registrationId, Long orderId, Long operatorId) {
        DrugSplitMeta meta = loadDrug(drugId);
        String reason = splitBlockReason(meta);
        if (reason != null) {
            // 5460/5461 的区分见 splitBlockReason：未维护 vs 维护成了不可用的值
            throw err(meta.packSize == null || meta.minUnit == null || meta.minUnit.isBlank() ? 5460 : 5461, reason);
        }
        if (qtyMinUnit == null || qtyMinUnit <= 0 || qtyMinUnit > MAX_SPLIT_QTY) {
            throw err(5462, "拆零数量必须是 1–" + MAX_SPLIT_QTY + " 的正整数（最小单位：" + meta.minUnit + "）");
        }

        // ---- 效期 gate：先判再动数据，block 档不留半条记录 ----
        ExpiryVerdict verdict = checkExpiry(drugId);
        if (verdict.blocked()) {
            throw err(5481, verdict.message());
        }

        int packSize = meta.packSize;
        Instant ts = now();

        // ① 建行（若无）并锁行。on conflict do nothing + 再 select for update：
        //    两个并发首拆同一药品时，insert 的竞争由唯一约束消解，锁再把后手挡在 select 上。
        jdbc.update("insert into pharm_split_stock (drug_id, remain_qty, updated_at) values (?, 0, ?) "
                + "on conflict (drug_id) do nothing", drugId, Timestamp.from(ts));
        Integer lockedRemain = jdbc.queryForObject(
                "select remain_qty from pharm_split_stock where drug_id = ? for update", Integer.class, drugId);
        int remain = lockedRemain == null ? 0 : lockedRemain;

        // ② 缺口向上取整算开盒数
        int shortfall = qtyMinUnit - remain;
        int packsToOpen = shortfall <= 0 ? 0 : (shortfall + packSize - 1) / packSize;

        String dispenseNo = "CL" + BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + nextSeq("pharm_split_dispense_seq");

        if (packsToOpen > 0) {
            // 条件扣减：库存不足时影响 0 行，绝不允许扣成负数
            int n = jdbc.update("update md_drug set stock = stock - ? where id = ? and stock >= ?",
                    packsToOpen, drugId, packsToOpen);
            if (n == 0) {
                throw err(5464, "整包库存不足：需再开 " + packsToOpen + " " + meta.unit
                        + " 才能凑够 " + qtyMinUnit + " " + meta.minUnit
                        + "（当前整包库存 " + meta.stock + " " + meta.unit + "，散装余量 " + remain + " " + meta.minUnit + "）");
            }
            Integer stockAfter = jdbc.queryForObject("select stock from md_drug where id = ?", Integer.class, drugId);
            // 单位 = 盒，与 IN/RET/ADJ/STOCKTAKE 同表同口径；ref_no 用拆零单号，便于与本表勾稽
            jdbc.update("insert into inv_transaction (drug_id, type, qty, stock_after, ref_no, operator_id, created_at) "
                            + "values (?, 'OUT', ?, ?, ?, ?, ?)",
                    drugId, -packsToOpen, stockAfter == null ? 0 : stockAfter, dispenseNo, operatorId, Timestamp.from(ts));

            remain += packsToOpen * packSize;
            insertSplitTxn(drugId, "OPEN", packsToOpen * packSize, remain, packSize, meta.minUnit,
                    dispenseNo, null, operatorId, ts);
        }

        remain -= qtyMinUnit;
        // 到这里 remain 必然 >= 0（packsToOpen 就是按缺口算的），check 约束是最后一道保险
        jdbc.update("update pharm_split_stock set remain_qty = ?, updated_at = ? where drug_id = ?",
                remain, Timestamp.from(ts), drugId);

        Long dispenseId = jdbc.queryForObject("""
                        insert into pharm_split_dispense
                            (dispense_no, registration_id, order_id, drug_id, qty_min_unit, packs_opened,
                             pack_size, min_unit, status, returned_qty, expiry_warned, expiry_note, operator_id, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 'DISPENSED', 0, ?, ?, ?, ?)
                        returning id
                        """, Long.class,
                dispenseNo, registrationId, orderId, drugId, qtyMinUnit, packsToOpen,
                packSize, meta.minUnit, verdict.warned(), verdict.warned() ? trim(verdict.message(), 255) : null,
                operatorId, Timestamp.from(ts));

        insertSplitTxn(drugId, "OUT", -qtyMinUnit, remain, packSize, meta.minUnit,
                dispenseNo, dispenseId, operatorId, ts);

        var m = new LinkedHashMap<String, Object>();
        m.put("dispenseId", dispenseId);
        m.put("dispenseNo", dispenseNo);
        m.put("drugId", drugId);
        m.put("drugName", meta.name);
        m.put("qtyMinUnit", qtyMinUnit);
        m.put("minUnit", meta.minUnit);
        m.put("packSize", packSize);
        m.put("packsOpened", packsToOpen);
        m.put("remainQty", remain);
        m.put("dispensedAt", ts);
        m.put("expiryGate", verdict.gate());
        m.put("warnings", verdict.warnings());
        return m;
    }

    /**
     * 拆零退回：把已发出的散装退回余量池（支持部分退）。
     *
     * <p><b>刻意不把整盒退回 md_drug.stock</b>：盒一旦拆开就回不去了，退回来的散装仍是散装。
     * 把它折算成盒补回整包库存，会让「整包库存 12 盒」这个数字里混进一堆拆过的盒，
     * 下一次开包时又被当整盒开——账面数量对、实物形态错。散装退回只回余量池，由盘点兜底。
     */
    @Transactional
    public Map<String, Object> returnSplit(Long dispenseId, Integer qtyMinUnit, Long operatorId) {
        var rows = jdbc.queryForList("""
                select id, dispense_no, drug_id, qty_min_unit, returned_qty, pack_size, min_unit, status
                  from pharm_split_dispense where id = ? for update
                """, dispenseId);
        if (rows.isEmpty()) {
            throw err(5466, "拆零发药记录不存在");
        }
        var row = rows.get(0);
        String status = (String) row.get("status");
        if (!"DISPENSED".equals(status) && !"PART_RETURNED".equals(status)) {
            throw err(5467, "拆零发药记录当前状态（" + status + "）不允许退回");
        }
        int dispensed = intOf(row.get("qty_min_unit"));
        int alreadyReturned = intOf(row.get("returned_qty"));
        int returnable = dispensed - alreadyReturned;
        if (qtyMinUnit == null || qtyMinUnit <= 0 || qtyMinUnit > returnable) {
            throw err(5468, "退回数量必须是 1–" + returnable + " 的正整数（本次已发 " + dispensed
                    + "，已退 " + alreadyReturned + "）");
        }

        Long drugId = longOf(row.get("drug_id"));
        int packSize = intOf(row.get("pack_size"));
        String minUnit = (String) row.get("min_unit");
        String dispenseNo = (String) row.get("dispense_no");
        Instant ts = now();

        jdbc.update("insert into pharm_split_stock (drug_id, remain_qty, updated_at) values (?, 0, ?) "
                + "on conflict (drug_id) do nothing", drugId, Timestamp.from(ts));
        Integer locked = jdbc.queryForObject(
                "select remain_qty from pharm_split_stock where drug_id = ? for update", Integer.class, drugId);
        int remain = (locked == null ? 0 : locked) + qtyMinUnit;
        jdbc.update("update pharm_split_stock set remain_qty = ?, updated_at = ? where drug_id = ?",
                remain, Timestamp.from(ts), drugId);

        int totalReturned = alreadyReturned + qtyMinUnit;
        String newStatus = totalReturned >= dispensed ? "RETURNED" : "PART_RETURNED";
        jdbc.update("update pharm_split_dispense set returned_qty = ?, status = ? where id = ?",
                totalReturned, newStatus, dispenseId);

        insertSplitTxn(drugId, "RET", qtyMinUnit, remain, packSize, minUnit,
                dispenseNo, dispenseId, operatorId, ts);

        var m = new LinkedHashMap<String, Object>();
        m.put("dispenseId", dispenseId);
        m.put("dispenseNo", dispenseNo);
        m.put("returnedQty", totalReturned);
        m.put("status", newStatus);
        m.put("remainQty", remain);
        return m;
    }

    /** 拆零发药记录查询（退回入口）：按挂号或按药品，二者皆空则取最近 100 条 */
    public List<Map<String, Object>> splitDispenses(Long registrationId, Long drugId) {
        StringBuilder sql = new StringBuilder("""
                select d.id, d.dispense_no, d.registration_id, d.order_id, d.drug_id, g.name as drug_name,
                       d.qty_min_unit, d.returned_qty, d.packs_opened, d.pack_size, d.min_unit,
                       d.status, d.expiry_warned, d.expiry_note, d.created_at
                  from pharm_split_dispense d join md_drug g on g.id = d.drug_id
                 where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (registrationId != null) {
            sql.append(" and d.registration_id = ?");
            args.add(registrationId);
        }
        if (drugId != null) {
            sql.append(" and d.drug_id = ?");
            args.add(drugId);
        }
        sql.append(" order by d.id desc limit 100");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 某药品的散装余量（无行即 0） */
    public int remainQty(Long drugId) {
        Integer n = jdbc.queryForObject(
                "select coalesce((select remain_qty from pharm_split_stock where drug_id = ?), 0)",
                Integer.class, drugId);
        return n == null ? 0 : n;
    }

    private void insertSplitTxn(Long drugId, String type, int qty, int remainAfter, int packSize,
                                String minUnit, String refNo, Long dispenseId, Long operatorId, Instant ts) {
        jdbc.update("""
                insert into pharm_split_txn
                    (drug_id, type, qty, remain_after, pack_size, min_unit, ref_no, dispense_id, operator_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, drugId, type, qty, remainAfter, packSize, minUnit, refNo, dispenseId, operatorId, Timestamp.from(ts));
    }

    /** 拆零余量流水（最小单位），按药品倒序 */
    public List<Map<String, Object>> splitTxns(Long drugId, int limit) {
        return jdbc.queryForList("""
                select id, drug_id, type, qty, remain_after, pack_size, min_unit, ref_no, dispense_id, created_at
                  from pharm_split_txn where drug_id = ? order by id desc limit ?
                """, drugId, Math.max(1, Math.min(limit, 500)));
    }

    // ---- 药品换算元数据 ----

    private record DrugSplitMeta(Long id, String name, String unit, String spec, Integer packSize,
                                 String minUnit, int stock, boolean enabled) {}

    private DrugSplitMeta loadDrug(Long drugId) {
        if (drugId == null) throw err(5463, "药品不存在或已停用");
        var rows = jdbc.queryForList(
                "select id, name, unit, spec, pack_size, min_unit, stock, enabled from md_drug where id = ?", drugId);
        if (rows.isEmpty()) throw err(5463, "药品不存在或已停用");
        var r = rows.get(0);
        boolean enabled = Boolean.TRUE.equals(r.get("enabled"));
        if (!enabled) throw err(5463, "药品【" + r.get("name") + "】已停用，不能发药");
        return new DrugSplitMeta(longOf(r.get("id")), (String) r.get("name"), (String) r.get("unit"),
                (String) r.get("spec"), r.get("pack_size") == null ? null : intOf(r.get("pack_size")),
                (String) r.get("min_unit"), intOf(r.get("stock")), true);
    }

    /**
     * 不可拆零的原因；可拆零返回 null。
     *
     * <p>{@code pack_size == 1} 单独判：那是「一盒就是一片」的药，拆零无意义，
     * 但更常见的是<b>维护时图省事填的 1</b>——放行等于按 1 折算，与不维护同样危险，所以一并拒（5461）。
     */
    private String splitBlockReason(DrugSplitMeta meta) {
        if (meta.packSize == null || meta.minUnit == null || meta.minUnit.isBlank()) {
            return "药品【" + meta.name + "】未维护拆零换算系数（pack_size/min_unit），不允许拆零。"
                    + "请先在药品主数据维护「1 " + meta.unit + " = 多少最小单位」——"
                    + "系统不按规格文本推断，猜错一位就是把 1 " + meta.unit + " 当 1 片发出去";
        }
        if (meta.packSize <= 1) {
            return "药品【" + meta.name + "】的拆零换算系数为 " + meta.packSize + "，不成立（须 >1）。"
                    + "若该药本就不可拆零，请清空 pack_size 而不是填 1";
        }
        return null;
    }

    // =====================================================================================
    // 二、近效期预警（5480–5499）
    // =====================================================================================

    /**
     * 生效的预警天数：{@link #KEY_WARN_DAYS} →（空/非法则）{@link #KEY_INV_WARN_DAYS} →
     * {@value #DEFAULT_WARN_DAYS}。返回值连同回落原因一起给 {@link #expiryWarnings(Integer)} 的 caveats。
     */
    public record WarnDays(int days, String source, List<String> caveats) {}

    public WarnDays resolveWarnDays() {
        List<String> caveats = new ArrayList<>();
        String raw = configReader.get(KEY_WARN_DAYS, null);
        Integer days = parseDays(raw);
        if (days != null) {
            return new WarnDays(days, KEY_WARN_DAYS, caveats);
        }
        if (raw != null && !raw.isBlank()) {
            caveats.add(KEY_WARN_DAYS + " 配置值「" + raw + "」非法（须 1–" + MAX_WARN_DAYS
                    + " 的整数），已回落到 " + KEY_INV_WARN_DAYS);
        }
        String invRaw = configReader.get(KEY_INV_WARN_DAYS, null);
        days = parseDays(invRaw);
        if (days != null) {
            return new WarnDays(days, KEY_INV_WARN_DAYS, caveats);
        }
        if (invRaw != null && !invRaw.isBlank()) {
            caveats.add(KEY_INV_WARN_DAYS + " 配置值「" + invRaw + "」非法，已回落内置默认值");
        }
        return new WarnDays(DEFAULT_WARN_DAYS, "default", caveats);
    }

    private Integer parseDays(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= 1 && v <= MAX_WARN_DAYS ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 近效期批次列表。
     *
     * <p><b>直接委托 {@code InventoryService.expiryWarnings}，不复制一份估算算法</b>：
     * 那里的口径（消耗从最晚效期批次起分摊，使近效期批次估算偏保守、宁可多报不漏报）是第七轮审阅
     * 改出来的结论，还带着三条「已知偏差」的注释。药房再写一份必然与药库分叉，
     * 同一批药在两个页面显示不同的在库量，谁也说不清哪个对。
     *
     * @param days 显式指定的天数；为空则按 {@link #resolveWarnDays()} 解析配置
     */
    public Map<String, Object> expiryWarnings(Integer days) {
        WarnDays resolved;
        if (days == null) {
            resolved = resolveWarnDays();
        } else {
            if (days < 1 || days > MAX_WARN_DAYS) {
                throw err(5480, "预警天数须为 1–" + MAX_WARN_DAYS + " 的整数");
            }
            resolved = new WarnDays(days, "param", List.of());
        }
        int d = resolved.days();
        var list = inventoryService.expiryWarnings(d).stream().map(w -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("drugId", w.drugId());
            m.put("drugName", w.drugName());
            m.put("stockInId", w.stockInId());
            m.put("batchNo", w.batchNo());
            m.put("expireDate", w.expireDate());
            m.put("daysToExpire", w.daysToExpire());
            m.put("batchQty", w.batchQty());
            m.put("estimatedRemaining", w.estimatedRemaining());
            m.put("status", w.status());
            m.put("splitRemainQty", remainQty(w.drugId()));
            return m;
        }).toList();

        var out = new LinkedHashMap<String, Object>();
        out.put("warnDays", d);
        out.put("warnDaysSource", resolved.source());
        out.put("gate", gate());
        out.put("rows", list);
        out.put("expiredCount", list.stream().filter(r -> "EXPIRED".equals(r.get("status"))).count());
        List<String> caveats = new ArrayList<>(resolved.caveats());
        caveats.add("在库量为**估算值**：本仓出库不按批次记账（批次级在库量待 Lane A 的批次表落地），"
                + "此处按「消耗先扣远效期批次」分摊入库量得出，刻意偏保守（宁可多报不漏报），仅供药师人工复核");
        caveats.add("散装余量（splitRemainQty）不参与批次分摊：拆开的盒来自哪个批次本仓没有记录位");
        out.put("caveats", caveats);
        return out;
    }

    /** 当前 gate 档位；未知值回落 warn（不是 off——宁可多提示，不可静默失效） */
    public String gate() {
        String v = configReader.get(GATE_EXPIRY, "warn");
        return switch (v == null ? "" : v.trim().toLowerCase()) {
            case "off" -> "off";
            case "block" -> "block";
            default -> "warn";
        };
    }

    /** 效期判定结果：gate 档位 + 是否拦下 + 面向药师的可读提示 */
    public record ExpiryVerdict(String gate, boolean blocked, boolean warned, List<String> warnings) {
        public String message() {
            return warnings.isEmpty() ? "" : String.join("；", warnings);
        }
    }

    /**
     * 单药发药前的过期批次判定。
     *
     * <p><b>为什么这里自己算而不复用 {@code InventoryService.expiryWarnings}</b>：
     * 那个方法是<b>全库扫描</b>——先查全部近效期批次，再对涉及的每个药品各发 2 条查询
     * （批次列表 + 出退合计），总计 1+2N 条。放进「每次发药调一次」的路径，
     * 门诊药房高峰期会被它拖垮。这里只针对一个药品，固定 2 条查询。
     *
     * <p><b>口径与它逐字同源，必须同步改</b>：消耗从<b>最晚效期批次</b>起扣减
     * （不是 FEFO 方向），使近效期/已过期批次的估算在库量偏大——宁可多报不漏报。
     * 若哪天把那边的分摊方向改回 FEFO，这里必须一并改，否则同一批药在药库页与药房窗口结论相反。
     *
     * <p>只看<b>已过期</b>（expire_date &lt; 今天）且估算仍在架的批次。近效期（未过期）只在列表页提示，
     * 不进发药拦截——把「还有 80 天到期」也拦下来，warn 会刷屏、block 会停摆。
     */
    public ExpiryVerdict checkExpiry(Long drugId) {
        String gate = gate();
        if ("off".equals(gate)) {
            return new ExpiryVerdict("off", false, false, List.of());
        }
        LocalDate today = BusinessDates.today();
        // ① 该药已验收批次，效期升序（null 效期排最后：没填效期的批次判不了，也不该因此拦发药）
        var batches = jdbc.queryForList("""
                select id, batch_no, expire_date, qty
                  from inv_stock_in
                 where drug_id = ? and accept_status = 'ACCEPTED'
                 order by expire_date asc nulls last, id asc
                """, drugId);
        if (batches.isEmpty()) {
            return new ExpiryVerdict(gate, false, false, List.of());
        }
        // ② 净消耗（盒）：OUT 存负、RET 存正，故净耗 = -合计；与 InvTransactionRepository.sumOutReturnQty 同口径
        Long signed = jdbc.queryForObject(
                "select coalesce(sum(qty), 0) from inv_transaction where drug_id = ? and type in ('OUT', 'RET')",
                Long.class, drugId);
        long remainingConsume = Math.max(0, -(signed == null ? 0 : signed));

        // ③ 从最晚效期批次起扣消耗（逆序遍历），近效期批次因此尽量不被「消耗光」
        int[] est = new int[batches.size()];
        for (int i = batches.size() - 1; i >= 0; i--) {
            int q = intOf(batches.get(i).get("qty"));
            long alloc = Math.min(q, remainingConsume);
            est[i] = (int) (q - alloc);
            remainingConsume -= alloc;
        }

        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            Object ed = batches.get(i).get("expire_date");
            if (ed == null || est[i] <= 0) continue;
            LocalDate expire = toLocalDate(ed);
            if (expire == null || !expire.isBefore(today)) continue;
            warnings.add("批号 " + nz(batches.get(i).get("batch_no"), "(未填)") + " 已于 " + expire
                    + " 过期，估算仍有 " + est[i] + " 在架，请先隔离报损后再发药");
        }
        if (warnings.isEmpty()) {
            return new ExpiryVerdict(gate, false, false, List.of());
        }
        boolean blocked = "block".equals(gate);
        return new ExpiryVerdict(gate, blocked, !blocked, warnings);
    }

    /** 发药前效期校验的独立入口：供发药主链路（Lane A/B）在自己的端点里调用，无需依赖本类的拆零逻辑 */
    public Map<String, Object> expiryCheck(Long drugId) {
        loadDrug(drugId);   // 药品不存在/停用直接 5463，避免调用方拿到「无过期批次」的假放行
        ExpiryVerdict v = checkExpiry(drugId);
        var m = new LinkedHashMap<String, Object>();
        m.put("drugId", drugId);
        m.put("gate", v.gate());
        m.put("blocked", v.blocked());
        m.put("warnings", v.warnings());
        return m;
    }

    // =====================================================================================
    // 三、拆零余量盘点（5500–5519）
    // =====================================================================================
    //
    // 【范围声明】本节只盘**散装余量**。整包盘点已由 V81 的 inv_stock_take 完成
    // （InventoryService.createStockTake/enterCounts/confirmStockTake），本类不重建、不代理、不覆盖。
    // 之所以还要一套：拆零余量是本版新造出来的库存形态，V81 盘的是 md_drug.stock（整包数），
    // 盘不到散装；不给它配盘点，等于新增一个永远对不上账的口袋。

    /** 建拆零盘点单：对给定药品逐一快照当前散装余量为账面数（从未拆过的药账面记 0，允许盘盈） */
    @Transactional
    public Map<String, Object> createSplitTake(List<Long> drugIds, String remark, Long operatorId) {
        if (drugIds == null || drugIds.isEmpty()) {
            throw err(5503, "盘点单至少需选择一种药品");
        }
        Instant ts = now();
        String takeNo = "CLPD" + BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + nextSeq("pharm_split_take_seq");
        Long takeId = jdbc.queryForObject("""
                insert into pharm_split_take (take_no, status, remark, operator_id, created_at)
                values (?, 'DRAFT', ?, ?, ?) returning id
                """, Long.class, takeNo, trim(remark, 255), operatorId, Timestamp.from(ts));
        for (Long drugId : drugIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            loadDrugForTake(drugId);
            jdbc.update("""
                    insert into pharm_split_take_line (take_id, drug_id, book_qty, created_at)
                    values (?, ?, ?, ?)
                    """, takeId, drugId, remainQty(drugId), Timestamp.from(ts));
        }
        return splitTakeView(takeId);
    }

    /** 批量录实盘数：命中行更新，未建行的药品按当前余量补建行后再录（upsert，与 V81 同语义） */
    @Transactional
    public Map<String, Object> enterSplitCounts(Long takeId, List<Map<String, Object>> entries, Long operatorId) {
        requireDraft(takeId);
        if (entries != null) {
            Instant ts = now();
            for (var e : entries) {
                Long drugId = longOf(e.get("drugId"));
                Object aq = e.get("actualQty");
                Integer actual = aq == null ? null : intOf(aq);
                if (drugId == null) throw err(5502, "盘点行缺少药品 id");
                if (actual != null && actual < 0) throw err(5502, "实盘数不能为负");
                loadDrugForTake(drugId);
                int n = jdbc.update("update pharm_split_take_line set actual_qty = ? where take_id = ? and drug_id = ?",
                        actual, takeId, drugId);
                if (n == 0) {
                    jdbc.update("""
                            insert into pharm_split_take_line (take_id, drug_id, book_qty, actual_qty, created_at)
                            values (?, ?, ?, ?, ?)
                            """, takeId, drugId, remainQty(drugId), actual, Timestamp.from(ts));
                }
            }
        }
        return splitTakeView(takeId);
    }

    /**
     * 确认盘点：对每条已录实盘数的行，按「账面 = 建单时快照」<b>条件更新</b>余量并写 TAKEADJ 流水。
     *
     * <p>三条纪律照抄 V81 的教训，一条不减：
     * <ol>
     *   <li><b>先抢状态再动数</b>——{@code DRAFT→CONFIRMED} 原子抢占，
     *       防 confirm×cancel 并发下「作废单却动了余量」；</li>
     *   <li><b>条件更新 + 判行数</b>——余量在盘点期间被并发拆零改过就整单回滚报 5504，
     *       迫使重新盘点，否则期间正常发出的散装会被误记成盘亏；</li>
     *   <li><b>差异为 0 的行也要校验账面未漂移</b>——直接跳过会把「窗口内被改动过又恰好改回来 /
     *       被改动过但账面快照已失效」的行记成「账实相符」，破坏审计口径。用零位移条件更新做存在性校验。</li>
     * </ol>
     * <b>余量绝不直接赋值了事</b>：每一次调整都写一条 {@code pharm_split_txn} TAKEADJ，
     * 账实不符时能沿流水回溯到是哪张盘点单调的。
     */
    @Transactional
    public Map<String, Object> confirmSplitTake(Long takeId, Long operatorId) {
        var head = jdbc.queryForList("select id, take_no, status from pharm_split_take where id = ?", takeId);
        if (head.isEmpty()) throw err(5500, "拆零盘点单不存在");
        String takeNo = (String) head.get(0).get("take_no");
        if (jdbc.update("update pharm_split_take set status = 'CONFIRMED' where id = ? and status = 'DRAFT'", takeId) == 0) {
            throw err(5501, "拆零盘点单非草稿状态，不能确认（可能已被他人确认或作废）");
        }
        var lines = jdbc.queryForList(
                "select id, drug_id, book_qty, actual_qty from pharm_split_take_line where take_id = ? order by id", takeId);
        var counted = lines.stream().filter(l -> l.get("actual_qty") != null).toList();
        if (counted.isEmpty()) {
            throw err(5505, "盘点单没有已录实盘数的盘点行，无法确认");
        }
        Instant ts = now();
        for (var l : counted) {
            Long drugId = longOf(l.get("drug_id"));
            int book = intOf(l.get("book_qty"));
            int actual = intOf(l.get("actual_qty"));
            // 账面 0 且从未拆过的药可能根本没有余量行：先补 0 行，条件更新才有落点（盘盈场景）
            jdbc.update("insert into pharm_split_stock (drug_id, remain_qty, updated_at) values (?, 0, ?) "
                    + "on conflict (drug_id) do nothing", drugId, Timestamp.from(ts));
            int n = jdbc.update("update pharm_split_stock set remain_qty = ?, updated_at = ? "
                            + "where drug_id = ? and remain_qty = ?",
                    actual, Timestamp.from(ts), drugId, book);
            if (n == 0) {
                String name = drugNameOf(drugId);
                throw err(5504, "药品【" + name + "】的拆零余量在盘点期间已变化（有拆零发出或退回），请重新盘点");
            }
            if (actual == book) continue;   // 账实相符且经上面的零位移条件更新证明未漂移：不调库也不写流水
            insertSplitTxn(drugId, "TAKEADJ", actual - book, actual, packSizeOf(drugId), minUnitOf(drugId),
                    takeNo, null, operatorId, ts);
        }
        jdbc.update("update pharm_split_take set confirmed_at = ? where id = ?", Timestamp.from(ts), takeId);
        return splitTakeView(takeId);
    }

    /** 作废盘点单（仅草稿可作废，与确认同一抢占纪律） */
    @Transactional
    public Map<String, Object> cancelSplitTake(Long takeId) {
        if (jdbc.queryForList("select id from pharm_split_take where id = ?", takeId).isEmpty()) {
            throw err(5500, "拆零盘点单不存在");
        }
        if (jdbc.update("update pharm_split_take set status = 'CANCELLED' where id = ? and status = 'DRAFT'", takeId) == 0) {
            throw err(5501, "拆零盘点单非草稿状态，不能作废");
        }
        return splitTakeView(takeId);
    }

    public Map<String, Object> splitTakeView(Long takeId) {
        var head = jdbc.queryForList(
                "select id, take_no, status, remark, operator_id, created_at, confirmed_at from pharm_split_take where id = ?",
                takeId);
        if (head.isEmpty()) throw err(5500, "拆零盘点单不存在");
        var lines = jdbc.queryForList("""
                select l.id, l.drug_id, g.name as drug_name, g.min_unit, l.book_qty, l.actual_qty
                  from pharm_split_take_line l join md_drug g on g.id = l.drug_id
                 where l.take_id = ? order by l.id
                """, takeId);
        List<Map<String, Object>> lineViews = new ArrayList<>();
        int counted = 0, gain = 0, loss = 0, net = 0;
        for (var l : lines) {
            var m = new LinkedHashMap<>(l);
            Integer diff = l.get("actual_qty") == null ? null : intOf(l.get("actual_qty")) - intOf(l.get("book_qty"));
            m.put("diff", diff);
            if (diff != null) {
                counted++;
                net += diff;
                if (diff > 0) gain++;
                else if (diff < 0) loss++;
            }
            lineViews.add(m);
        }
        var out = new LinkedHashMap<String, Object>(head.get(0));
        out.put("lines", lineViews);
        out.put("lineCount", lines.size());
        out.put("countedLines", counted);
        out.put("gainLines", gain);
        out.put("lossLines", loss);
        out.put("netDiff", net);
        out.put("scope", "SPLIT_REMAIN_ONLY");
        out.put("scopeNote", "本单只盘拆零散装余量（最小单位）。整包库存盘点走 /api/masterdata/inventory/stock-take（V81），两者不重叠");
        return out;
    }

    public List<Map<String, Object>> recentSplitTakes() {
        return jdbc.queryForList("""
                select t.id, t.take_no, t.status, t.remark, t.created_at, t.confirmed_at,
                       (select count(*) from pharm_split_take_line l where l.take_id = t.id) as line_count
                  from pharm_split_take t order by t.id desc limit 50
                """);
    }

    private void requireDraft(Long takeId) {
        var rows = jdbc.queryForList("select status from pharm_split_take where id = ?", takeId);
        if (rows.isEmpty()) throw err(5500, "拆零盘点单不存在");
        if (!"DRAFT".equals(rows.get(0).get("status"))) {
            throw err(5501, "拆零盘点单非草稿状态，不能录入实盘数");
        }
    }

    /**
     * 盘点行的药品校验：<b>不校验 enabled</b>——停用药的散装余量照样在架上、照样要盘。
     * 盘点是账实对账，把停用药排除在外正好漏掉最容易积压的那批。
     */
    private void loadDrugForTake(Long drugId) {
        if (jdbc.queryForList("select id from md_drug where id = ?", drugId).isEmpty()) {
            throw err(5463, "药品不存在（id=" + drugId + "）");
        }
    }

    // =====================================================================================
    // 内部工具
    // =====================================================================================

    private long nextSeq(String seq) {
        Long n = jdbc.queryForObject("select nextval('" + seq + "')", Long.class);
        return n == null ? 0 : n;
    }

    private String drugNameOf(Long drugId) {
        var rows = jdbc.queryForList("select name from md_drug where id = ?", drugId);
        return rows.isEmpty() ? String.valueOf(drugId) : String.valueOf(rows.get(0).get("name"));
    }

    /**
     * 盘点调整流水里的换算系数快照。药品未维护 pack_size 时记 0——
     * 这种药本来就发不出拆零（5460），但架上仍可能有历史散装被盘出来，
     * 此时<b>不能编一个系数</b>：0 就是「当时没有系数」，与真实系数 1 区分得开。
     */
    private int packSizeOf(Long drugId) {
        Integer n = jdbc.queryForObject("select coalesce(pack_size, 0) from md_drug where id = ?", Integer.class, drugId);
        return n == null ? 0 : n;
    }

    private String minUnitOf(Long drugId) {
        var rows = jdbc.queryForList("select min_unit from md_drug where id = ?", drugId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("min_unit");
    }

    private static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static Long longOf(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static String nz(Object o, String fallback) {
        return o == null || String.valueOf(o).isBlank() ? fallback : String.valueOf(o);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** JdbcTemplate 对 date 列可能给 java.sql.Date 或 LocalDate，两种都收 */
    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }
}
