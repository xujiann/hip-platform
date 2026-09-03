package cn.hip.outpatient.web;

import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.outpatient.service.RxTemplateService;
import cn.hip.outpatient.service.RxTemplateService.TemplateReq;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v44 车道F：处方模板与协定处方（技术偏离表 999★ / 1000★）。
 *
 * <p><b>本控制器不开单，也永远不许开单。</b>它只做两件事：维护模板，以及把模板明细
 * 吐成「开单表单能直接填」的形状。医生套用模板后<b>仍走原有</b>
 * {@code POST /api/outpatient/doctor/{registrationId}/orders}，
 * 皮试/过敏（4012）、同诊重复用药（4013）、抗菌药分级（4014）、CDSS（4015/4017）、
 * 停用药预检（8016）、库存预警<b>一条不少地照常执行</b>。
 * <b>严禁</b>为"提高配方速度"新增任何批量开单/直落 {@code outp_order} 的端点——
 * 模板一旦能绕过合理用药审查，它就从提效工具变成用药安全的后门。
 *
 * <p>权限：菜单授 ADMIN/DOCTOR_OUTP/PHARMACIST（V136）。
 * 角色只是<b>门槛</b>，真正的可见/可改判定按模板三级作用范围逐条做，见 {@link RxTemplateService}。
 *
 * <p>错误码 4060–4069：4060 模板不存在或无权使用/修改、4061 名称必填、
 * 4062 明细为空或明细行不成立、4063 作用范围非法、4064 协定处方不可修改明细、4069 模板类别非法。
 */
@RestController
@RequestMapping("/api/outpatient/rx-templates")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST')")
@RequiredArgsConstructor
public class RxTemplateController {

    private final RxTemplateService rxTemplateService;
    private final CurrentUserService currentUserService;

    /**
     * 按当前医生可见范围列模板：个人（本人的）+ 科室（本科室的）+ 全院。
     *
     * <p>行内附 {@code editable}（本人能否改，前端据此显隐按钮）与
     * {@code linesLocked}（协定处方明细锁定）、{@code line_count}（明细行数）。
     *
     * @param category      RX 处方模板 / AGREED 协定处方；不传即两类都出
     * @param includeDisabled 维护页传 true 才看得到已停用模板；医生站套用一律用默认 false
     */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String category,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "false") boolean includeDisabled,
                                             Authentication auth) {
        try {
            return R.ok(rxTemplateService.list(currentUserService.idOf(auth), category, keyword, includeDisabled));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /**
     * 取模板明细——<b>套用入口的唯一取数端点，返回体即开单表单行</b>。
     *
     * <p>每行的 {@code orderType/itemId/qty/usageRoute/frequency/dosePerTime/days} 与
     * {@code DoctorStationService.OrderLine} 逐字段同名，前端原样 push 进开单行数组即可，
     * 不需要任何字段转换表；其余键（itemName/spec/unit/unitPrice/category/sortNo/locked/
     * itemExists/itemEnabled/stock）是展示与提示用的，开单端点会忽略。
     *
     * @param forEdit true=维护页查看（可看已停用模板，但要求可改权限）；
     *                false=医生站套用（已停用模板返 4060）
     */
    @GetMapping("/{id}/lines")
    public R<List<Map<String, Object>>> lines(@PathVariable Long id,
                                              @RequestParam(defaultValue = "false") boolean forEdit,
                                              Authentication auth) {
        try {
            return R.ok(rxTemplateService.lines(currentUserService.idOf(auth), id, forEdit));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PostMapping
    public R<Long> create(@RequestBody TemplateReq req, Authentication auth) {
        try {
            return R.ok(rxTemplateService.create(currentUserService.idOf(auth), req));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /**
     * 改模板。{@code lines} 传 null 只改头（名称/范围/科室/备注），明细原样不动；
     * 传数组则整组替换——协定处方走到这里一律 4064（要改就停用旧版另建新版）。
     *
     * <p>emr_template 的教训：v42 才发现它只有 POST 与 GET、连 enabled 列都没有，
     * 于是"编辑模板"这件每天都要做的事一年来只能靠"复制为新模板"绕。本端点与
     * disable/enable 是同批交付的，不留"下一版再补编辑"的尾巴。
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody TemplateReq req, Authentication auth) {
        try {
            rxTemplateService.update(currentUserService.idOf(auth), id, req);
            return R.ok();
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 停用（软开关）：停用后不再出现在套用列表，直接按 id 取明细也返 4060 */
    @PutMapping("/{id}/disable")
    public R<Void> disable(@PathVariable Long id, Authentication auth) {
        return setEnabled(id, false, auth);
    }

    /** 启用 */
    @PutMapping("/{id}/enable")
    public R<Void> enable(@PathVariable Long id, Authentication auth) {
        return setEnabled(id, true, auth);
    }

    private R<Void> setEnabled(Long id, boolean enabled, Authentication auth) {
        try {
            rxTemplateService.setEnabled(currentUserService.idOf(auth), id, enabled);
            return R.ok();
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 删除（明细级联清）。日常用停用，删除只兜底建错了的模板。 */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id, Authentication auth) {
        try {
            rxTemplateService.delete(currentUserService.idOf(auth), id);
            return R.ok();
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
