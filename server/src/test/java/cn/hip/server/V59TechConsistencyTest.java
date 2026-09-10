package cn.hip.server;

import cn.hip.medtech.web.PathQcController;
import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyController.DiagnoseReq;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BatchCompleteReq;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyProcessController.SlideReq;
import cn.hip.medtech.web.PathologyProcessController.StainReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.medtech.web.PathologyReportController.TechOrderReq;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * v59 车道 C：<b>2563 切片与特检医嘱的一致性（5274）+ 2576-③「报告出了 = 正式签发」</b>。
 *
 * <h2>每条断言钉住的「修复前的反向事实」（主控实测）</h2>
 * <ol>
 *   <li>{@link #legacyMismatchedAttachmentStillCountsAndIsNowVisible()}：执行进度只按 {@code path_slide.tech_order_id + stained_at}
 *       计数——用一条直接 SQL 造出旧世界的挂接行（HE 片挂在「免疫组化 CK7」上），进度<b>照样</b>推到 STAINED、done 照样 DONE
 *       且 warnings 为空；同一件事现在从端点走被 5274 拦住，而清单 / 穿透新增的 {@code attached_stain} 把这张挂错的片子
 *       写成「HE ×1」——修复前三处都不回挂接切片的实际染色。</li>
 *   <li>{@link #attachRejectsStainTypeOrItemMismatchBeforeInsert()}：修复前 slides 挂接只校验医嘱存在 / 同标本 / 同蜡块 / ORDERED，
 *       HE 片、CK20 片都能挂到 CK7 医嘱；现在按 {@code TECH_TO_STAIN} 映射与医嘱项目判 5274，被拒时一张片不插、不留 SECTION 节点，
 *       5272 四条仍先于 5274。</li>
 *   <li>{@link #stainRegistrationCannotRewriteItemOfAttachedSlide()} / {@link #batchCompleteRejectsWholeBatchOnItemMismatch()}：
 *       修复前 stain 与 batch-complete 的 {@code stain_item = coalesce(?, stain_item)} 可把已挂接切片的项目改成任何项目；
 *       现在入参非空且与医嘱项目不同 → 5274（批量整批不写，含批内普通切片），空照旧保留。</li>
 *   <li>{@link #listingsCarryAttachedStainSummary()}：两个清单分支 + WORKLOAD_TECH 穿透带 {@code attached_stain}
 *       （按 stain_type / stain_item 去重计数，「、」相连，无挂接为 NULL），{@code stained_count} 语义不变。</li>
 *   <li>{@link #executedMovesFromDiagnoseToIssue()}：修复前 diagnose 同一事务把 {@code outp_order} 置 EXECUTED（PathologyController:118-129），
 *       医生站在报告尚未初签 / 复签 / 签发时就显示「已执行」；现在 diagnose 后仍 CHARGED、issue 后 EXECUTED，被 5265 / 5262 拦下的
 *       签发不置，住院来源的签发不碰 {@code inp_order}。</li>
 * </ol>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：夹具时刻由 SQL 按 {@code now()} 现算；质控穿透的日期窗由 {@link BusinessDates#today()} 派生并两端各留一天余量。
 * 夹具与断言助手照抄 V58TechProgressTest。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V59TechConsistencyTest {

    @Autowired PathologyReportController report;
    @Autowired PathologyProcessController process;
    @Autowired PathologyController pathology;
    @Autowired PathQcController pathQc;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Long regId;
    private Long patientId;
    private Authentication doc1;
    private Authentication doc2;

    private long a;
    private long b;
    private long blockA;
    private long blockB;

    @BeforeEach
    void setUp() {
        assertEquals(1, setGate(PathologyReportController.TECH_DONE_GATE_KEY, "warn"), "techdone gate 行必须存在（V165 seed）");
        assertEquals(1, setGate(PathologyReportController.DOUBLE_SIGN_GATE_KEY, "warn"), "doublesign gate 行必须存在（V144 seed）");

        tag = "V59C" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");
        doc2 = doctorAuth(tag + "d2");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "特检一致性测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "特检一致性患者" + tag);
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
    // ① 反向事实：旧世界挂错的片子进度照算、done 照过；同一件事现在被 5274 拦住，且清单把它写成「HE ×1」
    // =====================================================================================

    @Test
    void legacyMismatchedAttachmentStillCountsAndIsNowVisible() {
        long ck7 = techOrder(a, blockA, "IHC", "CK7", "查 CK7 定来源 " + tag);
        assertProgress(ck7, "PENDING_SECTION", 0, 0);
        assertAttachedStain(ck7, null);

        // 直接 SQL 造出修复前能造出来的行：一张 HE 片（无项目）挂在「免疫组化 CK7」上且已染色
        Long uid = userId(tag + "d1");
        jdbc.update("""
                insert into path_slide(block_id, slide_no, slide_code, stain_type, stain_item, tech_order_id, stained_at, stained_by)
                select ?, 900, b.block_code || '-900', 'HE', null, ?, now(), ?
                from path_block b where b.id = ?
                """, blockA, ck7, uid, blockA);

        // 进度只按 tech_order_id + stained_at 计数：HE 片把「免疫组化 CK7」推到了「已染色待确认」
        assertProgress(ck7, "STAINED", 1, 1);

        // 同一件事从端点走，现在被 5274 拦住、一张片不插
        var viaEndpoint = process.slides(new SlideReq(blockA, 1, "HE", null, null, ck7), doc1);
        assertEquals(5274, viaEndpoint.getCode(), viaEndpoint.getMessage());
        assertEquals(1L, slidesOf(blockA, ck7), "被 5274 拦下的挂接一张片不插（只剩 SQL 造的那张）");

        // done 照样 DONE 且 warnings 为空——这就是反驳者说的「2 张 HE 片推到已完成而 warnings 为空」
        var done = ok(report.doneTechOrder(ck7, doc1));
        assertEquals("DONE", done.get("status"));
        assertEquals(List.of(), done.get("warnings"), "旧世界挂错的片子在完成校验里看不出来（gate 只看片数）");

        // 现在三处都能看见它挂的是 HE
        assertAttachedStain(ck7, "HE ×1");
        assertProgress(ck7, "DONE", 1, 1);
    }

    // =====================================================================================
    // ② 挂接：类型 / 项目与医嘱不一致 → 5274，插入之前；5272 仍先于 5274
    // =====================================================================================

    @Test
    void attachRejectsStainTypeOrItemMismatchBeforeInsert() {
        long ck7 = techOrder(a, blockA, "IHC", "CK7", null);

        // 类型不符：HE 片挂 IHC 医嘱
        var he = process.slides(new SlideReq(blockA, 2, "HE", null, null, ck7), doc1);
        assertEquals(5274, he.getCode(), he.getMessage());
        assertTrue(he.getMessage().contains("染色类型") && he.getMessage().contains("IHC"), he.getMessage());
        // 类型不符：SPECIAL 片带对的项目也不行
        assertEquals(5274, process.slides(new SlideReq(blockA, 1, "SPECIAL", "CK7", null, ck7), doc1).getCode());
        // 项目不符：IHC CK20 挂 CK7 医嘱
        var ck20 = process.slides(new SlideReq(blockA, 2, "IHC", "CK20", null, ck7), doc1);
        assertEquals(5274, ck20.getCode(), ck20.getMessage());
        assertTrue(ck20.getMessage().contains("项目") && ck20.getMessage().contains("CK7") && ck20.getMessage().contains("CK20"),
                ck20.getMessage());
        // 项目缺失：医嘱有项目而切片不给
        assertEquals(5274, process.slides(new SlideReq(blockA, 1, "IHC", null, null, ck7), doc1).getCode());
        // 四条被拒路径：一张片不插、不留 SECTION 节点
        assertEquals(0L, slidesOf(blockA, null), "被拒时一张片都不许插");
        assertEquals(0L, nodes(a, "SECTION"), "被拒的切片不留 SECTION 节点");
        assertProgress(ck7, "PENDING_SECTION", 0, 0);

        // 合法：IHC CK7（项目 trim 后比较）
        var okBody = ok(process.slides(new SlideReq(blockA, 2, "IHC", "  CK7 ", null, ck7), doc1));
        assertEquals(2, rows(okBody, "slides").size());
        for (var s : rows(okBody, "slides")) {
            assertEquals("IHC", s.get("stain_type"));
            assertEquals("CK7", s.get("stain_item"));
            assertEquals(ck7, asLong(s.get("tech_order_id")));
        }
        assertProgress(ck7, "SECTIONING", 2, 0);
        assertEquals(1L, nodes(a, "SECTION"));

        // 深切医嘱（无项目）：IHC 片不行，HE 片可挂且项目不限
        long deep = techOrder(a, blockA, "DEEP_CUT", null, null);
        assertEquals(5274, process.slides(new SlideReq(blockA, 1, "IHC", "CK7", null, deep), doc1).getCode());
        assertEquals(5274, process.slides(new SlideReq(blockA, 1, "IHC", null, null, deep), doc1).getCode());
        assertEquals(0L, slidesOf(blockA, deep));
        ok(process.slides(new SlideReq(blockA, 1, "HE", null, null, deep), doc1));
        ok(process.slides(new SlideReq(blockA, 1, "HE", "连续切片 " + tag, null, deep), doc1));
        assertEquals(2L, slidesOf(blockA, deep), "深切医嘱无项目：HE 片不限项目");

        // 特殊染色 / 分子病理各对各的染色类型
        long pas = techOrder(a, blockA, "SPECIAL_STAIN", "PAS", null);
        assertEquals(5274, process.slides(new SlideReq(blockA, 1, "IHC", "PAS", null, pas), doc1).getCode());
        ok(process.slides(new SlideReq(blockA, 1, "SPECIAL", "PAS", null, pas), doc1));
        long egfr = techOrder(a, blockA, "MOLECULAR", "EGFR", null);
        assertEquals(5274, process.slides(new SlideReq(blockA, 1, "SPECIAL", "EGFR", null, egfr), doc1).getCode());
        ok(process.slides(new SlideReq(blockA, 1, "MOLECULAR", "EGFR", null, egfr), doc1));

        // 5272 四条仍先于 5274：他标本的医嘱，即使类型也不对，返回的仍是 5272
        long onB = techOrder(b, blockB, "IHC", "Ki-67", null);
        assertEquals(5272, process.slides(new SlideReq(blockA, 1, "HE", null, null, onB), doc1).getCode());
        // 普通切片（不挂接）不受任何限制——旧契约不变
        ok(process.slides(new SlideReq(blockA, 1, "HE", null, null), doc1));
        ok(process.slides(new SlideReq(blockA, 1, "IHC", "随便", null), doc1));
    }

    // =====================================================================================
    // ③ 单张染色登记：已挂接「有项目」的医嘱，项目不得改成别的；空照旧保留
    // =====================================================================================

    @Test
    void stainRegistrationCannotRewriteItemOfAttachedSlide() {
        long ck7 = techOrder(a, blockA, "IHC", "CK7", null);
        var slides = rows(ok(process.slides(new SlideReq(blockA, 2, "IHC", "CK7", null, ck7), doc1)), "slides");
        long s1 = idOf(slides.get(0));
        long s2 = idOf(slides.get(1));

        // 修复前：coalesce(?, stain_item) 把 CK7 改成 CK20 照样登记、进度照样 +1
        var bad = process.stain(s1, new StainReq("GOOD", "CK20", null), doc1);
        assertEquals(5274, bad.getCode(), bad.getMessage());
        assertTrue(bad.getMessage().contains("CK7") && bad.getMessage().contains("CK20"), bad.getMessage());
        var row1 = slideRow(s1);
        assertNull(row1.get("stained_at"), "被拒不写：stained_at 仍空");
        assertNull(row1.get("quality"), "被拒不写：quality 仍空");
        assertEquals("CK7", row1.get("stain_item"), "被拒不写：项目仍是 CK7");
        assertEquals(0L, nodes(a, "STAIN"), "被拒不留 STAIN 节点");
        assertProgress(ck7, "SECTIONING", 2, 0);

        // 空照旧：保留 CK7
        ok(process.stain(s1, new StainReq("GOOD", null, null), doc1));
        assertEquals("CK7", slideRow(s1).get("stain_item"));
        assertNotNull(slideRow(s1).get("stained_at"));
        assertProgress(ck7, "SECTIONING", 2, 1);
        // 传相同项目（trim 后）照过
        ok(process.stain(s2, new StainReq("GOOD", " CK7 ", null), doc1));
        assertEquals("CK7", slideRow(s2).get("stain_item"));
        assertProgress(ck7, "STAINED", 2, 2);
        assertEquals(2L, nodes(a, "STAIN"));

        // 已染色的片子再传错项目：仍 5274（判在 update 之前，什么都不写）
        assertEquals(5274, process.stain(s1, new StainReq(null, "CK20", null), doc1).getCode());

        // 医嘱无项目（深切）：染色登记可以补任何项目
        long deep = techOrder(a, blockA, "DEEP_CUT", null, null);
        long d1 = idOf(rows(ok(process.slides(new SlideReq(blockA, 1, "HE", null, null, deep), doc1)), "slides").get(0));
        ok(process.stain(d1, new StainReq("GOOD", "连续切片 " + tag, null), doc1));
        assertEquals("连续切片 " + tag, slideRow(d1).get("stain_item"));

        // 普通切片不受限
        long plain = idOf(rows(ok(process.slides(new SlideReq(blockA, 1, "HE", null, null), doc1)), "slides").get(0));
        ok(process.stain(plain, new StainReq("GOOD", "任意项目", null), doc1));
        assertEquals("任意项目", slideRow(plain).get("stain_item"));
    }

    // =====================================================================================
    // ④ 批量核销：批内任一张不一致 → 5274 整批不写（含普通切片）；空照旧
    // =====================================================================================

    @Test
    void batchCompleteRejectsWholeBatchOnItemMismatch() {
        long ck7 = techOrder(a, blockA, "IHC", "CK7", null);
        var attached = rows(ok(process.slides(new SlideReq(blockA, 2, "IHC", "CK7", null, ck7), doc1)), "slides");
        long s1 = idOf(attached.get(0));
        long s2 = idOf(attached.get(1));
        long plain = idOf(rows(ok(process.slides(new SlideReq(blockA, 1, "HE", null, null), doc1)), "slides").get(0));
        String s1Code = String.valueOf(attached.get(0).get("slide_code"));

        var bad = process.batchComplete(new BatchCompleteReq(List.of(s1, s2, plain), "GOOD", "CK20", null), doc1);
        assertEquals(5274, bad.getCode(), bad.getMessage());
        assertTrue(bad.getMessage().contains(s1Code) && bad.getMessage().contains("#" + ck7 + " CK7"),
                "5274 消息应连同问题条目与医嘱项目返回，实际：" + bad.getMessage());
        for (long id : List.of(s1, s2, plain)) {
            assertNull(slideRow(id).get("stained_at"), "整批不写：切片 " + id + " 仍未染色");
            assertNull(slideRow(id).get("quality"));
        }
        assertEquals("CK7", slideRow(s1).get("stain_item"));
        assertNull(slideRow(plain).get("stain_item"), "普通切片也在整批不写之列");
        assertEquals(0L, nodes(a, "STAIN"), "整批被拒不留 STAIN 节点");
        assertProgress(ck7, "SECTIONING", 2, 0);

        // 空照旧：各自保留原值，整批核销
        var okBody = ok(process.batchComplete(new BatchCompleteReq(List.of(s1, s2, plain), "GOOD", null, null), doc1));
        assertEquals(3L, asLong(okBody.get("completed")));
        assertEquals("CK7", slideRow(s1).get("stain_item"));
        assertEquals("CK7", slideRow(s2).get("stain_item"));
        assertNull(slideRow(plain).get("stain_item"), "普通切片留空就还是空（coalesce 保留）");
        assertNotNull(slideRow(plain).get("stained_at"));
        assertEquals(1L, nodes(a, "STAIN"), "按标本去重一条 STAIN 节点");
        assertProgress(ck7, "STAINED", 2, 2);

        // 传相同项目照过；深切（无项目）挂接片与普通片一起传任意项目也照过
        long ck20 = techOrder(a, blockA, "IHC", "CK20", null);
        var more = rows(ok(process.slides(new SlideReq(blockA, 2, "IHC", "CK20", null, ck20), doc1)), "slides");
        ok(process.batchComplete(new BatchCompleteReq(List.of(idOf(more.get(0)), idOf(more.get(1))), null, "CK20", null), doc1));
        assertProgress(ck20, "STAINED", 2, 2);
        long deep = techOrder(a, blockA, "DEEP_CUT", null, null);
        long d1 = idOf(rows(ok(process.slides(new SlideReq(blockA, 1, "HE", null, null, deep), doc1)), "slides").get(0));
        long p2 = idOf(rows(ok(process.slides(new SlideReq(blockA, 1, "HE", null, null), doc1)), "slides").get(0));
        ok(process.batchComplete(new BatchCompleteReq(List.of(d1, p2), "FAIR", "补充说明", null), doc1));
        assertEquals("补充说明", slideRow(d1).get("stain_item"));
        assertEquals("补充说明", slideRow(p2).get("stain_item"));
    }

    // =====================================================================================
    // ⑤ 清单 / 穿透的 attached_stain 汇总；stained_count 语义不变
    // =====================================================================================

    @Test
    void listingsCarryAttachedStainSummary() {
        long ck7 = techOrder(a, blockA, "IHC", "CK7", null);
        assertAttachedStain(ck7, null);

        var s = rows(ok(process.slides(new SlideReq(blockA, 2, "IHC", "CK7", null, ck7), doc1)), "slides");
        assertAttachedStain(ck7, "IHC CK7 ×2");
        ok(process.stain(idOf(s.get(0)), new StainReq("GOOD", null, null), doc1));
        assertAttachedStain(ck7, "IHC CK7 ×2");
        assertProgress(ck7, "SECTIONING", 2, 1);

        // 旧世界（直接 SQL）挂错的两张：HE 无项目、IHC CK20——汇总按 stain_type、stain_item 排序、「、」相连
        Long uid = userId(tag + "d1");
        jdbc.update("""
                insert into path_slide(block_id, slide_no, slide_code, stain_type, stain_item, tech_order_id, stained_at, stained_by)
                select ?, 901, b.block_code || '-901', 'HE', null, ?, now(), ? from path_block b where b.id = ?
                """, blockA, ck7, uid, blockA);
        jdbc.update("""
                insert into path_slide(block_id, slide_no, slide_code, stain_type, stain_item, tech_order_id)
                select ?, 902, b.block_code || '-902', 'IHC', 'CK20', ? from path_block b where b.id = ?
                """, blockA, ck7, blockA);
        assertAttachedStain(ck7, "HE ×1、IHC CK20 ×1、IHC CK7 ×2");
        assertProgress(ck7, "SECTIONING", 4, 2);

        // 深切：三张 HE 无项目
        long deep = techOrder(a, blockA, "DEEP_CUT", null, null);
        ok(process.slides(new SlideReq(blockA, 3, "HE", null, null, deep), doc1));
        assertAttachedStain(deep, "HE ×3");

        // 另一标本的医嘱不串：B 上的 Ki-67 无挂接
        long onB = techOrder(b, blockB, "IHC", "Ki-67", null);
        assertAttachedStain(onB, null);
        ok(process.slides(new SlideReq(blockB, 1, "IHC", "Ki-67", null, onB), doc1));
        assertAttachedStain(onB, "IHC Ki-67 ×1");
        assertAttachedStain(deep, "HE ×3");
    }

    // =====================================================================================
    // ⑥ EXECUTED：diagnose 后仍 CHARGED（修复前 EXECUTED）、issue 后 EXECUTED；5265 / 5262 不置；住院来源不碰 inp_order
    // =====================================================================================

    @Test
    void executedMovesFromDiagnoseToIssue() {
        // --- 门诊 A：diagnose → 仍 CHARGED；issue → EXECUTED ---
        var dg = pathology.diagnose(barcodeOf(a), new DiagnoseReq("灰白组织 " + tag, "镜下 " + tag, "诊断 " + tag), doc1);
        assertEquals(0, dg.getCode(), dg.getMessage());
        assertEquals("DIAGNOSED", specimenStatus(a));
        assertEquals("CHARGED", orderStatus(a),
                "修复前 diagnose 同一事务把 outp_order 置 EXECUTED——医生站在报告尚未签发时就显示「已执行」");

        var issued = ok(report.issue(a, doc1));
        assertNotNull(issued.get("reportIssuedAt"));
        assertEquals(Boolean.TRUE, issued.get("orderExecuted"), "签发返回体回带「本次置了 EXECUTED」的事实");
        assertEquals("EXECUTED", orderStatus(a), "正式签发后门诊申请才是 EXECUTED");
        assertEquals("DIAGNOSED", specimenStatus(a), "path_specimen.status 值域不动（已签发看 report_issued_at）");
        // 重复签发 5261，状态不变
        assertEquals(5261, report.issue(a, doc1).getCode());
        assertEquals("EXECUTED", orderStatus(a));

        // --- 门诊 B：未诊断即签发 5262 → 不置；gate=block 缺双签 5265 → 不置；恢复 warn 后签发 → 置 ---
        assertEquals(5262, report.issue(b, doc1).getCode());
        assertEquals("CHARGED", orderStatus(b), "5262 拦下的签发不置 EXECUTED");
        assertEquals(0, pathology.diagnose(barcodeOf(b), new DiagnoseReq(null, null, "诊断 B " + tag), doc1).getCode());
        assertEquals("CHARGED", orderStatus(b));
        assertEquals(1, setGate(PathologyReportController.DOUBLE_SIGN_GATE_KEY, "block"));
        var blocked = report.issue(b, doc1);
        assertEquals(5265, blocked.getCode(), blocked.getMessage());
        assertEquals("CHARGED", orderStatus(b), "5265 拦下的签发不置 EXECUTED");
        assertNull(jdbc.queryForObject("select report_issued_at from path_specimen where id = ?", Object.class, b));
        assertEquals(1, setGate(PathologyReportController.DOUBLE_SIGN_GATE_KEY, "warn"));
        var issuedB = ok(report.issue(b, doc2));
        assertEquals(Boolean.TRUE, issuedB.get("orderExecuted"));
        assertEquals("EXECUTED", orderStatus(b));

        // --- 住院来源 C：diagnose 与 issue 都不碰 inp_order（此前 diagnose 也从未动过它） ---
        var inp = inpatientSpecimen("C");
        long c = inp.specimenId();
        assertEquals("CHARGED", inpOrderStatus(inp.inpOrderId()));
        assertEquals(0, pathology.diagnose(inp.barcode(), new DiagnoseReq(null, null, "诊断 C " + tag), doc1).getCode());
        assertEquals("CHARGED", inpOrderStatus(inp.inpOrderId()));
        var issuedC = ok(report.issue(c, doc1));
        assertNotNull(issuedC.get("reportIssuedAt"), "住院来源照常签发");
        assertEquals(Boolean.FALSE, issuedC.get("orderExecuted"), "住院来源：没有门诊申请可置，orderExecuted=false");
        assertEquals("CHARGED", inpOrderStatus(inp.inpOrderId()), "签发不碰 inp_order（维持既有口径，javadoc 写明）");
        // 门诊申请的总数对账：只有 A、B 两条被置，别的 CHARGED 行没被误伤
        assertEquals(2L, (long) jdbc.queryForObject(
                "select count(*) from outp_order where registration_id = ? and status = 'EXECUTED'", Long.class, regId));
    }

    // ==================== 断言与夹具 ====================

    /** 两个清单分支 + 质控穿透三处同口径（照抄 V58TechProgressTest.assertProgress） */
    private void assertProgress(long id, String progress, long slides, long stained) {
        assertRow("标本清单", bySpecimenRow(id), progress, slides, stained);
        assertRow("全院清单", wideRow(id), progress, slides, stained);
        assertRow("质控穿透", qcTechRow(id), progress, slides, stained);
    }

    private static void assertRow(String where, Map<String, Object> row, String progress, long slides, long stained) {
        assertEquals(progress, row.get("progress"), where + " progress：" + row);
        assertEquals(PathologyReportController.TECH_PROGRESS_NAMES.get(progress), row.get("progress_name"), where + " progress_name");
        assertEquals(slides, asLong(row.get("slide_count")), where + " slide_count");
        assertEquals(stained, asLong(row.get("stained_count")), where + " stained_count");
    }

    /** attached_stain 三处同口径；expected 为 null 即「列在、值为 NULL」 */
    private void assertAttachedStain(long id, String expected) {
        for (var e : List.of(Map.entry("标本清单", bySpecimenRow(id)), Map.entry("全院清单", wideRow(id)),
                Map.entry("质控穿透", qcTechRow(id)))) {
            assertTrue(e.getValue().containsKey("attached_stain"), e.getKey() + " 行必须带 attached_stain 列：" + e.getValue().keySet());
            assertEquals(expected, e.getValue().get("attached_stain"), e.getKey() + " attached_stain");
        }
    }

    private Map<String, Object> bySpecimenRow(long id) {
        return techRow(ok(report.techOrders(specimenOf(id), null, null, null, null, null, null, null, null)), id);
    }

    private Map<String, Object> wideRow(long id) {
        return techRow(ok(report.techOrders(null, "ALL", null, null, pathNoOf(id), null, null, null, null)), id);
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

    private Long userId(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
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

    private String specimenStatus(long specimenId) {
        return jdbc.queryForObject("select status from path_specimen where id = ?", String.class, specimenId);
    }

    private String orderStatus(long specimenId) {
        return jdbc.queryForObject(
                "select o.status from outp_order o join path_specimen s on s.order_id = o.id where s.id = ?",
                String.class, specimenId);
    }

    private String inpOrderStatus(long inpOrderId) {
        return jdbc.queryForObject("select status from inp_order where id = ?", String.class, inpOrderId);
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

    private record InpFixture(long specimenId, long inpOrderId, String barcode) {}

    /** 住院来源：inp_admission + inp_order(CHARGED) + 标本挂 inp_order_id、order_id 为 NULL（V144 双来源） */
    private InpFixture inpatientSpecimen(String suffix) {
        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        Long bedId = jdbc.queryForObject("select id from inp_bed order by id limit 1", Long.class);
        assertNotNull(bedId, "测试库须有床位种子（V8）");
        Long admId = jdbc.queryForObject("""
                insert into inp_admission(admission_no, patient_id, dept_id, ward_id, bed_id, status, admit_at)
                values (?, ?, ?, ?, ?, 'IN_HOSPITAL', now()) returning id
                """, Long.class, tag + "ADM", patientId, deptId, deptId, bedId);
        Long inpOrderId = jdbc.queryForObject("""
                insert into inp_order(admission_id, group_no, order_type, item_id, item_code, item_name,
                                      unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, admId, "I" + tag + suffix);
        String barcode = "PB" + tag + suffix;
        long sid = jdbc.queryForObject("""
                insert into path_specimen(inp_order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent)
                values (?, 1, ?, ?, 'ROUTINE', ?, 'RECEIVED',
                        now() - interval '2 days' - interval '1 hour', now() - interval '2 days', false)
                returning id
                """, Long.class, inpOrderId, barcode, pathNo(suffix), "住院标本" + suffix);
        return new InpFixture(sid, inpOrderId, barcode);
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

    private Map<String, Object> slideRow(long slideId) {
        return jdbc.queryForMap("select stain_type, stain_item, stained_at, quality from path_slide where id = ?", slideId);
    }

    /** 该蜡块上挂接到某医嘱（null 即全部）的切片数 */
    private long slidesOf(long blockId, Long techOrderId) {
        var args = new ArrayList<Object>();
        args.add(blockId);
        String sql = "select count(*) from path_slide where block_id = ?";
        if (techOrderId != null) {
            sql += " and tech_order_id = ?";
            args.add(techOrderId);
        }
        Long n = jdbc.queryForObject(sql, Long.class, args.toArray());
        return n == null ? 0L : n;
    }

    private long nodes(long specimenId, String node) {
        Long n = jdbc.queryForObject("select count(*) from path_process where specimen_id = ? and node = ?",
                Long.class, specimenId, node);
        return n == null ? 0L : n;
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

    // ==================================================================================
    // v59 审阅补：前端 ProcessingPanel 抄了一份 TECH_TO_STAIN 做下拉锁定；后端 5274 是最终守卫，
    // 但两份不同源就会「前端锁成 A、后端拦成 B」多一次被打回——机械断言逐键逐字相等。
    // ==================================================================================

    @Test
    void frontendTechToStainMirrorsBackend() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !(java.nio.file.Files.isDirectory(root.resolve("frontend")) && java.nio.file.Files.isDirectory(root.resolve("modules")))) root = root.getParent();
        assertNotNull(root, "找不到仓库根");
        String vue = java.nio.file.Files.readString(root.resolve("frontend/shell/src/views/medtech/pathology/ProcessingPanel.vue"));
        vue = vue.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//[^\\n]*", "");
        java.util.regex.Matcher obj = java.util.regex.Pattern.compile("const TECH_TO_STAIN[^=]*=\\s*\\{([^}]*)\\}").matcher(vue);
        assertTrue(obj.find(), "ProcessingPanel.vue 里找不到 const TECH_TO_STAIN = { … }");
        var front = new java.util.LinkedHashMap<String, String>();
        java.util.regex.Matcher kv = java.util.regex.Pattern.compile("([A-Z_]+)\\s*:\\s*'([A-Z_]+)'").matcher(obj.group(1));
        while (kv.find()) front.put(kv.group(1), kv.group(2));
        assertEquals(PathologyProcessController.TECH_TO_STAIN, front,
                "前端 TECH_TO_STAIN 必须与后端逐键逐字相同（后端是最终守卫，前端只是锁定下拉）");
        assertTrue(front.size() >= 6, "活的对照组：至少六种特检类型，实际 " + front);
    }
}
