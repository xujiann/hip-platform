package cn.hip.server;

import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossReviseReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.platform.core.common.R;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v62（2530 复核五条）：修订预填不再依赖 fieldsCurrent、口径真被消费、结构化退化守卫 5277。
 *
 * <p><b>修复前的反向事实</b>（v61 交付后复核，主控已逐条实测坐实）：
 * <ul>
 *   <li><b>修复把自己的预填拆了</b>：v61 把补取材追加场景的 {@code fieldsCurrent} 改判为 false（判定本身是对的），
 *       而 GrossingPanel 的「修订取材描述」恰好拿这个键当预填开关
 *       （{@code const fields = (d.fieldsCurrent === true ? (d.fields ?? []) : []) as Row[]}）——
 *       于是补取材追加后点修订，库里已落的字段<b>一项都不预填</b>，累积全文整段被塞进「自由描述」textarea；
 *       用户照单提交，新版 = 当前全文、结构化字段归零。</li>
 *   <li><b>后端对这条退化零防线</b>：{@code reviseGrossFields} 只要求「字段与自由描述至少填一项」，
 *       {@code gross=null} 照收，{@code storeGrossFields} 落 0 行——结构化记录静默洗成扁平文本。</li>
 *   <li><b>说假话的标签</b>：只写了自由描述、没有结构化字段的标本（零种子库里最普通的一种）
 *       在查看弹窗打出「被取代版本（第 1 版，0 项）：已被第 <b>—</b> 版字段取代，仅供调阅」——
 *       它没被任何版本取代（就是当前版、唯一版），「第 —」也不是版本号而是 {@code fmt(null)}。</li>
 *   <li><b>说真话的提示与说假话的标签互斥出现</b>：{@code textIsCumulative} 全仓 9 处命中<b>前端零消费</b>，
 *       而诚实的 {@code fieldsNote} 警示条 {@code v-if} 要求「是最新字段版」，切到第 1 版即消失。</li>
 * </ul>
 *
 * <p><b>本类钉住</b>：(a) 追加后读端点仍给出最新一版字段行、按前端同一套规则能原样预填且不丢更早各版内容
 * （并把「按 fieldsCurrent 取字段」的旧写法当场算成空，钉住反向事实）；(b) 5277 三档 gate 行为与坏配置回落；
 * (c) 源码扫描（剥注释、带活对照组）：预填不再读 fieldsCurrent、前端真消费 textIsCumulative、
 * 「被取代版本」只在 superseded 为真时出现；(d) 纯自由文本标本读端点里那套假文案所依赖的组合，后端如实给空。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V62GrossReviseGuardTest {

    @Autowired PathologyProcessController process;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private static final String PANEL = "frontend/shell/src/views/medtech/pathology/GrossingPanel.vue";
    private static final String TRAIL = "frontend/shell/src/views/medtech/pathology/TrailPanel.vue";
    private static final String V168 = "server/src/main/resources/db/migration/V168__grossfield_gate_seed.sql";

    private String tag;
    private Authentication doc;
    private long specimenId;     // 主标本：走字段 → 追加 → 修订
    private long freeTextId;     // 纯自由文本标本：一行字段都没有
    private int gatePinned;

    @BeforeEach
    void setUp() {
        // 档位先钉到出厂值：本类要逐档改它，别让别的用例留下的值决定本类的起点
        gatePinned = setGate("warn");

        tag = "V62G" + Long.toHexString(System.nanoTime());
        doc = doctorAuth(tag + "d1");
        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "取材修订守卫测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "取材修订患者" + tag);
        Long regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag);
        specimenId = newSpecimen(orderId, 1);
        freeTextId = newSpecimen(orderId, 2);
    }

    // =====================================================================================
    // ① 补取材追加后：库里已落的字段必须能原样预填——预填源与 fieldsCurrent 无关
    // =====================================================================================

    @Test
    void appendedFieldsArePrefillableRegardlessOfFieldsCurrent() {
        var first = new LinkedHashMap<String, String>();
        first.put("标本大小", "5×4×3cm");
        first.put("切面", "灰白");
        ok(process.grossing(new GrossingReq(specimenId, null, first, "首次 " + tag, false, null,
                List.of(new BlockReq("首块 " + tag))), doc));
        var second = new LinkedHashMap<String, String>();
        second.put("补取块数", "2 块");
        second.put("最大径", "0.8cm");
        ok(process.grossing(new GrossingReq(specimenId, null, second, "补取材 " + tag, true, null,
                List.of(new BlockReq("补块 " + tag))), doc));

        var v = ok(process.grossingView(specimenId));
        String text = String.valueOf(v.get("grossFinding"));
        // v61 的判定不动（它是对的）：追加后字段只覆盖最后一次，覆盖不全累积全文
        assertEquals(Boolean.FALSE, v.get("fieldsCurrent"), "追加后字段覆盖不全当前文本");
        assertEquals(Boolean.TRUE, v.get("textIsCumulative"));
        // 而读端点照常给出「最新一版有字段行的那一版」——预填要的正是它
        var fields = rows(v, "fields");
        assertEquals(List.of("补取块数", "最大径"), labelsOf(fields),
                "fields 的口径是 max(revision_seq) from path_gross_field，与 fieldsCurrent 无关");

        // ---- 修复前的写法：按 fieldsCurrent 决定取不取 fields ----
        var broken = prefill(text, Boolean.TRUE.equals(v.get("fieldsCurrent")) ? fields : List.of());
        assertTrue(broken.gross().isEmpty(),
                "**修复前的反向事实**：fieldsCurrent=false → fields 取空 → 库里已落的字段一项都不预填");
        assertEquals("EXACT", broken.mode());
        assertEquals(text, broken.free(), "**修复前的反向事实**：累积全文整段被塞进自由描述");

        // ---- 修复后的写法：预填源就是 fields 本身 ----
        var fixed = prefill(text, fields);
        assertEquals("CUMULATIVE", fixed.mode(), "累积全文按「。补取材：」切得开");
        assertEquals(List.of("补取块数", "最大径"), new ArrayList<>(fixed.gross().keySet()), "本版字段原样预填");
        assertEquals("2 块", fixed.gross().get("补取块数"));
        assertEquals("0.8cm", fixed.gross().get("最大径"));
        assertTrue(fixed.free().contains("5×4×3cm") && fixed.free().contains("首次 " + tag),
                "更早各版的内容仍留在自由描述里，一个字不丢：" + fixed.free());
        assertFalse(fixed.free().contains("补取块数：2 块"),
                "本版字段不再重复留在自由描述里，否则提交后拼出重复内容：" + fixed.free());

        // 照这份预填原样提交：字段一项不少地落进新版本（修复前这里落 0 项）
        var r = ok(process.reviseGrossFields(specimenId,
                new GrossReviseReq(null, new LinkedHashMap<>(fixed.gross()), fixed.free()), doc));
        assertEquals(2, ((Number) r.get("grossFieldCount")).intValue(), "预填原样提交 → 结构化字段不丢");
        assertEquals(List.of(), warningsOf(r), "没有退化就没有告警");
        var v2 = ok(process.grossingView(specimenId));
        assertEquals(List.of("补取块数", "最大径"), labelsOf(rows(v2, "fields")));
        assertEquals(Boolean.TRUE, v2.get("fieldsCurrent"), "修订后字段版 = 文本版，且文本不再是累积拼装");
        assertEquals(Boolean.FALSE, v2.get("textIsCumulative"));
    }

    // =====================================================================================
    // ② 5277 三档 gate：block 零写入 / warn 落库带 warnings / off 不判；坏配置回落 warn
    // =====================================================================================

    @Test
    void degradingReviseIsGovernedByTheGrossFieldGate() {
        var g = new LinkedHashMap<String, String>();
        g.put("大小", "3cm");
        g.put("切面", "灰白");
        ok(process.grossing(new GrossingReq(specimenId, null, g, "首次 " + tag, false, null,
                List.of(new BlockReq("块 " + tag))), doc));
        assertEquals(2, fieldRows(specimenId), "前置：库里已有 2 行字段");

        // ---- block：返 5277 且**零写入** ----
        assertEquals(1, setGate("block"), "sys_config 里必须有这一行可改（V168 种子）");
        String snap = snapshot(specimenId);
        var blocked = process.reviseGrossFields(specimenId,
                new GrossReviseReq(null, null, "只写自由描述 block " + tag), doc);
        assertEquals(5277, blocked.getCode(), "block 档应返 5277，实际 " + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("=block")
                        && blocked.getMessage().contains("结构化记录全部丢失"),
                "5277 消息要说清是哪个 gate 拦的、拦的是什么：" + blocked.getMessage());
        assertEquals(snap, snapshot(specimenId), "block 档一个字都不许写（R.fail 不是异常，@Transactional 不回滚）");

        // block 档但本次仍带字段 → 不算退化，照常放行、无告警
        var keep = new LinkedHashMap<String, String>();
        keep.put("大小", "3.5cm");
        var kept = ok(process.reviseGrossFields(specimenId,
                new GrossReviseReq(null, keep, "仍有字段 " + tag), doc));
        assertEquals(1, ((Number) kept.get("grossFieldCount")).intValue());
        assertEquals(List.of(), warningsOf(kept), "不退化就没有告警");

        // ---- warn：照常落库，返回体带 warnings ----
        assertEquals(1, setGate("warn"));
        String warnText = "只写自由描述 warn " + tag;
        var warned = ok(process.reviseGrossFields(specimenId, new GrossReviseReq(null, null, warnText), doc));
        assertEquals(0, ((Number) warned.get("grossFieldCount")).intValue());
        var ws = warningsOf(warned);
        assertEquals(1, ws.size(), "warn 且退化应恰一条告警：" + ws);
        assertTrue(ws.get(0).contains("不再有结构化字段") && ws.get(0).contains("gate=warn 放行"),
                "告警要说清后果与放行原因：" + ws.get(0));
        assertEquals(warnText, grossFinding(specimenId), "warn 必须**真落库**，不是只喊一声");
        int warnSeq = ((Number) warned.get("revisionSeq")).intValue();
        assertEquals(0, fieldRowsOfRevision(specimenId, warnSeq), "本版 0 行字段行");
        assertEquals(3, fieldRows(specimenId), "更早各版的字段行不动（2 + 1）");

        // ---- off：不判，warnings 是空数组而不是缺键 ----
        assertEquals(1, setGate("off"));
        String offText = "只写自由描述 off " + tag;
        var offR = ok(process.reviseGrossFields(specimenId, new GrossReviseReq(null, null, offText), doc));
        assertEquals(List.of(), warningsOf(offR), "off 不判；warnings 键仍在，调用方不必判键在不在");
        assertEquals(offText, grossFinding(specimenId));

        // ---- 坏配置回落 warn 而非 off ----
        assertEquals(1, setGate("blocked"));
        String badText = "只写自由描述 badcfg " + tag;
        var badR = ok(process.reviseGrossFields(specimenId, new GrossReviseReq(null, null, badText), doc));
        assertEquals(1, warningsOf(badR).size(),
                "坏配置回落 warn 而非 off：把笔误当成静默关闭校验是更坏的默认");
        assertEquals(badText, grossFinding(specimenId));

        // ---- 修订前本就没有字段行 → 任何档位都不判（存量标本不会被这条守卫拦死）----
        assertEquals(1, setGate("block"));
        ok(process.grossing(new GrossingReq(freeTextId, null, null, "纯自由文本 " + tag, false, null,
                List.of(new BlockReq("块F " + tag))), doc));
        assertEquals(0, fieldRows(freeTextId), "前置：这个标本一行字段都没有");
        var freeR = ok(process.reviseGrossFields(freeTextId,
                new GrossReviseReq(null, null, "改过的纯自由文本 " + tag), doc));
        assertEquals(List.of(), warningsOf(freeR), "守卫看的是「修订前有、修订后没有」，本来就没有不算退化");
    }

    // =====================================================================================
    // ③ V168 种子：键登记进 sys_config、出厂 warn、remark ≤255、on conflict do nothing、零 update
    // =====================================================================================

    @Test
    void gateKeyIsSeededAndWired() throws IOException {
        assertEquals(1, gatePinned, "setUp 把 " + PathologyProcessController.GROSS_FIELD_GATE_KEY
                + " 钉到 warn 的 update 必须影响 1 行——影响 0 行就是「建了开关没接电」");
        Integer remarkLen = jdbc.queryForObject("select length(remark) from sys_config where cfg_key = ?",
                Integer.class, PathologyProcessController.GROSS_FIELD_GATE_KEY);
        assertNotNull(remarkLen, "sys_config 里必须有这一行");
        assertTrue(remarkLen > 0 && remarkLen <= 255, "remark 须非空且 ≤ 255，实际 " + remarkLen);

        String sql = stripSqlComments(read(V168));
        assertEquals("emr.gate.pathology.grossfield", PathologyProcessController.GROSS_FIELD_GATE_KEY,
                "代码里读的键与 V168 seed 的键必须逐字相同");
        assertTrue(sql.contains("'emr.gate.pathology.grossfield', 'warn'"), "出厂值必须是 warn：" + sql);
        assertTrue(sql.contains("on conflict (cfg_key) do nothing"), "seed 形态照抄 V161");
        assertFalse(sql.matches("(?s).*(?m)^\\s*update\\s+\\w+\\s+set\\b.*"),
                "V168 只做种子，零回填：剥注释后不得有顶层 update <表> set");
        // 活的对照组：同一个匹配器在 V161 / V22 上抓得到，证明它不是恒假
        assertTrue(stripSqlComments(read("server/src/main/resources/db/migration/V161__timeliness_gate_seed.sql"))
                        .contains("on conflict (cfg_key) do nothing"),
                "对照组：扫描器确实读到了真文件（V161 同形态）");
        assertTrue(stripSqlComments(read("server/src/main/resources/db/migration/V22__phase25_mgmt.sql"))
                        .matches("(?s).*(?m)^\\s*update\\s+\\w+\\s+set\\b.*"),
                "活对照组：顶层 update 的匹配器在 V22 上抓得到，V168 的「零 update」才不是空表恒真");
    }

    // =====================================================================================
    // ④ 源码扫描（剥注释、按语法形态、带活对照组）：预填不再读 fieldsCurrent、前端真消费 textIsCumulative
    // =====================================================================================

    @Test
    void grossingPanelPrefillsFromFieldsAndConsumesTextIsCumulative() throws IOException {
        String raw = read(PANEL);
        String panel = stripComments(raw);

        // --- 活对照组：剥注释器真的在剥，且真的读到了这份文件 ---
        String commentOnly = "本轮头号纪律";
        assertTrue(raw.contains(commentOnly), "对照组：这句话只写在注释里，原文必须有");
        assertFalse(panel.contains(commentOnly), "活对照组：剥注释器若失灵，下面所有「剥注释后没有 X」的断言都成空话");
        assertTrue(panel.contains("splitGross") && panel.contains("openRevise"),
                "活对照组：扫描器确实读到了修订表单的真代码");

        // --- 预填不再读 fieldsCurrent ---
        assertTrue(panel.contains("const fields = (d.fields ?? []) as Row[]"),
                "修订预填必须直接取 fields（库里最新一版有字段行的那一版）");
        assertFalse(panel.contains("d.fieldsCurrent === true"),
                "**修复前的反向事实**：预填开关写作 (d.fieldsCurrent === true ? (d.fields ?? []) : [])，"
                        + "v61 把追加场景改判为 false 后，预填当场落空");
        // fieldsCurrent 本身仍是正当的展示口径——断言不是「全文删干净」，而是「不再当预填开关」
        assertTrue(panel.contains("fieldsCurrent"), "活对照组：fieldsCurrent 仍被展示层消费，不是被整片删掉才变绿的");

        // --- textIsCumulative 真被消费（此前全仓 9 处命中、前端零消费）---
        assertTrue(panel.contains("view.value.textIsCumulative === true"),
                "查看弹窗要按 textIsCumulative 分档措辞，不自己推断");
        assertTrue(panel.contains("d.textIsCumulative === true"),
                "修订弹窗的提示条要按 textIsCumulative 分档措辞");
        assertTrue(stripComments(read(TRAIL)).contains("gross.value.textIsCumulative === true"),
                "轨迹抽屉的兜底措辞同样消费它——写死「文本已在第 N 版修订」在追加场景是假话");

        // --- 「被取代版本（第 N 版…）」标签只在 superseded 为真时出现 ---
        // 只钉标签本身的形态（带「（第 」），不钉裸词——同屏另有一句
        //「后端未回 fieldsByRevision：…被取代版本暂不可调阅」是另一回事，不该被这条断言拖下水
        String labelForm = "被取代版本（第 ";
        int gate = panel.indexOf("const superseded = ");
        int label = panel.indexOf(labelForm);
        assertTrue(gate > 0, "必须有一处显式的「真被取代」判定");
        assertTrue(label > gate, "该标签必须出现在该判定之后：gate=" + gate + " label=" + label);
        assertTrue(panel.substring(gate, label).contains("if (superseded)"),
                "该文案必须被 superseded 守着，而不是 else 分支兜底");
        assertEquals(1, count(panel, labelForm), "这句文案全文件只该有一处");
        // **修复前的反向事实**：版号直接从 view 上取（fieldsRevisionSeq 为 null 时 fmt(null) 打出「第 —」）。
        // 现在这句只在 latestSeq != null 的分支里，版号取自那个已判非空的局部量。
        assertFalse(panel.contains("已被第 ${fmt(view.fieldsRevisionSeq)} 版字段取代"),
                "「已被第 N 版取代」不得再直接读 view.fieldsRevisionSeq——纯自由文本标本那里恒为 null");
        assertTrue(panel.contains("已被第 ${fmt(latestSeq)} 版字段取代"), "活对照组：这句文案确实还在，只是换了守卫与取值");
        // 0 项的诚实兜底要排在「被取代」分支之前，否则纯自由文本标本（0 项）又会掉进去
        int zero = panel.indexOf("if (n === 0) return");
        int supersededBranch = panel.indexOf("if (superseded)");
        assertTrue(zero > 0 && supersededBranch > 0 && zero < supersededBranch,
                "0 项兜底必须先判：zero=" + zero + " supersededBranch=" + supersededBranch);
        assertTrue(panel.contains("viewNoFieldsNote"), "0 项兜底文案要区分「整份标本没有字段」与「这一版没填」");
    }

    // =====================================================================================
    // ⑤ 纯自由文本标本：后端如实给空——那套假文案所依赖的组合，后端这一侧一个都不提供
    // =====================================================================================

    @Test
    void pureFreeTextSpecimenHasNoFieldVersionToBeSupersededBy() {
        ok(process.grossing(new GrossingReq(freeTextId, null, null, "纯自由文本 " + tag, false, null,
                List.of(new BlockReq("块F " + tag))), doc));

        var v = ok(process.grossingView(freeTextId));
        assertEquals(Boolean.TRUE, v.get("grossFindingPresent"));
        assertEquals(Boolean.FALSE, v.get("fieldsAvailable"), "不从文本反解析字段");
        assertEquals(List.of(), rows(v, "fields"));
        assertNull(v.get("fieldsRevisionSeq"), "没有任何字段行 → 没有「第 N 版字段」可言（前端 fmt(null) 打出的「第 —」正源于此）");
        assertEquals(1, ((Number) v.get("textRevisionSeq")).intValue(), "文本只有第 1 版");
        assertEquals(Boolean.FALSE, v.get("fieldsCurrent"));
        assertEquals(Boolean.FALSE, v.get("textIsCumulative"));
        assertNull(v.get("fieldsNote"), "无字段行时没有「字段与文本分叉」可说，后端不编一句");

        // fieldsByRevision 确有一条（第 1 版、fields=[]）——正是这条让前端此前误入「被取代版本」分支
        var byRev = rows(v, "fieldsByRevision");
        assertEquals(1, byRev.size(), "唯一版");
        assertEquals(1, ((Number) byRev.get(0).get("revisionSeq")).intValue());
        assertEquals(List.of(), rows(byRev.get(0), "fields"), "这一版一行字段都没有");
        assertNotNull(byRev.get(0).get("changedByName"), "版本头带录入人——被取代版本的「录入」列由它回填，不再恒「— —」");
        assertNotNull(byRev.get(0).get("changedAt"), "版本头带时刻，同上");
    }

    // ==================================================================================
    // 助手
    // ==================================================================================

    /**
     * GrossingPanel.splitGross 的 Java 同构版——<b>逐条对应</b>那段 TS：
     * 字段按「标签：值」以「；」相连；整段相等 / 前缀加「。」/ 累积全文（按「。补取材：」切开）/ 拆不开。
     * 前端改了这套规则而这里没跟着改，本类第 ① 条会先红——这正是它存在的意义。
     */
    private record Prefill(Map<String, String> gross, String free, String mode) {}

    private static final String APPEND_MARK = "。补取材：";

    private static Prefill prefill(String text, List<Map<String, Object>> fields) {
        var gross = new LinkedHashMap<String, String>();
        for (var f : fields) gross.put(String.valueOf(f.get("label")), String.valueOf(f.get("value")));
        if (fields.isEmpty()) return new Prefill(gross, text, "EXACT");
        var parts = new ArrayList<String>();
        for (var f : fields) parts.add(f.get("label") + "：" + f.get("value"));
        String prefix = String.join("；", parts);
        if (text.equals(prefix)) return new Prefill(gross, "", "EXACT");
        if (text.startsWith(prefix + "。")) return new Prefill(gross, text.substring(prefix.length() + 1), "PREFIX");
        int at = text.lastIndexOf(APPEND_MARK);
        if (at >= 0) {
            String older = text.substring(0, at);
            String seg = text.substring(at + APPEND_MARK.length());
            String free = seg.equals(prefix) ? ""
                    : seg.startsWith(prefix + "。") ? seg.substring(prefix.length() + 1) : null;
            if (free != null) {
                return new Prefill(gross, free.isEmpty() ? older : older + APPEND_MARK + free, "CUMULATIVE");
            }
        }
        return new Prefill(new LinkedHashMap<>(), text, "UNPARSED");
    }

    private long newSpecimen(Long orderId, int partNo) {
        Long id = jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent)
                values (?, ?, ?, ?, 'ROUTINE', ?, 'RECEIVED',
                        now() - interval '2 days' - interval '1 hour', now() - interval '2 days', false)
                returning id
                """, Long.class, orderId, partNo, "PB" + tag + partNo, tag + "-" + partNo, "守卫标本" + tag + partNo);
        assertNotNull(id);
        return id;
    }

    /** 被拒路径前后必须逐字相等的四张表快照 */
    private String snapshot(long id) {
        return jdbc.queryForObject("""
                select coalesce(s.gross_finding, '') || '|'
                     || (select count(*) from path_gross_field f where f.specimen_id = s.id) || '|'
                     || (select count(*) from path_gross_revision r where r.specimen_id = s.id) || '|'
                     || (select count(*) from path_block b where b.specimen_id = s.id) || '|'
                     || (select count(*) from path_process p where p.specimen_id = s.id and p.node = 'GROSSING')
                from path_specimen s where s.id = ?
                """, String.class, id);
    }

    private int fieldRows(long id) {
        Integer n = jdbc.queryForObject("select count(*) from path_gross_field where specimen_id = ?", Integer.class, id);
        return n == null ? -1 : n;
    }

    private int fieldRowsOfRevision(long id, int seq) {
        Integer n = jdbc.queryForObject(
                "select count(*) from path_gross_field where specimen_id = ? and revision_seq = ?", Integer.class, id, seq);
        return n == null ? -1 : n;
    }

    private String grossFinding(long id) {
        return jdbc.queryForObject("select gross_finding from path_specimen where id = ?", String.class, id);
    }

    private int setGate(String value) {
        configReader.evictAll();
        int n = jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?",
                value, PathologyProcessController.GROSS_FIELD_GATE_KEY);
        configReader.evictAll();
        return n;
    }

    @SuppressWarnings("unchecked")
    private static List<String> warningsOf(Map<String, Object> body) {
        Object w = body.get("warnings");
        assertNotNull(w, "warnings 必须恒在（off / 无退化时为空数组）：" + body.keySet());
        return (List<String>) w;
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

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /** 仓库相对路径（绝对路径在 worktree 下扫空恒绿，是本仓写进 CLAUDE.md 的教训） */
    private static String read(String rel) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !(Files.isDirectory(root.resolve("frontend")) && Files.isDirectory(root.resolve("modules")))) {
            root = root.getParent();
        }
        assertNotNull(root, "找不到仓库根");
        return Files.readString(root.resolve(rel));
    }

    /** 剥 HTML / 块 / 行注释——注释里的形态不算数（本仓多次源码扫描误判的教训） */
    private static String stripComments(String s) {
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("(?m)^\\s*//[^\\n]*", "");
        return s;
    }

    /** 剥 SQL 行注释与块注释 */
    private static String stripSqlComments(String s) {
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("(?m)^\\s*--[^\\n]*", "");
        return s;
    }
}
