package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.medtech.service.SurgeryService;
import cn.hip.medtech.service.SurgeryService.CancelReq;
import cn.hip.medtech.web.MedTechController;
import cn.hip.medtech.web.MedTechController.SurgeryDoneReq;
import cn.hip.medtech.web.MedTechController.SurgeryReq;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v49 车道 Q4 回归：取消手术缺「取消时刻」列（技术偏离表 1426★ 取消手术四阶段构成）。
 *
 * <h3>这条缺陷是什么</h3>
 * {@code inp_surgery} 自建表起没有任何一列记录"什么时候取消的"：V140 加了
 * {@code cancel_stage}（哪个阶段取消）与 {@code cancel_reason}（为什么取消），
 * 唯独没有取消时刻。于是 1426★ 只能挂 {@code AnesQcController.ANCHOR} 这个全局手术锚点
 * {@code coalesce(start_at, in_room_at, scheduled_at, created_at)}——
 * 取消的台次四个时间点全空（取消了就没入室没开台），锚点实际退化成<b>排台日/建单日</b>。
 * 一台 9 月 1 日排台、9 月 20 日才取消的手术，因此算进 9 月 1 日那一周而不是取消发生的那一周。
 * §③ 用一个 30 天的落差把这个分歧钉成断言：<b>两个日期确实不同</b>，
 * 所以"改挂 cancelled_at"是真的口径变更，不是等价重写。
 *
 * <h3>本类断言的四件事</h3>
 * <ol>
 *   <li><b>§① 列形状</b>：{@code cancelled_at} 是 timestamptz、<b>可空</b>、
 *       未给 inp_surgery 引入任何 CHECK（同 V140 纪律二：白名单落写侧，
 *       试点库历史脏值挡住 Flyway 的代价远高于脏数据）；部分索引在位。</li>
 *   <li><b>§② 零回填</b>：迁移脚本剥掉注释后<b>一条 update 都没有</b>，
 *       且模拟出来的"V146 之前就已取消"的历史行 {@code cancelled_at} 保持 NULL。
 *       最诱人的歧路是拿 {@code updated_at} 反推——它是最后一次改动，
 *       取消之后任何一次术中信息维护都会把它推后，回填只会得到一列
 *       "看上去列列有值、条条可疑"的假数据。NULL 会逼统计层标注分母，假值不会。</li>
 *   <li><b>§③ 写侧</b>：只有真正成功的取消才落 {@code cancelled_at}；
 *       校验失败（4907）、已完成不可取消（4900）、重复取消（4900）三条路径
 *       一律不写、不覆盖。时刻<b>截断到微秒</b>——PG timestamptz 只存微秒且四舍五入，
 *       不截断的写入值与回读值会差最多 500ns（本仓已因此炸过）。</li>
 *   <li><b>§④ 契约不动</b>：既有 {@code GET /api/inpatient/surgeries} 的键集合
 *       与既有 complete 端点行为一字未改——本车道只加了一列与一次写入。</li>
 * </ol>
 *
 * <p><b>本类不改 AnesQcController。</b> 统计侧怎么用这一列（按取消日归集、
 * NULL 行单列一档"取消日期未采集"、汇总与明细同时改口径并在 caveat 里写清）
 * 由主控裁决，见本车道 cross_lane。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "TECHNICIAN"})
class V49SurgeryCancelledAtTest {

    @Autowired MedTechController medTech;
    @Autowired SurgeryService surgeryService;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;

    private final Authentication auth = new UsernamePasswordAuthenticationToken("user", null, List.of());

    // ================= 夹具（与 V46SurgeryBaseTest 同款：床位自给自足，不与别的测试类抢床） =================

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        return inpatientService.admit(pid, 1L, freeBed(), null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
    }

    private Long freeBed() {
        var ids = jdbc.queryForList("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        if (!ids.isEmpty()) return ids.get(0);
        Long wardId = jdbc.queryForObject("select ward_id from inp_bed limit 1", Long.class);
        return jdbc.queryForObject(
                "insert into inp_bed(ward_id, bed_no, status) values (?, ?, 'FREE') returning id",
                Long.class, wardId, "T" + (System.nanoTime() % 100000000L));
    }

    /** 走既有 POST 端点建台，不直接 insert——顺带证明既有写路径没被本车道碰过 */
    private Long newSurgery(Long admId, String procedure, String scheduledAt) {
        var r = medTech.requestSurgery(new SurgeryReq(admId, procedure, "全身麻醉", scheduledAt, null), auth);
        assertEquals(0, r.getCode(), "既有手术申请端点应成功：" + r.getMessage());
        return jdbc.queryForObject(
                "select id from inp_surgery where admission_id = ? order by id desc limit 1", Long.class, admId);
    }

    private Map<String, Object> rowOf(Long id) {
        return jdbc.queryForMap("select * from inp_surgery where id = ?", id);
    }

    /** 驱动可能给回 Timestamp 或 OffsetDateTime，两种都认（本仓 SurgeryService.toInstant 同款容忍） */
    private static Instant instantOf(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ts.toInstant();
        if (v instanceof OffsetDateTime odt) return odt.toInstant();
        if (v instanceof Instant i) return i;
        if (v instanceof java.util.Date d) return d.toInstant();
        return fail("cancelled_at 回读成了意料之外的类型：" + v.getClass());
    }

    // ================= ① 列形状 =================

    /**
     * <b>{@code cancelled_at} 必须可空。</b> 历史取消行永远为空——那是事实，当时没采集；
     * 加 NOT NULL 就等于逼出一次回填，而回填就是伪造。
     */
    @Test
    void cancelledAtColumnIsNullableTimestamptzWithoutCheck() {
        var col = jdbc.queryForMap("""
                select data_type, is_nullable, column_default
                from information_schema.columns
                where table_name = 'inp_surgery' and column_name = 'cancelled_at'
                """);
        assertEquals("timestamp with time zone", col.get("data_type"),
                "与 scheduled_at / 四个时间点 / created_at 同型，时区语义一致");
        assertEquals("YES", col.get("is_nullable"),
                "必须可空：历史取消行的 cancelled_at 必然为空，不许回填伪造");
        assertNull(col.get("column_default"), "不给默认值——默认值会让历史行凭空拿到一个假时刻");

        Integer checks = jdbc.queryForObject("""
                select count(*) from pg_constraint
                where conrelid = 'inp_surgery'::regclass and contype = 'c'
                """, Integer.class);
        assertEquals(0, checks, "inp_surgery 仍不得有 CHECK 约束（V140 纪律二：白名单落写侧）");

        Integer idx = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'inp_surgery' and indexname = 'idx_inp_surgery_cancelled_at'
                """, Integer.class);
        assertEquals(1, idx, "1426★ 改按取消日归集后要走 cancelled_at 的窗口谓词，部分索引必须在位");
    }

    // ================= ② 零回填 =================

    /**
     * <b>迁移脚本里剥掉注释后一条 update 都没有。</b>
     *
     * <p>这条断言看着像洁癖，实际是本仓被反复踩过的那条线（v41 床位效率、v42 archived_at、
     * V140 四时间点各守过一次）：新列一旦允许"顺手回填一下让老数据好看"，
     * 统计层就再也分不清哪些是真值。剥注释是必要的——迁移注释里正好在讲
     * "为什么不能拿 updated_at 反推"，字面上带着 update 三个字母。
     */
    @Test
    void migrationContainsZeroUpdateStatements() throws Exception {
        String raw = new String(new ClassPathResource("db/migration/V146__anesqc_caliber.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String code = Arrays.stream(raw.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"))
                .toLowerCase();
        assertFalse(code.contains("update "), "迁移里零条 update：历史取消行的 cancelled_at 永远是 NULL");
        assertFalse(code.contains("updated_at"), "严禁拿 updated_at 反推取消时刻");
        assertTrue(code.contains("add column cancelled_at timestamptz"), "迁移应只加一列");
    }

    /**
     * <b>模拟"V146 之前就已取消"的历史行：cancelled_at 保持 NULL，且照样进 1426★ 的分阶段计数。</b>
     *
     * <p>直接 SQL 造出这种行（绕开写侧），因为走 {@code cancel()} 现在一定会落时刻——
     * 而线上确实存在一批只有 {@code cancel_stage} 没有 {@code cancelled_at} 的历史取消。
     * 统计层必须把它们单列成「取消日期未采集」一档并标注分母，
     * <b>不许静默并进锚点口径</b>——那是把新旧两种口径混在一个数里。
     */
    @Test
    void legacyCancelledRowKeepsNullCancelledAt() {
        Long admId = admit("历史取消");
        Long sid = newSurgery(admId, "历史取消术式", null);
        jdbc.update("""
                update inp_surgery set status = 'CANCELLED', cancel_stage = 'SCHEDULE',
                       cancel_reason = 'V146 之前取消的历史行'
                where id = ?
                """, sid);

        var row = rowOf(sid);
        assertEquals("CANCELLED", row.get("status"));
        assertEquals("SCHEDULE", row.get("cancel_stage"));
        assertNull(row.get("cancelled_at"), "历史取消行的 cancelled_at 必须仍是 NULL——那就是事实");

        // 分阶段计数（1426★ 的取数形状）不受影响：这一行照样算得进去
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from inp_surgery
                where id = ? and (cancel_stage is not null or coalesce(status, '') = 'CANCELLED')
                """, Integer.class, sid), "缺取消时刻不影响它进四阶段构成");
    }

    // ================= ③ 写侧 =================

    /**
     * <b>成功取消才落时刻，且落的是微秒精度的当前时刻。</b>
     *
     * <p>{@code Instant.now()} 在本平台是 100ns 粒度，PG timestamptz 只存微秒并<b>四舍五入</b>，
     * 尾数 ≥500ns 会向上进位——回读值比写入值晚最多 500ns。本仓已因这类精度/时区问题炸过四次，
     * 所以写入前一律 {@code truncatedTo(MICROS)}，回读值与写入值恒等。
     */
    @Test
    void successfulCancelStampsMicrosecondTruncatedNow() {
        Long admId = admit("取消落时刻");
        Long sid = newSurgery(admId, "取消落时刻术式", null);

        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertEquals(0, surgeryService.cancel(sid, new CancelReq("PRE_IN", "患者血压过高")).getCode());
        Instant after = Instant.now();

        Instant at = instantOf(rowOf(sid).get("cancelled_at"));
        assertNotNull(at, "成功取消必须落 cancelled_at");
        assertFalse(at.isBefore(before), "取消时刻不能早于调用前：" + at + " < " + before);
        assertFalse(at.isAfter(after), "取消时刻不能晚于调用后：" + at + " > " + after);
        assertEquals(0, at.getNano() % 1000,
                "必须截断到微秒——留亚微秒尾数会被 PG 四舍五入成与写入值不等的回读值");
    }

    /** 四个阶段各取消一台，四条都带取消时刻——1426★ 每一档都能按取消日归集 */
    @Test
    void allFourStagesGetCancelledAt() {
        Long admId = admit("四阶段时刻");
        for (String stage : List.of("APPLY", "SCHEDULE", "PRE_IN", "IN_OP")) {
            Long sid = newSurgery(admId, "四阶段时刻" + stage, null);
            assertEquals(0, surgeryService.cancel(sid, new CancelReq(stage, stage + " 阶段取消原因")).getCode());
            assertNotNull(rowOf(sid).get("cancelled_at"), stage + " 阶段取消也必须落时刻");
        }
        assertEquals(4, jdbc.queryForObject("""
                select count(*) from inp_surgery
                where admission_id = ? and status = 'CANCELLED' and cancelled_at is not null
                """, Integer.class, admId));
    }

    /**
     * <b>被拒的取消一律不落时刻。</b> 校验失败（4907）与"已完成不可取消"（4900）
     * 两条路径都必须保持 cancelled_at 为 NULL——否则一台从没被取消的手术会带着取消时刻
     * 出现在按取消日归集的报表里。
     */
    @Test
    void rejectedCancelLeavesCancelledAtNull() {
        Long admId = admit("取消被拒");

        Long bad = newSurgery(admId, "取消被拒术式", null);
        assertEquals(4907, surgeryService.cancel(bad, new CancelReq("BEFORE_ANES", "阶段拼错了")).getCode());
        assertEquals(4907, surgeryService.cancel(bad, new CancelReq("SCHEDULE", "  ")).getCode());
        var badRow = rowOf(bad);
        assertEquals("REQUESTED", badRow.get("status"), "校验失败不得改状态");
        assertNull(badRow.get("cancelled_at"), "校验失败不得落取消时刻");

        // 已 DONE 的手术不可取消（V46 §② 那条硬规矩），同样不得落时刻
        Long done = newSurgery(admId, "已完成术式", null);
        assertEquals(0, medTech.completeSurgery(done, new SurgeryDoneReq("手术记录正文", "麻醉记录正文")).getCode());
        assertEquals(4900, surgeryService.cancel(done, new CancelReq("IN_OP", "术中改期")).getCode());
        var doneRow = rowOf(done);
        assertEquals("DONE", doneRow.get("status"));
        assertNull(doneRow.get("cancelled_at"), "已完成手术取消被拒，不得落取消时刻");
    }

    /**
     * <b>重复取消返 4900，首次的取消时刻一字不改。</b>
     * 取消的 update 带 {@code status in ('REQUESTED','SCHEDULED')} 条件，
     * 已 CANCELLED 的行进不来，所以 cancelled_at 一经写入就是"第一次也是唯一一次取消"的时刻。
     */
    @Test
    void secondCancelDoesNotOverwriteFirstStamp() throws Exception {
        Long admId = admit("重复取消");
        Long sid = newSurgery(admId, "重复取消术式", null);

        assertEquals(0, surgeryService.cancel(sid, new CancelReq("SCHEDULE", "患者术前发热")).getCode());
        Instant first = instantOf(rowOf(sid).get("cancelled_at"));
        assertNotNull(first);

        Thread.sleep(5);
        assertEquals(4900, surgeryService.cancel(sid, new CancelReq("PRE_IN", "再取消一次")).getCode());

        var row = rowOf(sid);
        assertEquals(first, instantOf(row.get("cancelled_at")), "重复取消不得覆盖首次取消时刻");
        assertEquals("SCHEDULE", row.get("cancel_stage"), "重复取消也不得改阶段");
        assertEquals("患者术前发热", row.get("cancel_reason"));
    }

    /**
     * <b>缺陷本身的证据：取消日 ≠ 全局手术锚点日。</b>
     *
     * <p>一台 30 天前排台的手术今天取消：{@code AnesQcController.ANCHOR}
     * {@code coalesce(start_at, in_room_at, scheduled_at, created_at)} 落在 30 天前
     * （取消的台次四时间点全空，锚点退化成排台日），而实际取消发生在今天。
     * 两个日期在这里相差 30 天——所以改挂 {@code cancelled_at} 是<b>真的口径变更</b>，
     * 不是等价重写，必须同时改汇总与明细并在 caveat 里写清（见 cross_lane）。
     *
     * <p>日期都在<b>数据库会话时区</b>（Hikari connection-init-sql 钉死 Asia/Shanghai）里算，
     * 两侧同口径，因此 JVM 跑在 UTC 还是 UTC+8 都不影响这条断言。
     */
    @Test
    void cancelDayDiffersFromGlobalSurgeryAnchorDay() {
        Long admId = admit("锚点分歧");
        Long sid = newSurgery(admId, "锚点分歧术式", null);
        // 排台到 30 天前（不碰四个时间点：取消的台次本来就没有入室/开台）
        jdbc.update("update inp_surgery set scheduled_at = now() - interval '30 days', status = 'SCHEDULED' where id = ?", sid);

        assertEquals(0, surgeryService.cancel(sid, new CancelReq("SCHEDULE", "患者要求改期")).getCode());

        var days = jdbc.queryForMap("""
                select coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at)::date as anchor_day,
                       s.cancelled_at::date                                                   as cancel_day,
                       coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at)::date
                           = s.cancelled_at::date                                             as same_day
                from inp_surgery s where s.id = ?
                """, sid);
        assertEquals(Boolean.FALSE, days.get("same_day"),
                "锚点日 " + days.get("anchor_day") + " 与取消日 " + days.get("cancel_day")
                        + " 必须不同——这正是 1426★ 现在把取消算错到排台日的证据");
    }

    // ================= ④ 契约不动 =================

    /**
     * <b>既有 {@code GET /api/inpatient/surgeries} 的键集合一键未变。</b>
     * 本车道动了 {@code SurgeryService.cancel} 的那条 update，没动任何读侧投影；
     * 该端点显式列了 11 个键（不是 {@code select *}），加列不会漏进去——这条断言把它钉死。
     */
    @Test
    void legacyListEndpointKeySetUnchangedAfterNewColumn() {
        Long admId = admit("契约不动");
        newSurgery(admId, "契约不动术式", null);

        var mine = medTech.surgeries().getData().stream()
                .filter(m -> "契约不动术式".equals(m.get("procedure_name")))
                .findFirst().orElseThrow(() -> new AssertionError("新建手术应出现在既有列表端点里"));

        assertFalse(mine.containsKey("cancelled_at"), "新列不得漏进既有列表端点的返回体");
        assertEquals(11, mine.size(), "既有列表端点仍是 11 个键，一个不多一个不少");
    }
}
