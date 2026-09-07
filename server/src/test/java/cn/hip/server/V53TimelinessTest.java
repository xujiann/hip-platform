package cn.hip.server;

import cn.hip.inpatient.service.EmrTimelinessService;
import cn.hip.inpatient.web.EmrTimelinessController;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v53 车道 V3：<b>病历书写时限质控</b>的判据测试。
 *
 * <h2>这个类要抓的是什么</h2>
 * 超时率是一个<b>算得出来就没人会怀疑</b>的数字：拿两个时刻相减、除一除，报表上就有百分比。
 * 所以本类不测「算不算得出」，测的是四件<b>算错了也不会报错</b>的事：
 * <ol>
 *   <li><b>锚点选得对不对</b>。「首次病程 8 小时」的起点若取成「首条医嘱时刻」，
 *       病历写得越晚起点就越晚，超时率恒为 0——一个永远达标的指标不会有人来查。
 *       {@link #executedAnchorsMatchTheDeclaredOnes()} 逐规则钉住「代码执行的锚点」
 *       必须等于「库里声明的锚点」；{@link #anchorMismatchDegradesTheRuleInsteadOfGuessing()}
 *       故意把两者改得不一致，断言服务<b>停下来说对不上</b>而不是按其中一个蒙着算。</li>
 *   <li><b>算不出来的有没有被伪装成 0</b>。{@link #unavailableRulesGiveReasonsNotZeroes()}
 *       断言 6 条缺锚点的规则返回体里<b>连 rows / summary / coverage 三个键都不许出现</b>——
 *       一张全 0 的表会被读成「本院无超时」，比不给更坏。</li>
 *   <li><b>分母里混没混进不该算的</b>。{@link #judgementCaliberIsExactAndDenominatorExcludesUndetermined()}
 *       用五种结论各造一条数据：未到时限的 PENDING 与终点早于起点的 ANOMALY_NEGATIVE
 *       <b>都不进分母</b>。把在写的病历算成「及时」会让当日报表永远好看；
 *       把负数时长算成「极其及时」会把一处数据缺陷伪装成一项优异指标。</li>
 *   <li><b>汇总与穿透对不对得上账</b>。{@link #summaryReconcilesWithDetailRowCount()}
 *       逐规则断言「汇总说的超时条数 == 穿透清单真给出的行数」。
 *       v49 麻醉质控就是栽在这里：汇总一套 SQL、明细另一套，点开当场对不上。</li>
 * </ol>
 *
 * <h2>夹具隔离</h2>
 * 全部夹具挂在一个<b>本用例现建的科室</b>下，所有查询一律带 {@code deptId} 过滤。
 * 时间窗口取 {@code current_date - 320 起五天}（与 V49CaliberTest 的 -300 错开），
 * 开跑前断言该窗口内本科室一行都没有——否则测出来的是环境不是代码。
 * <b>不写任何时间字面量</b>：全部时刻由 SQL 按 {@code current_date} / {@code now()} 现算
 * （本仓已被时区与时刻字面量炸过四次）。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "QUALITY"})
class V53TimelinessTest {

    @Autowired EmrTimelinessController controller;
    @Autowired EmrTimelinessService service;
    @Autowired PatientService patientService;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    /** 历史窗口：全部夹具的起点锚点都落在这五天里，且都早已过了任何法定时限 */
    private String hFrom;
    private String hTo;

    private Long deptId;
    private Long wardId;
    private Long bedId;
    private Long doctorId;

    private long a1;   // ON_TIME：入院记录 23 小时写完（限 24 小时）
    private long a2;   // LATE：入院记录 25 小时才写
    private long a3;   // MISSING_OVERDUE：至今没写
    private long a4;   // ANOMALY_NEGATIVE：入院记录的落库时刻早于入院时刻

    private long b1;   // PENDING（未到时限、未到提醒点）：1 小时前入院
    private long b2;   // PENDING + warning（已达 75% 提醒点）：20 小时前入院

    private long c2;   // 会诊：至今未完成，早已超 48 小时

    @BeforeEach
    void setUp() {
        configReader.evictAll();
        hFrom = jdbc.queryForObject("select (current_date - 320)::text", String.class);
        hTo = jdbc.queryForObject("select (current_date - 316)::text", String.class);

        String tag = "TL" + Long.toHexString(System.nanoTime());
        deptId = jdbc.queryForObject(
                "insert into sys_dept(parent_id, name, code, type, sort_no) "
                        + "values (null, '时限质控测试科', ?, 'CLINICAL', 999) returning id",
                Long.class, tag);
        bedId = jdbc.queryForObject("select id from inp_bed order by id limit 1", Long.class);
        wardId = jdbc.queryForObject("select ward_id from inp_bed where id = ?", Long.class, bedId);
        doctorId = jdbc.queryForObject("select min(id) from sys_user", Long.class);

        assertEquals(0L, (long) jdbc.queryForObject(
                "select count(*) from inp_admission where dept_id = ?", Long.class, deptId),
                "夹具科室开跑前必须是空的，否则测的是环境不是代码");

        // ---- 历史窗口：起点固定在 current_date - 318 的 08:00（窗口正中，不贴边）----
        String base = "((current_date - 318)::timestamptz + interval '8 hours')";
        a1 = admit(base);
        a2 = admit(base);
        a3 = admit(base);
        a4 = admit(base);
        record(a1, "ADMISSION", 23 * 60);     // 23 小时 < 24 小时 → ON_TIME
        record(a2, "ADMISSION", 25 * 60);     // 25 小时 > 24 小时 → LATE
        record(a4, "ADMISSION", -60);         // 终点早于起点 → ANOMALY_NEGATIVE
        // a1 另有一条 PROGRESS 但没有 FIRST_PROGRESS：首程规则应给出 FIRST_PROGRESS_UNTYPED 口径提示
        record(a1, "PROGRESS", 30);

        // ---- 会诊：责任科室是被邀科室（to_dept_id），不是申请科室 ----
        jdbc.update("""
                insert into inp_consult(admission_id, from_doctor_id, to_dept_id, question, status,
                                        created_at, done_at, consultant_id)
                values (?, ?, ?, '测试会诊', 'DONE', %s, %s + interval '47 hours', ?)
                """.formatted(base, base), a1, doctorId, deptId, doctorId);
        c2 = jdbc.queryForObject("""
                insert into inp_consult(admission_id, from_doctor_id, to_dept_id, question, status, created_at)
                values (?, ?, ?, '未完成会诊', 'REQUESTED', %s) returning id
                """.formatted(base), Long.class, a1, doctorId, deptId);

        // ---- 近实时窗口：PENDING 与「即将超时」----
        b1 = admit("(now() - interval '1 hour')");
        b2 = admit("(now() - interval '20 hours')");
    }

    @AfterEach
    void tearDown() {
        // 配置缓存是跨用例存活的单例，事务回滚不会把它一起回滚——
        // 不清干净，下一个用例读到的是本用例改过的阈值（1.1.4 的 30 秒 TTL）
        configReader.evictAll();
    }

    // ==================================================================================
    // 一、锚点：本车道的要害
    // ==================================================================================

    /**
     * 代码里实际执行的锚点必须逐字等于规则表里声明的锚点。
     *
     * <p>这条断言的价值在于：口径说明与执行口径一旦分家，报表就会<b>声称按 A 判、实际按 B 判</b>，
     * 而这件事在返回体上看不出任何异常。改锚点声明而忘了改代码（或反过来），本条当场红。
     */
    @Test
    void executedAnchorsMatchTheDeclaredOnes() {
        var body = ok(controller.anchors());
        @SuppressWarnings("unchecked")
        var rules = (List<Map<String, Object>>) body.get("rules");
        assertEquals(11, rules.size(), "V159 种子共 11 条规则");

        int executable = 0;
        for (var r : rules) {
            if (!Boolean.TRUE.equals(r.get("executable"))) continue;
            executable++;
            assertEquals(r.get("declaredStartAnchor"), r.get("executedStartAnchor"),
                    r.get("ruleCode") + " 的起点锚点：声明与执行必须逐字一致");
            assertEquals(r.get("declaredEndAnchor"), r.get("executedEndAnchor"),
                    r.get("ruleCode") + " 的终点锚点：声明与执行必须逐字一致");
            assertNull(r.get("anchorNote"), r.get("ruleCode") + " 可执行时不应有锚点告警");
        }
        assertEquals(5, executable, "V159 里可用且已实现的规则是 5 条");
    }

    /**
     * 锚点列的存在与否是<b>机器查出来的事实</b>，不是写迁移的人的断言。
     *
     * <p>V159 说死亡记录算不出来是因为「inp_admission 无死亡时刻列」——本条直接去
     * {@code information_schema} 里核实这句话。同时钉住反向的一半：手术记录那条的<b>起点列是存在的</b>
     * （{@code inp_surgery.end_at}），它算不出来是因为缺终点——
     * 由「列存在」推出「规则可用」会得到相反的结论，所以列级事实不许被当成规则级结论。
     */
    @Test
    void anchorColumnFactsAreVerifiedAgainstInformationSchema() {
        var byCode = anchorsByCode();

        @SuppressWarnings("unchecked")
        var deathStart = (Map<String, Object>) byCode.get("INP_DEATH_24H").get("startColumn");
        assertEquals("inp_admission", deathStart.get("table"));
        assertEquals("death_at", deathStart.get("column"));
        assertEquals(Boolean.FALSE, deathStart.get("exists"),
                "V159 声称 inp_admission 没有死亡时刻列——本条去 information_schema 核实这句话");

        @SuppressWarnings("unchecked")
        var surgStart = (Map<String, Object>) byCode.get("INP_SURGERY_NOTE_24H").get("startColumn");
        assertEquals(Boolean.TRUE, surgStart.get("exists"), "手术记录的**起点**列是存在的");
        @SuppressWarnings("unchecked")
        var surgEnd = (Map<String, Object>) byCode.get("INP_SURGERY_NOTE_24H").get("endColumn");
        assertEquals(Boolean.FALSE, surgEnd.get("declared"),
                "手术记录算不出来是因为**没有终点锚点**，不是因为起点缺失");

        // 门诊病历那条的起点列同样存在（outp_registration.created_at），
        // 它算不出来是因为 outp_emr 只有 updated_at——同一课，反向的一半。
        @SuppressWarnings("unchecked")
        var outpStart = (Map<String, Object>) byCode.get("OUTP_EMR_TIMELY").get("startColumn");
        assertEquals(Boolean.TRUE, outpStart.get("exists"));
        assertNotNull(byCode.get("OUTP_EMR_TIMELY").get("unavailableReason"));

        // 五条可用规则的两端锚点列都必须真实存在，否则「可用」是空头支票
        for (String code : List.of("INP_ADMISSION_24H", "INP_FIRST_PROGRESS_8H",
                "INP_ROUND_ATTENDING_48H", "INP_DISCHARGE_24H", "INP_CONSULT_ROUTINE_48H")) {
            @SuppressWarnings("unchecked")
            var s = (Map<String, Object>) byCode.get(code).get("startColumn");
            @SuppressWarnings("unchecked")
            var e = (Map<String, Object>) byCode.get(code).get("endColumn");
            assertEquals(Boolean.TRUE, s.get("exists"), code + " 的起点锚点列必须存在");
            assertEquals(Boolean.TRUE, e.get("exists"), code + " 的终点锚点列必须存在");
        }
    }

    /**
     * 库里的锚点声明被改动而代码没跟上时，规则必须<b>降级为不可执行</b>——
     * 不给清单、不给统计，而不是按其中任何一个蒙着算。
     *
     * <p>注意断言里 {@code available} 仍然是 true：本条钉的是锚点守卫，不是可用性守卫。
     */
    @Test
    void anchorMismatchDegradesTheRuleInsteadOfGuessing() {
        jdbc.update("update emr_timeliness_rule set start_anchor = 'inp_admission.created_at' "
                + "where rule_code = 'INP_ADMISSION_24H'");

        var entry = indicatorOf("INP_ADMISSION_24H", ok(controller.indicators(
                "INP_ADMISSION_24H", hFrom, hTo, "dept", deptId, null, null)));
        assertEquals(Boolean.TRUE, entry.get("available"), "本条钉的是锚点守卫，可用性不该被牵连");
        assertEquals(Boolean.FALSE, entry.get("executable"));
        assertFalse(entry.containsKey("rows"), "锚点对不上时不许给行");
        assertFalse(entry.containsKey("summary"), "锚点对不上时不许给汇总");
        assertTrue(String.valueOf(entry.get("note")).contains("不一致"));

        // 清单路径同样拒绝，而不是返回一份用错锚点算出来的清单
        var r = controller.overdue("INP_ADMISSION_24H", hFrom, hTo, deptId, null, null, null, true, null, null);
        assertEquals(5744, r.getCode());
    }

    // ==================================================================================
    // 二、诚实标注：算不出来就说算不出来
    // ==================================================================================

    /** 缺锚点的 6 条规则：只给原因与补法，<b>rows / summary / coverage 一个都不许出现</b>。 */
    @Test
    void unavailableRulesGiveReasonsNotZeroes() {
        var body = ok(controller.indicators(null, hFrom, hTo, "dept", deptId, null, null));
        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) body.get("indicators");
        assertEquals(11, list.size(), "统计端点必须把 11 条规则全列出来，算不出来的那几条也要在场");

        int unavailable = 0;
        for (var e : list) {
            if (Boolean.TRUE.equals(e.get("available"))) continue;
            unavailable++;
            String code = (String) e.get("code");
            assertNotNull(e.get("unavailableReason"), code + " 必须给出不可用原因");
            assertFalse(((List<?>) e.get("missingFields")).isEmpty(), code + " 必须给出补法");
            assertFalse(e.containsKey("rows"), code + " 不许给 rows");
            assertFalse(e.containsKey("summary"), code + " 不许给 summary——一个 0 会被读成「没有超时」");
            assertFalse(e.containsKey("coverage"), code + " 不许给 coverage");
        }
        assertEquals(6, unavailable, "V159 种子里算不出来的是 6 条");

        // 显式点名一条算不出来的规则要清单 → 明确报错，而不是回一个空数组让人以为「查过了，没有超时」
        var r = controller.overdue("INP_DEATH_24H", hFrom, hTo, deptId, null, null, null, true, null, null);
        assertEquals(5744, r.getCode());
        assertTrue(r.getMessage().contains("算不出来"));
    }

    /**
     * 停用的规则不进统计，但必须在返回体里出现并说明为什么停用。
     * 主治查房那条 available=true / enabled=false：round_level 历史全空，
     * 不猜的代价是历史查房整片判为「未写」——那是误报，所以默认停用。
     */
    @Test
    void disabledRuleIsExplainedNotSilentlyDropped() {
        var entry = indicatorOf("INP_ROUND_ATTENDING_48H",
                ok(controller.indicators(null, hFrom, hTo, "dept", deptId, null, null)));
        assertEquals(Boolean.TRUE, entry.get("available"));
        assertEquals(Boolean.FALSE, entry.get("enabled"));
        assertFalse(entry.containsKey("rows"));
        assertFalse(entry.containsKey("summary"));
        assertTrue(String.valueOf(entry.get("note")).contains("停用"));

        var r = controller.overdue("INP_ROUND_ATTENDING_48H", hFrom, hTo, deptId, null, null, null,
                true, null, null);
        assertEquals(5745, r.getCode());
    }

    /** 未经病案科核对原文的规则，条文号在 catalog 与 indicators <b>两处</b>都要带后缀。 */
    @Test
    void unverifiedThresholdsAreSuffixedInBothCatalogAndIndicators() {
        var cat = ok(controller.catalog());
        assertEquals(11L, ((Number) cat.get("unverifiedCount")).longValue(),
                "V159 种子里 source_verified 全部 false（刻意的，见迁移文件头）");
        @SuppressWarnings("unchecked")
        var rules = (List<Map<String, Object>>) cat.get("rules");
        for (var r : rules) {
            assertTrue(String.valueOf(r.get("sourceArticle")).endsWith(EmrTimelinessService.UNVERIFIED_SUFFIX),
                    r.get("code") + " 的条文号必须带「待核对」后缀");
            assertNotNull(r.get("sourceArticleRaw"));
        }

        var entry = indicatorOf("INP_ADMISSION_24H", ok(controller.indicators(
                "INP_ADMISSION_24H", hFrom, hTo, "dept", deptId, null, null)));
        assertTrue(String.valueOf(entry.get("sourceArticle")).endsWith(EmrTimelinessService.UNVERIFIED_SUFFIX),
                "indicators 里同样要带后缀——只在 catalog 标一次，看报表的人根本看不到");

        // 病案科核过原文后置 true：后缀消失，并落 verified_by / verified_at
        var res = service.update("INP_ADMISSION_24H", new EmrTimelinessService.UpdateRequest(
                null, null, null, true, null, "病案科已取《病历书写基本规范》原文逐条核对"), doctorId);
        assertTrue(res.ok(), res.message());
        var after = service.rule("INP_ADMISSION_24H");
        assertTrue(after.sourceVerified());
        assertFalse(after.sourceArticleDisplay().endsWith(EmrTimelinessService.UNVERIFIED_SUFFIX));
        assertEquals(1L, (long) jdbc.queryForObject(
                "select count(*) from emr_timeliness_rule where rule_code = 'INP_ADMISSION_24H' "
                        + "and verified_by = ? and verified_at is not null", Long.class, doctorId));
    }

    // ==================================================================================
    // 三、判定口径：算错了也不会报错的那一类
    // ==================================================================================

    /**
     * 五种结论逐条钉死，并断言 <b>PENDING 与 ANOMALY_NEGATIVE 不进分母</b>。
     *
     * <p>分母 = ON_TIME(1) + LATE(1) + MISSING_OVERDUE(1) = 3，超时 2 条 → 66.67%。
     * 若把 ANOMALY_NEGATIVE（终点早于起点）当成「极其及时」算进去，分母会变 4、超时率变 50%——
     * 一处数据缺陷就这样变成了一项优异指标。
     */
    @Test
    void judgementCaliberIsExactAndDenominatorExcludesUndetermined() {
        var byId = statusBySubject("INP_ADMISSION_24H", hFrom, hTo);
        assertEquals("ON_TIME", byId.get(a1), "23 小时写完，限 24 小时");
        assertEquals("LATE", byId.get(a2), "25 小时才写，限 24 小时");
        assertEquals("MISSING_OVERDUE", byId.get(a3), "至今未写且早已过时限");
        assertEquals("ANOMALY_NEGATIVE", byId.get(a4), "落库时刻早于入院时刻是数据异常，不是「极其及时」");

        var entry = indicatorOf("INP_ADMISSION_24H", ok(controller.indicators(
                "INP_ADMISSION_24H", hFrom, hTo, "dept", deptId, null, null)));
        @SuppressWarnings("unchecked")
        var sum = (Map<String, Object>) entry.get("summary");
        assertEquals(4L, num(sum.get("total")));
        assertEquals(1L, num(sum.get("onTime")));
        assertEquals(1L, num(sum.get("late")));
        assertEquals(1L, num(sum.get("missingOverdue")));
        assertEquals(0L, num(sum.get("pending")));
        assertEquals(1L, num(sum.get("anomalyNegative")));
        assertEquals(3L, num(sum.get("judgeable")), "分母不含 ANOMALY_NEGATIVE");
        assertEquals(new BigDecimal("66.67"), sum.get("overtimeRatePct"));
        assertEquals(new BigDecimal("33.33"), sum.get("onTimeRatePct"));

        // 按科室分组的那一行必须与整体汇总一模一样（本夹具只有一个科室）
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) entry.get("rows");
        assertEquals(1, rows.size());
        assertEquals(deptId, ((Number) rows.get(0).get("groupId")).longValue());
        assertEquals(sum.get("overtimeRatePct"), rows.get(0).get("overtimeRatePct"));
    }

    /**
     * 分母为 0 时超时率返 <b>null 而不是 0</b>。
     *
     * <p>近实时窗口里两条住院都还没到 24 小时（PENDING），一条超时也没有。
     * 若此时报 0%，管理者会读成「本科室今天零超时」，而事实是「今天还没有一条能判」。
     */
    @Test
    void zeroDenominatorYieldsNullRateNotZero() {
        String from = jdbc.queryForObject("select (current_date - 1)::text", String.class);
        String to = jdbc.queryForObject("select current_date::text", String.class);
        var entry = indicatorOf("INP_ADMISSION_24H", ok(controller.indicators(
                "INP_ADMISSION_24H", from, to, "none", deptId, null, null)));
        @SuppressWarnings("unchecked")
        var sum = (Map<String, Object>) entry.get("summary");
        assertEquals(2L, num(sum.get("total")));
        assertEquals(2L, num(sum.get("pending")));
        assertEquals(0L, num(sum.get("judgeable")));
        assertNull(sum.get("overtimeRatePct"), "没有可判定病例时超时率必须是 null，不是 0");
        assertNull(sum.get("onTimeRatePct"));
        assertNotNull(sum.get("rateNote"));
    }

    /**
     * 即将超时提醒只包含<b>还来得及的那些</b>：未书写、未到时限、已达 {@code warn_ratio_pct}%。
     *
     * <p>b2 入院 20 小时（24 小时限的 75% = 18 小时）→ 提醒；
     * b1 入院 1 小时 → 不提醒；已经超时的 a2/a3 → 不提醒（那属于超时清单，不是提醒）。
     */
    @Test
    void upcomingListsOnlyThoseStillInTime() {
        var body = ok(controller.upcoming("INP_ADMISSION_24H", deptId, null, null, null));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) body.get("items");
        assertEquals(1, items.size(), "只有 b2 落在提醒区间内");
        var it = items.get(0);
        assertEquals(b2, ((Number) it.get("subjectId")).longValue());
        assertEquals("PENDING", it.get("status"));
        assertEquals(Boolean.TRUE, it.get("warning"));
        assertNull(it.get("overMinutes"), "还没超时的行不给超时分钟数——0 会被读成「刚好卡点」");
        assertNotNull(it.get("dueAt"));
        assertNotNull(it.get("warnAt"));
    }

    /**
     * <b>汇总说的超时条数必须等于穿透清单真给出的行数。</b>
     * 逐规则遍历，不手写清单——以后新增规则会自动被纳入对账，而不是又漏掉一条。
     */
    @Test
    void summaryReconcilesWithDetailRowCount() {
        var body = ok(controller.indicators(null, hFrom, hTo, "dept", deptId, null, null));
        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) body.get("indicators");

        int reconciled = 0;
        for (var e : list) {
            if (!e.containsKey("summary")) continue;
            reconciled++;
            String code = (String) e.get("code");
            @SuppressWarnings("unchecked")
            var sum = (Map<String, Object>) e.get("summary");
            long expected = num(sum.get("late")) + num(sum.get("missingOverdue"));
            assertEquals(expected, num(sum.get("overtime")), code + " 的 overtime 必须等于 late + missingOverdue");

            var detail = ok(controller.overdue(code, hFrom, hTo, deptId, null, null, null, true, 500, null));
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) detail.get("items");
            assertEquals(expected, items.size(),
                    code + "：汇总说超时 " + expected + " 条，穿透明细给出 " + items.size() + " 行——对不上账");
            for (var it : items) {
                assertTrue(List.of("LATE", "MISSING_OVERDUE").contains(it.get("status")),
                        "overdueOnly 的清单里不许混进未超时的行");
                assertNotNull(it.get("overMinutes"));
            }
        }
        assertEquals(4, reconciled, "在用规则 4 条（主治查房那条默认停用，不进统计）");
    }

    /** 会诊那条的责任科室是<b>被邀科室</b>（to_dept_id）——超时的责任方是被邀方，不是申请方。 */
    @Test
    void consultResponsibilityFallsOnTheInvitedDepartment() {
        var byId = statusBySubject("INP_CONSULT_ROUTINE_48H", hFrom, hTo);
        assertEquals("MISSING_OVERDUE", byId.get(c2), "至今未完成的会诊早已过 48 小时");
        assertEquals(2, byId.size(), "两条会诊都归到被邀科室名下");

        // 申请科室是 a1 所在科室（同为本用例科室），但把 deptId 换成别的科室应当查不到这两条
        Long other = jdbc.queryForObject("select id from sys_dept where id <> ? order by id limit 1",
                Long.class, deptId);
        var body = ok(controller.overdue("INP_CONSULT_ROUTINE_48H", hFrom, hTo, other, null, null,
                null, true, null, null));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) body.get("items");
        assertTrue(items.stream().noneMatch(i -> ((Number) i.get("subjectId")).longValue() == c2));
    }

    // ==================================================================================
    // 四、覆盖率：先看这一段再看指标值
    // ==================================================================================

    /**
     * 覆盖率必须如实说出「多少病历有可用的时间锚点」，并把判不准的那些逐条标出来。
     *
     * <p>首程规则在本夹具上全判「未写」，其中 a1 是<b>可能的误报</b>——它有 PROGRESS 病程但
     * 没有 FIRST_PROGRESS 类型记录，而 v34 之前首程混写在 PROGRESS 里、在库里区分不了。
     * 不把这一条标出来，就会拿一个含误报的超时率去考核医师。
     */
    @Test
    void coverageReportsAnchorAvailabilityAndAmbiguousRows() {
        var entry = indicatorOf("INP_FIRST_PROGRESS_8H", ok(controller.indicators(
                "INP_FIRST_PROGRESS_8H", hFrom, hTo, "dept", deptId, null, null)));
        @SuppressWarnings("unchecked")
        var cov = (Map<String, Object>) entry.get("coverage");
        assertEquals(4L, num(cov.get("subjectsInWindow")));
        assertEquals(0L, num(cov.get("withEndAnchor")), "四条住院都没有 FIRST_PROGRESS 记录");
        assertEquals(new BigDecimal("0.00"), cov.get("endAnchorFillRatePct"));
        assertEquals(1L, num(cov.get("caveatRows")), "a1 有 PROGRESS 无 FIRST_PROGRESS，判定可能是误报");
        assertNotNull(cov.get("caveatNote"));

        // 明细里逐行给出口径提示原文，而不是只在汇总里说一句「有 1 条判不准」
        var detail = ok(controller.overdue("INP_FIRST_PROGRESS_8H", hFrom, hTo, deptId, null, null,
                null, true, 500, null));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) detail.get("items");
        var flagged = items.stream()
                .filter(i -> ((Number) i.get("subjectId")).longValue() == a1).findFirst().orElseThrow();
        assertEquals("FIRST_PROGRESS_UNTYPED", flagged.get("caveat"));
        assertTrue(String.valueOf(flagged.get("caveatText")).contains("误判"));

        // 入院记录那条：4 条主体里 3 条有终点锚点，可判定 3 条（异常那条不进分母）
        var adm = indicatorOf("INP_ADMISSION_24H", ok(controller.indicators(
                "INP_ADMISSION_24H", hFrom, hTo, "dept", deptId, null, null)));
        @SuppressWarnings("unchecked")
        var acov = (Map<String, Object>) adm.get("coverage");
        assertEquals(3L, num(acov.get("withEndAnchor")));
        assertEquals(new BigDecimal("75.00"), acov.get("endAnchorFillRatePct"));
        assertEquals(3L, num(acov.get("judgeable")));
        assertEquals(1L, num(acov.get("anomalyNegative")));

        // 全窗口覆盖率段：锚点可得性，不是指标值
        var body = ok(controller.indicators(null, hFrom, hTo, "dept", deptId, null, null));
        @SuppressWarnings("unchecked")
        var global = (Map<String, Object>) body.get("coverage");
        for (String k : List.of("admissions", "with_admit_at", "discharged_in_window",
                "dischargedStatusWithoutTimestamp", "first_progress_typed", "round_records",
                "round_level_typed", "consults", "consults_with_done_at", "with_writer")) {
            assertTrue(global.containsKey(k), "coverage 缺少 " + k);
        }
    }

    // ==================================================================================
    // 五、阈值：同一个阈值只有一处可改
    // ==================================================================================

    /**
     * 首程 8 小时这个数字<b>只有 v34 那个 sys_config 键可改</b>。
     * 若本服务另存一个 8，院方把键改成 12 之后会得到两个互相矛盾的超时率。
     */
    @Test
    void effectiveThresholdComesFromTheOneExistingConfigKey() {
        var r = service.rule("INP_FIRST_PROGRESS_8H");
        assertEquals("emr.timeliness.first_progress_hours", r.configKey());
        assertEquals(480, r.limitMinutes());
        assertEquals("CONFIG_KEY", r.limitSource());

        jdbc.update("update sys_config set cfg_value = '12' where cfg_key = 'emr.timeliness.first_progress_hours'");
        configReader.evict("emr.timeliness.first_progress_hours");
        assertEquals(720, service.rule("INP_FIRST_PROGRESS_8H").limitMinutes(),
                "改那一个键，本服务的阈值必须跟着动");

        // 键被写坏时回落规则表的 limit_minutes，并**把回落这件事说出来**
        jdbc.update("update sys_config set cfg_value = 'abc' where cfg_key = 'emr.timeliness.first_progress_hours'");
        configReader.evict("emr.timeliness.first_progress_hours");
        var bad = service.rule("INP_FIRST_PROGRESS_8H");
        assertEquals(480, bad.limitMinutes());
        assertEquals("RULE_FALLBACK", bad.limitSource());
        assertTrue(bad.warnings().stream().anyMatch(w -> w.contains("回落")),
                "静默回落等于让一个笔误改掉全院的法定时限而报表上看不出异常");
    }

    /** 阈值变更必须带原因并落留痕；不可用的规则不许被启用（且不能把事务搞成 aborted）。 */
    @Test
    void thresholdChangesRequireAReasonAndLeaveATrail() {
        assertEquals(5747, service.update("INP_ADMISSION_24H", new EmrTimelinessService.UpdateRequest(
                720, null, null, null, null, null), doctorId).code(), "改法定时限必须给原因");
        assertEquals(5748, service.update("INP_ADMISSION_24H", new EmrTimelinessService.UpdateRequest(
                720, null, null, null, null, "理由"), null).code(), "记不下是谁改的留痕没有价值");
        assertEquals(5746, service.update("INP_ADMISSION_24H", new EmrTimelinessService.UpdateRequest(
                0, null, null, null, null, "理由"), doctorId).code());

        var res = service.update("INP_ADMISSION_24H", new EmrTimelinessService.UpdateRequest(
                720, 60, null, null, null, "本院制度加严至 12 小时，2026 年质控方案第 3 条"), doctorId);
        assertTrue(res.ok(), res.message());
        var log = service.changeLog("INP_ADMISSION_24H");
        assertEquals(2, log.size(), "两个字段变更 → 两行留痕");
        var limitRow = log.stream().filter(x -> "limit_minutes".equals(x.get("field"))).findFirst().orElseThrow();
        assertEquals("1440", limitRow.get("old_value"));
        assertEquals("720", limitRow.get("new_value"));
        assertTrue(String.valueOf(limitRow.get("reason")).contains("本院制度加严"));
        assertEquals(doctorId, ((Number) limitRow.get("changed_by")).longValue());

        // 改完立刻生效：a1 的 23 小时在 12 小时限下变成超时
        assertEquals("LATE", statusBySubject("INP_ADMISSION_24H", hFrom, hTo).get(a1));

        // 算不出来的规则不许启用——先判再写，绝不让 CHECK 约束把事务打成 aborted
        assertEquals(5744, service.update("INP_DEATH_24H", new EmrTimelinessService.UpdateRequest(
                null, null, true, null, null, "想试试"), doctorId).code());
        assertEquals(0L, (long) jdbc.queryForObject(
                "select count(*) from emr_timeliness_rule where rule_code = 'INP_DEATH_24H' and enabled",
                Long.class), "被拒之后事务必须还是好的，后续 SQL 不能连坐报错");
    }

    // ==================================================================================
    // 六、gate 与入参守卫
    // ==================================================================================

    /** gate 三态：坏值回落 warn（不回落 off）；off 档下 findings 照样是真的。 */
    @Test
    void gateFallsBackToWarnAndFactsSurviveEveryTier() {
        assertEquals("warn", service.gate(), "键未登记时默认 warn");
        assertFalse(service.gateKeyRegistered(), "V159 没有插这一行——/config 会把这个事实明说出来");

        jdbc.update("insert into sys_config(cfg_key, cfg_value, remark) values (?, 'blocked', 't') "
                + "on conflict (cfg_key) do update set cfg_value = 'blocked'", EmrTimelinessService.GATE_KEY);
        configReader.evict(EmrTimelinessService.GATE_KEY);
        assertEquals("warn", service.gate(), "坏值回落 warn，不回落 off——笔误不该静默关掉法定校验");

        jdbc.update("update sys_config set cfg_value = 'off' where cfg_key = ?", EmrTimelinessService.GATE_KEY);
        configReader.evict(EmrTimelinessService.GATE_KEY);
        var off = service.verdict(a3);
        assertFalse(off.blocked());
        assertFalse(off.findings().isEmpty(), "off 档只决定拿事实怎么办，不决定事实是什么");

        jdbc.update("update sys_config set cfg_value = 'block' where cfg_key = ?", EmrTimelinessService.GATE_KEY);
        configReader.evict(EmrTimelinessService.GATE_KEY);
        var block = service.verdict(a3);
        assertTrue(block.blocked());
        assertEquals(5749, block.code());

        // 未到时限的住院不该被挡住：PENDING 不是缺项
        assertTrue(service.verdict(b1).findings().isEmpty(), "还没到时限的病历不能在出院时把人挡住");
    }

    /** 入参守卫：窗口、分页、分组、规则码各有各的码，不许混。 */
    @Test
    void inputGuardsUseTheirOwnCodes() {
        assertEquals(5741, controller.overdue(null, "2026-13-01", null, null, null, null, null,
                true, null, null).getCode());
        assertEquals(5741, controller.indicators(null, hTo, hFrom, "dept", null, null, null).getCode());
        String far = jdbc.queryForObject("select (current_date - 500)::text", String.class);
        assertEquals(5741, controller.indicators(null, far, null, "dept", null, null, null).getCode());
        assertEquals(5742, controller.indicators(null, hFrom, hTo, "surgeon", null, null, null).getCode());
        assertEquals(5742, controller.overdue(null, hFrom, hTo, null, null, null, "SOMEDAY",
                true, null, null).getCode());
        assertEquals(5743, controller.overdue(null, hFrom, hTo, null, null, null, null, true, 0, null).getCode());
        assertEquals(5743, controller.overdue(null, hFrom, hTo, null, null, null, null, true, null, -1).getCode());
        assertEquals(5740, controller.overdue("NO_SUCH_RULE", hFrom, hTo, null, null, null, null,
                true, null, null).getCode());
        assertEquals(5740, controller.rule("NO_SUCH_RULE").getCode());
    }

    // ==================================================================================
    // 夹具与小工具
    // ==================================================================================

    private long admit(String admitAtSql) {
        Patient p = new Patient();
        p.setName("时限" + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        return jdbc.queryForObject("""
                insert into inp_admission(admission_no, patient_id, dept_id, ward_id, bed_id,
                                          doctor_id, status, admit_at)
                values (?, ?, ?, ?, ?, ?, 'IN_HOSPITAL', %s) returning id
                """.formatted(admitAtSql), Long.class,
                "TLT" + System.nanoTime(), pid, deptId, wardId, bedId, doctorId);
    }

    /** 病历落库时刻按「入院时刻 + N 分钟」现算，绝不写时间字面量（本仓已被时区炸过四次）。 */
    private void record(long admissionId, String type, int minutesAfterAdmit) {
        jdbc.update("""
                insert into inp_medical_record(admission_id, record_type, title, content, doctor_id, created_at)
                values (?, ?, ?, '测试内容', ?,
                        (select a.admit_at from inp_admission a where a.id = ?) + (? * interval '1 minute'))
                """, admissionId, type, type, doctorId, admissionId, minutesAfterAdmit);
    }

    private Map<Long, String> statusBySubject(String rule, String from, String to) {
        var body = ok(controller.overdue(rule, from, to, deptId, null, null, null, false, 500, null));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) body.get("items");
        var m = new java.util.LinkedHashMap<Long, String>();
        for (var i : items) m.put(((Number) i.get("subjectId")).longValue(), (String) i.get("status"));
        return m;
    }

    private Map<String, Map<String, Object>> anchorsByCode() {
        var body = ok(controller.anchors());
        @SuppressWarnings("unchecked")
        var rules = (List<Map<String, Object>>) body.get("rules");
        var m = new java.util.LinkedHashMap<String, Map<String, Object>>();
        for (var r : rules) m.put((String) r.get("ruleCode"), r);
        return m;
    }

    private static Map<String, Object> indicatorOf(String code, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) body.get("indicators");
        return list.stream().filter(e -> code.equals(e.get("code"))).findFirst().orElseThrow(
                () -> new AssertionError("返回体里没有规则 " + code));
    }

    private static Map<String, Object> ok(cn.hip.platform.core.common.R<Map<String, Object>> r) {
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }
}
