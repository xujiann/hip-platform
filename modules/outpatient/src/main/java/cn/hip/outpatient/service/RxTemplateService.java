package cn.hip.outpatient.service;

import cn.hip.outpatient.service.RegistrationService.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v44 车道F：处方模板与协定处方（技术偏离表 999★ 模板+模板编辑 / 1000★ 协定处方）。
 *
 * <p><b>安全口径（本类最要紧的一条，改动本类前必读）</b>：
 * 本服务只负责<b>维护模板</b>与<b>把模板明细吐成开单表单能直接填的形状</b>。
 * <b>它不开单，也永远不许开单。</b>医生套用模板后仍走原有
 * {@code POST /api/outpatient/doctor/{registrationId}/orders} →
 * {@link DoctorStationService#createOrders}，其中的皮试/过敏禁忌（4012）、同诊重复用药（4013）、
 * 抗菌药分级处方权（4014）、CDSS 相互作用与年龄禁忌（4015/4017）、v43 停用药预检（8016）、
 * 库存预警（stockWarnAvailable）<b>一条不少地照常执行</b>。
 * <b>严禁</b>为了"提高配方速度"在本类（或任何地方）新增一条批量落 {@code outp_order} 的端点——
 * 那等于给用药安全开一个后门，模板会立刻变成绕过合理用药审查的通道。
 * 本类全文没有一处写 {@code outp_order}，也没有 import 任何 order 仓储，这是刻意的。
 *
 * <p><b>零核心写路径改动</b>：{@code createOrders} 一行未动，
 * {@code DoctorStationService}/{@code DoctorStationController} 一行未动。
 *
 * <p><b>三级作用范围的权限口径</b>（用 {@code V44RxTemplateTest} 把越权路径钉死）：
 * <table>
 *   <tr><th>scope</th><th>可见</th><th>可改（含停用/启用/删除）</th></tr>
 *   <tr><td>PERSONAL 个人</td><td>仅本人（ADMIN 也看不到别人的个人模板）</td><td>仅本人</td></tr>
 *   <tr><td>DEPT 科室</td><td>本科室医生 + ADMIN</td><td>创建者 + ADMIN</td></tr>
 *   <tr><td>HOSPITAL 全院</td><td>所有人</td><td>仅 ADMIN</td></tr>
 * </table>
 * ADMIN 刻意<b>不</b>能看别人的个人模板：个人模板是医生自己的用药习惯草稿，
 * 「全院模板仅 ADMIN 可改」保护的是全院口径，不是给管理员一把翻看私人草稿的钥匙。
 *
 * <p><b>协定处方（AGREED，1000★）</b>：由药事委员会审定的固定组合，
 * <b>明细任何人都不可就地修改（ADMIN 亦然，见 4064）</b>——要改就停用旧版、另建新版。
 * 理由与 emr_template「复制为新模板」同源：已按旧版开出的处方必须能追溯到当时的那一版；
 * 就地改写会让历史处方的来源凭空变脸。套用时整组带入（每行 {@code locked=true}）。
 *
 * <p><b>错误码 4060–4069</b>（见 docs/错误码分段.md；4040/4041/4050/4090/4091 是通用保留段，
 * 业务代码禁用，故本段从 4060 起）：
 * 4060 模板不存在/无权使用或修改、4061 名称必填、4062 明细为空或明细行不成立、
 * 4063 作用范围非法、4064 协定处方不可修改明细、4069 模板类别非法。
 */
@Service
@RequiredArgsConstructor
public class RxTemplateService {

    private final JdbcTemplate jdbc;

    /** 与 outp_order.order_type / md_charge_item.category 同域 */
    private static final Set<String> ORDER_TYPES = Set.of("DRUG", "LAB", "EXAM", "TREAT");
    private static final Set<String> SCOPES = Set.of("PERSONAL", "DEPT", "HOSPITAL");
    private static final Set<String> CATEGORIES = Set.of("RX", "AGREED");

    // ------------------------------------------------------------------
    // 请求体
    // ------------------------------------------------------------------

    /**
     * 模板明细行入参。<b>字段名与 {@link DoctorStationService.OrderLine} 逐字段同名</b>——
     * 前端从「套用」返回体拿到的行，原样就能塞进开单请求，不需要任何转换表。
     */
    public record TemplateLine(String orderType, Long itemId, Integer qty,
                               String usageRoute, String frequency, String dosePerTime,
                               Integer days, Integer sortNo) {}

    /** 模板头入参；{@code lines} 为 null 表示"本次不动明细"（PUT 时用） */
    public record TemplateReq(String name, String scope, Long deptId, String category,
                              String remark, List<TemplateLine> lines) {}

    // ------------------------------------------------------------------
    // 当前用户画像
    // ------------------------------------------------------------------

    /** 当前用户的判权要素：科室 + 是否 ADMIN + 是否药事管理人员（ADMIN/PHARMACIST） */
    private record Actor(Long userId, Long deptId, boolean admin, boolean pharmacyMgr) {}

    private Actor actorOf(Long userId) {
        if (userId == null) {
            return new Actor(null, null, false, false);
        }
        List<Long> depts = jdbc.queryForList(
                "select dept_id from sys_user where id = ?", Long.class, userId);
        Long deptId = depts.isEmpty() ? null : depts.get(0);
        List<String> roles = jdbc.queryForList("""
                select r.code from sys_user_role ur join sys_role r on r.id = ur.role_id
                where ur.user_id = ?
                """, String.class, userId);
        return new Actor(userId, deptId, roles.contains("ADMIN"),
                roles.contains("ADMIN") || roles.contains("PHARMACIST"));
    }

    /** 模板头的判权快照（只取判权要用的列，避免把整行 Map 传来传去） */
    private record Tpl(Long id, String scope, Long ownerId, Long deptId,
                       String category, boolean enabled) {}

    private Tpl load(Long id) {
        List<Tpl> rows = jdbc.query(
                "select id, scope, owner_id, dept_id, category, enabled from rx_template where id = ?",
                (rs, i) -> new Tpl(rs.getLong("id"), rs.getString("scope"),
                        (Long) rs.getObject("owner_id"), (Long) rs.getObject("dept_id"),
                        rs.getString("category"), rs.getBoolean("enabled")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean canSee(Tpl t, Actor a) {
        return switch (t.scope()) {
            // 个人模板对 ADMIN 也不可见（见类注释）
            case "PERSONAL" -> a.userId() != null && a.userId().equals(t.ownerId());
            case "DEPT" -> a.admin() || (a.deptId() != null && a.deptId().equals(t.deptId()));
            default -> true;   // HOSPITAL
        };
    }

    private boolean canEdit(Tpl t, Actor a) {
        if (a.userId() == null) return false;
        return switch (t.scope()) {
            case "PERSONAL" -> a.userId().equals(t.ownerId());
            case "DEPT" -> a.userId().equals(t.ownerId()) || a.admin();
            default -> a.admin();   // HOSPITAL：仅 ADMIN
        };
    }

    /** 读侧守卫：拿不到就是 4060（"不存在"与"无权"合并成一句，不泄露他人模板的存在性） */
    private Tpl requireVisible(Long id, Actor a) {
        Tpl t = load(id);
        if (t == null || !canSee(t, a)) {
            throw new BizException(4060, "处方模板不存在或无权使用");
        }
        return t;
    }

    private Tpl requireEditable(Long id, Actor a) {
        Tpl t = load(id);
        if (t == null || !canSee(t, a)) {
            throw new BizException(4060, "处方模板不存在或无权使用");
        }
        if (!canEdit(t, a)) {
            throw new BizException(4060, "无权修改该模板：%s 模板只能由 %s 维护"
                    .formatted(scopeName(t.scope()), editorName(t.scope())));
        }
        return t;
    }

    private static String scopeName(String scope) {
        return switch (scope) {
            case "PERSONAL" -> "个人";
            case "DEPT" -> "科室";
            default -> "全院";
        };
    }

    private static String editorName(String scope) {
        return switch (scope) {
            case "PERSONAL" -> "本人";
            case "DEPT" -> "创建者本人或系统管理员";
            default -> "系统管理员";
        };
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /**
     * 按当前医生的可见范围列模板：个人（本人的）+ 科室（本科室的）+ 全院。
     *
     * <p>{@code includeDisabled=false}（默认，医生站套用路径）只出启用中的模板；
     * 维护页传 true 才看得到已停用的——停用了还能被套用，"停用"就等于没做。
     */
    public List<Map<String, Object>> list(Long userId, String category, String keyword,
                                          boolean includeDisabled) {
        Actor a = actorOf(userId);
        StringBuilder sql = new StringBuilder("""
                select t.*, u.real_name as owner_name, d.name as dept_name,
                       (select count(*) from rx_template_line l where l.template_id = t.id) as line_count
                from rx_template t
                left join sys_user u on u.id = t.owner_id
                left join sys_dept d on d.id = t.dept_id
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (!includeDisabled) {
            sql.append(" and t.enabled ");
        }
        if (category != null && !category.isBlank()) {
            sql.append(" and t.category = ? ");
            args.add(category.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and t.name like ? ");
            args.add("%" + keyword.trim() + "%");
        }
        // 可见范围三支或，与 canSee 严格同构（改一处必须改两处，故用例对两条路径都断言）
        sql.append(" and ( t.scope = 'HOSPITAL' ");
        if (a.userId() != null) {
            sql.append(" or (t.scope = 'PERSONAL' and t.owner_id = ?) ");
            args.add(a.userId());
        }
        if (a.admin()) {
            sql.append(" or t.scope = 'DEPT' ");
        } else if (a.deptId() != null) {
            sql.append(" or (t.scope = 'DEPT' and t.dept_id = ?) ");
            args.add(a.deptId());
        }
        sql.append(" ) order by t.category, t.scope, t.name, t.id ");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            Tpl t = new Tpl(((Number) r.get("id")).longValue(), (String) r.get("scope"),
                    r.get("owner_id") == null ? null : ((Number) r.get("owner_id")).longValue(),
                    r.get("dept_id") == null ? null : ((Number) r.get("dept_id")).longValue(),
                    (String) r.get("category"), Boolean.TRUE.equals(r.get("enabled")));
            // 前端据此决定按钮的显隐：后端仍会再校验一次，这里只是别摆死按钮
            r.put("editable", canEdit(t, a));
            // 协定处方的明细不可改（4064）——维护页要按这个标志把明细编辑区整块置灰
            r.put("linesLocked", "AGREED".equals(t.category()));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 明细（套用入口的返回体）
    // ------------------------------------------------------------------

    /**
     * 取模板明细，<b>返回体直接就是开单表单的行</b>。
     *
     * <p>每行的 {@code orderType/itemId/qty/usageRoute/frequency/dosePerTime/days} 七个键
     * 与 {@link DoctorStationService.OrderLine} 的记录分量<b>逐字段同名同义</b>，
     * 前端拿到即可原样 push 进开单行数组、再原样 POST 给既有开单端点，无需任何字段转换。
     * 其余键是<b>只读展示/提示</b>用的附加信息（后端 {@code createOrders} 会忽略多余键）：
     * <ul>
     *   <li>{@code itemName/spec/unit/unitPrice/category} —— 开单区列表回显（{@code category}
     *       与 {@code orderType} 同值，对检验检查治疗行来说它就是 md_charge_item.category）；</li>
     *   <li>{@code itemExists/itemEnabled/stock} —— 模板建好之后主数据可能变（药品被停用、
     *       项目被删）。这里<b>只提示不拦截</b>：真正的拦截在开单端点（停用药 8016、
     *       项目不存在 4004/4005），套用页提前把话说在前头，避免"填完才被打回"。</li>
     *   <li>{@code locked} —— 协定处方为 true，前端须整组带入且禁用逐行编辑。</li>
     * </ul>
     *
     * @param forEdit true=维护页查看（允许看已停用的模板，但要求可改权限）；
     *                false=医生站套用（已停用模板一律 4060，停用了还能套等于没停）
     */
    public List<Map<String, Object>> lines(Long userId, Long templateId, boolean forEdit) {
        Actor a = actorOf(userId);
        Tpl t = forEdit ? requireEditable(templateId, a) : requireVisible(templateId, a);
        if (!forEdit && !t.enabled()) {
            throw new BizException(4060, "该处方模板已停用，不可套用");
        }
        boolean locked = "AGREED".equals(t.category());
        List<Map<String, Object>> raw = jdbc.queryForList("""
                select l.order_type, l.item_id, l.qty, l.usage_route, l.frequency,
                       l.dose_per_time, l.days, l.sort_no
                from rx_template_line l where l.template_id = ? order by l.sort_no, l.id
                """, templateId);
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            String orderType = (String) r.get("order_type");
            Long itemId = ((Number) r.get("item_id")).longValue();
            // 用 LinkedHashMap 固定键序：与 OrderLine 分量同序，便于人肉核对与用例断言
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderType", orderType);
            m.put("itemId", itemId);
            m.put("qty", r.get("qty"));
            m.put("usageRoute", r.get("usage_route"));
            m.put("frequency", r.get("frequency"));
            m.put("dosePerTime", r.get("dose_per_time"));
            m.put("days", r.get("days"));
            // ↓ 以下均为展示/提示字段，开单端点忽略
            m.put("sortNo", r.get("sort_no"));
            m.put("category", orderType);
            m.put("locked", locked);
            m.putAll(itemInfo(orderType, itemId));
            out.add(m);
        }
        return out;
    }

    /** 主数据回显：药品与收费项目分表，缺失时给出 itemExists=false 让前端标红而不是静默 */
    private Map<String, Object> itemInfo(String orderType, Long itemId) {
        Map<String, Object> m = new LinkedHashMap<>();
        String sql = "DRUG".equals(orderType)
                ? "select name, spec, unit, price, enabled, stock from md_drug where id = ?"
                : "select name, null as spec, unit, price, enabled, null as stock from md_charge_item where id = ?";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, itemId);
        if (rows.isEmpty()) {
            m.put("itemName", "（项目已不存在 #" + itemId + "）");
            m.put("spec", null);
            m.put("unit", null);
            m.put("unitPrice", null);
            m.put("itemExists", false);
            m.put("itemEnabled", false);
            m.put("stock", null);
            return m;
        }
        Map<String, Object> r = rows.get(0);
        m.put("itemName", r.get("name"));
        m.put("spec", r.get("spec"));
        m.put("unit", r.get("unit"));
        m.put("unitPrice", r.get("price"));
        m.put("itemExists", true);
        m.put("itemEnabled", Boolean.TRUE.equals(r.get("enabled")));
        m.put("stock", r.get("stock"));
        return m;
    }

    // ------------------------------------------------------------------
    // 写侧
    // ------------------------------------------------------------------

    @Transactional
    public Long create(Long userId, TemplateReq req) {
        Actor a = actorOf(userId);
        if (a.userId() == null) {
            throw new BizException(4060, "无法识别当前登录用户，不能建模板");
        }
        String name = requireName(req);
        String category = normCategory(req.category());
        String scope = normScope(req.scope());
        Long deptId = resolveDeptId(scope, req.deptId(), a);
        if ("HOSPITAL".equals(scope) && !a.admin()) {
            throw new BizException(4060, "全院模板只能由系统管理员建立");
        }
        // 协定处方是药事管理动作（药事委员会审定的固定组合），不是医生的个人习惯，
        // 故建档限 ADMIN/PHARMACIST——与 v43 药品启停「授权给真正的责任人」同口径。
        if ("AGREED".equals(category) && !a.pharmacyMgr()) {
            throw new BizException(4060, "协定处方须由药事管理人员（管理员/药师）建档");
        }
        List<TemplateLine> lines = requireLines(req.lines());
        Long id = jdbc.queryForObject("""
                insert into rx_template(name, scope, owner_id, dept_id, category, enabled, remark, created_by)
                values (?, ?, ?, ?, ?, true, ?, ?) returning id
                """, Long.class, name, scope, a.userId(), deptId, category, trimOrNull(req.remark()), a.userId());
        replaceLines(id, lines);
        return id;
    }

    /**
     * 改模板。{@code lines} 为 null 时只改头（名称/范围/科室/备注），明细原样不动；
     * 非 null 时整组替换——<b>协定处方走到这里一律 4064</b>。
     */
    @Transactional
    public void update(Long userId, Long id, TemplateReq req) {
        Actor a = actorOf(userId);
        Tpl t = requireEditable(id, a);
        String name = requireName(req);
        String scope = req.scope() == null || req.scope().isBlank() ? t.scope() : normScope(req.scope());
        Long deptId = resolveDeptId(scope, req.deptId() != null ? req.deptId() : t.deptId(), a);
        if ("HOSPITAL".equals(scope) && !a.admin()) {
            throw new BizException(4060, "全院模板只能由系统管理员维护");
        }
        if (req.lines() != null && "AGREED".equals(t.category())) {
            // 1000★ 的立身之本：协定处方是被审定的固定组合，谁都不能就地改明细（ADMIN 亦然）。
            // 要改就停用旧版另建新版，历史处方才追得到当时用的是哪一版。
            throw new BizException(4064,
                    "协定处方的明细由药事委员会固定，不可修改；如需调整请停用本模板后另建新版");
        }
        jdbc.update("""
                update rx_template set name = ?, scope = ?, dept_id = ?, remark = ? where id = ?
                """, name, scope, deptId, trimOrNull(req.remark()), id);
        if (req.lines() != null) {
            replaceLines(id, requireLines(req.lines()));
        }
    }

    /** 停用/启用（软开关，不删行——历史处方仍要能解释当时照的是哪张模板） */
    @Transactional
    public void setEnabled(Long userId, Long id, boolean enabled) {
        Actor a = actorOf(userId);
        requireEditable(id, a);
        jdbc.update("update rx_template set enabled = ? where id = ?", enabled, id);
    }

    /** 删除（明细随 on delete cascade 一并清）。日常应优先用停用，删除只给建错了的空壳模板兜底。 */
    @Transactional
    public void delete(Long userId, Long id) {
        Actor a = actorOf(userId);
        requireEditable(id, a);
        jdbc.update("delete from rx_template where id = ?", id);
    }

    // ------------------------------------------------------------------
    // 校验与工具
    // ------------------------------------------------------------------

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String requireName(TemplateReq req) {
        String name = trimOrNull(req == null ? null : req.name());
        if (name == null) {
            throw new BizException(4061, "模板名称必填");
        }
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private String normScope(String scope) {
        String s = scope == null ? "" : scope.trim().toUpperCase();
        if (!SCOPES.contains(s)) {
            throw new BizException(4063, "模板作用范围非法（应为 PERSONAL/DEPT/HOSPITAL）：" + scope);
        }
        return s;
    }

    private String normCategory(String category) {
        String c = category == null || category.isBlank() ? "RX" : category.trim().toUpperCase();
        if (!CATEGORIES.contains(c)) {
            throw new BizException(4069, "模板类别非法（应为 RX 处方模板 / AGREED 协定处方）：" + category);
        }
        return c;
    }

    /** DEPT 模板必须落到一个科室；非管理员只能建/改本科室的 */
    private Long resolveDeptId(String scope, Long reqDeptId, Actor a) {
        if (!"DEPT".equals(scope)) {
            return null;   // 个人/全院模板不挂科室，避免出现"个人模板还带科室"的歧义数据
        }
        Long deptId = reqDeptId != null ? reqDeptId : a.deptId();
        if (deptId == null) {
            throw new BizException(4063, "科室模板必须指定所属科室（当前账号也未配置科室）");
        }
        if (!a.admin() && !deptId.equals(a.deptId())) {
            throw new BizException(4060, "只能维护本科室的科室模板");
        }
        return deptId;
    }

    /**
     * 明细校验。空明细 4062；明细行不成立（类型不在白名单 / 数量非正 / 项目不存在）同样 4062——
     * 纸面上都是"这张模板的明细不成立"，与 4880「检索条件不成立」的归并口径一致。
     *
     * <p><b>刻意不校验"项目已停用"</b>：停用药能不能开，判定点在开单端点（8016），不在模板。
     * 模板侧多设一道拦截既救不了"存进模板之后才被停用"的绝大多数情形，
     * 又会让医生连一张只是暂时缺药的老模板都改不动。读侧回 {@code itemEnabled=false} 提示即可。
     */
    private List<TemplateLine> requireLines(List<TemplateLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BizException(4062, "模板明细为空，至少要有一行");
        }
        for (int i = 0; i < lines.size(); i++) {
            TemplateLine l = lines.get(i);
            String where = "第 " + (i + 1) + " 行";
            if (l == null || l.orderType() == null || !ORDER_TYPES.contains(l.orderType().trim().toUpperCase())) {
                throw new BizException(4062, where + "医嘱类型非法（应为 DRUG/LAB/EXAM/TREAT）");
            }
            if (l.itemId() == null) {
                throw new BizException(4062, where + "未选择项目");
            }
            if (l.qty() != null && l.qty() <= 0) {
                throw new BizException(4062, where + "数量必须大于 0");
            }
            String type = l.orderType().trim().toUpperCase();
            String table = "DRUG".equals(type) ? "md_drug" : "md_charge_item";
            Integer n = jdbc.queryForObject("select count(*) from " + table + " where id = ?",
                    Integer.class, l.itemId());
            if (n == null || n == 0) {
                throw new BizException(4062, where + "项目不存在：#" + l.itemId());
            }
        }
        return lines;
    }

    /** 整组替换明细：模板明细是一个整体（一张处方的配伍），逐行增删改会让并发编辑出半张处方 */
    private void replaceLines(Long templateId, List<TemplateLine> lines) {
        jdbc.update("delete from rx_template_line where template_id = ?", templateId);
        for (int i = 0; i < lines.size(); i++) {
            TemplateLine l = lines.get(i);
            jdbc.update("""
                    insert into rx_template_line(template_id, order_type, item_id, qty,
                            usage_route, frequency, dose_per_time, days, sort_no)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, templateId, l.orderType().trim().toUpperCase(), l.itemId(),
                    l.qty() == null || l.qty() <= 0 ? 1 : l.qty(),
                    trimOrNull(l.usageRoute()), trimOrNull(l.frequency()),
                    trimOrNull(l.dosePerTime()), l.days(),
                    l.sortNo() == null ? i : l.sortNo());
        }
    }
}
