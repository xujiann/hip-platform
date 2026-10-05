package cn.hip.platform.masterdata.web;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.service.LabRouteService;
import cn.hip.platform.masterdata.service.LabRouteService.Resolved;
import cn.hip.platform.masterdata.service.LabRouteService.RuleReq;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
 * v78 起 specimenType 须在标本类型字典内：5916 不存在 / 5917 已停用（校验在 service）。
 * 开单落值路径零新码（规则读失败回落字典，不抛）。
 *
 * <p><b>5913 的第二道腿（v78，V176 {@code uq_lab_route_rule_key}）</b>：service 的同键判定是读-判-写，两位技师同时建同键规则
 * 会双双通过；启用行同键唯一部分索引在数据库兜底，撞索引抛出的 DataIntegrityViolationException（Spring 对 JPA flush 的翻译；
 * DuplicateKeyException 是它的子类）在建/改/启用三处翻成同一个 5913——用户看到的仍是"同键已有启用规则"，不是 4090/4091。
 * 撞索引时事务已 aborted，不能再读库补充对方规则名，文案只能少这一段。
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
        Long id;
        try {
            id = labRouteService.create(req);
        } catch (DataIntegrityViolationException e) {
            throw translateUniqueKey(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        return R.ok(body);
    }

    /** 编辑（同体；启停状态不动）。不存在 → 5910。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Void> update(@PathVariable Long id, @RequestBody RuleReq req) {
        try {
            labRouteService.update(id, req);
        } catch (DataIntegrityViolationException e) {
            throw translateUniqueKey(e);
        }
        return R.ok();
    }

    /** 启停。不存在 → 5910；启用时判同键重复 5913。 */
    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        try {
            labRouteService.setEnabled(id, enabled);
        } catch (DataIntegrityViolationException e) {
            throw translateUniqueKey(e);
        }
        return R.ok();
    }

    /** V176 唯一部分索引名（只翻这一个索引的冲突；别的完整性错误照旧交给全局处理器 4090/4091） */
    static final String UNIQUE_KEY_INDEX = "uq_lab_route_rule_key";

    /** 同键索引冲突 → 5913（与 service 读-判-写的 5913 同文案前缀）；其他完整性异常原样抛回 */
    static RuntimeException translateUniqueKey(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(UNIQUE_KEY_INDEX)) {
                return new HipBizException(5913,
                        "相同 项目/标本类型/开单科室 已有启用规则（刚被他人同时写入）；请刷新列表后编辑该规则或先停用它");
            }
        }
        return e;
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
