package cn.hip.server;

import cn.hip.medtech.web.PathologyReportController;
import cn.hip.platform.core.common.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v60 审阅修补：并发签发同一申请的最后两个部位，申请必须结算成 EXECUTED（不变量），且恰好一次签发返回 orderExecuted=true。
 *
 * <p><b>为什么单独成类、不加 @Transactional</b>：两个线程各跑各的事务，夹具必须已提交才对它们可见——
 * @Transactional 的测试类里夹具是本线程未提交的行，别的线程看不见（方法论④）。本类自己建、自己删。
 *
 * <p><b>修复前的反向事实</b>（审阅者用两个 psql 会话复现，见 CHANGELOG 1.6.0）：两个事务各自的
 * {@code update outp_order … and not exists(兄弟部位未签发)} 都在对方提交前求值，READ COMMITTED 看不到对方未提交的
 * {@code report_issued_at}，两边都命中 0 行，申请永久停在 CHARGED。修复后结算前先 {@code select … for update}
 * 锁申请行，第二个事务在锁上等第一个提交后重新求值。
 *
 * <p><b>诚实边界（方法论⑨）</b>：本测试用 CyclicBarrier 让两线程同时进入签发并重复多轮，断言不变量；
 * 但两线程是否真的在「update 与 commit 之间」交错，本测试无法从外部观测——那一层由审阅者的 psql 级复现背书，
 * 本测试是修复后的回归守卫，不是竞争发生的证明。
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class V60IssueConcurrencyTest {

    private static final int ROUNDS = 8;

    @Autowired PathologyReportController report;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Long regId;
    private Long patientId;
    private Authentication doc;
    private final List<Long> orders = new ArrayList<>();
    private final List<Long> specimens = new ArrayList<>();

    private Long deptId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        // 夹具照抄 V60IssueAllPartsTest.setUp（列名与 not null 约束以它为准，别猜）
        tag = "V60C" + Long.toHexString(System.nanoTime());
        deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "并发签发测试科", tag + "D");
        scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "并发签发患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
        jdbc.update("insert into sys_user(username, password, real_name, enabled) values (?, 'x', ?, true) on conflict (username) do nothing",
                tag + "d", tag + "医生");
        doc = new UsernamePasswordAuthenticationToken(tag + "d", null, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    @AfterEach
    void tearDown() {
        // 自建自删（本类无 @Transactional，夹具是真提交的行）
        if (!specimens.isEmpty()) {
            String in = String.join(",", specimens.stream().map(String::valueOf).toList());
            jdbc.update("delete from path_process where specimen_id in (" + in + ")");
            jdbc.update("delete from path_specimen where id in (" + in + ")");
        }
        if (!orders.isEmpty()) {
            jdbc.update("delete from outp_order where id in (" + String.join(",", orders.stream().map(String::valueOf).toList()) + ")");
        }
        if (regId != null) jdbc.update("delete from outp_registration where id = ?", regId);
        if (scheduleId != null) jdbc.update("delete from outp_schedule where id = ?", scheduleId);
        if (patientId != null) jdbc.update("delete from empi_patient where id = ?", patientId);
        if (deptId != null) jdbc.update("delete from sys_dept where id = ?", deptId);
    }

    @Test
    void concurrentIssueOfTheLastTwoPartsAlwaysSettlesTheOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                Long order = order("R" + round);
                long p1 = part(order, 1);
                long p2 = part(order, 2);
                CyclicBarrier gate = new CyclicBarrier(2);
                List<Future<R<Map<String, Object>>>> fs = new ArrayList<>();
                for (long sid : new long[] {p1, p2}) {
                    fs.add(pool.submit(() -> {
                        // @WithMockUser 只装在测试线程上；issue() 有 @PreAuthorize，工作线程得自己带鉴权
                        SecurityContextHolder.getContext().setAuthentication(doc);
                        try {
                            gate.await(10, TimeUnit.SECONDS);
                            return report.issue(sid, doc);
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    }));
                }
                int executedTrue = 0;
                for (var f : fs) {
                    R<Map<String, Object>> r = f.get(30, TimeUnit.SECONDS);
                    assertEquals(0, r.getCode(), "第 " + round + " 轮签发失败：" + r.getMessage());
                    if (Boolean.TRUE.equals(r.getData().get("orderExecuted"))) executedTrue++;
                }
                assertEquals("EXECUTED", jdbc.queryForObject("select status from outp_order where id = ?", String.class, order),
                        "第 " + round + " 轮：两部位并发签发后申请必须结算成 EXECUTED（修复前两边各命中 0 行、永久 CHARGED）");
                assertEquals(1, executedTrue, "第 " + round + " 轮：恰好一次签发把申请置成 EXECUTED（锁串行化后后到者看见先到者）");
                Long issued = jdbc.queryForObject("select count(*) from path_specimen where order_id = ? and report_issued_at is not null", Long.class, order);
                assertEquals(2L, issued);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Long order(String suffix) {
        Long id = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag + suffix);
        assertNotNull(id);
        orders.add(id);
        return id;
    }

    /** 同一申请的第 partNo 个部位：已核收、已诊断（可签发）；时刻由 SQL 现算 */
    private long part(Long orderId, int partNo) {
        String suffix = orderId + "-" + partNo;
        Long id = jdbc.queryForObject("""
                insert into path_specimen(order_id, part_no, barcode, path_no, specimen_type, specimen_desc,
                                          status, collected_at, received_at, urgent, diagnosis, micro_finding, diagnosed_at)
                values (?, ?, ?, ?, 'ROUTINE', ?, 'DIAGNOSED',
                        now() - interval '2 days' - interval '1 hour', now() - interval '2 days', false,
                        ?, ?, now() - interval '1 hour')
                returning id
                """, Long.class, orderId, partNo, "PB" + tag + suffix, tag + "-" + suffix, "部位" + partNo,
                "诊断 " + suffix + " " + tag, "镜下 " + suffix);
        assertNotNull(id);
        specimens.add(id);
        return id;
    }
}
