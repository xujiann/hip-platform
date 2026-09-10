package cn.hip.server;

import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyController.DiagnoseReq;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossReviseReq;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * v59 车道 A（2530 复核打回）：<b>取材字段随修订版本化 + 取材修订入口 + 补取材记描述 + 模板码落库 +
 * 旧页写死描述</b>（V166）。
 *
 * <h2>修复前的反向事实（主控逐条实测坐实）</h2>
 * <ol>
 *   <li>字段行是一次性快照：{@code path_gross_field} 只有 insert、唯一约束 (specimen_id, seq)，字段一旦落库不能改不能补，
 *       字段永远是第 1 版；根本没有 {@code PUT /grossing/{id}/fields} 这个端点（本类 (b) 在修复前编译不过），
 *       读端点没有 {@code fieldsRevisionSeq / textRevisionSeq / fieldsCurrent}——诊断改了文本后字段行不跟着改，
 *       查看大体所见与轨迹抽屉并排自相矛盾而无任何标记。</li>
 *   <li>已有大体所见后任何取材（含 append=true）再传描述一律 5222——已诊断标本的补取材（RESAMPLE）没有任何入口
 *       记录该次取材的大体描述（本类 (e) 在修复前是 5222）。</li>
 *   <li>模板代码只拼进 {@code path_process.remark}，不落标本：{@code path_gross_revision} 没有 {@code template_code} 列
 *       （本类 (a) 对该列的查询在修复前 BadSqlGrammar）。</li>
 *   <li>旧页「专科流程」的「取材打码」把写死的 {@code specimenDesc:'手术切除标本'} 落 {@code path_specimen.specimen_desc}，
 *       后端 {@code POST /api/pathology/specimens} 描述可空（本类 (f) 在修复前 collect(orderId, null) 返回 0）。</li>
 * </ol>
 *
 * <h2>本类钉住的契约</h2>
 * <ul>
 *   <li>(a) 取材带模板 → 修订 seq1 的 template_code 落库（大写规范化）、读端点 revisions[].templateCode / sourceName；不带模板为 null。</li>
 *   <li>(b) 修订字段 → 修订 seq2 source=GROSSING_EDIT old/new 正确、字段行落 revision_seq=2（第 1 版字段行原样保留）、
 *       读端点 fields 为新版、fieldsRevisionSeq=2、fieldsCurrent=true；相同 / 空 / 未知模板 / 幽灵用户各条被拒路径零写入。</li>
 *   <li>(c) 诊断覆盖 → 修订 seq3 DIAGNOSE，fieldsCurrent=false、textRevisionSeq=3、fields 仍第 2 版。</li>
 *   <li>(d) 诊断后修订 → 5221 零写入；尚无大体所见时修订 → 5221 零写入。</li>
 *   <li>(e) 已有大体所见后 append=true 带描述 → 新版本，文本以「。补取材：」拼接、字段在新版；append=false → 仍 5222 零写入；
 *       历史标本（无修订行）追加 → 第 1 版 old_text 是那段历史文本（真实事实）；已诊断标本补取材同样出新版本；追加后超长 → 5222 零写入。</li>
 *   <li>(f) collect 无描述 / 空白 → 4554 且 path_specimen 零行；有描述 → 成功且描述原样落库；重复登记仍 4550。</li>
 *   <li>(g) SpecialtyView 剥注释后无 POST /pathology/specimens 调用、无「手术切除标本」字样；对照组 GrossingPanel 含
 *       POST /pathology/process/grossing；检测器会咬人（GET 列表 / 核收 PUT / 文案里的端点名都不算）。</li>
 *   <li>(h) V166 零 update / 零 insert 扫描（剥注释、语法形态、活的对照组、探针）。</li>
 * </ul>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：夹具时刻由 SQL 按 {@code now()} / {@code current_date} 现算；修订时刻用「与库端 now() 偏差 ≤ 5 分钟」
 * 的容差断言（照抄 V58GrossFieldsTest.assertRecent）。事务回滚，库里不留痕。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V59GrossReviseTest {

    private static final String V166 = "server/src/main/resources/db/migration/V166__path_gross_revise.sql";
    private static final String OLD_PAGE = "frontend/shell/src/views/medtech/SpecialtyView.vue";
    /** 对照组：工作台取材面板确实含 POST /pathology/process/grossing 调用 */
    private static final String GROSSING_PANEL = "frontend/shell/src/views/medtech/pathology/GrossingPanel.vue";
    /** 对照组：确实含顶层 update 语句的既有迁移（V22 {@code update md_drug set abx_level = 1 where antibiotic;}） */
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
        tag = "V59A" + Long.toHexString(System.nanoTime());
        doc = doctorAuth(tag + "d1");
        docUid = userId(tag + "d1");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "取材修订测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "取材修订患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
    }

    // =====================================================================================
    // (a) 取材带模板 → 修订行落 template_code；读端点 templateCode / sourceName
    // =====================================================================================

    @Test
    void grossingStoresTemplateCodeOnRevision() {
        var gross = new LinkedHashMap<String, String>();
        gross.put("送检组织", "灰白破碎组织");
        gross.put("块数", "3");
        gross.put("最大径", "0.3cm");
        String free = "质软 " + tag;
        Fixture f = specimen("A");

        // 模板码传小写：端点 trimUpper 规范化后落库（与 5225 校验用的目录键一致）
        var gr = ok(process.grossing(new GrossingReq(f.id(), "gi_biopsy", gross, free, false, null,
                List.of(new BlockReq("全部取材"))), doc));
        assertEquals(1, ((Number) gr.get("grossRevisionSeq")).intValue(), "首写是第 1 版");
        assertEquals(3, ((Number) gr.get("grossFieldCount")).intValue());

        // 修复前：path_gross_revision 没有 template_code 列——这条查询直接 BadSqlGrammar；模板码只拼在 path_process.remark
        var revs = revisionRows(f.id());
        assertEquals(1, revs.size());
        assertEquals("GI_BIOPSY", revs.get(0).get("template_code"), "模板码落修订行（大写规范化）");
        assertEquals("GROSSING", revs.get(0).get("source"));
        assertNull(revs.get(0).get("old_text"));
        assertRecent(revs.get(0).get("changed_at"));
        for (var r : fieldRows(f.id())) assertEquals(1, revSeq(r), "首写的字段行属于第 1 版");

        var view = ok(process.grossingView(f.id()));
        var rv = rows(view, "revisions");
        assertEquals(1, rv.size());
        assertEquals("GI_BIOPSY", rv.get(0).get("templateCode"));
        assertEquals("取材首写", rv.get(0).get("sourceName"));
        assertEquals(1, ((Number) view.get("fieldsRevisionSeq")).intValue());
        assertEquals(1, ((Number) view.get("textRevisionSeq")).intValue());
        assertEquals(Boolean.TRUE, view.get("fieldsCurrent"));
        assertEquals(Boolean.TRUE, view.get("fieldsAvailable"));

        // 不用模板：template_code 如实为 null，不拿别的东西凑
        Fixture f0 = specimen("A0");
        ok(process.grossing(new GrossingReq(f0.id(), null, gross, null, false, null,
                List.of(new BlockReq("x"))), doc));
        assertNull(revisionRows(f0.id()).get(0).get("template_code"));
        assertNull(rows(ok(process.grossingView(f0.id())), "revisions").get(0).get("templateCode"));

        // 未知模板：既有 5225，零写入
        Fixture f1 = specimen("A1");
        assertEquals(5225, process.grossing(new GrossingReq(f1.id(), "NO_SUCH_TPL", gross, null, false, null,
                List.of(new BlockReq("x"))), doc).getCode());
        assertNothingWritten(f1.id(), "5225 路径");
    }

    // =====================================================================================
    // (b) 修订字段：新版本 GROSSING_EDIT，字段行落 revision_seq=2，读端点回新版且 fieldsCurrent=true
    // =====================================================================================

    @Test
    void reviseFieldsCreatesNewVersionAndReadReturnsIt() {
        var g1 = new LinkedHashMap<String, String>();
        g1.put("大小", "3×2×1cm");
        g1.put("颜色", "灰白");
        String free1 = "切面实性 " + tag;
        Fixture f = specimenWithGross("B", g1, free1);
        String expected1 = "大小：3×2×1cm；颜色：灰白。" + free1;
        assertEquals(expected1, grossOf(f.id()));

        var g2 = new LinkedHashMap<String, String>();
        g2.put("大小", " 3.5×2×1cm ");     // 敲错的尺寸改回来；trim 与 assembleGross 同取舍
        g2.put("颜色", "灰白");
        g2.put("质地", "质硬");             // 补一个字段
        String free2 = "切面实性，局灶出血 " + tag;
        String expected2 = "大小：3.5×2×1cm；颜色：灰白；质地：质硬。" + free2;

        // 修复前：根本没有这个端点，字段永远是第 1 版
        var r = ok(process.reviseGrossFields(f.id(), new GrossReviseReq("generic", g2, free2), doc));
        assertEquals(Set.of("revisionSeq", "grossFieldCount", "grossFinding"), r.keySet());
        assertEquals(2, ((Number) r.get("revisionSeq")).intValue());
        assertEquals(3, ((Number) r.get("grossFieldCount")).intValue());
        assertEquals(expected2, r.get("grossFinding"));
        assertEquals(expected2, grossOf(f.id()), "gross_finding 更新为新文本");

        var revs = revisionRows(f.id());
        assertEquals(2, revs.size(), "取材首写 + 取材修订 = 两版");
        assertEquals(2, seq(revs.get(1)));
        assertEquals(expected1, revs.get(1).get("old_text"), "old_text 是修订前的原文");
        assertEquals(expected2, revs.get(1).get("new_text"));
        assertEquals("GROSSING_EDIT", revs.get(1).get("source"));
        assertEquals("GENERIC", revs.get(1).get("template_code"));
        assertEquals(docUid, revs.get(1).get("changed_by"));
        assertRecent(revs.get(1).get("changed_at"));

        // 第 1 版字段行原样保留（两行、revision_seq=1）；第 2 版三行、seq 在本版内从 1 起
        var v1 = fieldRowsOfRevision(f.id(), 1);
        assertEquals(List.of("大小", "颜色"), labels(v1), "第 1 版字段行不动");
        assertEquals(List.of("3×2×1cm", "灰白"), values(v1));
        var v2 = fieldRowsOfRevision(f.id(), 2);
        assertEquals(List.of("大小", "颜色", "质地"), labels(v2));
        assertEquals(List.of("3.5×2×1cm", "灰白", "质硬"), values(v2), "值已 trim");
        assertEquals(List.of(1, 2, 3), v2.stream().map(V59GrossReviseTest::seq).toList(), "seq 在本版内从 1 起");
        for (var row : v2) assertEquals(docUid, row.get("operator_id"));
        assertEquals(5, fieldRows(f.id()).size(), "两版共五行，没有删旧行");
        Long nodes = jdbc.queryForObject(
                "select count(*) from path_process where specimen_id = ?", Long.class, f.id());
        assertEquals(1L, nodes, "修订不是流转环节：只有取材那一个 GROSSING 节点，修订不打点");

        var view = ok(process.grossingView(f.id()));
        assertEquals(Boolean.TRUE, view.get("fieldsAvailable"));
        var fields = rows(view, "fields");
        assertEquals(List.of("大小", "颜色", "质地"), labels(fields), "读端点回最大 revision_seq 那一版");
        assertEquals(Set.of("seq", "label", "value", "createdAt", "operatorId", "operatorName"), fields.get(0).keySet(),
                "字段行的键不变（v58 契约）");
        assertEquals(2, ((Number) view.get("fieldsRevisionSeq")).intValue());
        assertEquals(2, ((Number) view.get("textRevisionSeq")).intValue());
        assertEquals(Boolean.TRUE, view.get("fieldsCurrent"));
        assertEquals(expected2, view.get("grossFinding"));
        var rv = rows(view, "revisions");
        assertEquals(List.of("GROSSING", "GROSSING_EDIT"), rv.stream().map(x -> x.get("source")).toList());
        assertEquals(List.of("取材首写", "取材修订"), rv.stream().map(x -> x.get("sourceName")).toList());
        assertEquals("GENERIC", rv.get(1).get("templateCode"));
        assertEquals(expected1, rv.get(1).get("oldText"));
        assertEquals(tag + "d1医生", rv.get(1).get("changedByName"));

        // 被拒路径全部零写入：相同文本 / 空内容 / 未知模板 / 幽灵用户 / 查无此标本
        assertEquals(5222, process.reviseGrossFields(f.id(), new GrossReviseReq(null, g2, free2), doc).getCode(),
                "与当前相同：没有变化就没有版本");
        assertEquals(5222, process.reviseGrossFields(f.id(), new GrossReviseReq(null, null, "   "), doc).getCode(), "空内容");
        assertEquals(5222, process.reviseGrossFields(f.id(), null, doc).getCode(), "空 body");
        assertEquals(5225, process.reviseGrossFields(f.id(), new GrossReviseReq("NO_SUCH_TPL", g1, null), doc).getCode());
        var dup = new LinkedHashMap<String, String>();
        dup.put("大小", "a");
        dup.put(" 大小 ", "b");
        assertEquals(5222, process.reviseGrossFields(f.id(), new GrossReviseReq(null, dup, null), doc).getCode(), "字段名重复");
        Authentication ghost = new UsernamePasswordAuthenticationToken("ghost_" + tag, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
        assertEquals(5224, process.reviseGrossFields(f.id(), new GrossReviseReq(null, g1, "改回去 " + tag), ghost).getCode());
        assertEquals(5220, process.reviseGrossFields(-1L, new GrossReviseReq(null, g1, null), doc).getCode());
        assertEquals(expected2, grossOf(f.id()), "被拒路径不改文本");
        assertEquals(2, revisionRows(f.id()).size(), "被拒路径不出版本");
        assertEquals(5, fieldRows(f.id()).size(), "被拒路径不落字段行");
    }

    // =====================================================================================
    // (c) 诊断覆盖：第 3 版 DIAGNOSE 只改文本，字段仍第 2 版，读端点如实标 fieldsCurrent=false
    // =====================================================================================

    @Test
    void diagnoseOverwriteLeavesFieldsAtTheirOwnVersion() {
        Fixture f = revisedFixture("C");
        String g3 = "诊断时改写的大体所见 " + tag;
        var d = pathology.diagnose(f.barcode(), new DiagnoseReq(g3, "镜下 " + tag, "浸润性导管癌 " + tag), doc);
        assertEquals(0, d.getCode(), d.getMessage());
        assertEquals(Boolean.TRUE, d.getData().get("grossRevised"));

        var revs = revisionRows(f.id());
        assertEquals(3, revs.size());
        assertEquals("DIAGNOSE", revs.get(2).get("source"));
        assertNull(revs.get(2).get("template_code"), "诊断覆盖没有模板");
        assertEquals(g3, revs.get(2).get("new_text"));

        var view = ok(process.grossingView(f.id()));
        assertEquals(g3, view.get("grossFinding"));
        assertEquals(Boolean.TRUE, view.get("fieldsAvailable"));
        // 修复前：读端点没有这三个键，两处并排自相矛盾而无任何标记
        assertEquals(2, ((Number) view.get("fieldsRevisionSeq")).intValue(), "字段仍是第 2 版");
        assertEquals(3, ((Number) view.get("textRevisionSeq")).intValue(), "文本已到第 3 版");
        assertEquals(Boolean.FALSE, view.get("fieldsCurrent"), "两者不同版：以文本为准");
        assertEquals(List.of("大小", "颜色", "质地"), labels(rows(view, "fields")), "fields 仍是第 2 版那套");
        assertEquals(List.of("取材首写", "取材修订", "诊断修订"),
                rows(view, "revisions").stream().map(x -> x.get("sourceName")).toList());
    }

    // =====================================================================================
    // (d) 诊断后修订 → 5221 零写入；尚无大体所见 → 5221 零写入
    // =====================================================================================

    @Test
    void reviseAfterDiagnosisOrWithoutGrossIsRejectedWithoutWrites() {
        Fixture f = revisedFixture("D");
        String g3 = "诊断时改写 " + tag;
        assertEquals(0, pathology.diagnose(f.barcode(), new DiagnoseReq(g3, null, "慢性炎症 " + tag), doc).getCode());
        var g = new LinkedHashMap<String, String>();
        g.put("大小", "9×9×9cm");
        var r = process.reviseGrossFields(f.id(), new GrossReviseReq("GENERIC", g, "诊断后还想改 " + tag), doc);
        assertEquals(5221, r.getCode(), r.getMessage());
        assertEquals(g3, grossOf(f.id()), "5221 路径不改文本");
        assertEquals(3, revisionRows(f.id()).size(), "5221 路径不出版本");
        assertEquals(5, fieldRows(f.id()).size(), "5221 路径不落字段行");

        // 尚无大体所见：本端点只修订不首写（首写走取材登记，否则之后正常取材会被「已有大体所见」挡住）
        Fixture f0 = specimen("D0");
        var r0 = process.reviseGrossFields(f0.id(), new GrossReviseReq(null, g, null), doc);
        assertEquals(5221, r0.getCode(), r0.getMessage());
        assertNothingWritten(f0.id(), "尚无大体所见的修订路径");

        // 未核收 / 已拒收同样 5221
        Fixture fc = collectedOnly("D1");
        assertEquals(5221, process.reviseGrossFields(fc.id(), new GrossReviseReq(null, g, null), doc).getCode());
        assertNothingWritten(fc.id(), "未核收的修订路径");
    }

    // =====================================================================================
    // (e) 补取材记描述：append=true 带描述出新版本（「。补取材：」拼接、字段在新版）；append=false 仍 5222
    // =====================================================================================

    @Test
    void appendWithDescriptionCreatesNewVersionButAppendFalseStillRejected() {
        var g1 = new LinkedHashMap<String, String>();
        g1.put("大小", "2×1cm");
        String free1 = "灰白组织一块 " + tag;
        Fixture f = specimenWithGross("E", g1, free1);
        String expected1 = "大小：2×1cm。" + free1;

        var g2 = new LinkedHashMap<String, String>();
        g2.put("块数", "2");
        g2.put("最大径", "0.5cm");
        String free2 = "补取材组织 " + tag;
        // 修复前：已有大体所见后任何取材再传描述一律 5222
        var gr = ok(process.grossing(new GrossingReq(f.id(), "GI_BIOPSY", g2, free2, true, null,
                List.of(new BlockReq("补取材 " + tag))), doc));
        String expected2 = expected1 + "。补取材：" + "块数：2；最大径：0.5cm。" + free2;
        assertEquals(expected2, gr.get("grossFinding"), "返回体是拼接后的全文");
        assertEquals(Boolean.TRUE, gr.get("grossFindingWritten"));
        assertEquals(2, ((Number) gr.get("grossFieldCount")).intValue());
        assertEquals(2, ((Number) gr.get("grossRevisionSeq")).intValue());
        assertEquals(2, ((Number) gr.get("totalBlockCount")).intValue(), "蜡块照常追加");
        assertEquals(expected2, grossOf(f.id()));

        var revs = revisionRows(f.id());
        assertEquals(2, revs.size());
        assertEquals(expected1, revs.get(1).get("old_text"), "old_text 是追加前的原文");
        assertEquals(expected2, revs.get(1).get("new_text"));
        assertEquals("GROSSING", revs.get(1).get("source"), "补取材仍是取材端点写的：source 不变");
        assertEquals("GI_BIOPSY", revs.get(1).get("template_code"));
        assertEquals(List.of("大小"), labels(fieldRowsOfRevision(f.id(), 1)), "第 1 版字段行不动");
        assertEquals(List.of("块数", "最大径"), labels(fieldRowsOfRevision(f.id(), 2)), "本次字段落在第 2 版");
        var view = ok(process.grossingView(f.id()));
        assertEquals(2, ((Number) view.get("fieldsRevisionSeq")).intValue());
        assertEquals(2, ((Number) view.get("textRevisionSeq")).intValue());
        assertEquals(Boolean.TRUE, view.get("fieldsCurrent"));
        assertEquals(List.of("块数", "最大径"), labels(rows(view, "fields")));
        assertEquals("补取材追加", rows(view, "revisions").get(1).get("sourceName"),
                "GROSSING 且带原文的是追加，不叫「首写」");

        // 追加后超长 → 5222，在任何写入之前：文本 / 版本 / 字段 / 蜡块 / 节点全不动
        var before = snapshot(f.id());
        assertEquals(5222, process.grossing(new GrossingReq(f.id(), null, null, "x".repeat(1995), true, null,
                List.of(new BlockReq("y"))), doc).getCode());
        assertEquals(before, snapshot(f.id()), "超长路径零写入");

        // append=false 仍 5222：历史标本（gross_finding 由 SQL 置入、无蜡块、无修订行）首次取材还传描述
        Fixture legacy = specimen("E2");
        String legacyText = "大小：2×1cm；切面：灰白。" + tag;
        jdbc.update("update path_specimen set gross_finding = ? where id = ?", legacyText, legacy.id());
        var rej = process.grossing(new GrossingReq(legacy.id(), null, g2, free2, false, null,
                List.of(new BlockReq("x"))), doc);
        assertEquals(5222, rej.getCode(), rej.getMessage());
        assertTrue(rej.getMessage().contains("append=true"), rej.getMessage());
        assertEquals(legacyText, grossOf(legacy.id()));
        assertEquals(0L, rowsOf("path_gross_revision", legacy.id()));
        assertEquals(0L, rowsOf("path_gross_field", legacy.id()));
        assertEquals(0L, rowsOf("path_block", legacy.id()));

        // 同一历史标本 append=true：第 1 版的 old_text 就是那段历史文本（真实事实），字段落在第 1 版
        var la = ok(process.grossing(new GrossingReq(legacy.id(), null, g2, null, true, null,
                List.of(new BlockReq("x"))), doc));
        assertEquals(1, ((Number) la.get("grossRevisionSeq")).intValue());
        assertEquals(legacyText + "。补取材：块数：2；最大径：0.5cm", la.get("grossFinding"));
        var lr = revisionRows(legacy.id());
        assertEquals(1, lr.size());
        assertEquals(legacyText, lr.get(0).get("old_text"));
        assertEquals(List.of("块数", "最大径"), labels(fieldRowsOfRevision(legacy.id(), 1)));
        var lv = ok(process.grossingView(legacy.id()));
        assertEquals(1, ((Number) lv.get("fieldsRevisionSeq")).intValue());
        assertEquals(Boolean.TRUE, lv.get("fieldsCurrent"));

        // 已诊断标本的补取材（RESAMPLE）：诊断空白保留原值 → append=true 带描述 → 新版本；这正是修复前没有任何入口的场景
        Fixture fd = specimenWithGross("E3", g1, free1);
        var dg = pathology.diagnose(fd.barcode(), new DiagnoseReq(null, null, "需补取材 " + tag), doc);
        assertEquals(0, dg.getCode(), dg.getMessage());
        assertEquals(Boolean.TRUE, dg.getData().get("grossKept"));
        var rs = ok(process.grossing(new GrossingReq(fd.id(), null, g2, free2, true, null,
                List.of(new BlockReq("RESAMPLE " + tag))), doc));
        assertEquals(expected2, rs.get("grossFinding"));
        assertEquals(2, ((Number) rs.get("grossRevisionSeq")).intValue());
        assertEquals(List.of("GROSSING", "GROSSING"),
                revisionRows(fd.id()).stream().map(x -> x.get("source")).toList());
        assertEquals(expected2, grossOf(fd.id()));
    }

    // =====================================================================================
    // (f) 取材登记描述必填：空白 → 4554 且零行；有描述 → 成功
    // =====================================================================================

    @Test
    void collectRequiresSpecimenDesc() {
        Long orderId = newOrder("F");
        // 修复前：描述可空，collect(orderId, null) 直接落一行 specimen_desc 为空的标本
        assertEquals(4554, pathology.collect(orderId, null).getCode());
        assertEquals(4554, pathology.collect(orderId, "   \t ").getCode());
        Long n = jdbc.queryForObject("select count(*) from path_specimen where order_id = ?", Long.class, orderId);
        assertEquals(0L, n, "4554 路径不落任何标本行");

        String desc = "胃窦活检组织 " + tag;
        var c = pathology.collect(orderId, desc);
        assertEquals(0, c.getCode(), c.getMessage());
        String barcode = (String) c.getData().get("barcode");
        assertNotNull(barcode);
        assertTrue(barcode.startsWith("PB"));
        assertEquals(desc, jdbc.queryForObject(
                "select specimen_desc from path_specimen where barcode = ?", String.class, barcode), "描述原样落库");
        assertEquals(4550, pathology.collect(orderId, desc).getCode(), "重复登记仍是既有 4550");
    }

    // =====================================================================================
    // (g) 源码扫描：旧页不再 POST /pathology/specimens、无写死描述；对照组是活的；检测器会咬人
    // =====================================================================================

    /** 登记端点的调用形态：client.post(<引号或反引号> /pathology/specimens <引号或反引号>——列表 GET、核收 PUT 都不算 */
    private static final Pattern POST_SPECIMENS = Pattern.compile(
            "client\\.post\\(\\s*[`'\"]/pathology/specimens[`'\"]");
    /** 对照组的调用形态：client.post(<引号> /pathology/process/grossing <引号> */
    private static final Pattern POST_GROSSING = Pattern.compile(
            "client\\.post\\(\\s*[`'\"]/pathology/process/grossing[`'\"]");
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 行注释：// 前是行首或空白（不碰 http:// 这类紧跟冒号的） */
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(^|\\s)//.*$");

    static String stripComments(String src) {
        String s = HTML_COMMENT.matcher(src).replaceAll("");
        s = BLOCK_COMMENT.matcher(s).replaceAll("");
        return LINE_COMMENT.matcher(s).replaceAll("$1");
    }

    static boolean postsSpecimens(String src) {
        return POST_SPECIMENS.matcher(src).find();
    }

    @Test
    void legacySpecialtyPageNoLongerPostsSpecimensWithHardcodedDesc() {
        String raw = read(OLD_PAGE);
        String old = stripComments(raw);
        assertFalse(postsSpecimens(old), "旧页「专科流程」不得再调 POST /pathology/specimens——那是写死描述落库的路径");
        assertFalse(old.contains("手术切除标本"), "剥注释后不得再有写死的「手术切除标本」");
        assertFalse(raw.contains("手术切除标本"), "连注释里都不留这个假事实");
        assertTrue(raw.contains("/pathology/workbench"), "待取材行的按钮应把人带去病理工作台登记");
        assertTrue(raw.contains("去病理工作台登记"), "按钮文案");

        // 活的对照组：工作台取材面板剥注释后仍含 POST /pathology/process/grossing——否则上面的 false 只说明扫描器没在读文件
        String panel = stripComments(read(GROSSING_PANEL));
        assertTrue(POST_GROSSING.matcher(panel).find(), "对照组：GrossingPanel.vue 剥注释后应含 client.post('/pathology/process/grossing'）");
        assertTrue(panel.contains("/pathology/process/grossing"));
        assertTrue(panel.contains("/fields"), "对照组：取材面板走了 v59 修订端点");
    }

    @Test
    void postSpecimensDetectorActuallyBites() {
        String hit = "const resp = await client.post('/pathology/specimens', null,\n"
                + "      { params: { orderId: row.order_id, specimenDesc: '手术切除标本' } })";
        assertTrue(postsSpecimens(stripComments(hit)), "修复前旧页的原样调用必须被抓到");
        assertTrue(postsSpecimens(stripComments("client.post(`/pathology/specimens`, null)")), "反引号也算");
        assertTrue(postsSpecimens(stripComments("client.post( \"/pathology/specimens\", body)")), "双引号 + 空格也算");
        assertFalse(postsSpecimens(stripComments("// " + hit.replace("\n", "\n// "))), "行注释里的不算");
        assertFalse(postsSpecimens(stripComments("/* " + hit + " */")), "块注释里的不算");
        assertFalse(postsSpecimens(stripComments("<!-- " + hit + " -->")), "HTML 注释里的不算");
        assertFalse(postsSpecimens("client.get('/pathology/specimens')"), "列表 GET 不是登记调用");
        assertFalse(postsSpecimens("client.put(`/pathology/specimens/${row.barcode}/receive`)"), "核收 PUT 不是登记调用");
        assertFalse(postsSpecimens("title=\"POST /pathology/specimens 描述必填\""), "文案里的端点名不是调用");
        assertEquals("const u = 'http://x/pathology/specimens'", stripComments("const u = 'http://x/pathology/specimens'"),
                "剥注释不误伤 URL 里的 //");
    }

    // =====================================================================================
    // (h) 迁移扫描：V166 零 update / 零 insert；对照组抓得到；探针证明扫描器在咬
    // =====================================================================================

    /** 顶层 update 语句的语法形态：行首（允许缩进）update <表> set。不匹配裸 token——注释里「零条 update」这句话本身就含它 */
    private static final Pattern TOP_LEVEL_UPDATE = Pattern.compile("(?im)^\\s*update\\s+\\w+\\s+set\\b");
    /** 顶层 insert 语句的语法形态：行首 insert into <表> */
    private static final Pattern TOP_LEVEL_INSERT = Pattern.compile("(?im)^\\s*insert\\s+into\\s+\\w+");

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
    void migrationV166IsZeroBackfill() {
        String v166 = read(V166);
        String body = stripSqlComments(v166);
        assertTrue(body.contains("add column revision_seq smallint not null default 1"), "字段行加 revision_seq，默认 1");
        assertTrue(body.contains("drop constraint uq_path_gross_field_seq")
                        && body.contains("unique (specimen_id, revision_seq, seq)"),
                "唯一约束改为 (specimen_id, revision_seq, seq)：先 drop V164 的同名约束再建");
        assertTrue(body.contains("add column template_code varchar(32)"), "修订行加 template_code");
        assertTrue(body.contains("check (source in ('GROSSING', 'DIAGNOSE', 'GROSSING_EDIT'))"),
                "source 白名单三档：原两值保留 + GROSSING_EDIT");
        assertTrue(v166.contains("零条 update"),
                "注释里必须写明零回填纪律——这句本身含 update 一词，正是剥注释要处理的活样本");
        assertTrue(v166.contains("默认值 1 是事实") || v166.contains("default 1 写的是它们真实的版本号"),
                "头注释须论证 revision_seq 默认 1 为何是事实而非编造");
        assertFalse(hasTopLevelUpdate(v166), "V166 不许有任何 update 语句：不给既有修订行猜 template_code，不改既有字段行的版号");
        assertFalse(hasTopLevelInsert(v166), "V166 不许 insert 任何行：不伪造修订历史");

        // 活的对照组：既有迁移里确实有 update / insert，扫描器必须抓到——否则上面的绿不说明任何事
        assertTrue(hasTopLevelUpdate(read(CONTROL_WITH_UPDATE)),
                "对照组 " + CONTROL_WITH_UPDATE + " 确有 update md_drug set …，扫描器没抓到就是扫描器坏了");
        assertTrue(hasTopLevelInsert(read(CONTROL_WITH_INSERT)),
                "对照组 " + CONTROL_WITH_INSERT + " 确有 insert into sys_config …，扫描器没抓到就是扫描器坏了");

        // 探针：补一条回填后必须被抓到；注释里的不算；裸 token 不算
        assertTrue(hasTopLevelUpdate(v166 + "\nupdate path_gross_field set revision_seq = 1;\n"));
        assertTrue(hasTopLevelUpdate(v166 + "\n    UPDATE path_gross_revision SET template_code = 'GENERIC';\n"), "大小写与缩进不影响");
        assertFalse(hasTopLevelUpdate(v166 + "\n-- update path_gross_revision set template_code = null;\n"), "行注释里的不算");
        assertFalse(hasTopLevelUpdate(v166 + "\n/* update path_gross_field\n   set revision_seq = 2; */\n"), "块注释里的不算");
        assertFalse(hasTopLevelUpdate("comment on column path_gross_field.revision_seq is 'update set 之类的字样';"), "裸 token 不算");
        assertTrue(hasTopLevelInsert(v166
                + "\ninsert into path_gross_revision(specimen_id, seq, new_text, source) select id, 1, gross_finding, 'GROSSING' from path_specimen;\n"));
        assertFalse(hasTopLevelInsert(v166 + "\n-- insert into path_gross_revision ...\n"), "行注释里的不算");
    }

    // ==================== 夹具与取值 ====================

    private record Fixture(long id, String barcode, Long orderId) {}

    private Long newOrder(String suffix) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag + suffix);
    }

    /** 一条已收费门诊病理医嘱 → 既有 collect 登记打码（带真实描述）→ 既有 receive 核收（不取材） */
    private Fixture specimen(String suffix) {
        Fixture f = collectedOnly(suffix);
        var rc = pathology.receive(f.barcode());
        assertEquals(0, rc.getCode(), rc.getMessage());
        return f;
    }

    /** 只登记不核收 */
    private Fixture collectedOnly(String suffix) {
        Long orderId = newOrder(suffix);
        var c = pathology.collect(orderId, "标本" + suffix + " " + tag);
        assertEquals(0, c.getCode(), c.getMessage());
        String barcode = (String) c.getData().get("barcode");
        assertNotNull(barcode);
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
        return f;
    }

    /** 取材（大小 / 颜色 + 自由文本）→ 修订成三字段（大小 / 颜色 / 质地）：修订表两版、字段表五行 */
    private Fixture revisedFixture(String suffix) {
        var g1 = new LinkedHashMap<String, String>();
        g1.put("大小", "3×2×1cm");
        g1.put("颜色", "灰白");
        Fixture f = specimenWithGross(suffix, g1, "切面实性 " + tag);
        var g2 = new LinkedHashMap<String, String>();
        g2.put("大小", "3.5×2×1cm");
        g2.put("颜色", "灰白");
        g2.put("质地", "质硬");
        var r = process.reviseGrossFields(f.id(), new GrossReviseReq("GENERIC", g2, "切面实性，局灶出血 " + tag), doc);
        assertEquals(0, r.getCode(), r.getMessage());
        assertEquals(2, revisionRows(f.id()).size());
        assertEquals(5, fieldRows(f.id()).size());
        return f;
    }

    private String grossOf(long specimenId) {
        return jdbc.queryForObject("select gross_finding from path_specimen where id = ?", String.class, specimenId);
    }

    private List<Map<String, Object>> fieldRows(long specimenId) {
        return jdbc.queryForList("""
                select revision_seq, seq, label, value, operator_id, created_at
                from path_gross_field where specimen_id = ? order by revision_seq asc, seq asc
                """, specimenId);
    }

    private List<Map<String, Object>> fieldRowsOfRevision(long specimenId, int revisionSeq) {
        return jdbc.queryForList("""
                select revision_seq, seq, label, value, operator_id, created_at
                from path_gross_field where specimen_id = ? and revision_seq = ? order by seq asc
                """, specimenId, revisionSeq);
    }

    private List<Map<String, Object>> revisionRows(long specimenId) {
        return jdbc.queryForList("""
                select seq, old_text, new_text, source, template_code, changed_by, changed_at
                from path_gross_revision where specimen_id = ? order by seq asc
                """, specimenId);
    }

    /** 被拒路径的全量快照：文本 + 三张表行数 + 节点数，前后相等即零写入 */
    private List<Object> snapshot(long specimenId) {
        Long nodes = jdbc.queryForObject("select count(*) from path_process where specimen_id = ?", Long.class, specimenId);
        return List.of(String.valueOf(grossOf(specimenId)), rowsOf("path_gross_field", specimenId),
                rowsOf("path_gross_revision", specimenId), rowsOf("path_block", specimenId), nodes == null ? 0L : nodes);
    }

    private void assertNothingWritten(long sid, String why) {
        assertNull(grossOf(sid), why);
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

    private static List<Object> labels(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("label")).toList();
    }

    private static List<Object> values(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("value")).toList();
    }

    private static int seq(Map<String, Object> row) {
        return ((Number) row.get("seq")).intValue();
    }

    private static int revSeq(Map<String, Object> row) {
        return ((Number) row.get("revision_seq")).intValue();
    }

    /** 时刻容差断言：与库端 now() 偏差 ≤ 5 分钟（库端算，不在 Java 里做时区换算；照抄 V58GrossFieldsTest） */
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

    // 源码读取（照抄 V58GrossFieldsTest：从 user.dir 向上找仓库根）

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
