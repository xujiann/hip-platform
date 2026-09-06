package cn.hip.outpatient.web;

import cn.hip.outpatient.service.DispenseCheckService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * v50 车道 C：门诊发药核对端点。业务口径、错误码分配（5440–5451）与三条不可轻改的规则
 * 全部写在 {@link DispenseCheckService} 类注释里，此处不重复。
 *
 * <p><b>既有 {@code DispenseController} 的 4 个端点一个字节没改</b>：
 * {@code GET /api/outpatient/dispense/worklist}、{@code POST /api/outpatient/dispense/{registrationId}}、
 * {@code GET /api/outpatient/dispense/dispensed}、{@code POST /api/outpatient/dispense/orders/{orderId}/return}
 * 契约与错误码 6001–6006 原样保留。本控制器全部走 {@code /api/outpatient/dispense-check/**} 新前缀。
 *
 * <p><b>发药有两个入口，这是本版的已知事实、不是疏漏</b>：
 * <ul>
 *   <li>既有 {@code POST /api/outpatient/dispense/{registrationId}}——不过 gate、不记台账，
 *       零核对也能发。前端发药页现在走的是这条。</li>
 *   <li>本版 {@code POST /api/outpatient/dispense-check/registrations/{id}/dispense}——
 *       先过 gate 再<b>委托同一个 {@code DispenseService.dispense}</b>，扣库存的并发写法完全共用。</li>
 * </ul>
 * 收敛成一个入口要么改 {@code DispenseService}（车道 C 名下无此文件），要么改前端
 * （{@code frontend/shell/src/**} 是共用文件），两件都已写进 cross_lane 交主控。
 *
 * <p>权限与既有发药同口径：{@code ADMIN} 与 {@code PHARMACIST}。
 * 高危目录与看似听似对照的维护端点也在同一权限下——那是药剂科的活。
 */
@RestController
@RequestMapping("/api/outpatient/dispense-check")
@PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")   // 与既有 DispenseController 同口径
@RequiredArgsConstructor
public class DispenseCheckController {

    private final DispenseCheckService checkService;
    private final CurrentUserService currentUserService;

    // ==================================================================
    // 一、核对流程
    // ==================================================================

    /** 待核对工作台：全部 PREPARED 核对单，按摆药时刻先进先出 */
    @GetMapping("/worklist")
    public R<Object> worklist(@RequestParam(required = false) Integer limit) {
        return call(() -> checkService.worklist(limit));
    }

    /**
     * 某挂号的核对总览（只读）：待核对处方行与提示 + 当前 gate 评估 + 该挂号既有的核对单。
     * 前端进核对页的第一跳，也是「本挂号到底能不能发药」的一站式回答。
     */
    @GetMapping("/registrations/{registrationId}")
    public R<Object> overview(@PathVariable Long registrationId) {
        return call(() -> checkService.overview(registrationId));
    }

    /** 建单前预览：该挂号待核对的处方行与高危/看似听似提示（只读，不落数据） */
    @GetMapping("/registrations/{registrationId}/preview")
    public R<Object> preview(@PathVariable Long registrationId) {
        return call(() -> checkService.preview(registrationId));
    }

    public record CreateReq(Long registrationId) {}

    /**
     * 摆药完成，提交核对：当前登录人记为摆药人。
     *
     * <p>两个写法同一个动作、同一段服务代码：{@code POST /registrations/{id}}（RESTful 路径式）
     * 与 {@code POST /registrations}（请求体式，v50 e2e 与前端按这个形状调）。
     * 只留一份实现，避免两条路各自长出不同的校验。
     */
    @PostMapping("/registrations/{registrationId}")
    public R<Object> create(@PathVariable Long registrationId, Authentication auth) {
        return call(() -> checkService.create(registrationId, uid(auth)));
    }

    @PostMapping("/registrations")
    public R<Object> createByBody(@RequestBody(required = false) CreateReq req, Authentication auth) {
        if (req == null || req.registrationId() == null) return R.fail(5442, "必须指定 registrationId");
        return call(() -> checkService.create(req.registrationId(), uid(auth)));
    }

    /** 核对单详情（单头 + 行 + 高危待确认数 + 未维护数 + warnings） */
    @GetMapping("/{checkId}")
    public R<Object> detail(@PathVariable Long checkId) {
        return call(() -> checkService.detail(checkId));
    }

    /**
     * 高危药品<b>单独</b>确认：一行一次、留独立时刻与签名人。
     * 独立端点而不是核对通过时一并勾选——一次点头把五种高危药一起认了，与没确认没区别。
     */
    @PostMapping("/{checkId}/lines/{lineId}/high-alert-confirm")
    public R<Object> confirmHighAlert(@PathVariable Long checkId, @PathVariable Long lineId,
                                      Authentication auth) {
        return call(() -> checkService.confirmHighAlert(checkId, lineId, uid(auth)));
    }

    /**
     * 核对通过：核对人须与摆药人不同（5443，恒定校验、与 gate 无关）。
     * {@code PUT /{id}/check} 与 {@code POST /{id}/pass} 是同一个处理方法的两个路径，
     * 不是两份实现——v50 e2e 与前端按前者调，后者留给已按动词式写好的调用方。
     */
    @RequestMapping(value = {"/{checkId}/check", "/{checkId}/pass"},
            method = {RequestMethod.PUT, RequestMethod.POST})
    public R<Object> pass(@PathVariable Long checkId, Authentication auth) {
        return call(() -> checkService.pass(checkId, uid(auth)));
    }

    public record ReturnReq(String reason) {}

    /** 核对不通过，退回摆药（原因必填 5447；不删记录，退回率与退回原因是药房质控的分母） */
    @PostMapping("/{checkId}/return")
    public R<Object> returnToPicking(@PathVariable Long checkId,
                                     @RequestBody(required = false) ReturnReq req,
                                     Authentication auth) {
        return call(() -> checkService.returnToPicking(checkId, uid(auth), req == null ? null : req.reason()));
    }

    // ==================================================================
    // 二、发药前 gate / 核对后发药
    // ==================================================================

    /** 发药前评估（只读）：待发处方是否都被已通过的核对单覆盖，当前 gate 档位与 warnings */
    @GetMapping("/registrations/{registrationId}/gate")
    public R<Object> evaluate(@PathVariable Long registrationId) {
        return call(() -> checkService.evaluate(registrationId));
    }

    /**
     * 核对后发药：过 gate → 记台账 → <b>委托既有 {@code DispenseService.dispense}</b>。
     * block 档未通过核对返 5448；warn 档照常发药并回带 warnings；6001–6003 原样透出。
     */
    @PostMapping("/registrations/{registrationId}/dispense")
    public R<Object> dispense(@PathVariable Long registrationId, Authentication auth) {
        return call(() -> checkService.dispenseWithCheck(registrationId, uid(auth)));
    }

    // ==================================================================
    // 三、查询与台账
    // ==================================================================

    /** 核对记录查询（默认今天，日窗口按业务时区半开区间取，不用裸 LocalDate.now()） */
    @GetMapping("/records")
    public R<Object> records(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return call(() -> checkService.records(date, status, limit));
    }

    /**
     * 发药核对台账（默认今天）：warn 档到底放行了多少次、是谁放的。
     * {@code onlyBypassed=true} 只看未核对即发药的那些。
     */
    @GetMapping("/gate-log")
    public R<Object> gateLog(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Boolean onlyBypassed,
            @RequestParam(required = false) Integer limit) {
        return call(() -> checkService.gateLog(date, onlyBypassed, limit));
    }

    // ==================================================================
    // 四、药剂科维护：高危目录 / 看似听似对照
    // ==================================================================
    // 这两张「表」是本版提示能力的<b>唯一</b>数据来源，且**只能靠人维护**：
    // 按药名匹配会漏掉全部商品名制剂，字符串相似度既误报又漏报（详见 V149 头注释）。
    // 维护端点做在这里，是为了让属性位有真实写入方——只加列不给入口，
    // 那一列就永远是 null，「高危提示」也就永远是一句空话。

    public record HighAlertReq(Boolean highAlert) {}

    /**
     * 维护某药的高危属性。取值只接受 true/false：
     * null 的语义是「从未维护」，人工写回 null 等于伪造一条没人看过的历史（5449）。
     */
    @PutMapping("/drugs/{drugId}/high-alert")
    public R<Object> setHighAlert(@PathVariable Long drugId,
                                  @RequestBody(required = false) HighAlertReq req,
                                  Authentication auth) {
        return call(() -> checkService.setHighAlert(drugId, req == null ? null : req.highAlert(), uid(auth)));
    }

    /** 已标记为高危的药品清单 */
    @GetMapping("/drugs/high-alert")
    public R<Object> highAlertDrugs(@RequestParam(required = false) Integer limit) {
        return call(() -> checkService.highAlertDrugs(limit));
    }

    /** 高危属性尚未维护的药品清单——药剂科的待办，也是「提示覆盖率」的分母 */
    @GetMapping("/drugs/unmaintained")
    public R<Object> unmaintainedDrugs(@RequestParam(required = false) Integer limit) {
        return call(() -> checkService.unmaintainedDrugs(limit));
    }

    public record LasaReq(Long drugIdA, Long drugIdB, String note) {}

    /** 登记一对看似听似药品（无序对，A-B 与 B-A 是同一条） */
    @PostMapping("/lasa")
    public R<Object> addLasa(@RequestBody(required = false) LasaReq req, Authentication auth) {
        if (req == null) return R.fail(5450, "两个药品 id 都必须指定");
        return call(() -> checkService.addLasaPair(req.drugIdA(), req.drugIdB(), req.note(), uid(auth)));
    }

    /** 撤销看似听似对照（维护字典的纠错） */
    @DeleteMapping("/lasa/{pairId}")
    public R<Object> removeLasa(@PathVariable Long pairId) {
        return call(() -> checkService.removeLasaPair(pairId));
    }

    /** 看似听似对照查询；带 drugId 只列与该药相关的对 */
    @GetMapping("/lasa")
    public R<Object> lasaPairs(@RequestParam(required = false) Long drugId,
                               @RequestParam(required = false) Integer limit) {
        return call(() -> checkService.lasaPairs(drugId, limit));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 登录态解析：拿不到 uid 时由服务层返 5444，这里不擅自兜成匿名 */
    private Long uid(Authentication auth) {
        return auth == null ? null : currentUserService.idOf(auth);
    }

    /**
     * 业务异常统一转 {@code R.fail}，与既有 {@code DispenseController} 同写法。
     * 既有发药的 6001–6003 经 {@code dispenseWithCheck} 上抛时也走这里，码与消息原样透出。
     */
    private R<Object> call(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return R.ok(action.get());
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
