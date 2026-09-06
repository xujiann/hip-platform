package cn.hip.server;

import cn.hip.platform.core.service.ConfigReader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * v49 车道 Q5：<b>麻醉质控指标的机械对账保障</b>（技术偏离表 1421★–1450★）。
 *
 * <h2>这个类为什么存在</h2>
 * v46 之所以能带着一批口径缺陷交付，不是因为没写测试，而是因为
 * {@code V46AnesQcTest} 的「汇总合计 == 穿透明细条数」<b>只覆盖了 1428★ 一条指标</b>。
 * 其余 19 条 available 指标从没被机械核对过，于是
 * <ul>
 *   <li>1429★ 的 {@code deaths} 数的是<b>手术台次</b>而不是<b>死亡患者</b>（一人两台手术分子算两次），
 *   <li>1433★/1434★ 汇总是<b>患者粒度</b>而穿透明细是<b>记录粒度</b>（点开穿透当场对不上账），
 *   <li>1437★ 汇总按<b>事件日期</b>归桶而穿透明细按<b>手术锚点日期</b>归桶（两套口径互相核对不了），
 * </ul>
 * 一路活到今天。<b>缺陷不是没被发现，是根本没有人（没有机器）去核对。</b>
 *
 * <h2>因此本类的判据不是「指标算得出来」，而是三条机械保障</h2>
 * <ol>
 *   <li><b>逐指标遍历，不手写清单</b>——指标集合从 {@code GET /catalog} 现取现遍历。
 *       以后新增指标会<b>自动</b>被纳入对账，而不是又漏掉一条。
 *   <li><b>逐列普查（本类最要紧的一条）</b>——汇总行里<b>每一个非标签列</b>都必须落进
 *       四类判据之一：<b>计数</b>（== 明细中落入该桶的行数）、<b>求和</b>、<b>派生</b>（由同行其它列算出）、
 *       <b>跨指标核对</b>；否则必须<b>显式登记进 {@link #UNRECONCILABLE_COLUMNS} 并写明原因</b>。
 *       一个既没有判据、也没有登记的列 = 当场 fail。
 *       <b>「静默跳过」正是这次缺陷活下来的原因，所以本类把静默跳过做成了编译不过关的事。</b>
 *   <li><b>缺数据源的三条不许被「补」成近似值</b>——1435★/1444★/1445★ 的返回体里
 *       不但没有 {@code rows}/{@code summary}，<b>连一个数字都不许有</b>。
 * </ol>
 *
 * <h2>为什么走 MockMvc 而不是直接调 Controller</h2>
 * v49 要给 {@code /indicators} 加 {@code by}、给 {@code /detail} 加 {@code bucket}
 * 两个新参数（4943/4944）。直接调 Java 方法会把测试<b>焊死在方法签名上</b>：
 * 别的车道一加参数，本类连编译都过不去，于是"改签名"就变成了"顺手改测试"。
 * 走 HTTP 层则：签名怎么变都不影响本类，而且测的正是对接方真正会用的那层契约。
 *
 * <h2>这张网确实抓得住东西（已实测，不是设想）</h2>
 * 把本类原样搬到 <b>v46 基线</b>（HEAD，{@code AnesQcController} 里 {@code 4943 / bucket /
 * counts_as_patient} 全部零命中）上跑，<b>20 条用例红 9 条</b>：
 * <ul>
 *   <li>1429★ 明细缺 {@code counts_as_patient} —— 人数根本数不出来
 *   <li>1433★/1434★ 明细缺患者去重键 —— 患者粒度的汇总对着记录粒度的明细
 *   <li>1437★ {@code removed} 汇总说 0 而明细数出 1 —— 汇总按事件日期、明细按手术锚点日期
 *   <li>1437★ {@code in_use} 汇总说 1 台而穿透给出 2 行 —— 那是个点不出来的数字
 *   <li>事件日期归桶 4 处、{@code by} 4943、{@code bucket} 4944、占比不可穿透、口径变更未随体下发
 * </ul>
 * 同一批用例对着 v49 修完的代码 <b>20 条全绿</b>。<b>一条永远不会红的测试等于没有测试</b>，
 * 所以本类的每一条判据都在基线上验过它真的会红。
 *
 * <p>夹具窗口取 <b>current_date - 300 起三天</b>：与 {@code V46AnesQcTest} 的 -200 错开，
 * 且开跑前先断言该窗口内库里<b>一行都没有</b>——否则测出来的是环境不是代码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = {"ADMIN", "QUALITY"})
class V49CaliberTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper om;
    @Autowired ConfigReader configReader;

    /** 业务时区：{@code ?::date} 与 {@code current_date} 都按会话时区（Asia/Shanghai）切日。
     *  <b>禁用 JVM 默认时区</b>——本仓已因时区炸过四次，CI 跑在 UTC，用默认时区必错位 8 小时。 */
    private static final ZoneId BIZ = ZoneId.of("Asia/Shanghai");

    /** 缺数据源的三条：1435★ 术中出血量 / 1444★ 毒麻药 / 1445★ 肌松药 */
    private static final Set<String> UNAVAILABLE = Set.of("1435", "1444", "1445");

    /** 事件类指标：v49 的 {@code by=event|surgery} 只对这几条有意义（错误码分段表 4943 行） */
    private static final Set<String> EVENT_INDICATORS =
            new LinkedHashSet<>(List.of("1437", "1446", "1447", "1448", "1449", "1450"));

    /**
     * 二维分桶键的拼接分隔符。与 {@code AnesQcController.BUCKET_SEP} 同源，
     * 但那个常量是包内可见的、跨包取不到，所以这里另写一份——
     * 返回体带 {@code bucketSeparator} 时本类会断言两者一致，不靠"我记得是这个"。
     */
    private static final String BUCKET_SEP = " / ";

    /** 1437★ 唯一的分桶取值：在用镇痛泵清单（度量列而非维度，走单独一条穿透） */
    private static final String IN_USE_BUCKET = "in_use";

    private String d0;   // 夹具首日：手术当天
    private String d1;   // 次日：术后延迟拔管 / 术后转 ICU / 术后抢救 —— 事件日期 != 手术日期
    private String d2;   // 第三日：术后 48 小时拆镇痛泵

    // ==================================================================================
    // 【显式清单一】确实无法机械对账的**列**：不许静默跳过，必须登记原因与补法。
    // 键 = "指标.列"。{@link #columnsThatCannotBeReconciledAreDeclaredNotSkipped()} 会
    // 逐条断言：原因非空、且该列在真实返回体里仍然存在（列没了/被修好了要来删这一条，
    // 免得清单变成一张没人维护的免死金牌）。
    // ==================================================================================
    private static final Map<String, String> UNRECONCILABLE_COLUMNS = new LinkedHashMap<>();

    static {
        // 【已销案 v49】1439.unplanned_reop 与 1438.with_op_icd 原登记为「无法对账」，
        // 原因都是公共投影 SURG_SELECT 缺列。主控已给该投影补上 s.op_icd 与 s.is_unplanned_reop
        // （纯读侧、末尾追加、不动既有列顺序），两列现已有真判据，故从本表移除。
        // 保留这段说明是为了记住：**「无法对账」应当是待办不是终局**——
        // 登记原因的意义正在于让它可以被销掉，而不是让它体面地留在名单上。
        UNRECONCILABLE_COLUMNS.put("1427.timed_cases",
                "1427★ 的穿透明细【只含跨日台次】，而 timed_cases 是分母（该时段所有已录开台+结束的台次），"
                + "明细里根本没有对应的行。"
                + "本类改用【跨指标核对】兜住它：1427.summary.timed_cases 必须等于 1425★ 各日 timed_cases 之和"
                + "（见 crossIndicatorPopulationsAgree），不算无判据。");
    }

    // ==================================================================================
    // 【显式清单二】不是「某一列」而是「某一层」对不了账的，同样登记，同样不静默跳过。
    // ==================================================================================
    private static final Map<String, String> NOT_MECHANICALLY_COVERED = new LinkedHashMap<>();

    static {
        NOT_MECHANICALLY_COVERED.put("1436★ 的分桶（年龄段 × 性别）在【不带 bucket 的全量明细】上",
                "汇总按「年龄段 × 性别」分桶，而全量明细走的公共投影 SURG_SELECT 里【既无出生日期也无性别】，"
                + "落到某个桶里的是哪几台手术，在全量明细上看不出来。故本类的通用对账引擎对 1436★ 只对【总数】"
                + "（各桶 cases 之和 == 明细条数）。"
                + "分桶级已由 v49 新增的 bucket 参数覆盖（bucket=「18–44 岁 / 女」这样的二元键），"
                + "见 bucketDrillsIntoExactlyOneShareOfTheRatio——那条用例逐桶断言「桶内条数 == 该行 cases」。");
        NOT_MECHANICALLY_COVERED.put("1438★ 的分桶（术式名 × op_icd）在【不带 bucket 的全量明细】上",
                "汇总按 (procedure_name, op_icd) 二元组分行，而全量明细的公共投影 SURG_SELECT 里【没有 op_icd 列】，"
                + "二元键的后一半取不到，逐桶还原不了；且汇总行本身有 limit 50，行合计在术式多于 50 种时也不等于总量。"
                + "本类对 1438★ 只对【总量】（summary.cases == 明细条数，且断言本窗口行数 < 50 才用行合计），"
                + "分桶级由 bucket 参数覆盖。补法：给 SURG_SELECT 补 s.op_icd 一列即可让全量明细也逐桶对上。");
        NOT_MECHANICALLY_COVERED.put("1450★ 与手术域之间",
                "qc_adverse_event 是全院不良事件登记表，【无 surgery_id 关联列】，"
                + "所以「麻醉与手术相关不良事件」这个子集在数据上取不出来——1450★ 只能自洽对账"
                + "（汇总 ↔ 自己的明细），不能与手术域交叉核对。这是 ADVERSE_NOTE 已自陈的口径，不是本版新引入的。");
        NOT_MECHANICALLY_COVERED.put("4946 统计口径版本闸门",
                "4946『跨口径变更日期的区间查询须提示分段查』依赖【口径变更生效日期】这个常量，"
                + "该常量属实现车道所有，本车道不知道也不该猜它的取值，故本类不做机械断言。"
                + "本类改为断言口径变更必须随返回体下发（见 caliberChangeIsDeclaredInTheResponseBody），"
                + "4946 的闸门行为请主控在实现车道的用例里补钉。已写进 cross_lane。");
        NOT_MECHANICALLY_COVERED.put("1424★ / 1425★ / 1427★ / 1437★ / 1448★ / 1449★ 的逐日分桶",
                "这几条的汇总是【日表】（按 op_day 一天一行），而穿透明细里没有 op_day 列，"
                + "无法把明细逐条归到某一天再与该天的行对账。"
                + "本类的处置：主对账窗口刻意取【单日】（此时日表只会有一行，分桶恒等于总数，逐列判据全部成立），"
                + "另跑一遍【三日窗口】只对总量（见 totalsReconcileAcrossAMultiDayWindow）。"
                + "要能逐日对账须给明细补 op_day（= 与汇总同一串 coalesce 锚点）一列。");
    }

    // ==================================================================================
    // 夹具
    // ==================================================================================

    @BeforeEach
    void setUp() {
        d0 = jdbc.queryForObject("select (current_date - 300)::text", String.class);
        d1 = jdbc.queryForObject("select (current_date - 299)::text", String.class);
        d2 = jdbc.queryForObject("select (current_date - 298)::text", String.class);
        // 首台准点阈值是跨用例存活的单例缓存，先清干净——否则本类的判定随上一个用例漂移
        jdbc.update("delete from sys_config where cfg_key = 'anes.qc.ontime_minutes'");
        configReader.evict("anes.qc.ontime_minutes");
        assertWindowIsEmpty();
        seed();
    }

    /**
     * 开跑前证明夹具窗口是干净的。<b>不做这一步，对账测的就是环境不是代码</b>：
     * 库里只要有一台别人的手术落在窗口里，逐列判据会红得莫名其妙，而下一个人会以为是测试不稳。
     */
    private void assertWindowIsEmpty() {
        Long surgeries = jdbc.queryForObject("""
                select count(*) from inp_surgery s
                where coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) >= ?::date
                  and coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) <  ?::date + 1
                """, Long.class, d0, d2);
        Long adverse = jdbc.queryForObject(
                "select count(*) from qc_adverse_event where occurred_on between ?::date and ?::date",
                Long.class, d0, d2);
        assertEquals(0L, surgeries, "夹具窗口 " + d0 + "~" + d2 + " 内不得有其它手术，否则对账测的是环境");
        assertEquals(0L, adverse, "夹具窗口内不得有其它不良事件");
    }

    private static long seq = 0;

    private static String uniq(String prefix) {
        return prefix + (System.nanoTime() % 100000000L) + (seq++);
    }

    private Long newPatient(String sex, int ageYears) {
        return jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, ?, current_date - make_interval(years => ?))
                returning id
                """, Long.class, uniq("V49P"), "口径对账" + uniq(""), sex, ageYears);
    }

    private Long newAdmission(Long patientId) {
        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        Long bedId = jdbc.queryForObject("select id from inp_bed order by id limit 1", Long.class);
        assertNotNull(bedId, "测试库须有床位种子");
        return jdbc.queryForObject("""
                insert into inp_admission(admission_no, patient_id, dept_id, ward_id, bed_id, status, admit_at)
                values (?, ?, ?, ?, ?, 'IN_HOSPITAL', now())
                returning id
                """, Long.class, uniq("V49A"), patientId, deptId, deptId, bedId);
    }

    /** 一台字段录全的手术（本版之后录入的形态）。时间参数一律 "HH:mm"，落在 {@link #d0} 当天。 */
    private Long surgery(Long admissionId, String name, String room, String sched, String in,
                         String start, String end, String out,
                         String level, String asa, String kind, String anesthesia) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status,
                                        scheduled_at, room_no, in_room_at, start_at, end_at, out_room_at,
                                        surgery_level, asa_grade, surgery_kind, op_icd)
                values (?, ?, ?, 'DONE', ?::timestamptz, ?, ?::timestamptz, ?::timestamptz,
                        ?::timestamptz, ?::timestamptz, ?, ?, ?, '47.09')
                returning id
                """, Long.class, admissionId, name, anesthesia,
                at(d0, sched), room, at(d0, in), at(d0, start), at(d0, end), at(d0, out),
                level, asa, kind);
    }

    /** 本版<b>之前</b>的历史手术：新字段全 null，刻意不回填（零回填纪律） */
    private Long legacySurgery(Long admissionId) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status, scheduled_at)
                values (?, 'V49历史手术', '全身麻醉', 'DONE', ?::timestamptz)
                returning id
                """, Long.class, admissionId, at(d0, "07:00"));
    }

    private void cancelledSurgery(Long admissionId, String stage, String reason) {
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, status, scheduled_at, cancel_stage, cancel_reason)
                values (?, ?, 'CANCELLED', ?::timestamptz, ?, ?)
                """, admissionId, "V49取消术-" + stage, at(d0, "08:00"), stage, reason);
    }

    private void event(Long surgeryId, String type, String day, String hhmm, Boolean planned) {
        jdbc.update("""
                insert into surg_event(surgery_id, event_type, event_time, planned, detail)
                values (?, ?, ?::timestamptz, ?, 'V49对账夹具')
                """, surgeryId, type, at(day, hhmm), planned);
    }

    private void transfusion(Long surgeryId, String product, int ml, boolean isAuto) {
        jdbc.update("""
                insert into surg_transfusion(surgery_id, product_type, volume_ml, is_auto, transfused_at)
                values (?, ?, ?, ?, ?::timestamptz)
                """, surgeryId, product, ml, isAuto, at(d0, "10:00"));
    }

    private static String at(String day, String hhmm) {
        return hhmm == null ? null : day + " " + hhmm;
    }

    /**
     * 夹具刻意造出四个「口径陷阱」，每一个都对应一条 v46 真缺陷：
     * <ol>
     *   <li><b>同一患者同一次住院两台手术 + 一张死亡卡</b> → 1429★ 的 deaths 若按台次数会得 2，
     *       按死亡患者数才是 1。
     *   <li><b>同一患者跨两台手术输血、且有一条「自体洗涤红细胞」(RBC + is_auto)</b>
     *       → 1433★/1434★ 的患者粒度汇总与记录粒度明细必须能互相还原。
     *   <li><b>术后延迟事件</b>：次日拔管、次日非计划转 ICU、次日抢救、术后 48 小时拆镇痛泵
     *       → 事件日期 ≠ 手术日期，1437★/1446★/1447★/1449★ 的归桶口径当场现形。
     *   <li><b>一台历史手术（新字段全 null）+ 两台取消手术</b> → 「未填写」桶不许被丢掉。
     * </ol>
     */
    private void seed() {
        // 手术间 V49-OR-1 首台（ELECTIVE / 三级 / ASA II）与第二台（EMERGENCY / 二级 / ASA II），
        // 两台同属患者 P1 的同一次住院 —— 陷阱①的载体
        Long p1 = newPatient("M", 45);
        Long a1 = newAdmission(p1);
        Long s1 = surgery(a1, "V49阑尾切除术", "V49-OR-1", "08:00", "08:05", "08:10", "09:30", "09:40",
                "三级", "II", "ELECTIVE", "全身麻醉");
        Long s2 = surgery(a1, "V49阑尾切除术", "V49-OR-1", "10:00", "10:00", "10:10", "11:00", "11:10",
                "二级", "II", "EMERGENCY", "椎管内麻醉");

        Long p2 = newPatient("F", 33);
        Long a2 = newAdmission(p2);
        Long s3 = surgery(a2, "V49清创缝合术", "V49-OR-2", "08:00", "08:00", "08:10", "09:00", "09:10",
                null, "I", "DAY", "局部麻醉");
        legacySurgery(a2);
        cancelledSurgery(a2, "PRE_IN", "患者血压过高");
        cancelledSurgery(a2, "IN_OP", null);

        // 跨日手术：d0 23:00 开台 → d1 01:00 结束（1427★）
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status, scheduled_at,
                                        room_no, in_room_at, start_at, end_at, out_room_at,
                                        surgery_level, asa_grade, surgery_kind, op_icd)
                values (?, 'V49跨日术', '全身麻醉', 'DONE', ?::timestamptz, 'V49-OR-3',
                        ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz,
                        '三级', 'III', 'ELECTIVE', '47.09')
                """, a2, at(d0, "22:30"), at(d0, "22:40"), at(d0, "23:00"), at(d1, "01:00"), at(d1, "01:10"));

        // 陷阱①：同一患者、同一次住院的两台手术，只有一张死亡卡
        jdbc.update("""
                insert into mr_death_card(patient_id, admission_id, died_at, direct_cause)
                values (?, ?, ?::timestamptz, 'V49对账夹具死因')
                """, p1, a1, at(d1, "06:00"));

        // 陷阱②：患者 P1 跨两台手术输血，其中一条是自体洗涤红细胞（product_type=RBC 而 is_auto=true）
        transfusion(s1, "AUTO", 600, true);
        transfusion(s1, "RBC", 400, false);
        transfusion(s1, "RBC", 300, true);
        transfusion(s2, "RBC", 200, false);
        transfusion(s3, "PLASMA", 200, false);

        // 术中事件（都在手术当天）
        event(s1, "INTUBATE_OR", d0, "08:05", Boolean.TRUE);
        event(s1, "INVASIVE", d0, "08:30", null);
        event(s1, "RESCUE", d0, "09:00", null);
        event(s1, "EXTUBATE", d0, "10:05", null);
        event(s1, "REINTUBATE", d0, "10:08", Boolean.FALSE);
        event(s1, "OUT_WITH_TUBE", d0, "10:10", null);
        event(s1, "PAIN_PUMP_ON", d0, "10:15", null);
        event(s1, "TO_ICU", d0, "10:30", Boolean.FALSE);
        event(s1, "TO_PACU", d0, "10:35", null);

        // 陷阱③：术后延迟发生的事件 —— 事件日期与手术日期不在同一天
        event(s1, "EXTUBATE", d1, "09:00", null);              // 次日 ICU 内延迟拔管
        event(s1, "TO_ICU", d1, "14:00", Boolean.FALSE);       // 术后病情恶化，非计划转 ICU
        event(s1, "RESCUE", d1, "15:00", null);                // 术后抢救
        event(s1, "PAIN_PUMP_OFF", d2, "10:00", null);         // 术后 48 小时拆镇痛泵

        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        jdbc.update("""
                insert into qc_adverse_event(type, level, occurred_on, dept_id, description, status)
                values ('V49麻醉意外', 2, ?::date, ?, 'V49对账夹具', 'NEW')
                """, d0, deptId);
        jdbc.update("""
                insert into qc_adverse_event(type, level, occurred_on, dept_id, description, status)
                values ('V49麻醉意外', 1, ?::date, ?, 'V49对账夹具', 'HANDLED')
                """, d1, deptId);
    }

    // ==================================================================================
    // HTTP 调用（走 MockMvc：签名怎么改都不影响本类，测的是对接方真正用的那层契约）
    // ==================================================================================

    private Map<String, Object> apiRaw(String path, String... kv) {
        MockHttpServletRequestBuilder req = get("/api/anes-qc/" + path);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) req = req.param(kv[i], kv[i + 1]);
        }
        try {
            String json = mvc.perform(req).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            return om.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("调用 /api/anes-qc/" + path + " 失败", e);
        }
    }

    private static int code(Map<String, Object> body) {
        return ((Number) body.get("code")).intValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> body) {
        assertEquals(0, code(body), "接口应成功：" + body.get("message"));
        // 4945「明细粒度与汇总粒度不匹配」是内部一致性断言失败才返的码，正常路径永不应出现
        assertNotEquals4945(body);
        return (Map<String, Object>) body.get("data");
    }

    private static void assertNotEquals4945(Map<String, Object> body) {
        assertTrue(code(body) != 4945,
                "4945（明细粒度与汇总粒度不匹配）是内部一致性断言，正常路径不应出现：" + body.get("message"));
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> indicator(String code, String from, String to, String... extra) {
        var d = data(apiRaw("indicators", concat(new String[]{"from", from, "to", to, "indicator", code}, extra)));
        var list = (List<Map<String, Object>>) d.get("indicators");
        assertEquals(1, list.size(), "按编码筛应只回一条");
        return list.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String code, String from, String to, String... extra) {
        Object r = indicator(code, from, to, extra).get("rows");
        assertNotNull(r, code + "★ 应有 rows");
        return (List<Map<String, Object>>) r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryOf(String code, String from, String to, String... extra) {
        return (Map<String, Object>) indicator(code, from, to, extra).get("summary");
    }

    /** 穿透明细；顺带钉死「明细被截断时对账不成立」——截断了还对账等于自欺 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String code, String from, String to, String... extra) {
        var d = data(apiRaw("detail", concat(new String[]{"indicator", code, "from", from, "to", to}, extra)));
        assertEquals(Boolean.FALSE, d.get("truncated"),
                code + "★ 的明细被截断了——截断状态下任何「汇总 == 明细」的判据都不成立，夹具须缩窗");
        return (List<Map<String, Object>>) d.get("items");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> catalog() {
        var body = apiRaw("catalog");
        assertEquals(0, code(body));
        return (List<Map<String, Object>>) body.get("data");
    }

    // ==================================================================================
    // 取值小工具
    // ==================================================================================

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static Double dbl(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Boolean bool(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    /** {@code round(x, scale)}：与 PG 的 {@code round(numeric, n)} 同为四舍五入 */
    private static Double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** 百分比派生：分母为 0 时 PG 的 {@code nullif} 让整式为 null，此处同口径回 null */
    private static Double pct(long numerator, long denominator) {
        return denominator == 0 ? null : round(100.0 * numerator / denominator, 2);
    }

    /** 时间戳：JdbcTemplate 直出经 Jackson 序列化，可能是 ISO 串也可能是毫秒数，两种都接 */
    private static Instant instantOf(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        String s = String.valueOf(v);
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return Instant.parse(s);
        }
    }

    /** 事件/时间戳落在业务时区的哪一天。<b>绝不用 JVM 默认时区</b>——CI 跑 UTC，用默认时区必差一天。 */
    private static LocalDate bizDay(Object v) {
        Instant i = instantOf(v);
        return i == null ? null : i.atZone(BIZ).toLocalDate();
    }

    private static long countWhere(List<Map<String, Object>> items, Function<Map<String, Object>, Boolean> p) {
        return items.stream().filter(m -> Boolean.TRUE.equals(p.apply(m))).count();
    }

    private static long distinct(List<Map<String, Object>> items, String key) {
        var s = new HashSet<Object>();
        for (var m : items) if (m.get(key) != null) s.add(m.get(key));
        return s.size();
    }

    /**
     * 1433★/1434★ 的<b>患者去重键</b>。
     *
     * <p>汇总是 {@code group by a.patient_id} 的<b>患者粒度</b>，明细若不带同一个去重键，
     * 「输血患者数」这类列在机械上<b>根本无法核对</b>——这正是 v46 的口径缺陷（4945 的由来）。
     * 明细做成记录粒度还是患者粒度都行，但必须带 {@code patient_id}（或 {@code patient_no}）。
     */
    private static String patientKey(Map<String, Object> item) {
        for (String k : new String[]{"patient_id", "patient_no"}) {
            Object v = item.get(k);
            if (v != null) return k + "=" + v;
        }
        return fail("输血类指标的穿透明细缺【患者去重键】（patient_id / patient_no）：\n"
                + "  汇总按 a.patient_id 去重是患者粒度，明细却是记录粒度，两者机械上对不上账。\n"
                + "  这就是 v46 的 1433★/1434★ 口径缺陷本身——评委点开穿透一核对就当场露馅。\n"
                + "  补法：transfusionDetail 的投影补 a.patient_id 一列（纯读侧投影，不改写路径）。\n"
                + "  明细行现有列：" + new TreeSet<>(item.keySet()));
    }

    /** 输注量：记录粒度取 volume_ml，患者粒度取 total_ml —— 两种明细形态都能对账 */
    private static long volumeOf(Map<String, Object> item) {
        if (item.get("volume_ml") != null) return num(item, "volume_ml");
        if (item.get("total_ml") != null) return num(item, "total_ml");
        return fail("输血明细既无 volume_ml（记录粒度）也无 total_ml（患者粒度），量对不出来："
                + new TreeSet<>(item.keySet()));
    }

    /** 是否含自体血：患者粒度看 has_auto / auto_ml，记录粒度看 is_auto。三种写法都接，但都不看 product_type */
    private static boolean hasAuto(Map<String, Object> item) {
        Boolean h = bool(item.get("has_auto"));
        if (h != null) return h;
        Boolean b = bool(item.get("is_auto"));
        if (b != null) return b;
        if (item.get("auto_ml") != null) return num(item, "auto_ml") > 0;
        return fail("输血明细无法判定自体血：既无 has_auto / is_auto 也无 auto_ml。"
                + "【自体血只认 is_auto 布尔位，不许按 product_type='AUTO' 字符串比对】"
                + "——自体洗涤红细胞是按 RBC + is_auto=true 录的，字符串比对会整片漏统计。");
    }

    /** 是否含非自体血 */
    private static boolean hasNonAuto(Map<String, Object> item) {
        Boolean h = bool(item.get("has_non_auto"));
        if (h != null) return h;
        Boolean b = bool(item.get("is_auto"));
        if (b != null) return !b;
        if (item.get("non_auto_ml") != null) return num(item, "non_auto_ml") > 0;
        if (item.get("total_ml") != null) return volumeOf(item) - num(item, "auto_ml") > 0;
        return fail("输血明细无法判定非自体血：" + new TreeSet<>(item.keySet()));
    }

    /** 1429★：该明细行是否为「本次住院的代表行」（计入患者数的那一行） */
    private static boolean isRepresentative(Map<String, Object> item) {
        Boolean b = bool(item.get("counts_as_patient"));
        if (b != null) return b;
        return fail("1429★ 的穿透明细缺 counts_as_patient 列：\n"
                + "  汇总的 patients / deaths 是【患者（人次）粒度】，明细是【台次粒度】，\n"
                + "  明细不标出「哪一行代表这次住院」，人数就永远数不出来、对不上账。\n"
                + "  明细行现有列：" + new TreeSet<>(item.keySet()));
    }

    /** 1429★：该明细行的「归属分级」（一次住院取最重一级）是否等于该汇总行的 ASA 分级 */
    private static boolean sameGrade(Map<String, Object> item, Map<String, Object> row) {
        Object g = item.get("attributed_asa_grade");
        if (g == null) {
            return fail("1429★ 的穿透明细缺 attributed_asa_grade 列：\n"
                    + "  同一次住院多台手术 ASA 不同时，人数只能归到其中一级，明细必须说清归到了哪一级，\n"
                    + "  否则各级 patients 之和与实际人数对不上，也说不清是哪一台代表了这次住院。\n"
                    + "  明细行现有列：" + new TreeSet<>(item.keySet()));
        }
        return String.valueOf(g).equals(str(row, "asa_grade"));
    }

    // ==================================================================================
    // 对账规则 DSL
    // ==================================================================================

    /** 派生列判据：由同一行的其它列 / 全部汇总行 / 该桶明细算出期望值 */
    @FunctionalInterface
    private interface Derived {
        Double of(Map<String, Object> row, List<Map<String, Object>> rows, List<Map<String, Object>> items);
    }

    /** 明细行的分桶键：可以要用到全量明细（1433★ 要先按患者合并再分档） */
    @FunctionalInterface
    private interface ItemKey {
        String of(Map<String, Object> item, List<Map<String, Object>> allItems);
    }

    /**
     * 计数判据。多数列只看「该桶的明细子集」，但 1429★ 的 {@code patients}/{@code deaths}
     * 走的是<b>另一个分桶键</b>（attributed_asa_grade 归属分级，与 cases 的 asa_grade 不同列），
     * 故判据要能同时拿到本行与全量明细。
     */
    @FunctionalInterface
    private interface Counted {
        long of(Map<String, Object> row, List<Map<String, Object>> subset, List<Map<String, Object>> all);
    }

    /**
     * 一条指标的对账规则。<b>汇总行里每一个非标签列都必须在这四类判据里有归属</b>：
     * {@link #counts}（该桶明细行数/去重数）、{@link #totals}（各行合计 == 明细某个量）、
     * {@link #derived}（由其它列算出）、{@link #unreconcilable}（登记原因，见类头清单）。
     * 一个都不沾的列 = 当场 fail，不许静默跳过。
     */
    private static final class Rule {
        final String code;
        /** 标签/键列，不进普查（分桶键、中文名、日期标签等） */
        final Set<String> labels = new LinkedHashSet<>();
        /** 汇总行的分桶键；null = 单桶（该行对全部明细） */
        Function<Map<String, Object>, String> rowKey;
        /** 明细行的分桶键；rowKey 非 null 时必须给 */
        ItemKey itemKey;
        /** 逐桶计数/去重判据：汇总列 == f(该桶的明细子集) */
        final Map<String, Counted> counts = new LinkedHashMap<>();
        /** 合计判据：各汇总行该列之和 == f(全部明细)。用于分桶无法逐桶还原（1436★）或日表 */
        final Map<String, ToLongFunction<List<Map<String, Object>>>> totals = new LinkedHashMap<>();
        /** 派生列判据 */
        final Map<String, Derived> derived = new LinkedHashMap<>();
        /** 登记在案的无法对账列：列名 → 原因 */
        final Map<String, String> unreconcilable = new LinkedHashMap<>();
        /** 由本类另一个具名用例覆盖的列：列名 → 那个用例的方法名（会用反射验它真的存在） */
        final Map<String, String> coveredBy = new LinkedHashMap<>();
        /** summary 对象的规则（恒单桶） */
        Rule summaryRule;
        /** 三日窗口的总量判据：f(全部汇总行) == g(全部明细)；null = 该指标不做总量级校验 */
        ToLongFunction<List<Map<String, Object>>> rowsTotal;
        ToLongFunction<List<Map<String, Object>>> itemsTotal = List::size;

        Rule(String code) {
            this.code = code;
        }

        Rule labels(String... cols) {
            this.labels.addAll(List.of(cols));
            return this;
        }

        Rule key(String rowCol, String itemCol, String nullLabel) {
            this.rowKey = row -> str(row, rowCol);
            this.itemKey = (item, all) -> {
                String v = str(item, itemCol);
                return v == null || v.isBlank() ? nullLabel : v.trim();
            };
            this.labels.add(rowCol);
            return this;
        }

        Rule count(String col, ToLongFunction<List<Map<String, Object>>> f) {
            counts.put(col, (row, subset, all) -> f.applyAsLong(subset));
            return this;
        }

        /** 判据要用到本行或全量明细时用这个（1429★ 的 patients/deaths 走归属分级这个另一个键） */
        Rule countRow(String col, Counted f) {
            counts.put(col, f);
            return this;
        }

        /** 该列由本类另一个具名用例覆盖 —— 记下是哪一个，不许含糊带过 */
        Rule coveredBy(String col, String testMethod) {
            coveredBy.put(col, testMethod);
            return this;
        }

        Rule total(String col, ToLongFunction<List<Map<String, Object>>> f) {
            totals.put(col, f);
            return this;
        }

        Rule derived(String col, Derived f) {
            derived.put(col, f);
            return this;
        }

        Rule cannotReconcile(String col) {
            String reason = UNRECONCILABLE_COLUMNS.get(code + "." + col);
            assertNotNull(reason, "列 " + code + "." + col + " 要标成「无法对账」必须先登记原因");
            unreconcilable.put(col, reason);
            return this;
        }

        Rule rowsTotal(ToLongFunction<List<Map<String, Object>>> f) {
            this.rowsTotal = f;
            return this;
        }

        Rule itemsTotal(ToLongFunction<List<Map<String, Object>>> f) {
            this.itemsTotal = f;
            return this;
        }
    }

    private static long sum(List<Map<String, Object>> rows, String col) {
        return rows.stream().mapToLong(r -> num(r, col)).sum();
    }

    /** 逐桶计数（该桶明细条数） */
    private static final ToLongFunction<List<Map<String, Object>>> SIZE = List::size;

    // ==================================================================================
    // 【逐条指标的对账判据】—— 判据因指标而异，每一条都写清为什么这么判
    // ==================================================================================

    private static Map<String, Rule> rules() {
        Map<String, Rule> m = new LinkedHashMap<>();

        // 1424★ 每日首台量与开台准点率。
        // 判据：明细就是各手术间当日的 rn=1 那一台，逐台带 judgement（准点/延迟/无法判定），
        //       所以汇总的四个计数列可以逐条从明细数出来；两个率是纯派生。
        // 分桶：日表（op_day）；主窗口取单日，此时只有一行，分桶恒等于总量。
        m.put("1424", new Rule("1424")
                .labels("op_day", "first_case_depts")
                .count("first_cases", SIZE)
                .count("on_time", it -> countWhere(it, x -> "准点".equals(str(x, "judgement"))))
                .count("delayed", it -> countWhere(it, x -> "延迟".equals(str(x, "judgement"))))
                .count("unjudgeable", it -> countWhere(it,
                        x -> str(x, "judgement") != null && str(x, "judgement").startsWith("无法判定")))
                .count("elective_first_cases", it -> countWhere(it, x -> "ELECTIVE".equals(str(x, "surgery_kind"))))
                .count("elective_on_time", it -> countWhere(it,
                        x -> "ELECTIVE".equals(str(x, "surgery_kind")) && "准点".equals(str(x, "judgement"))))
                .count("elective_delayed", it -> countWhere(it,
                        x -> "ELECTIVE".equals(str(x, "surgery_kind")) && "延迟".equals(str(x, "judgement"))))
                // 准点率的分母只含判得了的台次：不把「判不了」算成不准点，这条口径必须机械钉住
                .derived("on_time_rate_pct", (row, rows, it) -> pct(num(row, "on_time"),
                        num(row, "on_time") + num(row, "delayed")))
                .derived("elective_on_time_rate_pct", (row, rows, it) -> pct(num(row, "elective_on_time"),
                        num(row, "elective_on_time") + num(row, "elective_delayed")))
                .rowsTotal(rs -> sum(rs, "first_cases")));
        Rule r1424 = m.get("1424");
        r1424.summaryRule = new Rule("1424.summary")
                .count("first_cases", SIZE)
                .countRow("on_time", r1424.counts.get("on_time"))
                .countRow("delayed", r1424.counts.get("delayed"))
                .countRow("unjudgeable", r1424.counts.get("unjudgeable"))
                .countRow("elective_first_cases", r1424.counts.get("elective_first_cases"))
                .countRow("elective_on_time", r1424.counts.get("elective_on_time"))
                .countRow("elective_delayed", r1424.counts.get("elective_delayed"))
                .derived("on_time_rate_pct", r1424.derived.get("on_time_rate_pct"))
                .derived("elective_on_time_rate_pct", r1424.derived.get("elective_on_time_rate_pct"));

        // 1425★ 手术台数 / 总手术时长 / 接台时长。
        // 判据：明细逐台带 op_minutes 与 turnover_minutes，
        //       台数 = 明细条数；有时长台次 = op_minutes 有值的条数；总时长 = 逐条求和；
        //       接台次数 = turnover_minutes 有值的条数（接台的分母是「相邻对」不是「台数」）。
        // 注：夹具的时长一律取整分钟，避免「汇总先求和后取整」与「明细逐条取整」的舍入差。
        m.put("1425", new Rule("1425")
                .labels("op_day")
                .count("cases", SIZE)
                .count("timed_cases", it -> countWhere(it, x -> dbl(x, "op_minutes") != null && num(x, "op_minutes") > 0))
                .count("turnovers", it -> countWhere(it, x -> dbl(x, "turnover_minutes") != null))
                .derived("total_op_minutes", (row, rows, it) -> round(it.stream()
                        .filter(x -> dbl(x, "op_minutes") != null && num(x, "op_minutes") > 0)
                        .mapToDouble(x -> dbl(x, "op_minutes")).sum(), 1))
                .derived("total_turnover_minutes", (row, rows, it) -> round(it.stream()
                        .filter(x -> dbl(x, "turnover_minutes") != null)
                        .mapToDouble(x -> dbl(x, "turnover_minutes")).sum(), 1))
                .derived("avg_op_minutes", (row, rows, it) -> num(row, "timed_cases") == 0 ? null
                        : round(dbl(row, "total_op_minutes") / num(row, "timed_cases"), 1))
                .derived("avg_turnover_minutes", (row, rows, it) -> num(row, "turnovers") == 0 ? null
                        : round(dbl(row, "total_turnover_minutes") / num(row, "turnovers"), 1))
                .rowsTotal(rs -> sum(rs, "cases")));

        // 1426★ 取消手术四阶段构成。判据：明细逐条带 cancel_stage 与 cancel_reason。
        m.put("1426", new Rule("1426")
                .labels("stage_name")
                .key("cancel_stage", "cancel_stage", "（未标注）")
                .count("cases", SIZE)
                .count("with_reason", it -> countWhere(it,
                        x -> str(x, "cancel_reason") != null && !str(x, "cancel_reason").isBlank()))
                .rowsTotal(rs -> sum(rs, "cases")));

        // 1427★ 跨日手术。判据：明细只含跨日台次，条数即 cross_day_cases；
        //        avg_hours 由明细的开台/结束时间算回来。
        //        分母 timed_cases 只出现在 summary 且明细里没有对应行 → 走跨指标核对（见类头清单）。
        m.put("1427", new Rule("1427")
                .labels("op_day")
                .count("cross_day_cases", SIZE)
                .derived("avg_hours", (row, rows, it) -> it.isEmpty() ? null : round(it.stream()
                        .mapToDouble(x -> (instantOf(x.get("end_at")).toEpochMilli()
                                - instantOf(x.get("start_at")).toEpochMilli()) / 3600000.0)
                        .average().orElse(0), 1))
                .rowsTotal(rs -> sum(rs, "cross_day_cases")));
        m.get("1427").summaryRule = new Rule("1427")
                .count("cross_day_cases", SIZE)
                .cannotReconcile("timed_cases")
                .derived("cross_day_pct", (row, rows, it) -> pct(num(row, "cross_day_cases"), num(row, "timed_cases")));

        // 1428★ 各类手术数量（择期/急诊/日间）。v46 唯一被机械核对过的一条，判据照旧。
        m.put("1428", new Rule("1428")
                .labels("kind_name")
                .key("surgery_kind", "surgery_kind", "（未填写）")
                .count("cases", SIZE)
                .derived("pct", (row, rows, it) -> pct(num(row, "cases"), sum(rows, "cases")))
                .rowsTotal(rs -> sum(rs, "cases")));

        // 1429★ ASA 分级分布与死亡关联。
        // 【判据要点】deaths 的口径是【死亡患者数】不是【手术台次数】：
        //   一名患者一次住院做 3 台手术后死亡，按台次数会把同一条死亡记成 3 例，
        //   而《2022 版》「各 ASA 分级患者麻醉死亡率」的分子分母都是患者，台次口径直接放大死亡数。
        // 【两个分桶键，刻意不同】cases 按每台手术自己的 asa_grade 分；
        //   patients / deaths 按【归属分级】attributed_asa_grade 分（一次住院取最重的一级），
        //   否则同一名患者会在多个分级里各记一次人数，各级合计就大于实际人数。
        //   明细每行给 attributed_asa_grade 与 counts_as_patient（是否为该次住院的代表行），
        //   故人数与死亡数都能从明细逐条数出来 —— 这就是「粒度不同也要对得上账」的实现形态。
        m.put("1429", new Rule("1429")
                .key("asa_grade", "asa_grade", "（未填写）")
                .count("cases", SIZE)
                .countRow("patients", (row, subset, all) -> countWhere(all,
                        x -> isRepresentative(x) && sameGrade(x, row)))
                .countRow("deaths", (row, subset, all) -> countWhere(all,
                        x -> isRepresentative(x) && sameGrade(x, row)
                                && Boolean.TRUE.equals(bool(x.get("died")))))
                .derived("death_rate_pct", (row, rows, it) -> pct(num(row, "deaths"), num(row, "patients")))
                .rowsTotal(rs -> sum(rs, "cases")));

        // 1430★ 麻醉方式分布。判据：明细逐台带 anesthesia_type（自由文本，空归「（未填写）」）。
        m.put("1430", new Rule("1430")
                .key("anesthesia_type", "anesthesia_type", "（未填写）")
                .count("cases", SIZE)
                .derived("pct", (row, rows, it) -> pct(num(row, "cases"), sum(rows, "cases")))
                .rowsTotal(rs -> sum(rs, "cases")));

        // 1431★ 局麻手术按临床科室分布。判据：明细逐台带 dept_name（同一个 left join 出来的值）。
        m.put("1431", new Rule("1431")
                .key("dept_name", "dept_name", "（未知科室）")
                .count("cases", SIZE)
                .derived("pct", (row, rows, it) -> pct(num(row, "cases"), sum(rows, "cases")))
                .rowsTotal(rs -> sum(rs, "cases")));

        // 1432★ 术中血制品类型与总量。判据：明细是逐条输血记录，与汇总同粒度，可逐列还原。
        //        auto_records / auto_ml 必须按 is_auto 布尔位数——自体洗涤红细胞录的是 RBC+is_auto。
        m.put("1432", new Rule("1432")
                .labels("product_name")
                .key("product_type", "product_type", "（未填写）")
                .count("records", SIZE)
                .count("surgeries", it -> distinct(it, "surgery_id"))
                .count("total_ml", it -> it.stream().mapToLong(V49CaliberTest::volumeOf).sum())
                .count("auto_records", it -> countWhere(it, V49CaliberTest::hasAuto))
                .count("auto_ml", it -> it.stream().filter(V49CaliberTest::hasAuto)
                        .mapToLong(V49CaliberTest::volumeOf).sum())
                .rowsTotal(rs -> sum(rs, "records")));

        // 1433★ 自体血按输注量分档统计人数。
        // 【判据要点】汇总是【先按患者合并全窗口的自体血量、再分档】的患者粒度，
        //   故明细也必须是患者粒度（一行一名患者），否则「400ml 以下 3 人」对着 7 条记录，
        //   点开穿透当场对不上账 —— 那正是 v46 的缺陷。
        //   patients 不只数行数，还【顺手复核明细自己的 band 列】：
        //   用明细的 total_ml 重算档位，与它自报的 band 不一致就当场红——
        //   两处分档表达式一旦漂移（比如一边 <=1000 一边 <1000），只有这样才抓得住。
        m.put("1433", new Rule("1433")
                .key("band", "band", "（未分档）")
                .countRow("patients", (row, subset, all) -> subset.stream()
                        .filter(x -> bandOf(volumeOf(x)).equals(str(row, "band"))).count())
                .count("total_ml", it -> it.stream().mapToLong(V49CaliberTest::volumeOf).sum())
                .rowsTotal(rs -> sum(rs, "patients"))
                .itemsTotal(it -> it.stream().map(V49CaliberTest::patientKey).distinct().count()));

        // 1434★ 输血患者数 / 自体血患者数 / 非自体血患者数。
        // 【判据要点】口径是【患者数】不是【台次数】，且自体/非自体两类可重叠，
        //   both_patients 显式给出重叠人数（两列相加 != 总数）。明细为患者粒度，逐列可还原：
        //   行数 == transfused_patients，自体位为真的行数 == auto_patients，……
        //   台次数 transfused_surgeries 是各行 surgeries 之和（患者粒度明细里没有 surgery_id 逐行可数）。
        m.put("1434", new Rule("1434")
                .count("transfused_patients", it -> it.stream().map(V49CaliberTest::patientKey).distinct().count())
                .count("auto_patients", it -> patientsMatching(it, true, false))
                .count("non_auto_patients", it -> patientsMatching(it, false, true))
                .count("both_patients", it -> patientsMatching(it, true, true))
                .count("transfused_surgeries", it -> sum(it, "surgeries"))
                .rowsTotal(rs -> sum(rs, "transfused_patients"))
                .itemsTotal(it -> it.stream().map(V49CaliberTest::patientKey).distinct().count()));

        // 1436★ 手术患者年龄段 × 性别分布。
        // 【只能对总数、对不到分桶】：明细的公共投影里既无出生日期也无性别（见类头清单二）。
        m.put("1436", new Rule("1436")
                .labels("age_band", "sex")
                .total("cases", SIZE)
                .rowsTotal(rs -> sum(rs, "cases")));

        // 1437★ 每日镇痛泵新增 / 拆泵 / 在用量。
        // 【判据要点】新增/拆泵是【事件当日】的计数，明细逐条带 event_type，可逐日数出来；
        //   in_use 是无下界的全历史累计，与任何时间窗内的明细都不同 population → 登记在案。
        m.put("1437", new Rule("1437")
                .labels("op_day")
                .count("added", it -> countWhere(it, x -> "PAIN_PUMP_ON".equals(str(x, "event_type"))))
                .count("removed", it -> countWhere(it, x -> "PAIN_PUMP_OFF".equals(str(x, "event_type"))))
                // in_use 走另一条穿透（bucket=in_use，截至 to 当日末仍在用的每一台泵），
                // 不带 bucket 的明细是 ON/OFF 事件流水，两者不同 population，故在此另案覆盖
                .coveredBy("in_use", "painPumpInUseIsDrillableAndReconciles")
                .coveredBy("orphan_off", "orphanOffIsolatesOnlyOffPumpsWithoutEatingInUse")
                .rowsTotal(rs -> sum(rs, "added") + sum(rs, "removed")));

        // 1438★ 手术数量（按术式）。分桶键是 (术式名, op_icd) 二元组——
        //   同一术式的不同写法本就该分成两行（ICD9_NOTE）。
        // 【只能对总量、对不到分桶】不带 bucket 的明细走公共投影 SURG_SELECT，其中【没有 op_icd 列】，
        //   二元键的后一半在明细里取不到，逐桶还原不了（登记在案，见类头清单一）。
        //   分桶级由 bucket 参数覆盖（bucketDrillsIntoExactlyOneShareOfTheRatio 用 " / " 拼二元键穿透）。
        Rule r1438 = new Rule("1438")
                .total("cases", SIZE)
                .total("done_cases", it -> countWhere(it, x -> "DONE".equals(str(x, "status"))));
        r1438.labels.addAll(List.of("procedure_name", "op_icd"));
        m.put("1438", r1438);
        m.get("1438").summaryRule = new Rule("1438")
                .count("cases", SIZE)
                .count("procedures", it -> it.stream()
                        .map(x -> blankTo(str(x, "procedure_name"), null)).filter(java.util.Objects::nonNull)
                        .distinct().count())
                // v49 主控补 SURG_SELECT 的 s.op_icd 列后，本列不再是「无法对账」：
                // 直接数明细里 op_icd 非空的行即可。
                .count("with_op_icd", it -> countWhere(it,
                        x -> blankTo(str(x, "op_icd"), null) != null));

        // 1439★ 手术级别分布。unplanned_reop 已可对账（v49 主控给 SURG_SELECT 补了该列）。
        m.put("1439", new Rule("1439")
                .key("surgery_level", "surgery_level", "（未分级）")
                .count("cases", SIZE)
                .derived("pct", (row, rows, it) -> pct(num(row, "cases"), sum(rows, "cases")))
                // v49 主控补 SURG_SELECT 的 s.is_unplanned_reop 列后，本列不再是「无法对账」。
                .count("unplanned_reop", it -> countWhere(it,
                        x -> Boolean.TRUE.equals(x.get("is_unplanned_reop"))))
                .rowsTotal(rs -> sum(rs, "cases")));

        // 1446★ 计划 / 非计划转入 ICU 与苏醒室。
        // 【判据要点】planned 是可空三态，汇总把 null 单列「未区分」。
        //   明细逐条带 event_type 与 planned，故分桶键 = 事件类型 + 三态标签，必须逐桶对上。
        //   汇总行只给中文 target（转入 ICU / 转入苏醒室（PACU）），故这里做一次显式反查。
        Rule r1446 = new Rule("1446")
                .count("events", SIZE)
                .count("surgeries", it -> distinct(it, "surgery_id"))
                .rowsTotal(rs -> sum(rs, "events"));
        r1446.labels.addAll(List.of("target", "planned_name"));
        r1446.rowKey = row -> (str(row, "target").startsWith("转入 ICU") ? "TO_ICU" : "TO_PACU")
                + "|" + str(row, "planned_name");
        r1446.itemKey = (item, all) -> str(item, "event_type") + "|" + plannedLabel(item);
        m.put("1446", r1446);

        // 1447★ 插管 / 拔管 / 带管出室各类计数 + 计划性三态拆分。
        // 【判据要点】三态之和恒等于事件总数：写成 not planned 会吞掉 null 行，
        //   而被吞掉的恰好是「非计划再插管」这类最该被看见的部分。
        m.put("1447", new Rule("1447")
                .labels("event_name")
                .key("event_type", "event_type", "（未知）")
                .count("events", SIZE)
                .count("surgeries", it -> distinct(it, "surgery_id"))
                .count("planned_events", it -> countWhere(it, x -> Boolean.TRUE.equals(bool(x.get("planned")))))
                .count("unplanned_events", it -> countWhere(it, x -> Boolean.FALSE.equals(bool(x.get("planned")))))
                .count("unspecified_events", it -> countWhere(it, x -> x.get("planned") == null))
                .rowsTotal(rs -> sum(rs, "events")));

        // 1448★ 术中有创操作 / 1449★ 术中抢救：同形日表，明细逐条事件。
        for (String c : new String[]{"1448", "1449"}) {
            m.put(c, new Rule(c)
                    .labels("op_day")
                    .count("events", SIZE)
                    .count("surgeries", it -> distinct(it, "surgery_id"))
                    .rowsTotal(rs -> sum(rs, "events")));
        }

        // 1450★ 不良事件按名称与数量。全院口径（无 surgery_id 关联），只能自洽对账（见类头清单二）。
        m.put("1450", new Rule("1450")
                .key("event_type", "event_type", "（未填写）")
                .count("events", SIZE)
                .count("level1", it -> countWhere(it, x -> num(x, "level") == 1))
                .count("level2", it -> countWhere(it, x -> num(x, "level") == 2))
                .count("level3", it -> countWhere(it, x -> num(x, "level") == 3))
                .count("level4", it -> countWhere(it, x -> num(x, "level") == 4))
                .count("handled", it -> countWhere(it, x -> "HANDLED".equals(str(x, "status"))))
                .rowsTotal(rs -> sum(rs, "events")));

        return m;
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String plannedLabel(Map<String, Object> item) {
        Boolean p = bool(item.get("planned"));
        return p == null ? "未区分" : (p ? "计划" : "非计划");
    }

    /** 1433★ 的三档：参数对 1435★ 明文给出的 400ml 以下 / 400–1000ml / 1000ml 以上，本平台不另立档 */
    private static String bandOf(long vol) {
        if (vol < 400) return "400ml 以下";
        return vol <= 1000 ? "400–1000ml" : "1000ml 以上";
    }

    /** 有自体血 / 有非自体血两个条件的患者去重计数（两者都要 = 重叠人数 both_patients） */
    private static long patientsMatching(List<Map<String, Object>> items, boolean needAuto, boolean needNonAuto) {
        var auto = new HashSet<String>();
        var nonAuto = new HashSet<String>();
        var all = new LinkedHashSet<String>();
        for (var it : items) {
            String k = patientKey(it);
            all.add(k);
            if (hasAuto(it)) auto.add(k);
            if (hasNonAuto(it)) nonAuto.add(k);
        }
        return all.stream()
                .filter(k -> !needAuto || auto.contains(k))
                .filter(k -> !needNonAuto || nonAuto.contains(k))
                .count();
    }

    // ==================================================================================
    // 对账引擎
    // ==================================================================================

    private void reconcile(Rule rule, List<Map<String, Object>> rows, List<Map<String, Object>> items, String ctx) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        if (rule.itemKey != null) {
            for (var it : items) {
                groups.computeIfAbsent(rule.itemKey.of(it, items), k -> new ArrayList<>()).add(it);
            }
        }

        // ① 合计级判据（分桶还原不了的指标走这条，如 1436★）
        for (var e : rule.totals.entrySet()) {
            assertEquals(e.getValue().applyAsLong(items), sum(rows, e.getKey()),
                    ctx + " 指标 " + rule.code + " 的 " + e.getKey() + " 各行合计与穿透明细对不上账");
        }

        // ② 逐桶逐列普查
        Set<String> coveredKeys = new HashSet<>();
        for (var row : rows) {
            String key = rule.rowKey == null ? null : rule.rowKey.apply(row);
            List<Map<String, Object>> subset = key == null ? items : groups.getOrDefault(key, List.of());
            if (key != null) coveredKeys.add(key);
            String where = ctx + " 指标 " + rule.code + (key == null ? "" : " 分桶[" + key + "]");

            for (String col : row.keySet()) {
                if (rule.labels.contains(col) || rule.totals.containsKey(col)) continue;
                if (rule.unreconcilable.containsKey(col) || rule.coveredBy.containsKey(col)) continue;

                var cnt = rule.counts.get(col);
                if (cnt != null) {
                    long expect = cnt.of(row, subset, items);
                    assertEquals(expect, num(row, col),
                            where + " 的列 " + col + " 与穿透明细对不上账"
                            + "（汇总说 " + num(row, col) + "，明细数出来 " + expect + "）");
                    continue;
                }
                var der = rule.derived.get(col);
                if (der != null) {
                    Double expect = der.of(row, rows, subset);
                    Double actual = dbl(row, col);
                    if (expect == null) {
                        assertNull(actual, where + " 的列 " + col + " 分母为 0 时应回 null，不许凑一个 0 出来");
                    } else {
                        assertNotNull(actual, where + " 的列 " + col + " 不应为空");
                        assertEquals(expect, actual, 0.06, where + " 的列 " + col + " 与同行其它列算不通");
                    }
                    continue;
                }
                fail(where + " 的列【" + col + "】既没有对账判据，也没有登记进「无法机械对账」清单。\n"
                        + "  —— v46 的 1429/1433/1434/1437 口径缺陷正是这样活下来的：\n"
                        + "     列加进去了，却没有任何一处机械核对它，于是错值一路走到评委面前。\n"
                        + "  请二选一：在 V49CaliberTest.rules() 里给它写判据；\n"
                        + "  或在 UNRECONCILABLE_COLUMNS 里登记「为什么对不了 + 要对得上需要什么」。");
            }
        }

        // ③ 明细里出现了汇总没给出的桶 = 漏统计（比数值对不上更严重：整桶消失且无声无息）
        if (rule.rowKey != null) {
            for (String k : groups.keySet()) {
                assertTrue(coveredKeys.contains(k),
                        ctx + " 指标 " + rule.code + " 的穿透明细里有分桶【" + k + "】，汇总却一行都没给——那是整桶漏统计");
            }
        }
    }

    // ==================================================================================
    // §① 主测试：逐条 available 指标机械对账（单日窗口，全列普查）
    // ==================================================================================

    /**
     * <b>本类的主测试</b>：从 {@code /catalog} 现取指标集合逐条遍历，对每一条 available 指标
     * 跑一遍「汇总 ↔ 穿透明细」的<b>逐列</b>对账。
     *
     * <p>与 v46 的差别就一句话：v46 只核对了 1428★ 一条的一个数字，本测试核对
     * <b>每一条指标的每一个数字</b>，且不许有一个数字没人管。
     */
    @Test
    void everyAvailableIndicatorIsMechanicallyReconciled() {
        var rules = rules();
        var failures = new ArrayList<String>();
        for (var def : catalog()) {
            String code = (String) def.get("code");
            if (!Boolean.TRUE.equals(def.get("available"))) continue;
            Rule rule = rules.get(code);
            assertNotNull(rule, "指标 " + code + "★（" + def.get("name") + "）没有对账判据。\n"
                    + "  指标集合是从 /catalog 现取的，新增指标会自动被纳入对账——这正是为了不再漏掉一条。\n"
                    + "  请在 V49CaliberTest.rules() 里给它写判据，或在清单里登记为什么对不了。");
            try {
                reconcile(rule, rows(code, d0, d0), items(code, d0, d0), "[单日窗口 " + d0 + "]");
                if (rule.summaryRule != null) {
                    var s = summaryOf(code, d0, d0);
                    assertNotNull(s, code + "★ 应有 summary");
                    reconcile(rule.summaryRule, List.of(s), items(code, d0, d0), "[单日窗口 summary]");
                }
            } catch (AssertionError e) {
                // 一次跑完全部指标再统一报告：主控要的是「哪些红了」的完整名单，不是第一条红就停
                failures.add("指标 " + code + "★（" + def.get("name") + "）：" + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            fail("以下指标的汇总与穿透明细对不上账（共 " + failures.size() + " 条）：\n\n"
                    + String.join("\n\n----------------\n\n", failures));
        }
    }

    /**
     * 三日窗口只做<b>总量级</b>对账：日表类指标（1424★/1425★/1427★/1437★/1448★/1449★）
     * 的分桶是 op_day，而明细里没有 op_day 列（见类头清单二），跨日窗口逐桶还原不了，
     * 但<b>各行合计必须仍等于明细总量</b>——这条一红就说明日表把某一天整个丢了或重复了。
     */
    @Test
    void totalsReconcileAcrossAMultiDayWindow() {
        var rules = rules();
        var failures = new ArrayList<String>();
        for (var def : catalog()) {
            String code = (String) def.get("code");
            if (!Boolean.TRUE.equals(def.get("available"))) continue;
            Rule rule = rules.get(code);
            if (rule == null || rule.rowsTotal == null) continue;
            try {
                var rs = rows(code, d0, d2);
                var it = items(code, d0, d2);
                assertEquals(rule.itemsTotal.applyAsLong(it), rule.rowsTotal.applyAsLong(rs),
                        "指标 " + code + "★ 在三日窗口 " + d0 + "~" + d2 + " 的汇总总量与穿透明细总量对不上账");
            } catch (AssertionError e) {
                failures.add("指标 " + code + "★：" + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            fail("三日窗口总量对不上账（共 " + failures.size() + " 条）：\n\n" + String.join("\n\n", failures));
        }
    }

    // ==================================================================================
    // §② 目录遍历：新增指标自动入网，不许有指标没人管
    // ==================================================================================

    @Test
    void everyIndicatorInTheCatalogIsAccountedFor() {
        var rules = rules();
        var catalog = catalog();
        assertFalse(catalog.isEmpty(), "指标目录不应为空");
        for (var def : catalog) {
            String code = (String) def.get("code");
            boolean available = Boolean.TRUE.equals(def.get("available"));
            if (available) {
                assertTrue(rules.containsKey(code),
                        "指标 " + code + "★ 是 available 的，却没有对账判据——不许静默跳过");
            } else {
                assertTrue(UNAVAILABLE.contains(code),
                        "指标 " + code + "★ 标成了 available=false，但它不在本类登记的三条缺数据源指标里。"
                        + "新增一条「做不了」的指标必须同时说明为什么做不了，并更新本清单。");
                assertNotNull(def.get("unavailableReason"), code + "★ 必须写明为什么没有");
            }
        }
        // 反向：登记的三条必须真的还在目录里且仍是 unavailable（被"补"成可用了要来改这里）
        var codes = catalog.stream().map(x -> (String) x.get("code")).toList();
        for (String c : UNAVAILABLE) {
            assertTrue(codes.contains(c), "指标 " + c + "★ 从目录里消失了");
        }
    }

    // ==================================================================================
    // §③ 缺数据源三条：不许被「补」成近似值
    // ==================================================================================

    /**
     * 1435★ 出血量 / 1444★ 毒麻药 / 1445★ 肌松药：全仓无数据源。
     *
     * <p>判据比 v46 更紧一格：不但<b>没有 rows / summary 键</b>，
     * <b>整个返回体里连一个数字都不许有</b>——一个「看起来像真的」的 0 比不返回更坏，
     * 管理者会把「本院无毒麻药开具」当成结论。
     */
    @Test
    void unavailableIndicatorsCarryNoRowsNoSummaryAndNoNumbers() {
        for (String code : UNAVAILABLE) {
            var ind = indicator(code, d0, d0);
            assertEquals(Boolean.FALSE, ind.get("available"), code + "★ 应标缺数据源");
            String reason = (String) ind.get("unavailableReason");
            assertNotNull(reason, code + "★ 必须写明为什么没有");
            assertFalse(reason.isBlank(), code + "★ 的原因不许留空");
            assertFalse(ind.containsKey("rows"),
                    code + "★ 的返回体里不许有 rows 键：给一个空数组，前端就会画出一张「全 0」的表");
            assertFalse(ind.containsKey("summary"), code + "★ 的返回体里不许有 summary 键");
            assertNumberFree(ind, code + "★ 的指标返回体");

            // 穿透与目录同样诚实
            var d = data(apiRaw("detail", "indicator", code, "from", d0, "to", d0));
            assertEquals(Boolean.FALSE, d.get("available"), code + "★ 穿透同样须标缺数据源");
            assertFalse(d.containsKey("rows"), code + "★ 穿透返回体里不许有 rows 键");
            assertFalse(d.containsKey("summary"), code + "★ 穿透返回体里不许有 summary 键");
            assertEquals(List.of(), d.get("items"), code + "★ 穿透不许给出任何条目");
            assertNotNull(d.get("unavailableReason"), code + "★ 穿透须带原因");
        }
        // 出血量绝不能拿输血量冒充——原因里必须点破这一点（这条是 1435★ 的要害）
        assertTrue(((String) indicator("1435", d0, d0).get("unavailableReason")).contains("输血量"),
                "1435★ 的原因必须明写「输血量不是出血量」：拿前者冒充后者会直接误导备血与失血管理决策");
    }

    /** 递归查有没有数字混进来（{@code available} 这类布尔与文本不算） */
    private static void assertNumberFree(Object node, String where) {
        if (node instanceof Number n) {
            fail(where + " 里出现了数字 " + n + "：缺数据源的指标不许给近似值，"
                    + "一个「看起来像真的」的 0 比不返回更坏。");
        } else if (node instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) assertNumberFree(e.getValue(), where + "." + e.getKey());
        } else if (node instanceof Collection<?> c) {
            for (var v : c) assertNumberFree(v, where + "[]");
        }
    }

    // ==================================================================================
    // §④ 无法机械对账的，显式列出且必须仍然成立
    // ==================================================================================

    /**
     * 「确实对不了账」的列与层，<b>必须显式登记、写明原因</b>，不许静默跳过——
     * 静默跳过正是这次缺陷活下来的原因。
     *
     * <p>本用例反过来看住这份清单：原因不许空；登记的列必须<b>真的还在返回体里</b>
     * （列被删了或被修好了，就该来删这一条，免得清单变成没人维护的免死金牌）。
     */
    @Test
    void columnsThatCannotBeReconciledAreDeclaredNotSkipped() {
        assertFalse(UNRECONCILABLE_COLUMNS.isEmpty(), "清单不该是空的——真有对不了的列");
        for (var e : UNRECONCILABLE_COLUMNS.entrySet()) {
            String[] parts = e.getKey().split("\\.", 2);
            assertEquals(2, parts.length, "清单的键须是「指标.列」：" + e.getKey());
            assertTrue(e.getValue() != null && e.getValue().length() > 30,
                    "登记 " + e.getKey() + " 必须写清「为什么对不了 + 要对得上需要什么」，一句敷衍不算");

            String code = parts[0];
            String col = parts[1];
            var rowsOrSummary = new ArrayList<Map<String, Object>>(rows(code, d0, d0));
            var s = summaryOf(code, d0, d0);
            if (s != null) rowsOrSummary.add(s);
            assertTrue(rowsOrSummary.stream().anyMatch(r -> r.containsKey(col)),
                    "清单登记了 " + e.getKey() + "，但返回体里已经没有这一列了——"
                    + "列没了/被修好了就该把这条登记删掉，清单不许变成没人维护的免死金牌");
        }
        for (var e : NOT_MECHANICALLY_COVERED.entrySet()) {
            assertTrue(e.getValue() != null && e.getValue().length() > 30,
                    "「" + e.getKey() + "」必须写清为什么覆盖不到，一句敷衍不算");
        }
        // 标了「由另一个用例覆盖」的列，那个用例必须真的存在且真的是 @Test ——
        // 否则「另案覆盖」就成了最体面的一种静默跳过
        var methods = new HashSet<String>();
        for (var mth : V49CaliberTest.class.getDeclaredMethods()) {
            if (mth.isAnnotationPresent(Test.class)) methods.add(mth.getName());
        }
        for (var rule : rules().values()) {
            for (var e : rule.coveredBy.entrySet()) {
                assertTrue(methods.contains(e.getValue()),
                        "指标 " + rule.code + " 的列 " + e.getKey() + " 标了「由 " + e.getValue()
                        + " 覆盖」，但本类里没有这个 @Test 方法——"
                        + "「另案覆盖」若指不到一个真的用例，就是最体面的一种静默跳过");
            }
        }
    }

    // ==================================================================================
    // §⑤ 口径：事件类指标按【事件日期】归桶，不按【手术锚点日期】
    // ==================================================================================

    /**
     * <b>v49 的口径修正本体</b>。条款原文写的是「统计<b>指定时间段内</b>……转入 ICU / 苏醒室 /
     * 拔管 / 拆镇痛泵 / 抢救」，而 v46 把这些事件按<b>手术锚点日期</b>归桶。
     *
     * <p>于是<b>术后延迟发生</b>的事件（次日拔管、术后非计划转 ICU、术后抢救、48 小时拆镇痛泵）
     * 会落进「手术当天」的桶而不是「事件当天」的桶——管理者按月查会漏，
     * 跨月的那几台手术更是整批错月。
     *
     * <p>夹具里这四类术后事件都是真实场景，不是为了造红而造的：
     * 延迟拔管发生在 ICU、非计划转 ICU 常在术后 24 小时内、拆镇痛泵按规范就在术后 48 小时。
     */
    @Test
    void eventIndicatorsAreBucketedByEventDateNotBySurgeryAnchor() {
        var failures = new ArrayList<String>();

        // ---- d1（术后次日）：手术不在这一天，但拔管 / 转 ICU / 抢救三件事都在这一天 ----
        record Expect(String code, String bucketCol, String bucket, long events, String scene) {}
        var expects = List.of(
                new Expect("1447", "event_type", "EXTUBATE", 1, "次日在 ICU 内延迟拔管"),
                new Expect("1449", null, null, 1, "术后抢救"));
        for (var ex : expects) {
            try {
                var rs = rows(ex.code(), d1, d1);
                long got = ex.bucket() == null
                        ? sum(rs, "events")
                        : rs.stream().filter(r -> ex.bucket().equals(str(r, ex.bucketCol())))
                              .mapToLong(r -> num(r, "events")).sum();
                assertEquals(ex.events(), got, ex.code() + "★ 在 " + d1 + " 这一天应统计到「" + ex.scene()
                        + "」——事件发生在这一天，就该落在这一天的桶里，"
                        + "而不是落在手术当天（" + d0 + "）。这是 v46 的口径缺陷。");
                var it = items(ex.code(), d1, d1);
                assertEquals(ex.events(), ex.bucket() == null ? it.size()
                                : countWhere(it, x -> ex.bucket().equals(str(x, "event_type"))),
                        ex.code() + "★ 的穿透明细必须与汇总同口径（同样按事件日期落窗）");
                for (var x : it) {
                    assertEquals(LocalDate.parse(d1), bizDay(x.get("event_time")),
                            ex.code() + "★ 的明细里混进了不属于 " + d1 + " 的事件");
                }
            } catch (AssertionError e) {
                failures.add(e.getMessage());
            }
        }

        // 1446★ 术后非计划转 ICU —— 「非计划转入 ICU」是重点监控指标，错月即失真
        try {
            var rs = rows("1446", d1, d1);
            long icuUnplanned = rs.stream()
                    .filter(r -> str(r, "target").startsWith("转入 ICU") && "非计划".equals(str(r, "planned_name")))
                    .mapToLong(r -> num(r, "events")).sum();
            assertEquals(1, icuUnplanned, "1446★ 在 " + d1 + " 应统计到术后非计划转入 ICU 一例："
                    + "事件发生在术后次日，就该落在次日的桶里");
        } catch (AssertionError e) {
            failures.add(e.getMessage());
        }

        // ---- d2（术后 48 小时）：拆镇痛泵 ----
        try {
            var rs = rows("1437", d2, d2);
            assertEquals(1, sum(rs, "removed"), "1437★ 在 " + d2 + " 应统计到术后 48 小时拆镇痛泵一例");
            var it = items("1437", d2, d2);
            assertEquals(1, it.size(), "1437★ 的穿透明细必须与汇总同口径："
                    + "v46 的汇总按事件日期数（generate_series over event_time），"
                    + "明细却按手术锚点日期落窗，两者永远对不上账");
        } catch (AssertionError e) {
            failures.add(e.getMessage());
        }

        if (!failures.isEmpty()) {
            fail("事件类指标仍按手术锚点日期归桶（共 " + failures.size() + " 处）：\n\n"
                    + String.join("\n\n", failures));
        }
    }

    /**
     * 旧口径必须保留：{@code by=surgery} 复现 v46 的「按手术锚点日期归桶」，
     * 供与分母同 population 的率类计算使用（比如「每百台手术的非计划转 ICU 例数」，
     * 分子分母必须是同一批手术，这时按事件日期反而算错）。
     */
    @Test
    void bySurgeryReproducesTheV46Caliber() {
        // 手术当天用旧口径：s1 的全部术后事件都挂回手术当天
        assertEquals(2, rows("1447", d0, d0, "by", "surgery").stream()
                        .filter(r -> "EXTUBATE".equals(str(r, "event_type")))
                        .mapToLong(r -> num(r, "events")).sum(),
                "by=surgery 应复现 v46 口径：术后次日的那次拔管也挂在手术当天");
        assertEquals(2, sum(rows("1449", d0, d0, "by", "surgery"), "events"),
                "by=surgery 下术后抢救也挂回手术当天");

        // 术后次日用旧口径：手术不在这一天，一件事都不该有
        assertEquals(0, sum(rows("1447", d1, d1, "by", "surgery"), "events"),
                "by=surgery 是手术锚点口径，手术不在 " + d1 + " 就该是 0");

        // 新旧口径必须都能穿透，且穿透与汇总同口径
        for (String code : List.of("1446", "1447", "1449")) {
            for (String by : List.of("event", "surgery")) {
                var rs = rows(code, d0, d0, "by", by);
                var it = items(code, d0, d0, "by", by);
                assertEquals(sum(rs, "events"), it.size(),
                        code + "★ 在 by=" + by + " 下汇总与明细必须同口径——"
                        + "换了口径只改汇总不改明细，就是又造一次 v46 的缺陷");
            }
        }
    }

    /** 4943：时间归集口径参数非法 */
    @Test
    void invalidTimeBasisReturns4943() {
        assertEquals(4943, code(apiRaw("indicators", "from", d0, "to", d0, "indicator", "1447", "by", "BOGUS")),
                "by 只收 event / surgery，别的值必须当场拒绝——静默回落成默认口径等于让人拿到一个说不清是哪套口径的数");
        assertEquals(4943, code(apiRaw("detail", "indicator", "1447", "from", d0, "to", d0, "by", "BOGUS")),
                "穿透同样要校验 by");
        assertEquals(0, code(apiRaw("indicators", "from", d0, "to", d0, "indicator", "1447", "by", "event")));
        assertEquals(0, code(apiRaw("indicators", "from", d0, "to", d0, "indicator", "1447", "by", "surgery")));
    }

    // ==================================================================================
    // §⑥ 占比穿透：bucket 参数（1422★「查看各指标占比详情」与「对占比准确性核对校验」）
    // ==================================================================================

    /**
     * v46 的 {@code /detail} 只有 {@code indicator/from/to/limit} 四个参数，<b>占比的分子取不出来</b>：
     * 「择期手术占 40%」这一行点开，回来的是全部手术而不是那 40% 的择期手术，
     * 于是 1422★ 的「查看各指标占比详情」与「对占比准确性核对校验」两条子要求实际上不成立。
     *
     * <p>判据：{@code bucket=<该行的编码列取值>} 穿透回来的条数必须<b>正好等于该行的计数</b>，
     * 且每一条都真的属于这个桶——这才叫「占比的分子取得出来」。
     */
    @Test
    @SuppressWarnings("unchecked")
    void bucketDrillsIntoExactlyOneShareOfTheRatio() {
        var failures = new ArrayList<String>();
        for (var def : catalog()) {
            String code = (String) def.get("code");
            if (!Boolean.TRUE.equals(def.get("available"))) continue;
            // 该指标支不支持分桶，以返回体自报的 bucketColumns 为准，测试里不另抄一份名单
            var probe = data(apiRaw("detail", "indicator", code, "from", d0, "to", d0));
            var cols = (List<String>) probe.get("bucketColumns");
            if (cols == null || cols.contains(IN_USE_BUCKET)) continue;   // 1437★ 的 in_use 是度量不是维度，另案
            if (probe.get("bucketSeparator") != null) {
                assertEquals(BUCKET_SEP, probe.get("bucketSeparator"),
                        code + "★ 的分桶拼接分隔符与本类假定的不一致——拼错的键会命中 0 行还看不出为什么");
            }

            for (var row : rows(code, d0, d0)) {
                // 分桶取值 = 汇总行里那几个维度列按 " / " 顺序拼起来（顺序即 bucketColumns）
                String bucket = String.join(BUCKET_SEP, cols.stream().map(c -> str(row, c)).toList());
                try {
                    var body = data(apiRaw("detail", "indicator", code, "from", d0, "to", d0, "bucket", bucket));
                    var it = (List<Map<String, Object>>) body.get("items");
                    assertEquals(Boolean.FALSE, body.get("truncated"), "分桶穿透被截断时对账不成立");
                    assertEquals(num(row, "cases"), it.size(),
                            code + "★ bucket=「" + bucket + "」穿透回来的条数应正好等于该行的 cases"
                            + "——否则「这个 32% 是哪 8 台」仍然点不出来，1422★ 的占比穿透就是空话");
                    // 返回体自报的 bucketSummaryCount 是「该桶在汇总里的计数」，未截断时必须等于条数。
                    // 这是实现自带的自检，外部再钉一遍：防它自报的和真穿出来的成了两张皮
                    assertEquals(num(row, "cases"), ((Number) body.get("bucketSummaryCount")).longValue(),
                            code + "★ bucket=「" + bucket + "」的 bucketSummaryCount 与汇总行的 cases 不一致");
                    // 逐条验它真属于这个桶（明细里取得到该维度列的才验，取不到的已登记在案）
                    for (var x : it) {
                        for (String c : cols) {
                            String v = blankTo(str(x, c), null);
                            if (v == null) continue;              // 空值走 coalesce 占位串，不逐字比
                            assertTrue(bucket.contains(v),
                                    code + "★ bucket=「" + bucket + "」的穿透里混进了 " + c + "=" + v + " 的行");
                        }
                    }
                } catch (AssertionError e) {
                    failures.add(e.getMessage());
                }
            }
        }
        if (!failures.isEmpty()) {
            fail("占比分桶穿透不成立（共 " + failures.size() + " 处）：\n\n" + String.join("\n\n", failures));
        }
    }

    /**
     * <b>凡是给了占比列 pct 的指标，都必须能分桶穿透</b>——1422★ 要的「查看各指标占比详情」
     * 与「对占比准确性核对校验」，说的就是「这个 32% 是哪几台，点开给我看」。
     * 给了占比却点不开，那两条子要求就是空话。
     */
    @Test
    void everyIndicatorWithARatioColumnIsDrillableByBucket() {
        for (var def : catalog()) {
            String code = (String) def.get("code");
            if (!Boolean.TRUE.equals(def.get("available"))) continue;
            if (rows(code, d0, d0).stream().noneMatch(r -> r.containsKey("pct"))) continue;
            var probe = data(apiRaw("detail", "indicator", code, "from", d0, "to", d0));
            assertNotNull(probe.get("bucketColumns"),
                    "指标 " + code + "★ 给了占比列 pct 却不支持 bucket 分桶穿透："
                    + "占比的分子取不出来，1422★ 的「查看各指标占比详情」「对占比准确性核对校验」就不成立");
        }
    }

    /** 4944：分桶取值参数非法 */
    @Test
    void invalidBucketReturns4944() {
        assertEquals(4944,
                code(apiRaw("detail", "indicator", "1428", "from", d0, "to", d0, "bucket", "根本没有这个桶")),
                "bucket 取值不在该指标的分桶里必须当场拒绝——"
                + "静默返回全量会让人以为「这个桶就是这么多」，比报错坏得多");
        assertEquals(4944,
                code(apiRaw("detail", "indicator", "1437", "from", d0, "to", d0, "bucket", "added")),
                "1437★ 只认 bucket=" + IN_USE_BUCKET + "，别的度量列不是分桶");
    }

    // ==================================================================================
    // §⑥-2 1437★ 在用量：从「点不出来的数字」变成「点得出来」
    // ==================================================================================

    /**
     * {@code in_use} 是全历史累计（截至 to 当日末仍未拆的泵），刻意不受时间窗左端影响——
     * 因此它<b>与不带 bucket 的 ON/OFF 事件流水不是同一个 population，永远对不上</b>。
     * 能对上的只有一条路：{@code bucket=in_use} 的「在用清单」穿透，
     * 其台数必须等于汇总里 {@code op_day == to} 那一行的 {@code in_use}。
     */
    @Test
    @SuppressWarnings("unchecked")
    void painPumpInUseIsDrillableAndReconciles() {
        // 夹具：泵在 d0 装上、d2 拆掉。故 d0 / d1 当日末仍在用 1 台，d2 当日末回 0
        for (String day : new String[]{d0, d1, d2}) {
            var last = rows("1437", d0, day).stream().filter(r -> day.equals(str(r, "op_day")))
                    .findFirst().orElseGet(() -> fail("1437★ 的日表必须含截止日 " + day + " 那一行"));
            var body = data(apiRaw("detail", "indicator", "1437", "from", d0, "to", day,
                    "bucket", IN_USE_BUCKET));
            var it = (List<Map<String, Object>>) body.get("items");
            assertEquals(Boolean.FALSE, body.get("truncated"), "在用清单被截断时对账不成立");
            // 清单可能一行一台次（带该台次在用泵数），也可能一行一支泵——两种都按「泵数」合计
            long pumps = it.stream().mapToLong(x -> x.containsKey(IN_USE_BUCKET)
                    ? num(x, IN_USE_BUCKET) : 1).sum();
            assertEquals(num(last, "in_use"), pumps,
                    "1437★ 截至 " + day + " 的 in_use 与「在用清单」穿透对不上账："
                    + "汇总说 " + num(last, "in_use") + " 台，清单给出 " + pumps + " 台。"
                    + "in_use 曾经是一个点不出来的数字——能点开、且点开就对得上，正是本版要补的那条路径");
        }
        assertEquals(1, num(rows("1437", d0, d1).stream()
                        .filter(r -> d1.equals(str(r, "op_day"))).findFirst().orElseThrow(), "in_use"),
                "泵在 " + d0 + " 装上、" + d2 + " 才拆，" + d1 + " 当日末应仍在用 1 台");
        assertEquals(0, num(rows("1437", d0, d2).stream()
                        .filter(r -> d2.equals(str(r, "op_day"))).findFirst().orElseThrow(), "in_use"),
                "泵已在 " + d2 + " 拆掉，当日末在用应回 0");
    }

    /**
     * 「只拆没装」的坏行（补录漏了装泵事件）<b>不许去冲抵别台真在用的泵</b>。
     * 旧口径是全局 ON 数减 OFF 数，一条这样的坏行会把在用量算小 1，
     * 而那个「小 1」既解释不了也穿透不出来——本版把它单列进 {@code orphan_off}，明码标价。
     */
    @Test
    void orphanOffIsolatesOnlyOffPumpsWithoutEatingInUse() {
        String to = d1;   // 夹具的泵在 d2 才拆，故截至 d1 在用 1 台
        var before = rows("1437", d0, to).stream().filter(r -> to.equals(str(r, "op_day")))
                .findFirst().orElseThrow();
        long inUseBefore = num(before, "in_use");
        long orphanBefore = num(before, "orphan_off");

        // 另起一台手术，只记拆泵、查无装泵 —— 真实里就是补录漏了 ON 的那种坏行
        Long adm = newAdmission(newPatient("M", 51));
        Long sx = surgery(adm, "V49只拆没装术", "V49-OR-9", "08:00", "08:00", "08:10", "09:00", "09:10",
                "一级", "I", "ELECTIVE", "全身麻醉");
        event(sx, "PAIN_PUMP_OFF", d0, "12:00", null);

        var after = rows("1437", d0, to).stream().filter(r -> to.equals(str(r, "op_day")))
                .findFirst().orElseThrow();
        assertEquals(inUseBefore, num(after, "in_use"),
                "「只拆没装」的坏行不许把别台真在用的泵冲掉——在用量应纹丝不动。"
                + "旧口径（全局 ON 数减 OFF 数）会在这里少算 1 台，而少的那 1 台谁也解释不了、也穿透不出来");
        assertEquals(orphanBefore + 1, num(after, "orphan_off"),
                "这条坏行应当被单列进 orphan_off 摆出来，而不是悄悄从在用量里扣掉");
    }

    // ==================================================================================
    // §⑦ 跨指标：同一批手术的分母必须一致
    // ==================================================================================

    /**
     * 同一时间窗下，「该时段全部未取消手术台次」这个 population 在多条指标里必须是同一个数。
     * 一条指标悄悄多/少一批手术（比如漏了 {@code cancel_stage is null} 那一半条件），
     * 单看它自己是自洽的，只有横向比才现形。
     */
    @Test
    void crossIndicatorPopulationsAgree() {
        long byKind = sum(rows("1428", d0, d0), "cases");
        assertEquals(byKind, sum(rows("1439", d0, d0), "cases"), "1439★ 手术级别分布的分母应与 1428★ 同一批手术");
        assertEquals(byKind, sum(rows("1430", d0, d0), "cases"), "1430★ 麻醉方式分布的分母应与 1428★ 同一批手术");
        assertEquals(byKind, sum(rows("1436", d0, d0), "cases"), "1436★ 年龄性别分布的分母应与 1428★ 同一批手术");
        assertEquals(byKind, sum(rows("1429", d0, d0), "cases"), "1429★ ASA 分布的分母应与 1428★ 同一批手术");
        assertEquals(byKind, num(summaryOf("1438", d0, d0), "cases"), "1438★ 术式合计应与 1428★ 同一批手术");
        // 1438★ 的汇总行有 limit 50：只有本窗口不足 50 行时，行合计才等于总量，本类的总量判据才成立
        assertTrue(rows("1438", d0, d0).size() < 50,
                "1438★ 汇总行已到 limit 50 上限，行合计不再等于总量——本类的 1438 总量判据须改走 summary");
        assertEquals(byKind, sum(rows("1425", d0, d0), "cases"), "1425★ 台数应与 1428★ 同一批手术");

        // 1427★ 的分母（已录开台+结束的台次）在自己的明细里没有对应行，靠 1425★ 兜住
        assertEquals(sum(rows("1425", d0, d0), "timed_cases"), num(summaryOf("1427", d0, d0), "timed_cases"),
                "1427★ 的分母 timed_cases 必须与 1425★ 的 timed_cases 同口径同数值——"
                + "它在 1427★ 自己的穿透明细里没有对应行（明细只含跨日台次），只能这样交叉核对");

        // 首台是「按手术间的 rn=1」，必然 <= 台数，且日表合计 == summary
        assertEquals(num(summaryOf("1424", d0, d0), "first_cases"), sum(rows("1424", d0, d0), "first_cases"),
                "1424★ 日表合计与 summary 必须一致");
        assertTrue(num(summaryOf("1424", d0, d0), "first_cases") <= byKind, "首台数不可能多于总台数");
    }

    // ==================================================================================
    // §⑧ 口径变更必须随体下发；4945 正常路径不得出现；新参数不得写库
    // ==================================================================================

    /**
     * 铁律：口径变更要在返回体里写清<b>改了什么、为什么改</b>，否则历史报表与新报表对不上时无从追溯。
     * 事件类指标的归桶口径这一版变了，返回体必须说出来——只写在页面 alert 上不算，
     * 拿 API 取数的对接方看不到页面。
     */
    @Test
    void caliberChangeIsDeclaredInTheResponseBody() {
        var body = data(apiRaw("indicators", "from", d0, "to", d0, "indicator", "1447"));
        String blob = String.valueOf(body);
        assertTrue(blob.contains("口径"), "返回体必须带口径说明");
        assertTrue(blob.contains("事件日期") || blob.contains("事件当日") || blob.contains("事件发生日")
                        || blob.contains("by=event"),
                "事件类指标的归桶口径这一版从「手术锚点日期」改成了「事件日期」，"
                + "返回体必须写清改了什么、从哪个版本起生效——"
                + "口径变更不写进返回体，历史报表与新报表对不上时无从追溯。");
    }

    /** 4945 是内部一致性断言失败才返的码，正常路径不应出现——一出现就说明汇总与明细粒度真的错位了 */
    @Test
    void noNormalPathEverReturns4945() {
        for (var def : catalog()) {
            String code = (String) def.get("code");
            for (String by : new String[]{null, "event", "surgery"}) {
                var ind = by == null
                        ? apiRaw("indicators", "from", d0, "to", d2, "indicator", code)
                        : apiRaw("indicators", "from", d0, "to", d2, "indicator", code, "by", by);
                assertTrue(code(ind) != 4945,
                        "指标 " + code + "★（by=" + by + "）返回了 4945：汇总与明细粒度错位。" + ind.get("message"));
                var det = by == null
                        ? apiRaw("detail", "indicator", code, "from", d0, "to", d2)
                        : apiRaw("detail", "indicator", code, "from", d0, "to", d2, "by", by);
                assertTrue(code(det) != 4945,
                        "指标 " + code + "★ 的穿透（by=" + by + "）返回了 4945。" + det.get("message"));
            }
        }
    }

    /**
     * 新参数不得把只读统计层变成写路径。v46 已钉过一次「统计端点纯只读」，
     * 本版新增 {@code by}/{@code bucket} 两条新 SQL 分支，同样要钉——
     * 「跑个报表把数据改了」是这类事故的经典形态。
     */
    @Test
    void newParametersStayStrictlyReadOnly() {
        var before = tableCounts();
        for (var def : catalog()) {
            String code = (String) def.get("code");
            apiRaw("indicators", "from", d0, "to", d2, "indicator", code, "by", "event");
            apiRaw("indicators", "from", d0, "to", d2, "indicator", code, "by", "surgery");
            apiRaw("detail", "indicator", code, "from", d0, "to", d2, "by", "event");
            apiRaw("detail", "indicator", code, "from", d0, "to", d2, "bucket", "ELECTIVE");
        }
        assertEquals(before, tableCounts(), "带 by / bucket 的统计与穿透全程不得写任何一行");
    }

    private Map<String, Object> tableCounts() {
        var m = new LinkedHashMap<String, Object>();
        for (String t : new String[]{"inp_surgery", "surg_event", "surg_transfusion",
                                     "qc_adverse_event", "mr_death_card", "inp_admission"}) {
            m.put(t, jdbc.queryForObject("select count(*) from " + t, Long.class));
        }
        return m;
    }

    // ==================================================================================
    // §⑨ 既有对接方零感知：不传新参数时，非事件类指标逐字不变
    // ==================================================================================

    /**
     * {@code by} 对<b>非事件类</b>指标没有意义（它们本来就按手术锚点日期归集），
     * 因此不传 / {@code by=event} / {@code by=surgery} 三者必须给出<b>完全相同</b>的返回体——
     * 既有对接方零感知。
     *
     * <p><b>事件类指标是刻意的例外</b>：默认口径这一版从「手术锚点日期」改成「事件日期」，
     * 那是错误码分段表 4943 行登记在案的<b>口径变更</b>（v46 算错了，不是新功能），
     * 旧口径由 {@code by=surgery} 保留。这一点由
     * {@link #eventIndicatorsAreBucketedByEventDateNotBySurgeryAnchor()} 与
     * {@link #bySurgeryReproducesTheV46Caliber()} 分别钉死。
     */
    @Test
    void nonEventIndicatorsAreUnaffectedByTheNewParameters() {
        for (var def : catalog()) {
            String code = (String) def.get("code");
            if (!Boolean.TRUE.equals(def.get("available")) || EVENT_INDICATORS.contains(code)) continue;
            var plain = indicator(code, d0, d2);
            assertEquals(plain, indicator(code, d0, d2, "by", "event"),
                    "指标 " + code + "★ 不是事件类，by=event 不得改变它的任何一个数");
            assertEquals(plain, indicator(code, d0, d2, "by", "surgery"),
                    "指标 " + code + "★ 不是事件类，by=surgery 不得改变它的任何一个数");
        }
    }

    /**
     * 不传新参数时，返回体的<b>键</b>与 v46 逐字一致：既有的键只增不改不删。
     * 对接方按键取数，删一个键或改一个键名就是断掉一条已上线的对接。
     */
    @Test
    void responseKeysFromV46AreAllStillThere() {
        var body = data(apiRaw("indicators", "from", d0, "to", d0));
        for (String k : new String[]{"from", "to", "days", "onTimeMinutes", "standardNote",
                                     "anchorNote", "timepointCaveat", "coverage", "indicators"}) {
            assertTrue(body.containsKey(k), "v46 的顶层键 " + k + " 不见了——既有返回体的键只增不改不删");
        }
        var ind = indicator("1428", d0, d0);
        for (String k : new String[]{"code", "name", "available", "detailEndpoint", "rows"}) {
            assertTrue(ind.containsKey(k), "v46 的指标体键 " + k + " 不见了");
        }
        var det = data(apiRaw("detail", "indicator", "1428", "from", d0, "to", d0));
        for (String k : new String[]{"code", "name", "available", "from", "to", "limit", "items",
                                     "truncated", "anchorNote"}) {
            assertTrue(det.containsKey(k), "v46 的穿透体键 " + k + " 不见了");
        }
        // coverage 段是「先看覆盖率再看指标值」的依据，不许被顺手删掉
        @SuppressWarnings("unchecked")
        var cov = (Map<String, Object>) body.get("coverage");
        for (String k : new String[]{"surgeries", "with_room", "with_start", "with_level", "with_asa", "note"}) {
            assertTrue(cov.containsKey(k), "coverage 段的键 " + k + " 不见了");
        }
    }

    // ==================================================================================
    // §⑩ 既有校验码不得被新参数带塌
    // ==================================================================================

    @Test
    void v46ValidationsStillHold() {
        assertEquals(4940, code(apiRaw("indicators", "from", "2026-03-10", "to", "2026-03-01")), "起止倒置");
        assertEquals(4940, code(apiRaw("indicators", "from", "2024-01-01", "to", "2026-01-01")), "跨度超 366 天");
        assertEquals(4940, code(apiRaw("indicators", "from", "2026/03/01", "to", "2026-03-10")), "日期格式非法");
        assertEquals(4941, code(apiRaw("indicators", "from", d0, "to", d0, "indicator", "9999")));
        assertEquals(4941, code(apiRaw("detail", "indicator", "9999", "from", d0, "to", d0)));
        assertEquals(4942, code(apiRaw("detail", "indicator", "1424", "from", d0, "to", d0, "limit", "201")));
        assertEquals(0, code(apiRaw("detail", "indicator", "1424", "from", d0, "to", d0, "limit", "200")),
                "正好 200 应放行");
    }
}
