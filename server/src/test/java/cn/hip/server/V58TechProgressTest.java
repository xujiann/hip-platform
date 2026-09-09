package cn.hip.server;

import cn.hip.medtech.web.PathQcController;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * v58 车道 B：<b>特检技术医嘱执行进度派生 + 流转节点 + 完成校验 gate</b>（2563 三次核账，V165）。
 *
 * <h2>每条断言钉住的「修复前的反向事实」</h2>
 * <ol>
 *   <li>{@link #constraintAdmitsTechNodesAndLifecycleWritesProcessRows()}：修复前 chk_path_process_node 只有 V144 的
 *       12 档，直接往 path_process 写 'TECH_ORDER' 会撞 CHECK；技术医嘱的建 / 完 / 取消在 path_process 里<b>零行</b>。
 *       现在：三个新档由直接 SQL 探针证明放开（活的对照组 'TECH_BOGUS' 仍被拦、原 12 档一个不少），
 *       建 / 完 / 取消各落一条节点，operator 是当前用户，remark 带「#id 类型 项目」、取消带原因。</li>
 *   <li>{@link #progressIsDerivedFromAttachedSlidesThroughFiveStates()}：修复前清单只有手工三态 status；
 *       现在五态 progress 由挂接切片派生：0 片 → PENDING_SECTION；切 2 片未染 → SECTIONING；染 1 片仍 SECTIONING；
 *       染完 → STAINED；done → DONE；cancel → CANCELLED。两个清单分支与质控穿透同口径；slideId 反查只回该切片挂接的医嘱。</li>
 *   <li>{@link #doneGateSixCombosAndBadConfig()}：修复前 DONE 仅靠手点、不校验任何切片存在；现在 off / warn / block ×
 *       「0 片」「染完」六种组合逐一钉住：码、行状态、warnings、返回体事实；坏配置 'blocked' 按 warn；
 *       有片未染完是 5273 的第二条路径；5268 / 5270 既有守卫不变。</li>
 *   <li>{@link #gateKeyIsSeededAndWired()}：种子在 sys_config（update 影响行数为 1——影响 0 行正是「建了开关没接电」），
 *       remark 不超 255，V165 里的 seed 形态是 on conflict do nothing。</li>
 *   <li>{@link #migrationV165IsZeroBackfillAndKeepsOriginalNodes()} / {@link #zeroBackfillDetectorActuallyBites()}：
 *       V165 剥注释后无 update 语句、原 12 档字面量全部还在；对照组 V22 确有 update 且扫描器抓得到。</li>
 * </ol>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：夹具时刻由 SQL 按 {@code now()} 现算；节点时刻用「与 JVM 此刻偏差 ≤ 5 分钟」的容差断言；
 * 质控穿透的日期窗由 {@link BusinessDates#today()} 派生并两端各留一天余量。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V58TechProgressTest {

    @Autowired PathologyReportController report;
    @Autowired PathologyProcessController process;
    @Autowired PathQcController pathQc;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private static final String V165 = "server/src/main/resources/db/migration/V165__path_tech_progress.sql";
    /** 对照组：确实含顶层 update 语句的既有迁移（V22 {@code update md_drug set abx_level = 1 where antibiotic;}） */
    private static final String CONTROL_WITH_UPDATE = "server/src/main/resources/db/migration/V22__phase25_mgmt.sql";

    /** V144:125-127 的原 12 档——V165 一个不许删 */
    private static final List<String> V144_NODES = List.of(
            "RECEIVE", "REJECT", "GROSSING", "DEHYDRATE", "EMBED", "SECTION",
            "STAIN", "READ", "FIRST_SIGN", "SECOND_SIGN", "ISSUE", "SUPPLEMENT");
    private static final List<String> V165_NODES = List.of("TECH_ORDER", "TECH_DONE", "TECH_CANCEL");

    private String tag;
    private Long regId;
    private Authentication doc1;
    private Authentication doc2;
    private int gatePinned;   // setUp 里把 gate 钉到 warn 的 update 影响行数

    private long a;
    private long b;
    private long c;           // 只给约束探针用，不进任何清单断言
    private long blockA;
    private long blockB;

    @BeforeEach
    void setUp() {
        gatePinned = setGate("warn");

        tag = "V58" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");
        doc2 = doctorAuth(tag + "d2");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "特检进度测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "特检进度患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);

        a = specimen("A");
        b = specimen("B");
        c = specimen("C");
        blockA = gross(a, "肿物中心 " + tag);
        blockB = gross(b, "切缘 " + tag);
    }

    // =====================================================================================
    // ① 约束放开（直接 SQL 探针 + 活的对照组）；建 / 完 / 取消各落一条流转节点
    // =====================================================================================

    @Test
    void constraintAdmitsTechNodesAndLifecycleWritesProcessRows() {
        // 修复前反向事实：'TECH_ORDER' 直接撞 chk_path_process_node——现在三档都放行
        for (String n : V165_NODES) assertTrue(nodeAdmitted(c, n), n + " 应被 chk_path_process_node 放行（V165）");
        // 原 12 档一个不许删
        for (String n : V144_NODES) assertTrue(nodeAdmitted(c, n), n + " 是 V144 原有档，V165 不许删");
        // 活的对照组：白名单外的值必须仍被 CHECK 拦住——否则上面的绿只说明约束根本不存在了
        assertFalse(nodeAdmitted(c, "TECH_BOGUS"), "CHECK 应仍拦住白名单外的 TECH_BOGUS");
        assertFalse(nodeAdmitted(c, "tech_order"), "CHECK 区分大小写：小写 tech_order 不在白名单");

        // 修复前反向事实：技术医嘱建 / 完 / 取消在 path_process 里零行
        assertEquals(0L, techNodes(a));

        String reason = "HE 见腺样结构，查 CK7 定来源 " + tag;
        long ihc = techOrder(a, blockA, "IHC", "CK7", reason);
        assertEquals(1L, techNodes(a), "下达后应恰有一条技术医嘱节点");
        var ordered = node(a, "TECH_ORDER", ihc);
        assertEquals(userId(tag + "d1").longValue(), asLong(ordered.get("operator_id")), "TECH_ORDER 的操作人是下达人");
        String orderedRemark = String.valueOf(ordered.get("remark"));
        assertTrue(orderedRemark.contains("#" + ihc + " ") && orderedRemark.contains("IHC")
                        && orderedRemark.contains("免疫组化") && orderedRemark.contains("CK7") && orderedRemark.contains(reason),
                "TECH_ORDER 备注应带「#id 类型 项目」与下达原因，实际：" + orderedRemark);
        assertRecent(ordered.get("occurred_at"), "TECH_ORDER.occurred_at");

        // 完成（默认 warn、0 片：放行但节点写明缺口）
        var done = ok(report.doneTechOrder(ihc, doc2));
        assertEquals("DONE", done.get("status"));
        var doneNode = node(a, "TECH_DONE", ihc);
        assertEquals(userId(tag + "d2").longValue(), asLong(doneNode.get("operator_id")), "TECH_DONE 的操作人是确认人");
        String doneRemark = String.valueOf(doneNode.get("remark"));
        assertTrue(doneRemark.contains("#" + ihc + " ") && doneRemark.contains("CK7")
                        && doneRemark.contains("挂接 0 片") && doneRemark.contains("gate=warn 放行"),
                "warn 档放行的 TECH_DONE 备注必须写明缺口与放行，实际：" + doneRemark);
        assertRecent(doneNode.get("occurred_at"), "TECH_DONE.occurred_at");

        // 取消（带原因）
        long deep = techOrder(a, blockA, "DEEP_CUT", null, null);
        String why = "临床撤回 " + tag;
        var cancelled = ok(report.cancelTechOrder(deep, new CancelTechOrderReq(why), doc2));
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals("CANCELLED", cancelled.get("progress"));
        var cancelNode = node(a, "TECH_CANCEL", deep);
        assertEquals(userId(tag + "d2").longValue(), asLong(cancelNode.get("operator_id")), "TECH_CANCEL 的操作人是取消人");
        String cancelRemark = String.valueOf(cancelNode.get("remark"));
        assertTrue(cancelRemark.contains("#" + deep + " ") && cancelRemark.contains("深切") && cancelRemark.contains(why),
                "TECH_CANCEL 备注应带「#id 类型」与取消原因，实际：" + cancelRemark);
        assertRecent(cancelNode.get("occurred_at"), "TECH_CANCEL.occurred_at");

        assertEquals(4L, techNodes(a), "两次下达 + 一次完成 + 一次取消 = 4 条节点");

        // 被拒路径不留节点：5271（无原因）/ 5268（重复完成、重复取消）
        long again = techOrder(a, blockA, "RECUT", null, null);
        assertEquals(5L, techNodes(a));
        assertEquals(5271, report.cancelTechOrder(again, null, doc1).getCode());
        assertEquals(5268, report.doneTechOrder(ihc, doc1).getCode());
        assertEquals(5268, report.cancelTechOrder(deep, new CancelTechOrderReq("再取消"), doc1).getCode());
        assertEquals(5L, techNodes(a), "三条被拒路径一条节点都不许留");
    }

    // =====================================================================================
    // ② 进度五态逐一：0 片 → 切 2 片未染 → 染 1 片 → 染完 → done；另一条 cancel
    // =====================================================================================

    @Test
    void progressIsDerivedFromAttachedSlidesThroughFiveStates() {
        var created = ok(report.createTechOrder(new TechOrderReq(a, blockA, "IHC", "CK7", null), doc1));
        long ihc = asLong(created.get("id"));
        assertEquals("PENDING_SECTION", created.get("progress"), "刚下达：待切片");
        assertEquals("待切片", created.get("progressName"));
        assertProgress(ihc, "PENDING_SECTION", "待切片", 0, 0);

        var sl = ok(process.slides(new SlideReq(blockA, 2, "IHC", "CK7", null, ihc), doc1));
        List<Long> slideIds = new ArrayList<>();
        for (var s : rows(sl, "slides")) slideIds.add(idOf(s));
        assertEquals(2, slideIds.size());
        assertProgress(ihc, "SECTIONING", "切片中", 2, 0);

        ok(process.stain(slideIds.get(0), new StainReq("GOOD", null, null), doc1));
        assertProgress(ihc, "SECTIONING", "切片中", 2, 1);

        ok(process.stain(slideIds.get(1), new StainReq("GOOD", null, null), doc1));
        assertProgress(ihc, "STAINED", "已染色待确认", 2, 2);

        // slideId 反查：只回该切片挂接的那条医嘱；普通切片反查为空，且普通切片不进任何医嘱的计数
        var bySlide = ok(report.techOrders(a, "ALL", null, null, null, null, null, null, null, slideIds.get(0)));
        assertEquals(1, rows(bySlide, "items").size(), "slideId 反查应恰一行");
        assertEquals(ihc, idOf(rows(bySlide, "items").get(0)));
        assertEquals(slideIds.get(0).longValue(), asLong(bySlide.get("slideId")));
        var plain = ok(process.slides(new SlideReq(blockA, 1, "HE", null, null), doc1));
        long plainSlide = idOf(rows(plain, "slides").get(0));
        assertEquals(0, rows(ok(report.techOrders(a, "ALL", null, null, null, null, null, null, null, plainSlide)),
                "items").size(), "普通切片不挂任何医嘱，反查为空");
        assertProgress(ihc, "STAINED", "已染色待确认", 2, 2);

        var done = ok(report.doneTechOrder(ihc, doc1));
        assertEquals("DONE", done.get("progress"));
        assertEquals(2L, asLong(done.get("slideCount")));
        assertEquals(2L, asLong(done.get("stainedCount")));
        assertEquals(List.of(), done.get("warnings"), "染完再确认：没有告警");
        assertProgress(ihc, "DONE", "已完成", 2, 2);

        long pas = techOrder(a, blockA, "SPECIAL_STAIN", "PAS", null);
        assertProgress(pas, "PENDING_SECTION", "待切片", 0, 0);
        ok(report.cancelTechOrder(pas, new CancelTechOrderReq("不做了 " + tag), doc1));
        assertProgress(pas, "CANCELLED", "已取消", 0, 0);

        // 历史行（直连改库只改 status）：派生照状态走，不猜
        long legacy = techOrder(b, blockB, "RECUT", null, null);
        jdbc.update("update path_tech_order set status = 'CANCELLED' where id = ?", legacy);
        var legacyRow = techRow(ok(report.techOrders(b, null, null, null, null, null, null, null, null)), legacy);
        assertEquals("CANCELLED", legacyRow.get("progress"));
        assertNull(legacyRow.get("cancelled_at"), "历史取消行三列仍为 NULL（零回填）");
    }

    // =====================================================================================
    // ③ 完成校验 gate：off / warn / block × 0 片 / 染完 六种组合 + 坏配置 + 第二条缺口路径 + 既有守卫
    // =====================================================================================

    @Test
    void doneGateSixCombosAndBadConfig() {
        for (String gate : List.of("off", "warn", "block")) {
            for (boolean stained : List.of(false, true)) {
                String label = "gate=" + gate + (stained ? "/染完" : "/0片");
                assertEquals(1, setGate(gate), label + "：sys_config 里必须有这一行可改");
                long id = techOrder(a, blockA, "IHC", "T-" + gate + (stained ? "-S" : "-0"), null);
                if (stained) {
                    long slide = idOf(rows(ok(process.slides(new SlideReq(blockA, 1, "IHC", "T", null, id), doc1)), "slides").get(0));
                    ok(process.stain(slide, new StainReq("GOOD", null, null), doc1));
                }
                var r = report.doneTechOrder(id, doc1);
                boolean blocked = "block".equals(gate) && !stained;
                if (blocked) {
                    assertEquals(5273, r.getCode(), label + "：block 且无挂接切片应返 5273，实际 " + r.getMessage());
                    assertTrue(r.getMessage().contains("不得确认完成") && r.getMessage().contains("=block"),
                            label + "：5273 消息应说明 gate=block，实际 " + r.getMessage());
                    var row = techRowInDb(id);
                    assertEquals("ORDERED", row.get("status"), label + "：被拦后行仍 ORDERED");
                    assertNull(row.get("done_at"));
                    assertNull(row.get("done_by"));
                    assertNull(nodeOrNull(a, "TECH_DONE", id), label + "：被拦不写 TECH_DONE 节点");
                    continue;
                }
                assertEquals(0, r.getCode(), label + "：应放行，实际 " + r.getMessage());
                var body = r.getData();
                assertEquals("DONE", body.get("status"), label);
                assertEquals(gate, body.get("techDoneGate"), label + "：返回体回带生效档位");
                assertEquals(stained ? 1L : 0L, asLong(body.get("slideCount")), label + "：任何档位都带 slideCount 事实");
                assertEquals(stained ? 1L : 0L, asLong(body.get("stainedCount")), label + "：任何档位都带 stainedCount 事实");
                assertEquals(stained, body.get("stainedComplete"), label);
                @SuppressWarnings("unchecked")
                var warnings = (List<String>) body.get("warnings");
                assertNotNull(warnings, label + "：warnings 必须是数组（off 档也要给空数组）");
                var doneNode = node(a, "TECH_DONE", id);
                String remark = String.valueOf(doneNode.get("remark"));
                if ("warn".equals(gate) && !stained) {
                    assertEquals(1, warnings.size(), label + "：warn 且有缺口应恰一条告警：" + warnings);
                    assertTrue(warnings.get(0).startsWith("无已染色挂接切片即确认完成（gate=warn 放行）"),
                            label + "：告警文案，实际 " + warnings.get(0));
                    assertTrue(remark.contains("gate=warn 放行"), label + "：TECH_DONE 备注须写明放行，实际 " + remark);
                } else {
                    assertEquals(List.of(), warnings, label + "：不该有告警");
                    assertFalse(remark.contains("gate=warn"), label + "：备注不该提 warn 放行，实际 " + remark);
                }
                assertTrue(remark.contains("挂接 " + (stained ? 1 : 0) + " 片 / 已染色 " + (stained ? 1 : 0)),
                        label + "：备注带两个事实，实际 " + remark);
                assertEquals("DONE", techRowInDb(id).get("status"), label);
            }
        }

        // 坏配置 'blocked' → 按 warn：放行 + 告警
        assertEquals(1, setGate("blocked"));
        long bad = techOrder(a, blockA, "IHC", "T-bad", null);
        var badR = ok(report.doneTechOrder(bad, doc1));
        assertEquals("warn", badR.get("techDoneGate"), "坏配置回落 warn 而非 off");
        assertEquals(1, ((List<?>) badR.get("warnings")).size());
        assertEquals("DONE", badR.get("status"));

        // 5273 的第二条路径：有挂接切片但未全部染色
        assertEquals(1, setGate("block"));
        long partial = techOrder(a, blockA, "IHC", "T-partial", null);
        var slides = rows(ok(process.slides(new SlideReq(blockA, 2, "IHC", "T", null, partial), doc1)), "slides");
        ok(process.stain(idOf(slides.get(0)), new StainReq("GOOD", null, null), doc1));
        var partialR = report.doneTechOrder(partial, doc1);
        assertEquals(5273, partialR.getCode(), partialR.getMessage());
        assertTrue(partialR.getMessage().contains("1 片尚未染色"), "消息应指出未染色片数，实际 " + partialR.getMessage());
        assertEquals("ORDERED", techRowInDb(partial).get("status"));
        assertProgress(partial, "SECTIONING", "切片中", 2, 1);
        // 染完后同一档位放行
        ok(process.stain(idOf(slides.get(1)), new StainReq("GOOD", null, null), doc1));
        assertEquals(0, report.doneTechOrder(partial, doc1).getCode(), "block 档染完即放行");

        // 既有守卫不变：5268（不存在 / 非 ORDERED）、5270（完成人解析不出）——两者都不碰库、不写节点
        assertEquals(5268, report.doneTechOrder(-1L, doc1).getCode());
        assertEquals(5268, report.doneTechOrder(partial, doc1).getCode(), "已 DONE 再完成仍 5268");
        long guarded = techOrder(a, blockA, "IHC", "T-guard", null);
        var stranger = new UsernamePasswordAuthenticationToken(tag + "nobody", null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
        assertEquals(5270, report.doneTechOrder(guarded, stranger).getCode());
        assertEquals("ORDERED", techRowInDb(guarded).get("status"));
        assertNull(nodeOrNull(a, "TECH_DONE", guarded));
    }

    // =====================================================================================
    // ④ 种子：键在 sys_config、update 能改到、remark 不超 255、seed 形态是 on conflict do nothing
    // =====================================================================================

    @Test
    void gateKeyIsSeededAndWired() {
        assertEquals(1, gatePinned, "setUp 把 " + PathologyReportController.TECH_DONE_GATE_KEY
                + " 钉到 warn 的 update 必须影响 1 行——影响 0 行就是「建了开关没接电」（v53 emr.gate.timeliness 的老病）");
        Integer n = jdbc.queryForObject("select count(*) from sys_config where cfg_key = ?",
                Integer.class, PathologyReportController.TECH_DONE_GATE_KEY);
        assertEquals(1, n);
        Integer remarkLen = jdbc.queryForObject("select length(remark) from sys_config where cfg_key = ?",
                Integer.class, PathologyReportController.TECH_DONE_GATE_KEY);
        assertNotNull(remarkLen);
        assertTrue(remarkLen > 0 && remarkLen <= 255, "remark 须非空且 ≤ 255，实际 " + remarkLen);

        String v165 = stripSqlComments(read(V165));
        assertTrue(v165.contains("'emr.gate.pathology.techdone', 'warn'"), "V165 出厂值必须是 warn");
        assertTrue(v165.contains("on conflict (cfg_key) do nothing"), "seed 形态照抄 V161：on conflict (cfg_key) do nothing");
        assertEquals("emr.gate.pathology.techdone", PathologyReportController.TECH_DONE_GATE_KEY,
                "代码里读的键与 V165 seed 的键必须逐字相同");
    }

    // =====================================================================================
    // ⑤ 迁移扫描：V165 零 update、原 12 档全在；对照组抓得到；探针证明扫描器真的在咬
    // =====================================================================================

    /** 顶层 update 语句的语法形态：行首（允许缩进）update <表> set。不匹配裸 token——注释里「零条 update」这句话本身就含它 */
    private static final Pattern TOP_LEVEL_UPDATE = Pattern.compile("(?im)^\\s*update\\s+\\w+\\s+set\\b");

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

    @Test
    void migrationV165IsZeroBackfillAndKeepsOriginalNodes() {
        String raw = read(V165);
        String v165 = stripSqlComments(raw);
        assertTrue(v165.contains("drop constraint chk_path_process_node")
                        && v165.contains("add constraint chk_path_process_node check"),
                "读到的不是 V165 本尊（先 drop 再 add 同名约束）");
        for (String n : V144_NODES) {
            assertTrue(v165.contains("'" + n + "'"), "V144 原有档 '" + n + "' 必须仍在 V165 的白名单里");
        }
        for (String n : V165_NODES) {
            assertTrue(v165.contains("'" + n + "'"), "V165 新档 '" + n + "' 必须在白名单里");
        }
        assertTrue(raw.contains("零条 update"),
                "注释里必须写明零回填纪律——这句本身含 update 一词，正是剥注释要处理的活样本");
        assertFalse(hasTopLevelUpdate(raw), "V165 不许有任何 update 语句：历史医嘱不补节点、不改任何既有配置行");

        // 活的对照组：既有迁移里确实有 update，扫描器必须抓到——否则上面的绿不说明任何事
        assertTrue(hasTopLevelUpdate(read(CONTROL_WITH_UPDATE)),
                "对照组 " + CONTROL_WITH_UPDATE + " 确有 update md_drug set …，扫描器没抓到就是扫描器坏了");
    }

    @Test
    void zeroBackfillDetectorActuallyBites() {
        String v165 = read(V165);
        assertTrue(hasTopLevelUpdate(v165
                        + "\ninsert into path_process(specimen_id, node, occurred_at) select 1, 'TECH_ORDER', now();\n"
                        + "update path_tech_order set done_at = ordered_at where status = 'DONE';\n"),
                "补一条回填语句后必须被抓到");
        assertTrue(hasTopLevelUpdate(v165 + "\n    UPDATE sys_config SET cfg_value = 'block';\n"), "大小写与缩进不影响");
        assertFalse(hasTopLevelUpdate(v165 + "\n-- update path_process set node = 'TECH_ORDER';\n"), "行注释里的不算");
        assertFalse(hasTopLevelUpdate(v165 + "\n/* update path_process\n   set node = 'X'; */\n"), "块注释里的不算");
        assertFalse(hasTopLevelUpdate("comment on constraint chk_path_process_node on path_process is 'update set 字样';"),
                "裸 token 不算：须是行首 update <表> set 的语法形态");
    }

    // ==================== 断言与夹具 ====================

    /**
     * 直接 SQL 探针：往 path_process 插一条 node 为 {@code node} 的行，被 CHECK 拦住则返回 false。
     * 用 PL/pgSQL 的 EXCEPTION 子句（隐含子事务）吞掉 check_violation——直接让语句失败会把测试事务整个打成 aborted。
     * node 只来自本类的常量，不接调用方输入。
     */
    private boolean nodeAdmitted(long specimenId, String node) {
        String remark = "约束探针 " + node;
        jdbc.execute("""
                do $$ begin
                    insert into path_process(specimen_id, node, occurred_at, remark)
                    values (%d, '%s', now(), '%s');
                exception when check_violation then
                    null;
                end $$
                """.formatted(specimenId, node, remark));
        Long n = jdbc.queryForObject("select count(*) from path_process where specimen_id = ? and remark = ?",
                Long.class, specimenId, remark);
        return n != null && n == 1;
    }

    /** 两个清单分支 + 质控穿透三处同口径 */
    private void assertProgress(long id, String progress, String name, long slides, long stained) {
        var bySpecimen = techRow(ok(report.techOrders(specimenOf(id), null, null, null, null, null, null, null, null)), id);
        assertRow("标本清单", bySpecimen, progress, name, slides, stained);
        var wide = techRow(ok(report.techOrders(null, "ALL", null, null, pathNoOf(id), null, null, null, null)), id);
        assertRow("全院清单", wide, progress, name, slides, stained);
        assertRow("质控穿透", qcTechRow(id), progress, name, slides, stained);
    }

    private static void assertRow(String where, Map<String, Object> row, String progress, String name,
                                  long slides, long stained) {
        assertEquals(progress, row.get("progress"), where + " progress：" + row);
        assertEquals(name, row.get("progress_name"), where + " progress_name");
        assertEquals(slides, asLong(row.get("slide_count")), where + " slide_count");
        assertEquals(stained, asLong(row.get("stained_count")), where + " stained_count");
    }

    private static void assertRecent(Object ts, String what) {
        assertNotNull(ts, what + " 为空");
        OffsetDateTime at = ts instanceof OffsetDateTime o ? o
                : ts instanceof java.sql.Timestamp t ? t.toInstant().atOffset(java.time.ZoneOffset.UTC)
                : fail(what + " 类型不是时间：" + ts.getClass());
        Duration drift = Duration.between(at, OffsetDateTime.now()).abs();
        assertTrue(drift.compareTo(Duration.ofMinutes(5)) <= 0,
                what + " 应在本次测试时刻附近（库端 now()），实际偏差 " + drift);
    }

    private int setGate(String value) {
        configReader.evictAll();
        int n = jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?",
                value, PathologyReportController.TECH_DONE_GATE_KEY);
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

    /** 一条门诊医嘱 + 一份已核收标本；时刻由 SQL 表达式现算，不写字面量 */
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

    private Map<String, Object> techRowInDb(long id) {
        return jdbc.queryForMap("select status, done_at, done_by from path_tech_order where id = ?", id);
    }

    private long techNodes(long specimenId) {
        Long n = jdbc.queryForObject("""
                select count(*) from path_process
                where specimen_id = ? and node in ('TECH_ORDER', 'TECH_DONE', 'TECH_CANCEL')
                """, Long.class, specimenId);
        return n == null ? 0L : n;
    }

    /** 该医嘱的某类节点（按 remark 里的「#id 」定位；「#12 」不会误配「#123 」） */
    private Map<String, Object> nodeOrNull(long specimenId, String node, long techOrderId) {
        var rows = jdbc.queryForList("""
                select operator_id, remark, occurred_at from path_process
                where specimen_id = ? and node = ? and remark like ?
                order by id desc
                """, specimenId, node, "%#" + techOrderId + " %");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> node(long specimenId, String node, long techOrderId) {
        var r = nodeOrNull(specimenId, node, techOrderId);
        assertNotNull(r, "找不到医嘱 #" + techOrderId + " 的 " + node + " 节点");
        return r;
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
