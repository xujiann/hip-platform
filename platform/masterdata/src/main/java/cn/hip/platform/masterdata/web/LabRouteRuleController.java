package cn.hip.platform.masterdata.web;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.service.LabRouteService;
import cn.hip.platform.masterdata.service.LabRouteService.Resolved;
import cn.hip.platform.masterdata.service.LabRouteService.RuleReq;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
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

/**
 * v77 车道 A：检验流向规则维护 + 规则试算（偏离表 1016★「根据流向自动获取执行科室」）。
 *
 * <p>独立成类，不碰 {@link MasterDataController}（与 v76 诊断字典同一纪律：新端点单独成类，避免多车道同改一文件）。
 * 匹配口径、校验与错误码全在 {@link LabRouteService}，本类只做参数搬运与权限声明。
 *
 * <p>权限：读登录即可；写 {@code hasAnyRole('ADMIN','TECHNICIAN')}——检验科自己配自己的分流（V175 菜单同口径）。
 *
 * <p>错误码（5910–5914，规划节预分配）：5910 规则不存在 / 5911 执行科室不存在或已停用 /
 * 5912 收费项目不存在或不是检验类 / 5913 同键重复 / 5914 开单科室不存在；字段缺失/超长走通用 4000。
 * 开单落值路径零新码（规则读失败回落字典，不抛）。
 */
@RestController
@RequestMapping("/api/masterdata/lab-route-rules")
@RequiredArgsConstructor
public class LabRouteRuleController {

    private final LabRouteService labRouteService;
    private final ChargeItemRepository chargeItemRepository;

    /**
     * 规则列表，按具体度降序、priority 升序、id 升序（与匹配顺序同，页面从上到下读就是匹配次序）。
     *
     * @return [{id,name,itemCategory,chargeItemId,chargeItemCode,chargeItemName,specimenType,orderDeptId,orderDeptName,
     *          execDeptId,execDeptName,priority,enabled,remark,updatedAt}]
     */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(defaultValue = "false") boolean includeDisabled) {
        return R.ok(labRouteService.list(includeDisabled));
    }

    /** 新增，默认启用。body {name,chargeItemId?,specimenType?,orderDeptId?,execDeptId,priority?,remark?} → {id} */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Map<String, Object>> create(@RequestBody RuleReq req) {
        Long id = labRouteService.create(req);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        return R.ok(body);
    }

    /** 编辑（同体；启停状态不动）。不存在 → 5910。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Void> update(@PathVariable Long id, @RequestBody RuleReq req) {
        labRouteService.update(id, req);
        return R.ok();
    }

    /** 启停。不存在 → 5910；启用时判同键重复 5913。 */
    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        labRouteService.setEnabled(id, enabled);
        return R.ok();
    }

    /** 删除。不存在 → 5910。已开医嘱的执行科室是快照，不受影响。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Void> delete(@PathVariable Long id) {
        labRouteService.delete(id);
        return R.ok();
    }

    /**
     * 规则试算：与开单落值走同一个 service 方法。chargeItemId 必填且须为检验类项目（否则 5912）。
     *
     * @return {execDeptId,execDeptName,source:'RULE'|'ITEM'|'NONE',ruleId,ruleName}（无值的键为 null）
     */
    @GetMapping("/resolve")
    public R<Map<String, Object>> resolve(@RequestParam Long chargeItemId,
                                          @RequestParam(required = false) String specimenType,
                                          @RequestParam(required = false) Long orderDeptId) {
        ChargeItem item = chargeItemRepository.findById(chargeItemId)
                .filter(ci -> "LAB".equals(ci.getCategory()))
                .orElseThrow(() -> new HipBizException(5912, "收费项目不存在或不是检验类：" + chargeItemId));
        Resolved r = labRouteService.resolve(item, specimenType, orderDeptId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("execDeptId", r.execDeptId());
        body.put("execDeptName", r.execDeptName());
        body.put("source", r.source());
        body.put("ruleId", r.ruleId());
        body.put("ruleName", r.ruleName());
        return R.ok(body);
    }
}
