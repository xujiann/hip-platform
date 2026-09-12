package cn.hip.server;

import cn.hip.medtech.web.PathQcController;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyProcessController.SlideReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.medtech.web.PathologyReportController.CancelTechOrderReq;
import cn.hip.medtech.web.PathologyReportController.TechOrderReq;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v61（2563 复核两条）：「蜡块」列的来源判定与值同源、流转节点备注去裸英文枚举。
 *
 * <p><b>修复前的反向事实</b>（v60 复核，两个镜头各自指出，主控实测坐实）：
 * <ul>
 *   <li><b>改了值没改判定</b>：v60 把 {@code blocks_derived} 由 {@code coalesce(b.block_code, union…)} 改成三源并集，
 *       但前端「派生」标的条件仍写作 {@code !row.block_code && row.blocks_derived}。而补取材挂接会把医嘱
 *       {@code block_id} 由空<b>回写为本次首块</b>，回写一发生 {@code block_code} 就非空、判定恒 false——
 *       补取材出 2 块时「蜡块」列显示两个块码却<b>不带「派生」标</b>，屏幕上与「下达时医师指定了这两块」完全同形。
 *       现在判定由后端与并集<b>同一处 SQL</b> 算出（{@code blocks_derived_source}），前端只读不推断。</li>
 *   <li><b>备注裸码</b>：{@code techLabel} 拼的是 {@code #12 免疫组化(IHC) CK7}，括号里是内部枚举；
 *       且两个清单都没有医嘱 id 列，节点里的 {@code #12} 在清单上无从对应（同一标本先取消一条 IHC CK7
 *       再下一条时，两行类型项目逐字相同）。现在备注只留中文，清单各补一列「医嘱号」。</li>
 * </ul>
 *
 * <p><b>「指定」的口径</b>：{@code t.block_id} 指向的块<b>且该块不是为本医嘱补出的</b>才算医师指定——
 * 补取材回写进 {@code block_id} 的首块是系统写的，算派生；否则「回写」本身会把系统产物标成「医师指定」，
 * 等于把同一个半截修复换个地方再犯一次。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V61TechLabelTest {

    /** 括号里的裸英文枚举：( 后紧跟全大写 / 下划线 ) —— 修复前 techLabel 拼的就是这个形态 */
    private static final Pattern RAW_ENUM = Pattern.compile("\\([A-Z][A-Z_]*\\)");

    @Autowired PathologyProcessController process;
    @Autowired PathologyReportController report;
    @Autowired PathQcController pathQc;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc1;
    private long a;          // 标本
    private long blockA;     // 首次取材的块（医师下达时可指定它）
    private String codeA;

    @BeforeEach
    void setUp() {
        tag = "V61T" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");
        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "派生标测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "派生标患者" + tag);
        Long regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag);
        a = jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent)
                values (?, 1, ?, ?, 'ROUTINE', ?, 'RECEIVED',
                        now() - interval '2 days' - interval '1 hour', now() - interval '2 days', false)
                returning id
                """, Long.class, orderId, "PB" + tag, tag + "-1", "派生标标本" + tag);
        var gr = ok(process.grossing(new GrossingReq(a, null, null, "首次取材 " + tag, false, null,
                List.of(new BlockReq("肿物中心 " + tag))), doc1));
        blockA = idOf(rows(gr, "blocks").get(0));
        codeA = blockCode(blockA);
    }

    // =====================================================================================
    // ① 四种来源场景：ORDERED / DERIVED / MIXED / null——判定与值同一处 SQL 算出
    // =====================================================================================

    @Test
    void blocksDerivedSourceCoversFourCasesAndStaysInSyncWithTheValue() {
        // (a) 无块：并集为空 → 两列都是 null
        long bare = techOrder(a, null, "IHC", "Ki-67");
        assertSource(bare, null, null);

        // (b) 只下达时指定：医师点名 blockA，且该块不是为本医嘱补出的 → ORDERED
        long ordered = techOrder(a, blockA, "SPECIAL_STAIN", "PAS");
        assertSource(ordered, codeA, "ORDERED");

        // (c) 只挂接派生：不指定块，从 blockA 挂片 → DERIVED
        long derived = techOrder(a, null, "IHC", "CK7");
        ok(process.slides(new SlideReq(blockA, 1, "IHC", "CK7", null, derived), doc1));
        assertSource(derived, codeA, "DERIVED");

        // (d) 补取材：挂接出两块。医嘱 block_id 被回写为首块——**回写来的块算派生不算指定**，
        //     所以两块全派生 → DERIVED（修复前前端按 !block_code 判，此场景恒不标派生）
        long resample = techOrder(a, null, "RESAMPLE", null);
        var gr = ok(process.grossing(new GrossingReq(a, null, null, null, true, null,
                List.of(new BlockReq("补块一 " + tag), new BlockReq("补块二 " + tag)), resample), doc1));
        var created = rows(gr, "blocks");
        String c1 = String.valueOf(created.get(0).get("block_code"));
        String c2 = String.valueOf(created.get(1).get("block_code"));
        assertEquals(idOf(created.get(0)), orderBlockId(resample), "夹具前提：block_id 已回写为本次首块");
        assertSource(resample, c1 + "、" + c2, "DERIVED");

        // (e) MIXED 在真实业务路径下**不可达**，如实钉住这个事实而不是绕过规则去造数：
        //     v57 的 5272 规定「医嘱指定了蜡块就只能挂那一块」（v60 只为「为该医嘱补出的块」放宽一档），
        //     所以「既有医师指定的块、又有别处挂片派生来的块」这一组合走端点走不出来。
        long specified = techOrder(a, blockA, "DEEP_CUT", null);
        var blocked = process.slides(new SlideReq(idOf(created.get(0)), 1, "HE", null, null, specified), doc1);
        assertEquals(5272, blocked.getCode(), "指定了蜡块的医嘱不能往别的块挂片（v57 规则），故 MIXED 端点不可达");
        assertSource(specified, codeA, "ORDERED");
        // SQL 仍能算出 MIXED：直接造一条「指定块 + 别处挂片」的库状态证明判定本身正确，
        // 免得这一档成为永远走不到、也从未被验证过的死代码。
        jdbc.update("""
                insert into path_slide(block_id, slide_no, slide_code, stain_type, stain_item, tech_order_id)
                select ?, 903, b.block_code || '-903', 'HE', null, ? from path_block b where b.id = ?
                """, idOf(created.get(0)), specified, idOf(created.get(0)));
        assertSource(specified, codeA + "、" + c1, "MIXED");
    }

    // =====================================================================================
    // ② 节点备注去裸码；#id 保留（追溯靠它对回清单的「医嘱号」列）
    // =====================================================================================

    @Test
    void processNodeRemarksCarryChineseTypeAndNoRawEnum() {
        long ihc = techOrder(a, blockA, "IHC", "CK7");
        String ordered = remarkOf(a, "TECH_ORDER", ihc);
        assertTrue(ordered.contains("#" + ihc + " "), "#id 保留（追溯要靠它）：" + ordered);
        assertTrue(ordered.contains("免疫组化"), "类型用中文：" + ordered);
        assertNoRawEnum(ordered, "TECH_ORDER");

        ok(report.doneTechOrder(ihc, doc1));
        assertNoRawEnum(remarkOf(a, "TECH_DONE", ihc), "TECH_DONE");

        long other = techOrder(a, null, "SPECIAL_STAIN", "PAS");
        ok(report.cancelTechOrder(other, new CancelTechOrderReq("不做了 " + tag), doc1));
        String cancelled = remarkOf(a, "TECH_CANCEL", other);
        assertTrue(cancelled.contains("特殊染色"), "类型用中文：" + cancelled);
        assertNoRawEnum(cancelled, "TECH_CANCEL");

        // 活的对照组：修复前的字符串形态必须被同一正则抓到，否则这条断言是恒真的
        assertTrue(RAW_ENUM.matcher("#12 免疫组化(IHC) CK7").find(), "对照组：修复前形态必须被抓到");
    }

    // =====================================================================================
    // ③ 源码扫描：两个清单都读 blocks_derived_source、都有「医嘱号」列，且不再按 !block_code 推断
    // =====================================================================================

    @Test
    void panelsReadTheSourceColumnAndNoLongerInferFromBlockCode() throws IOException {
        for (String rel : List.of("frontend/shell/src/views/medtech/pathology/TechOrderPanel.vue",
                "frontend/shell/src/views/medtech/pathology/DiagnosisPanel.vue")) {
            String src = stripComments(read(rel));
            assertTrue(src.contains("blocks_derived_source"), rel + " 的「派生」标必须读后端判定列");
            assertTrue(src.contains("医嘱号"), rel + " 必须有「医嘱号」列，否则节点备注里的 #id 对不回清单行");
            assertFalse(src.contains("!row.block_code"), rel + " 不得再按 !block_code 二次推断是否派生（补取材回写后恒 false）");
        }
        // 活的对照组：扫描器确实在读真文件——两个面板都必然含这个既有键
        assertTrue(stripComments(read("frontend/shell/src/views/medtech/pathology/TechOrderPanel.vue"))
                .contains("blocks_derived"), "对照组：扫描器在读真文件");
    }

    // ==================================================================================
    // 助手
    // ==================================================================================

    /** 三处清单同口径：标本清单 / 全院清单 / 质控穿透 */
    private void assertSource(long techOrderId, String blocksDerived, String source) {
        for (var e : List.of(Map.entry("标本清单", bySpecimenRow(techOrderId)),
                Map.entry("全院清单", wideRow(techOrderId)),
                Map.entry("质控穿透", qcRow(techOrderId)))) {
            var row = e.getValue();
            assertTrue(row.containsKey("blocks_derived_source"), e.getKey() + " 行必须带 blocks_derived_source：" + row.keySet());
            assertEquals(blocksDerived, row.get("blocks_derived"), e.getKey() + " blocks_derived");
            assertEquals(source, row.get("blocks_derived_source"), e.getKey() + " blocks_derived_source");
            // 判定与值同源：有值必有来源、无值必无来源
            assertEquals(row.get("blocks_derived") == null, row.get("blocks_derived_source") == null,
                    e.getKey() + " 值与来源必须同生同灭：" + row.get("blocks_derived") + " / " + row.get("blocks_derived_source"));
        }
    }

    private void assertNoRawEnum(String remark, String node) {
        assertFalse(RAW_ENUM.matcher(remark).find(), node + " 备注不得含括号里的裸英文枚举：" + remark);
    }

    private String remarkOf(long specimenId, String node, long techOrderId) {
        String r = jdbc.queryForObject("""
                select remark from path_process
                where specimen_id = ? and node = ? and remark like ?
                order by id desc limit 1
                """, String.class, specimenId, node, "%#" + techOrderId + " %");
        assertNotNull(r, node + " 节点未找到（医嘱 #" + techOrderId + "）");
        return r;
    }

    private Map<String, Object> bySpecimenRow(long id) {
        return techRow(ok(report.techOrders(a, null, null, null, null, null, null, null, null, null)), id);
    }

    private Map<String, Object> wideRow(long id) {
        return techRow(ok(report.techOrders(null, "ALL", null, null, null, null, null, null, null, null)), id);
    }

    private Map<String, Object> qcRow(long id) {
        var body = ok(pathQc.detail("WORKLOAD_TECH", null, null, null, null));
        for (var r : rows(body, "items")) {
            if (asLong(r.get("tech_order_id")) == id) return r;
        }
        throw new AssertionError("质控穿透里找不到医嘱 " + id);
    }

    private Map<String, Object> techRow(Map<String, Object> body, long id) {
        for (var r : rows(body, "items")) {
            if (asLong(r.get("id")) == id) return r;
        }
        throw new AssertionError("清单里找不到医嘱 " + id);
    }

    private long techOrder(long specimenId, Long blockId, String type, String item) {
        var t = ok(report.createTechOrder(new TechOrderReq(specimenId, blockId, type, item, "原因 " + tag), doc1));
        return ((Number) t.get("id")).longValue();
    }

    private Long orderBlockId(long techOrderId) {
        return jdbc.queryForObject("select block_id from path_tech_order where id = ?", Long.class, techOrderId);
    }

    private String blockCode(long blockId) {
        return jdbc.queryForObject("select block_code from path_block where id = ?", String.class, blockId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
        Object v = body.get(key);
        assertNotNull(v, "返回体缺键 " + key + "：" + body.keySet());
        return (List<Map<String, Object>>) v;
    }

    private static Map<String, Object> ok(R<Map<String, Object>> r) {
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

    private static long idOf(Map<String, Object> row) {
        return asLong(row.get("id"));
    }

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    /** 从 user.dir 向上找仓库根（照抄 V57GrossKeepTest） */
    private static String read(String rel) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !(Files.isDirectory(root.resolve("frontend")) && Files.isDirectory(root.resolve("modules")))) {
            root = root.getParent();
        }
        assertNotNull(root, "找不到仓库根");
        return Files.readString(root.resolve(rel));
    }

    /** 剥 HTML / 块 / 行注释——注释里的形态不算数（本仓三次源码扫描误判的教训） */
    private static String stripComments(String s) {
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("(?m)^\\s*//[^\\n]*", "");
        return s;
    }
}
