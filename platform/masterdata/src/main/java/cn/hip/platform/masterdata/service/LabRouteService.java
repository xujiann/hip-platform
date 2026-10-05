package cn.hip.platform.masterdata.service;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.entity.LabRouteRule;
import cn.hip.platform.masterdata.repository.LabRouteRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * v77 车道 A：检验流向规则——维护校验 + 开单落值用的匹配（偏离表 1016★「根据流向自动获取执行科室」）。
 *
 * <p><b>匹配口径</b>（规划节「匹配口径」，页面文案与本处一致）：
 * 取医嘱 (chargeItemId, specimenType 规范化值, 开单科室=挂号 dept_id)，在 enabled 且 item_category='LAB'
 * 的规则里筛"所有非空键全相等"的候选；排序 = 具体度（项目键 4 + 标本键 2 + 开单科室键 1，分高者先）
 * → priority 升序 → id 升序；取首条的 exec_dept_id。规则指向的科室已停用 → 视为不命中、继续下一候选
 * （warn 日志，不阻断开单）；无命中 → 回落 {@code md_charge_item.exec_dept_id}；仍为空 → null。
 * {@code lab.route.enabled} 非 "1" 时直接回落字典。
 *
 * <p><b>{@link #resolve} 纯读且绝不抛</b>：它跑在医生开单事务里，规则表读不到/配置读不到/任何意外
 * 都只能让这一行"按字典带科室"，不能让医生开不出单——分流是便利，开单是业务。
 * 注意这保证的是"本方法不向上抛"；若底层 SQL 真报错，PG 会把整个事务标为 aborted，
 * 后续落库照样失败——那属于库坏了，不是本方法能兜的。
 *
 * <p><b>标本类型规范化</b>：null/空白 → null；否则去首尾空白、去内部所有空白、大写。规则落库与匹配
 * 两侧用同一函数，医生手填"全 血"与规则"全血"视为同一标本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabRouteService {

    /**
     * 写一律 saveAndFlush / flush：本类的重复判定与匹配都走 jdbc 读库，而 JPA 写在同一事务里不会在 jdbc 读前自动刷出
     * （Hibernate 只在 JPQL/native 查询前 auto-flush）。独立请求各自提交时无感，但被更大的事务（用例、批处理）包住时
     * "刚停用的规则 jdbc 还看见是启用"——同一事务内读写口径必须一致。
     */
    private final LabRouteRuleRepository ruleRepository;
    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    /** sys_config 开关键（V174 种默认 "1"） */
    public static final String CFG_KEY = "lab.route.enabled";

    /** 新增/编辑请求体（控制器与 E2E 同形）。priority 缺省 100。 */
    public record RuleReq(String name, Long chargeItemId, String specimenType, Long orderDeptId,
                          Long execDeptId, Integer priority, String remark) {}

    /**
     * 匹配结果。source：RULE 规则命中 / ITEM 回落收费项目字典 / NONE 两处都没有。
     * ruleId/ruleName 仅 RULE 时非空；execDeptName 仅有科室时非空。
     */
    /**
     * @param routeEnabled 第二轮复核（1016★ 反驳者二/审计者 N6）：开关 lab.route.enabled 关闭时此前与"无规则命中"
     *                     返回体完全一样，页面试算只能印"无规则命中，按收费项目字典"——与事实（根本没查规则）不符。
     *                     现用本键区分：false = 未查规则、直接按字典。source 三档含义不变。
     */
    public record Resolved(Long execDeptId, String execDeptName, String source, Long ruleId, String ruleName,
                           Boolean routeEnabled) {}

    // ==================== 匹配 ====================

    /**
     * 开单落值与页面"规则试算"共用的唯一匹配入口。
     *
     * @param item         收费项目（开单时已取到的实体；不为 null）
     * @param specimenType 医嘱填写的标本类型原文（可空，内部规范化）
     * @param orderDeptId  开单科室 = 挂号 dept_id（可空）
     */
    public Resolved resolve(ChargeItem item, String specimenType, Long orderDeptId) {
        try {
            if (!"1".equals(configReader.get(CFG_KEY, "1"))) {
                Resolved f = fallback(item);
                return new Resolved(f.execDeptId(), f.execDeptName(), f.source(), null, null, false);
            }
            String spec = normalizeSpecimen(specimenType);
            // 非空键全相等：规则键为空即通配；医嘱值为空时带该键的规则不匹配（null = x 为 unknown，自然落空）
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select r.id, r.name, r.exec_dept_id, d.name as dept_name, d.enabled as dept_enabled
                    from lab_route_rule r
                    join sys_dept d on d.id = r.exec_dept_id
                    where r.enabled and r.item_category = 'LAB'
                      and (r.charge_item_id is null or r.charge_item_id = cast(? as bigint))
                      and (r.specimen_type is null or r.specimen_type = cast(? as varchar))
                      and (r.order_dept_id is null or r.order_dept_id = cast(? as bigint))
                    order by (case when r.charge_item_id is not null then 4 else 0 end
                            + case when r.specimen_type is not null then 2 else 0 end
                            + case when r.order_dept_id is not null then 1 else 0 end) desc,
                             r.priority, r.id
                    """, item.getId(), spec, orderDeptId);
            for (Map<String, Object> r : rows) {
                if (!Boolean.TRUE.equals(r.get("dept_enabled"))) {
                    log.warn("检验流向规则 {}「{}」指向的执行科室 {} 已停用，跳过（项目 {} 标本 {} 开单科室 {}）",
                            r.get("id"), r.get("name"), r.get("exec_dept_id"), item.getId(), spec, orderDeptId);
                    continue;
                }
                return new Resolved(((Number) r.get("exec_dept_id")).longValue(), (String) r.get("dept_name"),
                        "RULE", ((Number) r.get("id")).longValue(), (String) r.get("name"), true);
            }
            return fallback(item);
        } catch (Exception e) {
            // 分流是便利，开单是业务：任何意外都回落字典，不向上抛
            log.warn("检验流向规则匹配失败，回落收费项目字典（项目 {}）：{}", item == null ? null : item.getId(), e.toString());
            try {
                return fallback(item);
            } catch (Exception e2) {
                Long dict = item == null ? null : item.getExecDeptId();
                return new Resolved(dict, null, dict == null ? "NONE" : "ITEM", null, null, true);
            }
        }
    }

    /** 回落收费项目字典：有执行科室 → ITEM（不校验启停，与打印/队列 join 的现状口径一致）；无 → NONE */
    private Resolved fallback(ChargeItem item) {
        Long dict = item == null ? null : item.getExecDeptId();
        if (dict == null) {
            return new Resolved(null, null, "NONE", null, null, true);
        }
        List<String> names = jdbc.queryForList("select name from sys_dept where id = ?", String.class, dict);
        return new Resolved(dict, names.isEmpty() ? null : names.get(0), "ITEM", null, null, true);
    }

    /** 标本类型规范化：null/空白 → null；否则去首尾空白、去内部所有空白、大写。规则与医嘱两侧共用。 */
    public static String normalizeSpecimen(String raw) {
        if (raw == null) return null;
        // 第二轮复核（反驳者三实测）：\s 不含全角空格 U+3000 与 NBSP，"血　清"/"血\u00a0清" 此前不命中"血清"；
        // 中文输入法与 Excel 复制粘贴最常带的就是这两种，用 \p{Z}（Unicode 分隔符类）一并吃掉。
        String s = raw.strip().replaceAll("[\\s\\p{Z}]+", "");
        return s.isEmpty() ? null : s.toUpperCase();
    }

    // ==================== 列表 ====================

    /** 规则列表，排序与匹配一致（具体度降序 → priority → id）。includeDisabled=false 只列启用。 */
    public List<Map<String, Object>> list(boolean includeDisabled) {
        return jdbc.queryForList("""
                select r.id, r.name, r.item_category as "itemCategory",
                       r.charge_item_id as "chargeItemId", ci.code as "chargeItemCode", ci.name as "chargeItemName",
                       r.specimen_type as "specimenType",
                       r.order_dept_id as "orderDeptId", od.name as "orderDeptName",
                       r.exec_dept_id as "execDeptId", ed.name as "execDeptName",
                       r.priority, r.enabled, r.remark,
                       to_char(r.updated_at at time zone 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS') as "updatedAt"
                from lab_route_rule r
                left join md_charge_item ci on ci.id = r.charge_item_id
                left join sys_dept od on od.id = r.order_dept_id
                left join sys_dept ed on ed.id = r.exec_dept_id
                where (? or r.enabled)
                order by (case when r.charge_item_id is not null then 4 else 0 end
                        + case when r.specimen_type is not null then 2 else 0 end
                        + case when r.order_dept_id is not null then 1 else 0 end) desc,
                         r.priority, r.id
                """, includeDisabled);
    }

    // ==================== 增 / 改 / 启停 / 删 ====================
    // 纪律：**只读校验一律前置于任何对实体的写**（测试方法论⑨推论）。validate() 只产出值对象、不碰实体；
    // 同键判定也对值对象做；全部通过后才 copyTo 实体。否则被拒绝的编辑会把半套新键留在托管实体上，
    // 同事务里下一次 flush 就把被拒的键写进库（独立请求回滚无感，被更大事务包住时就是真脏数据）。

    /** 校验后的规则字段（不含 enabled / 时间） */
    private record Normalized(String name, Long chargeItemId, String specimenType, Long orderDeptId,
                              Long execDeptId, Integer priority, String remark) {}

    /** 新增（默认启用）。校验顺序：字段 4000 → 执行科室 5911 → 项目 5912 → 开单科室 5914 → 标本字典 5916/5917 → 同键重复 5913。 */
    public Long create(RuleReq req) {
        Normalized n = validate(req);
        checkDuplicate(n, null);
        LabRouteRule r = new LabRouteRule();
        copyTo(r, n);
        return ruleRepository.saveAndFlush(r).getId();
    }

    /** 编辑（启停状态不动）。不存在 → 5910；本条已停用时不判同键重复（停用规则不参与匹配，也不占键）。 */
    public void update(Long id, RuleReq req) {
        LabRouteRule r = ruleRepository.findById(id)
                .orElseThrow(() -> new HipBizException(5910, "流向规则不存在：" + id));
        Normalized n = validate(req);
        if (Boolean.TRUE.equals(r.getEnabled())) {
            checkDuplicate(n, id);
        }
        copyTo(r, n);
        r.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        ruleRepository.saveAndFlush(r);
    }

    /** 启停。不存在 → 5910；启用一条停用规则时同样判同键重复 5913（否则两条同键规则会同时生效）。幂等。 */
    public void setEnabled(Long id, boolean enabled) {
        LabRouteRule r = ruleRepository.findById(id)
                .orElseThrow(() -> new HipBizException(5910, "流向规则不存在：" + id));
        if (enabled && !Boolean.TRUE.equals(r.getEnabled())) {
            checkDuplicate(new Normalized(r.getName(), r.getChargeItemId(), r.getSpecimenType(), r.getOrderDeptId(),
                    r.getExecDeptId(), r.getPriority(), r.getRemark()), id);
        }
        r.setEnabled(enabled);
        r.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        ruleRepository.saveAndFlush(r);
    }

    /** 删除。不存在 → 5910。已开医嘱的 exec_dept_id 是快照，不受影响。 */
    public void delete(Long id) {
        if (!ruleRepository.existsById(id)) {
            throw new HipBizException(5910, "流向规则不存在：" + id);
        }
        ruleRepository.deleteById(id);
        ruleRepository.flush();
    }

    /** 请求体校验（纯读，不碰实体） */
    private Normalized validate(RuleReq req) {
        if (req == null) throw new HipBizException(4000, "请求参数不正确：请求体为空");
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty() || name.length() > 64) {
            throw new HipBizException(4000, "规则名称必填，且不超过 64 字");
        }
        if (req.execDeptId() == null) {
            throw new HipBizException(4000, "执行科室必填");
        }
        String spec = normalizeSpecimen(req.specimenType());
        if (spec != null && spec.length() > 32) {
            throw new HipBizException(4000, "标本类型不超过 32 字");
        }
        String remark = req.remark() == null || req.remark().isBlank() ? null : req.remark().strip();
        if (remark != null && remark.length() > 255) {
            throw new HipBizException(4000, "备注不超过 255 字");
        }
        // 执行科室：存在且启用
        List<Boolean> execEnabled = jdbc.queryForList(
                "select enabled from sys_dept where id = ?", Boolean.class, req.execDeptId());
        if (execEnabled.isEmpty() || !Boolean.TRUE.equals(execEnabled.get(0))) {
            throw new HipBizException(5911, "执行科室不存在或已停用：" + req.execDeptId());
        }
        // 收费项目：若给则必须存在且为检验类（规则键只认 category=LAB 项目）
        if (req.chargeItemId() != null) {
            List<String> cat = jdbc.queryForList(
                    "select category from md_charge_item where id = ?", String.class, req.chargeItemId());
            if (cat.isEmpty() || !"LAB".equals(cat.get(0))) {
                throw new HipBizException(5912, "收费项目不存在或不是检验类：" + req.chargeItemId());
            }
        }
        // 开单科室：若给则必须存在（停用科室仍可能有历史挂号，不要求启用）
        if (req.orderDeptId() != null) {
            Integer n = jdbc.queryForObject("select count(*) from sys_dept where id = ?", Integer.class, req.orderDeptId());
            if (n == null || n == 0) {
                throw new HipBizException(5914, "开单科室不存在：" + req.orderDeptId());
            }
        }
        // 标本类型：若给则规范化后须等于某条**启用**字典项名称的规范化值（v78 字典化，md_specimen_type）。
        // 库里仍存规范化名称（列不变、匹配逻辑不变）；/resolve 与开单落值不走本校验，字典外值自然不命中。
        // 字典名称也在 Java 侧用同一函数规范化（PG 正则不认 \p{Z}，两侧口径必须出自同一处）。
        if (spec != null) {
            requireSpecimenInDictionary(spec, req.specimenType());
        }
        return new Normalized(name, req.chargeItemId(), spec, req.orderDeptId(), req.execDeptId(),
                req.priority() == null ? 100 : req.priority(), remark);
    }

    /** 字典里无同规范化值的行 → 5916；有但全部停用 → 5917（同规范化值的行按设计只会有一条，见 SpecimenTypeController） */
    private void requireSpecimenInDictionary(String normalized, String raw) {
        boolean found = false;
        for (Map<String, Object> d : jdbc.queryForList("select name, enabled from md_specimen_type")) {
            if (normalized.equals(normalizeSpecimen((String) d.get("name")))) {
                if (Boolean.TRUE.equals(d.get("enabled"))) return;
                found = true;
            }
        }
        String shown = raw == null ? normalized : raw.strip();
        if (found) {
            throw new HipBizException(5917, "标本类型「" + shown + "」已停用；请先在基础数据→标本类型里启用它，或改选其他标本");
        }
        throw new HipBizException(5916, "标本类型「" + shown + "」不在字典中；请从下拉选择，或先在基础数据→标本类型里建档");
    }

    private static void copyTo(LabRouteRule r, Normalized n) {
        r.setName(n.name());
        r.setItemCategory("LAB");
        r.setChargeItemId(n.chargeItemId());
        r.setSpecimenType(n.specimenType());
        r.setOrderDeptId(n.orderDeptId());
        r.setExecDeptId(n.execDeptId());
        r.setPriority(n.priority());
        r.setRemark(n.remark());
    }

    /**
     * 同键重复只在<b>启用</b>规则间判：三元组 chargeItemId / specimenType(规范化) / orderDeptId 全相等
     * （null 与 null 相等，故用 is not distinct from）。selfId 非空时排除自身（编辑/启用）。
     */
    private void checkDuplicate(Normalized r, Long selfId) {
        List<Map<String, Object>> dup = jdbc.queryForList("""
                select id, name from lab_route_rule
                where enabled and item_category = 'LAB'
                  and charge_item_id is not distinct from cast(? as bigint)
                  and specimen_type is not distinct from cast(? as varchar)
                  and order_dept_id is not distinct from cast(? as bigint)
                  and id <> coalesce(cast(? as bigint), -1)
                order by id limit 1
                """, r.chargeItemId(), r.specimenType(), r.orderDeptId(), selfId);
        if (!dup.isEmpty()) {
            throw new HipBizException(5913, "相同 项目/标本类型/开单科室 已有启用规则「" + dup.get(0).get("name")
                    + "」（id " + dup.get(0).get("id") + "）；请编辑该规则或先停用它");
        }
    }
}
