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

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v81 车道 A：1424★ / 1425★ 两处口径硬伤（2026-09-06 复核反驳者打回、v49 未修）。
 *
 * <p><b>1424★「延迟开台量」</b>：条款把「延迟开台数量」与「延迟开台量」并列，后者是延迟的<b>量</b>（分钟），
 * 不是台数的重复。修正前汇总只有延迟台数；明细的 {@code delay_minutes} 是「实开 − 排台」的有符号偏移——
 * 准点台次（迟 20 分钟、阈值 30）也是正数，提前开台是负数，拿明细加总根本得不出「延迟量」。
 * 本类钉死：汇总 {@code delayed_minutes_total / avg_delay_minutes}（及择期国标口径一套）只对判为延迟的首台求；
 * 明细 {@code delay_minutes} 仅延迟台有值，另给有符号的 {@code start_offset_minutes} 与 {@code delayed / on_time}；
 * <b>汇总延迟量 == 明细延迟分钟逐台加总</b>。
 *
 * <p><b>1425★「入室时间」与接台对账</b>：修正前汇总无入室时间；明细接台用 {@code max(out_room_at)} 横连、
 * 汇总用 {@code lag()}，入室早于上一台出室（重叠/录错）时两边算出不同的对数与分钟。
 * 本类钉死：汇总给 {@code first_in_room_at / in_room_recorded}；<b>明细接台对数与分钟逐对加总 == 汇总</b>。
 *
 * <p>夹具日取距今 260 / 261 天，避开 V46（200/201）、V49 系（210/220/298–300）的夹具窗。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "QUALITY"})
class V81AnesQcCaliberTest {

    @Autowired AnesQcController anesQc;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    /** 1424★ 夹具日 */
    private String dayA;
    /** 1425★ 夹具日：与 1424 分日，免得接台夹具改了首台判定 */
    private String dayB;

    @BeforeEach
    void setUp() {
        dayA = jdbc.queryForObject("select (current_date - 260)::text", String.class);
        dayB = jdbc.queryForObject("select (current_date - 261)::text", String.class);
        jdbc.update("delete from sys_config where cfg_key = 'anes.qc.ontime_minutes'");
        configReader.evict("anes.qc.ontime_minutes");
        Long others = jdbc.queryForObject("""
                select count(*) from inp_surgery s
                where coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) >= ?::date
                  and coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) <  ?::date + 1
                """, Long.class, dayB, dayA);
        assertEquals(0L, others, "夹具窗口内不得有其它手术，否则对账测的是环境");
    }

    // ================= 夹具 =================

    private static long seq = 0;

    private static String uniq(String prefix) {
        return prefix + (System.nanoTime() % 100000000L) + (seq++);
    }

    private Long newAdmission() {
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'M', current_date - make_interval(years => 50))
                returning id
                """, Long.class, uniq("V81P"), "口径修正" + uniq(""));
        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        Long bedId = jdbc.queryForObject("select id from inp_bed order by id limit 1", Long.class);
        assertNotNull(bedId, "测试库须有床位种子");
        return jdbc.queryForObject("""
                insert into inp_admission(admission_no, patient_id, dept_id, ward_id, bed_id, status, admit_at)
                values (?, ?, ?, ?, ?, 'IN_HOSPITAL', now())
                returning id
                """, Long.class, uniq("V81A"), patientId, deptId, deptId, bedId);
    }

    /** 时间参数一律 "HH:mm"，落在 day 当天（会话时区 Asia/Shanghai 解释，不经 JVM 默认时区） */
    private Long surgery(Long adm, String day, String room, String sched, String in,
                         String start, String end, String out, String kind) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status,
                                        scheduled_at, room_no, in_room_at, start_at, end_at, out_room_at,
                                        surgery_level, asa_grade, surgery_kind, op_icd)
                values (?, 'V81口径夹具术', '全身麻醉', 'DONE', ?::timestamptz, ?, ?::timestamptz,
                        ?::timestamptz, ?::timestamptz, ?::timestamptz, '二级', 'II', ?, '47.09')
                returning id
                """, Long.class, adm, at(day, sched), room, at(day, in), at(day, start), at(day, end),
                at(day, out), kind);
    }

    private static String at(String day, String hhmm) {
        return hhmm == null ? null : day + " " + hhmm;
    }

    // ================= 取数 =================

    @SuppressWarnings("unchecked")
    private Map<String, Object> indicator(String code, String day) {
        var r = anesQc.indicators(day, day, code);
        assertEquals(0, r.getCode(), r.getMessage());
        var list = (List<Map<String, Object>>) r.getData().get("indicators");
        assertEquals(1, list.size());
        return list.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String code, String day) {
        return (List<Map<String, Object>>) indicator(code, day).get("rows");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(String code, String day) {
        return (Map<String, Object>) indicator(code, day).get("summary");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String code, String day) {
        var r = anesQc.detail(code, day, day, null);
        assertEquals(0, r.getCode(), r.getMessage());
        assertEquals(Boolean.FALSE, r.getData().get("truncated"));
        return (List<Map<String, Object>>) r.getData().get("items");
    }

    private static Double dbl(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static Map<String, Object> bySurgery(List<Map<String, Object>> items, Long id) {
        return items.stream().filter(x -> Objects.equals(num(x, "surgery_id"), id)).findFirst()
                .orElseGet(() -> fail("明细里找不到手术 " + id));
    }

    private static double sumOf(List<Map<String, Object>> items, String key) {
        return items.stream().map(x -> dbl(x, key)).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    }

    @Test
    void midnightCaseMustNotStealNextDaysFirstCase() {
        Long adm = newAdmission();
        Long night = surgery(adm, dayB, "V81-NIGHT", "23:00", "23:30", "23:40", "23:50", "23:55", "EMERGENCY");
        jdbc.update("update inp_surgery set start_at = ?::date + interval '10 minutes', end_at = ?::date + interval '50 minutes', out_room_at = ?::date + interval '60 minutes' where id = ?", dayA, dayA, dayA, night);
        Long morning = surgery(adm, dayA, "V81-NIGHT", "08:00", "07:50", "08:00", "09:00", "09:10", "ELECTIVE");
        assertEquals(morning, num(items("1424", dayA).getFirst(), "surgery_id"));
        assertEquals(1, num(summary("1424", dayB), "first_cases"));
        assertEquals(0, num(summary("1425", dayA), "turnovers"));
    }

    @Test
    void electiveFirstCaseIsSelectedWithinElectivePopulation() {
        Long adm = newAdmission();
        surgery(adm, dayA, "V81-ELECT", "02:00", "01:50", "02:00", "03:00", "03:10", "EMERGENCY");
        surgery(adm, dayA, "V81-ELECT", "08:00", "08:40", "08:50", "09:30", "09:40", "ELECTIVE");
        var total = summary("1424", dayA);
        assertEquals(1, num(total, "first_cases"));
        assertEquals(1, num(total, "elective_first_cases"));
        assertEquals(1, num(total, "elective_delayed"));
        assertEquals(50.0, dbl(total, "elective_delayed_minutes_total"));
    }

    @Test
    void missingEntryMustNotMakeTurnoverSkipAnInterveningCase() {
        Long adm = newAdmission();
        surgery(adm, dayA, "V81-GAP", "08:00", "08:00", "08:10", "09:00", "09:10", "ELECTIVE");
        surgery(adm, dayA, "V81-GAP", "09:30", null, "09:40", "10:00", "10:20", "ELECTIVE");
        Long last = surgery(adm, dayA, "V81-GAP", "10:30", "10:40", "10:50", "11:30", "11:40", "ELECTIVE");
        assertEquals(20.0, dbl(bySurgery(items("1425", dayA), last), "turnover_minutes"));
        assertEquals(20.0, dbl(summary("1425", dayA), "total_turnover_minutes"));
    }

    // ================= 1424★ 延迟开台量 =================

    /**
     * 四间手术间各一台首台（阈值默认 30 分钟）：
     * OR-X 择期迟 20 分钟（准点）、OR-Y 择期迟 70 分钟（延迟）、OR-Z 择期提前 15 分钟（准点）、
     * OR-W 急诊迟 45 分钟（延迟）。OR-X 另有一台非首台（迟 70 分钟），不得进 1424 任何一个数。
     */
    private Map<String, Long> seed1424() {
        Long adm = newAdmission();
        Long x = surgery(adm, dayA, "V81-OR-X", "08:00", "08:10", "08:20", "09:00", "09:10", "ELECTIVE");
        surgery(adm, dayA, "V81-OR-X", "09:30", "10:30", "10:40", "11:30", "11:40", "ELECTIVE");
        Long y = surgery(adm, dayA, "V81-OR-Y", "08:00", "09:00", "09:10", "10:00", "10:10", "ELECTIVE");
        Long z = surgery(adm, dayA, "V81-OR-Z", "08:30", "08:05", "08:15", "09:00", "09:10", "ELECTIVE");
        Long w = surgery(adm, dayA, "V81-OR-W", "08:00", "08:35", "08:45", "09:30", "09:40", "EMERGENCY");
        return Map.of("x", x, "y", y, "z", z, "w", w);
    }

    @Test
    void delayedVolumeCountsOnlyDelayedFirstCases() {
        seed1424();
        var sum = summary("1424", dayA);
        assertEquals(4, num(sum, "first_cases"));
        assertEquals(2, num(sum, "delayed"), "OR-Y 与 OR-W 延迟");
        assertEquals(115.0, dbl(sum, "delayed_minutes_total"), 0.001,
                "延迟开台量只算判为延迟的首台：70 + 45；准点的 20 分钟、提前的 −15 分钟都不进");
        assertEquals(57.5, dbl(sum, "avg_delay_minutes"), 0.001, "均值分母是延迟台数 2，不是首台数 4");
        assertEquals(70.0, dbl(sum, "elective_delayed_minutes_total"), 0.001, "国标口径只算择期：OR-Y 的 70");
        assertEquals(70.0, dbl(sum, "elective_avg_delay_minutes"), 0.001);

        var day = rows("1424", dayA);
        assertEquals(1, day.size());
        assertEquals(115.0, dbl(day.get(0), "delayed_minutes_total"), 0.001, "日表与 summary 同口径");
        assertEquals(57.5, dbl(day.get(0), "avg_delay_minutes"), 0.001);
        assertEquals(70.0, dbl(day.get(0), "elective_delayed_minutes_total"), 0.001);
        assertEquals(70.0, dbl(day.get(0), "elective_avg_delay_minutes"), 0.001);
    }

    @Test
    void detailDelayMinutesOnlyOnDelayedCasesAndSignedOffsetSeparately() {
        var ids = seed1424();
        var items = items("1424", dayA);
        assertEquals(4, items.size());

        var x = bySurgery(items, ids.get("x"));
        assertEquals("准点", x.get("judgement"));
        assertNull(x.get("delay_minutes"), "【口径修正 v81】准点台次的延迟分钟为空——旧口径这里是 +20，加总会把准点算成延迟量");
        assertEquals(20.0, dbl(x, "start_offset_minutes"), 0.001, "有符号偏移另列：实开 − 排台");
        assertEquals(Boolean.FALSE, x.get("delayed"));
        assertEquals(Boolean.TRUE, x.get("on_time"));

        var z = bySurgery(items, ids.get("z"));
        assertEquals("准点", z.get("judgement"));
        assertNull(z.get("delay_minutes"), "提前开台不是负的延迟量");
        assertEquals(-15.0, dbl(z, "start_offset_minutes"), 0.001);
        assertEquals(Boolean.FALSE, z.get("delayed"));
        assertEquals(Boolean.TRUE, z.get("on_time"));

        var y = bySurgery(items, ids.get("y"));
        assertEquals("延迟", y.get("judgement"));
        assertEquals(70.0, dbl(y, "delay_minutes"), 0.001);
        assertEquals(70.0, dbl(y, "start_offset_minutes"), 0.001);
        assertEquals(Boolean.TRUE, y.get("delayed"));
        assertEquals(Boolean.FALSE, y.get("on_time"));

        // 汇总 == 明细：延迟量、延迟台数、择期延迟量三项逐台加总
        var sum = summary("1424", dayA);
        assertEquals(dbl(sum, "delayed_minutes_total"), sumOf(items, "delay_minutes"), 0.001,
                "汇总延迟量必须等于明细 delay_minutes 逐台加总");
        assertEquals(num(sum, "delayed"), items.stream().filter(i -> Boolean.TRUE.equals(i.get("delayed"))).count());
        assertEquals(num(sum, "on_time"), items.stream().filter(i -> Boolean.TRUE.equals(i.get("on_time"))).count());
        assertEquals(dbl(sum, "elective_delayed_minutes_total"),
                sumOf(items.stream().filter(i -> "ELECTIVE".equals(i.get("surgery_kind"))).toList(), "delay_minutes"),
                0.001);
    }

    /** 延迟判定与汇总同一阈值：阈值收到 10 分钟，OR-X 的 20 分钟也成延迟量，两边同步变 */
    @Test
    void delayJudgementFollowsTheConfiguredThresholdOnBothSides() {
        var ids = seed1424();
        jdbc.update("""
                insert into sys_config(cfg_key, cfg_value, remark) values ('anes.qc.ontime_minutes', '10', 'v81 test')
                on conflict (cfg_key) do update set cfg_value = excluded.cfg_value
                """);
        configReader.evict("anes.qc.ontime_minutes");
        try {
            var sum = summary("1424", dayA);
            assertEquals(3, num(sum, "delayed"));
            assertEquals(135.0, dbl(sum, "delayed_minutes_total"), 0.001, "20 + 70 + 45");
            assertEquals(45.0, dbl(sum, "avg_delay_minutes"), 0.001);
            var items = items("1424", dayA);
            assertEquals(20.0, dbl(bySurgery(items, ids.get("x")), "delay_minutes"), 0.001);
            assertEquals(dbl(sum, "delayed_minutes_total"), sumOf(items, "delay_minutes"), 0.001);
        } finally {
            jdbc.update("delete from sys_config where cfg_key = 'anes.qc.ontime_minutes'");
            configReader.evict("anes.qc.ontime_minutes");
        }
    }

    /** 无延迟首台时：延迟量 0、均值为空（分母 0 不凑数） */
    @Test
    void noDelayedFirstCaseGivesZeroVolumeAndNullAverage() {
        Long adm = newAdmission();
        surgery(adm, dayA, "V81-OR-X", "08:00", "08:10", "08:20", "09:00", "09:10", "ELECTIVE");
        var sum = summary("1424", dayA);
        assertEquals(0.0, dbl(sum, "delayed_minutes_total"), 0.001);
        assertNull(sum.get("avg_delay_minutes"));
        assertNull(sum.get("elective_avg_delay_minutes"));
    }

    // ================= 1425★ 入室时间与接台对账 =================

    /**
     * 手术间 OR-T 同日五台：08:00–09:00、09:20–10:30、10:45–12:00、12:10–12:50，
     * 以及一台入室 12:30（早于上一台 12:50 出室，重叠/录错）。lag 口径：前三对接台 20 + 15 + 10，
     * 重叠那台不计；旧明细的 max(out_room_at) 横连会把它接到 12:00 那台上、多出一对 30 分钟。
     * 另一间 OR-U 一台未录入室时间（只录开台），进台数、不进入室计数。
     */
    @Test
    void turnoverDetailReconcilesWithSummaryPairByPair() {
        Long adm = newAdmission();
        Long s1 = surgery(adm, dayB, "V81-OR-T", "08:00", "08:00", "08:10", "08:50", "09:00", "ELECTIVE");
        Long s2 = surgery(adm, dayB, "V81-OR-T", "09:20", "09:20", "09:30", "10:20", "10:30", "ELECTIVE");
        Long s3 = surgery(adm, dayB, "V81-OR-T", "10:45", "10:45", "10:55", "11:50", "12:00", "ELECTIVE");
        Long s4 = surgery(adm, dayB, "V81-OR-T", "12:10", "12:10", "12:15", "12:45", "12:50", "ELECTIVE");
        Long s5 = surgery(adm, dayB, "V81-OR-T", "12:30", "12:30", "12:55", "13:20", "13:30", "EMERGENCY");
        surgery(adm, dayB, "V81-OR-U", "07:30", null, "07:40", "08:30", null, "ELECTIVE");

        var r = rows("1425", dayB);
        assertEquals(1, r.size());
        var row = r.get(0);
        assertEquals(6, num(row, "cases"));
        assertEquals(3, num(row, "turnovers"), "重叠那台不计接台");
        assertEquals(45.0, dbl(row, "total_turnover_minutes"), 0.001);
        assertEquals(15.0, dbl(row, "avg_turnover_minutes"), 0.001);

        var items = items("1425", dayB);
        assertEquals(6, items.size());
        long pairs = items.stream().filter(i -> i.get("turnover_minutes") != null).count();
        assertEquals(num(row, "turnovers"), pairs, "明细接台对数必须等于汇总 turnovers");
        assertEquals(dbl(row, "total_turnover_minutes"), sumOf(items, "turnover_minutes"), 0.001,
                "明细接台分钟逐对加总必须等于汇总 total_turnover_minutes");

        assertNull(bySurgery(items, s1).get("turnover_minutes"), "当日首台无上一台");
        assertEquals(20.0, dbl(bySurgery(items, s2), "turnover_minutes"), 0.001);
        assertEquals(15.0, dbl(bySurgery(items, s3), "turnover_minutes"), 0.001);
        assertEquals(10.0, dbl(bySurgery(items, s4), "turnover_minutes"), 0.001);
        assertNull(bySurgery(items, s5).get("turnover_minutes"),
                "入室早于上一台出室（重叠/录错）不计接台——旧明细横连取 max(out_room_at) 会接到 12:00 那台上算出 30 分钟");
    }

    // ================= 1426★ 取消手术按取消日归集 =================

    /**
     * V146 补 {@code cancelled_at} 就是为了让 1426★ 按「哪一天取消的」统计，而控制器此前仍挂全局手术锚点
     * （取消的手术四时间点全空，锚点退化为排台日）：第 1 天排台、第 20 天才取消的手术落在第 1 天。
     * 修正后窗口 = coalesce(cancelled_at, 原锚点)：录了取消时刻的按取消日，历史行（V146 前取消、无取消时刻）回落原锚点。
     */
    @Test
    void cancellationIsBucketedByCancelDayWithAnchorFallback() {
        String day1 = jdbc.queryForObject("select (current_date - 290)::text", String.class);
        String day20 = jdbc.queryForObject("select (current_date - 271)::text", String.class);
        Long clash = jdbc.queryForObject("""
                select count(*) from inp_surgery s
                where (coalesce(s.cancelled_at, s.start_at, s.in_room_at, s.scheduled_at, s.created_at) >= ?::date
                       and coalesce(s.cancelled_at, s.start_at, s.in_room_at, s.scheduled_at, s.created_at) < ?::date + 1)
                   or (coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) >= ?::date
                       and coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) < ?::date + 1)
                """, Long.class, day1, day20, day1, day20);
        assertEquals(0L, clash, "夹具窗口内不得有其它手术");

        Long adm = newAdmission();
        Long late = jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, status, scheduled_at, cancel_stage,
                                        cancel_reason, cancelled_at)
                values (?, 'V81第20天才取消', 'CANCELLED', ?::timestamptz, 'SCHEDULE', '患者转院', ?::timestamptz)
                returning id
                """, Long.class, adm, at(day1, "08:00"), at(day20, "16:00"));
        Long legacy = jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, status, scheduled_at, cancel_stage)
                values (?, 'V81历史取消（无取消时刻）', 'CANCELLED', ?::timestamptz, 'PRE_IN')
                returning id
                """, Long.class, adm, at(day1, "09:00"));

        var onDay20 = rows("1426", day20);
        assertEquals(1, onDay20.stream().mapToLong(r -> num(r, "cases")).sum(),
                "第 1 天排台、第 20 天取消的手术必须算进第 20 天");
        assertEquals("SCHEDULE", onDay20.get(0).get("cancel_stage"));
        var onDay1 = rows("1426", day1);
        assertEquals(1, onDay1.stream().mapToLong(r -> num(r, "cases")).sum(),
                "第 1 天只剩那台无取消时刻的历史取消（回落原锚点 = 排台日），不得再含第 20 天才取消的那台");
        assertEquals("PRE_IN", onDay1.get(0).get("cancel_stage"));

        var d20 = items("1426", day20);
        assertEquals(List.of(late), d20.stream().map(i -> num(i, "surgery_id")).toList(), "明细与汇总同窗");
        var d1 = items("1426", day1);
        assertEquals(List.of(legacy), d1.stream().map(i -> num(i, "surgery_id")).toList());
        // 分桶穿透同窗：bucketSummaryCount == items
        var b = anesQc.detail("1426", day20, day20, null, null, "SCHEDULE");
        assertEquals(0, b.getCode(), b.getMessage());
        assertEquals(1, ((Number) b.getData().get("bucketSummaryCount")).intValue());
    }

    @Test
    void turnoverSummaryGivesFirstInRoomTimeAndRecordedCount() {
        Long adm = newAdmission();
        surgery(adm, dayB, "V81-OR-T", "08:00", "08:05", "08:10", "08:50", "09:00", "ELECTIVE");
        surgery(adm, dayB, "V81-OR-T", "09:20", "09:20", "09:30", "10:20", "10:30", "ELECTIVE");
        surgery(adm, dayB, "V81-OR-V", "07:30", "07:50", "08:00", "09:00", "09:10", "ELECTIVE");
        surgery(adm, dayB, "V81-OR-U", "07:00", null, "07:40", "08:30", null, "ELECTIVE");

        var row = rows("1425", dayB).get(0);
        assertEquals(4, num(row, "cases"));
        assertEquals(3, num(row, "in_room_recorded"), "OR-U 那台没录入室时间");
        Object expect = jdbc.queryForObject("select ?::timestamptz", Object.class, at(dayB, "07:50"));
        assertEquals(expect, row.get("first_in_room_at"), "当日最早入室时刻是 OR-V 的 07:50，不是排台更早的 OR-U");
    }
}
