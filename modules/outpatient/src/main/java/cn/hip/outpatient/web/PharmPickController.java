package cn.hip.outpatient.web;

import cn.hip.outpatient.service.PharmPickService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * v50 车道B：门诊摆药与预调剂工作台。
 *
 * <h2>它在流程里的位置</h2>
 * <pre>
 *   处方收费 ──▶ 待摆药队列 ──▶ 摆药（逐项取药）──▶ 预调剂完成 ──▶ 患者到窗口 ──▶ 发药核对 ──▶ 交付
 *              └──────────────── 本控制器 ────────────────┘          └── 既有 DispenseController ──┘
 * </pre>
 * 本控制器只管<b>发药之前</b>的三步。既有 {@code /api/outpatient/dispense} 的 4 个端点
 * （worklist / dispense / dispensed / return）与 {@code DispenseService}
 * <b>本车道一个字节都没有改</b>，错误码 6001–6006 原样保留。
 *
 * <h2>为什么另开一组端点而不是扩既有发药端点</h2>
 * {@code DispenseController.worklist} 回的四个键正被前端发药页消费，加键改行都是改契约；
 * 且它<b>不知道摆药单的存在</b>——建了摆药单的挂号照样留在它的队列里。两个队列语义本就不同：
 * 那是「等发药的人」，这是「等摆药的活」。
 *
 * <h2>本版如实留的缺口（没有造假实现）</h2>
 * <ul>
 *   <li><b>不碰库存、不改 {@code outp_order.status}</b>——扣库存与置 DISPENSED 仍然只由发药那一步做。
 *       摆药单是旁挂的作业记录，两处都写库存等于双扣。</li>
 *   <li><b>不做拆零、不做批次与效期</b>——{@code md_drug.stock} 是主数据上的一个整数字段，
 *       没有批次维度。摆药明细的数量单位就是医嘱单位（5460–5479 拆零段、5400–5419 库存地基段
 *       各有其车道），本版不假装摆的是「第 3 批次的 2 板」。</li>
 *   <li><b>不做双人核对</b>——那是发药核对环节（5440–5459），不在摆药这一步。</li>
 *   <li><b>不做叫号</b>——窗口在这里只是一个分配目标；叫号屏与呼叫在 5440–5459 段。
 *       {@code pharm_window} 这张窗口字典<b>请直接复用，不要再建第二张</b>。</li>
 *   <li><b>没有前端页面</b>——{@code frontend/shell} 是共用目录，本车道不改，
 *       也因此<b>不插 sys_menu</b>：插一条指向不存在路由的菜单只会让点进去是白屏。</li>
 * </ul>
 *
 * <p>错误码 5420–5439 的完整语义见 {@link PharmPickService} 类注释与 docs/错误码分段.md。
 */
@RestController
@RequestMapping("/api/outpatient/pharmacy/picking")
@PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")   // 与既有 DispenseController 同口径：摆药限药师
@RequiredArgsConstructor
public class PharmPickController {

    private final PharmPickService service;
    private final CurrentUserService currentUserService;

    // ==================================================================
    // 一、待摆药队列与工作台
    // ==================================================================

    /**
     * 待摆药队列：有已收费待发药、且尚未被任何在途摆药单收走的药品的挂号。
     *
     * @param registrationId 可空；给定时只看这一次挂号（工作台点开某患者问「他的药建单了没有」）
     */
    @GetMapping("/queue")
    public R<Map<String, Object>> queue(@RequestParam(required = false) Long registrationId,
                                        @RequestParam(required = false) Integer limit) {
        return call(() -> service.queue(registrationId, limit));
    }

    /**
     * 摆药工作台检索：按状态、按窗口、按建单日期、按关键词（单号/患者姓名/患者号）。
     *
     * @param status 可重复或逗号分隔；取值须在 PENDING/PICKING/PREPARED/DISPENSED/CANCELLED 内
     * @param from   建单日 yyyy-MM-dd，含
     * @param to     建单日 yyyy-MM-dd，含
     */
    @GetMapping("/orders")
    public R<Map<String, Object>> search(@RequestParam(required = false) List<String> status,
                                         @RequestParam(required = false) String windowCode,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Integer limit) {
        return call(() -> service.search(status, windowCode, from, to, keyword, limit));
    }

    /** 摆药单详情：单头 + 明细。明细带 {@code order_status}（该医嘱当前状态），便于识别陈旧单。 */
    @GetMapping("/orders/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return call(() -> service.detail(id));
    }

    // ==================================================================
    // 二、建单与状态流转
    // ==================================================================

    /**
     * 建摆药单：把该挂号下全部尚未被在途单收走的已收费药品医嘱汇成一张单。
     *
     * <p>{@code windowCode} 可空——建单时未必知道去哪个窗口。缺省且
     * {@code pharm.picking.auto_window=least_load} 时按在途单最少的启用窗口自动分配。
     */
    @PostMapping("/orders")
    public R<Map<String, Object>> create(@RequestBody CreateReq req, Authentication auth) {
        return call(() -> service.create(req == null ? null : req.registrationId(),
                req == null ? null : req.windowCode(), currentUserService.idOf(auth)));
    }

    /** 开始摆药：PENDING → PICKING。并发双摆只有一方拿到行，另一方返 5423。 */
    @PostMapping("/orders/{id}/start")
    public R<Map<String, Object>> start(@PathVariable Long id, Authentication auth) {
        return call(() -> service.start(id, currentUserService.idOf(auth)));
    }

    /**
     * 逐项确认摆药。{@code pickedQty} 不传视为按应摆量足额摆出；允许小于应摆量（缺货部分摆），
     * <b>不允许大于</b>（那是配错药，5428）。
     */
    @PutMapping("/orders/{id}/lines/{lineId}/pick")
    public R<Map<String, Object>> pickLine(@PathVariable Long id, @PathVariable Long lineId,
                                           @RequestBody(required = false) PickReq req,
                                           Authentication auth) {
        return call(() -> service.pickLine(id, lineId,
                req == null ? null : req.pickedQty(),
                req == null ? null : req.remark(),
                currentUserService.idOf(auth)));
    }

    /** 撤销逐项确认（拿错了放回去）。 */
    @DeleteMapping("/orders/{id}/lines/{lineId}/pick")
    public R<Map<String, Object>> unpickLine(@PathVariable Long id, @PathVariable Long lineId,
                                             Authentication auth) {
        return call(() -> service.unpickLine(id, lineId, currentUserService.idOf(auth)));
    }

    /**
     * 预调剂完成：PICKING → PREPARED（药已配好，等患者来窗口取）。
     *
     * <p>两道三态 gate 在这里生效，<b>默认都是 warn</b>：warn 档不返错误码、改以返回体的
     * {@code warnings} 数组回带并照常流转；<b>只有 block 档才真的返 5424 / 5425</b>。
     */
    @PostMapping("/orders/{id}/prepared")
    public R<Map<String, Object>> prepared(@PathVariable Long id, Authentication auth) {
        return call(() -> service.prepared(id, currentUserService.idOf(auth)));
    }

    /**
     * 标记已发药（收口这张作业单）：只允许 PREPARED → DISPENSED。
     *
     * <p><b>这一步不扣库存、不改医嘱状态</b>——真正的发药仍走既有
     * {@code POST /api/outpatient/dispense/{registrationId}}。本端点只把作业单收口，
     * 好让该挂号下一次能建新单。两边如何自动衔接见 cross_lane。
     */
    @PostMapping("/orders/{id}/dispensed")
    public R<Map<String, Object>> markDispensed(@PathVariable Long id, Authentication auth) {
        return call(() -> service.markDispensed(id, currentUserService.idOf(auth)));
    }

    /** 作废摆药单（原因必填）。<b>作废不删记录</b>，否则「建了多少张、废了多少张」永远统计不出。 */
    @PostMapping("/orders/{id}/cancel")
    public R<Map<String, Object>> cancel(@PathVariable Long id,
                                         @RequestBody(required = false) CancelReq req,
                                         Authentication auth) {
        return call(() -> service.cancel(id, req == null ? null : req.reason(),
                currentUserService.idOf(auth)));
    }

    /** 分配 / 改派发药窗口。已收口（已发药/已作废）的单不再改派——那是改历史。 */
    @PutMapping("/orders/{id}/window")
    public R<Map<String, Object>> assignWindow(@PathVariable Long id,
                                               @RequestBody(required = false) WindowAssignReq req,
                                               Authentication auth) {
        return call(() -> service.assignWindow(id, req == null ? null : req.windowCode(),
                currentUserService.idOf(auth)));
    }

    // ==================================================================
    // 三、发药窗口字典
    // ==================================================================

    /** 窗口列表，带在途单与已配好单的负载计数——「哪个窗口最忙」是分配窗口时唯一要看的数。 */
    @GetMapping("/windows")
    public R<List<Map<String, Object>>> windows(
            @RequestParam(required = false, defaultValue = "false") boolean includeDisabled) {
        return R.ok(service.windows(includeDisabled));
    }

    /**
     * 新增或修改发药窗口（按 code upsert），限管理员。
     *
     * <p><b>不提供删除</b>：历史摆药单的 {@code window_code} 外键到窗口字典，
     * 删掉窗口会让「当时在哪个窗口配的」永久不可考。停用即可（{@code enabled=false}），
     * 停用后不能再被分配，但历史单照样显示得出来。
     */
    @PostMapping("/windows")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> saveWindow(@RequestBody WindowReq req) {
        return call(() -> service.saveWindow(
                req == null ? null : req.code(),
                req == null ? null : req.name(),
                req == null ? null : req.enabled(),
                req == null ? null : req.sortNo(),
                req == null ? null : req.remark()));
    }

    /**
     * 当前两道 gate 的实际档位。<b>存在的理由是可核验</b>：配置写错时 {@link PharmPickService#gate}
     * 会回落 warn，运维从 sys_config 里看到的是那个写错的值，从这里看到的才是真正生效的档位。
     */
    @GetMapping("/gates")
    public R<Map<String, Object>> gates() {
        var body = new LinkedHashMap<String, Object>();
        body.put(PharmPickService.GATE_LINE_CONFIRM, service.gate(PharmPickService.GATE_LINE_CONFIRM));
        body.put(PharmPickService.GATE_WINDOW, service.gate(PharmPickService.GATE_WINDOW));
        return R.ok(body);
    }

    // ==================================================================
    // 请求体
    // ==================================================================

    public record CreateReq(Long registrationId, String windowCode) {}

    public record PickReq(Integer pickedQty, String remark) {}

    public record CancelReq(String reason) {}

    public record WindowAssignReq(String windowCode) {}

    public record WindowReq(String code, String name, Boolean enabled, Integer sortNo, String remark) {}

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 统一把业务异常转成 {@code R.fail(code, message)}，与既有 {@code DispenseController} 同口径。
     * <b>只接 {@link HipBizException}</b>——其它异常继续上抛给 GlobalExceptionHandler，
     * 在这里一网打尽会把 NPE、SQL 语法错之类的真故障伪装成一个业务码。
     */
    private static <T> R<T> call(Supplier<T> action) {
        try {
            return R.ok(action.get());
        } catch (HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
