package cn.hip.outpatient.web;

import cn.hip.outpatient.service.PopulationRuleService;
import cn.hip.outpatient.service.PopulationRuleService.RuleReq;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * v51 车道 C：CDSS 特殊人群用药端点（妊娠 / 哺乳 / 肝功能不全 / 肾功能不全）。
 * 业务口径、数据源核实结论、三处"判不了"的处理与错误码分配（5640–5659）
 * 全部写在 {@link PopulationRuleService} 类注释里，此处不重复。
 *
 * <h2>既有 CDSS 契约逐字不动</h2>
 * 既有 {@code CdssController} 的 4 个端点——{@code GET /api/cdss/rules}、
 * {@code POST /api/cdss/ddi-rules}、{@code GET /api/cdss/alerts}、{@code GET /api/cdss/suggestions}
 * ——一个字节没改，错误码 4015 / 4017 / 4650 原样。本控制器全部走
 * {@code /api/cdss/population/**} 新前缀，新表新端点，只增不改。
 *
 * <h2>权限口径</h2>
 * <ul>
 *   <li><b>规则维护</b>（{@code /rules/**}）：{@code ADMIN} + {@code PHARMACIST}。
 *       规则内容是药学知识，本就是药剂科的活；医生不该能改自己被拦的规则。</li>
 *   <li><b>状态申报</b>（{@code /status/**}）：{@code ADMIN} + {@code DOCTOR_OUTP} + {@code NURSE}。
 *       妊娠状态由问诊/护理采集，药师无从得知。</li>
 *   <li><b>评估与留痕查询</b>：上述角色都可读——药师复核处方时同样要看到医生看到的那条提示。</li>
 * </ul>
 *
 * <h2>本控制器不做什么</h2>
 * <ul>
 *   <li><b>不提供"批量导入规则"端点</b>。批量导入等于让一份来源不明的表格一次性变成院内规则，
 *       而本版的核心纪律正是"规则内容必须由药剂科逐条对着院内用药目录/说明书维护"。
 *       逐条入库慢，但每条都有人对依据负过责。</li>
 *   <li><b>不提供"从自由文本推断妊娠状态"的端点</b>。「否认妊娠」与「妊娠」文本高度相近而语义相反，
 *       与车道 A 的「青霉素过敏」/「青霉素皮试阴性」是同一类反向拦截风险。状态只能人工申报。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/cdss/population")
@RequiredArgsConstructor
public class PopulationRuleController {

    private final PopulationRuleService service;
    private final CurrentUserService currentUserService;

    // ==================================================================
    // 一、状态申报（妊娠 / 哺乳）
    // ==================================================================

    public record StatusReq(Long patientId, String status, String source,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validUntil,
                            String note) {}

    /**
     * 申报妊娠 / 哺乳状态。追加写，最新未撤销行胜出。
     *
     * <p>{@code status=NEITHER}（问过了、不是）与"从未申报"是两件事，必须分别记：
     * 前者静默放行，后者要在评估结果里提示"该维度未评估"。
     */
    @PostMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")
    public R<Map<String, Object>> assertStatus(@RequestBody StatusReq req, Authentication auth) {
        return call(() -> Map.of("statusId",
                service.assertStatus(req.patientId(), req.status(), req.source(),
                        req.validUntil(), req.note(), currentUserService.idOf(auth))));
    }

    public record RevokeReq(String reason) {}

    /** 撤销一条录错的申报（软撤销，不删不改历史行） */
    @PostMapping("/status/{statusId}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")
    public R<Void> revokeStatus(@PathVariable Long statusId, @RequestBody(required = false) RevokeReq req,
                                Authentication auth) {
        return callVoid(() -> service.revokeStatus(statusId,
                req == null ? null : req.reason(), currentUserService.idOf(auth)));
    }

    /**
     * 当前生效状态。{@code status} 为 {@code PREGNANT}/{@code LACTATING}/{@code NEITHER}/{@code UNKNOWN}；
     * {@code UNKNOWN} 时 {@code reason} 说明是"从未采集"还是"最近一次申报已过期"，
     * {@code stale=true} 表示申报虽仍有效但已超过陈旧阈值，请人工核对。
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE','PHARMACIST')")
    public R<Map<String, Object>> currentStatus(@RequestParam Long patientId) {
        return call(() -> service.currentStatus(patientId));
    }

    /** 申报历史（含已撤销与已过期，供人工核对） */
    @GetMapping("/status/history")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE','PHARMACIST')")
    public R<List<Map<String, Object>>> statusHistory(@RequestParam Long patientId,
                                                      @RequestParam(required = false) Integer limit) {
        return call(() -> service.statusHistory(patientId, limit));
    }

    // ==================================================================
    // 二、规则维护（药剂科）
    // ==================================================================

    /**
     * 新增规则。
     *
     * <p><b>依据两列必填</b>：{@code basisSource}（说明书版本 / 院内用药目录 / 指南名称+年份）
     * 与 {@code basisLevel}，缺任一条回 5651。这不是形式主义——只说「孕妇慎用」而不给出处，
     * 医生无从判断该不该采纳，最终整类提示会被无视。
     *
     * <p>肝/肾规则必须给全 {@code labItemCode + comparator + threshold + labUnit}（缺则 5652）。
     * {@code labUnit} 必填是因为本引擎<b>不做单位换算</b>：结果单位与阈值单位不一致时该规则整条跳过并提示。
     */
    @PostMapping("/rules")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Map<String, Object>> addRule(@RequestBody RuleReq req, Authentication auth) {
        return call(() -> Map.of("ruleId", service.addRule(req, currentUserService.idOf(auth))));
    }

    /** 停用/启用规则。规则不删——停用过的规则要能查回来解释历史留痕。 */
    @PostMapping("/rules/{ruleId}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Void> setRuleEnabled(@PathVariable Long ruleId, @RequestParam boolean enabled) {
        return callVoid(() -> service.setRuleEnabled(ruleId, enabled));
    }

    /** 规则清单。种子只有一条 enabled=false 的示例行，真实规则须由药剂科逐条维护。 */
    @GetMapping("/rules")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP')")
    public R<List<Map<String, Object>>> listRules(@RequestParam(required = false) String population,
                                                  @RequestParam(required = false) Boolean enabledOnly,
                                                  @RequestParam(required = false) Integer limit) {
        return call(() -> service.listRules(population, enabledOnly, limit));
    }

    // ==================================================================
    // 三、评估（只读预检）
    // ==================================================================

    public record EvalReq(Long registrationId, Long patientId, List<String> drugNames) {}

    /**
     * 只读评估：不落库、不拦截，供医生站提交前预检与药师复核。
     *
     * <p>返回体三段都要看：
     * <ul>
     *   <li>{@code hits} —— 命中项，每条都带 {@code basisSource} / {@code basisLevel} / {@code evidence}；</li>
     *   <li>{@code notices} —— <b>本次"判不了"的维度</b>：状态未采集、检验缺失/超期/非数值、单位不一致。
     *       这一段是本端点的重点：没有 hits <b>不等于</b>没问题，可能只是这几个维度都没数据。</li>
     *   <li>{@code status} —— 当前妊娠/哺乳状态与其来源、申报时间、是否陈旧。</li>
     * </ul>
     */
    @PostMapping("/evaluate")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST','NURSE')")
    public R<Map<String, Object>> evaluate(@RequestBody EvalReq req) {
        return call(() -> service.evaluate(req.registrationId(), req.patientId(), req.drugNames()));
    }

    // ==================================================================
    // 四、留痕
    // ==================================================================

    /** 命中留痕。{@code basis_*} 与 {@code message} 是<b>快照</b>，规则被改后仍还原医生当时看到的原话。 */
    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST','QUALITY')")
    public R<List<Map<String, Object>>> alerts(@RequestParam(required = false) Long patientId,
                                               @RequestParam(required = false) Integer limit) {
        return call(() -> service.alerts(patientId, limit));
    }

    /** 当前 gate 档位（前端据此决定命中项显示成"警告"还是"阻断"） */
    @GetMapping("/gate")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST','NURSE')")
    public R<Map<String, Object>> gate() {
        return call(() -> Map.of(
                "key", PopulationRuleService.GATE_KEY,
                "gate", service.gate(),
                "note", "warn（默认）不拦截，但命中会落 cdss_population_alert 并在 evaluate 返回体的 warnings 里回带；"
                        + "off 整体旁路；坏配置回落 warn"));
    }

    // ==================================================================
    // 业务码透出：与既有控制器同口径，BizException 的码原样进 R.code
    // ==================================================================

    private <T> R<T> call(java.util.function.Supplier<T> action) {
        try {
            return R.ok(action.get());
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    private R<Void> callVoid(Runnable action) {
        try {
            action.run();
            return R.ok();
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
