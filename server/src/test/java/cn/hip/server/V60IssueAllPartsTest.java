package cn.hip.server;

import cn.hip.medtech.web.PathologyRegistryController;
import cn.hip.medtech.web.PathologyRegistryController.RejectReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.service.ConfigReader;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v60 车道 B：<b>2576 尾-①——多部位申请的 EXECUTED 口径：同一申请的全部未拒收部位都已签发才置</b>。
 *
 * <h2>每条断言钉住的「修复前的反向事实」（反驳者原话，主控核过）</h2>
 * 「多部位申请第 1 个部位签发即把整张 outp_order 置 EXECUTED，不看同申请其余部位」——v59 的 update 只判
 * {@code id = 该标本 order_id and status = 'CHARGED'}，医生站在另外两个部位还在切片时就显示「已执行」。
 * <ol>
 *   <li>{@link #multiPartOrderExecutesOnlyAfterEveryUnrejectedPartIssued()}：两部位——第 1 部位签发 → 仍 CHARGED、
 *       {@code orderExecuted=false}、{@code partsPending=1}（<b>修复前 true / EXECUTED / 0</b>）；第 2 部位签发 → EXECUTED、
 *       {@code partsPending=0}；重复签发 5261 状态不变。</li>
 *   <li>{@link #rejectedPartDoesNotHoldTheOrder()}：第 2 部位拒收再签发第 1 → EXECUTED（拒收不删行、不改 status，按
 *       {@code rejected_at} 排除）；反过来先签发第 1 再拒收第 2 → 仍 CHARGED（拒收端点不联动、如实标注）。</li>
 *   <li>{@link #threePartsMixedOrder()}：三部位——签发 1（pending 2）→ 拒收 3 → 签发 2 → EXECUTED（pending 0）。</li>
 *   <li>{@link #singlePartBehaviourUnchanged()}：单部位申请与此前逐字相同——签发即 EXECUTED、{@code orderExecuted=true}、
 *       {@code partsPending=0}；同挂号下别的 CHARGED 申请不被误伤（对账）。</li>
 *   <li>{@link #inpatientSourcePartsPendingIsInformationalOnly()}：住院来源两部位——{@code orderExecuted} 恒 false、
 *       {@code partsPending} 按 inp_order_id 计数只作信息、{@code inp_order} 不碰（维持 v59 口径）。</li>
 * </ol>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：夹具时刻由 SQL 按 {@code now()} 现算。夹具照抄 V59TechConsistencyTest（直插已诊断标本，跳过取材 / 切片——
 * 签发只看 diagnosis / rejected_at / report_issued_at 三列）。双签 gate 钉到 warn：缺签放行、返回体带 warnings，不影响本类断言。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V60IssueAllPartsTest {

    @Autowired PathologyReportController report;
    @Autowired PathologyRegistryController registry;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Long regId;
    private Long patientId;
    private Authentication doc;

    @BeforeEach
    void setUp() {
        assertEquals(1, setGate(PathologyReportController.DOUBLE_SIGN_GATE_KEY, "warn"), "doublesign gate 行必须存在（V144 seed）");

        tag = "V60I" + Long.toHexString(System.nanoTime());
        doc = doctorAuth(tag + "d1");

        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "多部位签发测试科", tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "多部位签发患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
    }

    // =====================================================================================
    // ① 两部位：第 1 部位签发不置（修复前置）；第 2 部位签发才置
    // =====================================================================================

    @Test
    void multiPartOrderExecutesOnlyAfterEveryUnrejectedPartIssued() {
        Long o = order("A");
        long p1 = part(o, 1, true);
        long p2 = part(o, 2, true);
        assertEquals("CHARGED", orderStatus(o), "夹具前提：登记后申请仍 CHARGED");

        var i1 = ok(report.issue(p1, doc));
        assertNotNull(i1.get("reportIssuedAt"), "第 1 部位照常签发");
        assertNotNull(issuedAt(p1));
        assertEquals(Boolean.FALSE, i1.get("orderExecuted"),
                "修复前反向事实：第 1 个部位签发即把整张 outp_order 置 EXECUTED（orderExecuted=true）——现在同申请还有第 2 部位未签发，不置");
        assertEquals(1L, asLong(i1.get("partsPending")), "同申请未签发未拒收部位数 = 1（第 2 部位）");
        assertEquals("CHARGED", orderStatus(o), "第 1 部位签发后申请仍 CHARGED");
        assertNull(issuedAt(p2), "第 2 部位未被波及");

        var i2 = ok(report.issue(p2, doc));
        assertEquals(Boolean.TRUE, i2.get("orderExecuted"), "最后一个部位签发才置 EXECUTED");
        assertEquals(0L, asLong(i2.get("partsPending")));
        assertEquals("EXECUTED", orderStatus(o));

        // 重复签发 5261，状态不变；返回体既有键一个不少
        assertEquals(5261, report.issue(p1, doc).getCode());
        assertEquals("EXECUTED", orderStatus(o));
        for (String k : List.of("specimenId", "reportIssuedAt", "diagnosedAt", "doubleSignGate", "doubleSignComplete", "warnings",
                "orderExecuted", "partsPending")) {
            assertTrue(i2.containsKey(k), "签发返回体缺 " + k + "：" + i2.keySet());
        }
    }

    // =====================================================================================
    // ② 拒收的部位不算未完成：先拒收第 2 再签发第 1 → EXECUTED；先签发第 1 再拒收第 2 → 仍 CHARGED（如实）
    // =====================================================================================

    @Test
    void rejectedPartDoesNotHoldTheOrder() {
        Long o = order("B");
        long p1 = part(o, 1, true);
        long p2 = part(o, 2, false);   // 未诊断才能拒收（5208 已出诊断不能拒收）

        var rj = registry.reject(p2, new RejectReq("固定不良 " + tag, null), doc);
        assertEquals(0, rj.getCode(), rj.getMessage());
        assertNotNull(jdbc.queryForObject("select rejected_at from path_specimen where id = ?", Object.class, p2));
        assertEquals("CHARGED", orderStatus(o), "拒收不动申请状态");

        var i1 = ok(report.issue(p1, doc));
        assertEquals(Boolean.TRUE, i1.get("orderExecuted"), "第 2 部位已拒收：签发第 1 即全部未拒收部位都已签发 → EXECUTED");
        assertEquals(0L, asLong(i1.get("partsPending")), "已拒收的不算未完成");
        assertEquals("EXECUTED", orderStatus(o));

        // 反过来：先签发第 1（pending 1）再拒收第 2——拒收端点（PathologyRegistryController，不归本车道）不联动，
        // 申请留在 CHARGED；如实钉住当前行为，主控若在拒收端点补联动请改此断言
        Long o2 = order("B2");
        long q1 = part(o2, 1, true);
        long q2 = part(o2, 2, false);
        var j1 = ok(report.issue(q1, doc));
        assertEquals(Boolean.FALSE, j1.get("orderExecuted"));
        assertEquals(1L, asLong(j1.get("partsPending")));
        assertEquals(0, registry.reject(q2, new RejectReq("量不足 " + tag, null), doc).getCode());
        assertEquals("CHARGED", orderStatus(o2), "拒收端点不回头置 EXECUTED（本版范围外，如实）");
        assertEquals(5261, report.issue(q1, doc).getCode(), "已签发的第 1 部位不能再签一次来触发");
        // 拒收的部位本身不能签发（5261 已拒收）
        assertEquals(5261, report.issue(q2, doc).getCode());
    }

    // =====================================================================================
    // ③ 三部位：签发 1 → 拒收 3 → 签发 2 → EXECUTED
    // =====================================================================================

    @Test
    void threePartsMixedOrder() {
        Long o = order("C");
        long p1 = part(o, 1, true);
        long p2 = part(o, 2, true);
        long p3 = part(o, 3, false);

        var i1 = ok(report.issue(p1, doc));
        assertEquals(Boolean.FALSE, i1.get("orderExecuted"));
        assertEquals(2L, asLong(i1.get("partsPending")), "还有第 2、3 部位");
        assertEquals("CHARGED", orderStatus(o));

        assertEquals(0, registry.reject(p3, new RejectReq("送检不合格 " + tag, null), doc).getCode());

        var i2 = ok(report.issue(p2, doc));
        assertEquals(Boolean.TRUE, i2.get("orderExecuted"), "第 3 部位已拒收、第 1 已签发：第 2 签发即全部完成");
        assertEquals(0L, asLong(i2.get("partsPending")));
        assertEquals("EXECUTED", orderStatus(o));
    }

    // =====================================================================================
    // ④ 单部位：行为逐字不变；同挂号下别的申请不被误伤
    // =====================================================================================

    @Test
    void singlePartBehaviourUnchanged() {
        Long o = order("D");
        Long other = order("D-other");   // 同一挂号下另一条 CHARGED 病理申请：对照组
        long p1 = part(o, 1, true);
        long q1 = part(other, 1, true);

        var i1 = ok(report.issue(p1, doc));
        assertEquals(Boolean.TRUE, i1.get("orderExecuted"), "单部位：签发即 EXECUTED（与 v59 逐字相同）");
        assertEquals(0L, asLong(i1.get("partsPending")));
        assertEquals("EXECUTED", orderStatus(o));
        assertEquals("CHARGED", orderStatus(other), "别的申请不被误伤");
        assertNull(issuedAt(q1));
        assertEquals(1L, (long) jdbc.queryForObject(
                "select count(*) from outp_order where registration_id = ? and status = 'EXECUTED'", Long.class, regId),
                "本挂号下恰一条被置 EXECUTED");
    }

    // =====================================================================================
    // ⑤ 住院来源：orderExecuted 恒 false、partsPending 只作信息、inp_order 不碰
    // =====================================================================================

    @Test
    void inpatientSourcePartsPendingIsInformationalOnly() {
        var inp = inpatientOrder("E");
        long p1 = inpatientPart(inp, 1);
        long p2 = inpatientPart(inp, 2);

        var i1 = ok(report.issue(p1, doc));
        assertEquals(Boolean.FALSE, i1.get("orderExecuted"), "住院来源：没有门诊申请可置");
        assertEquals(1L, asLong(i1.get("partsPending")), "按 inp_order_id 同口径计数（只作信息）");
        assertEquals("CHARGED", inpOrderStatus(inp));
        var i2 = ok(report.issue(p2, doc));
        assertEquals(Boolean.FALSE, i2.get("orderExecuted"));
        assertEquals(0L, asLong(i2.get("partsPending")));
        assertEquals("CHARGED", inpOrderStatus(inp), "签发不碰 inp_order（维持 v59 口径，javadoc 写明）");
    }

    // ==================== 夹具与取值 ====================

    private int setGate(String key, String value) {
        configReader.evictAll();
        int n = jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, key);
        configReader.evictAll();
        return n;
    }

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    /** 一条已收费门诊病理申请 */
    private Long order(String suffix) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag + suffix);
    }

    /**
     * 同一申请的第 {@code partNo} 个部位：已核收；{@code diagnosed=true} 则直插诊断（DIAGNOSED，可签发），
     * false 则 RECEIVED（可拒收）。时刻由 SQL 现算。
     */
    private long part(Long orderId, int partNo, boolean diagnosed) {
        String suffix = orderId + "-" + partNo;
        return jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent, diagnosis, micro_finding, diagnosed_at)
                values (?, ?, ?, ?, 'ROUTINE', ?, ?,
                        now() - interval '2 days' - interval '1 hour', now() - interval '2 days', false,
                        ?, ?, case when ?::boolean then now() - interval '1 hour' else null end)
                returning id
                """, Long.class, orderId, partNo, "PB" + tag + suffix, tag + "-" + suffix, "部位" + partNo,
                diagnosed ? "DIAGNOSED" : "RECEIVED",
                diagnosed ? "诊断 " + suffix + " " + tag : null,
                diagnosed ? "镜下 " + suffix : null,
                diagnosed);
    }

    /** 住院来源：inp_admission + inp_order(CHARGED)（照抄 V59TechConsistencyTest.inpatientSpecimen） */
    private Long inpatientOrder(String suffix) {
        Long deptId = jdbc.queryForObject("select id from sys_dept order by id limit 1", Long.class);
        Long bedId = jdbc.queryForObject("select id from inp_bed order by id limit 1", Long.class);
        assertNotNull(bedId, "测试库须有床位种子（V8）");
        Long admId = jdbc.queryForObject("""
                insert into inp_admission(admission_no, patient_id, dept_id, ward_id, bed_id, status, admit_at)
                values (?, ?, ?, ?, ?, 'IN_HOSPITAL', now()) returning id
                """, Long.class, tag + "ADM" + suffix, patientId, deptId, deptId, bedId);
        return jdbc.queryForObject("""
                insert into inp_order(admission_id, group_no, order_type, item_id, item_code, item_name,
                                      unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, admId, "I" + tag + suffix);
    }

    private long inpatientPart(Long inpOrderId, int partNo) {
        String suffix = "I" + inpOrderId + "-" + partNo;
        return jdbc.queryForObject("""
                insert into path_specimen(inp_order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent, diagnosis, diagnosed_at)
                values (?, ?, ?, ?, 'ROUTINE', ?, 'DIAGNOSED',
                        now() - interval '2 days' - interval '1 hour', now() - interval '2 days', false,
                        ?, now() - interval '1 hour')
                returning id
                """, Long.class, inpOrderId, partNo, "PB" + tag + suffix, tag + "-" + suffix, "住院部位" + partNo,
                "诊断 " + suffix + " " + tag);
    }

    private String orderStatus(Long orderId) {
        return jdbc.queryForObject("select status from outp_order where id = ?", String.class, orderId);
    }

    private String inpOrderStatus(Long inpOrderId) {
        return jdbc.queryForObject("select status from inp_order where id = ?", String.class, inpOrderId);
    }

    private Object issuedAt(long specimenId) {
        return jdbc.queryForObject("select report_issued_at from path_specimen where id = ?", Object.class, specimenId);
    }

    private static Map<String, Object> ok(R<Map<String, Object>> r) {
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }
}
