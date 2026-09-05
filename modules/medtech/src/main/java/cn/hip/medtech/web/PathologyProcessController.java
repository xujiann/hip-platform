package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v48 车道 P2：病理技术流程——取材 → 脱水 → 包埋 → 切片 → 染色。
 *
 * <p>这五个环节在本版之前<b>完全不存在</b>：病理域只有 {@code path_specimen} 一张表，
 * {@code status} 只有 COLLECTED/RECEIVED/DIAGNOSED 三档，中间的制片过程一行记录都没有。
 * 本控制器把 V144 新建的 {@code path_block} / {@code path_slide} / {@code path_process}
 * 三张表接上业务。
 *
 * <p><b>与既有 {@link PathologyController} 的边界</b>：既有五个端点
 * （{@code GET /api/pathology/pending}、{@code POST /specimens}、
 * {@code PUT /specimens/{barcode}/receive}、{@code PUT /specimens/{barcode}/diagnose}、
 * {@code GET /specimens}）与错误码 4550–4553 一个字节没动。本控制器全部走
 * {@code /api/pathology/process/**} 新前缀。
 *
 * <p><b>四条口径</b>：
 * <ul>
 *   <li><b>编号是「标本内 / 蜡块内序号」，不是全局序列</b>：病理报告上写的是「3 号蜡块第 2 张切片」。
 *       {@code block_no} 在标本内从 1 起、{@code slide_no} 在蜡块内从 1 起；
 *       {@code block_code} =「病理号-块号」、{@code slide_code} =「病理号-块号-片号」。
 *       序号由<b>单条 insert 内的子查询</b>取 max+1，并发下靠
 *       {@code uq_path_block_no} / {@code uq_path_slide_no} 兜底（撞号由全局兜底返 4090，
 *       调用方重试即可）——<b>不做「查了再写」的两段式</b>，那个窗口更大。</li>
 *   <li><b>病理号缺失时编码回落到 barcode</b>：{@code path_no} 由 P1 车道在登记环节生成，
 *       V144 之前的存量标本没有病理号。此时回落既有 {@code barcode}（PB+序列，not null unique）
 *       而不是拒绝取材，更<b>不是临时造一个病理号</b>——造号会与 P1 的按年连续编号撞车。
 *       返回体带 {@code codePrefixSource}=PATH_NO|BARCODE，让调用方看得见用的是哪个。
 *       <b>已知缺口</b>：{@code block_code} 在建块时定死，若病理号事后才补，旧块编码不会跟着变
 *       （改编码就是改已打印在蜡块上的字，那是更坏的事）。</li>
 *   <li><b>脱水篮分组是逻辑分组，不是设备对接</b>：{@code dehydrate_batch} 只是一个批次号字符串。
 *       平台<b>不与脱水机、包埋机、染色机做任何通讯</b>，读不到程序号、温度、试剂缸次、运行时长，
 *       也不会因设备报警更新任何状态。它解决的是「这一篮蜡块是一起走的，出问题一起追溯」，
 *       不是脱水机监控。任何把它读成设备直连的说法都是错的。</li>
 *   <li><b>时间一律由 SQL 的 {@code now()} 派生，Java 侧不产生任何时间字面量</b>。
 *       PostgreSQL 的 {@code now()} 是<b>事务开始时刻</b>且事务内恒定，所以同一次取材里
 *       各蜡块的 {@code created_at} 与 GROSSING 节点的 {@code occurred_at} 天然逐位相等；
 *       包埋、染色的流转节点更是直接 {@code select b.embedded_at} / {@code sl.stained_at}
 *       回填节点时间，两处绝不各取一次 now()。</li>
 * </ul>
 *
 * <p><b>错误码</b>（v48 病理段的 5220–5239 取材 / 5240–5259 技术制片两个子段）：
 * <ul>
 *   <li>5220 标本不存在</li>
 *   <li>5221 标本状态不允许取材（未核收 / 已拒收 / 已诊断而未声明补取材）</li>
 *   <li>5222 取材请求非法（蜡块清单为空或超上限 / 组织描述超长 / 大体描述字段非法 / 备注超长 /
 *       已有大体所见时重复提交）</li>
 *   <li>5223 该标本已取材（未显式声明 append 的重复取材）</li>
 *   <li>5224 无法识别当前登录用户，不能登记取材</li>
 *   <li>5225 取材模板不存在</li>
 *   <li>5240 蜡块不存在</li>
 *   <li>5241 蜡块状态不允许该操作（重复包埋 / 已包埋不得再改脱水批次）</li>
 *   <li>5242 脱水批次请求非法（批次号空或超长 / 蜡块清单为空或超上限 / 备注超长）</li>
 *   <li>5243 切片与染色请求非法（份数 / 染色类型 / 染色项目 / 备注）</li>
 *   <li>5244 切片不存在</li>
 *   <li>5245 切片状态不允许该操作（已染色，重复核销）</li>
 *   <li>5246 染色质量取值非法</li>
 *   <li>5247 批量核销请求非法（清单为空 / 超上限 / 参数超长）</li>
 *   <li>5248 无法识别当前登录用户，不能登记制片</li>
 *   <li>5249 检索参数非法（日期格式 / 区间倒置 / 跨度过大 / 枚举取值）</li>
 * </ul>
 * 5226–5239 与 5250–5259 <b>本版未使用，也未登记</b>——不写代码就不占码。
 *
 * <p><b>本版如实留的缺口（没有造假实现）</b>：
 * <ul>
 *   <li><b>不做玻片/蜡块打码设备直连</b>——打码机属设备驱动硬边界。平台只生成
 *       {@code block_code} / {@code slide_code} 字符串并回给调用方，<b>打印由谁完成不在本版范围</b>。</li>
 *   <li><b>不做图像</b>——{@code MultipartFile} 全仓零命中，没有文件上传基础设施；
 *       大体图像、切片扫描、数字病理 WSI 一概不做。</li>
 *   <li><b>脱水批次没有独立的「分组时刻」列</b>：{@code path_block} 无 batch_assigned_at，
 *       本版不加列。故批次进度里的时间只能给建块时间与包埋时间；DEHYDRATE 流转节点是标本级的，
 *       <b>同一标本的蜡块若分属两个批次，节点时间区分不到批次</b>。这是地基缺列，不是本版漏做。</li>
 *   <li><b>切片没有独立的「制片完成」时刻</b>：{@code path_slide} 只有 {@code stained_at}
 *       一个完成时间戳。故「批量核销」核销的<b>就是染色完成</b>，不另造一个只活在返回体里的
 *       「已制片」状态——那种状态重启就没了，是假实现。</li>
 *   <li><b>取材模板只能配到「字段清单」这一层</b>，且没有模板管理界面，见 {@link #grossingTemplates}。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pathology/process")
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP')")   // 与既有 PathologyController 同口径
public class PathologyProcessController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    /** 检索类端点硬上限：超限回 {@code truncated=true}，<b>不做翻页也不静默截断</b> */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;

    /** 检索时间窗最大跨度（天），与 AnesQcController 同量级：防一个参数打出上亿行 */
    private static final int MAX_SPAN_DAYS = 366;

    /** 一次取材最多产出的蜡块数 */
    private static final int MAX_BLOCKS_PER_GROSSING = 50;

    /** 单标本蜡块序号上限：block_no 是 smallint，且 block_code 只有 varchar(40) */
    private static final int MAX_BLOCK_NO = 999;

    /** 一次切片最多产出的切片数 */
    private static final int MAX_SLIDES_PER_SECTION = 20;

    /** 单蜡块切片序号上限：slide_no 是 smallint，且 slide_code 只有 varchar(48) */
    private static final int MAX_SLIDE_NO = 999;

    /** 批量操作一次最多处理的条目数（脱水分篮 / 批量核销） */
    private static final int MAX_BATCH_SIZE = 200;

    private static final int TISSUE_DESC_MAX = 500;    // path_block.tissue_desc
    private static final int GROSS_MAX = 2000;         // path_specimen.gross_finding
    private static final int GROSS_LABEL_MAX = 32;
    private static final int GROSS_VALUE_MAX = 300;
    private static final int GROSS_FIELD_MAX = 20;
    private static final int REMARK_MAX = 255;         // path_process.remark
    private static final int BATCH_NO_MAX = 32;        // path_block.dehydrate_batch
    private static final int STAIN_ITEM_MAX = 64;      // path_slide.stain_item

    /** 染色类型白名单（与 chk_path_slide_stain 一致） */
    public static final List<String> STAIN_TYPES = List.of("HE", "IHC", "SPECIAL", "MOLECULAR");

    /** 切片质量白名单（与 chk_path_slide_quality 一致）：染色切片优良率的唯一数据源 */
    public static final List<String> SLIDE_QUALITIES = List.of("GOOD", "FAIR", "POOR");

    /** 取材模板的 sys_config 键前缀，见 {@link #grossingTemplates} */
    public static final String TEMPLATE_KEY_PREFIX = "path.grossing.template.";

    // ==================================================================
    // 一、取材
    // ==================================================================

    /**
     * 取材工作列表：已核收、待取材的标本，加急优先。
     *
     * <p><b>「待取材」的判定口径</b>：已核收（{@code received_at is not null}）、未拒收、
     * <b>名下一块蜡块都没有</b>。用蜡块存在与否而不是 {@code status} 判断，是因为既有 status
     * 只有 COLLECTED/RECEIVED/DIAGNOSED 三档、压根没有「已取材」这一档，
     * 而给它加一档就是在改既有 {@code GET /specimens} 返回体的值域（铁律一）。
     *
     * <p><b>pending 档为什么要排除已诊断标本</b>：V144 之前的存量标本<b>一块蜡块都没有</b>
     * （零回填纪律——当时确实没采集，不许拿别的列凑）。不排除的话，它们会永远堵在
     * 「待取材」列表顶端。要看这批存量标本请显式用 {@code scope=all}，<b>不默默把两类混在一起</b>。
     *
     * <p>排序：加急优先 → 核收早的优先。{@code hoursSinceReceived} 是<b>原始事实</b>
     * （距核收的小时数），本端点<b>不判「是否超时」</b>——时限阈值口径归质控段（5280–5299）
     * 唯一定义，两处各判一次必然分叉。
     *
     * @param scope pending（默认：已核收、未拒收、未诊断且无蜡块）/ all（已核收未拒收的全部）
     */
    @GetMapping("/grossing/worklist")
    public R<Map<String, Object>> grossingWorklist(@RequestParam(required = false) String scope,
                                                   @RequestParam(required = false) String specimenType,
                                                   @RequestParam(required = false) Boolean urgentOnly,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Integer limit) {
        String mode = "all".equalsIgnoreCase(trim(scope)) ? "all" : "pending";
        int cap = capOf(limit);

        var sql = new StringBuilder(SPECIMEN_SELECT)
                .append(" where s.received_at is not null and s.rejected_at is null ");
        var args = new ArrayList<Object>();

        // 下面每一段 SQL 片段都是编译期常量，用户输入一律走 ?（禁止把值拼进 SQL）
        if ("pending".equals(mode)) {
            sql.append(" and s.diagnosed_at is null ")
               .append(" and not exists (select 1 from path_block b where b.specimen_id = s.id) ");
        }
        String type = trimUpper(specimenType);
        if (type != null) {
            sql.append(" and s.specimen_type = ? ");
            args.add(type);
        }
        if (Boolean.TRUE.equals(urgentOnly)) {
            sql.append(" and s.urgent = true ");
        }
        String kw = trim(keyword);
        if (kw != null) {
            sql.append(" and (s.barcode ilike ? or s.path_no ilike ? or p.name ilike ? or p.patient_no ilike ?) ");
            String like = "%" + kw + "%";
            for (int i = 0; i < 4; i++) args.add(like);
        }
        sql.append(" order by s.urgent desc, s.received_at asc nulls last, s.id asc limit ? ");
        args.add(cap + 1);   // 多取 1 条判 truncated：只取 cap 条会让「刚好第 cap 条」漏报

        var body = page(jdbc.queryForList(sql.toString(), args.toArray()), cap);
        body.put("scope", mode);
        body.put("note", "pending：已核收、未拒收、未诊断且尚无蜡块的标本；"
                + "all：已核收未拒收的全部（含已取材与 V144 之前无蜡块记录的存量标本）。"
                + "hoursSinceReceived 是距核收的小时数，是否超时由病理质控端点判定，本端点不判。");
        return R.ok(body);
    }

    public record BlockReq(String tissueDesc) {}

    public record GrossingReq(Long specimenId,
                              String templateCode,
                              Map<String, String> gross,
                              String grossText,
                              Boolean append,
                              String remark,
                              List<BlockReq> blocks) {}

    /**
     * 取材登记：结构化大体描述 + 一次产出 N 个蜡块，并写 GROSSING 流转节点。
     *
     * <p><b>蜡块编号</b>：{@code block_no} 是<b>标本内序号</b>，从既有最大号 +1 续排
     * （补取材追加的块接着排，不会从 1 重来）；{@code block_code} =「编码前缀-块号」，
     * 编码前缀取 {@code path_no}、缺失时回落 {@code barcode}（见类注释第二条口径）。
     *
     * <p><b>大体描述写在哪</b>：结构化字段 {@code gross}（有序的 标签→值）与自由文本
     * {@code grossText} 拼成一段文本，写入 {@code path_specimen.gross_finding}——
     * 那本来就是「大体所见」列。但有两条硬规矩：
     * <ul>
     *   <li><b>只在该列为空时写，绝不覆盖</b>。该列同时被既有
     *       {@code PUT /specimens/{barcode}/diagnose} 写（病理医师出报告时可修订大体所见），
     *       本端点不去抢它。</li>
     *   <li>已有大体所见时若仍传 {@code gross}/{@code grossText}，<b>直接报 5222 拒绝</b>，
     *       不静默丢弃：补取材的组织描述应写在 {@code blocks[].tissueDesc}（每块 500 字），
     *       修订大体所见走既有 diagnose 端点。悄悄吞掉用户写的字比报错坏得多。</li>
     * </ul>
     *
     * <p><b>append 的语义</b>：第二次及以后的取材必须显式 {@code append=true}（否则 5223），
     * 已诊断标本的补取材同样必须显式声明（否则 5221）。这不是为了麻烦人——病理医师看完 HE 片
     * 下的补取材（RESAMPLE）技术医嘱是诊断环节的正常延伸，而误点两次「取材」凭空多出一组蜡块
     * 则是事故，两者必须由调用方明确区分。
     */
    @PostMapping("/grossing")
    @Transactional
    public R<Map<String, Object>> grossing(@RequestBody GrossingReq req, Authentication auth) {
        if (req == null || req.specimenId() == null) return R.fail(5222, "标本 id 不能为空");

        List<BlockReq> blocks = req.blocks() == null ? List.of() : req.blocks();
        if (blocks.isEmpty()) return R.fail(5222, "取材必须至少产出 1 个蜡块");
        if (blocks.size() > MAX_BLOCKS_PER_GROSSING) {
            return R.fail(5222, "一次取材最多 " + MAX_BLOCKS_PER_GROSSING + " 个蜡块，收到 " + blocks.size());
        }
        var tissueDescs = new ArrayList<String>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            String d = blocks.get(i) == null ? null : trim(blocks.get(i).tissueDesc());
            if (d != null && d.length() > TISSUE_DESC_MAX) {
                return R.fail(5222, "第 " + (i + 1) + " 块的组织描述超长（上限 " + TISSUE_DESC_MAX + " 字）");
            }
            tissueDescs.add(d);
        }

        String remark = trim(req.remark());
        if (remark != null && remark.length() > REMARK_MAX) {
            return R.fail(5222, "备注超长（上限 " + REMARK_MAX + " 字）");
        }

        String templateCode = trimUpper(req.templateCode());
        if (templateCode != null && !templateCatalog().containsKey(templateCode)) {
            return R.fail(5225, "取材模板不存在：" + req.templateCode());
        }

        String grossAssembled;
        try {
            grossAssembled = assembleGross(req.gross(), trim(req.grossText()));
        } catch (IllegalArgumentException e) {
            return R.fail(5222, e.getMessage());
        }

        var head = one("""
                select s.id, s.barcode, s.path_no, s.status, s.received_at, s.rejected_at, s.diagnosed_at,
                       s.gross_finding,
                       (select count(*) from path_block b where b.specimen_id = s.id) as block_count,
                       (select coalesce(max(b.block_no), 0) from path_block b
                         where b.specimen_id = s.id) as max_block_no
                from path_specimen s where s.id = ?
                """, req.specimenId());
        if (head == null) return R.fail(5220, "标本不存在：" + req.specimenId());
        if (head.get("rejected_at") != null) return R.fail(5221, "标本已拒收，不能取材");
        if (head.get("received_at") == null) return R.fail(5221, "标本尚未核收，不能取材");

        boolean append = Boolean.TRUE.equals(req.append());
        long existing = asLong(head.get("block_count"));
        if (existing > 0 && !append) {
            return R.fail(5223, "该标本已有 " + existing + " 个蜡块；补取材请显式传 append=true");
        }
        if (head.get("diagnosed_at") != null && !append) {
            return R.fail(5221, "标本已出诊断；诊断后的补取材请显式传 append=true");
        }

        long maxNo = asLong(head.get("max_block_no"));
        if (maxNo + blocks.size() > MAX_BLOCK_NO) {
            return R.fail(5222, "蜡块序号将超过上限 " + MAX_BLOCK_NO + "（当前最大 " + maxNo + "）");
        }

        String existingGross = trim((String) head.get("gross_finding"));
        boolean grossWritten = false;
        if (grossAssembled != null) {
            if (existingGross != null) {
                return R.fail(5222, "该标本已有大体所见，本端点不覆盖既有内容："
                        + "补取材的组织描述请写在 blocks[].tissueDesc，修订大体所见请走诊断端点");
            }
            jdbc.update("""
                    update path_specimen set gross_finding = ?
                    where id = ? and (gross_finding is null or btrim(gross_finding) = '')
                    """, grossAssembled, req.specimenId());
            grossWritten = true;
        }

        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5224, "无法识别当前登录用户，不能登记取材");

        String pathNo = trim((String) head.get("path_no"));
        String prefix = pathNo != null ? pathNo : (String) head.get("barcode");

        var created = new ArrayList<Map<String, Object>>(blocks.size());
        for (String tissueDesc : tissueDescs) {
            // block_no 与 block_code 由单条 insert 内的子查询取 max+1：
            // 「查了再写」的两段式窗口更大；并发撞号由 uq_path_block_no 兜底（全局返 4090）。
            // 参数上的 ::bigint / ::varchar 不是装饰：insert ... select 的 select 列表里，
            // PostgreSQL 对未指定类型的参数（尤其传 null 时）会报
            // 「could not determine data type of parameter」，显式转型才是稳的。
            var ins = jdbc.queryForList("""
                    insert into path_block(specimen_id, block_no, block_code, tissue_desc, created_by)
                    select ?::bigint, nb.n, ?::text || '-' || nb.n::text, ?::varchar, ?::bigint
                    from (select coalesce(max(block_no), 0) + 1 as n
                          from path_block where specimen_id = ?) nb
                    returning id, block_no, block_code, tissue_desc, created_at
                    """, req.specimenId(), prefix, tissueDesc, uid, req.specimenId());
            created.add(ins.get(0));
        }

        // GROSSING 节点：now() 在 PostgreSQL 里是事务开始时刻且事务内恒定，
        // 与上面各蜡块的 created_at 逐位相等——Java 侧不产生任何时间字面量
        logProcess(req.specimenId(), "GROSSING", uid,
                "取材产出 " + created.size() + " 块"
                        + (templateCode == null ? "" : "（模板 " + templateCode + "）")
                        + (remark == null ? "" : "：" + remark));

        var body = new LinkedHashMap<String, Object>();
        body.put("specimenId", req.specimenId());
        body.put("append", append);
        body.put("codePrefix", prefix);
        body.put("codePrefixSource", pathNo != null ? "PATH_NO" : "BARCODE");
        body.put("blocks", created);
        body.put("blockCount", created.size());
        body.put("totalBlockCount", existing + created.size());
        body.put("grossFinding", grossAssembled);
        body.put("grossFindingWritten", grossWritten);
        body.put("note", "block_code 由「编码前缀-块号」生成并在建块时定死；"
                + (pathNo == null ? "本标本尚无病理号，编码回落院内条码 barcode，"
                                  + "病理号事后补发不会回改已生成的 block_code。" : "")
                + "打码机属设备直连，平台只给编码字符串，不负责打印。");
        return R.ok(body);
    }

    /**
     * 取材模板：常见标本类型的大体描述字段清单。
     *
     * <p><b>这个「模板」到底能配到什么程度，说清楚</b>：
     * <ul>
     *   <li>内置常量给出 {@code code / name / fields（字段清单）/ example（示例描述）}。</li>
     *   <li>字段清单<b>是真可配的</b>：{@code sys_config} 键 {@code path.grossing.template.<CODE>}，
     *       值为逗号（半角或全角）分隔的字段标签。运维插一行配置即可改字段清单，
     *       也可新增内置里没有的模板代码，<b>不需要发版、不需要迁移</b>。
     *       每条返回体带 {@code source}=BUILTIN|CONFIG，看得见这条是配的还是内置的。</li>
     *   <li><b>但 {@code sys_config.cfg_value} 只有 varchar(255)</b>——字段清单放得下，
     *       长篇示例描述放不下。所以 {@code example} <b>永远来自内置硬编码常量</b>，配置覆盖不了；
     *       配置新增的模板代码没有 example（返回 null，不编一段假的）。</li>
     *   <li><b>本版没有模板写端点、没有模板管理界面、没有按科室/按医师维护、没有版本与停用</b>。
     *       那些需要独立的模板表与迁移，而地基已定、本版不加表不加列；
     *       配置写入口属配置段（5300–5319），不在本车道。</li>
     * </ul>
     * 一句话：<b>字段清单可配，示例文本硬编码，模板管理没有。</b>
     *
     * @param code 只看某一个模板；不存在返 5225
     */
    @GetMapping("/grossing/templates")
    public R<Map<String, Object>> grossingTemplates(@RequestParam(required = false) String code) {
        var catalog = templateCatalog();
        var items = new ArrayList<Map<String, Object>>();
        String want = trimUpper(code);
        if (want != null) {
            var hit = catalog.get(want);
            if (hit == null) return R.fail(5225, "取材模板不存在：" + code);
            items.add(hit);
        } else {
            items.addAll(catalog.values());
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        body.put("configKeyPrefix", TEMPLATE_KEY_PREFIX);
        body.put("note", "fields（字段清单）可由 sys_config 键 " + TEMPLATE_KEY_PREFIX
                + "<CODE> 覆盖或新增，值为逗号分隔的标签；example（示例描述）是内置硬编码常量，"
                + "配置覆盖不了——sys_config.cfg_value 只有 255 字符。"
                + "本版没有模板写端点与模板管理界面：那需要独立模板表与迁移，本版不加表不加列。");
        return R.ok(body);
    }

    // ==================================================================
    // 二、脱水篮分组（逻辑分组，不是设备对接）
    // ==================================================================

    public record DehydrateBatchReq(String batchNo, List<Long> blockIds, String remark) {}

    /**
     * 脱水篮分组：把若干蜡块归入同一个脱水批次号，并按标本写 DEHYDRATE 流转节点。
     *
     * <p><b>这是逻辑分组，不是脱水机直连</b>。平台不与脱水机通讯，读不到程序号、温度、
     * 试剂缸次、运行时长，也不会因设备报警更新任何状态；{@code dehydrate_batch}
     * 就是一个批次号字符串，作用是「这一篮蜡块一起走，出问题一起追溯」。见类注释第三条口径。
     *
     * <p><b>全批校验通过才写，否则整批拒绝</b>：任一蜡块不存在（5240）或已包埋（5241）
     * 都会连同问题条目一起返回，<b>不做「能写几个写几个」的部分成功</b>——
     * 部分成功会让调用方以为整篮都进了批次，实际漏了两块，那两块最后不知去向。
     *
     * <p>已在别的批次里的蜡块<b>允许改判</b>（换篮是真实操作），但在返回体的 {@code moved} 里
     * 逐条列出原批次号，不静默搬走。
     */
    @PutMapping("/blocks/dehydrate-batch")
    @Transactional
    public R<Map<String, Object>> dehydrateBatch(@RequestBody DehydrateBatchReq req, Authentication auth) {
        String batchNo = req == null ? null : trim(req.batchNo());
        if (batchNo == null) return R.fail(5242, "脱水批次号不能为空");
        if (batchNo.length() > BATCH_NO_MAX) {
            return R.fail(5242, "脱水批次号超长（上限 " + BATCH_NO_MAX + " 字）");
        }
        String remark = req == null ? null : trim(req.remark());
        if (remark != null && remark.length() > REMARK_MAX) {
            return R.fail(5242, "备注超长（上限 " + REMARK_MAX + " 字）");
        }
        var ids = distinctIds(req == null ? null : req.blockIds());
        if (ids.isEmpty()) return R.fail(5242, "蜡块清单不能为空");
        if (ids.size() > MAX_BATCH_SIZE) {
            return R.fail(5242, "一次最多 " + MAX_BATCH_SIZE + " 个蜡块，收到 " + ids.size());
        }

        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5248, "无法识别当前登录用户，不能登记脱水分篮");

        String in = placeholders(ids.size());
        var rows = jdbc.queryForList("""
                select b.id, b.specimen_id, b.block_no, b.block_code, b.dehydrate_batch, b.embedded_at
                from path_block b where b.id in (%s)
                """.formatted(in), ids.toArray());

        var found = new LinkedHashSet<Long>();
        var embedded = new ArrayList<Object>();
        var moved = new ArrayList<Map<String, Object>>();
        for (var r : rows) {
            found.add(asLong(r.get("id")));
            if (r.get("embedded_at") != null) embedded.add(r.get("block_code"));
            String old = trim((String) r.get("dehydrate_batch"));
            if (old != null && !old.equals(batchNo)) {
                var m = new LinkedHashMap<String, Object>();
                m.put("blockId", r.get("id"));
                m.put("blockCode", r.get("block_code"));
                m.put("fromBatchNo", old);
                moved.add(m);
            }
        }
        var missing = ids.stream().filter(i -> !found.contains(i)).toList();
        if (!missing.isEmpty()) return R.fail(5240, "蜡块不存在：" + missing);
        if (!embedded.isEmpty()) {
            return R.fail(5241, "以下蜡块已包埋，不能再改脱水批次：" + embedded);
        }

        var updArgs = new ArrayList<Object>();
        updArgs.add(batchNo);
        updArgs.addAll(ids);
        int n = jdbc.update("update path_block set dehydrate_batch = ? where id in (%s)".formatted(in),
                updArgs.toArray());

        // DEHYDRATE 节点按标本去重各记一条：节点是标本级的，同一标本的蜡块若分属两个批次，
        // 节点时间区分不到批次（地基无 batch_assigned_at，见类注释缺口）
        var specimenIds = rows.stream().map(r -> asLong(r.get("specimen_id"))).distinct().toList();
        for (Long sid : specimenIds) {
            long cnt = rows.stream().filter(r -> asLong(r.get("specimen_id")) == sid.longValue()).count();
            logProcess(sid, "DEHYDRATE", uid, "脱水批次 " + batchNo + "，本标本 " + cnt + " 块"
                    + (remark == null ? "" : "：" + remark));
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("batchNo", batchNo);
        body.put("blockCount", n);
        body.put("specimenCount", specimenIds.size());
        body.put("moved", moved);
        body.put("note", "脱水篮分组是逻辑分组：平台不与脱水机通讯，不采集程序号/温度/时长，"
                + "批次号只用于成组追溯。"
                + (moved.isEmpty() ? "" : "有蜡块从其它批次改判，明细见 moved。"));
        return R.ok(body);
    }

    /**
     * 脱水批次进度：按批次号汇总蜡块数、涉及标本数、已包埋数。
     *
     * <p><b>时间字段口径</b>：{@code firstBlockCreatedAt}/{@code lastBlockCreatedAt} 是<b>建块时间</b>
     * （取材时刻），<b>不是分篮时刻</b>——{@code path_block} 没有 batch_assigned_at 列，本版不加列。
     * {@code lastEmbeddedAt} 是该批最后一块的包埋时刻。分篮动作本身请查 {@code path_process}
     * 的 DEHYDRATE 节点（标本级）。
     *
     * @param onlyPending 只看尚有未包埋蜡块的批次
     */
    @GetMapping("/dehydrate/batches")
    public R<Map<String, Object>> dehydrateBatches(@RequestParam(required = false) String batchNo,
                                                   @RequestParam(required = false) Boolean onlyPending,
                                                   @RequestParam(required = false) Integer limit) {
        int cap = capOf(limit);
        var sql = new StringBuilder("""
                select b.dehydrate_batch               as batch_no,
                       count(*)                        as block_count,
                       count(distinct b.specimen_id)   as specimen_count,
                       count(b.embedded_at)            as embedded_count,
                       count(*) - count(b.embedded_at) as pending_count,
                       min(b.created_at)               as first_block_created_at,
                       max(b.created_at)               as last_block_created_at,
                       max(b.embedded_at)              as last_embedded_at
                from path_block b
                where b.dehydrate_batch is not null
                """);
        var args = new ArrayList<Object>();
        String want = trim(batchNo);
        if (want != null) {
            sql.append(" and b.dehydrate_batch = ? ");
            args.add(want);
        }
        sql.append(" group by b.dehydrate_batch ");
        if (Boolean.TRUE.equals(onlyPending)) {
            sql.append(" having count(*) > count(b.embedded_at) ");
        }
        sql.append(" order by max(b.created_at) desc limit ? ");
        args.add(cap + 1);

        var rows = jdbc.queryForList(sql.toString(), args.toArray());
        var items = new ArrayList<Map<String, Object>>(rows.size());
        for (var r : rows) {
            var m = new LinkedHashMap<String, Object>(r);
            long total = asLong(r.get("block_count"));
            long done = asLong(r.get("embedded_count"));
            m.put("progress", done == 0 ? "PENDING" : done < total ? "PARTIAL" : "EMBEDDED");
            items.add(m);
        }
        var body = page(items, cap);
        body.put("note", "progress 由已包埋蜡块数派生：PENDING（一块未包埋）/ PARTIAL / EMBEDDED。"
                + "firstBlockCreatedAt/lastBlockCreatedAt 是建块（取材）时间，不是分篮时刻——"
                + "path_block 无 batch_assigned_at 列，本版不加列。"
                + "脱水篮是逻辑分组，平台不与脱水机通讯。");
        return R.ok(body);
    }

    // ==================================================================
    // 三、包埋
    // ==================================================================

    /**
     * 包埋确认：落 {@code embedded_at}/{@code embedded_by} 与 EMBED 流转节点。
     *
     * <p>update 带 {@code and embedded_at is null} 作乐观并发闸：两人同时点确认，
     * 只有一个人的名字进得去，另一个人拿到 5241，而不是把先到者的署名悄悄改掉。
     *
     * <p>EMBED 节点的 {@code occurred_at} <b>直接 select 回刚写入的 {@code embedded_at}</b>，
     * 不在 Java 里另取一次时间——两处各取一次 now() 在跨事务边界时会分叉。
     *
     * <p><b>不强制先分脱水篮</b>：分篮是可选的逻辑分组，强制它会让不做批次管理的科室卡死。
     * 未分篮直接包埋只在返回体回一条 warning。
     */
    @PutMapping("/blocks/{id}/embed")
    @Transactional
    public R<Map<String, Object>> embed(@PathVariable Long id,
                                        @RequestParam(required = false) String remark,
                                        Authentication auth) {
        String rk = trim(remark);
        if (rk != null && rk.length() > REMARK_MAX) {
            return R.fail(5243, "备注超长（上限 " + REMARK_MAX + " 字）");
        }

        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5248, "无法识别当前登录用户，不能登记包埋");

        var updated = jdbc.queryForList("""
                update path_block set embedded_at = now(), embedded_by = ?
                where id = ? and embedded_at is null
                returning id, specimen_id, block_no, block_code, dehydrate_batch, embedded_at
                """, uid, id);
        if (updated.isEmpty()) {
            var exists = one("select id, block_code, embedded_at from path_block where id = ?", id);
            if (exists == null) return R.fail(5240, "蜡块不存在：" + id);
            return R.fail(5241, "蜡块 " + exists.get("block_code") + " 已于 "
                    + exists.get("embedded_at") + " 包埋，不重复登记");
        }
        var row = updated.get(0);

        // 节点时间直接取刚写入的 embedded_at，两处不各取一次 now()
        jdbc.update("""
                insert into path_process(specimen_id, node, occurred_at, operator_id, remark)
                select b.specimen_id, 'EMBED', b.embedded_at, ?::bigint, ?::varchar
                from path_block b where b.id = ?
                """, uid, clip("包埋 " + row.get("block_code") + (rk == null ? "" : "：" + rk), REMARK_MAX), id);

        var warnings = new ArrayList<String>();
        if (row.get("dehydrate_batch") == null) {
            warnings.add("该蜡块未归入任何脱水批次（分篮是可选的逻辑分组，本端点不强制）");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("blockId", row.get("id"));
        body.put("specimenId", row.get("specimen_id"));
        body.put("blockNo", row.get("block_no"));
        body.put("blockCode", row.get("block_code"));
        body.put("dehydrateBatch", row.get("dehydrate_batch"));
        body.put("embeddedAt", row.get("embedded_at"));
        body.put("embeddedBy", uid);
        body.put("warnings", warnings);
        return R.ok(body);
    }

    /**
     * 蜡块检索：按标本、脱水批次、包埋状态查，供包埋台与切片台取工作清单。
     *
     * @param embedded true 只看已包埋 / false 只看未包埋 / 不传 全部
     */
    @GetMapping("/blocks")
    public R<Map<String, Object>> blocks(@RequestParam(required = false) Long specimenId,
                                         @RequestParam(required = false) String dehydrateBatch,
                                         @RequestParam(required = false) Boolean embedded,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Integer limit) {
        int cap = capOf(limit);
        var sql = new StringBuilder("""
                select b.id, b.specimen_id, b.block_no, b.block_code, b.tissue_desc,
                       b.dehydrate_batch, b.embedded_at, b.embedded_by, b.created_at, b.created_by,
                       s.barcode, s.path_no, s.part_no, s.specimen_type, s.urgent, s.sampling_site,
                       (select count(*) from path_slide sl where sl.block_id = b.id) as slide_count,
                       (select count(*) from path_slide sl
                         where sl.block_id = b.id and sl.stained_at is not null) as stained_slide_count
                from path_block b
                join path_specimen s on s.id = b.specimen_id
                where 1 = 1
                """);
        var args = new ArrayList<Object>();
        if (specimenId != null) {
            sql.append(" and b.specimen_id = ? ");
            args.add(specimenId);
        }
        String batch = trim(dehydrateBatch);
        if (batch != null) {
            sql.append(" and b.dehydrate_batch = ? ");
            args.add(batch);
        }
        if (embedded != null) {
            sql.append(embedded ? " and b.embedded_at is not null " : " and b.embedded_at is null ");
        }
        String kw = trim(keyword);
        if (kw != null) {
            sql.append(" and (b.block_code ilike ? or s.barcode ilike ? or s.path_no ilike ?) ");
            String like = "%" + kw + "%";
            for (int i = 0; i < 3; i++) args.add(like);
        }
        sql.append(" order by b.specimen_id desc, b.block_no asc limit ? ");
        args.add(cap + 1);

        return R.ok(page(jdbc.queryForList(sql.toString(), args.toArray()), cap));
    }

    // ==================================================================
    // 四、切片与染色
    // ==================================================================

    public record SlideReq(Long blockId, Integer count, String stainType, String stainItem, String remark) {}

    /**
     * 切片：从一个蜡块产出 N 张切片，写 SECTION 流转节点。
     *
     * <p>{@code slide_no} 是<b>蜡块内序号</b>，从既有最大号 +1 续排（深切、重切追加的片接着排）；
     * {@code slide_code} =「蜡块编码-片号」=「病理号-块号-片号」。{@code stain_type} 默认 HE——
     * 绝大多数切片就是 HE，这个默认值省掉的是最高频的一次输入。
     *
     * <p>序号与编码同样由<b>单条 insert 内的子查询</b>算出（配合 {@code generate_series} 一次插 N 张），
     * 并发撞号由 {@code uq_path_slide_no} 兜底返 4090。
     *
     * <p><b>未包埋只告警不拦截</b>：物理上切片当然在包埋之后，但存量蜡块没有包埋记录
     * （零回填：当时确实没采集），硬拦会让这些蜡块永远切不了片。
     */
    @PostMapping("/slides")
    @Transactional
    public R<Map<String, Object>> slides(@RequestBody SlideReq req, Authentication auth) {
        if (req == null || req.blockId() == null) return R.fail(5243, "蜡块 id 不能为空");
        int count = req.count() == null ? 1 : req.count();
        if (count < 1 || count > MAX_SLIDES_PER_SECTION) {
            return R.fail(5243, "切片份数须在 1–" + MAX_SLIDES_PER_SECTION + " 之间，收到 " + count);
        }
        String stainType = req.stainType() == null ? "HE" : trimUpper(req.stainType());
        if (stainType == null) stainType = "HE";
        if (!STAIN_TYPES.contains(stainType)) {
            return R.fail(5243, "染色类型非法：" + req.stainType() + "，可选 " + STAIN_TYPES);
        }
        String stainItem = trim(req.stainItem());
        if (stainItem != null && stainItem.length() > STAIN_ITEM_MAX) {
            return R.fail(5243, "染色项目超长（上限 " + STAIN_ITEM_MAX + " 字）");
        }
        String rk = trim(req.remark());
        if (rk != null && rk.length() > REMARK_MAX) {
            return R.fail(5243, "备注超长（上限 " + REMARK_MAX + " 字）");
        }

        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5248, "无法识别当前登录用户，不能登记切片");

        var block = one("""
                select b.id, b.specimen_id, b.block_no, b.block_code, b.embedded_at,
                       (select coalesce(max(sl.slide_no), 0) from path_slide sl
                         where sl.block_id = b.id) as max_slide_no
                from path_block b where b.id = ?
                """, req.blockId());
        if (block == null) return R.fail(5240, "蜡块不存在：" + req.blockId());
        long maxNo = asLong(block.get("max_slide_no"));
        if (maxNo + count > MAX_SLIDE_NO) {
            return R.fail(5243, "切片序号将超过上限 " + MAX_SLIDE_NO + "（当前最大 " + maxNo + "）");
        }

        var created = jdbc.queryForList("""
                insert into path_slide(block_id, slide_no, slide_code, stain_type, stain_item)
                select ?::bigint, ns.n + g.g, ?::text || '-' || (ns.n + g.g)::text, ?::varchar, ?::varchar
                from (select coalesce(max(slide_no), 0) as n from path_slide where block_id = ?) ns,
                     generate_series(1, ?::int) g(g)
                returning id, slide_no, slide_code, stain_type, stain_item, created_at
                """, req.blockId(), block.get("block_code"), stainType, stainItem, req.blockId(), count);

        Long specimenId = asLongObj(block.get("specimen_id"));
        // now() 是事务开始时刻且事务内恒定：SECTION 节点时间与各切片 created_at 逐位相等
        logProcess(specimenId, "SECTION", uid,
                "切片 " + created.size() + " 张（" + block.get("block_code") + "，" + stainType
                        + (stainItem == null ? "" : " " + stainItem) + "）"
                        + (rk == null ? "" : "：" + rk));

        var warnings = new ArrayList<String>();
        if (block.get("embedded_at") == null) {
            warnings.add("该蜡块没有包埋记录（存量蜡块或漏登记），本端点只告警不拦截");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("blockId", req.blockId());
        body.put("specimenId", specimenId);
        body.put("blockCode", block.get("block_code"));
        body.put("stainType", stainType);
        body.put("slides", created);
        body.put("slideCount", created.size());
        body.put("warnings", warnings);
        body.put("note", "slide_code 由「蜡块编码-片号」生成；玻片打码机属设备直连，"
                + "平台只给编码字符串，不负责打印。新建切片尚未染色（stainedAt 为空），"
                + "染色请走 PUT /slides/{id}/stain 或 PUT /slides/batch-complete。");
        return R.ok(body);
    }

    public record StainReq(String quality, String stainItem, String remark) {}

    /**
     * 染色登记（单张）：落 {@code stained_at}/{@code stained_by}/{@code quality} 与 STAIN 节点。
     *
     * <p>{@code quality}（GOOD/FAIR/POOR）是<b>染色切片优良率</b>的唯一数据源。不传就留空——
     * 留空表示「没评」，比默认填 GOOD 诚实（默认 GOOD 会让优良率恒等于 100%，同 V144
     * 「宁可少算，不可假算」）。
     *
     * <p>{@code stain_item} 用 {@code coalesce(?, stain_item)}：不传就保留切片时登记的项目，
     * <b>不清空</b>。update 带 {@code and stained_at is null} 作乐观并发闸，重复登记返 5245。
     */
    @PutMapping("/slides/{id}/stain")
    @Transactional
    public R<Map<String, Object>> stain(@PathVariable Long id,
                                        @RequestBody(required = false) StainReq req,
                                        Authentication auth) {
        String quality = req == null ? null : trimUpper(req.quality());
        if (quality != null && !SLIDE_QUALITIES.contains(quality)) {
            return R.fail(5246, "染色质量非法：" + req.quality() + "，可选 " + SLIDE_QUALITIES);
        }
        String stainItem = req == null ? null : trim(req.stainItem());
        if (stainItem != null && stainItem.length() > STAIN_ITEM_MAX) {
            return R.fail(5243, "染色项目超长（上限 " + STAIN_ITEM_MAX + " 字）");
        }
        String rk = req == null ? null : trim(req.remark());
        if (rk != null && rk.length() > REMARK_MAX) {
            return R.fail(5243, "备注超长（上限 " + REMARK_MAX + " 字）");
        }

        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5248, "无法识别当前登录用户，不能登记染色");

        var updated = jdbc.queryForList("""
                update path_slide
                   set stained_at = now(), stained_by = ?,
                       quality    = coalesce(?, quality),
                       stain_item = coalesce(?, stain_item)
                where id = ? and stained_at is null
                returning id, block_id, slide_no, slide_code, stain_type, stain_item, quality, stained_at
                """, uid, quality, stainItem, id);
        if (updated.isEmpty()) {
            var exists = one("select id, slide_code, stained_at from path_slide where id = ?", id);
            if (exists == null) return R.fail(5244, "切片不存在：" + id);
            return R.fail(5245, "切片 " + exists.get("slide_code") + " 已于 "
                    + exists.get("stained_at") + " 完成染色，不重复登记");
        }
        var row = updated.get(0);

        // 节点时间直接取刚写入的 stained_at
        jdbc.update("""
                insert into path_process(specimen_id, node, occurred_at, operator_id, remark)
                select b.specimen_id, 'STAIN', sl.stained_at, ?::bigint, ?::varchar
                from path_slide sl join path_block b on b.id = sl.block_id
                where sl.id = ?
                """, uid,
                clip("染色 " + row.get("slide_code") + "（" + row.get("stain_type")
                        + (row.get("stain_item") == null ? "" : " " + row.get("stain_item")) + "）"
                        + (quality == null ? "" : "，质量 " + quality)
                        + (rk == null ? "" : "：" + rk), REMARK_MAX),
                id);

        var body = new LinkedHashMap<String, Object>(row);
        body.put("stainedBy", uid);
        if (quality == null) {
            body.put("warnings", List.of("未评定切片质量（quality 留空）："
                    + "留空计入「未评」而不是优良，染色切片优良率的分母口径由病理质控端点定义"));
        }
        return R.ok(body);
    }

    public record BatchCompleteReq(List<Long> slideIds, String quality, String stainItem, String remark) {}

    /**
     * 批量核销：一次标记多张切片完成。
     *
     * <p><b>「完成」落到哪个字段，说清楚</b>：{@code path_slide} 只有 {@code stained_at}
     * 一个完成时间戳，没有独立的「制片完成」列（地基已定，本版不加列）。所以批量核销
     * <b>核销的就是染色完成</b>，与 {@code PUT /slides/{id}/stain} 是同一动作的批量版，
     * 不另造一个只活在返回体里的「已制片」状态——那种状态重启就没了，是假实现。
     *
     * <p><b>全批校验通过才写，否则整批拒绝</b>（切片不存在 5244 / 已染色 5245，均连同问题条目返回）：
     * 批量核销最怕「点了 30 张、实际进了 27 张」，剩下 3 张永远漏在流程外。
     *
     * <p>一次 update 写全部；STAIN 节点按标本去重各记一条，节点时间取本批
     * {@code max(stained_at)}——同一事务内 {@code now()} 恒定，该值就是本批的染色时刻。
     */
    @PutMapping("/slides/batch-complete")
    @Transactional
    public R<Map<String, Object>> batchComplete(@RequestBody BatchCompleteReq req, Authentication auth) {
        var ids = distinctIds(req == null ? null : req.slideIds());
        if (ids.isEmpty()) return R.fail(5247, "切片清单不能为空");
        if (ids.size() > MAX_BATCH_SIZE) {
            return R.fail(5247, "一次最多核销 " + MAX_BATCH_SIZE + " 张切片，收到 " + ids.size());
        }
        String quality = req == null ? null : trimUpper(req.quality());
        if (quality != null && !SLIDE_QUALITIES.contains(quality)) {
            return R.fail(5246, "染色质量非法：" + req.quality() + "，可选 " + SLIDE_QUALITIES);
        }
        String stainItem = req == null ? null : trim(req.stainItem());
        if (stainItem != null && stainItem.length() > STAIN_ITEM_MAX) {
            return R.fail(5247, "染色项目超长（上限 " + STAIN_ITEM_MAX + " 字）");
        }
        String rk = req == null ? null : trim(req.remark());
        if (rk != null && rk.length() > REMARK_MAX) {
            return R.fail(5247, "备注超长（上限 " + REMARK_MAX + " 字）");
        }

        Long uid = currentUserService.idOf(auth);
        if (uid == null) return R.fail(5248, "无法识别当前登录用户，不能核销切片");

        String in = placeholders(ids.size());
        var rows = jdbc.queryForList("""
                select sl.id, sl.slide_code, sl.stained_at, b.specimen_id
                from path_slide sl join path_block b on b.id = sl.block_id
                where sl.id in (%s)
                """.formatted(in), ids.toArray());

        var found = new LinkedHashSet<Long>();
        var already = new ArrayList<Object>();
        for (var r : rows) {
            found.add(asLong(r.get("id")));
            if (r.get("stained_at") != null) already.add(r.get("slide_code"));
        }
        var missing = ids.stream().filter(i -> !found.contains(i)).toList();
        if (!missing.isEmpty()) return R.fail(5244, "切片不存在：" + missing);
        if (!already.isEmpty()) {
            return R.fail(5245, "以下切片已完成染色，整批不予核销（请剔除后重试）：" + already);
        }

        var updArgs = new ArrayList<Object>();
        updArgs.add(uid);
        updArgs.add(quality);
        updArgs.add(stainItem);
        updArgs.addAll(ids);
        int n = jdbc.update("""
                update path_slide
                   set stained_at = now(), stained_by = ?,
                       quality    = coalesce(?, quality),
                       stain_item = coalesce(?, stain_item)
                where id in (%s) and stained_at is null
                """.formatted(in), updArgs.toArray());

        // STAIN 节点按标本去重；occurred_at 取本批 max(stained_at)（事务内 now() 恒定，即本批染色时刻）
        var specimenIds = rows.stream().map(r -> asLong(r.get("specimen_id"))).distinct().toList();
        for (Long sid : specimenIds) {
            long cnt = rows.stream().filter(r -> asLong(r.get("specimen_id")) == sid.longValue()).count();
            var nodeArgs = new ArrayList<Object>();
            nodeArgs.add(sid);
            nodeArgs.add(uid);
            nodeArgs.add(clip("批量核销染色 " + cnt + " 张"
                    + (quality == null ? "" : "，质量 " + quality)
                    + (rk == null ? "" : "：" + rk), REMARK_MAX));
            nodeArgs.add(sid);
            nodeArgs.addAll(ids);
            jdbc.update("""
                    insert into path_process(specimen_id, node, occurred_at, operator_id, remark)
                    select ?::bigint, 'STAIN', max(sl.stained_at), ?::bigint, ?::varchar
                    from path_slide sl join path_block b on b.id = sl.block_id
                    where b.specimen_id = ? and sl.id in (%s)
                    """.formatted(in), nodeArgs.toArray());
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("requested", ids.size());
        body.put("completed", n);
        body.put("specimenCount", specimenIds.size());
        body.put("quality", quality);
        body.put("note", "核销落在 stained_at（染色完成）——path_slide 没有独立的「制片完成」列，"
                + "本版不加列，也不另造一个不落库的状态。"
                + (quality == null ? "本批未评定切片质量（quality 留空计入「未评」）。" : ""));
        return R.ok(body);
    }

    /**
     * 切片检索：按标本、蜡块、染色类型、质量、染色状态、日期区间查。
     *
     * <p>{@code dateField} 白名单二选一，<b>不做「日期落在染色时间或建片时间任一」的模糊口径</b>——
     * 那种口径统计出来的数没人能解释：
     * <ul>
     *   <li>{@code CREATED}（默认）：按切片产出时间 {@code created_at}；</li>
     *   <li>{@code STAINED}：按染色完成时间 {@code stained_at}，未染色的切片自然不在结果里。</li>
     * </ul>
     * 区间是闭开 [from, to+1)，跨度上限 {@value #MAX_SPAN_DAYS} 天。
     *
     * @param stained true 只看已染色 / false 只看未染色 / 不传 全部
     */
    @GetMapping("/slides/search")
    public R<Map<String, Object>> slideSearch(@RequestParam(required = false) Long specimenId,
                                              @RequestParam(required = false) Long blockId,
                                              @RequestParam(required = false) String stainType,
                                              @RequestParam(required = false) String quality,
                                              @RequestParam(required = false) Boolean stained,
                                              @RequestParam(required = false) String dateField,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer limit) {
        int cap = capOf(limit);

        String type = trimUpper(stainType);
        if (type != null && !STAIN_TYPES.contains(type)) {
            return R.fail(5249, "染色类型非法：" + stainType + "，可选 " + STAIN_TYPES);
        }
        String q = trimUpper(quality);
        if (q != null && !SLIDE_QUALITIES.contains(q)) {
            return R.fail(5249, "染色质量非法：" + quality + "，可选 " + SLIDE_QUALITIES);
        }
        String df = trimUpper(dateField);
        if (df == null) df = "CREATED";
        if (!df.equals("CREATED") && !df.equals("STAINED")) {
            return R.fail(5249, "dateField 非法：" + dateField + "，可选 CREATED / STAINED");
        }
        String f = trim(from);
        String t = trim(to);
        if ((f == null) != (t == null)) return R.fail(5249, "日期区间 from 与 to 必须同时给出");
        if (f != null) {
            LocalDate fd;
            LocalDate td;
            try {
                fd = LocalDate.parse(f);
                td = LocalDate.parse(t);
            } catch (DateTimeParseException e) {
                return R.fail(5249, "日期格式须为 yyyy-MM-dd：" + f + " / " + t);
            }
            if (td.isBefore(fd)) return R.fail(5249, "日期区间倒置：" + f + " 晚于 " + t);
            if (fd.plusDays(MAX_SPAN_DAYS).isBefore(td)) {
                return R.fail(5249, "日期跨度超过 " + MAX_SPAN_DAYS + " 天");
            }
        }

        var sql = new StringBuilder("""
                select sl.id, sl.block_id, sl.slide_no, sl.slide_code, sl.stain_type, sl.stain_item,
                       sl.stained_at, sl.stained_by, sl.quality, sl.created_at,
                       b.specimen_id, b.block_no, b.block_code, b.dehydrate_batch, b.embedded_at,
                       s.barcode, s.path_no, s.part_no, s.specimen_type, s.urgent, s.sampling_site,
                       p.patient_no, p.name as patient_name
                from path_slide sl
                join path_block b on b.id = sl.block_id
                join path_specimen s on s.id = b.specimen_id
                left join outp_order oo on oo.id = s.order_id
                left join outp_registration r on r.id = oo.registration_id
                left join inp_order io on io.id = s.inp_order_id
                left join inp_admission a on a.id = io.admission_id
                left join empi_patient p on p.id = coalesce(r.patient_id, a.patient_id)
                where 1 = 1
                """);
        var args = new ArrayList<Object>();
        if (specimenId != null) {
            sql.append(" and b.specimen_id = ? ");
            args.add(specimenId);
        }
        if (blockId != null) {
            sql.append(" and sl.block_id = ? ");
            args.add(blockId);
        }
        if (type != null) {
            sql.append(" and sl.stain_type = ? ");
            args.add(type);
        }
        if (q != null) {
            sql.append(" and sl.quality = ? ");
            args.add(q);
        }
        if (stained != null) {
            sql.append(stained ? " and sl.stained_at is not null " : " and sl.stained_at is null ");
        }
        if (f != null) {
            // 片段由白名单二选一，是编译期常量；日期值走 ?
            sql.append("STAINED".equals(df)
                    ? " and sl.stained_at >= ?::date and sl.stained_at < ?::date + 1 "
                    : " and sl.created_at >= ?::date and sl.created_at < ?::date + 1 ");
            args.add(f);
            args.add(t);
        }
        String kw = trim(keyword);
        if (kw != null) {
            sql.append(" and (sl.slide_code ilike ? or b.block_code ilike ? or s.barcode ilike ? "
                    + "or s.path_no ilike ? or p.name ilike ? or p.patient_no ilike ?) ");
            String like = "%" + kw + "%";
            for (int i = 0; i < 6; i++) args.add(like);
        }
        sql.append(" order by sl.id desc limit ? ");
        args.add(cap + 1);

        var body = page(jdbc.queryForList(sql.toString(), args.toArray()), cap);
        body.put("dateField", df);
        body.put("note", "dateField=CREATED 按切片产出时间、STAINED 按染色完成时间"
                + "（未染色切片不出现在 STAINED 结果里）；quality 为空表示尚未评定，不等于优良。");
        return R.ok(body);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 取材工作列表的公共投影：双来源（门诊 / 住院）一律 left join 后 coalesce 到同一组列 */
    private static final String SPECIMEN_SELECT = """
            select s.id, s.barcode, s.path_no, s.part_no, s.specimen_type, s.sampling_site,
                   s.clinical_diagnosis, s.specimen_desc, s.fixative, s.fixed_at, s.urgent, s.status,
                   s.collected_at, s.received_at, s.diagnosed_at,
                   case when s.order_id is not null then 'OUTP' else 'INP' end as source,
                   s.order_id, s.inp_order_id,
                   coalesce(oo.item_name, io.item_name) as item_name,
                   p.id as patient_id, p.patient_no, p.name as patient_name, p.sex, p.birth_date,
                   (select count(*) from path_block b where b.specimen_id = s.id) as block_count,
                   (select count(*) from path_slide sl join path_block b on b.id = sl.block_id
                     where b.specimen_id = s.id) as slide_count,
                   round((extract(epoch from (now() - s.received_at)) / 3600.0)::numeric, 1)
                       as hours_since_received
            from path_specimen s
            left join outp_order oo on oo.id = s.order_id
            left join outp_registration r on r.id = oo.registration_id
            left join inp_order io on io.id = s.inp_order_id
            left join inp_admission a on a.id = io.admission_id
            left join empi_patient p on p.id = coalesce(r.patient_id, a.patient_id)
            """;

    /** 内置取材模板；fields 可被 sys_config 覆盖，example 恒为硬编码常量（见 grossingTemplates） */
    private record Template(String code, String name, List<String> fields, String example) {}

    private static final List<Template> BUILTIN_TEMPLATES = List.of(
            new Template("GI_BIOPSY", "胃肠镜活检",
                    List.of("送检组织", "块数", "最大径", "色泽", "质地", "取材方式"),
                    "送检灰白色破碎组织 3 块，最大径 0.3cm，质软，全部取材。"),
            new Template("BREAST_MASS", "乳腺肿物",
                    List.of("标本类型", "标本大小", "肿物大小", "肿物切面", "边界", "距最近切缘", "取材块数"),
                    "送检乳腺组织一块，大小 5×4×3cm，其内见灰白质硬肿物一枚，大小 2×1.5×1.5cm，"
                            + "切面灰白，界欠清，距最近切缘 0.8cm，取材 6 块。"),
            new Template("LYMPH_NODE", "淋巴结",
                    List.of("送检部位", "淋巴结枚数", "最大径", "切面", "取材块数"),
                    "送检脂肪组织内检出淋巴结 8 枚，最大径 1.2cm，切面灰白灰红，分别取材 4 块。"),
            new Template("SKIN", "皮肤及软组织",
                    List.of("标本大小", "病变部位", "病变大小", "病变外观", "距最近切缘", "取材块数"),
                    "送检皮肤组织一块，大小 2×1×0.5cm，表面见灰褐色斑块一处，大小 0.8×0.6cm，"
                            + "距最近切缘 0.3cm，垂直皮面取材 3 块。"),
            new Template("UTERUS_CERVIX", "子宫及宫颈",
                    List.of("标本类型", "子宫大小", "宫颈长度", "内膜厚度", "肌壁病变", "取材部位", "取材块数"),
                    "送检全子宫一个，大小 8×6×4cm，宫颈长 2.5cm，内膜厚 0.3cm，"
                            + "肌壁内见结节数枚，最大径 3cm，切面灰白编织状，取材 8 块。"),
            new Template("LUNG", "肺",
                    List.of("标本类型", "肺组织大小", "肿物大小", "距支气管断端", "胸膜情况", "取材块数"),
                    "送检肺叶切除标本，大小 12×8×5cm，距支气管断端 2cm 见灰白肿物，大小 3×2.5cm，"
                            + "累及脏层胸膜，取材 10 块。"),
            new Template("FROZEN", "术中冰冻",
                    List.of("送检部位", "标本大小", "肉眼所见", "取材块数"),
                    "术中送检左侧甲状腺结节一枚，大小 1.5×1.2×1cm，切面灰白实性，取材 1 块行冰冻。"),
            new Template("CYTOLOGY", "细胞学",
                    List.of("标本来源", "标本量", "性状", "制片方式", "制片张数"),
                    "送检胸水 200ml，淡黄色微浑，离心后制片 2 张。"),
            new Template("GENERIC", "通用",
                    List.of("送检组织", "标本大小", "色泽", "质地", "切面", "取材块数"),
                    "送检组织一块，大小 ××cm，色泽 ××，质 ××，切面 ××，取材 × 块。"));

    /**
     * 模板目录：内置常量为底，{@code sys_config} 的 {@code path.grossing.template.*} 覆盖字段清单，
     * 也可新增内置里没有的模板代码（此时 example 为 null——255 字符的配置放不下示例文本，
     * 与其编一段假的不如留空）。
     */
    private Map<String, Map<String, Object>> templateCatalog() {
        var overrides = new LinkedHashMap<String, List<String>>();
        var cfgRows = jdbc.queryForList(
                "select cfg_key, cfg_value from sys_config where cfg_key like ? order by cfg_key",
                TEMPLATE_KEY_PREFIX + "%");
        for (var r : cfgRows) {
            String key = (String) r.get("cfg_key");
            String val = trim((String) r.get("cfg_value"));
            if (key == null || val == null || key.length() <= TEMPLATE_KEY_PREFIX.length()) continue;
            String code = key.substring(TEMPLATE_KEY_PREFIX.length()).toUpperCase(Locale.ROOT);
            var fields = new ArrayList<String>();
            for (String part : val.split("[,，]")) {
                String s = trim(part);
                if (s != null) fields.add(s);
            }
            if (!fields.isEmpty()) overrides.put(code, fields);
        }

        var out = new LinkedHashMap<String, Map<String, Object>>();
        for (Template t : BUILTIN_TEMPLATES) {
            var fields = overrides.remove(t.code());
            var m = new LinkedHashMap<String, Object>();
            m.put("code", t.code());
            m.put("name", t.name());
            m.put("fields", fields == null ? t.fields() : fields);
            m.put("example", t.example());
            m.put("source", fields == null ? "BUILTIN" : "CONFIG");
            out.put(t.code(), m);
        }
        for (var e : overrides.entrySet()) {   // 配置新增的模板代码
            var m = new LinkedHashMap<String, Object>();
            m.put("code", e.getKey());
            m.put("name", e.getKey());
            m.put("fields", e.getValue());
            m.put("example", null);            // 配置放不下示例文本，如实留空
            m.put("source", "CONFIG");
            out.put(e.getKey(), m);
        }
        return out;
    }

    /**
     * 结构化大体描述拼装：有序字段「标签：值」以「；」连接，末尾接自由文本。
     * 全空返回 null（本次不写大体所见）；越界抛 {@link IllegalArgumentException}，由调用方转 5222。
     */
    private static String assembleGross(Map<String, String> gross, String freeText) {
        var parts = new ArrayList<String>();
        if (gross != null && !gross.isEmpty()) {
            if (gross.size() > GROSS_FIELD_MAX) {
                throw new IllegalArgumentException("大体描述字段最多 " + GROSS_FIELD_MAX
                        + " 项，收到 " + gross.size());
            }
            for (var e : gross.entrySet()) {
                String label = trim(e.getKey());
                String value = trim(e.getValue());
                if (label == null) throw new IllegalArgumentException("大体描述的字段名不能为空");
                if (label.length() > GROSS_LABEL_MAX) {
                    throw new IllegalArgumentException("大体描述字段名超长（上限 "
                            + GROSS_LABEL_MAX + " 字）：" + label);
                }
                if (value == null) continue;   // 值为空的字段整条略去，不写出「大小：」这种半截话
                if (value.length() > GROSS_VALUE_MAX) {
                    throw new IllegalArgumentException("大体描述「" + label + "」的内容超长（上限 "
                            + GROSS_VALUE_MAX + " 字）");
                }
                parts.add(label + "：" + value);
            }
        }
        String assembled = String.join("；", parts);
        if (freeText != null) assembled = assembled.isEmpty() ? freeText : assembled + "。" + freeText;
        if (assembled.isEmpty()) return null;
        if (assembled.length() > GROSS_MAX) {
            throw new IllegalArgumentException("大体描述总长超过 " + GROSS_MAX + " 字（当前 "
                    + assembled.length() + "），请精简或改写在各蜡块的组织描述里");
        }
        return assembled;
    }

    /** 流转节点（occurred_at = now()：PostgreSQL 里是事务开始时刻，事务内恒定） */
    private void logProcess(Long specimenId, String node, Long operatorId, String remark) {
        jdbc.update("""
                insert into path_process(specimen_id, node, occurred_at, operator_id, remark)
                values (?, ?, now(), ?, ?)
                """, specimenId, node, operatorId, clip(remark, REMARK_MAX));
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 限量 + truncated 标记，不做翻页也不静默截断 */
    private static LinkedHashMap<String, Object> page(List<Map<String, Object>> rows, int cap) {
        boolean truncated = rows.size() > cap;
        var body = new LinkedHashMap<String, Object>();
        body.put("limit", cap);
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        return body;
    }

    private static int capOf(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    /** 只生成占位符个数，值一律走参数——不是把用户输入拼进 SQL */
    private static String placeholders(int n) {
        return "?" + ",?".repeat(Math.max(0, n - 1));
    }

    /** 去重保序的 id 清单，null 元素丢弃 */
    private static List<Long> distinctIds(List<Long> raw) {
        if (raw == null) return List.of();
        var set = new LinkedHashSet<Long>();
        for (Long v : raw) if (v != null) set.add(v);
        return new ArrayList<>(set);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimUpper(String s) {
        String t = trim(s);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static Long asLongObj(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }
}
