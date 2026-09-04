package cn.hip.server;

import cn.hip.medtech.web.AnesQcController;
import cn.hip.platform.core.service.ConfigReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v46 车道M 回归：麻醉与手术质控指标（技术偏离表 1421★–1439★ / 1444★–1450★）。
 *
 * <p><b>本类最要紧的三组断言不是「指标算得出来」</b>：
 * <ul>
 *   <li><b>§① 只读证据</b>——把全部指标、全部穿透明细、全部 CSV 各跑一遍，断言
 *       {@code inp_surgery} / {@code surg_event} / {@code surg_transfusion} /
 *       {@code qc_adverse_event} / {@code mr_death_card} 五张表<b>行数一字不变</b>。
 *       统计层混进写路径是「跑个报表把数据改了」这类事故的源头。
 *   <li><b>§② 缺数据源不许冒充</b>——1435★ 出血量、1444★ 毒麻药、1445★ 肌松药三条必须
 *       {@code available=false} 且带原因，且<b>返回体里根本没有 rows 键</b>。
 *       返回一个「看着像真的」的 0 比不返回更坏：管理者会把「本院无毒麻药开具」当成结论。
 *   <li><b>§⑥ 汇总与明细对得上账</b>——同一时间窗下 1428★ 汇总各行 cases 之和 == 穿透明细条数。
 *       这正是 1422★1423★ 要求「可穿透查看取值明细」的目的：指标算得出但对不上账等于没做。
 * </ul>
 *
 * <p>另有一组专防「悄无声息漏统计」的断言：
 * <ul>
 *   <li><b>§⑧ 自体血只认 {@code is_auto} 布尔位</b>——「自体洗涤红细胞」是按
 *       {@code product_type='RBC' + is_auto=true} 录的，按字符串比 {@code product_type='AUTO'}
 *       会把这一整类漏掉。用例刻意造一条这样的行，钉死 1433★/1434★ 必须统计到它。
 *   <li><b>§⑨ {@code planned} 三态不得用 {@code not planned} 分组</b>——那会吞掉 null 行，
 *       让「非计划转入 ICU」这类重点指标少算，而少算的恰好是最该被看见的那部分。
 * </ul>
 *
 * <p>依赖车道 K（V140，{@code inp_surgery} 加 12 列）与车道 L（V141，术中记录三表）的真 schema。
 * <b>本车道生产代码一列没加、一张表没建、一行没写</b>——纯只读统计层。
 *
 * <p><b>时间窗刻意取 200 天前的一天</b>：夹具与既有数据必须互不干扰，否则「准点开台率」这类
 * 断言会被库里的其它手术行搅乱，测出来的是环境不是代码。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "QUALITY"})
class V46AnesQcTest {

    @Autowired AnesQcController anesQc;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    /** 夹具日：距今 200 天，避开库里既有手术与其它用例 */
    private String day;
    private String prevDay;

    @BeforeEach
    void setUp() {
        day = jdbc.queryForObject("select (current_date - 200)::text", String.class);
        prevDay = jdbc.queryForObject("select (current_date - 201)::text", String.class);
        // 阈值键在库里可能被上一次用例改过（ConfigReader 是 30 秒缓存的单例），先清干净
        jdbc.update("delete from sys_config where cfg_key = 'anes.qc.ontime_minutes'");
        configReader.evict("anes.qc.ontime_minutes");
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
                """, Long.class, uniq("V46P"), "麻醉质控" + uniq(""), sex, ageYears);
    }

    private Long newAdmission(Long patientId) {
        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        Long bedId = jdbc.queryForObject("select id from inp_bed order by id limit 1", Long.class);
        assertNotNull(bedId, "测试库须有床位种子");
        return jdbc.queryForObject("""
                insert into inp_admission(admission_no, patient_id, dept_id, ward_id, bed_id, status, admit_at)
                values (?, ?, ?, ?, ?, 'IN_HOSPITAL', now())
                returning id
                """, Long.class, uniq("V46A"), patientId, deptId, deptId, bedId);
    }

    /** 一台"字段录全"的手术（本版之后录入的形态） */
    private Long surgery(Long admissionId, String room, String schedTime, String inTime,
                         String startTime, String endTime, String outTime,
                         String level, String asa, String kind, String anesthesia) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status,
                                        scheduled_at, room_no, in_room_at, start_at, end_at, out_room_at,
                                        surgery_level, asa_grade, surgery_kind, op_icd)
                values (?, ?, ?, 'DONE', ?::timestamptz, ?, ?::timestamptz, ?::timestamptz,
                        ?::timestamptz, ?::timestamptz, ?, ?, ?, '47.09')
                returning id
                """, Long.class, admissionId, "V46阑尾切除术", anesthesia,
                ts(schedTime), room, ts(inTime), ts(startTime), ts(endTime), ts(outTime),
                level, asa, kind);
    }

    /** 本版<b>之前</b>的历史手术形态：新字段全为 null，刻意不回填 */
    private Long legacySurgery(Long admissionId, String schedTime) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status, scheduled_at)
                values (?, 'V46历史手术', '全身麻醉', 'DONE', ?::timestamptz)
                returning id
                """, Long.class, admissionId, ts(schedTime));
    }

    private String ts(String hhmm) {
        return hhmm == null ? null : (hhmm.contains(" ") ? hhmm : day + " " + hhmm);
    }

    private void event(Long surgeryId, String type, String time, Boolean planned) {
        jdbc.update("""
                insert into surg_event(surgery_id, event_type, event_time, planned, detail)
                values (?, ?, ?::timestamptz, ?, 'V46测试事件')
                """, surgeryId, type, ts(time), planned);
    }

    private void transfusion(Long surgeryId, String product, int ml, boolean isAuto) {
        jdbc.update("""
                insert into surg_transfusion(surgery_id, product_type, volume_ml, is_auto, transfused_at)
                values (?, ?, ?, ?, ?::timestamptz)
                """, surgeryId, product, ml, isAuto, ts("10:00"));
    }

    // ================= 断言小工具 =================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> indicatorsOf(String indicator) {
        var r = anesQc.indicators(day, day, indicator);
        assertEquals(0, r.getCode(), r.getMessage());
        return (List<Map<String, Object>>) r.getData().get("indicators");
    }

    private Map<String, Object> one(String indicator) {
        var list = indicatorsOf(indicator);
        assertEquals(1, list.size(), "按编码筛应只回一条");
        return list.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String indicator) {
        return (List<Map<String, Object>>) one(indicator).get("rows");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(String indicator) {
        return (Map<String, Object>) one(indicator).get("summary");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> detailItems(String indicator) {
        var r = anesQc.detail(indicator, day, day, null);
        assertEquals(0, r.getCode(), r.getMessage());
        return (List<Map<String, Object>>) r.getData().get("items");
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static double dbl(Map<String, Object> row, String key) {
        Object v = row.get(key);
        assertNotNull(v, key + " 不应为空");
        return ((Number) v).doubleValue();
    }

    private Map<String, Object> tableCounts() {
        var m = new LinkedHashMap<String, Object>();
        for (String t : new String[]{"inp_surgery", "surg_event", "surg_transfusion",
                                     "qc_adverse_event", "mr_death_card", "inp_admission"}) {
            m.put(t, jdbc.queryForObject("select count(*) from " + t, Long.class));
        }
        return m;
    }

    // ================= §① 纯只读证据 =================

    /**
     * 把全部指标 / 全部穿透明细 / 两个 CSV 端点各跑一遍，五张相关表行数一字不变。
     * <b>统计层混进写路径</b>是「跑个报表把数据改了」这类事故的源头，必须钉死。
     */
    @Test
    void everyEndpointIsStrictlyReadOnly() {
        Long adm = newAdmission(newPatient("M", 40));
        Long s = surgery(adm, "OR-1", "08:00", "08:05", "08:10", "09:30", "09:40",
                "三级", "II", "ELECTIVE", "全身麻醉");
        event(s, "TO_ICU", "09:45", Boolean.FALSE);
        transfusion(s, "RBC", 400, false);

        var before = tableCounts();

        // 全量指标
        var all = anesQc.indicators(day, day, null);
        assertEquals(0, all.getCode());
        // 逐指标：汇总 + 明细 + 两个 CSV
        for (var d : anesQc.catalog().getData()) {
            String code = (String) d.get("code");
            assertEquals(0, anesQc.indicators(day, day, code).getCode(), "指标 " + code + " 汇总应可执行");
            assertEquals(0, anesQc.detail(code, day, day, null).getCode(), "指标 " + code + " 明细应可执行");
            assertNotNull(anesQc.indicatorsCsv(code, day, day), "指标 " + code + " 汇总 CSV");
            assertNotNull(anesQc.detailCsv(code, day, day), "指标 " + code + " 明细 CSV");
        }

        assertEquals(before, tableCounts(), "统计与穿透与导出全程不得写任何一行");
    }

    // ================= §② 缺数据源三条：available=false，且不给 0 =================

    @Test
    void indicatorsWithoutDataSourceAreReportedHonestly() {
        for (String code : new String[]{"1435", "1444", "1445"}) {
            var ind = one(code);
            assertEquals(Boolean.FALSE, ind.get("available"), code + "★ 应标缺数据源");
            String reason = (String) ind.get("unavailableReason");
            assertNotNull(reason, code + "★ 必须写明为什么没有");
            assertFalse(reason.isBlank());
            assertFalse(ind.containsKey("rows"),
                    code + "★ 不得返回 rows：一个看着像真的的 0 比不返回更坏");
            assertFalse(ind.containsKey("summary"), code + "★ 不得返回 summary");

            // 穿透与 CSV 同样诚实：空明细 + 原因，而不是一张全 0 的表
            var detail = anesQc.detail(code, day, day, null);
            assertEquals(0, detail.getCode());
            assertEquals(Boolean.FALSE, detail.getData().get("available"));
            assertEquals(List.of(), detail.getData().get("items"));
            assertNotNull(detail.getData().get("unavailableReason"));
            assertTrue(anesQc.indicatorsCsv(code, day, day).contains("缺数据源原因"),
                    code + "★ CSV 导出的应是「为什么没有」");
        }
        // 出血量绝不能拿输血量冒充——原因里必须点破这一点
        assertTrue(((String) one("1435").get("unavailableReason")).contains("输血量"),
                "1435★ 须明写「输血量不是出血量」");
    }

    /** 其余 20 条指标必须真出得来（available=true 且有 rows），不许整域空转 */
    @Test
    void availableIndicatorsAllReturnRows() {
        var list = indicatorsOf(null);
        assertEquals(23, list.size(), "指标注册表共 23 条");
        long unavailable = list.stream().filter(m -> Boolean.FALSE.equals(m.get("available"))).count();
        assertEquals(3, unavailable, "只有 1435/1444/1445 三条缺数据源");
        for (var ind : list) {
            if (Boolean.TRUE.equals(ind.get("available"))) {
                assertNotNull(ind.get("rows"), ind.get("code") + " 应有 rows");
                assertNotNull(ind.get("detailEndpoint"), ind.get("code") + " 应给穿透入口");
            }
        }
    }

    // ================= §③ 校验：4940 / 4941 / 4942 =================

    @Test
    void invalidWindowReturns4940() {
        assertEquals(4940, anesQc.indicators("2026-03-10", "2026-03-01", null).getCode(), "起止倒置");
        assertEquals(4940, anesQc.indicators("2024-01-01", "2026-01-01", null).getCode(), "跨度超 366 天");
        assertEquals(4940, anesQc.indicators("2026/03/01", "2026-03-10", null).getCode(), "日期格式非法");
        assertEquals(4940, anesQc.detail("1424", "2026-03-10", "2026-03-01", null).getCode(), "穿透同样校验");
        assertTrue(anesQc.indicatorsCsv("1424", "2026-03-10", "2026-03-01").contains("4940"),
                "CSV 无 code 字段承载错误，须把错误码写进正文，不导空表");
    }

    @Test
    void unknownIndicatorCodeReturns4941() {
        assertEquals(4941, anesQc.indicators(day, day, "9999").getCode());
        assertEquals(4941, anesQc.detail("9999", day, day, null).getCode());
        assertEquals(4941, anesQc.detail(null, day, day, null).getCode(), "穿透必须指定指标");
        assertTrue(anesQc.detailCsv("9999", day, day).contains("4941"));
    }

    @Test
    void detailLimitOverCapReturns4942() {
        assertEquals(4942, anesQc.detail("1424", day, day, 201).getCode());
        assertEquals(0, anesQc.detail("1424", day, day, 200).getCode(), "正好 200 应放行");
        assertEquals(0, anesQc.detail("1424", day, day, null).getCode(), "不传 limit 走默认上限");
    }

    // ================= §④ 1424★ 首台准点：阈值走 sys_config，不硬编码 =================

    @Test
    void firstCaseOnTimeRateUsesConfigurableThreshold() {
        Long adm = newAdmission(newPatient("M", 50));
        // OR-A 首台：排台 08:00 实开 08:20（默认阈值 30 分钟 → 准点）
        surgery(adm, "V46-OR-A", "08:00", "08:15", "08:20", "09:00", "09:10",
                "二级", "II", "ELECTIVE", "全身麻醉");
        // OR-A 第二台：不是首台，不进 1424 分子分母
        surgery(adm, "V46-OR-A", "09:30", "09:40", "09:45", "10:30", "10:40",
                "二级", "II", "ELECTIVE", "全身麻醉");
        // OR-B 首台：排台 08:00 实开 09:10（迟 70 分钟 → 延迟）
        surgery(adm, "V46-OR-B", "08:00", "09:00", "09:10", "10:00", "10:10",
                "三级", "III", "ELECTIVE", "椎管内麻醉");
        // OR-C 首台：只排了台没录开台时间 → 判不了，不并进分母
        surgery(adm, "V46-OR-C", "08:00", null, null, null, null,
                null, null, "ELECTIVE", "全身麻醉");
        // OR-D 首台：急诊、准点 —— 进全量口径，不进 2022 国标的「择期首台」口径
        surgery(adm, "V46-OR-D", "08:00", "08:00", "08:05", "09:00", "09:10",
                "三级", "III", "EMERGENCY", "全身麻醉");

        var sum = summary("1424");
        assertEquals(4, num(sum, "first_cases"), "四个手术间各一台首台");
        assertEquals(2, num(sum, "on_time"), "OR-A 与 OR-D 准点");
        assertEquals(1, num(sum, "delayed"), "OR-B 延迟 70 分钟");
        assertEquals(1, num(sum, "unjudgeable"), "OR-C 缺开台时间，判不了");
        assertEquals(66.67, dbl(sum, "on_time_rate_pct"), 0.01,
                "准点率分母只含判得了的 3 台，不把判不了的算成不准点");
        // 2022 国标口径只算择期首台：急诊那台不进分子也不进分母
        assertEquals(3, num(sum, "elective_first_cases"));
        assertEquals(1, num(sum, "elective_on_time"));
        assertEquals(1, num(sum, "elective_delayed"));
        assertEquals(50.0, dbl(sum, "elective_on_time_rate_pct"), 0.01,
                "两套口径必须并排给：只给国标口径会让 surgery_kind 未录的历史手术整片消失");

        var byDay = rows("1424");
        assertEquals(1, byDay.size());
        assertEquals(4, num(byDay.get(0), "first_cases"));
        assertEquals(3, num(byDay.get(0), "elective_first_cases"));
        assertNotNull(byDay.get(0).get("first_case_depts"), "首台科室须给出");

        // 阈值调到 10 分钟：同一批数据，OR-A 的 20 分钟即算延迟 —— 证明口径真的走配置
        jdbc.update("""
                insert into sys_config(cfg_key, cfg_value, remark) values ('anes.qc.ontime_minutes', '10', 'v46 test')
                on conflict (cfg_key) do update set cfg_value = excluded.cfg_value
                """);
        configReader.evict("anes.qc.ontime_minutes");
        try {
            assertEquals(10, ((Number) anesQc.indicators(day, day, "1424").getData().get("onTimeMinutes")).intValue());
            var tight = summary("1424");
            assertEquals(1, num(tight, "on_time"), "阈值收到 10 分钟后只剩 OR-D（迟 5 分钟）准点");
            assertEquals(2, num(tight, "delayed"), "OR-A 的 20 分钟与 OR-B 的 70 分钟都算延迟");
            assertEquals(0, num(tight, "elective_on_time"), "择期口径里 OR-A 变延迟");
        } finally {
            // ConfigReader 是跨用例存活的单例缓存，事务回滚清不掉它
            jdbc.update("delete from sys_config where cfg_key = 'anes.qc.ontime_minutes'");
            configReader.evict("anes.qc.ontime_minutes");
        }
    }

    /** 穿透明细逐台给出「排台 / 开台 / 延迟分钟 / 判定」，否则「准点率 50%」无从核对 */
    @Test
    void firstCaseDetailShowsPerCaseJudgement() {
        Long adm = newAdmission(newPatient("F", 33));
        surgery(adm, "V46-OR-D", "08:00", "08:15", "08:20", "09:00", "09:10",
                "二级", "I", "ELECTIVE", "全身麻醉");
        var items = detailItems("1424");
        assertEquals(1, items.size());
        var it = items.get(0);
        assertEquals("准点", it.get("judgement"));
        assertEquals(20L, num(it, "delay_minutes"));
        assertNotNull(it.get("admission_no"), "明细须能核对到住院号");
        assertNotNull(it.get("patient_name"), "明细须能核对到人");
    }

    // ================= §⑤ 1425★ 接台时长 =================

    @Test
    void turnoverMinutesComputedBetweenAdjacentCasesInSameRoom() {
        Long adm = newAdmission(newPatient("M", 60));
        // 同一手术间同日两台：上一台 09:40 出室 → 下一台 10:00 入室 = 接台 20 分钟
        surgery(adm, "V46-OR-T", "08:00", "08:10", "08:20", "09:30", "09:40",
                "二级", "II", "ELECTIVE", "全身麻醉");
        surgery(adm, "V46-OR-T", "10:00", "10:00", "10:10", "11:10", "11:20",
                "二级", "II", "ELECTIVE", "全身麻醉");
        // 另一手术间当日单台：无相邻对，不产生接台
        surgery(adm, "V46-OR-U", "08:00", "08:00", "08:10", "09:00", "09:10",
                "一级", "I", "ELECTIVE", "局部麻醉");

        var r = rows("1425");
        assertEquals(1, r.size(), "只有夹具日一行");
        var row = r.get(0);
        assertEquals(3, num(row, "cases"));
        assertEquals(3, num(row, "timed_cases"));
        assertEquals(1, num(row, "turnovers"), "只有 OR-T 那一对构成接台");
        assertEquals(20.0, dbl(row, "total_turnover_minutes"), 0.01);
        assertEquals(20.0, dbl(row, "avg_turnover_minutes"), 0.01);
        // 手术时长：70 + 60 + 50 = 180 分钟
        assertEquals(180.0, dbl(row, "total_op_minutes"), 0.01);
        assertEquals(60.0, dbl(row, "avg_op_minutes"), 0.01);
    }

    // ================= §⑥ 汇总与明细对得上账（1422★1423★ 的目的） =================

    @Test
    void summaryTotalsReconcileWithDrillDownDetail() {
        Long adm = newAdmission(newPatient("M", 45));
        surgery(adm, "V46-OR-R", "08:00", "08:00", "08:10", "09:00", "09:10",
                "二级", "II", "ELECTIVE", "全身麻醉");
        surgery(adm, "V46-OR-R", "10:00", "10:00", "10:10", "11:00", "11:10",
                "三级", "III", "EMERGENCY", "椎管内麻醉");
        surgery(adm, "V46-OR-S", "08:00", "08:00", "08:10", "09:00", "09:10",
                null, "I", "DAY", "局部麻醉");
        legacySurgery(adm, "07:00");   // 历史行：新字段全空，仍应被计入总量

        long byKind = rows("1428").stream().mapToLong(m -> num(m, "cases")).sum();
        assertEquals(4, byKind, "四台（含一台字段全空的历史手术）都要计入");
        assertEquals(byKind, detailItems("1428").size(), "汇总合计须等于穿透明细条数");

        long byLevel = rows("1439").stream().mapToLong(m -> num(m, "cases")).sum();
        assertEquals(4, byLevel, "未分级的两台走「（未分级）」行，不被丢掉");

        // 覆盖率段：4 台里只有 3 台录了开台时间 —— 这一段就是防「以为是全院全历史口径」
        @SuppressWarnings("unchecked")
        Map<String, Object> cov = (Map<String, Object>) anesQc.indicators(day, day, "1428")
                .getData().get("coverage");
        assertEquals(4, num(cov, "surgeries"));
        assertEquals(3, num(cov, "with_start"), "历史手术无开台时间");
        assertEquals(3, num(cov, "with_room"));
        assertEquals(2, num(cov, "with_level"), "只有两台分了级");
    }

    /** 历史手术（新字段全 null）既不被漏掉，也不被算成「准点」或「未分级=一级」 */
    @Test
    void legacySurgeriesCountedButNeverFabricated() {
        Long adm = newAdmission(newPatient("F", 70));
        legacySurgery(adm, "07:00");

        assertEquals(1, rows("1428").stream().mapToLong(m -> num(m, "cases")).sum(), "历史手术要计入总量");
        assertEquals("（未填写）", rows("1428").get(0).get("surgery_kind"), "类别为空就写未填写，不猜择期");
        assertEquals("（未分级）", rows("1439").get(0).get("surgery_level"), "级别为空就写未分级");
        assertEquals("（未填写）", rows("1429").get(0).get("asa_grade"), "ASA 为空不臆造");
        // 无手术间 → 不进首台统计（首台是按手术间定义的，硬凑会造出假的准点率）
        assertEquals(0, num(summary("1424"), "first_cases"));
        assertEquals(0, num(rows("1425").get(0), "turnovers"), "接台需入室/出室时间，历史行不产生接台");
    }

    // ================= §⑦ ASA × 死亡（1429★） =================

    @Test
    void asaGradeCrossedWithDeathFromDeathCardOnly() {
        Long pid = newPatient("M", 80);
        Long adm = newAdmission(pid);
        surgery(adm, "V46-OR-E", "08:00", "08:00", "08:10", "09:00", "09:10",
                "四级", "IV", "EMERGENCY", "全身麻醉");
        Long adm2 = newAdmission(newPatient("F", 30));
        surgery(adm2, "V46-OR-E", "10:00", "10:00", "10:10", "11:00", "11:10",
                "二级", "II", "ELECTIVE", "全身麻醉");
        jdbc.update("""
                insert into mr_death_card(patient_id, admission_id, died_at, direct_cause)
                values (?, ?, now(), 'V46测试死因')
                """, pid, adm);

        var byAsa = rows("1429");
        var iv = byAsa.stream().filter(m -> "IV".equals(m.get("asa_grade"))).findFirst().orElseThrow();
        var ii = byAsa.stream().filter(m -> "II".equals(m.get("asa_grade"))).findFirst().orElseThrow();
        assertEquals(1, num(iv, "cases"));
        assertEquals(1, num(iv, "deaths"), "死亡按死亡登记卡关联住院判定");
        assertEquals(100.0, dbl(iv, "death_rate_pct"), 0.01);
        assertEquals(0, num(ii, "deaths"));

        // 口径必须写明「未登记死亡卡的死亡病例判不出来」，不能让人当上报口径用
        assertTrue(((String) one("1429").get("note")).contains("死亡登记卡"));
    }

    // ================= §⑧ 输血三条（1432★/1433★/1434★） =================

    @Test
    void transfusionByProductBandAndPatientCounts() {
        Long pid = newPatient("M", 55);
        Long adm = newAdmission(pid);
        Long s = surgery(adm, "V46-OR-F", "08:00", "08:00", "08:10", "10:00", "10:10",
                "三级", "III", "ELECTIVE", "全身麻醉");
        transfusion(s, "AUTO", 600, true);     // 自体血 600ml
        transfusion(s, "RBC", 400, false);     // 非自体红细胞
        // ★ 陷阱行：自体洗涤红细胞 —— product_type='RBC' 而 is_auto=true。
        //   按 product_type='AUTO' 字符串比对会把它整片漏掉，且漏得悄无声息。
        transfusion(s, "RBC", 300, true);
        Long adm2 = newAdmission(newPatient("F", 44));
        Long s2 = surgery(adm2, "V46-OR-F", "11:00", "11:00", "11:10", "12:00", "12:10",
                "二级", "II", "ELECTIVE", "全身麻醉");
        transfusion(s2, "PLASMA", 200, false); // 只输非自体

        // 1432★ 类型与总量（按制品形态归集，另给「其中自体血」列）
        var byProduct = rows("1432");
        assertEquals(3, byProduct.size());
        var auto = byProduct.stream().filter(m -> "AUTO".equals(m.get("product_type"))).findFirst().orElseThrow();
        assertEquals("自体血", auto.get("product_name"));
        assertEquals(600, num(auto, "total_ml"));
        var rbc = byProduct.stream().filter(m -> "RBC".equals(m.get("product_type"))).findFirst().orElseThrow();
        assertEquals(700, num(rbc, "total_ml"), "红细胞 400 非自体 + 300 自体洗涤");
        assertEquals(1, num(rbc, "auto_records"), "红细胞行须显式给出其中自体血的部分");
        assertEquals(300, num(rbc, "auto_ml"));

        // 1433★ 自体血按输注量分档统计人数 —— 900 = 600(AUTO) + 300(RBC+is_auto)。
        // 若按 product_type='AUTO' 比对，这里会是 600，且没有任何报错提示漏了 300。
        var bands = rows("1433");
        assertEquals(1, bands.size());
        assertEquals("400–1000ml", bands.get(0).get("band"));
        assertEquals(1, num(bands.get(0), "patients"));
        assertEquals(900, num(bands.get(0), "total_ml"),
                "自体血只认 is_auto：自体洗涤红细胞（product_type='RBC'）也必须计入");

        // 1434★ 三个患者数 + 显式给出重叠人数（两列相加 != 总数）
        var counts = rows("1434").get(0);
        assertEquals(2, num(counts, "transfused_patients"), "口径是患者数：同一患者两条输血记录只算一人");
        assertEquals(1, num(counts, "auto_patients"));
        assertEquals(2, num(counts, "non_auto_patients"));
        assertEquals(1, num(counts, "both_patients"), "重叠人数须显式给出，否则相加对不上");
        assertEquals(2, num(counts, "transfused_surgeries"), "台次数另列，不与患者数混为一谈");
        assertTrue(((String) one("1434").get("note")).contains("is_auto"),
                "1434★ 须写明自体血只认 is_auto 布尔位，不按 product_type 字符串比对");
    }

    // ================= §⑨ 术中事件四组（1437★/1446★/1447★/1448★/1449★） =================

    @Test
    void intraOpEventIndicators() {
        Long adm = newAdmission(newPatient("M", 62));
        Long s = surgery(adm, "V46-OR-G", "08:00", "08:00", "08:10", "10:00", "10:10",
                "三级", "III", "ELECTIVE", "全身麻醉");
        event(s, "PAIN_PUMP_ON", "10:15", null);
        event(s, "PAIN_PUMP_OFF", "10:20", null);
        event(s, "PAIN_PUMP_ON", "10:25", null);
        event(s, "TO_ICU", "10:30", Boolean.FALSE);
        event(s, "TO_PACU", "10:35", null);           // planned 未区分
        event(s, "INTUBATE_OR", "08:05", Boolean.TRUE);
        event(s, "EXTUBATE", "10:05", null);
        event(s, "REINTUBATE", "10:08", Boolean.FALSE);   // 非计划再插管：重点监控项，不许被 null 吞掉
        event(s, "OUT_WITH_TUBE", "10:10", null);
        event(s, "INVASIVE", "08:30", null);
        event(s, "RESCUE", "09:00", null);

        // 1437★ 镇痛泵：当日 2 增 1 拆，在用 1（统计窗只有夹具日一天，故只会有一行）
        var pumpRows = rows("1437");
        assertEquals(1, pumpRows.size());
        var pump = pumpRows.get(0);
        assertEquals(2, num(pump, "added"));
        assertEquals(1, num(pump, "removed"));
        assertEquals(1, num(pump, "in_use"));

        // 1446★ 转 ICU / 苏醒室 × 计划性；planned 为空单列「未区分」，绝不默认算计划内
        // （实现用「未区分」而非「未标注」——planned=null 是"这条没区分计划性"，不是"漏填"）
        var transfers = rows("1446");
        assertEquals(2, transfers.size());
        var icu = transfers.stream().filter(m -> "转入 ICU".equals(m.get("target"))).findFirst().orElseThrow();
        assertEquals("非计划", icu.get("planned_name"));
        var pacu = transfers.stream().filter(m -> String.valueOf(m.get("target")).startsWith("转入苏醒室"))
                .findFirst().orElseThrow();
        assertEquals("未区分", pacu.get("planned_name"));

        // 1447★ 插管/拔管/带管出室 + 计划性三态拆分
        var tubes = rows("1447");
        assertEquals(4, tubes.size());
        assertEquals(1, tubes.stream().mapToLong(m -> num(m, "events")).min().orElseThrow());
        assertTrue(tubes.stream().anyMatch(m -> "带管出室".equals(m.get("event_name"))));
        var reint = tubes.stream().filter(m -> "REINTUBATE".equals(m.get("event_type")))
                .findFirst().orElseThrow();
        assertEquals(1, num(reint, "unplanned_events"), "非计划再插管须算得出来，不能被 null 行吞掉");
        assertEquals(0, num(reint, "planned_events"));
        var extub = tubes.stream().filter(m -> "EXTUBATE".equals(m.get("event_type")))
                .findFirst().orElseThrow();
        assertEquals(1, num(extub, "unspecified_events"), "planned=null 单列「未区分」，不并进计划或非计划");
        assertEquals(0, num(extub, "planned_events"));
        assertEquals(0, num(extub, "unplanned_events"));
        // 三态之和恒等于总数：写成 not planned 会让这条断言当场失败
        for (var t : tubes) {
            assertEquals(num(t, "events"),
                    num(t, "planned_events") + num(t, "unplanned_events") + num(t, "unspecified_events"),
                    t.get("event_type") + " 的计划/非计划/未区分三态之和须等于事件总数");
        }

        // 1448★ 有创操作 / 1449★ 抢救
        assertEquals(1, rows("1448").stream().mapToLong(m -> num(m, "events")).sum());
        assertEquals(1, rows("1449").stream().mapToLong(m -> num(m, "events")).sum());

        // 穿透明细能落到人到台
        var invasiveDetail = detailItems("1448");
        assertEquals(1, invasiveDetail.size());
        assertNotNull(invasiveDetail.get(0).get("patient_name"));
        assertEquals("INVASIVE", invasiveDetail.get(0).get("event_type"));
    }

    // ================= §⑩ 取消 / 跨日 / 麻醉方式 / 局麻科室 / 年龄性别 =================

    @Test
    void cancelStagesCrossDayAnesthesiaAndDemographics() {
        Long adm = newAdmission(newPatient("F", 8));
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, status, scheduled_at, cancel_stage, cancel_reason)
                values (?, 'V46取消术', 'CANCELLED', ?::timestamptz, 'PRE_IN', '患者血压过高')
                """, adm, ts("08:00"));
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, status, scheduled_at, cancel_stage)
                values (?, 'V46取消术2', 'CANCELLED', ?::timestamptz, 'IN_OP')
                """, adm, ts("09:00"));

        var cancels = rows("1426");
        assertEquals(2, cancels.size());
        assertEquals("入室前取消", cancels.get(0).get("stage_name"), "四阶段按 APPLY→SCHEDULE→PRE_IN→IN_OP 排序");
        assertEquals(1, num(cancels.get(0), "with_reason"));
        assertEquals("术中取消", cancels.get(1).get("stage_name"));
        // 取消的手术不进「做了几台」类指标
        assertEquals(0, rows("1428").stream().mapToLong(m -> num(m, "cases")).sum(),
                "取消的手术不计入手术类别构成");

        // 跨日手术：开台当日 23:00 → 次日 01:00
        Long adm2 = newAdmission(newPatient("M", 21));
        jdbc.update("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status, scheduled_at,
                                        room_no, in_room_at, start_at, end_at, out_room_at, surgery_level)
                values (?, 'V46跨日术', '局部麻醉', 'DONE', ?::timestamptz, 'V46-OR-H',
                        ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, '三级')
                """, adm2, ts("22:30"), ts("22:40"), ts("23:00"),
                day + " 23:00", day + " 23:00");
        jdbc.update("""
                update inp_surgery set end_at = (?::date + interval '1 day' + interval '1 hour'),
                                       out_room_at = (?::date + interval '1 day' + interval '1 hour 10 minutes')
                where procedure_name = 'V46跨日术' and admission_id = ?
                """, day, day, adm2);

        var crossDay = summary("1427");
        assertEquals(1, num(crossDay, "cross_day_cases"));
        assertEquals(1, num(crossDay, "timed_cases"));

        // 1430★ 麻醉方式 / 1431★ 局麻按科室
        assertEquals("局部麻醉", rows("1430").get(0).get("anesthesia_type"));
        assertEquals(1, rows("1431").stream().mapToLong(m -> num(m, "cases")).sum(), "局麻计入科室分布");

        // 1436★ 年龄段 × 性别
        var demo = rows("1436");
        assertEquals(1, demo.size());
        assertEquals("18–44 岁", demo.get(0).get("age_band"));
        assertEquals("男", demo.get(0).get("sex"));
    }

    // ================= §⑪ 1450★ 不良事件 + CSV 导出 =================

    @Test
    void adverseEventsAndCsvExport() {
        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        jdbc.update("""
                insert into qc_adverse_event(type, level, occurred_on, dept_id, description, status)
                values ('V46麻醉意外', 2, ?::date, ?, '=测试逗号,与公式注入', 'NEW')
                """, day, deptId);

        var ae = rows("1450");
        assertEquals(1, ae.size());
        assertEquals("V46麻醉意外", ae.get(0).get("event_type"));
        assertEquals(1, num(ae.get(0), "level2"));
        assertTrue(((String) one("1450").get("note")).contains("全院口径"),
                "不良事件表无 surgery_id，须诚实标注是全院口径而非手术麻醉专项");

        // CSV：BOM + 中文表头 + 口径尾注；公式注入与逗号须被转义
        String csv = anesQc.detailCsv("1450", day, day);
        assertTrue(csv.startsWith("﻿"), "须带 BOM，Excel 才不乱码");
        assertTrue(csv.contains("发生日期"), "表头须中文化");
        assertTrue(csv.contains("口径："), "口径说明须随表导出，与页面 alert 同源");
        assertTrue(csv.contains("\"'=测试逗号,与公式注入\""),
                "以 = 开头的自由文本须加 ' 前缀并整体加引号，防 Excel 当公式执行/串列");

        String summaryCsv = anesQc.indicatorsCsv("1450", day, day);
        assertTrue(summaryCsv.contains("1450"));
        assertTrue(summaryCsv.contains("事件次数"));
    }

    /** 空区间导出的是「无数据」而不是一张看不出真假的空表 */
    @Test
    void csvOnEmptyWindowSaysSoExplicitly() {
        String csv = anesQc.indicatorsCsv("1428", prevDay, prevDay);
        assertTrue(csv.contains("（该统计区间内无数据）"));
        assertTrue(csv.contains("口径："));
    }

    // ================= §⑫ 目录端点 =================

    @Test
    void catalogListsEveryIndicatorWithAvailability() {
        var catalog = anesQc.catalog().getData();
        assertEquals(23, catalog.size());
        assertEquals(3, catalog.stream().filter(m -> Boolean.FALSE.equals(m.get("available"))).count());
        assertTrue(catalog.stream().allMatch(m -> m.get("name") != null && !((String) m.get("name")).isBlank()));
    }

    // ================= §⑬ 权限：与 V142 菜单 112 的授权逐字对齐 =================

    /**
     * 穿透明细逐条带住院号与患者姓名，是<b>全院范围、无科室边界</b>的患者级数据。
     * 本端点因此只对 ADMIN / QUALITY 开放（= V142 菜单 112 的授权），
     * <b>不能成为一条绕开专页限权的取数通道</b>。护士角色在此必须吃 403。
     */
    @Test
    @WithMockUser(roles = "NURSE")
    void patientLevelDrillDownIsNotOpenToEveryClinicalRole() {
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> anesQc.detail("1428", day, day, null), "护士不得穿透全院患者级明细");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> anesQc.indicators(day, day, null), "指标看板同样限管理侧");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> anesQc.detailCsv("1428", day, day), "CSV 导出不得是绕开限权的后门");
    }
}
