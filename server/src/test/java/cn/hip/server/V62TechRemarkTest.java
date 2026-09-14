package cn.hip.server;

import cn.hip.medtech.web.PathQcController;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.medtech.web.PathologyReportController.CancelTechOrderReq;
import cn.hip.medtech.web.PathologyReportController.TechOrderReq;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v62 车道 B（2563 复核三条里归本车道的那部分）：节点备注去裸码 / 完成 gate 认蜡块 / 穿透 CSV 列。
 *
 * <p><b>修复前的反向事实</b>（v61 交付后复核，主控实测坐实）：
 * <ul>
 *   <li><b>完成判定漏掉蜡块事实</b>：{@code doneTechOrder} 的事实查询只取 slide_count / stained_count，
 *       不认平台自己在 v60/v61 才建起来的「补取材已出块」（{@code path_block.tech_order_id}，V167）。
 *       同一屏上进度列写着「已补取材待切片 · 已出块 2」，点「完成」却判 gap =「无挂接切片（slide_count=0）」：
 *       warn 档（出厂默认）弹告警并把「挂接 0 片 / 已染色 0；无挂接切片（slide_count=0）」永久写进 TECH_DONE
 *       节点备注——<b>给这条医嘱留下一条「无证据完成」的假账，而证据就在隔壁列</b>；
 *       block 档下补取材医嘱<b>永远完不成</b>。六态派生已读四参、完成 gate 仍是 v58 的两参口径，
 *       两套口径在同一条医嘱上自相矛盾。</li>
 *   <li><b>节点备注里的内部标识</b>：TECH_DONE 备注把库列名 {@code slide_count=0} 逐字写进给评委看的正文。</li>
 *   <li><b>穿透 CSV 列</b>：WORKLOAD_TECH 穿透明细压根没有中文「技术分类」列（中文只存在于汇总行的 case 分支里），
 *       表头却写作「技术类型编码」；「挂接切片染色」这个中文表头<b>出现两次</b>（attached_stain 英文版与
 *       attached_stain_name 中文版被 zh() 映射成同一个名字）；「状态」与「关联蜡块来源」在同一行同时出现
 *       ORDERED 而含义完全相反。屏上靠前端 cellText 掩盖，导出一离开页面就原形毕露。</li>
 * </ul>
 *
 * <p><b>当时未动、已由合并后补齐处理</b>：STAIN（{@code 质量 GOOD}）/ SECTION（{@code ，IHC CK7），特检医嘱#12 IHC}）/
 * GROSSING（{@code ，补取材医嘱#12 RESAMPLE}）三类节点的备注在 {@code PathologyProcessController} 里逐字打裸码，
 * 而那个文件本轮归车道 A 独占，故本类第 ① 条只钉本车道管得到的三个节点，
 * <b>对照组用的正是那三处当时的真实形态</b>——同一组正则抓得到它们，才说明上面三条不是恒真的。
 * 合并后由 {@link V62NodeRemarkCodeTest} 把那三个节点补齐并<b>实查落库正文</b>；
 * 本类的对照组是<b>历史形态的字面量</b>，不随修复变化，两边各自成立。
 *
 * <p>{@link #RAW_ENUM} / {@link #RAW_COLUMN} 对包内可见，供 {@code V62NodeRemarkCodeTest} 复用——
 * 「备注里什么算裸码」只该有一份定义，抄第二份就是两套口径，改一处漂一处。
 *
 * <p>夹具照抄 V61TechLabelTest（同一条演示路径），gate 在 setUp 里钉成出厂值 warn。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V62TechRemarkTest {

    /**
     * 备注正文里的<b>裸英文枚举</b>：本域实际值域的白名单 + 前后不得接字母数字下划线。
     * 刻意不写成「任意大写串」——医嘱项目名（CK7 / PAS / P53）本来就该原样保留，
     * 那样的正则会把合法内容一起咬掉，而一条咬错的断言迟早被改宽到形同虚设。
     */
    static final Pattern RAW_ENUM = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:DEEP_CUT|RECUT|RESAMPLE|IHC|SPECIAL_STAIN|MOLECULAR|SPECIAL"
            + "|ORDERED|DONE|CANCELLED|PENDING_SECTION|SAMPLED|SECTIONING|STAINED"
            + "|DERIVED|MIXED|GOOD|FAIR|POOR|HE)(?![A-Za-z0-9_])");

    /** 备注正文里的<b>库列名 / 内部标识</b>：snake_case 形态（slide_count / tech_order_id / sampled_block_count） */
    static final Pattern RAW_COLUMN = Pattern.compile(
            "(?<![A-Za-z0-9_])[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?![A-Za-z0-9_])");

    @Autowired PathologyProcessController process;
    @Autowired PathologyReportController report;
    @Autowired PathQcController pathQc;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc1;
    private long a;          // 标本
    private long blockA;     // 首次取材的块

    @BeforeEach
    void setUp() {
        assertEquals(1, setGate("warn"), "techdone gate 行必须存在（V165 seed）");
        tag = "V62T" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");
        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "备注去码测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "备注去码患者" + tag);
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
                """, Long.class, orderId, "PB" + tag, tag + "-1", "备注去码标本" + tag);
        var gr = ok(process.grossing(new GrossingReq(a, null, null, "首次取材 " + tag, false, null,
                List.of(new BlockReq("肿物中心 " + tag))), doc1));
        blockA = idOf(rows(gr, "blocks").get(0));
    }

    // =====================================================================================
    // ① 本车道管得到的三个节点备注：既无裸英文枚举，也无库列名；对照组是那四处真实的修复前形态
    // =====================================================================================

    @Test
    void techNodeRemarksCarryNeitherRawEnumNorColumnName() {
        long ihc = techOrder(a, blockA, "IHC", "CK7");
        assertClean(remarkOf(a, "TECH_ORDER", ihc), "TECH_ORDER");

        // 0 片 0 块 + 出厂 warn：走的是「有缺口、放行」那条最长的备注分支
        ok(report.doneTechOrder(ihc, doc1));
        String done = remarkOf(a, "TECH_DONE", ihc);
        assertClean(done, "TECH_DONE");
        assertTrue(done.contains("已出块 0 / 挂接 0 片 / 已染色 0"),
                "TECH_DONE 备注须如实写三个事实（v62 增「已出块」）：" + done);
        assertTrue(done.contains("既无挂接切片、也无补取材已出块"),
                "缺口文案用人话、不带库列名（修复前是「无挂接切片（slide_count=0）」）：" + done);

        long pas = techOrder(a, null, "SPECIAL_STAIN", "PAS");
        ok(report.cancelTechOrder(pas, new CancelTechOrderReq("不做了 " + tag), doc1));
        String cancelled = remarkOf(a, "TECH_CANCEL", pas);
        assertClean(cancelled, "TECH_CANCEL");
        assertTrue(cancelled.contains("特殊染色") && cancelled.contains("PAS"),
                "类型走中文表、项目名原样保留：" + cancelled);

        // ---- 活的对照组：修复前的真实形态必须被同一组正则抓到，否则上面三条是恒真的 ----
        assertTrue(RAW_ENUM.matcher("#12 免疫组化(IHC) CK7").find(),
                "对照组：v61 之前 techLabel 拼的形态");
        assertTrue(RAW_ENUM.matcher("染色 " + tag + "-1-1（IHC CK7），质量 GOOD").find(),
                "对照组：STAIN 节点备注形态（仍在 PathologyProcessController，本轮归车道 A）");
        assertTrue(RAW_ENUM.matcher("切片 2 张（P-1，IHC CK7），特检医嘱#12 IHC").find(),
                "对照组：SECTION 节点备注形态（同上）");
        assertTrue(RAW_ENUM.matcher("取材产出 2 块，补取材医嘱#12 RESAMPLE").find(),
                "对照组：GROSSING 节点备注形态（同上）");
        assertTrue(RAW_COLUMN.matcher("确认完成特检医嘱 #12 免疫组化 CK7，挂接 0 片 / 已染色 0"
                + "；无挂接切片（slide_count=0）（gate=warn 放行）").find(),
                "对照组：v62 之前的 TECH_DONE 备注把库列名 slide_count 写进了给评委看的正文");
        // 反向：修好之后的形态不该被抓到——正则不能宽到连合法内容一起咬
        assertFalse(RAW_ENUM.matcher("#12 免疫组化 CK7").find(), "对照组：修好的形态不该被抓到");
        assertFalse(RAW_COLUMN.matcher("确认完成特检医嘱 #12 补取材，已出块 2 / 挂接 0 片 / 已染色 0").find(),
                "对照组：修好的形态不该被抓到");
    }

    // =====================================================================================
    // ② 完成 gate 认「补取材已出块」：三档齐全 + 每档各带一个「真的什么都没有」的活对照组
    // =====================================================================================

    @Test
    void doneGateAcceptsSampledBlocksAsEvidenceInAllThreeGates() {
        for (String gate : List.of("off", "warn", "block")) {
            assertEquals(1, setGate(gate), gate + "：sys_config 里必须有这一行可改");

            // (a) 补取材医嘱：已出 2 块、一张片子都没有
            long rs = techOrder(a, null, "RESAMPLE", null);
            var gr = ok(process.grossing(new GrossingReq(a, null, null, null, true, null,
                    List.of(new BlockReq("切缘一 " + tag), new BlockReq("切缘二 " + tag)), rs), doc1));
            assertEquals(2, rows(gr, "blocks").size(), "夹具前提：本次补取材出两块");
            assertEquals("SAMPLED", listRow(rs).get("progress"), "夹具前提：六态派生已认这两块");

            var r = report.doneTechOrder(rs, doc1);
            assertEquals(0, r.getCode(), gate
                    + "：补取材已出块就是执行证据，三档都该放行（**修复前 block 档下这条医嘱永远完不成**）："
                    + r.getMessage());
            var body = r.getData();
            assertEquals(2L, asLong(body.get("sampledBlockCount")), gate + "：任何档位都带已出块事实");
            assertEquals(0L, asLong(body.get("slideCount")), gate);
            assertEquals(0L, asLong(body.get("stainedCount")), gate);
            assertNull(body.get("doneGap"), gate + "：有已出块就不该判缺口（**与六态派生同口径**）");
            assertEquals(Boolean.FALSE, body.get("stainedComplete"),
                    gate + "：一张片子都没有，不能报「染色完整」——gap 为空不等于染完了");
            assertEquals(List.of(), body.get("warnings"), gate + "：无缺口即无告警");
            String remark = remarkOf(a, "TECH_DONE", rs);
            assertTrue(remark.contains("已出块 2 / 挂接 0 片 / 已染色 0"),
                    gate + "：TECH_DONE 备注须如实写「已出块 2」：" + remark);
            assertFalse(remark.contains("无挂接切片"),
                    gate + "：**修复前这里被写进一条「无证据完成」的假账，而证据就在隔壁列**：" + remark);
            assertFalse(remark.contains("gate="), gate + "：无缺口就没有「放行」这回事：" + remark);
            assertEquals("DONE", statusInDb(rs), gate);

            // (b) 活的对照组：同一档位下，真的什么都没有的补取材医嘱仍被判缺口——
            //     证明上面的「放行」是因为认了蜡块，不是因为 gate 被整体放空
            long bare = techOrder(a, null, "RESAMPLE", null);
            assertEquals("PENDING_SECTION", listRow(bare).get("progress"), "对照组前提：这条一块一片都没有");
            var br = report.doneTechOrder(bare, doc1);
            if ("block".equals(gate)) {
                assertEquals(5273, br.getCode(), "对照组：真无证据时 block 档仍拦：" + br.getMessage());
                assertTrue(br.getMessage().contains("既无挂接切片、也无补取材已出块")
                        && br.getMessage().contains("=block"), "5273 消息：" + br.getMessage());
                assertEquals("ORDERED", statusInDb(bare), "被拦后行仍 ORDERED");
                assertNull(nodeRemarkOrNull(a, "TECH_DONE", bare), "被拦不写 TECH_DONE 节点");
            } else {
                assertEquals(0, br.getCode(), br.getMessage());
                assertEquals("既无挂接切片、也无补取材已出块", br.getData().get("doneGap"),
                        gate + "：off 档也照算缺口、返回体照带（只是不拦不告警）");
                assertEquals(0L, asLong(br.getData().get("sampledBlockCount")), gate);
                assertEquals("warn".equals(gate) ? 1 : 0,
                        ((List<?>) br.getData().get("warnings")).size(), gate + "：warn 一条、off 零条");
                String bareRemark = remarkOf(a, "TECH_DONE", bare);
                assertTrue(bareRemark.contains("已出块 0 / 挂接 0 片 / 已染色 0"), bareRemark);
                assertEquals("warn".equals(gate), bareRemark.contains("（gate=warn 放行）"),
                        gate + "：warn 放行的字样只在 warn 档出现（off 档写的是「（gate=off 放行）」）：" + bareRemark);
            }
        }
    }

    // =====================================================================================
    // ③ 六态派生与完成 gate 必须是同一口径——纯判定层的穷举，不依赖库
    // =====================================================================================

    @Test
    void doneGapIsDerivedFromTheSameSixStateProgress() {
        var seen = new HashSet<String>();
        for (long slides : new long[]{0, 1, 2}) {
            for (long stained = 0; stained <= slides; stained++) {
                for (long blocks : new long[]{0, 1, 3}) {
                    String p = PathologyReportController.techProgress("ORDERED", slides, stained, blocks);
                    String gap = PathologyReportController.techDoneGap("ORDERED", slides, stained, blocks);
                    seen.add(p);
                    String at = "slides=" + slides + " stained=" + stained + " blocks=" + blocks + " → " + p;
                    boolean evidence = List.of("SAMPLED", "STAINED").contains(p);
                    assertEquals(evidence, gap == null,
                            at + "：完成 gate 与六态派生必须同口径（有证据才可完成），实际 gap=" + gap);
                }
            }
        }
        // 活的对照组：四个 ORDERED 子态都真的被穷举到了，否则上面的等价关系是空跑
        assertTrue(seen.containsAll(List.of("PENDING_SECTION", "SAMPLED", "SECTIONING", "STAINED")),
                "对照组：四个子态都要覆盖到，实际 " + seen);
        // 非 ORDERED 在完成端点已被 5268 挡住，判定层不再二次编故事
        assertNull(PathologyReportController.techDoneGap("DONE", 0L, 0L, 0L));
        assertNull(PathologyReportController.techDoneGap("CANCELLED", 0L, 0L, 0L));
    }

    // =====================================================================================
    // ④ 穿透 CSV：中文技术分类列在、表头无重名、两个 ORDERED 分得开
    // =====================================================================================

    @Test
    void techDetailCsvHeaderIsUnambiguousAndCarriesChineseTechClass() {
        long rs = techOrder(a, null, "RESAMPLE", null);
        ok(process.grossing(new GrossingReq(a, null, null, null, true, null,
                List.of(new BlockReq("切缘 " + tag)), rs), doc1));

        String csv = pathQc.detailCsv("WORKLOAD_TECH", null, null, null);
        List<String> header = csvHeader(csv);

        // 活的对照组：解析到的确实是 WORKLOAD_TECH 穿透的表头行，不是别的什么行
        assertTrue(header.contains("技术医嘱ID") && header.contains("技术项目"),
                "对照组：解析到的不是穿透表头：" + header);
        // 探针：重名检测器真的会报
        assertEquals(List.of("x"), duplicates(List.of("x", "y", "x")), "探针：重名检测器必须会报");

        assertEquals(List.of(), duplicates(header),
                "**同一份 CSV 的表头不得重名**（修复前「挂接切片染色」出现两次）：" + header);
        assertTrue(header.contains("技术类型"),
                "**穿透 CSV 必须有中文技术分类列**（修复前中文只在汇总行的 case 分支里）：" + header);
        assertTrue(header.contains("技术类型编码"), "编码列照旧保留，两列以有无「编码」区分：" + header);
        assertTrue(header.contains("挂接切片染色") && header.contains("挂接切片染色编码"),
                "中文版与编码版各自成名：" + header);
        assertTrue(header.contains("医嘱状态") && header.contains("关联蜡块来源"),
                "**同一行两个 ORDERED 含义相反，表头必须分得开**：" + header);
        assertFalse(header.contains("状态"), "「状态」这个笼统叫法不该再出现在本指标表头里：" + header);

        // 数据行：本条补取材医嘱的技术分类导出成中文，编码列原样保留
        String row = csvRow(csv, rs);
        assertTrue(row.startsWith(rs + ",RESAMPLE,补取材,"),
                "穿透行应为「医嘱ID,编码,中文」（修复前只有编码）：" + row);
        // 中文与汇总行同源：同一份 case 展开的，值必须逐字相同
        String summaryCsv = pathQc.indicatorsCsv("WORKLOAD_TECH", null, null);
        assertTrue(summaryCsv.contains("RESAMPLE,补取材,"),
                "汇总行的中文技术分类（与穿透共用同一段 case）：" + summaryCsv);
    }

    // ==================================================================================
    // 助手
    // ==================================================================================

    private void assertClean(String remark, String node) {
        assertFalse(RAW_ENUM.matcher(remark).find(), node + " 备注不得含裸英文枚举：" + remark);
        assertFalse(RAW_COLUMN.matcher(remark).find(), node + " 备注不得含库列名 / 内部标识：" + remark);
    }

    /** toCsv 的表头行：两行元信息后有一个空行，紧跟着的就是表头 */
    private static List<String> csvHeader(String csv) {
        String[] lines = csv.split("\n", -1);
        for (int i = 1; i < lines.length - 1; i++) {
            if (lines[i].isEmpty()) {
                return List.of(lines[i + 1].replace("\uFEFF", "").split(",", -1));
            }
        }
        throw new AssertionError("CSV 里找不到表头行：" + csv);
    }

    private static String csvRow(String csv, long techOrderId) {
        return csv.lines().filter(l -> l.startsWith(techOrderId + ","))
                .findFirst().orElseThrow(() -> new AssertionError("CSV 里找不到医嘱 " + techOrderId + " 的行：" + csv));
    }

    private static List<String> duplicates(List<String> cols) {
        var seen = new HashSet<String>();
        var dup = new ArrayList<String>();
        for (String c : cols) {
            if (!seen.add(c) && !dup.contains(c)) dup.add(c);
        }
        return dup;
    }

    private Map<String, Object> listRow(long techOrderId) {
        for (var r : rows(ok(report.techOrders(a, null, null, null, null, null, null, null, null, null)), "items")) {
            if (asLong(r.get("id")) == techOrderId) return r;
        }
        throw new AssertionError("清单里找不到医嘱 " + techOrderId);
    }

    private String remarkOf(long specimenId, String node, long techOrderId) {
        String r = nodeRemarkOrNull(specimenId, node, techOrderId);
        assertNotNull(r, node + " 节点未找到（医嘱 #" + techOrderId + "）");
        return r;
    }

    private String nodeRemarkOrNull(long specimenId, String node, long techOrderId) {
        var rs = jdbc.queryForList("""
                select remark from path_process
                where specimen_id = ? and node = ? and remark like ?
                order by id desc limit 1
                """, specimenId, node, "%#" + techOrderId + " %");
        return rs.isEmpty() ? null : String.valueOf(rs.get(0).get("remark"));
    }

    private String statusInDb(long techOrderId) {
        return jdbc.queryForObject("select status from path_tech_order where id = ?", String.class, techOrderId);
    }

    private long techOrder(long specimenId, Long blockId, String type, String item) {
        var t = ok(report.createTechOrder(new TechOrderReq(specimenId, blockId, type, item, "原因 " + tag), doc1));
        return ((Number) t.get("id")).longValue();
    }

    private int setGate(String value) {
        configReader.evictAll();
        int n = jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?",
                value, PathologyReportController.TECH_DONE_GATE_KEY);
        configReader.evictAll();
        return n;
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
}
