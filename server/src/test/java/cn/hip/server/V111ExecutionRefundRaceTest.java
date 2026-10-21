package cn.hip.server;

import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.RefundApprovalService;
import cn.hip.outpatient.web.ExecStationController;
import cn.hip.outpatient.web.ExecStationController.ExecuteRequest;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No enclosing test transaction: fixtures are committed and every race observes a PostgreSQL Lock wait.
 * The preload case explicitly composes calls in one transaction; controller prechecks with OSIV off
 * do not themselves naturally preload the execution transaction. LIS/RIS/pathology producers are out of scope.
 */
@SpringBootTest
class V111ExecutionRefundRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ExecStationController exec;
    @Autowired ChargeService charges;
    @Autowired RefundApprovalService approvals;
    @Autowired OutpOrderRepository orders;
    @Autowired EntityManager em;

    Long dept, patient, schedule, registration, user, cashier, other, order, charge, approval;
    String username, cashierName, otherName;
    BigDecimal amount;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext();
        String tag = "E111" + Long.toUnsignedString(System.nanoTime(), 36);
        username = tag; cashierName = tag + "c"; otherName = tag + "b";
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag, tag);
        user = newUser(username, "ADMIN", "TECHNICIAN", "NURSE");
        cashier = newUser(cashierName, "CASHIER"); other = newUser(otherName, "TECHNICIAN", "CASHIER", "DOCTOR_OUTP");
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id", Long.class, tag, tag);
        // A historical visit owned by someone else remains in this shared clinical duty.
        String day = BusinessDates.today().minusDays(1).toString();
        schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee)
                values (?,?::date,5,1,1,0) returning id
                """, Long.class, dept, day);
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status)
                values (1,?,?,?,?,?::date,0,'VISITED') returning id
                """, Long.class, patient, schedule, dept, other, day);
        order = jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,
                                       unit,qty,unit_price,amount,status,doctor_id,review_status,review_note,reviewer_id)
                values (?,?,'TREAT',0,?,'执行退款夹具','次',1,7.50,7.50,'CREATED',?,'APPROVED','原审方',?) returning id
                """, Long.class, registration, tag, tag, other, other);
        amount = new BigDecimal("7.50");
        authenticate(cashierName, "CASHIER");
        charge = charges.settle(registration, "CASH", cashier).getId();
        authenticate(username, "TECHNICIAN");
    }

    Long newUser(String name, String... roles) {
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, name, name, dept);
        for (String role : roles) jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        return id;
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            if (registration != null) {
                jdbc.update("delete from outp_order_report where order_id in(select id from outp_order where registration_id=?)", registration);
                jdbc.update("delete from outp_refund_approval where charge_id in(select id from outp_charge where registration_id=?)", registration);
                jdbc.update("delete from outp_order where registration_id=?", registration);
                jdbc.update("delete from outp_charge where registration_id=?", registration);
                jdbc.update("delete from outp_registration where id=?", registration);
            }
            if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
            if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
            for (Long id : new Long[]{user, cashier, other}) if (id != null) {
                jdbc.update("delete from sys_user_role where user_id=?", id);
                jdbc.update("delete from sys_user where id=?", id);
            }
            if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
        });
    }

    Authentication token(String name, String role) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    void authenticate(String name, String role) { SecurityContextHolder.getContext().setAuthentication(token(name, role)); }
    Authentication auth() { return SecurityContextHolder.getContext().getAuthentication(); }
    int execute(String text) { return exec.execute(order, new ExecuteRequest(text), auth()).getCode(); }
    int refund() { charges.refund(charge, cashier); return 0; }

    int attempt(IntSupplier operation) {
        try { return operation.getAsInt(); }
        catch (HipBizException e) { return e.code; }
        catch (AccessDeniedException e) { return 1005; }
        // A raw unique/column failure is not the required readable business rejection.
        catch (DataIntegrityViolationException e) { return -4090; }
    }
    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, String name, String role, IntSupplier operation) {
        return pool.submit(() -> {
            authenticate(name, role);
            try {
                return attempt(() -> new TransactionTemplate(manager).execute(tx -> {
                    pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return operation.getAsInt();
                }));
            } finally { SecurityContextHolder.clearContext(); }
        });
    }
    void lockOrder() {
        assertEquals(List.of(order), jdbc.queryForList("select id from outp_order where id=? for update", Long.class, order));
    }
    void lockCharge() {
        assertEquals(List.of(charge), jdbc.queryForList("select id from outp_charge where id=? for update", Long.class, charge));
    }
    void waiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')", Boolean.class, pid.get()))) return;
            if (future.isDone()) fail("Operation finished without the required PostgreSQL Lock wait");
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Expected an observed PostgreSQL Lock wait");
    }
    void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdown(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "Workers finish before fixture cleanup");
    }
    Object snapshot() {
        return List.of(jdbc.queryForList("select * from outp_order where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_order_report where order_id=? order by id", order),
                jdbc.queryForList("select * from outp_refund_approval where charge_id=? order by id", charge));
    }
    void assertLedger(String orderStatus, String chargeStatus, int reports) {
        var row = jdbc.queryForMap("select status,charge_id,amount,qty,doctor_id,review_status,review_note,reviewer_id from outp_order where id=?", order);
        assertEquals(orderStatus, row.get("status"));
        assertEquals(0, amount.compareTo((BigDecimal) row.get("amount"))); assertEquals(1, row.get("qty"));
        assertEquals(other, row.get("doctor_id"));
        assertEquals("APPROVED", row.get("review_status")); assertEquals("原审方", row.get("review_note")); assertEquals(other, row.get("reviewer_id"));
        var bill = jdbc.queryForMap("select status,total_amount,refunded_at,refund_by,cashier_id from outp_charge where id=?", charge);
        assertEquals(chargeStatus, bill.get("status"));
        assertEquals(0, amount.compareTo((BigDecimal) bill.get("total_amount"))); assertEquals(cashier, bill.get("cashier_id"));
        if ("REFUNDED".equals(chargeStatus)) {
            assertNull(row.get("charge_id")); assertNotNull(bill.get("refunded_at"));
        } else {
            assertEquals(charge, row.get("charge_id")); assertNull(bill.get("refunded_at")); assertNull(bill.get("refund_by"));
        }
        assertEquals(reports, jdbc.queryForObject("select count(*) from outp_order_report where order_id=?", Integer.class, order));
        assertEquals(1, jdbc.queryForObject("select count(*) from outp_charge where registration_id=?", Integer.class, registration));
        assertEquals(1, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(1, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule));
    }
    void approvedLargeRefund() {
        amount = new BigDecimal("1000.00");
        assertTrue(approvals.needsApproval(amount), "Fixture must exceed the unchanged configured threshold");
        jdbc.update("update outp_order set amount=?,unit_price=? where id=?", amount, amount, order);
        jdbc.update("update outp_charge set total_amount=? where id=?", amount, charge);
        authenticate(cashierName, "CASHIER"); approval = approvals.apply(charge, "V111 fixture", cashier);
        authenticate(username, "ADMIN"); approvals.decide(approval, true, "V111 approved", user);
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,LAB", "TECHNICIAN,EXAM", "NURSE,TREAT"})
    void existingClinicalDutiesCanReadAndExecuteAllThreeTypesWithEmptyResults(String role, String type) {
        jdbc.update("update outp_order set order_type=? where id=?", type, order); authenticate(username, role);
        assertTrue(exec.worklist().getData().stream().anyMatch(row -> order.equals(row.get("orderId"))));
        String text = "LAB".equals(type) ? null : "";
        assertEquals(0, execute(text)); assertLedger("EXECUTED", "PAID", 1);
        var report = jdbc.queryForMap("select result_text,executor_id from outp_order_report where order_id=?", order);
        assertEquals(text, report.get("result_text")); assertEquals(user, report.get("executor_id"));
        assertEquals(1, exec.reports(registration).getData().size());
        assertFalse(exec.worklist().getData().stream().anyMatch(row -> order.equals(row.get("orderId"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOCTOR_OUTP", "CASHIER", "PHARMACIST"})
    void otherDutiesCannotReadOrExecuteThisStation(String role) {
        authenticate(username, role); Object before = snapshot();
        assertEquals(1005, attempt(() -> exec.worklist().getCode()));
        assertEquals(1005, attempt(() -> exec.reports(registration).getCode()));
        assertEquals(1005, attempt(() -> execute("不应写入"))); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roleClaimsDoNotAuthorizeDisabledOrMissingEmployees(boolean missing) {
        if (missing) authenticate(username + "missing", "TECHNICIAN");
        else jdbc.update("update sys_user set enabled=false where id=?", user);
        Object before = snapshot();
        assertEquals(1005, attempt(() -> exec.worklist().getCode()));
        assertEquals(1005, attempt(() -> exec.reports(registration).getCode()));
        assertEquals(1005, attempt(() -> execute("不应写入"))); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRUG", "REG", "OTHER"})
    void executionRefusesEveryTypeOutsideItsWorklist(String type) {
        jdbc.update("update outp_order set order_type=? where id=?", type, order);
        Object before = snapshot(); assertEquals(7003, attempt(() -> execute("不应执行"))); assertEquals(before, snapshot());
    }

    @Test
    void missingUnchargedAndOversizedResultGuardsRejectBeforeWriting() {
        assertEquals(7001, exec.execute(-111L, new ExecuteRequest("x"), auth()).getCode());
        jdbc.update("update outp_order set status='CREATED',order_type='DRUG' where id=?", order);
        Object before = snapshot(); assertEquals(7002, execute("x")); assertEquals(before, snapshot());
        jdbc.update("update outp_order set status='CHARGED',order_type='TREAT' where id=?", order);
        Object charged = snapshot();
        assertEquals(4000, attempt(() -> execute("界".repeat(2001)))); assertEquals(charged, snapshot());
    }

    @Test
    void anInconsistentChargedOrderWithAnExistingReportIsRejectedWithoutOverwritingHistory() {
        jdbc.update("insert into outp_order_report(order_id,result_text,executor_id) values (?,'旧报告',?)", order, other);
        Object before = snapshot(); assertEquals(7002, attempt(() -> execute("覆盖"))); assertEquals(before, snapshot());
    }

    @Test
    void explicitExecutionIdentityMustMatchItsAuthenticatedContext() {
        Object before = snapshot();
        assertEquals(1005, attempt(() -> exec.execute(order, new ExecuteRequest("冒名"), token(otherName, "TECHNICIAN")).getCode()));
        assertEquals(before, snapshot());
    }

    @Test
    void explicitRefundOperatorMustMatchItsAuthenticatedCashier() {
        authenticate(cashierName, "CASHIER"); Object before = snapshot();
        assertEquals(1005, attempt(() -> { charges.refund(charge, other); return 0; })); assertEquals(before, snapshot());
    }

    @Test
    void clinicalDutyDoesNotGrantTheCashierRefundOperation() {
        Object before = snapshot();
        assertEquals(1005, attempt(() -> { charges.refund(charge, user); return 0; })); assertEquals(before, snapshot());
    }

    @Test
    void trustedRefundServiceWithoutAnAuthenticationContextRetainsItsCompatibility() {
        SecurityContextHolder.clearContext(); charges.refund(charge, null); assertLedger("CREATED", "REFUNDED", 0);
        assertNull(jdbc.queryForObject("select refund_by from outp_charge where id=?", Long.class, charge));
    }

    @Test
    void committedRefundWinsAgainstExecutionWaitingOnTheSameOrder() throws Exception {
        authenticate(cashierName, "CASHIER");
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                assertEquals(0, refund());
                var future = worker(pool, pid, username, "TECHNICIAN", () -> execute("迟到执行"));
                results.add(future); waiting(pid, future);
            });
            assertEquals(7002, results.getFirst().get(10, TimeUnit.SECONDS)); assertLedger("CREATED", "REFUNDED", 0);
            assertEquals(cashier, jdbc.queryForObject("select refund_by from outp_charge where id=?", Long.class, charge));
        } finally { shutdown(pool); }
    }

    @Test
    void committedExecutionWinsAgainstRefundWaitingOnTheSameOrder() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                assertEquals(0, execute("真实执行")); em.flush();
                var future = worker(pool, pid, cashierName, "CASHIER", this::refund); results.add(future); waiting(pid, future);
            });
            assertEquals(5005, results.getFirst().get(10, TimeUnit.SECONDS)); assertLedger("EXECUTED", "PAID", 1);
        } finally { shutdown(pool); }
    }

    @Test
    void twoCompetingExecutionsHaveOneSuccessAndOneReadableBusinessFailure() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockOrder();
                for (int i = 0; i < 2; i++) {
                    var pid = new AtomicInteger(); var future = worker(pool, pid, username, "TECHNICIAN", () -> execute("唯一报告"));
                    results.add(future); waiting(pid, future);
                }
            });
            var codes = List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(1, codes.stream().filter(c -> c == 0).count());
            assertEquals(1, codes.stream().filter(c -> c == 7002).count()); assertLedger("EXECUTED", "PAID", 1);
        } finally { shutdown(pool); }
    }

    @Test
    void rollingBackExecutionRollsBackItsReportAndAllowsOneSafeRetry() {
        Object before = snapshot();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertEquals(0, execute("回滚的报告")); em.flush(); assertLedger("EXECUTED", "PAID", 1); tx.setRollbackOnly();
        });
        assertEquals(before, snapshot()); assertLedger("CHARGED", "PAID", 0);
        assertEquals(0, execute("安全重试")); assertLedger("EXECUTED", "PAID", 1);
        assertEquals("安全重试", jdbc.queryForObject("select result_text from outp_order_report where order_id=?", String.class, order));
    }

    @Test
    void preloadedOrderInAComposedTransactionCannotOverwriteCommittedRefund() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, username, "TECHNICIAN", () -> {
                assertEquals("CHARGED", orders.findById(order).orElseThrow().getStatus());
                loaded.countDown(); await(proceed); return execute("陈旧缓存");
            }); await(loaded);
            authenticate(cashierName, "CASHIER");
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                assertEquals(0, refund()); proceed.countDown(); waiting(pid, future);
            });
            assertEquals(7002, future.get(10, TimeUnit.SECONDS)); assertLedger("CREATED", "REFUNDED", 0);
        } finally { proceed.countDown(); shutdown(pool); }
    }

    @Test
    void waitingExecutionRechecksTheEmployeesEnabledState() throws Exception {
        Object before = snapshot();
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockOrder(); var future = worker(pool, pid, username, "TECHNICIAN", () -> execute("撤权后不可执行"));
                results.add(future); waiting(pid, future); jdbc.update("update sys_user set enabled=false where id=?", user);
            });
            assertEquals(1005, results.getFirst().get(10, TimeUnit.SECONDS)); assertEquals(before, snapshot());
        } finally { shutdown(pool); }
    }

    @Test
    void successfulExecutionPreservesReviewCommittedDuringItsOrderWait() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockOrder(); jdbc.update("update outp_order set review_note='等待中复核',reviewer_id=? where id=?", cashier, order);
                var future = worker(pool, pid, username, "TECHNICIAN", () -> execute("执行结果"));
                results.add(future); waiting(pid, future);
            });
            assertEquals(0, results.getFirst().get(10, TimeUnit.SECONDS));
            var row = jdbc.queryForMap("select status,charge_id,review_note,reviewer_id from outp_order where id=?", order);
            assertEquals(Map.of("status", "EXECUTED", "charge_id", charge, "review_note", "等待中复核", "reviewer_id", cashier), row);
            assertEquals(1, jdbc.queryForObject("select count(*) from outp_order_report where order_id=?", Integer.class, order));
        } finally { shutdown(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"C", "O"})
    void waitingRefundRechecksItsCashierAndDoesNotConsumeApprovalOnDenial(String blockedRow) throws Exception {
        approvedLargeRefund(); Object before = snapshot();
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                if ("C".equals(blockedRow)) lockCharge(); else lockOrder();
                var future = worker(pool, pid, cashierName, "CASHIER", this::refund); results.add(future); waiting(pid, future);
                jdbc.update("update sys_user set enabled=false where id=?", cashier);
            });
            assertEquals(1005, results.getFirst().get(10, TimeUnit.SECONDS));
            assertEquals(before, snapshot()); assertLedger("CHARGED", "PAID", 0);
            assertEquals("APPROVED", jdbc.queryForObject("select status from outp_refund_approval where id=?", String.class, approval));
        } finally { shutdown(pool); }
    }

    @Test
    void anAlreadyExecutedOrderDoesNotConsumeItsApprovedRefundPermission() {
        approvedLargeRefund(); authenticate(username, "TECHNICIAN"); assertEquals(0, execute("已执行"));
        Object before = snapshot(); authenticate(cashierName, "CASHIER");
        // Observe read-only validation before rolling back the composed caller transaction. A caught
        // participant exception is not evidence that its earlier approval UPDATE was never performed.
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertEquals(5005, attempt(this::refund)); assertEquals(before, snapshot());
            tx.setRollbackOnly();
        });
        assertLedger("EXECUTED", "PAID", 1);
        assertEquals("APPROVED", jdbc.queryForObject("select status from outp_refund_approval where id=?", String.class, approval));
    }
}
