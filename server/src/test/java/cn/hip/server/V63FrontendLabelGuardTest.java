package cn.hip.server;

import cn.hip.medtech.web.PathQcController;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyProcessController.SlideReq;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <h1>v63 车道 B（2530 / 2563 / 2576 复核）：屏上说假话的标签、重名的表头、假出来的百分比</h1>
 *
 * <h2>修复前的反向事实（v62 地基 ff45ae8，复核者原话经主控实测坐实）</h2>
 * <ol>
 *   <li><b>2563（表头重名 + 标着「编码」的列显示中文名）</b>：前端 {@code format.ts} 的 ZH 是一张<b>扁平字典</b>，
 *       表头走 {@code zh(col)}、格子走 {@code cellText(col, v, row)}，两者各自决定。于是
 *       {@code attached_stain}/{@code attached_stain_name} 两列表头逐字相同（「挂接切片染色」），
 *       {@code specimen_type}/{@code type_name} 同样同名，而 {@code tech_type}（表头「技术类型编码」）、
 *       {@code progress} 的格子里显示的是 cellText 翻出来的<b>中文名</b>——标着「编码」的列不含一个编码。
 *       v62 只改了后端 CSV 字典（{@code PathQcController.zh()}），渲染屏上表头的这张 ZH 一个字没动；
 *       真正拦住它的是仓库自己的一条断言 {@code V60DeptDimensionTest:306}「编码版与中文版同一个表头叫法」
 *       ——把缺陷锁成了契约，本轮已<b>翻过来</b>（见该类同名方法的整段注释）。</li>
 *   <li><b>2576（假百分比 + 同屏两个「蜡块数」）</b>：<b>先于所有指标渲染</b>的「本时段字段录入覆盖率」段里，
 *       {@code blocks} 一个键服务两段语义不同的列（蜡块段 {@code count(*)} 产出数、切片段
 *       {@code count(distinct sl.block_id)} 涉及数），经扁平字典渲染成同一屏上两个一模一样的中文表头；
 *       而分档逻辑只分「是不是本段分母」，<b>不是分母就一律套 ratio()</b>——切片段的 {@code blocks}
 *       （单位是蜡块、分母单位是切片）被打成「3 / 8（37.5%）」，一个 {@code not null} 外键的去重计数
 *       就此变成一条「37.5% 的覆盖率」。蜡块段的 {@code specimens} 同型。</li>
 *   <li><b>2530（同屏两句互相矛盾的话）</b>：取材查看弹窗的版本标签只看单键 {@code textIsCumulative}
 *       就断言「本版字段只覆盖最后一次补取材」——而最后那次补取材<b>可能一行结构化字段都没写</b>
 *       （该版 path_gross_field 零行，字段级记录停在更早那一版），这句话与库内事实相反，
 *       且被它正下方那条后端 {@code fieldsNote}「第 2 版补取材追加未填写字段」逐字打脸；
 *       同一份返回体在轨迹抽屉里却按后端文案渲染——<b>同一事实两个入口两套说法</b>。
 *       {@code superseded} 判定又压根不看 {@code textIsCumulative}：累积全文场景里更早各版写下的内容
 *       原封不动留在当前 {@code gross_finding} 里，却被标成「已被第 2 版字段取代，仅供调阅」。
 *       后端那句话还带着裸 Markdown {@code **累积全文**}，两处都原样打在屏幕上。</li>
 * </ol>
 *
 * <h2>本类怎么钉（不是「只断返回体」）</h2>
 * 前端渲染跑不进 JUnit，但<b>渲染用的那几张表和那几条分档规则可以从源码解析出来，在 Java 里按相同语义回放</b>
 * （照抄 {@code V60DeptDimensionTest} 回放 {@code mdText} 三条正则的办法）：本类把
 * {@code format.ts} 的 {@code ZH} / {@code CODE_COLUMNS} 与 {@code PathQcView.vue} 的
 * {@code COVERAGE_SECTIONS}（denom / rates / labels）逐条解析出来，再拿<b>后端此刻真返回的行与覆盖率段</b>
 * 喂进去，断言「这一屏上不会出现两个同名表头」「标着编码的列给的是编码」「不是本段分母子集的列不套百分比」。
 * 每条都带<b>活对照组</b>（解析器真解析出东西、修复前的写法在同一份数据上必然翻车）与<b>探针</b>（喂一份坏字典必红）。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V63FrontendLabelGuardTest {

    private static final String FORMAT_TS = "frontend/shell/src/views/medtech/pathology/format.ts";
    private static final String QC_VIEW = "frontend/shell/src/views/medtech/pathology/PathQcView.vue";
    private static final String PANEL = "frontend/shell/src/views/medtech/pathology/GrossingPanel.vue";
    private static final String TRAIL = "frontend/shell/src/views/medtech/pathology/TrailPanel.vue";
    private static final String QC_CONTROLLER = "modules/medtech/src/main/java/cn/hip/medtech/web/PathQcController.java";

    /** v62 的标签原文——最后一次补取材没填字段时它就是假话。本轮两处都不许再出现这句 */
    private static final String V62_FALSE_CLAIM = "本版字段只覆盖最后一次补取材";

    @Autowired PathologyProcessController process;
    @Autowired PathQcController pathQc;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc;
    private String from;
    private String to;
    private long orderId;

    @BeforeEach
    void setUp() {
        tag = "V63B" + Long.toHexString(System.nanoTime());
        doc = userAuth(tag + "d1", "DOCTOR_OUTP");
        from = BusinessDates.today().minusDays(1).toString();
        to = BusinessDates.today().toString();

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "前端表头守卫科" + tag, tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "表头守卫患者" + tag);
        Long regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
        Long oid = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag);
        assertNotNull(oid);
        orderId = oid;
    }

    // =====================================================================================
    // ① 编码列 / 中文名列：同一屏上不许两个同名表头，标着「编码」的列必须给编码
    // =====================================================================================

    @Test
    void pairedCodeAndNameColumnsRenderTwoDifferentHeadersAndTheCodeColumnShowsTheCode() {
        long sid = newSpecimen(1);
        ok(process.grossing(new GrossingReq(sid, null, grossOf("标本大小", "5×4×3cm"), "首次取材 " + tag,
                false, null, List.of(new BlockReq("块甲 " + tag))), doc));

        Map<String, String> zh = V59QcLabelsTest.zhEntries(read(FORMAT_TS));
        Map<String, String> backend = V59QcLabelsTest.backendLabels(read(QC_CONTROLLER));
        Map<String, String[]> codeCols = codeColumns(read(FORMAT_TS));

        // ---- 活对照组：两张表都真解析出来了（空表撞空表是这类扫描最常见的假绿）----
        assertTrue(zh.size() > 50, "前端 ZH 只解析出 " + zh.size() + " 个键");
        assertTrue(backend.size() > 50, "后端 zh() 只解析出 " + backend.size() + " 个 case 键");
        assertTrue(codeCols.size() >= 8, "format.ts 的 CODE_COLUMNS 只解析出 " + codeCols.keySet());

        // ---- 编码列的表头照后端同名 case 逐字抄（CSV 表头与页面表头必须是同一个叫法）----
        for (String col : List.of("attached_stain", "tech_type", "progress", "specimen_type", "stain_type", "node")) {
            assertTrue(codeCols.containsKey(col), "CODE_COLUMNS 缺编码列 " + col);
            assertNotNull(backend.get(col), "后端 zh() 缺 case \"" + col + "\"");
            assertEquals(backend.get(col), zh.get(col),
                    "ZH." + col + " 须与后端 zh() 同名 case 逐字一致（配对时的表头）");
            assertTrue(zh.get(col).endsWith("编码"),
                    "配对时这一列的表头要自带「编码」，否则与中文名列分不开：" + zh.get(col));
            assertNotEquals(zh.get(col), codeCols.get(col)[1],
                    col + " 配对 / 独占两种叫法不该相同（独占时格子里是中文名，表头就不该写「编码」）");
        }

        // ---- 真数据：SPECIMEN_RECEIVE 汇总行里 specimen_type 与 type_name 并排 ----
        List<Map<String, Object>> recv = indicatorRows("SPECIMEN_RECEIVE");
        Set<String> recvCols = recv.get(0).keySet();
        assertTrue(recvCols.contains("specimen_type") && recvCols.contains("type_name"),
                "活对照组：这两列在本指标的汇总行里确实并排出现（重名缺陷的现场）：" + recvCols);
        assertNoDuplicateHeaders("SPECIMEN_RECEIVE 汇总行", recvCols, zh, codeCols);
        assertEquals(zh.get("specimen_type"), colLabel("specimen_type", recvCols, zh, codeCols));
        assertTrue(showsRawCode("specimen_type", recvCols, codeCols),
                "**标着「" + zh.get("specimen_type") + "」的列必须显示编码**，而不是与隔壁「"
                        + zh.get("type_name") + "」列逐字相同的中文名");
        assertFalse(showsRawCode("type_name", recvCols, codeCols), "中文名列照旧显示中文名");

        // ---- 真数据：PROCESS_TAT 汇总行 node + node_name 并排；穿透明细里 node 独占 ----
        List<Map<String, Object>> tat = indicatorRows("PROCESS_TAT");
        Set<String> tatCols = tat.get(0).keySet();
        assertTrue(tatCols.contains("node") && tatCols.contains("node_name"), "活对照组：node 与 node_name 并排：" + tatCols);
        assertNoDuplicateHeaders("PROCESS_TAT 汇总行", tatCols, zh, codeCols);
        assertTrue(showsRawCode("node", tatCols, codeCols), "并排时环节编码列给编码");

        Set<String> tatDetailCols = detailColumns("PROCESS_TAT");
        assertTrue(tatDetailCols.contains("node") && !tatDetailCols.contains("node_name"),
                "活对照组：穿透明细里只有 node 一列、没有中文名列（独占分支的现场）：" + tatDetailCols);
        assertFalse(showsRawCode("node", tatDetailCols, codeCols),
                "独占时屏上没有第二列可读，格子仍要翻中文——否则就是把裸英文枚举放回屏幕上");
        assertEquals("环节", colLabel("node", tatDetailCols, zh, codeCols),
                "独占时表头不许再写「编码」（格子里是中文名），这一句与上一句是同一个判定派生的");
        assertNoDuplicateHeaders("PROCESS_TAT 穿透明细", tatDetailCols, zh, codeCols);

        Set<String> recvDetailCols = detailColumns("SPECIMEN_RECEIVE");
        assertTrue(recvDetailCols.contains("specimen_type") && !recvDetailCols.contains("type_name"),
                "活对照组：SPEC_SELECT 穿透里 specimen_type 独占：" + recvDetailCols);
        assertEquals("标本类别", colLabel("specimen_type", recvDetailCols, zh, codeCols));
        assertFalse(showsRawCode("specimen_type", recvDetailCols, codeCols));
        assertNoDuplicateHeaders("SPECIMEN_RECEIVE 穿透明细", recvDetailCols, zh, codeCols);

        // ---- 修复前的反向事实：表头一律 zh(col)（不看中文名列在不在场），这几对当场同名 ----
        var before = new LinkedHashMap<>(zh);
        before.put("specimen_type", "标本类别");      // v62 及之前 format.ts 里逐字如此
        before.put("attached_stain", "挂接切片染色");  // 同上，与 attached_stain_name 逐字相同
        assertEquals(before.get("specimen_type"), before.get("type_name"),
                "**修复前的反向事实**：两列表头逐字相同");
        assertEquals(before.get("attached_stain"), before.get("attached_stain_name"),
                "**修复前的反向事实**：v62 只正名了后端 CSV 字典，页面这张字典仍是同一个叫法");
        // 探针：把坏字典喂给检测器必须被抓到（否则上面那几条 assertNoDuplicateHeaders 是空话）
        Map<String, String[]> noCodeCols = Map.of();
        assertTrue(duplicateHeaders(recvCols, before, noCodeCols).contains("标本类别"),
                "探针：检测器对修复前的字典必须报出重名表头");
        assertEquals(1, duplicateHeaders(Set.of("attached_stain", "attached_stain_name"), before, noCodeCols).size(),
                "探针：编码版 / 中文版重名必须被抓到");
        assertTrue(duplicateHeaders(Set.of("attached_stain", "attached_stain_name"), zh, codeCols).isEmpty(),
                "修复后：同一行里这两列分得开");
    }

    // =====================================================================================
    // ② 覆盖率段：一个键两段语义要按段给中文；不是本段分母子集的列不许套百分比
    // =====================================================================================

    @Test
    void coverageSectionsLabelPerSectionAndCountColumnsNeverBecomeAPercentage() {
        long sid = newSpecimen(2);
        var gr = process.grossing(new GrossingReq(sid, null, grossOf("标本大小", "3×2cm"), "取材 " + tag,
                false, null, List.of(new BlockReq("块一 " + tag), new BlockReq("块二 " + tag))), doc);
        assertEquals(0, gr.getCode(), gr.getMessage());
        List<Long> blockIds = new ArrayList<>();
        for (var b : rows(gr.getData(), "blocks")) blockIds.add(((Number) b.get("id")).longValue());
        assertEquals(2, blockIds.size(), "夹具：两个蜡块");
        ok(process.slides(new SlideReq(blockIds.get(0), 3, "HE", null, null), doc));

        Map<String, String> zh = V59QcLabelsTest.zhEntries(read(FORMAT_TS));
        List<CovSec> secs = coverageSections(read(QC_VIEW));
        assertTrue(secs.size() >= 4, "PathQcView 的 COVERAGE_SECTIONS 只解析出 " + secs.size() + " 段");

        Map<String, Object> cov = map(ok(pathQc.indicators(from, to, null)), "coverage");
        assertTrue(cov.size() >= 4, "后端 coverage 只回了 " + cov.keySet());

        CovSec blocksSec = secByKey(secs, "blocks");
        CovSec slidesSec = secByKey(secs, "slides");
        Map<String, Object> blocksSeg = segment(cov, "blocks");
        Map<String, Object> slidesSeg = segment(cov, "slides");

        // ---- 活对照组：一个键真的服务了两段（重名与假百分比的现场都在这里）----
        assertTrue(blocksSeg.containsKey("blocks") && slidesSeg.containsKey("blocks"),
                "活对照组：blocks 键在蜡块段与切片段各有一个：" + blocksSeg.keySet() + " / " + slidesSeg.keySet());
        assertTrue(blocksSeg.containsKey("specimens"), "活对照组：蜡块段还有一个 specimens 计数列");
        long blocksDenom = n(blocksSeg.get("blocks"));
        long slidesDenom = n(slidesSeg.get("slides"));
        long slidesBlocks = n(slidesSeg.get("blocks"));
        assertTrue(blocksDenom >= 2 && slidesDenom >= 3 && slidesBlocks >= 1,
                "夹具：蜡块段分母 " + blocksDenom + "、切片段分母 " + slidesDenom + "、涉及蜡块 " + slidesBlocks);
        assertNotEquals(slidesDenom, slidesBlocks, "夹具刻意让两者不等，假百分比才不是 100%");

        // ---- 修复前的反向事实：不是本段分母就套 ratio()，切片段的涉及蜡块数被打成一条百分比 ----
        String fake = ratio(slidesBlocks, slidesDenom);
        assertTrue(fake.matches("\\d+ / \\d+（\\d+\\.\\d%）") && !fake.contains("100.0%"),
                "**修复前的反向事实**：切片段的 blocks 被渲染成「" + fake + "」——"
                        + "一个 not null 外键的去重计数（单位是蜡块）被套上以切片为分母的百分比");
        assertTrue(ratio(n(blocksSeg.get("specimens")), blocksDenom).contains("%"),
                "**修复前的反向事实**：蜡块段的涉及标本数同型");

        // ---- 修复后：计数列给原始数，只有本段分母的子集才算覆盖率 ----
        assertFalse(slidesSec.isRate("blocks"), "切片段的 blocks 是计数列，不套百分比");
        assertFalse(blocksSec.isRate("specimens"), "蜡块段的 specimens 是计数列，不套百分比");
        assertFalse(slidesSec.isRate("slides"), "分母本身给原始数");
        for (String k : slidesSeg.keySet()) {
            if (k.startsWith("with_")) assertTrue(slidesSec.isRate(k), "切片段的 " + k + " 是本段分母的子集，该给覆盖率");
        }
        assertTrue(blocksSec.isRate("with_embedded_at") && blocksSec.isRate("with_dehydrate_batch"));
        CovSec specSec = secByKey(secs, "specimens");
        for (String k : List.of("with_path_no", "outp_source", "rejected", "diagnosed_not_issued")) {
            assertTrue(specSec.isRate(k), "标本段的 " + k + " 是 path_specimen 同一批行的子集，该给覆盖率");
        }
        assertFalse(specSec.isRate("specimens"), "分母本身不套自己");

        // ---- 修复后：两个 blocks / 两个 specimens 在同一屏上有各自的中文 ----
        String blocksHere = blocksSec.label("blocks", zh);
        String blocksThere = slidesSec.label("blocks", zh);
        assertNotEquals(blocksHere, blocksThere,
                "**同一屏上两个「蜡块数」必须分得开**：蜡块段是 count(*) 产出数、切片段是 count(distinct block_id) 涉及数");
        assertNotEquals(specSec.label("specimens", zh), blocksSec.label("specimens", zh),
                "标本段的分母与蜡块段的涉及标本数同理");
        // 段内也不许重名
        for (CovSec sec : secs) {
            if (!cov.containsKey(sec.key())) continue;
            Map<String, Object> seg = segment(cov, sec.key());
            var seen = new LinkedHashSet<String>();
            for (String k : seg.keySet()) {
                if ("note".equals(k)) continue;
                assertTrue(seen.add(sec.label(k, zh)), "覆盖率「" + sec.key + "」段里表头重名：" + sec.label(k, zh));
            }
        }
        // 探针：labels 为空（修复前就是这样，全走扁平字典）时检测器必须报重名
        var flat = new CovSec("slides", "slides", List.of(), Map.of());
        assertEquals(flat.label("blocks", zh), new CovSec("blocks", "blocks", List.of(), Map.of()).label("blocks", zh),
                "**修复前的反向事实**：不给段内 label 时两段的 blocks 渲染成同一个中文表头");
        assertFalse(flat.isRate("blocks"), "探针：isRate 只认 with_ 与 rates 白名单，不认 blocks");
    }

    // =====================================================================================
    // ②b v64（2576 复核 demo 镜头）：合计块此前是整页唯一没有标题的 el-descriptions
    // =====================================================================================

    /**
     * 修复前那一行的逐字原文（v63 交付形态，{@code PathQcView.vue:93}）——
     * <b>活对照组</b>：同一个「有没有标题」的判据喂它必须判「没有」。
     */
    private static final String LEGACY_SUMMARY_TAG =
            "<el-descriptions v-if=\"ind.summary\" :column=\"4\" border size=\"small\" class=\"cav\">";

    /**
     * v64 交付形态那一行的逐字原文（{@code PathQcView.vue:95}）——<b>活对照组</b>：
     * 它<b>有</b>标题，所以上一轮那条「每块都要有标题」的判据放行了它；
     * 而它绑的是个不带指标参数的全局 computed，本轮那条「标题必须随指标而变」的判据喂它必须判红。
     */
    private static final String LEGACY_SUMMARY_TAG_V64 =
            "<el-descriptions v-if=\"ind.summary\" :column=\"4\" border size=\"small\" class=\"cav\" :title=\"summaryTitle\">";

    /** 有标题的形态（同页时限阈值那一块的逐字原文）——探针：判据不能把什么都判成「没有标题」 */
    private static final String TITLED_TAG =
            "<el-descriptions v-if=\"thresholds\" :column=\"3\" border size=\"small\" class=\"cav\" title=\"时限阈值与统计区间\">";

    private static final Pattern DESCRIPTIONS_OPEN = Pattern.compile("<el-descriptions(?![-\\w])[^>]*>");
    private static final Pattern HAS_TITLE = Pattern.compile("(^|\\s):?title\\s*=");
    private static final Pattern TITLE_BIND = Pattern.compile(":title=\"([^\"]*)\"");

    /**
     * <b>v63 那一轮的反向事实</b>（复核者原话）：「这个合计块在整页所有 el-descriptions 里是<b>唯一一个没有标题的</b>，
     * 屏上没有一个字说它是区间合计。」
     *
     * <p><b>v64 修它时带进来的新反向事实</b>（v64 交付后复核，主控实测坐实，归 v65 车道 C）：
     * 新加的 {@code summaryTitle} 是个<b>不带指标参数的全局 computed</b>，末句写死
     * 「下面那张表才按日拆分」，却被无条件绑在<b>逐指标的 v-for</b> 里那一块 el-descriptions 上——
     * 送检科室工作量 / 特检技术医嘱量 / 标本接收 / 标本固定信息完整率 / 染色切片优良率这五条
     * 分别按科室、技术类型、标本类别、标本类别、染色类型分组，<b>表里连日期列都没有</b>，
     * 屏上却每一块都在宣告自己是按日维度。上一轮那条「每块都要有标题」的判据对它一句话也说不出来：
     * 它盯的是「有没有」，不盯「那句话是不是真的」。
     *
     * <p>本条钉三样：整页每块描述表都有标题（v63 那条留着）；<b>合计块的标题必须随指标而变</b>
     * （绑定里引用循环变量 {@code ind}）；本文件里<b>不许再有一个文件级的 summaryTitle</b>——
     * 一个不带指标参数的常量，天然做不到「随指标而变」。
     */
    @Test
    void theRangeTotalBlockTitleIsPerIndicatorAndNeverAFileLevelConstant() {
        String src = stripComments(read(QC_VIEW));

        var tags = new ArrayList<String>();
        Matcher m = DESCRIPTIONS_OPEN.matcher(src);
        while (m.find()) tags.add(m.group());
        assertTrue(tags.size() >= 4,
                "活对照组：PathQcView 里本该解析出好几块 el-descriptions（阈值 / 覆盖率 / 缺字段 / 合计）：" + tags);

        var untitled = tags.stream().filter(t -> !HAS_TITLE.matcher(t).find()).toList();
        assertEquals(List.of(), untitled,
                "**整页每一块 el-descriptions 都要有标题**——v63 交付时只有合计块没有，"
                        + "屏上没有一个字说它是区间合计：" + untitled);

        String summaryTag = tags.stream().filter(t -> t.contains("ind.summary")).findFirst()
                .orElseGet(() -> fail("找不到合计块那一处 el-descriptions"));
        assertTitleVariesPerIndicator(summaryTag);

        // 修复前的反向事实：文件级的 summaryTitle 一旦还在，就说明这句话仍可能被写死一次套给所有指标
        assertFalse(src.contains("const summaryTitle"),
                "**本文件不许再有文件级的 summaryTitle**：它不带指标参数，"
                        + "做不到「按这条指标自己的分组维度说话」，而分组维度是后端 group by 时就知道的事实，"
                        + "必须由那个事实生成（PathQcController#summaryTitle）");
        assertTrue(src.contains("ind.summaryVsDailyNote"),
                "合计格下面那条「本期与按日之和的实际关系」也要上屏（后端按库态现算的 summaryVsDailyNote）");

        // ---- 活对照组：同一个判据喂 v63 / v64 两代的那一行，必须分别判红 ----
        assertFalse(HAS_TITLE.matcher(LEGACY_SUMMARY_TAG).find(),
                "探针：v63 交付时的那一行正是没有 title 的形态：" + LEGACY_SUMMARY_TAG);
        assertTrue(HAS_TITLE.matcher(TITLED_TAG).find(),
                "探针：判据不能把有标题的也判成没标题：" + TITLED_TAG);
        assertTrue(HAS_TITLE.matcher(LEGACY_SUMMARY_TAG_V64).find(),
                "探针：v64 那一行是**有**标题的——所以「每块都要有标题」那条判据放行了它：" + LEGACY_SUMMARY_TAG_V64);
        assertThrows(AssertionError.class, () -> assertTitleVariesPerIndicator(LEGACY_SUMMARY_TAG_V64),
                "**活对照组**：本轮这条判据喂 v64 交付时那一行必须判红（它绑的是全局 computed，不随指标而变）");
        assertEquals(1, DESCRIPTIONS_OPEN.matcher(LEGACY_SUMMARY_TAG).results().count(),
                "探针：标签匹配器咬得住那一行（不咬 el-descriptions-item）");
        assertEquals(0, DESCRIPTIONS_OPEN.matcher("<el-descriptions-item label=\"x\">").results().count(),
                "探针：el-descriptions-item 不算一块描述表");
    }

    /**
     * 这一块的标题绑定<b>引用了循环变量</b> {@code ind}——即这句话随指标而变。
     * 不引用循环变量的绑定，就是「一句话被无条件说给 N 个不同对象听」
     * （{@code tools/claim-ratchet.py --detect} 的循环变量规则，v64 的 summaryTitle 正是这个形状）。
     */
    private static void assertTitleVariesPerIndicator(String tag) {
        Matcher b = TITLE_BIND.matcher(tag);
        assertTrue(b.find(), "合计块要有 :title 绑定：" + tag);
        assertTrue(b.group(1).contains("ind."),
                "**合计块的标题必须随指标而变**（绑定里要引用循环变量 ind）——"
                        + "绑一个不带指标参数的全局常量，等于把只对某一条指标成立的话印到每一条头上：" + tag);
    }

    // =====================================================================================
    // ③ 2530：字段覆盖范围只有一个措辞来源；累积全文里更早各版不是「被取代」
    // =====================================================================================

    @Test
    void fieldsNoteIsTheOnlyWordingSourceAndSupersededKnowsAboutCumulativeText() {
        // --- 库态 A（复核者的 demo 镜头）：补取材也填了字段 → 字段版 = 文本版、当前文本是累积全文 ---
        long a = newSpecimen(3);
        ok(process.grossing(new GrossingReq(a, null, grossOf("标本大小", "5cm"), "首次 " + tag,
                false, null, List.of(new BlockReq("A1 " + tag))), doc));
        ok(process.grossing(new GrossingReq(a, null, grossOf("补取块数", "2 块"), "补取材 " + tag,
                true, null, List.of(new BlockReq("A2 " + tag))), doc));
        var va = ok(process.grossingView(a));
        assertEquals(Boolean.TRUE, va.get("textIsCumulative"));
        assertEquals(2, ((Number) va.get("fieldsRevisionSeq")).intValue());
        assertEquals(2, ((Number) va.get("textRevisionSeq")).intValue());
        String noteA = String.valueOf(va.get("fieldsNote"));
        // v64（2530 复核 data 镜头）：后半句原文是「要看各版完整的结构化记录请切换版本（各版字段都在）」，
        // v63 拿「；」把它与「余下 N 个……字段级查询与统计取不到」接成一句，两半互相否定。
        // 现在两句各自成句、辖域写在句子里（见 V62GrossReviseGuardTest②d）。
        assertTrue(noteA.contains("累积全文") && noteA.contains("按版本逐版可调阅"), noteA);
        assertTrue(noteA.contains("**"),
                "**后端事实**：这句话带裸 Markdown 强调；去标记是前端 mdText 的事（修复前两处原样打在屏幕上）");

        // --- 库态 B（复核者的 data 镜头）：最后一次补取材只写自由描述 → 该版零行字段 ---
        long b = newSpecimen(4);
        ok(process.grossing(new GrossingReq(b, null, grossOf("标本大小", "4cm"), "首次 " + tag,
                false, null, List.of(new BlockReq("B1 " + tag))), doc));
        ok(process.grossing(new GrossingReq(b, null, new LinkedHashMap<>(), "补取材只写描述 " + tag,
                true, null, List.of(new BlockReq("B2 " + tag))), doc));
        var vb = ok(process.grossingView(b));
        assertEquals(Boolean.TRUE, vb.get("textIsCumulative"), "当前文本仍是累积全文");
        assertEquals(1, ((Number) vb.get("fieldsRevisionSeq")).intValue(),
                "字段级记录停在第 1 版「取材首写」——最后那次补取材一行字段都没有");
        assertEquals(2, ((Number) vb.get("textRevisionSeq")).intValue());
        assertEquals(0, rows(vb, "fieldsByRevision").stream()
                        .filter(r -> ((Number) r.get("revisionSeq")).intValue() == 2)
                        .mapToInt(r -> ((List<?>) r.get("fields")).size()).sum(),
                "第 2 版 path_gross_field 零行");
        String noteB = String.valueOf(vb.get("fieldsNote"));
        assertTrue(noteB.contains("第 2 版补取材追加未填写字段"),
                "**同屏那条后端文案说的是这个**：" + noteB);
        // 这就是 v62 标签为假的那一刻：它只看 textIsCumulative 就说「本版字段只覆盖最后一次补取材」，
        // 而最后一次补取材（第 2 版）一行字段都没有，屏上那 1 项字段来自第 1 版「取材首写」。
        assertNotEquals(vb.get("fieldsRevisionSeq"), vb.get("textRevisionSeq"),
                "**修复前的反向事实**：textIsCumulative=true 时字段版未必就是最后那次补取材那一版");

        // --- 源码：两处措辞同源、不再各自推断，且都经 mdText 去标记 ---
        String panel = stripComments(read(PANEL));
        String trail = stripComments(read(TRAIL));
        String ts = stripComments(read(FORMAT_TS));
        assertTrue(panel.contains("splitGross"), "活对照组：真读到了 GrossingPanel 的取材表单代码");
        assertTrue(trail.contains("revisionFields"), "活对照组：真读到了 TrailPanel 的逐版展开代码");
        assertTrue(ts.contains("const ZH"), "活对照组：真读到了 format.ts 的字典");
        assertFalse(panel.contains(V62_FALSE_CLAIM),
                "**修复前的反向事实**：查看弹窗这句「" + V62_FALSE_CLAIM + "」在库态 B 下是假话，不得再出现");
        assertFalse(trail.contains(V62_FALSE_CLAIM),
                "**修复前的反向事实**：轨迹抽屉的兜底里同一句话同样为假，不得再出现");
        assertTrue(ts.contains("export function grossFieldsNote("),
                "措辞来源要收成一处：format.ts 的 grossFieldsNote（后端 fieldsNote 优先、后端没给才兜底）");
        assertTrue(panel.contains("grossFieldsNote(") && trail.contains("grossFieldsNote("),
                "**查看弹窗与轨迹抽屉必须用同一套措辞来源**（修复前一个按后端文案、一个自己推断）");
        assertFalse(panel.contains("String(view.fieldsNote)"),
                "**修复前的反向事实**：后端那句带 ** 的文案此前 String() 直出，星号打在屏幕上");
        assertFalse(trail.contains("gross.fieldsNote ||"),
                "**修复前的反向事实**：轨迹抽屉此前直接插 gross.fieldsNote（同样带 **）");
        assertTrue(grossFieldsNoteBody(ts).contains("mdText("),
                "共享的那一份内部必须过 mdText——两个入口才都不会把 ** 打在屏幕上");

        // --- superseded 判定把累积全文算进去 ---
        String gate = lineContaining(panel, "const superseded = ");
        assertTrue(gate.contains("!cumulative"),
                "superseded 必须排除累积全文场景（更早各版的内容仍在正文里，没被谁取代）：" + gate);
        assertTrue(panel.contains("const cumulative = view.value.textIsCumulative === true"),
                "累积与否读后端 textIsCumulative，不自己推断");
        assertFalse(gate.contains("latestSeq"),
                "**修复前的反向事实**：v62 的判定原文是 const superseded = latestSeq != null "
                        + "&& Number(v.revisionSeq) < Number(latestSeq)——只比版号、压根不看 textIsCumulative："
                        + gate);
        assertTrue(trail.contains("olderVersionNote("),
                "轨迹抽屉逐版展开处同一判定（同一事实的另一个读出口，v62 只修了一半的那种漏法）");
    }

    // =====================================================================================
    // 回放：把前端那几条分档规则在 Java 里按相同语义跑一遍
    // =====================================================================================

    /** {@code format.ts#colLabel} 的语义 */
    private static String colLabel(String col, Set<String> cols, Map<String, String> zh, Map<String, String[]> code) {
        String[] pair = code.get(col);
        if (pair != null && cols.contains(pair[0])) return zh.getOrDefault(col, col);
        return pair == null ? zh.getOrDefault(col, col) : pair[1];
    }

    /** {@code format.ts#cellText} 里那条新规则：中文名列在场 → 编码列给原始编码 */
    private static boolean showsRawCode(String col, Set<String> cols, Map<String, String[]> code) {
        String[] pair = code.get(col);
        return pair != null && cols.contains(pair[0]);
    }

    /** {@code format.ts#ratio} 的语义（修复前那条假百分比就是这么出来的） */
    private static String ratio(long numerator, long denominator) {
        if (denominator == 0) return numerator + " / 0（无数据）";
        return numerator + " / " + denominator
                + "（" + String.format("%.1f", 100.0 * numerator / denominator) + "%）";
    }

    private static List<String> duplicateHeaders(Set<String> cols, Map<String, String> zh, Map<String, String[]> code) {
        var seen = new LinkedHashSet<String>();
        var dup = new ArrayList<String>();
        for (String c : cols) {
            String label = colLabel(c, cols, zh, code);
            if (!seen.add(label)) dup.add(label);
        }
        return dup;
    }

    private static void assertNoDuplicateHeaders(String what, Set<String> cols,
                                                 Map<String, String> zh, Map<String, String[]> code) {
        List<String> dup = duplicateHeaders(cols, zh, code);
        assertTrue(dup.isEmpty(), what + " 上有重名表头 " + dup + "：同一屏两列同名，读的人无从分辨。列：" + cols);
    }

    /** PathQcView 的一段覆盖率描述：分母 / 占比列白名单 / 段内中文 */
    private record CovSec(String key, String denom, List<String> rates, Map<String, String> labels) {
        boolean isRate(String k) {
            return !k.equals(denom) && (k.startsWith("with_") || rates.contains(k));
        }

        String label(String k, Map<String, String> zh) {
            String v = labels.get(k);
            return v != null ? v : zh.getOrDefault(k, k);
        }
    }

    // =====================================================================================
    // 解析器：从前端源码里把那几张表抠出来
    // =====================================================================================

    /** format.ts 的 {@code CODE_COLUMNS}：编码列 → [中文名列, 独占时的表头] */
    static Map<String, String[]> codeColumns(String tsSource) {
        String code = stripComments(tsSource);
        int at = code.indexOf("const CODE_COLUMNS");
        assertTrue(at >= 0, "format.ts 里找不到 CODE_COLUMNS");
        // 注意：'{' 不能从 at 直接找——CODE_COLUMNS 的**类型注解** Record<string, { name…}> 里就有一个左大括号
        String body = braceBody(code, code.indexOf('{', code.indexOf('=', at)));
        var out = new LinkedHashMap<String, String[]>();
        Matcher m = Pattern.compile("(\\w+)\\s*:\\s*\\{\\s*name:\\s*'([^']*)'\\s*,\\s*alone:\\s*'([^']*)'\\s*}")
                .matcher(body);
        while (m.find()) out.put(m.group(1), new String[]{m.group(2), m.group(3)});
        return out;
    }

    /** PathQcView.vue 的 {@code COVERAGE_SECTIONS}（剥注释后按大括号配对逐段抠） */
    static List<CovSec> coverageSections(String vueSource) {
        String code = stripComments(vueSource);
        int at = code.indexOf("const COVERAGE_SECTIONS");
        assertTrue(at >= 0, "PathQcView 里找不到 COVERAGE_SECTIONS");
        int open = code.indexOf('[', code.indexOf('=', at));
        assertTrue(open > 0, "COVERAGE_SECTIONS 没有数组字面量");
        var out = new ArrayList<CovSec>();
        int depth = 0;
        int start = -1;
        for (int i = open; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    out.add(parseSection(code.substring(start, i + 1)));
                    start = -1;
                }
            } else if (ch == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    private static CovSec parseSection(String group) {
        String key = group1(group, "key\\s*:\\s*'([^']*)'");
        String denom = group1(group, "denom\\s*:\\s*'([^']*)'");
        var rates = new ArrayList<String>();
        Matcher r = Pattern.compile("rates\\s*:\\s*\\[([^]]*)]").matcher(group);
        if (r.find()) {
            Matcher one = Pattern.compile("'([^']*)'").matcher(r.group(1));
            while (one.find()) rates.add(one.group(1));
        }
        var labels = new LinkedHashMap<String, String>();
        Matcher l = Pattern.compile("labels\\s*:\\s*\\{([^}]*)}").matcher(group);
        if (l.find()) {
            Matcher one = Pattern.compile("(\\w+)\\s*:\\s*'([^']*)'").matcher(l.group(1));
            while (one.find()) labels.put(one.group(1), one.group(2));
        }
        return new CovSec(key, denom == null ? "" : denom, rates, labels);
    }

    /** {@code export function grossFieldsNote(...)} 的函数体（剥注释后按大括号配对截） */
    private static String grossFieldsNoteBody(String strippedTs) {
        int at = strippedTs.indexOf("export function grossFieldsNote(");
        assertTrue(at >= 0, "format.ts 没有 export function grossFieldsNote(");
        return braceBody(strippedTs, strippedTs.indexOf('{', strippedTs.indexOf(')', at)));
    }

    private static String braceBody(String s, int open) {
        assertTrue(open > 0, "找不到左大括号");
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return s.substring(open + 1, i);
        }
        return fail("大括号不配对");
    }

    private static String group1(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String lineContaining(String s, String needle) {
        int at = s.indexOf(needle);
        assertTrue(at >= 0, "源码里找不到「" + needle + "」");
        int end = s.indexOf('\n', at);
        return s.substring(at, end < 0 ? s.length() : end);
    }

    // =====================================================================================
    // 夹具与小工具
    // =====================================================================================

    private static CovSec secByKey(List<CovSec> secs, String key) {
        return secs.stream().filter(x -> key.equals(x.key())).findFirst()
                .orElseGet(() -> fail("COVERAGE_SECTIONS 里没有「" + key + "」段"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> segment(Map<String, Object> cov, String key) {
        Object v = cov.get(key);
        assertNotNull(v, "coverage 缺「" + key + "」段：" + cov.keySet());
        return (Map<String, Object>) v;
    }

    private List<Map<String, Object>> indicatorRows(String code) {
        var body = ok(pathQc.indicators(from, to, code));
        var inds = rows(body, "indicators");
        assertFalse(inds.isEmpty(), code + " 没回指标体");
        var list = rows(inds.get(0), "rows");
        assertFalse(list.isEmpty(), code + " 在本窗口内没有汇总行（夹具没落进窗口？）");
        return list;
    }

    private Set<String> detailColumns(String code) {
        var items = rows(ok(pathQc.detail(code, from, to, null)), "items");
        assertFalse(items.isEmpty(), code + " 穿透明细为空（夹具没落进窗口？）");
        return items.get(0).keySet();
    }

    private static LinkedHashMap<String, String> grossOf(String k, String v) {
        var m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }

    private long newSpecimen(int partNo) {
        Long id = jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent)
                values (?, ?, ?, ?, 'ROUTINE', ?, 'RECEIVED', now(), now(), false)
                returning id
                """, Long.class, orderId, partNo, "PB" + tag + partNo, tag + "-" + partNo, "守卫标本" + tag + partNo);
        assertNotNull(id);
        return id;
    }

    private Authentication userAuth(String username, String role) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "用户");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static long n(Object v) {
        assertNotNull(v, "计数列不该为 null");
        return ((Number) v).longValue();
    }

    private static Map<String, Object> ok(R<Map<String, Object>> r) {
        assertEquals(0, r.getCode(), r.getMessage());
        assertNotNull(r.getData());
        return r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
        Object v = body.get(key);
        assertNotNull(v, "返回体缺键 " + key + "：" + body.keySet());
        return (List<Map<String, Object>>) v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> body, String key) {
        Object v = body.get(key);
        assertNotNull(v, "返回体缺键 " + key + "：" + body.keySet());
        return (Map<String, Object>) v;
    }

    // ---------------- 剥注释与读文件（私有复制自 V60DeptDimensionTest，不改它） ----------------

    private static String stripComments(String s) {
        return stripJsComments(stripHtmlComments(s));
    }

    private static String stripHtmlComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf("<!--", i);
            if (start < 0) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, start);
            int end = s.indexOf("-->", start + 4);
            int stop = end < 0 ? s.length() : end + 3;
            for (int k = start; k < stop; k++) if (s.charAt(k) == '\n') out.append('\n');
            i = stop;
        }
        return out.toString();
    }

    private static String stripJsComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                out.append(ch);
                if (ch == '\\' && i + 1 < s.length()) out.append(s.charAt(++i));
                else if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                out.append(ch);
            } else if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                int end = e < 0 ? s.length() : e + 2;
                for (int k = i; k < end; k++) if (s.charAt(k) == '\n') out.append('\n');
                i = end - 1;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("modules")) && Files.isDirectory(p.resolve("platform"))) {
                return p;
            }
        }
        return fail("定位不到仓库根（从 " + System.getProperty("user.dir") + " 向上找 modules/ 与 platform/）");
    }

    /** 按仓库相对路径读真文件（绝对路径在 worktree 下会扫空恒绿）；文件不在就红 */
    private static String read(String rel) {
        Path f = repoRoot().resolve(rel);
        if (!Files.isRegularFile(f)) fail("找不到 " + rel + "（仓库根 " + repoRoot() + "）");
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读 " + f + " 失败", e);
        }
    }
}
