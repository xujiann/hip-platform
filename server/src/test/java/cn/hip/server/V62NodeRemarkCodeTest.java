package cn.hip.server;

import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyProcessController.SlideReq;
import cn.hip.medtech.web.PathologyProcessController.StainReq;
import cn.hip.medtech.web.PathologyReportController;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * v62 合并后补齐（2563 复核第三条的剩余部分）：<b>GROSSING / SECTION / STAIN 三类流转节点备注去裸码</b>。
 *
 * <p><b>为什么是合并后才补</b>：这三处备注都在 {@code PathologyProcessController} 里，而那个文件本轮归车道 A
 * 独占（车道 B 只改得到 {@code PathologyReportController} 的 TECH_ORDER / TECH_DONE / TECH_CANCEL 三处）。
 * B 把它列进 blocked_notes 并在 {@link V62TechRemarkTest} 里<b>用字面量</b>留下了那三处当时的真实形态作对照组。
 *
 * <p><b>修复前的反向事实</b>（本类每条断言都对着一句真在库里出现过的正文）：
 * <ul>
 *   <li>SECTION：{@code 切片 2 张（P25-1-1，IHC CK7），特检医嘱#12 IHC}——染色类型与医嘱类型两处裸枚举；</li>
 *   <li>STAIN：{@code 染色 P25-1-1-1（HE），质量 GOOD}——染色类型与质量两处裸枚举；</li>
 *   <li>GROSSING：{@code 取材产出 2 块，补取材医嘱#12 RESAMPLE}——{@code RESAMPLE} 是逐字写死在
 *       Java 字符串里的字面量，连库值都不是。</li>
 * </ul>
 * 评委在流转轨迹正文里读到的是库枚举值，而同一个屏幕的隔壁列早就在显示中文。
 *
 * <p><b>本类与 {@link V62TechRemarkTest} 的分工</b>：那边钉车道 B 管得到的三个节点、对照组是历史形态的字面量；
 * 本类钉这三个节点，且<b>实查 {@code path_process.remark} 的落库正文</b>，不看代码也不看返回体——
 * 备注是写进库里给人查的，断言就该问库要。两条正则（{@link V62TechRemarkTest#RAW_ENUM} /
 * {@link V62TechRemarkTest#RAW_COLUMN}）直接复用那边的定义，不抄第二份。
 *
 * <p><b>第 ③ 条钉的是「中文名不是新起的一套」</b>：{@code STAIN_TYPE_NAMES} 是一份 Java map，
 * 而同一套染色类型中文在仓库里另有两份 SQL {@code case} 展开与一份前端字典。
 * 一份 Java map 抄一段 SQL CASE，不带断言就是第四份独立口径——改一处漂一处，这正是 v61 复核的总模式。
 *
 * <p>夹具照抄 {@link V62TechRemarkTest}（同一条演示路径）。事务回滚，库里不留痕。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V62NodeRemarkCodeTest {

    private static final String PROCESS_CONTROLLER =
            "modules/medtech/src/main/java/cn/hip/medtech/web/PathologyProcessController.java";
    private static final String REPORT_CONTROLLER =
            "modules/medtech/src/main/java/cn/hip/medtech/web/PathologyReportController.java";
    private static final String FORMAT_TS = "frontend/shell/src/views/medtech/pathology/format.ts";

    @Autowired PathologyProcessController process;
    @Autowired PathologyReportController report;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc1;
    private long a;
    private long blockA;

    @BeforeEach
    void setUp() {
        tag = "V62N" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");
        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "节点去码测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "节点去码患者" + tag);
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
                """, Long.class, orderId, "PB" + tag, tag + "-1", "节点去码标本" + tag);
        var gr = ok(process.grossing(new GrossingReq(a, null, null, "首次取材 " + tag, false, null,
                List.of(new BlockReq("肿物中心 " + tag))), doc1));
        blockA = idOf(rows(gr, "blocks").get(0));
    }

    // =====================================================================================
    // ① SECTION / STAIN：落库正文里既无裸英文枚举，也无库列名，且中文真的出现了
    // =====================================================================================

    @Test
    void sectionAndStainRemarksCarryChineseInsteadOfRawEnums() {
        long ihc = techOrder(a, blockA, "IHC", "CK7");
        var sec = ok(process.slides(new SlideReq(blockA, 2, "IHC", "CK7", null, ihc), doc1));
        long slideId = idOf(rows(sec, "slides").get(0));

        String section = latestRemark(a, "SECTION");
        assertClean(section, "SECTION");
        // 活的对照组之一：不是读到空串才「干净」的——三样中文都得真出现
        assertTrue(section.contains("免疫组化"),
                "SECTION 备注的染色类型走中文（修复前是裸 IHC）：" + section);
        assertTrue(section.contains("CK7"),
                "项目名是业务内容、原样保留，不能被一起译掉：" + section);
        assertTrue(section.contains("特检医嘱#" + ihc + " 免疫组化 CK7"),
                "医嘱标识走 techLabel（#id 保留 + 中文类型 + 项目；修复前是「#" + ihc + " IHC」）：" + section);

        ok(process.stain(slideId, new StainReq("GOOD", null, null), doc1));
        String stain = latestRemark(a, "STAIN");
        assertClean(stain, "STAIN");
        assertTrue(stain.contains("质量 优"),
                "STAIN 备注的质量走中文（修复前是「质量 GOOD」）：" + stain);
        assertTrue(stain.contains("免疫组化"),
                "STAIN 备注的染色类型同样走中文（修复前是裸 IHC）：" + stain);
    }

    // =====================================================================================
    // ② GROSSING：补取材医嘱那半句此前连库值都不是，是写死的字面量 RESAMPLE
    // =====================================================================================

    @Test
    void grossingRemarkNamesTheResampleOrderInChinese() {
        long rs = techOrder(a, null, "RESAMPLE", null);
        ok(process.grossing(new GrossingReq(a, null, null, null, true, null,
                List.of(new BlockReq("切缘一 " + tag), new BlockReq("切缘二 " + tag)), rs), doc1));

        String grossing = latestRemark(a, "GROSSING");
        assertClean(grossing, "GROSSING");
        assertTrue(grossing.contains("补取材医嘱#" + rs + " 补取材"),
                "走 techLabel：「补取材医嘱」是这半句的名头、「补取材」是医嘱类型的中文"
                        + "（修复前是写死的字面量「补取材医嘱#" + rs + " RESAMPLE」）：" + grossing);
        assertTrue(grossing.contains("取材产出 2 块"), "本次产出块数照旧如实写：" + grossing);

        // 活的对照组：首次取材那条（setUp 里建的）没有医嘱半句，说明上面那半句是因为挂了医嘱才出现的
        String first = jdbc.queryForObject("""
                select remark from path_process
                where specimen_id = ? and node = 'GROSSING' order by id asc limit 1
                """, String.class, a);
        assertNotNull(first, "对照组：首次取材的 GROSSING 节点必须在");
        assertClean(first, "GROSSING（首次）");
        assertFalse(first.contains("补取材医嘱"),
                "对照组：没挂医嘱的取材不该凭空长出医嘱半句：" + first);
    }

    // =====================================================================================
    // ③ 三条正则 / 四份字典的活对照组：扫描器是活的，中文名不是第四套口径
    // =====================================================================================

    @Test
    void theScannersAreLiveAndTheChineseNamesAreNotAFourthCaliber() {
        // --- (a) 正则是活的：修复前的真实形态必须被同一组正则抓到 ---
        assertTrue(V62TechRemarkTest.RAW_ENUM.matcher("切片 2 张（P-1，IHC CK7），特检医嘱#12 IHC").find(),
                "活对照组：SECTION 修复前形态抓不到，则第 ① 条是恒真的");
        assertTrue(V62TechRemarkTest.RAW_ENUM.matcher("染色 P-1-1（HE），质量 GOOD").find(),
                "活对照组：STAIN 修复前形态");
        assertTrue(V62TechRemarkTest.RAW_ENUM.matcher("取材产出 2 块，补取材医嘱#12 RESAMPLE").find(),
                "活对照组：GROSSING 修复前形态");
        // 反向：修好的形态不该被咬到，否则这条断言迟早被改宽到形同虚设
        assertFalse(V62TechRemarkTest.RAW_ENUM.matcher(
                        "切片 2 张（P-1，免疫组化 CK7），特检医嘱#12 免疫组化 CK7").find(),
                "活对照组：修好的形态不该被抓到（项目名 CK7 是业务内容）");
        assertFalse(V62TechRemarkTest.RAW_ENUM.matcher("染色 P-1-1（免疫组化 CK7），质量 优").find(),
                "活对照组：修好的形态不该被抓到");

        // --- (b) 四档染色类型：Java map / 两份 SQL case / 前端字典，逐档逐字相同 ---
        String reportSrc = read(REPORT_CONTROLLER);
        String formatTs = read(FORMAT_TS);
        // 活对照组：三份源文件真被读到了（空串上做 contains 全假、做 !contains 全真，两头都能假绿）
        assertTrue(read(PROCESS_CONTROLLER).contains("STAIN_TYPE_NAMES"),
                "活对照组：扫描器确实读到了 PathologyProcessController");
        assertTrue(reportSrc.contains("attached_stain_name"), "活对照组：确实读到了 PathologyReportController");
        assertTrue(formatTs.contains("export const STAIN_TYPES"), "活对照组：确实读到了 format.ts");

        assertEquals(PathologyProcessController.STAIN_TYPES.size(),
                PathologyProcessController.STAIN_TYPE_NAMES.size(),
                "白名单四档都得有中文名，一档不落");
        var fromTs = stainLabelsFromFormatTs(formatTs);
        assertEquals(4, fromTs.size(), "活对照组：format.ts 的 STAIN_TYPES 必须真解析出四档，实际 " + fromTs);
        for (String code : PathologyProcessController.STAIN_TYPES) {
            String zh = PathologyProcessController.STAIN_TYPE_NAMES.get(code);
            assertNotNull(zh, code + " 没有中文名");
            assertEquals(zh, fromTs.get(code),
                    code + "：Java 侧与前端 format.ts 的中文名必须逐字相同（两处并排显示，名字不同就是两套口径）");
            // 后端 SQL 的 stain_name 口径：case 里 'IHC' then '免疫组化' 这样的形态
            if (!"MOLECULAR".equals(code)) {   // MOLECULAR 在 SQL 里走 else 分支，没有 when 字面量
                assertTrue(reportSrc.contains("'" + code + "' then '" + zh + "'"),
                        code + "：Java map 必须与 attached_stain_name 的 SQL case 逐字相同，"
                                + "否则同一个染色类型在节点备注与质控列里是两个名字。期望 SQL 里有 '"
                                + code + "' then '" + zh + "'");
            }
        }
        assertTrue(reportSrc.contains("else '分子病理'"),
                "MOLECULAR 走 SQL 的 else 分支，中文同样得是「分子病理」");

        // --- (c) 三档质量：中文名与前端下拉标签「优（GOOD）」的前缀对得上 ---
        assertEquals(PathologyProcessController.SLIDE_QUALITIES.size(),
                PathologyProcessController.SLIDE_QUALITY_NAMES.size(), "三档质量都得有中文名");
        for (String code : PathologyProcessController.SLIDE_QUALITIES) {
            String zh = PathologyProcessController.SLIDE_QUALITY_NAMES.get(code);
            assertNotNull(zh, code + " 没有中文名");
            assertTrue(formatTs.contains("'" + code + "', label: '" + zh + "（" + code + "）'"),
                    code + "：节点正文用「" + zh + "」，前端下拉用「" + zh + "（" + code + "）」，"
                            + "两者必须是同一个中文打头（前端要能对回库值，正文是散文只留中文）");
        }
    }

    // ==================================================================================
    // 助手
    // ==================================================================================

    private void assertClean(String remark, String node) {
        assertNotNull(remark, node + " 节点备注为 null");
        assertFalse(remark.isBlank(), node + " 节点备注为空——空串上的「不含裸码」是恒真的");
        assertFalse(V62TechRemarkTest.RAW_ENUM.matcher(remark).find(),
                node + " 备注不得含裸英文枚举：" + remark);
        assertFalse(V62TechRemarkTest.RAW_COLUMN.matcher(remark).find(),
                node + " 备注不得含库列名 / 内部标识：" + remark);
    }

    /** 实查落库正文：备注是写进库里给人查的，断言就问库要，不看返回体 */
    private String latestRemark(long specimenId, String node) {
        var rs = jdbc.queryForList("""
                select remark from path_process
                where specimen_id = ? and node = ? order by id desc limit 1
                """, specimenId, node);
        if (rs.isEmpty()) fail(node + " 节点未落库（标本 " + specimenId + "）");
        return String.valueOf(rs.get(0).get("remark"));
    }

    /** 从 format.ts 的 {@code export const STAIN_TYPES = [...]} 里解析 value → label */
    private static Map<String, String> stainLabelsFromFormatTs(String src) {
        int at = src.indexOf("export const STAIN_TYPES");
        assertTrue(at >= 0, "format.ts 里找不到 STAIN_TYPES");
        int end = src.indexOf(']', at);
        assertTrue(end > at, "format.ts 的 STAIN_TYPES 没有闭合");
        var m = Pattern.compile("\\{\\s*value:\\s*'([A-Z_]+)',\\s*label:\\s*'([^']+)'\\s*\\}")
                .matcher(src.substring(at, end));
        var out = new java.util.LinkedHashMap<String, String>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
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

    private long techOrder(long specimenId, Long blockId, String type, String item) {
        var t = ok(report.createTechOrder(new TechOrderReq(specimenId, blockId, type, item, "原因 " + tag), doc1));
        return ((Number) t.get("id")).longValue();
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

    private static long idOf(Map<String, Object> row) {
        Object v = row.get("id");
        return v instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }
}
