package cn.hip.platform.masterdata.web;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.masterdata.service.LabRouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * v78 车道 A：标本类型字典（V176 {@code md_specimen_type}）维护端点（1016★ 第二轮复核仍开项"标本类型自由文本"）。
 *
 * <p>独立成类，不碰 {@link MasterDataController}（v76/v77 同一纪律：新端点单独成类，避免多车道同改一文件）。
 * 它是两处标本输入的共同取值来源：流向规则维护只能选字典里的<b>启用</b>项
 * （{@link LabRouteService} validate 5916/5917）；医生站下拉取启用项、仍允许手工录入字典外值
 * （开单 {@code OrderLine.specimenType} 后端不校验，字典外值自然不命中规则、回落收费项目字典）。
 *
 * <p>权限：读登录即可（医生站下拉、规则页都要拿它）；写 {@code hasAnyRole('ADMIN','TECHNICIAN')}——
 * 检验科维护自己的标本字典，与流向规则同口径（V177 菜单 186 同授这两个角色）。
 *
 * <p>错误码（5915–5917，规划节预分配）：<b>5915</b> 编码或名称已存在 / <b>5916</b> 标本类型不存在 /
 * <b>5917</b> 已停用或仍被启用规则引用（停用、删除、改名三处守卫与规则引用校验同码）。字段缺失/超长走通用 4000。
 *
 * <p><b>ruleCount 与引用守卫的口径</b>：启用规则里 {@code specimen_type}（落库即规范化值）等于本行名称规范化值
 * （{@link LabRouteService#normalizeSpecimen}）的条数——规则表存的是规范化值而不是字典 id（列不变、匹配逻辑不变），
 * 所以"引用"只能按规范化值等值算。停用规则不算引用（不参与匹配、不占键），可以直接停用/删除它引用的字典项。
 *
 * <p><b>名称唯一按规范化值判</b>（比表上的 name unique 更严）：两行名称只差空白/大小写（"全血"与"全 血"）
 * 规范化后相同，规则匹配时分不出谁是谁、ruleCount 会双计；故新增/改名时规范化撞既有行一律 5915。
 * 编码一律转大写落库（与 ICD/费用类别同）；名称只 strip 首尾空白，内部空白保留原样显示。
 */
@RestController
@RequestMapping("/api/masterdata/specimen-types")
@RequiredArgsConstructor
public class SpecimenTypeController {

    private final JdbcTemplate jdbc;

    /** 编码：字母数字开头，允许 _ -，≤16（列宽） */
    private static final Pattern CODE_RE = Pattern.compile("^[A-Z0-9][A-Z0-9_\\-]{0,15}$");

    /** 新增/编辑请求体。编辑时 code 被忽略（编码建档后不可改）。sortNo 缺省 0。 */
    public record SpecimenTypeReq(String code, String name, Integer sortNo) {}

    // ==================== 列表 ====================

    /**
     * 字典列表，按 sort_no、code 排。默认只返启用项（医生站/规则页下拉），all=true 返回全部（维护页）。
     *
     * @return [{id,code,name,sortNo,enabled,ruleCount}]
     */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(defaultValue = "false") boolean all) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, sort_no as "sortNo", enabled
                from md_specimen_type
                where (? or enabled)
                order by sort_no, code
                """, all);
        // 引用数按规范化值算（规范化在 Java 侧、与规则落库同一函数），两条查询拼起来，不逐行子查询
        Map<String, Long> refs = enabledRuleCountsBySpecimen();
        for (Map<String, Object> r : rows) {
            r.put("ruleCount", refs.getOrDefault(LabRouteService.normalizeSpecimen((String) r.get("name")), 0L));
        }
        return R.ok(rows);
    }

    // ==================== 新增 / 编辑 / 启停 / 删除 ====================

    /** 新增（默认启用）。编码或名称（按规范化值）已存在 → 5915；字段不合法 → 4000。返回 {id}。 */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Map<String, Object>> create(@RequestBody SpecimenTypeReq req) {
        if (req == null) throw new HipBizException(4000, "请求参数不正确：请求体为空");
        String code = normCode(req.code());
        String name = normName(req.name());
        checkNameNotTaken(name, null);
        // 编码/名称精确重复走数据库唯一约束 + 受影响行数判定（费用类别同款），不做读-判-写
        List<Long> ids = jdbc.queryForList("""
                insert into md_specimen_type(code, name, sort_no) values (?,?,?)
                on conflict do nothing
                returning id
                """, Long.class, code, name, req.sortNo() == null ? 0 : req.sortNo());
        if (ids.isEmpty()) {
            throw new HipBizException(5915, "标本类型编码「" + code + "」或名称「" + name + "」已存在；已停用的请在列表中启用");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", ids.get(0));
        return R.ok(body);
    }

    /**
     * 编辑名称/排序（编码与启停状态不动）。不存在 → 5916；新名称撞其他行 → 5915。
     * <p><b>改名守卫</b>：名称的规范化值变了、而旧值仍被启用规则引用 → 5917。规则表存的是规范化名称，
     * 改名不级联（级联可能撞 uq_lab_route_rule_key，且规则名/备注里的旧名改不了），放行则这些规则
     * 从此指向一个字典里不存在的值、永远不再命中——与停用/删除守卫同一个理由同一个码。
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Void> update(@PathVariable Long id, @RequestBody SpecimenTypeReq req) {
        if (req == null) throw new HipBizException(4000, "请求参数不正确：请求体为空");
        String name = normName(req.name());
        Map<String, Object> row = requireRow(id);
        String oldNorm = LabRouteService.normalizeSpecimen((String) row.get("name"));
        if (!Objects.equals(oldNorm, LabRouteService.normalizeSpecimen(name))) {
            checkNameNotTaken(name, id);
            long refs = enabledRuleCount(oldNorm);
            if (refs > 0) {
                throw new HipBizException(5917, "标本类型「" + row.get("name") + "」仍被 " + refs
                        + " 条启用的流向规则引用，不能改名；请先停用或修改这些规则");
            }
        }
        jdbc.update("update md_specimen_type set name = ?, sort_no = ?, updated_at = now() where id = ?",
                name, req.sortNo() == null ? 0 : req.sortNo(), id);
        return R.ok();
    }

    /** 启停。不存在 → 5916；停用时仍被启用规则引用 → 5917。幂等：已是目标状态也返回成功。 */
    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        Map<String, Object> row = requireRow(id);
        if (!enabled) {
            requireNotReferenced(row, "停用");
        }
        jdbc.update("update md_specimen_type set enabled = ?, updated_at = now() where id = ?", enabled, id);
        return R.ok();
    }

    /** 删除。不存在 → 5916；仍被启用规则引用 → 5917（删比停更彻底，同一守卫）。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    @Transactional
    public R<Void> delete(@PathVariable Long id) {
        Map<String, Object> row = requireRow(id);
        requireNotReferenced(row, "删除");
        jdbc.update("delete from md_specimen_type where id = ?", id);
        return R.ok();
    }

    // ==================== 工具 ====================

    private Map<String, Object> requireRow(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, code, name, enabled from md_specimen_type where id = ?", id);
        if (rows.isEmpty()) {
            throw new HipBizException(5916, "标本类型不存在：" + id);
        }
        return rows.get(0);
    }

    private void requireNotReferenced(Map<String, Object> row, String action) {
        long refs = enabledRuleCount(LabRouteService.normalizeSpecimen((String) row.get("name")));
        if (refs > 0) {
            throw new HipBizException(5917, "标本类型「" + row.get("name") + "」仍被 " + refs
                    + " 条启用的流向规则引用，不能" + action + "；请先停用或修改这些规则");
        }
    }

    /** 名称按规范化值与其他行比（selfId 排除自身）；撞 → 5915 */
    private void checkNameNotTaken(String name, Long selfId) {
        String norm = LabRouteService.normalizeSpecimen(name);
        List<Map<String, Object>> rows = jdbc.queryForList("select id, name from md_specimen_type");
        for (Map<String, Object> r : rows) {
            if (selfId != null && selfId.equals(((Number) r.get("id")).longValue())) continue;
            if (Objects.equals(norm, LabRouteService.normalizeSpecimen((String) r.get("name")))) {
                throw new HipBizException(5915, "标本类型名称「" + name + "」与已有项「" + r.get("name")
                        + "」相同（忽略空白与大小写）；已停用的请在列表中启用");
            }
        }
    }

    /** 启用规则按 specimen_type（落库即规范化值）分组计数 */
    private Map<String, Long> enabledRuleCountsBySpecimen() {
        Map<String, Long> m = new HashMap<>();
        jdbc.query("select specimen_type, count(*) as n from lab_route_rule where enabled and specimen_type is not null group by 1",
                rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    private long enabledRuleCount(String normalizedName) {
        if (normalizedName == null) return 0;
        Long n = jdbc.queryForObject("select count(*) from lab_route_rule where enabled and specimen_type = ?",
                Long.class, normalizedName);
        return n == null ? 0 : n;
    }

    private static String normCode(String raw) {
        String c = raw == null ? "" : raw.strip().toUpperCase();
        if (!CODE_RE.matcher(c).matches()) {
            throw new HipBizException(4000, "标本类型编码必填（字母数字开头，仅含字母数字及 _ -，最长 16 位）");
        }
        return c;
    }

    private static String normName(String raw) {
        String n = raw == null ? "" : raw.strip();
        if (n.isEmpty() || n.length() > 32 || LabRouteService.normalizeSpecimen(n) == null) {
            throw new HipBizException(4000, "标本类型名称必填，且不超过 32 字");
        }
        return n;
    }
}
