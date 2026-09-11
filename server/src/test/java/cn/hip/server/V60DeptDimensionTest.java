package cn.hip.server;

import cn.hip.medtech.web.PathQcController;
import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyController.DiagnoseReq;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyRegistryController;
import cn.hip.medtech.web.PathologyRegistryController.RejectReq;
import cn.hip.medtech.web.PathologyReportController;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <h1>v60 车道 C（2576-②③）：质控页按送检科室汇总维度 + 口径文本去 Markdown 标记 + ZH 补键</h1>
 *
 * <h2>修复前的反向事实（v60 地基 96f098e，反驳者原话经主控实测坐实）</h2>
 * <ol>
 *   <li>「多维度」里<b>没有按送检科室聚合的汇总维度</b>：{@code dept_name} 只出现在穿透明细里，六条 WORKLOAD_* 汇总行只按
 *       日 / 类别 / 染色 / 医师 / 技术类型分组——{@code GET /api/path-qc/indicators?indicator=WORKLOAD_DEPT} 修复前返 5281
 *       （{@link #workloadDeptAggregatesBySubmittingDept} 第一行断言必红），目录里也没有这个编码。</li>
 *   <li>页面顶部统计口径 alert、覆盖率 note、节假日口径是后端 Markdown 味文本（含 {@code **…**} 与反引号），
 *       前端 {@code {{ c }}} / {@code :title="sec.note"} <b>原样输出</b>，星号上屏（{@link #qcViewMarkdownTextGoesThroughMdText}
 *       对修复前模板片段的对照组恰好抓到这些）。</li>
 * </ol>
 *
 * <h2>本类钉住的契约</h2>
 * <ul>
 *   <li>(a) 指标 {@code WORKLOAD_DEPT}（名称「送检科室工作量」）进目录与 indicators：按送检科室（门诊挂号科室 / 住院在院科室，
 *       与 SPEC_SELECT 的 dept_name 同一条联接链）聚合本期登记的 registered / issued / rejected / in_progress，
 *       一科一行；取不到科室或科室名为空白的标本落「（未知科室）」一行、不丢行；summary 的 registered / rejected 与
 *       WORKLOAD_REGISTER 同窗同值（同分母，独立的交叉对照）。</li>
 *   <li>(b) 穿透明细复用 SPEC_SELECT（患者 / 病理号 / 科室 / 各时刻列齐全）并多一列 stage（已签发 / 已拒收 / 在办，与汇总三列同判据），
 *       {@code dept} 过滤只对 WORKLOAD_DEPT 生效（其余指标不套用、返回体不回带 dept），空白视为不过滤；CSV 同过滤且页脚写明。</li>
 *   <li>(c) 后端 zh() 与前端 ZH 都含本指标全部列且中文逐字相同；ZH 另含车道 B 契约列 blocks_derived「关联蜡块」、
 *       attached_stain_name「挂接切片染色」（规划节写死、由 C 统一补键）。V59QcLabelsTest（ZH ⊇ zh()）由主控另跑回归。</li>
 *   <li>(d) PathQcView 剥注释后，所有承载后端 Markdown 文本的绑定都经 {@code mdText}（对照组：修复前 96f098e 的
 *       {@code {{ c }}} / {@code :title="sec.note"} 直出片段必红；探针证明扫描器在咬）；模板不用 v-html。</li>
 *   <li>(e) {@code mdText} 的三条正则从 format.ts 源码逐条解析、在 Java 里按相同语义回放：'**a** b' → 'a b'、反引号删除、
 *       行首列表符去掉；对照组是后端 catalog 真回来的 caveats（确实带 **）；去掉一条规则探针必红。</li>
 * </ul>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：夹具时刻由端点按库端 {@code now()} 落，日期窗由 {@link BusinessDates#today()} 派生并两端各留一天余量
 * （照抄 V59TechConsistencyTest），时刻断言用「与库端 now() 偏差 ≤ 5 分钟」的容差（照抄 V58GrossFieldsTest.assertRecent）。
 * 事务回滚，库里不留痕。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V60DeptDimensionTest {

    private static final String CONTROLLER = "modules/medtech/src/main/java/cn/hip/medtech/web/PathQcController.java";
    private static final String FORMAT_TS = "frontend/shell/src/views/medtech/pathology/format.ts";
    private static final String QC_VIEW = "frontend/shell/src/views/medtech/pathology/PathQcView.vue";

    static final String CODE = "WORKLOAD_DEPT";
    static final String NAME = "送检科室工作量";
    static final String UNKNOWN = PathQcController.UNKNOWN_DEPT;

    /** 汇总行列序（与 rowsOf 的 select 别名顺序一致） */
    static final List<String> ROW_COLUMNS = List.of("dept_name", "registered", "issued", "rejected", "in_progress");
    /** 合计行列 */
    static final List<String> SUMMARY_COLUMNS = List.of("registered", "issued", "rejected", "in_progress", "dept_count", "unknown_dept");
    /** 穿透明细里必须有的 SPEC_SELECT 列（能核对到人、到标本、到科室、到状态时刻）+ 本指标派生列 stage */
    static final List<String> DETAIL_COLUMNS = List.of("specimen_id", "path_no", "barcode", "part_no", "specimen_type", "source",
            "patient_no", "patient_name", "dept_name", "collected_at", "received_at", "report_issued_at", "rejected_at",
            "reject_reason", "pathologist_name", "stage");

    @Autowired PathologyController pathology;
    @Autowired PathologyProcessController process;
    @Autowired PathologyRegistryController registry;
    @Autowired PathologyReportController report;
    @Autowired PathQcController pathQc;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc;
    private Authentication tech;
    private String from;
    private String to;

    @BeforeEach
    void setUp() {
        tag = "V60C" + Long.toHexString(System.nanoTime());
        doc = userAuth(tag + "d1", "DOCTOR_OUTP");
        tech = userAuth(tag + "t1", "TECHNICIAN");
        from = BusinessDates.today().minusDays(1).toString();
        to = BusinessDates.today().plusDays(1).toString();
    }

    // =====================================================================================
    // (a) 汇总：一科一行、各计数、未知科室不丢行、与 WORKLOAD_REGISTER 同分母
    // =====================================================================================

    @Test
    void workloadDeptAggregatesBySubmittingDept() {
        Dept a = dept("送检科室甲" + tag, "A");
        Dept b = dept("送检科室乙" + tag, "B");
        // 门诊挂号科室 / 住院在院科室在库里都 not null，联接链上唯一能「取不到科室」的现实形态是科室名为空白（sys_dept.name not null 但不禁止空白）
        Dept blank = dept("   ", "U");
        long a1 = specimen(a, "a1");
        long a2 = rejected(a, "a2");
        long a3 = issued(a, "a3");
        long b1 = issued(b, "b1");
        long b2 = rejected(b, "b2");
        long b3 = specimen(b, "b3");
        long b4 = specimen(b, "b4");
        long u1 = specimen(blank, "u1");
        assertTrue(a1 > 0 && a2 > 0 && a3 > 0 && b1 > 0 && b2 > 0 && b3 > 0 && b4 > 0 && u1 > 0);

        // 修复前：5281「指标编码不存在：WORKLOAD_DEPT」
        var body = ok(pathQc.indicators(from, to, CODE));
        var inds = rows(body, "indicators");
        assertEquals(1, inds.size());
        var ind = inds.get(0);
        assertEquals(CODE, ind.get("code"));
        assertEquals(NAME, ind.get("name"));
        assertEquals(Boolean.TRUE, ind.get("available"));
        assertEquals("path_specimen.collected_at", ind.get("anchorField"), "日期窗同登记类指标：按登记时刻落窗");
        assertEquals(Boolean.FALSE, ind.get("rowsTruncated"));
        assertTrue(String.valueOf(ind.get("caveat")).contains(UNKNOWN), "口径里要写明未知科室的归法");

        var list = rows(ind, "rows");
        assertFalse(list.isEmpty());
        assertEquals(ROW_COLUMNS, new ArrayList<>(list.get(0).keySet()), "汇总行列序");
        List<String> names = list.stream().map(r -> String.valueOf(r.get("dept_name"))).toList();
        assertEquals(names.size(), new HashSet<>(names).size(), "一科一行，不许重复：" + names);
        assertRow(list, a.name(), 3, 1, 1, 1);
        assertRow(list, b.name(), 4, 1, 1, 2);
        assertRow(list, UNKNOWN, 1, 0, 0, 1);
        assertTrue(names.indexOf(b.name()) < names.indexOf(a.name()) && names.indexOf(a.name()) < names.indexOf(UNKNOWN),
                "按 registered 降序：乙(4) → 甲(3) → 未知(1)：" + names);
        // 空白名科室的标本没有被混进任何真实科室行
        for (var r : list) {
            if (!UNKNOWN.equals(r.get("dept_name"))) assertFalse(String.valueOf(r.get("dept_name")).isBlank(), "不许出现空白科室名行");
        }

        // 合计行：与各行相加一致；dept_count = 行数；unknown_dept = 未知行的 registered
        var summary = map(ind, "summary");
        assertEquals(SUMMARY_COLUMNS, new ArrayList<>(summary.keySet()));
        for (String col : List.of("registered", "issued", "rejected", "in_progress")) {
            long sum = list.stream().mapToLong(r -> n(r.get(col))).sum();
            assertEquals(sum, n(summary.get(col)), "summary." + col + " 应等于各科室行之和");
        }
        assertEquals(list.size(), n(summary.get("dept_count")), "dept_count = 行数（含未知科室行）");
        assertEquals(1L, n(summary.get("unknown_dept")));
        assertEquals(n(summary.get("registered")),
                n(summary.get("issued")) + n(summary.get("rejected")) + n(summary.get("in_progress")),
                "签发 / 拒收 / 在办三分且互斥，之和 = 登记数");

        // 独立的交叉对照：WORKLOAD_REGISTER 同窗的 registered / rejected 必须相等（同分母、同锚点）——科室维度只是换了分组，不换总体
        var reg = map(rows(ok(pathQc.indicators(from, to, "WORKLOAD_REGISTER")), "indicators").get(0), "summary");
        assertEquals(n(reg.get("registered")), n(summary.get("registered")), "与 WORKLOAD_REGISTER 同分母");
        assertEquals(n(reg.get("rejected")), n(summary.get("rejected")));

        // 目录与全量 indicators 都含这个编码（修复前两处都没有）
        var cat = rows(ok(pathQc.catalog()), "indicators");
        assertTrue(cat.stream().anyMatch(c -> CODE.equals(c.get("code")) && Boolean.TRUE.equals(c.get("available"))
                && NAME.equals(c.get("name"))), "catalog 缺 WORKLOAD_DEPT：" + cat.stream().map(c -> c.get("code")).toList());
        var all = rows(ok(pathQc.indicators(from, to, null)), "indicators");
        assertTrue(all.stream().anyMatch(i -> CODE.equals(i.get("code"))), "全量 indicators 缺 WORKLOAD_DEPT");

        // (c) 的活样本：真返回的汇总行 / 合计行的每个键，后端 zh() 与前端 ZH 都登记且中文逐字相同
        assertLabelsRegistered(list.get(0).keySet(), "汇总行");
        assertLabelsRegistered(summary.keySet(), "合计行");

        // CSV 与页面同口径：表头走 zh()，各科室一行
        String csv = pathQc.indicatorsCsv(CODE, from, to);
        assertTrue(csv.contains("科室,登记标本数,签发份数,拒收数,在办数"), csv);
        assertTrue(csv.contains(a.name() + ",3,1,1,1"), csv);
        assertTrue(csv.contains(b.name() + ",4,1,1,2"), csv);
        assertTrue(csv.contains(UNKNOWN + ",1,0,0,1"), csv);
    }

    // =====================================================================================
    // (b) 穿透：复用 SPEC_SELECT + stage；dept 过滤只对本指标生效；CSV 同过滤
    // =====================================================================================

    @Test
    void detailReusesSpecSelectAndFiltersByDept() {
        Dept a = dept("送检科室丙" + tag, "A");
        Dept blank = dept(" \t ", "U");
        long a1 = specimen(a, "a1");
        long a2 = rejected(a, "a2");
        long a3 = issued(a, "a3");
        long u1 = specimen(blank, "u1");

        // 按科室过滤：只有甲的三条，列齐全，stage 与状态时刻一致
        var d = ok(pathQc.detail(CODE, from, to, null, a.name()));
        assertEquals(a.name(), d.get("dept"), "生效的过滤回带在返回体里");
        var items = rows(d, "items");
        assertEquals(3, items.size(), "该科室恰好三条：" + items);
        assertTrue(items.get(0).keySet().containsAll(DETAIL_COLUMNS), "明细列不全：" + items.get(0).keySet());
        assertEquals("stage", new ArrayList<>(items.get(0).keySet()).get(items.get(0).size() - 1), "派生列 stage 在最后");
        for (var it : items) {
            assertEquals(a.name(), it.get("dept_name"));
            assertEquals("OUTP", it.get("source"));
            assertEquals(a.patientName(), it.get("patient_name"));
            assertRecent(it.get("collected_at"));
        }
        assertEquals("在办", stageOf(items, a1));
        assertEquals("已拒收", stageOf(items, a2));
        assertNotNull(byId(items, a2).get("rejected_at"));
        assertEquals("已签发", stageOf(items, a3));
        assertNotNull(byId(items, a3).get("report_issued_at"));
        assertLabelsRegistered(items.get(0).keySet(), "穿透明细行");

        // 未知科室也能穿：传「（未知科室）」拿到空白名科室的标本
        var u = ok(pathQc.detail(CODE, from, to, null, UNKNOWN));
        var uItems = rows(u, "items");
        assertTrue(uItems.stream().allMatch(x -> UNKNOWN.equals(x.get("dept_name"))), "过滤后只有未知科室行：" + uItems);
        assertNotNull(byId(uItems, u1), "空白名科室的标本应落在「（未知科室）」下");
        assertEquals("在办", stageOf(uItems, u1));

        // 不过滤：四条都在，按科室名排；四参形态（v60 之前的既有直调）与 dept=null / 空白等价
        var noFilter = ok(pathQc.detail(CODE, from, to, null, null));
        assertTrue(noFilter.containsKey("dept") && noFilter.get("dept") == null, "本指标不过滤时 dept 回带 null");
        Set<Long> ids = ids(rows(noFilter, "items"));
        assertTrue(ids.containsAll(List.of(a1, a2, a3, u1)), "不过滤时四条都在：" + ids);
        assertEquals(ids, ids(rows(ok(pathQc.detail(CODE, from, to, null)), "items")), "四参形态 = 不过滤");
        assertEquals(ids, ids(rows(ok(pathQc.detail(CODE, from, to, null, "   ")), "items")), "空白 dept = 不过滤");
        // 按科室排 = 同科室的行连成一段（不断言具体次序：库端 collation 与 Java 字符序对中文不一定一致）
        List<String> order = rows(noFilter, "items").stream().map(x -> String.valueOf(x.get("dept_name"))).toList();
        var seen = new ArrayList<String>();
        for (String name : order) {
            if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(name)) {
                assertFalse(seen.contains(name), "科室「" + name + "」被别的科室隔开了，不是按科室排：" + order);
                seen.add(name);
            }
        }

        // 其余指标不套用科室过滤：WORKLOAD_REGISTER 带 dept 照样给全部、且不回带 dept 键
        var other = ok(pathQc.detail("WORKLOAD_REGISTER", from, to, null, a.name()));
        assertFalse(other.containsKey("dept"), "非本指标不该假装套用了过滤");
        assertTrue(ids(rows(other, "items")).contains(u1), "非本指标不按科室过滤");

        // CSV：同过滤、页脚写明过滤条件、表头走 zh()
        String csv = pathQc.detailCsv(CODE, from, to, a.name());
        assertTrue(csv.contains("科室过滤：" + a.name()), csv);
        assertTrue(csv.contains(",办理阶段"), "明细 CSV 表头缺 stage 的中文：" + csv);
        assertTrue(csv.contains(",已拒收") && csv.contains(",已签发") && csv.contains(",在办"), csv);
        long lines = csv.lines().filter(l -> l.contains("," + a.name() + ",")).count();
        assertEquals(3, lines, "CSV 里该科室恰好三行：" + csv);
        assertFalse(pathQc.detailCsv(CODE, from, to, null).contains("科室过滤："), "不过滤时不写过滤页脚");
    }

    // =====================================================================================
    // (c) 两侧字典：本指标全部列 + 车道 B 契约列
    // =====================================================================================

    @Test
    void zhCoversDeptColumnsWithFrontendWording() {
        Map<String, String> backend = V59QcLabelsTest.backendLabels(read(CONTROLLER));
        Map<String, String> zh = V59QcLabelsTest.zhEntries(read(FORMAT_TS));
        var all = new ArrayList<>(ROW_COLUMNS);
        all.addAll(SUMMARY_COLUMNS);
        all.add("stage");
        for (String k : new HashSet<>(all)) {
            assertTrue(backend.containsKey(k), "后端 zh() 缺 case \"" + k + "\"");
            assertTrue(zh.containsKey(k), "前端 ZH 缺 " + k + "（后端叫「" + backend.get(k) + "」）");
            assertEquals(backend.get(k), zh.get(k), "ZH." + k + " 与后端同名 case 中文不一致");
        }
        assertEquals("在办数", backend.get("in_progress"));
        assertEquals("送检科室数", backend.get("dept_count"));
        assertEquals("未知科室标本数", backend.get("unknown_dept"));
        assertEquals("办理阶段", backend.get("stage"));
        // 车道 B 契约列（规划节写死）：B 的端点在 PathologyReportController，不进本控制器的 CSV，故只登记在前端 ZH
        assertEquals("关联蜡块", zh.get("blocks_derived"), "ZH 缺车道 B 的 blocks_derived");
        assertEquals("挂接切片染色", zh.get("attached_stain_name"), "ZH 缺车道 B 的 attached_stain_name");
        assertEquals(zh.get("attached_stain"), zh.get("attached_stain_name"), "编码版与中文版同一个表头叫法");
        // 活的对照：两侧都真解析出几十个键（不是空表撞空表）
        assertTrue(backend.size() > 50 && zh.size() > 50, backend.size() + "/" + zh.size());
    }

    // =====================================================================================
    // (d) 源码扫描：PathQcView 承载后端 Markdown 文本的绑定都经 mdText；对照组与探针
    // =====================================================================================

    /** 修复前（96f098e）PathQcView 的相关片段，逐字抄来当对照组：{{ c }} / fmt(...) / :title="sec.note" / stripStars 直出 */
    static final String BEFORE_V60 = """
            <template>
                <el-alert v-for="(c, i) in caveats" :key="i" type="warning" show-icon :closable="false" class="cav"
                          :title="i === 0 ? '统计口径（请先看完这几条再看指标值）' : ''">
                  <div>{{ c }}</div>
                </el-alert>
                  <el-descriptions-item label="节假日口径" :span="3">
                    {{ fmt(thresholds.holidayNote) }}</el-descriptions-item>
                  <el-descriptions-item label="接收及时率为何不给单一数字" :span="3">
                    {{ fmt(thresholds.receiveThresholdNote) }}</el-descriptions-item>
                    <el-alert v-if="sec.note" type="info" :closable="false" class="cav" :title="sec.note" />
                      <div>{{ ind.unavailableReason }}</div>
                        {{ f }}
                    <el-alert type="info" :closable="false" class="cav"
                              :title="`归集锚点：${ind.anchorField}　${stripStars(String(ind.anchor ?? ''))}`" />
                    <el-alert v-if="ind.caveat" type="warning" :closable="false" class="cav"
                              :title="stripStars(String(ind.caveat))" />
                    <el-alert v-if="ind.rowsTruncated === true" type="warning" show-icon :closable="false" class="cav"
                              :title="String(ind.rowsTruncatedNote ?? '汇总行超限，已截断，请缩小统计区间')" />
                <el-alert v-if="detailUnavailable" type="error" show-icon :closable="false" class="cav"
                          :title="detailUnavailable" />
                  <el-alert v-if="detailCaveat" type="warning" :closable="false" class="cav" :title="detailCaveat" />
            </template>
            <script setup lang="ts"></script>
            """;

    /** 模板里承载后端 Markdown 味文本的表达式：每一处上屏绑定都得包在 mdText( 里（v-if 只判有无，不算绑定） */
    static final List<String> MD_EXPRS = List.of("sec.note", "thresholds.holidayNote", "thresholds.receiveThresholdNote",
            "ind.unavailableReason", "ind.anchor", "ind.caveat", "ind.rowsTruncatedNote", "detailUnavailable", "detailCaveat");

    @Test
    void qcViewMarkdownTextGoesThroughMdText() {
        String view = read(QC_VIEW);
        List<String> bad = mdViolations(view);
        assertTrue(bad.isEmpty(), QC_VIEW + " 仍有口径文本不经 mdText 上屏：\n  " + String.join("\n  ", bad));

        String script = stripJsComments(view.substring(view.indexOf("<script")));
        assertTrue(Pattern.compile("import\\s*\\{[^}]*\\bmdText\\b[^}]*\\}\\s*from\\s*'\\./format'").matcher(script).find(),
                QC_VIEW + " 须从 ./format 引入 mdText");
        assertFalse(script.contains("stripStars"), "旧的 stripStars 已由 mdText 取代，不留两套");
        assertTrue(stripJsComments(read(FORMAT_TS)).contains("export function mdText("), FORMAT_TS + " 没有导出 mdText");

        // 活的对照组：修复前片段必须被逐条抓到
        List<String> before = mdViolations(BEFORE_V60);
        for (String must : List.of("{{ c }}", "{{ f }}", "sec.note", "thresholds.holidayNote", "thresholds.receiveThresholdNote",
                "ind.unavailableReason", "ind.anchor", "ind.caveat", "ind.rowsTruncatedNote", "detailUnavailable", "detailCaveat")) {
            assertTrue(before.stream().anyMatch(v -> v.contains(must)), "修复前形态没抓到 " + must + "：" + before);
        }
    }

    @Test
    void mdBindingDetectorActuallyBites() {
        String view = read(QC_VIEW);
        assertTrue(mdViolations(view).isEmpty(), "前提：当前视图干净");

        // 抠掉一处 mdText → 恰好报那一个表达式
        assertTrue(view.contains("mdText(sec.note)"), "探针无从抠起");
        List<String> probe = mdViolations(view.replace("mdText(sec.note)", "sec.note"));
        assertEquals(1, probe.size(), probe.toString());
        assertTrue(probe.get(0).startsWith("sec.note"), probe.get(0));

        // 注释里的修复前形态不计；v-if 里出现表达式不算绑定；v-html 一出现就报
        assertTrue(mdViolations(view.replace("<template>", "<template><!-- {{ c }} :title=\"sec.note\" -->")).isEmpty(),
                "注释里的形态不得计数");
        assertTrue(mdViolations(view.replace("<template>", "<template><span v-if=\"ind.caveat\" />")).isEmpty(),
                "v-if 只判有无，不是上屏绑定");
        List<String> html = mdViolations(view.replace("<template>", "<template><div v-html=\"mdText(ind.caveat)\" />"));
        assertTrue(html.stream().anyMatch(v -> v.contains("v-html")), html.toString());
        // 直出 {{ c }} 即使另有 mdText(c) 也要报
        assertTrue(mdViolations(view.replace("<template>", "<template><p>{{ c }}</p>")).stream().anyMatch(v -> v.contains("{{ c }}")));
    }

    // =====================================================================================
    // (e) mdText：正则从源码解析、在 Java 里回放；对照组是 catalog 真回来的 caveats
    // =====================================================================================

    @Test
    void mdTextStripsMarkersPerSourceRegexes() {
        List<Rule> rules = mdTextRules(read(FORMAT_TS));
        assertTrue(rules.size() >= 3, "mdText 至少三条 .replace(/…/g, '…') 规则（** / 反引号 / 行首列表符），解析到：" + rules);
        assertEquals("a b", apply(rules, "**a** b"));
        assertEquals("x y", apply(rules, "`x` y"));
        assertEquals("项一\n项二", apply(rules, "- 项一\n- 项二"));
        assertEquals("有 强调 与 代码", apply(rules, "有 **强调** 与 `代码`"));
        assertEquals("本域各指标不共用同一个归集时刻，跨指标横向相加没有意义。",
                apply(rules, "本域各指标**不共用同一个归集时刻**，跨指标横向相加没有意义。"));
        assertEquals("a - b（行中破折号不是列表符）", apply(rules, "a - b（行中破折号不是列表符）"));
        assertEquals("纯文本原样", apply(rules, "纯文本原样"));
        assertEquals("", apply(rules, ""));

        // 活的对照组：后端 catalog 真回来的 caveats 确实带 **（否则本函数无事可做），去标记后一个星号对都不剩、文字长度只少了标记
        @SuppressWarnings("unchecked")
        List<String> caveats = (List<String>) ok(pathQc.catalog()).get("caveats");
        assertNotNull(caveats);
        assertTrue(caveats.stream().anyMatch(c -> c.contains("**")), "后端口径文本本该带 ** 强调：" + caveats);
        for (String c : caveats) {
            String plain = apply(rules, c);
            assertFalse(plain.contains("**") || plain.contains("`"), plain);
            int markers = c.length() - c.replace("**", "").replace("`", "").length();
            assertEquals(c.length() - markers, plain.length(), "只去标记不动文字：" + c);
        }
    }

    @Test
    void mdTextRuleDetectorActuallyBites() {
        List<Rule> rules = mdTextRules(read(FORMAT_TS));
        // 去掉 ** 规则 → 星号留在屏幕上（修复前的样子）
        List<Rule> noStars = rules.stream().filter(r -> !r.regex().contains("\\*\\*")).toList();
        assertEquals(rules.size() - 1, noStars.size(), "本该恰好有一条 ** 规则：" + rules);
        assertEquals("**a** b", apply(noStars, "**a** b"));
        // 解析器只认 mdText 函数体里的 .replace(/re/flags, '…')：没有规则的合成函数解析出 0 条
        assertTrue(mdTextRules("export function mdText(v: unknown): string {\n  return String(v)\n}\n").isEmpty());
        // 注释里的规则不计
        assertTrue(mdTextRules("export function mdText(v: unknown): string {\n  // .replace(/\\*\\*/g, '')\n  return String(v)\n}\n").isEmpty());
        // 别的函数里的 .replace 不计
        assertTrue(mdTextRules("export function other(s: string): string {\n  return s.replace(/\\*\\*/g, '')\n}\n"
                + "export function mdText(v: unknown): string {\n  return String(v)\n}\n").isEmpty());
    }

    // =====================================================================================
    // 扫描器与回放器
    // =====================================================================================

    /** 模板里承载后端 Markdown 文本的绑定不经 mdText 的清单（空 = 干净）。只看 &lt;script 之前的模板、剥 HTML 注释、去掉 v-if 属性 */
    static List<String> mdViolations(String view) {
        int sc = view.indexOf("<script");
        String tpl = stripHtmlComments(sc < 0 ? view : view.substring(0, sc));
        tpl = tpl.replaceAll("v-(?:else-)?if=\"[^\"]*\"", "");
        var out = new ArrayList<String>();
        if (tpl.contains("v-html")) out.add("模板用了 v-html——口径文本一律纯文本插值");
        if (find("\\{\\{\\s*c\\s*\\}\\}", tpl)) out.add("caveats：{{ c }} 直出（修复前形态）");
        if (!find("\\{\\{\\s*mdText\\(\\s*c\\s*\\)\\s*\\}\\}", tpl)) out.add("caveats：没有 {{ mdText(c) }}");
        if (find("\\{\\{\\s*f\\s*\\}\\}", tpl)) out.add("missingFields：{{ f }} 直出（修复前形态）");
        if (!find("\\{\\{\\s*mdText\\(\\s*f\\s*\\)\\s*\\}\\}", tpl)) out.add("missingFields：没有 {{ mdText(f) }}");
        for (String e : MD_EXPRS) {
            String q = Pattern.quote(e);
            int total = count("(?<![\\w.$])" + q + "\\b", tpl);
            int wrapped = count("mdText\\(\\s*" + q + "\\b", tpl);
            if (total == 0) out.add(e + "：模板里没有这个绑定（口径文本被删了？）");
            else if (wrapped != total) out.add(e + "：" + total + " 处上屏绑定只有 " + wrapped + " 处经 mdText");
        }
        return out;
    }

    /** 一条 {@code .replace(/re/flags, 'repl')} 规则 */
    record Rule(String regex, String flags, String replacement) {}

    private static final Pattern MD_TEXT_FN = Pattern.compile("export\\s+function\\s+mdText\\s*\\(");
    private static final Pattern REPLACE_CALL = Pattern.compile(
            "\\.replace\\(\\s*/((?:\\\\.|[^/\\\\\\n])+)/([a-z]*)\\s*,\\s*'((?:\\\\.|[^'\\\\])*)'\\s*\\)");

    /** format.ts 里 mdText 函数体内的全部 .replace(/re/flags, '…') 规则，源码顺序；剥注释后按括号配对截函数体 */
    static List<Rule> mdTextRules(String ts) {
        String code = stripJsComments(ts);
        Matcher m = MD_TEXT_FN.matcher(code);
        if (!m.find()) fail("找不到 export function mdText(");
        int open = code.indexOf('{', m.end());
        String body = code.substring(open + 1, V59QcLabelsTest.closerOf(code, open));
        var out = new ArrayList<Rule>();
        Matcher r = REPLACE_CALL.matcher(body);
        while (r.find()) out.add(new Rule(r.group(1), r.group(2), r.group(3)));
        return out;
    }

    /** 按 JS 语义回放：g → 全替换否则首个；m/i/s 转 Java 内联标志；替换串无 $ 时按字面 */
    static String apply(List<Rule> rules, String input) {
        String s = input;
        for (Rule r : rules) {
            String re = r.regex();
            if (r.flags().contains("s")) re = "(?s)" + re;
            if (r.flags().contains("i")) re = "(?i)" + re;
            if (r.flags().contains("m")) re = "(?m)" + re;
            String repl = r.replacement().contains("$") ? r.replacement() : Matcher.quoteReplacement(r.replacement());
            Matcher mm = Pattern.compile(re).matcher(s);
            s = r.flags().contains("g") ? mm.replaceAll(repl) : mm.replaceFirst(repl);
        }
        return s;
    }

    private static boolean find(String regex, String s) {
        return Pattern.compile(regex).matcher(s).find();
    }

    private static int count(String regex, String s) {
        int n = 0;
        Matcher m = Pattern.compile(regex).matcher(s);
        while (m.find()) n++;
        return n;
    }

    // =====================================================================================
    // 夹具与取值
    // =====================================================================================

    record Dept(long id, String name, long regId, String patientName) {}

    /** 一个科室 + 它的排班 / 患者 / 一次已就诊挂号（同一挂号下开多条病理申请 → 多条标本，与 V59ExecutedGuardTest 同建法） */
    private Dept dept(String name, String suffix) {
        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, name, tag + suffix);
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        String patientName = "送检患者" + suffix + tag;
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P" + suffix, patientName);
        Long regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
        return new Dept(deptId, name, regId, patientName);
    }

    /** 一条已收费门诊病理申请 → 既有 collect 登记打码 → 标本 id（在办：未签发、未拒收） */
    private long specimen(Dept d, String suffix) {
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, d.regId(), "G" + tag + suffix);
        var c = pathology.collect(orderId, "标本" + suffix + " " + tag);
        assertEquals(0, c.getCode(), c.getMessage());
        String barcode = (String) c.getData().get("barcode");
        assertNotNull(barcode);
        return jdbc.queryForObject("select id from path_specimen where barcode = ?", Long.class, barcode);
    }

    /** 登记后走真实拒收端点（不删记录、不改 status，只落 rejected_at） */
    private long rejected(Dept d, String suffix) {
        long sid = specimen(d, suffix);
        var r = registry.reject(sid, new RejectReq("固定液不规范 " + tag, null), tech);
        assertEquals(0, r.getCode(), r.getMessage());
        assertNotNull(jdbc.queryForObject("select rejected_at from path_specimen where id = ?", Object.class, sid));
        return sid;
    }

    /** collect → receive → 真实 grossing → diagnose → 正式签发（gate 默认 warn 放行，report_issued_at 由库端 now() 落） */
    private long issued(Dept d, String suffix) {
        long sid = specimen(d, suffix);
        String barcode = jdbc.queryForObject("select barcode from path_specimen where id = ?", String.class, sid);
        var rc = pathology.receive(barcode);
        assertEquals(0, rc.getCode(), rc.getMessage());
        var gr = process.grossing(new GrossingReq(sid, null, null, "灰白组织一块 " + tag, false, null,
                List.of(new BlockReq("肿物中心"))), doc);
        assertEquals(0, gr.getCode(), gr.getMessage());
        var dx = pathology.diagnose(barcode, new DiagnoseReq(null, "镜下见慢性炎细胞浸润 " + tag, "慢性炎症 " + tag), doc);
        assertEquals(0, dx.getCode(), dx.getMessage());
        var is = report.issue(sid, doc);
        assertEquals(0, is.getCode(), is.getMessage());
        assertNotNull(is.getData().get("reportIssuedAt"));
        return sid;
    }

    private static void assertRow(List<Map<String, Object>> rows, String dept, long registered, long issued,
                                  long rejected, long inProgress) {
        var row = rows.stream().filter(r -> dept.equals(r.get("dept_name"))).findFirst()
                .orElseGet(() -> fail("汇总里没有科室行「" + dept + "」：" + rows));
        assertEquals(registered, n(row.get("registered")), dept + ".registered");
        assertEquals(issued, n(row.get("issued")), dept + ".issued");
        assertEquals(rejected, n(row.get("rejected")), dept + ".rejected");
        assertEquals(inProgress, n(row.get("in_progress")), dept + ".in_progress");
    }

    /**
     * 后端 zh() javadoc 明写的、两侧刻意措辞不同的四个键（页面单元格经 cellText 翻成中文、CSV 导出原始编码，故 CSV 表头保留「编码」提示）——
     * 只豁免这四个，其余键两侧中文必须逐字相同。
     */
    static final Set<String> WORDING_DIVERGES_BY_DESIGN = Set.of("specimen_type", "source", "slide_count", "progress");

    /** 真返回的一组键，后端 zh() 与前端 ZH 都登记且中文逐字相同（不许有一列在页面显示英文、CSV 却是中文） */
    private static void assertLabelsRegistered(Set<String> keys, String what) {
        Map<String, String> backend = V59QcLabelsTest.backendLabels(read(CONTROLLER));
        Map<String, String> zh = V59QcLabelsTest.zhEntries(read(FORMAT_TS));
        var bad = new ArrayList<String>();
        for (String k : keys) {
            if (!backend.containsKey(k)) bad.add(k + "：后端 zh() 未登记");
            else if (!zh.containsKey(k)) bad.add(k + "：前端 ZH 未登记");
            else if (!WORDING_DIVERGES_BY_DESIGN.contains(k) && !backend.get(k).equals(zh.get(k))) {
                bad.add(k + "：后端「" + backend.get(k) + "」≠ 前端「" + zh.get(k) + "」");
            }
        }
        assertTrue(bad.isEmpty(), what + " 的列名字典有漏：\n  " + String.join("\n  ", bad));
    }

    private static Map<String, Object> byId(List<Map<String, Object>> items, long specimenId) {
        return items.stream().filter(x -> n(x.get("specimen_id")) == specimenId).findFirst()
                .orElseGet(() -> fail("明细里没有标本 " + specimenId + "：" + items));
    }

    private static String stageOf(List<Map<String, Object>> items, long specimenId) {
        return String.valueOf(byId(items, specimenId).get("stage"));
    }

    private static Set<Long> ids(List<Map<String, Object>> items) {
        var out = new HashSet<Long>();
        for (var x : items) out.add(n(x.get("specimen_id")));
        return out;
    }

    private static long n(Object v) {
        assertNotNull(v, "计数列不该为 null");
        return ((Number) v).longValue();
    }

    /** 时刻容差断言：与库端 now() 偏差 ≤ 5 分钟（库端算，不在 Java 里做时区换算） */
    private void assertRecent(Object ts) {
        assertNotNull(ts);
        Double gap = jdbc.queryForObject(
                "select abs(extract(epoch from (now() - ?::timestamptz)))", Double.class, ts);
        assertNotNull(gap);
        assertTrue(gap <= 300, "时刻应在此刻 5 分钟内，偏差秒数=" + gap);
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

    private Authentication userAuth(String username, String role) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "用户");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    // ---------------- 剥注释与读文件（私有复制自 V59QcLabelsTest，不改它） ----------------

    /** 去掉模板里的 {@code <!-- -->}，换行保留 */
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

    /** 去掉 {@code //} 行注释与块注释，字符串里的内容原样保留（Java 与 TS 注释语法相同） */
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

    /** 从测试工作目录（server 模块）向上找仓库根 */
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
