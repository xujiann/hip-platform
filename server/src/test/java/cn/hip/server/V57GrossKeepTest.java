package cn.hip.server;

import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyController.DiagnoseReq;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
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
 * v57 车道 A（2530）：<b>取材记录不被旧页抹掉</b>。
 *
 * <h2>修复前的反向事实</h2>
 * {@code PUT /api/pathology/specimens/{barcode}/diagnose} 对 {@code gross_finding} / {@code micro_finding}
 * 无条件覆盖：取材工位（真实 grossing 端点，GROSSING 节点）写进去的大体所见 G，只要出报告时该字段传空
 * 就被置空——旧页「专科流程」（SpecialtyView.vue，菜单 47）正是这样调的，而且传的还是写死的伪造文本
 * 「灰白组织一块」。本类三组断言分别钉住：
 * <ol>
 *   <li>{@link #blankGrossAndMicroKeepWhatGrossingWrote()}：传 {@code ''} / {@code null} → G 仍在、诊断已写、
 *       status=DIAGNOSED、响应 {@code grossKept=true}（修复前 G 被置成空串，此断言必红）。</li>
 *   <li>{@link #explicitValuesStillOverwriteAndAreTrimmed()}：显式传 G2 → 覆盖为 G2（工作台预填后编辑的合法路径，
 *       契约不变），且入库已 trim。</li>
 *   <li>{@link #legacySpecialtyPageNoLongerCallsDiagnose()}：旧页剥注释后既无诊断端点调用也无大体 / 镜下字段；
 *       对照组 DiagnosisPanel.vue 剥注释后<b>含</b>真实调用（证明扫描器在读真实文件）；
 *       {@link #diagnoseCallDetectorActuallyBites()} 自证注释掉的调用不计、裸 token 不计。</li>
 * </ol>
 *
 * <h2>夹具</h2>
 * 每个标本都走真实端点：既有 collect（登记打码）→ receive（核收）→ 真实 grossing（写大体所见 + 蜡块）。
 * 不写任何时间字面量；事务回滚，库里不留痕。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V57GrossKeepTest {

    static final String OLD_PAGE = "frontend/shell/src/views/medtech/SpecialtyView.vue";
    static final String WORKBENCH_PANEL = "frontend/shell/src/views/medtech/pathology/DiagnosisPanel.vue";

    @Autowired PathologyController pathology;
    @Autowired PathologyProcessController process;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Long regId;
    private Authentication doc;

    @BeforeEach
    void setUp() {
        tag = "V57A" + Long.toHexString(System.nanoTime());
        doc = doctorAuth(tag + "d1");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "取材保留测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "取材保留患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
    }

    // =====================================================================================
    // (a) 空白即保留：取材写的 G 不被 '' / null 抹掉
    // =====================================================================================

    @Test
    void blankGrossAndMicroKeepWhatGrossingWrote() {
        String g = "灰白组织一块，3×2×1cm，切面实性 " + tag;
        Fixture f = specimenWithGross("A", g);
        String dx = "（胃窦）慢性浅表性胃炎 " + tag;

        // 修复前：update 无条件 set gross_finding = ''（入参原样写入），G 在这里被抹掉
        var r = pathology.diagnose(f.barcode(), new DiagnoseReq("", null, dx), doc);
        assertEquals(0, r.getCode(), r.getMessage());

        Map<String, Object> row = jdbc.queryForMap("""
                select status, gross_finding, micro_finding, diagnosis, pathologist_id, diagnosed_at
                from path_specimen where id = ?
                """, f.id());
        assertEquals(g, row.get("gross_finding"), "取材工位写入的大体所见不得被空白入参抹掉");
        assertNull(row.get("micro_finding"), "原本没有镜下所见，传 null 也不该变成空串");
        assertEquals(dx, row.get("diagnosis"), "诊断照常写入");
        assertEquals("DIAGNOSED", row.get("status"));
        assertNotNull(row.get("diagnosed_at"));
        assertEquals(userId(tag + "d1"), row.get("pathologist_id"));

        // 响应只带四个事实（v58 起多 grossRevised：本次覆盖是否写了大体所见修订行）
        assertNotNull(r.getData(), "返回体不再是 R<Void>");
        assertEquals(Set.of("specimenId", "grossKept", "microKept", "grossRevised"), r.getData().keySet());
        assertEquals(f.id(), ((Number) r.getData().get("specimenId")).longValue());
        assertEquals(Boolean.TRUE, r.getData().get("grossKept"), "入参空白且原值非空 → 被保留");
        assertEquals(Boolean.FALSE, r.getData().get("microKept"), "原值为空的列没有「被保留」可言");

        // 其余逐字节不动：医嘱联动、状态守卫、4552
        assertEquals("EXECUTED", jdbc.queryForObject(
                "select status from outp_order where id = ?", String.class, f.orderId()));
        assertEquals(4553, pathology.diagnose(f.barcode(), new DiagnoseReq(null, null, "再来一次"), doc).getCode(),
                "已诊断的标本再诊断仍是 4553（状态守卫未动）");
        assertEquals(4552, pathology.diagnose(f.barcode(), new DiagnoseReq("x", "y", "   "), doc).getCode(),
                "诊断空白仍是 4552，且先于状态守卫");
    }

    @Test
    void whitespaceOnlyIsBlankAndPreexistingMicroIsKeptToo() {
        String g = "灰白组织两块 " + tag;
        String m = "镜下见慢性炎细胞浸润 " + tag;
        Fixture f = specimenWithGross("W", g);
        // 夹具：镜下所见此前唯一写入口就是本端点，故直接落库造出「原值非空」的事实
        jdbc.update("update path_specimen set micro_finding = ? where id = ?", m, f.id());

        // 修复前：gross_finding 会被写成 "  \t "、micro_finding 被写成 ""——两处断言都红
        var r = pathology.diagnose(f.barcode(), new DiagnoseReq("  \t ", "", "慢性炎症 " + tag), doc);
        assertEquals(0, r.getCode(), r.getMessage());
        var row = jdbc.queryForMap("select gross_finding, micro_finding from path_specimen where id = ?", f.id());
        assertEquals(g, row.get("gross_finding"), "纯空白与空串同义：保留原值");
        assertEquals(m, row.get("micro_finding"));
        assertEquals(Boolean.TRUE, r.getData().get("grossKept"));
        assertEquals(Boolean.TRUE, r.getData().get("microKept"));
    }

    // =====================================================================================
    // (b) 显式传值仍覆盖（工作台预填后编辑的合法路径，契约不变）
    // =====================================================================================

    @Test
    void explicitValuesStillOverwriteAndAreTrimmed() {
        String g = "灰白组织一块 " + tag;
        Fixture f = specimenWithGross("B", g);
        String g2 = "灰白组织一块，切面实性，质硬 " + tag;
        String m2 = "镜下见异型细胞浸润 " + tag;

        var r = pathology.diagnose(f.barcode(), new DiagnoseReq("  " + g2 + "  ", " " + m2 + "\t", "浸润性导管癌 " + tag), doc);
        assertEquals(0, r.getCode(), r.getMessage());
        var row = jdbc.queryForMap("select status, gross_finding, micro_finding from path_specimen where id = ?", f.id());
        assertEquals(g2, row.get("gross_finding"), "显式传值仍覆盖原值，且入库已 trim");
        assertEquals(m2, row.get("micro_finding"));
        assertEquals("DIAGNOSED", row.get("status"));
        assertEquals(Boolean.FALSE, r.getData().get("grossKept"), "覆盖了就不叫保留");
        assertEquals(Boolean.FALSE, r.getData().get("microKept"));
        assertEquals(f.id(), ((Number) r.getData().get("specimenId")).longValue());
    }

    // =====================================================================================
    // (c) 源码扫描：旧页不再调诊断端点；对照组是活的；检测器会咬人
    // =====================================================================================

    @Test
    void legacySpecialtyPageNoLongerCallsDiagnose() {
        String raw = read(OLD_PAGE);
        String old = stripComments(raw);
        assertFalse(callsDiagnose(old), "旧页「专科流程」不得再调 PUT .../diagnose——那是取材记录被抹掉的路径");
        assertFalse(touchesGrossFields(old), "旧页不得再拼大体 / 镜下所见字段（此前是写死的伪造文本）");
        // 字样级（任务硬要求：连注释都不许留）
        assertFalse(raw.contains("/diagnose"), "文件里不得再有 '/diagnose' 字样");
        assertFalse(raw.contains("grossFinding") || raw.contains("microFinding"), "文件里不得再有大体 / 镜下字段名");
        assertTrue(raw.contains("/pathology/workbench"), "RECEIVED 行的按钮应把人带去病理工作台");
        assertFalse(raw.contains("replace('T', ' ')") || raw.contains("slice(0, 16)") || raw.contains("slice(0, 10)"),
                "裸切时间戳应已换成 utils/date 的 fmtDateTime / fmtDate");

        // 活的对照组：工作台 DiagnosisPanel 剥注释后仍含真实调用——否则上面的 false 只说明扫描器没在读文件
        String panel = stripComments(read(WORKBENCH_PANEL));
        assertTrue(callsDiagnose(panel), "对照组：DiagnosisPanel.vue 剥注释后应含 client.put(`.../diagnose`) 调用");
        assertTrue(touchesGrossFields(panel), "对照组：DiagnosisPanel.vue 剥注释后应含 grossFinding: 字段");
    }

    @Test
    void diagnoseCallDetectorActuallyBites() {
        String panel = read(WORKBENCH_PANEL);
        assertTrue(callsDiagnose(stripComments(panel)), "真实源码应判「有调用」");

        // 把含调用的行逐行注释掉 → 剥注释后不计；未剥注释时同一文本仍能匹配，说明是剥注释在起作用
        String commentedOut = panel.lines()
                .map(l -> CALL.matcher(l).find() ? "// " + l : l)
                .collect(Collectors.joining("\n"));
        assertTrue(callsDiagnose(commentedOut), "未剥注释时被注释的调用仍能匹配到——否则下面的 false 不说明任何事");
        assertFalse(callsDiagnose(stripComments(commentedOut)), "注释掉的调用不该计入");

        // 三种注释形态都不计；活的调用计
        assertFalse(callsDiagnose(stripComments("/* client.put(`/pathology/specimens/x/diagnose`, {}) */")));
        assertFalse(callsDiagnose(stripComments("<!-- client.put(`/pathology/specimens/x/diagnose`, {}) -->")));
        assertFalse(callsDiagnose(stripComments("  // client.put('/pathology/specimens/x/diagnose', {})")));
        assertTrue(callsDiagnose(stripComments("await client.put(`/pathology/specimens/${b}/diagnose`, {})")));
        assertTrue(callsDiagnose(stripComments("client.put('/pathology/specimens/PB1/diagnose', body)")));

        // 裸 token 不算：prose / 属性文案里提到端点名不是调用
        assertFalse(callsDiagnose(stripComments(
                "title=\"本操作走既有 PUT /api/pathology/specimens/{barcode}/diagnose，会整体写入\"")));
        assertFalse(touchesGrossFields(stripComments("const label = '大体所见 grossFinding'")));
        assertTrue(touchesGrossFields(stripComments("const form = reactive({ grossFinding: '' })")));
        assertTrue(touchesGrossFields(stripComments("form.microFinding = x")));
        assertFalse(touchesGrossFields(stripComments("// form.microFinding = x")));

        // 剥注释不误伤 URL 里的 //
        assertEquals("const u = 'http://x/diagnose'", stripComments("const u = 'http://x/diagnose'"));
    }

    // ==================== 检测器 ====================

    /** 诊断端点的调用形态：client.put/post(<引号或反引号开头的 URL 字面量 … /diagnose<引号或反引号> */
    private static final Pattern CALL = Pattern.compile(
            "client\\.(put|post|patch)\\(\\s*[`'\"][^`'\"\\n]*/diagnose[`'\"]");
    /** 大体 / 镜下字段的语法形态：对象键 `grossFinding:`、赋值 `grossFinding =`、成员 `.grossFinding` */
    private static final Pattern GROSS_FIELD = Pattern.compile(
            "\\b(grossFinding|microFinding)\\s*[:=]|\\.(grossFinding|microFinding)\\b");
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 行注释：// 前是行首或空白（不碰 http:// 这类紧跟冒号的） */
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(^|\\s)//.*$");

    static String stripComments(String src) {
        String s = HTML_COMMENT.matcher(src).replaceAll("");
        s = BLOCK_COMMENT.matcher(s).replaceAll("");
        return LINE_COMMENT.matcher(s).replaceAll("$1");
    }

    static boolean callsDiagnose(String src) {
        return CALL.matcher(src).find();
    }

    static boolean touchesGrossFields(String src) {
        return GROSS_FIELD.matcher(src).find();
    }

    // ==================== 夹具与取值 ====================

    private record Fixture(long id, String barcode, Long orderId) {}

    /** 一条已收费门诊病理医嘱 → 既有 collect 登记打码 → 既有 receive 核收 → 真实 grossing 写大体所见 */
    private Fixture specimenWithGross(String suffix, String gross) {
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

        var gr = process.grossing(new GrossingReq(id, null, null, gross, false, null,
                List.of(new BlockReq("肿物中心"))), doc);
        assertEquals(0, gr.getCode(), gr.getMessage());
        assertEquals(Boolean.TRUE, gr.getData().get("grossFindingWritten"), "取材端点应真的写了大体所见");
        assertEquals(gross, jdbc.queryForObject(
                "select gross_finding from path_specimen where id = ?", String.class, id),
                "夹具前提：取材后 gross_finding 就是 G");
        assertEquals("RECEIVED", jdbc.queryForObject(
                "select status from path_specimen where id = ?", String.class, id));
        return new Fixture(id, barcode, orderId);
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

    // 源码读取（照抄 V55EmrVersionEntryTest：从 user.dir 向上找仓库根）

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
