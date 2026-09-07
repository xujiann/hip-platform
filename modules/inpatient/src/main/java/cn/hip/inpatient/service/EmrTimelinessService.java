package cn.hip.inpatient.service;

import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * v53 车道 V3：<b>病历书写时限质控</b>（错误码子段 5740–5759）。
 *
 * <h2>本类的判据是「能不能证明」，不是「算不算得出数」</h2>
 * 一个超时率是可以随便算出来的：拿任意两个时刻相减，除一除就有百分比。
 * 问题在于<b>那两个时刻是不是法定条文说的那两个时刻</b>。
 * 「首次病程 8 小时」的起点若取错——取了「首条医嘱时刻」而不是入院时刻——
 * 病历写得越晚起点就越晚，超时率会**恒为 0**，而且看不出破绽。
 * <b>选错锚点不会报错，只会让整张报表安静地失真。</b>
 *
 * <p>所以本类把「锚点」当成一等公民：
 * <ol>
 *   <li><b>可执行的锚点钉死在代码里（{@link #ANCHORS}），不从数据库字符串拼 SQL。</b>
 *       {@code emr_timeliness_rule.start_anchor / end_anchor} 两列是<b>给人读的口径说明</b>，
 *       是可写的配置数据；把它拼进 SQL 既是注入口，也意味着改一行配置就能悄悄换掉判定口径。</li>
 *   <li><b>两者对不上就停下来说对不上。</b>{@link #anchorMismatch} 逐字比对「库里写的锚点」
 *       与「代码里执行的锚点」，不一致即把该规则降级为不可执行、不给 rows——
 *       <b>不按其中任何一个蒙着算</b>。报表说的锚点必须就是它真正用的锚点。</li>
 *   <li><b>锚点字段是否真的有值，用机器查、不用嘴说。</b>{@link #anchors()} 逐规则回答
 *       「这一列在 information_schema 里存不存在、全表填充率多少」，
 *       让种子里那些 {@code unavailable_reason} 从「断言」变成「可复核的事实」。</li>
 * </ol>
 *
 * <h2>诚实标注（v46/v48/v50 的硬纪律，本车道逐条落实）</h2>
 * <ul>
 *   <li><b>{@code available=false} 的规则不给 rows、不给 0、不给近似值。</b>
 *       种子里 6 条算不出来（缺终点锚点/缺急会诊标识/缺死亡时刻…），返回体里只有原因与补法。
 *       返回一个看着像真的 0，会让管理者把「我们没有能力统计」误读成「我们没有超时」。</li>
 *   <li><b>分母为 0 时超时率返 {@code null} 而不是 0。</b>「0% 超时」与「没有可判定的病例」
 *       是两句完全不同的话，前者能拿去考核，后者只能拿去说明系统还没准备好。</li>
 *   <li><b>{@code source_verified=false} 的规则，条文号一律加后缀标出。</b>
 *       种子里的阈值与条文号是按现行规范填的<b>默认值，未经原文逐字核对</b>；
 *       不标出来，院方会把它当成本院制度用去考核医师。</li>
 *   <li><b>返回体带 {@code coverage} 段。</b>历史病历可能没有可靠的书写时刻，
 *       那样算出来的超时率是假的——先看覆盖率再看指标值。</li>
 *   <li><b>「未到时限」不算及时也不算超时。</b>{@code PENDING} 单列，不进分母。
 *       把在写的病历算成「及时」会让当日报表永远好看。</li>
 *   <li><b>终点早于起点单列为 {@code ANOMALY_NEGATIVE}，不进分子分母。</b>
 *       负数时长在及时率里会被当成「极其及时」，那是把一处数据缺陷伪装成一项优异指标
 *       （V159 对手术记录那条已写明同一个陷阱）。</li>
 * </ul>
 *
 * <h2>本类不做的事</h2>
 * <ul>
 *   <li><b>不碰任何既有写路径</b>：{@link #verdict(Long)} 是纯只读、不 throw 的判定，
 *       挂到出院/归档哪个挡点由主控裁决（已写进 cross_lane）。
 *       {@code DoctorStationService} / {@code InpatientService} / {@code InpEmrController} 一行不动。</li>
 *   <li><b>不回填、不推算任何时刻</b>：缺锚点就是缺锚点。</li>
 *   <li><b>不新建表、不加迁移</b>：规则字典与变更留痕已由 V159 落盘。</li>
 * </ul>
 *
 * <h2>错误码（子段 5740–5759，实测用掉 10 个：5740–5749，5750–5759 空置）</h2>
 * <ul>
 *   <li>5740 规则码不存在</li>
 *   <li>5741 日期窗口非法（格式/倒序/跨度超上限）</li>
 *   <li>5742 筛选或分组参数非法（by / deptId / doctorId / wardId / status）</li>
 *   <li>5743 分页参数非法（limit / offset）</li>
 *   <li>5744 该规则不可用（缺时间锚点），不提供清单与统计</li>
 *   <li>5745 该规则已停用，不进统计</li>
 *   <li>5746 规则维护入参非法（字段名或取值）</li>
 *   <li>5747 规则变更原因必填</li>
 *   <li>5748 无法识别当前登录用户，不能修改法定时限阈值</li>
 *   <li>5749 未达法定病历书写时限，不能出院/归档（<b>只有 block 档才返</b>）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EmrTimelinessService {

    /** gate 配置键。三态 off|warn|block，默认 warn，坏值回落 warn（不回落 off）。 */
    public static final String GATE_KEY = "emr.gate.timeliness";

    /**
     * <b>本键 V159 没有插 sys_config 行</b>（本车道名下只有两个 Java 文件，不加迁移）。
     * 缺行时 {@link ConfigReader} 回落默认值 warn，功能完整；但
     * {@code SysConfigController.update} 只 UPDATE 不 INSERT（键不存在返 1401），
     * 因此<b>在主控补上这一行之前，本 gate 只能停在 warn、调不动</b>。
     * {@code /config} 端点会把这个事实明说出来（{@code gateKeyRegistered}），已写进 cross_lane。
     */
    public static final String GATE_KEY_NOTE =
            "gate 键 " + GATE_KEY + " 尚未登记进 sys_config：当前恒为默认档 warn，"
            + "经 PUT /api/config/{key} 改不动（该端点只 UPDATE 不 INSERT，返 1401）。"
            + "需主控在其名下的迁移里补一行 insert into sys_config values ('" + GATE_KEY + "','warn',…) on conflict do nothing。";

    /** 未经病案科核对原文的条文号后缀。catalog 与 indicators 两处都加，想漏看都难。 */
    public static final String UNVERIFIED_SUFFIX = "（阈值与条文号待病案科核对原文确认）";

    private static final String UNVERIFIED_WARNING_PREFIX = "阈值待病案科确认：";

    /** 判定窗口跨度上限。超过即 5741——全表扫一年以上的病历不是质控，是把库拖垮。 */
    private static final int MAX_WINDOW_DAYS = 366;
    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 100;
    /** 跨规则合并清单时每条规则的取数上限，见 {@link #overdue} 的 {@code truncated} 说明。 */
    private static final int MERGE_CAP = 1000;

    /** 阈值合理域：1 分钟 – 366 天。超出即视为配置写错，回落规则表的 limit_minutes 并告警。 */
    private static final int MAX_LIMIT_MINUTES = 366 * 24 * 60;

    /** 标识符白名单。{@link #anchors()} 会把锚点列名拼进 count 查询，不校验就是注入口。 */
    private static final Pattern IDENT = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    /** 从「inp_admission.admit_at」「min(inp_medical_record.created_at) where …」里取出第一个 表.列 */
    private static final Pattern TABLE_COL = Pattern.compile("([a-z_][a-z0-9_]*)\\.([a-z_][a-z0-9_]*)");

    /** 判定结论码。新增只能追加，既有码不得改名（前端筛选、CSV 导出按码绑定）。 */
    public static final String ST_ON_TIME = "ON_TIME";
    public static final String ST_LATE = "LATE";
    public static final String ST_MISSING_OVERDUE = "MISSING_OVERDUE";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_ANOMALY_NEGATIVE = "ANOMALY_NEGATIVE";

    private static final List<String> ALL_STATUS =
            List.of(ST_ON_TIME, ST_LATE, ST_MISSING_OVERDUE, ST_PENDING, ST_ANOMALY_NEGATIVE);

    /** gate 缺项码（结构与 {@link CountersignService.Finding} 对齐，便于主控并到同一个挡点） */
    public static final String CODE_TIMELINESS_LATE = "TIMELINESS_LATE";
    public static final String CODE_TIMELINESS_MISSING = "TIMELINESS_MISSING_OVERDUE";

    /** 逐行口径提示码 → 文案。放 Java 不放 SQL：SQL 里每行拼一段长中文是纯浪费。 */
    private static final Map<String, String> CAVEATS = Map.of(
            "FIRST_PROGRESS_UNTYPED",
            "该次住院有 PROGRESS 病程记录却无 FIRST_PROGRESS 类型记录。FIRST_PROGRESS 是 v34 才有的类型，"
            + "此前首次病程混写在 PROGRESS 里、在库里与普通病程无法区分，"
            + "因此本行的「未写」有可能是误判，不宜直接用于考核个人。",
            "ROUND_LEVEL_BLANK",
            "该次住院有 ROUND 查房记录但 round_level 为空。round_level 是 V115 才加的列，"
            + "本规则不把空 round_level 猜成主治查房（猜错会让指标虚高），"
            + "代价是此行判为「未写」，那是误报。");

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    // ==================================================================
    // 零、返回封装、gate、时刻
    // ==================================================================

    /** 统一结果封装：{@code code==0} 为成功，非 0 时 {@code body} 为 null。 */
    public record Result(int code, String message, Map<String, Object> body) {
        public boolean ok() {
            return code == 0;
        }
    }

    private static Result err(int code, String message) {
        return new Result(code, message, null);
    }

    private static Result ok(Map<String, Object> body) {
        return new Result(0, "success", body);
    }

    /**
     * gate 三态解析。<b>坏配置回落 warn 而不是 off</b>：把 'blocked'、'true'、'on' 这类写错的值
     * 当成 off，等于让一个笔误静默关掉一条法定校验。与 v53 车道 V1/V2 同口径。
     */
    public String gate() {
        String v = configReader.get(GATE_KEY, "warn");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
    }

    /** gate 键是否已登记进 sys_config（决定它调不调得动，见 {@link #GATE_KEY_NOTE}）。 */
    public boolean gateKeyRegistered() {
        Integer n = jdbc.queryForObject(
                "select count(*) from sys_config where cfg_key = ?", Integer.class, GATE_KEY);
        return n != null && n > 0;
    }

    /**
     * 全仓时刻纪律：{@code Instant.now().truncatedTo(MICROS)}。
     * PG 的 timestamptz 只存到微秒且四舍五入落盘，Java 的 Instant 带纳秒——
     * 不截断会产生「差 100ns 的假越界」，本仓已为此付过四次学费。
     */
    private static Timestamp nowTs() {
        return Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    // ==================================================================
    // 一、规则字典（含阈值寻址与锚点守卫）
    // ==================================================================

    /**
     * 一条时限规则。{@code limitMinutes} 是<b>生效阈值</b>（已解析 config_key），
     * {@code fallbackLimitMinutes} 是规则表里的回落值。两者不同时 {@code limitSource=CONFIG_KEY}。
     */
    public record Rule(
            long id, String code, String name, String scope,
            String startAnchor, String startAnchorCn, String endAnchor, String endAnchorCn,
            int fallbackLimitMinutes, String configKey, String configUnit, int warnRatioPct,
            String sourceDoc, String sourceArticle, LocalDate sourceEffectiveFrom, boolean sourceVerified,
            boolean available, String unavailableReason, String missingFields,
            boolean enabled, String remark,
            int limitMinutes, String limitSource, String configRawValue,
            boolean executable, String anchorNote, List<String> warnings) {

        /** 能不能真的算：可用 + 启用 + 代码里有可执行锚点且与库里声明一致。 */
        public boolean inPlay() {
            return available && enabled && executable;
        }

        /** 未核对原文时给条文号加后缀。source_article 可空（种子里全部非空，此处只作防御）。 */
        public String sourceArticleDisplay() {
            String a = sourceArticle == null ? "" : sourceArticle;
            return sourceVerified ? a : a + UNVERIFIED_SUFFIX;
        }
    }

    /**
     * 可执行锚点表。<b>键是 rule_code，值是钉死在代码里的 SQL</b>——不从数据库字符串拼 SQL，
     * 理由见类注释第 1 条。{@code declaredStart/declaredEnd} 是<b>守卫用的期望值</b>，
     * 必须与 V159 种子里 {@code start_anchor/end_anchor} 两列逐字一致，
     * 对不上即 {@link #anchorMismatch} 报警并把该规则降级为不可执行。
     */
    private record AnchorSpec(String declaredStart, String declaredEnd,
                              String startColumn, String admissionPredicate, String baseSelect) {}

    /** 住院口径的公共 from/join（三条以 inp_admission 为主体的规则共用，保证列集完全一致） */
    private static final String ADMISSION_JOINS = """
            from inp_admission a
            left join empi_patient p on p.id = a.patient_id
            left join sys_dept d on d.id = a.dept_id
            left join sys_dept w on w.id = a.ward_id
            left join sys_user du on du.id = a.doctor_id
            left join lateral (
                select min(m.created_at) as first_at,
                       (array_agg(m.doctor_id order by m.created_at, m.id))[1] as writer_id
                from inp_medical_record m
                where m.admission_id = a.id and %s
            ) r on true
            left join sys_user wu on wu.id = r.writer_id
            """;

    /**
     * 住院主体的公共投影。{@code %1$s} = 起点列表达式，{@code %2$s} = 逐行提示码表达式。
     * <b>列集在三条规则间完全一致</b>——汇总与穿透用同一段 SQL 才不会出现「点开对不上账」（v49 的课）。
     */
    private static final String ADMISSION_SELECT = """
            select 'ADMISSION'::text as subject_kind,
                   a.id as subject_id, a.admission_no as subject_no, a.id as admission_id,
                   a.patient_id, p.name as patient_name,
                   a.dept_id, d.name as dept_name, a.ward_id, w.name as ward_name,
                   a.doctor_id as duty_doctor_id, du.real_name as duty_doctor_name,
                   r.writer_id as written_by, wu.real_name as written_by_name,
                   %1$s as start_at, r.first_at as end_at,
                   %2$s as caveat
            """;

    private static String admissionBase(String startCol, String typePredicate, String caveatExpr) {
        return ADMISSION_SELECT.formatted(startCol, caveatExpr) + ADMISSION_JOINS.formatted(typePredicate);
    }

    private static final Map<String, AnchorSpec> ANCHORS = buildAnchors();

    private static Map<String, AnchorSpec> buildAnchors() {
        var m = new LinkedHashMap<String, AnchorSpec>();

        m.put("INP_ADMISSION_24H", new AnchorSpec(
                "inp_admission.admit_at",
                "min(inp_medical_record.created_at) where record_type='ADMISSION'",
                "a.admit_at", "a.id = ?",
                admissionBase("a.admit_at", "m.record_type = 'ADMISSION'", "null::text")));

        m.put("INP_FIRST_PROGRESS_8H", new AnchorSpec(
                "inp_admission.admit_at",
                "min(inp_medical_record.created_at) where record_type='FIRST_PROGRESS'",
                "a.admit_at", "a.id = ?",
                admissionBase("a.admit_at", "m.record_type = 'FIRST_PROGRESS'", """
                        case when r.first_at is null and exists (
                                 select 1 from inp_medical_record m2
                                 where m2.admission_id = a.id and m2.record_type = 'PROGRESS')
                             then 'FIRST_PROGRESS_UNTYPED' else null end""")));

        m.put("INP_ROUND_ATTENDING_48H", new AnchorSpec(
                "inp_admission.admit_at",
                "min(inp_medical_record.created_at) where record_type='ROUND' and round_level='ATTENDING'",
                "a.admit_at", "a.id = ?",
                admissionBase("a.admit_at", "m.record_type = 'ROUND' and m.round_level = 'ATTENDING'", """
                        case when r.first_at is null and exists (
                                 select 1 from inp_medical_record m2
                                 where m2.admission_id = a.id and m2.record_type = 'ROUND'
                                   and m2.round_level is null)
                             then 'ROUND_LEVEL_BLANK' else null end""")));

        m.put("INP_DISCHARGE_24H", new AnchorSpec(
                "inp_admission.discharged_at",
                "min(inp_medical_record.created_at) where record_type='DISCHARGE'",
                "a.discharged_at", "a.id = ?",
                admissionBase("a.discharged_at", "m.record_type = 'DISCHARGE'", "null::text")));

        // 会诊主体：责任科室取 to_dept_id（被邀科室）——会诊超时的责任方是被邀方，不是申请方。
        // 责任医师取 consultant_id（会诊医师）；未指派时为 null，如实返 null，不猜成申请医师。
        m.put("INP_CONSULT_ROUTINE_48H", new AnchorSpec(
                "inp_consult.created_at", "inp_consult.done_at",
                "c.created_at", "c.admission_id = ?", """
                select 'CONSULT'::text as subject_kind,
                       c.id as subject_id, a.admission_no as subject_no, c.admission_id,
                       a.patient_id, p.name as patient_name,
                       c.to_dept_id as dept_id, d.name as dept_name, a.ward_id, w.name as ward_name,
                       c.consultant_id as duty_doctor_id, cu.real_name as duty_doctor_name,
                       c.consultant_id as written_by, cu.real_name as written_by_name,
                       c.created_at as start_at, c.done_at as end_at,
                       null::text as caveat
                from inp_consult c
                join inp_admission a on a.id = c.admission_id
                left join empi_patient p on p.id = a.patient_id
                left join sys_dept d on d.id = c.to_dept_id
                left join sys_dept w on w.id = a.ward_id
                left join sys_user cu on cu.id = c.consultant_id
                """));

        return Collections.unmodifiableMap(m);
    }

    /**
     * 锚点守卫：库里声明的锚点与代码里执行的锚点必须逐字一致。
     *
     * <p>{@code emr_timeliness_rule.start_anchor} 是可写的配置数据。若有人改了它而代码没改，
     * <b>报表会声称按 A 判、实际按 B 判</b>——那正是「算得出但证明不了」。
     * 本方法一旦发现不一致就返回一段说明，调用方据此把规则降级为不可执行：
     * <b>不给 rows，也不按其中任何一个蒙着算。</b>
     *
     * @return null 表示一致；非 null 为不一致说明
     */
    private static String anchorMismatch(String code, String dbStart, String dbEnd) {
        AnchorSpec s = ANCHORS.get(code);
        if (s == null) return null;
        var diff = new ArrayList<String>(2);
        if (!java.util.Objects.equals(norm(s.declaredStart()), norm(dbStart))) {
            diff.add("起点：库里写的是「" + dbStart + "」，代码里执行的是「" + s.declaredStart() + "」");
        }
        if (!java.util.Objects.equals(norm(s.declaredEnd()), norm(dbEnd))) {
            diff.add("终点：库里写的是「" + dbEnd + "」，代码里执行的是「" + s.declaredEnd() + "」");
        }
        if (diff.isEmpty()) return null;
        return "规则表声明的时间锚点与本服务实际执行的锚点不一致，本规则已降级为不可执行（不给清单、不给统计）。"
                + String.join("；", diff)
                + "。口径说明与执行口径必须同源，否则报表说的锚点不是它真正用的锚点——"
                + "请改回锚点声明，或由实现车道同步修改 EmrTimelinessService.ANCHORS。";
    }

    private static String norm(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").trim();
    }

    /** 全部规则（按 scope、rule_code 稳定排序）。 */
    public List<Rule> rules() {
        var out = new ArrayList<Rule>();
        for (var row : jdbc.queryForList("select * from emr_timeliness_rule order by scope, rule_code")) {
            out.add(toRule(row));
        }
        return out;
    }

    /** 单条规则；不存在返 null。 */
    public Rule rule(String code) {
        var rows = jdbc.queryForList("select * from emr_timeliness_rule where rule_code = ?", code);
        return rows.isEmpty() ? null : toRule(rows.get(0));
    }

    private Rule toRule(Map<String, Object> r) {
        String code = (String) r.get("rule_code");
        int fallback = intOf(r.get("limit_minutes"));
        String configKey = (String) r.get("config_key");
        String configUnit = (String) r.get("config_unit");
        var warnings = new ArrayList<String>();

        int effective = fallback;
        String limitSource = "RULE_FALLBACK";
        String raw = null;
        if (configKey != null && !configKey.isBlank()) {
            raw = configReader.get(configKey, null);
            Integer conv = convertToMinutes(raw, configUnit);
            if (conv == null) {
                // 键缺失/写成 'abc'/单位不认识：回落规则表的 limit_minutes 并**说出来**。
                // 静默回落等于让一个笔误把全院的法定时限改了，而报表上看不出任何异常。
                warnings.add("阈值配置键 " + configKey + " 的取值「" + raw + "」无法按单位 " + configUnit
                        + " 解析为分钟，本次回落规则表的 limit_minutes=" + fallback + "。");
            } else if (conv <= 0 || conv > MAX_LIMIT_MINUTES) {
                warnings.add("阈值配置键 " + configKey + " 解析得到 " + conv + " 分钟，超出合理域（1–"
                        + MAX_LIMIT_MINUTES + "），本次回落规则表的 limit_minutes=" + fallback + "。");
            } else {
                effective = conv;
                limitSource = "CONFIG_KEY";
            }
        }

        boolean available = Boolean.TRUE.equals(r.get("available"));
        String startAnchor = (String) r.get("start_anchor");
        String endAnchor = (String) r.get("end_anchor");
        String mismatch = anchorMismatch(code, startAnchor, endAnchor);
        boolean hasSpec = ANCHORS.containsKey(code);
        boolean executable = available && hasSpec && mismatch == null;
        String anchorNote = null;
        if (mismatch != null) {
            anchorNote = mismatch;
            warnings.add(code + "：" + mismatch);
        } else if (available && !hasSpec) {
            // available=true 但代码里没有实现——这是实现缺口，必须显性化，不能装作算过了
            anchorNote = "规则表标记 available=true，但本服务的可执行锚点表里没有 " + code
                    + " 的实现，本规则不给清单也不给统计。这是实现缺口，不是数据缺口。";
            warnings.add(code + "：" + anchorNote);
        }
        if (!Boolean.TRUE.equals(r.get("source_verified"))) {
            warnings.add(UNVERIFIED_WARNING_PREFIX + code + "（" + r.get("rule_name") + "）的阈值 "
                    + effective + " 分钟与条文号「" + r.get("source_article")
                    + "」尚未由病案科取原文核对，不得用于对外考核或举证。");
        }

        Object eff = r.get("source_effective_from");
        LocalDate from = eff == null ? null : ((java.sql.Date) eff).toLocalDate();

        return new Rule(
                longOf(r.get("id")), code, (String) r.get("rule_name"), (String) r.get("scope"),
                startAnchor, (String) r.get("start_anchor_cn"), endAnchor, (String) r.get("end_anchor_cn"),
                fallback, configKey, configUnit, intOf(r.get("warn_ratio_pct")),
                (String) r.get("source_doc"), (String) r.get("source_article"), from,
                Boolean.TRUE.equals(r.get("source_verified")),
                available, (String) r.get("unavailable_reason"), (String) r.get("missing_fields"),
                Boolean.TRUE.equals(r.get("enabled")), (String) r.get("remark"),
                effective, limitSource, raw, executable, anchorNote, List.copyOf(warnings));
    }

    private static Integer convertToMinutes(String raw, String unit) {
        if (raw == null || unit == null) return null;
        long v;
        try {
            v = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        long minutes = switch (unit) {
            case "MINUTES" -> v;
            case "HOURS" -> v * 60;
            case "DAYS" -> v * 1440;
            default -> Long.MIN_VALUE;
        };
        if (minutes == Long.MIN_VALUE) return null;
        return minutes > Integer.MAX_VALUE || minutes < Integer.MIN_VALUE ? null : (int) minutes;
    }

    // ==================================================================
    // 二、判定 SQL（汇总与穿透明细同源）
    // ==================================================================

    /**
     * 判定 CTE。<b>清单、统计、gate 三处共用这一段</b>——三套 SQL 各判各的，
     * 迟早会出现「汇总说 3 条超时、点开明细只有 2 行」（v49 麻醉质控栽过这一跤）。
     *
     * <p>五种结论：
     * <ul>
     *   <li>{@code ON_TIME} 已写且在时限内</li>
     *   <li>{@code LATE} 已写但超时</li>
     *   <li>{@code MISSING_OVERDUE} 未写且已过时限</li>
     *   <li>{@code PENDING} 未写但尚未到时限 —— <b>不算及时也不算超时，不进分母</b></li>
     *   <li>{@code ANOMALY_NEGATIVE} 终点早于起点 —— 数据异常，<b>不进分子也不进分母</b></li>
     * </ul>
     */
    private static final String JUDGE = """
            select b.*,
                   (b.start_at + (p.limit_min::double precision * interval '1 minute')) as due_at,
                   (b.start_at + ((p.limit_min * p.warn_pct / 100.0)::double precision * interval '1 minute')) as warn_at,
                   round(extract(epoch from (coalesce(b.end_at, p.now_at) - b.start_at)) / 60.0)::int as elapsed_minutes,
                   case
                     when b.end_at is not null and b.end_at < b.start_at then 'ANOMALY_NEGATIVE'
                     when b.end_at is not null
                          and b.end_at <= b.start_at + (p.limit_min::double precision * interval '1 minute')
                          then 'ON_TIME'
                     when b.end_at is not null then 'LATE'
                     when p.now_at > b.start_at + (p.limit_min::double precision * interval '1 minute')
                          then 'MISSING_OVERDUE'
                     else 'PENDING'
                   end as status,
                   (b.end_at is null
                      and p.now_at <= b.start_at + (p.limit_min::double precision * interval '1 minute')
                      and p.now_at >= b.start_at
                          + ((p.limit_min * p.warn_pct / 100.0)::double precision * interval '1 minute')) as warning
            from base b cross join params p
            """;

    private static final String DETAIL_COLS = """
            subject_kind, subject_id, subject_no, admission_id, patient_id, patient_name,
            dept_id, dept_name, ward_id, ward_name,
            duty_doctor_id, duty_doctor_name, written_by, written_by_name,
            start_at, end_at, due_at, warn_at, elapsed_minutes, status, warning, caveat
            """;

    /** 一段带参数的 SQL 片段。 */
    private record Frag(String sql, List<Object> args) {}

    /**
     * 组装 {@code with params, base, judged}。
     *
     * <p>绑定参数按<b>文本出现顺序</b>入列：params 三个在前，base 的窗口/住院号在后。
     *
     * @param admissionId 非 null 时按单次住院取数（gate 用），为 null 时按日期窗口取数
     */
    private Frag judgedCte(Rule rule, LocalDate from, LocalDate to, Long admissionId, Timestamp now) {
        AnchorSpec s = ANCHORS.get(rule.code());
        var args = new ArrayList<Object>();
        args.add(now);
        args.add(rule.limitMinutes());
        args.add(rule.warnRatioPct());

        // 起点为空的主体一律排除：起点是 null 时上面五个分支会全部落到 PENDING，
        // 那是把「没有锚点」伪装成「还没到点」。没有起点就是判不了，不是还没超时。
        String where = "where " + s.startColumn() + " is not null and ";
        if (admissionId != null) {
            where += s.admissionPredicate();
            args.add(admissionId);
        } else {
            where += "(" + s.startColumn() + " >= ?::date and " + s.startColumn() + " < (?::date + 1))";
            args.add(from.toString());
            args.add(to.toString());
        }

        String sql = "with params as (select ?::timestamptz as now_at, ?::int as limit_min, ?::int as warn_pct),\n"
                + "base as (\n" + s.baseSelect() + where + "\n),\n"
                + "judged as (\n" + JUDGE + ")\n";
        return new Frag(sql, args);
    }

    /** 清单/统计通用筛选。返回追加到 where 的片段（首字符为空格，恒以 {@code and} 起头）。 */
    private static Frag filters(Long deptId, Long doctorId, Long wardId, String status, boolean warningOnly) {
        var sb = new StringBuilder();
        var args = new ArrayList<Object>();
        if (deptId != null) {
            sb.append(" and dept_id = ?");
            args.add(deptId);
        }
        if (doctorId != null) {
            sb.append(" and duty_doctor_id = ?");
            args.add(doctorId);
        }
        if (wardId != null) {
            sb.append(" and ward_id = ?");
            args.add(wardId);
        }
        if (status != null) {
            sb.append(" and status = ?");
            args.add(status);
        }
        if (warningOnly) {
            sb.append(" and warning");
        }
        return new Frag(sb.toString(), args);
    }

    // ==================================================================
    // 三、超时清单
    // ==================================================================

    /**
     * 超时清单。<b>不传 {@code ruleCode} 时跨全部「在用」规则合并</b>
     * （在用 = available 且 enabled 且锚点可执行）。
     *
     * <p>跨规则合并的分页是<b>先各取 {@value #MERGE_CAP} 条、合并排序、再切页</b>：
     * 五条规则的 SQL 主体不同（住院 / 会诊），没有一条 SQL 能一次排完序。
     * 任一规则取满上限时返回体里 {@code truncated=true} 并点名是哪几条——
     * <b>不静默截断</b>，截断后的清单拿去盘点会漏掉真实的超时病历。
     * 需要完整清单时按 {@code rule=} 逐条取，那条路径没有合并、没有截断。
     *
     * @param overdueOnly true 只要 LATE + MISSING_OVERDUE；false 返回全部结论（含 PENDING）
     */
    public Result overdue(String ruleCode, String from, String to, Long deptId, Long doctorId, Long wardId,
                          String status, boolean overdueOnly, Integer limit, Integer offset) {
        var w = window(from, to);
        if (w.err() != null) return w.err();
        var pg = paging(limit, offset);
        if (pg.err() != null) return pg.err();
        var f = validateFilters(deptId, doctorId, wardId, status);
        if (f != null) return f;

        var sel = selectRules(ruleCode);
        if (sel.err() != null) return sel.err();

        Timestamp now = nowTs();
        var items = new ArrayList<Map<String, Object>>();
        var truncatedRules = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        int perRuleCap = sel.rules().size() == 1 ? pg.limit() + pg.offset() : MERGE_CAP;

        for (Rule r : sel.rules()) {
            var cte = judgedCte(r, w.from(), w.to(), null, now);
            var flt = filters(deptId, doctorId, wardId, status, false);
            var args = new ArrayList<>(cte.args());
            args.addAll(flt.args());
            String sql = cte.sql() + "select " + DETAIL_COLS + " from judged where 1=1" + flt.sql()
                    + (overdueOnly ? " and status in ('" + ST_LATE + "','" + ST_MISSING_OVERDUE + "')" : "")
                    + " order by due_at, subject_id limit ?";
            args.add(perRuleCap + 1);
            var rows = jdbc.queryForList(sql, args.toArray());
            if (rows.size() > perRuleCap) {
                rows = rows.subList(0, perRuleCap);
                truncatedRules.add(r.code());
            }
            for (var row : rows) items.add(decorate(row, r));
            warnings.addAll(r.warnings());
        }
        // 合并排序：最早该写完的排最前（同 due_at 按规则码+主体 id 稳定排序，翻页不跳行）
        items.sort((x, y) -> {
            int c = compareTs(x.get("dueAt"), y.get("dueAt"));
            if (c != 0) return c;
            c = String.valueOf(x.get("ruleCode")).compareTo(String.valueOf(y.get("ruleCode")));
            return c != 0 ? c : Long.compare(longOf(x.get("subjectId")), longOf(y.get("subjectId")));
        });
        int total = items.size();
        int fromIdx = Math.min(pg.offset(), total);
        int toIdx = Math.min(fromIdx + pg.limit(), total);

        var body = baseBody(w, sel, now);
        body.put("overdueOnly", overdueOnly);
        body.put("limit", pg.limit());
        body.put("offset", pg.offset());
        body.put("mergedAcrossRules", sel.rules().size() > 1);
        body.put("truncated", !truncatedRules.isEmpty());
        body.put("truncatedRules", truncatedRules);
        if (!truncatedRules.isEmpty()) {
            warnings.add("以下规则本次取数达到跨规则合并上限 " + MERGE_CAP + " 条，清单不完整，"
                    + "盘点存量请按 rule= 逐条查询（该路径不合并、不截断）：" + String.join("、", truncatedRules));
        }
        body.put("matchedInPage", toIdx - fromIdx);
        body.put("mergedCandidateCount", total);
        body.put("items", new ArrayList<>(items.subList(fromIdx, toIdx)));
        body.put("statusMeaning", statusMeaning());
        body.put("warnings", dedup(warnings));
        return ok(body);
    }

    /**
     * 即将超时提醒：<b>尚未书写、尚未到时限、但已用时长达到 {@code warn_ratio_pct}%</b> 的主体。
     *
     * <p>只提醒还来得及的——已经超时的属于超时清单，那是另一件事（提醒一件已经发生的事没有意义）。
     */
    public Result upcoming(String ruleCode, Long deptId, Long doctorId, Long wardId, Integer limit) {
        var pg = paging(limit, 0);
        if (pg.err() != null) return pg.err();
        var f = validateFilters(deptId, doctorId, wardId, null);
        if (f != null) return f;
        var sel = selectRules(ruleCode);
        if (sel.err() != null) return sel.err();

        Timestamp now = nowTs();
        // 提醒面向「此刻在写的病历」，窗口取最近 MAX_WINDOW_DAYS 天的起点即可覆盖任何法定时限
        LocalDate to = BusinessDates.today();
        LocalDate from = to.minusDays(MAX_WINDOW_DAYS - 1L);
        var items = new ArrayList<Map<String, Object>>();
        var warnings = new ArrayList<String>();

        for (Rule r : sel.rules()) {
            var cte = judgedCte(r, from, to, null, now);
            var flt = filters(deptId, doctorId, wardId, null, true);
            var args = new ArrayList<>(cte.args());
            args.addAll(flt.args());
            String sql = cte.sql() + "select " + DETAIL_COLS + " from judged where 1=1" + flt.sql()
                    + " order by due_at, subject_id limit ?";
            args.add(MERGE_CAP);
            for (var row : jdbc.queryForList(sql, args.toArray())) items.add(decorate(row, r));
            warnings.addAll(r.warnings());
        }
        items.sort((x, y) -> compareTs(x.get("dueAt"), y.get("dueAt")));
        int total = items.size();
        var page = new ArrayList<>(items.subList(0, Math.min(pg.limit(), total)));

        var body = new LinkedHashMap<String, Object>();
        body.put("now", now.toInstant());
        body.put("scanFrom", from.toString());
        body.put("scanTo", to.toString());
        body.put("gate", gate());
        body.put("rulesInPlay", sel.rules().stream().map(Rule::code).toList());
        body.put("skippedRules", sel.skipped());
        body.put("candidateCount", total);
        body.put("items", page);
        body.put("note", "即将超时 = 尚未书写、尚未到时限、已用时长已达该规则 warn_ratio_pct%。"
                + "已经超时的不在此列（见 /overdue）——提醒一件已经发生的事没有意义。");
        body.put("warnings", dedup(warnings));
        return ok(body);
    }

    /** 给一行明细补上规则维度与中文提示。SQL 里不拼这些长文案，一行一份纯浪费。 */
    private Map<String, Object> decorate(Map<String, Object> row, Rule r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("ruleCode", r.code());
        m.put("ruleName", r.name());
        m.put("limitMinutes", r.limitMinutes());
        m.put("subjectKind", row.get("subject_kind"));
        m.put("subjectId", row.get("subject_id"));
        m.put("subjectNo", row.get("subject_no"));
        m.put("admissionId", row.get("admission_id"));
        m.put("patientId", row.get("patient_id"));
        m.put("patientName", row.get("patient_name"));
        m.put("deptId", row.get("dept_id"));
        m.put("deptName", row.get("dept_name"));
        m.put("wardId", row.get("ward_id"));
        m.put("wardName", row.get("ward_name"));
        m.put("dutyDoctorId", row.get("duty_doctor_id"));
        m.put("dutyDoctorName", row.get("duty_doctor_name"));
        m.put("writtenBy", row.get("written_by"));
        m.put("writtenByName", row.get("written_by_name"));
        m.put("startAt", row.get("start_at"));
        m.put("endAt", row.get("end_at"));
        m.put("dueAt", row.get("due_at"));
        m.put("warnAt", row.get("warn_at"));
        m.put("elapsedMinutes", row.get("elapsed_minutes"));
        m.put("overMinutes", overMinutes(row));
        m.put("status", row.get("status"));
        m.put("warning", row.get("warning"));
        String caveat = (String) row.get("caveat");
        m.put("caveat", caveat);
        m.put("caveatText", caveat == null ? null : CAVEATS.get(caveat));
        return m;
    }

    /** 超时分钟数：仅对 LATE / MISSING_OVERDUE 有意义，其余返 null（不返 0，0 会被读成「刚好卡点」）。 */
    private static Integer overMinutes(Map<String, Object> row) {
        String st = (String) row.get("status");
        if (!ST_LATE.equals(st) && !ST_MISSING_OVERDUE.equals(st)) return null;
        Object e = row.get("elapsed_minutes");
        return e == null ? null : ((Number) e).intValue();
    }

    // ==================================================================
    // 四、超时率统计
    // ==================================================================

    /**
     * 超时率统计：按科室 / 责任医师 / 病区分组，或整体（{@code by=none}）。
     *
     * <p><b>责任医师取主管医师 {@code inp_admission.doctor_id}，不取书写人。</b>
     * 「未写」的病历根本没有书写人，取书写人会让所有未写的病历归不到任何人头上——
     * 于是最该被看见的那一类恰好从科室报表里消失。明细里另给 {@code writtenBy}（已写的那条是谁写的），
     * 两者不同时可据此追责到具体书写人。会诊那条的责任医师是 {@code consultant_id}（会诊医师），
     * 未指派时为 null，如实返 null，不猜成申请医师。
     *
     * @param by dept / doctor / ward / none
     */
    public Result indicators(String ruleCode, String from, String to, String by,
                             Long deptId, Long doctorId, Long wardId) {
        var w = window(from, to);
        if (w.err() != null) return w.err();
        var f = validateFilters(deptId, doctorId, wardId, null);
        if (f != null) return f;
        String group = by == null || by.isBlank() ? "dept" : by.trim().toLowerCase(Locale.ROOT);
        if (!List.of("dept", "doctor", "ward", "none").contains(group)) {
            return err(5742, "分组维度非法：" + by + "（合法值 dept / doctor / ward / none）");
        }
        var sel = selectRulesForReport(ruleCode);
        if (sel.err() != null) return sel.err();

        Timestamp now = nowTs();
        var warnings = new ArrayList<String>();
        var list = new ArrayList<Map<String, Object>>();
        for (Rule r : sel.rules()) {
            list.add(indicatorBody(r, w, group, deptId, doctorId, wardId, now, warnings));
        }

        var body = baseBody(w, sel, now);
        body.put("by", group);
        body.put("coverage", globalCoverage(w));
        body.put("statusMeaning", statusMeaning());
        body.put("rateNote", RATE_NOTE);
        body.put("indicators", list);
        body.put("warnings", dedup(warnings));
        return ok(body);
    }

    private static final String RATE_NOTE =
            "超时率分母 = ON_TIME + LATE + MISSING_OVERDUE（可判定病例），"
            + "**不含 PENDING（未到时限，结论未定）与 ANOMALY_NEGATIVE（终点早于起点的数据异常）**。"
            + "分母为 0 时超时率返 null 而不是 0——「0% 超时」与「没有可判定的病例」是两句完全不同的话。";

    private Map<String, Object> indicatorBody(Rule r, Win w, String group,
                                              Long deptId, Long doctorId, Long wardId,
                                              Timestamp now, List<String> warnings) {
        var m = ruleHeader(r);
        warnings.addAll(r.warnings());
        if (!r.available()) {
            // 刻意不放 rows / summary / coverage：缺锚点的规则不给空数组也不给 0，
            // 免得前端画出一张「全 0」的表被当成「本院无超时」。
            m.put("missingFields", splitLines(r.missingFields()));
            m.put("note", "缺时间锚点，本平台算不出这条指标。不给 rows、不给 0、不给近似值——"
                    + "一个看着像真的 0 会让人把「统计不出来」读成「没有超时」。");
            return m;
        }
        if (!r.executable()) {
            m.put("note", r.anchorNote());
            return m;
        }
        if (!r.enabled()) {
            m.put("note", "该规则当前停用，不进统计。停用理由见 remark。"
                    + "确认数据条件具备后经 PUT /api/emr-timeliness/rules/" + r.code() + " 启用（须填变更原因，落留痕）。");
            return m;
        }

        var cte = judgedCte(r, w.from(), w.to(), null, now);
        var flt = filters(deptId, doctorId, wardId, null, false);
        String groupCol = switch (group) {
            case "doctor" -> "duty_doctor_id";
            case "ward" -> "ward_id";
            case "dept" -> "dept_id";
            default -> null;
        };
        String nameCol = switch (group) {
            case "doctor" -> "duty_doctor_name";
            case "ward" -> "ward_name";
            case "dept" -> "dept_name";
            default -> null;
        };

        String counts = """
                count(*) as total,
                count(*) filter (where status = 'ON_TIME')          as on_time,
                count(*) filter (where status = 'LATE')             as late,
                count(*) filter (where status = 'MISSING_OVERDUE')  as missing_overdue,
                count(*) filter (where status = 'PENDING')          as pending,
                count(*) filter (where status = 'ANOMALY_NEGATIVE') as anomaly_negative,
                count(*) filter (where warning)                     as warning,
                count(*) filter (where end_at is not null)          as with_end_anchor,
                count(*) filter (where caveat is not null)          as caveat_rows
                """;

        var args = new ArrayList<>(cte.args());
        args.addAll(flt.args());
        var summaryRow = jdbc.queryForList(
                cte.sql() + "select " + counts + " from judged where 1=1" + flt.sql(), args.toArray()).get(0);
        m.put("summary", summarize(summaryRow, null, null));

        if (groupCol != null) {
            var rargs = new ArrayList<>(cte.args());
            rargs.addAll(flt.args());
            var rows = jdbc.queryForList(
                    cte.sql() + "select " + groupCol + " as group_id, max(" + nameCol + ") as group_name, "
                            + counts + " from judged where 1=1" + flt.sql()
                            + " group by " + groupCol + " order by " + groupCol + " nulls last",
                    rargs.toArray());
            var out = new ArrayList<Map<String, Object>>(rows.size());
            for (var row : rows) {
                out.add(summarize(row, row.get("group_id"), (String) row.get("group_name")));
            }
            m.put("rows", out);
            m.put("groupBy", group);
            if ("doctor".equals(group)) {
                m.put("groupNote", "责任医师取主管医师（会诊那条取会诊医师）。未写的病历没有书写人，"
                        + "按书写人分组会让未写的病历归不到人头上——最该被看见的那一类恰好会消失。"
                        + "group_id 为 null 的一行是「主管医师未登记」，不是某个人。");
            }
        }
        m.put("coverage", ruleCoverage(r, summaryRow, w));
        m.put("detailEndpoint", "/api/emr-timeliness/overdue?rule=" + r.code()
                + "&from=" + w.from() + "&to=" + w.to());
        m.put("caliberNote", r.remark());
        return m;
    }

    /** 汇总一行：计数原样带出，比率在 Java 侧由同一批计数算出——汇总与穿透不可能对不上账。 */
    private Map<String, Object> summarize(Map<String, Object> row, Object groupId, String groupName) {
        long onTime = longOf(row.get("on_time"));
        long late = longOf(row.get("late"));
        long missing = longOf(row.get("missing_overdue"));
        long judgeable = onTime + late + missing;
        long overtime = late + missing;

        var m = new LinkedHashMap<String, Object>();
        if (groupId != null || groupName != null) {
            m.put("groupId", groupId);
            m.put("groupName", groupName);
        }
        m.put("total", longOf(row.get("total")));
        m.put("onTime", onTime);
        m.put("late", late);
        m.put("missingOverdue", missing);
        m.put("pending", longOf(row.get("pending")));
        m.put("anomalyNegative", longOf(row.get("anomaly_negative")));
        m.put("warning", longOf(row.get("warning")));
        m.put("judgeable", judgeable);
        m.put("overtime", overtime);
        m.put("overtimeRatePct", ratePct(overtime, judgeable));
        m.put("onTimeRatePct", ratePct(onTime, judgeable));
        if (judgeable == 0) {
            m.put("rateNote", "本组没有可判定的病例（分母为 0），超时率返 null 而不是 0。");
        }
        return m;
    }

    private static BigDecimal ratePct(long numerator, long denominator) {
        if (denominator <= 0) return null;
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    // ==================================================================
    // 五、覆盖率（先看这一段再看指标值）
    // ==================================================================

    /** 单条规则的锚点覆盖：这条指标算的是多少主体、其中多少有终点锚点、多少真的可判定。 */
    private Map<String, Object> ruleCoverage(Rule r, Map<String, Object> s, Win w) {
        long total = longOf(s.get("total"));
        long withEnd = longOf(s.get("with_end_anchor"));
        long judgeable = longOf(s.get("on_time")) + longOf(s.get("late")) + longOf(s.get("missing_overdue"));
        var m = new LinkedHashMap<String, Object>();
        m.put("startAnchor", r.startAnchor());
        m.put("startAnchorCn", r.startAnchorCn());
        m.put("endAnchor", r.endAnchor());
        m.put("endAnchorCn", r.endAnchorCn());
        m.put("subjectsInWindow", total);
        m.put("withStartAnchor", total);   // 起点为空的主体在 base 的 where 里已排除，见 judgedCte
        m.put("withEndAnchor", withEnd);
        m.put("endAnchorFillRatePct", ratePct(withEnd, total));
        m.put("judgeable", judgeable);
        m.put("judgeableRatePct", ratePct(judgeable, total));
        m.put("undetermined", longOf(s.get("pending")));
        m.put("anomalyNegative", longOf(s.get("anomaly_negative")));
        long caveatRows = longOf(s.get("caveat_rows"));
        m.put("caveatRows", caveatRows);
        if (caveatRows > 0) {
            m.put("caveatNote", "本窗口有 " + caveatRows + " 条主体带口径提示（见明细的 caveatText），"
                    + "这些行的判定可能是误报，不宜直接用于考核个人。");
        }
        if ("INP_DISCHARGE_24H".equals(r.code())) {
            // 起点为空的这一批在 base 里已被排除，但它们是真实存在的数据质量问题，
            // 不单列出来就等于让它们从报表上彻底消失。
            Long orphan = jdbc.queryForObject("""
                    select count(*) from inp_admission
                    where status = 'DISCHARGED' and discharged_at is null
                    """, Long.class);
            m.put("dischargedStatusWithoutTimestamp", orphan);
            m.put("dischargedStatusNote", "全库有 " + orphan + " 次住院 status='DISCHARGED' 但 discharged_at 为空。"
                    + "这些住院<b>没有起点锚点</b>，不进本指标的任何一档（既不是及时也不是超时），"
                    + "本版不拿别的时刻硬凑起点。这是数据质量问题，须由病案科单独清理。");
        }
        m.put("note", "先看这一段再看指标值：endAnchorFillRatePct 很低时，"
                + "超时率只代表已书写部分，不代表全院。judgeable 是真正进分母的条数。");
        return m;
    }

    /** 全窗口锚点覆盖：多少病历有可用的时间锚点。历史病历没有可靠的书写时刻，那样算出来的超时率是假的。 */
    private Map<String, Object> globalCoverage(Win w) {
        var m = new LinkedHashMap<String, Object>();
        String f = w.from().toString();
        String t = w.to().toString();

        var adm = jdbc.queryForList("""
                select count(*) as admissions,
                       count(admit_at) as with_admit_at,
                       count(doctor_id) as with_duty_doctor
                from inp_admission
                where admit_at >= ?::date and admit_at < (?::date + 1)
                """, f, t).get(0);
        m.putAll(adm);

        var dis = jdbc.queryForList("""
                select count(*) as discharged_in_window
                from inp_admission
                where discharged_at >= ?::date and discharged_at < (?::date + 1)
                """, f, t).get(0);
        m.putAll(dis);
        m.put("dischargedStatusWithoutTimestamp", jdbc.queryForObject(
                "select count(*) from inp_admission where status = 'DISCHARGED' and discharged_at is null",
                Long.class));

        var rec = jdbc.queryForList("""
                select count(*) as records,
                       count(*) filter (where record_type = 'ADMISSION')      as admission_records,
                       count(*) filter (where record_type = 'FIRST_PROGRESS') as first_progress_typed,
                       count(*) filter (where record_type = 'PROGRESS')       as progress_records,
                       count(*) filter (where record_type = 'DISCHARGE')      as discharge_records,
                       count(*) filter (where record_type = 'ROUND')          as round_records,
                       count(*) filter (where record_type = 'ROUND' and round_level is not null)
                                                                              as round_level_typed,
                       count(doctor_id)                                       as with_writer
                from inp_medical_record
                where created_at >= ?::date and created_at < (?::date + 1)
                """, f, t).get(0);
        m.putAll(rec);

        var con = jdbc.queryForList("""
                select count(*) as consults, count(done_at) as consults_with_done_at
                from inp_consult
                where created_at >= ?::date and created_at < (?::date + 1)
                """, f, t).get(0);
        m.putAll(con);

        m.put("note", "本段是**时间锚点的可得性**，不是指标值。"
                + "first_progress_typed 为 0 而 progress_records 不为 0，意味着首次病程混写在 PROGRESS 里、"
                + "在库里与普通病程无法区分，INP_FIRST_PROGRESS_8H 在这批住院上判不准（明细的 caveat 会逐条标出）。"
                + "round_level_typed 远小于 round_records 时，主治查房时限判不出来——"
                + "这正是 INP_ROUND_ATTENDING_48H 默认停用的原因。"
                + "with_writer 是有书写人的病历数：书写人为空的病历无法追责到人，本版不回填、不猜。");
        return m;
    }

    // ==================================================================
    // 六、锚点体检：把「不可用原因」从断言变成可复核的事实
    // ==================================================================

    /**
     * 逐规则报告：<b>锚点在库里到底取哪个字段、那个字段存不存在、全表填充率多少。</b>
     *
     * <p>V159 种子里每条 {@code available=false} 都写了原因（具体到表名列名与代码行号），
     * 但那是<b>写迁移的人的断言</b>。本端点用 {@code information_schema} 与 {@code count(col)}
     * 把断言变成机器可复核的事实：{@code inp_admission.death_at} 到底存不存在，一查便知。
     *
     * <p><b>刻意不由列的存在与否反推结论。</b>多条规则的起点列是存在的（例如
     * {@code inp_surgery.end_at}、{@code er_rescue_record.rescue_end}），它们不可用的原因在<b>终点</b>
     * 或在缺急/常规标识——由「列存在」推出「规则可用」会得到相反的结论。
     * 因此本端点只给列的事实，规则级结论一律照抄 {@code unavailable_reason} 原文。
     *
     * <p>填充率查询是 {@code count(*)/count(col)} 的<b>全表</b>顺序扫描，非热路径：
     * 这是给病案科/实施做锚点体检用的，不进日常报表。表很大时请错峰调用。
     */
    public Result anchors() {
        var out = new ArrayList<Map<String, Object>>();
        for (Rule r : rules()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("ruleCode", r.code());
            m.put("ruleName", r.name());
            m.put("scope", r.scope());
            m.put("available", r.available());
            m.put("enabled", r.enabled());
            m.put("executable", r.executable());
            m.put("declaredStartAnchor", r.startAnchor());
            m.put("declaredStartAnchorCn", r.startAnchorCn());
            m.put("declaredEndAnchor", r.endAnchor());
            m.put("declaredEndAnchorCn", r.endAnchorCn());
            m.put("startColumn", columnFacts(r.startAnchor()));
            m.put("endColumn", r.endAnchor() == null
                    ? Map.of("declared", false,
                             "note", "该规则未声明终点锚点——这正是它算不出来的原因。")
                    : columnFacts(r.endAnchor()));
            AnchorSpec s = ANCHORS.get(r.code());
            m.put("executedStartAnchor", s == null ? null : s.declaredStart());
            m.put("executedEndAnchor", s == null ? null : s.declaredEnd());
            m.put("anchorNote", r.anchorNote());
            m.put("unavailableReason", r.unavailableReason());
            m.put("missingFields", splitLines(r.missingFields()));
            out.add(m);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("rules", out);
        body.put("note", "startColumn / endColumn 两段是**列级事实**（列在不在、有没有值），"
                + "不是规则级结论：多条规则的起点列是存在的，它们算不出来是因为缺终点锚点或缺急/常规标识。"
                + "规则级结论请读 unavailableReason 原文。"
                + "executedStartAnchor / executedEndAnchor 是本服务**实际执行**的锚点；"
                + "与 declared 不一致时 anchorNote 会说明，且该规则已被降级为不可执行。");
        body.put("fillRateNote", "填充率是全表 count(*)/count(col) 顺序扫描，非热路径，表大时请错峰调用。");
        return ok(body);
    }

    /**
     * 一个锚点表达式对应的列级事实。
     * 表达式可能是 {@code inp_admission.admit_at}，也可能是
     * {@code min(inp_medical_record.created_at) where record_type='ADMISSION'}——
     * 一律取其中第一个 {@code 表.列}，那就是时刻的真正来源列。
     */
    private Map<String, Object> columnFacts(String anchorExpr) {
        var m = new LinkedHashMap<String, Object>();
        m.put("declared", true);
        m.put("expression", anchorExpr);
        var mt = anchorExpr == null ? null : TABLE_COL.matcher(anchorExpr);
        if (mt == null || !mt.find()) {
            m.put("resolved", false);
            m.put("note", "锚点表达式里解析不出「表.列」，无法做列级体检。");
            return m;
        }
        String table = mt.group(1);
        String column = mt.group(2);
        m.put("resolved", true);
        m.put("table", table);
        m.put("column", column);
        if (!IDENT.matcher(table).matches() || !IDENT.matcher(column).matches()) {
            m.put("exists", false);
            m.put("note", "表名或列名不是合法标识符，拒绝拼进查询。");
            return m;
        }
        Integer n = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = current_schema() and table_name = ? and column_name = ?
                """, Integer.class, table, column);
        boolean exists = n != null && n > 0;
        m.put("exists", exists);
        if (!exists) {
            m.put("note", "该列在本平台的数据库里**不存在**——这是可复核的事实，不是断言。"
                    + "以它为锚点的规则算不出来，任何「近似替代」都是在编造一个从未采集过的时刻。");
            return m;
        }
        try {
            // 标识符已过 information_schema 存在性校验 + IDENT 白名单，此处拼接安全
            var row = jdbc.queryForList(
                    "select count(*) as total, count(%s) as filled from %s".formatted(column, table)).get(0);
            long total = longOf(row.get("total"));
            long filled = longOf(row.get("filled"));
            m.put("rowCount", total);
            m.put("filled", filled);
            m.put("fillRatePct", ratePct(filled, total));
            if (total > 0 && filled == 0) {
                m.put("note", "该列存在但**全表为空**：以它为锚点的指标目前一条也判不出来，"
                        + "算出来的任何比率都不代表现实。");
            }
        } catch (Exception e) {
            m.put("note", "填充率查询失败：" + e.getClass().getSimpleName());
        }
        return m;
    }

    // ==================================================================
    // 七、gate 判定（纯只读，不 throw；挂点由主控裁决）
    // ==================================================================

    /** 一条时限缺项。结构与 {@link CountersignService.Finding} 对齐，便于主控并到同一个挡点。 */
    public record Finding(String code, String ruleCode, Long subjectId, String text) {}

    /**
     * 某次住院的时限缺项（空 = 无缺项）。<b>纯只读、无副作用、不 throw。</b>
     *
     * <p><b>任何档位下都照算</b>：off 档若跳过计算，返回体里的「时限合格」就会在超时时谎报 true。
     * gate 只决定「拿这个事实怎么办」，不决定「事实是什么」（v48 病理签发的同一课）。
     *
     * <p>只把 {@code LATE} 与 {@code MISSING_OVERDUE} 算缺项：{@code PENDING} 是还没到点，
     * {@code ANOMALY_NEGATIVE} 是数据异常——两者都不该在出院时把人挡住。
     */
    public List<Finding> findings(Long admissionId) {
        var out = new ArrayList<Finding>();
        if (admissionId == null) return out;
        Timestamp now = nowTs();
        for (Rule r : rules()) {
            if (!r.inPlay()) continue;
            var cte = judgedCte(r, null, null, admissionId, now);
            var rows = jdbc.queryForList(
                    cte.sql() + "select subject_id, subject_no, status, elapsed_minutes, caveat "
                            + "from judged where status in ('" + ST_LATE + "','" + ST_MISSING_OVERDUE + "') "
                            + "order by subject_id",
                    cte.args().toArray());
            for (var row : rows) {
                String st = (String) row.get("status");
                Long sid = longObj(row.get("subject_id"));
                int elapsed = intOf(row.get("elapsed_minutes"));
                String caveat = (String) row.get("caveat");
                String tail = caveat == null ? "" : "（口径提示：" + CAVEATS.get(caveat) + "）";
                if (ST_LATE.equals(st)) {
                    out.add(new Finding(CODE_TIMELINESS_LATE, r.code(), sid,
                            "《" + r.name() + "》书写超时：法定时限 " + r.limitMinutes()
                                    + " 分钟，实际用时 " + elapsed + " 分钟" + tail));
                } else {
                    out.add(new Finding(CODE_TIMELINESS_MISSING, r.code(), sid,
                            "《" + r.name() + "》已超过法定时限 " + r.limitMinutes()
                                    + " 分钟仍未书写（已过 " + elapsed + " 分钟）" + tail));
                }
            }
        }
        return out;
    }

    /**
     * 出院/归档挡点用的裁决。<b>本车道不把它挂到任何既有写路径上</b>——
     * {@code InpatientService.discharge} 与病案归档是核心写路径、多套 E2E 钉着，
     * 挂哪一个、挂在哪一行由主控裁决（已写进 cross_lane）。这里只把判定备好，一行即可接入。
     */
    public record Verdict(String gate, boolean blocked, int code, String message,
                          List<Finding> findings, List<String> warnings) {}

    public Verdict verdict(Long admissionId) {
        var found = findings(admissionId);
        String gate = gate();
        if (found.isEmpty()) {
            return new Verdict(gate, false, 0, "success", found, List.of());
        }
        String detail = found.size() <= 3
                ? String.join("；", found.stream().map(Finding::text).toList())
                : found.get(0).text() + " 等 " + found.size() + " 条";
        if ("block".equals(gate)) {
            return new Verdict(gate, true, 5749,
                    "未达法定病历书写时限，不能出院/归档：" + detail + "（gate " + GATE_KEY + "=block）",
                    found, List.of());
        }
        if ("off".equals(gate)) {
            return new Verdict(gate, false, 0, "success", found, List.of());
        }
        return new Verdict(gate, false, 0, "success", found,
                List.of("存在病历书写时限缺项即放行：" + detail + "（gate=warn）"));
    }

    // ==================================================================
    // 八、规则维护（阈值可配置 + 每一次变更都留痕）
    // ==================================================================

    /** 可维护字段白名单。<b>锚点、出处、可用性不在其中</b>——见 {@link #update} 的说明。 */
    private static final List<String> EDITABLE =
            List.of("limitMinutes", "warnRatioPct", "enabled", "sourceVerified", "remark");

    public record UpdateRequest(Integer limitMinutes, Integer warnRatioPct, Boolean enabled,
                                Boolean sourceVerified, String remark, String reason) {}

    /**
     * 修改一条规则，<b>每个变更字段落一行 {@code emr_timeliness_rule_log}</b>。
     *
     * <p><b>为什么原因必填（5747）</b>：法定时限阈值被改过而系统说不清改成了什么、谁改的、为什么改，
     * 等于历史超时率永远对不上账——「上个月超时率 3%，为什么现在重算是 11%？」。
     *
     * <p><b>为什么不开放锚点与出处的修改</b>：
     * {@code start_anchor / end_anchor} 是<b>给人读的口径说明</b>，与代码里可执行的锚点必须同源
     * （见 {@link #anchorMismatch}）；从接口改掉说明而代码不动，就会造出「报表说按 A 判、实际按 B 判」，
     * 那正是本版要消灭的东西。{@code source_doc / source_article / source_effective_from} 同理——
     * 换了出处等于换了一条不同的法定依据，应当走迁移新增一条规则，而不是就地改掉旧的。
     * {@code available} 更不开放：可用性由平台有没有那个字段决定，不由人声明。
     *
     * <p><b>config_key 非空时的警告</b>：那类规则的生效阈值来自 sys_config 键，
     * 本接口改的是<b>回落值</b>，不改变生效阈值。不说清楚，改完发现报表没动会以为接口坏了。
     */
    @Transactional
    public Result update(String code, UpdateRequest req, Long uid) {
        if (req == null) return err(5746, "请求体不能为空（可维护字段：" + String.join("/", EDITABLE) + "）");
        Rule r = rule(code);
        if (r == null) return err(5740, "规则码不存在：" + code + "（可用规则码见 GET /api/emr-timeliness/catalog）");
        if (uid == null) {
            return err(5748, "无法识别当前登录用户，不能修改法定时限阈值"
                    + "（改了谁改的都记不下来的留痕，在举证时没有价值）");
        }
        String reason = req.reason() == null ? null : req.reason().trim();
        if (reason == null || reason.isEmpty()) {
            return err(5747, "变更原因必填：改法定时限没有原因，事后就说不清当时为什么是这个值");
        }
        if (reason.length() > 500) return err(5746, "变更原因超长（最多 500 字，收到 " + reason.length() + "）");

        var sets = new ArrayList<String>();
        var args = new ArrayList<Object>();
        var logs = new ArrayList<Object[]>();
        var warnings = new ArrayList<String>();

        if (req.limitMinutes() != null && req.limitMinutes() != r.fallbackLimitMinutes()) {
            int v = req.limitMinutes();
            if (v <= 0 || v > MAX_LIMIT_MINUTES) {
                return err(5746, "时限非法：" + v + " 分钟（合理域 1–" + MAX_LIMIT_MINUTES + "）");
            }
            sets.add("limit_minutes = ?");
            args.add(v);
            logs.add(new Object[]{"limit_minutes", String.valueOf(r.fallbackLimitMinutes()), String.valueOf(v)});
            if (r.configKey() != null && "CONFIG_KEY".equals(r.limitSource())) {
                warnings.add("本规则的生效阈值来自 sys_config 键 " + r.configKey()
                        + "（当前生效 " + r.limitMinutes() + " 分钟）。本次改的是规则表里的**回落值**，"
                        + "不改变生效阈值；要改生效阈值请改那个键（PUT /api/config/" + r.configKey() + "）。"
                        + "同一个阈值只有一处可改，是为了让两张报表不会给出两个超时率。");
            }
        }
        if (req.warnRatioPct() != null && req.warnRatioPct() != r.warnRatioPct()) {
            int v = req.warnRatioPct();
            if (v < 1 || v > 99) return err(5746, "即将超时触发点非法：" + v + "%（合理域 1–99）");
            sets.add("warn_ratio_pct = ?");
            args.add(v);
            logs.add(new Object[]{"warn_ratio_pct", String.valueOf(r.warnRatioPct()), String.valueOf(v)});
        }
        if (req.enabled() != null && req.enabled() != r.enabled()) {
            if (req.enabled() && !r.available()) {
                // 先判再写：让 CHECK 约束抛出来会把整个事务置为 aborted，
                // 调用方随后的任何一条 SQL 都会连坐报错（照抄 CountersignService 的同一条纪律）。
                return err(5744, "规则 " + code + " 标记为不可用（缺时间锚点），不能启用。"
                        + "算不出来的规则启用了也只会在清单里出现一条永远空的规则，纯误导。原因："
                        + r.unavailableReason());
            }
            sets.add("enabled = ?");
            args.add(req.enabled());
            logs.add(new Object[]{"enabled", String.valueOf(r.enabled()), String.valueOf(req.enabled())});
        }
        if (req.sourceVerified() != null && req.sourceVerified() != r.sourceVerified()) {
            sets.add("source_verified = ?");
            args.add(req.sourceVerified());
            if (req.sourceVerified()) {
                sets.add("verified_by = ?");
                args.add(uid);
                sets.add("verified_at = ?");
                args.add(nowTs());
            } else {
                sets.add("verified_by = null");
                sets.add("verified_at = null");
            }
            logs.add(new Object[]{"source_verified", String.valueOf(r.sourceVerified()),
                    String.valueOf(req.sourceVerified())});
        }
        if (req.remark() != null && !req.remark().equals(r.remark())) {
            if (req.remark().length() > 2000) {
                return err(5746, "备注超长（最多 2000 字，收到 " + req.remark().length() + "）");
            }
            sets.add("remark = ?");
            args.add(req.remark());
            logs.add(new Object[]{"remark", trunc(r.remark()), trunc(req.remark())});
        }

        if (sets.isEmpty()) {
            return err(5746, "没有任何字段发生变化（可维护字段：" + String.join("/", EDITABLE)
                    + "；锚点、出处、可用性不开放修改，理由见接口文档）");
        }
        sets.add("updated_at = ?");
        args.add(nowTs());
        args.add(code);
        jdbc.update("update emr_timeliness_rule set " + String.join(", ", sets) + " where rule_code = ?",
                args.toArray());
        for (Object[] lg : logs) {
            jdbc.update("""
                    insert into emr_timeliness_rule_log
                        (rule_code, field, old_value, new_value, reason, changed_by, changed_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, code, lg[0], lg[1], lg[2], reason, uid, nowTs());
        }

        configReader.evict(GATE_KEY);
        Rule after = rule(code);
        var body = new LinkedHashMap<String, Object>();
        body.put("ruleCode", code);
        body.put("changedFields", logs.stream().map(l -> (String) l[0]).toList());
        body.put("reason", reason);
        body.put("changedBy", uid);
        body.put("rule", ruleBody(after, true));
        body.put("warnings", dedup(concat(warnings, after.warnings())));
        return ok(body);
    }

    /** 一条规则的变更留痕（新到旧）。 */
    public List<Map<String, Object>> changeLog(String code) {
        return jdbc.queryForList("""
                select l.id, l.rule_code, l.field, l.old_value, l.new_value, l.reason,
                       l.changed_by, u.real_name as changed_by_name, l.changed_at
                from emr_timeliness_rule_log l
                left join sys_user u on u.id = l.changed_by
                where l.rule_code = ?
                order by l.id desc
                """, code);
    }

    // ==================================================================
    // 九、目录与配置
    // ==================================================================

    /** 规则目录：编码 → 名称 / 可用性 / 生效阈值 / 出处（未核对的条文号带后缀）。 */
    public Result catalog() {
        var rules = rules();
        var out = new ArrayList<Map<String, Object>>(rules.size());
        var warnings = new ArrayList<String>();
        for (Rule r : rules) {
            out.add(ruleBody(r, false));
            warnings.addAll(r.warnings());
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("gate", gate());
        body.put("gateKey", GATE_KEY);
        body.put("rules", out);
        body.put("unverifiedCount", rules.stream().filter(x -> !x.sourceVerified()).count());
        body.put("unavailableCount", rules.stream().filter(x -> !x.available()).count());
        body.put("inPlayCount", rules.stream().filter(Rule::inPlay).count());
        body.put("warnings", dedup(warnings));
        return ok(body);
    }

    /** 单条规则 + 它的变更留痕。 */
    public Result ruleDetail(String code) {
        Rule r = rule(code);
        if (r == null) return err(5740, "规则码不存在：" + code + "（可用规则码见 GET /api/emr-timeliness/catalog）");
        var body = new LinkedHashMap<String, Object>();
        body.put("rule", ruleBody(r, true));
        body.put("changeLog", changeLog(code));
        body.put("editableFields", EDITABLE);
        body.put("editableNote", "锚点（start_anchor / end_anchor）、出处（source_doc / source_article /"
                + " source_effective_from）与可用性（available）不开放修改：口径说明必须与代码里可执行的锚点同源，"
                + "换出处等于换了一条法定依据（应新增规则而不是就地改），可用性由平台有没有那个字段决定、不由人声明。");
        body.put("warnings", dedup(r.warnings()));
        return ok(body);
    }

    /** 当前生效配置（前端提示文案与表头按它渲染，避免把默认值写死在前端第二份）。 */
    public Result config() {
        var body = new LinkedHashMap<String, Object>();
        body.put("gate", gate());
        body.put("gateKey", GATE_KEY);
        boolean registered = gateKeyRegistered();
        body.put("gateKeyRegistered", registered);
        if (!registered) body.put("gateKeyNote", GATE_KEY_NOTE);
        body.put("maxWindowDays", MAX_WINDOW_DAYS);
        body.put("defaultWindowDays", DEFAULT_WINDOW_DAYS);
        body.put("maxLimit", MAX_LIMIT);
        body.put("statusMeaning", statusMeaning());
        body.put("rateNote", RATE_NOTE);
        body.put("unverifiedSuffix", UNVERIFIED_SUFFIX);
        body.put("notes", List.of(
                "只回看不回滚、只增不改：本车道不提供任何能修改历史判定结果的端点。",
                "available=false 的规则不返回 rows、不返回 0——统计不出来与没有超时是两件事。",
                "source_verified=false 的规则，条文号在 catalog 与 indicators 两处都带后缀「"
                        + UNVERIFIED_SUFFIX + "」，未确认前不得用于对外考核或举证。",
                "责任医师取主管医师而不是书写人：未写的病历没有书写人，按书写人分组会让它们从报表上消失。"));
        return ok(body);
    }

    private Map<String, Object> ruleHeader(Rule r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("code", r.code());
        m.put("name", r.name());
        m.put("scope", r.scope());
        m.put("available", r.available());
        m.put("enabled", r.enabled());
        m.put("executable", r.executable());
        m.put("limitMinutes", r.limitMinutes());
        m.put("warnRatioPct", r.warnRatioPct());
        m.put("startAnchor", r.startAnchor());
        m.put("startAnchorCn", r.startAnchorCn());
        m.put("endAnchor", r.endAnchor());
        m.put("endAnchorCn", r.endAnchorCn());
        m.put("sourceDoc", r.sourceDoc());
        m.put("sourceArticle", r.sourceArticleDisplay());
        m.put("sourceArticleRaw", r.sourceArticle());
        m.put("sourceEffectiveFrom", r.sourceEffectiveFrom() == null ? null : r.sourceEffectiveFrom().toString());
        m.put("sourceVerified", r.sourceVerified());
        if (!r.sourceVerified()) {
            m.put("sourceVerifiedNote", "本规则的阈值与条文号是按现行规范填写的**默认值，未经病案科取原文核对**，"
                    + "不得用于对外考核或举证。核对后经 PUT /api/emr-timeliness/rules/" + r.code()
                    + " 置 sourceVerified=true（须填原因，落变更留痕）。");
        }
        if (!r.available()) m.put("unavailableReason", r.unavailableReason());
        return m;
    }

    private Map<String, Object> ruleBody(Rule r, boolean full) {
        var m = ruleHeader(r);
        m.put("limitSource", r.limitSource());
        m.put("configKey", r.configKey());
        m.put("configUnit", r.configUnit());
        m.put("fallbackLimitMinutes", r.fallbackLimitMinutes());
        if (r.configKey() != null) {
            m.put("configRawValue", r.configRawValue());
            m.put("limitSourceNote", "本规则的生效阈值以 sys_config 键 " + r.configKey()
                    + " 为准，规则表的 limit_minutes(" + r.fallbackLimitMinutes() + ") 只是回落值。"
                    + "同一个阈值只有一处可改，改了两张报表一起动。");
        }
        if (full) {
            m.put("missingFields", splitLines(r.missingFields()));
            m.put("remark", r.remark());
            m.put("anchorNote", r.anchorNote());
        }
        return m;
    }

    private static Map<String, String> statusMeaning() {
        var m = new LinkedHashMap<String, String>();
        m.put(ST_ON_TIME, "已书写且在法定时限内");
        m.put(ST_LATE, "已书写但超过法定时限");
        m.put(ST_MISSING_OVERDUE, "已过法定时限仍未书写");
        m.put(ST_PENDING, "尚未书写、也尚未到时限——**结论未定，不算及时也不算超时，不进分母**");
        m.put(ST_ANOMALY_NEGATIVE, "终点时刻早于起点时刻的数据异常——**不进分子也不进分母**。"
                + "负数时长在及时率里会被当成「极其及时」，那是把数据缺陷伪装成优异指标");
        return m;
    }

    // ==================================================================
    // 十、入参校验与小工具
    // ==================================================================

    private record Win(LocalDate from, LocalDate to, Result err) {}

    /** 日期窗口。缺省 = 业务今天前推 {@value #DEFAULT_WINDOW_DAYS} 天。时区一律走 {@code BusinessDates}。 */
    private Win window(String from, String to) {
        LocalDate t;
        LocalDate f;
        try {
            t = to == null || to.isBlank() ? BusinessDates.today() : LocalDate.parse(to.trim());
        } catch (DateTimeParseException e) {
            return new Win(null, null, err(5741, "截止日期格式非法（须 YYYY-MM-DD）：" + to));
        }
        try {
            f = from == null || from.isBlank() ? t.minusDays(DEFAULT_WINDOW_DAYS - 1L) : LocalDate.parse(from.trim());
        } catch (DateTimeParseException e) {
            return new Win(null, null, err(5741, "起始日期格式非法（须 YYYY-MM-DD）：" + from));
        }
        if (f.isAfter(t)) {
            return new Win(null, null, err(5741, "起始日期晚于截止日期：" + f + " > " + t));
        }
        long days = ChronoUnit.DAYS.between(f, t) + 1;
        if (days > MAX_WINDOW_DAYS) {
            return new Win(null, null, err(5741,
                    "查询窗口 " + days + " 天超过上限 " + MAX_WINDOW_DAYS + " 天，请分段查询"));
        }
        return new Win(f, t, null);
    }

    private record Page(int limit, int offset, Result err) {}

    private static Page paging(Integer limit, Integer offset) {
        if (limit != null && (limit <= 0 || limit > MAX_LIMIT)) {
            return new Page(0, 0, err(5743, "limit 非法（1–" + MAX_LIMIT + "）：" + limit));
        }
        if (offset != null && offset < 0) {
            return new Page(0, 0, err(5743, "offset 非法（须 >= 0）：" + offset));
        }
        return new Page(limit == null ? DEFAULT_LIMIT : limit, offset == null ? 0 : offset, null);
    }

    private static Result validateFilters(Long deptId, Long doctorId, Long wardId, String status) {
        if (deptId != null && deptId <= 0) return err(5742, "科室 id 非法：" + deptId);
        if (doctorId != null && doctorId <= 0) return err(5742, "医师 id 非法：" + doctorId);
        if (wardId != null && wardId <= 0) return err(5742, "病区 id 非法：" + wardId);
        if (status != null && !ALL_STATUS.contains(status)) {
            return err(5742, "结论码非法：" + status + "（合法值 " + String.join("/", ALL_STATUS) + "）");
        }
        return null;
    }

    /** 一次查询选中的规则集合 + 被跳过的规则及原因（<b>跳过必须说明，不静默丢弃</b>）。 */
    private record Selection(List<Rule> rules, List<Map<String, Object>> skipped, Result err) {}

    /** 清单类端点：只取真正能算的规则；显式点名某条不能算的规则时报错而不是返回空清单。 */
    private Selection selectRules(String ruleCode) {
        if (ruleCode != null && !ruleCode.isBlank()) {
            Rule r = rule(ruleCode.trim());
            if (r == null) {
                return new Selection(null, null, err(5740,
                        "规则码不存在：" + ruleCode + "（可用规则码见 GET /api/emr-timeliness/catalog）"));
            }
            if (!r.available()) {
                return new Selection(null, null, err(5744,
                        "规则 " + r.code() + "（" + r.name() + "）缺时间锚点，本平台算不出来，"
                                + "不提供清单也不提供统计——不给 0、不给近似值。原因：" + r.unavailableReason()));
            }
            if (!r.executable()) {
                return new Selection(null, null, err(5744, r.anchorNote()));
            }
            if (!r.enabled()) {
                return new Selection(null, null, err(5745,
                        "规则 " + r.code() + "（" + r.name() + "）当前停用，不进统计。停用理由：" + r.remark()));
            }
            return new Selection(List.of(r), List.of(), null);
        }
        var in = new ArrayList<Rule>();
        var skipped = new ArrayList<Map<String, Object>>();
        for (Rule r : rules()) {
            if (r.inPlay()) {
                in.add(r);
            } else {
                skipped.add(skipEntry(r));
            }
        }
        return new Selection(in, skipped, null);
    }

    /** 统计类端点：全部规则都要出现在返回体里（不可用的那几条带原因、不带数字）。 */
    private Selection selectRulesForReport(String ruleCode) {
        if (ruleCode != null && !ruleCode.isBlank()) {
            Rule r = rule(ruleCode.trim());
            if (r == null) {
                return new Selection(null, null, err(5740,
                        "规则码不存在：" + ruleCode + "（可用规则码见 GET /api/emr-timeliness/catalog）"));
            }
            return new Selection(List.of(r), List.of(), null);
        }
        var all = rules();
        var skipped = new ArrayList<Map<String, Object>>();
        for (Rule r : all) if (!r.inPlay()) skipped.add(skipEntry(r));
        return new Selection(all, skipped, null);
    }

    private static Map<String, Object> skipEntry(Rule r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("code", r.code());
        m.put("name", r.name());
        m.put("available", r.available());
        m.put("enabled", r.enabled());
        m.put("executable", r.executable());
        m.put("reason", !r.available() ? r.unavailableReason()
                : !r.executable() ? r.anchorNote()
                : "规则当前停用：" + r.remark());
        return m;
    }

    private LinkedHashMap<String, Object> baseBody(Win w, Selection sel, Timestamp now) {
        var body = new LinkedHashMap<String, Object>();
        body.put("from", w.from().toString());
        body.put("to", w.to().toString());
        body.put("days", ChronoUnit.DAYS.between(w.from(), w.to()) + 1);
        body.put("now", now.toInstant());
        body.put("gate", gate());
        body.put("rulesInPlay", sel.rules().stream().filter(Rule::inPlay).map(Rule::code).toList());
        body.put("skippedRules", sel.skipped());
        return body;
    }

    private static List<String> splitLines(String s) {
        if (s == null || s.isBlank()) return List.of();
        var out = new ArrayList<String>();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static List<String> dedup(List<String> in) {
        var seen = new java.util.LinkedHashSet<String>(in);
        return List.copyOf(seen);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String trunc(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    private static int compareTs(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return ((Comparable<Object>) a).compareTo(b);
    }

    private static long longOf(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static Long longObj(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
