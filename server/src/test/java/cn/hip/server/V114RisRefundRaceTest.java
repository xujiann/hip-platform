package cn.hip.server;

import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.ChargeService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real committed PostgreSQL transactions: no enclosing @Transactional test. Every coordinated
 * race observes pg_stat_activity.wait_event_type=Lock before releasing its owner. Authentication
 * here exercises the controller/service boundary, not JWT parsing. Managed-entity cases compose
 * calls in one transaction deliberately; they are not claims about an HTTP metadata endpoint.
 */
@SpringBootTest
class V114RisRefundRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ChargeService charges;
    @Autowired MedTechController ris;
    @Autowired OutpOrderRepository orders;
    @Autowired EntityManager em;

    record Staff(Long id, String name, String role) {}
    final List<Staff> employees = new ArrayList<>();
    Staff tech, reporter, cashier;
    Long dept, patient, schedule, registration, order, charge, exam;
    String tag;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext();
        tag = "R114" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag, tag);
        tech = employee("t", "TECHNICIAN"); reporter = employee("d", "DOCTOR_OUTP"); cashier = employee("c", "CASHIER");
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id", Long.class, tag, tag);
        String day = BusinessDates.today().minusDays(1).toString();
        schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee)
                values (?,?::date,5,1,1,0) returning id
                """, Long.class, dept, day);
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status)
                values (1,?,?,?,?,?::date,0,'VISITED') returning id
                """, Long.class, patient, schedule, dept, reporter.id(), day);
        order = jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,
                                       unit,qty,unit_price,amount,status,doctor_id,review_status,review_note,reviewer_id,
                                       remark,clinical_summary)
                values (?,?,'EXAM',0,?,'RIS退款夹具','次',1,7.50,7.50,'CREATED',?,'APPROVED','原审方',?,'原备注','原摘要') returning id
                """, Long.class, registration, tag, tag, reporter.id(), reporter.id());
        authenticate(cashier); charge = charges.settle(registration, "CASH", cashier.id()).getId();
        exam = jdbc.queryForObject("insert into ris_exam(order_id,status) values (?,'REGISTERED') returning id", Long.class, order);
        authenticate(tech);
    }

    Staff employee(String suffix, String role) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id", Long.class, name, name, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        var actor = new Staff(id, name, role); employees.add(actor); return actor;
    }
    Authentication token(String name, String role) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    Authentication authenticate(Staff actor) {
        if (actor == null) { SecurityContextHolder.clearContext(); return null; }
        Authentication auth = token(actor.name(), actor.role()); SecurityContextHolder.getContext().setAuthentication(auth); return auth;
    }
    Authentication auth() { return SecurityContextHolder.getContext().getAuthentication(); }
    int arrive() { return ris.arrive(exam).getCode(); }
    int report() { return ris.writeReport(exam, new MedTechController.RisReportReq("模拟所见", "模拟结论"), auth()).getCode(); }
    int verify() { return ris.verifyReport(exam, auth()).getCode(); }
    int critical() { return ris.markRisCritical(exam, new MedTechController.RisCriticalReq("模拟危急征象")).getCode(); }
    int refund() { charges.refund(charge, cashier.id()); return 0; }
    int operation(String op) {
        return switch (op) { case "arrive" -> arrive(); case "report" -> report(); case "verify" -> verify(); case "critical" -> critical(); default -> throw new IllegalArgumentException(op); };
    }
    int rejected(String op) { return List.of("arrive", "report").contains(op) ? 9944 : 9945; }
    void reportedBy(Staff actor) {
        jdbc.update("update ris_exam set status='REPORTED',findings='原所见',impression='原结论',reporter_id=?,reported_at=now() where id=?", actor.id(), exam);
    }
    int attempt(IntSupplier operation) {
        try { return operation.getAsInt(); }
        catch (HipBizException e) { return e.code; }
        catch (AccessDeniedException e) { return 1005; }
        catch (org.springframework.security.authentication.AuthenticationCredentialsNotFoundException e) { return 1005; }
    }
    TransactionTemplate transaction() { var tx = new TransactionTemplate(manager); tx.setTimeout(20); return tx; }
    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, Staff actor, IntSupplier operation) {
        return pool.submit(() -> {
            authenticate(actor);
            try { return attempt(() -> transaction().execute(tx -> { pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return operation.getAsInt(); })); }
            finally { SecurityContextHolder.clearContext(); }
        });
    }
    int result(Future<Integer> future) throws Exception {
        try { return future.get(20, TimeUnit.SECONDS); }
        catch (ExecutionException e) { return fail("Unexpected runtime/SQL failure instead of a readable business outcome", e.getCause()); }
    }
    void waiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')", Boolean.class, pid.get()))) return;
            if (future.isDone()) fail("Operation completed without the required PostgreSQL Lock wait");
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Expected a real PostgreSQL Lock wait");
    }
    void await(CountDownLatch latch) {
        try { assertTrue(latch.await(12, TimeUnit.SECONDS), "Worker must reach its coordinated point"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    void close(ExecutorService pool) throws Exception { pool.shutdown(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Workers finish before cleanup"); }
    void lockOrder() { assertEquals(List.of(order), jdbc.queryForList("select id from outp_order where id=? for update", Long.class, order)); }
    void lockExam() { assertEquals(List.of(exam), jdbc.queryForList("select id from ris_exam where id=? for update", Long.class, exam)); }
    void assertWorkerOwnsStrongOrderLock() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("select id from outp_order where id=? for no key update nowait")) {
                statement.setLong(1, order);
                try (var ignored = statement.executeQuery()) { fail("Worker must own O before waiting on E; a later FK KEY SHARE is insufficient"); }
            } catch (java.sql.SQLException expected) { assertEquals("55P03", expected.getSQLState()); }
            finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
            return null;
        });
    }
    Object snapshot() {
        return List.of(jdbc.queryForList("select * from outp_order where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id", registration),
                jdbc.queryForList("select * from ris_exam where order_id=? order by id", order),
                jdbc.queryForList("select * from outp_critical_alert where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_registration where id=?", registration));
    }
    void assertLedger(String source, String billStatus, String examStatus, Staff author, Staff verifier, int alerts) {
        var row = jdbc.queryForMap("select status,charge_id,amount,qty,doctor_id,review_status,review_note,reviewer_id,remark,clinical_summary from outp_order where id=?", order);
        assertEquals(source, row.get("status")); assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) row.get("amount")));
        assertEquals(1, row.get("qty")); assertEquals(reporter.id(), row.get("doctor_id"));
        assertEquals("APPROVED", row.get("review_status")); assertEquals("原审方", row.get("review_note")); assertEquals(reporter.id(), row.get("reviewer_id"));
        assertEquals("原备注", row.get("remark")); assertEquals("原摘要", row.get("clinical_summary"));
        var bill = jdbc.queryForMap("select status,total_amount,pay_method,refunded_at,refund_by,cashier_id from outp_charge where id=?", charge);
        assertEquals(billStatus, bill.get("status")); assertEquals(cashier.id(), bill.get("cashier_id")); assertEquals("CASH", bill.get("pay_method"));
        assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) bill.get("total_amount")));
        if ("REFUNDED".equals(billStatus)) { assertNull(row.get("charge_id")); assertNotNull(bill.get("refunded_at")); assertEquals(cashier.id(), bill.get("refund_by")); }
        else { assertEquals(charge, row.get("charge_id")); assertNull(bill.get("refunded_at")); assertNull(bill.get("refund_by")); }
        var e = jdbc.queryForMap("select status,reporter_id,reported_at,verifier_id,verified_at,critical_flag,critical_note,arrived_at from ris_exam where id=?", exam);
        assertEquals(examStatus, e.get("status")); assertEquals(author == null ? null : author.id(), e.get("reporter_id")); assertEquals(verifier == null ? null : verifier.id(), e.get("verifier_id"));
        if (author == null) assertNull(e.get("reported_at")); else assertNotNull(e.get("reported_at"));
        if (verifier == null) assertNull(e.get("verified_at")); else assertNotNull(e.get("verified_at"));
        assertEquals(alerts > 0, e.get("critical_flag"));
        if (alerts == 0) assertNull(e.get("critical_note")); else assertEquals("模拟危急征象", e.get("critical_note"));
        assertEquals(alerts, jdbc.queryForObject("select count(*) from outp_critical_alert where order_id=?", Integer.class, order));
        if (alerts > 0) {
            var alert = jdbc.queryForMap("select source,notify_to_user_id,notified_at,deadline_at,status,content from outp_critical_alert where order_id=?", order);
            assertEquals("RIS", alert.get("source")); assertEquals(reporter.id(), alert.get("notify_to_user_id")); assertEquals("NEW", alert.get("status"));
            assertNotNull(alert.get("notified_at")); assertNotNull(alert.get("deadline_at")); assertTrue(alert.get("content").toString().contains("模拟危急征象"));
        }
        assertEquals(1, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(1, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        transaction().executeWithoutResult(tx -> {
            if (registration != null) {
                jdbc.update("delete from outp_critical_alert where registration_id=?", registration);
                jdbc.update("delete from ris_exam where order_id in(select id from outp_order where registration_id=?)", registration);
                jdbc.update("delete from outp_refund_approval where charge_id in(select id from outp_charge where registration_id=?)", registration);
                jdbc.update("delete from outp_order where registration_id=?", registration); jdbc.update("delete from outp_charge where registration_id=?", registration);
                jdbc.update("delete from outp_registration where id=?", registration);
            }
            if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
            if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
            for (var employee : employees) { jdbc.update("delete from sys_user_role where user_id=?", employee.id()); jdbc.update("delete from sys_user where id=?", employee.id()); }
            if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "TECHNICIAN", "DOCTOR_OUTP", "NURSE"})
    void fourDutiesRetainArrivalDraftCriticalAndIndependentVerifyAcrossYesterdayOtherDoctorsVisit(String role) {
        Staff actor = employee("role" + role, role); authenticate(actor);
        assertEquals(0, arrive()); assertNotNull(jdbc.queryForObject("select arrived_at from ris_exam where id=?", java.sql.Timestamp.class, exam));
        assertEquals(0, report()); assertLedger("CHARGED", "PAID", "REPORTED", actor, null, 0);
        assertEquals(0, critical()); assertLedger("CHARGED", "PAID", "REPORTED", actor, null, 1);
        authenticate(reporter); assertEquals(0, verify()); assertLedger("EXECUTED", "PAID", "VERIFIED", actor, reporter, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REGISTERED", "ARRIVED", "REPORTED"})
    void anotherDutyCanWriteOrRewriteAndBecomesTheCurrentAuthor(String state) {
        if (state.equals("REPORTED")) reportedBy(reporter); else jdbc.update("update ris_exam set status=? where id=?", state, exam);
        assertEquals(0, report()); assertLedger("CHARGED", "PAID", "REPORTED", tech, null, 0);
        Object before = snapshot(); assertEquals(9947, verify()); assertEquals(before, snapshot());
        authenticate(reporter); assertEquals(0, verify()); assertLedger("EXECUTED", "PAID", "VERIFIED", tech, reporter, 0);
    }

    @Test
    void alreadyExecutedSourceRetainsReportThenIndependentVerifyAndCriticalCompatibility() {
        jdbc.update("update outp_order set status='EXECUTED' where id=?", order);
        assertEquals(0, arrive()); assertEquals(0, report()); authenticate(reporter); assertEquals(0, verify()); assertEquals(0, critical());
        assertLedger("EXECUTED", "PAID", "VERIFIED", tech, reporter, 1);
    }

    @ParameterizedTest
    @CsvSource({"EXAM,CREATED", "EXAM,CANCELLED", "EXAM,DISPENSED", "LAB,CHARGED", "TREAT,CHARGED", "DRUG,CHARGED"})
    void everyWriteRejectsActualIneligibleSourceWithoutChangingAnyBusinessRow(String type, String status) {
        jdbc.update("update outp_order set order_type=?,status=? where id=?", type, status, order);
        for (String op : List.of("arrive", "report", "verify", "critical")) {
            jdbc.update("update ris_exam set status='REGISTERED',reporter_id=null,reported_at=null where id=?", exam);
            if (List.of("verify", "critical").contains(op)) reportedBy(reporter);
            Object before = snapshot(); assertEquals(rejected(op), attempt(() -> operation(op)), op); assertEquals(before, snapshot(), op);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "missing", "wrong-role", "none"})
    void everyWriteRequiresAnEnabledExistingEmployeeWithAnExistingClinicalDuty(String kind) {
        reportedBy(reporter);
        switch (kind) {
            case "disabled" -> jdbc.update("update sys_user set enabled=false where id=?", tech.id());
            case "missing" -> SecurityContextHolder.getContext().setAuthentication(token(tag + "ghost", "TECHNICIAN"));
            case "wrong-role" -> authenticate(cashier);
            case "none" -> authenticate(null);
            default -> fail("Unknown fixture kind");
        }
        Object before = snapshot();
        for (String op : List.of("arrive", "report", "verify", "critical")) { assertEquals(1005, attempt(() -> operation(op)), op); assertEquals(before, snapshot(), op); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"report", "verify"})
    void explicitAuthenticationCannotAttributeAnotherEmployeeToTheCurrentRequest(String op) {
        reportedBy(reporter); Authentication supplied = token(reporter.name(), reporter.role()); Object before = snapshot();
        int code = attempt(() -> op.equals("report") ? ris.writeReport(exam, new MedTechController.RisReportReq("错人", "错人"), supplied).getCode() : ris.verifyReport(exam, supplied).getCode());
        assertEquals(1005, code); assertEquals(before, snapshot());
    }

    @Test
    void sourceAndReportStateAreCheckedBeforeSelfReviewErrorAndSelfReviewHasZeroWrites() {
        reportedBy(tech); Object valid = snapshot(); assertEquals(9947, verify()); assertEquals(valid, snapshot());
        jdbc.update("update outp_order set status='CREATED' where id=?", order); Object invalid = snapshot(); assertEquals(9945, verify()); assertEquals(invalid, snapshot());
    }

    @Test
    void reportedRowWithoutAnAuthorStillCannotBeVerifiedAndWritesNothing() {
        jdbc.update("update ris_exam set status='REPORTED',findings='无作者旧稿',impression='模拟' where id=?", exam);
        Object before = snapshot(); assertEquals(9945, verify()); assertEquals(before, snapshot());
    }

    @Test
    void originalStatusAndCriticalErrorsRemainReadableAndWriteNothing() {
        Object initial = snapshot(); assertEquals(9945, verify()); assertEquals(9945, critical()); assertEquals(initial, snapshot());
        assertEquals(9948, ris.markRisCritical(exam, new MedTechController.RisCriticalReq(" ")).getCode()); assertEquals(initial, snapshot());
        assertEquals(0, arrive()); Object arrived = snapshot(); assertEquals(9944, arrive()); assertEquals(arrived, snapshot());
        reportedBy(reporter); assertEquals(0, critical()); Object marked = snapshot(); assertEquals(9949, critical()); assertEquals(marked, snapshot());
        assertEquals(0, verify()); Object verified = snapshot(); assertEquals(9944, report()); assertEquals(9945, verify()); assertEquals(verified, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"arrive", "report", "verify", "critical"})
    void missingExamHasTheExistingObjectErrorAndZeroWrites(String op) {
        Long old = exam; exam = -1L; Object before = snapshot();
        assertEquals(rejected(op), attempt(() -> operation(op))); assertEquals(before, snapshot()); exam = old;
    }

    @ParameterizedTest
    @ValueSource(strings = {"arrive", "report", "verify", "critical"})
    void committedRefundMakesLateWritesRejectWithoutLosingHistoricalReport(String op) {
        if (List.of("verify", "critical").contains(op)) reportedBy(reporter);
        authenticate(cashier); assertEquals(0, refund()); authenticate(tech); Object after = snapshot();
        assertEquals(rejected(op), attempt(() -> operation(op))); assertEquals(after, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"arrive", "report", "verify", "critical"})
    void refundOwnerMakesActualWaitingWriterLoseWithItsObjectCodeAndNoLateWrite(String op) throws Exception {
        if (List.of("verify", "critical").contains(op)) reportedBy(reporter);
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        Object[] refunded = new Object[1];
        try {
            transaction().executeWithoutResult(tx -> {
                authenticate(cashier); assertEquals(0, refund()); refunded[0] = snapshot();
                var future = worker(pool, pid, tech, () -> operation(op)); futures.add(future); waiting(pid, future);
            });
            assertEquals(rejected(op), result(futures.getFirst())); assertEquals(refunded[0], snapshot());
        } finally { close(pool); }
    }

    @Test
    void verifiedOwnerMakesWaitingRefundLose5005AndPreservesBothSignaturesAndPaidLedger() throws Exception {
        reportedBy(reporter); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> { assertEquals(0, verify()); var future = worker(pool, pid, cashier, this::refund); futures.add(future); waiting(pid, future); });
            assertEquals(5005, result(futures.getFirst())); assertLedger("EXECUTED", "PAID", "VERIFIED", reporter, tech, 0);
        } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"arrive", "report", "critical"})
    void nonExecutionWritesHoldSourceUntilCommitButDoNotInventARefundProhibition(String op) throws Exception {
        if (op.equals("critical")) reportedBy(reporter);
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> { assertEquals(0, operation(op)); var future = worker(pool, pid, cashier, this::refund); futures.add(future); waiting(pid, future); });
            assertEquals(0, result(futures.getFirst()));
            assertLedger("CREATED", "REFUNDED", op.equals("arrive") ? "ARRIVED" : "REPORTED", op.equals("report") ? tech : op.equals("critical") ? reporter : null, null, op.equals("critical") ? 1 : 0);
        } finally { close(pool); }
    }

    @Test
    void twoActualWaitingVerifiersHaveExactlyOneSuccessAndOne9945() throws Exception {
        reportedBy(reporter); Staff second = employee("second", "NURSE");
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> { assertEquals(0, verify()); var future = worker(pool, pid, second, this::verify); futures.add(future); waiting(pid, future); });
            assertEquals(9945, result(futures.getFirst())); assertLedger("EXECUTED", "PAID", "VERIFIED", reporter, tech, 0);
        } finally { close(pool); }
    }

    @Test
    void twoActualWaitingCriticalMarkersHaveExactlyOneSuccessOne9949AndOneAlert() throws Exception {
        reportedBy(reporter); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> { assertEquals(0, critical()); var future = worker(pool, pid, reporter, this::critical); futures.add(future); waiting(pid, future); });
            assertEquals(9949, result(futures.getFirst())); assertLedger("CHARGED", "PAID", "REPORTED", reporter, null, 1);
        } finally { close(pool); }
    }

    @Test
    void rewriteOwnerMakesWaitingNewAuthorSelfVerificationLose9947() throws Exception {
        reportedBy(reporter); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> { assertEquals(0, report()); var future = worker(pool, pid, tech, this::verify); futures.add(future); waiting(pid, future); });
            assertEquals(9947, result(futures.getFirst())); assertLedger("CHARGED", "PAID", "REPORTED", tech, null, 0);
        } finally { close(pool); }
    }

    @Test
    void verifyOwnerMakesWaitingDraftRewriteLose9944WithoutChangingVerifiedAuthor() throws Exception {
        reportedBy(reporter); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> { assertEquals(0, verify()); var future = worker(pool, pid, reporter, this::report); futures.add(future); waiting(pid, future); });
            assertEquals(9944, result(futures.getFirst())); assertLedger("EXECUTED", "PAID", "VERIFIED", reporter, tech, 0);
        } finally { close(pool); }
    }

    @ParameterizedTest
    @CsvSource({"arrive,O", "report,O", "verify,O", "critical,O", "arrive,E", "report,E", "verify,E", "critical,E"})
    void everyWriterRechecksEmployeeAfterActualSourceOrExamWait(String op, String locked) throws Exception {
        if (List.of("verify", "critical").contains(op)) reportedBy(reporter);
        Object before = snapshot(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                if (locked.equals("O")) lockOrder(); else lockExam();
                var future = worker(pool, pid, tech, () -> operation(op)); futures.add(future); waiting(pid, future);
                jdbc.update("update sys_user set enabled=false where id=?", tech.id());
            });
            assertEquals(1005, result(futures.getFirst())); assertEquals(before, snapshot());
        } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"arrive", "report", "verify", "critical"})
    void everyWriterOwnsStrongSourceLockBeforeWaitingForExam(String op) throws Exception {
        if (List.of("verify", "critical").contains(op)) reportedBy(reporter);
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                lockExam(); var future = worker(pool, pid, tech, () -> operation(op)); futures.add(future); waiting(pid, future); assertWorkerOwnsStrongOrderLock();
            });
            assertEquals(0, result(futures.getFirst()));
        } finally { close(pool); }
    }

    @Test
    void rolledBackVerifyOwnerReleasesRefundAndLeavesNoSignatureOrExecution() throws Exception {
        reportedBy(reporter); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> { assertEquals(0, verify()); var future = worker(pool, pid, cashier, this::refund); futures.add(future); waiting(pid, future); tx.setRollbackOnly(); });
            assertEquals(0, result(futures.getFirst())); assertLedger("CREATED", "REFUNDED", "REPORTED", reporter, null, 0);
        } finally { close(pool); }
    }

    @Test
    void managedCachedOrderCannotAllowVerificationAfterCommittedRefund() throws Exception {
        reportedBy(reporter); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, tech, () -> { assertEquals("CHARGED", orders.findById(order).orElseThrow().getStatus()); loaded.countDown(); await(proceed); return verify(); });
            await(loaded); authenticate(cashier); assertEquals(0, refund()); Object refunded = snapshot(); proceed.countDown(); assertEquals(9945, result(future)); assertEquals(refunded, snapshot());
        } finally { proceed.countDown(); close(pool); }
    }

    @Test
    void managedCachedExamTypeCannotPermitVerificationAfterCommittedSourceTypeChange() throws Exception {
        reportedBy(reporter); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, tech, () -> { assertEquals("EXAM", orders.findById(order).orElseThrow().getOrderType()); loaded.countDown(); await(proceed); return verify(); });
            await(loaded); jdbc.update("update outp_order set order_type='LAB' where id=?", order); Object changed = snapshot(); proceed.countDown(); assertEquals(9945, result(future)); assertEquals(changed, snapshot());
        } finally { proceed.countDown(); close(pool); }
    }

    @Test
    void managedCachedMetadataCannotOverwriteNewerFinanceOrClinicalFieldsDuringVerify() throws Exception {
        reportedBy(reporter); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, tech, () -> { orders.findById(order).orElseThrow(); loaded.countDown(); await(proceed); return verify(); });
            await(loaded); jdbc.update("update outp_order set amount=8.25,unit_price=8.25,remark='新备注',clinical_summary='新摘要',review_status='REJECTED',review_note='新审方' where id=?", order);
            var expected = jdbc.queryForMap("select amount,unit_price,remark,clinical_summary,review_status,review_note,doctor_id,reviewer_id,charge_id from outp_order where id=?", order);
            proceed.countDown(); assertEquals(0, result(future)); assertEquals(expected, jdbc.queryForMap("select amount,unit_price,remark,clinical_summary,review_status,review_note,doctor_id,reviewer_id,charge_id from outp_order where id=?", order));
            assertEquals("EXECUTED", jdbc.queryForObject("select status from outp_order where id=?", String.class, order));
        } finally { proceed.countDown(); close(pool); }
    }

    @Test
    void pendingManagedMetadataIsFlushedBeforeVerifyAndDoesNotResurrectChargedStatus() {
        reportedBy(reporter);
        transaction().executeWithoutResult(tx -> {
            var entity = orders.findById(order).orElseThrow(); entity.setRemark("组合事务备注"); entity.setClinicalSummary("组合事务摘要");
            assertEquals(0, verify()); em.flush();
        });
        assertEquals("EXECUTED", jdbc.queryForObject("select status from outp_order where id=?", String.class, order));
        assertEquals("组合事务备注", jdbc.queryForObject("select remark from outp_order where id=?", String.class, order));
        assertEquals("组合事务摘要", jdbc.queryForObject("select clinical_summary from outp_order where id=?", String.class, order));
    }

    @Test
    void pendingManagedIneligibleSourceIsFlushedBeforeWriterValidationAndRollsBackWithException() {
        reportedBy(reporter); Object before = snapshot();
        assertThrows(HipBizException.class, () -> transaction().executeWithoutResult(tx -> {
            orders.findById(order).orElseThrow().setOrderType("LAB");
            int code = verify(); if (code != 0) throw new HipBizException(code, "rollback composed source change");
        }));
        assertEquals(before, snapshot()); assertEquals(0, verify());
    }

    @Test
    void failedReportStatementRollsBackAuthorTimeAndContentAndCanRetry() {
        Object before = snapshot();
        assertThrows(RuntimeException.class, () -> ris.writeReport(exam, new MedTechController.RisReportReq("X".repeat(2001), "模拟结论"), auth()));
        assertEquals(before, snapshot()); assertEquals(0, report()); assertLedger("CHARGED", "PAID", "REPORTED", tech, null, 0);
    }

    @Test
    void failedCriticalAlertInsertRollsBackFlagAndNoteThenCanRetryWithOneAlert() {
        reportedBy(reporter); Object before = snapshot();
        // note fits ris_exam(512), while the prefixed alert content overflows its own 512 limit.
        assertThrows(RuntimeException.class, () -> ris.markRisCritical(exam, new MedTechController.RisCriticalReq("危".repeat(512))));
        assertEquals(before, snapshot()); assertEquals(0, critical()); assertLedger("CHARGED", "PAID", "REPORTED", reporter, null, 1);
    }
}
