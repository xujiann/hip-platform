package cn.hip.server;

import cn.hip.medtech.web.AnesQcController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v49 车道Q3 回归：占比穿透分桶（1422★）与 1437★ 在用量口径。
 *
 * <p><b>本类的核心断言只有一句</b>：<b>汇总里某个分桶的 count == 该分桶穿透出来的行数</b>。
 * 这正是 1422★「支持查看各质控指标数据占比详情」与「可对占比准确性进行核对校验」两条子要求的实质——
 * 修正前 {@code /detail} 只有 indicator/from/to/limit 四个参数，构成比类指标汇总时按维度给出 pct，
 * 穿透时却取不出某一个分桶的分子，评委问「这个 32% 是哪 8 台」当场答不出来。
 *
 * <p>{@link #everyBucketOfEveryCompositionIndicatorReconciles()} 对<b>每个构成比指标的每一个分桶</b>
 * 逐桶断言，而不是挑一两个样例：分桶表达式（{@code bucketSpec}）与汇总的 group by 表达式是两份写法，
 * 只要有人改了汇总口径忘了改分桶表达式，这条用例当场红。这是本车道唯一有效的回归保护。
 *
 * <p>1437★ 另有一组：{@code in_use} 修正前是<b>无下界的全历史累计</b>，而它唯一的明细带时间窗——
 * 汇总说「在用 15 台」，明细怎么点都点不出 15 行。本版补上 {@code bucket=in_use} 的同口径穿透，
 * 并把 in_use 由「全局 ON 减 OFF」改为「按台次配对后求和」，同时新增 orphan_off 列让旧口径可倒推。
 *
 * <p><b>时间窗取 220 天前</b>：避开 V46AnesQcTest（200/201 天前）与库里既有数据，
 * 否则测出来的是环境不是代码。1437★ 的在用量是全历史口径、无法靠挪窗隔离，
 * 故那一组一律<b>先取基线再断言增量</b>。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "QUALITY"})
class V49QcBucketTest {

    @Autowired AnesQcController anesQc;
    @Autowired JdbcTemplate jdbc;

    /** 构成比类指标：汇总按维度分组给 pct，穿透必须取得出某一个分桶的分子 */
    private static final List<String> COMPOSITION = List.of(
            "1426", "1428", "1429", "1430", "1431", "1436", "1438", "1439");

    /**
     * 二维分桶取值的拼接分隔符。生产侧常量是包内可见的，此处照抄一份，
     * 并由 {@link #twoDimensionalBucketsUseDeclaredColumnOrder()} 拿返回体的 bucketSeparator 对上——
     * 两边漂了当场红，不靠人眼盯。
     */
    private static final String SEP = " / ";

    private String day;

    @BeforeEach
    void setUp() {
        day = jdbc.queryForObject("select (current_date - 220)::text", String.class);
    }

    // ================= 夹具 =================

    private static long seq = 0;

    private static String uniq(String prefix) {
        return prefix + (System.nanoTime() % 100000000L) + (seq++);
    }

    private Long newPatient(String sex, int ageYears) {
        return jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, ?, current_date - make_interval(years => ?))
                returning id
                """, Long.class, uniq("V49P"), "分桶穿透" + uniq(""), sex, ageYears);
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

    private Long surgery(Long admissionId, String procedure, String anesthesia,
                         String level, String asa, String kind) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status,
                                        scheduled_at, room_no, in_room_at, start_at, end_at, out_room_at,
                                        surgery_level, asa_grade, surgery_kind, op_icd)
                values (?, ?, ?, 'DONE', ?::timestamptz, 'V49-OR-1', ?::timestamptz, ?::timestamptz,
                        ?::timestamptz, ?::timestamptz, ?, ?, ?, '47.09')
                returning id
                """, Long.class, admissionId, procedure, anesthesia,
                ts("08:00"), ts("08:05"), ts("08:10"), ts("09:30"), ts("09:40"),
                level, asa, kind);
    }

    /** 本版之前的历史手术形态：新字段全为 null，各构成比指标的「（未填写）」分桶靠它才有行 */
    private Long legacySurgery(Long admissionId) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status, scheduled_at)
                values (?, 'V49历史手术', null, 'DONE', ?::timestamptz)
                returning id
                """, Long.class, admissionId, ts("07:00"));
    }

    /** 取消手术：1426★ 的四阶段分桶 + 「已取消但未标注阶段」那一桶 */
    private void cancelledSurgery(Long admissionId, String stage) {
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status,
                                        scheduled_at, cancel_stage, cancel_reason)
                values (?, 'V49取消术', '全身麻醉', 'CANCELLED', ?::timestamptz, ?, ?)
                """, admissionId, ts("07:30"), stage, stage == null ? null : "患者原因");
    }

    /** 返回事件 id：断言「留在用的是哪一支泵」只认 id，不认渲染出来的时间字面量——
     *  库会话时区与 JVM 时区可以不同（本用例在 UTC 下跑时就差 8 小时），比字符串必炸 */
    private Long event(Long surgeryId, String type, String time) {
        return jdbc.queryForObject("""
                insert into surg_event(surgery_id, event_type, event_time, planned, detail)
                values (?, ?, ?::timestamptz, null, 'V49测试事件')
                returning id
                """, Long.class, surgeryId, type, ts(time));
    }

    private String ts(String hhmm) {
        return day + " " + hhmm;
    }

    /** 一批覆盖各构成比指标全部分桶（含「未填写」占位桶）的手术 */
    private void seedMixedSurgeries() {
        Long a1 = newAdmission(newPatient("M", 30));
        surgery(a1, "V49阑尾切除术", "全身麻醉", "三级", "II", "ELECTIVE");
        Long a2 = newAdmission(newPatient("F", 70));
        surgery(a2, "V49阑尾切除术", "椎管内麻醉", "四级", "III", "EMERGENCY");
        Long a3 = newAdmission(newPatient("F", 8));
        surgery(a3, "V49疝修补术", "局部麻醉", "二级", "II", "DAY");
        Long a4 = newAdmission(newPatient("M", 80));
        surgery(a4, "V49疝修补术", "局麻", "一级", "I", "ELECTIVE");
        // 历史行：surgery_kind / asa / level / 麻醉方式全空 → 各指标的「（未填写）」桶
        legacySurgery(newAdmission(newPatient("M", 50)));
        // 取消：四阶段之一 + 未标阶段
        cancelledSurgery(newAdmission(newPatient("F", 40)), "PRE_IN");
        cancelledSurgery(newAdmission(newPatient("M", 40)), null);
    }

    // ================= 小工具 =================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String code) {
        var r = anesQc.indicators(day, day, code);
        assertEquals(0, r.getCode(), r.getMessage());
        var list = (List<Map<String, Object>>) r.getData().get("indicators");
        assertEquals(1, list.size());
        return (List<Map<String, Object>>) list.get(0).get("rows");
    }

    private Map<String, Object> detail(String code, String bucket) {
        var r = anesQc.detail(code, day, day, null, null, bucket);
        assertEquals(0, r.getCode(), "指标 " + code + " 分桶 " + bucket + "：" + r.getMessage());
        return r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    @SuppressWarnings("unchecked")
    private List<String> bucketColumns(String code) {
        return (List<String>) detail(code, null).get("bucketColumns");
    }

    /** 汇总行 → 分桶取值：与生产侧 bucketKey 同一套拼法（多列按 " / " 拼） */
    private String keyOf(List<String> cols, Map<String, Object> row) {
        var parts = new ArrayList<String>();
        for (String c : cols) {
            assertTrue(row.containsKey(c), "汇总行里应有分桶列 " + c + "，实际列：" + row.keySet());
            parts.add(String.valueOf(row.get(c)));
        }
        return String.join(SEP, parts);
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    // ================= §① 每个指标的每一个分桶都要对得上账 =================

    /**
     * <b>本车道的验收标准</b>：汇总某桶的 count == 该桶 bucket 穿透的行数。
     * 逐指标、逐分桶全跑一遍，不挑样例——分桶表达式与汇总 group by 是两份写法，
     * 有人改了一处忘了另一处，这里当场红。
     */
    @Test
    void everyBucketOfEveryCompositionIndicatorReconciles() {
        seedMixedSurgeries();

        for (String code : COMPOSITION) {
            var cols = bucketColumns(code);
            assertNotNull(cols, code + "★ 是构成比指标，/detail 须给出 bucketColumns");
            var summary = rows(code);
            assertFalse(summary.isEmpty(), code + "★ 夹具日应有汇总行，否则这条断言什么也没验");

            long bucketed = 0;
            for (var row : summary) {
                String key = keyOf(cols, row);
                var body = detail(code, key);
                assertEquals(Boolean.FALSE, body.get("truncated"),
                        code + "★ 分桶「" + key + "」不应被截断，否则下面的等式无意义");
                assertEquals(num(row, "cases"), items(body).size(),
                        code + "★ 分桶「" + key + "」：汇总 cases 必须等于穿透行数");
                assertEquals(num(row, "cases"), ((Number) body.get("bucketSummaryCount")).longValue(),
                        code + "★ 分桶「" + key + "」：返回体回显的汇总计数须与汇总一致");
                assertEquals(key, body.get("bucket"), "返回体须回显分桶取值");
                bucketed += items(body).size();
            }

            // 各桶之和 == 不传 bucket 的全量明细：分桶既不重也不漏
            var all = detail(code, null);
            assertEquals(Boolean.FALSE, all.get("truncated"), code + "★ 全量明细不应被截断");
            assertEquals(items(all).size(), bucketed,
                    code + "★ 各分桶行数之和必须等于全量明细行数（不重不漏）");
        }
    }

    /** 「（未填写）」这类占位取值是真实分桶，必须点得开——历史手术全靠它才穿透得到 */
    @Test
    void placeholderBucketsAreDrillable() {
        seedMixedSurgeries();

        var kind = detail("1428", "（未填写）");
        assertEquals(1, items(kind).size(), "surgery_kind 为空的历史手术须能按「（未填写）」穿透");
        assertEquals("V49历史手术", items(kind).get(0).get("procedure_name"));

        assertEquals(1, items(detail("1439", "（未分级）")).size(), "手术级别未分级桶");
        assertEquals(1, items(detail("1429", "（未填写）")).size(), "ASA 未填写桶");
        assertEquals(1, items(detail("1430", "（未填写）")).size(), "麻醉方式未填写桶");
        assertEquals(1, items(detail("1426", "（未标注）")).size(), "取消未标阶段桶");
    }

    /** 二维汇总（年龄段 × 性别 / 术式 × 编码）的分桶取值按 " / " 拼，顺序即 bucketColumns */
    @Test
    void twoDimensionalBucketsUseDeclaredColumnOrder() {
        seedMixedSurgeries();

        var demo = detail("1436", null);
        assertEquals(List.of("age_band", "sex"), demo.get("bucketColumns"));
        assertEquals(SEP, demo.get("bucketSeparator"), "生产侧分隔符与本用例照抄的那份必须一致");
        assertEquals(1, items(detail("1436", "1–17 岁 / 女")).size(), "8 岁女童落 1–17 岁 / 女");
        assertEquals(1, items(detail("1436", "≥75 岁 / 男")).size(), "80 岁男性落 ≥75 岁 / 男");

        var proc = detail("1438", null);
        assertEquals(List.of("procedure_name", "op_icd"), proc.get("bucketColumns"));
        assertEquals(2, items(detail("1438", "V49阑尾切除术 / 47.09")).size(),
                "同名同码的两台手术归一个桶");
    }

    // ================= §② 4944：非法分桶不给「0 行」的假结果 =================

    @Test
    void unknownBucketValueReturns4944() {
        seedMixedSurgeries();

        var r = anesQc.detail("1428", day, day, null, null, "不存在的类别");
        assertEquals(4944, r.getCode());
        assertTrue(r.getMessage().contains("合法取值"),
                "报错须把本时间窗的合法取值列出来，不让调用方去猜：" + r.getMessage());
        assertTrue(r.getMessage().contains("择期手术") || r.getMessage().contains("ELECTIVE"),
                "合法取值里应含夹具造出来的桶：" + r.getMessage());
    }

    /** 窗内不存在的取值也返 4944：返回一张 0 行的表会被当成 bug，也无法与汇总核对 */
    @Test
    void bucketNotPresentInWindowReturns4944() {
        seedMixedSurgeries();
        // ASA VI 是合法值域但夹具日无此病例
        assertEquals(4944, anesQc.detail("1429", day, day, null, null, "VI").getCode());
    }

    @Test
    void bucketOnNonCompositionIndicatorReturns4944() {
        var r = anesQc.detail("1424", day, day, null, null, "任意值");
        assertEquals(4944, r.getCode(), "1424★ 是日期序列指标，没有构成比分桶");
        assertTrue(r.getMessage().contains("支持分桶的指标"), "报错须给出支持分桶的指标名单");

        // 缺数据源的三条：分桶无从谈起，报错须点出缺数据源而不是含糊说「不支持」
        for (String code : new String[]{"1435", "1444", "1445"}) {
            var u = anesQc.detail(code, day, day, null, null, "任意值");
            assertEquals(4944, u.getCode(), code + "★ 缺数据源，不该假装能分桶");
            assertTrue(u.getMessage().contains("缺数据源"), code + "★ 报错须说明是缺数据源：" + u.getMessage());
        }
    }

    /** 空串按「没传」处理，等价于全量——不是一个查不到的分桶 */
    @Test
    void blankBucketMeansFullDetail() {
        seedMixedSurgeries();
        var blank = anesQc.detail("1428", day, day, null, null, "  ");
        assertEquals(0, blank.getCode());
        assertEquals(items(detail("1428", null)).size(),
                ((List<?>) blank.getData().get("items")).size());
        assertFalse(blank.getData().containsKey("bucket"), "没传分桶就不该回显 bucket 键");
    }

    /** 不传 bucket 的既有四参 / 五参调用一字不变——v46 起的调用方不用改 */
    @Test
    void legacyCallsAreUnchanged() {
        seedMixedSurgeries();
        var four = anesQc.detail("1428", day, day, null);
        var six = anesQc.detail("1428", day, day, null, null, null);
        assertEquals(0, four.getCode());
        assertEquals(((List<?>) six.getData().get("items")).size(),
                ((List<?>) four.getData().get("items")).size());
        assertFalse(four.getData().containsKey("bucketSummaryCount"));
    }

    // ================= §③ 1437★ 在用量：从「点不出来」到「点得出来」 =================

    /**
     * 修正前 in_use 是全局 ON 减 OFF：某台次只记了拆泵（补录漏了装泵）时，这条坏行会去冲抵
     * 别的台次真在用的泵——本例旧口径会算出 1，实际在用是 2 台，且那 1 台是哪一台永远点不出来。
     */
    @Test
    void painPumpInUseIsPairedPerSurgeryAndDrillable() {
        var base = rows("1437").get(0);
        long baseInUse = num(base, "in_use");
        long baseOrphan = num(base, "orphan_off");

        Long sa = surgery(newAdmission(newPatient("M", 60)), "V49泵手术A", "全身麻醉", "三级", "II", "ELECTIVE");
        Long earlyOn = event(sa, "PAIN_PUMP_ON", "10:00");
        event(sa, "PAIN_PUMP_OFF", "10:30");
        Long lateOn = event(sa, "PAIN_PUMP_ON", "11:00");   // 净 1 支在用
        Long sb = surgery(newAdmission(newPatient("F", 55)), "V49泵手术B", "全身麻醉", "三级", "II", "ELECTIVE");
        event(sb, "PAIN_PUMP_ON", "12:00");          // 净 1 支在用
        Long sc = surgery(newAdmission(newPatient("M", 45)), "V49泵手术C", "全身麻醉", "三级", "II", "ELECTIVE");
        event(sc, "PAIN_PUMP_OFF", "13:00");         // 只拆没装：不得去冲抵 A / B

        var row = rows("1437").get(0);
        assertEquals(day, String.valueOf(row.get("op_day")), "汇总首行应是 to 当日");
        assertEquals(baseInUse + 2, num(row, "in_use"),
                "按台次配对后在用 2 台；旧的全局 ON−OFF 会被 C 的孤儿拆泵冲成 1");
        assertEquals(baseOrphan + 1, num(row, "orphan_off"), "只拆没装的那 1 条须单列，不得悄悄吞掉");
        assertEquals(baseInUse - baseOrphan + 1,
                num(row, "in_use") - num(row, "orphan_off"),
                "旧口径值（全局 ON−OFF）= in_use − orphan_off，随时可倒推核对");

        // 穿透：与 in_use 同口径，行数必须相等——这正是修正前做不到的那一步
        var body = detail("1437", "in_use");
        assertEquals(Boolean.FALSE, body.get("truncated"));
        assertEquals(num(row, "in_use"), items(body).size(),
                "bucket=in_use 的行数必须等于汇总 op_day=to 那一行的 in_use");
        assertEquals(num(row, "in_use"), ((Number) body.get("bucketSummaryCount")).longValue());

        var mine = items(body).stream()
                .filter(m -> String.valueOf(m.get("procedure_name")).startsWith("V49泵手术"))
                .toList();
        assertEquals(2, mine.size(), "在用的两台泵须逐台列得出来");
        assertTrue(mine.stream().allMatch(m -> m.get("patient_name") != null), "每行须给到患者");
        assertTrue(mine.stream().allMatch(m -> m.get("event_time") != null), "每行须给到装泵时间");
        assertTrue(mine.stream().noneMatch(m -> "V49泵手术C".equals(m.get("procedure_name"))),
                "只记了拆泵的台次不得出现在在用清单里");
        // A 台次留在用的是较晚那一支，不是先装先拆的那一支——按 id 认，不按时间字面量认
        var aRow = mine.stream().filter(m -> "V49泵手术A".equals(m.get("procedure_name")))
                .findFirst().orElseThrow();
        assertEquals(lateOn.longValue(), ((Number) aRow.get("event_id")).longValue(),
                "同台次多次装泵时未拆的是最近那一支");
        assertTrue(mine.stream().noneMatch(m -> earlyOn == ((Number) m.get("event_id")).longValue()),
                "先装先拆的那一支不得出现在在用清单里");
        assertEquals(1, ((Number) aRow.get("surgery_in_use")).intValue(), "该台次在用 1 支");
    }

    /**
     * by=surgery 下的在用量穿透同样要能跑、也要对得上账。
     * 这条分支的 SQL 与 by=event 是两段（截至当日按手术锚点日而不是事件时间判），
     * 没有用例就等于没跑过——一个语法错要到评委点开才炸。
     */
    @Test
    void painPumpInUseDrillDownWorksUnderBySurgery() {
        Long s = surgery(newAdmission(newPatient("M", 60)), "V49泵手术F", "全身麻醉", "三级", "II", "ELECTIVE");
        event(s, "PAIN_PUMP_ON", "10:00");
        event(s, "PAIN_PUMP_ON", "11:00");
        event(s, "PAIN_PUMP_OFF", "11:30");

        var r = anesQc.detail("1437", day, day, null, "surgery", "in_use");
        assertEquals(0, r.getCode(), r.getMessage());
        var body = r.getData();

        var summary = anesQc.indicators(day, day, "1437", "surgery");
        assertEquals(0, summary.getCode(), summary.getMessage());
        @SuppressWarnings("unchecked")
        var inds = (List<Map<String, Object>>) summary.getData().get("indicators");
        @SuppressWarnings("unchecked")
        var pumpRows = (List<Map<String, Object>>) inds.get(0).get("rows");
        var today = pumpRows.get(0);
        assertEquals(day, String.valueOf(today.get("op_day")));

        assertEquals(Boolean.FALSE, body.get("truncated"));
        assertEquals(num(today, "in_use"), items(body).size(),
                "by=surgery 下明细行数同样须等于汇总 op_day=to 那一行的 in_use");
        assertEquals(1, items(body).stream()
                .filter(m -> "V49泵手术F".equals(m.get("procedure_name"))).count(),
                "该台次装 2 拆 1，在用 1 支");
    }

    /** 1437★ 只有 in_use 一个分桶；传别的值返 4944，不返回一张对不上账的表 */
    @Test
    void painPumpOnlyExposesInUseBucket() {
        var r = anesQc.detail("1437", day, day, null, null, "added");
        assertEquals(4944, r.getCode(), "added / removed 与明细口径不同源，本版不假装能分桶");
        assertTrue(r.getMessage().contains("in_use"), "报错须指出唯一可用的分桶取值");
        assertEquals(List.of("in_use"), detail("1437", null).get("bucketColumns"));
    }

    /** 不传 bucket 的 1437★ 明细仍是既有的 ON/OFF 事件流水，一字不变 */
    @Test
    void painPumpPlainDetailIsUnchanged() {
        Long s = surgery(newAdmission(newPatient("M", 60)), "V49泵手术D", "全身麻醉", "三级", "II", "ELECTIVE");
        event(s, "PAIN_PUMP_ON", "10:00");
        event(s, "PAIN_PUMP_OFF", "10:30");

        var mine = items(detail("1437", null)).stream()
                .filter(m -> "V49泵手术D".equals(m.get("procedure_name")))
                .toList();
        assertEquals(2, mine.size(), "ON 与 OFF 两条流水都在");
        assertTrue(mine.stream().anyMatch(m -> "PAIN_PUMP_OFF".equals(m.get("event_type"))),
                "全量明细里必须还有拆泵事件——分桶是加法，不许改既有行为");
    }

    // ================= §④ 分桶穿透仍是纯只读 =================

    @Test
    void bucketDrillDownIsStrictlyReadOnly() {
        seedMixedSurgeries();
        Long s = surgery(newAdmission(newPatient("M", 60)), "V49泵手术E", "全身麻醉", "三级", "II", "ELECTIVE");
        event(s, "PAIN_PUMP_ON", "10:00");

        long surgeries = count("inp_surgery");
        long events = count("surg_event");

        for (String code : COMPOSITION) {
            var cols = bucketColumns(code);
            for (var row : rows(code)) {
                detail(code, keyOf(cols, row));
            }
        }
        detail("1437", "in_use");

        assertEquals(surgeries, count("inp_surgery"), "分桶穿透不得写任何一行");
        assertEquals(events, count("surg_event"), "分桶穿透不得写任何一行");
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
