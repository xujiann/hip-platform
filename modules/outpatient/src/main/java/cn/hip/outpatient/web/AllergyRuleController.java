package cn.hip.outpatient.web;

import cn.hip.outpatient.service.AllergyRuleService;
import cn.hip.outpatient.service.AllergyRuleService.AllergyInput;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v51 车道 A：CDSS 过敏规则端点。
 *
 * <p>业务口径、错误码分配（5600–5614）、假阴性/假阳性的取舍与本版留的缺口，
 * 全部写在 {@link AllergyRuleService} 类注释里，此处不重复。
 *
 * <p><b>既有 {@code CdssController} 的 4 个端点一个字节没改</b>：
 * {@code GET /api/cdss/rules}、{@code POST /api/cdss/ddi-rules}、{@code GET /api/cdss/alerts}、
 * {@code GET /api/cdss/suggestions} 契约与错误码 4650 原样保留。
 * 本控制器全部走 {@code /api/cdss/allergy/**} 新前缀，Spring MVC 上不与
 * {@code /api/cdss/rules} 等既有精确路径冲突。
 *
 * <p><b>权限分三层，不是懒省事的一刀切</b>：
 * <ul>
 *   <li><b>字典与规则维护</b>（过敏原目录、药品映射、交叉族）——{@code ADMIN} / {@code PHARMACIST}。
 *       这是药剂科按院内用药目录做的事，一条映射写错会影响全院所有处方。</li>
 *   <li><b>患者过敏档与人工核对</b>——再加 {@code DOCTOR_OUTP} / {@code NURSE}。
 *       问过敏史、确认过敏原的是临床，不是药剂科；把这层锁死会让工作台永远清不完。</li>
 *   <li><b>开单审查</b>——上述全部角色。它是开处方路上的一次只读判断加留痕，
 *       任何能开单的人都必须叫得动，否则 warn 档的提示根本到不了医生眼前。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/cdss/allergy")
@RequiredArgsConstructor
public class AllergyRuleController {

    private final AllergyRuleService allergyService;
    private final CurrentUserService currentUserService;

    // ==================================================================
    // 一、开单过敏审查
    // ==================================================================

    public record CheckReq(Long patientId, Long registrationId, List<Long> drugIds) {}

    /**
     * 开药过敏审查（<b>会留痕</b>：写 {@code cdss_alert} 与 {@code cdss_allergy_gate_log}）。
     *
     * <p>block 档直接命中时返 5612；warn/off 档返 0 并在 {@code data.warnings} 里带全部提示。
     * <b>前端必须显示 warnings</b>——warn 档若不显示，这一版的过敏提示对医生就是不存在的
     * （前端为共用文件，本车道不改，已写进 cross_lane）。
     *
     * <p>用 POST 而不是 GET：本端点有写副作用（留痕），而且药品清单是一组 id，不该塞进 query string。
     */
    @PostMapping("/check")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')")
    public R<Object> check(@RequestBody(required = false) CheckReq req, Authentication auth) {
        if (req == null) return R.fail(5614, "请求体不能为空（需要 patientId 与 drugIds）");
        return call(() -> allergyService.enforceOnOrdering(
                req.patientId(), req.registrationId(), req.drugIds(), uid(auth)));
    }

    /** 过敏审查预演（<b>纯只读，不留痕、不抛 5612</b>）：给维护映射的人自查用，也给前端做即时提示 */
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')")
    public R<Object> preview(@RequestBody(required = false) CheckReq req) {
        if (req == null) return R.fail(5614, "请求体不能为空（需要 patientId 与 drugIds）");
        return call(() -> allergyService.evaluate(req.patientId(), req.drugIds()));
    }

    /** 过敏审查台账：三档都记、命中与否都记（否则命中率的分母是假的） */
    @GetMapping("/gate-log")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> gateLog(@RequestParam(required = false) Long patientId,
                             @RequestParam(required = false) Integer limit) {
        return call(() -> allergyService.gateLog(patientId, limit));
    }

    // ==================================================================
    // 二、患者过敏档
    // ==================================================================

    /** 患者过敏档：结构化记录 + 自由文本原文 + 覆盖度（原文与结构化并排，便于核对提取有无遗漏） */
    @GetMapping("/patients/{patientId}")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')")
    public R<Object> profile(@PathVariable Long patientId) {
        return call(() -> allergyService.patientProfile(patientId));
    }

    public record AllergyReq(Long allergenId, String severity, String manifestation,
                             String source, String note) {}

    /** 登记一条结构化过敏记录（确认人取当前登录人，不可匿名——这条记录会拦处方，必须有人签字） */
    @PostMapping("/patients/{patientId}/allergies")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')")
    public R<Object> addAllergy(@PathVariable Long patientId,
                                @RequestBody(required = false) AllergyReq req,
                                Authentication auth) {
        if (req == null) return R.fail(5604, "请求体不能为空（至少需要 allergenId、severity、source）");
        return call(() -> allergyService.addPatientAllergy(patientId, req.allergenId(), req.severity(),
                req.manifestation(), req.source(), req.note(), null, uid(auth)));
    }

    public record RevokeReq(String reason) {}

    /**
     * 撤销一条过敏记录（例如后续皮试证实并非过敏）。
     * <b>没有物理删除端点</b>：删掉一条过敏记录直接放开了一次拦截，这个动作不该是无痕的。
     */
    @PostMapping("/patients/{patientId}/allergies/{allergyId}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')")
    public R<Object> revokeAllergy(@PathVariable Long patientId, @PathVariable Long allergyId,
                                   @RequestBody(required = false) RevokeReq req, Authentication auth) {
        return call(() -> allergyService.revokePatientAllergy(patientId, allergyId,
                req == null ? null : req.reason(), uid(auth)));
    }

    // ==================================================================
    // 三、自由文本迁移工作台（**人工**，绝不自动解析）
    // ==================================================================

    /**
     * 待人工核对的自由文本过敏史清单。
     *
     * <p><b>此处只返回原文，不返回任何「建议过敏原」</b>：一个自动填好的下拉框会把人工确认
     * 降级成人工点确定——「青霉素皮试阴性」被预填成「青霉素」，多数人不会去改。
     * 全仓不存在把 {@code allergy_history} 自动转结构化的代码路径。
     */
    @GetMapping("/migration/worklist")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')")
    public R<Object> worklist(@RequestParam(required = false) Integer limit,
                              @RequestParam(required = false, defaultValue = "false") boolean includeReviewed) {
        return call(() -> allergyService.migrationWorklist(limit, includeReviewed));
    }

    public record ReviewReq(String sourceText, String resolution, List<AllergyReq> allergens, String note) {}

    /**
     * 提交人工核对结论。
     *
     * <p>{@code sourceText} 必填且须与当前 {@code allergy_history} 逐字一致（否则 5613）——
     * 原文在核对期间被人改过是常事，不校验就会把按旧原文做的判断记成对新原文的核对，
     * 新内容从此再不进待办。
     *
     * <p>{@code resolution}：STRUCTURED 已确认并登记 / NO_ALLERGY 原文不构成过敏记录 /
     * UNCLEAR 无法判定需再询问（<b>UNCLEAR 不等于无过敏</b>，该患者的审查仍标记为覆盖不全）。
     */
    @PostMapping("/migration/patients/{patientId}/review")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')")
    public R<Object> review(@PathVariable Long patientId,
                            @RequestBody(required = false) ReviewReq req, Authentication auth) {
        if (req == null) return R.fail(5610, "请求体不能为空（需要 sourceText 与 resolution）");
        var inputs = req.allergens() == null ? List.<AllergyInput>of()
                : req.allergens().stream().map(a -> new AllergyInput(
                        a.allergenId(), a.severity(), a.manifestation(), a.source(), a.note())).toList();
        return call(() -> allergyService.reviewText(patientId, req.sourceText(), req.resolution(),
                inputs, req.note(), uid(auth)));
    }

    // ==================================================================
    // 四、字典与规则维护（药剂科）
    // ==================================================================

    /** 规则总览 + 维护完成度（coverage 段是把 gate 收紧到 block 的唯一数据依据） */
    @GetMapping("/rules")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')")
    public R<Object> rules(@RequestParam(required = false) Integer limit) {
        return call(() -> allergyService.rulesOverview(limit));
    }

    public record AllergenReq(String code, String name, String allergenType, String drugLevel, String remark) {}

    /** 新增过敏原（内容由药剂科按院内用药目录维护，本平台不预置任何药学判断） */
    @PostMapping("/allergens")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> addAllergen(@RequestBody(required = false) AllergenReq req, Authentication auth) {
        if (req == null) return R.fail(5601, "请求体不能为空（需要 code、name、allergenType）");
        return call(() -> allergyService.addAllergen(req.code(), req.name(), req.allergenType(),
                req.drugLevel(), req.remark(), uid(auth)));
    }

    public record EnabledReq(Boolean enabled) {}

    /**
     * 启用/停用过敏原。
     * <b>停用只挡新登记</b>，既有患者记录照常参与审查——不让一次字典整理静默关掉一批过敏拦截。
     */
    @PutMapping("/allergens/{allergenId}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> setAllergenEnabled(@PathVariable Long allergenId,
                                        @RequestBody(required = false) EnabledReq req) {
        return call(() -> allergyService.setAllergenEnabled(allergenId, req == null ? null : req.enabled()));
    }

    public record MapDrugReq(Long drugId, String mappedLevel, String note) {}

    /**
     * 过敏原 → 院内药品映射（INGREDIENT 成分 / PRODUCT 药品 / CLASS 药理类别）。
     * <b>这是全模块唯一的命中依据</b>；禁止用药名子串或相似度自动生成，理由见 V152 与服务类注释。
     */
    @PostMapping("/allergens/{allergenId}/drugs")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> mapDrug(@PathVariable Long allergenId,
                             @RequestBody(required = false) MapDrugReq req, Authentication auth) {
        if (req == null) return R.fail(5602, "请求体不能为空（需要 drugId 与 mappedLevel）");
        return call(() -> allergyService.mapDrug(allergenId, req.drugId(), req.mappedLevel(),
                req.note(), uid(auth)));
    }

    /** 某过敏原已映射的院内药品 */
    @GetMapping("/allergens/{allergenId}/drugs")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','DOCTOR_OUTP','NURSE')")
    public R<Object> allergenDrugs(@PathVariable Long allergenId,
                                   @RequestParam(required = false) Integer limit) {
        return call(() -> allergyService.allergenDrugs(allergenId, limit));
    }

    /** 撤销一条过敏原-药品映射（维护字典的纠错） */
    @DeleteMapping("/allergen-drugs/{mapId}")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> unmapDrug(@PathVariable Long mapId) {
        return call(() -> allergyService.unmapDrug(mapId));
    }

    public record GroupReq(String code, String name, String remark) {}

    /** 新增交叉过敏族（族的成员与族间交叉风险均由药剂科维护） */
    @PostMapping("/groups")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> addGroup(@RequestBody(required = false) GroupReq req, Authentication auth) {
        if (req == null) return R.fail(5603, "请求体不能为空（需要 code 与 name）");
        return call(() -> allergyService.addGroup(req.code(), req.name(), req.remark(), uid(auth)));
    }

    public record GroupMemberReq(Long allergenId) {}

    @PostMapping("/groups/{groupId}/members")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> addGroupMember(@PathVariable Long groupId,
                                    @RequestBody(required = false) GroupMemberReq req, Authentication auth) {
        if (req == null) return R.fail(5603, "请求体不能为空（需要 allergenId）");
        return call(() -> allergyService.addGroupMember(groupId, req.allergenId(), uid(auth)));
    }

    @DeleteMapping("/group-members/{memberId}")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> removeGroupMember(@PathVariable Long memberId) {
        return call(() -> allergyService.removeGroupMember(memberId));
    }

    public record CrossReq(Long groupIdA, Long groupIdB, String riskLevel, String note) {}

    /**
     * 登记一对族间交叉风险（无序对，A-B 与 B-A 是同一条）。
     * 交叉命中在开单侧<b>恒为警告、三档下永不拦截</b>——理由见服务类注释第三节第 2 条。
     */
    @PostMapping("/cross")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> addCross(@RequestBody(required = false) CrossReq req, Authentication auth) {
        if (req == null) return R.fail(5603, "请求体不能为空（需要 groupIdA、groupIdB、riskLevel）");
        return call(() -> allergyService.addCross(req.groupIdA(), req.groupIdB(), req.riskLevel(),
                req.note(), uid(auth)));
    }

    /** 撤销一条族间交叉风险：这是交叉规则唯一的关闭方式，配得太宽的规则必须能被干净撤掉 */
    @DeleteMapping("/cross/{crossId}")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> removeCross(@PathVariable Long crossId) {
        return call(() -> allergyService.removeCross(crossId));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 登录态解析：拿不到 uid 时由服务层返 5606，这里不擅自兜成匿名——过敏记录必须有人签字 */
    private Long uid(Authentication auth) {
        return auth == null ? null : currentUserService.idOf(auth);
    }

    /** 业务异常统一转 {@code R.fail}，与既有 {@code CdssController}/{@code DispenseCheckController} 同写法 */
    private R<Object> call(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return R.ok(action.get());
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
