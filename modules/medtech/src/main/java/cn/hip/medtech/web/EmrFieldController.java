package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v45 车道 I：病历模板<b>结构化字段定义</b>的维护，与 1098★ 的结构化元素检索通道。
 *
 * <p>兑现三条已答"平台已实现"而实际全仓零命中的参数：
 * <ul>
 *   <li><b>1075★</b> 模板兼容最小结构化元素：文本·数值·复选·单选·多选·日期<b>六型</b>；</li>
 *   <li><b>989★</b> 结构化书写、元素快速跳转（{@code sortNo} 即前端的 Tab 序）；</li>
 *   <li><b>1098★</b> 检索门急诊病历中<b>某个结构化元素内容</b>的接口通道（{@code /field-search}）。</li>
 * </ul>
 *
 * <p><b>刻意不做的事</b>：本控制器<b>不碰 {@code emr_template} 一行</b>（模板本体的
 * enabled/scope/授权属 v45 车道 H 的 {@code V138}），也<b>不提供任何写病历的端点</b>——
 * 结构化值一律经既有病历保存端点（门诊 {@code PUT /api/outpatient/doctor/{id}/emr}、
 * 住院 {@code POST /api/inpatient/admissions/{id}/records}）的可空 {@code fields} 参数落库，
 * 这样签名冻结、诊断替换、CDSS 触发这些既有规则一条都不会被绕开。
 *
 * <p>错误码（本车道独占 4024–4029）：
 * <b>4024</b> 字段定义不存在或已停用 / <b>4026</b> 取值不在值域内（含单选多选未配候选值）/
 * <b>4027</b> 数据类型不匹配或字段定义不成立 / <b>4028</b> 结构化元素检索条件非法。
 * （4025 必填未填、4029 渲染超长发生在病历保存侧，见 DoctorStationService / InpEmrController。）
 */
@RestController
@RequestMapping("/api/emr")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE','QUALITY')")
@RequiredArgsConstructor
public class EmrFieldController {

    private final JdbcTemplate jdbc;

    /** 1075★ 明文六型，一个不少一个不多（与 V139 的 CHECK 约束、两侧录入校验同源；单测直接断言本表） */
    public static final List<String> DATATYPES =
            List.of("TEXT", "NUMBER", "CHECKBOX", "RADIO", "MULTI", "DATE");

    /** 需要候选值的两型 */
    private static final List<String> VALUE_SET_TYPES = List.of("RADIO", "MULTI");

    /** 字段编码白名单：既是编码规范，也让 1098 的检索入参有一条可校验的形状（防注入的第二道闸） */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    /** 检索结果硬上限（照抄 mr-workqueue / v43 医嘱检索的纪律：限量 + truncated 标记，不做翻页） */
    static final int SEARCH_LIMIT = 200;

    // ==================== ① 字段定义 CRUD ====================

    /**
     * 某模板的字段定义列表。
     *
     * <p>这就是<b>前端动态表单的渲染契约</b>：按 {@code sortNo} 升序返回，前端逐条按
     * {@code datatype} 选控件、按 {@code valueSet} 出候选、按 {@code required} 标星、
     * 按 {@code unit} 显示单位、按数组下标排 tabindex（989★ 的"元素快速跳转"）。
     *
     * @param includeDisabled 缺省只返回启用中的（录入用）；维护页传 true 看全量
     */
    @GetMapping("/templates/{templateId}/fields")
    public R<List<Map<String, Object>>> fields(@PathVariable Long templateId,
                                               @RequestParam(required = false) Boolean includeDisabled) {
        var rows = jdbc.queryForList("""
                select id, template_id, field_code, label, datatype, required, sort_no,
                       value_set, placeholder, unit, enabled, created_at
                from emr_template_field
                where template_id = ? and (cast(? as boolean) or enabled = true)
                order by sort_no, id
                """, templateId, Boolean.TRUE.equals(includeDisabled));
        return R.ok(rows.stream().map(EmrFieldController::toDto).toList());
    }

    /**
     * 返回体形状（前端契约，键名与病历保存时 {@code fields} 的键一一对应）：
     * <pre>{ id, templateId, fieldCode, label, datatype, required, sortNo,
     *   valueSet: string[]（非单选多选恒为 []）, placeholder, unit, enabled }</pre>
     * {@code valueSet} 在库里是 text 存的 JSON 数组（本仓惯例），出接口时<b>已解析成数组</b>——
     * 不把 JSON 字符串丢给前端二次 parse。
     */
    private static Map<String, Object> toDto(Map<String, Object> r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", r.get("id"));
        m.put("templateId", r.get("template_id"));
        m.put("fieldCode", r.get("field_code"));
        m.put("label", r.get("label"));
        m.put("datatype", r.get("datatype"));
        m.put("required", r.get("required"));
        m.put("sortNo", r.get("sort_no"));
        m.put("valueSet", parseValueSet((String) r.get("value_set")));
        m.put("placeholder", r.get("placeholder"));
        m.put("unit", r.get("unit"));
        m.put("enabled", r.get("enabled"));
        return m;
    }

    public record FieldReq(String fieldCode, String label, String datatype, Boolean required,
                           Integer sortNo, List<String> valueSet, String placeholder, String unit,
                           Boolean enabled) {}

    /** 新增字段定义。datatype 非白名单返 4027；单选/多选未配候选值返 4026 */
    @PostMapping("/templates/{templateId}/fields")
    public R<Map<String, Object>> create(@PathVariable Long templateId, @RequestBody FieldReq req) {
        Integer tpl = jdbc.queryForObject(
                "select count(1) from emr_template where id = ?", Integer.class, templateId);
        if (tpl == null || tpl == 0) {
            return R.fail(4024, "病历模板不存在：" + templateId);
        }
        String err = validate(req, true);
        if (err != null) {
            return R.fail(errCode(err), err.substring(err.indexOf('|') + 1));
        }
        Integer dup = jdbc.queryForObject(
                "select count(1) from emr_template_field where template_id = ? and field_code = ?",
                Integer.class, templateId, req.fieldCode().trim());
        if (dup != null && dup > 0) {
            return R.fail(4027, "字段编码在本模板下已存在：" + req.fieldCode().trim());
        }
        Long id = jdbc.queryForObject("""
                insert into emr_template_field
                    (template_id, field_code, label, datatype, required, sort_no, value_set,
                     placeholder, unit, enabled)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class,
                templateId, req.fieldCode().trim(), req.label().trim(), req.datatype().trim().toUpperCase(),
                Boolean.TRUE.equals(req.required()), req.sortNo() == null ? 0 : req.sortNo(),
                serializeValueSet(req.valueSet()), blankToNull(req.placeholder()), blankToNull(req.unit()),
                req.enabled() == null || req.enabled());
        return R.ok(oneDto(id));
    }

    /**
     * 修改字段定义（含启用/停用）。字段不存在返 4024。
     *
     * <p><b>{@code fieldCode} 刻意不可改</b>：它是历史病历 {@code content_json} 里的键，
     * 改了等于把已落库的结构化值全部变成无主孤儿（也会让 1098 的历史检索断线）。
     * 要换编码就停用旧字段、新建一个。
     */
    @PutMapping("/fields/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody FieldReq req) {
        var cur = jdbc.queryForList("select * from emr_template_field where id = ?", id);
        if (cur.isEmpty()) {
            return R.fail(4024, "模板字段定义不存在：" + id);
        }
        String err = validate(req, false);
        if (err != null) {
            return R.fail(errCode(err), err.substring(err.indexOf('|') + 1));
        }
        jdbc.update("""
                update emr_template_field
                set label = ?, datatype = ?, required = ?, sort_no = ?, value_set = ?,
                    placeholder = ?, unit = ?, enabled = ?
                where id = ?
                """,
                req.label().trim(), req.datatype().trim().toUpperCase(),
                Boolean.TRUE.equals(req.required()), req.sortNo() == null ? 0 : req.sortNo(),
                serializeValueSet(req.valueSet()), blankToNull(req.placeholder()), blankToNull(req.unit()),
                req.enabled() == null || req.enabled(), id);
        return R.ok(oneDto(id));
    }

    /**
     * "删除"字段定义 = <b>停用</b>（软开关，不删行）。
     *
     * <p>历史病历的 {@code content_json} 里还留着这个 fieldCode 的值，定义行一删，
     * 那些值就再也解释不出"当时录的是什么元素"——法定病历的可追溯性不允许这样。
     * 停用后：录入侧不再渲染该元素（传它一律 4024），检索侧仍能查到历史值。
     */
    @DeleteMapping("/fields/{id}")
    public R<Void> disable(@PathVariable Long id) {
        int n = jdbc.update("update emr_template_field set enabled = false where id = ? and enabled = true", id);
        return n == 0 ? R.fail(4024, "模板字段定义不存在或已停用：" + id) : R.ok();
    }

    private Map<String, Object> oneDto(Long id) {
        return toDto(jdbc.queryForList("""
                select id, template_id, field_code, label, datatype, required, sort_no,
                       value_set, placeholder, unit, enabled, created_at
                from emr_template_field where id = ?
                """, id).get(0));
    }

    /** 返回 "错误码|文案"，null 表示通过（合并成一条是为让两个写端点共用同一批判定，不复制粘贴） */
    private static String validate(FieldReq req, boolean requireCode) {
        if (requireCode && (req.fieldCode() == null || !CODE.matcher(req.fieldCode().trim()).matches())) {
            return "4027|字段编码非法：只接受字母/数字/下划线，1-64 位（它是 content_json 的键与检索入参）";
        }
        if (req.label() == null || req.label().isBlank()) {
            return "4027|字段标签不能为空（标签是渲染进病历正文的那个中文名）";
        }
        String dt = req.datatype() == null ? "" : req.datatype().trim().toUpperCase();
        if (!DATATYPES.contains(dt)) {
            return "4027|字段数据类型非法：" + req.datatype() + "（只接受 " + String.join("/", DATATYPES) + "）";
        }
        if (VALUE_SET_TYPES.contains(dt)) {
            var vs = req.valueSet();
            if (vs == null || vs.stream().allMatch(v -> v == null || v.isBlank())) {
                return "4026|单选/多选字段必须配置候选值 valueSet";
            }
        }
        return null;
    }

    private static int errCode(String err) {
        return Integer.parseInt(err.substring(0, err.indexOf('|')));
    }

    /** 候选值以 JSON 数组存 text（本仓惯例，不开 jsonb）；非单选多选传了也照存，不额外报错 */
    private static String serializeValueSet(List<String> vs) {
        if (vs == null || vs.isEmpty()) {
            return null;
        }
        var kept = vs.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
        if (kept.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(kept);
        } catch (Exception e) {
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static List<String> parseValueSet(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            var node = MAPPER.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            var out = new java.util.ArrayList<String>();
            node.forEach(n -> {
                if (n.asText() != null && !n.asText().isBlank()) {
                    out.add(n.asText().trim());
                }
            });
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ==================== ② 1098★ 结构化元素内容检索 ====================

    /**
     * 按<b>结构化元素</b>检索病历（1098★："给予检索门急诊病历中某个结构化元素内容的接口通道"）。
     *
     * <p>检索的是侧车列 {@code content_json}，不是正文全文——正文检索是另一件事（模糊匹配），
     * 而 1098 要的是"哪些病历的<b>这个元素</b>取了这个值"，是结构化查询。
     *
     * <p><b>注入安全</b>：{@code fieldCode} 先过 {@code ^[A-Za-z0-9_]{1,64}$} 白名单（非法 4028），
     * 再<b>作为 SQL 参数</b>喂给 {@code json ->> cast(? as text)}——<b>任何一个字符都不拼进 SQL</b>；
     * {@code value} 走 {@code ilike ? escape '\'} 且先做 {@code %} / {@code _} / {@code \} 转义，
     * 因此搜 {@code %} 只会匹配真的含 {@code %} 的病历，不会退化成匹配全部
     * （V45StructuredEmrTest#searchIsInjectionSafe 钉死）。
     *
     * <p><b>结果上限</b>：照抄 mr-workqueue 与 v43 医嘱检索的纪律——硬上限
     * {@value #SEARCH_LIMIT} 条 + {@code truncated} 标记，<b>不做翻页</b>：命中超限说明条件太宽
     * （多半是只填了 fieldCode 没填 value），应收窄条件而不是翻页。
     *
     * <p>门诊与住院各查一趟（两张表列名不同，硬 union 要凑列型，得不偿失），Java 侧按时间倒序归并。
     *
     * @param fieldCode 结构化元素编码，必填
     * @param value     元素值包含匹配（不区分大小写）；不填则返回"填过这个元素"的全部病历
     * @param from      记录时间下界（含），门诊看 updated_at、住院看 created_at
     * @param to        记录时间上界（含当日）
     * @param emrType   限定 OUTP 门诊 / INP 住院；不填两者都查
     */
    @GetMapping("/field-search")
    public R<Map<String, Object>> fieldSearch(
            @RequestParam String fieldCode,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String emrType) {
        String code = fieldCode == null ? "" : fieldCode.trim();
        if (!CODE.matcher(code).matches()) {
            return R.fail(4028, "结构化元素编码非法：只接受字母/数字/下划线，1-64 位");
        }
        String v = value == null ? "" : value.trim();
        if (v.length() > 128) {
            return R.fail(4028, "检索值过长（上限 128 字符）");
        }
        if (from != null && to != null && from.isAfter(to)) {
            return R.fail(4028, "检索时间区间非法：起始日期晚于截止日期");
        }
        String type = emrType == null ? "" : emrType.trim().toUpperCase();
        if (!type.isEmpty() && !List.of("OUTP", "INP").contains(type)) {
            return R.fail(4028, "病历类型非法（OUTP 门诊 / INP 住院，不填则两者都查）");
        }

        var rows = new java.util.ArrayList<Map<String, Object>>();
        if (!"INP".equals(type)) {
            rows.addAll(query(OUTP_SQL, "e.updated_at", code, v, from, to));
        }
        if (!"OUTP".equals(type)) {
            rows.addAll(query(INP_SQL, "r.created_at", code, v, from, to));
        }
        rows.sort(java.util.Comparator.comparing(
                (Map<String, Object> m) -> instantOf(m.get("recordedAt")),
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        boolean truncated = rows.size() > SEARCH_LIMIT;
        List<Map<String, Object>> items = truncated ? rows.subList(0, SEARCH_LIMIT) : rows;

        var m = new LinkedHashMap<String, Object>();
        m.put("fieldCode", code);
        m.put("items", items);
        m.put("total", items.size());
        m.put("limit", SEARCH_LIMIT);
        m.put("truncated", truncated);
        return R.ok(m);
    }

    /**
     * {@code content_json} 是 text 列（本仓惯例不开 jsonb），故读侧显式 {@code ::json} 再取键。
     * {@code cast(? as text)} 不能省：{@code ->>} 同时有 {@code json->>int} 与 {@code json->>text}
     * 两个重载，参数不带类型时 PostgreSQL 会报 "operator is not unique"。
     */
    private static final String OUTP_SQL = """
            select 'OUTP' as emr_type, e.id as emr_id, e.registration_id as visit_id,
                   e.template_id, t.name as template_name, e.updated_at as recorded_at,
                   p.id as patient_id, p.name as patient_name,
                   r.reg_no as visit_no, r.visit_date, d.name as dept_name,
                   e.chief_complaint as title,
                   (e.content_json::json) ->> cast(? as text) as field_value
            from outp_emr e
            join outp_registration r on r.id = e.registration_id
            join empi_patient p on p.id = r.patient_id
            left join sys_dept d on d.id = r.dept_id
            left join emr_template t on t.id = e.template_id
            where e.content_json is not null
              and (e.content_json::json) ->> cast(? as text) is not null
            """;

    private static final String INP_SQL = """
            select 'INP' as emr_type, r.id as emr_id, r.admission_id as visit_id,
                   r.template_id, t.name as template_name, r.created_at as recorded_at,
                   p.id as patient_id, p.name as patient_name,
                   a.admission_no as visit_no, a.admit_at as visit_date, d.name as dept_name,
                   r.title as title,
                   (r.content_json::json) ->> cast(? as text) as field_value
            from inp_medical_record r
            join inp_admission a on a.id = r.admission_id
            join empi_patient p on p.id = a.patient_id
            left join sys_dept d on d.id = a.dept_id
            left join emr_template t on t.id = r.template_id
            where r.content_json is not null
              and (r.content_json::json) ->> cast(? as text) is not null
            """;

    private List<Map<String, Object>> query(String base, String timeCol, String code, String value,
                                            LocalDate from, LocalDate to) {
        var sql = new StringBuilder(base);
        var args = new java.util.ArrayList<Object>();
        args.add(code);     // select 里的 ->>
        args.add(code);     // where 里的 is not null
        String jsonCol = base.startsWith("select 'OUTP'") ? "e.content_json" : "r.content_json";
        if (!value.isEmpty()) {
            sql.append(" and (").append(jsonCol).append("::json) ->> cast(? as text) ilike ? escape '\\'");
            args.add(code);
            args.add("%" + escapeLike(value) + "%");
        }
        if (from != null) {
            sql.append(" and ").append(timeCol).append(" >= ?");
            args.add(java.sql.Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" and ").append(timeCol).append(" < ?");
            args.add(java.sql.Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        sql.append(" order by ").append(timeCol).append(" desc limit ?");
        args.add(SEARCH_LIMIT + 1);
        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(EmrFieldController::toHit).toList();
    }

    /** 检索命中行（前端契约：门诊住院同形，靠 emrType 区分） */
    private static Map<String, Object> toHit(Map<String, Object> r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("emrType", r.get("emr_type"));
        m.put("emrId", r.get("emr_id"));
        m.put("visitId", r.get("visit_id"));
        m.put("visitNo", r.get("visit_no"));
        m.put("patientId", r.get("patient_id"));
        m.put("patientName", r.get("patient_name"));
        m.put("deptName", r.get("dept_name"));
        m.put("title", r.get("title"));
        m.put("templateId", r.get("template_id"));
        m.put("templateName", r.get("template_name"));
        m.put("fieldValue", r.get("field_value"));
        m.put("recordedAt", r.get("recorded_at"));
        return m;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 门诊 updated_at 与住院 created_at 由驱动分别返回 Timestamp / OffsetDateTime，归并排序前统一 */
    private static java.time.Instant instantOf(Object v) {
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (v instanceof java.time.Instant i) return i;
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (v instanceof java.time.LocalDateTime l) return l.atZone(java.time.ZoneId.systemDefault()).toInstant();
        return null;
    }
}
