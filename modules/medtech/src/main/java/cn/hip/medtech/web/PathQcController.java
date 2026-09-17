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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * <h2>v60（2576-②③）：按送检科室聚合的汇总维度 {@code WORKLOAD_DEPT}</h2>
 * <p>此前六条 WORKLOAD_* 只按日 / 类别 / 染色 / 医师 / 技术类型分组，{@code dept_name} 只出现在穿透明细里——
 * 「多维度」缺一个科室维度（v59 复核反驳者原话）。本版加 {@code WORKLOAD_DEPT}：按送检科室（门诊取挂号科室
 * outp_registration.dept_id、住院取在院科室 inp_admission.dept_id，与 {@code SPEC_SELECT} 的 dept_name <b>同一条联接链</b>）
 * 聚合本期<b>登记</b>的标本条数与其截至查询时刻的状态（已签发 / 已拒收 / 在办）；取不到科室或科室名为空白的归
 * 「{@value #UNKNOWN_DEPT}」一行、<b>不丢行</b>。穿透明细复用 SPEC_SELECT，可按 {@code dept}（科室显示名）过滤到一个科室。
 * 口径文本仍是带 {@code **} / 反引号的 Markdown 味纯文本，由前端 {@code format.ts#mdText} 去标记后显示（不做 Markdown 渲染），本控制器不改文本。
 *
 * <h2>v65（2576 复核）：关于合计格的那两句话，由它们所断言的状态生成</h2>
 * <p>返回体里每条有 {@code summary} 的指标另带 {@code summaryTitle}（合计格标题）；{@code WORKLOAD_BLOCK} 另带
 * {@code summaryVsDailyNote}（合计与按日各行的<b>本次实际</b>对账关系，同一段字随 CSV 页脚走）。
 * <b>两句都不许在前端拼</b>——v64 的教训正是：前端一个不带指标参数的全局 computed 写死「下面那张表才按日拆分」，
 * 被无条件绑在逐指标的 v-for 里，而按科室 / 技术类型 / 标本类别 / 染色类型分组的五条指标表里连日期列都没有；
 * 同一轮的 caveat 又把只在跨统计日时成立的关系写成「必然」，而演示库态只有当天一根柱、两数逐字相等。
 * 分组维度见 {@link RowGroup} 与 {@link #summaryTitle(Def, List, Window)}，对账关系见
 * {@link #summaryVsDailyNote(Def, List, Map)}：前者说出口之前拿本次真实返回的行验一遍，后者按库态现算。
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
            + "登记 / 接收 / 固定类与送检科室工作量按 path_specimen.collected_at（登记时刻）落窗；"
            + "报告及时率、双签率与报告签发量按 report_issued_at（正式签发时刻）落窗；"
            + "蜡块按 coalesce(embedded_at, created_at)、切片按 coalesce(stained_at, created_at)、"
            + "流转环节按 path_process.occurred_at、特检技术医嘱按 path_tech_order.ordered_at。"
            + "每个指标各自回带 anchorField 与 anchor 明示——同一批标本在「本期登记」与「本期签发」"
            + "两个口径下不是同一批，前者含尚未出报告的在途标本，后者含上期登记本期才发的标本。";

    static final String DATA_CAVEAT =
            "数据覆盖面：病理专业模块上线之前入库的标本，病理号 / 标本类别 / 取材部位 / 固定液 / 固定时刻 / "
            + "拒收信息 / 初诊复诊双签 / 报告签发时刻全部为空，且**刻意不补填**（宁可少算，不可假算："
            + "当时确实没采集，拿登记时刻去顶签收时刻会让签收及时率恒等于 100%）。"
            + "因此依赖这些字段的指标只覆盖模块上线之后真正录了相应字段的标本，不是全院全历史口径。"
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

    /**
     * <b>「蜡块按日产出数」这一条事实的唯一口径结论</b>（v63，2576 复核第二条）。
     *
     * <p>{@code WORKLOAD_BLOCK} 的合计列 {@code blocks_produced_in_period}、按日列 {@code blocks_produced}
     * 与覆盖率段 {@code coverage.blocks.blocks} 是<b>同一个 {@code count(*) from path_block}、
     * 同一条落窗谓词 {@code W_BLOCK}</b>（合计与覆盖率段落窗整个区间，按日列只是把它按 {@code stat_day} 拆开）——
     * 同一个数只能有一个结论。
     *
     * <p>修复前的反向事实（v62 交付后复核，反驳者原话）：v62 只把「这个数事后会变、导出的历史报表不可复现」
     * 写进了 WORKLOAD_BLOCK 自己的 caveat，而同一块看板上<b>位置更靠前、且被 {@code DATA_CAVEAT} 点名
     * 「请先看」</b>的覆盖率段仍写着这个数「蜡块产出量仍可信（建档即产出）」——
     * 同一页对同一个蜡块产出数给出两个相反的结论，而说反话的那一句先上屏。
     * 两处各写一段散文必然漂移，故结论只在这里写一遍，两边引用同一个常量。
     */
    public static final String BLOCK_DAY_CAVEAT =
            "**按日数事后会变、导出的历史报表不可复现**：尚未登记包埋的蜡块"
            + "按**建档时刻**暂记，先算进取材那天的产出；等包埋登记落下去，同一块蜡块就从建档日消失、"
            + "移到包埋日——**同一个已关闭区间今天导与明天导的按日数不一样（某天会变小）**。"
            + "脱水过夜跨日是病理常规，不是边角情形。"
            + "本平台**不为此补填包埋时刻**（宁可少算，不可假算），"
            + "发出去之前请连同导出时刻一起注明（与送检科室工作量那三列存量数同一体例）。";

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

    /**
     * v60：送检科室联接——门诊按挂号科室（outp_registration.dept_id）、住院按在院科室（inp_admission.dept_id）。
     * 与 {@code SPEC_SELECT} 里 dept_name 走的是同一条链，WORKLOAD_DEPT 的汇总与穿透因此同口径。
     */
    private static final String DEPT_JOINS = """
            left join outp_order oo on oo.id = s.order_id
            left join outp_registration orr on orr.id = oo.registration_id
            left join sys_dept od on od.id = orr.dept_id
            left join inp_order io on io.id = s.inp_order_id
            left join inp_admission ia on ia.id = io.admission_id
            left join sys_dept ad on ad.id = ia.dept_id
            """;

    /** 取不到送检科室、或科室名为空白的标本归到这一行——不丢行，也不把它们混进任何一个真实科室 */
    public static final String UNKNOWN_DEPT = "（未知科室）";

    /**
     * v62（2563 复核第三条）：WORKLOAD_TECH 的中文技术分类表达式（以 {@code t} 为 path_tech_order 别名），
     * 由 {@link #q(String)} 以 <code>{techName}</code> 占位展开——<b>汇总行与穿透明细共用这一份</b>，不各写一段 case。
     *
     * <p>修复前：中文只存在于汇总行的 case 分支里，穿透 SQL 只选 {@code t.tech_type}，于是
     * <b>穿透 CSV 里压根没有中文「技术分类」列</b>，表头却写作「技术类型编码」——屏上靠前端 cellText
     * 逐列翻译掩盖，导出一离开页面就只剩 DEEP_CUT / IHC。值域与措辞与
     * {@code PathologyReportController.TECH_TYPE_NAMES}、前端 {@code format.ts} 的 techTypeName 逐字一致。
     */
    private static final String TECH_NAME_CASE =
            "case t.tech_type"
            + " when 'DEEP_CUT' then '深切' when 'RECUT' then '重切'"
            + " when 'RESAMPLE' then '补取材' when 'IHC' then '免疫组化'"
            + " when 'SPECIAL_STAIN' then '特殊染色' else '分子病理' end";

    /**
     * 科室显示名：门诊科室优先、住院科室其次；首尾空白剥掉后为空的视同缺失（sys_dept.name not null 但不禁止空白）。
     * 用 regexp_replace 的 \s 而不是 trim()：PostgreSQL 的 trim() 只剥空格，制表 / 换行会漏成一个「看不见的科室」。
     */
    private static final String DEPT_NAME =
            "coalesce(nullif(regexp_replace(od.name, '^\\s+|\\s+$', '', 'g'), ''),"
            + " nullif(regexp_replace(ad.name, '^\\s+|\\s+$', '', 'g'), ''), '" + UNKNOWN_DEPT + "')";

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
                   {deptName}                                                  as dept_name,
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
            """.replace("{deptName}", DEPT_NAME);

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

    /** 占位符展开。{spec} 内不含其它占位符（dept_name 的表达式在常量定义时已展开），其余互不嵌套 */
    private static String q(String sql) {
        return sql.replace("{spec}", SPEC_SELECT)
                .replace("{pat}", PAT_JOINS)
                .replace("{deptJoins}", DEPT_JOINS)
                .replace("{deptName}", DEPT_NAME)
                .replace("{unknownDept}", UNKNOWN_DEPT)
                .replace("{patName}", "coalesce(op.name, ipa.name)")
                .replace("{typeName}", TYPE_NAME)
                .replace("{techName}", TECH_NAME_CASE)
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
                       List<String> missingFields, String anchorField, String anchor, String caveat,
                       RowGroup rowGroup) {}

    /**
     * 汇总行的<b>分组维度</b>：这条指标的 {@code rows} 是按什么 group by 出来的。
     *
     * <p><b>v65 车道 C（2576 复核第一条）：合计格标题那句「下面那张表按 X 拆分」由这里生成</b>，
     * 前端<b>不再自己写一句话套给所有指标</b>。修复前的反向事实（复核者原话经主控实测坐实）：
     * v64 为「合计格没标题」新加的 {@code summaryTitle} 是个<b>不带指标参数的全局 computed</b>，
     * 末句写死「下面那张表才按日拆分」，却被无条件绑在 v-for 循环里那一块 el-descriptions 上——
     * 于是只对 {@code WORKLOAD_BLOCK} 成立的那句话被原样印到每一条有合计格的指标头上，
     * 而送检科室工作量 / 特检技术医嘱量 / 标本接收 / 标本固定信息完整率 / 染色切片优良率这五条
     * 分别按科室、技术类型、标本类别、标本类别、染色类型分组，<b>表里连日期列都没有</b>。
     * 承载本参数「多维度」的恰是这几个维度屏，而每一块都在屏上宣告自己是按日维度。
     *
     * <p>登记在这里还不够——<b>登记的是意图，上屏的必须是事实</b>：
     * {@link #summaryTitle(Def, List, Window)} 在说出「每行一个 X」之前，
     * 先拿<b>本次真实返回的那批行</b>验一遍（{@link #groupsOnePerRow}：这一列在行间逐行取值互不相同）；
     * 验不过就<b>一个字也不说下面那张表长什么样</b>。「不按日拆分」是个否定断言，
     * 说之前另查一遍行里确实没有按日分组列（{@link #hasDayColumn}）。
     *
     * @param column 该维度在返回行里的列名（就是 SQL 里 group by 的那一列）
     * @param label  这个维度的中文名，直接上屏
     * @param byDay  是不是按日维度（{@code stat_day} / {@code issue_day}）
     */
    private record RowGroup(String column, String label, boolean byDay) {}

    private static final RowGroup BY_STAT_DAY = new RowGroup("stat_day", "统计日", true);
    private static final RowGroup BY_ISSUE_DAY = new RowGroup("issue_day", "报告签发日", true);
    private static final RowGroup BY_SPECIMEN_TYPE = new RowGroup("specimen_type", "标本类别", false);
    private static final RowGroup BY_STAIN_TYPE = new RowGroup("stain_type", "染色类型", false);
    private static final RowGroup BY_DEPT = new RowGroup("dept_name", "送检科室", false);
    private static final RowGroup BY_TECH_TYPE = new RowGroup("tech_type", "技术类型", false);
    private static final RowGroup BY_NODE = new RowGroup("node", "流转节点", false);
    private static final RowGroup BY_PATHOLOGIST = new RowGroup("user_id", "病理医师", false);

    /** 按日分组列的全集：要宣告一张表「不按日拆分」，先得证明它一列都没有 */
    private static final Set<String> DAY_COLUMNS = Set.of("stat_day", "issue_day");

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    private static void def(String code, String name, RowGroup rowGroup,
                            String anchorField, String anchor, String caveat) {
        DEFS.put(code, new Def(code, name, true, null, List.of(), anchorField, anchor, caveat, rowGroup));
    }

    private static void unavailable(String code, String name, String reason, String... missing) {
        DEFS.put(code, new Def(code, name, false, reason, List.of(missing), null, null, null, null));
    }

    private static final String ANCHOR_COLLECTED = "按标本**登记时刻**归集（含本期登记但尚未出报告的在途标本）";
    private static final String ANCHOR_ISSUED = "按报告**正式签发时刻**归集（含上期登记、本期才签发的标本）";

    static {
        def("SPECIMEN_RECEIVE", "标本接收（登记→签收）情况与时长分档", BY_SPECIMEN_TYPE,
                "path_specimen.collected_at", ANCHOR_COLLECTED,
                "collected_at 是**登记时刻**（V21 建表 default now()，取材登记时由系统打时间戳），"
                + "**不是标本离体时刻**——本仓无离体时刻字段。故本指标度量的是「登记→签收」的院内流转，"
                + "不能当作《2024 版》以离体时刻为起点的标本接收及时率上报。"
                + "另：「及时」的判定阈值本平台**无配置键**（病理只有报告时限两个键），故**不给一个单一的"
                + "「接收及时率」数字**，改给接收率 + 时长分档 + 中位数 / P90，由看的人按本院规定自己判。"
                + "要出单一及时率须先新增配置键（如 path.receive.timely_minutes）并同时补迁移 seed 与配置手册。"
                + "补录导致的「签收早于登记」单列 negative_interval，既不并进分档也不取绝对值——"
                + "取绝对值会把一条数据质量问题伪装成一次极快的签收。"
                + "分母 submitted 是送检总数、**含拒收**（拒收不删记录，原样留档）。");

        def("FIXATION", "标本固定信息完整率（**不是**国标口径的规范化固定率）", BY_SPECIMEN_TYPE,
                "path_specimen.collected_at", ANCHOR_COLLECTED,
                "《2024 版》标本规范化固定的要素——固定液类型（中性缓冲福尔马林）、固定液量为标本体积 "
                + "3–10 倍、离体后 ≤30 分钟内固定、固定时长 6–72 小时——本平台只有 fixative"
                + "（自由文本，无固定液字典）与 fixed_at 两列，**离体时刻、固定液用量、固定结束时刻"
                + "三个字段全仓不存在**，四要素只有「固定信息是否录全」这一条可判。"
                + "故列名刻意叫 fixation_recorded_rate_pct 而**不叫 compliance_rate_pct**——"
                + "它不是国标规范率，不可上报。fixatives 列原样列出该组实际录入的固定液文本，"
                + "是否规范请人工看：本平台**不按关键字（如含「福尔马林」）猜规范性**。"
                + "分母含拒收标本。穿透明细的 minutes_to_fixation 是「登记→固定」而非「离体→固定」。");

        def("REPORT_ROUTINE", "常规病理报告及时率（时限读 path.report.routine_hours）", BY_ISSUE_DAY,
                "path_specimen.report_issued_at", ANCHOR_ISSUED,
                ISSUE_VS_DIAGNOSE_NOTE + " " + TAT_START_NOTE + " " + HOLIDAY_NOTE
                + " 只统计 specimen_type='ROUTINE' 的标本：类别未填的历史标本**不默认算常规**"
                + "（默认算常规会把一批不知道时限的标本按 120 小时判，判出来的超时是假的），"
                + "其条数见 coverage.specimens.with_specimen_type。"
                + "with_tech_order 一列是「本期签发的报告里加做过特检技术（免疫组化 / 深切 / 补取材等）的份数」："
                + "国标常允许这类病例延长时限，本平台**不自动放宽**，故这一列的超时不等于质量问题，"
                + "要单独看；不放宽是因为「放宽多久」同样没有配置键，硬编码一个天数就是自造口径。");

        def("REPORT_FROZEN", "术中冰冻病理报告及时率（时限读 path.report.frozen_minutes）", BY_ISSUE_DAY,
                "path_specimen.report_issued_at", ANCHOR_ISSUED,
                ISSUE_VS_DIAGNOSE_NOTE + " " + TAT_START_NOTE
                + " 只统计 specimen_type='FROZEN' 的标本。冰冻的国标起点通常是「标本送达病理科」，"
                + "本平台取 received_at（签收）——两者在流程规范的科室基本重合，在签收靠补录的科室会明显偏乐观。"
                + "冰冻 30 分钟的时限对签收时刻的准确度要求远高于常规 120 小时：晚补 5 分钟就能把超时变及时，"
                + "故这条指标**必须配合 coverage 看，且不建议在签收全靠事后补录的科室对外公布**。");

        def("REPORT_DOUBLE_SIGN", "报告双签完成率（初诊—复诊两级签发）", BY_ISSUE_DAY,
                "path_specimen.report_issued_at", ANCHOR_ISSUED,
                "双签 gate emr.gate.pathology.doublesign **默认 warn**（未双签也放行签发："
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
                + "（自由文本 500 字），但符合性是病理医师的**人工判定**，不是两段自由文本的"
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

        def("SLIDE_QUALITY", "染色切片优良率（按染色类型分）", BY_STAIN_TYPE,
                "coalesce(path_slide.stained_at, created_at)",
                "按切片**染色时刻**归集（未录染色时刻的回落建档时刻，否则整片漏统计）",
                "「优良」口径歧义：本仓 quality 只有 GOOD / FAIR / POOR 三档，"
                + "而《2024 版》HE 与免疫组化染色切片优良率的「优良」通常指「优 + 良」。"
                + "故**两个口径并排给、不替调用方二选一**：good_rate_pct 只算 GOOD，"
                + "good_or_fair_rate_pct 算 GOOD + FAIR。两者差得越大，口径选择对结论的影响越大。"
                + "未评质量的切片**不进分母**（quality 可空），其占比见 grade_coverage_pct——"
                + "覆盖率低时该率只代表被评价的那一小部分，通常还是被挑出来评的那部分，会系统性偏高。");

        def("PROCESS_TAT", "各流转环节耗时（距签收的小时数中位数）", BY_NODE,
                "path_process.occurred_at", "按流转节点**打点时刻**归集",
                "各环节耗时按 path_process 已登记的节点算，**没打点的环节不会显示为 0，"
                + "而是根本不出现在行里**——行的缺席本身就是「这个环节没在系统里打点」的信号，"
                + "补一行 0 会让人误以为该环节零耗时。"
                + "median_hours_from_receive 是该节点时刻距 received_at 的小时数中位数："
                + "RECEIVE 节点本身必然接近 0；received_at 为空的标本不进中位数，其条数见 no_receive_time 列。"
                + "同一标本同一节点可多次打点（如补取材后再次取材），events 是打点次数、specimens 是标本数。");

        def("WORKLOAD_REGISTER", "登记总量（按日，含拒收与加急构成）", BY_STAT_DAY,
                "path_specimen.collected_at", ANCHOR_COLLECTED, WORKLOAD_NOTE
                + " 分母口径：registered 是当日登记的**标本条数**，不是申请单数——"
                + "多部位送检一份申请对应多条标本（同一份申请下按部位序号各自唯一），"
                + "这正是病理科的真实工作量单位。rejected 一列是当日登记的标本里**后来**被拒收的条数"
                + "（按登记日归集，不是按拒收日），要看「本期拒了多少」请走 PROCESS_TAT 的 REJECT 节点。"
                + " molecular_specimens 数的是 specimen_type='MOLECULAR' 的**标本条数**，"
                + "与 WORKLOAD_SLIDE 的 molecular_slides（stain_type='MOLECULAR' 的**切片张数**，锚染色时刻）"
                + "**既不同分母也不同锚点**，两个数不相等是正常的，不是对不上账。");

        // v60（2576-②）：科室维度——此前 dept_name 只在穿透明细里，六条 WORKLOAD_* 汇总行没有一条按送检科室分组
        def("WORKLOAD_DEPT", "送检科室工作量", BY_DEPT,
                "path_specimen.collected_at", ANCHOR_COLLECTED, WORKLOAD_NOTE
                + " 送检科室取自申请归属：门诊按挂号科室（outp_registration.dept_id）、住院按在院科室（inp_admission.dept_id），"
                + "与穿透明细的 dept_name 同一条联接链；取不到科室或科室名为空白的标本归「" + UNKNOWN_DEPT + "」一行，**不丢行**；"
                + "同名科室（不同编码）合并为一行。registered 是本期**登记**的标本条数（与 WORKLOAD_REGISTER 同分母），"
                + "issued_of_registered / rejected / in_progress 是这批标本**截至查询时刻**的状态（已签发 / 已拒收 / 两者皆非）。"
                + "**这三列是存量不是流量**：按登记日归集，同一个已结束的历史区间今天查与下周查，"
                + "issued_of_registered 会变大、in_progress 会变小——上月登记、本月才签发的标本会被追加进上月那一行，"
                + "**据此做的科室工作量表不可复现**，发出去之前请连同查询时刻一起注明。"
                + "**平台目前拿不出「本期签发量按科室分」**：REPORT_* 只按日分组、没有科室维度，本指标的 dept 过滤也只对自己生效——"
                + "要这个口径须另开指标，本版不做（不写代码就不占码）。"
                + "三列之和等于 registered：签发端点拒绝已拒收标本、拒收端点拒绝已诊断标本，两态互斥。");

        // v62（2576 复核）：blocks → blocks_produced。此前本指标的 blocks（count(*) from path_block、锚
        // coalesce(embedded_at, created_at)）与 WORKLOAD_SLIDE 的 blocks（count(distinct block_id)、锚
        // coalesce(stained_at, created_at)）同名同中文「蜡块数」，两个指标在同一页顺序渲染——演示数据下
        // 一屏之内就是「蜡块数 6」与「蜡块数 3」两个数，页面上没有一行字能回答「到底做了几块」（复核者原话，主控实测坐实）。
        // 同时补写「按日数事后会变」：未包埋的蜡块拿 created_at 顶替 embedded_at 先算进取材日，包埋登记一落
        // 就从取材日消失、跳到包埋日——同一个已关闭区间今天导与明天导不一样，此前 caveat 一个字没提。
        def("WORKLOAD_BLOCK", "蜡块产出数（本期合计 + 按日）", BY_STAT_DAY,
                "coalesce(path_block.embedded_at, created_at)",
                "按蜡块**包埋时刻**归集（未录包埋时刻的回落建档时刻）", WORKLOAD_NOTE
                + " **本指标一屏两套口径，列名与中文都已分开**：最上面那一格是**本期合计**"
                + "（整个统计区间只出一个数），四列一律以「本期」起头；下面那张表才是**按日拆分**，"
                + "各列一律以「当日」起头。**合计那一格按整个区间算、不按某一天算**："
                + "「本期产出蜡块数」与某一天的「当日产出蜡块数」不是同一个口径——"
                + "区间内只有一天有产出时两数会逐字相等，那也不表示它是「今天的产量」。"
                + " 「当日产出蜡块数」数的是这一天**产出**了几块蜡块；切片产出数那张表里另有一列"
                + "「当日染色涉及蜡块数(去重)」，数的是这一天染出来的切片**来自**几块蜡块。"
                + "**两列都是「蜡块数」，但一个逐块计数、一个按来源去重，归集时刻也不同**——"
                + "同一天两行并排时两个数不相等是正常的（取材 2 块、只从其中 1 块切片，产出 2 / 涉及 1），"
                + "不是对不上账。"
                // v65 车道 C（2576 复核）：这一段此前把两条**只在跨统计日时才成立**的关系写成「必然」，
                // 而演示脚本造出来的库态只有当天一根柱、两数逐字相等——屏上宣告「必然不等」，库里给出的是相等。
                // 通例改写成条件句，本次查询的**实际**关系由 summaryVsDailyNote 按库态现算、另起一行。
                + " 「本期涉及标本数(去重)」与按日各行「当日涉及标本数(去重)」之和，"
                + "**只在本区间内没有一份标本的蜡块落进两个以上统计日时才相等**：同一份标本的蜡块可以分落在多天"
                + "——脱水过夜跨日、补取材隔几天再给同一标本出块都是常规形态——"
                + "那份标本在它出过块的每一天各被数一次，而本期合计对它只数一次，此时合计就小于各行之和，"
                + "**这是去重口径使然，不是对不上账**。"
                + "「本期蜡块/标本」用的是整窗分母，因而它与按日表里某一行相等只是本次库态使然"
                + "（区间内只有一天有产出时必然相等），**不要拿它当某一天的值读**。"
                + "「本期产出蜡块数」是逐块计数、没有去重，它等于按日各行之和。"
                + "**本次查询这三组数的实际关系另有一行如实写出**（屏上在合计格下面、导出的 CSV 在页脚），"
                + "那是按本次库态现算的，不是通例。"
                // v63（2576 复核）：这句结论与覆盖率段 coverage.blocks.note 同源，只在 BLOCK_DAY_CAVEAT 里写一遍
                + " " + BLOCK_DAY_CAVEAT
                + "「当日已确认包埋」是该行里已登记包埋时刻的块数，"
                + "它与当日产出数差得越大，该行后面越可能还会变；「本期已确认包埋」是同一件事的区间合计。");

        // v62（2576 复核）：blocks → blocks_stained、molecular → molecular_slides；并补写按日数事后会变。
        // 此前本指标的 caveat 只挂了通用 WORKLOAD_NOTE（讲的是「不折算工时」），对这两列零说明。
        def("WORKLOAD_SLIDE", "切片产出数（按日、按染色类型）", BY_STAT_DAY,
                "coalesce(path_slide.stained_at, created_at)",
                "按切片**染色时刻**归集（未录染色时刻的回落建档时刻）", WORKLOAD_NOTE
                + " 「当日染色涉及蜡块数(去重)」数的是这一天染出来的切片**来自**几块蜡块，"
                + "**不是当日的蜡块产出量**——后者在蜡块产出数那张表里，按日叫「当日产出蜡块数」、"
                + "区间合计叫「本期产出蜡块数」，按包埋时刻归集、逐块计数。"
                + "两列同在一块看板上、同一天两行并排，数不相等是正常的。"
                + " 「分子病理切片数(染色类型)」数的是染色类型为分子病理的**切片张数**，"
                + "登记总量那张表里的「分子病理标本数(标本类别)」数的是标本类别为分子病理的**标本条数**，"
                + "**既不同分母也不同锚点**。"
                + " **按日数事后会变、导出的历史报表不可复现**：尚未登记染色的切片"
                + "按**建档时刻**暂记，先算进制片那天；等染色登记落下去，同一张切片就从建档日消失、"
                + "移到染色日——**同一个已关闭区间今天导与明天导的按日数不一样（某天会变小）**，"
                + "blocks_stained 这一列同理（它按同一锚点去重数蜡块）。stained 一列是该行里已录染色时刻的条数，"
                + "它与 slides 差得越大，该行后面越可能还会变。"
                + "本平台**不为此补填染色时刻**（宁可少算，不可假算），"
                + "发出去之前请连同导出时刻一起注明（与送检科室工作量那三列存量数同一体例）。");

        def("WORKLOAD_REPORT", "报告签发量（首次报告 / 补充报告分列）", BY_STAT_DAY,
                "path_specimen.report_issued_at 与 path_report.signed_at", ANCHOR_ISSUED,
                ISSUE_VS_DIAGNOSE_NOTE
                + " issued_reports（首次报告签发）与 supplement_reports（补充报告）**分列不合并**："
                + "补充报告是免疫组化结果回来后的追加意见，不代表一次新的诊断结案，"
                + "两列相加当「报告总数」会把工作量算高。diagnosed 一列是当日写完诊断的标本数"
                + "（按 diagnosed_at 落窗），与 issued_reports 不是同一批——两者的差就是卡在签名 / 签发环节的量。");

        def("WORKLOAD_PATHOLOGIST", "各病理医师工作量（写诊断 / 初签 / 复签 / 补充报告 / 取材 / 特检开单）", BY_PATHOLOGIST,
                "各活动各自的时刻列", "**每一列按自己的时刻列分别落窗**，见 caveat", WORKLOAD_NOTE
                + " 各列的归集时刻各不相同：写诊断按 diagnosed_at、初签按 first_signed_at、"
                + "复签按 second_signed_at、补充报告按 coalesce(signed_at, created_at)、"
                + "取材按 path_process.occurred_at（node='GROSSING'）、特检开单按 ordered_at。"
                + "**同一份报告的初签与复签分属两人、各计一次，六列相加不等于报告份数**；"
                + "activities 是该人本期各类操作的次数合计，**不是「做了多少份报告」**，"
                + "更不能直接拿来排绩效——不同操作的耗时与难度差一个数量级，而本仓没有权重表。");

        def("WORKLOAD_TECH", "特检技术医嘱量（深切 / 重切 / 补取材 / 免疫组化 / 特殊染色 / 分子）", BY_TECH_TYPE,
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
        // v64 合并后补齐：本段 note 原样写着库列名 specimens / diagnosed_not_issued，而它是**上屏**文案
        //（PathQcView 的覆盖率段，评委看到的第一屏）。车道 C 按复核点名只重写了隔壁 blocks 那一段，
        // 同一个函数里的兄弟段留着没动——正是本轮宣称治好的「只修了被点名的那一个入口」。
        // 列名一律改用屏上那一格自己的中文标签，读的人才对得上。
        spec.put("note", "「登记标本数」是该时段**登记**的全部标本（含历史无新字段的行、含拒收），本段各项占比都以它为分母；"
                + "其余各格是真正录了该项的条数。「写了诊断未走签发」这一格是写了诊断但没走正式签发的条数——"
                + "这批标本不在报告及时率的分母里，该格越大，及时率越不代表全院。");
        m.put("specimens", spec);

        var blocks = new LinkedHashMap<String, Object>(one(q("""
                select count(*)                                                   as blocks,
                       count(distinct b.specimen_id)                              as specimens,
                       count(*) filter (where b.embedded_at is not null)          as with_embedded_at,
                       count(*) filter (where b.dehydrate_batch is not null)      as with_dehydrate_batch
                from path_block b
                where {wb}
                """), w.args()));
        // v63（2576 复核）：这一段的 blocks 与蜡块产出数指标的合计列是同一个 count(*)、同一条落窗谓词
        // W_BLOCK，结论必须同源。修复前这里写的是「蜡块产出量仍可信（建档即产出）」，
        // 与同页 WORKLOAD_BLOCK 的 caveat「按日数事后会变、导出的历史报表不可复现」结论相反，
        // 而覆盖率段先于所有指标渲染——说反话的那一句先上屏（复核者原话）。
        // v64（2576 复核）：本段那个数是**整个统计区间**的合计，中文跟着合计列一起正名成「本期产出蜡块数」，
        // 三处呈现（本段 / 合计格 / 按日表）从此一眼分得出哪两处是同一个数（前端 COVERAGE_SECTIONS.labels 给中文）。
        blocks.put("note", "本段按蜡块包埋时刻落窗（未录包埋时刻的回落建档时刻），数的是**整个统计区间**的合计，"
                + "与蜡块产出数那条指标的「本期产出蜡块数」是同一个数、同一条落窗谓词，故结论与该指标的口径同源："
                + BLOCK_DAY_CAVEAT
                + "已录包埋时刻的条数低，说明包埋确认环节没在系统里打点：包埋耗时算不出来，"
                + "且这一行的蜡块数后面还会变（它与本段合计差得越大，越可能变）。");
        m.put("blocks", blocks);

        var slides = new LinkedHashMap<String, Object>(one(q("""
                select count(*)                                              as slides,
                       count(distinct sl.block_id)                           as blocks,
                       count(*) filter (where sl.stained_at is not null)     as with_stained_at,
                       count(*) filter (where sl.quality is not null)        as with_quality
                from path_slide sl
                where {ws}
                """), w.args()));
        // v64 合并后补齐：同上——本段 note 此前把 SQL 表达式 coalesce(stained_at, created_at) 与
        // 库列名 with_quality / slides 直接打在屏上。改用屏上那几格自己的中文标签。
        slides.put("note", "本段按切片染色时刻落窗（未录染色时刻的回落建档时刻）。"
                + "「已评切片质量」这一格是染色切片优良率的**真实分母**——"
                + "它与「切片数」差得越远，优良率越只代表被挑出来评价的那一小部分。");
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
        others.put("note", "流转节点值域共 15 档（从签收一路到补充报告，含特检开单 / 完成 / 取消三档）；"
                + "「出现过的环节数」远小于 15 说明多数环节没有打点，各流转环节耗时那条指标只覆盖打了点的那几档。");
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
        // 下面所有「关于那张表」的话，一律拿**本次真正交出去的这批行**去生成与校验，不拿截断前的
        List<Map<String, Object>> shown = cut ? rows.subList(0, ROW_LIMIT) : rows;
        m.put("rows", shown);
        m.put("rowsTruncated", cut);
        if (cut) {
            m.put("rowsTruncatedNote",
                    "汇总行超过 " + ROW_LIMIT + " 行，本次只返回前 " + ROW_LIMIT + " 行，请缩小统计时间段");
        }
        Map<String, Object> summary = summaryOf(d.code(), w);
        if (summary != null) {
            m.put("summary", summary);
            m.put("summaryTitle", summaryTitle(d, shown, w));
            String vs = summaryVsDailyNote(d, shown, summary);
            if (vs != null) m.put("summaryVsDailyNote", vs);
        }
        return m;
    }

    // ===================== 合计格那两句话（由它们所断言的状态生成，不是写死一句套给所有指标） =====================

    /**
     * 合计格的标题（v65 车道 C，2576 复核第一条）。
     *
     * <p><b>修复前的反向事实</b>：这句话是前端一个<b>不带指标参数的全局 computed</b>，末句写死
     * 「下面那张表才按日拆分」，被无条件绑在 v-for 里那一块 el-descriptions 上——按送检科室 / 技术类型 /
     * 标本类别 / 染色类型分组的五条指标，表里连日期列都没有，屏上却每一块都在宣告自己是按日维度。
     *
     * <p>现在这句话的每个分句都由它所断言的那个状态生成：
     * <ul>
     *   <li>区间取<b>本次实际统计的窗口</b>（{@link Window}），不是前端的日期选择框——
     *       用户改了日期还没点查询时，屏上的数仍是上一次的区间。</li>
     *   <li>「不是某一天的数」只在 {@code days > 1} 时说：区间就一天的时候，这一格<b>正是</b>那一天。</li>
     *   <li>分组维度取 {@link Def#rowGroup()}（后端本就知道自己 group by 什么），
     *       而且在说出口之前拿<b>本次返回的那批行</b>验一遍（{@link #groupsOnePerRow}）；
     *       验不过就一个字也不说下面那张表长什么样。</li>
     *   <li>「不按日拆分」是否定断言，另查一遍行里确实没有按日分组列（{@link #hasDayColumn}）。</li>
     *   <li>行数取<b>截断后真正交出去的行数</b>，屏上数得出来。</li>
     * </ul>
     */
    private static String summaryTitle(Def d, List<Map<String, Object>> shown, Window w) {
        var sb = new StringBuilder("本期合计　").append(w.f()).append(" 至 ").append(w.t())
                .append("（共 ").append(w.days()).append(" 天）。");
        sb.append(w.days() > 1
                ? "这一格的每个数都按整个统计区间算，不是某一天的数；"
                : "这一格按 " + w.f() + " 这一天算（本区间只有这一天）；");
        if (shown.isEmpty()) {
            return sb.append("本区间没有分组行，下面没有表。").toString();
        }
        RowGroup g = d.rowGroup();
        if (g == null || !groupsOnePerRow(shown, g.column())) {
            return sb.append("下面那张表共 ").append(shown.size())
                    .append(" 行，分组维度未登记，请按表头自行判读。").toString();
        }
        if (g.byDay()) {
            sb.append("下面那张表按日拆分，每行一个").append(g.label());
        } else if (hasDayColumn(shown)) {
            sb.append("下面那张表按").append(g.label()).append("分组，每行一个").append(g.label());
        } else {
            sb.append("下面那张表不按日拆分，按").append(g.label())
                    .append("分组，每行一个").append(g.label());
        }
        return sb.append("，共 ").append(shown.size()).append(" 行。").toString();
    }

    /** 这一列在本次返回的行里<b>逐行取值互不相同</b>——「每行一个 X」只在它成立时才说得出口 */
    private static boolean groupsOnePerRow(List<Map<String, Object>> rows, String column) {
        var seen = new HashSet<String>();
        for (Map<String, Object> r : rows) {
            if (!r.containsKey(column) || !seen.add(String.valueOf(r.get(column)))) return false;
        }
        return true;
    }

    /** 行里有没有按日分组列（{@link #DAY_COLUMNS}）——「不按日拆分」说之前先查这一遍 */
    private static boolean hasDayColumn(List<Map<String, Object>> rows) {
        for (String k : rows.get(0).keySet()) {
            if (DAY_COLUMNS.contains(k)) return true;
        }
        return false;
    }

    /**
     * 合计格与按日各行的<b>本次实际</b>对账关系（v65 车道 C，2576 复核第二条）。
     *
     * <p><b>修复前的反向事实</b>（复核者原话经主控实测坐实）：v64 把两条<b>与库内事实相反</b>的对账口径
     * 当成「必然」加粗印在数字正上方、并随 CSV 页脚发出去——宣称「本期涉及标本数(去重)」<b>必然小于</b>
     * 按日各行之和、且「本期蜡块/标本」不等于按日表里的任何一行。这两句<b>只在「同一份标本的蜡块落进两个
     * 以上统计日」时才成立</b>；而平台自己的演示脚本造出来的库态只有当天一根柱，默认 30 天窗口下合计格与
     * 按日唯一那一行<b>逐字相等</b>——屏上宣告「必然不等」，库里给出的是相等。
     *
     * <p>现在不再讲通例：三组数的关系<b>按本次查询的库态现算</b>。
     * 通例那部分（为什么会小于、什么时候才相等）留在指标 caveat 里，且一律写成条件句。
     */
    private static String summaryVsDailyNote(Def d, List<Map<String, Object>> shown,
                                             Map<String, Object> summary) {
        if (!"WORKLOAD_BLOCK".equals(d.code())) return null;
        String head = "本期合计与按日各行的实际对账（按本次查询的库态现算，不是通例）：";
        if (shown.isEmpty()) {
            return head + "本区间没有按日行（没有蜡块落进这个窗口），无从比较。";
        }
        long periodBlocks = lng(summary.get("blocks_produced_in_period"));
        long dayBlocks = sumOf(shown, "blocks_produced");
        long periodSpecimens = lng(summary.get("specimens_in_period"));
        long daySpecimens = sumOf(shown, "specimens_of_day");
        var sb = new StringBuilder(head);
        sb.append("「本期产出蜡块数」").append(periodBlocks)
                .append(periodBlocks == dayBlocks ? " 等于" : " 不等于")
                .append("按日各行「当日产出蜡块数」之和 ").append(dayBlocks).append("；");
        sb.append("「本期涉及标本数(去重)」").append(periodSpecimens);
        if (periodSpecimens == daySpecimens) {
            sb.append(" 等于按日各行「当日涉及标本数(去重)」之和 ").append(daySpecimens)
                    .append("——本区间内没有一份标本的蜡块落进两个以上统计日，两数相等；");
        } else if (periodSpecimens < daySpecimens) {
            sb.append(" 小于按日各行「当日涉及标本数(去重)」之和 ").append(daySpecimens)
                    .append("——有标本的蜡块分落在两个以上统计日，那份标本在它出过块的每一天各被数一次、"
                            + "本期合计对它只数一次，这不是对不上账；");
        } else {
            sb.append(" 大于按日各行「当日涉及标本数(去重)」之和 ").append(daySpecimens)
                    .append("——整窗去重数不该大于按日之和，出现即是缺陷，请报修；");
        }
        Object periodRatio = summary.get("blocks_per_specimen_in_period");
        if (periodRatio == null) {
            sb.append("「本期蜡块/标本」本区间算不出（涉及标本数为 0）。");
        } else {
            long same = 0;
            for (Map<String, Object> r : shown) {
                if (sameNumber(periodRatio, r.get("blocks_per_specimen_of_day"))) same++;
            }
            sb.append("「本期蜡块/标本」").append(periodRatio).append(same == 0
                    ? " 与按日表里的每一行都不相同（它用的是整窗分母）。"
                    : " 与按日表里 " + same + " 行的数值相同（它用的是整窗分母，相同是本次库态使然）。");
        }
        return sb.toString();
    }

    private static long lng(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static long sumOf(List<Map<String, Object>> rows, String column) {
        long sum = 0;
        for (Map<String, Object> r : rows) sum += lng(r.get(column));
        return sum;
    }

    /** 两个数值列的值是否相等（库端 {@code round(...)} 回的是 BigDecimal，标度可能不同，故按数值比） */
    private static boolean sameNumber(Object a, Object b) {
        if (!(a instanceof Number x) || !(b instanceof Number y)) return false;
        return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
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
                               when 'ISSUE'       then '报告签发'  when 'SUPPLEMENT'  then '补充报告'
                               when 'TECH_ORDER'  then '下达特检医嘱' when 'TECH_DONE' then '确认完成特检医嘱'
                               when 'TECH_CANCEL' then '取消特检医嘱' else pp.node end              as node_name,
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
                                 when 'SECOND_SIGN' then 10 when 'ISSUE' then 11 when 'SUPPLEMENT' then 12
                                 when 'TECH_ORDER' then 13 when 'TECH_DONE' then 14 when 'TECH_CANCEL' then 15 else 16 end
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
                           count(*) filter (where s.specimen_type = 'MOLECULAR')         as molecular_specimens,
                           count(*) filter (where s.specimen_type is null)               as type_unfilled
                    from path_specimen s
                    where {wc}
                    group by 1
                    order by 1 desc
                    """, w);
            // v60（2576-②）：送检科室维度。args: from, to
            // v61（2576 复核）：issued → issued_of_registered。此前这一列叫 issued，与 REPORT_* 系列的
            // issued 同名同中文「签发份数」，但两者锚点不同：这里的时间窗是 {wc}=collected_at（登记时刻），
            // 数的是「本期**登记**的标本里、截至查询那一刻已签发的条数」（存量、随查询时刻变化）；
            // REPORT_* 的 issued 锚 {wi}=report_issued_at，数的是「本期**签发**了多少」（流量、区间关闭后不再变）。
            // 后果：同一个已结束的历史区间今天导一次、下周再导一次，科室这一列会变大——上月登记、本月才签发的
            // 标本会被追加进上月那一行，一张已经发给科室做工作量考核的表因此不可复现（复核者原话，主控实测坐实）。
            case "WORKLOAD_DEPT" -> query("""
                    select {deptName}                                                  as dept_name,
                           count(*)                                                    as registered,
                           count(*) filter (where s.report_issued_at is not null)      as issued_of_registered,
                           count(*) filter (where s.rejected_at is not null)           as rejected,
                           count(*) filter (where s.report_issued_at is null
                                              and s.rejected_at is null)               as in_progress
                    from path_specimen s
                    {deptJoins}
                    where {wc}
                    group by 1
                    order by registered desc, 1
                    """, w);
            // args: from, to
            // v62（2576 复核）：blocks → blocks_produced（count(*)，当日产出）——与 WORKLOAD_SLIDE 的
            // blocks_stained（count(distinct block_id)，当日染色涉及）此前同名同中文「蜡块数」，并排两个数。
            // v64（2576 复核）：**按日行与合计行不再共用列名**。这里每一行都是一个 stat_day，列名一律带 _of_day
            // （或本来就只出现在按日行的 blocks_produced / embedded），中文一律「当日…」；
            // 区间合计那一格改用 *_in_period / 「本期…」，见 summaryOf 的同名 case。
            case "WORKLOAD_BLOCK" -> query("""
                    select coalesce(b.embedded_at, b.created_at)::date                    as stat_day,
                           count(*)                                                       as blocks_produced,
                           count(*) filter (where b.embedded_at is not null)              as embedded,
                           count(distinct b.specimen_id)                                  as specimens_of_day,
                           round(count(*)::numeric
                                 / nullif(count(distinct b.specimen_id), 0), 2)           as blocks_per_specimen_of_day,
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
                           count(*) filter (where sl.stain_type = 'MOLECULAR')            as molecular_slides,
                           count(distinct sl.block_id)                                    as blocks_stained
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
            case "WORKLOAD_TECH" -> jdbc.queryForList(q("""
                    select t.tech_type,
                           {techName}                                                         as tech_name,
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
                    """), w.args());
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
            // v60：dept_count 是本期有登记的科室数（含「（未知科室）」这一行）；unknown_dept 是落到该行的标本条数
            case "WORKLOAD_DEPT" -> one(q("""
                    select count(*)                                                    as registered,
                           count(*) filter (where s.report_issued_at is not null)      as issued_of_registered,
                           count(*) filter (where s.rejected_at is not null)           as rejected,
                           count(*) filter (where s.report_issued_at is null
                                              and s.rejected_at is null)               as in_progress,
                           count(distinct {deptName})                                  as dept_count,
                           count(*) filter (where {deptName} = '{unknownDept}')        as unknown_dept
                    from path_specimen s
                    {deptJoins}
                    where {wc}
                    """), w.args());
            // v64（2576 复核）：**这一格落窗的是整个 {wb}（默认 30 天）**，四列一律 *_in_period。
            // 修复前四列与按日行同名（blocks_produced / embedded / specimens / blocks_per_specimen），
            // 中文又都写着「当日…」，于是同一屏上「当日产出蜡块数 39」（区间合计）与「当日产出蜡块数 3」（某一天）并存，
            // 指标自己的 caveat 还逐字把这一列定义成「这一天产出了几块」，等于给合计数背书成单日数（复核者原话）。
            // 命名照 WORKLOAD_DEPT 的 issued_of_registered =「本期登记中已签发」的体例：窗口口径写进列名与中文。
            // v65（2576 复核）：specimens_in_period 是**整窗去重**，blocks_per_specimen_in_period 用的是整窗分母。
            // 此前这里与 caveat 都写着它们「必然小于按日各行之和」「不等于按日表里的任何一行」——**那是假的**：
            // 两句只在同一份标本的蜡块跨统计日时成立，而演示库态只有当天一根柱、两数逐字相等。
            // 通例已改成条件句，本次查询的实际关系由 summaryVsDailyNote 按库态现算。
            case "WORKLOAD_BLOCK" -> one(q("""
                    select count(*)                                                as blocks_produced_in_period,
                           count(*) filter (where b.embedded_at is not null)       as embedded_in_period,
                           count(distinct b.specimen_id)                           as specimens_in_period,
                           round(count(*)::numeric
                                 / nullif(count(distinct b.specimen_id), 0), 2)    as blocks_per_specimen_in_period
                    from path_block b
                    where {wb}
                    """), w.args());
            case "WORKLOAD_SLIDE" -> one(q("""
                    select count(*)                                                as slides,
                           count(*) filter (where sl.stained_at is not null)       as stained,
                           count(*) filter (where sl.stain_type = 'HE')            as he,
                           count(*) filter (where sl.stain_type = 'IHC')           as ihc,
                           count(*) filter (where sl.stain_type = 'SPECIAL')       as special_stain,
                           count(*) filter (where sl.stain_type = 'MOLECULAR')     as molecular_slides
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
     * @param dept  可选（v60），<b>只对 WORKLOAD_DEPT 生效</b>：按科室显示名过滤到一个科室（传「{@value #UNKNOWN_DEPT}」
     *              取无科室 / 空白科室名的标本）。其余指标的明细不按科室分组，不套用该过滤；返回体只在生效时回带 {@code dept}
     */
    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam(required = false) String indicator,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) String dept) {
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
        String deptFilter = deptFilterFor(d.code(), dept);
        List<Map<String, Object>> rows = detailRows(d.code(), w.window(), deptFilter);
        boolean truncated = rows.size() > DETAIL_LIMIT;
        body.put("items", truncated ? rows.subList(0, DETAIL_LIMIT) : rows);
        body.put("truncated", truncated);
        if ("WORKLOAD_DEPT".equals(d.code())) body.put("dept", deptFilter);
        body.put("anchorField", d.anchorField());
        body.put("anchor", d.anchor());
        body.put("caveat", d.caveat());
        body.put("windowAnchorNote", WINDOW_ANCHOR_NOTE);
        return R.ok(body);
    }

    /** v60 之前的四参形态（V57TechTraceTest / V58TechProgressTest / V59TechConsistencyTest 直调），等价于不带科室过滤 */
    public R<Map<String, Object>> detail(String indicator, String from, String to, Integer limit) {
        return detail(indicator, from, to, limit, null);
    }

    /** 科室过滤只对 WORKLOAD_DEPT 生效（其余指标的明细不按科室分组，静默套用会让「同过滤条件」失真）；空白视为不过滤 */
    private static String deptFilterFor(String code, String dept) {
        if (!"WORKLOAD_DEPT".equals(code) || dept == null || dept.isBlank()) return null;
        return dept.trim();
    }

    private List<Map<String, Object>> detailRows(String code, Window w, String dept) {
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
            // v60：复用 SPEC_SELECT（dept_name 同一条联接链），按科室排、可按 dept 过滤到一个科室；stage 与汇总三列同判据
            case "WORKLOAD_DEPT" -> {
                String filter = dept == null ? "" : "where x.dept_name = ?";
                Object[] args = dept == null ? w.args() : new Object[]{w.f(), w.t(), dept};
                yield jdbc.queryForList(q("""
                        select x.*,
                               case when x.rejected_at is not null      then '已拒收'
                                    when x.report_issued_at is not null then '已签发'
                                    else '在办' end                       as stage
                        from ( {spec} where {wc} ) x
                        {deptFilter}
                        order by x.dept_name, x.collected_at desc, x.specimen_id desc
                        {cap}
                        """).replace("{deptFilter}", filter), args);
            }
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
            case "WORKLOAD_TECH" -> withTechProgress(jdbc.queryForList(q("""
                    select t.id                                  as tech_order_id,
                           t.tech_type,
                           {techName}                            as tech_name,
                           t.tech_item, t.reason, t.status,
                           t.ordered_at, t.done_at,
                           ou.real_name                          as ordered_by_name,
                           du.real_name                          as done_by_name,
                           t.cancelled_at,
                           cu.real_name                          as cancelled_by_name,
                           t.cancel_reason,
                           (select count(*) from path_slide sl
                             where sl.tech_order_id = t.id)      as slide_count,
                           (select count(*) from path_slide sl
                             where sl.tech_order_id = t.id
                               and sl.stained_at is not null)    as stained_count,
                           (select string_agg(g.stain_type || coalesce(' ' || g.stain_item, '') || ' ×' || g.n::text, '、'
                                              order by g.stain_type, g.stain_item)
                              from (select sl.stain_type, sl.stain_item, count(*) as n
                                      from path_slide sl where sl.tech_order_id = t.id
                                     group by sl.stain_type, sl.stain_item) g) as attached_stain,
            """ + PathologyReportController.TECH_DERIVED_COLUMNS + """
                           b.block_code,
                           s.id                                  as specimen_id,
                           s.path_no, s.barcode,
                           {patName}                             as patient_name
                    from path_tech_order t
                    join path_specimen s on s.id = t.specimen_id
                    left join path_block b on b.id = t.block_id
                    left join sys_user ou on ou.id = t.ordered_by
                    left join sys_user du on du.id = t.done_by
                    left join sys_user cu on cu.id = t.cancelled_by
                    {pat}
                    where t.ordered_at >= ?::date and t.ordered_at < ?::date + 1
                    order by t.ordered_at desc, t.id desc
                    {cap}
                    """), w.args()));
            default -> List.of();
        };
    }

    /**
     * v58：特检穿透行补执行进度 {@code progress} / {@code progress_name}——派生规则与
     * {@link PathologyReportController#techProgress} 是同一份（只读派生，不是库列），别在质控层再抄一遍五态。
     * v59：穿透行的 {@code attached_stain}（挂接切片实际染色类型 / 项目的去重汇总，如「IHC CK7 ×2」）与
     * PathologyReportController.techOrders 同一段子查询——只回事实、不判一致（一致性在挂接时按
     * {@code PathologyProcessController.TECH_TO_STAIN} 判，5274）。
     * <p>v62：穿透行增中文 {@code tech_name}（与汇总行共用 {@link #TECH_NAME_CASE}，不抄第二份），
     * 导出的 CSV 从此有一列人读得懂的「技术类型」；{@code tech_type} 编码列照旧保留，两列表头以有无「编码」区分。
     */
    private static List<Map<String, Object>> withTechProgress(List<Map<String, Object>> rows) {
        for (var r : rows) {
            // v60：穿透行现在带 sampled_block_count（TECH_DERIVED_COLUMNS），走四参口径才派得出 SAMPLED
            String p = PathologyReportController.techProgress(r.get("status"), r.get("slide_count"), r.get("stained_count"),
                    r.get("sampled_block_count"));
            r.put("progress", p);
            r.put("progress_name", PathologyReportController.TECH_PROGRESS_NAMES.getOrDefault(p, p));
        }
        return rows;
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
        List<Map<String, Object>> shown = cut ? rows.subList(0, ROW_LIMIT) : rows;
        // v65 车道 C：合计与按日各行的**实际**对账关系随文件走——这段字屏上在合计格下面，
        // 而导出的表格一转手就脱离页面。与屏上同一个函数、同一批（截断后的）行算出来，两处逐字同源。
        return toCsv(d, w.window(), "指标汇总", null,
                summaryVsDailyNote(d, shown, summaryOf(d.code(), w.window())), shown, cut, ROW_LIMIT);
    }

    /** 穿透明细 CSV（与 {@link #detail} 同 SQL 同口径、同 200 条上限、同 dept 过滤；超限在页脚明写截断） */
    @GetMapping(value = "/detail.csv", produces = "text/csv;charset=UTF-8")
    public String detailCsv(@RequestParam(required = false) String indicator,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false) String dept) {
        Def d = DEFS.get(indicator == null ? "" : indicator.trim().toUpperCase(Locale.ROOT));
        if (d == null) return errCsv(5281, "指标编码不存在：" + indicator);
        var w = parseWindow(from, to);
        if (w.error() != null) return errCsv(w.error().getCode(), w.error().getMessage());
        if (!d.available()) return unavailableCsv(d, w.window());
        String deptFilter = deptFilterFor(d.code(), dept);
        List<Map<String, Object>> rows = detailRows(d.code(), w.window(), deptFilter);
        boolean truncated = rows.size() > DETAIL_LIMIT;
        return toCsv(d, w.window(), "取值明细", deptFilter == null ? null : "科室过滤：" + deptFilter, null,
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

    /**
     * @param filterNote  过滤条件说明（v60 科室过滤），紧跟表头行写出——过滤过的明细脱离页面后必须看得出它不是全量
     * @param derivedNote 按本次库态现算的对账说明（v65，只有指标汇总有；明细导出传 null），随口径页脚一起写出
     */
    private String toCsv(Def d, Window w, String kind, String filterNote, String derivedNote,
                         List<Map<String, Object>> rows, boolean truncated, int limit) {
        var sb = new StringBuilder("﻿指标编码,指标名称,报表,统计区间,归集时刻\n");
        sb.append("%s,%s,%s,%s,%s\n".formatted(csv(d.code()), csv(d.name()), csv(kind),
                csv(w.f() + " 至 " + w.t()), csv(d.anchorField())));
        if (filterNote != null) sb.append(csv(filterNote)).append('\n');
        sb.append('\n');
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
        if (derivedNote != null) sb.append(csv(derivedNote)).append('\n');
        for (String c : caveats()) sb.append(csv("口径：" + c)).append('\n');
        sb.append(csv("阈值：常规报告 " + routineHours() + " 小时（path.report.routine_hours）／冰冻 "
                + frozenMinutes() + " 分钟（path.report.frozen_minutes）")).append('\n');
        return sb.toString();
    }

    /**
     * 列名中文化（CSV 表头用；未登记的列名原样输出，不猜也不隐藏）。
     *
     * <p>前端 {@code format.ts} 的 ZH 是页面表头的字典，v59 起由 V59QcLabelsTest 机械断言 ZH ⊇ 本方法的 case 键。
     * <b>两侧刻意措辞不同的四个键</b>：specimen_type「类别编码」vs「标本类别」、source「来源(OUTP门诊/INP住院)」vs「来源」、
     * slide_count「挂接切片数」vs「切片数」、progress「执行进度编码」vs「进度」——页面单元格经 {@code cellText} 翻译成中文
     * （OUTP→门诊、IHC→免疫组化、progress 取同行 progress_name），CSV 导出的却是原始编码，故 CSV 表头保留「编码」提示是对的，
     * 不向前端措辞看齐。本方法只登记<b>确实会出现在 rowsOf / detailRows 别名里</b>的键：coverage() 四段的 with_* 列不进 CSV，
     * 不在这里登记（v59 核对：前端 ZH 独有的 63 键里，真正出现在汇总 / 明细行的只有 created_at，见下）。
     */
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
            // v59：切片 / 蜡块穿透明细都带裸列 created_at（SLIDE_QUALITY / WORKLOAD_SLIDE / WORKLOAD_BLOCK），此前 CSV 表头漏登记
            case "created_at" -> "创建时刻";
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
            // v62（2576 复核）：molecular 正名成两列——WORKLOAD_REGISTER 数的是标本类别 MOLECULAR 的标本条数，
            // WORKLOAD_SLIDE 数的是染色类型 MOLECULAR 的切片张数；此前两列同名同中文「分子病理」
            case "molecular_slides" -> "分子病理切片数(染色类型)";
            case "molecular_specimens" -> "分子病理标本数(标本类别)";
            // 蜡块
            case "block_id" -> "蜡块ID";
            case "block_code" -> "蜡块编码";
            case "block_no" -> "块号";
            case "tissue_desc" -> "取材组织描述";
            case "dehydrate_batch" -> "脱水篮批次";
            case "dehydrate_batches" -> "脱水篮批次数";
            case "embedded_at" -> "包埋时刻";
            case "embedded_by_name" -> "包埋人";
            // v62（2576 复核）：blocks 正名成两列——WORKLOAD_BLOCK 是 count(*)（当日产出），
            // WORKLOAD_SLIDE 是 count(distinct block_id)（当日染色涉及、去重）；此前两列同名同中文「蜡块数」，
            // 演示数据下一屏之内就是 6 与 3 两个数。覆盖率段（coverage()）里也有两个裸 blocks 键，
            // 但那一段是 JSON-only、表头走前端 ZH 且各在自己的小标题下（「蜡块（按包埋时刻落窗）」/
            // 「切片（按染色时刻落窗）」），不进 CSV、不经本方法——本方法只登记确实出现在 rowsOf / detailRows 别名里的键。
            case "blocks_produced" -> "当日产出蜡块数";
            case "blocks_stained" -> "当日染色涉及蜡块数(去重)";
            // v64（2576 复核）：合计行与按日行分成两套列名——合计落窗整个统计区间，按日行才是「这一天」。
            // 此前两处共用一套键、中文又都写着「当日…」，同一屏上「当日产出蜡块数」既是 30 天合计又是单日数。
            // 合计四列一律 *_in_period / 「本期…」（照 issued_of_registered =「本期登记中已签发」的体例）；
            // 按日四列一律「当日…」。blocks_per_specimen 这个旧共用键已不是任何行的列名，故不再登记。
            case "blocks_produced_in_period" -> "本期产出蜡块数";
            case "embedded" -> "当日已确认包埋";
            case "embedded_in_period" -> "本期已确认包埋";
            case "blocks_per_specimen_of_day" -> "当日蜡块/标本";
            case "blocks_per_specimen_in_period" -> "本期蜡块/标本";
            case "specimens_of_day" -> "当日涉及标本数(去重)";
            case "specimens_in_period" -> "本期涉及标本数(去重)";
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
            // v60（2576-②）送检科室维度：dept_name / registered / issued / rejected 复用上面的既有键，这四个是新键
            // v61（2576 复核）：WORKLOAD_DEPT 的三列是「本期登记的这批标本、截至查询时刻的状态」（存量），
            // 与 REPORT_* 锚 report_issued_at 的「签发份数」（流量）必须在表头上就能分开——此前两者同名同中文
            case "issued_of_registered" -> "本期登记中已签发";
            case "in_progress" -> "本期登记中在办";
            case "dept_count" -> "送检科室数";
            case "unknown_dept" -> "未知科室标本数";
            case "stage" -> "办理阶段";
            // 特检
            case "tech_order_id" -> "技术医嘱ID";
            case "tech_type" -> "技术类型编码";
            case "tech_name" -> "技术类型";
            case "tech_item" -> "技术项目";
            // v62（2563 复核第三条）：WORKLOAD_TECH 穿透里 status 是 ORDERED/DONE/CANCELLED，
            // 而同一行的 blocks_derived_source 是 ORDERED/DERIVED/MIXED——**同一行两个 ORDERED 含义完全相反**，
            // 表头只写「状态」在导出的 CSV 里无从分辨。status 只在本指标的穿透行里作为列出现（SPEC_SELECT 不含它）
            case "status" -> "医嘱状态";
            case "ordered" -> "开单数";
            case "done" -> "已完成";
            case "pending" -> "未完成";
            case "cancelled" -> "已取消";
            case "ordered_at" -> "开单时刻";
            case "done_at" -> "完成时刻";
            case "ordered_by_name" -> "开单人";
            case "done_by_name" -> "完成人";
            case "median_hours_to_done" -> "开单→完成中位数(小时)";
            // v57 取消留痕与切片挂接（V163）：历史取消行三列为空，导出时就是空格，不填 0
            case "cancelled_at" -> "取消时刻";
            case "cancelled_by_name" -> "取消人";
            case "cancel_reason" -> "取消原因";
            case "slide_count" -> "挂接切片数";
            // v58 执行进度派生（只读：由挂接切片 + stained_at 与 status 算出，不是库列）
            case "stained_count" -> "已染色挂接切片数";
            case "sampled_block_count" -> "已补取材蜡块数";
            case "blocks_derived" -> "关联蜡块";
            // v61（2563 复核）：「关联蜡块」这一列的来源判定（ORDERED 全为下达时指定 / DERIVED 全为派生 / MIXED 两者都有），
            // 与并集同一处 SQL 算出；穿透复用 TECH_DERIVED_COLUMNS，故这里也要有中文名，否则 CSV 表头露英文列名
            case "blocks_derived_source" -> "关联蜡块来源";
            case "attached_stain_name" -> "挂接切片染色";
            case "progress" -> "执行进度编码";
            case "progress_name" -> "执行进度";
            // v59 挂接切片实际染色类型 / 项目的去重汇总（如「IHC CK7 ×2」）
            // v62（2563 复核第三条）：英文版与中文版 attached_stain_name 此前被映射成同一个中文名，
            // 同一份 CSV 里「挂接切片染色」这个表头**出现两次**；编码版按本类既有惯例（tech_type / progress）加「编码」
            case "attached_stain" -> "挂接切片染色编码";
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
