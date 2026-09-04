package cn.hip.outpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.service.PatientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v45 车道J：临床资料引用带出（偏离表 992★/1092★）+ 复制粘贴管控的配置读口（1082★）。
 *
 * <p><b>本类是纯只读聚合层，一行写路径都没有。</b>没有任何 {@code insert/update/delete}，
 * 不注入任何 Service，不开事务——{@code V45EmrRefTest} 用「调用前后 outp_emr /
 * inp_medical_record 行数与全文逐字不变」把这条钉死。引用带出的语义就是"把已有的资料
 * 抄一段给医生看"，它<b>不能</b>顺手改病历，否则 992 就从便利功能变成了数据污染源。
 *
 * <p><b>返回体契约（四种 kind 同形）</b>：每段都返回两份——
 * {@code items[].text} 是<b>可直接插入病历正文的文本片段</b>（前端"插入正文"按钮用），
 * {@code items[].raw} 是<b>原始结构化数据</b>（前端要自己排版、或将来要做结构化引用时用）；
 * 段级 {@code snippet} 是全部 item 文本的合并版（"全部插入"按钮用）。
 *
 * <p><b>限条数纪律</b>：LAB/EXAM 照抄 mr-workqueue（硬限 {@value #ROW_LIMIT} 条 +
 * {@code truncated} 标记，不做翻页）；HISTORY 限最近 {@value #HISTORY_LIMIT} 次。
 * 每种 kind 至多两条聚合 SQL，<b>无 N+1</b>。
 *
 * <p><b>诚实边界（务必读，别把它当成"全院资料引用"）</b>：
 * <ul>
 *   <li>检验结果只有 {@code outp_lab_result}、检查报告只有 {@code ris_exam}，两张表都
 *       <b>只外键到 {@code outp_order}</b>——全仓不存在挂在 {@code inp_order} 上的结果表。
 *       因此住院患者查 LAB/EXAM 引用，拿到的是<b>该患者门诊侧</b>的结果；住院医嘱开出的
 *       检验检查在本平台<b>尚无结果落地表</b>，引用不到就是引用不到，此处不编造空壳条目。</li>
 *   <li>BASIC 段只带姓名/性别/年龄/门诊号或住院号/过敏史/血型/科室这些<b>写病历会用到</b>的项，
 *       <b>刻意不带身份证号与手机号</b>——引用带出不是患者档案导出口。</li>
 * </ul>
 *
 * <p><b>越权口径</b>：复用 {@code DiagnosisController#add} 的对象级归属校验——
 * ADMIN/QUALITY 按业务横切；仅具 DOCTOR_OUTP 的医生只能查<b>本人接诊/主管</b>的就诊，
 * 归属未采集（doctor_id 为 null）时放行（不能强推一个从未采集的归属）。
 * 本端点一次返回该患者的既往病历正文与检验检查全文，越权读取即隐私事故，
 * 故这里比 {@code /workspace} 更严——<b>它是新增读面，收紧不影响任何既有行为</b>。
 *
 * <p><b>错误码 4036</b>（无权查阅）：任务书原给本车道预留 4029，但车道 I 已把 4029 用在
 * {@code DoctorStationService.saveEmr}（结构化内容渲染进现病史超长），4024–4029 整段被其占满。
 * 改用门诊段里实测空置的 4036（4030–4039 是 v44 诊断段，实测只用掉 4033/4034/4035）。
 * 合版时请在 {@code docs/错误码分段.md} 登记。参数校验一律复用通用 4000，不另占码。
 */
@Slf4j
@RestController
@RequestMapping("/api/outpatient/emr-ref")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
@RequiredArgsConstructor
public class EmrRefController {

    /** 检验/检查引用条数硬上限（照抄 mr-workqueue：限 + truncated，不做翻页） */
    public static final int ROW_LIMIT = 200;

    /** 历史病历引用条数上限（规划文档明文"限最近 20 次"） */
    public static final int HISTORY_LIMIT = 20;

    /** 1082★ 跨患者复制粘贴管控开关（三态 off|warn|block，默认 warn） */
    public static final String COPY_GATE_KEY = "emr.copy.cross_patient";

    /**
     * 默认 <b>warn</b> 而非 block：直接禁止会挡住合理的模板套用与同患者续写，
     * 且本平台此前从无此限制，上来就硬拦是运行时打扰（全仓 gate 纪律：默认 warn 运行时零打扰）。
     */
    public static final String COPY_GATE_DEFAULT = "warn";

    private static final Set<String> GATE_MODES = Set.of("off", "warn", "block");

    private static final Set<String> KINDS = Set.of("BASIC", "LAB", "EXAM", "HISTORY");

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;
    private final CurrentUserService currentUserService;

    /** 定位到的一次就诊（门诊挂号 或 住院病案，二选一）+ 其患者与归属医生 */
    private record Encounter(Long registrationId, Long admissionId, Long patientId, Long ownerDoctorId,
                             Map<String, Object> head) {}

    /** 合并排序用：一条引用条目 + 它的时间 */
    private record Entry(Instant at, Map<String, Object> item) {}

    // ==================== ① 1082★ 复制粘贴管控：配置读口 ====================

    /**
     * 跨患者复制粘贴管控档位（1082★）。
     *
     * <p>前端病历输入框据此决定粘贴时 <b>放行 / 弹确认 / 拒绝</b>。
     * {@code /api/config/public} 是院名电话那类公开键的白名单，业务参数不得经它外泄，
     * 故本键由本控制器（已限 ADMIN/DOCTOR_OUTP）自带读口。
     *
     * <p>配置值非法（不是三态之一）时<b>回落 warn 而不是 off</b>——坏配置不能把管控静默关掉。
     */
    @GetMapping("/copy-policy")
    public R<Map<String, Object>> copyPolicy() {
        var m = new LinkedHashMap<String, Object>();
        m.put("key", COPY_GATE_KEY);
        m.put("mode", copyGateMode());
        m.put("defaultMode", COPY_GATE_DEFAULT);
        // 诚实边界随配置一起下发，前端直接显示，避免院方以为这是"全面防复制"
        m.put("scopeNote", "仅能识别本系统内复制的病历片段（复制时记下来源患者）；"
                + "从外部编辑器/浏览器/纸质材料粘贴进来的内容无法识别来源，本管控识别不到，也不拦截。");
        return R.ok(m);
    }

    /** 三态归一：非法值回落默认 warn（并留一条 warn 日志，方便管理员发现配置写错了） */
    private String copyGateMode() {
        String v = configReader.get(COPY_GATE_KEY, COPY_GATE_DEFAULT);
        String mode = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        if (!GATE_MODES.contains(mode)) {
            log.warn("sys_config 键 {} 的值 [{}] 不是 off/warn/block，按默认 {} 处理", COPY_GATE_KEY, v,
                    COPY_GATE_DEFAULT);
            return COPY_GATE_DEFAULT;
        }
        return mode;
    }

    // ==================== ② 992★/1092★ 引用带出：只读聚合 ====================

    /**
     * 临床资料引用（992★ 引用患者基本资料/检验/检查/历史病历；1092★ 新建病历自动带出基础信息）。
     *
     * @param registrationId 门诊挂号 id（与 admissionId 二选一）
     * @param admissionId    住院病案 id（与 registrationId 二选一）
     * @param kind           BASIC 基本资料 / LAB 检验 / EXAM 检查 / HISTORY 历史病历
     */
    @GetMapping
    public R<Map<String, Object>> ref(@RequestParam(required = false) Long registrationId,
                                      @RequestParam(required = false) Long admissionId,
                                      @RequestParam String kind,
                                      Authentication auth) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(k)) {
            return R.fail(4000, "请求参数不正确：kind 须为 BASIC / LAB / EXAM / HISTORY 之一");
        }
        if ((registrationId == null) == (admissionId == null)) {
            return R.fail(4000, "请求参数不正确：registrationId 与 admissionId 须且只须传一个");
        }
        Encounter enc = loadEncounter(registrationId, admissionId);
        if (enc == null) {
            return R.fail(4000, registrationId != null ? "挂号记录不存在" : "住院记录不存在");
        }
        if (!canRead(enc, auth)) {
            return R.fail(4036, "无权查阅该患者的临床引用资料（非本人接诊/主管的就诊）");
        }
        return R.ok(switch (k) {
            case "BASIC" -> basic(enc);
            case "LAB" -> lab(enc);
            case "EXAM" -> exam(enc);
            default -> history(enc);
        });
    }

    // ---------- 就诊定位与归属校验 ----------

    private Encounter loadEncounter(Long registrationId, Long admissionId) {
        if (registrationId != null) {
            var rows = jdbc.queryForList("""
                    select r.id as registration_id, r.doctor_id, r.visit_date, r.reg_no, r.status,
                           p.id as patient_id, p.patient_no, p.name, p.sex, p.birth_date,
                           p.allergy_history, p.blood_type, d.name as dept_name
                    from outp_registration r
                    join empi_patient p on p.id = r.patient_id
                    left join sys_dept d on d.id = r.dept_id
                    where r.id = ?
                    """, registrationId);
            if (rows.isEmpty()) return null;
            var h = rows.get(0);
            return new Encounter(registrationId, null, asLong(h.get("patient_id")),
                    asLong(h.get("doctor_id")), h);
        }
        var rows = jdbc.queryForList("""
                select a.id as admission_id, a.doctor_id, a.admission_no, a.admit_at, a.status,
                       a.admit_diag_name, p.id as patient_id, p.patient_no, p.name, p.sex,
                       p.birth_date, p.allergy_history, p.blood_type, d.name as dept_name
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                left join sys_dept d on d.id = a.dept_id
                where a.id = ?
                """, admissionId);
        if (rows.isEmpty()) return null;
        var h = rows.get(0);
        return new Encounter(null, admissionId, asLong(h.get("patient_id")),
                asLong(h.get("doctor_id")), h);
    }

    /**
     * 对象级归属校验（口径同 {@code DiagnosisController#add}）：
     * ADMIN/QUALITY 横切；其余角色只能读归属本人的就诊；归属为空时放行。
     */
    private boolean canRead(Encounter enc, Authentication auth) {
        if (hasCrossDomainRole(auth)) return true;
        Long owner = enc.ownerDoctorId();
        if (owner == null) return true;
        // auth 为空是"认不出是谁"：归属已采集却认不出人，只能拒（安全的默认方向）
        return auth != null && owner.equals(currentUserService.idOf(auth));
    }

    private static boolean hasCrossDomainRole(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .anyMatch(r -> "ROLE_ADMIN".equals(r) || "ROLE_QUALITY".equals(r));
    }

    // ---------- BASIC：1092★ 新建病历自动带出 ----------

    /**
     * 患者基本资料。{@code prefill} 是 1092★ 的"自动带出"载荷：新建病历时前端直接预填，
     * <b>医生可改</b>（带出的是资料不是判断，不锁死）。
     */
    private Map<String, Object> basic(Encounter enc) {
        var h = enc.head();
        Integer age = PatientService.ageOf(asLocalDate(h.get("birth_date")));
        String visitNo = enc.registrationId() != null
                ? "第 " + str(h.get("reg_no")) + " 号（" + str(h.get("visit_date")) + "）"
                : str(h.get("admission_no"));

        var prefill = new LinkedHashMap<String, Object>();
        prefill.put("patientId", enc.patientId());
        prefill.put("patientName", h.get("name"));
        prefill.put("patientNo", h.get("patient_no"));
        prefill.put("sex", h.get("sex"));
        prefill.put("sexText", sexText(str(h.get("sex"))));
        prefill.put("age", age);
        prefill.put("birthDate", h.get("birth_date"));
        prefill.put("visitNo", visitNo);
        prefill.put("deptName", h.get("dept_name"));
        prefill.put("allergyHistory", h.get("allergy_history"));
        prefill.put("bloodType", h.get("blood_type"));

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(field("name", "姓名", str(h.get("name"))));
        items.add(field("sex", "性别", sexText(str(h.get("sex")))));
        items.add(field("age", "年龄", age == null ? "" : age + " 岁"));
        items.add(field("patientNo", enc.registrationId() != null ? "门诊号" : "患者编号",
                str(h.get("patient_no"))));
        items.add(field("visitNo", enc.registrationId() != null ? "本次就诊" : "住院号", visitNo));
        items.add(field("deptName", "就诊科室", str(h.get("dept_name"))));
        items.add(field("allergyHistory", "过敏史",
                blank(str(h.get("allergy_history"))) ? "无记录" : str(h.get("allergy_history"))));
        items.add(field("bloodType", "血型",
                blank(str(h.get("blood_type"))) ? "无记录" : str(h.get("blood_type"))));
        items.removeIf(i -> blank(str(i.get("text"))));

        var out = envelope("BASIC", enc, items, items.size(), false);
        out.put("prefill", prefill);
        return out;
    }

    /** BASIC 段的一项：raw 只放该项自身的结构化取值，不把整张患者表重复八遍 */
    private static Map<String, Object> field(String code, String label, String value) {
        var m = new LinkedHashMap<String, Object>();
        m.put("refId", "BASIC-" + code);
        m.put("title", label);
        m.put("text", blank(value) ? "" : label + "：" + value);
        m.put("raw", Map.of("code", code, "label", label, "value", value == null ? "" : value));
        return m;
    }

    // ---------- LAB：检验结果（含异常标记） ----------

    private Map<String, Object> lab(Encounter enc) {
        List<Entry> entries = new ArrayList<>();

        // ① 结构化检验结果（唯一带 abnormal_flag 的来源）
        for (var r : jdbc.queryForList("""
                select lr.id, lr.item_code, lr.item_name, lr.result_value, lr.unit, lr.ref_range,
                       lr.abnormal_flag, lr.created_at, o.id as order_id, o.item_name as order_name,
                       o.registration_id, reg.visit_date
                from outp_lab_result lr
                join outp_order o on o.id = lr.order_id
                join outp_registration reg on reg.id = o.registration_id
                where reg.patient_id = ?
                order by lr.created_at desc, lr.id desc
                limit ?
                """, enc.patientId(), ROW_LIMIT + 1)) {
            boolean abnormal = !blank(str(r.get("abnormal_flag")));
            String text = str(r.get("item_name")) + " " + str(r.get("result_value"))
                    + suffix(" ", str(r.get("unit")))
                    + (blank(str(r.get("ref_range"))) ? "" : "（参考 " + str(r.get("ref_range")) + "）")
                    + (abnormal ? "［异常 " + str(r.get("abnormal_flag")) + "］" : "");
            var it = item("LAB-RESULT-" + str(r.get("id")),
                    str(r.get("order_name")) + " · " + dateText(r.get("visit_date"), r.get("created_at")),
                    text, r);
            it.put("abnormal", abnormal);
            it.put("currentVisit", sameRegistration(enc, r.get("registration_id")));
            entries.add(new Entry(asInstant(r.get("created_at")), it));
        }

        // ② 医技执行站的文本结果（LAB 单）：无结构化明细的检验只有这一份
        for (var r : jdbc.queryForList("""
                select rep.id, rep.result_text, rep.executed_at, o.id as order_id,
                       o.item_name as order_name, o.registration_id, reg.visit_date
                from outp_order_report rep
                join outp_order o on o.id = rep.order_id
                join outp_registration reg on reg.id = o.registration_id
                where reg.patient_id = ? and o.order_type = 'LAB' and rep.result_text is not null
                order by rep.executed_at desc, rep.id desc
                limit ?
                """, enc.patientId(), ROW_LIMIT + 1)) {
            var it = item("LAB-REPORT-" + str(r.get("id")),
                    str(r.get("order_name")) + " · " + dateText(r.get("visit_date"), r.get("executed_at")),
                    str(r.get("order_name")) + "：" + str(r.get("result_text")), r);
            it.put("abnormal", false);
            it.put("currentVisit", sameRegistration(enc, r.get("registration_id")));
            entries.add(new Entry(asInstant(r.get("executed_at")), it));
        }
        return capped("LAB", enc, entries, ROW_LIMIT);
    }

    // ---------- EXAM：检查报告 ----------

    private Map<String, Object> exam(Encounter enc) {
        List<Entry> entries = new ArrayList<>();

        for (var r : jdbc.queryForList("""
                select e.id, e.status, e.modality, e.findings, e.impression, e.critical_flag,
                       e.critical_note, e.reported_at, e.created_at, o.id as order_id,
                       o.item_name as order_name, o.registration_id, reg.visit_date
                from ris_exam e
                join outp_order o on o.id = e.order_id
                join outp_registration reg on reg.id = o.registration_id
                where reg.patient_id = ?
                order by coalesce(e.reported_at, e.created_at) desc, e.id desc
                limit ?
                """, enc.patientId(), ROW_LIMIT + 1)) {
            StringBuilder sb = new StringBuilder(str(r.get("order_name")));
            if (!blank(str(r.get("findings")))) sb.append("　所见：").append(str(r.get("findings")));
            if (!blank(str(r.get("impression")))) sb.append("　印象：").append(str(r.get("impression")));
            if (Boolean.TRUE.equals(r.get("critical_flag"))) {
                sb.append("　［危急值").append(suffix("：", str(r.get("critical_note")))).append("］");
            }
            var it = item("EXAM-RIS-" + str(r.get("id")),
                    str(r.get("order_name")) + " · "
                            + dateText(r.get("visit_date"), first(r.get("reported_at"), r.get("created_at"))),
                    sb.toString(), r);
            it.put("critical", Boolean.TRUE.equals(r.get("critical_flag")));
            it.put("currentVisit", sameRegistration(enc, r.get("registration_id")));
            entries.add(new Entry(asInstant(first(r.get("reported_at"), r.get("created_at"))), it));
        }

        for (var r : jdbc.queryForList("""
                select rep.id, rep.result_text, rep.executed_at, o.id as order_id,
                       o.item_name as order_name, o.registration_id, reg.visit_date
                from outp_order_report rep
                join outp_order o on o.id = rep.order_id
                join outp_registration reg on reg.id = o.registration_id
                where reg.patient_id = ? and o.order_type = 'EXAM' and rep.result_text is not null
                order by rep.executed_at desc, rep.id desc
                limit ?
                """, enc.patientId(), ROW_LIMIT + 1)) {
            var it = item("EXAM-REPORT-" + str(r.get("id")),
                    str(r.get("order_name")) + " · " + dateText(r.get("visit_date"), r.get("executed_at")),
                    str(r.get("order_name")) + "：" + str(r.get("result_text")), r);
            it.put("critical", false);
            it.put("currentVisit", sameRegistration(enc, r.get("registration_id")));
            entries.add(new Entry(asInstant(r.get("executed_at")), it));
        }
        return capped("EXAM", enc, entries, ROW_LIMIT);
    }

    // ---------- HISTORY：历史病历正文 ----------

    /**
     * 该患者的历史病历正文（门诊 {@code outp_emr} + 住院 {@code inp_medical_record} 合并，
     * 按时间倒序取最近 {@value #HISTORY_LIMIT} 次）。
     *
     * <p><b>剔除本次就诊自身</b>：把正在写的这份再"引用"进来是自我复制，没有意义。
     * 两条 SQL 各多取 2 行（{@code HISTORY_LIMIT + 2}）而非 +1：本次就诊自身会被剔掉一条，
     * 只多取 1 行会让"刚好第 21 条"的场景漏报 truncated。
     */
    private Map<String, Object> history(Encounter enc) {
        List<Entry> entries = new ArrayList<>();

        for (var r : jdbc.queryForList("""
                select reg.id as registration_id, reg.visit_date, e.id as emr_id, e.chief_complaint,
                       e.present_illness, e.past_history, e.physical_exam, e.advice,
                       (e.signature is not null) as signed, e.updated_at
                from outp_emr e
                join outp_registration reg on reg.id = e.registration_id
                where reg.patient_id = ? and reg.status <> 'CANCELLED'
                order by reg.visit_date desc, reg.id desc
                limit ?
                """, enc.patientId(), HISTORY_LIMIT + 2)) {
            if (sameRegistration(enc, r.get("registration_id"))) continue;
            String text = joinSections(List.of(
                    section("主诉", str(r.get("chief_complaint"))),
                    section("现病史", str(r.get("present_illness"))),
                    section("既往史", str(r.get("past_history"))),
                    section("体格检查", str(r.get("physical_exam"))),
                    section("处理意见", str(r.get("advice")))));
            if (blank(text)) continue;
            var it = item("HIST-OUTP-" + str(r.get("emr_id")),
                    "门诊病历 · " + str(r.get("visit_date"))
                            + (Boolean.TRUE.equals(r.get("signed")) ? " · 已签名" : ""),
                    text, r);
            it.put("source", "OUTP");
            entries.add(new Entry(asInstant(r.get("updated_at")), it));
        }

        for (var r : jdbc.queryForList("""
                select m.id as record_id, m.admission_id, m.record_type, m.title, m.content,
                       (m.signature is not null) as signed, m.created_at, a.admission_no
                from inp_medical_record m
                join inp_admission a on a.id = m.admission_id
                where a.patient_id = ?
                order by m.created_at desc, m.id desc
                limit ?
                """, enc.patientId(), HISTORY_LIMIT + 2)) {
            if (sameAdmission(enc, r.get("admission_id"))) continue;
            if (blank(str(r.get("content")))) continue;
            var it = item("HIST-INP-" + str(r.get("record_id")),
                    "住院病历 · " + str(r.get("title")) + " · " + dateText(null, r.get("created_at"))
                            + (Boolean.TRUE.equals(r.get("signed")) ? " · 已签名" : ""),
                    str(r.get("title")) + "：" + str(r.get("content")), r);
            it.put("source", "INP");
            entries.add(new Entry(asInstant(r.get("created_at")), it));
        }
        return capped("HISTORY", enc, entries, HISTORY_LIMIT);
    }

    // ---------- 组装与小工具 ----------

    /** 合并排序 → 截断 → 打包（truncated 语义同 mr-workqueue：命中超上限，仅返回前 N 条） */
    private Map<String, Object> capped(String kind, Encounter enc, List<Entry> entries, int limit) {
        entries.sort(Comparator.comparing(Entry::at, Comparator.nullsLast(Comparator.reverseOrder())));
        boolean truncated = entries.size() > limit;
        List<Map<String, Object>> items = entries.stream().limit(limit).map(Entry::item).toList();
        return envelope(kind, enc, items, limit, truncated);
    }

    private Map<String, Object> envelope(String kind, Encounter enc, List<Map<String, Object>> items,
                                         int limit, boolean truncated) {
        var out = new LinkedHashMap<String, Object>();
        out.put("kind", kind);
        out.put("patientId", enc.patientId());
        out.put("registrationId", enc.registrationId());
        out.put("admissionId", enc.admissionId());
        out.put("limit", limit);
        out.put("truncated", truncated);
        out.put("count", items.size());
        out.put("snippet", buildSnippet(kind, items));
        out.put("items", items);
        return out;
    }

    private static final Map<String, String> KIND_TITLE = Map.of(
            "BASIC", "基本资料", "LAB", "检验结果", "EXAM", "检查报告", "HISTORY", "既往病历");

    /** 段级"全部插入"用的合并片段；空段返回空串（不生成"【检验结果】\n"这种空壳标题） */
    private static String buildSnippet(String kind, List<Map<String, Object>> items) {
        var body = items.stream().map(i -> str(i.get("text"))).filter(t -> !blank(t)).toList();
        if (body.isEmpty()) return "";
        return "【" + KIND_TITLE.getOrDefault(kind, kind) + "】\n" + String.join("\n", body);
    }

    private static Map<String, Object> item(String refId, String title, String text,
                                            Map<String, Object> raw) {
        var m = new LinkedHashMap<String, Object>();
        m.put("refId", refId);
        m.put("title", title);
        m.put("text", text);
        m.put("raw", raw);
        return m;
    }

    private static String section(String label, String value) {
        return blank(value) ? "" : label + "：" + value;
    }

    private static String joinSections(List<String> parts) {
        return String.join("；", parts.stream().filter(s -> !blank(s)).toList());
    }

    private boolean sameRegistration(Encounter enc, Object registrationId) {
        return enc.registrationId() != null && enc.registrationId().equals(asLong(registrationId));
    }

    private boolean sameAdmission(Encounter enc, Object admissionId) {
        return enc.admissionId() != null && enc.admissionId().equals(asLong(admissionId));
    }

    private static Object first(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String dateText(Object visitDate, Object at) {
        if (visitDate != null) return String.valueOf(visitDate);
        if (at == null) return "";
        return String.valueOf(at).replace('T', ' ');
    }

    private static String suffix(String sep, String v) {
        return blank(v) ? "" : sep + v;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String sexText(String sex) {
        return switch (sex == null ? "" : sex) {
            case "M" -> "男";
            case "F" -> "女";
            default -> "未知";
        };
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static LocalDate asLocalDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return null;
    }

    private static Instant asInstant(Object o) {
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof Instant i) return i;
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }
}
