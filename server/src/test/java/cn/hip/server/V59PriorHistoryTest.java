package cn.hip.server;

import cn.hip.medtech.web.PathologyRegistryController;
import cn.hip.medtech.web.PathologyRegistryController.RejectReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.medtech.web.PathologyReportController.SupplementReq;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.service.ConfigReader;
import org.junit.jupiter.api.AfterEach;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * v59 车道 B（2558）：<b>既往病理对比 + 登记页既往口径</b>的判据测试。
 *
 * <h2>反驳者原话（三票全推翻，主控实测坐实）</h2>
 * <ul>
 *   <li>「既往页签只画 {@code path_specimen.diagnosis} 一列，后端 /prior 已返回的
 *       gross_finding / micro_finding / clinical_diagnosis 被前端整行丢弃，补充报告只给份数不给内容；
 *       既往行无任何点击入口；抽屉单实例，要看既往全文必须关抽屉→改范围→手抄病理号再搜，
 *       两份报告任何时刻不能同屏，『对比』根本不存在」。</li>
 *   <li>「登记页既往抽屉 history() 用 SPECIMEN_SELECT 只加 patient_id=? and s.id&lt;&gt;?，不过滤 rejected_at；
 *       一份因未固定被拒收、从未受检的标本在医师眼里就是一条既往病理，同名统计也把拒收行算进份数；
 *       同一患者在 ① 与 ④ 看到的既往条数不一致」。</li>
 *   <li>「/prior 与 /history 两端点全仓零自动化测试、零 E2E」——本类就是那个零。</li>
 * </ul>
 *
 * <h2>钉住的反向事实（修复前为真、修复后为假）</h2>
 * <ol>
 *   <li>{@link #priorCarriesFullTextAndSupplementsAndExcludesRejectedAndOthers()}：修复前 /prior 行<b>没有
 *       {@code status} 与 {@code supplements} 两键</b>（补充报告只有 {@code supplement_count} 份数）。
 *       gross_finding / micro_finding / clinical_diagnosis 三列在 select 里本就有（反驳者说的正是「后端已返回、
 *       前端丢弃」），本类一并钉住它们不许再掉；口径：同患者的 A3（已拒收）与未诊断的 A2 不算既往，
 *       他患者 B1 与<b>同名他人</b> C1 不许混进来。</li>
 *   <li>{@link #historyExcludesRejectedByDefaultAndIncludesOnDemand()}：修复前 /history 默认<b>含 A3</b>；
 *       同名他人 C 的 {@code specimen_count} 把拒收的 C2 算进去是 <b>2</b>（修复后 1，拒收另给 rejected_count）；
 *       只有拒收标本的同名 D 修复前也在同名块里。</li>
 *   <li>{@link #frontendPriorTabShowsFullTextAndCompareAndRegistryHasIncludeRejected()}：源码扫描——
 *       剥注释后 DiagnosisPanel.vue 含 {@code micro_finding} / {@code gross_finding} / {@code >对比</el-button>} /
 *       {@code append-to-body}；对照组 RegistryPanel.vue 含 {@code includeRejected}。剥注释器带活的对照：
 *       两份文件里「工位四 / 工位一」只出现在注释里，剥完必须不见。</li>
 * </ol>
 *
 * <h2>夹具</h2>
 * 同一患者 A 三份标本：A1 已签发 + 1 份补充报告、A2 当前（已核收未诊断）、A3 已拒收（走真实拒收端点，不删行不改 status）；
 * 他患者 B 一份已签发 B1；<b>同名他人</b> C（与 A 同名、不同 id）一份在办 C1 + 一份已拒收 C2；同名 D 只有一份已拒收 D1。
 * <b>不写任何时间字面量</b>：全部时刻由 SQL 按 {@code now()} 现算，时刻断言在库端用 now() 算容差
 * （抄 V58GrossFieldsTest.assertRecent）；setup 抄 V55PathologyReachTest。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V59PriorHistoryTest {

    private static final String DIAGNOSIS_PANEL = "frontend/shell/src/views/medtech/pathology/DiagnosisPanel.vue";
    private static final String REGISTRY_PANEL = "frontend/shell/src/views/medtech/pathology/RegistryPanel.vue";

    @Autowired PathologyReportController report;
    @Autowired PathologyRegistryController registry;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc1;

    private long a1;   // 患者 A：已签发 + 1 份补充报告
    private long a2;   // 患者 A：当前标本，已核收未诊断
    private long a3;   // 患者 A：已拒收（从未受检）
    private long b1;   // 他患者 B：已签发
    private long c1;   // 同名他人 C：在办
    private long c2;   // 同名他人 C：已拒收
    private long d1;   // 同名他人 D：只有这一份，已拒收
    private long patientC;
    private long patientD;

    @BeforeEach
    void setUp() {
        configReader.evictAll();
        // 双签 gate 钉在 warn：A1 / B1 只初签即签发；事务回滚，库里不留痕
        jdbc.update("update sys_config set cfg_value = 'warn' where cfg_key = ?",
                PathologyReportController.DOUBLE_SIGN_GATE_KEY);
        configReader.evictAll();

        tag = "V59" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "既往对比测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        String sameName = "既往患者" + tag;
        long patientA = patient(tag + "PA", sameName);
        long patientB = patient(tag + "PB", "他患者" + tag);
        patientC = patient(tag + "PC", sameName);
        patientD = patient(tag + "PD", sameName);
        long regA = registration(patientA, scheduleId, deptId, 1);
        long regB = registration(patientB, scheduleId, deptId, 2);
        long regC = registration(patientC, scheduleId, deptId, 3);
        long regD = registration(patientD, scheduleId, deptId, 4);

        a1 = specimen(regA, "A1");
        a2 = specimen(regA, "A2");
        a3 = specimen(regA, "A3");
        b1 = specimen(regB, "B1");
        c1 = specimen(regC, "C1");
        c2 = specimen(regC, "C2");
        d1 = specimen(regD, "D1");

        // A1 / B1：诊断走库（既有 diagnose 端点按 barcode、状态机在别处覆盖）→ 初签 → 缺复签即签发（gate=warn 放行）
        diagnoseAndIssue(a1, "A1");
        diagnoseAndIssue(b1, "B1");
        // A1 再出一份补充报告——既往行要带的是它的正文，不只是份数
        var sup = report.supplement(a1, new SupplementReq("免疫组化：CK7(+)、CK20(-) " + tag, "免疫组化回报 " + tag), doc1);
        assertEquals(0, sup.getCode(), sup.getMessage());
        assertEquals(1, ((Number) sup.getData().get("seqNo")).intValue());

        // A3 / C2 / D1：走真实拒收端点——不删行、不改 status，只写 reject_reason/rejected_at/rejected_by
        for (long id : new long[] {a3, c2, d1}) {
            var rj = registry.reject(id, new RejectReq("标本未固定 " + tag, null), doc1);
            assertEquals(0, rj.getCode(), rj.getMessage());
        }
        assertEquals("RECEIVED", jdbc.queryForObject("select status from path_specimen where id = ?", String.class, a3),
                "拒收不改 status——夹具得先证明这一点，下面「默认排除拒收」才不是在按 status 过滤");
    }

    @AfterEach
    void tearDown() {
        // 配置缓存是跨用例存活的单例，事务回滚不会把它一起回滚
        configReader.evictAll();
    }

    // =====================================================================================
    // ① /prior：既往行带全文 + 补充报告正文；口径 = 同患者、已写诊断、未拒收
    // =====================================================================================

    @Test
    void priorCarriesFullTextAndSupplementsAndExcludesRejectedAndOthers() {
        var body = ok(report.prior(a2, null));
        assertEquals(Boolean.TRUE, body.get("patientResolved"));
        Set<Long> ids = ids(body);
        assertTrue(ids.contains(a1), "已签发的 A1 是 A2 的既往：" + ids);
        assertFalse(ids.contains(a3), "已拒收的 A3 从未受检，不是既往病理（与登记页 history 同口径）");
        assertFalse(ids.contains(a2), "本标本自身不列");
        assertFalse(ids.contains(b1), "他患者 B1 不许混进来");
        assertFalse(ids.contains(c1) || ids.contains(c2), "同名他人 C 的标本不许混进来——患者同一性以 empi_patient.id 为准");

        Map<String, Object> row = find(body, a1);
        assertNotNull(row);
        // 修复前的反向事实：行里没有 status / supplements 两键（补充报告只给 supplement_count 份数）
        assertTrue(row.containsKey("status"), "既往行须带 status：" + row.keySet());
        assertTrue(row.containsKey("supplements"), "既往行须带 supplements 正文：" + row.keySet());
        assertEquals("DIAGNOSED", row.get("status"));
        // 三段正文与临床诊断：后端本就返回、前端此前整行丢弃——钉住不许再掉
        assertEquals("灰白组织一块 " + tag, row.get("gross_finding"));
        assertEquals("镜下见异型细胞 " + tag, row.get("micro_finding"));
        assertEquals("临床拟诊肿物 " + tag, row.get("clinical_diagnosis"));
        assertEquals("（左乳）浸润性导管癌 " + tag, row.get("diagnosis"));
        assertRecent(row.get("report_issued_at"));
        assertEquals(1, ((Number) row.get("supplement_count")).intValue());

        List<Map<String, Object>> sups = rows(row, "supplements");
        assertEquals(1, sups.size(), "A1 恰有 1 份补充报告：" + sups);
        Map<String, Object> s0 = sups.get(0);
        assertEquals(1, ((Number) s0.get("seqNo")).intValue());
        assertEquals("免疫组化：CK7(+)、CK20(-) " + tag, s0.get("diagnosis"), "supplements[0].diagnosis 是补充报告正文");
        assertEquals("免疫组化回报 " + tag, s0.get("reason"));
        assertEquals(tag + "d1医生", s0.get("signerName"));
        assertRecent(s0.get("signedAt"));

        String note = String.valueOf(body.get("note"));
        assertTrue(note.contains("已写诊断") && note.contains("未拒收") && note.contains("同一患者"),
                "口径须写进返回体 note：" + note);

        // 反过来看：从已签发的 A1 看既往，未诊断的 A2 与已拒收的 A3 都不算
        Set<Long> fromA1 = ids(ok(report.prior(a1, null)));
        assertFalse(fromA1.contains(a2), "未写诊断的 A2 正在做，不构成既往病理");
        assertFalse(fromA1.contains(a3));
        // 没有既往的 B1：items 空但 patientResolved=true——「没有既往」与「不知道是谁」两回事
        var forB = ok(report.prior(b1, null));
        assertEquals(Boolean.TRUE, forB.get("patientResolved"));
        assertTrue(rows(forB, "items").isEmpty());
    }

    // =====================================================================================
    // ② /history：默认排除拒收；includeRejected=true 才带；同名份数排除拒收
    // =====================================================================================

    @Test
    void historyExcludesRejectedByDefaultAndIncludesOnDemand() {
        // 默认：A1 在、A3 不在（修复前 A3 在——SPECIMEN_SELECT 只加了 patient_id=? and s.id<>?）
        var dflt = ok(registry.history(a2, null, null));
        Set<Long> ids = ids(dflt);
        assertTrue(ids.contains(a1));
        assertFalse(ids.contains(a3), "默认口径不含已拒收的 A3——与诊断页 prior 同口径");
        assertFalse(ids.contains(b1));
        assertFalse(ids.contains(c1), "同名他人 C1 不是本患者的既往");
        assertEquals(Boolean.FALSE, dflt.get("includeRejected"));
        assertTrue(String.valueOf(dflt.get("note")).contains("prior"), "note 须写明与 ④ prior 同口径：" + dflt.get("note"));
        assertNull(dflt.get("sameName"), "不传 includeSameName 不查同名");

        // includeRejected=true：A3 回来，行带 status / rejected_at / reject_reason
        var withRej = ok(registry.history(a2, null, true));
        ids = ids(withRej);
        assertTrue(ids.contains(a1) && ids.contains(a3), "含拒收时 A1 与 A3 都在：" + ids);
        assertFalse(ids.contains(b1) || ids.contains(c2) || ids.contains(d1), "含拒收也只含本患者的");
        assertEquals(Boolean.TRUE, withRej.get("includeRejected"));
        Map<String, Object> rowA3 = find(withRej, a3);
        assertNotNull(rowA3);
        assertRecent(rowA3.get("rejected_at"));
        assertEquals("标本未固定 " + tag, rowA3.get("reject_reason"));
        assertEquals("RECEIVED", rowA3.get("status"), "拒收不改 status，前端按 rejected_at 标「已拒收」");
        Map<String, Object> rowA1 = find(withRej, a1);
        assertNotNull(rowA1);
        assertNull(rowA1.get("rejected_at"));

        // 同名他人块：C 的份数只算未拒收的 C1（修复前 2），拒收份数另给 rejected_count；只有拒收标本的 D 默认不出现
        var same = ok(registry.history(a2, true, null));
        List<Map<String, Object>> sameRows = rows(same, "sameName");
        Map<String, Object> rowC = samePatient(sameRows, patientC);
        assertNotNull(rowC, "同名他人 C 必须在提醒块里：" + sameRows);
        assertEquals(1, ((Number) rowC.get("specimen_count")).intValue(), "同名份数须排除拒收的 C2（修复前算成 2）");
        assertEquals(0, ((Number) rowC.get("rejected_count")).intValue(), "默认不带拒收，rejected_count 为 0");
        // 最近一次登记 = 未拒收的 C1 的 collected_at（库端比较，不在 Java 里换算时区）
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select ?::timestamptz = collected_at from path_specimen where id = ?",
                Boolean.class, rowC.get("latest_collected_at"), c1), "latest_collected_at 须是未拒收的 C1 的登记时刻");
        assertNull(samePatient(sameRows, patientD), "只有拒收标本的同名 D，默认不该出现在同名块（修复前出现）");
        assertFalse(rowC.containsKey("diagnosis"), "同名他人不给诊断——那是越界");

        // 同名块 + 含拒收：C 的 specimen_count 仍是 1、rejected_count=1；D 以 0 + 1 出现
        var sameRej = ok(registry.history(a2, true, true));
        sameRows = rows(sameRej, "sameName");
        rowC = samePatient(sameRows, patientC);
        assertNotNull(rowC);
        assertEquals(1, ((Number) rowC.get("specimen_count")).intValue(), "含拒收时 specimen_count 也不把拒收混进去");
        assertEquals(1, ((Number) rowC.get("rejected_count")).intValue());
        Map<String, Object> rowD = samePatient(sameRows, patientD);
        assertNotNull(rowD, "含拒收时同名 D 出现：" + sameRows);
        assertEquals(0, ((Number) rowD.get("specimen_count")).intValue());
        assertEquals(1, ((Number) rowD.get("rejected_count")).intValue());
        assertNull(rowD.get("latest_collected_at"), "D 没有未拒收标本，最近一次登记如实为 null，不拿拒收行凑");

        // 患者解析不出仍是 5211（既有行为不动）；标本不存在 5206
        assertEquals(5206, registry.history(-1L, null, null).getCode());
    }

    // =====================================================================================
    // ③ 源码扫描：前端既往页签真的画了全文、真的有「对比」；登记页真的有 includeRejected
    // =====================================================================================

    @Test
    void frontendPriorTabShowsFullTextAndCompareAndRegistryHasIncludeRejected() {
        String diagRaw = read(DIAGNOSIS_PANEL);
        String regRaw = read(REGISTRY_PANEL);
        // 剥注释器的活对照：「工位四 / 工位一」只在 HTML 注释与 JSDoc 块注释里，剥完必须不见
        assertTrue(diagRaw.contains("工位四") && regRaw.contains("工位一"), "对照组标记不在了，扫描器失去校准物");
        String diag = stripVueComments(diagRaw);
        String reg = stripVueComments(regRaw);
        assertFalse(diag.contains("工位四"), "剥注释器坏了：DiagnosisPanel 的注释没剥干净");
        assertFalse(reg.contains("工位一"), "剥注释器坏了：RegistryPanel 的注释没剥干净");

        // 修复前：既往页签只画 row.diagnosis 一列，无 micro_finding / gross_finding，无「对比」按钮，无对照框
        assertTrue(diag.contains("row.micro_finding"), "既往页签须画镜下所见列");
        assertTrue(diag.contains("row.gross_finding"), "既往页签须画大体所见列");
        assertTrue(diag.contains("row.clinical_diagnosis"), "既往页签须画临床诊断列");
        assertTrue(diag.contains(">对比</el-button>"), "每行须有「对比」按钮");
        assertTrue(diag.contains("append-to-body"), "对照框须 append-to-body，才能叠在抽屉与书写对话框之上");
        assertTrue(diag.contains("compareRow.micro_finding") && diag.contains("compareSupplements"),
                "对照框右栏须画既往的镜下所见与补充报告正文");
        assertTrue(diag.contains("查看既往"), "书写诊断对话框内须有「查看既往」入口");
        // 对照组：登记页既往抽屉带「含拒收」开关并把 includeRejected 传给后端
        assertTrue(reg.contains("includeRejected"), "RegistryPanel 须有 includeRejected");
        assertTrue(reg.contains("已拒收"), "含拒收时行须标「已拒收」");
        assertFalse(diag.contains("includeRejected"), "诊断页既往不带拒收开关（prior 无此参数）——两份文件的扫描结果得不一样，否则扫描器在扫空");
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

    private long patient(String patientNo, String name) {
        return jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, patientNo, name);
    }

    private long registration(long patientId, Long scheduleId, Long deptId, int regNo) {
        return jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (?, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, regNo, patientId, scheduleId, deptId);
    }

    /** 一条门诊医嘱 + 一份已核收标本；时刻由 SQL 按 now() 现算，不写字面量 */
    private long specimen(long regId, String suffix) {
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag + suffix);
        return jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          clinical_diagnosis, status, collected_at, received_at, urgent)
                values (?, 1, ?, ?, 'ROUTINE', ?, ?, 'RECEIVED',
                        now() - interval '3 days', now() - interval '3 days' + interval '1 hour', false) returning id
                """, Long.class, orderId, "PB" + tag + suffix, tag + "-" + suffix, "标本" + suffix, "临床拟诊肿物 " + tag);
    }

    private void diagnoseAndIssue(long specimenId, String suffix) {
        jdbc.update("""
                update path_specimen
                   set status = 'DIAGNOSED', diagnosis = ?, gross_finding = ?, micro_finding = ?,
                       diagnosed_at = now() - interval '2 days', pathologist_id = ?
                 where id = ?
                """, "（左乳）浸润性导管癌 " + tag, "灰白组织一块 " + tag, "镜下见异型细胞 " + tag,
                userId(tag + "d1"), specimenId);
        var fs = report.firstSign(specimenId, null, doc1);
        assertEquals(0, fs.getCode(), suffix + " 初签：" + fs.getMessage());
        var is = report.issue(specimenId, doc1);
        assertEquals(0, is.getCode(), suffix + " 签发：" + is.getMessage());
    }

    /** 时刻容差断言：与库端 now() 偏差 ≤ 5 分钟（库端算，不在 Java 里做时区换算）——抄 V58GrossFieldsTest */
    private void assertRecent(Object ts) {
        assertNotNull(ts);
        Double gap = jdbc.queryForObject(
                "select abs(extract(epoch from (now() - ?::timestamptz)))", Double.class, ts);
        assertNotNull(gap);
        assertTrue(gap <= 300, "时刻应在此刻 5 分钟内，偏差秒数=" + gap);
    }

    private static Map<String, Object> ok(R<Map<String, Object>> r) {
        assertEquals(0, r.getCode(), r.getMessage());
        assertNotNull(r.getData());
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

    private static Set<Long> ids(Map<String, Object> body) {
        Set<Long> s = new HashSet<>();
        for (var r : rows(body, "items")) s.add(idOf(r));
        return s;
    }

    private static Map<String, Object> find(Map<String, Object> body, long specimenId) {
        return rows(body, "items").stream().filter(r -> idOf(r) == specimenId).findFirst().orElse(null);
    }

    private static Map<String, Object> samePatient(List<Map<String, Object>> sameRows, long patientId) {
        return sameRows.stream()
                .filter(r -> ((Number) r.get("patient_id")).longValue() == patientId)
                .findFirst().orElse(null);
    }

    // ==================== 源码扫描（照抄 V58GrossFieldsTest：从 user.dir 向上找仓库根） ====================

    /**
     * 剥 .vue 的三种注释：HTML {@code <!-- -->}、JS 块注释、JS 行注释（整行的，或代码后空白 + // 的）。
     * 这两份文件里没有 {@code ://}（URL）之类的假阳性源，setUp 前已 grep 确认；扫描断言只认剥完之后的文本。
     */
    private static String stripVueComments(String src) {
        String s = src.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.lines()
                .map(l -> {
                    String t = l.stripLeading();
                    if (t.startsWith("//")) return "";
                    int i = l.indexOf(" //");
                    return i >= 0 ? l.substring(0, i) : l;
                })
                .collect(Collectors.joining("\n"));
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
