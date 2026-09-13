package cn.hip.server;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v61（2530 复核两条）：自定义字段名入口 + 补取材追加后的字段版本口径。
 *
 * <p><b>修复前的反向事实</b>（v60 复核，两个镜头各自指出，主控实测坐实）：
 * <ul>
 *   <li><b>只做了后端</b>：结构化字段的字段名集合在界面上是封闭的——输入框只按「模板字段 ∪ 表单已有键」渲染，
 *       全页唯一的「增加」按钮是加蜡块，没有任何自定义字段名入口；模板清单又只能由运维直改 sys_config。
 *       于是「模板里没有『淋巴结清扫组数』这一项怎么办」的唯一答案是写进自由描述，即回落成一段非结构化文本，
 *       正是参数摘要「确保描述内容的结构化存储」要消灭的形态。而后端 {@code assembleGross} 本就支持任意字段名。</li>
 *   <li><b>口径与事实相反</b>：补取材追加后，当前文本是<b>累积全文</b>（旧文本 + 「。补取材：」+ 本次拼装），
 *       而字段行只有最后那一次追加的几项；此前两个版号相等就判 {@code fieldsCurrent=true}，
 *       等于把「覆盖不全」标成「与当前文本同版」，且没有任何一处能调阅出与当前全文对应的完整结构化记录。</li>
 * </ul>
 *
 * <p><b>本类钉住</b>：后端对任意字段名的四条校验（空 / 超长 / trim 后重名 / 超 20 项）全在写入之前且零写入；
 * 追加后 {@code fieldsCurrent=false}、{@code textIsCumulative=true}、{@code fieldsNote} 说清覆盖范围，
 * 而各版完整字段仍可由 {@code fieldsByRevision} 调阅；前端有自定义字段名入口（源码扫描，带活对照组）。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V61GrossFieldEntryTest {

    @Autowired PathologyProcessController process;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc;
    private long specimenId;

    @BeforeEach
    void setUp() {
        tag = "V61G" + Long.toHexString(System.nanoTime());
        doc = doctorAuth(tag + "d1");
        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "自定义字段测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "自定义字段患者" + tag);
        Long regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag);
        specimenId = jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent)
                values (?, 1, ?, ?, 'ROUTINE', ?, 'RECEIVED',
                        now() - interval '2 days' - interval '1 hour', now() - interval '2 days', false)
                returning id
                """, Long.class, orderId, "PB" + tag, tag + "-1", "自定义字段标本" + tag);
    }

    // =====================================================================================
    // ① 任意字段名可落库；四条非法路径在写入之前拒、零写入
    // =====================================================================================

    @Test
    void customFieldNamesLandAndIllegalOnesAreRejectedBeforeAnyWrite() {
        // 模板之外的字段名照样落库——后端一直支持，v61 之前只是前端没有入口
        var gross = new LinkedHashMap<String, String>();
        gross.put("标本大小", "5×4×3cm");
        gross.put("淋巴结清扫组数", "3 组");        // 内置模板里没有这一项
        var r = ok(process.grossing(new GrossingReq(specimenId, null, gross, "自由描述 " + tag, false, null,
                List.of(new BlockReq("肿物中心 " + tag))), doc));
        assertEquals(2, ((Number) r.get("grossFieldCount")).intValue());
        assertEquals(List.of("标本大小", "淋巴结清扫组数"), labelsOfRevision(1), "任意字段名按入参顺序落库");

        // 四条非法路径：空白名 / 超 32 字 / trim 后与已有项重名 / 超 20 项——全部零写入
        var snap = snapshot();
        assertRejected(Map.of("   ", "x"), "空白字段名");
        assertRejected(Map.of("名".repeat(33), "x"), "字段名超 32 字");

        var dup = new LinkedHashMap<String, String>();
        dup.put("边界", "清");
        dup.put(" 边界 ", "欠清");                   // JSON 里是两个键，trim 后同名
        assertRejected(dup, "trim 后重名");

        var many = new LinkedHashMap<String, String>();
        for (int i = 1; i <= 21; i++) many.put("字段" + i, "值" + i);
        assertRejected(many, "超 20 项");

        assertEquals(snap, snapshot(), "四条被拒路径都不得留下任何写入");
    }

    // =====================================================================================
    // ② 追加后：文本累积、字段只覆盖最后一次——口径要如实，且各版字段仍可调阅
    // =====================================================================================

    @Test
    void appendMakesTextCumulativeSoFieldsAreNotCurrent() {
        var first = new LinkedHashMap<String, String>();
        first.put("标本大小", "5×4×3cm");
        first.put("切面", "灰白");
        ok(process.grossing(new GrossingReq(specimenId, null, first, "首次 " + tag, false, null,
                List.of(new BlockReq("首块 " + tag))), doc));
        var v1 = ok(process.grossingView(specimenId));
        assertEquals(Boolean.TRUE, v1.get("fieldsCurrent"), "首写：字段就是当前文本的全部来源");
        assertEquals(Boolean.FALSE, v1.get("textIsCumulative"));

        // 补取材追加：文本变成累积全文，字段行只落本次这两项
        var second = new LinkedHashMap<String, String>();
        second.put("补取块数", "2 块");
        second.put("最大径", "0.8cm");
        ok(process.grossing(new GrossingReq(specimenId, null, second, "补取材 " + tag, true, null,
                List.of(new BlockReq("补块 " + tag))), doc));

        var v2 = ok(process.grossingView(specimenId));
        String text = String.valueOf(v2.get("grossFinding"));
        assertTrue(text.contains("5×4×3cm") && text.contains("。补取材：") && text.contains("2 块"),
                "当前文本是累积全文：" + text);
        assertEquals(2, ((Number) v2.get("fieldsRevisionSeq")).intValue());
        assertEquals(2, ((Number) v2.get("textRevisionSeq")).intValue());
        // **修复前这里是 true**：两个版号相等就判「与当前文本同版」，而字段只覆盖后半段
        assertEquals(Boolean.FALSE, v2.get("fieldsCurrent"),
                "追加后字段覆盖不全当前文本，不能标成与全文同版");
        assertEquals(Boolean.TRUE, v2.get("textIsCumulative"));
        String note = String.valueOf(v2.get("fieldsNote"));
        assertTrue(note.contains("累积全文") && note.contains("切换版本"),
                "口径要说清覆盖范围与去哪看全量：" + note);

        // 完整结构化记录仍调阅得到：各版都在，合起来正好对应累积全文的两段
        var byRev = rows(v2, "fieldsByRevision");
        assertEquals(2, byRev.size());
        assertEquals(List.of("标本大小", "切面"), labelsOf(rows(byRev.get(0), "fields")), "第 1 版字段仍可调阅");
        assertEquals(List.of("补取块数", "最大径"), labelsOf(rows(byRev.get(1), "fields")));
    }

    // =====================================================================================
    // ③ 源码扫描：前端确有自定义字段名入口（此前只能写进自由描述）
    // =====================================================================================

    @Test
    void grossingPanelHasACustomFieldEntry() throws IOException {
        String panel = stripComments(read("frontend/shell/src/views/medtech/pathology/GrossingPanel.vue"));
        assertTrue(panel.contains("添加字段"), "取材表单必须有自定义字段名入口，否则模板外的项只能写进自由描述");
        assertTrue(panel.contains("addCustomField"), "入口要真的往 form.gross 里加键，不是摆设");
        // 活的对照组：扫描器在读真文件——这两个既有形态必然在
        assertTrue(panel.contains("grossFields") && panel.contains("form.gross"),
                "对照组：扫描器确实读到了取材表单");
        // 轨迹面板是只读的，不该有录入口（反向对照，防止断言写成恒真）
        assertFalse(stripComments(read("frontend/shell/src/views/medtech/pathology/TrailPanel.vue"))
                .contains("addCustomField"), "只读面板不该有字段录入口");
    }

    // ==================================================================================
    // 助手
    // ==================================================================================

    /** 非法字段名：必须被拒（5222，与 assembleGross 同源），且一个字都不许写进去 */
    private void assertRejected(Map<String, String> gross, String why) {
        var r = process.grossing(new GrossingReq(specimenId, null, new LinkedHashMap<>(gross), null, false, null,
                List.of(new BlockReq("块 " + tag))), doc);
        assertEquals(5222, r.getCode(), why + " 应被拒：" + r.getMessage());
    }

    /** 四张表的快照——被拒路径前后必须逐字相等 */
    private String snapshot() {
        return jdbc.queryForObject("""
                select coalesce(s.gross_finding, '') || '|'
                     || (select count(*) from path_gross_field f where f.specimen_id = s.id) || '|'
                     || (select count(*) from path_gross_revision r where r.specimen_id = s.id) || '|'
                     || (select count(*) from path_block b where b.specimen_id = s.id) || '|'
                     || (select count(*) from path_process p where p.specimen_id = s.id and p.node = 'GROSSING')
                from path_specimen s where s.id = ?
                """, String.class, specimenId);
    }

    private List<String> labelsOfRevision(int revisionSeq) {
        return jdbc.queryForList("""
                select label from path_gross_field where specimen_id = ? and revision_seq = ? order by seq
                """, String.class, specimenId, revisionSeq);
    }

    private static List<String> labelsOf(List<Map<String, Object>> fields) {
        return fields.stream().map(f -> String.valueOf(f.get("label"))).toList();
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

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

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
