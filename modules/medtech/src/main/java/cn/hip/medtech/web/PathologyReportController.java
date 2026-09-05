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
 *   <li><b>取消技术医嘱不留原因</b>——{@code path_tech_order} 没有 cancel_reason 列，
 *       本版不新建迁移也不往 {@code reason} 里塞取消原因（那会覆盖下达时的原因）。</li>
 * </ul>
 *
 * <p><b>错误码 5260–5270</b>（v48 诊断与报告段 5260–5279，5271–5279 空置未启用）：
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
 *   <li>5270 无法识别当前登录用户，不能签名</li>
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
     * @param scope stained（默认，已染色待诊断）/ all（已核收未诊断的全部，含无切片记录的存量标本）
     */
    @GetMapping("/worklist")
    public R<Map<String, Object>> worklist(@RequestParam(required = false) String scope,
                                           @RequestParam(required = false) String specimenType,
                                           @RequestParam(required = false) Boolean urgentOnly,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer limit) {
        String mode = "all".equalsIgnoreCase(trim(scope)) ? "all" : "stained";
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
                           as hours_since_received
                from path_specimen s
                left join outp_order oo on oo.id = s.order_id
                left join outp_registration r on r.id = oo.registration_id
                left join inp_order io on io.id = s.inp_order_id
                left join inp_admission a on a.id = io.admission_id
                left join empi_patient p on p.id = coalesce(r.patient_id, a.patient_id)
                where s.diagnosed_at is null and s.rejected_at is null and s.received_at is not null
                """);
        var args = new ArrayList<Object>();

        // 片段是编译期常量，参数一律走 ?（禁止拼接 SQL）
        if ("stained".equals(mode)) {
            sql.append("""
                      and exists (select 1 from path_slide sl join path_block b on b.id = sl.block_id
                                   where b.specimen_id = s.id and sl.stained_at is not null)
                    """);
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
        sql.append(" order by s.urgent desc, s.received_at asc nulls last, s.id asc limit ? ");
        args.add(cap + 1);   // 多取 1 条判 truncated：只取 cap 条会让「刚好第 cap 条」漏报

        var rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean truncated = rows.size() > cap;

        var body = new LinkedHashMap<String, Object>();
        body.put("scope", mode);
        body.put("limit", cap);
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        body.put("note", "stained：存在已染色切片的待诊断标本；all：已核收未诊断的全部标本"
                + "（含 V144 之前无蜡块/切片记录的存量标本）。hoursSinceReceived 为距核收的小时数，"
                + "是否超时由病理质控端点判定，本端点不判。");
        return R.ok(body);
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
     * <p><b>只写 report_issued_at</b>——{@code diagnosed_at}（写完诊断的时刻）、
     * {@code status}、gross/micro/diagnosis 三列全部一字不动。
     *
     * <p>双签校验按 {@code emr.gate.pathology.doublesign} 三态：
     * off 整段跳过（返回体 warnings 为空数组）；warn <b>放行并回带 warnings</b>；block 返 5265。
     * 三档都会把「本次是否完整双签」写进 ISSUE 节点的 remark——
     * warn 档放行不等于没发生过，事后追责得能查到当时是谁在缺签的情况下发的报告。
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

        var body = new LinkedHashMap<String, Object>();
        body.put("specimenId", specimenId);
        body.put("reportIssuedAt", updated.get(0).get("report_issued_at"));
        body.put("diagnosedAt", h.get("diagnosed_at"));
        body.put("doubleSignGate", gate);
        body.put("doubleSignComplete", missing.isEmpty());
        body.put("warnings", warnings);
        return R.ok(body);
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

        var ins = jdbc.queryForList("""
                insert into path_tech_order(specimen_id, block_id, tech_type, tech_item, reason, ordered_by)
                values (?, ?, ?, ?, ?, ?)
                returning id, status, ordered_at
                """, req.specimenId(), req.blockId(), type, item, reason, currentUserService.idOf(auth));
        var row = ins.get(0);

        var body = new LinkedHashMap<String, Object>();
        body.put("id", row.get("id"));
        body.put("specimenId", req.specimenId());
        body.put("blockId", req.blockId());
        body.put("techType", type);
        body.put("techTypeName", TECH_TYPE_NAMES.getOrDefault(type, type));
        body.put("techItem", item);
        body.put("status", row.get("status"));
        body.put("orderedAt", row.get("ordered_at"));
        return R.ok(body);
    }

    /** 完成特检技术医嘱（条件更新 + 受影响行数判定，不做读-判-写） */
    @PutMapping("/tech-orders/{id}/done")
    public R<Map<String, Object>> doneTechOrder(@PathVariable Long id, Authentication auth) {
        var updated = jdbc.queryForList("""
                update path_tech_order set status = 'DONE', done_at = now(), done_by = ?
                where id = ? and status = 'ORDERED'
                returning id, specimen_id, tech_type, tech_item, status, done_at
                """, currentUserService.idOf(auth), id);
        if (updated.isEmpty()) return R.fail(5268, "特检技术医嘱不存在或不是待执行状态：id=" + id);
        return R.ok(new LinkedHashMap<>(updated.get(0)));
    }

    /**
     * 取消特检技术医嘱。
     *
     * <p><b>缺口如实标注</b>：{@code path_tech_order} 没有 cancel_reason 列，取消原因无处存放。
     * 本版<b>不接收取消原因参数</b>，也不把它塞进 {@code reason}（那会覆盖下达时的原因，
     * 让「当初为什么要做这个免疫组化」永久丢失）。需要留痕请给主控加列。
     */
    @PutMapping("/tech-orders/{id}/cancel")
    public R<Map<String, Object>> cancelTechOrder(@PathVariable Long id) {
        var updated = jdbc.queryForList("""
                update path_tech_order set status = 'CANCELLED'
                where id = ? and status = 'ORDERED'
                returning id, specimen_id, tech_type, tech_item, status
                """, id);
        if (updated.isEmpty()) return R.fail(5268, "特检技术医嘱不存在或不是待执行状态：id=" + id);
        var body = new LinkedHashMap<>(updated.get(0));
        body.put("note", "本版无 cancel_reason 列，取消原因未留痕（不覆盖下达原因）");
        return R.ok(body);
    }

    /**
     * 特检技术医嘱追踪。
     *
     * <p>{@code specimenId} 可空——不传即技师侧的全院待执行清单（默认只看 ORDERED）；
     * 传了则是该标本的全部技术医嘱（默认全状态）。两种用法的默认状态刻意不同：
     * 全院清单默认拉全状态会把历史全捞出来，标本清单只看 ORDERED 又看不到已完成的项目。
     */
    @GetMapping("/tech-orders")
    public R<Map<String, Object>> techOrders(@RequestParam(required = false) Long specimenId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Integer limit) {
        String st = trim(status);
        if (st != null) {
            st = st.toUpperCase(Locale.ROOT);
            if (!TECH_STATUSES.contains(st)) {
                return R.fail(5267, "特检医嘱状态非法（" + String.join("/", TECH_STATUSES) + "）");
            }
        } else if (specimenId == null) {
            st = "ORDERED";   // 全院清单默认只看待执行
        }
        int cap = capOf(limit);

        var sql = new StringBuilder("""
                select t.id, t.specimen_id, t.block_id, b.block_no, b.block_code,
                       t.tech_type, t.tech_item, t.reason, t.status,
                       t.ordered_by, ob.real_name as ordered_by_name, t.ordered_at,
                       t.done_by, db.real_name as done_by_name, t.done_at,
                       s.barcode, s.path_no, s.part_no, s.specimen_type, s.urgent,
                       p.id as patient_id, p.patient_no, p.name as patient_name
                from path_tech_order t
                join path_specimen s on s.id = t.specimen_id
                left join path_block b on b.id = t.block_id
                left join sys_user ob on ob.id = t.ordered_by
                left join sys_user db on db.id = t.done_by
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
        if (st != null) {
            sql.append(" and t.status = ? ");
            args.add(st);
        }
        sql.append(" order by t.status = 'ORDERED' desc, s.urgent desc, t.ordered_at asc, t.id asc limit ? ");
        args.add(cap + 1);

        var rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean truncated = rows.size() > cap;

        var body = new LinkedHashMap<String, Object>();
        body.put("specimenId", specimenId);
        body.put("status", st);
        body.put("limit", cap);
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        return R.ok(body);
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
     * <p>只列<b>已有诊断或已签发</b>的既往标本（还没出结果的正在做，不构成「既往病理」），
     * 排除本标本自身。每行带补充报告条数，提示是否有后续修订意见。
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
                select s.id, s.barcode, s.path_no, s.part_no, s.specimen_type, s.sampling_site,
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

        body.put("limit", cap);
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        body.put("note", "仅列已有诊断或已签发的既往标本（含门诊与住院两条来源），已排除本标本与已拒收标本；"
                + "supplementCount>0 表示该次报告后另有补充报告");
        return R.ok(body);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 双签 gate 三态解析。
     *
     * <p><b>坏配置回落 warn 而非 off</b>：把 'blocked'、'true'、'1' 这类写错的值当成 off，
     * 等于让一个笔误静默关掉法定校验；回落 warn 至少还会在返回体里喊一声。
     */
    private String gate() {
        String v = configReader.get(DOUBLE_SIGN_GATE_KEY, "warn");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
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
