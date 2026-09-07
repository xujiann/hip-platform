package cn.hip.inpatient.web;

import cn.hip.inpatient.service.CountersignService;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v53 车道 V2：上级医师审签端点。
 *
 * <p><b>路径刻意另起 {@code /api/inpatient/countersign}，不并进
 * {@code InpEmrController}（{@code /api/inpatient/admissions/{admissionId}}）</b>：
 * 那个类是住院病历的核心读写口，多套 E2E 与单测钉着它的返回体形状；
 * 本版的纪律是「版本留痕只能是加一条记录」，端点同理——加一组新路径，
 * 既有路径与既有返回体一个字节不动。
 *
 * <p>审签按**病历行**寻址（{@code recordId}）而不是按住院号：一次住院有多份病历，
 * 「这次住院已审签」是一句没有意义的话——法定要问的是「入院记录是谁审的、首次病程是谁审的」。
 *
 * <p>错误码全部出自本车道子段 5720–5739，见 {@link CountersignService} 类注释。
 */
@RestController
@RequestMapping("/api/inpatient/countersign")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")
@RequiredArgsConstructor
public class CountersignController {

    private final CountersignService countersignService;
    private final CurrentUserService currentUserService;

    public record CountersignRequest(String opinion) {}

    /**
     * 上级审签一份病历。
     *
     * <p>写路径收窄到医师与管理员——{@code QUALITY}（病案室）可以看待审签清单、
     * 可以查某份病历被谁签过，但**不能代医师签字**。
     *
     * <p>成功返回体带 {@code versionSource} 与 {@code warnings}：
     * 当 v53 车道 V1 的版本表尚未就位时，{@code versionSource=DIGEST_ONLY} 且 warnings 里明说
     * 「本次审签只绑定正文摘要」——<b>前端必须显示这条 warning</b>（已写进 cross_lane）。
     * 让签字的人知道自己这一签绑定到了什么粒度，是这条 warning 存在的全部理由。
     */
    @PostMapping("/records/{recordId}")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Map<String, Object>> countersign(@PathVariable Long recordId,
                                              @RequestBody(required = false) CountersignRequest req,
                                              Authentication auth) {
        var result = countersignService.countersign(
                recordId, req == null ? null : req.opinion(), currentUserService.idOf(auth));
        return result.ok() ? R.ok(result.body()) : R.fail(result.code(), result.message());
    }

    /**
     * 某份病历的审签记录（时间正序）。
     *
     * <p>每条带 {@code stale}：true 表示**这一次审签之后正文被改过**，该次审签已失效。
     * 这是本版「能不能证明」的落点——没有它，一个审签时间戳证明不了签的是什么。
     */
    @GetMapping("/records/{recordId}")
    public R<Map<String, Object>> ofRecord(@PathVariable Long recordId) {
        var rows = countersignService.listForRecord(recordId);
        var body = new LinkedHashMap<String, Object>();
        body.put("recordId", recordId);
        body.put("countersigns", rows);
        // 「当前正文有没有一条有效审签」——前端按它决定是显示"已审签"还是"待重新审签"
        body.put("validForCurrentContent",
                rows.stream().anyMatch(r -> Boolean.FALSE.equals(r.get("stale"))));
        return R.ok(body);
    }

    /**
     * 待审签工作列表：按科室 / 按病区 / 按书写医师 / 按时限筛选。
     *
     * <p>{@code overdue} 由 {@code emr.countersign.due_hours}（默认 24 小时，自病历创建时刻起算）
     * 判定，<b>只是一个标记与排序依据，不拦截任何写入</b>——法定时限质控是 v53 车道 V3 的范围。
     */
    @GetMapping("/pending")
    public R<Map<String, Object>> pending(@RequestParam(required = false) Long deptId,
                                          @RequestParam(required = false) Long wardId,
                                          @RequestParam(required = false) Long doctorId,
                                          @RequestParam(defaultValue = "false") boolean overdueOnly,
                                          @RequestParam(defaultValue = "false") boolean includeArchived,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) Integer offset) {
        var result = countersignService.pending(deptId, wardId, doctorId, overdueOnly,
                includeArchived, limit, offset);
        return result.ok() ? R.ok(result.body()) : R.fail(result.code(), result.message());
    }

    /**
     * 某次住院的审签缺项与 gate 裁决（<b>纯只读预检</b>）。
     *
     * <p><b>本端点不拦任何东西</b>，它只把事实与裁决摆出来：{@code findings} 是客观缺项
     * （off 档下照样是真的——gate 只决定拿事实怎么办，不决定事实是什么），
     * {@code blocked} 是「若把这条 gate 挂在出院/归档挡点上，此刻会不会被挡」。
     *
     * <p>真正挂到 {@code InpatientService.discharge} 或病案归档的哪一行，由主控裁决——
     * 那两处是核心写路径、多套 E2E 钉着，本车道按铁律不碰（已写进 cross_lane）。
     */
    @GetMapping("/check")
    public R<Map<String, Object>> check(@RequestParam Long admissionId) {
        var v = countersignService.verdict(admissionId);
        var body = new LinkedHashMap<String, Object>();
        body.put("admissionId", admissionId);
        body.put("gate", v.gate());
        body.put("blocked", v.blocked());
        body.put("blockCode", v.blocked() ? v.code() : 0);
        body.put("blockMessage", v.blocked() ? v.message() : null);
        body.put("findings", v.findings().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("recordId", f.recordId());
            m.put("recordType", f.recordType());
            m.put("text", f.text());
            return m;
        }).toList());
        body.put("warnings", v.warnings());
        body.put("requiredTypes", countersignService.requiredTypes());
        return R.ok(body);
    }

    /** 当前生效的审签配置（前端提示文案与工作台表头按它渲染，避免把默认值写死在前端第二份） */
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        var body = new LinkedHashMap<String, Object>();
        body.put("gate", countersignService.gate());
        body.put("gateKey", CountersignService.GATE_KEY);
        body.put("requiredTypes", countersignService.requiredTypes());
        body.put("dueHours", countersignService.dueHours());
        List<String> notes = List.of(
                "审签人不得与书写人为同一人——该规则与 gate 无关，三档下一律生效",
                "只回看不回滚：审签记录只增不改不删，需要否定前一次审签走「病历改动 → 原审签失效 → 重新审签」");
        body.put("notes", notes);
        return R.ok(body);
    }
}
