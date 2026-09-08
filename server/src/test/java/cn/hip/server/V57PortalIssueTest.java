package cn.hip.server;

import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyController.DiagnoseReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.portal.web.PortalController;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v57 车道 D（2576）：<b>患者门户按「签发」而非「已诊断」发布病理报告</b>的判据测试。
 *
 * <h2>修复前的反向事实</h2>
 * {@code GET /api/portal/my/exam-reports} 的 PATH 分支此前是 {@code s.status = 'DIAGNOSED'}、
 * report_date 取 {@code diagnosed_at}：病理医师一写完诊断（尚未初签 / 复签 / 签发）患者端就能看到，
 * 而院内「已签发」看的是 {@code path_specimen.report_issued_at}（V144:64），唯一写侧是
 * {@code PUT /api/pathology/report/{specimenId}/issue}。本类每条断言都按「修复前会怎样」来钉：
 * <ol>
 *   <li>{@link #pathReportIsPublishedOnIssueNotOnDiagnosis()}：diagnose 后患者端<b>不含</b>该标本
 *       （修复前含）；签发后含，且 report_date 与签发响应的 reportIssuedAt 在微秒级相等、
 *       与 diagnosed_at 不等（修复前 report_date 是 diagnosed_at）；另一患者的令牌看不到它；
 *       {@code rejected_at} 非空的行不发布。</li>
 *   <li>{@link #examBranchIsByteForByteUnchanged()}：EXAM 分支仍只发布 VERIFIED、report_date 仍是
 *       verified_at，并且与 PATH 行按 report_date 倒序混排——本车道对它一个字节没动。</li>
 * </ol>
 *
 * <h2>夹具</h2>
 * setup 抄 {@code V55PathologyReachTest}：门诊医嘱 + 已核收标本走 SQL 直插；诊断走既有
 * {@code PUT /specimens/{barcode}/diagnose}，签发走 {@code /issue}（gate 钉 warn，缺双签放行）。
 * 患者端身份与 {@code V40PortalTest} 同款：令牌主体 {@code portal:{patientId}} + ROLE_PORTAL，
 * 与 {@code PortalController.patientId()} 的取法一致——<b>绝不从 body / path 取患者</b>。
 * <b>不写任何时间字面量</b>：全部时刻由 SQL 按 {@code now()} 现算；同一事务内 {@code now()} 恒定，
 * 故 diagnosed_at 用 SQL 回拨两天来造出「两天前诊断、今天签发」这个真实场景，
 * 否则 report_date 取的是哪一列在值上根本分不出来。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V57PortalIssueTest {

    @Autowired PortalController portal;
    @Autowired PathologyController pathology;
    @Autowired PathologyReportController report;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc1;
    private Long patientId;
    private Long otherPatientId;
    private Long regId;
    private long specimenId;
    private String barcode;

    @BeforeEach
    void setUp() {
        configReader.evictAll();
        // 双签 gate 钉在 warn：签发要走「缺双签即放行」这条路；事务回滚，库里不留痕
        jdbc.update("update sys_config set cfg_value = 'warn' where cfg_key = ?",
                PathologyReportController.DOUBLE_SIGN_GATE_KEY);
        configReader.evictAll();

        tag = "V57D" + Long.toHexString(System.nanoTime());
        doc1 = doctorAuth(tag + "d1");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "门户签发测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "门户患者" + tag);
        otherPatientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'M', current_date - 15000) returning id
                """, Long.class, tag + "Q", "旁人" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);

        barcode = "PB" + tag;
        specimenId = specimen(barcode);
    }

    @AfterEach
    void tearDown() {
        configReader.evictAll();
    }

    // =====================================================================================
    // ① 发布 = 签发（2576）
    // =====================================================================================

    @Test
    void pathReportIsPublishedOnIssueNotOnDiagnosis() {
        String dx = "（左乳）浸润性导管癌 " + tag;
        String micro = "镜下见异型细胞浸润 " + tag;

        // 尚未诊断：当然不在
        assertNull(pathRow(patientId, dx));

        // 既有 diagnose 端点：status → DIAGNOSED、diagnosed_at 落值，report_issued_at 仍空
        var dg = pathology.diagnose(barcode, new DiagnoseReq("灰白组织一块 " + tag, micro, dx), doc1);
        assertEquals(0, dg.getCode(), dg.getMessage());
        assertEquals("DIAGNOSED", jdbc.queryForObject(
                "select status from path_specimen where id = ?", String.class, specimenId));
        assertNotNull(jdbc.queryForObject("select diagnosed_at from path_specimen where id = ?", Object.class, specimenId));
        assertNull(jdbc.queryForObject("select report_issued_at from path_specimen where id = ?", Object.class, specimenId));
        // 两天前诊断、今天签发：同一事务 now() 恒定，不回拨的话 diagnosed_at == report_issued_at，
        // 「report_date 取的是哪一列」在值上分不出来
        jdbc.update("update path_specimen set diagnosed_at = now() - interval '2 days' where id = ?", specimenId);

        assertNull(pathRow(patientId, dx),
                "已诊断未签发的报告不得出现在患者端——修复前 where 是 status='DIAGNOSED'，这里会命中");

        // 签发（gate=warn：缺双签放行并回带 warnings）
        var is = report.issue(specimenId, doc1);
        assertEquals(0, is.getCode(), is.getMessage());
        assertFalse(((List<?>) is.getData().get("warnings")).isEmpty(), "缺双签签发必须回带 warnings");
        Instant issuedAt = instantOf(is.getData().get("reportIssuedAt"));
        Instant issuedInDb = instantOf(jdbc.queryForObject(
                "select report_issued_at from path_specimen where id = ?", Object.class, specimenId));
        assertEquals(issuedInDb, issuedAt, "签发响应的 reportIssuedAt 就是库里的 report_issued_at（微秒级）");

        Map<String, Object> row = pathRow(patientId, dx);
        assertNotNull(row, "签发后患者端必须能看到该报告");
        assertEquals("PATH", row.get("report_type"));
        assertEquals("病理检查", row.get("item_name"));
        assertEquals(dx, row.get("conclusion"));
        assertEquals(micro, row.get("detail"));
        assertEquals(issuedAt, instantOf(row.get("report_date")),
                "report_date 必须是签发时刻（与签发响应微秒级相等）");
        Instant diagnosedAt = instantOf(jdbc.queryForObject(
                "select diagnosed_at from path_specimen where id = ?", Object.class, specimenId));
        assertNotEquals(diagnosedAt, instantOf(row.get("report_date")),
                "report_date 不再是 diagnosed_at——修复前取的正是这一列");
        assertTrue(diagnosedAt.isBefore(issuedAt), "夹具：诊断在前、签发在后");

        // 越权反向事实：另一患者的令牌看不到它（身份只从令牌主体取）
        assertNull(pathRow(otherPatientId, dx), "旁人的令牌不得看到他人的病理报告");

        // 拒收行不发布：直接改库钉住 where 里的 rejected_at is null（端点层已拦「签发后拒收」，此处只钉子句）
        jdbc.update("update path_specimen set rejected_at = now() where id = ?", specimenId);
        assertNull(pathRow(patientId, dx), "rejected_at 非空的标本不得发布到患者端");
    }

    // =====================================================================================
    // ② EXAM 分支一个字节没动
    // =====================================================================================

    @Test
    void examBranchIsByteForByteUnchanged() {
        String impression = "影像印象 " + tag;
        String pending = "未审核印象 " + tag;
        // 一条已审核（昨天审核）、一条只报告未审核
        Long examOrder = examOrder("E1", "胸部CT " + tag);
        jdbc.update("""
                insert into ris_exam(order_id, status, findings, impression, reported_at, verified_at)
                values (?, 'VERIFIED', ?, ?, now() - interval '1 day', now() - interval '1 day')
                """, examOrder, "所见 " + tag, impression);
        jdbc.update("""
                insert into ris_exam(order_id, status, findings, impression, reported_at)
                values (?, 'REPORTED', ?, ?, now())
                """, examOrder("E2", "头颅MR " + tag), "所见2 " + tag, pending);

        List<Map<String, Object>> rows = rowsOf(patientId);
        Map<String, Object> exam = rows.stream()
                .filter(r -> "EXAM".equals(r.get("report_type")) && impression.equals(r.get("conclusion")))
                .findFirst().orElse(null);
        assertNotNull(exam, "VERIFIED 的影像报告仍发布");
        assertEquals("胸部CT " + tag, exam.get("item_name"));
        assertEquals("所见 " + tag, exam.get("detail"));
        Instant verifiedAt = instantOf(jdbc.queryForObject(
                "select verified_at from ris_exam where order_id = ?", Object.class, examOrder));
        assertEquals(verifiedAt, instantOf(exam.get("report_date")), "EXAM 的 report_date 仍是 verified_at");
        assertTrue(rows.stream().noneMatch(r -> pending.equals(r.get("conclusion"))),
                "未审核（REPORTED）的影像报告仍不发布");

        // 与 PATH 行混排：今天签发的病理报告排在昨天审核的影像报告前面（order by report_date desc）
        String dx = "混排诊断 " + tag;
        assertEquals(0, pathology.diagnose(barcode, new DiagnoseReq("g", "m", dx), doc1).getCode());
        assertEquals(0, report.issue(specimenId, doc1).getCode());
        rows = rowsOf(patientId);
        int idxPath = -1, idxExam = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (dx.equals(rows.get(i).get("conclusion"))) idxPath = i;
            if (impression.equals(rows.get(i).get("conclusion"))) idxExam = i;
        }
        assertTrue(idxPath >= 0 && idxExam >= 0, "两类报告都应在列表里：" + rows);
        assertTrue(idxPath < idxExam, "按 report_date 倒序：今天签发的 PATH 在昨天审核的 EXAM 之前");
        assertNull(pathRow(otherPatientId, dx));
        assertTrue(rowsOf(otherPatientId).stream().noneMatch(r -> impression.equals(r.get("conclusion"))),
                "旁人也看不到他人的影像报告");
    }

    // ==================== 夹具与取值 ====================

    /** 患者端令牌主体：portal:{patientId} + ROLE_PORTAL（与 PortalController.patientId() 的取法一致） */
    private static Authentication tokenOf(Long patientId) {
        return new UsernamePasswordAuthenticationToken("portal:" + patientId, null,
                List.of(new SimpleGrantedAuthority("ROLE_PORTAL")));
    }

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    /** 一条门诊病理医嘱 + 一份已核收标本；时刻由 SQL 现算，不写字面量 */
    private long specimen(String barcode) {
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag);
        return jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent)
                values (?, 1, ?, ?, 'ROUTINE', '标本', 'RECEIVED',
                        now() - interval '1 day' - interval '1 hour', now() - interval '1 day', false) returning id
                """, Long.class, orderId, barcode, tag + "-S");
    }

    /** 一条已执行的影像医嘱（group_no 是 varchar(32)，后缀只用两个字符） */
    private Long examOrder(String suffix, String itemName) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'RIS', ?, '次', 1, 100.00, 100.00, 'EXECUTED') returning id
                """, Long.class, regId, "E" + tag + suffix, itemName);
    }

    private List<Map<String, Object>> rowsOf(Long pid) {
        var r = portal.myExamReports(tokenOf(pid));
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    /** 患者端行没有标本 id，按 conclusion（带唯一 tag 的诊断文本）定位 PATH 行 */
    private Map<String, Object> pathRow(Long pid, String dx) {
        return rowsOf(pid).stream()
                .filter(r -> "PATH".equals(r.get("report_type")) && dx.equals(r.get("conclusion")))
                .findFirst().orElse(null);
    }

    /** timestamptz 经 JDBC 回来可能是 Timestamp 或 OffsetDateTime；统一到 Instant 后比较，微秒精度不丢 */
    private static Instant instantOf(Object v) {
        assertNotNull(v, "时刻不应为空");
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (v instanceof OffsetDateTime odt) return odt.toInstant();
        if (v instanceof Instant i) return i;
        throw new AssertionError("非时间类型：" + v.getClass() + " " + v);
    }
}
