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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
 * <p><b>v63（2530 复核）补的那一半</b>：复核者走完最普通的一条路
 * 「取材登记 → 补取材 → 修订取材描述（照预填原样提交）」后，第 1 版那几项被 v62 自己的新预填
 * <b>原样搬进「自由描述」</b>，新版只落第 2 版那两项字段行；而 5277 只认「本次落 0 行」
 * （{@code plannedFieldCount == 0}），这种「4 项变 2 项」的部分退化<b>既不 block 也不 warn</b>；
 * 提交之后读端点判 {@code fieldsCurrent=true}、{@code fieldsNote=null}，屏上打绿色
 * 「最新字段版（第 3 版，2 项），与当前文本同版」——当前这段描述里的 4 个「标签：值」
 * 只有 2 个有结构化记录，屏上没有一处说得出来。本轮补：第 ① 条末尾那条
 * {@code assertEquals(Boolean.TRUE, v2.get("fieldsCurrent"))} <b>正是钉死这个缺陷的断言，已翻过来</b>；
 * 另加第 ②b（部分退化三档）与第 ②c（N/M 事实的正反用例）。
 *
 * <h2>v65（2530 复核三条）</h2>
 * <ul>
 *   <li><b>写端点那两条告警此前在说假话</b>：部分退化告警逐字写着「少掉的那几项此后只留在大体所见文本里，
 *       字段级查询与统计取不到」、block 档的 5277 写「结构化记录全部丢失」——而 {@code path_gross_field}
 *       全仓只有 insert（本类第 ⑥ 条扫描坐实），那几行仍以旧 {@code revision_seq} 躺在库里、
 *       由同一批读端点的 {@code fieldsByRevision} 原样回出。仓库自己判过这句话是假的，
 *       但那条 {@code assertFalse} 只钉读端点的 {@code fieldsNote}，写端点这条同义告警原样留着
 *       （v62「只修被点名的那一个入口」重演）——本轮把同一条禁令扩到写端点。</li>
 *   <li><b>同一段两条兄弟告警两套基准</b>：全丢那档打全部版本累计行数、部分退化那档打最新一版行数，
 *       同一标本这两个数会是 6 与 2。现在整段只有一个基准（修订前最新一版），并点名是哪几项。</li>
 *   <li><b>英文模板码上屏的第四个入口</b>：取材端点把 {@code trimUpper} 后的模板码拼进
 *       {@code path_process.remark}，在「取材打点」表与⑥流转时间线上各印一次（v64 清了另外三处）。</li>
 *   <li><b>UNPARSED 档下覆盖提示与预填结果互相否定</b>：v64 把「已预填在下面的字段栏里」写死在
 *       {@code reviseCoverNote} 里，而 UNPARSED 档返回 <code>gross: {}</code> 一项都不预填。
 *       第 ②g 条在真库态下走出这一格，并拿修复前的形态当活对照组。</li>
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

    /** v64 写死在 reviseCoverNote 里的那半句——UNPARSED 档下它逐字为假 */
    private static final String V64_PREFILL_CLAIM = "已预填在下面的字段栏里";
    /** 修订弹窗「这次提交会发生什么」0 项那一档的开头，与 GrossingPanel 逐字相同（②f 钉着） */
    private static final String SUBMIT_NOTE_ZERO_HEAD =
            "下面的字段栏此刻一项都没有填：本次提交不带任何字段，落库后这一版不会有字段级记录，";
    /** path_gross_field 上的改写形态：只该有 insert，有 update / delete 就说明「旧版仍可调阅」不再成立 */
    private static final Pattern FIELD_ROW_WRITE = Pattern.compile(
            "(?is)(?:\\bupdate\\s+path_gross_field\\b|\\bdelete\\s+from\\s+path_gross_field\\b)");

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
        assertEquals(Boolean.FALSE, v2.get("textIsCumulative"), "新版文本是一次拼装的，不再带「。补取材：」");

        // v63（2530 复核）：**这条断言原来写的是 assertEquals(Boolean.TRUE, v2.get("fieldsCurrent"),
        // "修订后字段版 = 文本版，且文本不再是累积拼装")——它钉死的正是本轮被打回的那个缺陷。**
        // 原来那条为什么是错的：它把「版号相等 + 不是累积形态」直接当成「字段覆盖了当前文本」，
        // 而这条路径走完，新版文本里有 4 个「标签：值」（第 1 版那两项被预填搬进了自由描述），
        // 结构化字段行却只有 2 项。屏上据此打绿色「与当前文本同版」，等于对评委说了假话。
        // fieldsCurrent 现在是「版号相同」与「字段覆盖全文」两维之与，这条路上必须是 false。
        assertEquals(Boolean.FALSE, v2.get("fieldsCurrent"),
                "版号相等、文本也不是累积形态，但 4 个「标签：值」只有 2 个有字段行");
        assertEquals(4, ((Number) v2.get("textFieldForms")).intValue(),
                "当前文本里 4 个「标签：值」形态：" + v2.get("grossFinding"));
        assertEquals(2, ((Number) v2.get("textFieldFormsBacked")).intValue(), "其中只有 2 个有结构化字段行");
        assertEquals(Boolean.FALSE, v2.get("fieldsCoverText"));
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
                        && blocked.getMessage().contains("结构化记录停在第 1 版"),
                "5277 消息要说清是哪个 gate 拦的、拦的是什么：" + blocked.getMessage());
        // v65（2530 复核）：**修复前这里写的是「结构化记录全部丢失」**——与库内事实相反。
        // path_gross_field 只有 insert（第 ⑥ 条扫描坐实），第 1 版那两行一条不删，按版本照样调阅得到。
        assertFalse(blocked.getMessage().contains("结构化记录全部丢失"),
                "**修复前的反向事实**：拦得住的是「这一版没有结构化记录」，不是「记录没了」：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("按版本仍调阅得到"),
                "5277 要把「旧版字段行还在」一并说清（否则技师以为一提交就毁数据）：" + blocked.getMessage());
        assertEquals(snap, snapshot(specimenId), "block 档一个字都不许写（R.fail 不是异常，@Transactional 不回滚）");

        // v63（2530 复核）：**这里原来是 assertEquals(List.of(), warningsOf(kept), "不退化就没有告警")。**
        // 原来那条为什么是错的：这一次修订把字段从 2 项砍到 1 项，它<b>正是</b>被打回的那种部分退化，
        // 而当时的判定只认「落 0 行」，于是仓库自己有一条断言要求「2 项变 1 项必须一声不吭」。
        // 现在 block 档也不拦它（部分删字段有正当场景，理由见端点注释），但必须喊一声。
        assertEquals(1, setGate("block"), "仍在 block 档");
        var keep = new LinkedHashMap<String, String>();
        keep.put("大小", "3.5cm");
        var kept = ok(process.reviseGrossFields(specimenId,
                new GrossReviseReq(null, keep, "仍有字段 " + tag), doc));
        assertEquals(1, ((Number) kept.get("grossFieldCount")).intValue());
        var partial = warningsOf(kept);
        assertEquals(1, partial.size(), "**修复前这里是空数组**：2 项变 1 项既不 block 也不 warn：" + partial);
        // v64：档名改中文（配置值不上屏），但「说清是哪一档」这条要求不变
        assertTrue(partial.get(0).contains("由 2 项减至 1 项") && partial.get(0).contains("「拦截」档"),
                "部分退化在拦截档也只告警不拦截，且要把两个数与档位说出来：" + partial.get(0));
        assertFalse(partial.get(0).contains("gate="), "上屏告警不得含裸配置值：" + partial.get(0));
        // v65（2530 复核）：**这条同义告警此前原样留着那句假话**（v62 的 assertFalse 只钉读端点的 fieldsNote）
        assertNoDeadFieldClaim(partial.get(0));
        assertTrue(partial.get(0).contains("不再写入的是「切面」"),
                "少掉的是**哪一项**要点名——读的人正要拿这个名字去版本下拉里调阅它：" + partial.get(0));
        assertTrue(partial.get(0).contains("在第 1 版的字段行仍在库里"),
                "要说清它还在哪一版（这是「调阅得到」的落点）：" + partial.get(0));
        // 库内对照组：「仍在库里」不是换了个说法，是真的还在
        assertEquals(1L, (long) jdbc.queryForObject("""
                select count(*) from path_gross_field
                where specimen_id = ? and revision_seq = 1 and label = '切面'
                """, Long.class, specimenId), "被删的那一项仍以第 1 版的字段行躺在库里");
        // 读端点对照组：**「按版本调阅得到」得真调得到**，不是只在库里躺着
        var afterPartial = ok(process.grossingView(specimenId));
        var v1Fields = rows(afterPartial, "fieldsByRevision").stream()
                .filter(r -> ((Number) r.get("revisionSeq")).intValue() == 1)
                .flatMap(r -> rows(r, "fields").stream())
                .map(f -> String.valueOf(f.get("label"))).toList();
        assertEquals(List.of("大小", "切面"), v1Fields,
                "第 1 版两项由同一个读端点原样回出——屏上那句「按版本调阅得到」指的就是这里：" + afterPartial.get("fieldsByRevision"));

        // 活的对照组：项数不减就是真的不退化，一声不吭——否则上面那条成了「只要带字段就告警」
        var same = new LinkedHashMap<String, String>();
        same.put("大小", "3.6cm");
        var unchanged = ok(process.reviseGrossFields(specimenId,
                new GrossReviseReq(null, same, "只改了值 " + tag), doc));
        assertEquals(List.of(), warningsOf(unchanged), "活对照组：修订前最新一版 1 项、本次 1 项，不减就不告警");

        // ---- warn：照常落库，返回体带 warnings ----
        assertEquals(1, setGate("warn"));
        String warnText = "只写自由描述 warn " + tag;
        var warned = ok(process.reviseGrossFields(specimenId, new GrossReviseReq(null, null, warnText), doc));
        assertEquals(0, ((Number) warned.get("grossFieldCount")).intValue());
        var ws = warningsOf(warned);
        assertEquals(1, ws.size(), "warn 且退化应恰一条告警：" + ws);
        assertTrue(ws.get(0).contains("不再写入任何结构化字段") && ws.get(0).contains("「提示」档放行"),
                "告警要说清后果与放行原因：" + ws.get(0));
        // v65：基准统一成「修订前最新一版」——**修复前这一档打的是全部版本累计行数**（此刻是 3 行：2 + 1），
        // 而隔壁部分退化那档打的是最新一版行数，同一段守卫两套基准，读的人没法把两句话对起来
        assertTrue(ws.get(0).contains("修订前最新一版是第 3 版、1 项，本次 0 项"),
                "**修复前的反向事实**：这里打的是全部版本累计行数（此刻 3 行），不是最新一版的 1 项：" + ws.get(0));
        assertNoDeadFieldClaim(ws.get(0));
        assertEquals(warnText, grossFinding(specimenId), "warn 必须**真落库**，不是只喊一声");
        int warnSeq = ((Number) warned.get("revisionSeq")).intValue();
        assertEquals(0, fieldRowsOfRevision(specimenId, warnSeq), "本版 0 行字段行");
        assertEquals(4, fieldRows(specimenId), "更早各版的字段行不动（2 + 1 + 1）");

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
    // ②b v63（2530 复核）：**部分**退化——「4 项变 2 项」此前既不 block 也不 warn
    // =====================================================================================

    @Test
    void partialFieldLossWarnsOnEveryGateButNeverBlocks() {
        var four = new LinkedHashMap<String, String>();
        four.put("标本大小", "5×4×3cm");
        four.put("切面", "灰白");
        four.put("边界", "清");
        four.put("取材块数", "4");
        ok(process.grossing(new GrossingReq(specimenId, null, four, "首次 " + tag, false, null,
                List.of(new BlockReq("块 " + tag))), doc));
        assertEquals(4, fieldRows(specimenId), "前置：最新一版 4 行字段");

        // ---- block 档：部分退化**不拦**（不占预分配的 5278，理由见端点注释）----
        assertEquals(1, setGate("block"));
        var two = new LinkedHashMap<String, String>();
        two.put("标本大小", "5×4×3cm");
        two.put("切面", "灰白");
        var r = process.reviseGrossFields(specimenId, new GrossReviseReq(null, two, "只留两项 " + tag), doc);
        assertEquals(0, r.getCode(),
                "部分删字段有正当场景、gate 又是运维配置（录入者当场改不了），block 路径没有真实用途：" + r.getMessage());
        var body = r.getData();
        assertEquals(2, ((Number) body.get("grossFieldCount")).intValue());
        var ws = warningsOf(body);
        assertEquals(1, ws.size(),
                "**修复前的反向事实**：4 项变 2 项时 warnings 是空数组——5277 只认 plannedFieldCount == 0：" + ws);
        assertTrue(ws.get(0).contains("由 4 项减至 2 项") && ws.get(0).contains("少 2 项"),
                "告警要把「修订前 N 项、本次 M 项」两个数说出来：" + ws.get(0));
        assertTrue(ws.get(0).contains("「拦截」档"), "告警要说清是哪一档放行的（v64 起用中文档名）：" + ws.get(0));
        assertFalse(ws.get(0).contains("gate="), "上屏告警不得含裸配置值：" + ws.get(0));
        int seq2 = ((Number) body.get("revisionSeq")).intValue();
        assertEquals(2, fieldRowsOfRevision(specimenId, seq2), "warn 不是「只喊一声」：本版真落 2 行");

        // ---- warn 档：同一条告警；且基准是**修订前最新一版**、不是全部版本累计 ----
        assertEquals(1, setGate("warn"));
        var one = new LinkedHashMap<String, String>();
        one.put("标本大小", "5×4×3cm");
        var w = warningsOf(ok(process.reviseGrossFields(specimenId,
                new GrossReviseReq(null, one, "只留一项 " + tag), doc)));
        assertEquals(1, w.size(), "warn 档同样告警：" + w);
        assertTrue(w.get(0).contains("由 2 项减至 1 项"),
                "基准是修订前最新一版的 2 行，不是全部版本累计的 6 行（拿累计当基准会把「仍填满」误判成退化）："
                        + w.get(0));
        // v65：整段只剩这一个基准——全丢那档此前打的是累计行数（此刻 6），两条兄弟告警一段话里两套基准
        assertFalse(w.get(0).contains("6 项") || w.get(0).contains("6 行"),
                "**修复前的反向事实**：同一标本累计 6 行与最新一版 2 行两个数混在同一段守卫里：" + w.get(0));
        assertTrue(w.get(0).contains("不再写入的是「切面」"), "点名少掉的那一项：" + w.get(0));
        assertNoDeadFieldClaim(w.get(0));

        // ---- off 档：不判 ----
        assertEquals(1, setGate("off"));
        var z = ok(process.reviseGrossFields(specimenId,
                new GrossReviseReq(null, null, "整段自由描述 " + tag), doc));
        assertEquals(List.of(), warningsOf(z), "off 就是不判，全丢与部分退化一视同仁");

        // ---- 活对照组：项数不减就不告警（这几条不是「只要带字段就喊」）----
        assertEquals(1, setGate("warn"));
        var keepOne = new LinkedHashMap<String, String>();
        keepOne.put("标本大小", "6×4×3cm");
        var k = ok(process.reviseGrossFields(specimenId, new GrossReviseReq(null, keepOne, "改了值 " + tag), doc));
        assertEquals(List.of(), warningsOf(k),
                "活对照组：修订前最新一版 1 行（上一次整段自由描述没落字段行）、本次 1 行，不减就不告警");
    }

    // =====================================================================================
    // ②c v63（2530 复核）：读侧诚实——「文本里 N 个『标签：值』，其中 M 个有字段行」
    // =====================================================================================

    @Test
    void theReadEndpointSaysHowManyLabelValueFormsActuallyHaveFieldRows() {
        // 复核者走的就是最普通的这一条：取材登记 → 补取材 → 修订取材描述（照预填原样提交）
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

        // 累积全文阶段：4 个形态、只有最后一版那 2 项有字段行（fieldsCurrent 这一步 v61 已判 false）
        var v = ok(process.grossingView(specimenId));
        assertEquals(4, ((Number) v.get("textFieldForms")).intValue(),
                "累积全文里 4 个「标签：值」：" + v.get("grossFinding"));
        assertEquals(2, ((Number) v.get("textFieldFormsBacked")).intValue());
        assertEquals(Boolean.FALSE, v.get("fieldsCoverText"));

        // 照 GrossingPanel 的预填原样提交（Java 同构版 prefill 与那段 TS 逐条对应）
        var fixed = prefill(String.valueOf(v.get("grossFinding")), rows(v, "fields"));
        assertEquals("CUMULATIVE", fixed.mode());
        var r = ok(process.reviseGrossFields(specimenId,
                new GrossReviseReq(null, new LinkedHashMap<>(fixed.gross()), fixed.free()), doc));
        assertEquals(2, ((Number) r.get("grossFieldCount")).intValue());
        assertEquals(List.of(), warningsOf(r),
                "本次 2 项、修订前最新一版也是 2 项（追加那一版），不减——所以 gate 这一侧沉默，"
                        + "**这条路只能靠读侧说实话**");

        // ---- 复核者站的那一格 ----
        var v2 = ok(process.grossingView(specimenId));
        int fseq = ((Number) v2.get("fieldsRevisionSeq")).intValue();
        int tseq = ((Number) v2.get("textRevisionSeq")).intValue();
        // **修复前的反向事实**：v62 判 fieldsCurrent 的两个条件在这里全都成立，于是它判 true、
        // fieldsNote 给 null，查看弹窗打绿色「最新字段版（第 N 版，2 项），与当前文本同版」。
        assertEquals(fseq, tseq, "v62 条件一：字段版号 = 文本版号");
        assertEquals(Boolean.FALSE, v2.get("textIsCumulative"), "v62 条件二：文本不是累积拼装形态");
        assertEquals(4, ((Number) v2.get("textFieldForms")).intValue(),
                "而当前这段描述里有 4 个「标签：值」：" + v2.get("grossFinding"));
        assertEquals(2, ((Number) v2.get("textFieldFormsBacked")).intValue(), "其中只有 2 个有结构化字段行");
        assertEquals(Boolean.FALSE, v2.get("fieldsCoverText"));
        assertEquals(Boolean.FALSE, v2.get("fieldsCurrent"),
                "**修复前这里是 true**：只比版号，把「覆盖不全」标成「与当前文本同版」");
        String note = String.valueOf(v2.get("fieldsNote"));
        assertTrue(note.contains("4 个「标签：值」形态") && note.contains("其中 2 个"),
                "**修复前 fieldsNote 是 null**：屏上没有一处说得出这两个数：" + note);
        assertFalse(note.contains("第 " + tseq + " 版取材修订只写了自由文本"),
                "措辞不许倒退成「同一个版号说两件事」——本次修订真写了 2 项字段：" + note);

        // ---- 正向：把 4 项都填成字段 → N == M，这时才准说「与当前文本同版」----
        var all4 = new LinkedHashMap<String, String>();
        all4.put("补取块数", "2 块");
        all4.put("最大径", "0.8cm");
        all4.put("标本大小", "5×4×3cm");
        all4.put("切面", "灰白");
        ok(process.reviseGrossFields(specimenId, new GrossReviseReq(null, all4, "首次 " + tag), doc));
        var v3 = ok(process.grossingView(specimenId));
        assertEquals(4, ((Number) v3.get("textFieldForms")).intValue());
        assertEquals(4, ((Number) v3.get("textFieldFormsBacked")).intValue());
        assertEquals(Boolean.TRUE, v3.get("fieldsCoverText"));
        assertEquals(Boolean.TRUE, v3.get("fieldsCurrent"), "四个形态都有字段行，这才是真的「与当前文本同版」");
        assertNull(v3.get("fieldsNote"), "没有分叉就不编一句");

        // ---- 活的对照组：拆解器是活的，也没把自由描述里的普通句子数成「标签：值」----
        assertEquals(List.of("大小：3cm", "切面：灰白"),
                PathologyProcessController.grossFieldForms("大小：3cm；切面：灰白。质软，无出血"),
                "活对照组：按 assembleGross 的分隔规则拆得出形态，而「质软，无出血」不是形态");
        assertEquals(List.of(),
                PathologyProcessController.grossFieldForms("送检组织质软，切面灰白，未见明确肿物"),
                "活对照组：一段没有「：」的散文数出 0 个形态——否则上面几条是恒真的");
        assertEquals(List.of("补取块数：2 块"),
                PathologyProcessController.grossFieldForms("旧文本。补取材：补取块数：2 块。补取材组织"),
                "活对照组：追加标记「。补取材：」是分隔符，不能被自己数成一个「补取材：…」形态");
    }

    // =====================================================================================
    // ②d v64（2530 复核 data 镜头）：N-M 那几处**不是同一回事**；两句话辖域各自说清、不再互相否定
    // =====================================================================================

    @Test
    void unbackedFormsAreSplitByWhetherAnEarlierVersionStillHoldsTheirFieldRows() {
        // 复核者站的那一格：取材登记（标本大小 / 切面）→ 补取材（补取块数 / 最大径）→ 照预填原样修订
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
        var v0 = ok(process.grossingView(specimenId));
        var fixed = prefill(String.valueOf(v0.get("grossFinding")), rows(v0, "fields"));
        ok(process.reviseGrossFields(specimenId,
                new GrossReviseReq(null, new LinkedHashMap<>(fixed.gross()), fixed.free()), doc));

        var v = ok(process.grossingView(specimenId));
        assertEquals(4, ((Number) v.get("textFieldForms")).intValue(),
                "当前这段描述里 4 个「标签：值」：" + v.get("grossFinding"));
        assertEquals(2, ((Number) v.get("textFieldFormsBacked")).intValue(), "最新字段版只覆盖其中 2 个");

        // **修复前的反向事实**（复核者原话）：v63 把余下这 2 个一律说成「只是这段文本里的一句话，
        // 字段级查询与统计取不到」，而它们（第 1 版录入的「标本大小」「切面」）此刻正以 path_gross_field 行
        // 躺在库里、由同一个返回体的 fieldsByRevision 原样回出、在同屏那个版本下拉里点一下就能调阅。
        assertEquals(2, ((Number) v.get("textFieldFormsInEarlierVersions")).intValue(),
                "这 2 个的字段行落在更早的版本里，不是「从来没录成字段」");
        assertEquals(0, ((Number) v.get("textFieldFormsTextOnly")).intValue(),
                "这条路径上没有一处是「任何一版都没有字段行」的");
        var unbacked = rows(v, "textFieldFormsUnbacked");
        assertEquals(List.of("标本大小", "切面"),
                unbacked.stream().map(u -> String.valueOf(u.get("label"))).toList(),
                "逐条给出差在哪几项——屏上要指名道姓，这是修订弹窗那条提示的数据源");
        for (var u : unbacked) {
            assertEquals(Boolean.TRUE, u.get("inEarlierVersion"), u.toString());
            assertEquals(1, ((Number) u.get("earlierRevisionSeq")).intValue(), "它们是第 1 版录入的：" + u);
        }
        // 库内对照组：这两条字段行真的还在（断言钉的是库内事实，不是「后端换了个说法」）
        assertEquals(2L, (long) jdbc.queryForObject("""
                select count(*) from path_gross_field
                where specimen_id = ? and revision_seq = 1 and label in ('标本大小', '切面')
                """, Long.class, specimenId));

        // ---- 屏上那条唯一的口径提示（查看弹窗 / 轨迹抽屉同一句）----
        String note = String.valueOf(v.get("fieldsNote"));
        assertTrue(note.contains("4 个「标签：值」形态") && note.contains("其中 2 个"),
                "N / M 两个数照旧说得出：" + note);
        assertTrue(note.contains("另有 2 个的字段行落在更早的版本里"),
                "**修复前的反向事实**：这 2 个被说成「只是这段文本里的一句话」：" + note);
        assertFalse(note.contains("字段级查询与统计取不到"),
                "**修复前的反向事实**：这半句与库内事实相反——那 2 条正躺在第 1 版里：" + note);
        assertFalse(note.contains("要补齐请在"),
                "不许再给基于假结论的操作指引（那几条本来就有字段行）：" + note);

        // ---- 版号这一维：两个版号相等，**旧前端那句「但当前文本已是第 N 版」就是在这里成为假话的** ----
        assertEquals(v.get("fieldsRevisionSeq"), v.get("textRevisionSeq"),
                "字段版号与文本版号是同一个数——把它说成「文本已是第 N 版」就是同一个版号说两件事");
        assertEquals(Boolean.TRUE, v.get("fieldsVersionCurrent"), "版号这一维为真");
        assertEquals(Boolean.FALSE, v.get("fieldsCoverText"), "覆盖那一维为假");
        assertEquals(Boolean.FALSE, v.get("fieldsCurrent"), "两维之与——false 的**原因在覆盖，不在版号**");

        // ---- 活的对照组：把 4 项都填成字段 → 三个余量键归零、fieldsNote 一句不编 ----
        var all4 = new LinkedHashMap<String, String>();
        all4.put("补取块数", "2 块");
        all4.put("最大径", "0.8cm");
        all4.put("标本大小", "5×4×3cm");
        all4.put("切面", "灰白");
        ok(process.reviseGrossFields(specimenId, new GrossReviseReq(null, all4, "首次 " + tag), doc));
        var v3 = ok(process.grossingView(specimenId));
        assertEquals(0, ((Number) v3.get("textFieldFormsInEarlierVersions")).intValue());
        assertEquals(0, ((Number) v3.get("textFieldFormsTextOnly")).intValue());
        assertEquals(List.of(), rows(v3, "textFieldFormsUnbacked"), "活对照组：覆盖全了就一条余量都没有");
        assertNull(v3.get("fieldsNote"), "活对照组：没有分叉就不编一句——上面那几条断言才不是恒真");
    }

    // =====================================================================================
    // ②e v64（2530 复核 demo 镜头）：屏上不许出现开发者视角的内容
    // =====================================================================================

    @Test
    void screensDropDeveloperFacingText() throws IOException {
        // ---- ① 取材列表顶端那条提示条（后端 note 原样上屏）----
        String wlNote = String.valueOf(ok(process.grossingWorklist(null, null, null, null, null)).get("note"));
        for (String bad : List.of("pending", "all：", "hoursSinceReceived", "V144")) {
            assertFalse(wlNote.contains(bad),
                    "**修复前的反向事实**：取材列表顶端把「" + bad + "」打在屏上：" + wlNote);
        }
        assertTrue(wlNote.contains("「待取材」") && wlNote.contains("「距签收(小时)」"),
                "活对照组：这句话还在，只是改成了屏上看得懂的业务语言：" + wlNote);

        // ---- ② 轨迹抽屉底部那条（同型）----
        ok(process.grossing(new GrossingReq(specimenId, null, null, "自由文本 " + tag, false, null,
                List.of(new BlockReq("块 " + tag))), doc));
        String trailNote = String.valueOf(ok(process.trail(specimenId, null)).get("note"));
        for (String bad : List.of("nodes ", "hours_since_prev", "anomalies ", "STALLED", "stallHours")) {
            assertFalse(trailNote.contains(bad), "轨迹抽屉屏上不得出现「" + bad + "」：" + trailNote);
        }
        String anomalyNote = String.valueOf(ok(process.anomalies(null, null, null, null, null, null)).get("note"));
        for (String bad : List.of("STALLED", "SECTION_WITHOUT_EMBED", "DIAGNOSED_WITHOUT_STAIN",
                "ISSUED_WITHOUT_DOUBLE_SIGN", "counts ", "[from, to]")) {
            assertFalse(anomalyNote.contains(bad), "流转异常列表屏上不得出现「" + bad + "」：" + anomalyNote);
        }
        assertTrue(anomalyNote.contains("「超时未流转」") && anomalyNote.contains("「签发时缺双签」"),
                "活对照组：四类异常改用屏上已有的中文名：" + anomalyNote);

        // ---- ③ 查看弹窗那条唯一的口径提示不得夹带库列名 / 内部键名 / 迁移号 ----
        var v = ok(process.grossingView(specimenId));
        String apiNote = String.valueOf(v.get("note"));
        assertTrue(apiNote.contains("fieldsByRevision"), "活对照组：给调用方的接口说明照旧详尽（它只是不再上屏）");
        assertFalse(apiNote.contains("口径与事实相反"),
                "**修复前的反向事实**：平台自述的缺陷史写在返回体里，被前端原样打到演示正屏上：" + apiNote);
        assertFalse(apiNote.contains("v62 只比版号"), "同上：" + apiNote);

        // ---- ④ 源码：那条 <p> 不再原样打接口说明；模板码换中文名；覆盖事实真上屏 ----
        String raw = read(PANEL);
        String panel = stripComments(raw);
        String trail = stripComments(read(TRAIL));
        String gTpl = templateOf(raw);
        String tTpl = templateOf(read(TRAIL));
        assertTrue(panel.contains("splitGross") && trail.contains("revisionFields"),
                "活对照组：扫描器确实读到了这两份文件的真代码");

        assertFalse(gTpl.contains("view.note"),
                "**修复前的反向事实**：<p v-if=\"view.note\">{{ view.note }}</p> 把给调用方的接口说明原样打在「查看大体所见」上");
        assertFalse(gTpl.contains("fmt(viewVersion?.templateCode)"),
                "**修复前的反向事实**：「模板」一栏直接显示英文码 GI_BIOPSY");
        assertFalse(tTpl.contains("fmt(row.templateCode)"), "轨迹抽屉的「模板」列同型");
        assertFalse(gTpl.contains("fmt(result.codePrefixSource)"),
                "**修复前的反向事实**：取材完成页把 PATH_NO / BARCODE 当标签贴出");
        assertFalse(gTpl.contains("${t.code}"), "**修复前的反向事实**：模板下拉把码贴在中文名后面");
        for (String bad : List.of("后端未回 fieldsByRevision", "path_block.tech_order_id",
                "status=ORDERED 且 tech_type=RESAMPLE", "sys_config 只有 255 字符", "后端返 5223", "被拒（5222）")) {
            assertFalse(gTpl.contains(bad) || tTpl.contains(bad), "屏上不得出现「" + bad + "」");
        }
        assertFalse(tTpl.contains("scope=any"), "轨迹查询那条提示里的内部档位参数同型");

        // 探针：把修复前那一行塞回模板段，同一个扫描器必须抓到（证明它在咬）
        assertTrue(templateOf(raw.replace("<template>",
                        "<template>\r\n<p>{{ fmt(viewVersion?.templateCode) }}</p>"))
                        .contains("fmt(viewVersion?.templateCode)"),
                "探针：templateOf 剥注释后仍看得见模板段里的真表达式");

        // ---- ⑤ 模板名两处逐字同源（同一事实两个入口一套措辞）----
        assertTrue(panel.contains("templateNameOf(viewVersion)") && trail.contains("templateNameOf(row)"),
                "两个入口都按中文名显示模板");
        assertEquals(bodyOf(panel, "function templateNameOf"), bodyOf(trail, "function templateNameOf"),
                "两处实现必须逐字相同——v63 栽的就是「同一事实两个入口两套说法」");
    }

    // =====================================================================================
    // ②f v64（2530 复核 decompose 镜头）：三个覆盖事实键的屏上归宿就是「修订取材描述」弹窗
    // =====================================================================================

    @Test
    void reviseDialogConsumesTheCoverageFacts() throws IOException {
        String panel = stripComments(read(PANEL));
        assertTrue(panel.contains("splitGross") && panel.contains("openRevise"),
                "活对照组：扫描器确实读到了修订表单的真代码");

        // --- 止血：那一句不再按老含义读两维之与 ---
        assertFalse(panel.contains("if (d.fieldsCurrent === false) {"),
                "**修复前的反向事实**：修订弹窗顶上那条提示按老含义读 fieldsCurrent——v63 起它是"
                        + "「版号相同 且 字段覆盖全文」两维之与，于是「版号明明相同、只是覆盖不全」也打版号措辞，"
                        + "屏上「已按库里第 3 版字段原样预填，但当前文本已是第 3 版」把同一个版号说成两件事");
        assertTrue(panel.contains("if (d.fieldsVersionCurrent === false) {"),
                "版号那一维照后端**分开给出**的键判，不在前端重算");
        assertTrue(panel.contains("fieldsCurrent"),
                "活对照组：fieldsCurrent 仍被别处消费，不是整片删掉才变绿的");

        // --- 三个覆盖事实键真被消费，且归宿就是修订弹窗 ---
        for (String k : List.of("textFieldForms", "textFieldFormsBacked", "fieldsCoverText",
                "textFieldFormsUnbacked", "textFieldFormsInEarlierVersions", "textFieldFormsTextOnly")) {
            assertTrue(panel.contains(k),
                    "**修复前的反向事实**：v63 新增的这些键在 frontend/shell/src 里**零引用**，"
                            + "后端 javadoc 却写着「前端原样印这一个字符串」：" + k);
        }
        assertTrue(panel.contains("reviseCover.value = d"),
                "覆盖事实的数据源就是修订弹窗自己那次 GET /grossing/{id}，不另取一次、不自己解析文本");
        int compute = panel.indexOf("const reviseCoverNote = computed(");
        int bind = panel.indexOf(":title=\"reviseCoverNote\"");
        assertTrue(compute > 0, "必须有一处把三个键算成一句人话");
        assertTrue(bind > 0 && bind < compute,
                "**算了还得显示**：这句话必须绑在模板上（v63 的教训是「只在后端存在」）：bind=" + bind);
        assertTrue(panel.contains("mode === 'REVISE' && reviseCoverNote"),
                "它必须挂在「修订取材描述」弹窗上——后端正是把人指到这个入口来的");

        // --- v65：那句「已预填」不再写死，改由表单此刻真有哪几项字段生成 ---
        assertFalse(panel.contains(V64_PREFILL_CLAIM),
                "**修复前的反向事实**：v64 把「" + V64_PREFILL_CLAIM + "」直接写进模板串，"
                        + "与 splitGross 的档位、与字段栏里到底有没有东西都无关；UNPARSED 档一项都不预填");
        assertTrue(panel.contains("const reviseFormFields = computed"),
                "「表单此刻真会提交哪几项」要有一处显式定义（提交与这句话共用它）");
        assertTrue(bodyOf(panel, "const reviseSubmitNote = computed(").contains("reviseFormFields.value"),
                "「这次提交会发生什么」必须**由那个状态生成**，不是人手写一句");
        assertTrue(bodyOf(panel, "const reviseCoverNote = computed(").contains("reviseSubmitNote.value"),
                "覆盖提示的后半句就是那句生成出来的话");
        assertTrue(bodyOf(panel, "async function submitRevise(").contains("reviseFormFields.value"),
                "提交带哪几项与屏上那句话同源——两边各写一遍过滤规则，规则一改就必然漂");
        // 逐字钉住两档措辞：下面 ②g 的 Java 同构版照着它们写，措辞一改那边就得跟着改
        assertTrue(panel.contains(SUBMIT_NOTE_ZERO_HEAD), "0 项那档的措辞：" + SUBMIT_NOTE_ZERO_HEAD);
        assertTrue(panel.contains("下面的字段栏此刻填了 ${k} 项：本次提交带的就是这 ${k} 项，"
                        + "落库后它们构成这一版的字段级记录；"),
                "有字段那档的措辞");
        // 「按版本调阅得到」在屏上的落点：版本下拉逐版列 fieldsByRevision（后端把各版都回出来了）
        assertTrue(bodyOf(panel, "const viewFieldVersions = computed").contains("view.value.fieldsByRevision"),
                "告警里那句「在「查看大体所见」里按版本调阅得到」指的就是这个下拉，它必须真按 fieldsByRevision 逐版列");
    }

    // =====================================================================================
    // ②g v65（2530 复核 decompose 镜头，本轮头号纪律）：
    //     UNPARSED 档下「覆盖提示」与「实际预填」必须说同一件事
    // =====================================================================================

    @Test
    void theCoverNoteAgreesWithWhatWasActuallyPrefilled() {
        // 复核者站的那一格：取材(填字段) → 补取材(填字段) → 补取材(**只写自由描述**)
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
        ok(process.grossing(new GrossingReq(specimenId, null, null, "第三次只写描述 " + tag, true, null,
                List.of(new BlockReq("三块 " + tag))), doc));

        var v = ok(process.grossingView(specimenId));
        var fields = rows(v, "fields");
        var split = prefill(String.valueOf(v.get("grossFinding")), fields);
        assertEquals("UNPARSED", split.mode(),
                "最后一次补取材只写自由描述 → 当前文本不是由最新字段版直接拼出的形态：" + v.get("grossFinding"));
        assertTrue(split.gross().isEmpty(),
                "**这一格的事实**：UNPARSED 档返回 gross: {}，字段栏一项都不预填");
        // 同一格里那条覆盖提示确实会上屏（v-if 的两个条件都成立），两条黄条就是这么贴在一起的
        assertEquals(Boolean.TRUE, v.get("fieldsAvailable"));
        assertEquals(Boolean.FALSE, v.get("fieldsCoverText"));
        assertEquals(2, ((Number) v.get("textFieldFormsBacked")).intValue());

        // ---- 活的对照组：**修复前的形态**在这一格里逐字为假 ----
        String v64Tail = coverNoteTailV64(((Number) v.get("textFieldFormsBacked")).intValue());
        assertTrue(v64Tail.contains(V64_PREFILL_CLAIM),
                "活对照组：修复前那句把「已预填」写死，与预填结果无关：" + v64Tail);
        assertTrue(split.gross().isEmpty() && v64Tail.contains("2 处有结构化字段行"),
                "**修复前的反向事实**：屏上宣告 2 处已预填，而字段栏里是 0 项——两条黄条同屏互相否定");

        // ---- 修复后：这句话由「表单此刻真有哪几项」生成 ----
        String saidNow = submitNote(split.gross().size());
        assertFalse(saidNow.contains("已预填"), "不得再出现与预填结果无关的断言：" + saidNow);
        assertTrue(saidNow.contains("一项都没有填"), "UNPARSED 档下说的是「一项都没有填」：" + saidNow);
        assertTrue(saidNow.contains("结构化记录停在修订前那一版"),
                "技师真正要知道的是「这次提交会发生什么」：" + saidNow);

        // ---- 活的对照组：同一条规则在拆得开的那一档说的是另一句（不是恒说「没填」）----
        var cumulative = prefill(
                "标本大小：5cm。首次。补取材：补取块数：2 块。补描述",
                List.of(Map.<String, Object>of("label", "补取块数", "value", "2 块")));
        assertEquals("CUMULATIVE", cumulative.mode(), "累积全文按「。补取材：」切得开");
        assertEquals(1, cumulative.gross().size());
        assertTrue(submitNote(cumulative.gross().size()).contains("填了 1 项"),
                "活对照组：预填带出字段时这句话跟着变——它不是一句写死的话");
    }

    // =====================================================================================
    // ②h v65（2530 复核 demo 镜头）：英文模板码上屏的**第四个入口**——落库备注
    // =====================================================================================

    @Test
    void theGrossingNodeRemarkNamesTheTemplateInChinese() {
        var catalog = ok(process.grossingTemplates(null));
        var codeToName = new LinkedHashMap<String, String>();
        for (var t : rows(catalog, "items")) {
            codeToName.put(String.valueOf(t.get("code")), String.valueOf(t.get("name")));
        }
        assertTrue(codeToName.size() >= 9, "活对照组：模板清单真读到了：" + codeToName.keySet());
        assertEquals("胃肠镜活检", codeToName.get("GI_BIOPSY"), "中文名的唯一事实源就是这份清单");

        // ---- 带下划线的码（GI_BIOPSY）----
        ok(process.grossing(new GrossingReq(specimenId, "GI_BIOPSY", null, "取材描述 " + tag, false, null,
                List.of(new BlockReq("块 " + tag))), doc));
        String remark = latestGrossingRemark(specimenId);
        assertTrue(remark.contains("取材产出 1 块"), "本次产出块数照旧如实写：" + remark);
        assertTrue(remark.contains("（模板 胃肠镜活检）"), "模板印中文名：" + remark);
        assertEquals(List.of(), codesIn(remark, codeToName.keySet()),
                "**修复前的反向事实**：备注正文写的是「（模板 GI_BIOPSY）」，"
                        + "「取材打点」表的备注列与⑥流转时间线各印一次：" + remark);

        // ---- 不带下划线的码（LUNG）：**按形态猜的正则会漏掉它**，所以这条用例必须在 ----
        ok(process.grossing(new GrossingReq(freeTextId, "lung", null, "取材描述 " + tag, false, null,
                List.of(new BlockReq("块F " + tag))), doc));
        String lungRemark = latestGrossingRemark(freeTextId);
        assertTrue(lungRemark.contains("（模板 肺）"), "小写入参也规范化到同一份清单上：" + lungRemark);
        assertEquals(List.of(), codesIn(lungRemark, codeToName.keySet()), lungRemark);

        // ---- 活的对照组：同一个检查器抓得到修复前的两种形态（否则上面两条是恒真的）----
        assertEquals(List.of("GI_BIOPSY"), codesIn("取材产出 2 块（模板 GI_BIOPSY）", codeToName.keySet()),
                "活对照组：带下划线的码必须被抓到");
        assertEquals(List.of("LUNG"), codesIn("取材产出 2 块（模板 LUNG）", codeToName.keySet()),
                "活对照组：不带下划线的码同样要被抓到——这正是裸码正则（要求下划线）漏掉的那一类");
    }

    // =====================================================================================
    // ⑥ v65：「旧版字段行仍在、按版本调阅得到」凭什么成立——全仓零 update / delete
    // =====================================================================================

    @Test
    void fieldRowsAreNeverUpdatedOrDeletedAnywhere() throws IOException {
        var scanned = new ArrayList<String>();
        var offenders = new ArrayList<String>();
        for (String dir : List.of("modules", "server/src/main")) {
            try (var walk = Files.walk(repoRoot().resolve(dir))) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".java") && !name.endsWith(".sql")) continue;
                    String body = stripSqlComments(stripComments(
                            new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
                    if (!body.contains("path_gross_field")) continue;
                    scanned.add(name);
                    if (FIELD_ROW_WRITE.matcher(body).find()) offenders.add(name);
                }
            }
        }
        assertTrue(scanned.size() >= 3,
                "活对照组：扫描器必须真的读到提到 path_gross_field 的那几份文件，实得 " + scanned);
        assertEquals(List.of(), offenders,
                "写端点那两条告警说「旧版字段行仍在、按版本调阅得到」，凭的就是这张表只 insert 不改不删；"
                        + "这里一旦有人加了 update / delete，那句话当场变成假话：" + offenders);
        // 活的对照组：同一个匹配器抓得到这两种形态，也不咬 insert
        assertTrue(FIELD_ROW_WRITE.matcher("update path_gross_field set value = 'x' where id = 1").find(),
                "活对照组：update 形态必须被抓到");
        assertTrue(FIELD_ROW_WRITE.matcher("delete from path_gross_field where specimen_id = ?").find(),
                "活对照组：delete 形态必须被抓到");
        assertFalse(FIELD_ROW_WRITE.matcher(
                        "insert into path_gross_field(specimen_id, revision_seq) values (?, ?)").find(),
                "活对照组：insert 不该被咬——正则不能宽到把唯一合法写法也算进去");
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

    /**
     * GrossingPanel 的 {@code reviseSubmitNote} 同构版：由**表单此刻真有哪几项字段**生成。
     * 措辞与那边逐字相同（②f 用源码扫描钉着），那边改一个字，这里跟着红。
     */
    private static String submitNote(int formFieldCount) {
        if (formFieldCount == 0) {
            return SUBMIT_NOTE_ZERO_HEAD + "结构化记录停在修订前那一版（那几行不会被删，仍按版本调阅得到）。";
        }
        return "下面的字段栏此刻填了 " + formFieldCount + " 项：本次提交带的就是这 " + formFieldCount
                + " 项，落库后它们构成这一版的字段级记录；"
                + "没进字段栏的内容只作为文本保存（更早各版已经落下的字段行不会被删，仍按版本调阅得到）。";
    }

    /** **修复前的形态**：v64 把「已预填」写死在模板串里，与 splitGross 的档位、与字段栏里有没有东西都无关 */
    private static String coverNoteTailV64(int backed) {
        return "其中 " + backed + " 处有结构化字段行（" + V64_PREFILL_CLAIM + "）。";
    }

    /** 正文里出现的模板代码（按清单逐个找，不按形态猜——LUNG / SKIN 这类没有下划线，形态正则抓不到） */
    private static List<String> codesIn(String text, java.util.Collection<String> codes) {
        var hit = new ArrayList<String>();
        for (String c : codes) if (text.contains(c)) hit.add(c);
        return hit;
    }

    /** v65：写端点那两条告警不得再说那句与库内事实相反的话（v62 的同一条禁令此前只钉读端点） */
    private static void assertNoDeadFieldClaim(String warning) {
        assertFalse(warning.contains("字段级查询与统计取不到"),
                "**修复前的反向事实**：那几行仍以旧 revision_seq 躺在库里、按版本调阅得到：" + warning);
        assertFalse(warning.contains("只留在大体所见文本里"),
                "**修复前的反向事实**：新文本里有没有它们取决于修订者写没写进自由描述，端点不替它断言：" + warning);
    }

    private String latestGrossingRemark(long specimenId) {
        String r = jdbc.queryForObject("""
                select remark from path_process
                where specimen_id = ? and node = 'GROSSING' order by id desc limit 1
                """, String.class, specimenId);
        assertNotNull(r, "GROSSING 节点必须落了备注");
        return r;
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
        return Files.readString(repoRoot().resolve(rel));
    }

    private static Path repoRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !(Files.isDirectory(root.resolve("frontend")) && Files.isDirectory(root.resolve("modules")))) {
            root = root.getParent();
        }
        assertNotNull(root, "找不到仓库根");
        return root;
    }

    /** 剥 HTML / 块 / 行注释——注释里的形态不算数（本仓多次源码扫描误判的教训） */
    private static String stripComments(String s) {
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("(?m)^\\s*//[^\\n]*", "");
        return s;
    }

    /** v64：只取 &lt;template&gt; 段并剥掉注释——**屏上真会渲染出来的那一部分**，注释里的形态不算数 */
    private static String templateOf(String vue) {
        int end = vue.indexOf("</template>");
        String tpl = end < 0 ? vue : vue.substring(0, end);
        return tpl.replaceAll("(?s)<!--.*?-->", "");
    }

    /** v64：取一个 TS 函数从签名到闭合大括号的整段（用于「两个入口一套实现」的逐字比对，忽略换行形态） */
    private static String bodyOf(String src, String header) {
        int at = src.indexOf(header);
        assertTrue(at > 0, "找不到：" + header);
        int end = src.indexOf("\n}", at);
        assertTrue(end > at, "函数体没闭合：" + header);
        return src.substring(at, end).replace("\r", "");
    }

    /** 剥 SQL 行注释与块注释 */
    private static String stripSqlComments(String s) {
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("(?m)^\\s*--[^\\n]*", "");
        return s;
    }
}
