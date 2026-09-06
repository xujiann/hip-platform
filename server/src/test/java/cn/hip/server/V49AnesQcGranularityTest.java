package cn.hip.server;

import cn.hip.medtech.web.AnesQcController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v49 车道Q1 回归：麻醉质控两处<b>粒度</b>缺陷的修正（1429★ 死亡率 / 1433★1434★ 穿透明细）。
 *
 * <p>本类不测「指标算得出来」——那是 {@code V46AnesQcTest} 的事。本类只钉死一件事：
 * <b>汇总的哪个数 == 明细的哪个计数</b>。两处缺陷都不是「算不出来」，而是「算得出但对不上账」，
 * 后者更危险：数字照常显示、报表照常导出，没有任何报错，只有拿去对账的人才会发现差了一截。
 *
 * <ul>
 *   <li><b>1429★ 修正前是台次口径</b>：{@code from inp_surgery} 一行一台，
 *       一名患者一次住院做 3 台手术后死亡，deaths 记 <b>3</b>、分母也记 3 台；
 *       而《麻醉专业医疗质量控制指标（2022 年版）》「各 ASA 分级患者麻醉死亡率」的分子分母都是<b>患者</b>。
 *       {@link #deathRateIsPerPatientNotPerSurgery()} 造的就是这台「3 台手术 1 名死者」的夹具：
 *       修正前 deaths=3 / 死亡率 75%，修正后 deaths=1 / 死亡率 50%，差了整整一倍。
 *   <li><b>1433★/1434★ 修正前汇总与明细不同粒度</b>：汇总按患者去重出人数，
 *       穿透明细却一行一条输血记录。汇总说「400–1000ml 1 人」而明细列出 2 条记录，
 *       按条数根本对不上账——而穿透明细的存在意义正是防这个。
 * </ul>
 *
 * <p><b>夹具日取 210 天前</b>（V46AnesQcTest 用 200 天前）：两套用例的夹具必须互不干扰，
 * 否则「各档人数之和 == 明细行数」这类全窗口断言会被对方的行搅乱，测出来的是环境不是代码。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "QUALITY"})
class V49AnesQcGranularityTest {

    @Autowired AnesQcController anesQc;
    @Autowired JdbcTemplate jdbc;

    /** 夹具日：距今 210 天，避开库里既有手术与 V46AnesQcTest 的 200 天前 */
    private String day;

    @BeforeEach
    void setUp() {
        day = jdbc.queryForObject("select (current_date - 210)::text", String.class);
    }

    // ================= 夹具 =================

    private static long seq = 0;

    private static String uniq(String prefix) {
        return prefix + (System.nanoTime() % 100000000L) + (seq++);
    }

    private Long newPatient() {
        return jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'M', current_date - make_interval(years => 60))
                returning id
                """, Long.class, uniq("V49P"), "粒度" + uniq(""));
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

    /**
     * 一台手术。时间刻意只用 09:00–14:00 这一段：夹具日由库里的 current_date 取，
     * 时间字面量贴近午夜会在 UTC/UTC+8 下落到不同的自然日，测出来的是时区不是代码
     * （本仓已因时区炸过四次，见 CI 必跑 -Duser.timezone=UTC）。
     */
    private Long surgery(Long admissionId, String hh, String asa) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status,
                                        scheduled_at, room_no, in_room_at, start_at, end_at, out_room_at,
                                        surgery_level, asa_grade, surgery_kind)
                values (?, 'V49术式', '全身麻醉', 'DONE', ?::timestamptz, 'V49-OR-1',
                        ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz,
                        '三级', ?, 'ELECTIVE')
                returning id
                """, Long.class, admissionId,
                ts(hh + ":00"), ts(hh + ":02"), ts(hh + ":05"), ts(hh + ":40"), ts(hh + ":50"), asa);
    }

    /** 历史形态：ASA 等新字段全空，刻意不回填 */
    private Long legacySurgery(Long admissionId, String hhmm) {
        return jdbc.queryForObject("""
                insert into inp_surgery(admission_id, procedure_name, anesthesia_type, status, scheduled_at)
                values (?, 'V49历史手术', '全身麻醉', 'DONE', ?::timestamptz)
                returning id
                """, Long.class, admissionId, ts(hhmm));
    }

    private void deathCard(Long patientId, Long admissionId) {
        jdbc.update("""
                insert into mr_death_card(patient_id, admission_id, died_at, direct_cause)
                values (?, ?, now(), 'V49测试死因')
                """, patientId, admissionId);
    }

    private void transfusion(Long surgeryId, String product, int ml, boolean isAuto) {
        jdbc.update("""
                insert into surg_transfusion(surgery_id, product_type, volume_ml, is_auto, transfused_at)
                values (?, ?, ?, ?, ?::timestamptz)
                """, surgeryId, product, ml, isAuto, ts("11:00"));
    }

    private String ts(String hhmm) {
        return day + " " + hhmm;
    }

    // ================= 断言小工具 =================

    @SuppressWarnings("unchecked")
    private Map<String, Object> one(String indicator) {
        var r = anesQc.indicators(day, day, indicator);
        assertEquals(0, r.getCode(), r.getMessage());
        var list = (List<Map<String, Object>>) r.getData().get("indicators");
        assertEquals(1, list.size(), "按编码筛应只回一条");
        return list.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String indicator) {
        return (List<Map<String, Object>>) one(indicator).get("rows");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> detailItems(String indicator) {
        var r = anesQc.detail(indicator, day, day, null);
        assertEquals(0, r.getCode(), r.getMessage());
        assertEquals(Boolean.FALSE, r.getData().get("truncated"),
                "夹具很小，一旦截断说明夹具日撞了别的数据，对账断言就不作数了");
        return (List<Map<String, Object>>) r.getData().get("items");
    }

    private Map<String, Object> rowBy(String indicator, String key, String value) {
        return rows(indicator).stream()
                .filter(m -> value.equals(m.get(key)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "指标 " + indicator + " 没有 " + key + "=" + value + " 这一行：" + rows(indicator)));
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static long sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToLong(m -> num(m, key)).sum();
    }

    private static long countTrue(List<Map<String, Object>> rows, String key) {
        return rows.stream().filter(m -> Boolean.TRUE.equals(m.get(key))).count();
    }

    // ================= 1429★ 死亡率：患者口径不是台次口径 =================

    /**
     * <b>这条就是缺陷本身</b>：一名患者一次住院做 3 台手术后死亡。
     * 修正前 deaths=3（台次口径把一名死者记成 3 例）、分母 4 台、死亡率 75%；
     * 修正后 deaths=1、分母 2 人次、死亡率 50%。
     */
    @Test
    void deathRateIsPerPatientNotPerSurgery() {
        Long pid = newPatient();
        Long adm = newAdmission(pid);
        surgery(adm, "09", "IV");
        surgery(adm, "11", "IV");
        surgery(adm, "13", "IV");
        deathCard(pid, adm);

        Long pid2 = newPatient();
        Long adm2 = newAdmission(pid2);
        surgery(adm2, "10", "IV");   // 活着出院的对照组

        // 反向对照：先用**修正前那条 SQL 的原样**跑一遍，证明这套夹具确实踩得中缺陷。
        // 少了这一步，"测试跑绿"只能说明新代码自洽，说明不了旧代码错在哪、错多少。
        Long oldStyleDeaths = jdbc.queryForObject("""
                select count(*) filter (where dc.admission_id is not null)
                from inp_surgery s
                left join (select distinct admission_id from mr_death_card
                           where admission_id is not null) dc on dc.admission_id = s.admission_id
                where coalesce(s.status, '') <> 'CANCELLED' and s.cancel_stage is null
                  and s.asa_grade = 'IV'
                  and coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) >= ?::date
                  and coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) <  ?::date + 1
                """, Long.class, day, day);
        assertEquals(3, oldStyleDeaths,
                "夹具须真能踩中缺陷：台次口径下这里是 3（一名死者被三台手术各记一次）");

        var iv = rowBy("1429", "asa_grade", "IV");
        assertEquals(4, num(iv, "cases"), "cases 仍是台次口径，既有键含义不动");
        assertEquals(2, num(iv, "patients"), "麻醉患者数按 admission 去重：3 台手术是同一人次");
        assertEquals(1, num(iv, "deaths"),
                "★缺陷本体：修正前一名死者被记成 3 例死亡（台次口径），患者口径只能是 1");
        assertEquals(50.0, ((Number) iv.get("death_rate_pct")).doubleValue(), 0.01,
                "1/2 人次 = 50%；台次口径会算成 3/4 = 75%");

        // 口径变更必须写进返回体，不能只改代码不告诉看数的人
        String note = (String) one("1429").get("note");
        assertTrue(note.contains("口径已修正"), "口径变更须在 note 里明写");
        assertTrue(note.contains("台次"), "须写清修正前是什么口径");

        // 明细对账：台次粒度行数 == Σcases；代表行数 == Σpatients；代表行里死了的 == Σdeaths
        var items = detailItems("1429");
        assertEquals(sum(rows("1429"), "cases"), items.size(), "明细行数 == 各行 cases 之和（台次）");
        assertEquals(sum(rows("1429"), "patients"), countTrue(items, "counts_as_patient"),
                "counts_as_patient 为真的行数 == 各行 patients 之和（人次）");
        assertEquals(sum(rows("1429"), "deaths"),
                items.stream().filter(m -> Boolean.TRUE.equals(m.get("counts_as_patient"))
                                        && Boolean.TRUE.equals(m.get("died"))).count(),
                "代表行里 died 为真的行数 == 各行 deaths 之和");
    }

    /**
     * 一次住院里两台手术 ASA 分级不同时，人次归属到<b>最重</b>的一级。
     * 若按每台各归各级，同一名患者会在 II 和 IV 两行里各记一次人数、死亡也各记一次，
     * 各级 patients 之和就大于实际人次数——那是把台次口径的毛病换个地方犯一遍。
     */
    @Test
    void mixedAsaWithinOneAdmissionAttributedToWorstGradeOnly() {
        Long pid = newPatient();
        Long adm = newAdmission(pid);
        surgery(adm, "09", "II");
        surgery(adm, "13", "IV");
        deathCard(pid, adm);

        var ii = rowBy("1429", "asa_grade", "II");
        var iv = rowBy("1429", "asa_grade", "IV");
        assertEquals(1, num(ii, "cases"), "台次照各归各级，II 那台不消失");
        assertEquals(1, num(iv, "cases"));
        assertEquals(0, num(ii, "patients"), "人次只归最重一级，不在 II 里再记一次");
        assertEquals(0, num(ii, "deaths"), "死亡更不能在两个分级里各记一次");
        assertNull(ii.get("death_rate_pct"), "分母为 0 时给 null，不给一个看着像真的 0.00");
        assertEquals(1, num(iv, "patients"));
        assertEquals(1, num(iv, "deaths"));

        assertEquals(1, sum(rows("1429"), "patients"), "各级 patients 之和 == 该时段麻醉人次数");
        assertEquals(1, sum(rows("1429"), "deaths"), "各级 deaths 之和 == 死亡人次数");

        var items = detailItems("1429");
        assertEquals(2, items.size());
        assertEquals(1, countTrue(items, "counts_as_patient"), "一次住院只有一行代表行");
        items.forEach(m -> assertEquals("IV", m.get("attributed_asa_grade"),
                "同一次住院的每一行都标同一个归属分级，便于人工核对"));
    }

    /** ASA 全空的一次住院归「（未填写）」，不猜成任何一个实级——猜一级就是往那个分母里塞人 */
    @Test
    void admissionWithNoAsaAtAllGoesToUnfilledBucket() {
        Long pid = newPatient();
        Long adm = newAdmission(pid);
        legacySurgery(adm, "09:00");
        legacySurgery(adm, "13:00");

        var unfilled = rowBy("1429", "asa_grade", "（未填写）");
        assertEquals(2, num(unfilled, "cases"));
        assertEquals(1, num(unfilled, "patients"), "两台历史手术是同一人次");
        assertEquals(0, num(unfilled, "deaths"));
        assertEquals(1, rows("1429").size(), "不许因为分级空就凭空造出一个实级行");
    }

    /** 一次住院里「有一台录了分级、另一台没录」——归属到录了的那级，未填不参与竞争 */
    @Test
    void partiallyGradedAdmissionAttributedToTheGradedOne() {
        Long pid = newPatient();
        Long adm = newAdmission(pid);
        legacySurgery(adm, "09:00");
        surgery(adm, "13", "III");

        assertEquals(0, num(rowBy("1429", "asa_grade", "（未填写）"), "patients"));
        assertEquals(1, num(rowBy("1429", "asa_grade", "III"), "patients"));
        assertEquals(1, sum(rows("1429"), "patients"));
    }

    // ================= 1433★/1434★ 穿透明细：患者粒度 =================

    /**
     * <b>这条就是缺陷本身</b>：1433★ 汇总说「1 人」，修正前明细列出 2 条输血记录，
     * 按条数对不上账。修正后一行一名患者，<b>Σpatients == 明细行数</b>。
     */
    @Test
    void autoTransfusionBandDetailIsPatientGrained() {
        // P1：一次住院两台手术，自体血 600 + 300 = 900ml（跨台合并）→ 400–1000ml 档
        Long p1 = newPatient();
        Long a1 = newAdmission(p1);
        Long s1 = surgery(a1, "09", "III");
        Long s2 = surgery(a1, "13", "III");
        transfusion(s1, "AUTO", 600, true);
        // 自体洗涤红细胞：product_type='RBC' 而 is_auto=true，字符串比对会整片漏掉
        transfusion(s2, "RBC", 300, true);
        transfusion(s1, "RBC", 400, false);   // 非自体，1433★ 不该看见它

        // P2：自体血 200ml → 400ml 以下档
        Long p2 = newPatient();
        Long a2 = newAdmission(p2);
        Long s3 = surgery(a2, "10", "II");
        transfusion(s3, "AUTO", 200, true);

        // P3：只输非自体——1433★ 的明细里必须一行都没有
        Long p3 = newPatient();
        Long a3 = newAdmission(p3);
        Long s4 = surgery(a3, "11", "II");
        transfusion(s4, "PLASMA", 500, false);

        var bands = rows("1433");
        assertEquals(2, bands.size());
        assertEquals(2, sum(bands, "patients"));
        assertEquals(1, num(rowBy("1433", "band", "400–1000ml"), "patients"));
        assertEquals(900, num(rowBy("1433", "band", "400–1000ml"), "total_ml"),
                "自体血只认 is_auto：自体洗涤红细胞（product_type='RBC'）也必须计入");

        // 反向对照：修正前明细走的是记录粒度，同一时间窗下是 3 条自体血记录对 2 名患者。
        Long oldStyleDetailRows = jdbc.queryForObject("""
                select count(*)
                from surg_transfusion t
                join inp_surgery s on s.id = t.surgery_id
                where coalesce(t.is_auto, false)
                  and coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) >= ?::date
                  and coalesce(s.start_at, s.in_room_at, s.scheduled_at, s.created_at) <  ?::date + 1
                """, Long.class, day, day);
        assertEquals(3, oldStyleDetailRows,
                "夹具须真能踩中缺陷：记录粒度下明细是 3 行，而汇总说 2 人");

        // ★ 验收标准：汇总各档 patients 之和 == 该指标穿透明细行数
        var items = detailItems("1433");
        assertEquals(sum(bands, "patients"), items.size(),
                "★修正前这里是 3（三条自体血记录）对 2（两名患者），按条数根本对不上账");

        var big = items.stream().filter(m -> num(m, "total_ml") == 900).findFirst().orElseThrow();
        assertEquals("400–1000ml", big.get("band"), "明细的分档必须与汇总同一套分档表达式");
        assertEquals(2, num(big, "surgeries"), "涉及台次数要给出来，否则患者粒度看不出跨了几台");
        assertEquals(2, num(big, "transfusion_records"), "记录条数也给，人工要能倒推回记录明细");
        assertNotNull(big.get("admission_no"), "住院号是核对到人的钥匙，不能省");
        assertNotNull(big.get("patient_name"));
        items.forEach(m -> assertNotEquals(p3, m.get("patient_id"),
                "只输非自体血的患者不得出现在 1433★ 的明细里"));

        String note = (String) one("1433").get("note");
        assertTrue(note.contains("口径已修正"), "明细粒度变更须写进 note");
        assertTrue(note.contains("患者粒度"));
    }

    /** 1434★ 的四个患者数与台次合计，逐项都能在患者粒度明细上机械核对 */
    @Test
    void transfusedPatientCountsReconcileWithPatientDetail() {
        // P1：自体 + 非自体都有（both），两台手术
        Long p1 = newPatient();
        Long a1 = newAdmission(p1);
        Long s1 = surgery(a1, "09", "III");
        Long s2 = surgery(a1, "13", "III");
        transfusion(s1, "AUTO", 600, true);
        transfusion(s2, "RBC", 400, false);
        // P2：只自体
        Long a2 = newAdmission(newPatient());
        transfusion(surgery(a2, "10", "II"), "RBC", 300, true);
        // P3：只非自体
        Long a3 = newAdmission(newPatient());
        transfusion(surgery(a3, "11", "II"), "PLASMA", 500, false);

        var counts = rows("1434").get(0);
        var items = detailItems("1434");

        assertEquals(num(counts, "transfused_patients"), items.size(),
                "★修正前这里是 4 条输血记录对 3 名患者");
        assertEquals(3, items.size());
        assertEquals(num(counts, "auto_patients"), countTrue(items, "has_auto"));
        assertEquals(num(counts, "non_auto_patients"), countTrue(items, "has_non_auto"));
        assertEquals(num(counts, "both_patients"),
                items.stream().filter(m -> Boolean.TRUE.equals(m.get("has_auto"))
                                        && Boolean.TRUE.equals(m.get("has_non_auto"))).count(),
                "重叠人数也要能在明细上数出来");
        assertEquals(num(counts, "transfused_surgeries"), sum(items, "surgeries"),
                "各行涉及台次之和 == 汇总的输血台次数");
        assertEquals(2, num(counts, "auto_patients"), "只自体 1 人 + 两类均有 1 人");

        var both = items.stream().filter(m -> num(m, "total_ml") == 1000).findFirst().orElseThrow();
        assertEquals(600, num(both, "auto_ml"));
        assertEquals(400, num(both, "non_auto_ml"));
        assertEquals(2, num(both, "surgeries"));
    }

    /**
     * 1432★ 本就是记录 / 制品粒度的指标，<b>明细必须仍走记录粒度</b>——
     * 顺手把它也改成患者粒度，就会从「修好一处」变成「弄坏另一处」。
     */
    @Test
    void productLevelIndicatorKeepsRecordGrainedDetail() {
        Long a1 = newAdmission(newPatient());
        Long s1 = surgery(a1, "09", "III");
        transfusion(s1, "AUTO", 600, true);
        transfusion(s1, "RBC", 400, false);
        transfusion(s1, "RBC", 300, true);

        assertEquals(sum(rows("1432"), "records"), detailItems("1432").size(),
                "1432★ 汇总的 records 之和 == 记录粒度明细的行数");
        assertEquals(3, detailItems("1432").size(), "同一名患者的三条记录就是三行");
        assertNotNull(detailItems("1432").get(0).get("transfusion_id"),
                "记录粒度明细必须给得出记录 ID，否则倒查不到原始记录");
    }

    // ================= 只读证据（本车道一行没写，别的车道也别指望这里能写） =================

    /** 改口径不等于改数据：新老明细各跑一遍，两张源表行数一字不变 */
    @Test
    void granularityFixStaysReadOnly() {
        Long pid = newPatient();
        Long adm = newAdmission(pid);
        Long s = surgery(adm, "09", "IV");
        transfusion(s, "AUTO", 600, true);
        deathCard(pid, adm);

        long surgeriesBefore = jdbc.queryForObject("select count(*) from inp_surgery", Long.class);
        long txBefore = jdbc.queryForObject("select count(*) from surg_transfusion", Long.class);
        long deathBefore = jdbc.queryForObject("select count(*) from mr_death_card", Long.class);

        for (String code : new String[]{"1429", "1432", "1433", "1434"}) {
            assertEquals(0, anesQc.indicators(day, day, code).getCode());
            assertEquals(0, anesQc.detail(code, day, day, null).getCode());
            assertNotNull(anesQc.indicatorsCsv(code, day, day));
            assertNotNull(anesQc.detailCsv(code, day, day));
        }

        assertEquals(surgeriesBefore, jdbc.queryForObject("select count(*) from inp_surgery", Long.class));
        assertEquals(txBefore, jdbc.queryForObject("select count(*) from surg_transfusion", Long.class));
        assertEquals(deathBefore, jdbc.queryForObject("select count(*) from mr_death_card", Long.class));
    }
}
