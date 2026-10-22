package cn.hip.server;

import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.LabResultListener;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.integration.event.LabResultReceivedEvent;
import cn.hip.platform.integration.service.OruProcessingService;
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
 * Committed PostgreSQL transactions, never an enclosing @Transactional test. Lock-order tests
 * observe pg_stat_activity Lock waits before releasing the owner. Contexts below exercise the
 * service/controller boundary with real employees; they do not claim to parse real JWTs.
 * Managed-cache tests deliberately compose calls in one transaction, not an HTTP metadata API.
 */
@SpringBootTest
class V113LisRefundRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired LabResultListener listener;
    @Autowired ChargeService charges;
    @Autowired MedTechController lis;
    @Autowired OruProcessingService oru;
    @Autowired OutpOrderRepository orders;
    @Autowired EntityManager em;

    record Staff(Long id, String name, String role) {}
    final List<Staff> employees = new ArrayList<>();
    Staff tech, other, cashier;
    Long dept, patient, schedule, registration, order, charge, sample;
    String tag, barcode;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext();
        tag = "L113" + Long.toUnsignedString(System.nanoTime(), 36);
        barcode = tag + "BC";
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag, tag);
        tech = employee("t", "TECHNICIAN"); other = employee("d", "DOCTOR_OUTP"); cashier = employee("c", "CASHIER");
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",
                Long.class, tag, tag);
        String day = BusinessDates.today().minusDays(1).toString();
        schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee)
                values (?,?::date,5,1,1,0) returning id
                """, Long.class, dept, day);
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status)
                values (1,?,?,?,?,?::date,0,'VISITED') returning id
                """, Long.class, patient, schedule, dept, other.id(), day);
        order = newOrder(tag, "LAB", "CREATED");
        authenticate(cashier);
        charge = charges.settle(registration, "CASH", cashier.id()).getId();
        sample = jdbc.queryForObject("""
                insert into lis_sample(order_id,barcode,status,received_at)
                values (?,?,'RECEIVED',now()) returning id
                """, Long.class, order, barcode);
        authenticate(tech);
    }

    Staff employee(String suffix, String role) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, name, name, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        var actor = new Staff(id, name, role); employees.add(actor); return actor;
    }

    Long newOrder(String group, String type, String status) {
        Long id = jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,
                                       unit,qty,unit_price,amount,status,doctor_id,review_status,review_note,reviewer_id,
                                       remark,clinical_summary)
                values (?,?,?,0,?,'LIS退款夹具','次',1,7.50,7.50,?,?,'APPROVED','原审方',?,'原备注','原摘要') returning id
                """, Long.class, registration, group, type, tag, status, other.id(), other.id());
        if ("CHARGED".equals(status) && charge != null) {
            jdbc.update("update outp_order set charge_id=? where id=?", charge, id);
            jdbc.update("update outp_charge set total_amount=total_amount+7.50 where id=?", charge);
        }
        return id;
    }

    Authentication token(String name, String role) {
        return new UsernamePasswordAuthenticationToken(name, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    Authentication authenticate(Staff actor) {
        if (actor == null) { SecurityContextHolder.clearContext(); return null; }
        Authentication auth = token(actor.name(), actor.role());
        SecurityContextHolder.getContext().setAuthentication(auth); return auth;
    }
    Authentication auth() { return SecurityContextHolder.getContext().getAuthentication(); }
    List<LabResultReceivedEvent.Item> normal() {
        return List.of(new LabResultReceivedEvent.Item("HGB", "血红蛋白", "140", "g/L", "130-175", "N"));
    }
    List<LabResultReceivedEvent.Item> critical() {
        return List.of(new LabResultReceivedEvent.Item("HGB", "血红蛋白", "40", "g/L", "130-175", "LL"));
    }
    int receive(String group, List<LabResultReceivedEvent.Item> values) {
        listener.onLabResult(new LabResultReceivedEvent(group, values)); return 0;
    }
    int receive() { return receive(tag, critical()); }
    int publish(List<LabResultReceivedEvent.Item> values, Authentication supplied) {
        return lis.publish(barcode, new MedTechController.PublishReq(values.stream().map(x ->
                new MedTechController.ManualResult(x.code(), x.name(), x.value(), x.unit(), x.refRange(), x.abnormalFlag())).toList()), supplied).getCode();
    }
    int publish() { return publish(critical(), auth()); }
    int refund() { charges.refund(charge, cashier.id()); return 0; }
    int attempt(IntSupplier operation) {
        try { return operation.getAsInt(); }
        catch (HipBizException e) { return e.code; }
        catch (AccessDeniedException e) { return 1005; }
        // The machine listener has always rejected an unmatched/unpayable group with this exception.
        catch (IllegalStateException e) { return 7000; }
    }
    TransactionTemplate transaction() {
        var tx = new TransactionTemplate(manager); tx.setTimeout(20); return tx;
    }
    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, Staff actor, IntSupplier operation) {
        return pool.submit(() -> {
            authenticate(actor);
            try {
                return attempt(() -> transaction().execute(tx -> {
                    pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return operation.getAsInt();
                }));
            } finally { SecurityContextHolder.clearContext(); }
        });
    }
    int result(Future<Integer> future) throws Exception {
        try { return future.get(20, TimeUnit.SECONDS); }
        catch (ExecutionException e) { return fail("Unexpected runtime/SQL failure instead of a readable business outcome", e.getCause()); }
    }
    void waiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",
                    Boolean.class, pid.get()))) return;
            if (future.isDone()) fail("Operation completed without the required PostgreSQL Lock wait");
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Expected a real PostgreSQL Lock wait");
    }
    void await(CountDownLatch latch) {
        try { assertTrue(latch.await(12, TimeUnit.SECONDS), "Worker must reach its coordinated point"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    void close(ExecutorService pool) throws Exception {
        pool.shutdown(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Workers finish before cleanup");
    }
    void lockOrder(Long id) {
        assertEquals(List.of(id), jdbc.queryForList("select id from outp_order where id=? for update", Long.class, id));
    }
    void lockSample() {
        assertEquals(List.of(sample), jdbc.queryForList("select id from lis_sample where id=? for update", Long.class, sample));
    }
    void assertWorkerOwnsStrongOrderLock(Long id) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("select id from outp_order where id=? for no key update nowait")) {
                statement.setLong(1, id);
                try (var ignored = statement.executeQuery()) {
                    fail("Worker must own the preceding source-order lock; a result FK KEY SHARE is insufficient");
                }
            } catch (java.sql.SQLException expected) {
                assertEquals("55P03", expected.getSQLState(), "Only the intentional NOWAIT miss is expected");
            } finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
            return null;
        });
    }
    Object snapshot() {
        return List.of(
                jdbc.queryForList("select * from outp_order where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id", registration),
                jdbc.queryForList("select s.* from lis_sample s join outp_order o on o.id=s.order_id where o.registration_id=? order by s.id", registration),
                jdbc.queryForList("select r.* from outp_lab_result r join outp_order o on o.id=r.order_id where o.registration_id=? order by r.id", registration),
                jdbc.queryForList("select * from outp_critical_alert where registration_id=? order by id", registration));
    }
    void assertLedger(String status, String billStatus, int results, int alerts, boolean published) {
        var row = jdbc.queryForMap("select status,charge_id,amount,qty,doctor_id,review_status,review_note,reviewer_id,remark,clinical_summary from outp_order where id=?", order);
        assertEquals(status, row.get("status"));
        assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) row.get("amount")));
        assertEquals(1, row.get("qty")); assertEquals(other.id(), row.get("doctor_id"));
        assertEquals("APPROVED", row.get("review_status")); assertEquals("原审方", row.get("review_note"));
        assertEquals(other.id(), row.get("reviewer_id")); assertEquals("原备注", row.get("remark"));
        assertEquals("原摘要", row.get("clinical_summary"));
        var bill = jdbc.queryForMap("select status,total_amount,refunded_at,refund_by,cashier_id from outp_charge where id=?", charge);
        assertEquals(billStatus, bill.get("status")); assertEquals(cashier.id(), bill.get("cashier_id"));
        if ("REFUNDED".equals(billStatus)) {
            assertNull(row.get("charge_id")); assertNotNull(bill.get("refunded_at"));
            assertEquals(cashier.id(), bill.get("refund_by"));
        } else {
            assertEquals(charge, row.get("charge_id")); assertNull(bill.get("refunded_at")); assertNull(bill.get("refund_by"));
        }
        assertEquals(results, jdbc.queryForObject("select count(*) from outp_lab_result where order_id=?", Integer.class, order));
        assertEquals(alerts, jdbc.queryForObject("select count(*) from outp_critical_alert where order_id=?", Integer.class, order));
        var s = jdbc.queryForMap("select status,verifier_id,published_at from lis_sample where id=?", sample);
        assertEquals(published ? "PUBLISHED" : "RECEIVED", s.get("status"));
        if (published) { assertEquals(tech.id(), s.get("verifier_id")); assertNotNull(s.get("published_at")); }
        else { assertNull(s.get("verifier_id")); assertNull(s.get("published_at")); }
        assertEquals(1, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(1, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        transaction().executeWithoutResult(tx -> {
            if (registration != null) {
                jdbc.update("delete from outp_critical_alert where registration_id=?", registration);
                jdbc.update("delete from outp_lab_result where order_id in(select id from outp_order where registration_id=?)", registration);
                jdbc.update("delete from lis_sample where order_id in(select id from outp_order where registration_id=?)", registration);
                jdbc.update("delete from outp_refund_approval where charge_id in(select id from outp_charge where registration_id=?)", registration);
                jdbc.update("delete from outp_order where registration_id=?", registration);
                jdbc.update("delete from outp_charge where registration_id=?", registration);
                jdbc.update("delete from outp_registration where id=?", registration);
            }
            if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
            if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
            for (var employee : employees) {
                jdbc.update("delete from sys_user_role where user_id=?", employee.id());
                jdbc.update("delete from sys_user where id=?", employee.id());
            }
            if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
            if (tag != null) jdbc.update("delete from int_message_log where ref_no like ?", tag + "%");
        });
    }

    @Test
    void machineEventWithoutHumanLoginStillWritesResultsAndCriticalAlert() {
        authenticate(null); assertEquals(0, receive()); assertLedger("EXECUTED", "PAID", 1, 1, false);
        assertEquals(other.id(), jdbc.queryForObject("select notify_to_user_id from outp_critical_alert where order_id=?", Long.class, order));
        assertEquals("LAB", jdbc.queryForObject("select source from outp_critical_alert where order_id=?", String.class, order));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "TECHNICIAN", "DOCTOR_OUTP", "NURSE"})
    void allExistingManualPublishDutiesRemainUsableAcrossHistoricalOtherDoctorVisit(String role) {
        tech = employee("role" + role, role); authenticate(tech);
        assertEquals(0, publish()); assertLedger("EXECUTED", "PAID", 1, 1, true);
    }

    @Test
    void executedOrderAllowsAnotherMachineResultUnderExistingAppendSemantics() {
        authenticate(null); assertEquals(0, receive()); assertEquals(0, receive());
        assertLedger("EXECUTED", "PAID", 2, 2, false);
    }

    @Test
    void sameGroupFansOutToEveryEligibleLabAndSkipsOtherTypesAndUnpaidOrders() {
        Long sibling = newOrder(tag, "LAB", "CHARGED");
        Long unpaid = newOrder(tag, "LAB", "CREATED"); Long exam = newOrder(tag, "EXAM", "CHARGED");
        authenticate(null); assertEquals(0, receive());
        assertLedger("EXECUTED", "PAID", 1, 1, false);
        assertEquals("EXECUTED", jdbc.queryForObject("select status from outp_order where id=?", String.class, sibling));
        assertEquals(List.of(order, sibling), jdbc.queryForList("select distinct order_id from outp_lab_result where order_id in(?,?,?,?) order by order_id", Long.class, order, sibling, unpaid, exam));
    }

    @ParameterizedTest
    @CsvSource({"LAB,CREATED", "LAB,CANCELLED", "LAB,DISPENSED", "EXAM,CHARGED", "TREAT,CHARGED", "DRUG,CHARGED"})
    void ineligibleMachineSourceRejectsWithoutAnyBusinessWrite(String type, String status) {
        jdbc.update("update outp_order set order_type=?,status=? where id=?", type, status, order);
        Object before = snapshot(); authenticate(null);
        assertEquals(7000, attempt(this::receive)); assertEquals(before, snapshot());
    }

    @Test
    void missingMachineGroupRejectsWithoutAnyBusinessWrite() {
        Object before = snapshot(); authenticate(null);
        assertEquals(7000, attempt(() -> receive(tag + "missing", critical()))); assertEquals(before, snapshot());
    }

    @Test
    void refundFirstPreventsMachineResultAndDoesNotRestoreOldChargeLink() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                authenticate(cashier); assertEquals(0, refund());
                var future = worker(pool, pid, null, this::receive); futures.add(future); waiting(pid, future);
            });
            assertEquals(7000, result(futures.getFirst())); assertLedger("CREATED", "REFUNDED", 0, 0, false);
        } finally { close(pool); }
    }

    @Test
    void machineFirstMakesWaitingRefundLoseWith5005WithoutChangingBill() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                authenticate(null); assertEquals(0, receive());
                var future = worker(pool, pid, cashier, this::refund); futures.add(future); waiting(pid, future);
            });
            assertEquals(5005, result(futures.getFirst())); assertLedger("EXECUTED", "PAID", 1, 1, false);
        } finally { close(pool); }
    }

    @Test
    void refundFirstMakesWaitingManualPublishLoseWith9942AndUnpublishedSample() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                authenticate(cashier); assertEquals(0, refund());
                var future = worker(pool, pid, tech, this::publish); futures.add(future); waiting(pid, future);
            });
            assertEquals(9942, result(futures.getFirst())); assertLedger("CREATED", "REFUNDED", 0, 0, false);
        } finally { close(pool); }
    }

    @Test
    void manualPublishFirstMakesWaitingRefundLoseWith5005AndKeepsVerifier() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                authenticate(tech); assertEquals(0, publish());
                var future = worker(pool, pid, cashier, this::refund); futures.add(future); waiting(pid, future);
            });
            assertEquals(5005, result(futures.getFirst())); assertLedger("EXECUTED", "PAID", 1, 1, true);
        } finally { close(pool); }
    }

    @Test
    void concurrentManualPublishHasExactlyOneWinnerAndOne9942() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, publish());
                var future = worker(pool, pid, tech, this::publish); futures.add(future); waiting(pid, future);
            });
            assertEquals(9942, result(futures.getFirst())); assertLedger("EXECUTED", "PAID", 1, 1, true);
            Object before = snapshot(); assertEquals(9942, publish()); assertEquals(before, snapshot());
        } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "CANCELLED"})
    void selectedManualSourceCannotBorrowValidSiblingOfSameGroup(String status) {
        newOrder(tag, "LAB", "CHARGED"); jdbc.update("update outp_order set status=? where id=?", status, order);
        Object before = snapshot(); assertEquals(9942, attempt(this::publish)); assertEquals(before, snapshot());
    }

    @Test
    void selectedManualSourceMustActuallyBeLabEvenWhenSameGroupHasLabSibling() {
        newOrder(tag, "LAB", "CHARGED"); jdbc.update("update outp_order set order_type='EXAM' where id=?", order);
        Object before = snapshot(); assertEquals(9942, attempt(this::publish)); assertEquals(before, snapshot());
    }

    @Test
    void emptyManualResultsRetain9943AndWriteNothing() {
        Object before = snapshot(); assertEquals(9943, publish(List.of(), auth())); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COLLECTED", "PUBLISHED"})
    void wrongSampleStatusRetains9942AndWritesNothing(String status) {
        jdbc.update("update lis_sample set status=? where id=?", status, sample);
        Object before = snapshot(); assertEquals(9942, publish()); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "missing", "mismatch", "wrong-role"})
    void manualPublishRequiresEnabledExistingAndConsistentCurrentEmployee(String kind) {
        Authentication supplied = auth();
        switch (kind) {
            case "disabled" -> jdbc.update("update sys_user set enabled=false where id=?", tech.id());
            case "missing" -> { supplied = token(tag + "ghost", "TECHNICIAN"); SecurityContextHolder.getContext().setAuthentication(supplied); }
            case "mismatch" -> supplied = token(other.name(), "DOCTOR_OUTP");
            case "wrong-role" -> { supplied = authenticate(cashier); }
            default -> fail("Unknown fixture kind");
        }
        final Authentication selected = supplied;
        Object before = snapshot(); assertEquals(1005, attempt(() -> publish(critical(), selected))); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"O", "S"})
    void manualPublishRechecksEmployeeAfterActualOrderOrSampleLockWait(String locked) throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                if (locked.equals("O")) lockOrder(order); else lockSample();
                var future = worker(pool, pid, tech, this::publish); futures.add(future); waiting(pid, future);
                jdbc.update("update sys_user set enabled=false where id=?", tech.id());
            });
            assertEquals(1005, result(futures.getFirst())); assertLedger("CHARGED", "PAID", 0, 0, false);
        } finally { close(pool); }
    }

    @Test
    void groupedMachineAcquiresLowerOrderBeforeWaitingForHigherOrder() throws Exception {
        Long sibling = newOrder(tag, "LAB", "CHARGED");
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                lockOrder(sibling);
                var future = worker(pool, pid, null, this::receive); futures.add(future); waiting(pid, future);
                assertWorkerOwnsStrongOrderLock(order);
            });
            assertEquals(0, result(futures.getFirst()));
            assertEquals(2, jdbc.queryForObject("select count(*) from outp_lab_result where order_id in(?,?)", Integer.class, order, sibling));
            assertEquals(2, jdbc.queryForObject("select count(*) from outp_critical_alert where order_id in(?,?)", Integer.class, order, sibling));
        } finally { close(pool); }
    }

    @Test
    void manualPublishOwnsEveryGroupOrderBeforeWaitingForSample() throws Exception {
        Long sibling = newOrder(tag, "LAB", "CHARGED");
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                lockSample();
                var future = worker(pool, pid, tech, this::publish); futures.add(future); waiting(pid, future);
                assertWorkerOwnsStrongOrderLock(order); assertWorkerOwnsStrongOrderLock(sibling);
            });
            assertEquals(0, result(futures.getFirst())); assertLedger("EXECUTED", "PAID", 1, 1, true);
        } finally { close(pool); }
    }

    @Test
    void rolledBackMachineOwnerReleasesSourceForWaitingRefundWithoutLeakingResult() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                authenticate(null); assertEquals(0, receive());
                var future = worker(pool, pid, cashier, this::refund); futures.add(future); waiting(pid, future);
                tx.setRollbackOnly();
            });
            assertEquals(0, result(futures.getFirst())); assertLedger("CREATED", "REFUNDED", 0, 0, false);
        } finally { close(pool); }
    }

    @Test
    void managedCachedOrderCannotResurrectACommittedRefund() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, null, () -> {
                assertEquals("CHARGED", orders.findById(order).orElseThrow().getStatus()); loaded.countDown(); await(proceed);
                return receive();
            });
            await(loaded); authenticate(cashier); assertEquals(0, refund()); Object afterRefund = snapshot(); proceed.countDown();
            assertEquals(7000, result(future)); assertEquals(afterRefund, snapshot()); assertLedger("CREATED", "REFUNDED", 0, 0, false);
        } finally { proceed.countDown(); close(pool); }
    }

    @Test
    void managedCachedTypeMustBeRefreshedBeforeMachineResult() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, null, () -> {
                assertEquals("LAB", orders.findById(order).orElseThrow().getOrderType()); loaded.countDown(); await(proceed);
                return receive();
            });
            await(loaded); jdbc.update("update outp_order set order_type='EXAM' where id=?", order);
            Object changed = snapshot(); proceed.countDown(); assertEquals(7000, result(future)); assertEquals(changed, snapshot());
        } finally { proceed.countDown(); close(pool); }
    }

    @Test
    void machineExecutionUpdatesOnlyStatusWithoutOverwritingNewerClinicalAndFinanceMetadata() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, null, () -> {
                orders.findById(order).orElseThrow(); loaded.countDown(); await(proceed); return receive();
            });
            await(loaded);
            jdbc.update("update outp_order set amount=8.25,unit_price=8.25,review_status='REJECTED',review_note='新审方',reviewer_id=?,doctor_id=?,remark='新备注',clinical_summary='新摘要' where id=?", tech.id(), tech.id(), order);
            var expected = jdbc.queryForMap("select amount,unit_price,review_status,review_note,reviewer_id,doctor_id,remark,clinical_summary,charge_id from outp_order where id=?", order);
            proceed.countDown(); assertEquals(0, result(future));
            assertEquals(expected, jdbc.queryForMap("select amount,unit_price,review_status,review_note,reviewer_id,doctor_id,remark,clinical_summary,charge_id from outp_order where id=?", order));
            assertEquals("EXECUTED", jdbc.queryForObject("select status from outp_order where id=?", String.class, order));
        } finally { proceed.countDown(); close(pool); }
    }

    @Test
    void listenerFlushesPendingManagedChangesBeforeTakingAndRefreshingOrderLock() {
        authenticate(null);
        transaction().executeWithoutResult(tx -> {
            var entity = orders.findById(order).orElseThrow(); entity.setRemark("本组合事务备注"); entity.setClinicalSummary("本组合事务摘要");
            assertEquals(0, receive(tag, normal())); em.flush();
        });
        assertEquals("EXECUTED", jdbc.queryForObject("select status from outp_order where id=?", String.class, order));
        assertEquals("本组合事务备注", jdbc.queryForObject("select remark from outp_order where id=?", String.class, order));
        assertEquals("本组合事务摘要", jdbc.queryForObject("select clinical_summary from outp_order where id=?", String.class, order));
    }

    @ParameterizedTest
    @ValueSource(strings = {"result", "alert"})
    void publishSqlFailureRollsBackVerifierTimestampStatusAndAllResultsThenRetryIsSafe(String failure) {
        List<LabResultReceivedEvent.Item> invalid;
        if (failure.equals("result")) invalid = List.of(normal().getFirst(),
                new LabResultReceivedEvent.Item("BAD", "合法名", "X".repeat(65), null, null, "N"));
        else invalid = List.of(1, 2, 3).stream().map(i ->
                new LabResultReceivedEvent.Item("LONG" + i, "危".repeat(128), "4".repeat(64), "u".repeat(16), "", "LL")).toList();
        Object before = snapshot(); assertThrows(RuntimeException.class, () -> publish(invalid, auth()));
        assertEquals(before, snapshot(), "Real transaction abort restores PUBLISHED claim, verifier/time and every previous result");
        assertEquals(0, publish()); assertLedger("EXECUTED", "PAID", 1, 1, true);
    }

    @Test
    void listenerResultFailureRollsBackEarlierRowsAndCanRetryIndependently() {
        authenticate(null); Object before = snapshot();
        assertThrows(RuntimeException.class, () -> receive(tag, List.of(normal().getFirst(),
                new LabResultReceivedEvent.Item("BAD", "合法名", "X".repeat(65), null, null, "N"))));
        assertEquals(before, snapshot()); assertEquals(0, receive()); assertLedger("EXECUTED", "PAID", 1, 1, false);
    }

    String message(String group, String code, String value) {
        return "OBR|1|" + group + "||检验\rOBX|1|NM|" + code + "^测试||" + value + "|g/L|130-175|N\r";
    }

    @Test
    void oruRetainsCommittedEarlierObrWhenLaterGroupFailsAndSkipsEmptyObr() {
        authenticate(null);
        String missing = tag + "missing";
        var response = oru.process("MSH|^~\\&|LIS|HOSP|HIP|HOSP|20261022000000||ORU^R01|" + tag + "|P|2.3\r"
                + "OBR|0|" + tag + "empty||空组\r" + message(tag, "A", "140") + message(missing, "B", "150"));
        assertFalse(response.ok()); assertEquals(7000, response.code());
        assertLedger("EXECUTED", "PAID", 1, 0, false);
        assertEquals("A", jdbc.queryForObject("select item_code from outp_lab_result where order_id=?", String.class, order));
        assertEquals(1, jdbc.queryForObject("select count(*) from int_message_log where ref_no=? and status='OK'", Integer.class, tag));
    }
}
