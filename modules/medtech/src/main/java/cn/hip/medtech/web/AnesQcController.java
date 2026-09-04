package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v46 车道M：麻醉与手术质控指标（技术偏离表 1421★–1439★、1444★–1450★）。
 *
 * <p>按《麻醉专业医疗质量控制指标（2022 年版）》口径出指标。<b>纯只读统计层</b>：本控制器
 * 不建表、不加列、不写任何一行数据——地基字段由车道 K（{@code inp_surgery} 加列，V140）、
 * 术中记录三表由车道 L（{@code surg_tube} / {@code surg_transfusion} / {@code surg_event}，V141）交付。
 *
 * <h2>三处同源的诚实标注（端点 javadoc / 返回体 caveat / 页面 alert）</h2>
 * <ul>
 *   <li><b>{@value #TIMEPOINT_CAVEAT}</b>
 *   <li><b>{@value #ANCHOR_NOTE}</b>
 *   <li><b>{@value #STANDARD_NOTE}</b>
 * </ul>
 *
 * <h2>缺数据源的指标一律 {@code available=false}，不返回「看起来像真的」的 0</h2>
 * <ul>
 *   <li><b>1435★ 术中出血量分档</b>——全仓无出血量字段。{@code surg_transfusion} 记的是输血量；
 *       {@code inp_icu_record.output_ml} 与 {@code inp_vital_sign.output_ml} 是出入量的「出量」
 *       （尿量等），都不是术中出血量。<b>输血量与出血量是两回事</b>，拿前者冒充后者会让管理者
 *       按错误的失血分布做备血决策，故本指标不给近似值。
 *   <li><b>1444★ 毒麻药品开具统计 / 1445★ 肌松药开具统计</b>——{@code md_drug} 与
 *       {@code DrugItem} 全仓无精麻毒放属性位（现有属性只有 abx_level 抗菌药分级、drug_class
 *       西药/中成药、antibiotic、self_pay、ddd_per_unit、fee_category_code、enabled 一组），
 *       全仓 grep「毒麻 / 麻醉药品 / 精神药品 / 肌松 / narcotic / psychotropic」<b>零命中</b>。
 *       按药名关键词硬猜（「含吗啡即毒麻」）是危险的假实现——管制药品台账要对得上药监与卫健
 *       两条线的账，猜错一味药就是一条假账。
 * </ul>
 *
 * <p><b>1439★ 的部分兑现</b>：{@value #ICD9_NOTE}
 *
 * <h2>两处「一写错就悄无声息漏统计」的口径，已按车道 L 的建表契约钉死</h2>
 * <ul>
 *   <li><b>自体血只认 {@code is_auto} 布尔位</b>：{@value #AUTO_FLAG_NOTE}
 *   <li><b>{@code planned} 三态一律 {@code is true} / {@code is false} / {@code is null} 显式分组</b>：
 *       写成 {@code not planned} 会把 null 行整片吞掉，而被吞掉的恰好是「非计划转入 ICU」
 *       「非计划再插管」这类最该被看见的部分。
 * </ul>
 *
 * <p><b>1424★ 两套口径并排给、不二选一</b>：{@code on_time_rate_pct} 是全部首台，
 * {@code elective_on_time_rate_pct} 是《2022 版》国标口径（只算择期首台，见 V140 对
 * {@code surgery_kind} 的说明）。只给国标口径会让 {@code surgery_kind} 未录的历史手术整片消失，
 * 只给全量口径又与国标对不上——两套并排 + 覆盖率，才是能对账的形态。
 *
 * <p><b>1422★1423★「穿透查看取值明细 + 导出 EXCEL」不是可选项</b>，它是防「指标算得出但对不上账」
 * 的关键：每个 available 的指标都有 {@code GET /detail?indicator=} 逐条明细与 {@code .csv} 导出，
 * 且明细与汇总<b>同一时间窗、同一锚点、同一过滤条件</b>（共用 {@link #ANCHOR} / {@link #WINDOW}
 * / {@link #NOT_CANCELLED} 三个片段常量，改口径只有一处可改）。
 *
 * <p>错误码段 4940–4959（见 docs/错误码分段.md）：4940 统计时间段非法（起止倒置或跨度超限）、
 * 4941 指标编码不存在、4942 穿透明细条数超限。
 *
 * <p>配置：{@code anes.qc.ontime_minutes}（默认 {@value #DEFAULT_ONTIME_MINUTES} 分钟）——
 * 首台准点阈值，走 sys_config 不硬编码，已登记 docs/配置手册.md。
 */
/*
 * 权限与菜单对齐（v42 在 MedTechController 上吃过一次「菜单给了、接口 1005」的亏）：
 * V142 把菜单 112「麻醉质控」授给 ADMIN 与 QUALITY 两个角色，此处逐字对齐。
 * **刻意不放宽到 DOCTOR_OUTP / NURSE**：穿透明细逐条带住院号与患者姓名，
 * 是全院范围的患者级数据，没有科室边界；管理指标看板不该成为一条绕开专页限权的取数通道。
 * 若院方要让运营侧也看，须同时改 V142 的授权与本注解——两处一起改，不留单边缺口。
 */
@RestController
@RequestMapping("/api/anes-qc")
@PreAuthorize("hasAnyRole('ADMIN','QUALITY')")
@RequiredArgsConstructor
public class AnesQcController {

    // ===================== 口径常量（返回体、CSV 尾注与前端 alert 三处同源） =====================

    static final String TIMEPOINT_CAVEAT =
            "口径覆盖面：本版之前录入的历史手术行，手术间 / 入室·开台·结束·出室四时间点 / 手术级别 / "
            + "ASA 分级 / 切口等级 / 手术类别 / 取消阶段全部为空且刻意不回填（严禁伪造历史）。"
            + "因此「首台准点率」「接台时长」「跨日手术」「手术级别分布」「ASA 与死亡关联」等依赖新字段的指标，"
            + "只覆盖本版之后真正录了相应字段的手术，不是全院全历史口径。"
            + "每个指标各自给出可判定分母，本体 coverage 段给出该时段的字段录入覆盖率——请先看覆盖率再看指标值。";

    static final String ANCHOR_NOTE =
            "统计锚点日期 = coalesce(开台时间, 入室时间, 排台时间, 建单时间)：历史手术无时间点时回落到"
            + "排台/建单时间，保证历史手术不被漏统计；但这类行的「手术当日」是排台日而非真实开台日。";

    static final String STANDARD_NOTE =
            "指标口径按《麻醉专业医疗质量控制指标（2022 年版）》硬编码。本版不提供指标定义可配置编辑器"
            + "（1421★ 的「上线前结合医院实际调整指标口径」属实施期工作，已在 v46 计划中列为范围外，此处不留假入口）。";

    static final String DEATH_NOTE =
            "死亡判定取 mr_death_card（1028 死亡登记卡）按 admission_id 关联：本仓 inp_admission "
            + "无「离院方式」列（V8 建表与其后全部 alter 实证），也无其他住院死亡标志位，"
            + "故未登记死亡卡的死亡病例在此判不出来——死亡率会偏低，不能当作上报口径。";

    static final String ICD9_NOTE =
            "本仓无 ICD-9-CM-3 手术操作字典表（只有 md_icd10 诊断字典；dg_term 虽把 ICD9CM3 列进值域注释，"
            + "却零条 PROC 类种子），inp_surgery.op_icd 是无外键约束的自填文本。故手术名称按 procedure_name "
            + "自由文本 + op_icd 自填码归集，不是以 ICD-9 字典为标准，同一术式的不同写法会分成两行。";

    /**
     * 自体血判定口径。V141 建表注释与 CHECK 约束把这条钉死了，统计层必须照办：
     * {@code product_type='AUTO' → is_auto} 成立，<b>反向不成立</b>——「自体洗涤红细胞」
     * 按 {@code product_type='RBC' + is_auto=true} 录入。按字符串比 {@code product_type='AUTO'}
     * 会把这类整片漏掉，且漏得悄无声息。
     */
    static final String AUTO_FLAG_NOTE =
            "自体血一律以 surg_transfusion.is_auto 布尔位判定，不按 product_type='AUTO' 字符串比对："
            + "自体洗涤红细胞是按 product_type='RBC' + is_auto=true 录的，字符串比对会整片漏统计"
            + "（V141 的 CHECK 只保证 AUTO 制品必为自体血，反向不成立）。";

    static final String VOLUME_BAND_NOTE =
            "输注量分档沿用参数对 1435★ 明文给出的三档（400ml 以下 / 400–1000ml / 1000ml 以上），"
            + "本平台不另立档位。";

    static final String TURNOVER_NOTE =
            "接台时长 = 同一手术间、同一自然日内相邻两台的「上一台出室 → 下一台入室」间隔；"
            + "跨自然日的相邻两台不计接台，入室早于上一台出室（重叠或录错）的也不计入——不做负值累加。";

    static final String ADVERSE_NOTE =
            "qc_adverse_event 是全院不良事件登记表，无 surgery_id 关联列，无法筛出「麻醉与手术相关」子集，"
            + "故此处是全院口径而非手术麻醉专项口径，不能直接当作麻醉不良事件上报数。"
            + "另：本指标按 occurred_on 发生日期落窗，不走上面那条手术锚点日期——它压根不挂在手术上。";

    /** 首台准点阈值默认值（分钟）。真实取值走 {@code anes.qc.ontime_minutes}，见 docs/配置手册.md */
    static final int DEFAULT_ONTIME_MINUTES = 30;

    /** 统计时间窗最大跨度（天）：与 StatsController.daily 的 366 同量级，防一个参数打出上亿行 */
    static final int MAX_SPAN_DAYS = 366;

    /** 穿透明细硬上限（照抄 mr-workqueue / v43 医嘱检索纪律：限量 + truncated 标记，不做翻页） */
    static final int DETAIL_LIMIT = 200;

    // ===================== SQL 片段（占位符替换，不做字符串拼接） =====================
    //
    // 刻意用 {占位符} + replace 而不是 `""" ... """ + CONST + """ ... """`：
    // 文本块会剥掉每行行尾空白，拼接处极易少一个空格拼出 `... + 1order by ...` 这种
    // 只在运行期才炸的 SQL。占位符没有拼接边界，也让每段 SQL 在源码里保持可读的完整形态。

    /**
     * 统计锚点：历史手术四时间点全空，只用 start_at 会让本版之前的手术整体消失。
     * 这一串 coalesce 是全部指标共用的日期基准，改这里等于改全部指标口径。
     */
    private static final String ANCHOR = "coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at)";

    /** 统计时间窗谓词（两个 ?::date 参数，闭开区间 [from, to+1)） */
    private static final String WINDOW = ANCHOR + " >= ?::date and " + ANCHOR + " < ?::date + 1";

    /** 取消的手术不计入「做了几台」类指标（取消本身由 1426★ 单独统计） */
    private static final String NOT_CANCELLED =
            "coalesce(s.status, '') <> 'CANCELLED' and s.cancel_stage is null";

    /** 年龄（岁，按统计锚点日与出生日期算） */
    private static final String AGE_YEARS =
            "extract(year from age(" + ANCHOR + "::date, p.birth_date))";

    /** 穿透明细多取 1 条判 truncated：只取 LIMIT 条会让「刚好第 200 条」漏报 */
    private static final String DETAIL_CAP = " limit " + (DETAIL_LIMIT + 1);

    /** 手术类明细的公共投影：明细要能直接核对到人、到台，否则「穿透」是空话 */
    private static final String SURG_SELECT = """
            select s.id                        as surgery_id,
                   a.id                        as admission_id,
                   a.admission_no,
                   p.name                      as patient_name,
                   d.name                      as dept_name,
                   s.procedure_name,
                   s.room_no,
                   s.scheduled_at,
                   s.in_room_at,
                   s.start_at,
                   s.end_at,
                   s.out_room_at,
                   s.surgery_level,
                   s.asa_grade,
                   s.surgery_kind,
                   s.anesthesia_type,
                   s.status
            from inp_surgery s
            join inp_admission a on a.id = s.admission_id
            join empi_patient p on p.id = a.patient_id
            left join sys_dept d on d.id = a.dept_id
            """;

    /** 占位符展开。{surg} 先展开（其内不含其它占位符），其余互不嵌套 */
    private static String q(String sql) {
        return sql.replace("{surg}", SURG_SELECT)
                .replace("{window}", WINDOW)
                .replace("{alive}", NOT_CANCELLED)
                .replace("{ageYears}", AGE_YEARS)
                .replace("{anchor}", ANCHOR)
                .replace("{cap}", DETAIL_CAP);
    }

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    // ===================== 指标注册表 =====================

    /**
     * 指标定义。{@code available=false} 的三条各自带原因，<b>不返回 rows、不返回 0</b>——
     * 返回一个看起来像真的的 0 比不返回更坏：管理者会把「本院无毒麻药开具」当成结论。
     */
    private record Def(String code, String name, boolean available, String reason) {}

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    private static void def(String code, String name) {
        DEFS.put(code, new Def(code, name, true, null));
    }

    private static void unavailable(String code, String name, String reason) {
        DEFS.put(code, new Def(code, name, false, reason));
    }

    static {
        def("1424", "每日首台量与开台准点率");
        def("1425", "手术台数、总手术时长与接台时长");
        def("1426", "取消手术四阶段构成");
        def("1427", "跨日手术");
        def("1428", "各类手术数量（择期/急诊/日间）");
        def("1429", "ASA 分级分布与死亡关联");
        def("1430", "麻醉方式分布");
        def("1431", "局麻手术按临床科室分布");
        def("1432", "术中血制品类型与总量");
        def("1433", "自体血按输注量分档统计人数");
        def("1434", "输血患者数 / 自体血患者数 / 非自体血患者数");
        unavailable("1435", "术中出血量分档（400ml 以下 / 400–1000ml / 1000ml 以上）",
                "缺数据源：全仓无「术中出血量」字段。surg_transfusion 记的是输血量（血制品类型 / 量 / 是否自体血），"
                + "inp_icu_record.output_ml 与 inp_vital_sign.output_ml 是出入量的「出量」（尿量等），两者都不是出血量。"
                + "拿输血量冒充出血量会直接误导备血与失血管理决策，故本指标不给近似值。"
                + "补法：在术中记录里新增出血量字段（属车道 L 的表结构决定；本只读统计层不建表、不加列）。");
        def("1436", "手术患者年龄段 × 性别分布");
        def("1437", "每日镇痛泵新增 / 拆泵 / 在用量");
        def("1438", "手术数量（按术式）");
        def("1439", "手术级别分布");
        unavailable("1444", "毒麻药品开具统计",
                "缺数据源：药品主数据无「精麻毒放」属性位。md_drug / DrugItem 现有属性只有 abx_level 抗菌药分级、"
                + "drug_class（W 西药 / C 中成药）、antibiotic、self_pay、ddd_per_unit、fee_category_code、enabled，"
                + "全仓 grep「毒麻 / 麻醉药品 / 精神药品 / narcotic / psychotropic」零命中。"
                + "按药名关键词硬猜（如「含吗啡即毒麻」）是危险的假实现——管制药品台账要对得上药监与卫健两条线的账。"
                + "补法：主数据加管制类别属性位并按国家目录维护后，本指标即可按处方 / 医嘱明细出。");
        unavailable("1445", "肌松药开具统计",
                "缺数据源：同 1444★，药品主数据无「肌松药」药理分类属性位，全仓 grep「肌松 / muscle_relax」零命中。"
                + "药理分类不能由药名推断（同一通用名不同剂型、复方制剂归类不同），故不猜。");
        def("1446", "计划 / 非计划转入 ICU 与苏醒室");
        def("1447", "插管 / 拔管 / 带管出室各类计数");
        def("1448", "术中有创操作");
        def("1449", "术中抢救");
        def("1450", "不良事件按名称与数量");
    }

    // ===================== 统计时间窗 =====================

    private record Window(LocalDate from, LocalDate to) {
        String f() { return from.toString(); }
        String t() { return to.toString(); }
        Object[] args() { return new Object[]{from.toString(), to.toString()}; }
        long days() { return to.toEpochDay() - from.toEpochDay() + 1; }
    }

    private record WindowOrError(Window window, R<Object> error) {}

    /** 时间窗解析与校验；非法一律 4940（起止倒置 / 跨度超限 / 日期格式不合法都属「时间段非法」） */
    private WindowOrError parseWindow(String from, String to) {
        LocalDate t;
        LocalDate f;
        try {
            t = (to == null || to.isBlank()) ? BusinessDates.today() : LocalDate.parse(to.trim());
            f = (from == null || from.isBlank()) ? t.minusDays(29) : LocalDate.parse(from.trim());
        } catch (DateTimeParseException e) {
            return new WindowOrError(null, R.fail(4940, "统计时间段非法：起止日期须为 YYYY-MM-DD"));
        }
        if (f.isAfter(t)) {
            return new WindowOrError(null, R.fail(4940, "统计时间段非法：起始日期晚于截止日期"));
        }
        if (t.toEpochDay() - f.toEpochDay() + 1 > MAX_SPAN_DAYS) {
            return new WindowOrError(null,
                    R.fail(4940, "统计时间段非法：跨度超过 " + MAX_SPAN_DAYS + " 天，请分段统计"));
        }
        return new WindowOrError(new Window(f, t), null);
    }

    private int onTimeMinutes() {
        int v = configReader.getInt("anes.qc.ontime_minutes", DEFAULT_ONTIME_MINUTES);
        // 坏配置（0 或负数）回落默认值：阈值为负会把全部首台判成延迟，比读不到配置更坏
        return v > 0 ? v : DEFAULT_ONTIME_MINUTES;
    }

    // ===================== 统一指标端点 =====================

    /**
     * 麻醉与手术质控指标总表（1421★–1439★ / 1444★–1450★），一个端点返回全部指标。
     *
     * <p><b>口径与近似说明（诚实标注，页面 alert 与 CSV 尾注三处同源）</b>：
     * <ul>
     *   <li>{@value #TIMEPOINT_CAVEAT}
     *   <li>{@value #ANCHOR_NOTE}
     *   <li>{@value #STANDARD_NOTE}
     *   <li>1435★ / 1444★ / 1445★ 三条<b>缺数据源</b>，返回 {@code available=false} 与具体原因，
     *       <b>不返回 0</b>——见类注释。
     * </ul>
     *
     * @param from      起始日期 YYYY-MM-DD（缺省 = 截止日前推 29 天）
     * @param to        截止日期 YYYY-MM-DD（缺省 = 业务今天）
     * @param indicator 指标编码；缺省返回全部。编码不存在返 4941
     */
    @GetMapping("/indicators")
    public R<Map<String, Object>> indicators(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String indicator) {
        var w = parseWindow(from, to);
        if (w.error() != null) return R.fail(w.error().getCode(), w.error().getMessage());
        List<Def> defs = select(indicator);
        if (defs == null) {
            return R.fail(4941, "指标编码不存在：" + indicator + "（可用编码见 GET /api/anes-qc/catalog）");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("from", w.window().f());
        body.put("to", w.window().t());
        body.put("days", w.window().days());
        body.put("onTimeMinutes", onTimeMinutes());
        body.put("standardNote", STANDARD_NOTE);
        body.put("anchorNote", ANCHOR_NOTE);
        body.put("timepointCaveat", TIMEPOINT_CAVEAT);
        body.put("coverage", coverage(w.window()));
        var list = new ArrayList<Map<String, Object>>(defs.size());
        for (Def d : defs) list.add(indicatorBody(d, w.window()));
        body.put("indicators", list);
        return R.ok(body);
    }

    /** 指标目录（编码 → 名称 / 是否有数据源），供前端下拉与 4941 排错 */
    @GetMapping("/catalog")
    public R<List<Map<String, Object>>> catalog() {
        var rows = new ArrayList<Map<String, Object>>(DEFS.size());
        for (Def d : DEFS.values()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("code", d.code());
            m.put("name", d.name());
            m.put("available", d.available());
            m.put("unavailableReason", d.reason());
            rows.add(m);
        }
        return R.ok(rows);
    }

    private List<Def> select(String indicator) {
        if (indicator == null || indicator.isBlank()) return new ArrayList<>(DEFS.values());
        Def d = DEFS.get(indicator.trim());
        return d == null ? null : List.of(d);
    }

    /**
     * 字段录入覆盖率：<b>先看这一段再看指标值</b>。历史手术行新字段全空，
     * 「准点开台率 100%」很可能只是「本时段仅 2 台录了时间点」。
     */
    private Map<String, Object> coverage(Window w) {
        var m = new LinkedHashMap<String, Object>(one(q("""
                select count(*)                                                                      as surgeries,
                       count(*) filter (where s.room_no is not null)                                 as with_room,
                       count(*) filter (where s.start_at is not null)                                as with_start,
                       count(*) filter (where s.start_at is not null and s.end_at is not null)       as with_start_end,
                       count(*) filter (where s.in_room_at is not null and s.out_room_at is not null) as with_room_times,
                       count(*) filter (where s.scheduled_at is not null and s.start_at is not null) as judgeable_ontime,
                       count(*) filter (where s.surgery_level is not null)                           as with_level,
                       count(*) filter (where s.asa_grade is not null)                               as with_asa,
                       count(*) filter (where s.incision_type is not null)                           as with_incision,
                       count(*) filter (where s.surgery_kind is not null)                            as with_kind,
                       count(*) filter (where s.cancel_stage is not null)                            as with_cancel_stage
                from inp_surgery s
                where {window}
                """), w.args()));
        m.put("note", "分母 surgeries 是该时段全部手术（含历史无时间点的行）；其余各列是真正录了该字段的行数。"
                + "覆盖率低时指标值只代表已录入部分，不代表全院。");
        return m;
    }

    // ===================== 单指标装配 =====================

    private Map<String, Object> indicatorBody(Def d, Window w) {
        var m = new LinkedHashMap<String, Object>();
        m.put("code", d.code());
        m.put("name", d.name());
        m.put("available", d.available());
        if (!d.available()) {
            m.put("unavailableReason", d.reason());
            // 刻意不放 rows / summary：缺数据源的指标不给空数组，免得前端画出一张「全 0」的表
            return m;
        }
        m.put("detailEndpoint",
                "/api/anes-qc/detail?indicator=" + d.code() + "&from=" + w.f() + "&to=" + w.t());
        m.put("rows", rowsOf(d.code(), w));
        Map<String, Object> summary = summaryOf(d.code(), w);
        if (summary != null) m.put("summary", summary);
        String note = noteOf(d.code());
        if (note != null) m.put("note", note);
        return m;
    }

    /** 各指标的专属口径说明（与 rows 同返回体，前端逐指标展示，不指望用户去读总说明） */
    private static String noteOf(String code) {
        return switch (code) {
            case "1424" -> "首台 = 同一手术间、同一自然日内入室（缺则开台）时间最早的一台。准点 = 开台时间不晚于"
                    + "排台时间 + 阈值（阈值走 sys_config 键 anes.qc.ontime_minutes，不硬编码）。"
                    + "准点率的分母只含「排台时间与开台时间都录了」的首台，判不了的单列 unjudgeable，不并进分母。"
                    + "另给一组 elective_* 是《2022 版》国标口径（只算择期手术首台）：两套口径并排给、不二选一——"
                    + "只给国标口径会让 surgery_kind 未录的历史手术整片消失，只给全量口径又与国标对不上。";
            case "1425" -> TURNOVER_NOTE + " 手术时长 = 结束 − 开台，只统计两点都录了的台次。";
            case "1426" -> "按 cancel_stage 四阶段（APPLY 申请 / SCHEDULE 排程 / PRE_IN 入室前 / IN_OP 术中）归集；"
                    + "状态为取消但未标阶段的单列一行，不摊进四阶段。";
            case "1427" -> "跨日 = 开台与结束不在同一自然日，分母只含两点都录了的台次。";
            case "1429" -> DEATH_NOTE;
            case "1431" -> "局麻按 anesthesia_type 含「局部麻醉 / 局麻」判定：该字段是自由文本（前端下拉给四个值但未落库约束），"
                    + "非标准写法会漏判。科室取该住院的 inp_admission.dept_id。";
            case "1432" -> AUTO_FLAG_NOTE + " 各血制品行另给 auto_records / auto_ml，是该制品里属自体血的部分。";
            case "1433" -> VOLUME_BAND_NOTE + " 人数按患者去重，同一患者多次手术的自体血量在时间窗内合并。" + AUTO_FLAG_NOTE;
            case "1434" -> "口径是「患者数」不是「台次数」：同一患者两台手术都输了血只算一人（另给 transfused_surgeries 是台次）。"
                    + "自体血患者与非自体血患者两类可重叠（同一患者可同时输过两类），both_patients 列显式给出重叠人数——"
                    + "两列相加不等于输血患者总数。" + AUTO_FLAG_NOTE;
            case "1436" -> "年龄按统计锚点日期与出生日期计算；无出生日期的归「未知」，性别取 empi_patient.sex。";
            case "1437" -> "新增 / 拆泵按事件当日计数；「在用量」是全历史累计（截至当日的 ON 减 OFF），"
                    + "不受统计时间窗左端影响；未记拆泵的镇痛泵会一直累加在在用量里。";
            case "1438" -> ICD9_NOTE;
            case "1439" -> ICD9_NOTE + " unplanned_reop 列只统计**已标记**的病例："
                    + "V140 给 is_unplanned_reop 的默认值是 false，历史行统一落 false，"
                    + "那是「未标记」而不是「已确认不是非计划再次手术」——该列偏低是必然的，不可当上报口径。";
            case "1446", "1447" -> "planned 是可空三态（true 计划 / false 非计划 / null 不适用），一律按 "
                    + "`planned is true` / `is false` / `is null` 显式分组：写成 `not planned` 会把 null 行整片吞掉，"
                    + "而被吞掉的恰好会让「非计划转入 ICU」「非计划再插管」这类重点指标少算。"
                    + "null 单列「未区分」，不默认算计划内——默认算计划内会把问题掩盖掉。";
            case "1450" -> ADVERSE_NOTE;
            default -> null;
        };
    }

    // ===================== 各指标汇总 SQL =====================

    private List<Map<String, Object>> rowsOf(String code, Window w) {
        return switch (code) {
            case "1424" -> firstCaseRows(w);
            case "1425" -> turnoverRows(w);
            case "1426" -> query("""
                    select case s.cancel_stage
                               when 'APPLY'    then '申请阶段取消'
                               when 'SCHEDULE' then '排程阶段取消'
                               when 'PRE_IN'   then '入室前取消'
                               when 'IN_OP'    then '术中取消'
                               else '已取消但未标注阶段' end                          as stage_name,
                           coalesce(s.cancel_stage, '（未标注）')                     as cancel_stage,
                           count(*)                                                   as cases,
                           count(*) filter (where coalesce(s.cancel_reason, '') <> '') as with_reason
                    from inp_surgery s
                    where (s.cancel_stage is not null or coalesce(s.status, '') = 'CANCELLED')
                      and {window}
                    group by s.cancel_stage
                    order by case coalesce(s.cancel_stage, 'Z')
                                 when 'APPLY' then 1 when 'SCHEDULE' then 2
                                 when 'PRE_IN' then 3 when 'IN_OP' then 4 else 5 end
                    """, w);
            case "1427" -> query("""
                    select {anchor}::date                                             as op_day,
                           count(*)                                                   as cross_day_cases,
                           round(avg(extract(epoch from (s.end_at - s.start_at)) / 3600)::numeric, 1) as avg_hours
                    from inp_surgery s
                    where s.start_at is not null and s.end_at is not null
                      and s.start_at::date <> s.end_at::date
                      and {alive} and {window}
                    group by 1
                    order by 1 desc
                    """, w);
            case "1428" -> query("""
                    select case s.surgery_kind
                               when 'ELECTIVE'  then '择期手术'
                               when 'EMERGENCY' then '急诊手术'
                               when 'DAY'       then '日间手术'
                               else '未填写' end                                      as kind_name,
                           coalesce(s.surgery_kind, '（未填写）')                     as surgery_kind,
                           count(*)                                                   as cases,
                           round(100.0 * count(*) / nullif(sum(count(*)) over (), 0), 2) as pct
                    from inp_surgery s
                    where {alive} and {window}
                    group by s.surgery_kind
                    order by case coalesce(s.surgery_kind, 'Z')
                                 when 'ELECTIVE' then 1 when 'EMERGENCY' then 2
                                 when 'DAY' then 3 else 4 end
                    """, w);
            case "1429" -> query("""
                    select coalesce(s.asa_grade, '（未填写）')                        as asa_grade,
                           count(*)                                                   as cases,
                           count(*) filter (where dc.admission_id is not null)         as deaths,
                           round(100.0 * count(*) filter (where dc.admission_id is not null)
                                 / nullif(count(*), 0), 2)                             as death_rate_pct
                    from inp_surgery s
                    left join (select distinct admission_id from mr_death_card
                               where admission_id is not null) dc on dc.admission_id = s.admission_id
                    where {alive} and {window}
                    group by 1
                    order by 1
                    """, w);
            case "1430" -> query("""
                    select coalesce(nullif(trim(s.anesthesia_type), ''), '（未填写）') as anesthesia_type,
                           count(*)                                                   as cases,
                           round(100.0 * count(*) / nullif(sum(count(*)) over (), 0), 2) as pct
                    from inp_surgery s
                    where {alive} and {window}
                    group by 1
                    order by cases desc, 1
                    """, w);
            case "1431" -> query("""
                    select coalesce(d.name, '（未知科室）')                           as dept_name,
                           count(*)                                                   as cases,
                           round(100.0 * count(*) / nullif(sum(count(*)) over (), 0), 2) as pct
                    from inp_surgery s
                    join inp_admission a on a.id = s.admission_id
                    left join sys_dept d on d.id = a.dept_id
                    where (s.anesthesia_type like '%局部麻醉%' or s.anesthesia_type like '%局麻%')
                      and {alive} and {window}
                    group by 1
                    order by cases desc, 1
                    """, w);
            case "1432" -> query("""
                    select case t.product_type
                               when 'RBC'    then '红细胞'
                               when 'PLASMA' then '血浆'
                               when 'PLT'    then '血小板'
                               when 'CRYO'   then '冷沉淀'
                               when 'WHOLE'  then '全血'
                               when 'AUTO'   then '自体血'
                               else t.product_type end                                 as product_name,
                           t.product_type                                              as product_type,
                           count(*)                                                    as records,
                           count(distinct s.id)                                        as surgeries,
                           coalesce(sum(t.volume_ml), 0)                               as total_ml,
                           count(*) filter (where t.is_auto)                            as auto_records,
                           coalesce(sum(t.volume_ml) filter (where t.is_auto), 0)       as auto_ml
                    from surg_transfusion t
                    join inp_surgery s on s.id = t.surgery_id
                    where {window}
                    group by t.product_type
                    order by total_ml desc, 1
                    """, w);
            case "1433" -> query("""
                    with pat as (
                        select a.patient_id, sum(t.volume_ml) as vol
                        from surg_transfusion t
                        join inp_surgery s on s.id = t.surgery_id
                        join inp_admission a on a.id = s.admission_id
                        where t.is_auto and {window}
                        group by a.patient_id
                    ), banded as (
                        select case when vol < 400 then 1 when vol <= 1000 then 2 else 3 end as band_no,
                               case when vol < 400 then '400ml 以下'
                                    when vol <= 1000 then '400–1000ml'
                                    else '1000ml 以上' end                                   as band,
                               vol
                        from pat
                    )
                    select band, count(*) as patients, coalesce(sum(vol), 0) as total_ml
                    from banded
                    group by band, band_no
                    order by band_no
                    """, w);
            case "1434" -> query("""
                    with tx as (
                        select a.patient_id,
                               bool_or(t.is_auto)       as has_auto,
                               bool_or(not t.is_auto)   as has_non_auto,
                               count(distinct s.id)     as surgeries
                        from surg_transfusion t
                        join inp_surgery s on s.id = t.surgery_id
                        join inp_admission a on a.id = s.admission_id
                        where {window}
                        group by a.patient_id
                    )
                    select count(*)                                            as transfused_patients,
                           count(*) filter (where has_auto)                    as auto_patients,
                           count(*) filter (where has_non_auto)                as non_auto_patients,
                           count(*) filter (where has_auto and has_non_auto)   as both_patients,
                           coalesce(sum(surgeries), 0)                         as transfused_surgeries
                    from tx
                    """, w);
            case "1436" -> query("""
                    with base as (
                        select case when p.birth_date is null then 9
                                    when {ageYears} < 1  then 1
                                    when {ageYears} < 18 then 2
                                    when {ageYears} < 45 then 3
                                    when {ageYears} < 60 then 4
                                    when {ageYears} < 75 then 5
                                    else 6 end                                          as band_no,
                               case p.sex when 'M' then '男' when 'F' then '女'
                                          else '未知' end                               as sex_name
                        from inp_surgery s
                        join inp_admission a on a.id = s.admission_id
                        join empi_patient p on p.id = a.patient_id
                        where {alive} and {window}
                    )
                    select case band_no when 1 then '<1 岁'    when 2 then '1–17 岁'
                                        when 3 then '18–44 岁' when 4 then '45–59 岁'
                                        when 5 then '60–74 岁' when 6 then '≥75 岁'
                                        else '未知（无出生日期）' end                   as age_band,
                           sex_name                                                     as sex,
                           count(*)                                                     as cases
                    from base
                    group by band_no, sex_name
                    order by band_no, sex_name
                    """, w);
            case "1437" -> query("""
                    select d.day::date                                                  as op_day,
                           (select count(*) from surg_event e
                             where e.event_type = 'PAIN_PUMP_ON'
                               and e.event_time >= d.day
                               and e.event_time <  d.day + interval '1 day')             as added,
                           (select count(*) from surg_event e
                             where e.event_type = 'PAIN_PUMP_OFF'
                               and e.event_time >= d.day
                               and e.event_time <  d.day + interval '1 day')             as removed,
                           (select count(*) filter (where e.event_type = 'PAIN_PUMP_ON')
                                 - count(*) filter (where e.event_type = 'PAIN_PUMP_OFF')
                              from surg_event e
                             where e.event_type in ('PAIN_PUMP_ON', 'PAIN_PUMP_OFF')
                               and e.event_time < d.day + interval '1 day')              as in_use
                    from generate_series(?::date, ?::date, interval '1 day') as d(day)
                    order by d.day desc
                    """, w);
            case "1438" -> query("""
                    select coalesce(nullif(trim(s.procedure_name), ''), '（未填写术式）') as procedure_name,
                           coalesce(nullif(trim(s.op_icd), ''), '（未编码）')             as op_icd,
                           count(*)                                                      as cases,
                           count(*) filter (where coalesce(s.status, '') = 'DONE')        as done_cases
                    from inp_surgery s
                    where {alive} and {window}
                    group by 1, 2
                    order by cases desc, 1
                    limit 50
                    """, w);
            case "1439" -> query("""
                    select coalesce(s.surgery_level, '（未分级）')                       as surgery_level,
                           count(*)                                                      as cases,
                           round(100.0 * count(*) / nullif(sum(count(*)) over (), 0), 2)  as pct,
                           count(*) filter (where s.is_unplanned_reop)                    as unplanned_reop
                    from inp_surgery s
                    where {alive} and {window}
                    group by s.surgery_level
                    order by case coalesce(s.surgery_level, 'Z')
                                 when '一级' then 1 when '二级' then 2
                                 when '三级' then 3 when '四级' then 4 else 5 end
                    """, w);
            // planned 是可空三态（V141 明写：true 计划 / false 非计划 / null 不适用）。
            // 一律 `is true` / `is false` 显式分组，**不写 `not e.planned`**——那会吞掉 null 行，
            // 让「非计划转入 ICU」这类重点指标少算，而少算的恰好是最该被看见的那部分。
            case "1446" -> query("""
                    select case e.event_type when 'TO_ICU' then '转入 ICU'
                                             else '转入苏醒室（PACU）' end               as target,
                           case when e.planned is true  then '计划'
                                when e.planned is false then '非计划'
                                else '未区分' end                                        as planned_name,
                           count(*)                                                      as events,
                           count(distinct e.surgery_id)                                  as surgeries
                    from surg_event e
                    join inp_surgery s on s.id = e.surgery_id
                    where e.event_type in ('TO_ICU', 'TO_PACU') and {window}
                    group by e.event_type, e.planned
                    order by e.event_type,
                             case when e.planned is true then 1
                                  when e.planned is false then 2 else 3 end
                    """, w);
            case "1447" -> query("""
                    select case e.event_type
                               when 'INTUBATE_OR'   then '手术室内插管'
                               when 'INTUBATE_PACU' then '苏醒室插管'
                               when 'REINTUBATE'    then '再次插管'
                               when 'EXTUBATE'      then '拔管'
                               when 'OUT_WITH_TUBE' then '带管出室'
                               else e.event_type end                                     as event_name,
                           e.event_type                                                  as event_type,
                           count(*)                                                      as events,
                           count(distinct e.surgery_id)                                  as surgeries,
                           count(*) filter (where e.planned is true)                     as planned_events,
                           count(*) filter (where e.planned is false)                    as unplanned_events,
                           count(*) filter (where e.planned is null)                     as unspecified_events
                    from surg_event e
                    join inp_surgery s on s.id = e.surgery_id
                    where e.event_type in ('INTUBATE_OR', 'INTUBATE_PACU', 'REINTUBATE',
                                           'EXTUBATE', 'OUT_WITH_TUBE')
                      and {window}
                    group by e.event_type
                    order by case e.event_type
                                 when 'INTUBATE_OR' then 1 when 'INTUBATE_PACU' then 2
                                 when 'REINTUBATE' then 3 when 'EXTUBATE' then 4 else 5 end
                    """, w);
            case "1448" -> eventByDay(w, "INVASIVE");
            case "1449" -> eventByDay(w, "RESCUE");
            case "1450" -> query("""
                    select e.type                                       as event_type,
                           count(*)                                     as events,
                           count(*) filter (where e.level = 1)           as level1,
                           count(*) filter (where e.level = 2)           as level2,
                           count(*) filter (where e.level = 3)           as level3,
                           count(*) filter (where e.level = 4)           as level4,
                           count(*) filter (where e.status = 'HANDLED')  as handled
                    from qc_adverse_event e
                    where e.occurred_on >= ?::date and e.occurred_on <= ?::date
                    group by 1
                    order by events desc, 1
                    """, w);
            default -> List.of();
        };
    }

    /**
     * 1424★ 首台：先按手术间 × 自然日排序取 rn=1，再按日汇总准点 / 延迟 / 判不了。
     *
     * <p><b>两套口径并排给，不二选一</b>：{@code first_cases / on_time_rate_pct} 是全部首台，
     * {@code elective_*} 是《2022 版》国标口径（<b>只算择期手术首台</b>，见 V140 对
     * {@code surgery_kind} 的说明）。只给国标口径会让 {@code surgery_kind} 未录的历史手术
     * 整片消失，只给全量口径又与国标对不上——两套并排 + 覆盖率，才是能对账的形态。
     */
    private List<Map<String, Object>> firstCaseRows(Window w) {
        int m = onTimeMinutes();
        return jdbc.queryForList(q("""
                with base as (
                    select s.id, s.scheduled_at, s.start_at, s.surgery_kind, a.dept_id,
                           {anchor}::date as op_day,
                           row_number() over (
                               partition by s.room_no, {anchor}::date
                               order by coalesce(s.in_room_at, s.start_at, s.scheduled_at), s.id) as rn
                    from inp_surgery s
                    join inp_admission a on a.id = s.admission_id
                    where s.room_no is not null and {alive} and {window}
                ), judged as (
                    select b.*,
                           (b.start_at is not null and b.scheduled_at is not null)            as judgeable,
                           (b.start_at is not null and b.scheduled_at is not null
                              and b.start_at <= b.scheduled_at + (?::int * interval '1 minute')) as on_time
                    from base b where b.rn = 1
                )
                select j.op_day,
                       count(*)                                                              as first_cases,
                       count(*) filter (where j.on_time)                                     as on_time,
                       count(*) filter (where j.judgeable and not j.on_time)                 as delayed,
                       count(*) filter (where not j.judgeable)                               as unjudgeable,
                       round(100.0 * count(*) filter (where j.on_time)
                             / nullif(count(*) filter (where j.judgeable), 0), 2)            as on_time_rate_pct,
                       count(*) filter (where j.surgery_kind = 'ELECTIVE')                   as elective_first_cases,
                       count(*) filter (where j.surgery_kind = 'ELECTIVE' and j.on_time)     as elective_on_time,
                       count(*) filter (where j.surgery_kind = 'ELECTIVE'
                                          and j.judgeable and not j.on_time)                 as elective_delayed,
                       round(100.0 * count(*) filter (where j.surgery_kind = 'ELECTIVE' and j.on_time)
                             / nullif(count(*) filter (where j.surgery_kind = 'ELECTIVE'
                                          and j.judgeable), 0), 2)                           as elective_on_time_rate_pct,
                       string_agg(distinct coalesce(d.name, '（未知科室）'), '、')            as first_case_depts
                from judged j
                left join sys_dept d on d.id = j.dept_id
                group by j.op_day
                order by j.op_day desc
                """), w.f(), w.t(), m);
    }

    /** 1425★ 台数 / 手术时长 / 接台时长：ops 与 turn 分开算再左连——接台的分母是「相邻对」不是「台数」 */
    private List<Map<String, Object>> turnoverRows(Window w) {
        return jdbc.queryForList(q("""
                with base as (
                    select s.id, s.room_no, s.in_room_at, s.out_room_at, s.start_at, s.end_at,
                           {anchor}::date as op_day
                    from inp_surgery s
                    where {alive} and {window}
                ), seq as (
                    select b.op_day, b.in_room_at,
                           lag(b.out_room_at) over (partition by b.room_no, b.op_day
                                                    order by b.in_room_at, b.id) as prev_out
                    from base b
                    where b.room_no is not null and b.in_room_at is not null
                ), ops as (
                    select op_day,
                           count(*)                                                       as cases,
                           count(*) filter (where start_at is not null and end_at is not null
                                              and end_at > start_at)                       as timed_cases,
                           coalesce(sum(extract(epoch from (end_at - start_at)) / 60)
                                    filter (where start_at is not null and end_at is not null
                                              and end_at > start_at), 0)                   as op_minutes
                    from base
                    group by op_day
                ), turn as (
                    select op_day, count(*) as turnovers,
                           coalesce(sum(extract(epoch from (in_room_at - prev_out)) / 60), 0) as turn_minutes
                    from seq
                    where prev_out is not null and in_room_at >= prev_out
                    group by op_day
                )
                select o.op_day,
                       o.cases,
                       o.timed_cases,
                       round(o.op_minutes::numeric, 1)                                     as total_op_minutes,
                       round((o.op_minutes / nullif(o.timed_cases, 0))::numeric, 1)         as avg_op_minutes,
                       coalesce(t.turnovers, 0)                                             as turnovers,
                       round(coalesce(t.turn_minutes, 0)::numeric, 1)                       as total_turnover_minutes,
                       round((t.turn_minutes / nullif(t.turnovers, 0))::numeric, 1)         as avg_turnover_minutes
                from ops o
                left join turn t on t.op_day = o.op_day
                order by o.op_day desc
                """), w.args());
    }

    /** 1448★ / 1449★ 同形：按日给出事件次数与涉及手术数 */
    private List<Map<String, Object>> eventByDay(Window w, String eventType) {
        return jdbc.queryForList(q("""
                select {anchor}::date            as op_day,
                       count(*)                  as events,
                       count(distinct e.surgery_id) as surgeries
                from surg_event e
                join inp_surgery s on s.id = e.surgery_id
                where e.event_type = ? and {window}
                group by 1
                order by 1 desc
                """), eventType, w.f(), w.t());
    }

    /** 合计行：只给能一句话说清、且不会被误读成全院口径的几个指标配 */
    private Map<String, Object> summaryOf(String code, Window w) {
        return switch (code) {
            case "1424" -> one(q("""
                    with base as (
                        select s.scheduled_at, s.start_at, s.surgery_kind,
                               row_number() over (
                                   partition by s.room_no, {anchor}::date
                                   order by coalesce(s.in_room_at, s.start_at, s.scheduled_at), s.id) as rn
                        from inp_surgery s
                        where s.room_no is not null and {alive} and {window}
                    ), judged as (
                        select b.surgery_kind,
                               (b.start_at is not null and b.scheduled_at is not null)            as judgeable,
                               (b.start_at is not null and b.scheduled_at is not null
                                  and b.start_at <= b.scheduled_at
                                                    + (?::int * interval '1 minute'))             as on_time
                        from base b where b.rn = 1
                    )
                    select count(*)                                                          as first_cases,
                           count(*) filter (where on_time)                                   as on_time,
                           count(*) filter (where judgeable and not on_time)                 as delayed,
                           count(*) filter (where not judgeable)                             as unjudgeable,
                           round(100.0 * count(*) filter (where on_time)
                                 / nullif(count(*) filter (where judgeable), 0), 2)          as on_time_rate_pct,
                           count(*) filter (where surgery_kind = 'ELECTIVE')                 as elective_first_cases,
                           count(*) filter (where surgery_kind = 'ELECTIVE' and on_time)     as elective_on_time,
                           count(*) filter (where surgery_kind = 'ELECTIVE'
                                              and judgeable and not on_time)                 as elective_delayed,
                           round(100.0 * count(*) filter (where surgery_kind = 'ELECTIVE' and on_time)
                                 / nullif(count(*) filter (where surgery_kind = 'ELECTIVE'
                                              and judgeable), 0), 2)                         as elective_on_time_rate_pct
                    from judged
                    """), w.f(), w.t(), onTimeMinutes());
            case "1427" -> one(q("""
                    select count(*) filter (where s.start_at is not null and s.end_at is not null) as timed_cases,
                           count(*) filter (where s.start_at is not null and s.end_at is not null
                                              and s.start_at::date <> s.end_at::date)              as cross_day_cases,
                           round(100.0 * count(*) filter (where s.start_at is not null
                                              and s.end_at is not null
                                              and s.start_at::date <> s.end_at::date)
                                 / nullif(count(*) filter (where s.start_at is not null
                                              and s.end_at is not null), 0), 2)                    as cross_day_pct
                    from inp_surgery s
                    where {alive} and {window}
                    """), w.args());
            case "1438" -> one(q("""
                    select count(*)                                                   as cases,
                           count(distinct nullif(trim(s.procedure_name), ''))          as procedures,
                           count(*) filter (where coalesce(nullif(trim(s.op_icd), ''), '') <> '') as with_op_icd
                    from inp_surgery s
                    where {alive} and {window}
                    """), w.args());
            default -> null;
        };
    }

    // ===================== 穿透明细（1422★1423★ 的硬要求） =====================

    /**
     * 指标取值明细穿透（1422★1423★ 明文要求「可穿透查看指标取值明细」）。
     *
     * <p>与 {@link #indicators} <b>同时间窗、同锚点、同过滤条件</b>——明细对不上汇总时，
     * 这套指标就失去了管理价值，所以两处共用同一批 SQL 片段常量。
     *
     * <p>硬上限 {@value #DETAIL_LIMIT} 条 + {@code truncated} 标记，<b>不做翻页</b>
     * （照抄 mr-workqueue / v43 医嘱检索纪律：命中超限说明时间窗太宽，应缩窗而不是翻页）。
     * 调用方显式索要超过上限的条数时返 4942，<b>不静默截断成「看着像全量」的结果</b>。
     *
     * @param limit 可选，调用方自定条数上限；超过 {@value #DETAIL_LIMIT} 返 4942
     */
    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam(required = false) String indicator,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Integer limit) {
        Def d = DEFS.get(indicator == null ? "" : indicator.trim());
        if (d == null) {
            return R.fail(4941, "指标编码不存在：" + indicator + "（可用编码见 GET /api/anes-qc/catalog）");
        }
        if (limit != null && limit > DETAIL_LIMIT) {
            return R.fail(4942, "穿透明细条数超限：最多 " + DETAIL_LIMIT + " 条，请缩小统计时间段后再穿透");
        }
        var w = parseWindow(from, to);
        if (w.error() != null) return R.fail(w.error().getCode(), w.error().getMessage());

        var body = new LinkedHashMap<String, Object>();
        body.put("code", d.code());
        body.put("name", d.name());
        body.put("available", d.available());
        body.put("from", w.window().f());
        body.put("to", w.window().t());
        body.put("limit", DETAIL_LIMIT);
        if (!d.available()) {
            body.put("unavailableReason", d.reason());
            body.put("items", List.of());
            body.put("truncated", false);
            return R.ok(body);
        }
        List<Map<String, Object>> rows = detailRows(d.code(), w.window());
        boolean truncated = rows.size() > DETAIL_LIMIT;
        body.put("items", truncated ? rows.subList(0, DETAIL_LIMIT) : rows);
        body.put("truncated", truncated);
        body.put("anchorNote", ANCHOR_NOTE);
        String note = noteOf(d.code());
        if (note != null) body.put("note", note);
        return R.ok(body);
    }

    private List<Map<String, Object>> detailRows(String code, Window w) {
        return switch (code) {
            case "1424" -> jdbc.queryForList(q("""
                    with base as (
                        select s.id,
                               row_number() over (
                                   partition by s.room_no, {anchor}::date
                                   order by coalesce(s.in_room_at, s.start_at, s.scheduled_at), s.id) as rn
                        from inp_surgery s
                        where s.room_no is not null and {alive} and {window}
                    )
                    select x.*,
                           round(extract(epoch from (x.start_at - x.scheduled_at)) / 60.0) as delay_minutes,
                           case when x.start_at is null or x.scheduled_at is null
                                     then '无法判定（缺排台或开台时间）'
                                when x.start_at <= x.scheduled_at + (?::int * interval '1 minute')
                                     then '准点'
                                else '延迟' end                                            as judgement
                    from (
                        {surg}
                        where s.id in (select id from base where rn = 1)
                    ) x
                    order by x.start_at desc nulls last, x.surgery_id desc
                    {cap}
                    """), w.f(), w.t(), onTimeMinutes());
            case "1425" -> jdbc.queryForList(q("""
                    select x.*,
                           round(extract(epoch from (x.end_at - x.start_at)) / 60.0)        as op_minutes,
                           round(extract(epoch from (x.in_room_at - prev.prev_out)) / 60.0) as turnover_minutes
                    from (
                        {surg}
                        where {alive} and {window}
                    ) x
                    left join lateral (
                        select max(s2.out_room_at) as prev_out
                        from inp_surgery s2
                        where x.in_room_at is not null
                          and s2.room_no = x.room_no
                          and s2.out_room_at is not null
                          and s2.out_room_at <= x.in_room_at
                          and s2.out_room_at::date = x.in_room_at::date
                          and s2.id <> x.surgery_id
                    ) prev on true
                    order by x.in_room_at desc nulls last, x.surgery_id desc
                    {cap}
                    """), w.args());
            case "1426" -> jdbc.queryForList(q("""
                    select x.*, y.cancel_stage, y.cancel_reason
                    from (
                        {surg}
                        where (s.cancel_stage is not null or coalesce(s.status, '') = 'CANCELLED')
                          and {window}
                    ) x
                    join inp_surgery y on y.id = x.surgery_id
                    order by x.surgery_id desc
                    {cap}
                    """), w.args());
            case "1427" -> jdbc.queryForList(q("""
                    {surg}
                    where s.start_at is not null and s.end_at is not null
                      and s.start_at::date <> s.end_at::date
                      and {alive} and {window}
                    order by s.start_at desc
                    {cap}
                    """), w.args());
            case "1428", "1430", "1436", "1438", "1439" -> jdbc.queryForList(q("""
                    {surg}
                    where {alive} and {window}
                    order by {anchor} desc, s.id desc
                    {cap}
                    """), w.args());
            case "1429" -> jdbc.queryForList(q("""
                    select x.*, (dc.admission_id is not null) as died, dc.died_at
                    from (
                        {surg}
                        where {alive} and {window}
                    ) x
                    left join (select admission_id, min(died_at) as died_at from mr_death_card
                               where admission_id is not null group by admission_id) dc
                           on dc.admission_id = x.admission_id
                    order by x.surgery_id desc
                    {cap}
                    """), w.args());
            case "1431" -> jdbc.queryForList(q("""
                    {surg}
                    where (s.anesthesia_type like '%局部麻醉%' or s.anesthesia_type like '%局麻%')
                      and {alive} and {window}
                    order by {anchor} desc, s.id desc
                    {cap}
                    """), w.args());
            case "1432", "1434" -> transfusionDetail(w, false);
            case "1433" -> transfusionDetail(w, true);
            case "1437" -> eventDetail(w, "'PAIN_PUMP_ON', 'PAIN_PUMP_OFF'");
            case "1446" -> eventDetail(w, "'TO_ICU', 'TO_PACU'");
            case "1447" -> eventDetail(w,
                    "'INTUBATE_OR', 'INTUBATE_PACU', 'REINTUBATE', 'EXTUBATE', 'OUT_WITH_TUBE'");
            case "1448" -> eventDetail(w, "'INVASIVE'");
            case "1449" -> eventDetail(w, "'RESCUE'");
            case "1450" -> jdbc.queryForList(q("""
                    select e.id                            as event_id,
                           e.type                          as event_type,
                           e.level,
                           e.occurred_on,
                           coalesce(d.name, '（未填科室）') as dept_name,
                           e.description,
                           e.status
                    from qc_adverse_event e
                    left join sys_dept d on d.id = e.dept_id
                    where e.occurred_on >= ?::date and e.occurred_on <= ?::date
                    order by e.occurred_on desc, e.id desc
                    {cap}
                    """), w.args());
            default -> List.of();
        };
    }

    private List<Map<String, Object>> transfusionDetail(Window w, boolean autoOnly) {
        return jdbc.queryForList(q("""
                select t.id                as transfusion_id,
                       s.id                as surgery_id,
                       a.admission_no,
                       p.name              as patient_name,
                       s.procedure_name,
                       t.product_type,
                       t.volume_ml,
                       t.is_auto,
                       t.transfused_at
                from surg_transfusion t
                join inp_surgery s on s.id = t.surgery_id
                join inp_admission a on a.id = s.admission_id
                join empi_patient p on p.id = a.patient_id
                where {window}
                """ + (autoOnly ? "  and coalesce(t.is_auto, false)\n" : "") + """
                order by t.transfused_at desc nulls last, t.id desc
                {cap}
                """), w.args());
    }

    /** {@code typeList} 是本类内写死的字面量列表（无外部输入），不参与参数绑定 */
    private List<Map<String, Object>> eventDetail(Window w, String typeList) {
        return jdbc.queryForList(q("""
                select e.id            as event_id,
                       e.surgery_id,
                       a.admission_no,
                       p.name          as patient_name,
                       s.procedure_name,
                       s.room_no,
                       e.event_type,
                       e.event_time,
                       e.planned,
                       e.detail,
                       u.real_name     as operator_name
                from surg_event e
                join inp_surgery s on s.id = e.surgery_id
                join inp_admission a on a.id = s.admission_id
                join empi_patient p on p.id = a.patient_id
                left join sys_user u on u.id = e.operator_id
                where e.event_type in (""" + typeList + """
                ) and {window}
                order by e.event_time desc nulls last, e.id desc
                {cap}
                """), w.args());
    }

    // ===================== CSV 导出（1423★「支持导出为 EXCEL 表格」） =====================

    /**
     * 指标汇总 CSV（与 {@link #indicators} 同 SQL 同口径）。
     *
     * <p>各指标列结构不同，故 <b>CSV 逐指标导出</b>，{@code indicator} 必填。CSV 端点返回的是
     * 文本流，没有 {@code {code,message}} 载体，故编码不存在 / 时间段非法时导出一份<b>只含错误码
     * 与说明的 CSV</b>——绝不导出一张空表让人误以为「本时段无数据」。口径说明以尾注形式随表导出。
     */
    @GetMapping(value = "/indicators.csv", produces = "text/csv;charset=UTF-8")
    public String indicatorsCsv(@RequestParam(required = false) String indicator,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        Def d = DEFS.get(indicator == null ? "" : indicator.trim());
        if (d == null) return errCsv(4941, "指标编码不存在：" + indicator);
        var w = parseWindow(from, to);
        if (w.error() != null) return errCsv(w.error().getCode(), w.error().getMessage());
        if (!d.available()) return unavailableCsv(d, w.window());
        return toCsv(d, w.window(), "指标汇总", rowsOf(d.code(), w.window()), false);
    }

    /** 穿透明细 CSV（与 {@link #detail} 同 SQL 同口径、同 200 条上限；超限在尾注明写截断） */
    @GetMapping(value = "/detail.csv", produces = "text/csv;charset=UTF-8")
    public String detailCsv(@RequestParam(required = false) String indicator,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to) {
        Def d = DEFS.get(indicator == null ? "" : indicator.trim());
        if (d == null) return errCsv(4941, "指标编码不存在：" + indicator);
        var w = parseWindow(from, to);
        if (w.error() != null) return errCsv(w.error().getCode(), w.error().getMessage());
        if (!d.available()) return unavailableCsv(d, w.window());
        List<Map<String, Object>> rows = detailRows(d.code(), w.window());
        boolean truncated = rows.size() > DETAIL_LIMIT;
        return toCsv(d, w.window(), "取值明细",
                truncated ? rows.subList(0, DETAIL_LIMIT) : rows, truncated);
    }

    private String errCsv(int code, String message) {
        return "﻿错误码,说明\n" + csv(code) + "," + csv(message) + "\n";
    }

    /** 缺数据源的指标导出的是「为什么没有」，不是一张全 0 的表 */
    private String unavailableCsv(Def d, Window w) {
        var sb = new StringBuilder("﻿指标编码,指标名称,统计区间,是否有数据源\n");
        sb.append("%s,%s,%s,%s\n".formatted(csv(d.code()), csv(d.name()),
                csv(w.f() + " 至 " + w.t()), csv("否（缺数据源，本平台不给近似值）")));
        sb.append('\n').append(csv("缺数据源原因：" + d.reason())).append('\n');
        sb.append(csv("口径：" + STANDARD_NOTE)).append('\n');
        return sb.toString();
    }

    private String toCsv(Def d, Window w, String kind, List<Map<String, Object>> rows, boolean truncated) {
        var sb = new StringBuilder("﻿指标编码,指标名称,报表,统计区间\n");
        sb.append("%s,%s,%s,%s\n\n".formatted(csv(d.code()), csv(d.name()), csv(kind),
                csv(w.f() + " 至 " + w.t())));
        if (rows.isEmpty()) {
            sb.append(csv("（该统计区间内无数据）")).append('\n');
        } else {
            var cols = new ArrayList<>(rows.get(0).keySet());
            sb.append(String.join(",", cols.stream().map(c -> csv(zh(c))).toList())).append('\n');
            for (var r : rows) {
                sb.append(String.join(",", cols.stream().map(c -> csv(r.get(c))).toList())).append('\n');
            }
        }
        sb.append('\n');
        if (truncated) {
            sb.append(csv("注意：命中超过 " + DETAIL_LIMIT + " 条，本文件只含前 " + DETAIL_LIMIT
                    + " 条，请缩小统计区间后重新导出（不做翻页）")).append('\n');
        }
        sb.append(csv("口径：" + STANDARD_NOTE)).append('\n');
        sb.append(csv("口径：" + ANCHOR_NOTE)).append('\n');
        sb.append(csv("口径：" + TIMEPOINT_CAVEAT)).append('\n');
        String note = noteOf(d.code());
        if (note != null) sb.append(csv("口径：" + note)).append('\n');
        return sb.toString();
    }

    /** 列名中文化（CSV 表头用；未登记的列名原样输出，不猜也不隐藏） */
    private static String zh(String col) {
        return switch (col) {
            case "op_day" -> "日期";
            case "first_cases" -> "首台量";
            case "on_time" -> "准点开台数";
            case "delayed" -> "延迟开台数";
            case "unjudgeable" -> "无法判定数";
            case "on_time_rate_pct" -> "准点开台率(%)";
            case "elective_first_cases" -> "择期首台量(国标口径)";
            case "elective_on_time" -> "择期准点开台数";
            case "elective_delayed" -> "择期延迟开台数";
            case "elective_on_time_rate_pct" -> "择期首台准点率(%,2022国标)";
            case "first_case_depts" -> "首台科室";
            case "cases" -> "台数";
            case "timed_cases" -> "有完整手术时长的台数";
            case "total_op_minutes" -> "总手术时长(分钟)";
            case "avg_op_minutes" -> "平均手术时长(分钟)";
            case "turnovers" -> "接台次数";
            case "total_turnover_minutes" -> "总接台时长(分钟)";
            case "avg_turnover_minutes" -> "平均接台时长(分钟)";
            case "stage_name" -> "取消阶段";
            case "cancel_stage" -> "阶段编码";
            case "cancel_reason" -> "取消原因";
            case "with_reason" -> "已填原因数";
            case "cross_day_cases" -> "跨日手术台数";
            case "cross_day_pct" -> "跨日占比(%)";
            case "avg_hours" -> "平均时长(小时)";
            case "kind_name" -> "手术类别";
            case "surgery_kind" -> "类别编码";
            case "pct" -> "构成比(%)";
            case "asa_grade" -> "ASA 分级";
            case "deaths" -> "死亡例数";
            case "death_rate_pct" -> "死亡率(%)";
            case "anesthesia_type" -> "麻醉方式";
            case "dept_name" -> "科室";
            case "product_name" -> "血制品";
            case "product_type" -> "血制品编码";
            case "records" -> "记录条数";
            case "surgeries" -> "涉及手术数";
            case "total_ml" -> "总量(ml)";
            case "auto_records" -> "其中自体血记录数";
            case "auto_ml" -> "其中自体血量(ml)";
            case "band" -> "输注量分档";
            case "patients" -> "人数";
            case "transfused_patients" -> "输血患者数";
            case "auto_patients" -> "自体血患者数";
            case "non_auto_patients" -> "非自体血患者数";
            case "both_patients" -> "两类均有患者数(重叠)";
            case "transfused_surgeries" -> "输血台次数";
            case "planned_events" -> "计划";
            case "unplanned_events" -> "非计划";
            case "unspecified_events" -> "未区分";
            case "age_band" -> "年龄段";
            case "sex" -> "性别";
            case "added" -> "新增镇痛泵";
            case "removed" -> "拆泵";
            case "in_use" -> "在用量(全历史累计)";
            case "procedure_name" -> "术式";
            case "op_icd" -> "手术操作编码(自填)";
            case "done_cases" -> "已完成台数";
            case "procedures" -> "术式种数";
            case "with_op_icd" -> "已填编码台数";
            case "surgery_level" -> "手术级别";
            case "unplanned_reop" -> "非计划再次手术";
            case "target" -> "去向";
            case "planned_name" -> "计划性";
            case "events" -> "事件次数";
            case "event_name" -> "事件";
            case "event_type" -> "事件编码";
            case "level" -> "级别";
            case "level1" -> "Ⅰ级";
            case "level2" -> "Ⅱ级";
            case "level3" -> "Ⅲ级";
            case "level4" -> "Ⅳ级";
            case "handled" -> "已处置";
            case "surgery_id" -> "手术ID";
            case "admission_id" -> "住院ID";
            case "admission_no" -> "住院号";
            case "patient_name" -> "患者";
            case "room_no" -> "手术间";
            case "scheduled_at" -> "排台时间";
            case "in_room_at" -> "入室时间";
            case "start_at" -> "开台时间";
            case "end_at" -> "结束时间";
            case "out_room_at" -> "出室时间";
            case "status" -> "状态";
            case "delay_minutes" -> "较排台延迟(分钟)";
            case "judgement" -> "判定";
            case "op_minutes" -> "手术时长(分钟)";
            case "turnover_minutes" -> "接台时长(分钟)";
            case "died" -> "是否死亡";
            case "died_at" -> "死亡时间";
            case "transfusion_id" -> "输血记录ID";
            case "volume_ml" -> "输注量(ml)";
            case "is_auto" -> "是否自体血";
            case "transfused_at" -> "输注时间";
            case "event_id" -> "事件ID";
            case "event_time" -> "事件时间";
            case "planned" -> "是否计划内";
            case "detail" -> "说明";
            case "operator_name" -> "操作人";
            case "occurred_on" -> "发生日期";
            case "description" -> "描述";
            default -> col;
        };
    }

    /**
     * CSV 字段转义 —— 与 StatsController.csv / MrQcController.csv / PrintReportController.csv
     * 逐字同款（含公式注入守卫）：科室名、术式名、取消原因、不良事件描述都是可写入的自由文本，
     * 以 = + - @ 开头会被 Excel 当公式执行；含逗号会串列。
     * 数值不加 ' 前缀（加了在 Excel 里变文本，SUM 跳过，台数/时长列合计对不上）。
     * 本轮不改上述三处（并行车道占用），故在此自带一份；抽公共工具类留作后续小重构。
     */
    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (!(v instanceof Number) && !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n")
                ? "\"" + s.replace("\"", "\"\"") + "\""
                : s;
    }

    // ===================== 小工具 =====================

    private List<Map<String, Object>> query(String sql, Window w) {
        return jdbc.queryForList(q(sql), w.args());
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }
}
