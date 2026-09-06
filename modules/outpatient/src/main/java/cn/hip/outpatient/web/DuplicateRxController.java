package cn.hip.outpatient.web;

import cn.hip.outpatient.service.DuplicateRxService;
import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * v51 车道 B：重复用药与同类药端点。
 *
 * <h2>既有 CDSS 契约逐字未改</h2>
 * {@code CdssController} 的 4 个端点（{@code /rules}、{@code /ddi-rules}、{@code /alerts}、
 * {@code /suggestions}）与 {@code CdssService} 的三类规则一个字符都没动，
 * 错误码 4015/4017/4650 原样。本控制器全部挂在 {@code /api/cdss/duplicate} 下：
 * 与既有 {@code /api/cdss/alerts} <b>不冲突</b>（本版是 {@code /api/cdss/duplicate/alerts}），
 * 台账也另起一张 {@code cdss_duplicate_alert}，不混进既有 {@code cdss_alert}。
 *
 * <h2>两个检查端点的分工（不要合并）</h2>
 * <ul>
 *   <li>{@code POST /check} —— <b>只读预览，永不抛异常，不受 gate 影响</b>。
 *       医生站开单过程中随时可调，返回全部三层发现。一个显式的查询接口不该因为
 *       gate=off 就假装什么都没查到。</li>
 *   <li>{@code POST /gate-check} —— <b>开单校验入口</b>，受 {@code cdss.gate.duplicate} 管辖：
 *       off 整段旁路；warn 放行但回带 warnings 并落台账；block 仅拦「同次就诊 +
 *       完全相同(5620)/同通用名(5621)」。跨处方与同药理类别<b>即便 block 也只提示不拦</b>。</li>
 * </ul>
 *
 * <h2>尚未接进开单主链路（如实说明）</h2>
 * {@code DoctorStationService.createOrders} / {@code DoctorStationController} 不在本车道
 * 名下的文件里，本版<b>没有</b>把 {@code gate-check} 挂进去——也就是说，
 * 走既有开单路径仍然不会触发重复用药校验，必须由前端或开单服务显式调用本端点。
 * 接线是一行「只增不改」的调用，需求已写进 cross_lane，不要以为本版已经全线生效。
 *
 * <h2>维护端点：药学内容由药剂科填，本平台一行都不猜</h2>
 * {@code /drug-generic}、{@code /drug-category}、{@code /categories} 是通用名与药理类别的
 * 维护入口。V153 只加列不填值，出厂状态下第 ②③ 层查不出任何东西——
 * {@code GET /config} 的 {@code coverage} 如实回报维护了多少，不要把「覆盖率 0 所以没报警」
 * 当成「没有重复用药」。
 *
 * <h2>权限</h2>
 * 检查侧 ADMIN + DOCTOR_OUTP + PHARMACIST（开单与药师审方都要看）；
 * 维护侧收紧到 ADMIN + PHARMACIST——通用名与药理类别是<b>主数据</b>，
 * 一次改动影响全院所有处方的判重结果，不该由开单侧顺手改。
 */
@RestController
@RequestMapping("/api/cdss/duplicate")
@RequiredArgsConstructor
public class DuplicateRxController {

    private final DuplicateRxService duplicateRxService;

    /**
     * @param registrationId 本次就诊挂号 ID
     * @param drugIds        本次拟开具的药品 {@code md_drug.id}；同一个药写两行就传两次，
     *                       本次提交内部的重复也要抓（与「改方忘删旧行」同源）
     */
    public record CheckReq(Long registrationId, List<Long> drugIds) {
    }

    public record GenericReq(Long drugId, String genericName) {
    }

    public record CategoryOfDrugReq(Long drugId, String categoryCode) {
    }

    public record CategoryReq(String code, String name, String remark) {
    }

    // ===================== 检查 =====================

    /** 只读预览：返回全部三层发现，不看 gate、不拦、不落台账 */
    @PostMapping("/check")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST')")
    public R<Map<String, Object>> check(@RequestBody CheckReq req) {
        return R.ok(duplicateRxService.evaluate(req.registrationId(), req.drugIds()));
    }

    /** 开单校验：受 gate 管辖，warn 回带 warnings 并落台账，block 拦同次就诊重复（5620/5621） */
    @PostMapping("/gate-check")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST')")
    public R<Map<String, Object>> gateCheck(@RequestBody CheckReq req) {
        return R.ok(duplicateRxService.checkOnOrdering(req.registrationId(), req.drugIds()));
    }

    // ===================== 口径与台账 =====================

    /** gate / 回溯窗口 / 三层的数据源与维护覆盖率 */
    @GetMapping("/config")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST')")
    public R<Map<String, Object>> config() {
        return R.ok(duplicateRxService.config());
    }

    /**
     * 重复用药台账。<b>已知缺口</b>：block 档拦下的那一次随业务异常回滚，此处查不到；
     * warn 档的放行记录完整可信——而 warn→block 的决策依据恰恰来自 warn 档数据。
     */
    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST')")
    public R<List<Map<String, Object>>> alerts(@RequestParam(required = false) Long registrationId,
                                               @RequestParam(required = false) Integer limit) {
        return R.ok(duplicateRxService.alerts(registrationId, limit));
    }

    // ===================== 主数据维护 =====================

    /** 维护药品通用名；{@code genericName} 传空表示撤回维护（置回 null = 未维护） */
    @PostMapping("/drug-generic")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Map<String, Object>> setGeneric(@RequestBody GenericReq req) {
        return R.ok(duplicateRxService.setGenericName(req.drugId(), req.genericName()));
    }

    /** 维护药品药理类别；类别码须已存在于 cdss_pharm_category（否则 5623） */
    @PostMapping("/drug-category")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Map<String, Object>> setCategory(@RequestBody CategoryOfDrugReq req) {
        return R.ok(duplicateRxService.setPharmCategory(req.drugId(), req.categoryCode()));
    }

    /** 药理类别字典（出厂只有一条标注为占位的示例行，药剂科按院内用药目录替换） */
    @GetMapping("/categories")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST')")
    public R<List<Map<String, Object>>> categories() {
        return R.ok(duplicateRxService.categories());
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Map<String, Object>> upsertCategory(@RequestBody CategoryReq req) {
        return R.ok(duplicateRxService.upsertCategory(req.code(), req.name(), req.remark()));
    }
}
