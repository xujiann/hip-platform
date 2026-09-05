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
import java.util.Locale;
import java.util.Map;

/**
 * v48 车道 P4：病理质控指标与工作量（《病理专业医疗质量控制指标（2024 年版）》方向）。
 *
 * <p><b>纯只读统计层</b>：本控制器不建表、不加列、不写任何一行数据。表结构由主控的 V144 一次落定，
 * 字段由 P1（申请与登记）/ P2（取材）/ P3（技术制片、报告与双签）三条车道写入。
 *
 * <h2>诚实标注三件套（沿用 v46 {@link AnesQcController} 的范式）</h2>
 * <ol>
 *   <li><b>缺数据源的指标标 {@code available=false}，返回体里根本不给 rows / summary 键</b>——
 *       返回一个看起来像真的的 0 比不返回更坏：管理者会把「本院冰冻与石蜡零不符合」当成结论。
 *       每条另给 {@code missingFields}，明写缺哪几个字段、补了之后就能算。
 *   <li><b>返回体带 {@code coverage} 段</b>，给出各字段的实际录入覆盖率——
 *       「报告及时率 100%」很可能只是「本时段仅 2 例录了签发时刻」。
 *   <li><b>口径 {@code caveat} 随返回体下发</b>（顶层 {@code caveats} 数组 + 每个指标自己的
 *       {@code caveat}），CSV 导出也带同一份页脚。口径只写在页面上，导出的表格一转手就没了。
 * </ol>
 *
 * <h2>统计时间窗归集口径：本域各指标<b>不共用同一个锚点</b></h2>
 * <p>{@value #WINDOW_ANCHOR_NOTE}
 *
 * <h2>三条「符合率」指标全部缺数据源</h2>
 * <p>术中冰冻与石蜡诊断符合率、临床诊断符合率、外院会诊符合率——本仓<b>没有「符合 / 不符合」的
 * 录入位</b>，也没有冰冻与石蜡的配对键。这三条一律 {@code available=false} 并列出缺失字段，
 * <b>不用别的字段凑近似值</b>：拿两段自由文本诊断做字符串比对判「符合」，会得出一个精确到小数点后
 * 两位的假数字，而这三条恰恰是要上报、要追责、要做科室质量分析的指标。
 *
 * <p>错误码段 5280–5299（见 docs/错误码分段.md）：<b>实测只用掉 3 个</b>——
 * 5280 统计时间段非法（起止倒置 / 日期格式非法 / 跨度超限三路同码，纸面上都是「这个时间段不成立」）、
 * 5281 指标编码不存在、5282 穿透明细条数超限。5283–5299 空置，<b>不预留登记</b>（不写代码就不占码）。
 *
 * <p>配置：{@code path.report.routine_hours}（默认 {@value #DEFAULT_ROUTINE_HOURS} 小时）与
 * {@code path.report.frozen_minutes}（默认 {@value #DEFAULT_FROZEN_MINUTES} 分钟），
 * 均由 V144 seed，本控制器只读不写。
 */
/*
 * 权限：与 v46 麻醉质控（AnesQcController）逐字对齐 ADMIN + QUALITY。
 * **刻意不放宽到 TECHNICIAN / DOCTOR_OUTP**：穿透明细逐条带患者号与姓名，是全院范围的患者级数据，
 * 没有科室边界；管理指标看板不该成为一条绕开病理专页限权的取数通道。
 * 若院方要让病理科自查工作量，须**同时**改合版迁移里菜单 169 的 sys_role_menu 授权与本注解——
 * 两处一起改，不留单边缺口（v42 吃过一次「菜单给了、接口 1005」的亏）。
 */
@RestController
@RequestMapping("/api/path-qc")
@PreAuthorize("hasAnyRole('ADMIN','QUALITY')")
@RequiredArgsConstructor
public class PathQcController {

    // ===================== 口径常量（返回体 caveat 与 CSV 页脚同源一份） =====================

    static final String WINDOW_ANCHOR_NOTE =
            "统计时间窗归集口径：本域各指标**不共用同一个归集时刻**，跨指标横向相加没有意义。"
            + "登记 / 接收 / 固定类按 path_specimen.collected_at（登记时刻）落窗；"
            + "报告及时率、双签率与报告签发量按 report_issued_at（正式签发时刻）落窗；"
            + "蜡块按 coalesce(embedded_at, created_at)、切片按 coalesce(stained_at, created_at)、"
            + "流转环节按 path_process.occurred_at、特检技术医嘱按 path_tech_order.ordered_at。"
            + "每个指标各自回带 anchorField 与 anchor 明示——同一批标本在「本期登记」与「本期签发」"
            + "两个口径下不是同一批，前者含尚未出报告的在途标本，后者含上期登记本期才发的标本。";

    static final String DATA_CAVEAT =
            "数据覆盖面：v48 之前入库的病理标本，病理号 / 标本类别 / 取材部位 / 固定液 / 固定时刻 / "
            + "拒收信息 / 初诊复诊双签 / 报告签发时刻全部为空，且**刻意不回填**（V144 零回填纪律："
            + "当时确实没采集，拿 collected_at 去填 received_at 会让签收及时率恒等于 100%）。"
            + "因此依赖这些字段的指标只覆盖 v48 之后真正录了相应字段的标本，不是全院全历史口径。"
            + "**请先看 coverage 段的字段录入覆盖率，再看指标值**——「及时率 100%」很可能只是"
            + "「本时段仅 2 例录了时间」。";

    static final String STANDARD_NOTE =
            "指标口径按《病理专业医疗质量控制指标（2024 年版）》方向硬编码，报告时限两个阈值走 sys_config。"
            + "本版不提供指标定义可配置编辑器（属实施期工作），此处不留假入口。"
            + "本平台亦不做病理图像 / 数字切片（WSI）/ 玻片打码设备直连相关的任何指标——"
            + "全仓无文件上传基础设施与设备驱动，这些指标一条都算不出来，故连指标目录里都不列。";

    static final String STATUS_NOTE =
            "本域指标一律以**时间戳列**判定状态（received_at / rejected_at / diagnosed_at / "
            + "report_issued_at / first_signed_at / second_signed_at），不按 path_specimen.status "
            + "字符串判定：status 的既有值域只有 COLLECTED/RECEIVED/DIAGNOSED 三档，"
            + "拒收与签发都没有对应档位，按 status 判会整片漏统计。";

    static final String ISSUE_VS_DIAGNOSE_NOTE =
            "报告及时率的终点一律取 report_issued_at（双签后正式签发），**不拿 diagnosed_at（写完诊断）"
            + "冒充**：两者之间隔着初诊签名、复诊签名与签发三步，合并成一个时刻就再也算不出复诊等待时长。"
            + "后果是——仍走既有 PUT /specimens/{barcode}/diagnose 结案、未走新签发流程的标本，"
            + "report_issued_at 为空，**整片不进分母**，本指标会显示「本期签发 0 份」而不是「及时率 100%」。"
            + "coverage.specimens.diagnosed_not_issued 一列给出这类标本的条数，先看它再看及时率。";

    static final String TAT_START_NOTE =
            "报告时限的起点取 received_at（病理科签收），终点取 report_issued_at。若签收时刻是事后补录的，"
            + "本指标会失真——补录的签收时刻通常晚于真实签收，会把超时算成及时。";

    static final String HOLIDAY_NOTE =
            "常规报告时限配置项 path.report.routine_hours 默认 120（注为「5 个工作日」），但本平台"
            + "**按自然小时判定，不是工作日**：全仓无法定节假日日历表（grep holiday / 节假日 / workday 零命中）。"
            + "跨周末与长假的标本会被判成超时，节假日多的时段该指标必然偏低，不能直接当上报口径。";

    static final String WORKLOAD_NOTE =
            "工作量一律是**计数**，不折算工时、不折算绩效点数：本仓无病理工作量权重表（诊断难度系数 / RVU），"
            + "把标本数直接当工作量会让「一台大标本多部位取材」与「一份细胞学涂片」等值。"
            + "要折算须先建权重主数据，本只读统计层不建表。";

    /** 统计时间窗最大跨度（天）：与 AnesQcController / StatsController.daily 同量级 */
    static final int MAX_SPAN_DAYS = 366;

    /** 穿透明细硬上限（照抄 v43 医嘱检索与 v46 麻醉质控纪律：限量 + truncated 标记，不做翻页） */
    static final int DETAIL_LIMIT = 200;

    /**
     * 汇总行硬上限。汇总行数天然受时间窗（≤366 天）与枚举值域约束，正常到不了；
     * 但「各病理医师工作量」的行数等于本期有操作的人数，无天然上限——
     * 一律限量 + rowsTruncated 标记，<b>不静默截断</b>。
     */
    static final int ROW_LIMIT = 500;

    static final int DEFAULT_ROUTINE_HOURS = 120;
    static final int DEFAULT_FROZEN_MINUTES = 30;

    // ===================== SQL 片段（占位符替换，参数一律走 ?，禁止拼接用户输入） =====================
    //
    // 用 {占位符} + replace 而不是文本块直接相加：文本块会剥掉行尾空白，拼接处极易少一个空格，
    // 拼出只在运行期才炸的 SQL（v46 的同款处置）。占位符没有拼接边界。

    /** 患者归属联接：病理标本是**双来源**（门诊 order_id / 住院 inp_order_id 恰有其一） */
    private static final String PAT_JOINS = """
            left join outp_order oo on oo.id = s.order_id
            left join outp_registration orr on orr.id = oo.registration_id
            left join empi_patient op on op.id = orr.patient_id
            left join inp_order io on io.id = s.inp_order_id
            left join inp_admission ia on ia.id = io.admission_id
            left join empi_patient ipa on ipa.id = ia.patient_id
            """;

    /** 标本明细的公共投影：明细要能直接核对到人、到标本、到签名人，否则「穿透」是空话 */
    private static final String SPEC_SELECT = """
            select s.id                                                        as specimen_id,
                   s.path_no,
                   s.barcode,
                   s.part_no,
                   coalesce(s.specimen_type, '（未填）')                        as specimen_type,
                   case when s.order_id is not null then 'OUTP' else 'INP' end as source,
                   coalesce(op.patient_no, ipa.patient_no)                     as patient_no,
                   coalesce(op.name, ipa.name)                                 as patient_name,
                   coalesce(od.name, ad.name)                                  as dept_name,
                   s.sampling_site,
                   s.urgent,
                   s.collected_at,
                   s.received_at,
                   s.fixative,
                   s.fixed_at,
                   s.diagnosed_at,
                   s.first_signed_at,
                   s.second_signed_at,
                   s.report_issued_at,
                   s.rejected_at,
                   s.reject_reason,
                   pu.real_name                                                as pathologist_name,
                   su1.real_name                                               as first_signer_name,
                   su2.real_name                                               as second_signer_name
            from path_specimen s
            left join outp_order oo on oo.id = s.order_id
            left join outp_registration orr on orr.id = oo.registration_id
            left join empi_patient op on op.id = orr.patient_id
            left join sys_dept od on od.id = orr.dept_id
            left join inp_order io on io.id = s.inp_order_id
            left join inp_admission ia on ia.id = io.admission_id
            left join empi_patient ipa on ipa.id = ia.patient_id
            left join sys_dept ad on ad.id = ia.dept_id
            left join sys_user pu on pu.id = s.pathologist_id
            left join sys_user su1 on su1.id = s.first_signer_id
            left join sys_user su2 on su2.id = s.second_signer_id
            """;

    /** 标本类别中文名（值域与 chk_path_specimen_type 一致） */
    private static final String TYPE_NAME = """
            case s.specimen_type
                when 'ROUTINE'   then '常规'
                when 'FROZEN'    then '术中冰冻'
                when 'CYTOLOGY'  then '细胞学'
                when 'CONSULT'   then '会诊'
                when 'MOLECULAR' then '分子病理'
                else '（未填类别）' end
            """;

    /** 各归集锚点的时间窗谓词（闭开区间 [from, to+1)，各两个 ?::date 参数） */
    private static final String W_COLLECTED = "s.collected_at    >= ?::date and s.collected_at    < ?::date + 1";
    private static final String W_ISSUED = "s.report_issued_at >= ?::date and s.report_issued_at < ?::date + 1";
    private static final String W_SLIDE =
            "coalesce(sl.stained_at, sl.created_at) >= ?::date and coalesce(sl.stained_at, sl.created_at) < ?::date + 1";
    private static final String W_BLOCK =
            "coalesce(b.embedded_at, b.created_at) >= ?::date and coalesce(b.embedded_at, b.created_at) < ?::date + 1";

    /** 穿透明细多取 1 条判 truncated：只取 LIMIT 条会让「刚好第 200 条」漏报 */
    private static final String DETAIL_CAP = " limit " + (DETAIL_LIMIT + 1);

    /** 占位符展开。{spec} 内不含其它占位符，其余互不嵌套 */
    private static String q(String sql) {
        return sql.replace("{spec}", SPEC_SELECT)
                .replace("{pat}", PAT_JOINS)
                .replace("{patName}", "coalesce(op.name, ipa.name)")
                .replace("{typeName}", TYPE_NAME)
                .replace("{wc}", W_COLLECTED)
                .replace("{wi}", W_ISSUED)
                .replace("{ws}", W_SLIDE)
                .replace("{wb}", W_BLOCK)
                .replace("{cap}", DETAIL_CAP);
    }

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    // ===================== 指标注册表 =====================

    /**
     * 指标定义。{@code available=false} 的三条各自带 {@code reason} 与 {@code missingFields}，
     * <b>不返回 rows、不返回 0</b>。{@code anchorField} 是归集列（SQL 列名），
     * {@code anchor} 是同一件事的中文说明——两者必须同时给：只给中文说明的话，
     * 对账的人无法回到 SQL 去核实到底按哪一列落的窗。
     */
    private record Def(String code, String name, boolean available, String reason,
                       List<String> missingFields, String anchorField, String anchor, String caveat) {}

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    private static void def(String code, String name, String anchorField, String anchor, String caveat) {
        DEFS.put(code, new Def(code, name, true, null, List.of(), anchorField, anchor, caveat));
    }

    private static void unavailable(String code, String name, String reason, String... missing) {
        DEFS.put(code, new Def(code, name, false, reason, List.of(missing), null, null, null));
    }

    private static final String ANCHOR_COLLECTED = "按标本**登记时刻**归集（含本期登记但尚未出报告的在途标本）";
    private static final String ANCHOR_ISSUED = "按报告**正式签发时刻**归集（含上期登记、本期才签发的标本）";

    static {
        def("SPECIMEN_RECEIVE", "标本接收（登记→签收）情况与时长分档",
                "path_specimen.collected_at", ANCHOR_COLLECTED,
                "collected_at 是**登记时刻**（V21 建表 default now()，取材登记时由系统打时间戳），"
                + "**不是标本离体时刻**——本仓无离体时刻字段。故本指标度量的是「登记→签收」的院内流转，"
                + "不能当作《2024 版》以离体时刻为起点的标本接收及时率上报。"
                + "另：「及时」的判定阈值本平台**无配置键**（病理只有报告时限两个键），故**不给一个单一的"
                + "「接收及时率」数字**，改给接收率 + 时长分档 + 中位数 / P90，由看的人按本院规定自己判。"
                + "要出单一及时率须先新增配置键（如 path.receive.timely_minutes）并同时补迁移 seed 与配置手册。"
                + "补录导致的「签收早于登记」单列 negative_interval，既不并进分档也不取绝对值——"
                + "取绝对值会把一条数据质量问题伪装成一次极快的签收。"
                + "分母 submitted 是送检总数、**含拒收**（V144 明写拒收不删记录）。");

        def("FIXATION", "标本固定信息完整率（**不是**国标口径的规范化固定率）",
                "path_specimen.collected_at", ANCHOR_COLLECTED,
                "《2024 版》标本规范化固定的要素——固定液类型（中性缓冲福尔马林）、固定液量为标本体积 "
                + "3–10 倍、离体后 ≤30 分钟内固定、固定时长 6–72 小时——本平台只有 fixative"
                + "（自由文本，无固定液字典）与 fixed_at 两列，**离体时刻、固定液用量、固定结束时刻"
                + "三个字段全仓不存在**，四要素只有「固定信息是否录全」这一条可判。"
                + "故列名刻意叫 fixation_recorded_rate_pct 而**不叫 compliance_rate_pct**——"
                + "它不是国标规范率，不可上报。fixatives 列原样列出该组实际录入的固定液文本，"
                + "是否规范请人工看：本平台**不按关键字（如含「福尔马林」）猜规范性**。"
                + "分母含拒收标本。穿透明细的 minutes_to_fixation 是「登记→固定」而非「离体→固定」。");

        def("REPORT_ROUTINE", "常规病理报告及时率（时限读 path.report.routine_hours）",
                "path_specimen.report_issued_at", ANCHOR_ISSUED,
                ISSUE_VS_DIAGNOSE_NOTE + " " + TAT_START_NOTE + " " + HOLIDAY_NOTE
                + " 只统计 specimen_type='ROUTINE' 的标本：类别未填的历史标本**不默认算常规**"
                + "（默认算常规会把一批不知道时限的标本按 120 小时判，判出来的超时是假的），"
                + "其条数见 coverage.specimens.with_specimen_type。"
                + "with_tech_order 一列是「本期签发的报告里加做过特检技术（免疫组化 / 深切 / 补取材等）的份数」："
                + "国标常允许这类病例延长时限，本平台**不自动放宽**，故这一列的超时不等于质量问题，"
                + "要单独看；不放宽是因为「放宽多久」同样没有配置键，硬编码一个天数就是自造口径。");

        def("REPORT_FROZEN", "术中冰冻病理报告及时率（时限读 path.report.frozen_minutes）",
                "path_specimen.report_issued_at", ANCHOR_ISSUED,
                ISSUE_VS_DIAGNOSE_NOTE + " " + TAT_START_NOTE
                + " 只统计 specimen_type='FROZEN' 的标本。冰冻的国标起点通常是「标本送达病理科」，"
                + "本平台取 received_at（签收）——两者在流程规范的科室基本重合，在签收靠补录的科室会明显偏乐观。"
                + "冰冻 30 分钟的时限对签收时刻的准确度要求远高于常规 120 小时：晚补 5 分钟就能把超时变及时，"
                + "故这条指标**必须配合 coverage 看，且不建议在签收全靠事后补录的科室对外公布**。");

        def("REPORT_DOUBLE_SIGN", "报告双签完成率（初诊—复诊两级签发）",
                "path_specimen.report_issued_at", ANCHOR_ISSUED,
                "双签 gate emr.gate.pathology.doublesign **默认 warn**（未双签也放行签发，见 V144 注释："
                + "存量流程可能只有一名病理医师，直接 block 会让报告发不出去）。故 double_sign_rate_pct "
                + "小于 100% 是配置使然而非程序缺陷；要卡死须把 gate 切 block。"
                + "same_person_double_sign 一列是完整性哨兵：签发端点已禁止初诊人复诊，"
                + "该列非 0 只可能来自绕开端点的直接改库或历史数据，出现即应查。"
                + "only_second_sign（仅复签无初签）同理，正常流程走不出来。");

        unavailable("FROZEN_PARAFFIN_CONCORDANCE", "术中冰冻与石蜡诊断符合率",
                "缺数据源：全仓既没有「冰冻—石蜡配对」关系列，也没有「符合性判定」录入位。"
                + "path_specimen.specimen_type 能分出 FROZEN 与 ROUTINE 两类记录，但两条记录之间"
                + "**没有任何一列把它们关联起来**——part_no 是同一份申请下的部位序号，不是冰冻与石蜡的配对键，"
                + "同一部位的冰冻与后续石蜡完全可能分属两份申请（住院手术冰冻 + 术后石蜡送检是常态）。"
                + "「按同一 order_id 下既有冰冻又有常规就配成一对，再拿两段自由文本诊断做字符串比对判符合」"
                + "是危险的假实现：冰冻与石蜡不符合率要上报、要追责、要做科室质量分析，猜错一例就是一条假账。"
                + "补法：地基加配对键与符合性判定列后，本指标即可按现成口径出。",
                "path_specimen.paired_specimen_id（冰冻与对应石蜡标本的配对键）",
                "path_specimen.frozen_concordance（符合 / 部分符合 / 不符合）",
                "path_specimen.discordance_reason（不符合原因分类：取材误差 / 判读误差 / 标本因素…）",
                "判定人与判定时刻（谁在什么时候做的符合性判定，无此两列则判定不可追溯）");

        unavailable("CLINICAL_CONCORDANCE", "临床诊断与病理诊断符合率",
                "缺数据源：无「病理诊断与临床诊断是否符合」的录入位。clinical_diagnosis 列虽然有"
                + "（V144 新增，自由文本 500 字），但符合性是病理医师的**人工判定**，不是两段自由文本的"
                + "字符串比对——「乳腺癌」与「浸润性导管癌」文本完全不同却是符合的，"
                + "「良性病变」与「恶性肿瘤」只差几个字却是根本不符合。"
                + "做文本相似度会得出一个精确到小数点后两位的假数字，比不给更坏。"
                + "补法：加一列符合性判定 + 判定人 + 判定时刻，由病理医师在发报告时勾选。",
                "path_specimen.clinical_concordance（符合 / 部分符合 / 不符合）",
                "判定人与判定时刻",
                "不符合原因分类",
                "（clinical_diagnosis 已有，但它是待比对的一方，不是判定结果）");

        unavailable("CONSULT_CONCORDANCE", "外院会诊符合率",
                "缺数据源：specimen_type='CONSULT' 只标出「这是一份会诊标本」，"
                + "而**外院原诊断、会诊来源 / 送往医院、会诊方向、会诊结论与原诊断是否符合**四样全无字段。"
                + "既算不出符合率，也分不清「我院接收外院会诊」与「我院送外院会诊」两个方向——"
                + "两者的分子分母完全不同（前者衡量本院会诊质量，后者衡量本院疑难病例外送率），"
                + "混在一起出一个数字是无意义的。"
                + "补法：加会诊方向、外院原诊断、外院名称与符合性判定四列。",
                "path_specimen.consult_direction（IN 接收外院会诊 / OUT 送外院会诊）",
                "path_specimen.referring_hospital（外院名称）",
                "path_specimen.outside_diagnosis（外院原诊断）",
                "path_specimen.consult_concordance（符合性判定）");

        def("SLIDE_QUALITY", "染色切片优良率（按染色类型分）",
                "coalesce(path_slide.stained_at, created_at)",
                "按切片**染色时刻**归集（未录染色时刻的回落建档时刻，否则整片漏统计）",
                "「优良」口径歧义：本仓 quality 只有 GOOD / FAIR / POOR 三档，"
                + "而《2024 版》HE 与免疫组化染色切片优良率的「优良」通常指「优 + 良」。"
                + "故**两个口径并排给、不替调用方二选一**：good_rate_pct 只算 GOOD，"
                + "good_or_fair_rate_pct 算 GOOD + FAIR。两者差得越大，口径选择对结论的影响越大。"
                + "未评质量的切片**不进分母**（quality 可空），其占比见 grade_coverage_pct——"
                + "覆盖率低时该率只代表被评价的那一小部分，通常还是被挑出来评的那部分，会系统性偏高。");

        def("PROCESS_TAT", "各流转环节耗时（距签收的小时数中位数）",
                "path_process.occurred_at", "按流转节点**打点时刻**归集",
                "各环节耗时按 path_process 已登记的节点算，**没打点的环节不会显示为 0，"
                + "而是根本不出现在行里**——行的缺席本身就是「这个环节没在系统里打点」的信号，"
                + "补一行 0 会让人误以为该环节零耗时。"
                + "median_hours_from_receive 是该节点时刻距 received_at 的小时数中位数："
                + "RECEIVE 节点本身必然接近 0；received_at 为空的标本不进中位数，其条数见 no_receive_time 列。"
                + "同一标本同一节点可多次打点（如补取材后再次取材），events 是打点次数、specimens 是标本数。");

        def("WORKLOAD_REGISTER", "登记总量（按日，含拒收与加急构成）",
                "path_specimen.collected_at", ANCHOR_COLLECTED, WORKLOAD_NOTE
                + " 分母口径：registered 是当日登记的**标本条数**，不是申请单数——"
                + "多部位送检一份申请对应多条标本（V144 的 (来源, part_no) 唯一），"
                + "这正是病理科的真实工作量单位。rejected 一列是当日登记的标本里**后来**被拒收的条数"
                + "（按登记日归集，不是按拒收日），要看「本期拒了多少」请走 PROCESS_TAT 的 REJECT 节点。");

        def("WORKLOAD_BLOCK", "蜡块产出数（按日）",
                "coalesce(path_block.embedded_at, created_at)",
                "按蜡块**包埋时刻**归集（未录包埋时刻的回落建档时刻）", WORKLOAD_NOTE
                + " blocks_per_specimen 是当日蜡块数 / 当日涉及标本数，"
                + "**不是「每份标本平均取几块」**——同一标本的蜡块可能跨日建，两端分母不同。");

        def("WORKLOAD_SLIDE", "切片产出数（按日、按染色类型）",
                "coalesce(path_slide.stained_at, created_at)",
                "按切片**染色时刻**归集（未录染色时刻的回落建档时刻）", WORKLOAD_NOTE);

        def("WORKLOAD_REPORT", "报告签发量（首次报告 / 补充报告分列）",
                "path_specimen.report_issued_at 与 path_report.signed_at", ANCHOR_ISSUED,
                ISSUE_VS_DIAGNOSE_NOTE
                + " issued_reports（首次报告签发）与 supplement_reports（补充报告）**分列不合并**："
                + "补充报告是免疫组化结果回来后的追加意见，不代表一次新的诊断结案，"
                + "两列相加当「报告总数」会把工作量算高。diagnosed 一列是当日写完诊断的标本数"
                + "（按 diagnosed_at 落窗），与 issued_reports 不是同一批——两者的差就是卡在签名 / 签发环节的量。");

        def("WORKLOAD_PATHOLOGIST", "各病理医师工作量（写诊断 / 初签 / 复签 / 补充报告 / 取材 / 特检开单）",
                "各活动各自的时刻列", "**每一列按自己的时刻列分别落窗**，见 caveat", WORKLOAD_NOTE
                + " 各列的归集时刻各不相同：写诊断按 diagnosed_at、初签按 first_signed_at、"
                + "复签按 second_signed_at、补充报告按 coalesce(signed_at, created_at)、"
                + "取材按 path_process.occurred_at（node='GROSSING'）、特检开单按 ordered_at。"
                + "**同一份报告的初签与复签分属两人、各计一次，六列相加不等于报告份数**；"
                + "activities 是该人本期各类操作的次数合计，**不是「做了多少份报告」**，"
                + "更不能直接拿来排绩效——不同操作的耗时与难度差一个数量级，而本仓没有权重表。");

        def("WORKLOAD_TECH", "特检技术医嘱量（深切 / 重切 / 补取材 / 免疫组化 / 特殊染色 / 分子）",
                "path_tech_order.ordered_at", "按技术医嘱**开单时刻**归集", WORKLOAD_NOTE
                + " median_hours_to_done 是开单到完成的小时数中位数，只含 done_at 已录的行；"
                + "pending（仍为 ORDERED）的行不进中位数——把未完成的按「至今耗时」算进去会让中位数随时间漂移。");
    }

    // ===================== 统计时间窗 =====================

    private record Window(LocalDate from, LocalDate to) {
        String f() { return from.toString(); }
        String t() { return to.toString(); }
        Object[] args() { return new Object[]{from.toString(), to.toString()}; }
        long days() { return to.toEpochDay() - from.toEpochDay() + 1; }
    }

    private record WindowOrError(Window window, R<Object> error) {}

    /** 时间窗解析与校验；非法一律 5280（起止倒置 / 日期格式非法 / 跨度超限都属「这个时间段不成立」） */
    private WindowOrError parseWindow(String from, String to) {
        LocalDate t;
        LocalDate f;
        try {
            t = (to == null || to.isBlank()) ? BusinessDates.today() : LocalDate.parse(to.trim());
            f = (from == null || from.isBlank()) ? t.minusDays(29) : LocalDate.parse(from.trim());
        } catch (DateTimeParseException e) {
            return new WindowOrError(null, R.fail(5280, "统计时间段非法：起止日期须为 YYYY-MM-DD"));
        }
        if (f.isAfter(t)) {
            return new WindowOrError(null, R.fail(5280, "统计时间段非法：起始日期晚于截止日期"));
        }
        if (t.toEpochDay() - f.toEpochDay() + 1 > MAX_SPAN_DAYS) {
            return new WindowOrError(null,
                    R.fail(5280, "统计时间段非法：跨度超过 " + MAX_SPAN_DAYS + " 天，请分段统计"));
        }
        return new WindowOrError(new Window(f, t), null);
    }

    /** 常规报告时限（小时）。坏配置（0 / 负数 / 非数字）回落默认值：阈值为负会把全部报告判成超时 */
    private int routineHours() {
        int v = configReader.getInt("path.report.routine_hours", DEFAULT_ROUTINE_HOURS);
        return v > 0 ? v : DEFAULT_ROUTINE_HOURS;
    }

    /** 冰冻报告时限（分钟），同上 */
    private int frozenMinutes() {
        int v = configReader.getInt("path.report.frozen_minutes", DEFAULT_FROZEN_MINUTES);
        return v > 0 ? v : DEFAULT_FROZEN_MINUTES;
    }

    // ===================== 指标目录 =====================

    /** 指标目录（编码 → 名称 / 是否有数据源 / 归集时刻），供前端下拉与 5281 排错 */
    @GetMapping("/catalog")
    public R<Map<String, Object>> catalog() {
        var rows = new ArrayList<Map<String, Object>>(DEFS.size());
        for (Def d : DEFS.values()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("code", d.code());
            m.put("name", d.name());
            m.put("available", d.available());
            if (d.available()) {
                m.put("anchorField", d.anchorField());
                m.put("anchor", d.anchor());
            } else {
                m.put("unavailableReason", d.reason());
                m.put("missingFields", d.missingFields());
            }
            rows.add(m);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("indicators", rows);
        body.put("total", rows.size());
        body.put("availableCount", DEFS.values().stream().filter(Def::available).count());
        body.put("caveats", caveats());
        return R.ok(body);
    }

    private static List<String> caveats() {
        return List.of(WINDOW_ANCHOR_NOTE, DATA_CAVEAT, STATUS_NOTE, STANDARD_NOTE);
    }

    // ===================== 汇总端点 =====================

    /**
     * 病理质控指标汇总。
     *
     * @param from      起始日期 YYYY-MM-DD（缺省 = 截止日前推 29 天）
     * @param to        截止日期 YYYY-MM-DD（缺省 = 业务今天）；起止倒置 / 格式非法 / 跨度超限返 5280
     * @param indicator 指标编码；缺省返回全部。编码不存在返 5281
     */
    @GetMapping("/indicators")
    public R<Map<String, Object>> indicators(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String indicator) {
        var w = parseWindow(from, to);
        if (w.error() != null) return R.fail(w.error().getCode(), w.error().getMessage());
        List<Def> defs = select(indicator);
        if (defs == null) {
            return R.fail(5281, "指标编码不存在：" + indicator + "（可用编码见 GET /api/path-qc/catalog）");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("from", w.window().f());
        body.put("to", w.window().t());
        body.put("days", w.window().days());
        body.put("thresholds", thresholds());
        body.put("windowAnchorNote", WINDOW_ANCHOR_NOTE);
        body.put("caveats", caveats());
        body.put("coverage", coverage(w.window()));
        var list = new ArrayList<Map<String, Object>>(defs.size());
        for (Def d : defs) list.add(indicatorBody(d, w.window()));
        body.put("indicators", list);
        return R.ok(body);
    }

    private Map<String, Object> thresholds() {
        var m = new LinkedHashMap<String, Object>();
        m.put("routineHours", routineHours());
        m.put("routineHoursKey", "path.report.routine_hours");
        m.put("frozenMinutes", frozenMinutes());
        m.put("frozenMinutesKey", "path.report.frozen_minutes");
        m.put("holidayNote", HOLIDAY_NOTE);
        m.put("receiveThresholdNote",
                "标本接收「及时」的阈值本平台无配置键，故 SPECIMEN_RECEIVE 指标不给单一及时率，只给分档分布。");
        return m;
    }

    private List<Def> select(String indicator) {
        if (indicator == null || indicator.isBlank()) return new ArrayList<>(DEFS.values());
        Def d = DEFS.get(indicator.trim().toUpperCase(Locale.ROOT));
        return d == null ? null : List.of(d);
    }

    // ===================== 覆盖率（先看这一段再看指标值） =====================

    /**
     * 字段录入覆盖率。v48 之前的标本新字段全空且零回填，
     * 「报告及时率 100%」很可能只是「本时段仅 2 例录了签发时刻」。
     *
     * <p>四段各按自己的锚点落窗（标本按登记、蜡块按包埋、切片按染色、其余按各自事件时刻），
     * 与对应指标的归集口径一致——覆盖率与指标不同窗的话，拿覆盖率去解释指标就成了张冠李戴。
     */
    private Map<String, Object> coverage(Window w) {
        var m = new LinkedHashMap<String, Object>();

        var spec = new LinkedHashMap<String, Object>(one(q("""
                select count(*)                                                        as specimens,
                       count(*) filter (where s.order_id is not null)                  as outp_source,
                       count(*) filter (where s.inp_order_id is not null)              as inp_source,
                       count(*) filter (where s.specimen_type is not null)             as with_specimen_type,
                       count(*) filter (where s.path_no is not null)                   as with_path_no,
                       count(*) filter (where s.sampling_site is not null)             as with_sampling_site,
                       count(*) filter (where s.clinical_diagnosis is not null)        as with_clinical_diagnosis,
                       count(*) filter (where nullif(trim(s.fixative), '') is not null) as with_fixative,
                       count(*) filter (where s.fixed_at is not null)                  as with_fixed_at,
                       count(*) filter (where s.received_at is not null)               as with_received_at,
                       count(*) filter (where s.rejected_at is not null)               as rejected,
                       count(*) filter (where s.diagnosed_at is not null)              as with_diagnosed_at,
                       count(*) filter (where s.first_signed_at is not null)           as with_first_sign,
                       count(*) filter (where s.second_signed_at is not null)          as with_second_sign,
                       count(*) filter (where s.report_issued_at is not null)          as with_report_issued,
                       count(*) filter (where s.diagnosed_at is not null
                                          and s.report_issued_at is null)              as diagnosed_not_issued
                from path_specimen s
                where {wc}
                """), w.args()));
        spec.put("note", "分母 specimens 是该时段**登记**的全部标本（含历史无新字段的行、含拒收）；"
                + "其余各列是真正录了该字段的行数。diagnosed_not_issued 是「写了诊断但没走签发」的条数——"
                + "这批标本不在报告及时率的分母里，该列越大，及时率越不代表全院。");
        m.put("specimens", spec);

        var blocks = new LinkedHashMap<String, Object>(one(q("""
                select count(*)                                                   as blocks,
                       count(distinct b.specimen_id)                              as specimens,
                       count(*) filter (where b.embedded_at is not null)          as with_embedded_at,
                       count(*) filter (where b.dehydrate_batch is not null)      as with_dehydrate_batch
                from path_block b
                where {wb}
                """), w.args()));
        blocks.put("note", "按 coalesce(embedded_at, created_at) 落窗。with_embedded_at 低"
                + "说明包埋确认环节没在系统里打点，蜡块产出量仍可信（建档即产出），但包埋耗时算不出来。");
        m.put("blocks", blocks);

        var slides = new LinkedHashMap<String, Object>(one(q("""
                select count(*)                                              as slides,
                       count(distinct sl.block_id)                           as blocks,
                       count(*) filter (where sl.stained_at is not null)     as with_stained_at,
                       count(*) filter (where sl.quality is not null)        as with_quality
                from path_slide sl
                where {ws}
                """), w.args()));
        slides.put("note", "按 coalesce(stained_at, created_at) 落窗。with_quality 是染色切片优良率的**真实分母**——"
                + "它与 slides 差得越远，优良率越只代表被挑出来评价的那一小部分。");
        m.put("slides", slides);

        var others = new LinkedHashMap<String, Object>(one("""
                select (select count(*) from path_process pp
                         where pp.occurred_at >= ?::date and pp.occurred_at < ?::date + 1)      as process_events,
                       (select count(distinct pp.node) from path_process pp
                         where pp.occurred_at >= ?::date and pp.occurred_at < ?::date + 1)      as distinct_nodes,
                       (select count(*) from path_tech_order t
                         where t.ordered_at >= ?::date and t.ordered_at < ?::date + 1)          as tech_orders,
                       (select count(*) from path_report r
                         where coalesce(r.signed_at, r.created_at) >= ?::date
                           and coalesce(r.signed_at, r.created_at) < ?::date + 1)               as supplement_reports
                """, rep(w, 4)));
        others.put("note", "流转节点值域共 12 档（RECEIVE…SUPPLEMENT）；distinct_nodes 远小于 12 "
                + "说明多数环节没有打点，PROCESS_TAT 指标只覆盖打了点的那几档。");
        m.put("process", others);

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
            m.put("missingFields", d.missingFields());
            // 刻意不放 rows / summary 键：缺数据源的指标连空数组都不给，
            // 免得前端画出一张「全 0」的表，看的人把 0 当成结论。
            return m;
        }
        m.put("anchorField", d.anchorField());
        m.put("anchor", d.anchor());
        m.put("caveat", d.caveat());
        m.put("detailEndpoint",
                "/api/path-qc/detail?indicator=" + d.code() + "&from=" + w.f() + "&to=" + w.t());
        List<Map<String, Object>> rows = rowsOf(d.code(), w);
        boolean cut = rows.size() > ROW_LIMIT;
        m.put("rows", cut ? rows.subList(0, ROW_LIMIT) : rows);
        m.put("rowsTruncated", cut);
        if (cut) {
            m.put("rowsTruncatedNote",
                    "汇总行超过 " + ROW_LIMIT + " 行，本次只返回前 " + ROW_LIMIT + " 行，请缩小统计时间段");
        }
        Map<String, Object> summary = summaryOf(d.code(), w);
        if (summary != null) m.put("summary", summary);
        return m;
    }

    // ===================== 各指标汇总 SQL =====================

    private List<Map<String, Object>> rowsOf(String code, Window w) {
        return switch (code) {
            // args: from, to
            case "SPECIMEN_RECEIVE" -> query("""
                    select {typeName}                                                       as type_name,
                           coalesce(s.specimen_type, '（未填）')                             as specimen_type,
                           count(*)                                                          as submitted,
                           count(*) filter (where s.rejected_at is not null)                  as rejected,
                           count(*) filter (where s.received_at is not null)                  as received,
                           count(*) filter (where s.received_at is null
                                              and s.rejected_at is null)                      as not_received,
                           round(100.0 * count(*) filter (where s.received_at is not null)
                                 / nullif(count(*), 0), 2)                                    as received_rate_pct,
                           count(*) filter (where s.received_at is not null
                                              and s.received_at < s.collected_at)             as negative_interval,
                           count(*) filter (where s.received_at >= s.collected_at
                                              and s.received_at < s.collected_at
                                                                  + interval '30 minutes')    as within_30min,
                           count(*) filter (where s.received_at >= s.collected_at
                                                                  + interval '30 minutes'
                                              and s.received_at < s.collected_at
                                                                  + interval '2 hours')       as within_2h,
                           count(*) filter (where s.received_at >= s.collected_at
                                                                  + interval '2 hours'
                                              and s.received_at < s.collected_at
                                                                  + interval '24 hours')      as within_24h,
                           count(*) filter (where s.received_at >= s.collected_at
                                                                  + interval '24 hours')      as over_24h,
                           round((percentile_cont(0.5) within group (
                                     order by extract(epoch from (s.received_at - s.collected_at))
                                              / 60.0))::numeric, 1)                           as median_minutes,
                           round((percentile_cont(0.9) within group (
                                     order by extract(epoch from (s.received_at - s.collected_at))
                                              / 60.0))::numeric, 1)                           as p90_minutes
                    from path_specimen s
                    where {wc}
                    group by s.specimen_type
                    order by submitted desc, 2
                    """, w);
            // args: from, to
            case "FIXATION" -> query("""
                    select {typeName}                                                        as type_name,
                           coalesce(s.specimen_type, '（未填）')                              as specimen_type,
                           count(*)                                                           as submitted,
                           count(*) filter (where s.rejected_at is not null)                   as rejected,
                           count(*) filter (where nullif(trim(s.fixative), '') is not null)    as with_fixative,
                           count(*) filter (where s.fixed_at is not null)                      as with_fixed_at,
                           count(*) filter (where nullif(trim(s.fixative), '') is not null
                                              and s.fixed_at is not null)                      as fixation_recorded,
                           round(100.0 * count(*) filter (where nullif(trim(s.fixative), '') is not null
                                              and s.fixed_at is not null)
                                 / nullif(count(*), 0), 2)                          as fixation_recorded_rate_pct,
                           string_agg(distinct nullif(trim(s.fixative), ''), '、')             as fixatives
                    from path_specimen s
                    where {wc}
                    group by s.specimen_type
                    order by submitted desc, 2
                    """, w);
            case "REPORT_ROUTINE" -> reportRows(w, "ROUTINE", routineHours(), "hour", "3600.0", "hours");
            case "REPORT_FROZEN" -> reportRows(w, "FROZEN", frozenMinutes(), "minute", "60.0", "minutes");
            // args: from, to
            case "REPORT_DOUBLE_SIGN" -> query("""
                    select s.report_issued_at::date                                            as issue_day,
                           count(*)                                                             as issued,
                           count(*) filter (where s.first_signed_at is not null)                as with_first_sign,
                           count(*) filter (where s.second_signed_at is not null)               as with_second_sign,
                           count(*) filter (where s.first_signed_at is not null
                                              and s.second_signed_at is not null)               as double_signed,
                           round(100.0 * count(*) filter (where s.first_signed_at is not null
                                              and s.second_signed_at is not null)
                                 / nullif(count(*), 0), 2)                                as double_sign_rate_pct,
                           count(*) filter (where s.first_signed_at is null
                                              and s.second_signed_at is null)                   as no_sign,
                           count(*) filter (where s.first_signed_at is null
                                              and s.second_signed_at is not null)               as only_second_sign,
                           count(*) filter (where s.first_signer_id is not null
                                              and s.first_signer_id = s.second_signer_id) as same_person_double_sign
                    from path_specimen s
                    where {wi}
                    group by 1
                    order by 1 desc
                    """, w);
            // args: from, to
            case "SLIDE_QUALITY" -> query("""
                    select sl.stain_type,
                           case sl.stain_type when 'HE' then 'HE 染色' when 'IHC' then '免疫组化'
                                              when 'SPECIAL' then '特殊染色' else '分子病理' end as stain_name,
                           count(*)                                                        as slides,
                           count(*) filter (where sl.quality is not null)                   as graded,
                           count(*) filter (where sl.quality = 'GOOD')                      as good,
                           count(*) filter (where sl.quality = 'FAIR')                      as fair,
                           count(*) filter (where sl.quality = 'POOR')                      as poor,
                           round(100.0 * count(*) filter (where sl.quality = 'GOOD')
                                 / nullif(count(*) filter (where sl.quality is not null), 0), 2) as good_rate_pct,
                           round(100.0 * count(*) filter (where sl.quality in ('GOOD', 'FAIR'))
                                 / nullif(count(*) filter (where sl.quality is not null), 0), 2)
                                                                                     as good_or_fair_rate_pct,
                           round(100.0 * count(*) filter (where sl.quality is not null)
                                 / nullif(count(*), 0), 2)                                as grade_coverage_pct
                    from path_slide sl
                    where {ws}
                    group by sl.stain_type
                    order by case sl.stain_type when 'HE' then 1 when 'IHC' then 2
                                                when 'SPECIAL' then 3 else 4 end
                    """, w);
            // args: from, to
            case "PROCESS_TAT" -> jdbc.queryForList("""
                    select pp.node,
                           case pp.node
                               when 'RECEIVE'     then '核收'      when 'REJECT'      then '拒收'
                               when 'GROSSING'    then '取材'      when 'DEHYDRATE'   then '脱水'
                               when 'EMBED'       then '包埋'      when 'SECTION'     then '切片'
                               when 'STAIN'       then '染色'      when 'READ'        then '阅片'
                               when 'FIRST_SIGN'  then '初诊签名'  when 'SECOND_SIGN' then '复诊签名'
                               when 'ISSUE'       then '报告签发'  else '补充报告' end            as node_name,
                           count(*)                                                              as events,
                           count(distinct pp.specimen_id)                                        as specimens,
                           count(distinct pp.operator_id)                                        as operators,
                           round((percentile_cont(0.5) within group (
                                     order by extract(epoch from (pp.occurred_at - s.received_at))
                                              / 3600.0))::numeric, 1)                 as median_hours_from_receive,
                           count(*) filter (where s.received_at is null)                          as no_receive_time
                    from path_process pp
                    join path_specimen s on s.id = pp.specimen_id
                    where pp.occurred_at >= ?::date and pp.occurred_at < ?::date + 1
                    group by pp.node
                    order by case pp.node
                                 when 'RECEIVE' then 1 when 'REJECT' then 2 when 'GROSSING' then 3
                                 when 'DEHYDRATE' then 4 when 'EMBED' then 5 when 'SECTION' then 6
                                 when 'STAIN' then 7 when 'READ' then 8 when 'FIRST_SIGN' then 9
                                 when 'SECOND_SIGN' then 10 when 'ISSUE' then 11 else 12 end
                    """, w.args());
            // args: from, to
            case "WORKLOAD_REGISTER" -> query("""
                    select s.collected_at::date                                         as stat_day,
                           count(*)                                                      as registered,
                           count(*) filter (where s.urgent)                              as urgent,
                           count(*) filter (where s.rejected_at is not null)             as rejected,
                           count(*) filter (where s.order_id is not null)                as outp_source,
                           count(*) filter (where s.inp_order_id is not null)            as inp_source,
                           count(*) filter (where s.specimen_type = 'ROUTINE')           as routine,
                           count(*) filter (where s.specimen_type = 'FROZEN')            as frozen,
                           count(*) filter (where s.specimen_type = 'CYTOLOGY')          as cytology,
                           count(*) filter (where s.specimen_type = 'CONSULT')           as consult,
                           count(*) filter (where s.specimen_type = 'MOLECULAR')         as molecular,
                           count(*) filter (where s.specimen_type is null)               as type_unfilled
                    from path_specimen s
                    where {wc}
                    group by 1
                    order by 1 desc
                    """, w);
            // args: from, to
            case "WORKLOAD_BLOCK" -> query("""
                    select coalesce(b.embedded_at, b.created_at)::date                    as stat_day,
                           count(*)                                                       as blocks,
                           count(*) filter (where b.embedded_at is not null)              as embedded,
                           count(distinct b.specimen_id)                                  as specimens,
                           round(count(*)::numeric
                                 / nullif(count(distinct b.specimen_id), 0), 2)           as blocks_per_specimen,
                           count(distinct b.dehydrate_batch)                              as dehydrate_batches
                    from path_block b
                    where {wb}
                    group by 1
                    order by 1 desc
                    """, w);
            // args: from, to
            case "WORKLOAD_SLIDE" -> query("""
                    select coalesce(sl.stained_at, sl.created_at)::date                   as stat_day,
                           count(*)                                                       as slides,
                           count(*) filter (where sl.stained_at is not null)              as stained,
                           count(*) filter (where sl.stain_type = 'HE')                   as he,
                           count(*) filter (where sl.stain_type = 'IHC')                  as ihc,
                           count(*) filter (where sl.stain_type = 'SPECIAL')              as special_stain,
                           count(*) filter (where sl.stain_type = 'MOLECULAR')            as molecular,
                           count(distinct sl.block_id)                                    as blocks
                    from path_slide sl
                    where {ws}
                    group by 1
                    order by 1 desc
                    """, w);
            // args: from, to（generate_series 两个）
            case "WORKLOAD_REPORT" -> jdbc.queryForList("""
                    select d.day::date                                                        as stat_day,
                           (select count(*) from path_specimen s
                             where s.report_issued_at >= d.day
                               and s.report_issued_at <  d.day + interval '1 day')            as issued_reports,
                           (select count(*) from path_specimen s
                             where s.diagnosed_at >= d.day
                               and s.diagnosed_at <  d.day + interval '1 day')                as diagnosed,
                           (select count(*) from path_report r
                             where coalesce(r.signed_at, r.created_at) >= d.day
                               and coalesce(r.signed_at, r.created_at) <  d.day
                                                                          + interval '1 day') as supplement_reports
                    from generate_series(?::date, ?::date, interval '1 day') as d(day)
                    order by d.day desc
                    """, w.args());
            case "WORKLOAD_PATHOLOGIST" -> pathologistRows(w);
            // args: from, to
            case "WORKLOAD_TECH" -> jdbc.queryForList("""
                    select t.tech_type,
                           case t.tech_type
                               when 'DEEP_CUT'      then '深切'     when 'RECUT'     then '重切'
                               when 'RESAMPLE'      then '补取材'   when 'IHC'       then '免疫组化'
                               when 'SPECIAL_STAIN' then '特殊染色' else '分子病理' end        as tech_name,
                           count(*)                                                           as ordered,
                           count(*) filter (where t.status = 'DONE')                          as done,
                           count(*) filter (where t.status = 'ORDERED')                       as pending,
                           count(*) filter (where t.status = 'CANCELLED')                     as cancelled,
                           count(distinct t.specimen_id)                                      as specimens,
                           round((percentile_cont(0.5) within group (
                                     order by extract(epoch from (t.done_at - t.ordered_at))
                                              / 3600.0))::numeric, 1)                    as median_hours_to_done
                    from path_tech_order t
                    where t.ordered_at >= ?::date and t.ordered_at < ?::date + 1
                    group by t.tech_type
                    order by ordered desc, 1
                    """, w.args());
            default -> List.of();
        };
    }

    /**
     * 报告及时率（常规 / 冰冻同形，只差时限单位）。
     *
     * <p>阈值先在 CTE 里判成一个 boolean，避免同一个 {@code ?} 在 SQL 里出现四次、
     * 参数顺序一错就悄悄算错（占位符参数顺序：阈值 → 标本类别 → from → to）。
     */
    private List<Map<String, Object>> reportRows(Window w, String type, int threshold,
                                                 String ivl, String div, String sfx) {
        String sql = """
                with base as (
                    select s.id, s.report_issued_at, s.received_at, s.urgent,
                           (s.received_at is not null
                             and s.report_issued_at <= s.received_at
                                                       + (?::int * interval '1 {ivl}'))        as timely,
                           (s.received_at is not null)                                          as judgeable,
                           exists (select 1 from path_tech_order t where t.specimen_id = s.id)   as has_tech,
                           extract(epoch from (s.report_issued_at - s.received_at)) / {div}      as tat
                    from path_specimen s
                    where s.specimen_type = ? and {wi}
                )
                select report_issued_at::date                                           as issue_day,
                       count(*)                                                          as issued,
                       count(*) filter (where timely)                                    as timely,
                       count(*) filter (where judgeable and not timely)                  as overdue,
                       count(*) filter (where not judgeable)                             as unjudgeable,
                       round(100.0 * count(*) filter (where timely)
                             / nullif(count(*) filter (where judgeable), 0), 2)          as timely_rate_pct,
                       round(avg(tat)::numeric, 1)                                       as avg_tat_{sfx},
                       round((percentile_cont(0.5) within group (order by tat))::numeric, 1) as median_tat_{sfx},
                       count(*) filter (where has_tech)                                  as with_tech_order,
                       count(*) filter (where urgent)                                    as urgent_cases
                from base
                group by 1
                order by 1 desc
                """
                // {ivl} 必须先于 {div}/{sfx} 之外的短占位符替换，三者互不为前缀，顺序无歧义
                .replace("{ivl}", ivl).replace("{div}", div).replace("{sfx}", sfx);
        return jdbc.queryForList(q(sql), threshold, type, w.f(), w.t());
    }

    /**
     * 各病理医师工作量：六类活动<b>各按自己的时刻列</b>落窗后并起来再按人聚合。
     *
     * <p>参数 12 个（6 组 from/to），顺序与 union 分支的书写顺序严格一致。
     */
    private List<Map<String, Object>> pathologistRows(Window w) {
        return jdbc.queryForList("""
                with acts as (
                    select s.pathologist_id as user_id, 'DIAGNOSE' as kind
                    from path_specimen s
                    where s.pathologist_id is not null
                      and s.diagnosed_at >= ?::date and s.diagnosed_at < ?::date + 1
                    union all
                    select s.first_signer_id, 'FIRST_SIGN'
                    from path_specimen s
                    where s.first_signer_id is not null
                      and s.first_signed_at >= ?::date and s.first_signed_at < ?::date + 1
                    union all
                    select s.second_signer_id, 'SECOND_SIGN'
                    from path_specimen s
                    where s.second_signer_id is not null
                      and s.second_signed_at >= ?::date and s.second_signed_at < ?::date + 1
                    union all
                    select r.signer_id, 'SUPPLEMENT'
                    from path_report r
                    where r.signer_id is not null
                      and coalesce(r.signed_at, r.created_at) >= ?::date
                      and coalesce(r.signed_at, r.created_at) <  ?::date + 1
                    union all
                    select pp.operator_id, 'GROSSING'
                    from path_process pp
                    where pp.operator_id is not null and pp.node = 'GROSSING'
                      and pp.occurred_at >= ?::date and pp.occurred_at < ?::date + 1
                    union all
                    select t.ordered_by, 'TECH_ORDER'
                    from path_tech_order t
                    where t.ordered_by is not null
                      and t.ordered_at >= ?::date and t.ordered_at < ?::date + 1
                )
                select a.user_id,
                       coalesce(u.real_name, '（已删除用户 ' || a.user_id || '）')      as user_name,
                       coalesce(d.name, '（未配科室）')                                  as dept_name,
                       count(*) filter (where a.kind = 'DIAGNOSE')                       as diagnosed,
                       count(*) filter (where a.kind = 'FIRST_SIGN')                     as first_signed,
                       count(*) filter (where a.kind = 'SECOND_SIGN')                    as second_signed,
                       count(*) filter (where a.kind = 'SUPPLEMENT')                     as supplement_reports,
                       count(*) filter (where a.kind = 'GROSSING')                       as grossing,
                       count(*) filter (where a.kind = 'TECH_ORDER')                     as tech_orders,
                       count(*)                                                          as activities
                from acts a
                left join sys_user u on u.id = a.user_id
                left join sys_dept d on d.id = u.dept_id
                group by a.user_id, u.real_name, d.name
                order by activities desc, a.user_id
                """, rep(w, 6));
    }

    /** 合计行：只给能一句话说清、且不会被误读成全院口径的指标配 */
    private Map<String, Object> summaryOf(String code, Window w) {
        return switch (code) {
            case "SPECIMEN_RECEIVE" -> one(q("""
                    select count(*)                                                        as submitted,
                           count(*) filter (where s.rejected_at is not null)                as rejected,
                           count(*) filter (where s.received_at is not null)                as received,
                           count(*) filter (where s.received_at is null
                                              and s.rejected_at is null)                    as not_received,
                           round(100.0 * count(*) filter (where s.received_at is not null)
                                 / nullif(count(*), 0), 2)                                  as received_rate_pct,
                           count(*) filter (where s.received_at is not null
                                              and s.received_at < s.collected_at)           as negative_interval,
                           round((percentile_cont(0.5) within group (
                                     order by extract(epoch from (s.received_at - s.collected_at))
                                              / 60.0))::numeric, 1)                         as median_minutes,
                           round((percentile_cont(0.9) within group (
                                     order by extract(epoch from (s.received_at - s.collected_at))
                                              / 60.0))::numeric, 1)                         as p90_minutes
                    from path_specimen s
                    where {wc}
                    """), w.args());
            case "FIXATION" -> one(q("""
                    select count(*)                                                         as submitted,
                           count(*) filter (where s.rejected_at is not null)                 as rejected,
                           count(*) filter (where nullif(trim(s.fixative), '') is not null
                                              and s.fixed_at is not null)                    as fixation_recorded,
                           round(100.0 * count(*) filter (where nullif(trim(s.fixative), '') is not null
                                              and s.fixed_at is not null)
                                 / nullif(count(*), 0), 2)                         as fixation_recorded_rate_pct
                    from path_specimen s
                    where {wc}
                    """), w.args());
            case "REPORT_ROUTINE" -> reportSummary(w, "ROUTINE", routineHours(), "hour", "3600.0", "hours");
            case "REPORT_FROZEN" -> reportSummary(w, "FROZEN", frozenMinutes(), "minute", "60.0", "minutes");
            case "REPORT_DOUBLE_SIGN" -> one(q("""
                    select count(*)                                                          as issued,
                           count(*) filter (where s.first_signed_at is not null
                                              and s.second_signed_at is not null)            as double_signed,
                           round(100.0 * count(*) filter (where s.first_signed_at is not null
                                              and s.second_signed_at is not null)
                                 / nullif(count(*), 0), 2)                             as double_sign_rate_pct,
                           count(*) filter (where s.first_signed_at is null
                                              and s.second_signed_at is null)                as no_sign,
                           count(*) filter (where s.first_signer_id is not null
                                              and s.first_signer_id = s.second_signer_id) as same_person_double_sign
                    from path_specimen s
                    where {wi}
                    """), w.args());
            case "SLIDE_QUALITY" -> one(q("""
                    select count(*)                                                       as slides,
                           count(*) filter (where sl.quality is not null)                  as graded,
                           count(*) filter (where sl.quality = 'GOOD')                     as good,
                           count(*) filter (where sl.quality = 'FAIR')                     as fair,
                           count(*) filter (where sl.quality = 'POOR')                     as poor,
                           round(100.0 * count(*) filter (where sl.quality = 'GOOD')
                                 / nullif(count(*) filter (where sl.quality is not null), 0), 2) as good_rate_pct,
                           round(100.0 * count(*) filter (where sl.quality in ('GOOD', 'FAIR'))
                                 / nullif(count(*) filter (where sl.quality is not null), 0), 2)
                                                                                       as good_or_fair_rate_pct,
                           round(100.0 * count(*) filter (where sl.quality is not null)
                                 / nullif(count(*), 0), 2)                                as grade_coverage_pct
                    from path_slide sl
                    where {ws}
                    """), w.args());
            case "WORKLOAD_REGISTER" -> one(q("""
                    select count(*)                                                as registered,
                           count(*) filter (where s.urgent)                        as urgent,
                           count(*) filter (where s.rejected_at is not null)       as rejected,
                           count(*) filter (where s.order_id is not null)          as outp_source,
                           count(*) filter (where s.inp_order_id is not null)      as inp_source
                    from path_specimen s
                    where {wc}
                    """), w.args());
            case "WORKLOAD_BLOCK" -> one(q("""
                    select count(*)                                                as blocks,
                           count(*) filter (where b.embedded_at is not null)       as embedded,
                           count(distinct b.specimen_id)                           as specimens,
                           round(count(*)::numeric
                                 / nullif(count(distinct b.specimen_id), 0), 2)    as blocks_per_specimen
                    from path_block b
                    where {wb}
                    """), w.args());
            case "WORKLOAD_SLIDE" -> one(q("""
                    select count(*)                                                as slides,
                           count(*) filter (where sl.stained_at is not null)       as stained,
                           count(*) filter (where sl.stain_type = 'HE')            as he,
                           count(*) filter (where sl.stain_type = 'IHC')           as ihc,
                           count(*) filter (where sl.stain_type = 'SPECIAL')       as special_stain,
                           count(*) filter (where sl.stain_type = 'MOLECULAR')     as molecular
                    from path_slide sl
                    where {ws}
                    """), w.args());
            // args：{wi} 两个 + diagnosed 两个 + 补充报告两个
            case "WORKLOAD_REPORT" -> one(q("""
                    select (select count(*) from path_specimen s where {wi})                    as issued_reports,
                           (select count(*) from path_specimen s
                             where s.diagnosed_at >= ?::date and s.diagnosed_at < ?::date + 1)  as diagnosed,
                           (select count(*) from path_report r
                             where coalesce(r.signed_at, r.created_at) >= ?::date
                               and coalesce(r.signed_at, r.created_at) < ?::date + 1)      as supplement_reports
                    """), rep(w, 3));
            case "WORKLOAD_TECH" -> one("""
                    select count(*)                                          as ordered,
                           count(*) filter (where t.status = 'DONE')         as done,
                           count(*) filter (where t.status = 'ORDERED')      as pending,
                           count(*) filter (where t.status = 'CANCELLED')    as cancelled,
                           count(distinct t.specimen_id)                     as specimens
                    from path_tech_order t
                    where t.ordered_at >= ?::date and t.ordered_at < ?::date + 1
                    """, w.args());
            default -> null;
        };
    }

    private Map<String, Object> reportSummary(Window w, String type, int threshold,
                                              String ivl, String div, String sfx) {
        String sql = """
                with base as (
                    select (s.received_at is not null
                             and s.report_issued_at <= s.received_at
                                                       + (?::int * interval '1 {ivl}'))    as timely,
                           (s.received_at is not null)                                      as judgeable,
                           extract(epoch from (s.report_issued_at - s.received_at)) / {div} as tat
                    from path_specimen s
                    where s.specimen_type = ? and {wi}
                )
                select count(*)                                                      as issued,
                       count(*) filter (where timely)                                as timely,
                       count(*) filter (where judgeable and not timely)              as overdue,
                       count(*) filter (where not judgeable)                         as unjudgeable,
                       round(100.0 * count(*) filter (where timely)
                             / nullif(count(*) filter (where judgeable), 0), 2)      as timely_rate_pct,
                       round(avg(tat)::numeric, 1)                                   as avg_tat_{sfx},
                       round((percentile_cont(0.5) within group (order by tat))::numeric, 1) as median_tat_{sfx}
                from base
                """.replace("{ivl}", ivl).replace("{div}", div).replace("{sfx}", sfx);
        return one(q(sql), threshold, type, w.f(), w.t());
    }

    // ===================== 穿透明细（防「指标算得出但对不上账」） =====================

    /**
     * 指标取值明细穿透：每一行都能对到具体标本、具体人。
     *
     * <p>与 {@link #indicators} <b>同时间窗、同归集锚点、同过滤条件</b>——明细对不上汇总时，
     * 这套指标就失去了管理价值，所以两处共用同一批 SQL 片段常量与同一份阈值读取逻辑。
     *
     * <p>硬上限 {@value #DETAIL_LIMIT} 条 + {@code truncated} 标记，<b>不做翻页</b>：
     * 命中超限说明时间窗太宽，应缩窗而不是翻页。调用方显式索要超过上限的条数时返 5282，
     * <b>不静默截断成「看着像全量」的结果</b>。
     *
     * @param limit 可选，调用方自定条数上限；超过 {@value #DETAIL_LIMIT} 返 5282
     */
    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam(required = false) String indicator,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Integer limit) {
        Def d = DEFS.get(indicator == null ? "" : indicator.trim().toUpperCase(Locale.ROOT));
        if (d == null) {
            return R.fail(5281, "指标编码不存在：" + indicator + "（可用编码见 GET /api/path-qc/catalog）");
        }
        if (limit != null && limit > DETAIL_LIMIT) {
            return R.fail(5282, "穿透明细条数超限：最多 " + DETAIL_LIMIT + " 条，请缩小统计时间段后再穿透");
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
            body.put("missingFields", d.missingFields());
            body.put("items", List.of());
            body.put("truncated", false);
            return R.ok(body);
        }
        List<Map<String, Object>> rows = detailRows(d.code(), w.window());
        boolean truncated = rows.size() > DETAIL_LIMIT;
        body.put("items", truncated ? rows.subList(0, DETAIL_LIMIT) : rows);
        body.put("truncated", truncated);
        body.put("anchorField", d.anchorField());
        body.put("anchor", d.anchor());
        body.put("caveat", d.caveat());
        body.put("windowAnchorNote", WINDOW_ANCHOR_NOTE);
        return R.ok(body);
    }

    private List<Map<String, Object>> detailRows(String code, Window w) {
        return switch (code) {
            case "SPECIMEN_RECEIVE" -> jdbc.queryForList(q("""
                    select x.*,
                           round((extract(epoch from (x.received_at - x.collected_at)) / 60.0)::numeric, 1)
                                                                                          as receive_minutes,
                           case when x.received_at is null and x.rejected_at is not null then '已拒收（未签收）'
                                when x.received_at is null                               then '未签收'
                                when x.received_at <  x.collected_at                     then '签收早于登记（补录）'
                                when x.received_at <  x.collected_at + interval '30 minutes' then '30 分钟内'
                                when x.received_at <  x.collected_at + interval '2 hours'    then '2 小时内'
                                when x.received_at <  x.collected_at + interval '24 hours'   then '24 小时内'
                                else '超过 24 小时' end                                    as receive_band
                    from ( {spec} where {wc} ) x
                    order by x.collected_at desc, x.specimen_id desc
                    {cap}
                    """), w.args());
            case "FIXATION" -> jdbc.queryForList(q("""
                    select x.*,
                           case when nullif(trim(x.fixative), '') is not null and x.fixed_at is not null
                                     then '固定信息完整'
                                when nullif(trim(x.fixative), '') is null and x.fixed_at is null
                                     then '固定液与固定时刻均未录'
                                when nullif(trim(x.fixative), '') is null then '固定液未录'
                                else '固定时刻未录' end                                     as fixation_status,
                           round((extract(epoch from (x.fixed_at - x.collected_at)) / 60.0)::numeric, 1)
                                                                                            as minutes_to_fixation
                    from ( {spec} where {wc} ) x
                    order by x.collected_at desc, x.specimen_id desc
                    {cap}
                    """), w.args());
            case "REPORT_ROUTINE" -> reportDetail(w, "ROUTINE", routineHours(), "hour", "3600.0", "hours");
            case "REPORT_FROZEN" -> reportDetail(w, "FROZEN", frozenMinutes(), "minute", "60.0", "minutes");
            case "REPORT_DOUBLE_SIGN" -> jdbc.queryForList(q("""
                    select x.*,
                           case when x.first_signed_at is not null and x.second_signed_at is not null
                                     then '已双签'
                                when x.first_signed_at is not null  then '仅初诊签名'
                                when x.second_signed_at is not null then '仅复诊签名（异常）'
                                else '未签名即签发' end                                     as sign_status
                    from ( {spec} where {wi} ) x
                    order by x.report_issued_at desc, x.specimen_id desc
                    {cap}
                    """), w.args());
            case "SLIDE_QUALITY", "WORKLOAD_SLIDE" -> jdbc.queryForList(q("""
                    select sl.id                                as slide_id,
                           sl.slide_code, sl.slide_no, sl.stain_type, sl.stain_item, sl.quality,
                           sl.stained_at, sl.created_at,
                           b.block_code, b.block_no,
                           s.id                                 as specimen_id,
                           s.path_no, s.barcode,
                           coalesce(s.specimen_type, '（未填）') as specimen_type,
                           {patName}                            as patient_name,
                           u.real_name                          as stained_by_name
                    from path_slide sl
                    join path_block b on b.id = sl.block_id
                    join path_specimen s on s.id = b.specimen_id
                    left join sys_user u on u.id = sl.stained_by
                    {pat}
                    where {ws}
                    order by coalesce(sl.stained_at, sl.created_at) desc, sl.id desc
                    {cap}
                    """), w.args());
            case "PROCESS_TAT" -> jdbc.queryForList(q("""
                    select pp.id                                 as process_id,
                           pp.node, pp.occurred_at, pp.remark,
                           u.real_name                           as operator_name,
                           s.id                                  as specimen_id,
                           s.path_no, s.barcode,
                           coalesce(s.specimen_type, '（未填）')  as specimen_type,
                           s.received_at,
                           round((extract(epoch from (pp.occurred_at - s.received_at)) / 3600.0)::numeric, 1)
                                                                 as hours_from_receive,
                           {patName}                             as patient_name
                    from path_process pp
                    join path_specimen s on s.id = pp.specimen_id
                    left join sys_user u on u.id = pp.operator_id
                    {pat}
                    where pp.occurred_at >= ?::date and pp.occurred_at < ?::date + 1
                    order by pp.occurred_at desc, pp.id desc
                    {cap}
                    """), w.args());
            case "WORKLOAD_REGISTER" -> jdbc.queryForList(q("""
                    select x.* from ( {spec} where {wc} ) x
                    order by x.collected_at desc, x.specimen_id desc
                    {cap}
                    """), w.args());
            case "WORKLOAD_BLOCK" -> jdbc.queryForList(q("""
                    select b.id                                 as block_id,
                           b.block_code, b.block_no, b.tissue_desc, b.dehydrate_batch,
                           b.embedded_at, b.created_at,
                           u.real_name                          as embedded_by_name,
                           s.id                                 as specimen_id,
                           s.path_no, s.barcode,
                           coalesce(s.specimen_type, '（未填）') as specimen_type,
                           {patName}                            as patient_name
                    from path_block b
                    join path_specimen s on s.id = b.specimen_id
                    left join sys_user u on u.id = b.embedded_by
                    {pat}
                    where {wb}
                    order by coalesce(b.embedded_at, b.created_at) desc, b.id desc
                    {cap}
                    """), w.args());
            // args：首次报告 {wi} 两个 + 补充报告两个
            case "WORKLOAD_REPORT" -> jdbc.queryForList(q("""
                    select '首次报告'                            as report_kind,
                           s.id                                  as specimen_id,
                           s.path_no, s.barcode,
                           coalesce(s.specimen_type, '（未填）')  as specimen_type,
                           null::smallint                        as seq_no,
                           s.report_issued_at                    as report_time,
                           coalesce(su2.real_name, su1.real_name, pu.real_name) as signer_name,
                           null::varchar                         as reason,
                           {patName}                             as patient_name
                    from path_specimen s
                    left join sys_user pu  on pu.id  = s.pathologist_id
                    left join sys_user su1 on su1.id = s.first_signer_id
                    left join sys_user su2 on su2.id = s.second_signer_id
                    {pat}
                    where {wi}
                    union all
                    select '补充报告',
                           s.id,
                           s.path_no, s.barcode,
                           coalesce(s.specimen_type, '（未填）'),
                           r.seq_no,
                           coalesce(r.signed_at, r.created_at),
                           ru.real_name,
                           r.reason,
                           {patName}
                    from path_report r
                    join path_specimen s on s.id = r.specimen_id
                    left join sys_user ru on ru.id = r.signer_id
                    {pat}
                    where coalesce(r.signed_at, r.created_at) >= ?::date
                      and coalesce(r.signed_at, r.created_at) <  ?::date + 1
                    order by report_time desc
                    {cap}
                    """), rep(w, 2));
            case "WORKLOAD_PATHOLOGIST" -> pathologistDetail(w);
            case "WORKLOAD_TECH" -> jdbc.queryForList(q("""
                    select t.id                                  as tech_order_id,
                           t.tech_type, t.tech_item, t.reason, t.status,
                           t.ordered_at, t.done_at,
                           ou.real_name                          as ordered_by_name,
                           du.real_name                          as done_by_name,
                           b.block_code,
                           s.id                                  as specimen_id,
                           s.path_no, s.barcode,
                           {patName}                             as patient_name
                    from path_tech_order t
                    join path_specimen s on s.id = t.specimen_id
                    left join path_block b on b.id = t.block_id
                    left join sys_user ou on ou.id = t.ordered_by
                    left join sys_user du on du.id = t.done_by
                    {pat}
                    where t.ordered_at >= ?::date and t.ordered_at < ?::date + 1
                    order by t.ordered_at desc, t.id desc
                    {cap}
                    """), w.args());
            default -> List.of();
        };
    }

    /** 报告及时率穿透（参数顺序：阈值 → 标本类别 → from → to；阈值在外层 select，文本上先于子查询） */
    private List<Map<String, Object>> reportDetail(Window w, String type, int threshold,
                                                   String ivl, String div, String sfx) {
        String sql = """
                select x.*,
                       round((extract(epoch from (x.report_issued_at - x.received_at)) / {div})::numeric, 1)
                                                                                        as tat_{sfx},
                       case when x.received_at is null then '无法判定（无签收时刻）'
                            when x.report_issued_at <= x.received_at
                                                       + (?::int * interval '1 {ivl}') then '及时'
                            else '超时' end                                              as judgement
                from ( {spec} where s.specimen_type = ? and {wi} ) x
                order by x.report_issued_at desc, x.specimen_id desc
                {cap}
                """.replace("{ivl}", ivl).replace("{div}", div).replace("{sfx}", sfx);
        return jdbc.queryForList(q(sql), threshold, type, w.f(), w.t());
    }

    /** 各病理医师工作量穿透：六类活动逐条列出，与汇总同窗同口径（12 个参数，顺序同 union 分支） */
    private List<Map<String, Object>> pathologistDetail(Window w) {
        return jdbc.queryForList(q("""
                select '写诊断' as activity, coalesce(u.real_name, '（已删除用户）') as user_name,
                       s.diagnosed_at as act_time, s.id as specimen_id, s.path_no, s.barcode,
                       coalesce(s.specimen_type, '（未填）') as specimen_type, {patName} as patient_name
                from path_specimen s
                left join sys_user u on u.id = s.pathologist_id
                {pat}
                where s.pathologist_id is not null
                  and s.diagnosed_at >= ?::date and s.diagnosed_at < ?::date + 1
                union all
                select '初诊签名', coalesce(u.real_name, '（已删除用户）'), s.first_signed_at, s.id,
                       s.path_no, s.barcode, coalesce(s.specimen_type, '（未填）'), {patName}
                from path_specimen s
                left join sys_user u on u.id = s.first_signer_id
                {pat}
                where s.first_signer_id is not null
                  and s.first_signed_at >= ?::date and s.first_signed_at < ?::date + 1
                union all
                select '复诊签名', coalesce(u.real_name, '（已删除用户）'), s.second_signed_at, s.id,
                       s.path_no, s.barcode, coalesce(s.specimen_type, '（未填）'), {patName}
                from path_specimen s
                left join sys_user u on u.id = s.second_signer_id
                {pat}
                where s.second_signer_id is not null
                  and s.second_signed_at >= ?::date and s.second_signed_at < ?::date + 1
                union all
                select '补充报告', coalesce(u.real_name, '（已删除用户）'),
                       coalesce(r.signed_at, r.created_at), s.id,
                       s.path_no, s.barcode, coalesce(s.specimen_type, '（未填）'), {patName}
                from path_report r
                join path_specimen s on s.id = r.specimen_id
                left join sys_user u on u.id = r.signer_id
                {pat}
                where r.signer_id is not null
                  and coalesce(r.signed_at, r.created_at) >= ?::date
                  and coalesce(r.signed_at, r.created_at) <  ?::date + 1
                union all
                select '取材', coalesce(u.real_name, '（已删除用户）'), pp.occurred_at, s.id,
                       s.path_no, s.barcode, coalesce(s.specimen_type, '（未填）'), {patName}
                from path_process pp
                join path_specimen s on s.id = pp.specimen_id
                left join sys_user u on u.id = pp.operator_id
                {pat}
                where pp.operator_id is not null and pp.node = 'GROSSING'
                  and pp.occurred_at >= ?::date and pp.occurred_at < ?::date + 1
                union all
                select '特检开单', coalesce(u.real_name, '（已删除用户）'), t.ordered_at, s.id,
                       s.path_no, s.barcode, coalesce(s.specimen_type, '（未填）'), {patName}
                from path_tech_order t
                join path_specimen s on s.id = t.specimen_id
                left join sys_user u on u.id = t.ordered_by
                {pat}
                where t.ordered_by is not null
                  and t.ordered_at >= ?::date and t.ordered_at < ?::date + 1
                order by act_time desc
                {cap}
                """), rep(w, 6));
    }

    // ===================== CSV 导出（口径 caveat 随文件走，不只写在页面上） =====================

    /**
     * 指标汇总 CSV（与 {@link #indicators} 同 SQL 同口径）。
     *
     * <p>各指标列结构不同，故 <b>CSV 逐指标导出</b>，{@code indicator} 必填。CSV 端点返回的是文本流，
     * 没有 {@code {code,message}} 载体，故编码不存在 / 时间段非法时导出一份<b>只含错误码与说明的
     * CSV</b>——绝不导出一张空表让人误以为「本时段无数据」。口径以页脚形式随表导出。
     */
    @GetMapping(value = "/indicators.csv", produces = "text/csv;charset=UTF-8")
    public String indicatorsCsv(@RequestParam(required = false) String indicator,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        Def d = DEFS.get(indicator == null ? "" : indicator.trim().toUpperCase(Locale.ROOT));
        if (d == null) return errCsv(5281, "指标编码不存在：" + indicator);
        var w = parseWindow(from, to);
        if (w.error() != null) return errCsv(w.error().getCode(), w.error().getMessage());
        if (!d.available()) return unavailableCsv(d, w.window());
        List<Map<String, Object>> rows = rowsOf(d.code(), w.window());
        boolean cut = rows.size() > ROW_LIMIT;
        return toCsv(d, w.window(), "指标汇总", cut ? rows.subList(0, ROW_LIMIT) : rows, cut, ROW_LIMIT);
    }

    /** 穿透明细 CSV（与 {@link #detail} 同 SQL 同口径、同 200 条上限；超限在页脚明写截断） */
    @GetMapping(value = "/detail.csv", produces = "text/csv;charset=UTF-8")
    public String detailCsv(@RequestParam(required = false) String indicator,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to) {
        Def d = DEFS.get(indicator == null ? "" : indicator.trim().toUpperCase(Locale.ROOT));
        if (d == null) return errCsv(5281, "指标编码不存在：" + indicator);
        var w = parseWindow(from, to);
        if (w.error() != null) return errCsv(w.error().getCode(), w.error().getMessage());
        if (!d.available()) return unavailableCsv(d, w.window());
        List<Map<String, Object>> rows = detailRows(d.code(), w.window());
        boolean truncated = rows.size() > DETAIL_LIMIT;
        return toCsv(d, w.window(), "取值明细",
                truncated ? rows.subList(0, DETAIL_LIMIT) : rows, truncated, DETAIL_LIMIT);
    }

    private String errCsv(int code, String message) {
        return "﻿错误码,说明\n" + csv(code) + "," + csv(message) + "\n";
    }

    /** 缺数据源的指标导出的是「为什么没有」与「缺哪几个字段」，不是一张全 0 的表 */
    private String unavailableCsv(Def d, Window w) {
        var sb = new StringBuilder("﻿指标编码,指标名称,统计区间,是否有数据源\n");
        sb.append("%s,%s,%s,%s\n".formatted(csv(d.code()), csv(d.name()),
                csv(w.f() + " 至 " + w.t()), csv("否（缺数据源，本平台不给近似值）")));
        sb.append('\n').append(csv("缺数据源原因：" + d.reason())).append('\n');
        for (String f : d.missingFields()) {
            sb.append(csv("缺失字段：" + f)).append('\n');
        }
        for (String c : caveats()) sb.append(csv("口径：" + c)).append('\n');
        return sb.toString();
    }

    private String toCsv(Def d, Window w, String kind, List<Map<String, Object>> rows,
                         boolean truncated, int limit) {
        var sb = new StringBuilder("﻿指标编码,指标名称,报表,统计区间,归集时刻\n");
        sb.append("%s,%s,%s,%s,%s\n\n".formatted(csv(d.code()), csv(d.name()), csv(kind),
                csv(w.f() + " 至 " + w.t()), csv(d.anchorField())));
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
            sb.append(csv("注意：命中超过 " + limit + " 行，本文件只含前 " + limit
                    + " 行，请缩小统计区间后重新导出（不做翻页）")).append('\n');
        }
        // 口径页脚：导出的表格一转手就脱离页面，caveat 必须跟着文件走
        sb.append(csv("归集口径：" + d.anchor())).append('\n');
        if (d.caveat() != null) sb.append(csv("口径：" + d.caveat())).append('\n');
        for (String c : caveats()) sb.append(csv("口径：" + c)).append('\n');
        sb.append(csv("阈值：常规报告 " + routineHours() + " 小时（path.report.routine_hours）／冰冻 "
                + frozenMinutes() + " 分钟（path.report.frozen_minutes）")).append('\n');
        return sb.toString();
    }

    /** 列名中文化（CSV 表头用；未登记的列名原样输出，不猜也不隐藏） */
    private static String zh(String col) {
        return switch (col) {
            // 通用
            case "stat_day" -> "日期";
            case "issue_day" -> "签发日期";
            case "type_name" -> "标本类别";
            case "specimen_type" -> "类别编码";
            case "specimen_id" -> "标本ID";
            case "path_no" -> "病理号";
            case "barcode" -> "条码";
            case "part_no" -> "部位序号";
            case "source" -> "来源(OUTP门诊/INP住院)";
            case "patient_no" -> "患者号";
            case "patient_name" -> "患者";
            case "dept_name" -> "科室";
            case "sampling_site" -> "取材部位";
            case "urgent" -> "加急";
            case "collected_at" -> "登记时刻";
            case "received_at" -> "签收时刻";
            case "fixative" -> "固定液";
            case "fixed_at" -> "固定时刻";
            case "diagnosed_at" -> "写完诊断时刻";
            case "first_signed_at" -> "初诊签名时刻";
            case "second_signed_at" -> "复诊签名时刻";
            case "report_issued_at" -> "报告签发时刻";
            case "rejected_at" -> "拒收时刻";
            case "reject_reason" -> "拒收原因";
            case "pathologist_name" -> "诊断医师";
            case "first_signer_name" -> "初诊签名人";
            case "second_signer_name" -> "复诊签名人";
            // 接收
            case "submitted" -> "送检总数(含拒收)";
            case "rejected" -> "拒收数";
            case "received" -> "已签收数";
            case "not_received" -> "未签收数";
            case "received_rate_pct" -> "签收率(%)";
            case "negative_interval" -> "签收早于登记(补录)";
            case "within_30min" -> "30分钟内签收";
            case "within_2h" -> "2小时内签收";
            case "within_24h" -> "24小时内签收";
            case "over_24h" -> "超24小时签收";
            case "median_minutes" -> "登记→签收中位数(分钟)";
            case "p90_minutes" -> "登记→签收P90(分钟)";
            case "receive_minutes" -> "登记→签收(分钟)";
            case "receive_band" -> "签收时长分档";
            // 固定
            case "with_fixative" -> "已录固定液";
            case "with_fixed_at" -> "已录固定时刻";
            case "fixation_recorded" -> "固定信息完整数";
            case "fixation_recorded_rate_pct" -> "固定信息完整率(%,非国标规范率)";
            case "fixatives" -> "实际录入的固定液";
            case "fixation_status" -> "固定信息状态";
            case "minutes_to_fixation" -> "登记→固定(分钟)";
            // 报告
            case "issued" -> "签发份数";
            case "timely" -> "及时数";
            case "overdue" -> "超时数";
            case "unjudgeable" -> "无法判定(无签收时刻)";
            case "timely_rate_pct" -> "及时率(%)";
            case "avg_tat_hours" -> "平均周转(小时)";
            case "median_tat_hours" -> "周转中位数(小时)";
            case "avg_tat_minutes" -> "平均周转(分钟)";
            case "median_tat_minutes" -> "周转中位数(分钟)";
            case "tat_hours" -> "签收→签发(小时)";
            case "tat_minutes" -> "签收→签发(分钟)";
            case "judgement" -> "判定";
            case "with_tech_order" -> "其中加做过特检";
            case "urgent_cases" -> "其中加急";
            case "diagnosed" -> "写完诊断数";
            // 双签
            case "with_first_sign" -> "已初签";
            case "with_second_sign" -> "已复签";
            case "double_signed" -> "已双签";
            case "double_sign_rate_pct" -> "双签完成率(%)";
            case "no_sign" -> "未签名即签发";
            case "only_second_sign" -> "仅复签(异常)";
            case "same_person_double_sign" -> "同一人双签(异常)";
            case "sign_status" -> "签名状态";
            // 切片
            case "stain_type" -> "染色类型编码";
            case "stain_name" -> "染色类型";
            case "stain_item" -> "染色项目";
            case "slides" -> "切片数";
            case "graded" -> "已评质量数";
            case "good" -> "优(GOOD)";
            case "fair" -> "良(FAIR)";
            case "poor" -> "差(POOR)";
            case "good_rate_pct" -> "优良率(%,仅GOOD)";
            case "good_or_fair_rate_pct" -> "优良率(%,GOOD+FAIR)";
            case "grade_coverage_pct" -> "质量评价覆盖率(%)";
            case "slide_id" -> "切片ID";
            case "slide_code" -> "切片编码";
            case "slide_no" -> "片号";
            case "quality" -> "切片质量";
            case "stained_at" -> "染色时刻";
            case "stained_by_name" -> "染色人";
            case "stained" -> "已录染色时刻";
            case "he" -> "HE";
            case "ihc" -> "免疫组化";
            case "special_stain" -> "特殊染色";
            case "molecular" -> "分子病理";
            // 蜡块
            case "block_id" -> "蜡块ID";
            case "block_code" -> "蜡块编码";
            case "block_no" -> "块号";
            case "tissue_desc" -> "取材组织描述";
            case "dehydrate_batch" -> "脱水篮批次";
            case "dehydrate_batches" -> "脱水篮批次数";
            case "embedded_at" -> "包埋时刻";
            case "embedded_by_name" -> "包埋人";
            case "blocks" -> "蜡块数";
            case "embedded" -> "已确认包埋";
            case "blocks_per_specimen" -> "蜡块/标本";
            case "specimens" -> "涉及标本数";
            // 流转
            case "node" -> "环节编码";
            case "node_name" -> "环节";
            case "events" -> "打点次数";
            case "operators" -> "操作人数";
            case "median_hours_from_receive" -> "距签收中位数(小时)";
            case "no_receive_time" -> "无签收时刻(不进中位数)";
            case "process_id" -> "打点ID";
            case "occurred_at" -> "打点时刻";
            case "operator_name" -> "操作人";
            case "remark" -> "备注";
            case "hours_from_receive" -> "距签收(小时)";
            // 工作量
            case "registered" -> "登记标本数";
            case "outp_source" -> "门诊来源";
            case "inp_source" -> "住院来源";
            case "routine" -> "常规";
            case "frozen" -> "术中冰冻";
            case "cytology" -> "细胞学";
            case "consult" -> "会诊";
            case "type_unfilled" -> "类别未填";
            case "issued_reports" -> "首次报告签发";
            case "supplement_reports" -> "补充报告";
            case "report_kind" -> "报告类型";
            case "report_time" -> "报告时刻";
            case "signer_name" -> "签名人";
            case "seq_no" -> "补充报告序号";
            case "reason" -> "原因";
            case "user_id" -> "用户ID";
            case "user_name" -> "姓名";
            case "first_signed" -> "初诊签名数";
            case "second_signed" -> "复诊签名数";
            case "grossing" -> "取材打点数";
            case "tech_orders" -> "特检开单数";
            case "activities" -> "操作次数合计";
            case "activity" -> "操作";
            case "act_time" -> "操作时刻";
            // 特检
            case "tech_order_id" -> "技术医嘱ID";
            case "tech_type" -> "技术类型编码";
            case "tech_name" -> "技术类型";
            case "tech_item" -> "技术项目";
            case "status" -> "状态";
            case "ordered" -> "开单数";
            case "done" -> "已完成";
            case "pending" -> "未完成";
            case "cancelled" -> "已取消";
            case "ordered_at" -> "开单时刻";
            case "done_at" -> "完成时刻";
            case "ordered_by_name" -> "开单人";
            case "done_by_name" -> "完成人";
            case "median_hours_to_done" -> "开单→完成中位数(小时)";
            default -> col;
        };
    }

    /**
     * CSV 字段转义 —— 与 AnesQcController.csv / StatsController.csv 逐字同款（含公式注入守卫）：
     * 固定液、拒收原因、取材组织描述、备注都是可写入的自由文本，以 = + - @ 开头会被 Excel 当公式执行；
     * 含逗号会串列。数值不加 ' 前缀（加了在 Excel 里变文本，SUM 跳过，工作量列合计对不上）。
     * 本轮不改既有三处（并行车道占用），故在此自带一份；抽公共工具类留作后续小重构。
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

    /** 同一个时间窗要重复传 n 组 from/to 时用它，手写 12 个参数极易漏一个 */
    private static Object[] rep(Window w, int pairs) {
        Object[] a = new Object[pairs * 2];
        for (int i = 0; i < pairs; i++) {
            a[2 * i] = w.f();
            a[2 * i + 1] = w.t();
        }
        return a;
    }
}
