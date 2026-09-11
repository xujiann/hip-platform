package cn.hip.server;

import cn.hip.medtech.web.PathQcController;
import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyController.DiagnoseReq;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyProcessController.SlideReq;
import cn.hip.medtech.web.PathologyProcessController.StainReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.medtech.web.PathologyReportController.CancelTechOrderReq;
import cn.hip.medtech.web.PathologyReportController.TechOrderReq;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * v60 车道 B：<b>2563 尾——补取材 RESAMPLE 医嘱的挂接与进度派生自事实（V167）</b>。
 *
 * <h2>每条断言钉住的「修复前的反向事实」（反驳者原话，主控核过）</h2>
 * <ol>
 *   <li>{@link #appendGrossingAttachesResampleOrderAndProgressBecomesSampled()}：修复前「取材 append 路径不读不写
 *       path_tech_order，新蜡块永远挂不到医嘱上、进度恒『待切片』」。现在 append=true 带 techOrderId：每个新蜡块
 *       {@code path_block.tech_order_id} 落、医嘱 {@code block_id} 为空则回写为本次首块（再补不覆盖）、GROSSING 节点备注带
 *       「补取材医嘱#id」、两个清单分支进度 SAMPLED（已补取材待切片）、{@code blocks_derived} 含新块码、{@code sampled_block_count}
 *       计数；随后切片 → SECTIONING → 染完 STAINED → done DONE 照旧，为该医嘱补出的第 2 块也能挂片（v60 放宽 5272 一档）。</li>
 *   <li>{@link #legacyAppendWithoutOrderStaysPendingAndSqlAttachProvesDerivation()}：<b>反向事实本尊</b>——不传 techOrderId
 *       的旧路径照旧：新块 tech_order_id 为 NULL、医嘱 block_id 仍 NULL、进度恒 PENDING_SECTION；用一条直接 SQL 把旧世界的块
 *       挂上 → 进度立刻 SAMPLED、blocks_derived 派生出块码（block_id 仍 NULL，走的是派生路径）——证明派生读的是事实列而不是猜。
 *       三参 {@code techProgress} 永远派不出 SAMPLED（PathQcController 穿透合并前的口径，明示）。</li>
 *   <li>{@link #rejectedAttachPathsReturn5275BeforeAnyWrite()}：非 RESAMPLE / 他标本的医嘱 / 已取消 / 不存在 → 5275，
 *       蜡块、大体所见、修订行、GROSSING 节点、医嘱 block_id 全部零写入；append=false 带 techOrderId → 5222
 *       「首次取材不能挂接补取材医嘱」零写入；5224（当前用户解析不出）仍在任何写入之前。</li>
 *   <li>{@link #derivedColumnsAcrossListingBranches()}：修复前「block_id 对六种类型都可空且无回写端点，从某块挂了片后清单『蜡块』
 *       仍是『—』」「『挂接切片染色』列是后端 SQL 直接拼的英文枚举」。现在 blocks_derived 在 block_id 空时按挂接切片所在块派生、
 *       多块按块号「、」相连；attached_stain_name 是中文（HE 染色 / 免疫组化 / 特殊染色 / 分子病理 + 项目 + ×n），
 *       {@code attached_stain} 一个字节不动。</li>
 *   <li>{@link #grossingViewCarriesFieldsByRevisionAndSpecimenDescOnReadHeads()}：读端点新键 fieldsByRevision（全部版本、
 *       revisionSeq 升序、每版 fields 按 seq、无字段行的版本 fields=[]），既有键（fields / fieldsRevisionSeq / textRevisionSeq /
 *       fieldsCurrent）不动；grossingView / trail 的 specimen 头与 prior 行都带 specimen_desc。</li>
 *   <li>{@link #migrationV167IsZeroBackfillAndColumnIsLive()} / {@link #zeroBackfillDetectorActuallyBites()}：V167 剥注释后
 *       无 update / insert 语句；列与部分索引真的在库里；对照组 V22 确有 update 且扫描器抓得到。</li>
 *   <li>{@link #grossingReqStillDeserializesWithAndWithoutTechOrderId()}：记录类多了兼容构造器后 JSON 反序列化仍走规范构造器，
 *       旧七键 JSON → techOrderId 为 null。</li>
 * </ol>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：夹具时刻由 SQL 按 {@code now()} 现算；节点时刻用「与库端 now() 偏差 ≤ 5 分钟」的容差断言
 * （抄 V58GrossFieldsTest.assertRecent）。夹具与断言助手照抄 V58TechProgressTest。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V60ResampleProgressTest {

    @Autowired PathologyReportController report;
    @Autowired PathologyProcessController process;
    @Autowired PathologyController pathology;
    @Autowired PathQcController pathQc;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private static final String V167 = "server/src/main/resources/db/migration/V167__path_block_tech_order.sql";
    /** 对照组：确实含顶层 update 语句的既有迁移（V22 {@code update md_drug set abx_level = 1 where antibiotic;}） */
    private static final String CONTROL_WITH_UPDATE = "server/src/main/resources/db/migration/V22__phase25_mgmt.sql";
    /** 对照组：确实含顶层 insert 语句的既有迁移（V161 gate 种子） */
    private static final String CONTROL_WITH_INSERT = "server/src/main/resources/db/migration/V161__timeliness_gate_seed.sql";

    private String tag;
    private Long regId;
    private Authentication doc1;

    private long a;
    private long b;
    private long blockA;
    private long blockB;

    @BeforeEach
    void setUp() {
        assertEquals(1, setGate(PathologyReportController.TECH_DONE_GATE_KEY, "warn"), "techdone gate 行必须存在（V165 seed）");

        tag = "V60B" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "补取材进度测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "补取材进度患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);

        a = specimen("A");
        b = specimen("B");
        blockA = gross(a, "肿物中心 " + tag);
        blockB = gross(b, "切缘 " + tag);
    }

    // =====================================================================================
    // ① 正向：append=true 带 techOrderId → 块挂医嘱、医嘱回写首块、节点备注、SAMPLED、随后切片 / 染色 / 完成照旧
    // =====================================================================================

    @Test
    void appendGrossingAttachesResampleOrderAndProgressBecomesSampled() {
        long rs = techOrder(a, null, "RESAMPLE", null, "肿物边缘未取到 " + tag);
        assertProgress(rs, "PENDING_SECTION", "待切片", 0, 0);
        assertDerived(rs, null, 0, null);
        assertNull(orderBlockId(rs), "下达时块还不存在，RESAMPLE 医嘱 block_id 必为空");
        assertEquals(1L, nodes(a, "GROSSING"), "夹具前提：首次取材一条 GROSSING 节点");

        var gr = ok(process.grossing(new GrossingReq(a, null, null, "补取材灰白组织两块 " + tag, true, "补切缘 " + tag,
                List.of(new BlockReq("切缘一 " + tag), new BlockReq("切缘二 " + tag)), rs), doc1));
        assertEquals(rs, asLong(gr.get("techOrderId")), "返回体回带本次挂接的医嘱");
        assertEquals(Boolean.TRUE, gr.get("techOrderBlockBackfilled"), "医嘱 block_id 由空回写为本次首块");
        var created = rows(gr, "blocks");
        assertEquals(2, created.size());
        long nb1 = idOf(created.get(0));
        long nb2 = idOf(created.get(1));
        String code1 = String.valueOf(created.get(0).get("block_code"));
        String code2 = String.valueOf(created.get(1).get("block_code"));
        assertNotEquals(code1, code2);

        // 修复前反向事实：新蜡块永远挂不到医嘱上——现在两块 tech_order_id 都是它；首次取材的块仍 NULL
        assertEquals(rs, blockTechOrderId(nb1));
        assertEquals(rs, blockTechOrderId(nb2));
        assertNull(blockTechOrderId(blockA), "首次取材的块不被波及");
        assertEquals(rs, asLong(created.get(0).get("tech_order_id")), "返回体 blocks 行带 tech_order_id");
        // 医嘱 block_id 回写为本次首块
        assertEquals(nb1, orderBlockId(rs));

        // GROSSING 节点备注带「补取材医嘱#id」，时刻在库端 now() 附近
        assertEquals(2L, nodes(a, "GROSSING"));
        var node = latestNode(a, "GROSSING");
        String remark = String.valueOf(node.get("remark"));
        assertTrue(remark.contains("取材产出 2 块") && remark.contains("补取材医嘱#" + rs) && remark.contains("补切缘 " + tag),
                "GROSSING 备注应带块数、补取材医嘱#id 与备注，实际：" + remark);
        assertRecent(node.get("occurred_at"), "GROSSING.occurred_at");

        // 修复前反向事实：进度恒「待切片」——现在两个清单分支都是 SAMPLED；block_id 已回写 → 「蜡块」列取其块码
        assertProgress(rs, "SAMPLED", "已补取材待切片", 0, 0);
        assertDerived(rs, code1, 2, null);
        // 刚下达的返回体仍是 PENDING_SECTION（此时不可能有块）——既有契约不动
        assertEquals("PENDING_SECTION", ok(report.createTechOrder(new TechOrderReq(a, null, "RESAMPLE", null, null), doc1)).get("progress"));

        // 再补一块：block_id 已有值不覆盖，块数 3
        var gr2 = ok(process.grossing(new GrossingReq(a, null, null, null, true, null, List.of(new BlockReq("切缘三 " + tag)), rs), doc1));
        assertEquals(Boolean.FALSE, gr2.get("techOrderBlockBackfilled"), "block_id 已非空：不覆盖");
        assertEquals(nb1, orderBlockId(rs));
        long nb3 = idOf(rows(gr2, "blocks").get(0));
        assertEquals(rs, blockTechOrderId(nb3));
        assertProgress(rs, "SAMPLED", "已补取材待切片", 0, 0);
        assertDerived(rs, code1, 3, null);

        // 切片挂接：首块（医嘱回写的 block_id）→ SECTIONING；为该医嘱补出的第 2 块也可挂（v60 放宽 5272 一档）
        var s1 = rows(ok(process.slides(new SlideReq(nb1, 1, "HE", null, null, rs), doc1)), "slides");
        assertProgress(rs, "SECTIONING", "切片中", 1, 0);
        var s2 = rows(ok(process.slides(new SlideReq(nb2, 1, "HE", null, null, rs), doc1)), "slides");
        assertProgress(rs, "SECTIONING", "切片中", 2, 0);
        assertDerived(rs, code1, 3, "HE 染色 ×2");
        // 既非医嘱指定块、也非为它补出的块：仍 5272，一张片不插
        var bad = process.slides(new SlideReq(blockA, 1, "HE", null, null, rs), doc1);
        assertEquals(5272, bad.getCode(), bad.getMessage());
        assertTrue(bad.getMessage().contains("为它补出的蜡块"), bad.getMessage());
        assertEquals(0L, slidesOf(blockA, rs));
        // 他医嘱指定了别的块：原 5272 路径不受放宽影响（块是为 rs 补出的，不是为 ihc）
        long ihc = techOrder(a, blockA, "IHC", "CK7", null);
        assertEquals(5272, process.slides(new SlideReq(nb2, 1, "IHC", "CK7", null, ihc), doc1).getCode());
        // 类型不符仍 5274（RESAMPLE → HE）
        assertEquals(5274, process.slides(new SlideReq(nb3, 1, "IHC", "x", null, rs), doc1).getCode());
        assertProgress(rs, "SECTIONING", "切片中", 2, 0);

        ok(process.stain(idOf(s1.get(0)), new StainReq("GOOD", null, null), doc1));
        assertProgress(rs, "SECTIONING", "切片中", 2, 1);
        ok(process.stain(idOf(s2.get(0)), new StainReq("GOOD", null, null), doc1));
        assertProgress(rs, "STAINED", "已染色待确认", 2, 2);
        var done = ok(report.doneTechOrder(rs, doc1));
        assertEquals("DONE", done.get("progress"));
        assertEquals(List.of(), done.get("warnings"), "染完再确认：没有告警");
        assertProgress(rs, "DONE", "已完成", 2, 2);
        // 已完成的医嘱不能再挂补取材块
        assertEquals(5275, process.grossing(new GrossingReq(a, null, null, null, true, null, List.of(new BlockReq("x")), rs), doc1).getCode());
    }

    // =====================================================================================
    // ② 反向事实本尊：旧路径不挂、恒待切片；一条直接 SQL 挂上 → 派生生效（block_id 仍 NULL）
    // =====================================================================================

    @Test
    void legacyAppendWithoutOrderStaysPendingAndSqlAttachProvesDerivation() {
        long rs = techOrder(a, null, "RESAMPLE", null, null);

        // 修复前的世界：append 不读不写医嘱——不传 techOrderId 照旧（七参形态，v59 契约）
        var gr = ok(process.grossing(new GrossingReq(a, null, null, null, true, null, List.of(new BlockReq("旧世界补取材 " + tag))), doc1));
        var legacy = rows(gr, "blocks").get(0);
        long legacyBlock = idOf(legacy);
        String legacyCode = String.valueOf(legacy.get("block_code"));
        assertNull(gr.get("techOrderId"));
        assertEquals(Boolean.FALSE, gr.get("techOrderBlockBackfilled"));
        assertNull(blockTechOrderId(legacyBlock), "不传 techOrderId 的块 tech_order_id 为 NULL（旧契约不变）");
        assertNull(orderBlockId(rs), "医嘱 block_id 仍 NULL");
        assertFalse(String.valueOf(latestNode(a, "GROSSING").get("remark")).contains("补取材医嘱"), "不挂接的节点备注不提医嘱");
        // 反向事实：进度恒「待切片」、蜡块列「—」
        assertProgress(rs, "PENDING_SECTION", "待切片", 0, 0);
        assertDerived(rs, null, 0, null);

        // 一条直接 SQL 把旧世界的块挂上——派生立刻生效：SAMPLED，蜡块列由 tech_order_id 派生（block_id 仍 NULL）
        assertEquals(1, jdbc.update("update path_block set tech_order_id = ? where id = ?", rs, legacyBlock));
        assertProgress(rs, "SAMPLED", "已补取材待切片", 0, 0);
        assertDerived(rs, legacyCode, 1, null);
        assertNull(orderBlockId(rs), "派生不回写 block_id：读侧只读");

        // 派生函数本身：三参永远派不出 SAMPLED（PathQcController.withTechProgress 合并前的口径，明示）；四参才有第六态；
        // 切了片以切片事实为准，蜡块只是上游；非 ORDERED 照状态
        assertEquals("PENDING_SECTION", PathologyReportController.techProgress("ORDERED", 0L, 0L));
        assertEquals("PENDING_SECTION", PathologyReportController.techProgress("ORDERED", 0L, 0L, 0L));
        assertEquals("PENDING_SECTION", PathologyReportController.techProgress("ORDERED", 0L, 0L, null));
        assertEquals("SAMPLED", PathologyReportController.techProgress("ORDERED", 0L, 0L, 1L));
        assertEquals("SECTIONING", PathologyReportController.techProgress("ORDERED", 1L, 0L, 1L));
        assertEquals("STAINED", PathologyReportController.techProgress("ORDERED", 1L, 1L, 1L));
        assertEquals("DONE", PathologyReportController.techProgress("DONE", 0L, 0L, 1L));
        assertEquals("CANCELLED", PathologyReportController.techProgress("CANCELLED", 0L, 0L, 1L));
        assertEquals("已补取材待切片", PathologyReportController.TECH_PROGRESS_NAMES.get("SAMPLED"));
        var row = new LinkedHashMap<String, Object>(Map.of("status", "ORDERED", "slide_count", 0L, "stained_count", 0L, "sampled_block_count", 2L));
        PathologyReportController.applyTechProgress(row);
        assertEquals("SAMPLED", row.get("progress"));
        assertEquals("已补取材待切片", row.get("progress_name"));

        // 质控穿透 WORKLOAD_TECH（PathQcController，车道 C 独占）合并前仍走三参：本行在穿透里是 PENDING_SECTION——
        // 这里只钉「穿透行在、slide_count 同口径」，不钉它的 progress（主控合并后补穿透的 sampled_block_count，届时为 SAMPLED）
        var qc = qcTechRow(rs);
        assertEquals(0L, asLong(qc.get("slide_count")));
        assertTrue(List.of("SAMPLED", "PENDING_SECTION").contains(String.valueOf(qc.get("progress"))),
                "穿透 progress 应为 SAMPLED（合并后）或 PENDING_SECTION（合并前三参口径），实际 " + qc.get("progress"));
    }

    // =====================================================================================
    // ③ 被拒路径：5275 四条 / 5222 一条 / 5224 一条——全部零写入
    // =====================================================================================

    @Test
    void rejectedAttachPathsReturn5275BeforeAnyWrite() {
        long ihc = techOrder(a, blockA, "IHC", "CK7", null);
        long onB = techOrder(b, null, "RESAMPLE", null, null);
        long cancelled = techOrder(a, null, "RESAMPLE", null, null);
        ok(report.cancelTechOrder(cancelled, new CancelTechOrderReq("不补了 " + tag), doc1));
        long good = techOrder(a, null, "RESAMPLE", null, null);

        Map<String, Object> before = snapshot(a);
        String desc = "被拒路径描述 " + tag;
        var cases = new LinkedHashMap<Long, String>();
        cases.put(ihc, "RESAMPLE");
        cases.put(onB, "不属于该标本");
        cases.put(cancelled, "待执行");
        cases.put(-1L, "不存在");
        for (var e : cases.entrySet()) {
            var r = process.grossing(new GrossingReq(a, null, null, desc, true, null, List.of(new BlockReq("x " + tag)), e.getKey()), doc1);
            assertEquals(5275, r.getCode(), "techOrderId=" + e.getKey() + "：" + r.getMessage());
            assertTrue(r.getMessage().contains(e.getValue()), "techOrderId=" + e.getKey() + " 消息应点明原因「" + e.getValue() + "」，实际 " + r.getMessage());
            assertEquals(before, snapshot(a), "techOrderId=" + e.getKey() + " 被拒后零写入");
        }
        assertNull(orderBlockId(onB), "他标本的医嘱不被回写");
        assertEquals(blockA, orderBlockId(ihc), "IHC 医嘱下达时就指定了 blockA，被拒路径不得改动它既有的 block_id");

        // append=false 带 techOrderId → 5222（首次取材没有补取材医嘱可挂），零写入
        long c = specimen("C");
        long rsC = techOrder(c, null, "RESAMPLE", null, null);
        Map<String, Object> beforeC = snapshot(c);
        var first = process.grossing(new GrossingReq(c, null, null, "首次取材 " + tag, false, null, List.of(new BlockReq("x")), rsC), doc1);
        assertEquals(5222, first.getCode(), first.getMessage());
        assertTrue(first.getMessage().contains("首次取材不能挂接补取材医嘱"), first.getMessage());
        assertEquals(beforeC, snapshot(c), "5222 零写入");
        assertNull(orderBlockId(rsC));
        // 同一标本 append=false 不带 techOrderId 照旧能首次取材（旧契约）
        ok(process.grossing(new GrossingReq(c, null, null, "首次取材 " + tag, false, null, List.of(new BlockReq("x")), null), doc1));

        // 5224 仍在任何写入之前：合法医嘱 + 解析不出的登录人 → 不落块、不回写 block_id
        var stranger = new UsernamePasswordAuthenticationToken(tag + "nobody", null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
        var r = process.grossing(new GrossingReq(a, null, null, null, true, null, List.of(new BlockReq("y")), good), stranger);
        assertEquals(5224, r.getCode(), r.getMessage());
        assertEquals(before, snapshot(a), "5224 零写入");
        assertNull(orderBlockId(good));
        assertProgress(good, "PENDING_SECTION", "待切片", 0, 0);
    }

    // =====================================================================================
    // ④ 派生列：blocks_derived 在 block_id 空时按挂接切片所在块派生（多块「、」相连）；attached_stain_name 中文
    // =====================================================================================

    @Test
    void derivedColumnsAcrossListingBranches() {
        String codeA = blockCode(blockA);
        // 第二块（不挂医嘱）：块号 2，块码按块号排在 A 之后
        long blockA2 = idOf(rows(ok(process.grossing(new GrossingReq(a, null, null, null, true, null, List.of(new BlockReq("第二块 " + tag))), doc1)), "blocks").get(0));
        String codeA2 = blockCode(blockA2);

        // 未指定蜡块的 IHC：修复前从某块挂了片后「蜡块」仍是「—」——现在按挂接切片所在块派生
        long ck7 = techOrder(a, null, "IHC", "CK7", null);
        assertDerived(ck7, null, 0, null);
        ok(process.slides(new SlideReq(blockA, 2, "IHC", "CK7", null, ck7), doc1));
        assertDerived(ck7, codeA, 0, "免疫组化 CK7 ×2");
        assertAttachedStainUnchanged(ck7, "IHC CK7 ×2");
        // 旧世界（直接 SQL）挂在第二块上的一张 HE：两块按块号「、」相连；中文汇总按 stain_type、stain_item 排序
        jdbc.update("""
                insert into path_slide(block_id, slide_no, slide_code, stain_type, stain_item, tech_order_id)
                select ?, 901, b.block_code || '-901', 'HE', null, ? from path_block b where b.id = ?
                """, blockA2, ck7, blockA2);
        assertDerived(ck7, codeA + "、" + codeA2, 0, "HE 染色 ×1、免疫组化 CK7 ×2");
        assertAttachedStainUnchanged(ck7, "HE ×1、IHC CK7 ×2");

        // 指定了蜡块的：block_id 非空取其块码，不受挂接切片影响
        long pas = techOrder(a, blockA2, "SPECIAL_STAIN", "PAS", null);
        assertDerived(pas, codeA2, 0, null);
        ok(process.slides(new SlideReq(blockA2, 1, "SPECIAL", "PAS", null, pas), doc1));
        assertDerived(pas, codeA2, 0, "特殊染色 PAS ×1");
        long egfr = techOrder(a, null, "MOLECULAR", "EGFR", null);
        ok(process.slides(new SlideReq(blockA, 1, "MOLECULAR", "EGFR", null, egfr), doc1));
        assertDerived(egfr, codeA, 0, "分子病理 EGFR ×1");
        // 深切无项目：中文名后不带项目
        long deep = techOrder(a, null, "DEEP_CUT", null, null);
        ok(process.slides(new SlideReq(blockA, 3, "HE", null, null, deep), doc1));
        assertDerived(deep, codeA, 0, "HE 染色 ×3");

        // 挂接蜡块 + 挂接切片所在块并集去重：为 rs 补出的块上切了片 + 另一块（block_id 空的场景用直接 SQL 造）
        long rs = techOrder(a, null, "RESAMPLE", null, null);
        long nb = idOf(rows(ok(process.grossing(new GrossingReq(a, null, null, null, true, null, List.of(new BlockReq("补块 " + tag)), rs), doc1)), "blocks").get(0));
        String codeNb = blockCode(nb);
        jdbc.update("update path_tech_order set block_id = null where id = ?", rs);   // 造出「有挂接蜡块但 block_id 空」的行
        assertDerived(rs, codeNb, 1, null);
        ok(process.slides(new SlideReq(nb, 1, "HE", null, null, rs), doc1));
        assertDerived(rs, codeNb, 1, "HE 染色 ×1");   // 同一块：去重
        jdbc.update("""
                insert into path_slide(block_id, slide_no, slide_code, stain_type, stain_item, tech_order_id)
                select ?, 902, b.block_code || '-902', 'HE', null, ? from path_block b where b.id = ?
                """, blockA, rs, blockA);
        assertDerived(rs, codeA + "、" + codeNb, 1, "HE 染色 ×2");   // 块号 1 的 A 排在补块之前
        // 另一标本的医嘱不串
        long onB = techOrder(b, null, "IHC", "Ki-67", null);
        assertDerived(onB, null, 0, null);
    }

    // =====================================================================================
    // ⑤ 读端点：fieldsByRevision 全部版本；specimen_desc 在 grossingView / trail 头与 prior 行
    // =====================================================================================

    @Test
    void grossingViewCarriesFieldsByRevisionAndSpecimenDescOnReadHeads() {
        long d = specimen("D");
        var g1 = new LinkedHashMap<String, String>();
        g1.put("大小", "3×2×1cm");
        g1.put("颜色", "灰白");
        ok(process.grossing(new GrossingReq(d, null, g1, "切面实性 " + tag, false, null, List.of(new BlockReq("肿物中心"))), doc1));
        var g2 = new LinkedHashMap<String, String>();
        g2.put("质地", "质硬");
        ok(process.grossing(new GrossingReq(d, null, g2, null, true, null, List.of(new BlockReq("切缘"))), doc1));
        ok(process.grossing(new GrossingReq(d, null, null, "再补一块，未填字段 " + tag, true, null, List.of(new BlockReq("再补"))), doc1));

        var view = ok(process.grossingView(d));
        var byRev = rows(view, "fieldsByRevision");
        assertEquals(3, byRev.size(), "三次写描述 = 三版：" + byRev);
        assertEquals(List.of(1, 2, 3), byRev.stream().map(r -> ((Number) r.get("revisionSeq")).intValue()).toList(), "revisionSeq 升序");
        for (var r : byRev) assertEquals("GROSSING", r.get("source"));
        var f1 = rows(byRev.get(0), "fields");
        assertEquals(2, f1.size());
        assertEquals(List.of("大小", "颜色"), f1.stream().map(x -> String.valueOf(x.get("label"))).toList());
        assertEquals(List.of("3×2×1cm", "灰白"), f1.stream().map(x -> String.valueOf(x.get("value"))).toList());
        assertEquals(List.of(1, 2), f1.stream().map(x -> ((Number) x.get("seq")).intValue()).toList(), "每版 fields 按 seq");
        var f2 = rows(byRev.get(1), "fields");
        assertEquals(1, f2.size());
        assertEquals("质地", f2.get(0).get("label"));
        assertEquals(List.of(), rows(byRev.get(2), "fields"), "无字段行的版本 fields=[]（不是缺键、不是 null）");
        // 既有键不动：fields 仍是「最大 revision_seq 中有字段行的那一版」= 第 2 版；文本最新第 3 版；fieldsCurrent=false
        assertEquals(1, rows(view, "fields").size());
        assertEquals("质地", rows(view, "fields").get(0).get("label"));
        assertEquals(2, ((Number) view.get("fieldsRevisionSeq")).intValue());
        assertEquals(3, ((Number) view.get("textRevisionSeq")).intValue());
        assertEquals(Boolean.FALSE, view.get("fieldsCurrent"));
        assertEquals(3, rows(view, "revisions").size());
        // 历史 / 纯自由文本标本：fieldsByRevision 里每版 fields=[]（a 只有自由文本一版）
        var va = ok(process.grossingView(a));
        assertEquals(1, rows(va, "fieldsByRevision").size());
        assertEquals(List.of(), rows(rows(va, "fieldsByRevision").get(0), "fields"));
        assertEquals(Boolean.FALSE, va.get("fieldsAvailable"));

        // specimen_desc：grossingView 头 / trail 头 / prior 行
        @SuppressWarnings("unchecked")
        var head = (Map<String, Object>) view.get("specimen");
        assertEquals("标本D", head.get("specimen_desc"), "grossingView 的 specimen 头带 specimen_desc");
        @SuppressWarnings("unchecked")
        var trailHead = (Map<String, Object>) ok(process.trail(d, null)).get("specimen");
        assertTrue(trailHead.containsKey("specimen_desc"), "trail 头必须带 specimen_desc 键：" + trailHead.keySet());
        assertEquals("标本D", trailHead.get("specimen_desc"));
        // d 写了诊断后成为 a 的「既往」：prior 行带 specimen_desc
        assertEquals(0, pathology.diagnose(barcodeOf(d), new DiagnoseReq(null, null, "诊断 D " + tag), doc1).getCode());
        var prior = ok(report.prior(a, null));
        var dRow = rows(prior, "items").stream().filter(x -> asLong(x.get("id")) == d).findFirst()
                .orElseGet(() -> fail("prior 里找不到同患者已诊断的标本 D"));
        assertEquals("标本D", dRow.get("specimen_desc"), "prior 行带 specimen_desc");
    }

    // =====================================================================================
    // ⑥ 迁移扫描：V167 零 update / 零 insert；列与部分索引真的在；对照组抓得到；探针证明扫描器在咬
    // =====================================================================================

    /** 顶层 update 语句的语法形态：行首（允许缩进）update <表> set。不匹配裸 token——注释里「零条 update」这句话本身就含它 */
    private static final Pattern TOP_LEVEL_UPDATE = Pattern.compile("(?im)^\\s*update\\s+\\w+\\s+set\\b");
    /** 顶层 insert 语句的语法形态：行首（允许缩进）insert into <表> */
    private static final Pattern TOP_LEVEL_INSERT = Pattern.compile("(?im)^\\s*insert\\s+into\\s+\\w+");

    /** 先剥块注释，再剥每行 -- 之后的部分 */
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
    void migrationV167IsZeroBackfillAndColumnIsLive() {
        String raw = read(V167);
        String body = stripSqlComments(raw);
        assertTrue(body.contains("alter table path_block add column tech_order_id bigint references path_tech_order (id)"),
                "读到的不是 V167 本尊（path_block.tech_order_id 外键列）");
        assertTrue(body.contains("create index idx_path_block_tech_order on path_block (tech_order_id) where tech_order_id is not null"),
                "部分索引");
        assertTrue(raw.contains("零条 update"), "注释里必须写明零回填纪律——这句本身含 update 一词，正是剥注释要处理的活样本");
        assertTrue(raw.contains("永远为 NULL"), "头注释必须写明零回填后果：历史补取材块永远 NULL");
        assertFalse(hasTopLevelUpdate(raw), "V167 不许有任何 update 语句：历史补取材块不按时间 / 描述反猜医嘱，历史医嘱不回写 block_id");
        assertFalse(hasTopLevelInsert(raw), "V167 不许 insert 任何行");

        // 活的对照组：既有迁移里确实有 update / insert，扫描器必须抓到——否则上面的绿不说明任何事
        assertTrue(hasTopLevelUpdate(read(CONTROL_WITH_UPDATE)),
                "对照组 " + CONTROL_WITH_UPDATE + " 确有 update md_drug set …，扫描器没抓到就是扫描器坏了");
        assertTrue(hasTopLevelInsert(read(CONTROL_WITH_INSERT)),
                "对照组 " + CONTROL_WITH_INSERT + " 确有 insert into sys_config …，扫描器没抓到就是扫描器坏了");

        // 列真的在库里：bigint、可空；部分索引真的在
        var col = jdbc.queryForMap("""
                select data_type, is_nullable from information_schema.columns
                where table_name = 'path_block' and column_name = 'tech_order_id'
                """);
        assertEquals("bigint", col.get("data_type"));
        assertEquals("YES", col.get("is_nullable"), "可空：首次取材的块与历史块都是 NULL");
        String indexDef = jdbc.queryForObject(
                "select indexdef from pg_indexes where tablename = 'path_block' and indexname = 'idx_path_block_tech_order'", String.class);
        assertNotNull(indexDef);
        assertTrue(indexDef.toLowerCase().contains("where (tech_order_id is not null)"), "部分索引谓词，实际：" + indexDef);
        // 外键真的指向 path_tech_order：挂一个不存在的医嘱 id 被外键拦（PL/pgSQL EXCEPTION 子句吞 foreign_key_violation，免得打坏测试事务）
        jdbc.execute("""
                do $$ begin
                    update path_block set tech_order_id = -1 where id = %d;
                exception when foreign_key_violation then
                    null;
                end $$
                """.formatted(blockA));
        assertNull(blockTechOrderId(blockA), "外键拦住了 -1：列值仍 NULL");
    }

    @Test
    void zeroBackfillDetectorActuallyBites() {
        String v167 = read(V167);
        assertTrue(hasTopLevelUpdate(v167
                        + "\nupdate path_block set tech_order_id = (select id from path_tech_order limit 1);\n"),
                "补一条回填语句后必须被抓到");
        assertTrue(hasTopLevelUpdate(v167 + "\n    UPDATE path_tech_order SET block_id = 1;\n"), "大小写与缩进不影响");
        assertTrue(hasTopLevelInsert(v167 + "\ninsert into path_block(specimen_id, block_no, block_code) values (1, 1, 'x');\n"), "insert 同样抓得到");
        assertFalse(hasTopLevelUpdate(v167 + "\n-- update path_block set tech_order_id = 1;\n"), "行注释里的不算");
        assertFalse(hasTopLevelUpdate(v167 + "\n/* update path_block\n   set tech_order_id = 1; */\n"), "块注释里的不算");
        assertFalse(hasTopLevelUpdate("comment on column path_block.tech_order_id is 'update set 字样';"),
                "裸 token 不算：须是行首 update <表> set 的语法形态");
    }

    // =====================================================================================
    // ⑦ 记录类多了兼容构造器后，JSON 反序列化仍走规范构造器（照抄 V57TechTraceTest 的 SlideReq 形态）
    // =====================================================================================

    @Test
    void grossingReqStillDeserializesWithAndWithoutTechOrderId() throws Exception {
        GrossingReq with = objectMapper.readValue(
                "{\"specimenId\":7,\"append\":true,\"grossText\":\"x\",\"blocks\":[{\"tissueDesc\":\"a\"}],\"techOrderId\":99}",
                GrossingReq.class);
        assertEquals(7L, with.specimenId());
        assertEquals(Boolean.TRUE, with.append());
        assertEquals(99L, with.techOrderId());
        assertEquals(1, with.blocks().size());

        GrossingReq without = objectMapper.readValue(
                "{\"specimenId\":7,\"grossText\":\"x\",\"blocks\":[{\"tissueDesc\":\"a\"}]}", GrossingReq.class);
        assertEquals(7L, without.specimenId());
        assertNull(without.techOrderId(), "旧七键 JSON → techOrderId 为 null（旧契约不变）");
        assertNull(new GrossingReq(7L, null, null, "x", true, null, List.of()).techOrderId(), "七参构造器等价于不挂接");
    }

    // ==================== 断言与夹具 ====================

    /** 两个清单分支同口径（照抄 V58TechProgressTest.assertProgress；质控穿透归车道 C，见 ② 的说明） */
    private void assertProgress(long id, String progress, String name, long slides, long stained) {
        assertRow("标本清单", bySpecimenRow(id), progress, name, slides, stained);
        assertRow("全院清单", wideRow(id), progress, name, slides, stained);
    }

    private static void assertRow(String where, Map<String, Object> row, String progress, String name,
                                  long slides, long stained) {
        assertEquals(progress, row.get("progress"), where + " progress：" + row);
        assertEquals(name, row.get("progress_name"), where + " progress_name");
        assertEquals(slides, asLong(row.get("slide_count")), where + " slide_count");
        assertEquals(stained, asLong(row.get("stained_count")), where + " stained_count");
    }

    /** v60 三列两处同口径；expected 为 null 即「列在、值为 NULL」 */
    private void assertDerived(long id, String blocksDerived, long sampledBlocks, String attachedStainName) {
        for (var e : List.of(Map.entry("标本清单", bySpecimenRow(id)), Map.entry("全院清单", wideRow(id)))) {
            var row = e.getValue();
            for (String k : List.of("blocks_derived", "sampled_block_count", "attached_stain_name")) {
                assertTrue(row.containsKey(k), e.getKey() + " 行必须带 " + k + " 列：" + row.keySet());
            }
            assertEquals(blocksDerived, row.get("blocks_derived"), e.getKey() + " blocks_derived");
            assertEquals(sampledBlocks, asLong(row.get("sampled_block_count")), e.getKey() + " sampled_block_count");
            assertEquals(attachedStainName, row.get("attached_stain_name"), e.getKey() + " attached_stain_name");
        }
    }

    /** v59 的英文枚举版 attached_stain 一个字节不动 */
    private void assertAttachedStainUnchanged(long id, String expected) {
        assertEquals(expected, bySpecimenRow(id).get("attached_stain"));
        assertEquals(expected, wideRow(id).get("attached_stain"));
    }

    /** 与 V58GrossFieldsTest 同口径：偏差在库端用 now() 算，不碰 JVM 时钟 */
    private void assertRecent(Object ts, String what) {
        assertNotNull(ts, what + " 为空");
        Double gap = jdbc.queryForObject("select abs(extract(epoch from (now() - ?::timestamptz)))", Double.class, ts);
        assertNotNull(gap);
        assertTrue(gap <= 300, what + " 应在本次测试时刻附近（库端 now()），实际偏差秒数=" + gap);
    }

    /** 标本上一切「写」的快照：块数、大体所见、修订行数、GROSSING 节点数、挂了医嘱的块数——被拒路径前后必须逐字相等 */
    private Map<String, Object> snapshot(long specimenId) {
        return jdbc.queryForMap("""
                select (select count(*) from path_block b where b.specimen_id = s.id) as blocks,
                       (select count(*) from path_block b where b.specimen_id = s.id and b.tech_order_id is not null) as attached_blocks,
                       s.gross_finding,
                       (select count(*) from path_gross_revision r where r.specimen_id = s.id) as revisions,
                       (select count(*) from path_gross_field f where f.specimen_id = s.id) as fields,
                       (select count(*) from path_process p where p.specimen_id = s.id) as nodes
                from path_specimen s where s.id = ?
                """, specimenId);
    }

    private Map<String, Object> bySpecimenRow(long id) {
        return techRow(ok(report.techOrders(specimenOf(id), null, null, null, null, null, null, null, null)), id);
    }

    private Map<String, Object> wideRow(long id) {
        return techRow(ok(report.techOrders(null, "ALL", null, null, pathNoOf(id), null, null, null, null)), id);
    }

    /** 质控穿透 WORKLOAD_TECH 里本用例那一行；日期窗两端各留一天余量 */
    private Map<String, Object> qcTechRow(long techOrderId) {
        var qc = ok(pathQc.detail("WORKLOAD_TECH",
                BusinessDates.today().minusDays(1).toString(),
                BusinessDates.today().plusDays(1).toString(), null));
        return rows(qc, "items").stream()
                .filter(x -> asLong(x.get("tech_order_id")) == techOrderId)
                .findFirst()
                .orElseGet(() -> fail("质控穿透里找不到技术医嘱 " + techOrderId));
    }

    private int setGate(String key, String value) {
        configReader.evictAll();
        int n = jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, key);
        configReader.evictAll();
        return n;
    }

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    private String pathNo(String suffix) {
        return tag + "-" + suffix;
    }

    private long specimenOf(long techOrderId) {
        return jdbc.queryForObject("select specimen_id from path_tech_order where id = ?", Long.class, techOrderId);
    }

    private String pathNoOf(long techOrderId) {
        return jdbc.queryForObject("""
                select s.path_no from path_tech_order t join path_specimen s on s.id = t.specimen_id where t.id = ?
                """, String.class, techOrderId);
    }

    private String barcodeOf(long specimenId) {
        return jdbc.queryForObject("select barcode from path_specimen where id = ?", String.class, specimenId);
    }

    private Long orderBlockId(long techOrderId) {
        return jdbc.queryForObject("select block_id from path_tech_order where id = ?", Long.class, techOrderId);
    }

    private Long blockTechOrderId(long blockId) {
        return jdbc.queryForObject("select tech_order_id from path_block where id = ?", Long.class, blockId);
    }

    private String blockCode(long blockId) {
        return jdbc.queryForObject("select block_code from path_block where id = ?", String.class, blockId);
    }

    /** 一条门诊医嘱 + 一份已核收标本；时刻由 SQL 表达式现算，不写字面量（照抄 V58TechProgressTest） */
    private long specimen(String suffix) {
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag + suffix);
        return jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent)
                values (?, 1, ?, ?, 'ROUTINE', ?, 'RECEIVED',
                        now() - interval '2 days' - interval '1 hour', now() - interval '2 days', false)
                returning id
                """, Long.class, orderId, "PB" + tag + suffix, pathNo(suffix), "标本" + suffix);
    }

    private long gross(long specimenId, String tissue) {
        var gr = process.grossing(new GrossingReq(specimenId, null, null, "灰白组织一块 " + tag, false, null,
                List.of(new BlockReq(tissue))), doc1);
        assertEquals(0, gr.getCode(), gr.getMessage());
        return idOf(rows(gr.getData(), "blocks").get(0));
    }

    private long techOrder(long specimenId, Long blockId, String type, String item, String reason) {
        var t = report.createTechOrder(new TechOrderReq(specimenId, blockId, type, item, reason), doc1);
        assertEquals(0, t.getCode(), t.getMessage());
        return ((Number) t.getData().get("id")).longValue();
    }

    /** 该蜡块上挂接到某医嘱的切片数 */
    private long slidesOf(long blockId, long techOrderId) {
        Long n = jdbc.queryForObject("select count(*) from path_slide where block_id = ? and tech_order_id = ?",
                Long.class, blockId, techOrderId);
        return n == null ? 0L : n;
    }

    private long nodes(long specimenId, String node) {
        Long n = jdbc.queryForObject("select count(*) from path_process where specimen_id = ? and node = ?",
                Long.class, specimenId, node);
        return n == null ? 0L : n;
    }

    private Map<String, Object> latestNode(long specimenId, String node) {
        var rows = jdbc.queryForList("""
                select operator_id, remark, occurred_at from path_process
                where specimen_id = ? and node = ? order by id desc limit 1
                """, specimenId, node);
        assertFalse(rows.isEmpty(), "找不到 " + node + " 节点");
        return rows.get(0);
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

    private static Map<String, Object> techRow(Map<String, Object> body, long id) {
        return rows(body, "items").stream().filter(r -> idOf(r) == id).findFirst()
                .orElseGet(() -> fail("清单里找不到技术医嘱 " + id));
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.MIN_VALUE;
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

    private static String read(String rel) {
        try {
            return Files.readString(repoRoot().resolve(rel), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fail("读不到 " + rel + "：" + e.getMessage());
        }
    }
}
