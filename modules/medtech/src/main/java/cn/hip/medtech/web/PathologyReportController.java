package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v48 车道 P3：病理诊断与报告——阅片工作列表 / 初诊复诊双签 / 正式签发 /
 * 补充报告 / 特检技术医嘱 / 既往病理调取。
 *
 * <p><b>v55 可达性收口（车道 R1）在本类动了两处，既有契约只增不改</b>：
 * <ul>
 *   <li>{@link #worklist}：基础 SQL 原本写死 {@code diagnosed_at is null}，已诊断/已签发的标本
 *       <b>永久不在列表、也搜不到</b>，而报告抽屉的唯一入口就是这个列表——报告追溯与补充报告在 UI 上无路可走。
 *       {@code scope} 在既有 {@code stained}/{@code all} 之外新增 {@code diagnosed}/{@code any}，
 *       另加可选日期窗 {@code from}/{@code to}；<b>不传 = 旧行为不变</b>（含未识别取值回落 stained 这一条）。</li>
 *   <li>{@link #techOrders}：全院清单分支（不传 specimenId）前端此前零调用。新增
 *       {@code techType}/{@code urgentOnly}/{@code keyword}/{@code dateField}/{@code from}/{@code to}
 *       与 {@code status=ALL}，供全院特检工作台按状态/类型/时间筛选；不传 = 旧行为不变。</li>
 * </ul>
 * 新增检索参数非法统一返 <b>5800</b>（v55 病理可达性子段 5800–5819 在本类只用这一个码）。
 *
 * <p><b>与既有 {@link PathologyController} 的边界（逐字不动的五个端点）</b>：
 * {@code GET /api/pathology/pending}、{@code POST /specimens}、
 * {@code PUT /specimens/{barcode}/receive}、{@code PUT /specimens/{barcode}/diagnose}、
 * {@code GET /specimens} 一个字节没改，错误码 4550–4553 也没动。本控制器全部走
 * {@code /api/pathology/report/**} 新前缀，只读既有列、只写 v48 新列与新表。
 *
 * <p>四条口径：
 * <ul>
 *   <li><b>{@code report_issued_at} 不是 {@code diagnosed_at}</b>——后者是「病理医师写完诊断」
 *       （由既有 diagnose 端点写），前者是「双签完成、报告对外发布」。两者相差的那段时间正是
 *       复诊等待时长，合并成一个时刻就再也算不出来了。故 issue 只写 {@code report_issued_at}，
 *       <b>不碰 {@code diagnosed_at}，也不碰 {@code status}</b>：{@code status} 的三个取值
 *       COLLECTED/RECEIVED/DIAGNOSED 正被既有 {@code GET /specimens} 原样吐给前端，
 *       多一个 'ISSUED' 就是在改既有返回体的值域。</li>
 *   <li><b>补充报告绝不改原报告</b>：{@code path_specimen} 上的
 *       gross_finding / micro_finding / diagnosis 三列是首次报告，本控制器<b>一条 update 都没有</b>。
 *       免疫组化回报后的补充诊断、会诊意见一律 insert 进 {@code path_report} 并按 seq_no 留全历史——
 *       覆盖原报告会让「当时医生看到的是什么」永久不可考，纠纷里是致命的。</li>
 *   <li><b>双签走三态 gate {@code emr.gate.pathology.doublesign}，默认 warn</b>：
 *       off 整段旁路 / warn 不拦截但回带 {@code warnings} / block 未双签不得签发。
 *       默认 warn 的理由是存量流程可能只有一名病理医师，直接 block 会让报告发不出去；
 *       <b>坏配置回落 warn 而非 off</b>（回落成 off 等于把一个写错的配置值变成静默关闭法定校验）。</li>
 *   <li><b>复诊人不得与初诊人同一</b>（5263）——一个人签两次不叫双签，这条<b>与 gate 无关、永远生效</b>：
 *       off 的语义是「不强制走完双签即可签发」，不是「允许一个人把两个签名位都占了」。
 *       单人科室的正确用法是只做初诊、直接 issue（warn 档回带告警），而不是自签两遍。</li>
 * </ul>
 *
 * <p><b>本版如实留的缺口（没有造假实现）</b>：
 * <ul>
 *   <li><b>不做危急值登记</b>——{@code outp_critical_alert.order_id} 与 {@code registration_id}
 *       都是 not null 外键到门诊表，住院标本的病理危急值根本存不进去；只做门诊那一半会给法定闭环
 *       留一个静默漏报的口子。本控制器不碰危急值，也不新建危急值表。</li>
 *   <li><b>不做图像</b>——{@code MultipartFile} 全仓零命中，平台没有文件上传基础设施，
 *       大体图像、数字切片、免疫组化图片一概不做。</li>
 *   <li><b>取消技术医嘱不留原因——v57 已修</b>：V163 给 {@code path_tech_order} 补了
 *       cancelled_at / cancelled_by / cancel_reason 三列，{@link #cancelTechOrder} 改为必填取消原因（5271），
 *       三列与 status 在同一条 update 里落库；<b>取消原因与下达原因分列</b>，{@code reason} 一个字节不动。
 *       历史 CANCELLED 行三列永远 NULL（零回填，同 V146 cancelled_at 的处置），清单照常返回它们。</li>
 * </ul>
 *
 * <p><b>v58 特检执行进度（2563 三次核账：「执行进度是手工三态标记」「DONE 仅靠手点且不校验任何切片」）</b>：
 * <ul>
 *   <li>下达 / 完成 / 取消各在<b>同一事务</b>写 {@code path_process}（TECH_ORDER / TECH_DONE / TECH_CANCEL，
 *       V165 放开 chk_path_process_node 白名单、原 12 档一个不删），流转轨迹里能答「谁何时加做了什么、谁确认、谁为何取消」。
 *       历史医嘱不回填节点（零回填）。</li>
 *   <li>执行进度由挂接切片<b>只读派生</b>（{@link #techProgress}，不加状态列）：CANCELLED / DONE 照状态；ORDERED 且无挂接切片
 *       → PENDING_SECTION（待切片）；有挂接切片但未全部染色 → SECTIONING（切片中）；全部染色 → STAINED（已染色待确认）。
 *       两个清单分支与 PathQc WORKLOAD_TECH 穿透都带 {@code stained_count} / {@code progress} / {@code progress_name}。</li>
 *   <li>完成确认走三态 gate {@value #TECH_DONE_GATE_KEY}（默认 warn，V165 seed）：事实（挂接切片数 / 已染色数）
 *       <b>任何档位都算且返回体都带</b>，缺口 = 无挂接切片或有挂接切片未染色；block 返 5273、行仍 ORDERED、不写节点 /
 *       warn 照常 DONE 但 {@code warnings} 回带并写进 TECH_DONE 节点 / off 不判、warnings 为空数组。坏配置回落 warn。</li>
 * </ul>
 *
 * <p><b>v59（2576-③ 签发口径 + 2563 一致性）</b>：
 * <ul>
 *   <li>「报告出了」= <b>正式签发</b>：{@link #issue} 签发成功后同一事务把门诊来源的 {@code outp_order} 置 EXECUTED
 *       （与 RIS 到 verifyReport 才置同口径）。此前这一步在既有 diagnose 端点——医生站在报告尚未初签 / 复签 / 签发时
 *       就显示「已执行」，而「报告签发量」锚在 {@code report_issued_at}，同一平台对「报告出了几份」两套答案。
 *       diagnose 端点不再碰 {@code outp_order}；住院来源的 {@code inp_order} 此前 diagnose 也从未动过，维持不动。</li>
 *   <li>两个清单分支再增 {@code attached_stain}（挂接切片的 stain_type / stain_item 去重汇总，如「IHC CK7 ×2」）——
 *       挂接时的类型 / 项目一致性由 {@link PathologyProcessController#slides} 按 {@code TECH_TO_STAIN} 判（5274），
 *       这里只回事实、不判。{@code stained_count} 语义不变。</li>
 * </ul>
 *
 * <p><b>v60（2563 尾 + 2576 尾-①）</b>：
 * <ul>
 *   <li>执行进度增第六态 <b>SAMPLED</b>（已补取材待切片）：ORDERED、无挂接切片、但存在 {@code path_block.tech_order_id = t.id}
 *       的蜡块（V167，取材 {@code append=true} 带 {@code techOrderId} 时落，见 {@link PathologyProcessController#grossing}）。
 *       修复前补取材 RESAMPLE 医嘱的进度恒「待切片」——取材 append 路径不读不写医嘱，事实根本没采集。</li>
 *   <li>两个清单分支再增 {@code sampled_block_count} / {@code blocks_derived}（「蜡块」列：{@code block_id} 非空取其 block_code，
 *       否则按挂接蜡块 + 挂接切片所在块派生）/ {@code attached_stain_name}（{@code attached_stain} 的中文版，如「免疫组化 CK7 ×2」）
 *       ——三段 SQL 抽成 {@link #TECH_DERIVED_COLUMNS}，质控 WORKLOAD_TECH 穿透同用这一份。</li>
 *   <li>{@link #issue} 置 EXECUTED 的条件收紧为「<b>同一 order_id 的全部未拒收标本都已签发</b>」：多部位申请第一个部位签发不再把整张
 *       申请置「已执行」，返回体新增 {@code partsPending}；单部位申请行为不变。</li>
 * </ul>
 *
 * <p><b>错误码 5260–5275</b>（v48 诊断与报告段 5260–5279；5271–5272 v57、5273 v58、5274 v59、5275 v60 已用，5276–5279 空置）：
 * <ul>
 *   <li>5260 标本不存在（全部端点的「查无此标本」同码）</li>
 *   <li>5261 标本状态不允许该操作（已拒收 / 已签发 / 重复签名 / 并发抢写——归并同码，消息区分）</li>
 *   <li>5262 尚无诊断内容（初诊签名、正式签发、补充报告三条路径同码）</li>
 *   <li>5263 复诊人不得与初诊人为同一人（含初诊签名人未知、无从核验）</li>
 *   <li>5264 未完成初诊签名不得复诊签名</li>
 *   <li>5265 未完成双签不得签发（<b>只有 block 档才返</b>，warn 档改走 warnings）</li>
 *   <li>5266 补充报告不成立（内容为空 / 补充原因超长）</li>
 *   <li>5267 特检技术医嘱内容非法（类型或状态不在白名单 / 项目名原因超长 / 该类型必须指明项目——
 *       下达、按类型筛选、按状态筛选同码）</li>
 *   <li>5268 特检技术医嘱不存在或非待执行状态（完成、取消两条路径同码）</li>
 *   <li>5269 蜡块不存在或不属于该标本</li>
 *   <li>5270 无法识别当前登录用户，不能签名（v57 起取消技术医嘱同码：取消人解析不出就不叫留痕）</li>
 *   <li>5271 技术医嘱取消原因非法（v57：缺失 / 空白 / 超 255 字三条路径同码）</li>
 *   <li>5272 切片挂接的技术医嘱非法（v57，<b>由 {@link PathologyProcessController#slides} 返回</b>：
 *       医嘱不存在 / 不属于该蜡块所在标本 / 不是 ORDERED 三条路径同码）</li>
 *   <li>5273 特检技术医嘱无已染色挂接切片不得确认完成（v58，<b>只有 gate=block 才返</b>：挂接切片数为 0、
 *       或有挂接切片尚未染色，两条路径同码；warn 档改走 warnings、off 档不判）</li>
 *   <li>5274 切片染色类型或项目与特检医嘱不一致（v59，<b>由 {@link PathologyProcessController} 返回</b>：挂接时 stain_type
 *       与 tech_type 映射不符 / 医嘱有项目而 stain_item 不同 / 染色登记（单张、批量）把已挂接切片的项目改成别的，三条路径同码）</li>
 *   <li>5275 补取材挂接的特检医嘱非法（v60，<b>由 {@link PathologyProcessController#grossing} 返回</b>：取材 append=true 带
 *       techOrderId 时医嘱不存在 / 非 RESAMPLE 类型 / 不属于该标本 / 非 ORDERED 四条路径同码）</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pathology/report")
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP')")   // 与既有 PathologyController 同口径
public class PathologyReportController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final ConfigReader configReader;

    /** 双签 gate 配置键（V144 已随地基写入，默认值 warn） */
    public static final String DOUBLE_SIGN_GATE_KEY = "emr.gate.pathology.doublesign";

    /**
     * 特检技术医嘱完成校验 gate 配置键（v58，V165 seed，默认 warn）。
     * 默认 warn 而非 block：V163 之前的历史医嘱普遍没有挂接切片（tech_order_id 零回填），直接 block 会让存量医嘱永远完不成。
     */
    public static final String TECH_DONE_GATE_KEY = "emr.gate.pathology.techdone";

    /**
     * 执行进度六态（由 status + 挂接切片 / 挂接蜡块事实派生，不是库列，见 {@link #techProgress(Object, Object, Object, Object)}）→ 中文。
     * v60 增 SAMPLED（已补取材待切片）：介于 PENDING_SECTION 与 SECTIONING 之间。
     */
    public static final Map<String, String> TECH_PROGRESS_NAMES = Map.of(
            "PENDING_SECTION", "待切片", "SAMPLED", "已补取材待切片", "SECTIONING", "切片中", "STAINED", "已染色待确认",
            "DONE", "已完成", "CANCELLED", "已取消");

    /**
     * v60：两个清单分支与质控 WORKLOAD_TECH 穿透共用的三段派生列（以 {@code t} 为 path_tech_order 别名、
     * {@code b} 为 {@code left join path_block b on b.id = t.block_id}），紧跟在 select 列表里用、末尾自带逗号：
     * <ul>
     *   <li>{@code sampled_block_count}：{@code path_block.tech_order_id = t.id} 的蜡块数（补取材已出块，V167）；</li>
     *   <li>{@code blocks_derived}：「蜡块」列的派生文本——<b>三个来源的并集</b>：下达时指定的 {@code block_id}、
     *       为本医嘱补出的蜡块（{@code path_block.tech_order_id = t.id}）、挂接切片所在块；block_code 去重后按块号「、」相连，
     *       都没有为 NULL。<b>v60 复核修补</b>：此前写作 {@code coalesce(b.block_code, union…)}，而 v60 的补取材会把医嘱
     *       {@code block_id} 回写为本次首块（见 {@code PathologyProcessController.grossing} 的 append 分支），于是 coalesce
     *       一见非空就短路——补出 N 块只显示第 1 块，而同一行 {@code sampled_block_count} 是 N，同屏自相矛盾，
     *       且没有任何其它页面能补回缺的块码（2563 的两个独立反驳者同时指出）。改为并集后：v57「下达时指定蜡块」
     *       场景并集只有一行、输出逐字不变；v60 补取材场景 N 块全列出，与 {@code sampled_block_count} 对得上；</li>
     *   <li>{@code attached_stain_name}：挂接切片染色的中文汇总（与 {@code attached_stain} 同一去重计数，
     *       染色类型按 PathQcController SLIDE_QUALITY 的 stain_name 口径译中文：HE→HE 染色 / IHC→免疫组化 / SPECIAL→特殊染色 /
     *       其余→分子病理），如「免疫组化 CK7 ×2」，多组「、」相连，无挂接为 NULL。</li>
     * </ul>
     * 只回事实、不判一致（一致性在挂接时按 {@code TECH_TO_STAIN} 判，5274）。
     */
    public static final String TECH_DERIVED_COLUMNS = """
                       (select count(*) from path_block pb where pb.tech_order_id = t.id) as sampled_block_count,
                       (select string_agg(x.block_code, '、' order by x.block_no, x.block_code)
                          from (select b.block_code, b.block_no where b.id is not null
                                union
                                select pb.block_code, pb.block_no
                                  from path_block pb where pb.tech_order_id = t.id
                                union
                                select b2.block_code, b2.block_no
                                  from path_slide sl join path_block b2 on b2.id = sl.block_id
                                 where sl.tech_order_id = t.id) x)            as blocks_derived,
                       (select string_agg(case g.stain_type when 'HE' then 'HE 染色' when 'IHC' then '免疫组化'
                                                            when 'SPECIAL' then '特殊染色' else '分子病理' end
                                          || coalesce(' ' || g.stain_item, '') || ' ×' || g.n::text, '、'
                                          order by g.stain_type, g.stain_item)
                          from (select sl.stain_type, sl.stain_item, count(*) as n
                                  from path_slide sl where sl.tech_order_id = t.id
                                 group by sl.stain_type, sl.stain_item) g)              as attached_stain_name,
            """;

    /**
     * 法定署名行为（初诊签名 / 复诊签名 / 正式签发 / 补充报告）限病理医师与管理员。
     * <b>刻意比类级别更严</b>：TECHNICIAN 能跑制片全流程，但报告上的名字必须是医师的。
     */
    private static final String SIGNER_ROLES = "hasAnyRole('ADMIN','DOCTOR_OUTP')";

    /** 特检技术类型白名单（与 chk_path_tech_type 一致） */
    public static final List<String> TECH_TYPES = List.of(
            "DEEP_CUT", "RECUT", "RESAMPLE", "IHC", "SPECIAL_STAIN", "MOLECULAR");

    private static final Map<String, String> TECH_TYPE_NAMES = Map.of(
            "DEEP_CUT", "深切", "RECUT", "重切", "RESAMPLE", "补取材",
            "IHC", "免疫组化", "SPECIAL_STAIN", "特殊染色", "MOLECULAR", "分子病理");

    /**
     * 必须指明具体项目的技术类型：只写「免疫组化」而不写抗体，技师拿到这条医嘱不知道该做什么。
     * 深切/重切/补取材则天然没有项目名，不强制。
     */
    private static final Set<String> TECH_ITEM_REQUIRED = Set.of("IHC", "SPECIAL_STAIN", "MOLECULAR");

    /** 技术医嘱状态白名单（与 chk_path_tech_status 一致） */
    private static final List<String> TECH_STATUSES = List.of("ORDERED", "DONE", "CANCELLED");

    /** 检索类端点硬上限：超限回 truncated=true，<b>不做翻页也不静默截断</b>（照抄 v43 医嘱检索纪律） */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;

    private static final int TECH_ITEM_MAX = 64;
    private static final int REASON_MAX = 255;

    /** 日期窗最大跨度（天），与 PathologyProcessController.slideSearch 同量级 */
    private static final int MAX_SPAN_DAYS = 366;

    /** 阅片列表 scope 的取值集合：前两个是 v48 既有档，后两个是 v55 为报告追溯放开的档 */
    public static final List<String> WORKLIST_SCOPES = List.of("stained", "all", "diagnosed", "any");

    /** 全院特检清单的日期口径白名单 */
    private static final List<String> TECH_DATE_FIELDS = List.of("ORDERED", "DONE");

    // ==================================================================
    // 一、阅片工作列表
    // ==================================================================

    /**
     * 阅片工作列表：切片已完成、尚未写诊断的标本。
     *
     * <p><b>「切片已完成」的判定口径</b>：该标本名下存在 {@code path_slide.stained_at is not null}
     * 的切片（染色完成才谈得上阅片）。每行同时回带 blockCount / slideCount / stainedSlideCount，
     * 使调用方能看见判定依据而不是只拿到一个结果。
     *
     * <p><b>{@code scope=all} 的存在理由</b>：V144 之前入库的存量标本<b>一块蜡块一张切片都没有</b>
     * （零回填纪律——当时确实没采集，不许拿别的列凑），按 stained 口径它们永远不会出现在工作列表里，
     * 而它们的诊断还得写。故给一个显式档位：{@code scope=all} 列出「已核收、未拒收、未诊断」的全部标本，
     * 由调用方自己看 slideCount 判断。默认仍是 {@code stained}，<b>不默默把两类混在一起</b>。
     *
     * <p>排序：加急优先 → 核收早的优先。{@code hoursSinceReceived} 是<b>原始事实</b>（距核收的小时数），
     * 不在这里判「是否超时」——报告及时率的阈值口径归质控段（5280–5299）唯一定义，两处各判一次必然分叉。
     *
     * <p><b>v55：{@code diagnosed} / {@code any} 两档与日期窗（可达性收口）</b>。
     * 基础 SQL 原本写死 {@code diagnosed_at is null}，已诊断/已签发的标本永久不在列表也搜不到，
     * 而报告抽屉（补充报告、流转轨迹、既往病理）的唯一入口就是这个列表。故放开：
     * <ul>
     *   <li>{@code diagnosed}：已写诊断（含已签发）、未拒收——报告追溯与补充报告的入口，
     *       按 {@code coalesce(report_issued_at, diagnosed_at)} 倒序（最近出的报告在前）；</li>
     *   <li>{@code any}：已核收、未拒收的全部，不论是否诊断——拿着病理号/患者名追溯时用，按核收时刻倒序；</li>
     *   <li>{@code from}/{@code to}（yyyy-MM-dd，须同时给出，跨度 ≤ 366 天）：stained/all/any 按核收时刻，
     *       diagnosed 按 {@code coalesce(report_issued_at, diagnosed_at)}；非法返 5800。</li>
     * </ul>
     * <b>不传 scope 仍是 stained；未识别的取值仍回落 stained</b>（v48 既有行为，一个字节没改）。
     * 返回行新增 diagnosed_at / report_issued_at / first_signed_at / second_signed_at /
     * pathologist_name / supplement_count 六列——只增不改，未诊断行这几列为 null。
     *
     * @param scope stained（默认，已染色待诊断）/ all（已核收未诊断的全部，含无切片记录的存量标本）
     *              / diagnosed（已诊断含已签发）/ any（已核收未拒收的全部）
     */
    @GetMapping("/worklist")
    public R<Map<String, Object>> worklist(@RequestParam(required = false) String scope,
                                           @RequestParam(required = false) String specimenType,
                                           @RequestParam(required = false) Boolean urgentOnly,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) Integer limit) {
        String mode = scopeOf(scope);
        Window win;
        try {
            win = parseWindow(from, to);
        } catch (IllegalArgumentException e) {
            return R.fail(5800, "阅片列表检索参数非法：" + e.getMessage());
        }
        int cap = capOf(limit);

        var sql = new StringBuilder("""
                select s.id, s.barcode, s.path_no, s.part_no, s.specimen_type, s.sampling_site,
                       s.clinical_diagnosis, s.specimen_desc, s.urgent, s.status,
                       s.collected_at, s.received_at, s.fixed_at, s.fixative,
                       case when s.order_id is not null then 'OUTP' else 'INP' end as source,
                       s.order_id, s.inp_order_id,
                       coalesce(oo.item_name, io.item_name) as item_name,
                       p.id as patient_id, p.patient_no, p.name as patient_name, p.sex, p.birth_date,
                       (select count(*) from path_block b where b.specimen_id = s.id) as block_count,
                       (select count(*) from path_slide sl join path_block b on b.id = sl.block_id
                         where b.specimen_id = s.id) as slide_count,
                       (select count(*) from path_slide sl join path_block b on b.id = sl.block_id
                         where b.specimen_id = s.id and sl.stained_at is not null) as stained_slide_count,
                       (select count(*) from path_tech_order t
                         where t.specimen_id = s.id and t.status = 'ORDERED') as pending_tech_count,
                       round((extract(epoch from (now() - s.received_at)) / 3600)::numeric, 1)
                           as hours_since_received,
                       s.diagnosed_at, s.report_issued_at, s.first_signed_at, s.second_signed_at,
                       pu.real_name as pathologist_name,
                       (select count(*) from path_report rp where rp.specimen_id = s.id) as supplement_count
                from path_specimen s
                left join sys_user pu on pu.id = s.pathologist_id
                left join outp_order oo on oo.id = s.order_id
                left join outp_registration r on r.id = oo.registration_id
                left join inp_order io on io.id = s.inp_order_id
                left join inp_admission a on a.id = io.admission_id
                left join empi_patient p on p.id = coalesce(r.patient_id, a.patient_id)
                where s.rejected_at is null and s.received_at is not null
                """);
        var args = new ArrayList<Object>();

        // 片段是编译期常量，参数一律走 ?（禁止拼接 SQL）
        switch (mode) {
            case "stained" -> sql.append("""
                      and s.diagnosed_at is null
                      and exists (select 1 from path_slide sl join path_block b on b.id = sl.block_id
                                   where b.specimen_id = s.id and sl.stained_at is not null)
                    """);
            case "all" -> sql.append(" and s.diagnosed_at is null ");
            case "diagnosed" -> sql.append(" and s.diagnosed_at is not null ");
            default -> { }   // any：不限诊断状态
        }
        if (win != null) {
            sql.append("diagnosed".equals(mode)
                    ? " and coalesce(s.report_issued_at, s.diagnosed_at) >= ?::date "
                      + "and coalesce(s.report_issued_at, s.diagnosed_at) < ?::date + 1 "
                    : " and s.received_at >= ?::date and s.received_at < ?::date + 1 ");
            args.add(win.from());
            args.add(win.to());
        }
        String type = trim(specimenType);
        if (type != null) {
            sql.append(" and s.specimen_type = ? ");
            args.add(type.toUpperCase(Locale.ROOT));
        }
        if (Boolean.TRUE.equals(urgentOnly)) {
            sql.append(" and s.urgent = true ");
        }
        String kw = trim(keyword);
        if (kw != null) {
            sql.append(" and (s.barcode ilike ? or s.path_no ilike ? or p.name ilike ? or p.patient_no ilike ?) ");
            String like = "%" + escapeLike(kw) + "%";   // 通配符转义：% _ \ 一律按字面量匹配
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        // 待诊断两档：加急优先 → 核收早的优先（既有）；追溯两档：最近的在前
        sql.append(switch (mode) {
            case "diagnosed" -> " order by coalesce(s.report_issued_at, s.diagnosed_at) desc, s.id desc limit ? ";
            case "any" -> " order by s.received_at desc nulls last, s.id desc limit ? ";
            default -> " order by s.urgent desc, s.received_at asc nulls last, s.id asc limit ? ";
        });
        args.add(cap + 1);   // 多取 1 条判 truncated：只取 cap 条会让「刚好第 cap 条」漏报

        var rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean truncated = rows.size() > cap;

        var body = new LinkedHashMap<String, Object>();
        body.put("scope", mode);
        body.put("limit", cap);
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        body.put("from", win == null ? null : win.from());
        body.put("to", win == null ? null : win.to());
        body.put("note", "stained：存在已染色切片的待诊断标本；all：已核收未诊断的全部标本"
                + "（含 V144 之前无蜡块/切片记录的存量标本）；diagnosed：已写诊断（含已签发）的标本，"
                + "报告追溯与补充报告从这里进；any：已核收未拒收的全部，不论诊断状态。"
                + "hoursSinceReceived 为距核收的小时数，是否超时由病理质控端点判定，本端点不判。");
        return R.ok(body);
    }

    /** scope 解析：既有两档 + v55 两档；<b>未识别取值回落 stained</b> 是 v48 既有行为，不改 */
    private static String scopeOf(String scope) {
        String s = trim(scope);
        if (s == null) return "stained";
        String lower = s.toLowerCase(Locale.ROOT);
        return WORKLIST_SCOPES.contains(lower) ? lower : "stained";
    }

    // ==================================================================
    // 二、双签与正式签发
    // ==================================================================

    /**
     * 初诊签名。
     *
     * <p>前置：标本存在、未拒收、未签发、<b>已写诊断</b>（没有诊断内容签什么？）。
     * 落 {@code first_signer_id}/{@code first_signed_at} 与 FIRST_SIGN 流转节点。
     *
     * <p><b>无法识别当前登录用户时直接拒（5270）</b>而不是落一个 signer_id 为 null 的签名：
     * 匿名的「初诊签名」会让复诊环节永远无从核验「是不是同一个人签的」，双签就此形同虚设。
     */
    @PutMapping("/{specimenId}/first-sign")
    @PreAuthorize(SIGNER_ROLES)
    @Transactional
    public R<Map<String, Object>> firstSign(@PathVariable Long specimenId,
                                            @RequestParam(required = false) String remark,
                                            Authentication auth) {
        var h = head(specimenId);
        if (h == null) return R.fail(5260, "标本不存在：" + specimenId);
        R<Map<String, Object>> blocked = notSignable(h);
        if (blocked != null) return blocked;
        if (blank(h.get("diagnosis"))) return R.fail(5262, "该标本尚未书写病理诊断，不能初诊签名");
        if (h.get("first_signed_at") != null) return R.fail(5261, "该标本已完成初诊签名，不可重复签名");

        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5270, "无法识别当前登录用户，不能完成初诊签名");

        // 条件更新 + 受影响行数即并发闸门（照抄 8014 纪律，不做读-判-写）
        var updated = jdbc.queryForList("""
                update path_specimen set first_signer_id = ?, first_signed_at = now()
                where id = ? and first_signed_at is null
                  and rejected_at is null and report_issued_at is null
                returning first_signed_at
                """, uid, specimenId);
        if (updated.isEmpty()) return R.fail(5261, "该标本状态已变化（并发签名或已签发），本次初诊签名未生效");

        logProcess(specimenId, "FIRST_SIGN", uid, trimTo(remark, REASON_MAX));

        var body = new LinkedHashMap<String, Object>();
        body.put("specimenId", specimenId);
        body.put("firstSignerId", uid);
        body.put("firstSignedAt", updated.get(0).get("first_signed_at"));
        body.put("doubleSignGate", gate());
        return R.ok(body);
    }

    /**
     * 复诊签名（上级医师复核）。
     *
     * <p><b>复诊人不得与初诊人为同一人（5263）</b>——一个人签两次不叫双签。这条<b>与 gate 无关</b>：
     * gate 管的是「未走完双签能不能签发」，不是「能不能一个人把两个签名位都占了」。
     * 初诊签名人未知（历史数据 first_signer_id 为空）时同样返 5263——无从核验就不能盖章放行。
     */
    @PutMapping("/{specimenId}/second-sign")
    @PreAuthorize(SIGNER_ROLES)
    @Transactional
    public R<Map<String, Object>> secondSign(@PathVariable Long specimenId,
                                             @RequestParam(required = false) String remark,
                                             Authentication auth) {
        var h = head(specimenId);
        if (h == null) return R.fail(5260, "标本不存在：" + specimenId);
        R<Map<String, Object>> blocked = notSignable(h);
        if (blocked != null) return blocked;
        if (h.get("first_signed_at") == null) return R.fail(5264, "该标本尚未完成初诊签名，不能复诊签名");
        if (h.get("second_signed_at") != null) return R.fail(5261, "该标本已完成复诊签名，不可重复签名");

        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5270, "无法识别当前登录用户，不能完成复诊签名");

        Long first = asLong(h.get("first_signer_id"));
        if (first == null) {
            return R.fail(5263, "该标本的初诊签名人未知，无法核验复诊人与初诊人是否为同一人，不能复诊签名");
        }
        if (first.equals(uid)) return R.fail(5263, "复诊签名人不能与初诊签名人为同一人（一个人签两次不构成双签）");

        var updated = jdbc.queryForList("""
                update path_specimen set second_signer_id = ?, second_signed_at = now()
                where id = ? and second_signed_at is null and first_signed_at is not null
                  and first_signer_id is not null and first_signer_id <> ?
                  and rejected_at is null and report_issued_at is null
                returning second_signed_at
                """, uid, specimenId, uid);
        if (updated.isEmpty()) return R.fail(5261, "该标本状态已变化（并发签名或已签发），本次复诊签名未生效");

        logProcess(specimenId, "SECOND_SIGN", uid, trimTo(remark, REASON_MAX));

        var body = new LinkedHashMap<String, Object>();
        body.put("specimenId", specimenId);
        body.put("firstSignerId", first);
        body.put("secondSignerId", uid);
        body.put("secondSignedAt", updated.get(0).get("second_signed_at"));
        body.put("doubleSignGate", gate());
        return R.ok(body);
    }

    /**
     * 正式签发：写 {@code report_issued_at} 与 ISSUE 流转节点。
     *
     * <p><b>path_specimen 上只写 report_issued_at</b>——{@code diagnosed_at}（写完诊断的时刻）、
     * {@code status}、gross/micro/diagnosis 三列全部一字不动。
     *
     * <p>双签校验按 {@code emr.gate.pathology.doublesign} 三态：
     * off 整段跳过（返回体 warnings 为空数组）；warn <b>放行并回带 warnings</b>；block 返 5265。
     * 三档都会把「本次是否完整双签」写进 ISSUE 节点的 remark——
     * warn 档放行不等于没发生过，事后追责得能查到当时是谁在缺签的情况下发的报告。
     *
     * <p><b>v59（2576-③）：签发成功后同一事务把门诊申请置 EXECUTED</b>——
     * {@code update outp_order set status='EXECUTED' where id=(该标本 order_id) and status='CHARGED'}，
     * 与 RIS 检查到 verifyReport 才置 EXECUTED（MedTechController）同形同口径。此前这条 update 在既有 diagnose
     * 端点里：开单医生在报告尚未初签 / 复签 / 签发时就看到病理「已执行」，而质控的「报告签发量」锚在
     * {@code report_issued_at}。被 5265（gate=block 缺双签）、5260 / 5261 / 5262 拦下的签发走不到这里，不置。
     * <b>住院来源的 {@code inp_order} 维持不动</b>：diagnose 此前也从未动过它（只按 {@code order_id} 更新门诊表），
     * 本版不新开住院侧联动——那是住院医嘱执行状态机的事，不在签发端点里顺手改。
     * 返回体只加一个键 {@code orderExecuted}（本次是否真的把一条 CHARGED 门诊申请置成了 EXECUTED；住院来源、
     * 或门诊申请已不是 CHARGED 时为 false），既有键不动。
     *
     * <p><b>v60（2576 尾-①）：多部位申请的 EXECUTED 口径</b>——一张门诊病理申请可登记多个部位（{@code path_specimen.part_no}），
     * 每个部位各出各的报告。此前第 1 个部位一签发就把整张 {@code outp_order} 置 EXECUTED，不看同申请其余部位：
     * 医生站显示「已执行」时另外两个部位可能还在切片。现在置 EXECUTED 的条件收紧为
     * 「<b>同一 {@code order_id} 的全部未拒收标本都已签发</b>」——同一条 update 里用 {@code not exists}
     * 判「还有没有 {@code rejected_at is null and report_issued_at is null} 的兄弟部位」（本标本的 report_issued_at
     * 已在同一事务里落下，所以它自己不算）。已拒收的部位不算未完成（拒收不删行、不改 status，按 rejected_at 排除）；
     * 单部位申请与此前行为逐字相同（没有兄弟部位 → 立即 EXECUTED）。返回体新增 {@code partsPending}
     * （同一申请、未拒收、未签发的部位数；本次签发后为 0 即全部完成；住院来源按 inp_order_id 同口径计数、只作信息）。
     * 登记只认 CHARGED（5201）的纪律不变：各部位仍须在最后一个部位签发之前登记完。
     */
    @PutMapping("/{specimenId}/issue")
    @PreAuthorize(SIGNER_ROLES)
    @Transactional
    public R<Map<String, Object>> issue(@PathVariable Long specimenId, Authentication auth) {
        var h = head(specimenId);
        if (h == null) return R.fail(5260, "标本不存在：" + specimenId);
        if (h.get("rejected_at") != null) return R.fail(5261, "该标本已拒收，不能签发报告");
        if (h.get("report_issued_at") != null) {
            return R.fail(5261, "该标本报告已于 " + h.get("report_issued_at") + " 签发，不可重复签发");
        }
        if (blank(h.get("diagnosis"))) return R.fail(5262, "该标本尚未书写病理诊断，不能签发报告");

        // 事实与判定分开算：missing 是「双签到底缺了什么」的客观事实，**任何档位都照算**；
        // gate 只决定「拿这个事实怎么办」。off 档若也跳过计算，返回体的 doubleSignComplete
        // 就会在缺签时谎报 true，流转节点里也会留下一条与事实相反的记录。
        var missing = new ArrayList<String>();
        if (h.get("first_signed_at") == null) missing.add("初诊签名");
        if (h.get("second_signed_at") == null) missing.add("复诊签名");
        Long first = asLong(h.get("first_signer_id"));
        Long second = asLong(h.get("second_signer_id"));
        // 防御性：正常路径下 5263 已拦住，此处兜住历史数据/直连改库造出的同人双签
        if (first != null && first.equals(second)) missing.add("初诊与复诊为同一人");

        String gate = gate();
        if (!missing.isEmpty() && "block".equals(gate)) {
            return R.fail(5265, "未完成双签不得签发：缺 " + String.join("、", missing)
                    + "（gate " + DOUBLE_SIGN_GATE_KEY + "=block）");
        }
        var warnings = new ArrayList<String>();
        if (!missing.isEmpty() && !"off".equals(gate)) {
            warnings.add("未完成双签即签发：缺 " + String.join("、", missing)
                    + "（gate=" + gate + " 已放行，本次签发已记入流转节点）");
        }

        Long uid = currentUserService.idOf(auth);
        var updated = jdbc.queryForList("""
                update path_specimen set report_issued_at = now()
                where id = ? and report_issued_at is null and rejected_at is null
                returning report_issued_at
                """, specimenId);
        if (updated.isEmpty()) return R.fail(5261, "该标本状态已变化（并发签发或已拒收），本次签发未生效");

        logProcess(specimenId, "ISSUE", uid,
                missing.isEmpty() ? "双签完整" : "缺" + String.join("、", missing) + "（gate=" + gate + " 放行）");

        // v59（2576-③）：「报告出了」= 正式签发——门诊申请在此置 EXECUTED（此前在 diagnose）；inp_order 不碰。
        // v60（2576 尾-①）：只有同一申请的全部未拒收部位都已签发才置——not exists 在同一条 update 里判，
        // 本标本的 report_issued_at 已在上面同一事务落下，所以它自己不会被算成「未签发的兄弟部位」
        // v60 审阅修补：结算逻辑抽成 settleOutpOrderExecuted——先 select … for update 锁申请行再判。
        // 没有锁时并发签发最后两个部位，READ COMMITTED 下两边都看不见对方未提交的 report_issued_at、各自命中 0 行，
        // 申请永久停在 CHARGED（审阅者两会话复现）；拒收端点也调同一处（先签部分再拒收剩余的死局）。
        boolean executedNow = settleOutpOrderExecuted(jdbc, specimenId);
        // 同一申请（门诊按 order_id、住院按 inp_order_id）未拒收、未签发的部位数——本次之后为 0 即全部完成
        Long partsPending = jdbc.queryForObject("""
                select count(*) from path_specimen x, path_specimen me
                where me.id = ? and x.rejected_at is null and x.report_issued_at is null
                  and ((me.order_id is not null and x.order_id = me.order_id)
                       or (me.inp_order_id is not null and x.inp_order_id = me.inp_order_id))
                """, Long.class, specimenId);

        var body = new LinkedHashMap<String, Object>();
        body.put("specimenId", specimenId);
        body.put("reportIssuedAt", updated.get(0).get("report_issued_at"));
        body.put("diagnosedAt", h.get("diagnosed_at"));
        body.put("doubleSignGate", gate);
        body.put("doubleSignComplete", missing.isEmpty());
        body.put("warnings", warnings);
        body.put("orderExecuted", executedNow);
        body.put("partsPending", partsPending == null ? 0L : partsPending);   // v60：同申请未签发未拒收部位数
        return R.ok(body);
    }

    /**
     * v60 审阅修补：结算门诊申请的执行状态（签发与拒收共用）。
     *
     * <p>先 {@code select … for update} 锁申请行，再判「至少一个未拒收部位已签发 且 没有未拒收未签发的部位」。
     * 并发签发最后两个部位时，第二个事务在锁上等第一个提交，READ COMMITTED 下 update 重新求值就看得见对方的
     * {@code report_issued_at}；没有锁时两边都看不见对方、各自命中 0 行，申请永久停在 CHARGED——重复签发 5261、
     * 执行站 7004「由病理科签发后自动置执行」永不兑现、退费被 5005 挡（审阅者两 psql 会话复现）。
     * 拒收也要结算：先签发部分部位、再拒收剩余部位时，最后那次拒收就是条件成立的时刻。
     * 全部部位都拒收（没有已签发部位）不置——什么也没发布，退费此时应当放行。
     * 住院来源（order_id 为空）不结算，返回 false。
     */
    public static boolean settleOutpOrderExecuted(JdbcTemplate jdbc, Long specimenId) {
        Long orderId = jdbc.query("select order_id from path_specimen where id = ?",
                rs -> rs.next() ? (Long) rs.getObject("order_id") : null, specimenId);
        if (orderId == null) return false;
        jdbc.queryForList("select id from outp_order where id = ? for update", orderId);
        return jdbc.update("""
                update outp_order o set status = 'EXECUTED'
                where o.id = ? and o.status = 'CHARGED'
                  and exists (select 1 from path_specimen p
                               where p.order_id = o.id and p.rejected_at is null and p.report_issued_at is not null)
                  and not exists (select 1 from path_specimen x
                                   where x.order_id = o.id and x.rejected_at is null and x.report_issued_at is null)
                """, orderId) == 1;
    }

    // ==================================================================
    // 三、补充报告（只增不改）
    // ==================================================================

    public record SupplementReq(String content, String reason) {}

    /**
     * 补充报告：{@code path_report} 内 seq_no 自增，<b>原报告一字不动</b>。
     *
     * <p>{@code path_specimen} 上的 gross_finding / micro_finding / diagnosis
     * 在本方法里连读都只读来判空，绝无 update——免疫组化回报后的补充诊断是<b>新增一份文书</b>，
     * 不是修订旧文书。覆盖原报告会让「当时医生看到的是什么」永久不可考。
     *
     * <p>seq_no 由单条 insert 内的子查询取 {@code max+1}；并发下靠
     * {@code uq_path_report_seq(specimen_id, seq_no)} 兜底（撞号由全局兜底返 4090 唯一冲突，
     * 调用方重试即可）——<b>不做「查了再写」的两段式</b>，那个窗口更大。
     */
    @PostMapping("/{specimenId}/supplement")
    @PreAuthorize(SIGNER_ROLES)
    @Transactional
    public R<Map<String, Object>> supplement(@PathVariable Long specimenId,
                                             @RequestBody SupplementReq req,
                                             Authentication auth) {
        String content = req == null ? null : trim(req.content());
        if (content == null) return R.fail(5266, "补充报告内容不能为空");
        String reason = req == null ? null : trim(req.reason());
        if (reason != null && reason.length() > REASON_MAX) {
            return R.fail(5266, "补充报告原因超长（最多 " + REASON_MAX + " 字，当前 " + reason.length() + " 字）");
        }

        var h = head(specimenId);
        if (h == null) return R.fail(5260, "标本不存在：" + specimenId);
        if (h.get("rejected_at") != null) return R.fail(5261, "该标本已拒收，不能出补充报告");
        if (blank(h.get("diagnosis"))) {
            return R.fail(5262, "该标本尚未书写首次病理诊断，不能出补充报告（补充报告是对已有报告的追加）");
        }

        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5270, "无法识别当前登录用户，不能出具补充报告");

        var ins = jdbc.queryForList("""
                insert into path_report(specimen_id, seq_no, content, reason,
                                        signer_id, signed_at, created_by)
                values (?, (select coalesce(max(seq_no), 0) + 1 from path_report where specimen_id = ?),
                        ?, ?, ?, now(), ?)
                returning id, seq_no, created_at, signed_at
                """, specimenId, specimenId, content, reason, uid, uid);
        var row = ins.get(0);

        logProcess(specimenId, "SUPPLEMENT", uid, "补充报告 #" + row.get("seq_no"));

        var body = new LinkedHashMap<String, Object>();
        body.put("id", row.get("id"));
        body.put("specimenId", specimenId);
        body.put("seqNo", row.get("seq_no"));
        body.put("signerId", uid);
        body.put("signedAt", row.get("signed_at"));
        body.put("note", "补充报告为追加文书，原报告（gross/micro/diagnosis）未被修改");
        return R.ok(body);
    }

    /**
     * 报告全景：首次报告 + 全部补充报告，按时间序。
     *
     * <p>{@code primary} 取自 {@code path_specimen} 的三列（seqNo=0），
     * {@code supplements} 取自 {@code path_report} 按 seq_no 升序——两者<b>不合并成一个数组</b>：
     * 前者是标本主记录上的首次报告、后者是独立文书，来源不同就该在返回体里看得出来。
     * 另附 {@code process} 流转节点（谁在什么时候做了什么），供纠纷时还原时间线。
     */
    @GetMapping("/{specimenId}/reports")
    public R<Map<String, Object>> reports(@PathVariable Long specimenId,
                                          @RequestParam(required = false) Integer limit) {
        var heads = jdbc.queryForList("""
                select s.id, s.barcode, s.path_no, s.part_no, s.specimen_type, s.sampling_site,
                       s.clinical_diagnosis, s.specimen_desc, s.urgent, s.status,
                       s.gross_finding, s.micro_finding, s.diagnosis,
                       s.collected_at, s.received_at, s.diagnosed_at, s.report_issued_at,
                       s.rejected_at, s.reject_reason,
                       s.pathologist_id, pu.real_name as pathologist_name,
                       s.first_signer_id, s.first_signed_at, f.real_name as first_signer_name,
                       s.second_signer_id, s.second_signed_at, sd.real_name as second_signer_name,
                       case when s.order_id is not null then 'OUTP' else 'INP' end as source,
                       p.id as patient_id, p.patient_no, p.name as patient_name, p.sex, p.birth_date
                from path_specimen s
                left join sys_user pu on pu.id = s.pathologist_id
                left join sys_user f  on f.id  = s.first_signer_id
                left join sys_user sd on sd.id = s.second_signer_id
                left join outp_order oo on oo.id = s.order_id
                left join outp_registration r on r.id = oo.registration_id
                left join inp_order io on io.id = s.inp_order_id
                left join inp_admission a on a.id = io.admission_id
                left join empi_patient p on p.id = coalesce(r.patient_id, a.patient_id)
                where s.id = ?
                """, specimenId);
        if (heads.isEmpty()) return R.fail(5260, "标本不存在：" + specimenId);
        var h = heads.get(0);

        int cap = capOf(limit);
        var sups = jdbc.queryForList("""
                select rp.id, rp.seq_no, rp.content, rp.reason, rp.signer_id,
                       u.real_name as signer_name, rp.signed_at, rp.created_at
                from path_report rp
                left join sys_user u on u.id = rp.signer_id
                where rp.specimen_id = ?
                order by rp.seq_no asc
                limit ?
                """, specimenId, cap + 1);
        boolean truncated = sups.size() > cap;

        var primary = new LinkedHashMap<String, Object>();
        primary.put("seqNo", 0);
        primary.put("kind", "PRIMARY");
        primary.put("grossFinding", h.get("gross_finding"));
        primary.put("microFinding", h.get("micro_finding"));
        primary.put("diagnosis", h.get("diagnosis"));
        primary.put("pathologistId", h.get("pathologist_id"));
        primary.put("pathologistName", h.get("pathologist_name"));
        primary.put("diagnosedAt", h.get("diagnosed_at"));
        primary.put("reportIssuedAt", h.get("report_issued_at"));
        primary.put("firstSignerId", h.get("first_signer_id"));
        primary.put("firstSignerName", h.get("first_signer_name"));
        primary.put("firstSignedAt", h.get("first_signed_at"));
        primary.put("secondSignerId", h.get("second_signer_id"));
        primary.put("secondSignerName", h.get("second_signer_name"));
        primary.put("secondSignedAt", h.get("second_signed_at"));

        var body = new LinkedHashMap<String, Object>();
        body.put("specimen", h);
        body.put("hasPrimary", !blank(h.get("diagnosis")));
        body.put("primary", primary);
        body.put("supplements", truncated ? sups.subList(0, cap) : sups);
        body.put("truncated", truncated);
        body.put("limit", cap);
        body.put("process", jdbc.queryForList("""
                select pr.id, pr.node, pr.occurred_at, pr.operator_id,
                       u.real_name as operator_name, pr.remark
                from path_process pr
                left join sys_user u on u.id = pr.operator_id
                where pr.specimen_id = ?
                order by pr.occurred_at asc, pr.id asc
                limit ?
                """, specimenId, MAX_LIMIT));
        body.put("doubleSignGate", gate());
        body.put("note", "primary 为首次报告（存于 path_specimen，补充报告绝不覆盖它）；"
                + "supplements 按 seq_no 升序，即出具时间序。");
        return R.ok(body);
    }

    // ==================================================================
    // 四、特检技术医嘱（深切 / 重切 / 补取 / 免疫组化 / 特殊染色 / 分子）
    // ==================================================================

    public record TechOrderReq(Long specimenId, Long blockId, String techType,
                               String techItem, String reason) {}

    /** 类型字典（前端下拉的唯一取值来源，避免白名单在前后端各写一份走样） */
    @GetMapping("/tech-orders/dict")
    public R<Map<String, Object>> techDict() {
        var types = TECH_TYPES.stream()
                .map(c -> Map.of("value", c, "label", TECH_TYPE_NAMES.getOrDefault(c, c),
                        "itemRequired", TECH_ITEM_REQUIRED.contains(c)))
                .toList();
        return R.ok(Map.of("techTypes", types, "statuses", TECH_STATUSES));
    }

    /**
     * 下达特检技术医嘱。
     *
     * <p>挂在 specimen 上而不是新开一次申请——病理医师看完 HE 片后加做深切/免疫组化，
     * 是诊断环节的正常延伸。{@code blockId} 可空（补取材没有对应蜡块），
     * 但一旦指定就<b>必须属于本标本</b>（5269）：给别的标本的蜡块下免疫组化，
     * 出来的结果会挂到错的病人头上。
     */
    @PostMapping("/tech-orders")
    @Transactional
    public R<Map<String, Object>> createTechOrder(@RequestBody TechOrderReq req, Authentication auth) {
        if (req == null || req.specimenId() == null) return R.fail(5260, "标本不存在：未指定 specimenId");
        var h = head(req.specimenId());
        if (h == null) return R.fail(5260, "标本不存在：" + req.specimenId());
        if (h.get("rejected_at") != null) return R.fail(5261, "该标本已拒收，不能下达特检技术医嘱");

        String type = trim(req.techType());
        if (type == null || !TECH_TYPES.contains(type)) {
            return R.fail(5267, "特检技术类型非法（" + String.join("/", TECH_TYPES) + "）");
        }
        String item = trim(req.techItem());
        if (item != null && item.length() > TECH_ITEM_MAX) {
            return R.fail(5267, "特检项目名称超长（最多 " + TECH_ITEM_MAX + " 字）");
        }
        if (item == null && TECH_ITEM_REQUIRED.contains(type)) {
            return R.fail(5267, TECH_TYPE_NAMES.getOrDefault(type, type)
                    + "必须指明具体项目（如 CK7、Ki-67、PAS），否则技师无从执行");
        }
        String reason = trim(req.reason());
        if (reason != null && reason.length() > REASON_MAX) {
            return R.fail(5267, "特检医嘱原因超长（最多 " + REASON_MAX + " 字）");
        }
        if (req.blockId() != null) {
            Integer owned = jdbc.queryForObject(
                    "select count(*) from path_block where id = ? and specimen_id = ?",
                    Integer.class, req.blockId(), req.specimenId());
            if (owned == null || owned == 0) {
                return R.fail(5269, "蜡块不存在或不属于该标本：blockId=" + req.blockId()
                        + "，specimenId=" + req.specimenId());
            }
        }

        Long uid = currentUserService.idOf(auth);
        var ins = jdbc.queryForList("""
                insert into path_tech_order(specimen_id, block_id, tech_type, tech_item, reason, ordered_by)
                values (?, ?, ?, ?, ?, ?)
                returning id, status, ordered_at
                """, req.specimenId(), req.blockId(), type, item, reason, uid);
        var row = ins.get(0);
        // v58：下达进流转节点（同一事务）。V165 之前 'TECH_ORDER' 会直接撞 chk_path_process_node，所以此前这里什么都没写
        logProcess(req.specimenId(), "TECH_ORDER", uid,
                "下达特检医嘱 " + techLabel(row.get("id"), type, item) + (reason == null ? "" : "：" + reason));

        var body = new LinkedHashMap<String, Object>();
        body.put("id", row.get("id"));
        body.put("specimenId", req.specimenId());
        body.put("blockId", req.blockId());
        body.put("techType", type);
        body.put("techTypeName", TECH_TYPE_NAMES.getOrDefault(type, type));
        body.put("techItem", item);
        body.put("status", row.get("status"));
        body.put("orderedAt", row.get("ordered_at"));
        // 刚下达的医嘱不可能有挂接切片：进度恒为待切片（派生，不是库列）
        body.put("progress", "PENDING_SECTION");
        body.put("progressName", TECH_PROGRESS_NAMES.get("PENDING_SECTION"));
        return R.ok(body);
    }

    /**
     * 完成特检技术医嘱（v58 起带完成校验 gate {@value #TECH_DONE_GATE_KEY}）。
     *
     * <p>事实与判定分开算（照抄 {@link #issue} 的双签口径）：{@code slideCount}（挂接到该医嘱的切片数）与
     * {@code stainedCount}（其中 {@code stained_at} 非空的数）<b>任何档位都算、返回体都带</b>；
     * 缺口 = slideCount == 0，或 stainedCount &lt; slideCount。
     * <ul>
     *   <li>block 且有缺口：返 5273，行仍 ORDERED，不写节点；</li>
     *   <li>warn 且有缺口：照常置 DONE，返回体 {@code warnings} 回带，TECH_DONE 节点 remark 写明「gate=warn 放行」——
     *       放行不等于没发生过，事后得能查到是谁在没片子的情况下点的完成；</li>
     *   <li>off：不判，照常 DONE，返回体仍带两个事实、warnings 为空数组。</li>
     * </ul>
     * 坏配置回落 warn 而非 off。5268（不存在 / 不是待执行）与 5270（完成人解析不出）两条既有守卫不变。
     *
     * <p>先读事实再<b>条件更新</b>：update 仍带 {@code and status = 'ORDERED'}，并发抢完成时后到的一方照旧吃 5268，
     * 不存在「事实按 A 算、行按 B 改」的中间态。
     */
    @PutMapping("/tech-orders/{id}/done")
    @Transactional
    public R<Map<String, Object>> doneTechOrder(@PathVariable Long id, Authentication auth) {
        // v57 审阅补：与 cancelTechOrder 同口径——完成人解析不出就不叫留痕，不把 done_by 静默写成 NULL
        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5270, "无法识别当前登录用户，不能完成特检技术医嘱");

        var facts = jdbc.queryForList("""
                select t.id, t.specimen_id, t.tech_type, t.tech_item, t.status,
                       (select count(*) from path_slide sl where sl.tech_order_id = t.id)          as slide_count,
                       (select count(*) from path_slide sl
                         where sl.tech_order_id = t.id and sl.stained_at is not null)              as stained_count
                from path_tech_order t
                where t.id = ?
                """, id);
        if (facts.isEmpty() || !"ORDERED".equals(facts.get(0).get("status"))) {
            return R.fail(5268, "特检技术医嘱不存在或不是待执行状态：id=" + id);
        }
        var f = facts.get(0);
        long slideCount = ((Number) f.get("slide_count")).longValue();
        long stainedCount = ((Number) f.get("stained_count")).longValue();
        String label = techLabel(id, f.get("tech_type"), f.get("tech_item"));
        String gap = slideCount == 0 ? "无挂接切片（slide_count=0）"
                : stainedCount < slideCount ? "挂接 " + slideCount + " 片中 " + (slideCount - stainedCount) + " 片尚未染色"
                : null;

        String gate = techDoneGate();
        if (gap != null && "block".equals(gate)) {
            return R.fail(5273, "特检技术医嘱无已染色挂接切片不得确认完成：" + label + "，" + gap
                    + "（gate " + TECH_DONE_GATE_KEY + "=block）");
        }
        var warnings = new ArrayList<String>();
        if (gap != null && !"off".equals(gate)) {
            warnings.add("无已染色挂接切片即确认完成（gate=warn 放行）：" + label + "，" + gap
                    + "，本次完成已记入流转节点");
        }

        var updated = jdbc.queryForList("""
                update path_tech_order set status = 'DONE', done_at = now(), done_by = ?
                where id = ? and status = 'ORDERED'
                returning id, specimen_id, tech_type, tech_item, status, done_at
                """, uid, id);
        if (updated.isEmpty()) return R.fail(5268, "特检技术医嘱不存在或不是待执行状态：id=" + id);

        logProcess(asLong(f.get("specimen_id")), "TECH_DONE", uid,
                "确认完成特检医嘱 " + label + "，挂接 " + slideCount + " 片 / 已染色 " + stainedCount
                        + (gap == null ? "" : "；" + gap + "（gate=" + gate + " 放行）"));

        var body = new LinkedHashMap<String, Object>(updated.get(0));
        body.put("slideCount", slideCount);
        body.put("stainedCount", stainedCount);
        body.put("stainedComplete", gap == null);
        body.put("progress", "DONE");
        body.put("progressName", TECH_PROGRESS_NAMES.get("DONE"));
        body.put("techDoneGate", gate);
        body.put("warnings", warnings);
        return R.ok(body);
    }

    public record CancelTechOrderReq(String reason) {}

    /**
     * 取消特检技术医嘱（v57 起留痕）。
     *
     * <p>{@code reason} 必填——缺失 / 空白 / 超 {@value #REASON_MAX} 字三条路径同返 5271：
     * 取消是诊断环节的一次决策，「为什么不做了」与「当初为什么要做」同样要留。
     * <b>取消原因写 {@code cancel_reason}，下达原因 {@code reason} 一个字节不动</b>——两者分列是 V163 的既定口径。
     *
     * <p>取消时刻、取消人、取消原因与 status 在<b>同一条 update</b> 里落库（条件更新 + 受影响行数判定，
     * 不做读-判-写），不存在「状态变了、留痕没写」的中间态；时刻取库端 {@code now()}，与 done_at 同源。
     * 取消人解析不出返 5270——留痕缺了「谁」就不叫留痕，不把 cancelled_by 静默写成 NULL。
     * 5268（不存在 / 不是待执行）路径不变。
     *
     * <p>历史 CANCELLED 行（V163 之前取消的）三列永远为 NULL，本端点不回填，清单端点照常返回它们。
     *
     * <p>v58：同一事务再写 TECH_CANCEL 流转节点（操作人 = 取消人，remark 带医嘱标识与取消原因），
     * 流转轨迹里能直接答「这条免疫组化谁取消的、何时、为什么」。
     */
    @PutMapping("/tech-orders/{id}/cancel")
    @Transactional
    public R<Map<String, Object>> cancelTechOrder(@PathVariable Long id,
                                                  @RequestBody(required = false) CancelTechOrderReq req,
                                                  Authentication auth) {
        String reason = req == null ? null : trim(req.reason());
        if (reason == null) return R.fail(5271, "技术医嘱取消原因不能为空：id=" + id);
        if (reason.length() > REASON_MAX) {
            return R.fail(5271, "技术医嘱取消原因超长（最多 " + REASON_MAX + " 字，收到 " + reason.length() + " 字）");
        }
        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5270, "无法识别当前登录用户，不能取消特检技术医嘱（取消人须留痕）");

        var updated = jdbc.queryForList("""
                update path_tech_order
                   set status = 'CANCELLED', cancelled_at = now(), cancelled_by = ?, cancel_reason = ?
                 where id = ? and status = 'ORDERED'
                 returning id, specimen_id, block_id, tech_type, tech_item, reason, status,
                           cancelled_at, cancelled_by, cancel_reason
                """, uid, reason, id);
        if (updated.isEmpty()) return R.fail(5268, "特检技术医嘱不存在或不是待执行状态：id=" + id);
        var row = updated.get(0);
        logProcess(asLong(row.get("specimen_id")), "TECH_CANCEL", uid,
                "取消特检医嘱 " + techLabel(row.get("id"), row.get("tech_type"), row.get("tech_item")) + "：" + reason);
        var body = new LinkedHashMap<String, Object>(row);
        body.put("progress", "CANCELLED");
        body.put("progressName", TECH_PROGRESS_NAMES.get("CANCELLED"));
        return R.ok(body);
    }

    /**
     * 特检技术医嘱追踪。
     *
     * <p>{@code specimenId} 可空——不传即技师侧的全院待执行清单（默认只看 ORDERED）；
     * 传了则是该标本的全部技术医嘱（默认全状态）。两种用法的默认状态刻意不同：
     * 全院清单默认拉全状态会把历史全捞出来，标本清单只看 ORDERED 又看不到已完成的项目。
     *
     * <p><b>v55 全院特检工作台（可达性收口）</b>：全院清单分支此前前端零调用。新增
     * {@code status=ALL}（不按状态过滤）、{@code techType}（白名单外返 5267，与类注释「按类型筛选同码」一致）、
     * {@code urgentOnly}、{@code keyword}（条码 / 病理号 / 患者 / 患者号 / 项目名）、
     * {@code dateField}=ORDERED（默认，按开单时刻）|DONE（按完成时刻）与 {@code from}/{@code to}
     * （须同时给出，跨度 ≤ 366 天）。日期与口径参数非法返 5800。<b>全部不传 = 旧行为不变</b>。
     * 返回行新增 {@code hours_since_ordered}（距开单小时数，原始事实，不判超时）与
     * {@code tech_type_name}——只增不改。
     *
     * <p><b>v57 取消留痕与切片挂接</b>：两个分支的返回行再增 {@code cancelled_at} / {@code cancelled_by} /
     * {@code cancelled_by_name} / {@code cancel_reason}（V163 之前取消的历史行四列为 NULL，前端须显式标「历史取消」
     * 而不是画成 0 或空白）与 {@code slide_count}（{@code path_slide.tech_order_id = t.id} 的计数，
     * 即这条医嘱实际产出了几张片）。仍是只增不改。
     *
     * <p><b>v58 执行进度派生</b>：两个分支再增 {@code stained_count}（挂接切片中 stained_at 非空的数）、
     * {@code progress} / {@code progress_name}（{@link #techProgress} 五态，只读派生、不是库列）。
     * 另加可选 {@code slideId}：只要「这张切片挂在哪条医嘱上」（0 或 1 行）——切片检索行不带 tech_order_id，
     * 染色登记后前端据此提示医嘱进度。仍是只增不改。
     *
     * <p><b>v59（2563 一致性）</b>：两个分支再增 {@code attached_stain}——挂接切片按 (stain_type, stain_item) 去重计数后
     * 汇总成一段文本，如「IHC CK7 ×2」，多组以「、」相连，无挂接切片为 NULL。修复前三处清单 / 穿透都不回挂接切片的实际
     * 染色类型 / 项目，挂错的片子（v59 之前挂上去的 HE 片）在清单上看不出来。这里只回事实、不判一致（判在挂接时，5274）；
     * 与 PathQcController 的 WORKLOAD_TECH 穿透同一段子查询。{@code stained_count} 语义不变。
     *
     * <p><b>v60（2563 尾）</b>：两个分支再增 {@link #TECH_DERIVED_COLUMNS} 的三列——{@code sampled_block_count}（挂接蜡块数，V167）、
     * {@code blocks_derived}（「蜡块」列：block_id 非空取其 block_code，否则按挂接蜡块 + 挂接切片所在块派生，如「P-3、P-4」）、
     * {@code attached_stain_name}（{@code attached_stain} 的中文版，如「免疫组化 CK7 ×2」）；{@code progress} 增第六态 SAMPLED
     * （已补取材待切片：ORDERED、0 片、≥1 挂接蜡块）。修复前 RESAMPLE 医嘱的「蜡块」列永远「—」、进度永远「待切片」。仍是只增不改。
     */
    @GetMapping("/tech-orders")
    public R<Map<String, Object>> techOrders(@RequestParam(required = false) Long specimenId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String techType,
                                             @RequestParam(required = false) Boolean urgentOnly,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String dateField,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) Long slideId) {
        String st = trim(status);
        boolean allStatuses = false;
        if (st != null) {
            st = st.toUpperCase(Locale.ROOT);
            if ("ALL".equals(st)) {
                allStatuses = true;   // v55：显式要全状态（全院清单默认只看 ORDERED 这一条不动）
                st = null;
            } else if (!TECH_STATUSES.contains(st)) {
                return R.fail(5267, "特检医嘱状态非法（" + String.join("/", TECH_STATUSES) + "/ALL）");
            }
        } else if (specimenId == null) {
            st = "ORDERED";   // 全院清单默认只看待执行
        }
        String type = trim(techType);
        if (type != null) {
            type = type.toUpperCase(Locale.ROOT);
            if (!TECH_TYPES.contains(type)) {
                return R.fail(5267, "特检技术类型非法（" + String.join("/", TECH_TYPES) + "）");
            }
        }
        String df = trim(dateField);
        df = df == null ? "ORDERED" : df.toUpperCase(Locale.ROOT);
        if (!TECH_DATE_FIELDS.contains(df)) {
            return R.fail(5800, "特检清单 dateField 非法：" + dateField + "，可选 " + String.join(" / ", TECH_DATE_FIELDS));
        }
        Window win;
        try {
            win = parseWindow(from, to);
        } catch (IllegalArgumentException e) {
            return R.fail(5800, "特检清单检索参数非法：" + e.getMessage());
        }
        int cap = capOf(limit);

        var sql = new StringBuilder("""
                select t.id, t.specimen_id, t.block_id, b.block_no, b.block_code,
                       t.tech_type, t.tech_item, t.reason, t.status,
                       t.ordered_by, ob.real_name as ordered_by_name, t.ordered_at,
                       t.done_by, db.real_name as done_by_name, t.done_at,
                       t.cancelled_at, t.cancelled_by, cb.real_name as cancelled_by_name, t.cancel_reason,
                       (select count(*) from path_slide sl where sl.tech_order_id = t.id) as slide_count,
                       (select count(*) from path_slide sl
                         where sl.tech_order_id = t.id and sl.stained_at is not null)     as stained_count,
                       (select string_agg(g.stain_type || coalesce(' ' || g.stain_item, '') || ' ×' || g.n::text, '、'
                                          order by g.stain_type, g.stain_item)
                          from (select sl.stain_type, sl.stain_item, count(*) as n
                                  from path_slide sl where sl.tech_order_id = t.id
                                 group by sl.stain_type, sl.stain_item) g)              as attached_stain,
                """)
                .append(TECH_DERIVED_COLUMNS)   // v60：sampled_block_count / blocks_derived / attached_stain_name
                .append("""
                       s.barcode, s.path_no, s.part_no, s.specimen_type, s.urgent,
                       p.id as patient_id, p.patient_no, p.name as patient_name,
                       round((extract(epoch from (now() - t.ordered_at)) / 3600)::numeric, 1)
                           as hours_since_ordered
                from path_tech_order t
                join path_specimen s on s.id = t.specimen_id
                left join path_block b on b.id = t.block_id
                left join sys_user ob on ob.id = t.ordered_by
                left join sys_user db on db.id = t.done_by
                left join sys_user cb on cb.id = t.cancelled_by
                left join outp_order oo on oo.id = s.order_id
                left join outp_registration r on r.id = oo.registration_id
                left join inp_order io on io.id = s.inp_order_id
                left join inp_admission a on a.id = io.admission_id
                left join empi_patient p on p.id = coalesce(r.patient_id, a.patient_id)
                where 1 = 1
                """);
        var args = new ArrayList<Object>();
        if (specimenId != null) {
            sql.append(" and t.specimen_id = ? ");
            args.add(specimenId);
        }
        if (slideId != null) {
            sql.append(" and exists (select 1 from path_slide x where x.id = ? and x.tech_order_id = t.id) ");
            args.add(slideId);
        }
        if (st != null) {
            sql.append(" and t.status = ? ");
            args.add(st);
        }
        if (type != null) {
            sql.append(" and t.tech_type = ? ");
            args.add(type);
        }
        if (Boolean.TRUE.equals(urgentOnly)) {
            sql.append(" and s.urgent = true ");
        }
        String kw = trim(keyword);
        if (kw != null) {
            sql.append(" and (s.barcode ilike ? or s.path_no ilike ? or p.name ilike ? "
                    + "or p.patient_no ilike ? or t.tech_item ilike ?) ");
            String like = "%" + escapeLike(kw) + "%";
            for (int i = 0; i < 5; i++) args.add(like);
        }
        if (win != null) {
            // 片段由白名单二选一，是编译期常量；日期值走 ?
            sql.append("DONE".equals(df)
                    ? " and t.done_at >= ?::date and t.done_at < ?::date + 1 "
                    : " and t.ordered_at >= ?::date and t.ordered_at < ?::date + 1 ");
            args.add(win.from());
            args.add(win.to());
        }
        sql.append(" order by t.status = 'ORDERED' desc, s.urgent desc, t.ordered_at asc, t.id asc limit ? ");
        args.add(cap + 1);

        var rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean truncated = rows.size() > cap;
        for (var r : rows) {
            r.put("tech_type_name", TECH_TYPE_NAMES.getOrDefault(String.valueOf(r.get("tech_type")), null));
            applyTechProgress(r);   // v60：读 sampled_block_count 派生第六态 SAMPLED
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("specimenId", specimenId);
        body.put("slideId", slideId);
        body.put("status", allStatuses ? "ALL" : st);
        body.put("limit", cap);
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        body.put("techType", type);
        body.put("dateField", df);
        body.put("from", win == null ? null : win.from());
        body.put("to", win == null ? null : win.to());
        body.put("note", "不传 specimenId 为全院清单，默认只看 ORDERED，status=ALL 看全状态；"
                + "传 specimenId 为该标本清单，默认全状态。hoursSinceOrdered 是距开单的小时数（原始事实），"
                + "本端点不判超时。cancelled_at/cancelled_by_name/cancel_reason 为 NULL 且 status=CANCELLED 的是"
                + "V163 之前的历史取消（零回填）；slide_count 是挂接到该医嘱的切片数，stained_count 是其中已染色的数；"
                + "progress 由二者、sampled_block_count 与 status 派生（PENDING_SECTION 待切片 / SAMPLED 已补取材待切片 / "
                + "SECTIONING 切片中 / STAINED 已染色待确认 / DONE / CANCELLED），不是库列；slideId 只要该切片挂接的那条医嘱；"
                + "attached_stain 是挂接切片按染色类型/项目去重计数的汇总（如「IHC CK7 ×2」），attached_stain_name 是其中文版"
                + "（如「免疫组化 CK7 ×2」），无挂接为 null；blocks_derived 是「蜡块」列的派生文本（block_id 非空取其块码，"
                + "否则按挂接蜡块 + 挂接切片所在块去重「、」相连），都没有为 null；V167 之前的历史补取材块永远挂不上（零回填）。");
        return R.ok(body);
    }

    /** 九参形态（v55 契约）：等于不传 slideId。既有测试与调用方按此形态调用，签名不动 */
    public R<Map<String, Object>> techOrders(Long specimenId, String status, String techType, Boolean urgentOnly,
                                             String keyword, String dateField, String from, String to, Integer limit) {
        return techOrders(specimenId, status, techType, urgentOnly, keyword, dateField, from, to, limit, null);
    }

    /**
     * 执行进度派生（v58 三参形态，只读；行里没有挂接蜡块事实时用——等价于 sampledBlockCount = 0，永远派不出 SAMPLED）。
     * 既有调用方（PathQcController.withTechProgress）签名不动；要第六态请改用四参或 {@link #applyTechProgress}。
     */
    public static String techProgress(Object status, Object slideCount, Object stainedCount) {
        return techProgress(status, slideCount, stainedCount, null);
    }

    /**
     * 执行进度派生（v60 四参形态，只读；PathQcController 的 WORKLOAD_TECH 穿透同用这一份，别再抄一遍六态）。
     * CANCELLED / DONE 照状态；ORDERED 且无挂接切片：有挂接蜡块（{@code path_block.tech_order_id = t.id}，补取材已出块）→ SAMPLED
     * （已补取材待切片），否则 → PENDING_SECTION（待切片）；有挂接切片但未全部染色 → SECTIONING（切片中）；
     * 全部染色 → STAINED（已染色待确认）。状态不在三档内（直连改库造出来的）原样返回，不猜。
     * 挂接蜡块只在「0 片」时起作用——切了片之后进度以切片事实为准，蜡块只是它的上游。
     */
    public static String techProgress(Object status, Object slideCount, Object stainedCount, Object sampledBlockCount) {
        String st = String.valueOf(status);
        if (!"ORDERED".equals(st)) return st;
        long slides = slideCount instanceof Number n ? n.longValue() : 0L;
        long stained = stainedCount instanceof Number n ? n.longValue() : 0L;
        long sampled = sampledBlockCount instanceof Number n ? n.longValue() : 0L;
        if (slides == 0) return sampled > 0 ? "SAMPLED" : "PENDING_SECTION";
        return stained < slides ? "SECTIONING" : "STAINED";
    }

    /**
     * v60：给一行清单 / 穿透行补 {@code progress} / {@code progress_name}——读该行的 status / slide_count / stained_count /
     * sampled_block_count（后者缺失即按 0，退化为 v58 五态）。两个清单分支与质控穿透都该走这一处，别各自拼。
     */
    public static void applyTechProgress(Map<String, Object> row) {
        String p = techProgress(row.get("status"), row.get("slide_count"), row.get("stained_count"), row.get("sampled_block_count"));
        row.put("progress", p);
        row.put("progress_name", TECH_PROGRESS_NAMES.getOrDefault(p, p));
    }

    // ==================================================================
    // 五、既往病理调取（对比诊断）
    // ==================================================================

    /**
     * 同一患者的既往病理报告，供对比诊断。
     *
     * <p><b>患者同一性以 {@code empi_patient.id} 为准</b>，门诊与住院两条来源都归到同一个患者上——
     * 这正是 V144 把 {@code inp_order_id} 补上的意义：此前住院病理挂不进来，
     * 「既往病理」永远只能看到门诊那一半。
     *
     * <p><b>口径（v59 2558，与登记页 {@code GET /registry/specimens/{id}/history} 同一条）：
     * 既往 = 同一患者、已写诊断（含已签发）、未拒收的其他标本</b>。还没出结果的正在做，不构成「既往病理」；
     * 拒收不删行、不改 status（见 PathologyRegistryController 类注释），但一份因未固定被拒收、
     * 从未受检的标本不是既往病理，按 {@code rejected_at is null} 排除。排除本标本自身。
     *
     * <p>每行带首次报告三段<b>正文</b>（{@code gross_finding / micro_finding / diagnosis}）、
     * {@code clinical_diagnosis}、{@code status}、{@code report_issued_at}，以及补充报告正文
     * {@code supplements: [{seqNo, diagnosis, reason, signedAt, signerName}]}（取自 {@code path_report}，
     * 按 seq_no 升序；{@code diagnosis} 即 {@code path_report.content}）。v59 之前只给
     * {@code supplement_count} 份数不给内容、前端既往页签只画 diagnosis 一列，要看既往全文只能
     * 关抽屉→改范围→手抄病理号再搜，两份报告任何时刻不能同屏。<b>只加不改既有键</b>。
     *
     * <p>患者解析不出时（申请单被删、脏数据）返回空列表 + {@code patientResolved=false}，
     * <b>不静默返回空数组假装「这个病人没有既往病理」</b>——两者临床含义天差地别。
     */
    @GetMapping("/{specimenId}/prior")
    public R<Map<String, Object>> prior(@PathVariable Long specimenId,
                                        @RequestParam(required = false) Integer limit) {
        var cur = jdbc.queryForList("""
                select s.id, s.path_no, s.barcode, s.collected_at,
                       coalesce(r.patient_id, a.patient_id) as patient_id,
                       p.patient_no, p.name as patient_name
                from path_specimen s
                left join outp_order oo on oo.id = s.order_id
                left join outp_registration r on r.id = oo.registration_id
                left join inp_order io on io.id = s.inp_order_id
                left join inp_admission a on a.id = io.admission_id
                left join empi_patient p on p.id = coalesce(r.patient_id, a.patient_id)
                where s.id = ?
                """, specimenId);
        if (cur.isEmpty()) return R.fail(5260, "标本不存在：" + specimenId);
        var c = cur.get(0);
        Long patientId = asLong(c.get("patient_id"));

        var body = new LinkedHashMap<String, Object>();
        body.put("specimenId", specimenId);
        body.put("patientId", patientId);
        body.put("patientNo", c.get("patient_no"));
        body.put("patientName", c.get("patient_name"));
        body.put("patientResolved", patientId != null);
        if (patientId == null) {
            body.put("items", List.of());
            body.put("truncated", false);
            body.put("note", "无法从该标本解析出患者（来源申请单缺失或已被删除），"
                    + "本次未能检索既往病理——这不等于该患者没有既往病理");
            return R.ok(body);
        }

        int cap = capOf(limit);
        var rows = jdbc.queryForList("""
                select s.id, s.barcode, s.path_no, s.part_no, s.specimen_type, s.sampling_site, s.status,
                       s.clinical_diagnosis, s.specimen_desc, s.diagnosis, s.gross_finding, s.micro_finding,
                       s.collected_at, s.received_at, s.diagnosed_at, s.report_issued_at,
                       s.pathologist_id, u.real_name as pathologist_name,
                       s.first_signer_id, s.second_signer_id,
                       case when s.order_id is not null then 'OUTP' else 'INP' end as source,
                       (select count(*) from path_report rp where rp.specimen_id = s.id) as supplement_count
                from path_specimen s
                left join sys_user u on u.id = s.pathologist_id
                left join outp_order oo on oo.id = s.order_id
                left join outp_registration r on r.id = oo.registration_id
                left join inp_order io on io.id = s.inp_order_id
                left join inp_admission a on a.id = io.admission_id
                where coalesce(r.patient_id, a.patient_id) = ?
                  and s.id <> ?
                  and s.rejected_at is null
                  and (s.diagnosis is not null or s.report_issued_at is not null)
                order by coalesce(s.report_issued_at, s.diagnosed_at, s.collected_at) desc, s.id desc
                limit ?
                """, patientId, specimenId, cap + 1);
        boolean truncated = rows.size() > cap;
        var items = truncated ? rows.subList(0, cap) : rows;

        // v59（2558）：补充报告给正文不只给份数——一次查完本页所有既往标本的 path_report，按 specimen_id 归组
        if (!items.isEmpty()) {
            var ids = new ArrayList<Object>();
            for (var r : items) ids.add(asLong(r.get("id")));
            var sups = jdbc.queryForList("""
                    select rp.specimen_id, rp.seq_no, rp.content, rp.reason, rp.signed_at,
                           u.real_name as signer_name
                    from path_report rp
                    left join sys_user u on u.id = rp.signer_id
                    where rp.specimen_id in (%s)
                    order by rp.specimen_id, rp.seq_no
                    """.formatted(String.join(",", java.util.Collections.nCopies(ids.size(), "?"))),
                    ids.toArray());
            var bySpecimen = new java.util.HashMap<Long, List<Map<String, Object>>>();
            for (var sp : sups) {
                var one = new LinkedHashMap<String, Object>();
                one.put("seqNo", sp.get("seq_no"));
                one.put("diagnosis", sp.get("content"));
                one.put("reason", sp.get("reason"));
                one.put("signedAt", sp.get("signed_at"));
                one.put("signerName", sp.get("signer_name"));
                bySpecimen.computeIfAbsent(asLong(sp.get("specimen_id")), k -> new ArrayList<>()).add(one);
            }
            for (var r : items) r.put("supplements", bySpecimen.getOrDefault(asLong(r.get("id")), List.of()));
        }

        body.put("limit", cap);
        body.put("items", items);
        body.put("truncated", truncated);
        body.put("note", "既往 = 同一患者、已写诊断（含已签发）、未拒收的其他标本（含门诊与住院两条来源，已排除本标本）；"
                + "与登记页 /registry/specimens/{id}/history 同口径。每行带 gross_finding / micro_finding / diagnosis 正文与 status，"
                + "supplementCount 为补充报告份数、supplements 为其正文（按 seqNo 升序）");
        return R.ok(body);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 双签 gate 三态解析 */
    private String gate() {
        return gate(DOUBLE_SIGN_GATE_KEY);
    }

    /** 特检完成校验 gate 三态解析（v58） */
    private String techDoneGate() {
        return gate(TECH_DONE_GATE_KEY);
    }

    /**
     * gate 三态解析（双签与特检完成两把开关同一份规则）。
     *
     * <p><b>坏配置回落 warn 而非 off</b>：把 'blocked'、'true'、'1' 这类写错的值当成 off，
     * 等于让一个笔误静默关掉法定校验；回落 warn 至少还会在返回体里喊一声。
     */
    private String gate(String key) {
        String v = configReader.get(key, "warn");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
    }

    /** 流转节点留痕文案里的医嘱标识：#id 中文类型(编码) 项目——技师与评委看流转轨迹时不用再回头查字典 */
    private static String techLabel(Object id, Object type, Object item) {
        String t = String.valueOf(type);
        return "#" + id + " " + TECH_TYPE_NAMES.getOrDefault(t, t) + "(" + t + ")" + (item == null ? "" : " " + item);
    }

    /** 签名类端点共用的前置：已拒收 / 已签发的标本一律不许再签 */
    private R<Map<String, Object>> notSignable(Map<String, Object> h) {
        if (h.get("rejected_at") != null) return R.fail(5261, "该标本已拒收，不能签名");
        if (h.get("report_issued_at") != null) {
            return R.fail(5261, "该标本报告已签发，不能再补签名（如需更正请出补充报告）");
        }
        return null;
    }

    private Map<String, Object> head(Long specimenId) {
        if (specimenId == null) return null;
        var rows = jdbc.queryForList("""
                select id, status, diagnosis, diagnosed_at, rejected_at, report_issued_at,
                       first_signer_id, first_signed_at, second_signer_id, second_signed_at
                from path_specimen where id = ?
                """, specimenId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 流转节点留痕：occurred_at 取库时间，<b>不接受调用方传入</b>（签名时刻不是可以商量的） */
    private void logProcess(Long specimenId, String node, Long operatorId, String remark) {
        jdbc.update("""
                insert into path_process(specimen_id, node, occurred_at, operator_id, remark)
                values (?, ?, now(), ?, ?)
                """, specimenId, node, operatorId, trimTo(remark, REASON_MAX));
    }

    private static int capOf(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    /** 闭区间日期窗（yyyy-MM-dd 两端），SQL 里按 [from, to+1) 取 */
    private record Window(String from, String to) {}

    /**
     * 日期窗解析：两端都不给返回 null（不加窗）；只给一端、格式错、倒置、跨度超 {@value #MAX_SPAN_DAYS} 天
     * 抛 {@link IllegalArgumentException}，由调用方转 5800。规则与 PathologyProcessController.slideSearch 逐条一致。
     */
    private static Window parseWindow(String from, String to) {
        String f = trim(from);
        String t = trim(to);
        if (f == null && t == null) return null;
        if ((f == null) != (t == null)) throw new IllegalArgumentException("日期区间 from 与 to 必须同时给出");
        LocalDate fd;
        LocalDate td;
        try {
            fd = LocalDate.parse(f);
            td = LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式须为 yyyy-MM-dd：" + f + " / " + t);
        }
        if (td.isBefore(fd)) throw new IllegalArgumentException("日期区间倒置：" + f + " 晚于 " + t);
        if (fd.plusDays(MAX_SPAN_DAYS).isBefore(td)) {
            throw new IllegalArgumentException("日期跨度超过 " + MAX_SPAN_DAYS + " 天");
        }
        return new Window(fd.toString(), td.toString());
    }

    /** LIKE 通配符转义（与 InpatientController / EmrFieldController 同一份写法） */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 仅用于本类自己生成的留痕文案，绝不用于截断调用方送来的业务内容 */
    private static String trimTo(String s, int max) {
        String t = trim(s);
        return t == null || t.length() <= max ? t : t.substring(0, max);
    }

    private static boolean blank(Object o) {
        return o == null || !(o instanceof String s) || s.isBlank();
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
