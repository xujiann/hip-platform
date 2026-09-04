package cn.hip.medtech.service;

import cn.hip.platform.core.common.HipBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v45 车道H：病历模板体系（技术偏离表 987★ 常见病模板 / 988★ 科室默认模板 / 1073★ 四级作用范围 /
 * 1078★ 模板授权 / 1079★ 按既往病历建新病历 / 1095★ 病历存为模板）。
 *
 * <p><b>这块地基欠了三个版本</b>：{@code emr_template} 自 V17:59-64 建表只有 4 列，
 * v42 发现它没有 enabled 列（"停用"做不了，维护页只能退化成"复制为新模板"）、
 * v43 再次确认"编辑与停用仍未做"、v44 把"建表即带 enabled"写进 V136 的建表纪律。
 * 本类连同 V138 把这笔账一次还清：<b>PUT 编辑 / 停用 / 启用是本车道的第一优先级</b>，
 * 不是"下一版再补"的尾巴。
 *
 * <p><b>零核心写路径改动（本车道的硬约束）</b>：本类<b>不写</b> {@code outp_emr}、
 * 不写 {@code inp_medical_record}，全文没有一处 insert/update 落到病历正文表。
 * 「按既往病历建新病历」（1079★）在这里<b>只提供取正文的只读端点</b>——
 * 医生拿到正文后仍走既有的病历保存端点（门诊 {@code saveEmr} / 住院 {@code POST /records}），
 * 签名冻结（4008）、补正留痕、完整性 gate 全部照常。
 * 严禁为"少点一次保存"在本类新增任何写病历的路径：那等于给病历写入开一条不受既有校验管辖的旁路。
 *
 * <p><b>四级作用范围（1073★）与授权（1078★）的可见性矩阵</b>——
 * 这是本类最要紧的一张表，{@code V45TemplateScopeTest} 逐格钉死（含越权被拒 4066）：
 * <table>
 *   <caption>可见 / 可维护</caption>
 *   <tr><th>scope</th><th>可见</th><th>可维护（改/停用/启用/授权/设默认）</th></tr>
 *   <tr><td>GLOBAL 全局</td><td>所有登录人</td><td>仅 ADMIN</td></tr>
 *   <tr><td>HOSPITAL 全院</td><td>所有登录人</td><td>仅 ADMIN</td></tr>
 *   <tr><td>DEPT 科室</td><td>模板科室本科人 + <b>被授权科室</b>的人 + 创建者 + ADMIN</td>
 *       <td>创建者 + ADMIN</td></tr>
 *   <tr><td>PERSONAL 个人</td><td>本人 + <b>被授权个人</b>（ADMIN 也看不到别人的个人模板）</td>
 *       <td>仅本人</td></tr>
 * </table>
 * 最后一格与 {@code RxTemplateService} 同口径：个人模板是医生自己的书写习惯草稿，
 * "全院模板仅 ADMIN 可改"保护的是全院口径，不是给管理员一把翻看私人草稿的钥匙。
 * 授权是<b>叠加</b>的——任何 scope 的模板都可以再授权给某科室或某个人，
 * 授权只放大可见范围，<b>从不放大可维护范围</b>（被授权者能用，不能改别人的模板）。
 *
 * <p><b>新建即自动授权</b>（1078★，参数原话「需要授权的模板在新建的时候自动完成授权给
 * 构建科室或构建人」）：scope=DEPT 自动写 (DEPT, dept_id)，scope=PERSONAL 自动写 (USER, owner_id)。
 * GLOBAL/HOSPITAL 不写授权行——它们的可见范围就是全体，一条"授权给全院"的行既没有对象也没有语义。
 *
 * <p><b>错误码 4065–4068</b>（见 docs/错误码分段.md，v44 特意留空的那四个）：
 * <ul>
 *   <li><b>4065</b> 模板作用范围或授权非法：scope 不在四级白名单 / DEPT 模板未落到科室 /
 *       授权对象类型不在 DEPT|USER / 授权对象（科室、用户）不存在；</li>
 *   <li><b>4066</b> 未授权使用该模板：不存在、不可见、无权维护——<b>三条路径同码</b>。
 *       刻意不区分"不存在"与"无权"，与 4060 同口径：区分开就等于告诉外人
 *       "确实有这么一张你看不到的模板"，泄露他人个人模板的存在性；</li>
 *   <li><b>4067</b> 科室默认模板冲突或不成立：同科室同病历类型已有默认模板（由
 *       {@code uq_emr_tpl_default} 部分唯一索引在<b>数据库层</b>拦下，本类只负责把
 *       DuplicateKeyException 翻成人话）/ 设默认时未绑定科室或病历类型；</li>
 *   <li><b>4068</b> 既往病历不存在或非本患者。</li>
 * </ul>
 * <b>名称必填沿用 4061、正文为空沿用 4062</b>——这两条与 {@code RxTemplateService} 的
 * "模板名称必填 / 模板内容不成立"<b>逐字同义</b>，只是落在另一张模板表上。
 * 按 docs/错误码分段.md 的纪律（"能沿用既有码就沿用，不为既有语义另造同义码"，8016 已有先例：
 * 同一条规则的两个执行点共用一码属同义复用、不算撞码），本类不为它们另造 4065 段内的新码。
 */
@Service
@RequiredArgsConstructor
public class EmrTemplateService {

    private final JdbcTemplate jdbc;

    /** 四级作用范围（1073★）。顺序即前端下拉顺序：从大到小 */
    public static final List<String> SCOPES = List.of("GLOBAL", "HOSPITAL", "DEPT", "PERSONAL");
    private static final Set<String> SCOPE_SET = Set.of("GLOBAL", "HOSPITAL", "DEPT", "PERSONAL");
    private static final Set<String> GRANTEE_TYPES = Set.of("DEPT", "USER");
    /** emr_template.content 仍是 varchar(4000)（本版刻意不动列宽，见 V138 头注） */
    public static final int MAX_CONTENT = 4000;
    private static final int MAX_NAME = 64;

    // ==================================================================
    // 请求体
    // ==================================================================

    /**
     * 建模板 / 改模板入参。
     *
     * <p>与既有 {@code MedTechController.EmrTemplateReq}（4 个分量，v38/v42 起被 RisView、
     * v42 维护页、两条 E2E 消费）<b>刻意分开定义</b>：那个 record 的分量集合是冻结契约，
     * 加一个分量就会让 V38RisExpTest / V42EmrTemplateTest 当场编译失败。
     */
    public record TemplateReq(String name, String content, String templateType,
                              String scope, Long deptId, String recordType) {}

    /**
     * 病历存为模板（1095★）入参：source=INP 住院病历 / OUTP 门诊病历。
     *
     * <p>{@code patientId} <b>必传且做归属校验</b>（4068）：没有这一句，任何人拿一个自增 id
     * 就能把别的患者的病历正文原样存成一张模板，模板还会被发给全科室看——
     * "存为模板"会变成一条绕过病历访问控制的正文导出通道。
     */
    public record FromRecordReq(String source, Long recordId, Long patientId, String name,
                                String scope, Long deptId, String recordType) {}

    // ==================================================================
    // 当前用户画像
    // ==================================================================

    /** 判权要素：本人 id + 本人科室 + 是否 ADMIN */
    public record Actor(Long userId, Long deptId, boolean admin) {}

    public Actor actorOf(Long userId) {
        if (userId == null) {
            return new Actor(null, null, false);
        }
        List<Long> depts = jdbc.queryForList("select dept_id from sys_user where id = ?", Long.class, userId);
        Long deptId = depts.isEmpty() ? null : depts.get(0);
        List<String> roles = jdbc.queryForList("""
                select r.code from sys_user_role ur join sys_role r on r.id = ur.role_id
                where ur.user_id = ?
                """, String.class, userId);
        return new Actor(userId, deptId, roles.contains("ADMIN"));
    }

    /** 前端建模板对话框要据此决定"哪些作用范围可选、科室默认填谁"，避免摆一排点了就报错的死选项 */
    public Map<String, Object> actorInfo(Long userId) {
        Actor a = actorOf(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", a.userId());
        m.put("deptId", a.deptId());
        m.put("deptName", a.deptId() == null ? null : jdbc.queryForList(
                "select name from sys_dept where id = ?", String.class, a.deptId())
                .stream().findFirst().orElse(null));
        m.put("admin", a.admin());
        // 非管理员建不了全局/全院模板：前端据此把这两项置灰（后端仍会再判一次，见 requireScopeGrantable）
        m.put("grantableScopes", a.admin() ? SCOPES : List.of("DEPT", "PERSONAL"));
        return m;
    }

    // ==================================================================
    // 模板头快照与判权
    // ==================================================================

    /** 只取判权要用的列，避免把整行 Map 传来传去 */
    private record Tpl(Long id, String scope, Long ownerId, Long deptId, String recordType,
                       String templateType, boolean enabled, boolean isDefault) {}

    private Tpl load(Long id) {
        if (id == null) return null;
        List<Tpl> rows = jdbc.query("""
                select id, scope, owner_id, dept_id, record_type, template_type, enabled, is_default
                from emr_template where id = ?
                """, (rs, i) -> new Tpl(rs.getLong("id"), rs.getString("scope"),
                        (Long) rs.getObject("owner_id"), (Long) rs.getObject("dept_id"),
                        rs.getString("record_type"), rs.getString("template_type"),
                        rs.getBoolean("enabled"), rs.getBoolean("is_default")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 可见性判定——<b>与 {@link #visibleWhere} 的 SQL 严格同构</b>。
     * 一处改了必须两处都改，故 V45TemplateScopeTest 对"列表出不出"与"按 id 取用报不报 4066"
     * 两条路径分别断言同一个矩阵，任何一侧漂移都会当场红。
     */
    private boolean canSee(Tpl t, Actor a) {
        if ("GLOBAL".equals(t.scope()) || "HOSPITAL".equals(t.scope())) return true;
        if (a.userId() != null && a.userId().equals(t.ownerId())) return true;      // 自己建的
        if ("DEPT".equals(t.scope())) {
            if (a.admin()) return true;
            if (a.deptId() != null && a.deptId().equals(t.deptId())) return true;   // 本科室
        }
        return hasGrant(t.id(), a);                                                 // 被授权（叠加）
    }

    /** 授权只放大可见范围，从不放大可维护范围 */
    private boolean hasGrant(Long templateId, Actor a) {
        StringBuilder sql = new StringBuilder("""
                select count(*) from emr_template_grant
                where template_id = ? and (1 = 0
                """);
        List<Object> args = new ArrayList<>();
        args.add(templateId);
        if (a.userId() != null) {
            sql.append(" or (grantee_type = 'USER' and grantee_id = ?) ");
            args.add(a.userId());
        }
        if (a.deptId() != null) {
            sql.append(" or (grantee_type = 'DEPT' and grantee_id = ?) ");
            args.add(a.deptId());
        }
        sql.append(" ) ");
        Integer n = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return n != null && n > 0;
    }

    private boolean canEdit(Tpl t, Actor a) {
        if (a.userId() == null) return false;
        return switch (t.scope()) {
            case "PERSONAL" -> a.userId().equals(t.ownerId());
            case "DEPT" -> a.userId().equals(t.ownerId()) || a.admin();
            default -> a.admin();   // GLOBAL / HOSPITAL：仅 ADMIN
        };
    }

    /** 读侧守卫：不存在与不可见合并成一句话（不泄露他人个人模板的存在性） */
    private Tpl requireVisible(Long id, Actor a) {
        Tpl t = load(id);
        if (t == null || !canSee(t, a)) {
            throw new HipBizException(4066, "模板不存在或未授权使用");
        }
        return t;
    }

    private Tpl requireEditable(Long id, Actor a) {
        Tpl t = requireVisible(id, a);
        if (!canEdit(t, a)) {
            throw new HipBizException(4066, "无权维护该模板：%s模板只能由%s维护"
                    .formatted(scopeName(t.scope()), editorName(t.scope())));
        }
        return t;
    }

    public static String scopeName(String scope) {
        return switch (scope == null ? "" : scope) {
            case "GLOBAL" -> "全局";
            case "DEPT" -> "科室";
            case "PERSONAL" -> "个人";
            default -> "全院";
        };
    }

    private static String editorName(String scope) {
        return switch (scope == null ? "" : scope) {
            case "PERSONAL" -> "本人";
            case "DEPT" -> "创建者本人或系统管理员";
            default -> "系统管理员";
        };
    }

    // ==================================================================
    // 列表（1073★ 按登录人可见范围）
    // ==================================================================

    /**
     * 可见范围内的模板列表。
     *
     * <p><b>与既有 {@code GET /api/emr-templates} 是两条完全不同的通道</b>：
     * 那一条按 dept_id 过滤、不认登录人、不认 scope，是 RisView 与住院医生站下拉两年来的取数口径，
     * <b>本版一字未改</b>；本方法才是四级作用范围 + 授权的落点，供新的维护页与"按范围取模板"用。
     *
     * @param includeDisabled 维护页传 true 才看得到已停用模板；套用路径一律默认 false
     */
    public List<Map<String, Object>> listVisible(Long userId, String templateType, String recordType,
                                                 String scope, String keyword, boolean includeDisabled) {
        Actor a = actorOf(userId);
        StringBuilder sql = new StringBuilder("""
                select t.*, u.real_name as owner_name, d.name as dept_name,
                       (select count(*) from emr_template_grant g where g.template_id = t.id) as grant_count
                from emr_template t
                left join sys_user u on u.id = t.owner_id
                left join sys_dept d on d.id = t.dept_id
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (!includeDisabled) {
            sql.append(" and t.enabled ");
        }
        if (notBlank(templateType)) {
            sql.append(" and t.template_type = ? ");
            args.add(templateType.trim().toUpperCase());
        }
        if (notBlank(recordType)) {
            sql.append(" and t.record_type = ? ");
            args.add(recordType.trim().toUpperCase());
        }
        if (notBlank(scope)) {
            sql.append(" and t.scope = ? ");
            args.add(scope.trim().toUpperCase());
        }
        if (notBlank(keyword)) {
            sql.append(" and t.name like ? ");
            args.add("%" + keyword.trim() + "%");
        }
        visibleWhere(a, sql, args);
        // 排序：默认模板置顶（医生最常要的就是它），再按范围从大到小、名称
        sql.append("""
                 order by t.is_default desc,
                          case t.scope when 'GLOBAL' then 0 when 'HOSPITAL' then 1
                                       when 'DEPT' then 2 else 3 end,
                          t.name, t.id
                """);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            Tpl t = tplOf(r);
            // 前端据此决定按钮显隐；后端每条写路径仍会再校验一次，这里只是别摆死按钮
            r.put("editable", canEdit(t, a));
            r.put("scopeName", scopeName(t.scope()));
        }
        return rows;
    }

    /** 可见范围的 SQL 分支，与 {@link #canSee} 严格同构 */
    private void visibleWhere(Actor a, StringBuilder sql, List<Object> args) {
        sql.append(" and ( t.scope in ('GLOBAL', 'HOSPITAL') ");
        if (a.userId() != null) {
            sql.append(" or t.owner_id = ? ");
            args.add(a.userId());
            sql.append("""
                     or exists (select 1 from emr_template_grant g
                                where g.template_id = t.id and g.grantee_type = 'USER' and g.grantee_id = ?)
                    """);
            args.add(a.userId());
        }
        if (a.admin()) {
            sql.append(" or t.scope = 'DEPT' ");
        } else if (a.deptId() != null) {
            sql.append(" or (t.scope = 'DEPT' and t.dept_id = ?) ");
            args.add(a.deptId());
        }
        if (a.deptId() != null) {
            sql.append("""
                     or exists (select 1 from emr_template_grant g
                                where g.template_id = t.id and g.grantee_type = 'DEPT' and g.grantee_id = ?)
                    """);
            args.add(a.deptId());
        }
        sql.append(" ) ");
    }

    private static Tpl tplOf(Map<String, Object> r) {
        return new Tpl(num(r.get("id")), (String) r.get("scope"), num(r.get("owner_id")),
                num(r.get("dept_id")), (String) r.get("record_type"), (String) r.get("template_type"),
                Boolean.TRUE.equals(r.get("enabled")), Boolean.TRUE.equals(r.get("is_default")));
    }

    private static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    /**
     * 套用模板：按 id 取正文。<b>只读</b>——真正写病历仍走既有病历保存端点。
     * 不可见返 4066（与列表口径同构）；已停用返 4066——停用了还能套用，"停用"就等于没做。
     */
    public Map<String, Object> use(Long userId, Long id) {
        Actor a = actorOf(userId);
        Tpl t = requireVisible(id, a);
        if (!t.enabled()) {
            throw new HipBizException(4066, "该模板已停用，不可套用");
        }
        Map<String, Object> row = jdbc.queryForMap("select * from emr_template where id = ?", id);
        row.put("scopeName", scopeName(t.scope()));
        return row;
    }

    // ==================================================================
    // 写侧：建 / 改 / 停用启用
    // ==================================================================

    /**
     * 建模板（带作用范围与自动授权）。
     *
     * <p>与既有 {@code POST /api/emr-templates} 分开成两个端点：那一条的请求体 record 是冻结契约，
     * 且历史上<b>没有任何范围权限判定</b>（任何持有该控制器权限的角色都能建全院可见的模板）。
     * 本端点是新的、带判定的那条通道；既有端点保持原样以免打断 v42 维护页与两条 E2E。
     */
    @Transactional
    public Long create(Long userId, TemplateReq req) {
        Actor a = actorOf(userId);
        if (a.userId() == null) {
            throw new HipBizException(4066, "无法识别当前登录用户，不能建模板");
        }
        String name = requireName(req == null ? null : req.name());
        String content = requireContent(req == null ? null : req.content());
        String scope = normScope(req.scope());
        requireScopeGrantable(scope, a);
        Long deptId = resolveDeptId(scope, req.deptId(), a);
        String templateType = normType(req.templateType());
        String recordType = normRecordType(req.recordType());

        Long id = jdbc.queryForObject("""
                insert into emr_template(dept_id, name, content, template_type,
                                         scope, owner_id, record_type, enabled, created_by)
                values (?, ?, ?, ?, ?, ?, ?, true, ?) returning id
                """, Long.class, deptId, name, content, templateType, scope, a.userId(), recordType, a.userId());
        autoGrant(id, scope, deptId, a);
        return id;
    }

    /**
     * 新建即自动授权（1078★，参数原话「需要授权的模板在新建的时候自动完成授权给构建科室或构建人」）。
     * GLOBAL/HOSPITAL 不写行：可见范围本就是全体，一条"授权给全院"的行没有对象也没有语义。
     */
    private void autoGrant(Long templateId, String scope, Long deptId, Actor a) {
        if ("DEPT".equals(scope) && deptId != null) {
            insertGrant(templateId, "DEPT", deptId, a.userId());
        } else if ("PERSONAL".equals(scope) && a.userId() != null) {
            insertGrant(templateId, "USER", a.userId(), a.userId());
        }
    }

    /**
     * 改模板（<b>v42 起欠了三个版本的账，本版还清</b>）。
     *
     * <p>作用范围可改，但改到 GLOBAL/HOSPITAL 仍要 ADMIN；改了 scope 会按新范围补一条自动授权
     * （否则一张个人模板改成科室模板后，本科室反而看不到它——授权表与 scope 脱节）。
     */
    @Transactional
    public void update(Long userId, Long id, TemplateReq req) {
        Actor a = actorOf(userId);
        Tpl t = requireEditable(id, a);
        String name = requireName(req == null ? null : req.name());
        String content = requireContent(req == null ? null : req.content());
        String scope = notBlank(req.scope()) ? normScope(req.scope()) : t.scope();
        if (!scope.equals(t.scope())) {
            requireScopeGrantable(scope, a);
        }
        Long deptId = resolveDeptId(scope, req.deptId() != null ? req.deptId() : t.deptId(), a);
        // 不传 templateType 就保持原值（在 Java 里取回原值，而不是在 SQL 里写 coalesce(?, ...)：
        // 未定型的 null 参数进 coalesce 会让 Postgres 报"无法确定参数类型"）
        String templateType = notBlank(req.templateType()) ? normType(req.templateType()) : t.templateType();
        String recordType = normRecordType(req.recordType());

        // 默认模板一旦改了科室或病历类型，原来占的默认位就不再成立——先让位再改，
        // 否则 (dept_id, record_type) 会带着 is_default 漂到另一个格子上，把别人的默认位挤掉
        if (t.isDefault() && (!java.util.Objects.equals(deptId, t.deptId())
                || !java.util.Objects.equals(recordType, t.recordType()))) {
            jdbc.update("update emr_template set is_default = false where id = ?", id);
        }
        try {
            jdbc.update("""
                    update emr_template
                    set name = ?, content = ?, scope = ?, dept_id = ?, record_type = ?, template_type = ?
                    where id = ?
                    """, name, content, scope, deptId, recordType, templateType, id);
        } catch (DuplicateKeyException e) {
            // 改了科室/病历类型撞上别人的默认位（上面已先让位，这里只剩并发那一种）
            throw defaultConflict();
        }
        if (!scope.equals(t.scope())) {
            autoGrant(id, scope, deptId, a);
        }
    }

    /**
     * 停用 / 启用（软开关，<b>不删行</b>）——已按某张模板写成的历史病历，
     * 必须仍能解释"当时照的是哪一张"。
     */
    @Transactional
    public void setEnabled(Long userId, Long id, boolean enabled) {
        Actor a = actorOf(userId);
        requireEditable(id, a);
        try {
            jdbc.update("update emr_template set enabled = ? where id = ?", enabled, id);
        } catch (DuplicateKeyException e) {
            // 停用期间别的模板顶上了默认位，此时重新启用会撞 uq_emr_tpl_default。
            // 事务此刻已 aborted，不能再读库补充模板名（见 defaultConflict 的注释）
            throw new HipBizException(4067,
                    "该模板停用期间，本科室本病历类型的默认位已被另一张模板占用，"
                            + "无法直接启用；请先取消那一张的默认，或先取消本模板的默认标记再启用");
        }
    }

    // ==================================================================
    // 授权（1078★）
    // ==================================================================

    public List<Map<String, Object>> grants(Long userId, Long templateId) {
        Actor a = actorOf(userId);
        requireVisible(templateId, a);
        return jdbc.queryForList("""
                select g.id, g.template_id, g.grantee_type, g.grantee_id, g.granted_at,
                       case g.grantee_type when 'DEPT' then d.name else u.real_name end as grantee_name,
                       b.real_name as granted_by_name
                from emr_template_grant g
                left join sys_dept d on g.grantee_type = 'DEPT' and d.id = g.grantee_id
                left join sys_user u on g.grantee_type = 'USER' and u.id = g.grantee_id
                left join sys_user b on b.id = g.granted_by
                where g.template_id = ? order by g.grantee_type, g.id
                """, templateId);
    }

    /** 授予。重复授予按幂等处理（on conflict do nothing）——"再授权一次"业务上就是无操作，不该报错。 */
    @Transactional
    public void grant(Long userId, Long templateId, String granteeType, Long granteeId) {
        Actor a = actorOf(userId);
        requireEditable(templateId, a);
        String type = granteeType == null ? "" : granteeType.trim().toUpperCase();
        if (!GRANTEE_TYPES.contains(type)) {
            throw new HipBizException(4065, "授权对象类型非法（应为 DEPT 科室 / USER 个人）：" + granteeType);
        }
        if (granteeId == null) {
            throw new HipBizException(4065, "未指定授权对象");
        }
        String table = "DEPT".equals(type) ? "sys_dept" : "sys_user";
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, granteeId);
        if (n == null || n == 0) {
            throw new HipBizException(4065,
                    ("DEPT".equals(type) ? "授权科室不存在：#" : "授权用户不存在：#") + granteeId);
        }
        insertGrant(templateId, type, granteeId, a.userId());
    }

    private void insertGrant(Long templateId, String type, Long granteeId, Long by) {
        jdbc.update("""
                insert into emr_template_grant(template_id, grantee_type, grantee_id, granted_by)
                values (?, ?, ?, ?)
                on conflict (template_id, grantee_type, grantee_id) do nothing
                """, templateId, type, granteeId, by);
    }

    /**
     * 撤销授权。<b>建模板时自动写的那条不允许撤</b>——撤掉之后科室模板对本科室反而不可见，
     * 是一条谁都想不到的自伤路径；要收回就停用模板，别把它变成孤儿。
     */
    @Transactional
    public void revoke(Long userId, Long templateId, Long grantId) {
        Actor a = actorOf(userId);
        Tpl t = requireEditable(templateId, a);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select grantee_type, grantee_id from emr_template_grant where id = ? and template_id = ?",
                grantId, templateId);
        if (rows.isEmpty()) {
            throw new HipBizException(4065, "授权记录不存在");
        }
        String type = (String) rows.get(0).get("grantee_type");
        Long granteeId = num(rows.get(0).get("grantee_id"));
        boolean selfGrant = ("DEPT".equals(type) && java.util.Objects.equals(granteeId, t.deptId()))
                || ("USER".equals(type) && java.util.Objects.equals(granteeId, t.ownerId()));
        if (selfGrant) {
            throw new HipBizException(4065,
                    "不能撤销模板对自身%s的授权（建模板时自动授予）：撤销后本模板将无人可见，如需收回请停用模板"
                            .formatted("DEPT".equals(type) ? "科室" : "创建人"));
        }
        jdbc.update("delete from emr_template_grant where id = ? and template_id = ?", grantId, templateId);
    }

    /** 授权对象候选（只读字典）：/api/system/users 是 ADMIN 专属，质控与医生取不到人员字典 */
    public List<Map<String, Object>> granteeCandidates(String granteeType, String keyword) {
        String type = granteeType == null ? "DEPT" : granteeType.trim().toUpperCase();
        if (!GRANTEE_TYPES.contains(type)) {
            throw new HipBizException(4065, "授权对象类型非法（应为 DEPT 科室 / USER 个人）：" + granteeType);
        }
        String kw = notBlank(keyword) ? "%" + keyword.trim() + "%" : "%";
        return "DEPT".equals(type)
                ? jdbc.queryForList("""
                        select id, name from sys_dept where name like ? order by sort_no, id limit 200
                        """, kw)
                : jdbc.queryForList("""
                        select u.id, u.real_name as name, d.name as dept_name
                        from sys_user u left join sys_dept d on d.id = u.dept_id
                        where u.enabled = true and u.real_name like ? order by u.id limit 200
                        """, kw);
    }

    // ==================================================================
    // 科室默认模板（988★）
    // ==================================================================

    /**
     * 设为科室默认模板。
     *
     * <p><b>唯一性由数据库的部分唯一索引 {@code uq_emr_tpl_default} 保证</b>，不是应用层读-判-写：
     * 两位质控同时点"设为默认"时，只靠读-判-写会双双通过，之后医生站取默认模板就随机取到其中一张，
     * 现场根本查不出所以然。
     *
     * <p>两道各司其职，<b>顺序不能颠倒</b>：
     * <ol>
     *   <li><b>写之前</b>先读一次占位者，撞上就给一句带模板名的人话（日常的那 99%）；</li>
     *   <li>写本身撞索引（并发的那 1%）时只能给<b>不查库</b>的兜底话术——
     *       Postgres 里一条语句报错后整个事务即进入 aborted 状态（25P02），
     *       此时再发任何 SELECT 都只会得到"current transaction is aborted"，
     *       把真正的错因盖掉。这是本用例第一版踩过的坑，注释留在这里以免有人"顺手优化"回去。</li>
     * </ol>
     *
     * @param replace true=先把该科室该病历类型原有的默认位让出来再设（一次事务内完成，无并发窗口）；
     *                false=撞车就返 4067，让维护人看清楚是被哪一张占着
     */
    @Transactional
    public void setDefault(Long userId, Long id, boolean replace) {
        Actor a = actorOf(userId);
        Tpl t = requireEditable(id, a);
        if (t.deptId() == null || !notBlank(t.recordType())) {
            throw new HipBizException(4067,
                    "科室默认模板必须同时绑定科室与病历类型（988 的取数口径就是 deptId + recordType），"
                            + "请先在模板上补齐这两项");
        }
        if (!t.enabled()) {
            throw new HipBizException(4067, "已停用的模板不能设为科室默认模板，请先启用");
        }
        if (replace) {
            jdbc.update("""
                    update emr_template set is_default = false
                    where dept_id = ? and record_type = ? and is_default and enabled and id <> ?
                    """, t.deptId(), t.recordType(), id);
        } else {
            String occupied = occupyingDefaultName(t.deptId(), t.recordType(), id);
            if (occupied != null) {
                throw new HipBizException(4067,
                        "该科室该病历类型已有默认模板《%s》，同一科室同一病历类型只能有一张；如需替换请用「替换为默认」"
                                .formatted(occupied));
            }
        }
        try {
            jdbc.update("update emr_template set is_default = true where id = ?", id);
        } catch (DuplicateKeyException e) {
            throw defaultConflict();
        }
    }

    @Transactional
    public void unsetDefault(Long userId, Long id) {
        Actor a = actorOf(userId);
        requireEditable(id, a);
        jdbc.update("update emr_template set is_default = false where id = ?", id);
    }

    /** 写之前的友好提示读（事务还干净时才能读） */
    private String occupyingDefaultName(Long deptId, String recordType, Long excludeId) {
        if (deptId == null || recordType == null) return null;
        return jdbc.queryForList("""
                select name from emr_template
                where dept_id = ? and record_type = ? and is_default and enabled and id <> ? limit 1
                """, String.class, deptId, recordType, excludeId).stream().findFirst().orElse(null);
    }

    /**
     * 撞索引之后的兜底话术。<b>这里一个字都不许再查库</b>：
     * 事务此刻已 aborted（25P02），任何 SELECT 都只会把真正的错因盖成"事务已中止"。
     */
    private HipBizException defaultConflict() {
        return new HipBizException(4067,
                "该科室该病历类型的默认模板已存在（同一时刻另一人刚设过），"
                        + "同一科室同一病历类型只能有一张默认模板；请刷新后确认现状再操作");
    }

    /**
     * 取科室默认模板（988★ 的取数端点）：按 deptId + recordType 取那一张。
     * 没有默认模板返回 null（不报错）——绝大多数科室大多数类型本来就没设默认，
     * 让它报错等于逼医生站每次都吞一个异常。
     */
    public Map<String, Object> defaultTemplate(Long userId, Long deptId, String recordType) {
        if (deptId == null || !notBlank(recordType)) {
            throw new HipBizException(4067, "取科室默认模板必须同时给出科室与病历类型");
        }
        Actor a = actorOf(userId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from emr_template
                where dept_id = ? and record_type = ? and is_default and enabled limit 1
                """, deptId, recordType.trim().toUpperCase());
        if (rows.isEmpty()) return null;
        Tpl t = tplOf(rows.get(0));
        // 默认模板也照可见性判一次：跨科取别人科的默认模板要么本就可见（GLOBAL/HOSPITAL），
        // 要么得有授权，不能因为"它是默认的"就绕过 1073/1078
        if (!canSee(t, a)) {
            throw new HipBizException(4066, "模板不存在或未授权使用");
        }
        return rows.get(0);
    }

    // ==================================================================
    // 病历存为模板（1095★）与按既往病历建新病历（1079★/988★）
    // ==================================================================

    /**
     * 把一份既有病历的正文存成模板（1095★）。
     *
     * <p><b>只读病历、只写模板</b>：本方法不碰 {@code outp_emr} / {@code inp_medical_record} 一个字节。
     *
     * <p>{@code emr_template.content} 仍是 varchar(4000)（本版刻意不动列宽），超长时截断，
     * 并在返回体里显式回 {@code truncated=true} 与两个长度——<b>不做无声截断</b>：
     * 医生存了一份被砍掉一半的模板却毫不知情，比存不进去更糟。
     */
    @Transactional
    public Map<String, Object> saveAsTemplate(Long userId, FromRecordReq req) {
        Actor a = actorOf(userId);
        if (a.userId() == null) {
            throw new HipBizException(4066, "无法识别当前登录用户，不能建模板");
        }
        if (req == null || req.patientId() == null) {
            throw new HipBizException(4068, "存为模板必须指明来源病历所属患者（归属校验参数，不是过滤条件）");
        }
        PriorRecord src = loadRecord(req.source(), req.recordId(), req.patientId());
        String name = requireName(notBlank(req.name()) ? req.name() : src.title());
        String scope = normScope(notBlank(req.scope()) ? req.scope() : "PERSONAL");
        requireScopeGrantable(scope, a);
        Long deptId = resolveDeptId(scope, req.deptId(), a);
        String recordType = notBlank(req.recordType()) ? normRecordType(req.recordType()) : src.recordType();

        String full = src.content() == null ? "" : src.content();
        boolean truncated = full.length() > MAX_CONTENT;
        String content = truncated ? full.substring(0, MAX_CONTENT) : full;
        requireContent(content);

        Long id = jdbc.queryForObject("""
                insert into emr_template(dept_id, name, content, template_type,
                                         scope, owner_id, record_type, enabled, created_by)
                values (?, ?, ?, 'EMR', ?, ?, ?, true, ?) returning id
                """, Long.class, deptId, name, content, scope, a.userId(), recordType, a.userId());
        autoGrant(id, scope, deptId, a);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("scope", scope);
        out.put("recordType", recordType);
        out.put("contentLength", content.length());
        out.put("sourceLength", full.length());
        out.put("truncated", truncated);
        out.put("maxContent", MAX_CONTENT);
        return out;
    }

    /**
     * 该患者的既往病历列表（1079★ 「按既往病历建新病历」的选择清单）。
     *
     * <p><b>这只是"取正文"这半条路</b>：医生挑一份既往病历 → 取正文 → 在编辑器里改 →
     * <b>仍走既有病历保存端点写入</b>。本车道没有、也不许有任何新的写病历路径。
     */
    public List<Map<String, Object>> priorRecords(Long patientId, Integer limit) {
        if (patientId == null) {
            throw new HipBizException(4068, "未指定患者，取不到既往病历");
        }
        int n = limit == null || limit <= 0 || limit > 200 ? 50 : limit;
        return jdbc.queryForList("""
                select 'INP' as source, r.id as record_id, r.record_type, r.title,
                       r.created_at, a.admission_no as visit_no, d.name as dept_name,
                       u.real_name as doctor_name, length(r.content) as content_length
                from inp_medical_record r
                join inp_admission a on a.id = r.admission_id
                left join sys_dept d on d.id = a.dept_id
                left join sys_user u on u.id = r.doctor_id
                where a.patient_id = ?
                union all
                select 'OUTP', e.id, 'OUTP', '门诊病历',
                       e.updated_at, cast(g.reg_no as varchar), d2.name,
                       u2.real_name,
                       coalesce(length(e.chief_complaint), 0) + coalesce(length(e.present_illness), 0)
                       + coalesce(length(e.past_history), 0) + coalesce(length(e.physical_exam), 0)
                       + coalesce(length(e.advice), 0)
                from outp_emr e
                join outp_registration g on g.id = e.registration_id
                left join sys_dept d2 on d2.id = g.dept_id
                left join sys_user u2 on u2.id = e.doctor_id
                where g.patient_id = ?
                order by created_at desc
                limit ?
                """, patientId, patientId, n);
    }

    /**
     * 取一份既往病历的正文（1079★）。<b>只读</b>。
     *
     * <p>{@code patientId} 是必传的<b>归属校验参数</b>，不是过滤条件：
     * 病历 id 不属于该患者一律 4068。少了这一句，任何人拿一个自增 id 就能把别的患者的病历
     * 抄进自己面前的病历里——那不是"提效"，那是串档。
     */
    public Map<String, Object> priorRecordContent(Long patientId, String source, Long recordId) {
        if (patientId == null) {
            throw new HipBizException(4068, "未指定患者，取不到既往病历");
        }
        PriorRecord r = loadRecord(source, recordId, patientId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", r.source());
        m.put("recordId", r.id());
        m.put("recordType", r.recordType());
        m.put("title", r.title());
        m.put("content", r.content());
        m.put("createdAt", r.createdAt());
        return m;
    }

    /**
     * 既往病历的统一形状（住院一条记录 / 门诊五段拼一份）。
     * <p>刻意<b>不</b>叫 {@code Record}——那会在本类里遮住 {@link java.lang.Record}，读代码的人得停下来想一秒。
     */
    private record PriorRecord(String source, Long id, String recordType, String title,
                               String content, Object createdAt) {}

    /**
     * 取一份既往病历（住院/门诊统一形状）。
     *
     * <p>{@code patientId} 为 null 时不做归属校验；<b>本类所有对外路径都传了它</b>
     * （{@link #priorRecordContent} 与 {@link #saveAsTemplate} 都必传，缺了直接 4068）。
     * 归属条件<b>用拼 SQL 而不是 {@code (? is null or ...)}</b>：未定型的 null 参数
     * 会让 Postgres 当场报"无法确定参数类型"，这是本仓踩过的老坑。
     */
    private PriorRecord loadRecord(String source, Long recordId, Long patientId) {
        String s = source == null ? "" : source.trim().toUpperCase();
        if (recordId == null || !(s.equals("INP") || s.equals("OUTP"))) {
            throw new HipBizException(4068, "既往病历不存在或非本患者（source 应为 INP 住院 / OUTP 门诊）");
        }
        List<Object> args = new ArrayList<>();
        args.add(recordId);
        String ownerClause = "";
        if (patientId != null) {
            ownerClause = "INP".equals(s) ? " and a.patient_id = ? " : " and g.patient_id = ? ";
            args.add(patientId);
        }
        String sql = "INP".equals(s)
                ? """
                  select r.id, r.record_type, r.title, r.content, r.created_at
                  from inp_medical_record r
                  join inp_admission a on a.id = r.admission_id
                  where r.id = ?
                  """ + ownerClause
                : """
                  select e.id, 'OUTP' as record_type, '门诊病历' as title,
                         concat_ws(chr(10),
                             case when coalesce(e.chief_complaint, '') = '' then null
                                  else '主诉：' || e.chief_complaint end,
                             case when coalesce(e.present_illness, '') = '' then null
                                  else '现病史：' || e.present_illness end,
                             case when coalesce(e.past_history, '') = '' then null
                                  else '既往史：' || e.past_history end,
                             case when coalesce(e.physical_exam, '') = '' then null
                                  else '体格检查：' || e.physical_exam end,
                             case when coalesce(e.advice, '') = '' then null
                                  else '处理意见：' || e.advice end) as content,
                         e.updated_at as created_at
                  from outp_emr e
                  join outp_registration g on g.id = e.registration_id
                  where e.id = ?
                  """ + ownerClause;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        if (rows.isEmpty()) {
            throw new HipBizException(4068, "既往病历不存在或非本患者");
        }
        Map<String, Object> r = rows.get(0);
        return new PriorRecord(s, num(r.get("id")), (String) r.get("record_type"),
                (String) r.get("title"), (String) r.get("content"), r.get("created_at"));
    }

    // ==================================================================
    // 校验与工具
    // ==================================================================

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 沿用 4061（与 RxTemplateService「模板名称必填」逐字同义，只是落在另一张模板表上） */
    private String requireName(String raw) {
        if (!notBlank(raw)) {
            throw new HipBizException(4061, "模板名称必填");
        }
        String name = raw.trim();
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /** 沿用 4062（与 RxTemplateService「模板内容不成立」同族） */
    private String requireContent(String raw) {
        if (!notBlank(raw)) {
            throw new HipBizException(4062, "模板正文为空，至少要有一段可套用的内容");
        }
        return raw;
    }

    private String normScope(String scope) {
        String s = scope == null ? "" : scope.trim().toUpperCase();
        if (!SCOPE_SET.contains(s)) {
            throw new HipBizException(4065,
                    "模板作用范围非法（应为 GLOBAL 全局 / HOSPITAL 全院 / DEPT 科室 / PERSONAL 个人）：" + scope);
        }
        return s;
    }

    /** 全局/全院是院级口径，只有 ADMIN 能建与改；这是"无权"不是"取值非法"，故 4066 */
    private void requireScopeGrantable(String scope, Actor a) {
        if (("GLOBAL".equals(scope) || "HOSPITAL".equals(scope)) && !a.admin()) {
            throw new HipBizException(4066, scopeName(scope) + "模板只能由系统管理员建立与维护");
        }
    }

    /** DEPT 必须落到一个科室；非管理员只能维护本科室的（4065 是"范围不成立"，与授权非法同码） */
    private Long resolveDeptId(String scope, Long reqDeptId, Actor a) {
        if (!"DEPT".equals(scope)) {
            // 个人/全院/全局模板不挂科室，避免出现"个人模板还带科室"的歧义数据；
            // 也与既有 GET 的 dept_id 口径对齐（dept_id is null == 全院通用）
            return null;
        }
        Long deptId = reqDeptId != null ? reqDeptId : a.deptId();
        if (deptId == null) {
            throw new HipBizException(4065, "科室模板必须指定所属科室（当前账号也未配置科室）");
        }
        if (!a.admin() && !deptId.equals(a.deptId())) {
            throw new HipBizException(4066, "只能维护本科室的科室模板");
        }
        return deptId;
    }

    /** template_type 与 V116 同域：EMR 病历 / CONSENT 同意书 / RIS 报告；缺省 EMR（与既有端点一致） */
    private String normType(String type) {
        return notBlank(type) ? type.trim().toUpperCase() : "EMR";
    }

    /** 病历类型是开放集合，不设白名单（与 V133:15-18 对 record_type 的取舍同口径），只做规范化 */
    private String normRecordType(String recordType) {
        if (!notBlank(recordType)) return null;
        String s = recordType.trim().toUpperCase();
        return s.length() > 16 ? s.substring(0, 16) : s;
    }
}
