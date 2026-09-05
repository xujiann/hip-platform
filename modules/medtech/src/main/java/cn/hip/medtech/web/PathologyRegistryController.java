package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.HipProfiles;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v48 车道 P1：病理申请与登记——双来源待办 / 病理号生成 / 接收核对 / 拒收 / 检索 / 既往病理提醒。
 *
 * <p><b>与既有 {@link PathologyController} 的边界</b>：那五个端点
 * （{@code GET /api/pathology/pending}、{@code POST /specimens}、
 * {@code PUT /specimens/{barcode}/receive}、{@code PUT /specimens/{barcode}/diagnose}、
 * {@code GET /specimens}）的返回体键集合与错误码 4550–4553 <b>一个字节没改</b>。
 * 本控制器全部走 {@code /api/pathology/registry/**} 新前缀。唯一动过的既有代码是
 * {@code GET /api/pathology/specimens} 的 <b>inner join 漏行缺陷</b>（见该方法注释），
 * 那是补回本该在的行，键集合逐字不变。
 *
 * <p><b>五条口径</b>：
 * <ul>
 *   <li><b>病理号 ≠ 条码</b>。{@code barcode} 是院内流转条码（PB+全局序列，V21 起既有，
 *       扫码枪扫的就是它）；{@code path_no} 是对外出报告的法定编号（按年+类别连续，
 *       如 {@code 2026-C-000123}），患者与外院会诊按它索引。<b>两者都生成、都落库</b>，
 *       登记端点同时返回，谁也不替代谁。</li>
 *   <li><b>拒收不删记录</b>。拒收只写 {@code reject_reason/rejected_at/rejected_by} 加一条
 *       {@code path_process.REJECT}，行还在。删了行就永远算不出「送检多少、拒了多少」，
 *       标本固定规范率没有分母。同 V144 注释、同 v46「取消手术不删记录」。</li>
 *   <li><b>不扩 {@code status} 的值域</b>。{@code path_specimen.status} 的三个取值
 *       COLLECTED/RECEIVED/DIAGNOSED 正被既有 {@code GET /specimens} 原样吐给前端
 *       （SpecialtyView.vue 按它显示「核收 / 诊断报告」两个按钮），多一个 'REJECTED'
 *       就是在改既有返回体的值域。<b>「是否已拒收」一律看 {@code rejected_at is not null}</b>，
 *       本控制器的后续操作也按这个判据拦截。</li>
 *   <li><b>时间解析不出即报错，不静默吃成 {@code now()}</b>（5210）。倒填的离体固定时刻悄悄变成
 *       此刻，会让「离体到固定」时长恒等于 0、标本固定规范率恒等于 100%——与护理记录 4811、
 *       术中记录 4928 同口径。</li>
 *   <li><b>检索一律硬上限 + {@code truncated} 标记</b>，不静默截断也不做翻页（照抄 v43 车道D
 *       {@code /admissions/orders/search} 的纪律）。</li>
 * </ul>
 *
 * <p><b>本版如实留下的缺口（没有造假实现）</b>：
 * <ul>
 *   <li><b>真正「无任何院内来源」的标本登记不了</b>。V144 的
 *       {@code chk_path_specimen_source} 要求 {@code order_id} 与 {@code inp_order_id}
 *       <b>恰有其一</b>，且 {@code path_specimen} 全表<b>没有 {@code patient_id} 列</b>——
 *       患者身份是靠 order→挂号/住院 反查出来的。因此院外送检、外院会诊（{@code CONSULT}）
 *       这类没有院内医嘱的标本，本地基下<b>无处安放</b>。
 *       {@code POST /specimens/manual} 因此是<b>受限版手工登记</b>：仍需一个院内医嘱 id，
 *       放宽的是「项目名不含病理二字」「门诊未收费」这两道会把实物已到的标本挡在门外的闸，
 *       并把手工录入的申请科室/申请医师写进 {@code path_process.remark} 留痕。
 *       要做真正的手工登记，需要地基放宽该 check 并新增患者与申请信息列——<b>按纪律不自行加列</b>。</li>
 *   <li><b>申请科室/申请医师/送检医师无结构化落点</b>。{@code path_specimen} 没有这几列，
 *       手工登记只能塞进 {@code path_process.remark}（varchar(255)，超长截断），
 *       <b>不可用于统计</b>，只够追溯。</li>
 *   <li><b>不做图像</b>：大体拍照、切片扫描、数字病理 WSI 一概没有——{@code MultipartFile}
 *       全仓零命中，平台没有文件上传基础设施，硬做只能做出假的。</li>
 * </ul>
 *
 * <p>错误码占用 <b>5200–5212</b>（v48 病理段 5200–5219 的申请与登记子段，5213–5219 空置——
 * <b>预留的不登记</b>，没写代码就不占码）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pathology/registry")
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP')")   // 与既有 PathologyController 同口径
public class PathologyRegistryController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final ConfigReader configReader;

    /** 待办与检索硬上限：命中超过它说明条件太宽，应收窄而不是翻页（同 v43 车道D 口径） */
    private static final int SEARCH_LIMIT = 200;
    /** 既往病理提醒的条数上限：病理医师对比看的是最近几次，不是全生涯 */
    private static final int HISTORY_LIMIT = 50;

    /** 标本类别 → 病理号类别码（C常规/F冰冻/Y细胞学/H会诊/M分子，见 sys_config path.no.pattern 的说明） */
    private static final Map<String, String> TYPE_CODES = Map.of(
            "ROUTINE", "C", "FROZEN", "F", "CYTOLOGY", "Y", "CONSULT", "H", "MOLECULAR", "M");

    /** 类别在建议锁里的序号，保证「年+类别」各自一把锁而不是全域一把（并发登记不同类别不互相排队） */
    private static final List<String> TYPE_ORDER =
            List.of("ROUTINE", "FROZEN", "CYTOLOGY", "CONSULT", "MOLECULAR");

    /** 既有 status 三态；本版不扩值域，检索白名单照抄 */
    private static final List<String> STATUSES = List.of("COLLECTED", "RECEIVED", "DIAGNOSED");

    private static final List<String> SOURCES = List.of("OUTP", "INP");

    /** {seq} 或 {seq:6}；宽度缺省 6 位 */
    private static final Pattern SEQ_TOKEN = Pattern.compile("\\{seq(?::(\\d{1,2}))?\\}");
    /** 展开后仍残留的花括号 token——说明规则里有本版不认识的占位符，宁可报错也不把 `{xxx}` 印进法定编号 */
    private static final Pattern LEFTOVER_TOKEN = Pattern.compile("\\{[^}]*}");

    /** 建议锁命名空间：取本车道错误码段起点，便于排障时反查是谁在持锁 */
    private static final int ADVISORY_LOCK_NS = 5200;

    /** 时钟容差：前端与服务器差几分钟不该把合法登记判成「未来时间」（同 v47 4825 的 5 分钟口径） */
    private static final long CLOCK_SKEW_SECONDS = 300;

    // ==================================================================================
    // 一、双来源待办
    // ==================================================================================

    /**
     * 待登记的病理申请——<b>门诊 outp_order 与住院 inp_order 两个来源</b>。
     *
     * <p><b>为什么另开一个而不是改既有 {@code GET /api/pathology/pending}</b>：那个端点
     * 只查门诊、只认 {@code status='CHARGED'}，其返回体（{@code order_id/item_name/group_no/
     * patient_name} 四个键）正被 SpecialtyView.vue 的「待取材」表消费，加键改行都是改契约。
     *
     * <p><b>两个来源的口径差异（不是 bug，是两条业务线本来就不同）</b>：
     * <ul>
     *   <li>门诊沿用既有判据 {@code status='CHARGED'}——门诊是先收费后执行，没收费的申请单
     *       不该出现在病理科工作台。</li>
     *   <li>住院是先执行后计费，没有 CHARGED 这一档，故判据是 {@code status <> 'CANCELLED'}。</li>
     *   <li>{@code clinical_summary}（临床摘要）与 {@code urgent}（加急）是 V137 只给
     *       {@code outp_order} 加的列，{@code inp_order} 没有，住院来源的这两个键<b>恒为 null</b>——
     *       如实留空，不拿别的列凑数。</li>
     * </ul>
     *
     * <p>{@code registered_parts} 是该申请下已登记的部位数：多部位分别送检时，一个申请要登记多条，
     * 所以「已登记过」不等于「该从待办里消失」。默认仍隐藏已登记的（{@code includeRegistered=false}），
     * 需要补登第二个部位时传 true。
     *
     * @param source            OUTP/INP，不传为两个来源都要
     * @param keyword           患者姓名或项目名，模糊匹配（% _ \ 已转义）
     * @param includeRegistered 是否包含已登记过至少一个部位的申请
     */
    @GetMapping("/pending")
    public R<Map<String, Object>> pending(@RequestParam(required = false) String source,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Boolean includeRegistered) {
        String src = trim(source).toUpperCase(Locale.ROOT);
        String kw = trim(keyword);
        if (!src.isEmpty() && !SOURCES.contains(src)) return R.fail(5209, "来源条件非法（OUTP/INP）");
        if (kw.length() > 64) return R.fail(5209, "关键词过长（最多 64 字）");

        // 两个分支各自是固定字面量，过滤条件全部走外层占位符——keyword 不参与任何字符串拼接
        var sql = new StringBuilder("""
                select * from (
                    select 'OUTP' as source, o.id as order_id, null::bigint as inp_order_id,
                           o.item_name, o.group_no, o.status as order_status, o.created_at,
                           p.id as patient_id, p.name as patient_name, p.sex,
                           o.clinical_summary, o.urgent,
                           (select count(*) from path_specimen s where s.order_id = o.id) as registered_parts
                    from outp_order o
                    join outp_registration r on r.id = o.registration_id
                    join empi_patient p on p.id = r.patient_id
                    where o.status = 'CHARGED'
                      and (o.item_name like '%病理%' or o.item_name like '%活检%')
                    union all
                    select 'INP' as source, null::bigint as order_id, io.id as inp_order_id,
                           io.item_name, io.group_no, io.status as order_status, io.created_at,
                           p.id as patient_id, p.name as patient_name, p.sex,
                           null::varchar as clinical_summary, null::boolean as urgent,
                           (select count(*) from path_specimen s where s.inp_order_id = io.id) as registered_parts
                    from inp_order io
                    join inp_admission a on a.id = io.admission_id
                    join empi_patient p on p.id = a.patient_id
                    where io.status <> 'CANCELLED'
                      and (io.item_name like '%病理%' or io.item_name like '%活检%')
                ) t
                where 1 = 1
                """);
        var args = new ArrayList<Object>();
        if (!Boolean.TRUE.equals(includeRegistered)) sql.append(" and t.registered_parts = 0");
        if (!src.isEmpty()) { sql.append(" and t.source = ?"); args.add(src); }
        if (!kw.isEmpty()) {
            sql.append(" and (t.patient_name ilike ? escape '\\' or t.item_name ilike ? escape '\\')");
            String like = "%" + escapeLike(kw) + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" order by t.created_at desc, t.source, coalesce(t.order_id, t.inp_order_id) desc limit ?");
        args.add(SEARCH_LIMIT + 1);

        return R.ok(capped(jdbc.queryForList(sql.toString(), args.toArray()), SEARCH_LIMIT));
    }

    // ==================================================================================
    // 二、登记
    // ==================================================================================

    /**
     * 登记入参。{@code orderId} 与 {@code inpOrderId} <b>恰有其一</b>（V144
     * {@code chk_path_specimen_source} 是硬约束，两个都填会让同一份标本在门诊与住院两个工作台
     * 各出现一次）。{@code fixedAt} 是离体固定时刻的 ISO-8601 字面量，可空。
     */
    public record RegisterReq(Long orderId, Long inpOrderId, Integer partNo, String specimenType,
                              String specimenDesc, String samplingSite, String clinicalDiagnosis,
                              String fixative, String fixedAt, Boolean urgent) {}

    /**
     * 手工登记入参：在 {@link RegisterReq} 之上多三个只能进 remark 的字段。
     * {@code applyDept}/{@code applyDoctor} <b>没有结构化落点</b>（{@code path_specimen} 无此列），
     * 只写进 {@code path_process.remark} 供追溯，不参与任何统计。
     */
    public record ManualReq(Long orderId, Long inpOrderId, Integer partNo, String specimenType,
                            String specimenDesc, String samplingSite, String clinicalDiagnosis,
                            String fixative, String fixedAt, Boolean urgent,
                            String applyDept, String applyDoctor, String note) {}

    /**
     * 病理申请登记（双来源 + 多部位 + 病理号）。
     *
     * <p>与既有 {@code POST /api/pathology/specimens} 的关系：那个只认门诊、只登记一个部位
     * （V21 的 {@code unique(order_id)} 让第二个部位写不进去）、不生成病理号。本端点是它的
     * <b>超集但不替代</b>——既有端点原样保留，前端旧路径继续可用。
     *
     * <p><b>病理号按「年 + 类别」连续</b>，规则读配置 {@code path.no.pattern}。生成期间持
     * {@code pg_advisory_xact_lock(5200, 年*10+类别序号)}：并发登记同年同类别的两个请求会排队，
     * 不同类别互不阻塞；锁随事务提交自动释放，不会像会话级建议锁那样漏放。
     */
    @PostMapping("/specimens")
    @Transactional
    public R<Map<String, Object>> register(@RequestBody RegisterReq req, Authentication auth) {
        return doRegister(req, false, null, auth);
    }

    /**
     * 无电子病理申请单的手工登记（<b>受限版，见类注释的缺口说明</b>）。
     *
     * <p><b>能做到的</b>：绕开「项目名必须含病理/活检」与「门诊必须已收费」两道闸——实物标本已经
     * 送到病理科，因为项目名写作「快速冰冻」或因为患者还没去缴费就把标本拒之门外，只会造出
     * <b>台账外的标本</b>，那正是本版要消灭的东西。手工录入的申请科室/申请医师连同
     * 「手工登记」四个字一起写进 {@code path_process.RECEIVE} 的 remark，<b>可追溯</b>。
     *
     * <p><b>做不到的</b>：完全没有院内医嘱的标本（院外送检、外院会诊）仍需传一个来源 id，
     * 因为 {@code chk_path_specimen_source} 要求恰有其一、且表里没有 patient_id 列。
     *
     * <p><b>手工登记直接置为已接收</b>（{@code status='RECEIVED'}）：标本实物已在手里才会走这条路，
     * 再要求补一次核收就是让人对着空气点按钮。因此 {@code PUT /{id}/receive-check} 对手工登记的标本
     * 会返 5208（状态不允许），<b>这是有意的</b>——否则会落下两条 RECEIVE 流转记录，
     * 把病理质控的「接收及时率」分母算成两倍。
     */
    @PostMapping("/specimens/manual")
    @Transactional
    public R<Map<String, Object>> registerManual(@RequestBody ManualReq req, Authentication auth) {
        var base = new RegisterReq(req.orderId(), req.inpOrderId(), req.partNo(), req.specimenType(),
                req.specimenDesc(), req.samplingSite(), req.clinicalDiagnosis(),
                req.fixative(), req.fixedAt(), req.urgent());
        var remark = new StringBuilder("手工登记（无电子病理申请单）");
        if (!trim(req.applyDept()).isEmpty()) remark.append("；申请科室=").append(trim(req.applyDept()));
        if (!trim(req.applyDoctor()).isEmpty()) remark.append("；申请医师=").append(trim(req.applyDoctor()));
        if (!trim(req.note()).isEmpty()) remark.append("；").append(trim(req.note()));
        return doRegister(base, true, cut(remark.toString(), 255), auth);
    }

    private R<Map<String, Object>> doRegister(RegisterReq req, boolean manual, String manualRemark,
                                              Authentication auth) {
        boolean outp = req.orderId() != null;
        boolean inp = req.inpOrderId() != null;
        if (outp == inp) return R.fail(5200, "来源非法：orderId 与 inpOrderId 必须恰有其一");

        int partNo = req.partNo() == null ? 1 : req.partNo();
        if (partNo < 1 || partNo > 99) return R.fail(5204, "部位序号非法（1–99）");

        String type = trim(req.specimenType()).toUpperCase(Locale.ROOT);
        if (!TYPE_CODES.containsKey(type)) {
            return R.fail(5203, "标本类别非法（ROUTINE/FROZEN/CYTOLOGY/CONSULT/MOLECULAR）");
        }

        String tooLong = checkLengths(req);
        if (tooLong != null) return R.fail(5212, tooLong);

        Timestamp fixedAt = null;
        if (!trim(req.fixedAt()).isEmpty()) {
            Instant t = parseInstant(req.fixedAt());
            if (t == null) return R.fail(5210, "离体固定时刻格式非法（ISO-8601，如 2026-09-06T09:30）");
            if (t.isAfter(Instant.now().plusSeconds(CLOCK_SKEW_SECONDS))) {
                return R.fail(5210, "离体固定时刻不能晚于当前时刻");
            }
            fixedAt = Timestamp.from(t);
        }

        // 来源校验。手工登记放宽的正是这里：门诊不再要求已收费（标本实物已到，见端点注释）
        if (outp) {
            var rows = jdbc.queryForList("select status from outp_order where id = ?", String.class, req.orderId());
            if (rows.isEmpty()) return R.fail(5201, "门诊申请不存在");
            if (!manual && !"CHARGED".equals(rows.get(0))) {
                return R.fail(5201, "门诊申请未收费（标本实物已送达时走 /specimens/manual 登记并留痕）");
            }
        } else {
            var rows = jdbc.queryForList("select status from inp_order where id = ?", String.class, req.inpOrderId());
            if (rows.isEmpty()) return R.fail(5201, "住院医嘱不存在");
            if ("CANCELLED".equals(rows.get(0))) return R.fail(5201, "住院医嘱已作废，不能登记标本");
        }

        // 部位重复预检。真并发下最终仍由 V144 的部分唯一索引兜底（走全局异常处理返 4090），
        // 这里预检是为了给出「这个部位已经登记过」这句人话，而不是一句数据库约束名
        Integer dup = jdbc.queryForObject(outp
                        // **排除已拒收行**（V145）：拒收后临床重送同一部位是常态，
                        // 不排除的话拒收就等于把这个部位序号永久烧掉、只能被迫顺延，
                        // 而部位序号正是蜡块编码与报告上「3 号蜡块」的来源。
                        ? "select count(*) from path_specimen where order_id = ? and part_no = ? and rejected_at is null"
                        : "select count(*) from path_specimen where inp_order_id = ? and part_no = ? and rejected_at is null",
                Integer.class, outp ? req.orderId() : req.inpOrderId(), partNo);
        if (dup != null && dup > 0) return R.fail(5202, "该申请的第 " + partNo + " 个部位已登记（已拒收的不算，可重新登记）");

        String pathNo;
        try {
            pathNo = nextPathNo(type);
        } catch (PathNoRuleException e) {
            return R.fail(5205, e.getMessage());
        }

        // 条码沿用既有规则（PB + path_specimen_seq），与病理号并存：扫码枪扫条码，报告上印病理号
        String barcode = "PB" + jdbc.queryForObject("select nextval('path_specimen_seq')", Long.class);

        Long id = jdbc.queryForObject("""
                insert into path_specimen(order_id, inp_order_id, barcode, part_no, path_no,
                        specimen_type, specimen_desc, sampling_site, clinical_diagnosis,
                        fixative, fixed_at, urgent, status, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class,
                req.orderId(), req.inpOrderId(), barcode, partNo, pathNo,
                type, nullIfBlank(req.specimenDesc()), nullIfBlank(req.samplingSite()),
                nullIfBlank(req.clinicalDiagnosis()), nullIfBlank(req.fixative()), fixedAt,
                Boolean.TRUE.equals(req.urgent()),
                manual ? "RECEIVED" : "COLLECTED",
                manual ? Timestamp.from(Instant.now()) : null);

        if (manual) {
            jdbc.update("""
                    insert into path_process(specimen_id, node, occurred_at, operator_id, remark)
                    values (?, 'RECEIVE', now(), ?, ?)
                    """, id, currentUserService.idOf(auth), manualRemark);
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("barcode", barcode);
        out.put("pathNo", pathNo);
        out.put("partNo", partNo);
        out.put("source", outp ? "OUTP" : "INP");
        out.put("specimenType", type);
        out.put("status", manual ? "RECEIVED" : "COLLECTED");
        out.put("manual", manual);
        return R.ok(out);
    }

    // ==================================================================================
    // 三、接收核对与拒收
    // ==================================================================================

    /** 接收核对入参：{@code receivedAt} 可空（缺省取此刻），{@code remark} 记核对情况（如「与申请单部位一致」） */
    public record ReceiveCheckReq(String receivedAt, String remark) {}

    /**
     * 接收核对：核对标本与申请是否相符，落 {@code path_process.RECEIVE} 并置为已核收。
     *
     * <p><b>与既有 {@code PUT /specimens/{barcode}/receive} 的关系</b>：那个按条码、无核对信息、
     * 不留流转记录，本版<b>一个字节没改</b>，前端旧路径照常可用。本端点按 id，多两件事：
     * 记录核对时刻与核对备注、落一条 {@code path_process} 流转节点（病理质控的各环节耗时靠它算）。
     * 写的是同两列（{@code status/received_at}），不是新值域。
     *
     * <p>核对<b>不相符</b>的标本不要用本端点「核收后再说」——直接走
     * {@code PUT /{id}/reject}，否则台账上就成了一份已正常核收的标本。
     *
     * <p>幂等由<b>条件更新 + 受影响行数</b>保证（同 8014 口径，不是读-判-写）：并发双击的第二次
     * 返 5208，不会落下第二条 RECEIVE。
     */
    @PutMapping("/specimens/{id}/receive-check")
    @Transactional
    public R<Void> receiveCheck(@PathVariable Long id,
                                @RequestBody(required = false) ReceiveCheckReq req,
                                Authentication auth) {
        var rows = jdbc.queryForList(
                "select status, collected_at, rejected_at from path_specimen where id = ?", id);
        if (rows.isEmpty()) return R.fail(5206, "标本不存在");
        var row = rows.get(0);
        if (row.get("rejected_at") != null) return R.fail(5208, "标本已拒收，不能核收");
        if (!"COLLECTED".equals(row.get("status"))) {
            return R.fail(5208, "标本状态不允许核收（当前 " + row.get("status") + "）");
        }

        Instant received = Instant.now();
        if (req != null && !trim(req.receivedAt()).isEmpty()) {
            Instant t = parseInstant(req.receivedAt());
            if (t == null) return R.fail(5210, "接收时刻格式非法（ISO-8601，如 2026-09-06T09:30）");
            if (t.isAfter(Instant.now().plusSeconds(CLOCK_SKEW_SECONDS))) {
                return R.fail(5210, "接收时刻不能晚于当前时刻");
            }
            Instant collected = toInstant(row.get("collected_at"));
            if (collected != null && t.isBefore(collected)) {
                return R.fail(5210, "接收时刻早于取材时刻");
            }
            received = t;
        }

        int n = jdbc.update("""
                update path_specimen set status = 'RECEIVED', received_at = ?
                where id = ? and status = 'COLLECTED' and rejected_at is null
                """, Timestamp.from(received), id);
        if (n == 0) return R.fail(5208, "标本状态已变化，请刷新后重试");

        jdbc.update("""
                insert into path_process(specimen_id, node, occurred_at, operator_id, remark)
                values (?, 'RECEIVE', ?, ?, ?)
                """, id, Timestamp.from(received), currentUserService.idOf(auth),
                req == null ? null : cut(trim(req.remark()), 255));
        return R.ok();
    }

    /** 拒收入参：{@code reason} 必填（没有原因的拒收在质控上等于没发生过） */
    public record RejectReq(String reason, String rejectedAt) {}

    /**
     * 拒收标本。<b>不删记录、不改 status</b>——见类注释第二、三条口径。
     * 写 {@code reject_reason/rejected_at/rejected_by} 并落一条 {@code path_process.REJECT}。
     *
     * <p>已出诊断的标本不能再拒收（5208）：报告都发了再说「这标本我不收」，台账自相矛盾。
     */
    @PutMapping("/specimens/{id}/reject")
    @Transactional
    public R<Void> reject(@PathVariable Long id, @RequestBody RejectReq req, Authentication auth) {
        String reason = req == null ? "" : trim(req.reason());
        if (reason.isEmpty()) return R.fail(5207, "拒收原因必填");
        if (reason.length() > 255) return R.fail(5207, "拒收原因过长（最多 255 字）");

        var rows = jdbc.queryForList(
                "select status, collected_at, rejected_at from path_specimen where id = ?", id);
        if (rows.isEmpty()) return R.fail(5206, "标本不存在");
        var row = rows.get(0);
        if (row.get("rejected_at") != null) return R.fail(5208, "标本已拒收，不能重复拒收");
        if ("DIAGNOSED".equals(row.get("status"))) return R.fail(5208, "已出诊断的标本不能拒收");

        Instant rejected = Instant.now();
        if (!trim(req.rejectedAt()).isEmpty()) {
            Instant t = parseInstant(req.rejectedAt());
            if (t == null) return R.fail(5210, "拒收时刻格式非法（ISO-8601，如 2026-09-06T09:30）");
            if (t.isAfter(Instant.now().plusSeconds(CLOCK_SKEW_SECONDS))) {
                return R.fail(5210, "拒收时刻不能晚于当前时刻");
            }
            Instant collected = toInstant(row.get("collected_at"));
            if (collected != null && t.isBefore(collected)) return R.fail(5210, "拒收时刻早于取材时刻");
            rejected = t;
        }

        Long me = currentUserService.idOf(auth);
        int n = jdbc.update("""
                update path_specimen set reject_reason = ?, rejected_at = ?, rejected_by = ?
                where id = ? and rejected_at is null and status <> 'DIAGNOSED'
                """, reason, Timestamp.from(rejected), me, id);
        if (n == 0) return R.fail(5208, "标本状态已变化，请刷新后重试");

        jdbc.update("""
                insert into path_process(specimen_id, node, occurred_at, operator_id, remark)
                values (?, 'REJECT', ?, ?, ?)
                """, id, Timestamp.from(rejected), me, cut(reason, 255));
        return R.ok();
    }

    // ==================================================================================
    // 四、检索
    // ==================================================================================

    /**
     * 标本检索：病理号 / 条码 / 患者姓名 / 日期区间 / 状态 / 类别 / 来源。
     *
     * <p>键名沿用数据库列名（{@code path_no}、{@code patient_name}……）——与既有
     * {@code GET /api/pathology/specimens} 同风格，前端两个表可以共用列定义；外层信封
     * （{@code items/truncated/limit}）是驼峰。
     *
     * <p>{@code pathNo}/{@code barcode} 是<b>前缀匹配</b>：扫码枪扫全码时前缀匹配等价于精确匹配，
     * 手输前几位时又能当模糊查，二者兼得。关键字一律参数化并转义 {@code % _ \}。
     *
     * <p>命中超过 {@value #SEARCH_LIMIT} 条时截断并把 {@code truncated} 置 true，<b>不静默截断</b>。
     */
    @GetMapping("/specimens/search")
    public R<Map<String, Object>> search(@RequestParam(required = false) String pathNo,
                                         @RequestParam(required = false) String barcode,
                                         @RequestParam(required = false) String patientName,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String specimenType,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) Boolean rejected) {
        String no = trim(pathNo);
        String bc = trim(barcode);
        String name = trim(patientName);
        String st = trim(status).toUpperCase(Locale.ROOT);
        String type = trim(specimenType).toUpperCase(Locale.ROOT);
        String src = trim(source).toUpperCase(Locale.ROOT);

        if (no.length() > 32 || bc.length() > 32 || name.length() > 64) return R.fail(5209, "检索条件过长");
        if (!st.isEmpty() && !STATUSES.contains(st)) {
            return R.fail(5209, "标本状态非法（COLLECTED/RECEIVED/DIAGNOSED）");
        }
        if (!type.isEmpty() && !TYPE_CODES.containsKey(type)) {
            return R.fail(5209, "标本类别非法（ROUTINE/FROZEN/CYTOLOGY/CONSULT/MOLECULAR）");
        }
        if (!src.isEmpty() && !SOURCES.contains(src)) return R.fail(5209, "来源条件非法（OUTP/INP）");

        LocalDate fromDate = null;
        LocalDate toDate = null;
        if (!trim(from).isEmpty()) {
            fromDate = parseDate(from);
            if (fromDate == null) return R.fail(5210, "起始日期格式非法（yyyy-MM-dd）");
        }
        if (!trim(to).isEmpty()) {
            toDate = parseDate(to);
            if (toDate == null) return R.fail(5210, "截止日期格式非法（yyyy-MM-dd）");
        }
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            return R.fail(5209, "日期区间起止颠倒");
        }

        var sql = new StringBuilder(SPECIMEN_SELECT + " where 1 = 1");
        var args = new ArrayList<Object>();
        if (!no.isEmpty()) { sql.append(" and s.path_no ilike ? escape '\\'"); args.add(escapeLike(no) + "%"); }
        if (!bc.isEmpty()) { sql.append(" and s.barcode ilike ? escape '\\'"); args.add(escapeLike(bc) + "%"); }
        if (!name.isEmpty()) {
            sql.append(" and coalesce(op.name, ip.name) ilike ? escape '\\'");
            args.add("%" + escapeLike(name) + "%");
        }
        if (fromDate != null) {
            sql.append(" and s.collected_at >= ?");
            args.add(Timestamp.from(fromDate.atStartOfDay(ZoneId.of(HipProfiles.ZONE)).toInstant()));
        }
        if (toDate != null) {
            // 半开区间：截止日当天 24:00 之前都算，避免 `<= 当日 00:00` 把当天的行全漏掉
            sql.append(" and s.collected_at < ?");
            args.add(Timestamp.from(toDate.plusDays(1).atStartOfDay(ZoneId.of(HipProfiles.ZONE)).toInstant()));
        }
        if (!st.isEmpty()) { sql.append(" and s.status = ?"); args.add(st); }
        if (!type.isEmpty()) { sql.append(" and s.specimen_type = ?"); args.add(type); }
        if ("OUTP".equals(src)) sql.append(" and s.order_id is not null");
        if ("INP".equals(src)) sql.append(" and s.inp_order_id is not null");
        if (rejected != null) {
            sql.append(rejected ? " and s.rejected_at is not null" : " and s.rejected_at is null");
        }
        sql.append(" order by s.id desc limit ?");
        args.add(SEARCH_LIMIT + 1);

        return R.ok(capped(jdbc.queryForList(sql.toString(), args.toArray()), SEARCH_LIMIT));
    }

    // ==================================================================================
    // 五、既往病理与同名提醒
    // ==================================================================================

    /**
     * 同一患者的既往病理记录 + 同名他人提醒，供病理医师出报告前对比。
     *
     * <p><b>患者身份从来源侧解出</b>：{@code path_specimen} 没有 patient_id 列，门诊走
     * order→挂号→患者、住院走医嘱→住院记录→患者。两条路都解不出时返 5211，
     * <b>不静默返空列表</b>——「这个患者没有既往病理」和「我根本不知道这是谁」是两回事，
     * 后者当成前者显示给医师，等于把一次身份核对失败伪装成一次阴性结果。
     *
     * <p><b>同名提醒只给「有几份、最近一次是什么时候」，不给诊断</b>：病理科最怕同名混标本，
     * 所以提醒必须有；但把另一位同名患者的病理诊断一并吐出来是越界，
     * 医师要看就按患者去查。默认关闭，{@code includeSameName=true} 才查。
     */
    @GetMapping("/specimens/{id}/history")
    public R<Map<String, Object>> history(@PathVariable Long id,
                                          @RequestParam(required = false) Boolean includeSameName) {
        var rows = jdbc.queryForList("""
                select coalesce(r.patient_id, a.patient_id) as patient_id,
                       coalesce(op.name, ip.name) as patient_name
                from path_specimen s
                left join outp_order oo on oo.id = s.order_id
                left join outp_registration r on r.id = oo.registration_id
                left join empi_patient op on op.id = r.patient_id
                left join inp_order io on io.id = s.inp_order_id
                left join inp_admission a on a.id = io.admission_id
                left join empi_patient ip on ip.id = a.patient_id
                where s.id = ?
                """, id);
        if (rows.isEmpty()) return R.fail(5206, "标本不存在");
        Object patientId = rows.get(0).get("patient_id");
        Object patientName = rows.get(0).get("patient_name");
        if (patientId == null) {
            return R.fail(5211, "无法从来源解出患者身份（申请单或住院记录缺失），既往病理不可用");
        }

        var history = jdbc.queryForList(SPECIMEN_SELECT + """
                 where coalesce(r.patient_id, a.patient_id) = ? and s.id <> ?
                 order by s.collected_at desc, s.id desc limit ?
                """, patientId, id, HISTORY_LIMIT + 1);
        boolean truncated = history.size() > HISTORY_LIMIT;
        if (truncated) history = history.subList(0, HISTORY_LIMIT);

        var out = new LinkedHashMap<String, Object>();
        out.put("patientId", patientId);
        out.put("patientName", patientName);
        out.put("items", history);
        out.put("truncated", truncated);
        out.put("limit", HISTORY_LIMIT);

        if (Boolean.TRUE.equals(includeSameName) && patientName != null) {
            // 只统计「有几份、最近一次何时」，不带诊断——同名提醒不是查别人的报告
            out.put("sameName", jdbc.queryForList("""
                    select p.id as patient_id, p.name as patient_name, p.sex, p.birth_date,
                           count(s.id) as specimen_count, max(s.collected_at) as latest_collected_at
                    from empi_patient p
                    join path_specimen s
                      on s.id in (
                         select s2.id from path_specimen s2
                         left join outp_order oo on oo.id = s2.order_id
                         left join outp_registration r on r.id = oo.registration_id
                         left join inp_order io on io.id = s2.inp_order_id
                         left join inp_admission a on a.id = io.admission_id
                         where coalesce(r.patient_id, a.patient_id) = p.id)
                    where p.name = ? and p.id <> ?
                    group by p.id, p.name, p.sex, p.birth_date
                    order by max(s.collected_at) desc
                    limit 20
                    """, patientName, patientId));
        }
        return R.ok(out);
    }

    // ==================================================================================
    // 六、内部：病理号生成 / SQL 片段 / 小工具
    // ==================================================================================

    /**
     * 两个来源统一的标本行 select 片段——门诊走 order→挂号→患者，住院走医嘱→住院记录→患者，
     * 一律 left join 再 coalesce，<b>任何一侧缺失都不会把行整条吞掉</b>
     * （既有 {@code GET /specimens} 就是栽在 inner join 上，见 PathologyController 的注释）。
     * 使用时在其后拼 {@code where ...}，因此结尾故意不带换行以外的东西。
     */
    private static final String SPECIMEN_SELECT = """
            select s.id, s.path_no, s.barcode, s.part_no, s.specimen_type, s.status, s.urgent,
                   s.specimen_desc, s.sampling_site, s.clinical_diagnosis, s.fixative, s.fixed_at,
                   s.collected_at, s.received_at, s.diagnosed_at, s.report_issued_at,
                   s.diagnosis, s.reject_reason, s.rejected_at,
                   case when s.order_id is not null then 'OUTP' else 'INP' end as source,
                   s.order_id, s.inp_order_id,
                   coalesce(oo.item_name, io.item_name) as item_name,
                   coalesce(op.name, ip.name) as patient_name,
                   coalesce(r.patient_id, a.patient_id) as patient_id
            from path_specimen s
            left join outp_order oo on oo.id = s.order_id
            left join outp_registration r on r.id = oo.registration_id
            left join empi_patient op on op.id = r.patient_id
            left join inp_order io on io.id = s.inp_order_id
            left join inp_admission a on a.id = io.admission_id
            left join empi_patient ip on ip.id = a.patient_id
            """;

    /** 病理号规则不成立（配置错、位数不够、算出来超列宽）——统一走 5205 */
    private static class PathNoRuleException extends RuntimeException {
        PathNoRuleException(String msg) { super(msg); }
    }

    /**
     * 按「年 + 类别」生成下一个病理号。
     *
     * <p><b>为什么不用数据库序列</b>：序列不能按年归零、也不能按类别分组，而
     * {@code path.no.pattern} 是<b>可配置</b>的（各院编号习惯不同），今天的 {@code {yyyy}-{type}-{seq:6}}
     * 明天可能配成别的。所以序号由「同前缀、同长度、序号段全是数字」的既有病理号取 max 推出，
     * 外面套 {@code pg_advisory_xact_lock} 序列化并发。
     *
     * <p><b>已知边界</b>：单年单类别超过 {@code 10^位数 - 1} 例（默认 999999）时，
     * 新号会比既有号多一位、长度不同，于是取 max 时看不见彼此，最终由 {@code path_no} 的
     * unique 约束拒绝而不是悄悄发重号。真到那一天要改的是配置里的位数，不是这段代码。
     */
    private String nextPathNo(String specimenType) {
        String pattern = configReader.get("path.no.pattern", "{yyyy}-{type}-{seq:6}");
        Matcher m = SEQ_TOKEN.matcher(pattern);
        if (!m.find()) {
            throw new PathNoRuleException("病理号规则 path.no.pattern 缺少 {seq} 占位符：" + pattern);
        }
        int width = m.group(1) == null ? 6 : Integer.parseInt(m.group(1));
        if (width < 1 || width > 12) throw new PathNoRuleException("病理号规则的序号位数须在 1–12 之间");

        int year = LocalDate.now(ZoneId.of(HipProfiles.ZONE)).getYear();
        String typeCode = TYPE_CODES.get(specimenType);
        String prefix = expandTokens(pattern.substring(0, m.start()), year, typeCode);
        String suffix = expandTokens(pattern.substring(m.end()), year, typeCode);
        int total = prefix.length() + width + suffix.length();
        if (total > 32) throw new PathNoRuleException("按规则生成的病理号超过 32 字符（path_no 列宽）");

        // 建议锁：同年同类别串行，不同类别并行；随事务提交自动释放。
        // 用 ResultSetExtractor 而非 queryForObject：pg_advisory_xact_lock 的返回类型是
        // **void**（OID 2278），queryForObject 会去读这一列的值，走 pgjdbc
        // getObject(int, Class) 的「不支持的转换」分支——虽然 Spring 会 catch 后回落，
        // 但我们要的只是「把锁拿到」，根本不需要读这个值。不读就不会踩。
        jdbc.query("select pg_advisory_xact_lock(?, ?)",
                (ResultSetExtractor<Void>) rs -> null,
                ADVISORY_LOCK_NS, year * 10 + TYPE_ORDER.indexOf(specimenType));

        Long max = jdbc.queryForObject("""
                select coalesce(max(substr(path_no, ?::int, ?::int)::bigint), 0)
                from path_specimen
                where path_no like ? escape '\\'
                  and length(path_no) = ?
                  and substr(path_no, ?::int, ?::int) ~ '^[0-9]+$'
                """, Long.class,
                prefix.length() + 1, width,
                escapeLike(prefix) + "%",
                total,
                prefix.length() + 1, width);
        long seq = (max == null ? 0L : max) + 1;
        return prefix + String.format("%0" + width + "d", seq) + suffix;
    }

    /** 展开 {yyyy}/{yy}/{type}；展开后仍残留花括号说明规则里有本版不认识的占位符，宁可报错 */
    private static String expandTokens(String part, int year, String typeCode) {
        String s = part.replace("{yyyy}", String.valueOf(year))
                .replace("{yy}", String.format("%02d", year % 100))
                .replace("{type}", typeCode);
        if (LEFTOVER_TOKEN.matcher(s).find()) {
            throw new PathNoRuleException("病理号规则含未知占位符：" + part + "（只认 {yyyy} {yy} {type} {seq:n}）");
        }
        return s;
    }

    /**
     * 文本字段列宽预检（列宽照 V21 与 V144 的定义写死）。
     *
     * <p><b>为什么必须自己检</b>：不检的话，一段 300 字的大体描述会一路撞到 varchar(255)，
     * 抛 {@code DataIntegrityViolationException}，被 {@code GlobalExceptionHandler} 吃成
     * 4091「数据不符合约束要求，请检查输入」——<b>不说是哪个字段</b>，登记员只能一个个删着试。
     * 这里的截断策略是<b>报错而不是 cut()</b>：{@code path_process.remark} 那种留痕文本截了无妨，
     * 但标本描述与临床诊断是<b>诊断依据</b>，悄悄截掉后半段比登记失败危险得多。
     */
    private static String checkLengths(RegisterReq req) {
        if (trim(req.specimenDesc()).length() > 255) return "标本描述过长（最多 255 字）";
        if (trim(req.samplingSite()).length() > 128) return "取材部位过长（最多 128 字）";
        if (trim(req.clinicalDiagnosis()).length() > 500) return "临床诊断过长（最多 500 字）";
        if (trim(req.fixative()).length() > 32) return "固定液过长（最多 32 字）";
        return null;
    }

    /** 硬上限 + truncated 标记，不静默截断（同 v43 车道D {@code /admissions/orders/search}） */
    private static Map<String, Object> capped(List<Map<String, Object>> rows, int limit) {
        boolean truncated = rows.size() > limit;
        var out = new LinkedHashMap<String, Object>();
        out.put("items", truncated ? rows.subList(0, limit) : rows);
        out.put("truncated", truncated);
        out.put("limit", limit);
        return out;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Timestamp ts) return ts.toInstant();
        if (v instanceof Instant i) return i;
        if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (v instanceof java.util.Date d) return d.toInstant();
        return null;
    }

    /**
     * 宽容地解析 ISO-8601：带时区 → 原样；不带时区 → 按<b>业务时区</b>解释（不是 JVM 默认时区——
     * 容器 TZ 未生效时 JVM 是 UTC，会把「09:30 离体」记成北京时间 17:30）；「日期 空格 时间」也认。
     * 解析不出返 null，由调用方返 5210，<b>绝不回落 now()</b>。与 SurgeryService#parseInstant 同口径。
     */
    private static Instant parseInstant(String raw) {
        String s = raw.trim();
        if (s.length() > 10 && s.charAt(10) == ' ') s = s.substring(0, 10) + "T" + s.substring(11);
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException ignore) {
            // 继续尝试无时区形态
        }
        try {
            return LocalDateTime.parse(s).atZone(ZoneId.of(HipProfiles.ZONE)).toInstant();
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    /** yyyy-MM-dd；解析不出返 null（由调用方返 5210），同样不回落「今天」 */
    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
