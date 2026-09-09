package cn.hip.server;

import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyProcessController.SlideReq;
import cn.hip.medtech.web.PathologyProcessController.StainReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.medtech.web.PathologyReportController.TechOrderReq;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import org.junit.jupiter.api.AfterEach;
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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v55 车道 R1：<b>病理可达性收口</b>（2553/2558/2563/2530/2522）的判据测试。
 *
 * <h2>这个类要抓的是什么</h2>
 * 本版修的不是「后端算错」，是「后端做了、前端够不着」——控制器有前端引用、断言全绿，
 * 可医生要找的记录被列表的 where 条件永久排除了。这一类错<b>不会让构建失败，也不会报错</b>，
 * 所以本类每条断言都按「用户拿着一个真实的病理号，从菜单走进来能不能看见它」来写：
 * <ol>
 *   <li>{@link #worklistKeepsLegacyScopesAndOpensDiagnosedSpecimens()}：阅片列表<b>不传 scope 的行为一个字节不变</b>
 *       （已诊断的 B 仍不出现，未识别取值仍回落 stained），而新档 {@code diagnosed}/{@code any}
 *       能把已签发的 B 列出来、按病理号搜出来——报告抽屉的唯一入口从此进得去。</li>
 *   <li>{@link #grossFindingIsReadableBeforeDiagnosis()}：刚取材、未诊断的 A，取材工位能独立看到自己写的大体所见。</li>
 *   <li>{@link #hospitalWideTechOrderListFiltersByStatusTypeTimeAndKeyword()}：全院技术医嘱清单
 *       （不传 specimenId）默认仍只看 ORDERED，新参数能按状态 / 类型 / 时间 / 关键词 / 加急筛。</li>
 *   <li>{@link #anomaliesSurfaceStalledAndSkippedNodesAndTrailShowsNodes()}：超时未流转（C）与三类跳节点
 *       （A 切片前无包埋、B 诊断前无染色切片、B 签发缺复签）都能筛出来；轨迹端点按时间序给出节点。</li>
 * </ol>
 *
 * <h2>夹具</h2>
 * 三个标本各自挂在本用例现建的门诊医嘱上，病理号 / 条码带唯一 tag，所有断言按 id 判成员，
 * 不依赖排序位置。<b>不写任何时间字面量</b>：全部时刻由 SQL 按 {@code now()} 现算
 * （本仓已被时区与时刻字面量炸过五次）；日期窗用 {@link BusinessDates#today()} 派生，
 * 且刻意留一天余量，UTC 的 JVM 与北京时区的库对「今天」的分歧不该让本类变红。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V55PathologyReachTest {

    @Autowired PathologyReportController report;
    @Autowired PathologyProcessController process;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Long regId;
    private Authentication doc1;
    private Authentication doc2;

    private long a;   // 已核收 3 天：取材（写了大体所见）→ 切片（蜡块未包埋）→ 染色一张；未诊断
    private long b;   // 已核收 5 天：已诊断（2 天前）→ 初签 → 缺复签即签发（gate=warn 放行）；无任何切片
    private long c;   // 已核收 3 天：一个流转节点都没有——典型的「超时未流转」
    private long blockA;
    private long techOnB;   // IHC CK7，ORDERED
    private long techOnA;   // DEEP_CUT，DONE

    @BeforeEach
    void setUp() {
        configReader.evictAll();
        // 双签 gate 钉在 warn：B 要「缺复签即签发」才能造出 ISSUED_WITHOUT_DOUBLE_SIGN；事务回滚，库里不留痕
        jdbc.update("update sys_config set cfg_value = 'warn' where cfg_key = ?",
                PathologyReportController.DOUBLE_SIGN_GATE_KEY);
        configReader.evictAll();

        tag = "V55" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");
        doc2 = doctorAuth(tag + "d2");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "病理可达性测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "可达性患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);

        a = specimen("A", "now() - interval '3 days'");
        b = specimen("B", "now() - interval '5 days'");
        c = specimen("C", "now() - interval '3 days'");

        // ---- A：取材写大体所见 → 切片（蜡块无包埋记录，端点只告警）→ 染色一张 ----
        var gr = process.grossing(new GrossingReq(a, null, null, "灰白组织一块 " + tag, false, null,
                List.of(new BlockReq("肿物中心"))), doc1);
        assertEquals(0, gr.getCode(), gr.getMessage());
        blockA = idOf(rows(gr.getData(), "blocks").get(0));
        var sl = process.slides(new SlideReq(blockA, 2, "HE", null, null), doc1);
        assertEquals(0, sl.getCode(), sl.getMessage());
        long slideId = idOf(rows(sl.getData(), "slides").get(0));
        var st = process.stain(slideId, new StainReq("GOOD", null, null), doc1);
        assertEquals(0, st.getCode(), st.getMessage());

        // ---- B：诊断（走库，既有 diagnose 端点按 barcode 且状态机已在别处覆盖）→ 初签 → 缺复签即签发 ----
        jdbc.update("""
                update path_specimen
                   set status = 'DIAGNOSED', diagnosis = ?, gross_finding = ?,
                       diagnosed_at = now() - interval '2 days', pathologist_id = ?
                 where id = ?
                """, "（左乳）浸润性导管癌 " + tag, "灰白组织 " + tag, userId(tag + "d1"), b);
        var fs = report.firstSign(b, null, doc1);
        assertEquals(0, fs.getCode(), fs.getMessage());
        var is = report.issue(b, doc1);
        assertEquals(0, is.getCode(), is.getMessage());
        assertFalse(((List<?>) is.getData().get("warnings")).isEmpty(),
                "gate=warn 下缺复签签发必须回带 warnings，否则夹具没造出「缺双签即签发」这个事实");
        jdbc.update("update path_specimen set urgent = true where id = ?", b);

        // ---- 技术医嘱：B 一条待执行的免疫组化，A 一条已完成的深切 ----
        var t1 = report.createTechOrder(new TechOrderReq(b, null, "IHC", "CK7", "免疫组化 " + tag), doc1);
        assertEquals(0, t1.getCode(), t1.getMessage());
        techOnB = ((Number) t1.getData().get("id")).longValue();
        var t2 = report.createTechOrder(new TechOrderReq(a, blockA, "DEEP_CUT", null, null), doc1);
        assertEquals(0, t2.getCode(), t2.getMessage());
        techOnA = ((Number) t2.getData().get("id")).longValue();
        assertEquals(0, report.doneTechOrder(techOnA, doc1).getCode());
    }

    @AfterEach
    void tearDown() {
        // 配置缓存是跨用例存活的单例，事务回滚不会把它一起回滚
        configReader.evictAll();
    }

    // =====================================================================================
    // ① 阅片列表：旧档一字不变，新档进得去（2553 / 2558）
    // =====================================================================================

    @Test
    void worklistKeepsLegacyScopesAndOpensDiagnosedSpecimens() {
        // 不传 scope：v48 既有行为——只有已染色待诊断的 A；已签发的 B、无切片的 C 都不在
        var legacy = ok(report.worklist(null, null, null, null, null, null, null));
        assertEquals("stained", legacy.get("scope"));
        Set<Long> ids = ids(legacy);
        assertTrue(ids.contains(a), "默认档必须仍列出已染色待诊断的 A");
        assertFalse(ids.contains(b), "默认档不许混进已诊断的 B——旧行为一个字节不能变");
        assertFalse(ids.contains(c), "默认档不许混进无染色切片的 C");

        // 未识别的取值仍回落 stained（v48 既有行为，不改）
        var bogus = ok(report.worklist("BOGUS", null, null, null, null, null, null));
        assertEquals("stained", bogus.get("scope"));
        assertFalse(ids(bogus).contains(b));

        // all：已核收未诊断的全部——A 与 C，仍不含 B
        var all = ok(report.worklist("all", null, null, null, null, null, null));
        ids = ids(all);
        assertTrue(ids.contains(a) && ids.contains(c), "all 档应含 A 与 C");
        assertFalse(ids.contains(b), "all 档仍是「未诊断」口径，不含 B");

        // diagnosed：这是本版要打开的那扇门——已签发的 B 在列表里，且带追溯要用的列
        var diagnosed = ok(report.worklist("diagnosed", null, null, null, null, null, null));
        assertEquals("diagnosed", diagnosed.get("scope"));
        Map<String, Object> rowB = find(diagnosed, b);
        assertNotNull(rowB, "diagnosed 档必须列出已签发的 B——报告抽屉的唯一入口就是这个列表");
        assertNotNull(rowB.get("report_issued_at"), "已签发标本的 report_issued_at 必须回带");
        assertNotNull(rowB.get("diagnosed_at"));
        assertNotNull(rowB.get("first_signed_at"));
        assertNull(rowB.get("second_signed_at"), "B 刻意缺复签");
        assertEquals(tag + "d1医生", rowB.get("pathologist_name"));
        assertFalse(ids(diagnosed).contains(a), "diagnosed 档不含未诊断的 A");
        assertFalse(ids(diagnosed).contains(c));

        // any + 关键词：拿着病理号追溯，不用先知道它诊断没诊断
        var byPathNo = ok(report.worklist("any", null, null, pathNo("B"), null, null, null));
        assertEquals("any", byPathNo.get("scope"));
        assertEquals(Set.of(b), ids(byPathNo), "按病理号搜 B 应恰好命中 B");
        var byName = ok(report.worklist("any", null, null, "可达性患者" + tag, null, null, null));
        assertEquals(Set.of(a, b, c), ids(byName), "按患者名搜应把三个标本都列出（不分诊断状态）");

        // diagnosed 的日期窗按 coalesce(report_issued_at, diagnosed_at)：B 今天签发
        LocalDate today = BusinessDates.today();
        var inWindow = ok(report.worklist("diagnosed", null, null, null,
                today.minusDays(1).toString(), today.plusDays(1).toString(), null));
        assertTrue(ids(inWindow).contains(b), "今天签发的 B 应落在含今天的窗口里");
        assertEquals(today.minusDays(1).toString(), inWindow.get("from"));
        var outWindow = ok(report.worklist("diagnosed", null, null, null,
                today.minusDays(300).toString(), today.minusDays(200).toString(), null));
        assertFalse(ids(outWindow).contains(b), "远在过去的窗口不该命中今天签发的 B");

        // 新参数非法 → 5800（既有参数的错误码一个没动）
        assertEquals(5800, report.worklist("diagnosed", null, null, null, "2026-01-01", null, null).getCode(),
                "只给 from 不给 to 必须返 5800");
        assertEquals(5800, report.worklist("any", null, null, null, "bad", "2026-01-02", null).getCode());
        assertEquals(5800, report.worklist("any", null, null, null, "2026-02-01", "2026-01-01", null).getCode(),
                "倒置区间必须返 5800");

        // 走到抽屉：reports 端点对已签发的 B 给出 process 轨迹（放开列表后轨迹真的能看到）
        var reports = ok(report.reports(b, null));
        var process = rows(reports, "process");
        assertTrue(process.stream().anyMatch(n -> "ISSUE".equals(n.get("node"))),
                "报告全景的 process 应含 ISSUE 节点：" + process);
    }

    // =====================================================================================
    // ② 大体所见独立查看（2530）
    // =====================================================================================

    @Test
    void grossFindingIsReadableBeforeDiagnosis() {
        var view = ok(process.grossingView(a));
        assertEquals(Boolean.TRUE, view.get("grossFindingPresent"));
        assertEquals("灰白组织一块 " + tag, view.get("grossFinding"),
                "取材时写的大体所见必须在未诊断时就能读回——此前它被诊断抽屉的 hasPrimary 挡住");
        assertNull(view.get("diagnosedAt"), "A 尚未诊断");
        var blocks = rows(view, "blocks");
        assertEquals(1, blocks.size());
        assertEquals(blockA, idOf(blocks.get(0)));
        assertEquals("肿物中心", blocks.get(0).get("tissue_desc"));
        assertEquals(1, rows(view, "grossingEvents").size(), "取材打点应恰好一条");

        // C 没录过大体所见：present=false、内容 null，而不是空串或「—」
        var empty = ok(process.grossingView(c));
        assertEquals(Boolean.FALSE, empty.get("grossFindingPresent"));
        assertNull(empty.get("grossFinding"));
        assertTrue(rows(empty, "blocks").isEmpty());

        // 已诊断的 B 同样能看（不分诊断状态），diagnosedAt 回带供对时间线
        var viewB = ok(process.grossingView(b));
        assertEquals(Boolean.TRUE, viewB.get("grossFindingPresent"));
        assertNotNull(viewB.get("diagnosedAt"));

        assertEquals(5220, process.grossingView(-1L).getCode(), "查无此标本沿用既有 5220");
    }

    // =====================================================================================
    // ③ 全院技术医嘱清单（2563）
    // =====================================================================================

    @Test
    void hospitalWideTechOrderListFiltersByStatusTypeTimeAndKeyword() {
        // 不传任何参数：既有行为——全院清单默认只看 ORDERED
        var legacy = ok(report.techOrders(null, null, null, null, null, null, null, null, null));
        assertEquals("ORDERED", legacy.get("status"));
        assertTrue(techIds(legacy).contains(techOnB), "待执行的免疫组化应在默认清单里");
        assertFalse(techIds(legacy).contains(techOnA), "已完成的深切不该混进默认（ORDERED）清单");

        // status=ALL：两条都在，且行带 tech_type_name / hours_since_ordered
        var all = ok(report.techOrders(null, "ALL", null, null, null, null, null, null, null));
        assertEquals("ALL", all.get("status"));
        assertTrue(techIds(all).containsAll(Set.of(techOnA, techOnB)));
        Map<String, Object> rowB = techRow(all, techOnB);
        assertEquals("免疫组化", rowB.get("tech_type_name"));
        assertNotNull(rowB.get("hours_since_ordered"));

        // 按类型
        var deep = ok(report.techOrders(null, "ALL", "deep_cut", null, null, null, null, null, null));
        assertEquals(Set.of(techOnA), own(techIds(deep)), "techType=DEEP_CUT 应只命中 A 的深切");

        // 按完成时刻的日期窗：A 今天完成
        LocalDate today = BusinessDates.today();
        var doneToday = ok(report.techOrders(null, "DONE", null, null, null, "DONE",
                today.minusDays(1).toString(), today.plusDays(1).toString(), null));
        assertTrue(techIds(doneToday).contains(techOnA));
        assertEquals("DONE", doneToday.get("dateField"));
        var donePast = ok(report.techOrders(null, "DONE", null, null, null, "DONE",
                today.minusDays(300).toString(), today.minusDays(200).toString(), null));
        assertFalse(techIds(donePast).contains(techOnA));

        // 关键词（病理号 / 项目名）与加急
        assertEquals(Set.of(techOnB), own(techIds(ok(report.techOrders(
                null, "ALL", null, null, pathNo("B"), null, null, null, null)))));
        assertEquals(Set.of(techOnB), own(techIds(ok(report.techOrders(
                null, "ALL", null, null, "CK7", null, null, null, null)))));
        assertEquals(Set.of(techOnB), own(techIds(ok(report.techOrders(
                null, "ALL", null, true, null, null, null, null, null)))), "urgentOnly 应只留加急的 B");

        // 传 specimenId 的既有分支不受影响：默认全状态
        var forA = ok(report.techOrders(a, null, null, null, null, null, null, null, null));
        assertNull(forA.get("status"));
        assertEquals(Set.of(techOnA), techIds(forA));

        // 错误码：类型 / 状态非法沿用既有 5267，新参数非法 5800
        assertEquals(5267, report.techOrders(null, "ALL", "BOGUS", null, null, null, null, null, null).getCode());
        assertEquals(5267, report.techOrders(null, "BOGUS", null, null, null, null, null, null, null).getCode());
        assertEquals(5800, report.techOrders(null, null, null, null, null, "BOGUS", null, null, null).getCode());
        assertEquals(5800, report.techOrders(null, null, null, null, null, null, "2026-01-01", null, null).getCode());
        assertEquals(5800, report.techOrders(null, null, null, null, null, null, "x", "2026-01-01", null).getCode());
    }

    // =====================================================================================
    // ④ 流转异常与轨迹（2522）
    // =====================================================================================

    @Test
    void anomaliesSurfaceStalledAndSkippedNodesAndTrailShowsNodes() {
        // 超时未流转：C 核收 3 天无任何节点，阈值 24 小时命中、100 小时不命中；
        // A 最近节点是刚才的 STAIN，不算停滞；B 已签发，不进 STALLED
        var stalled24 = ok(process.anomalies("STALLED", null, 24, null, null, null));
        Set<Long> ids = specimenIds(stalled24);
        assertTrue(ids.contains(c), "核收 3 天无流转的 C 必须被 24 小时阈值筛出");
        assertFalse(ids.contains(a), "A 刚染色，不是停滞");
        assertFalse(ids.contains(b), "已签发的 B 不进 STALLED");
        Map<String, Object> rowC = anomalyRow(stalled24, c);
        assertEquals("超时未流转", rowC.get("kind_name"));
        assertNull(rowC.get("last_node"), "C 一个节点都没有，last_node 应为 null（锚点回落核收时刻）");
        assertTrue(((Number) rowC.get("hours")).doubleValue() >= 71.0, "停滞小时数应约 72：" + rowC.get("hours"));
        assertFalse(specimenIds(ok(process.anomalies("STALLED", null, 100, null, null, null))).contains(c),
                "100 小时阈值下 72 小时的 C 不该命中");

        // 跳节点三类（默认最近 30 天窗）
        assertTrue(specimenIds(ok(process.anomalies("SECTION_WITHOUT_EMBED", null, null, null, null, null))).contains(a),
                "A 的蜡块无包埋记录却已切片");
        assertTrue(specimenIds(ok(process.anomalies("DIAGNOSED_WITHOUT_STAIN", null, null, null, null, null))).contains(b),
                "B 无任何切片却已写诊断");
        var noSign = ok(process.anomalies("ISSUED_WITHOUT_DOUBLE_SIGN", null, null, null, null, null));
        assertTrue(specimenIds(noSign).contains(b));
        assertTrue(String.valueOf(anomalyRow(noSign, b).get("detail")).contains("复诊签名"),
                "缺的是复诊签名，detail 得点名：" + anomalyRow(noSign, b));
        assertFalse(specimenIds(noSign).contains(a));

        // ALL：counts 四类齐全且不受 limit 截断，日期窗缺省回显
        var all = ok(process.anomalies(null, null, null, null, null, null));
        assertEquals("ALL", all.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) all.get("counts");
        assertEquals(Set.of("STALLED", "SECTION_WITHOUT_EMBED", "DIAGNOSED_WITHOUT_STAIN", "ISSUED_WITHOUT_DOUBLE_SIGN"),
                counts.keySet());
        assertTrue(((Number) counts.get("STALLED")).longValue() >= 1);
        assertTrue(((Number) counts.get("ISSUED_WITHOUT_DOUBLE_SIGN")).longValue() >= 1);
        assertNotNull(all.get("from"));
        assertNotNull(all.get("to"));
        // 远在过去的窗口：跳节点三类不命中，STALLED 不受窗口约束仍命中 C
        var past = ok(process.anomalies(null, null, 24,
                BusinessDates.today().minusDays(300).toString(), BusinessDates.today().minusDays(200).toString(), null));
        assertTrue(specimenIds(past).contains(c), "STALLED 不受日期窗约束——积压不能被窗口藏起来");
        assertFalse(specimenIds(past).contains(b));
        assertFalse(specimenIds(past).contains(a));
        // 按标本类别
        assertTrue(specimenIds(ok(process.anomalies("STALLED", "ROUTINE", 24, null, null, null))).contains(c));
        assertFalse(specimenIds(ok(process.anomalies("STALLED", "FROZEN", 24, null, null, null))).contains(c));

        // 参数非法 → 5801
        assertEquals(5801, process.anomalies("BOGUS", null, null, null, null, null).getCode());
        assertEquals(5801, process.anomalies(null, null, 0, null, null, null).getCode());
        assertEquals(5801, process.anomalies(null, null, null, "2026-01-01", null, null).getCode());
        assertEquals(5801, process.anomalies(null, null, null, "2026-02-01", "2026-01-01", null).getCode());

        // 轨迹：B 的节点按时间序 FIRST_SIGN → ISSUE，带中文名与相邻间隔；本标本异常两项
        var trailB = ok(process.trail(b, null));
        var nodes = rows(trailB, "nodes");
        List<String> order = nodes.stream().map(n -> String.valueOf(n.get("node"))).toList();
        assertTrue(order.indexOf("FIRST_SIGN") >= 0 && order.indexOf("ISSUE") > order.indexOf("FIRST_SIGN"),
                "B 的轨迹应含 FIRST_SIGN 且 ISSUE 在其后：" + order);
        // v58 起技术医嘱进流转节点：夹具在签发后下达了 IHC，最后节点是 TECH_ORDER，且须带中文名（不能是 null/英文码）
        assertEquals("下达特检医嘱", nodes.get(nodes.size() - 1).get("node_name"));
        assertEquals("TECH_ORDER", trailB.get("lastNode"));
        assertTrue(order.indexOf("ISSUE") == order.size() - 2, "ISSUE 仍是倒数第二个节点：" + order);
        assertNotNull(trailB.get("hoursSinceLastNode"));
        Set<String> kindsB = new HashSet<>();
        for (var x : rows(trailB, "anomalies")) kindsB.add(String.valueOf(x.get("kind")));
        assertEquals(Set.of("DIAGNOSED_WITHOUT_STAIN", "ISSUED_WITHOUT_DOUBLE_SIGN"), kindsB);

        // A 的轨迹：GROSSING → SECTION → STAIN，第二个节点起有 hours_since_prev
        var trailA = ok(process.trail(a, null));
        var nodesA = rows(trailA, "nodes");
        List<String> nodeSeqA = nodesA.stream().map(n -> String.valueOf(n.get("node"))).toList();
        assertEquals(List.of("GROSSING", "SECTION", "STAIN"),
                nodeSeqA.stream().filter(n -> !n.startsWith("TECH_")).toList(), "制片节点序不变：" + nodeSeqA);
        // v58：夹具在染色后下达并完成了深切医嘱，轨迹多出两类技术节点，且带中文名
        assertEquals(List.of("TECH_ORDER", "TECH_DONE"), nodeSeqA.stream().filter(n -> n.startsWith("TECH_")).toList());
        assertEquals(List.of("下达特检医嘱", "确认完成特检医嘱"), nodesA.stream()
                .filter(n -> String.valueOf(n.get("node")).startsWith("TECH_"))
                .map(n -> String.valueOf(n.get("node_name"))).toList());
        assertNull(nodesA.get(0).get("hours_since_prev"));
        assertNotNull(nodesA.get(1).get("hours_since_prev"));
        assertEquals(Set.of("SECTION_WITHOUT_EMBED"),
                rows(trailA, "anomalies").stream().map(x -> String.valueOf(x.get("kind"))).collect(java.util.stream.Collectors.toSet()));

        // C：没有节点也要给出一条能看的轨迹（空 nodes、锚点回落核收）
        var trailC = ok(process.trail(c, null));
        assertTrue(rows(trailC, "nodes").isEmpty());
        assertNull(trailC.get("lastNode"));
        assertNotNull(trailC.get("hoursSinceLastNode"), "无节点时距今小时数按核收时刻算");

        assertEquals(5220, process.trail(-1L, null).getCode());
        assertEquals(5801, process.trail(a, 0).getCode());
    }

    // ==================== 夹具与取值 ====================

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    private Long userId(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private String pathNo(String suffix) {
        return tag + "-" + suffix;
    }

    /** 一条门诊医嘱 + 一份已核收标本；核收时刻由 SQL 表达式现算，不写字面量 */
    private long specimen(String suffix, String receivedExpr) {
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag + suffix);
        return jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent)
                values (?, 1, ?, ?, 'ROUTINE', ?, 'RECEIVED', %s - interval '1 hour', %s, false) returning id
                """.formatted(receivedExpr, receivedExpr), Long.class,
                orderId, "PB" + tag + suffix, pathNo(suffix), "标本" + suffix);
    }

    private static Map<String, Object> ok(R<Map<String, Object>> r) {
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
        Object v = body.get(key);
        assertNotNull(v, "返回体缺少 " + key + "：" + body.keySet());
        return (List<Map<String, Object>>) v;
    }

    private static long idOf(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private static Set<Long> ids(Map<String, Object> body) {
        Set<Long> s = new HashSet<>();
        for (var r : rows(body, "items")) s.add(idOf(r));
        return s;
    }

    private Map<String, Object> find(Map<String, Object> body, long specimenId) {
        return rows(body, "items").stream().filter(r -> idOf(r) == specimenId).findFirst().orElse(null);
    }

    private static Set<Long> techIds(Map<String, Object> body) {
        return ids(body);
    }

    private static Map<String, Object> techRow(Map<String, Object> body, long id) {
        return rows(body, "items").stream().filter(r -> idOf(r) == id).findFirst().orElseThrow();
    }

    /** 全院清单里只看本用例造的两条（库里可能有别的用例 / 种子留下的技术医嘱） */
    private Set<Long> own(Set<Long> ids) {
        Set<Long> s = new HashSet<>(ids);
        s.retainAll(Set.of(techOnA, techOnB));
        return s;
    }

    private static Set<Long> specimenIds(Map<String, Object> body) {
        Set<Long> s = new HashSet<>();
        for (var r : rows(body, "items")) s.add(((Number) r.get("specimen_id")).longValue());
        return s;
    }

    private static Map<String, Object> anomalyRow(Map<String, Object> body, long specimenId) {
        return rows(body, "items").stream()
                .filter(r -> ((Number) r.get("specimen_id")).longValue() == specimenId)
                .findFirst().orElseThrow();
    }
}
