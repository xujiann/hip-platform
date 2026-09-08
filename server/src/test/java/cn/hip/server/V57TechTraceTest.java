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
import cn.hip.platform.core.config.BusinessDates;
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
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * v57 车道 C：<b>特检技术医嘱取消留痕 + 切片挂接医嘱</b>（2563 二次核账坐实的地基缺列，V163）。
 *
 * <h2>每条断言钉住的「修复前的反向事实」</h2>
 * <ol>
 *   <li>{@link #cancelRejectsMissingBlankAndOverlongReasonAndLeavesRowOrdered()}：旧端点不收 body，
 *       无原因照样把行置 CANCELLED——现在三条非法路径同返 5271 且<b>一个字节不落库</b>。</li>
 *   <li>{@link #cancelWithReasonWritesFullTraceAndKeepsOrderReason()}：旧表没有 cancelled_at / cancelled_by /
 *       cancel_reason（查这三列直接 SQL 报错）——现在三列与 status 同一条 update 落库，
 *       下达原因 {@code reason} 原值不变，两个清单分支与质控穿透都带四列。</li>
 *   <li>{@link #slidesAttachToTechOrderOnlyWhenLegal()}：旧 {@code SlideReq} 没有 techOrderId，切片与医嘱互不认识——
 *       现在三条非法路径同返 5272 且一张片不插；合法则每张片的 tech_order_id 都等于它，医嘱<b>不自动置 DONE</b>。</li>
 *   <li>{@link #legacyCancelledRowsStayListedWithNullTrace()}：零回填——直接 SQL 造一条只改 status 的历史取消，
 *       清单仍返回它，三列就是 NULL（不拿 ordered_at 冒充取消时刻）。</li>
 *   <li>{@link #migrationV163IsZeroBackfill()} / {@link #zeroBackfillDetectorActuallyBites()}：V163 剥注释后
 *       无 {@code update <表> set} 形态；对照组 V22 确有 update 且扫描器抓得到；探针证明扫描器真的在咬。</li>
 *   <li>{@link #slideReqStillDeserializesWithAndWithoutTechOrderId()}：记录类多了一个兼容构造器后，
 *       JSON 反序列化仍走六参规范构造器——旧五键 JSON 的 techOrderId 为 null。</li>
 * </ol>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：夹具时刻由 SQL 按 {@code now()} 现算；取消时刻用「与 JVM 此刻偏差 ≤ 5 分钟」的容差断言
 * （库端 now() 是事务开始时刻，与 JVM 时钟不逐位相等）；质控穿透的日期窗由 {@link BusinessDates#today()} 派生并
 * 两端各留一天余量——UTC 的 JVM 与业务时区的库对「今天」的分歧不该让本类变红。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V57TechTraceTest {

    @Autowired PathologyReportController report;
    @Autowired PathologyProcessController process;
    @Autowired PathQcController pathQc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private static final String V163 = "server/src/main/resources/db/migration/V163__path_tech_trace.sql";
    /** 对照组：确实含顶层 update 语句的既有迁移（V22 第 40 行 {@code update md_drug set abx_level = 1 where antibiotic;}） */
    private static final String CONTROL_WITH_UPDATE = "server/src/main/resources/db/migration/V22__phase25_mgmt.sql";

    private String tag;
    private Long regId;
    private Authentication doc1;
    private Authentication doc2;

    private long a;
    private long b;
    private long blockA;
    private long ihcOnA;      // IHC CK7，挂在 blockA，ORDERED
    private long ihcOnB;      // IHC Ki-67，挂在 B（无蜡块），ORDERED
    private String reasonOnA; // ihcOnA 的下达原因——取消后必须原值不变

    @BeforeEach
    void setUp() {
        tag = "V57" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");
        doc2 = doctorAuth(tag + "d2");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "特检留痕测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "特检留痕患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);

        a = specimen("A");
        b = specimen("B");
        blockA = gross(a, "肿物中心 " + tag);
        gross(b, "切缘 " + tag);

        reasonOnA = "HE 见腺样结构，查 CK7 定来源 " + tag;
        ihcOnA = techOrder(a, blockA, "IHC", "CK7", reasonOnA);
        ihcOnB = techOrder(b, null, "IHC", "Ki-67", "增殖指数 " + tag);
    }

    // =====================================================================================
    // ① 取消：原因非法三路径同返 5271，且行一个字节不动
    // =====================================================================================

    @Test
    void cancelRejectsMissingBlankAndOverlongReasonAndLeavesRowOrdered() {
        assertEquals(5271, report.cancelTechOrder(ihcOnA, null, doc1).getCode(), "无 body");
        assertEquals(5271, report.cancelTechOrder(ihcOnA, new CancelTechOrderReq(null), doc1).getCode(), "reason 缺失");
        assertEquals(5271, report.cancelTechOrder(ihcOnA, new CancelTechOrderReq(" \t  "), doc1).getCode(), "空白");
        assertEquals(5271, report.cancelTechOrder(ihcOnA, new CancelTechOrderReq("原".repeat(256)), doc1).getCode(),
                "256 字（上限 255）");

        var row = techRowInDb(ihcOnA);
        assertEquals("ORDERED", row.get("status"), "四次被拒后行必须仍是 ORDERED——5271 路径不许碰库");
        assertNull(row.get("cancelled_at"));
        assertNull(row.get("cancelled_by"));
        assertNull(row.get("cancel_reason"));
        assertEquals(reasonOnA, row.get("reason"));

        // 5268 路径保留：不存在的 id
        assertEquals(5268, report.cancelTechOrder(-1L, new CancelTechOrderReq("不存在的医嘱"), doc1).getCode());
    }

    // =====================================================================================
    // ② 取消：三列与 status 同一条 update 落库，下达原因原值不变，清单与穿透都带列
    // =====================================================================================

    @Test
    void cancelWithReasonWritesFullTraceAndKeepsOrderReason() {
        // 恰 255 字：上限含边界
        String boundary = "因".repeat(255);
        var edge = ok(report.cancelTechOrder(ihcOnB, new CancelTechOrderReq(boundary), doc2));
        assertEquals("CANCELLED", edge.get("status"));
        assertEquals(boundary, edge.get("cancel_reason"));

        String why = "临床改送外院会诊 " + tag;
        var r = ok(report.cancelTechOrder(ihcOnA, new CancelTechOrderReq("  " + why + "  "), doc2));
        assertEquals("CANCELLED", r.get("status"));
        assertEquals(why, r.get("cancel_reason"), "取消原因两端空白剥掉后入库");
        assertEquals(reasonOnA, r.get("reason"), "下达原因 reason 一个字节不动");
        assertEquals(userId(tag + "d2").longValue(), asLong(r.get("cancelled_by")));
        assertNotNull(r.get("cancelled_at"));
        assertFalse(r.containsKey("note"), "「未留痕」的 note 必须删掉——留痕了还说没留是在误导调用方");

        var row = techRowInDb(ihcOnA);
        assertEquals("CANCELLED", row.get("status"));
        assertEquals(reasonOnA, row.get("reason"));
        assertEquals(why, row.get("cancel_reason"));
        assertEquals(userId(tag + "d2").longValue(), asLong(row.get("cancelled_by")));
        OffsetDateTime cancelledAt = jdbc.queryForObject(
                "select cancelled_at from path_tech_order where id = ?", OffsetDateTime.class, ihcOnA);
        assertNotNull(cancelledAt);
        Duration drift = Duration.between(cancelledAt, OffsetDateTime.now()).abs();
        assertTrue(drift.compareTo(Duration.ofMinutes(5)) <= 0,
                "cancelled_at 应在本次测试时刻附近（库端 now()），实际偏差 " + drift);

        // 重复取消 / 取消后再完成 → 5268（路径保留）
        assertEquals(5268, report.cancelTechOrder(ihcOnA, new CancelTechOrderReq("再取消一次"), doc2).getCode());
        assertEquals(5268, report.doneTechOrder(ihcOnA, doc2).getCode());

        // 标本分支
        var bySpecimen = techRow(ok(report.techOrders(a, null, null, null, null, null, null, null, null)), ihcOnA);
        assertEquals("CANCELLED", bySpecimen.get("status"));
        assertNotNull(bySpecimen.get("cancelled_at"));
        assertEquals(tag + "d2医生", bySpecimen.get("cancelled_by_name"));
        assertEquals(why, bySpecimen.get("cancel_reason"));
        assertEquals(0L, asLong(bySpecimen.get("slide_count")));

        // 全院分支（显式 CANCELLED 档 + 病理号关键词）
        var wide = techRow(ok(report.techOrders(null, "CANCELLED", null, null, pathNo("A"), null, null, null, null)), ihcOnA);
        assertNotNull(wide.get("cancelled_at"));
        assertEquals(tag + "d2医生", wide.get("cancelled_by_name"));
        assertEquals(why, wide.get("cancel_reason"));

        // 质控穿透 WORKLOAD_TECH：同样只增列
        var qcRow = qcTechRow(ihcOnA);
        assertNotNull(qcRow.get("cancelled_at"));
        assertEquals(tag + "d2医生", qcRow.get("cancelled_by_name"));
        assertEquals(why, qcRow.get("cancel_reason"));
        assertEquals(0L, asLong(qcRow.get("slide_count")));
    }

    // =====================================================================================
    // ③ 切片挂接：三条非法路径同返 5272 且一张片不插；合法则逐张挂上、医嘱不自动 DONE
    // =====================================================================================

    @Test
    void slidesAttachToTechOrderOnlyWhenLegal() {
        // 他标本的医嘱：ihcOnB 属于 B，蜡块 blockA 属于 A
        var foreign = process.slides(new SlideReq(blockA, 1, "IHC", "Ki-67", null, ihcOnB), doc1);
        assertEquals(5272, foreign.getCode(), foreign.getMessage());

        // 已取消的医嘱
        long cancelled = techOrder(a, blockA, "DEEP_CUT", null, "深切 " + tag);
        assertEquals(0, report.cancelTechOrder(cancelled, new CancelTechOrderReq("不需要了 " + tag), doc1).getCode());
        assertEquals(5272, process.slides(new SlideReq(blockA, 1, "HE", null, null, cancelled), doc1).getCode());

        // 已完成的医嘱同样不是 ORDERED
        long done = techOrder(a, blockA, "RECUT", null, null);
        assertEquals(0, report.doneTechOrder(done, doc1).getCode());
        assertEquals(5272, process.slides(new SlideReq(blockA, 1, "HE", null, null, done), doc1).getCode());

        // 不存在的 id
        assertEquals(5272, process.slides(new SlideReq(blockA, 1, "HE", null, null, -1L), doc1).getCode());

        assertEquals(0L, slidesOf(blockA, null), "四次 5272 一张片都不许插");
        assertEquals(0L, sectionNodes(a), "被拒的切片不留 SECTION 节点");

        // 合法：每张新切片 tech_order_id 都等于它
        var okBody = ok(process.slides(new SlideReq(blockA, 3, "IHC", "CK7", "免疫组化加做", ihcOnA), doc1));
        assertEquals(ihcOnA, asLong(okBody.get("techOrderId")));
        var slides = rows(okBody, "slides");
        assertEquals(3, slides.size());
        for (var s : slides) {
            assertEquals(ihcOnA, asLong(s.get("tech_order_id")), "返回行须带 tech_order_id：" + s);
        }
        assertEquals(3L, slidesOf(blockA, ihcOnA));
        assertEquals("ORDERED", techRowInDb(ihcOnA).get("status"), "挂接不等于完成——完成仍走 /done 由技师确认");

        String remark = jdbc.queryForObject("""
                select remark from path_process
                where specimen_id = ? and node = 'SECTION'
                order by id desc limit 1
                """, String.class, a);
        assertNotNull(remark);
        assertTrue(remark.contains("特检医嘱#" + ihcOnA + " IHC"), "SECTION 备注应带「特检医嘱#id 类型」，实际：" + remark);

        assertEquals(3L, asLong(techRow(ok(report.techOrders(a, null, null, null, null, null, null, null, null)), ihcOnA)
                .get("slide_count")), "标本清单 slide_count 应等于挂接到该医嘱的切片数");
        assertEquals(3L, asLong(qcTechRow(ihcOnA).get("slide_count")), "质控穿透 slide_count 同口径");

        // 不传（旧五参形态）→ NULL，旧契约不变；已挂接的计数不受影响
        var plain = ok(process.slides(new SlideReq(blockA, 2, "HE", null, null), doc1));
        assertNull(plain.get("techOrderId"));
        assertEquals(2, rows(plain, "slides").size());
        for (var s : rows(plain, "slides")) assertNull(s.get("tech_order_id"), "普通切片 tech_order_id 必须为 NULL：" + s);
        assertEquals(2L, (long) jdbc.queryForObject(
                "select count(*) from path_slide where block_id = ? and tech_order_id is null", Long.class, blockA));
        assertEquals(3L, slidesOf(blockA, ihcOnA));
    }

    // =====================================================================================
    // ④ 零回填：历史取消行照常在清单里，三列就是 NULL
    // =====================================================================================

    @Test
    void legacyCancelledRowsStayListedWithNullTrace() {
        // 直接 SQL 模拟 V163 之前的取消：只有 status，三列根本没采集
        jdbc.update("update path_tech_order set status = 'CANCELLED' where id = ?", ihcOnB);

        var row = techRow(ok(report.techOrders(b, null, null, null, null, null, null, null, null)), ihcOnB);
        assertEquals("CANCELLED", row.get("status"));
        assertTrue(row.containsKey("cancelled_at") && row.containsKey("cancelled_by_name")
                && row.containsKey("cancel_reason"), "列必须在（值为 NULL），不是整列消失：" + row.keySet());
        assertNull(row.get("cancelled_at"), "历史取消行不许拿任何既有时刻冒充取消时刻");
        assertNull(row.get("cancelled_by_name"));
        assertNull(row.get("cancel_reason"));
        assertEquals(0L, asLong(row.get("slide_count")));

        var wide = techRow(ok(report.techOrders(null, "CANCELLED", null, null, pathNo("B"), null, null, null, null)), ihcOnB);
        assertNull(wide.get("cancelled_at"));
        assertNull(wide.get("cancel_reason"));

        var qcRow = qcTechRow(ihcOnB);
        assertEquals("CANCELLED", qcRow.get("status"));
        assertTrue(qcRow.containsKey("cancelled_at") && qcRow.containsKey("cancel_reason"));
        assertNull(qcRow.get("cancelled_at"));
        assertNull(qcRow.get("cancelled_by_name"));
    }

    // =====================================================================================
    // ⑤ 迁移扫描：V163 零 update；对照组抓得到；探针证明扫描器真的在咬
    // =====================================================================================

    /** 顶层 update 语句的语法形态：行首（允许缩进）update <表> set。不匹配裸 token——注释里「零条 update」这句话本身就含它 */
    private static final Pattern TOP_LEVEL_UPDATE = Pattern.compile("(?im)^\\s*update\\s+\\w+\\s+set\\b");

    /** 先剥块注释（斜杠星 … 星斜杠），再剥每行 -- 之后的部分 */
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
    void migrationV163IsZeroBackfill() {
        String v163 = read(V163);
        assertTrue(v163.contains("alter table path_tech_order add column cancelled_at")
                        && v163.contains("alter table path_tech_order add column cancel_reason")
                        && v163.contains("alter table path_slide add column tech_order_id")
                        && v163.contains("where tech_order_id is not null"),
                "读到的不是 V163 本尊（三列 + tech_order_id + 部分索引）");
        assertTrue(v163.contains("零条 update"),
                "注释里必须写明零回填纪律——这句本身含 update 一词，正是剥注释要处理的活样本");
        assertFalse(hasTopLevelUpdate(v163), "V163 不许有任何 update 语句：历史 CANCELLED 行三列必须保持 NULL");

        // 活的对照组：既有迁移里确实有 update，扫描器必须抓到——否则上面的绿不说明任何事
        String control = read(CONTROL_WITH_UPDATE);
        assertTrue(hasTopLevelUpdate(control),
                "对照组 " + CONTROL_WITH_UPDATE + " 确有 update md_drug set …，扫描器没抓到就是扫描器坏了");
    }

    @Test
    void zeroBackfillDetectorActuallyBites() {
        String v163 = read(V163);
        assertTrue(hasTopLevelUpdate(v163
                        + "\nupdate path_tech_order set cancelled_at = ordered_at where status = 'CANCELLED';\n"),
                "补一条回填语句后必须被抓到");
        assertTrue(hasTopLevelUpdate(v163 + "\n    UPDATE path_slide SET tech_order_id = 1;\n"), "大小写与缩进不影响");
        assertFalse(hasTopLevelUpdate(v163 + "\n-- update path_tech_order set cancelled_at = now();\n"), "行注释里的不算");
        assertFalse(hasTopLevelUpdate(v163 + "\n/* update path_tech_order\n   set cancelled_at = now(); */\n"),
                "块注释里的不算");
        assertFalse(hasTopLevelUpdate("comment on column path_tech_order.cancel_reason is 'update set 之类的字样';"),
                "裸 token 不算：须是行首 update <表> set 的语法形态");
    }

    // =====================================================================================
    // ⑥ 记录类多了兼容构造器后，JSON 反序列化仍走规范构造器
    // =====================================================================================

    @Test
    void slideReqStillDeserializesWithAndWithoutTechOrderId() throws Exception {
        SlideReq with = objectMapper.readValue(
                "{\"blockId\":7,\"count\":2,\"stainType\":\"IHC\",\"stainItem\":\"CK7\",\"techOrderId\":99}",
                SlideReq.class);
        assertEquals(7L, with.blockId());
        assertEquals(2, with.count());
        assertEquals(99L, with.techOrderId());

        SlideReq without = objectMapper.readValue("{\"blockId\":7,\"count\":2,\"stainType\":\"HE\"}", SlideReq.class);
        assertEquals(7L, without.blockId());
        assertNull(without.techOrderId(), "旧五键 JSON → techOrderId 为 null（旧契约不变）");
    }

    // ==================== 夹具与取值 ====================

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

    /** 取材一块，返回蜡块 id */
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
        return jdbc.queryForMap(
                "select status, reason, cancelled_at, cancelled_by, cancel_reason from path_tech_order where id = ?", id);
    }

    private long slidesOf(long blockId, Long techOrderId) {
        Long n = techOrderId == null
                ? jdbc.queryForObject("select count(*) from path_slide where block_id = ?", Long.class, blockId)
                : jdbc.queryForObject("select count(*) from path_slide where block_id = ? and tech_order_id = ?",
                        Long.class, blockId, techOrderId);
        return n == null ? 0L : n;
    }

    private long sectionNodes(long specimenId) {
        Long n = jdbc.queryForObject(
                "select count(*) from path_process where specimen_id = ? and node = 'SECTION'", Long.class, specimenId);
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
