package cn.hip.server;

import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyController.DiagnoseReq;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.platform.core.common.R;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * v58 车道 A（2530 核账复核）：<b>取材字段级存储 + 大体所见修订留痕</b>（V164）。
 *
 * <h2>修复前的反向事实</h2>
 * <ol>
 *   <li>取材端点把有序的「字段名 → 值」拼成一段文本写 {@code path_specimen.gross_finding} 就完了，
 *       字段级信息落库即丢失——{@code path_gross_field} 表<b>根本不存在</b>（本类 (a)(d) 对该表的查询在修复前
 *       直接 BadSqlGrammar），读端点也没有 {@code fieldsAvailable / fields / revisions} 三个键。</li>
 *   <li>诊断端点非空即覆盖该列，覆盖前的原文没有任何地方留着——{@code path_gross_revision} 表不存在，
 *       返回体没有 {@code grossRevised}（本类 (b) 在修复前必红）。</li>
 * </ol>
 *
 * <h2>本类钉住的契约</h2>
 * <ul>
 *   <li>(a) 取材传三个字段 + 自由文本 → 字段表三行按入参顺序、修订 seq1 old=null new=拼好的文本 source=GROSSING；
 *       {@code gross_finding} 的文本<b>与 v57 前逐字一致</b>（文本契约不破，字段行是加法）。</li>
 *   <li>(a2) append=true 且本次没传 gross/grossText → 字段表、修订表都不写（没有新描述就没有新版本）；
 *       已有大体所见再传 gross 仍是既有 5222 且两张表零变化。</li>
 *   <li>(b) diagnose 传不同的 G2 → 修订 seq2 old=G new=G2 source=DIAGNOSE changed_by=当前用户、{@code grossRevised=true}；
 *       空白 → 无新修订、{@code grossKept=true}；相同文本（带空白）→ 无新修订；从未取材的标本由诊断首写 →
 *       修订 seq1 old=null source=DIAGNOSE 而 {@code fieldsAvailable=false}（字段与版本是两回事，不拿一个凑另一个）。</li>
 *   <li>(c) 历史标本：直接 SQL 置 gross_finding（模拟 V164 之前）→ 读端点 {@code fieldsAvailable=false}、
 *       revisions 空、grossFinding 原样——<b>即使那段文本长得像「大小：2×1cm；切面：灰白」也不反解析</b>。</li>
 *   <li>(d) 字段顺序是入参顺序不是字母序：用会被字符序打乱的三个中文字段名证明，并带活的对照
 *       （按字符序排出来的清单与 seq 序<b>确实不同</b>——否则这条断言什么都证明不了）。</li>
 *   <li>(e) V164 零 update / 零 insert 扫描（剥注释、按语法形态匹配、带活的对照组、探针证明扫描器在咬）；
 *       列长与控制器常量 GROSS_LABEL_MAX / GROSS_VALUE_MAX 逐字一致。</li>
 * </ul>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：夹具时刻由 SQL 按 {@code now()} / {@code current_date} 现算；
 * 修订时刻用「与库端 now() 偏差 ≤ 5 分钟」的容差断言。事务回滚，库里不留痕。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V58GrossFieldsTest {

    private static final String V164 = "server/src/main/resources/db/migration/V164__path_gross_fields.sql";
    private static final String CONTROLLER =
            "modules/medtech/src/main/java/cn/hip/medtech/web/PathologyProcessController.java";
    /** 对照组：确实含顶层 update 语句的既有迁移（V22 第 40 行 {@code update md_drug set abx_level = 1 where antibiotic;}） */
    private static final String CONTROL_WITH_UPDATE = "server/src/main/resources/db/migration/V22__phase25_mgmt.sql";
    /** 对照组：确实含顶层 insert 语句的既有迁移（V161 gate 种子） */
    private static final String CONTROL_WITH_INSERT = "server/src/main/resources/db/migration/V161__timeliness_gate_seed.sql";

    @Autowired PathologyController pathology;
    @Autowired PathologyProcessController process;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Long regId;
    private Authentication doc;
    private Long docUid;

    @BeforeEach
    void setUp() {
        tag = "V58A" + Long.toHexString(System.nanoTime());
        doc = doctorAuth(tag + "d1");
        docUid = userId(tag + "d1");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "取材字段测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "取材字段患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
    }

    // =====================================================================================
    // (a) 取材：字段按序落库 + 首写修订 + 文本契约不变
    // =====================================================================================

    @Test
    void grossingStoresFieldsInOrderAndFirstRevision() {
        var gross = new LinkedHashMap<String, String>();
        gross.put("大小", "3×2×1cm");
        gross.put("颜色", " 灰白 ");       // 入库 trim（与 assembleGross 同取舍）
        gross.put("质地", "质硬");
        gross.put("包膜", "   ");          // 值为空的字段整条不落（文本里也没有「包膜：」半截话）
        String free = "切面实性 " + tag;
        Fixture f = specimen("A");

        var gr = ok(process.grossing(new GrossingReq(f.id(), null, gross, free, false, null,
                List.of(new BlockReq("肿物中心"))), doc));

        // 文本契约与 v57 前逐字一致：字段「标签：值」以「；」相连，末尾「。」接自由文本
        String expected = "大小：3×2×1cm；颜色：灰白；质地：质硬。" + free;
        assertEquals(expected, gr.get("grossFinding"));
        assertEquals(Boolean.TRUE, gr.get("grossFindingWritten"));
        assertEquals(3, ((Number) gr.get("grossFieldCount")).intValue(), "三个非空字段落库，空值字段不算");
        assertEquals(expected, jdbc.queryForObject(
                "select gross_finding from path_specimen where id = ?", String.class, f.id()),
                "拼好的文本仍落 gross_finding：既有读方一个字节不用改");

        // 修复前：path_gross_field 根本不存在（V164 才建）——这条查询直接 BadSqlGrammar
        var rows = fieldRows(f.id());
        assertEquals(3, rows.size(), "三个非空字段三行");
        assertEquals(List.of("大小", "颜色", "质地"), labels(rows), "按入参顺序");
        assertEquals(List.of("3×2×1cm", "灰白", "质硬"),
                rows.stream().map(r -> r.get("value")).toList(), "值已 trim");
        assertEquals(List.of(1, 2, 3), rows.stream().map(r -> seq(r)).toList(), "seq 从 1 连续");
        for (var r : rows) {
            assertEquals(docUid, r.get("operator_id"), "operator_id = 当前用户");
            assertNotNull(r.get("created_at"));
        }

        var revs = revisionRows(f.id());
        assertEquals(1, revs.size(), "取材首写恰好一条修订");
        assertEquals(1, seq(revs.get(0)));
        assertNull(revs.get(0).get("old_text"), "首写没有原值");
        assertEquals(expected, revs.get(0).get("new_text"));
        assertEquals("GROSSING", revs.get(0).get("source"));
        assertEquals(docUid, revs.get(0).get("changed_by"));
        assertRecent(revs.get(0).get("changed_at"));

        // 读端点：三个新键只加不改，v55 既有键照旧
        var view = ok(process.grossingView(f.id()));
        assertEquals(Boolean.TRUE, view.get("fieldsAvailable"));
        assertEquals(expected, view.get("grossFinding"));
        assertEquals(Boolean.TRUE, view.get("grossFindingPresent"), "v55 既有键不动");
        var fields = rows(view, "fields");
        assertEquals(3, fields.size());
        assertEquals(Set.of("seq", "label", "value", "createdAt", "operatorId", "operatorName"), fields.get(0).keySet());
        assertEquals(List.of("大小", "颜色", "质地"), labels(fields));
        assertEquals(List.of(1, 2, 3), fields.stream().map(r -> seq(r)).toList());
        assertEquals(docUid, fields.get(0).get("operatorId"));
        assertEquals(tag + "d1医生", fields.get(0).get("operatorName"));
        assertNotNull(fields.get(0).get("createdAt"));
        var rv = rows(view, "revisions");
        assertEquals(1, rv.size());
        assertEquals(Set.of("seq", "oldText", "newText", "source", "changedAt", "changedBy", "changedByName"),
                rv.get(0).keySet());
        assertEquals(1, seq(rv.get(0)));
        assertNull(rv.get(0).get("oldText"));
        assertEquals(expected, rv.get(0).get("newText"));
        assertEquals("GROSSING", rv.get(0).get("source"));
        assertEquals(docUid, rv.get(0).get("changedBy"));
        assertEquals(tag + "d1医生", rv.get(0).get("changedByName"));
        assertNotNull(rv.get(0).get("changedAt"));

        assertEquals(5220, process.grossingView(-1L).getCode(), "查无此标本沿用既有 5220");
    }

    @Test
    void appendWithoutNewDescriptionWritesNoFieldNoRevision() {
        String g = "灰白组织一块 " + tag;
        var gross = new LinkedHashMap<String, String>();
        gross.put("大小", "2×1cm");
        gross.put("切面", "灰白");
        Fixture f = specimenWithGross("A2", gross, g);
        assertEquals(2, fieldRows(f.id()).size());
        assertEquals(1, revisionRows(f.id()).size());

        // 补取材、本次不传 gross/grossText：只加蜡块，两张表零变化
        var gr = ok(process.grossing(new GrossingReq(f.id(), null, null, null, true, null,
                List.of(new BlockReq("补取材 " + tag))), doc));
        assertEquals(Boolean.FALSE, gr.get("grossFindingWritten"));
        assertEquals(0, ((Number) gr.get("grossFieldCount")).intValue());
        assertEquals(2, fieldRows(f.id()).size(), "没有新描述就没有新字段行");
        assertEquals(1, revisionRows(f.id()).size(), "没有新描述就没有新版本");
        assertEquals(2, ((Number) gr.get("totalBlockCount")).intValue());

        // 既有规则保留：已有大体所见再传 gross → 5222，两张表仍零变化
        var again = new LinkedHashMap<String, String>();
        again.put("大小", "3×2cm");
        assertEquals(5222, process.grossing(new GrossingReq(f.id(), null, again, null, true, null,
                List.of(new BlockReq("x"))), doc).getCode());
        assertEquals(2, fieldRows(f.id()).size());
        assertEquals(1, revisionRows(f.id()).size());
    }

    // =====================================================================================
    // (b) 诊断覆盖：不同则留痕；空白保留、相同不写；诊断首写也是一版但不是字段
    // =====================================================================================

    @Test
    void diagnoseOverwriteWritesRevisionBlankAndSameDoNot() {
        String g = "灰白组织一块 " + tag;
        String g2 = "灰白组织一块，切面实性，质硬 " + tag;
        var gross = new LinkedHashMap<String, String>();
        gross.put("大小", "2×1cm");

        // ① 传不同的 G2 → 覆盖 + 修订 seq2
        Fixture f1 = specimenWithGross("B1", gross, g);
        String expected1 = "大小：2×1cm。" + g;
        // 修复前：返回体没有 grossRevised，path_gross_revision 表不存在
        var r1 = pathology.diagnose(f1.barcode(), new DiagnoseReq("  " + g2 + "  ", null, "浸润性导管癌 " + tag), doc);
        assertEquals(0, r1.getCode(), r1.getMessage());
        assertEquals(Boolean.TRUE, r1.getData().get("grossRevised"));
        assertEquals(Boolean.FALSE, r1.getData().get("grossKept"));
        assertTrue(r1.getData().keySet().containsAll(Set.of("specimenId", "grossKept", "microKept", "grossRevised")),
                "只加 grossRevised，三个既有键不动：" + r1.getData().keySet());
        assertEquals(g2, jdbc.queryForObject("select gross_finding from path_specimen where id = ?", String.class, f1.id()),
                "显式传值仍覆盖且 trim（v57 契约不变）");
        var revs1 = revisionRows(f1.id());
        assertEquals(2, revs1.size(), "取材首写 + 诊断覆盖 = 两版");
        assertEquals(2, seq(revs1.get(1)));
        assertEquals(expected1, revs1.get(1).get("old_text"), "old_text 是覆盖前的原文");
        assertEquals(g2, revs1.get(1).get("new_text"));
        assertEquals("DIAGNOSE", revs1.get(1).get("source"));
        assertEquals(docUid, revs1.get(1).get("changed_by"), "changed_by = 当前用户");
        assertRecent(revs1.get(1).get("changed_at"));
        // 字段行不因诊断覆盖而动（字段是取材时的事实，不回改）
        assertEquals(List.of("大小"), labels(fieldRows(f1.id())));
        var view1 = ok(process.grossingView(f1.id()));
        assertEquals(Boolean.TRUE, view1.get("fieldsAvailable"));
        var rv1 = rows(view1, "revisions");
        assertEquals(List.of("GROSSING", "DIAGNOSE"), rv1.stream().map(x -> x.get("source")).toList());
        assertEquals(expected1, rv1.get(1).get("oldText"));
        assertEquals(g2, rv1.get(1).get("newText"));
        assertEquals(tag + "d1医生", rv1.get(1).get("changedByName"));
        // 已诊断再诊断仍 4553（状态守卫未动），且不再多出修订
        assertEquals(4553, pathology.diagnose(f1.barcode(), new DiagnoseReq("再改一次", null, "x"), doc).getCode());
        assertEquals(2, revisionRows(f1.id()).size(), "4553 路径不写修订");

        // ② 空白 → 保留原值、不写修订
        Fixture f2 = specimenWithGross("B2", gross, g);
        var r2 = pathology.diagnose(f2.barcode(), new DiagnoseReq("  \t ", null, "慢性炎症 " + tag), doc);
        assertEquals(0, r2.getCode(), r2.getMessage());
        assertEquals(Boolean.TRUE, r2.getData().get("grossKept"));
        assertEquals(Boolean.FALSE, r2.getData().get("grossRevised"));
        assertEquals(1, revisionRows(f2.id()).size(), "空白保留：没有变化就没有版本");
        assertEquals(expected1, jdbc.queryForObject("select gross_finding from path_specimen where id = ?", String.class, f2.id()));

        // ③ 与原值相同（带空白）→ 不写修订
        Fixture f3 = specimenWithGross("B3", gross, g);
        var r3 = pathology.diagnose(f3.barcode(), new DiagnoseReq("  " + expected1 + "\t", null, "慢性炎症 " + tag), doc);
        assertEquals(0, r3.getCode(), r3.getMessage());
        assertEquals(Boolean.FALSE, r3.getData().get("grossRevised"), "trim 后相同不算修订");
        assertEquals(Boolean.FALSE, r3.getData().get("grossKept"), "显式传了值就不叫「保留」（v57 口径）");
        assertEquals(1, revisionRows(f3.id()).size());

        // ④ 从未取材的标本由诊断首写：修订 seq1 old=null source=DIAGNOSE；但 fieldsAvailable=false——
        //    字段与版本是两回事，不拿修订行凑字段
        Fixture f4 = specimen("B4");
        var r4 = pathology.diagnose(f4.barcode(), new DiagnoseReq(g, null, "慢性炎症 " + tag), doc);
        assertEquals(0, r4.getCode(), r4.getMessage());
        assertEquals(Boolean.TRUE, r4.getData().get("grossRevised"));
        var revs4 = revisionRows(f4.id());
        assertEquals(1, revs4.size());
        assertEquals(1, seq(revs4.get(0)));
        assertNull(revs4.get(0).get("old_text"));
        assertEquals(g, revs4.get(0).get("new_text"));
        assertEquals("DIAGNOSE", revs4.get(0).get("source"));
        var view4 = ok(process.grossingView(f4.id()));
        assertEquals(Boolean.FALSE, view4.get("fieldsAvailable"));
        assertTrue(rows(view4, "fields").isEmpty());
        assertEquals(1, rows(view4, "revisions").size());

        // ⑤ 4552（诊断空白）先于一切：不锁行、不写修订
        Fixture f5 = specimenWithGross("B5", gross, g);
        assertEquals(4552, pathology.diagnose(f5.barcode(), new DiagnoseReq(g2, null, "   "), doc).getCode());
        assertEquals(1, revisionRows(f5.id()).size());
    }

    // =====================================================================================
    // (c) 历史标本：不反解析、如实标 fieldsAvailable=false
    // =====================================================================================

    @Test
    void legacySpecimenIsReportedHonestlyWithoutReverseParsing() {
        Fixture f = specimen("C");
        // 模拟 V164 之前的写入：只有一段拼好的文本，没有字段行、没有修订行。
        // 文本刻意长得「像」字段格式——反解析会把它猜成两个字段，那就是假结构化。
        String legacy = "大小：2×1cm；切面：灰白。" + tag;
        jdbc.update("update path_specimen set gross_finding = ? where id = ?", legacy, f.id());

        var view = ok(process.grossingView(f.id()));
        assertEquals(legacy, view.get("grossFinding"), "文本原样回出");
        assertEquals(Boolean.TRUE, view.get("grossFindingPresent"));
        assertEquals(Boolean.FALSE, view.get("fieldsAvailable"), "无字段行就是 false，不从文本反解析");
        assertTrue(rows(view, "fields").isEmpty(), "不许从「大小：2×1cm；切面：灰白」猜出字段行");
        assertTrue(rows(view, "revisions").isEmpty(), "历史写入零修订行（当时根本没采集）");
        assertTrue(fieldRows(f.id()).isEmpty());

        // 历史标本之后被诊断覆盖：修订行的 old_text 是覆盖前的那段文本（真实事实），fieldsAvailable 仍 false
        String g2 = "灰白组织两块 " + tag;
        var r = pathology.diagnose(f.barcode(), new DiagnoseReq(g2, null, "慢性炎症 " + tag), doc);
        assertEquals(0, r.getCode(), r.getMessage());
        assertEquals(Boolean.TRUE, r.getData().get("grossRevised"));
        var after = ok(process.grossingView(f.id()));
        assertEquals(Boolean.FALSE, after.get("fieldsAvailable"));
        var rv = rows(after, "revisions");
        assertEquals(1, rv.size());
        assertEquals(legacy, rv.get(0).get("oldText"));
        assertEquals(g2, rv.get(0).get("newText"));
        assertEquals("DIAGNOSE", rv.get(0).get("source"));
    }

    // =====================================================================================
    // (d) 顺序是入参顺序，不是字母序（带活的对照：字符序确实与入参序不同）
    // =====================================================================================

    @Test
    void fieldOrderFollowsInputNotCodePointOrder() {
        // 质(U+8D28) > 大(U+5927)，颜(U+989C) 最大：按字符序会排成 大小 → 质地 → 颜色，与入参序不同
        var gross = new LinkedHashMap<String, String>();
        gross.put("质地", "质硬");
        gross.put("大小", "3×2×1cm");
        gross.put("颜色", "灰白");
        List<String> input = new ArrayList<>(gross.keySet());
        List<String> sorted = input.stream().sorted().toList();
        assertNotEquals(input, sorted, "对照：这三个字段名按字符序排会被打乱，否则本测试什么都证明不了");

        Fixture f = specimen("D");
        var gr = ok(process.grossing(new GrossingReq(f.id(), null, gross, null, false, null,
                List.of(new BlockReq("x"))), doc));
        assertEquals("质地：质硬；大小：3×2×1cm；颜色：灰白", gr.get("grossFinding"), "文本也按入参序");

        var rows = fieldRows(f.id());
        assertEquals(input, labels(rows), "seq 序 = 入参序");
        assertEquals(List.of(1, 2, 3), rows.stream().map(r -> seq(r)).toList());

        var view = ok(process.grossingView(f.id()));
        var fields = rows(view, "fields");
        assertEquals(input, labels(fields), "读端点按 seq 升序 = 入参序");
        assertNotEquals(sorted, labels(fields), "不是字符序");
    }

    // =====================================================================================
    // (e) 迁移扫描：V164 零 update / 零 insert；列长与常量一致；对照组抓得到；探针证明扫描器在咬
    // =====================================================================================

    /** 顶层 update 语句的语法形态：行首（允许缩进）update <表> set。不匹配裸 token——注释里「零条 update」这句话本身就含它 */
    private static final Pattern TOP_LEVEL_UPDATE = Pattern.compile("(?im)^\\s*update\\s+\\w+\\s+set\\b");
    /** 顶层 insert 语句的语法形态：行首 insert into <表>——零回填也不许伪造历史行 */
    private static final Pattern TOP_LEVEL_INSERT = Pattern.compile("(?im)^\\s*insert\\s+into\\s+\\w+");

    /** 先剥块注释（斜杠星 … 星斜杠），再剥每行 -- 之后的部分 */
    private static String stripSqlComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.lines()
                .map(l -> { int i = l.indexOf("--"); return i >= 0 ? l.substring(0, i) : l; })
                .collect(Collectors.joining("\n"));
    }

    private static boolean hasTopLevelUpdate(String sql) {
        return TOP_LEVEL_UPDATE.matcher(stripSqlComments(sql)).find();
    }

    private static boolean hasTopLevelInsert(String sql) {
        return TOP_LEVEL_INSERT.matcher(stripSqlComments(sql)).find();
    }

    @Test
    void migrationV164IsZeroBackfill() {
        String v164 = read(V164);
        assertTrue(v164.contains("create table path_gross_field")
                        && v164.contains("create table path_gross_revision")
                        && v164.contains("unique (specimen_id, seq)")
                        && v164.contains("check (source in ('GROSSING', 'DIAGNOSE'))"),
                "读到的不是 V164 本尊（两张表 + 唯一约束 + source 白名单）");
        assertTrue(v164.contains("零条 update"),
                "注释里必须写明零回填纪律——这句本身含 update 一词，正是剥注释要处理的活样本");
        assertTrue(v164.contains("fieldsAvailable"), "头注释须写清零回填后果：历史标本无字段行、读端点显式标 fieldsAvailable:false");
        assertFalse(hasTopLevelUpdate(v164), "V164 不许有任何 update 语句");
        assertFalse(hasTopLevelInsert(v164), "V164 不许 insert 任何行：不从 gross_finding 反解析出字段行、不伪造修订历史");

        // 活的对照组：既有迁移里确实有 update / insert，扫描器必须抓到——否则上面的绿不说明任何事
        assertTrue(hasTopLevelUpdate(read(CONTROL_WITH_UPDATE)),
                "对照组 " + CONTROL_WITH_UPDATE + " 确有 update md_drug set …，扫描器没抓到就是扫描器坏了");
        assertTrue(hasTopLevelInsert(read(CONTROL_WITH_INSERT)),
                "对照组 " + CONTROL_WITH_INSERT + " 确有 insert into sys_config …，扫描器没抓到就是扫描器坏了");
    }

    @Test
    void zeroBackfillDetectorActuallyBites() {
        String v164 = read(V164);
        assertTrue(hasTopLevelUpdate(v164 + "\nupdate path_specimen set gross_finding = btrim(gross_finding);\n"),
                "补一条回填语句后必须被抓到");
        assertTrue(hasTopLevelUpdate(v164 + "\n    UPDATE path_gross_field SET value = 'x';\n"), "大小写与缩进不影响");
        assertFalse(hasTopLevelUpdate(v164 + "\n-- update path_specimen set gross_finding = null;\n"), "行注释里的不算");
        assertFalse(hasTopLevelUpdate(v164 + "\n/* update path_specimen\n   set gross_finding = null; */\n"), "块注释里的不算");
        assertFalse(hasTopLevelUpdate("comment on column path_gross_field.value is 'update set 之类的字样';"),
                "裸 token 不算：须是行首 update <表> set 的语法形态");

        assertTrue(hasTopLevelInsert(v164
                        + "\ninsert into path_gross_field(specimen_id, seq, label, value) select id, 1, 'x', gross_finding from path_specimen;\n"),
                "补一条反解析回填后必须被抓到");
        assertFalse(hasTopLevelInsert(v164 + "\n-- insert into path_gross_revision ...\n"), "行注释里的不算");
        assertFalse(hasTopLevelInsert("comment on table path_gross_field is '不 insert into 任何历史行';"), "裸 token 不算");
    }

    @Test
    void columnLengthsMatchControllerConstants() {
        String ctl = read(CONTROLLER);
        int labelMax = intConst(ctl, "GROSS_LABEL_MAX");
        int valueMax = intConst(ctl, "GROSS_VALUE_MAX");
        String v164 = stripSqlComments(read(V164));
        assertEquals(labelMax, columnLength(v164, "label"), "path_gross_field.label 列长须 = GROSS_LABEL_MAX");
        assertEquals(valueMax, columnLength(v164, "value"), "path_gross_field.value 列长须 = GROSS_VALUE_MAX");
        // 对照：常量与列长都是真读出来的正数，不是两个 0 撞在一起
        assertTrue(labelMax > 0 && valueMax > labelMax, "读到的常量应为正且 VALUE > LABEL：" + labelMax + "/" + valueMax);
    }

    private static int intConst(String javaSrc, String name) {
        Matcher m = Pattern.compile("int\\s+" + name + "\\s*=\\s*(\\d+)\\s*;").matcher(javaSrc);
        assertTrue(m.find(), "控制器里找不到常量 " + name);
        return Integer.parseInt(m.group(1));
    }

    private static int columnLength(String sql, String column) {
        Matcher m = Pattern.compile("(?m)^\\s*" + column + "\\s+varchar\\((\\d+)\\)").matcher(sql);
        assertTrue(m.find(), "V164 里找不到列 " + column + " varchar(n)");
        return Integer.parseInt(m.group(1));
    }

    // ==================== 夹具与取值 ====================

    // v58 审阅补（审阅者探针实测：修复前 5224 路径 gross_finding 已写、字段/修订/蜡块/节点全无）：
    // 当前用户解析不出、字段名 trim 后重复，两条被拒路径都不得留下任何写入。
    @Test
    void unknownUserAndDuplicateLabelWriteNothing() {
        Fixture f = specimen("R");
        var gross = new LinkedHashMap<String, String>();
        gross.put("大小", "3×2×1cm");
        Authentication ghost = new UsernamePasswordAuthenticationToken("ghost_" + tag, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));   // sys_user 里没有这个人
        var r = process.grossing(new GrossingReq(f.id(), null, gross, "切面灰白 " + tag, false, null,
                List.of(new BlockReq("肿物中心"))), ghost);
        assertEquals(5224, r.getCode(), r.getMessage());
        assertNothingWritten(f.id(), "5224 路径");

        var dup = new LinkedHashMap<String, String>();
        dup.put("大小", "a");
        dup.put(" 大小 ", "b");            // Jackson 里是两个键，trim 后同名
        var d = process.grossing(new GrossingReq(f.id(), null, dup, null, false, null,
                List.of(new BlockReq("肿物中心"))), doc);
        assertEquals(5222, d.getCode(), d.getMessage());
        assertTrue(d.getMessage().contains("重复"), d.getMessage());
        assertNothingWritten(f.id(), "字段名重复路径");
    }

    private void assertNothingWritten(long sid, String why) {
        assertNull(jdbc.queryForObject("select gross_finding from path_specimen where id = ?", String.class, sid), why);
        assertEquals(0L, rowsOf("path_gross_field", sid), why);
        assertEquals(0L, rowsOf("path_gross_revision", sid), why);
        assertEquals(0L, rowsOf("path_block", sid), why);
        Long nodes = jdbc.queryForObject(
                "select count(*) from path_process where specimen_id = ? and node = 'GROSSING'", Long.class, sid);
        assertEquals(0L, nodes, why);
    }

    private long rowsOf(String table, long sid) {
        Long n = jdbc.queryForObject("select count(*) from " + table + " where specimen_id = ?", Long.class, sid);
        return n == null ? 0L : n;
    }

    private record Fixture(long id, String barcode, Long orderId) {}

    /** 一条已收费门诊病理医嘱 → 既有 collect 登记打码 → 既有 receive 核收（不取材） */
    private Fixture specimen(String suffix) {
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag + suffix);
        var c = pathology.collect(orderId, "标本" + suffix);
        assertEquals(0, c.getCode(), c.getMessage());
        String barcode = (String) c.getData().get("barcode");
        assertNotNull(barcode);
        var rc = pathology.receive(barcode);
        assertEquals(0, rc.getCode(), rc.getMessage());
        long id = jdbc.queryForObject("select id from path_specimen where barcode = ?", Long.class, barcode);
        return new Fixture(id, barcode, orderId);
    }

    /** 核收后走真实 grossing：结构化字段 + 自由文本 + 一块蜡块 */
    private Fixture specimenWithGross(String suffix, Map<String, String> gross, String freeText) {
        Fixture f = specimen(suffix);
        var gr = process.grossing(new GrossingReq(f.id(), null, gross, freeText, false, null,
                List.of(new BlockReq("肿物中心"))), doc);
        assertEquals(0, gr.getCode(), gr.getMessage());
        assertEquals(Boolean.TRUE, gr.getData().get("grossFindingWritten"), "夹具前提：取材端点真的写了大体所见");
        assertEquals("RECEIVED", jdbc.queryForObject(
                "select status from path_specimen where id = ?", String.class, f.id()));
        return f;
    }

    private List<Map<String, Object>> fieldRows(long specimenId) {
        return jdbc.queryForList("""
                select seq, label, value, operator_id, created_at
                from path_gross_field where specimen_id = ? order by seq asc
                """, specimenId);
    }

    private List<Map<String, Object>> revisionRows(long specimenId) {
        return jdbc.queryForList("""
                select seq, old_text, new_text, source, changed_by, changed_at
                from path_gross_revision where specimen_id = ? order by seq asc
                """, specimenId);
    }

    private static List<Object> labels(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("label")).toList();
    }

    private static int seq(Map<String, Object> row) {
        return ((Number) row.get("seq")).intValue();
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

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    private Long userId(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    // 源码读取（照抄 V57GrossKeepTest：从 user.dir 向上找仓库根）

    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("modules")) && Files.isDirectory(p.resolve("platform"))) {
                return p;
            }
        }
        return fail("定位不到仓库根（从 " + System.getProperty("user.dir") + " 向上找 modules/ 与 platform/）");
    }

    private static String read(String rel) {
        try {
            return Files.readString(repoRoot().resolve(rel), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fail("读不到 " + rel + "：" + e.getMessage());
        }
    }
}
