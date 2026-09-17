package cn.hip.server;

import cn.hip.medtech.web.PathQcController;
import cn.hip.medtech.web.PathologyController;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <h1>v62 车道 C（2576 复核两条）：同一块看板上两个「蜡块数」正名 + 按日归集可回溯变动如实</h1>
 *
 * <h2>修复前的反向事实（v62 地基 bd1a453，反驳者原话经主控实测坐实）</h2>
 * <ol>
 *   <li><b>同一块看板、同一个中文表头「蜡块数」，在两条指标里是两个口径</b>：
 *       {@code WORKLOAD_BLOCK} 的 {@code blocks} 是 {@code count(*) from path_block}、锚
 *       {@code coalesce(embedded_at, created_at)}（当日<b>产出</b>蜡块数）；{@code WORKLOAD_SLIDE} 的
 *       {@code blocks} 是 {@code count(distinct sl.block_id)}、锚 {@code coalesce(stained_at, created_at)}
 *       （当日染色切片<b>涉及</b>的蜡块数、去重）。两者经同一张扁平字典（{@code zh(col)} / 前端
 *       {@code ZH[col]}，不带指标上下文）渲染成同一个中文表头，两条指标在同一页顺序渲染——演示数据下
 *       一屏之内就是「蜡块数 6」与「蜡块数 3」，页面上没有一行字能回答「到底做了几块」。
 *       同型的还有 {@code molecular}→「分子病理」（{@code WORKLOAD_REGISTER} 数的是
 *       {@code specimen_type='MOLECULAR'} 的<b>标本条数</b>，{@code WORKLOAD_SLIDE} 数的是
 *       {@code stain_type='MOLECULAR'} 的<b>切片张数</b>）。
 *       {@link #twoBlockColumnsAreRenamedAndNoLongerShareAHeader} 与 {@link #molecularIsRenamedOnBothSides}
 *       把「两列已正名、中文互不相同、三条指标除日期外零同名列」钉死。</li>
 *   <li><b>「蜡块产出数」的按日归集是代填 + 可回溯变动，而 caveat 一个字没提</b>：未包埋的蜡块被拿
 *       {@code created_at} 顶替 {@code embedded_at}，先算进取材日的「产出」；等包埋登记落下去，同一块蜡块
 *       就从取材日消失、跳到包埋日——<b>同一个已关闭区间今天导与明天导的按日数不一样（某天变小），
 *       导出的历史报表不可复现</b>。v61 刚给 {@code WORKLOAD_DEPT} 存量三列写明的正是同一类缺陷，
 *       BLOCK / SLIDE 原样漏掉，且全仓无任何测试盯它（{@code server/src/test} 里 grep
 *       {@code WORKLOAD_BLOCK} 零命中）。{@link #closedIntervalDayCountsShrinkAfterEmbeddingIsRegistered}
 *       用一块「先落在已关闭区间、随后才补登包埋」的蜡块实证这个差异，并断言 caveat 里读得到这句说明。</li>
 * </ol>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：蜡块 / 切片的时刻由端点按库端 {@code now()} 落，「上期」由
 * {@code update … created_at = now() - interval '10 days'} 这一条<b>相对表达式</b>造出。
 * 窗口一律用 {@link BusinessDates#today()} 派生并留足 ±2 天余量——Java 侧「今天」按
 * {@code Asia/Shanghai}、库端 {@code now()::date} 按会话时区，两者可能差一天，
 * 钉死某一天那一行会在跨日时段炸（本仓已为时间字面量付过三次学费）。
 * 一切数值断言都取<b>同一窗口内的前后差</b>，不取绝对值，故与库里既有数据无关。事务回滚，库里不留痕。
 *
 * <h2>v63 追加（2576 复核第七条：同一页对同一个数不得给出两个相反的结论）</h2>
 * <p><b>修复前的反向事实</b>（v62 交付后复核，反驳者原话）：v62 只把「这个数事后会变、导出的历史报表
 * 不可复现」写进了 {@code WORKLOAD_BLOCK} 指标自己的 caveat，而同一块看板上<b>位置更靠前、且被平台
 * 自己的顶层口径（{@code DATA_CAVEAT}）点名「请先看」</b>的「字段录入覆盖率」段，用的是同一条谓词、
 * 同一个 {@code count(*) from path_block}，屏上却仍写着这个数「蜡块产出量仍可信（建档即产出）」
 * （{@code PathQcController.java:683}）——<b>同一页对同一个蜡块产出数给出两个相反的结论，
 * 而说反话的那一句先上屏</b>。而 {@link #closedIntervalDayCountsShrinkAfterEmbeddingIsRegistered()}
 * 恰恰实证了这个数会变小，即「仍可信」那句被平台自己的测试证伪、却仍留在同一块屏幕上。
 * {@link #coverageBlockNoteAndWorkloadBlockCaveatGiveTheSameConclusion()} 把这一条钉住：
 * 两段文本必须<b>引用同一个常量</b>（不是各写一段散文），且剥注释后的源码里不得再有「产出量仍可信」
 * 这类相反结论——对照组用的正是修复前那句原话与<b>未剥注释的同一份源码</b>（本文件的注释里逐字引用了它）。
 *
 * <h2>v64 追加（2576 复核三条之①②：合计行把整个统计区间标成「当日」）</h2>
 * <p><b>修复前的反向事实</b>（v63 交付形态，复核者原话经主控实测坐实）：合计行那段 SQL 落窗的是
 * <b>整个统计区间</b>（默认 30 天），四列却与按日表<b>共用同一套列名</b>
 * （{@code blocks_produced} / {@code embedded} / {@code specimens} / {@code blocks_per_specimen}），
 * 而这套列名的中文写的是「<b>当日</b>产出蜡块数」——于是同一屏上出现三处同名不同口径：
 * 覆盖率段 39、合计行 39、按日表某一天 3，指标自己的 caveat 还逐字把这一列定义成
 * 「这一天<b>产出</b>了几块蜡块」，等于用口径说明给合计数背书成单日数。
 * 同格的「涉及标本数」「蜡块/标本」同病：合计给的是<b>整窗去重</b>与<b>整窗比值</b>，
 * 而按平台自己认定的常规形态（{@link cn.hip.medtech.web.PathQcController#BLOCK_DAY_CAVEAT}：脱水过夜跨日、
 * 补取材隔几天再出块），同一份标本的蜡块必然分落多个 {@code stat_day} 行，于是
 * <b>合计的涉及标本数小于按日各行之和、合计的蜡块/标本不等于任何一行</b>——三个数当时都挂在
 * 被 caveat 定义成「当日」的表头下，屏上没有一个字解释。
 * <p>v62 的守卫只断言了合计行「同步正名」成 {@code blocks_produced} 这个<b>键名</b>，没有断言它的中文与合计口径相符；
 * v63 的前端守卫只比对覆盖率四段内部的表头，根本没碰 summary。
 * {@link #summaryColumnsAreWindowCaliberAndNeverShareANameWithTheDailyTable()} 三样一起钉：
 * 两套列名互不相交、合计中文一律「本期」且不含「当日」、合计与按日的关系（相等 / 小于 / 不等）由真实库态实证。
 *
 * <h2>v65 车道 C 追加（2576 复核两条，<b>两条都是 v64 修复自身带进来的假话</b>）</h2>
 * <ol>
 *   <li><b>合计格标题被无条件说给每一条指标</b>：v64 新加的 {@code summaryTitle} 是前端一个
 *       <b>不带指标参数的全局 computed</b>，末句写死「下面那张表才按日拆分」，却绑在逐指标的 v-for 里——
 *       按送检科室 / 技术类型 / 标本类别 / 染色类型分组的五条指标，表里连日期列都没有。
 *       {@link #everySummaryTitleDeclaresTheDimensionItsOwnTableIsGroupedBy()} 逐指标穷举，
 *       拿真返回的行验出真实分组维度再核标题。</li>
 *   <li><b>两条「必然」与库内事实相反</b>：v64 宣称「本期涉及标本数(去重)」必然小于按日各行之和、
 *       「本期蜡块/标本」不等于按日表里的任何一行，而这两句只在跨统计日时成立；演示脚本造出来的库态
 *       只有当天一根柱，两数逐字相等。
 *       {@link #theSummaryVsDailyNoteTellsTheTruthInBothLibraryStates()} 造两种库态各核一遍，
 *       v64 原话在「一根柱」下必须被判红、在跨日下不许被咬（它在那里恰好是真的）。</li>
 * </ol>
 * <p>两条都是「一句话里含有断言，而那个状态在某些真实路径上不成立」——上一轮的守卫全绿，
 * 因为它们盯的是「接没接上」（键名、有没有标题），不盯「那句话是不是真的」。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V62BlockCaliberTest {

    private static final String CONTROLLER = "modules/medtech/src/main/java/cn/hip/medtech/web/PathQcController.java";
    private static final String FORMAT_TS = "frontend/shell/src/views/medtech/pathology/format.ts";

    /** 正名后的两列：一个 count(*)（当日产出）、一个 count(distinct block_id)（当日染色涉及、去重） */
    private static final String BLOCKS_PRODUCED = "blocks_produced";
    private static final String BLOCKS_STAINED = "blocks_stained";
    private static final String MOLECULAR_SPECIMENS = "molecular_specimens";
    private static final String MOLECULAR_SLIDES = "molecular_slides";

    /**
     * v64（2576 复核）正名后的<b>合计四列</b>：落窗的是整个统计区间，中文一律「本期…」。
     * 命名照平台既有体例（{@code WORKLOAD_DEPT} 的 {@code issued_of_registered} =「本期登记中已签发」）。
     */
    private static final String BLOCKS_PRODUCED_IN_PERIOD = "blocks_produced_in_period";
    private static final List<String> PERIOD_COLS = List.of(
            BLOCKS_PRODUCED_IN_PERIOD, "embedded_in_period",
            "specimens_in_period", "blocks_per_specimen_in_period");

    /** 与之配对的<b>按日四列</b>：一行就是一个 stat_day，中文一律「当日…」 */
    private static final List<String> DAY_COLS = List.of(
            BLOCKS_PRODUCED, "embedded", "specimens_of_day", "blocks_per_specimen_of_day");

    /**
     * 修复前合计行那段 SQL 的逐字原文（v63 交付形态，{@code PathQcController.java:1201-1208}）——
     * 源码扫描的<b>活对照组</b>：同一个别名匹配器喂它，必须抓到按日列名。
     */
    private static final String LEGACY_SUMMARY_SQL = """
            select count(*)                                                as blocks_produced,
                   count(*) filter (where b.embedded_at is not null)       as embedded,
                   count(distinct b.specimen_id)                           as specimens,
                   round(count(*)::numeric
                         / nullif(count(distinct b.specimen_id), 0), 2)    as blocks_per_specimen
            from path_block b
            where {wb}
            """;

    /**
     * 修复前 caveat 里那三句的逐字原文（v63 交付形态）——<b>活对照组</b>：
     * 同一个「口径说明里直呼内部键名」的匹配器喂它必须命中，否则下面那条 assertFalse 什么也不说明。
     */
    private static final String LEGACY_BLOCK_CAVEAT =
            " blocks_produced（当日产出蜡块数）是 count(*) from path_block、锚"
            + " coalesce(embedded_at, created_at)：这一天**产出**了几块蜡块。"
            + " blocks_per_specimen 是当日蜡块数 / 当日涉及标本数，"
            + "**不是「每份标本平均取几块」**——同一标本的蜡块可能跨日建，两端分母不同。"
            + "embedded 一列是该行里已录包埋时刻的条数，"
            + "它与 blocks_produced 差得越大，该行后面越可能还会变。";

    /**
     * 「口径说明里直呼内部 JSON 键」的形态。<b>刻意按「键名 + 紧随其后的定语」匹配</b>，
     * 不写成「含 blocks 二字即红」——那样会把「两列都是「蜡块数」」这类合法中文一起咬掉。
     */
    private static final Pattern RAW_KEY_IN_CAVEAT = Pattern.compile(
            "\\b(blocks_produced|blocks_stained|blocks_per_specimen|specimens|embedded)\\b\\s*(（|\\(|是|一列|数的是)");

    /** 修复前两列共用的那一个键与那一个中文——反向事实，出现即是回退 */
    private static final String OLD_SHARED_COL = "blocks";
    private static final String OLD_SHARED_ZH = "蜡块数";
    private static final String OLD_SHARED_MOLECULAR_ZH = "分子病理";

    @Autowired PathologyController pathology;
    @Autowired PathologyProcessController process;
    @Autowired PathQcController pathQc;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc;
    private long regId;

    @BeforeEach
    void setUp() {
        tag = "V62B" + Long.toHexString(System.nanoTime());
        doc = userAuth(tag + "d1");
        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "蜡块口径科" + tag, tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "蜡块口径患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
    }

    // =====================================================================================
    // (a) 两个「蜡块数」已正名：列名、中文互不相同，且两个数确实不相等
    // =====================================================================================

    @Test
    void twoBlockColumnsAreRenamedAndNoLongerShareAHeader() {
        // 含今天的窗（留 ±1 天余量：库端日期与 Java 侧「今天」可能差一天）
        String from = BusinessDates.today().minusDays(1).toString();
        String to = BusinessDates.today().plusDays(1).toString();

        long producedBefore = windowSum("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED);
        long stainedBefore = windowSum("WORKLOAD_SLIDE", from, to, BLOCKS_STAINED);
        long slidesBefore = windowSum("WORKLOAD_SLIDE", from, to, "slides");

        // 演示脚本的形态：一份标本取材 2 块，切片只从第一块出——产出 2 块、染色涉及 1 块
        long sid = registered("a1", "ROUTINE");
        List<Long> blockIds = gross(sid, "肿物中心 " + tag, "肿物周边 " + tag);
        assertEquals(2, blockIds.size());
        var sl = process.slides(new SlideReq(blockIds.get(0), 2, "HE", null, null), doc);
        assertEquals(0, sl.getCode(), sl.getMessage());

        long produced = windowSum("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED) - producedBefore;
        long stained = windowSum("WORKLOAD_SLIDE", from, to, BLOCKS_STAINED) - stainedBefore;
        long slides = windowSum("WORKLOAD_SLIDE", from, to, "slides") - slidesBefore;
        assertEquals(2L, produced, BLOCKS_PRODUCED + " 是 count(*)：这一天产出了 2 块蜡块");
        assertEquals(2L, slides, "这一天出了 2 张切片");
        assertEquals(1L, stained, BLOCKS_STAINED + " 是 count(distinct block_id)：2 张切片只来自 1 块蜡块");
        assertNotEquals(produced, stained,
                "两列在同一块看板上并排显示、同一天就是两个数（演示数据里是 6 与 3）——"
                        + "修复前它们同名 " + OLD_SHARED_COL + "、同中文「" + OLD_SHARED_ZH + "」，评委无从分辨");

        // 列名：两条指标的汇总行与合计行都已正名，旧的共用键一个也不许剩
        Set<String> blockCols = rowCols("WORKLOAD_BLOCK", from, to);
        Set<String> slideCols = rowCols("WORKLOAD_SLIDE", from, to);
        assertTrue(blockCols.contains(BLOCKS_PRODUCED) && !blockCols.contains(OLD_SHARED_COL),
                "WORKLOAD_BLOCK 的蜡块数列须是 " + BLOCKS_PRODUCED + "：" + blockCols);
        assertTrue(slideCols.contains(BLOCKS_STAINED) && !slideCols.contains(OLD_SHARED_COL),
                "WORKLOAD_SLIDE 的蜡块数列须是 " + BLOCKS_STAINED + "：" + slideCols);
        // v64（2576 复核）：合计行**不再**与按日行共用这个键——它落窗的是整个统计区间，不是任何一天。
        // 两套列名、两套中文与三者的数量关系由 summaryColumnsAreWindowCaliberAndNeverShareANameWithTheDailyTable 钉死。
        assertTrue(summaryCols("WORKLOAD_BLOCK", from, to).contains(BLOCKS_PRODUCED_IN_PERIOD),
                "合计行同步正名成窗口口径：" + summaryCols("WORKLOAD_BLOCK", from, to));
        assertFalse(summaryCols("WORKLOAD_BLOCK", from, to).contains(BLOCKS_PRODUCED),
                "**合计行不得再叫按日列那个名字**（它是整个统计区间的合计，不是任何一天）："
                        + summaryCols("WORKLOAD_BLOCK", from, to));

        // 三条指标除「日期」外不得有同名列——同名即是同一个中文表头下的两个口径
        // 修复前：BLOCK ∩ SLIDE = {stat_day, blocks}、REGISTER ∩ SLIDE = {stat_day, molecular}
        Set<String> regCols = rowCols("WORKLOAD_REGISTER", from, to);
        assertOnlyStatDayInCommon("WORKLOAD_BLOCK", blockCols, "WORKLOAD_SLIDE", slideCols);
        assertOnlyStatDayInCommon("WORKLOAD_REGISTER", regCols, "WORKLOAD_SLIDE", slideCols);
        assertOnlyStatDayInCommon("WORKLOAD_REGISTER", regCols, "WORKLOAD_BLOCK", blockCols);
        // 活的对照组：三份列集合都得是真解析出来的（不是空集合恒真）
        assertTrue(blockCols.size() >= 5 && slideCols.size() >= 7 && regCols.size() >= 10,
                "列集合太小，说明没真查到行：" + blockCols + " / " + slideCols + " / " + regCols);

        // 中文：两侧字典都登记了、互不相同、且逐字一致
        assertLabelPair(BLOCKS_PRODUCED, "当日产出蜡块数");
        assertLabelPair(BLOCKS_STAINED, "当日染色涉及蜡块数(去重)");
        assertNotEquals(backendLabel(BLOCKS_PRODUCED), backendLabel(BLOCKS_STAINED),
                "两个「蜡块数」的中文必须互不相同（修复前同为「" + OLD_SHARED_ZH + "」）");

        // CSV 表头同源：导出文件一离开页面就只剩表头，这里不能再出现一个孤零零的「蜡块数」字段
        assertCsvHeader("WORKLOAD_BLOCK", from, to, "当日产出蜡块数");
        assertCsvHeader("WORKLOAD_SLIDE", from, to, "当日染色涉及蜡块数(去重)");
    }

    // =====================================================================================
    // (b) 按日归集可回溯变动：同一个已关闭区间，补登包埋之后再查就少了一块
    // =====================================================================================

    @Test
    void closedIntervalDayCountsShrinkAfterEmbeddingIsRegistered() {
        long sid = registered("b1", "ROUTINE");
        long blockId = gross(sid, "未包埋的一块 " + tag).get(0);
        assertNotEmbedded(blockId);

        // 把建档时刻推到 10 天前：这块蜡块于是落进一个**已经关闭**的历史区间，
        // 而包埋登记（下面那一步）的时刻由库端 now() 落在今天——正是「取材在上期、包埋在本期」的现实形态。
        assertEquals(1, jdbc.update(
                "update path_block set created_at = now() - interval '10 days' where id = ?", blockId));

        // 已关闭区间 [今天-12, 今天-8]：两端各留 2 天余量，库端日期与 Java 侧「今天」差一天也照样含得住
        String from = BusinessDates.today().minusDays(12).toString();
        String to = BusinessDates.today().minusDays(8).toString();
        String nowFrom = BusinessDates.today().minusDays(1).toString();
        String nowTo = BusinessDates.today().plusDays(1).toString();

        long closedBefore = windowSum("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED);
        long todayBefore = windowSum("WORKLOAD_BLOCK", nowFrom, nowTo, BLOCKS_PRODUCED);
        assertTrue(closedBefore >= 1,
                "未包埋的蜡块被拿 created_at 顶替 embedded_at，先算进了这个已关闭区间的「产出」：" + closedBefore);

        // 补登包埋：embedded_at 由库端 now() 落（今天），落在上面那个已关闭区间**之外**
        var emb = process.embed(blockId, null, doc);
        assertEquals(0, emb.getCode(), emb.getMessage());

        long closedAfter = windowSum("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED);
        long todayAfter = windowSum("WORKLOAD_BLOCK", nowFrom, nowTo, BLOCKS_PRODUCED);

        assertEquals(closedBefore - 1, closedAfter,
                "**同一个已关闭区间 [" + from + ", " + to + "]，补登包埋前后两次查得到的按日数不一样**："
                        + closedBefore + " → " + closedAfter
                        + "——昨天导出的历史报表今天再导就对不上，而修复前 caveat 一个字没提这件事");
        // 活的对照组：这一块不是凭空消失，而是**迁移**到了包埋日那一行——
        // 没有这一条，上面的「少了一块」也可能是查询坏了（空表恒真的另一种形态）
        assertEquals(todayBefore + 1, todayAfter,
                "同一块蜡块迁移到了包埋日那一行（含今天的窗口多了一块）：" + todayBefore + " → " + todayAfter);

        // caveat 必须把这件事讲清（与 v61 给 WORKLOAD_DEPT 存量列写的同一体例）
        String blockCaveat = caveatOf("WORKLOAD_BLOCK", from, to);
        for (String phrase : List.of("同一个已关闭区间", "不可复现", "包埋", "建档")) {
            assertTrue(blockCaveat.contains(phrase),
                    "WORKLOAD_BLOCK 的 caveat 缺「" + phrase + "」——修复前只写了「回落建档时刻」，"
                            + "一个字没提这个数事后会变：" + blockCaveat);
        }
        String slideCaveat = caveatOf("WORKLOAD_SLIDE", from, to);
        for (String phrase : List.of("同一个已关闭区间", "不可复现", "染色")) {
            assertTrue(slideCaveat.contains(phrase),
                    "WORKLOAD_SLIDE 同型（未染色的切片按建档时刻暂记）的 caveat 缺「" + phrase + "」："
                            + slideCaveat);
        }
        // 对照组：WORKLOAD_REGISTER 锚 collected_at，登记时刻不会事后迁移，不得跟着抄这句——
        // 若它也命中，说明上面两条只是抄中了一段全指标通用的样板文字，断言本身就是假的
        assertFalse(caveatOf("WORKLOAD_REGISTER", from, to).contains("不可复现"),
                "对照组：锚点不会迁移的指标不该写「不可复现」，否则上面两条断言只是抄中了通用样板");

        // 口径随 CSV 页脚下发：导出的表一转手就脱离页面，这句话必须跟着文件走
        assertTrue(pathQc.indicatorsCsv("WORKLOAD_BLOCK", from, to).contains("不可复现"),
                "CSV 页脚要带这句口径说明（toCsv 已把 caveat 写进页脚）");
    }

    // =====================================================================================
    // (b2) v64：合计行是窗口口径，与按日行既不同列名也不同中文；三个数的关系由真实库态实证
    // =====================================================================================

    @Test
    void summaryColumnsAreWindowCaliberAndNeverShareANameWithTheDailyTable() {
        // 窗口留足余量：库端日期与 Java 侧「今天」可能差一天，钉死某一天那一行会在跨日时段炸
        String from = BusinessDates.today().minusDays(7).toString();
        String to = BusinessDates.today().plusDays(1).toString();

        // ---- 先量基线：一切数值断言只取同一窗口内的前后差，与库里既有数据无关 ----
        long basePeriodBlocks = summaryLong("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED_IN_PERIOD);
        long basePeriodSpecimens = summaryLong("WORKLOAD_BLOCK", from, to, "specimens_in_period");
        long baseDayBlocks = windowSum("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED);
        long baseDaySpecimens = windowSum("WORKLOAD_BLOCK", from, to, "specimens_of_day");

        // ---- 库态：**同一份标本的两块蜡块分落两天**（脱水过夜跨日 / 补取材隔几天再出块的常规形态）----
        long sid = registered("f1", "ROUTINE");
        List<Long> ids = gross(sid, "同标本第一块 " + tag, "同标本第二块 " + tag);
        assertEquals(2, ids.size());
        assertEquals(1, jdbc.update(
                "update path_block set created_at = now() - interval '5 days' where id = ?", ids.get(0)),
                "夹具：把其中一块的建档时刻推到 5 天前，让同一份标本的蜡块落进两个 stat_day 行");

        long periodBlocks = summaryLong("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED_IN_PERIOD);
        long periodSpecimens = summaryLong("WORKLOAD_BLOCK", from, to, "specimens_in_period");
        long dayBlocks = windowSum("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED);
        long daySpecimens = windowSum("WORKLOAD_BLOCK", from, to, "specimens_of_day");

        // 计数列：合计 = 按日各行之和（没有去重，两边必然相等）——**活的对照组**，
        // 没有它，下面那条「合计小于各行之和」也可能只是查询坏了
        assertEquals(2L, periodBlocks - basePeriodBlocks, "本期产出蜡块数 +2");
        assertEquals(2L, dayBlocks - baseDayBlocks, "按日各行合起来也 +2");
        assertEquals(periodBlocks, dayBlocks,
                "**逐块计数的那一列，合计恰等于按日各行之和**：" + periodBlocks + " vs " + dayBlocks);

        // 去重列：合计只数一次，按日两行各数一次——**合计必然小于各行之和**，这正是复核者说「屏上没有一个字解释」的那件事
        assertEquals(1L, periodSpecimens - basePeriodSpecimens, "本期涉及标本数（整窗去重）只 +1");
        assertEquals(2L, daySpecimens - baseDaySpecimens, "按日各行的涉及标本数合起来 +2（同一份标本被两天各数一次）");
        assertTrue(periodSpecimens < daySpecimens,
                "**合计的涉及标本数小于按日各行之和，这是去重口径使然、不是对不上账**："
                        + periodSpecimens + " < " + daySpecimens);

        // 比值列：分子相同、分母不同（整窗去重 vs 按日各自去重）→ 合计不等于按日拼出来的任何一个值
        double periodRatio = summaryDouble("WORKLOAD_BLOCK", from, to, "blocks_per_specimen_in_period");
        assertEquals(round2((double) periodBlocks / periodSpecimens), periodRatio, 1e-9,
                "**本期蜡块/标本用的是整窗分母**，不是按日各行比值的平均");
        assertTrue(periodRatio > round2((double) dayBlocks / daySpecimens),
                "同一个分子配更小的分母，合计比值必然高于按日各行拼出来的比值——"
                        + "所以它**不等于按日表里的任何一行**：" + periodRatio);

        // ---- 两套列名：交集必须是空集 ----
        Set<String> rowCols = rowCols("WORKLOAD_BLOCK", from, to);
        Set<String> sumCols = summaryCols("WORKLOAD_BLOCK", from, to);
        assertTrue(rowCols.size() >= 5 && sumCols.size() >= 4,
                "活的对照组：两侧列集合都得是真解析出来的：" + rowCols + " / " + sumCols);
        var common = new LinkedHashSet<>(rowCols);
        common.retainAll(sumCols);
        assertEquals(Set.of(), common,
                "**合计行与按日行不得有同名列**——合计落窗整个统计区间、按日行才是「这一天」，"
                        + "同名即同一个中文表头下的两个口径（修复前这里是 "
                        + "[blocks_produced, embedded, specimens, blocks_per_specimen]）：" + common);
        assertTrue(sumCols.containsAll(PERIOD_COLS), "合计四列：" + sumCols);
        assertTrue(rowCols.containsAll(DAY_COLS), "按日四列：" + rowCols);

        // ---- 两套中文：合计一律「本期…」且**一个「当日」也不许有**；两侧字典逐字同源 ----
        for (String k : sumCols) {
            String label = backendLabel(k);
            assertNotNull(label, "合计列 " + k + " 没在后端 zh() 登记，CSV 与页面表头会直接显示英文键名");
            assertFalse(label.contains("当日"),
                    "**合计行的中文表头不得出现「当日」字样**：" + k + " =「" + label + "」——"
                            + "这一格是整个统计区间的合计，不是任何一天（修复前它逐字叫「当日产出蜡块数」）");
            assertTrue(label.startsWith("本期"),
                    "合计列的中文要自带窗口口径（照「本期登记中已签发」的体例）：" + k + " =「" + label + "」");
            assertEquals(label, frontendLabel(k), "前端 ZH." + k + " 须与后端 zh() 逐字一致");
        }
        for (String k : DAY_COLS) {
            String label = backendLabel(k);
            assertNotNull(label, "按日列 " + k + " 没在后端 zh() 登记");
            assertTrue(label.startsWith("当日"), "按日列的中文要自带单日口径：" + k + " =「" + label + "」");
            assertEquals(label, frontendLabel(k), "前端 ZH." + k + " 须与后端 zh() 逐字一致");
        }
        // 探针：同一个「含当日即红」的判据，喂修复前合计行用的那个键必须命中
        assertTrue(backendLabel(BLOCKS_PRODUCED).contains("当日"),
                "探针：修复前合计行用的正是这个键，它的中文含「当日」——上面那组 assertFalse 才说明得了事");

        // ---- caveat：合计与按日各自有口径说明，且把「对不上账其实是正常的」写出来 ----
        String caveat = caveatOf("WORKLOAD_BLOCK", from, to);
        // v65 车道 C（2576 复核）：「必然小于」「不等于按日表里的任何一行」两句已删——
        // 它们只在跨统计日时成立，演示库态（当天一根柱）下两数逐字相等。通例改成条件句，
        // 本次库态的实际关系由 summaryVsDailyNote 现算（见 theSummaryVsDailyNoteTellsTheTruthInBothLibraryStates）。
        for (String phrase : List.of("本期合计", "按日拆分", "本期产出蜡块数", "当日产出蜡块数",
                "本期涉及标本数(去重)", "当日涉及标本数(去重)", "本期蜡块/标本",
                "只在本区间内没有一份标本的蜡块落进两个以上统计日时才相等", "不是对不上账")) {
            assertTrue(caveat.contains(phrase),
                    "WORKLOAD_BLOCK 的 caveat 缺「" + phrase + "」——修复前它只讲「这一天…」，"
                            + "合计那一格没有任何一句口径说明：" + caveat);
        }
        // 反向事实：口径说明里不得再直呼内部键名（屏上那段字是给评委看的，不是给读代码的人看的）
        assertFalse(RAW_KEY_IN_CAVEAT.matcher(caveat).find(),
                "**口径说明里不得直呼内部 JSON 键**（屏上与 CSV 页脚都印这段字）：" + caveat);
        assertTrue(RAW_KEY_IN_CAVEAT.matcher(LEGACY_BLOCK_CAVEAT).find(),
                "探针：同一个匹配器喂 v63 交付时那三句原话必须命中：" + LEGACY_BLOCK_CAVEAT);

        // ---- 源码扫描：合计行那段 SQL 里不得再出现按日列名 ----
        String stripped = V57GrossKeepTest.stripComments(read(CONTROLLER));
        List<String> summaryAliases = sqlAliases(textBlockAfter(stripped, SUMMARY_ANCHOR));
        List<String> rowAliases = sqlAliases(textBlockAfter(stripped, ROWS_ANCHOR));
        assertTrue(summaryAliases.containsAll(PERIOD_COLS) && rowAliases.containsAll(DAY_COLS),
                "活的对照组：扫描器真解析出了两段 SQL 的别名：" + summaryAliases + " / " + rowAliases);
        assertEquals(List.of(), summaryAliases.stream().filter(DAY_COLS::contains).toList(),
                "**合计行的 SQL 里不得再出现按日列名**：" + summaryAliases);
        // 活的对照组：同一个别名匹配器喂修复前那段合计 SQL，必须抓到按日列名（否则上面那条 assertEquals 扫的是空集）
        assertEquals(List.of("blocks_produced", "embedded"),
                sqlAliases(LEGACY_SUMMARY_SQL).stream().filter(DAY_COLS::contains).toList(),
                "探针：v63 交付时的合计 SQL 正是拿按日列名当合计列名：" + sqlAliases(LEGACY_SUMMARY_SQL));

        // ---- CSV：导出的是按日行，表头一律「当日…」，不会再有一个含义是 30 天合计的「当日产出蜡块数」 ----
        assertCsvHeader("WORKLOAD_BLOCK", from, to, "当日产出蜡块数");
    }

    // =====================================================================================
    // (b2) v65 车道 C（2576 复核第一条）：合计格标题宣告的拆分维度 == 该指标真实的 group by
    // =====================================================================================

    /** 一条指标的汇总行**真实**按什么分组。期望值写在测试里，不从被测代码读回来（否则只是把实现抄一遍） */
    private record DimExpect(String column, String label, boolean byDay) {}

    /**
     * <b>逐指标穷举</b>：凡是有合计格的指标都要在这张表里有一行，新加指标不补这一行就红。
     * 值取自各指标 {@code rowsOf} 那段 SQL 的 group by（本文件另有一条断言拿真实返回的行验它）。
     */
    private static final Map<String, DimExpect> EXPECTED_DIMS = Map.ofEntries(
            Map.entry("SPECIMEN_RECEIVE", new DimExpect("specimen_type", "标本类别", false)),
            Map.entry("FIXATION", new DimExpect("specimen_type", "标本类别", false)),
            Map.entry("SLIDE_QUALITY", new DimExpect("stain_type", "染色类型", false)),
            Map.entry("WORKLOAD_DEPT", new DimExpect("dept_name", "送检科室", false)),
            Map.entry("WORKLOAD_TECH", new DimExpect("tech_type", "技术类型", false)),
            Map.entry("REPORT_ROUTINE", new DimExpect("issue_day", "报告签发日", true)),
            Map.entry("REPORT_FROZEN", new DimExpect("issue_day", "报告签发日", true)),
            Map.entry("REPORT_DOUBLE_SIGN", new DimExpect("issue_day", "报告签发日", true)),
            Map.entry("WORKLOAD_REGISTER", new DimExpect("stat_day", "统计日", true)),
            Map.entry("WORKLOAD_BLOCK", new DimExpect("stat_day", "统计日", true)),
            Map.entry("WORKLOAD_SLIDE", new DimExpect("stat_day", "统计日", true)),
            Map.entry("WORKLOAD_REPORT", new DimExpect("stat_day", "统计日", true)));

    /**
     * v64 交付时那句标题的<b>尾巴</b>（前半截是插值出来的区间，故只留断言部分的逐字原话）——
     * <b>活对照组</b>：本轮这条判据喂它必须判红。
     */
    private static final String LEGACY_TITLE_TAIL_V64 =
            "　——整个统计区间一个数，不是某一天；下面那张表才按日拆分";

    /**
     * <b>修复前的反向事实</b>（v64 交付后复核，复核者原话经主控实测坐实）：
     * 「送检科室工作量」「特检技术医嘱量」「标本接收」「标本固定信息完整率」「染色切片优良率」这五条指标的
     * 合计格，屏上都顶着一行<b>写死的</b>标题「…下面那张表才按日拆分」，可这五条指标下面那张表根本不按日拆分——
     * 分别是按送检科室、按技术类型、按标本类别、按标本类别、按染色类型分组，<b>表里连日期列都没有</b>。
     * v64 为修「合计格没标题」而新加的 {@code summaryTitle} 是个不带指标参数的全局 computed，
     * 被无条件绑在 v-for 循环里那一块 el-descriptions 上，于是只对 {@code WORKLOAD_BLOCK} 成立的那句话，
     * 被原样印到了每一条有合计格的指标头上。参数要求的正是「多维度的业务数据汇总」，
     * 而承载「多维度」的这几个维度屏每一块都在屏上宣告自己是按日维度。
     *
     * <p>本条<b>逐指标穷举</b>（不是只测一条）：拿后端此刻真返回的那批行验出这条指标真实的分组维度，
     * 再断言标题里宣告的维度与它一致；非按日维度的还要断言行里<b>确实一列日期都没有</b>，
     * 才说得出「不按日拆分」这句否定断言。
     */
    @Test
    void everySummaryTitleDeclaresTheDimensionItsOwnTableIsGroupedBy() {
        String from = BusinessDates.today().minusDays(1).toString();
        String to = BusinessDates.today().plusDays(1).toString();

        // ---- 造出五个非按日维度各自至少一行，否则「表里没有日期列」会在空集合上恒真 ----
        long sid = registered("dim", "ROUTINE");                     // 标本类别 / 送检科室 / 按登记日
        long blockId = gross(sid, "维度守卫取材 " + tag).get(0);        // 按包埋日
        var sl = process.slides(new SlideReq(blockId, 1, "HE", "HE " + tag, null), doc);
        assertEquals(0, sl.getCode(), sl.getMessage());               // 染色类型 / 按染色日
        assertEquals(1, jdbc.update("""
                insert into path_tech_order(specimen_id, block_id, tech_type, tech_item, status, ordered_at)
                values (?, ?, 'IHC', ?, 'ORDERED', now())
                """, sid, blockId, "CK7 " + tag), "夹具：特检技术医嘱量要有一行（时刻由库端 now() 落）");

        var body = ok(pathQc.indicators(from, to, null));
        var withSummary = new ArrayList<String>();
        for (var ind : rows(body, "indicators")) {
            String code = String.valueOf(ind.get("code"));
            if (ind.get("summary") == null) continue;                 // 没有合计格就没有这句话
            withSummary.add(code);
            DimExpect exp = EXPECTED_DIMS.get(code);
            assertNotNull(exp, "**新增了有合计格的指标 " + code + " 却没在 EXPECTED_DIMS 里登记分组维度**——"
                    + "合计格标题会宣告「下面那张表按 X 拆分」，X 必须逐指标核对过");
            assertTitleDeclaresDimension(code, String.valueOf(ind.get("summaryTitle")),
                    rows(ind, "rows"), exp);
        }
        assertEquals(EXPECTED_DIMS.keySet().stream().sorted().toList(), withSummary.stream().sorted().toList(),
                "活对照组：有合计格的指标集合必须与登记表逐条对上（多一条少一条都说明维度没核过）");

        // ---- 活对照组：五个非按日维度这一轮确实各自有行（空行会让「没有日期列」恒真）----
        for (String code : List.of("SPECIMEN_RECEIVE", "FIXATION", "SLIDE_QUALITY",
                "WORKLOAD_DEPT", "WORKLOAD_TECH")) {
            assertFalse(rows(indicator(code, from, to), "rows").isEmpty(),
                    "活对照组：本测试已为 " + code + " 造了数据，它这一窗必须有行");
        }

        // ---- 活对照组：同一条判据喂 v64 交付时那句话，对按送检科室分组的这张表必须判红 ----
        var deptRows = rows(indicator("WORKLOAD_DEPT", from, to), "rows");
        String legacy = "本期合计　" + from + " 至 " + to + "（共 3 天）" + LEGACY_TITLE_TAIL_V64;
        assertThrows(AssertionError.class,
                () -> assertTitleDeclaresDimension("WORKLOAD_DEPT", legacy, deptRows,
                        EXPECTED_DIMS.get("WORKLOAD_DEPT")),
                "**活对照组**：v64 那句「下面那张表才按日拆分」印在送检科室维度上就是假话，本判据必须抓到它");
        // 探针：判据不是见谁咬谁——把真标题配一个错的期望维度，同样必须判红
        String deptTitle = String.valueOf(indicator("WORKLOAD_DEPT", from, to).get("summaryTitle"));
        assertThrows(AssertionError.class,
                () -> assertTitleDeclaresDimension("WORKLOAD_DEPT", deptTitle, deptRows,
                        new DimExpect("stat_day", "统计日", true)),
                "探针：判据分得出维度对不对（拿真标题配错的期望维度必须红）");

        // ---- 「不是某一天」这句本身也是个断言：区间只有一天时它是假的 ----
        String oneDay = BusinessDates.today().toString();
        String single = String.valueOf(indicator("WORKLOAD_BLOCK", oneDay, oneDay).get("summaryTitle"));
        assertTrue(single.contains("这一格按 " + oneDay + " 这一天算（本区间只有这一天）"),
                "区间只有一天时，标题要直说它就是那一天：" + single);
        assertFalse(single.contains("不是某一天的数"),
                "**区间就一天的时候，这一格正是那一天**——此时再说「不是某一天」就是假话：" + single);
        assertTrue(LEGACY_TITLE_TAIL_V64.contains("不是某一天"),
                "探针：v64 那句话把「不是某一天」无条件说给每个区间，包括只有一天的区间");
    }

    /** 标题里宣告的拆分维度 == 这批行真实的分组维度（行里逐行取值互不相同的那一列） */
    private static void assertTitleDeclaresDimension(String code, String title,
                                                     List<Map<String, Object>> rows, DimExpect exp) {
        assertNotNull(title, code + " 有合计格却没有 summaryTitle——屏上那一格又会变回没有一个字说它是区间合计");
        assertTrue(title.startsWith("本期合计"), code + " 的合计格标题要一眼说明这是区间合计：" + title);
        if (rows.isEmpty()) {
            assertTrue(title.contains("本区间没有分组行"),
                    code + " 这一窗没有行，标题不许再说下面有一张表：" + title);
            assertFalse(title.contains("下面那张表"),
                    "**没有表的时候不许提「下面那张表」**（屏上此处显示的是「该统计区间内无数据」）：" + title);
            return;
        }
        // 真实分组维度：这一列在行间逐行取值互不相同（group by 的直接后果）
        var seen = new LinkedHashSet<String>();
        for (var r : rows) {
            assertTrue(r.containsKey(exp.column()),
                    code + " 的行里没有登记的维度列 " + exp.column() + "：" + r.keySet());
            assertTrue(seen.add(String.valueOf(r.get(exp.column()))),
                    code + " 的维度列 " + exp.column() + " 在行间重复，它不是这张表的分组维度：" + rows);
        }
        if (exp.byDay()) {
            assertTrue(title.contains("下面那张表按日拆分，每行一个" + exp.label()),
                    code + " 是按日维度，标题要这么说：" + title);
        } else {
            for (var r : rows) {
                for (String dayCol : List.of("stat_day", "issue_day")) {
                    assertFalse(r.containsKey(dayCol),
                            code + " 按 " + exp.label() + "分组，行里不该有按日列 " + dayCol + "：" + r.keySet());
                }
            }
            assertTrue(title.contains("下面那张表不按日拆分，按" + exp.label() + "分组，每行一个" + exp.label()),
                    "**" + code + " 的表按" + exp.label() + "分组，标题必须说它自己的维度**"
                            + "（修复前这里印的是「下面那张表才按日拆分」，而这张表里连日期列都没有）：" + title);
        }
        assertTrue(title.contains("共 " + rows.size() + " 行"),
                code + " 的标题里那个行数要与真正交出去的行数一致：" + title + " / " + rows.size());
    }

    // =====================================================================================
    // (b3) v65 车道 C（2576 复核第二条）：口径说明里的比较关系必须与真实数据一致
    // =====================================================================================

    /** v64 那两句「必然」的逐字原文——<b>活对照组</b>：同一个判据喂它，在「一根柱」的库态下必须判红 */
    private static final String LEGACY_CALIBER_CLAIM_V64 =
            "**「本期涉及标本数(去重)」必然小于按日各行「当日涉及标本数(去重)」之和，"
            + "「本期蜡块/标本」也不等于按日表里的任何一行**：同一份标本的蜡块本来就分落在多天";

    /**
     * <b>修复前的反向事实</b>（v64 交付后复核，复核者原话经主控实测坐实）：蜡块产出数这条工作量汇总，
     * 把两条<b>与库内事实相反</b>的对账口径当成「必然」加粗印在数字正上方（同一段字还随「导出汇总」的
     * CSV 页脚发出去）：它宣称「本期涉及标本数(去重)」<b>必然</b>小于按日各行之和、且「本期蜡块/标本」
     * 不等于按日表里的任何一行。这两句<b>只在「同一份标本的蜡块落进两个以上统计日」时才成立</b>；
     * 而平台自己的演示脚本造出来的库态<b>只有当天一根柱</b>，默认 30 天窗口下合计格与按日唯一那一行
     * <b>逐字相等</b>——屏上宣告「必然不等」，库里给出的是相等。
     *
     * <p>本条造两种库态，两次都拿<b>同一个判据</b>核「文案里宣称的比较关系与真实数据一致」：
     * 一根柱（演示脚本的形态）与跨日两根柱。v64 那两句在前一种库态下必须被判红、在后一种下不许被咬
     * （它在那里恰好是真的）——这才是活对照组，不是「见「必然」就咬」。
     */
    @Test
    void theSummaryVsDailyNoteTellsTheTruthInBothLibraryStates() {
        // 把窗口整体推到 200 天前（相对表达式，不写时间字面量），并先证明它是空的：
        // 「只有一根柱」的库态，只有在一个本来就没有别的蜡块的窗口里才造得出来
        String from = BusinessDates.today().minusDays(201).toString();
        String to = BusinessDates.today().minusDays(199).toString();
        assertEquals(List.of(), rows(indicator("WORKLOAD_BLOCK", from, to), "rows"),
                "夹具前提：这个窗口必须本来就是空的，否则造不出「只有一根柱」的演示库态");
        assertTrue(String.valueOf(indicator("WORKLOAD_BLOCK", from, to).get("summaryVsDailyNote"))
                        .contains("本区间没有按日行"),
                "空窗口下这句话也得如实：一行都没有的时候不许拿「合计小于各行之和」说事");

        // ---- 库态一：演示脚本的形态——**只有一根柱**（同一份标本的两块蜡块落在同一天）----
        long sid = registered("vs", "ROUTINE");
        List<Long> ids = gross(sid, "同日第一块 " + tag, "同日第二块 " + tag);
        assertEquals(2, ids.size());
        assertEquals(2, jdbc.update(
                        "update path_block set created_at = now() - interval '200 days' where id in (?, ?)",
                        ids.get(0), ids.get(1)),
                "夹具：两块都推到 200 天前的同一天（时刻由库端相对表达式给，不写时间字面量）");

        var one = indicator("WORKLOAD_BLOCK", from, to);
        assertEquals(1, rows(one, "rows").size(), "库态一：窗口内只有一根柱：" + rows(one, "rows"));
        long p1 = summaryLong("WORKLOAD_BLOCK", from, to, "specimens_in_period");
        long d1 = windowSum("WORKLOAD_BLOCK", from, to, "specimens_of_day");
        assertEquals(1L, p1, "本期涉及标本数(去重)");
        assertEquals(1L, d1, "按日各行之和——**与合计逐字相等**，这正是演示库态下屏上那两个数");
        String note1 = String.valueOf(one.get("summaryVsDailyNote"));
        assertTrue(note1.contains("「本期涉及标本数(去重)」1 等于按日各行「当日涉及标本数(去重)」之和 1"),
                "**一根柱的库态下这句话必须如实说「等于」**：" + note1);
        assertTrue(note1.contains("本区间内没有一份标本的蜡块落进两个以上统计日"),
                "相等的原因也要写出来（否则读的人会以为是对不上账）：" + note1);
        assertTrue(note1.contains("与按日表里 1 行的数值相同"),
                "**「本期蜡块/标本」与按日唯一那一行逐字相同**，此时不许宣告它不等于任何一行：" + note1);
        assertCaliberClaimMatchesData("本次的对账说明", note1, p1, d1);
        assertCaliberClaimMatchesData("本指标 caveat", caveatOf("WORKLOAD_BLOCK", from, to), p1, d1);
        // 活对照组：同一个判据喂 v64 那两句原话，在这个库态下必须判红
        assertThrows(AssertionError.class,
                () -> assertCaliberClaimMatchesData("v64 原话", LEGACY_CALIBER_CLAIM_V64, p1, d1),
                "**活对照组**：v64 那两句「必然」在演示库态下与库内事实相反，本判据必须抓到它");
        // CSV 页脚：这段字随文件走（v64 那两句正是同时印在屏上与 CSV 页脚里的）
        String csv1 = pathQc.indicatorsCsv("WORKLOAD_BLOCK", from, to);
        assertTrue(csv1.contains(note1), "**CSV 页脚要带同一段对账说明**（导出的表格一转手就脱离页面）：" + csv1);
        assertFalse(csv1.contains("必然小于"), "CSV 页脚里也不许再有那句「必然小于」：" + csv1);

        // ---- 库态二：跨统计日——把其中一块挪到相邻的另一天（脱水过夜跨日的常规形态）----
        assertEquals(1, jdbc.update(
                        "update path_block set created_at = now() - interval '199 days' where id = ?", ids.get(0)),
                "夹具：同一份标本的两块蜡块就此分落两天");
        var two = indicator("WORKLOAD_BLOCK", from, to);
        assertEquals(2, rows(two, "rows").size(), "库态二：两根柱：" + rows(two, "rows"));
        long p2 = summaryLong("WORKLOAD_BLOCK", from, to, "specimens_in_period");
        long d2 = windowSum("WORKLOAD_BLOCK", from, to, "specimens_of_day");
        assertEquals(1L, p2, "整窗去重仍只数一次");
        assertEquals(2L, d2, "按日两行各数一次");
        String note2 = String.valueOf(two.get("summaryVsDailyNote"));
        assertTrue(note2.contains("「本期涉及标本数(去重)」1 小于按日各行「当日涉及标本数(去重)」之和 2"),
                "**跨日的库态下这句话必须如实说「小于」**：" + note2);
        assertTrue(note2.contains("有标本的蜡块分落在两个以上统计日") && note2.contains("不是对不上账"),
                "小于的原因也要写出来：" + note2);
        assertTrue(note2.contains("与按日表里的每一行都不相同"),
                "此时「本期蜡块/标本」（2 块 / 1 份）确实与按日两行（各 1 块 / 1 份）都不同：" + note2);
        assertCaliberClaimMatchesData("本次的对账说明", note2, p2, d2);
        assertCaliberClaimMatchesData("本指标 caveat", caveatOf("WORKLOAD_BLOCK", from, to), p2, d2);
        // 探针：判据不是见「必然」就咬——v64 那两句在这个库态下恰好是真的，此处不许判红
        assertDoesNotThrow(() -> assertCaliberClaimMatchesData("v64 原话", LEGACY_CALIBER_CLAIM_V64, p2, d2),
                "探针：同一句话在跨日库态下是真的，判据不许在这里也咬它");

        // 计数列两种库态下都相等（逐块计数没有去重）——活对照组：不是所有对比都在说「小于」
        assertTrue(note2.contains("「本期产出蜡块数」2 等于按日各行「当日产出蜡块数」之和 2"),
                "逐块计数那一列，合计恰等于按日各行之和：" + note2);
    }

    /**
     * 口径说明里<b>宣称的比较关系</b>必须与真实数据一致。
     *
     * <p>刻意<b>不写成「出现「必然」二字即红」</b>：那样会把跨日库态下同一句真话也咬掉，
     * 而本项目真正的失败模式是「一条咬错的断言迟早被人改宽到形同虚设」。判的是
     * 「这句话宣称的关系」与「本次库态的真实关系」对不对得上。
     */
    private static void assertCaliberClaimMatchesData(String what, String text, long period, long daySum) {
        if (period == daySum) {
            assertFalse(text.contains("必然小于"),
                    "**" + what + "宣称合计「必然小于」按日各行之和，而本次库态两数相等**（"
                            + period + " = " + daySum + "）：" + text);
            assertFalse(text.contains("也不等于按日表里的任何一行"),
                    "**" + what + "宣称合计不等于按日表里的任何一行，而本次库态只有一根柱、两数逐字相等**（"
                            + period + " = " + daySum + "）：" + text);
        }
        if (period < daySum) {
            assertFalse(text.contains(" 等于按日各行「当日涉及标本数(去重)」之和"),
                    "**" + what + "宣称两数相等，而本次库态合计更小**（" + period + " < " + daySum + "）：" + text);
        }
    }

    // =====================================================================================
    // (c) 同型的 molecular 一并正名：标本类别 vs 染色类型
    // =====================================================================================

    @Test
    void molecularIsRenamedOnBothSides() {
        String from = BusinessDates.today().minusDays(1).toString();
        String to = BusinessDates.today().plusDays(1).toString();
        long specBefore = windowSum("WORKLOAD_REGISTER", from, to, MOLECULAR_SPECIMENS);
        long slideBefore = windowSum("WORKLOAD_SLIDE", from, to, MOLECULAR_SLIDES);

        // 一条 MOLECULAR 标本，从它的一块蜡块出 3 张 MOLECULAR 切片：标本数 1、切片数 3
        long sid = registered("c1", "MOLECULAR");
        long blockId = gross(sid, "分子检测取材 " + tag).get(0);
        var sl = process.slides(new SlideReq(blockId, 3, "MOLECULAR", "EGFR " + tag, null), doc);
        assertEquals(0, sl.getCode(), sl.getMessage());

        long specimens = windowSum("WORKLOAD_REGISTER", from, to, MOLECULAR_SPECIMENS) - specBefore;
        long slides = windowSum("WORKLOAD_SLIDE", from, to, MOLECULAR_SLIDES) - slideBefore;
        assertEquals(1L, specimens, MOLECULAR_SPECIMENS + " 数的是 specimen_type='MOLECULAR' 的标本条数");
        assertEquals(3L, slides, MOLECULAR_SLIDES + " 数的是 stain_type='MOLECULAR' 的切片张数");
        assertNotEquals(specimens, slides,
                "同一块看板上的两个「分子病理」本来就是两个数——修复前同名 molecular、同中文「"
                        + OLD_SHARED_MOLECULAR_ZH + "」");

        assertFalse(rowCols("WORKLOAD_REGISTER", from, to).contains("molecular"),
                "登记总量的 molecular 已正名成 " + MOLECULAR_SPECIMENS);
        assertFalse(rowCols("WORKLOAD_SLIDE", from, to).contains("molecular"),
                "切片产出的 molecular 已正名成 " + MOLECULAR_SLIDES);
        assertTrue(summaryCols("WORKLOAD_SLIDE", from, to).contains(MOLECULAR_SLIDES), "合计行同步正名");
        assertLabelPair(MOLECULAR_SPECIMENS, "分子病理标本数(标本类别)");
        assertLabelPair(MOLECULAR_SLIDES, "分子病理切片数(染色类型)");
        assertNotEquals(backendLabel(MOLECULAR_SPECIMENS), backendLabel(MOLECULAR_SLIDES),
                "两个「分子病理」的中文必须互不相同（修复前同为「" + OLD_SHARED_MOLECULAR_ZH + "」）");
    }

    // =====================================================================================
    // (d) 正名不许把既有的机械断言拆了：前端 ZH ⊇ 后端 zh() 仍成立
    // =====================================================================================

    @Test
    void frontendZhStillCoversEveryBackendLabelKey() {
        Set<String> backend = V59QcLabelsTest.backendLabelKeys(read(CONTROLLER));
        Set<String> zh = V59QcLabelsTest.zhKeys(read(FORMAT_TS));
        // 活的对照组：解析器真读到了两侧字典（v59 立的规矩，空集合恒真是这类扫描最常见的假绿）
        assertTrue(backend.size() > 50 && zh.size() > 50,
                "两侧字典没解析出来：后端 " + backend.size() + " 键、前端 " + zh.size() + " 键");
        assertTrue(backend.containsAll(List.of(BLOCKS_PRODUCED, BLOCKS_STAINED,
                        MOLECULAR_SPECIMENS, MOLECULAR_SLIDES)),
                "后端 zh() 必须登记本轮正名的四个键：" + backend);
        assertEquals(List.of(), V59QcLabelsTest.missing(backend, zh),
                "前端 ZH 缺后端 zh() 已登记的键——页面表头英文、CSV 表头中文（同一列两套表头）");
        // 反向事实：旧的共用键不该再出现在后端 zh() 里（它已不是任何汇总 / 明细行的列名）
        assertFalse(backend.contains(OLD_SHARED_COL),
                "后端 zh() 仍登记着 case \"" + OLD_SHARED_COL + "\"——正名没做干净");
        assertFalse(backend.contains("molecular"),
                "后端 zh() 仍登记着 case \"molecular\"——正名没做干净");
    }

    // =====================================================================================
    // (e) v63：覆盖率段 note 与 WORKLOAD_BLOCK caveat 对同一个数不得给出相反结论
    // =====================================================================================

    /**
     * 「这个数事后还可不可信」这条结论的反向形态：修复前逐字是「蜡块产出量仍可信（建档即产出）」。
     * 刻意不写成「含『可信』二字即红」——那样会把「with_quality 越低优良率越不可信」这类合法句子一起咬掉。
     */
    private static final Pattern STILL_RELIABLE = Pattern.compile("(产出量|产出数|这个数)[^。；\\n]{0,16}仍可信");

    /** 修复前覆盖率段那句话的逐字原文（PathQcController.java:682-683，v62 交付形态）——对照组用 */
    private static final String LEGACY_COVERAGE_NOTE =
            "按 coalesce(embedded_at, created_at) 落窗。with_embedded_at 低"
            + "说明包埋确认环节没在系统里打点，蜡块产出量仍可信（建档即产出），但包埋耗时算不出来。";

    @Test
    void coverageBlockNoteAndWorkloadBlockCaveatGiveTheSameConclusion() {
        String from = BusinessDates.today().minusDays(1).toString();
        String to = BusinessDates.today().plusDays(1).toString();

        // ---- 先证「是同一个数」：同一条谓词、同一个 count(*)，所以结论才必须一致 ----
        long covBefore = coverageBlocksCount(from, to);
        long producedBefore = windowSum("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED);
        long sid = registered("e1", "ROUTINE");
        assertEquals(2, gross(sid, "口径一 " + tag, "口径二 " + tag).size());
        long covDelta = coverageBlocksCount(from, to) - covBefore;
        long producedDelta = windowSum("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED) - producedBefore;
        // 活的对照组：两个数都真的动了（不是 0 == 0 恒真）
        assertEquals(2L, covDelta, "覆盖率段 coverage.blocks.blocks 是 count(*) from path_block，同一条谓词落窗");
        assertEquals(2L, producedDelta, BLOCKS_PRODUCED + " 是同一个 count(*)、同一条谓词");
        assertEquals(coverageBlocksCount(from, to), windowSum("WORKLOAD_BLOCK", from, to, BLOCKS_PRODUCED),
                "**同一页上这两格就是同一个数**——所以对它的结论只能有一个");

        // ---- 再证「结论同源」：两段文本引用的是同一个常量，不是各写一段散文 ----
        String covNote = coverageBlocksNote(from, to);
        String caveat = caveatOf("WORKLOAD_BLOCK", from, to);
        assertTrue(covNote.length() > 60 && caveat.length() > 60,
                "两段文本都得真取到（空串恒真是这类断言最常见的假绿）：" + covNote.length() + " / " + caveat.length());
        assertTrue(covNote.contains(PathQcController.BLOCK_DAY_CAVEAT),
                "覆盖率段 note 必须引用同一条口径结论常量：" + covNote);
        assertTrue(caveat.contains(PathQcController.BLOCK_DAY_CAVEAT),
                "WORKLOAD_BLOCK 的 caveat 必须引用同一条口径结论常量：" + caveat);
        for (String phrase : List.of("不可复现", "同一个已关闭区间", "建档时刻", "包埋日")) {
            assertTrue(covNote.contains(phrase) && caveat.contains(phrase),
                    "两段结论都得含「" + phrase + "」，否则同一页仍是两套说法：\n覆盖率段=" + covNote
                            + "\ncaveat=" + caveat);
        }

        // ---- 反向事实：说反话的那一句不得再出现 ----
        assertFalse(STILL_RELIABLE.matcher(covNote).find(),
                "**覆盖率段先于所有指标上屏，不得再写「产出量仍可信」**（同页 caveat 说的是这个数事后会变）：" + covNote);
        assertFalse(STILL_RELIABLE.matcher(caveat).find(), "caveat 侧同理：" + caveat);
        // 探针：同一个检测器抓得到修复前那句原话，否则上面两条 assertFalse 只是正则写空了
        assertTrue(STILL_RELIABLE.matcher(LEGACY_COVERAGE_NOTE).find(),
                "探针：检测器必须抓得到 v62 交付时那句原话：" + LEGACY_COVERAGE_NOTE);

        // ---- 源码扫描：整份控制器里（剥注释后）不得再有相反结论 ----
        String raw = read(CONTROLLER);
        // 活的对照组：**未剥注释时同一份源码仍能命中**——本轮的 javadoc 与行内注释逐字引用了修复前那句话，
        // 不先剥注释，这条扫描会被自己的注释绊成红；反过来，它也证明扫描器确实在读真文件（不是扫空恒绿）
        assertTrue(STILL_RELIABLE.matcher(raw).find(),
                "对照组：未剥注释时，源码注释里引用的修复前原话仍能匹配到——否则下面那条 false 不说明任何事");
        assertFalse(STILL_RELIABLE.matcher(V57GrossKeepTest.stripComments(raw)).find(),
                "剥注释后的 " + CONTROLLER + " 里不得再有「产出量仍可信」这类与 caveat 相反的结论");

        // ---- 口径随 CSV 页脚走：导出的表一转手就脱离页面 ----
        assertTrue(pathQc.indicatorsCsv("WORKLOAD_BLOCK", from, to).contains("不可复现"),
                "CSV 页脚要带这句口径说明");
    }

    /** 覆盖率段「蜡块」那一节（{@code coverage.blocks}）——它就是上屏时排在所有指标之前的那一段 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coverageBlocks(String from, String to) {
        var body = ok(pathQc.indicators(from, to, "WORKLOAD_BLOCK"));
        var cov = (Map<String, Object>) body.get("coverage");
        assertNotNull(cov, "indicators 返回体必须带 coverage 段：" + body.keySet());
        var blocks = (Map<String, Object>) cov.get("blocks");
        assertNotNull(blocks, "coverage 段必须有 blocks 一节：" + cov.keySet());
        return blocks;
    }

    /**
     * 覆盖率段「蜡块」那一节的蜡块计数。<b>刻意不写死列名</b>：这一节那个裸 {@code blocks} 键正由
     * 另一条车道正名（v63 2576⑧，前端表头重名那条），本测试要钉的是「两段结论同源」，
     * 不该因为隔壁车道给列改了名就红。两个名字都找不到才是真出了事。
     */
    private long coverageBlocksCount(String from, String to) {
        var sec = coverageBlocks(from, to);
        for (String k : List.of(OLD_SHARED_COL, BLOCKS_PRODUCED)) {
            if (sec.containsKey(k)) return n(sec.get(k));
        }
        throw new AssertionError("覆盖率段 blocks 一节里找不到蜡块计数列：" + sec.keySet());
    }

    private String coverageBlocksNote(String from, String to) {
        return String.valueOf(coverageBlocks(from, to).get("note"));
    }

    // =====================================================================================
    // 夹具（走真实端点，不直插业务行；照抄 V61QcCaliberTest / V60DeptDimensionTest）
    // =====================================================================================

    /** 登记 + 签收一条标本，并落下标本类别（类别未填的标本不进 WORKLOAD_REGISTER 的类别构成列） */
    private long registered(String suffix, String specimenType) {
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag + suffix);
        var c = pathology.collect(orderId, "标本" + suffix + " " + tag);
        assertEquals(0, c.getCode(), c.getMessage());
        String barcode = (String) c.getData().get("barcode");
        long sid = jdbc.queryForObject("select id from path_specimen where barcode = ?", Long.class, barcode);
        jdbc.update("update path_specimen set specimen_type = ? where id = ?", specimenType, sid);
        assertEquals(0, pathology.receive(barcode).getCode());
        return sid;
    }

    /** 取材产出 N 块蜡块，返回块 id（顺序同入参）；时刻由端点按库端 now() 落 */
    private List<Long> gross(long sid, String... tissueDescs) {
        var blocks = new ArrayList<BlockReq>();
        for (String d : tissueDescs) blocks.add(new BlockReq(d));
        var gr = process.grossing(new GrossingReq(sid, null, null, "取材描述 " + tag, false, null, blocks), doc);
        assertEquals(0, gr.getCode(), gr.getMessage());
        var out = new ArrayList<Long>();
        for (var row : rows(gr.getData(), "blocks")) out.add(n(row.get("id")));
        assertEquals(tissueDescs.length, out.size(), "取材应产出 " + tissueDescs.length + " 块：" + gr.getData());
        return out;
    }

    /** 夹具前提：取材 insert path_block 不落 embedded_at——「未包埋的蜡块被拿 created_at 顶替」的起点 */
    private void assertNotEmbedded(long blockId) {
        assertEquals(1, jdbc.queryForList(
                        "select 1 from path_block where id = ? and embedded_at is null", blockId).size(),
                "夹具前提：这块蜡块尚未包埋（取材 insert 不落 embedded_at）");
    }

    // =====================================================================================
    // 取数与断言小工具
    // =====================================================================================

    private Map<String, Object> indicator(String code, String from, String to) {
        var body = ok(pathQc.indicators(from, to, code));
        var list = rows(body, "indicators");
        assertEquals(1, list.size(), "指定 indicator 只该回一条：" + list);
        assertEquals(code, list.get(0).get("code"));
        return list.get(0);
    }

    /** 窗口内该列各行之和——不取绝对值，只用前后差，故与库里既有数据无关 */
    private long windowSum(String code, String from, String to, String col) {
        long sum = 0;
        for (var r : rows(indicator(code, from, to), "rows")) {
            assertTrue(r.containsKey(col), code + " 的行里没有列 " + col + "：" + r.keySet());
            sum += n(r.get(col));
        }
        return sum;
    }

    private Set<String> rowCols(String code, String from, String to) {
        var rs = rows(indicator(code, from, to), "rows");
        assertFalse(rs.isEmpty(), code + " 在本窗口内无行——列名断言会变成空集合恒真");
        return new LinkedHashSet<>(rs.get(0).keySet());
    }

    @SuppressWarnings("unchecked")
    private Set<String> summaryCols(String code, String from, String to) {
        Object s = indicator(code, from, to).get("summary");
        assertNotNull(s, code + " 没有合计行");
        return new LinkedHashSet<>(((Map<String, Object>) s).keySet());
    }

    private String caveatOf(String code, String from, String to) {
        return String.valueOf(indicator(code, from, to).get("caveat"));
    }

    private static void assertOnlyStatDayInCommon(String an, Set<String> a, String bn, Set<String> b) {
        var common = new LinkedHashSet<>(a);
        common.retainAll(b);
        assertEquals(Set.of("stat_day"), common,
                "**" + an + " 与 " + bn + " 除日期外不得有同名列**——同名列经同一张扁平字典渲染成同一个中文表头，"
                        + "并排显示时读的人分不出两个口径（修复前这里是 " + common + "）");
    }

    /** 后端 zh() 与前端 ZH 都登记了这个键，且中文逐字一致 */
    private void assertLabelPair(String key, String expectedZh) {
        assertEquals(expectedZh, backendLabel(key), "后端 zh() 的 case \"" + key + "\"");
        assertEquals(expectedZh, V59QcLabelsTest.zhEntries(read(FORMAT_TS)).get(key),
                "前端 ZH." + key + " 须与后端 zh() 同名 case 逐字一致");
    }

    private String backendLabel(String key) {
        return V59QcLabelsTest.backendLabels(read(CONTROLLER)).get(key);
    }

    private String frontendLabel(String key) {
        return V59QcLabelsTest.zhEntries(read(FORMAT_TS)).get(key);
    }

    /** 合计行某一列（计数列，恒非 null） */
    @SuppressWarnings("unchecked")
    private long summaryLong(String code, String from, String to, String col) {
        Object s = indicator(code, from, to).get("summary");
        assertNotNull(s, code + " 没有合计行");
        var map = (Map<String, Object>) s;
        assertTrue(map.containsKey(col), code + " 的合计行里没有列 " + col + "：" + map.keySet());
        return n(map.get(col));
    }

    /** 合计行某一列（比值列；分母为 0 时后端回 null，此处要求非 null——调用点都先造了数） */
    @SuppressWarnings("unchecked")
    private double summaryDouble(String code, String from, String to, String col) {
        Object s = indicator(code, from, to).get("summary");
        assertNotNull(s, code + " 没有合计行");
        Object v = ((Map<String, Object>) s).get(col);
        assertNotNull(v, code + " 的合计行里 " + col + " 为空——分母为 0？");
        return ((Number) v).doubleValue();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 两段 SQL 在源码里的锚（三个 case "WORKLOAD_BLOCK" 分属汇总行 / 合计行 / 穿透明细，调用形态各不相同） */
    private static final String ROWS_ANCHOR = "case \"WORKLOAD_BLOCK\" -> query(";
    private static final String SUMMARY_ANCHOR = "case \"WORKLOAD_BLOCK\" -> one(q(";

    /** 锚之后的第一个文本块（按 Java 文本块的三引号配对截） */
    private static String textBlockAfter(String src, String anchor) {
        int at = src.indexOf(anchor);
        assertTrue(at >= 0, "剥注释后的源码里找不到锚「" + anchor + "」");
        int open = src.indexOf("\"\"\"", at);
        assertTrue(open > at, "锚之后找不到文本块起始三引号");
        int close = src.indexOf("\"\"\"", open + 3);
        assertTrue(close > open, "文本块三引号不配对");
        return src.substring(open + 3, close);
    }

    /** 一段 SQL 里的全部列别名（{@code as xxx}，源码顺序） */
    private static List<String> sqlAliases(String sql) {
        var out = new ArrayList<String>();
        var m = Pattern.compile("\\bas\\s+([a-z_][a-z0-9_]*)").matcher(sql);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** CSV 表头：正名后的中文在、孤零零的旧中文不在（「蜡块数」是新中文的子串，故按字段切开比对） */
    private void assertCsvHeader(String code, String from, String to, String expected) {
        String csv = pathQc.indicatorsCsv(code, from, to);
        assertFalse(csv.contains("（该统计区间内无数据）"), code + " 本窗口内应有数据：" + csv);
        var lines = new ArrayList<String>();
        for (String ln : csv.replace("﻿", "").split("\n")) if (!ln.isBlank()) lines.add(ln.trim());
        // 第 1 行是指标元信息表头、第 2 行是取值，之后第一行才是列名表头
        assertTrue(lines.size() > 2, "CSV 太短：" + csv);
        var header = List.of(lines.get(2).split(","));
        assertTrue(header.contains(expected), code + " 的 CSV 表头缺「" + expected + "」：" + header);
        assertFalse(header.contains(OLD_SHARED_ZH),
                code + " 的 CSV 表头仍有一个孤零零的「" + OLD_SHARED_ZH + "」字段（修复前两张表各出一个）：" + header);
    }

    private Authentication userAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    private static long n(Object v) {
        return v instanceof Number num ? num.longValue() : 0L;
    }

    private static Map<String, Object> ok(R<Map<String, Object>> r) {
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
        Object v = body.get(key);
        assertNotNull(v, "返回体缺键 " + key + "：" + body.keySet());
        return (List<Map<String, Object>>) v;
    }

    /** 按仓库相对路径读真文件（源码扫描一律走仓库相对路径：绝对路径在 worktree 下会扫空恒绿） */
    private static String read(String rel) {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !(Files.isDirectory(root.resolve("frontend"))
                && Files.isDirectory(root.resolve("modules")))) {
            root = root.getParent();
        }
        assertNotNull(root, "找不到仓库根（从 " + System.getProperty("user.dir") + " 向上找 frontend/ 与 modules/）");
        Path f = root.resolve(rel);
        if (!Files.isRegularFile(f)) fail("找不到 " + rel + "（仓库根 " + root + "）");
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读不到 " + rel, e);
        }
    }
}
