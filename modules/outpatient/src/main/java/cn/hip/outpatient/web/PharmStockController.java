package cn.hip.outpatient.web;

import cn.hip.outpatient.service.PharmStockService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v50 车道 A：门诊药房库存地基端点（批次 / 效期 / 批次级出入库流水）。
 *
 * <h2>与既有发药端点的关系：新流程走新端点，既有契约逐字不动</h2>
 * {@code /api/outpatient/dispense} 的 4 个端点与 {@code DispenseService} 的实现
 * **本版一个字符都没改**，其错误码 6001–6006 原样保留。本控制器全部是新增路径，
 * 挂在 {@code /api/pharm/stock} 下，与发药链路无调用关系。
 *
 * <h2>本版能做什么、不能做什么（先说不能做的）</h2>
 * <ul>
 *   <li><b>发药不走批次扣减。</b>{@code DispenseService} 仍只扣 {@code md_drug.stock}。
 *       因此批次层账面会随发药单向偏高——这是设计已知的漂移，
 *       {@code GET /balance} 每行的 {@code drift} 与 {@code driftNote} 如实回报，
 *       不掩盖也不用假数字抹平。收敛要动既有发药链路与其并发抢占模式，须单独评估。
 *   <li><b>{@code GET /pick-preview} 是只读预览</b>，不占用、不预留库存，
 *       目前只供药师人工按批次备药，不是自动扣减依据。
 *   <li><b>不做麻精药品专项</b>（5520–5539）：{@code md_drug} 没有「精麻毒放」属性位，
 *       按药名猜（「含吗啡即毒麻」）是危险假实现——管制药品台账要同时对得上药监与卫健两条线，
 *       猜错的方向是把管制药漏出台账。要做须先补主数据属性位，见 cross_lane。
 *   <li><b>不做包药机/发药机设备直连</b>（硬边界，同玻片打码机）与药品追溯码上传国家平台。
 * </ul>
 *
 * <h2>权限</h2>
 * 与 {@link DispenseController} 逐字对齐 {@code ADMIN + PHARMACIST}。
 * **刻意不放宽到 DOCTOR_OUTP / CASHIER**：入库与报损是直接增减资产的写路径，
 * 报损尤其不可逆；查询侧也带进价与供应商，属采购商务信息，不该由开单侧顺手看到。
 * 若院方要让药库管理员单独持权，须**同时**改本注解与前端菜单授权，两处一起改
 * （v42 吃过一次「菜单给了、接口 1005」的亏）。
 */
@RestController
@RequestMapping("/api/pharm/stock")
@PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
@RequiredArgsConstructor
public class PharmStockController {

    private final PharmStockService pharmStockService;

    // ===================== 写路径 =====================

    /**
     * 入库登记（一步式：登记即入账）。
     *
     * <p>请求体字段：{@code drugId}（必填）、{@code qty}（必填，正整数）、{@code batchNo}、
     * {@code producedOn}、{@code expireOn}（yyyy-MM-dd）、{@code supplier}、
     * {@code purchasePrice}、{@code purchaseNo}（采购单号，可选勾稽）、{@code locationCode}。
     *
     * <p>批号与效期是否必填由 gate {@code pharm.gate.batch.required} 决定（默认 warn）：
     * warn 档**不返错误码**，照常入账并在返回体 {@code warnings} 里回带问题。
     */
    @PostMapping("/stock-in")
    public R<Object> stockIn(@RequestBody Map<String, Object> body, Authentication auth) {
        Long drugId = asLong(body.get("drugId"));
        int qty = asInt(body.get("qty"));
        return R.ok(pharmStockService.stockIn(
                drugId, qty,
                asString(body.get("batchNo")),
                asDate(body.get("producedOn"), "producedOn"),
                asDate(body.get("expireOn"), "expireOn"),
                asString(body.get("supplier")),
                asDecimal(body.get("purchasePrice")),
                asString(body.get("purchaseNo")),
                asString(body.get("locationCode")),
                null));
    }

    /**
     * 报损（破损 / 过期 / 丢失）：批次余额与 {@code md_drug.stock} 同时扣减。
     *
     * <p>请求体：{@code batchId}（必填）、{@code qty}（必填）、{@code reason}（必填）、
     * {@code locationCode}。原因必填与 V82 拒收原因（8012）同一纪律——
     * 减少资产且不可逆的动作，原因是审计与药监检查的唯一线索。
     */
    @PostMapping("/scrap")
    public R<Object> scrap(@RequestBody Map<String, Object> body, Authentication auth) {
        return R.ok(pharmStockService.scrap(
                asLong(body.get("batchId")),
                asString(body.get("locationCode")),
                asInt(body.get("qty")),
                asString(body.get("reason")),
                null));
    }

    // ===================== 读路径 =====================

    /** 批次查询：按药品 / 批号前缀 / 效期天数窗过滤，效期升序（空效期排最后） */
    @GetMapping("/batches")
    public R<Object> batches(@RequestParam(required = false) Long drugId,
                             @RequestParam(required = false) String batchNo,
                             @RequestParam(required = false) Integer expiringInDays,
                             @RequestParam(defaultValue = "100") int limit) {
        return R.ok(pharmStockService.batches(drugId, batchNo, expiringInDays, limit));
    }

    /**
     * 库存查询（按药品）：**同时给出总账、子账与两者的差**。
     *
     * <p>返回体三个数一起给不是啰嗦：只给 {@code batchStock} 会被当成可发量，
     * 只给 {@code aggregateStock} 又丢掉批次信息。另带 {@code coverage} 段——
     * 「批次库存都对得上」很可能只是「全院 3 个药启用了批次管理」。
     */
    @GetMapping("/balance")
    public R<Object> balance(@RequestParam(required = false) Long drugId,
                             @RequestParam(required = false) String locationCode,
                             @RequestParam(defaultValue = "100") int limit) {
        return R.ok(pharmStockService.balanceByDrug(drugId, locationCode, limit));
    }

    /** 库存查询（按批次）：批次追溯页 / 药柜备货明细 */
    @GetMapping("/balance/by-batch")
    public R<Object> balanceByBatch(@RequestParam(required = false) Long drugId,
                                    @RequestParam(required = false) Long batchId,
                                    @RequestParam(required = false) String locationCode,
                                    @RequestParam(defaultValue = "100") int limit) {
        return R.ok(pharmStockService.balanceByBatch(drugId, batchId, locationCode, limit));
    }

    /**
     * 流水查询：按药品 / 批次 / 类型 / 日窗口。
     *
     * <p>日窗口在服务层按 {@code >= from 00:00} 与 {@code < to+1 00:00} 展开——
     * 写成 {@code <= to} 会漏掉结束日当天的全部流水。
     */
    @GetMapping("/flows")
    public R<Object> flows(@RequestParam(required = false) Long drugId,
                           @RequestParam(required = false) Long batchId,
                           @RequestParam(required = false) String flowType,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(defaultValue = "100") int limit) {
        return R.ok(pharmStockService.flows(drugId, batchId, flowType, from, to, limit));
    }

    /**
     * 取批预览（{@code pharm.batch.pick_rule}：FEFO 先效期先出 / FIFO 先进先出）。
     *
     * <p><b>只读</b>：不占用也不预留库存。配不齐不报错，返回 {@code satisfied=false}
     * 与 {@code shortfall}——预览是给药师看的决策信息，「差 20 盒」比一个错误码有用。
     */
    @GetMapping("/pick-preview")
    public R<Object> pickPreview(@RequestParam Long drugId,
                                 @RequestParam int qty,
                                 @RequestParam(required = false) String locationCode) {
        return R.ok(pharmStockService.pick(drugId, locationCode, qty));
    }

    /**
     * 对账：逐 {@code (批次, 地点)} 校验「余额 == 流水累计」。
     *
     * <p>本车道的自证端点。「流水是账、库存是余额，余额必须能由流水推出来」若只写在注释里
     * 而没有一条查询去验，它就只是一句话。校验的是**批次子账内部自洽**，
     * 不校验它与 {@code md_drug.stock} 的差——那个差是已知设计漂移，看 {@code /balance}。
     */
    @GetMapping("/reconcile")
    public R<Object> reconcile(@RequestParam(required = false) Long drugId,
                               @RequestParam(defaultValue = "200") int limit) {
        return R.ok(pharmStockService.reconcile(drugId, limit));
    }

    /** 批次管理覆盖率（诚实标注：覆盖率低不代表数据错，代表迁移还没走完） */
    @GetMapping("/coverage")
    public R<Object> coverage() {
        return R.ok(pharmStockService.coverage());
    }

    /** 当前生效的 gate 与取批规则——前端据此显示必填星号，避免两边口径各写一份 */
    @GetMapping("/settings")
    public R<Object> settings() {
        var m = new LinkedHashMap<String, Object>(pharmStockService.settings());
        m.put("errorCodes", List.of(
                "5400 药品不存在或已停用", "5401 数量非法", "5402 批号必填(block)", "5403 有效期至必填(block)",
                "5404 效期不合格入库(block)", "5405 生产日期晚于有效期至", "5406 同批号效期/生产日期冲突",
                "5407 批次不存在", "5408 批次余额不足", "5409 报损原因必填",
                "5410 并发变化或总账子账漂移", "5411 进价非法", "5412 库房/药柜编码非法", "5413 查询参数非法"));
        return R.ok(m);
    }

    // ===================== 请求体取值（弱类型 Map 入参的守门） =====================
    //
    // 用 Map 而不是 DTO：本控制器与 service 同属新增路径，字段仍会随后续车道调整，
    // 一个 DTO 类要放进 entity/ 包才符合全仓布局，而那不在本车道名下的文件里。
    // 代价是要自己做类型转换——统统在这里收口，转不动一律返 4000（全仓参数兜底码），
    // 不让 ClassCastException 以 500 的形式暴露给前端。

    private static Long asLong(Object v) {
        if (v == null) throw new HipBizException(4000, "参数缺失：期望一个 id");
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new HipBizException(4000, "参数不是合法的 id：" + v);
        }
    }

    private static int asInt(Object v) {
        if (v == null) throw new HipBizException(4000, "参数缺失：期望一个数量");
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new HipBizException(4000, "参数不是合法的数量：" + v);
        }
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal asDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new HipBizException(4000, "进价不是合法数字：" + v);
        }
    }

    /**
     * 日期取值。**只认 ISO {@code yyyy-MM-dd}**，不做多格式兜底猜测——
     * 「2026/03/05」到底是三月五日还是五月三日取决于猜的人，而这里猜错会落进
     * 批次效期，最终表现为一批药提前或延后半年下架。格式不对就返 4000 让前端改。
     */
    private static LocalDate asDate(Object v, String field) {
        String s = asString(v);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            throw new HipBizException(4000, field + " 日期格式须为 yyyy-MM-dd，收到：" + s);
        }
    }
}
