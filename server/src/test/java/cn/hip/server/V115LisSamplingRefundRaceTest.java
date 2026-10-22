package cn.hip.server;

import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
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
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
 * Real committed PostgreSQL controller/service compositions: no outer @Transactional test.
 * Every coordinated race observes a database Lock wait before releasing its owner. These
 * contexts exercise live employees at the controller boundary; JWT parsing is tested separately.
 * Cache/flush cases deliberately compose calls in one transaction and assert JDBC decisions,
 * without promising that a caller's previously managed entity is automatically refreshed.
 */
@SpringBootTest
class V115LisSamplingRefundRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ChargeService charges;
    @Autowired MedTechController lis;
    @Autowired OutpOrderRepository orders;
    @Autowired EntityManager em;
    @Autowired ConfigReader config;

    record Staff(Long id, String name, String role) {}
    final List<Staff> employees = new ArrayList<>();
    Staff tech, doctor, cashier;
    Long dept, patient, schedule, registration, order, charge;
    String tag, barcode;
    List<String> originalSubstitute;
    Object stockBefore;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext();
        tag = "L115" + Long.toUnsignedString(System.nanoTime(), 36); barcode = tag + "BC";
        originalSubstitute = jdbc.queryForList("select cfg_value from sys_config where cfg_key='lis_allow_substitute'", String.class);
        stockBefore = stock();
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag, tag);
        tech = employee("t", "TECHNICIAN"); doctor = employee("d", "DOCTOR_OUTP"); cashier = employee("c", "CASHIER");
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id", Long.class, tag, tag);
        String day = BusinessDates.today().minusDays(1).toString();
        schedule = jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,?::date,5,1,1,0) returning id", Long.class, dept, day);
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status)
                values (1,?,?,?,?,?::date,0,'VISITED') returning id
                """, Long.class, patient, schedule, dept, doctor.id(), day);
        order = jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,
                    unit,qty,unit_price,amount,status,doctor_id,review_status,review_note,reviewer_id,remark,clinical_summary)
                values (?,?,'LAB',0,?,'采样退款夹具','次',1,7.50,7.50,'CREATED',?,'APPROVED','原审方',?,'原备注','原摘要') returning id
                """, Long.class, registration, tag, tag, doctor.id(), doctor.id());
        authenticate(cashier); charge = charges.settle(registration, "CASH", cashier.id()).getId(); authenticate(tech);
    }

    Staff employee(String suffix, String role) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id", Long.class, name, name, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        var actor = new Staff(id, name, role); employees.add(actor); return actor;
    }
    void authenticate(Staff actor) {
        if (actor == null) { SecurityContextHolder.clearContext(); return; }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.name(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()))));
    }
    int collect() { return lis.collect(order, null).getCode(); }
    int receive() { return lis.receive(barcode).getCode(); }
    int operation(String op) { return op.equals("collect") ? collect() : receive(); }
    int rejected(String op) { return op.equals("collect") ? 9940 : 9941; }
    void sample() { jdbc.update("insert into lis_sample(order_id,barcode) values (?,?)", order, barcode); }
    void prepare(String op) { if (op.equals("receive")) sample(); }
    int refund() { charges.refund(charge, cashier.id()); return 0; }
    int attempt(IntSupplier op) {
        try { return op.getAsInt(); }
        catch (HipBizException e) { return e.code; }
        catch (AccessDeniedException | AuthenticationCredentialsNotFoundException e) { return 1005; }
    }
    TransactionTemplate transaction() { var tx = new TransactionTemplate(manager); tx.setTimeout(20); return tx; }
    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, Staff actor, IntSupplier operation) {
        return pool.submit(() -> {
            authenticate(actor);
            try { return attempt(() -> transaction().execute(tx -> {
                pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return operation.getAsInt();
            })); } finally { SecurityContextHolder.clearContext(); }
        });
    }
    int result(Future<Integer> future) throws Exception {
        try { return future.get(20, TimeUnit.SECONDS); }
        catch (ExecutionException e) { return fail("Unexpected runtime/SQL failure instead of a readable business outcome", e.getCause()); }
    }
    void waiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')", Boolean.class, pid.get()))) {
                System.out.println("V115 PG_LOCK observed pid=" + pid.get()); return;
            }
            if (future.isDone()) fail("Operation completed without the required PostgreSQL Lock wait");
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Expected a real PostgreSQL Lock wait");
    }
    void await(CountDownLatch latch) {
        try { assertTrue(latch.await(12, TimeUnit.SECONDS), "Worker reaches its coordinated point"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    void close(ExecutorService pool) throws Exception { pool.shutdown(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Workers finish before cleanup"); }
    void lockOrder() { assertEquals(List.of(order), jdbc.queryForList("select id from outp_order where id=? for update", Long.class, order)); }
    void lockSample() { assertEquals(1, jdbc.queryForList("select id from lis_sample where barcode=? for update", Long.class, barcode).size()); }
    void assertWorkerOwnsStrongOrderLock() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("select id from outp_order where id=? for no key update nowait")) {
                statement.setLong(1, order);
                try (var ignored = statement.executeQuery()) { fail("Receiver must own strong O before waiting on S; a sample FK KEY SHARE is insufficient"); }
            } catch (java.sql.SQLException expected) { assertEquals("55P03", expected.getSQLState()); }
            finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
            return null;
        });
    }
    Object stock() { return List.of(jdbc.queryForList("select id,stock from md_drug order by id"),
            jdbc.queryForList("select * from pharm_batch order by id"), jdbc.queryForList("select * from inv_transaction order by id"),
            jdbc.queryForList("select * from pharm_stock order by id"), jdbc.queryForList("select * from pharm_stock_flow order by id")); }
    Object snapshot() {
        return List.of(jdbc.queryForList("select * from outp_order where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id", registration),
                jdbc.queryForList("select s.* from lis_sample s join outp_order o on o.id=s.order_id where o.registration_id=? order by s.id", registration),
                jdbc.queryForList("select r.* from outp_lab_result r join outp_order o on o.id=r.order_id where o.registration_id=? order by r.id", registration),
                jdbc.queryForList("select * from outp_critical_alert where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_registration where id=?", registration), stock());
    }
    void assertLedger(String source, String bill, String sampleStatus) {
        var o = jdbc.queryForMap("select status,charge_id,amount,qty,doctor_id,review_status,review_note,reviewer_id,remark,clinical_summary from outp_order where id=?", order);
        assertEquals(source, o.get("status")); assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) o.get("amount")));
        assertEquals(1, o.get("qty")); assertEquals(doctor.id(), o.get("doctor_id")); assertEquals("APPROVED", o.get("review_status"));
        assertEquals("原审方", o.get("review_note")); assertEquals(doctor.id(), o.get("reviewer_id")); assertEquals("原备注", o.get("remark")); assertEquals("原摘要", o.get("clinical_summary"));
        var c = jdbc.queryForMap("select status,total_amount,pay_method,cashier_id,refund_by,refunded_at from outp_charge where id=?", charge);
        assertEquals(bill, c.get("status")); assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) c.get("total_amount")));
        assertEquals("CASH", c.get("pay_method")); assertEquals(cashier.id(), c.get("cashier_id"));
        if (bill.equals("REFUNDED")) { assertNull(o.get("charge_id")); assertEquals(cashier.id(), c.get("refund_by")); assertNotNull(c.get("refunded_at")); }
        else { assertEquals(charge, o.get("charge_id")); assertNull(c.get("refund_by")); assertNull(c.get("refunded_at")); }
        var samples = jdbc.queryForList("select status,collected_at,received_at,published_at,verifier_id from lis_sample where order_id=?", order);
        assertEquals(sampleStatus == null ? 0 : 1, samples.size());
        if (sampleStatus != null) {
            var s = samples.getFirst(); assertEquals(sampleStatus, s.get("status")); assertNotNull(s.get("collected_at"));
            if (sampleStatus.equals("COLLECTED")) assertNull(s.get("received_at")); else assertNotNull(s.get("received_at"));
            assertNull(s.get("published_at")); assertNull(s.get("verifier_id"));
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from outp_lab_result where order_id=?", Integer.class, order));
        assertEquals(0, jdbc.queryForObject("select count(*) from outp_critical_alert where registration_id=?", Integer.class, registration));
        assertEquals(1, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(1, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(stockBefore, stock(), "Sampling/receiving never touch inventory");
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
                jdbc.update("delete from outp_order where registration_id=?", registration); jdbc.update("delete from outp_charge where registration_id=?", registration);
                jdbc.update("delete from outp_registration where id=?", registration);
            }
            if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
            if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
            for (var employee : employees) { jdbc.update("delete from sys_user_role where user_id=?", employee.id()); jdbc.update("delete from sys_user where id=?", employee.id()); }
            if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
            if (originalSubstitute != null && !originalSubstitute.isEmpty()) jdbc.update("update sys_config set cfg_value=? where cfg_key='lis_allow_substitute'", originalSubstitute.getFirst());
        });
        config.evictAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "TECHNICIAN", "DOCTOR_OUTP", "NURSE"})
    void originalFourDutiesCanCollectReceiveYesterdayOtherDoctorsVisitWithoutExecutingIt(String role) {
        authenticate(employee("role" + role, role)); var response = lis.collect(order, null);
        assertEquals(0, response.getCode()); assertEquals(1, response.getData().size()); barcode = response.getData().get("barcode").toString(); assertTrue(barcode.startsWith("BC"));
        assertLedger("CHARGED", "PAID", "COLLECTED"); assertEquals(0, receive()); assertLedger("CHARGED", "PAID", "RECEIVED");
    }

    @ParameterizedTest
    @CsvSource({"LAB,CREATED", "LAB,CANCELLED", "LAB,DISPENSED", "EXAM,CHARGED", "TREAT,CHARGED", "DRUG,CHARGED"})
    void bothWritesRejectAnActualIneligibleSourceAndLeaveEveryBusinessRowUnchanged(String type, String status) {
        jdbc.update("update outp_order set order_type=?,status=? where id=?", type, status, order);
        Object before = snapshot(); assertEquals(9940, collect()); assertEquals(before, snapshot());
        sample(); before = snapshot(); assertEquals(9941, receive()); assertEquals(before, snapshot());
    }

    @Test
    void executedLabSourceCannotBeCollectedButHistoricalCollectedSampleCanStillBeReceived() {
        jdbc.update("update outp_order set status='EXECUTED' where id=?", order); Object before = snapshot();
        assertEquals(9940, collect()); assertEquals(before, snapshot()); sample(); assertEquals(0, receive()); assertLedger("EXECUTED", "PAID", "RECEIVED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "missing", "wrong-role", "none"})
    void bothWritesRequireARealEnabledEmployeeWithOneOfTheOriginalDuties(String kind) {
        sample();
        switch (kind) {
            case "disabled" -> jdbc.update("update sys_user set enabled=false where id=?", tech.id());
            case "missing" -> authenticate(new Staff(-1L, tag + "ghost", "TECHNICIAN"));
            case "wrong-role" -> authenticate(cashier);
            case "none" -> authenticate(null);
            default -> fail("Unknown fixture");
        }
        Object before = snapshot(); assertEquals(1005, attempt(this::collect)); assertEquals(1005, attempt(this::receive)); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @CsvSource({"false,4770", "0,4770", "true,0", "1,0"})
    void substituteParameterRetainsBothHistoricAndNumericConfigurationSemantics(String value, int expected) {
        assertEquals(1, jdbc.update("update sys_config set cfg_value=? where cfg_key='lis_allow_substitute'", value)); config.evictAll();
        Object before = snapshot(); var response = lis.collect(order, "模拟替检人"); assertEquals(expected, response.getCode());
        if (expected != 0) assertEquals(before, snapshot());
        else {
            var row = jdbc.queryForMap("select substitute,substitute_name from lis_sample where order_id=?", order);
            assertEquals(true, row.get("substitute")); assertEquals("模拟替检人", row.get("substitute_name")); assertLedger("CHARGED", "PAID", "COLLECTED");
        }
    }

    @Test
    void blankSubstituteIsOrdinarySamplingEvenWhenSubstituteIsDisabled() {
        jdbc.update("update sys_config set cfg_value='0' where cfg_key='lis_allow_substitute'"); config.evictAll();
        assertEquals(0, lis.collect(order, "  ").getCode());
        var s = jdbc.queryForMap("select substitute,substitute_name from lis_sample where order_id=?", order);
        assertEquals(false, s.get("substitute")); assertNull(s.get("substitute_name")); assertLedger("CHARGED", "PAID", "COLLECTED");
    }

    @Test
    void missingObjectsAndRepeatTransitionsKeepTheirOriginalObjectErrorsAndZeroWrites() {
        Object before = snapshot(); assertEquals(9940, lis.collect(-1L, null).getCode()); assertEquals(9941, lis.receive(tag + "missing").getCode()); assertEquals(before, snapshot());
        sample(); before = snapshot(); assertEquals(9940, collect()); assertEquals(before, snapshot());
        assertEquals(0, receive()); before = snapshot(); assertEquals(9941, receive()); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect", "receive"})
    void committedRefundMakesLateWritesRejectWhileKeepingPhysicalHistory(String op) {
        prepare(op); authenticate(cashier); assertEquals(0, refund()); authenticate(tech); Object refunded = snapshot();
        assertEquals(rejected(op), operation(op)); assertEquals(refunded, snapshot()); assertLedger("CREATED", "REFUNDED", op.equals("receive") ? "COLLECTED" : null);
    }

    @Test
    void recollectionIsNotInventedAfterRefundAndRepaymentReactivatesTheOriginalSample() {
        sample(); Object originalSample = jdbc.queryForMap("select * from lis_sample where order_id=?", order);
        authenticate(cashier); assertEquals(0, refund()); authenticate(tech); assertEquals(9941, receive());
        authenticate(cashier); charge = charges.settle(registration, "CASH", cashier.id()).getId(); authenticate(tech);
        assertEquals(originalSample, jdbc.queryForMap("select * from lis_sample where order_id=?", order));
        assertEquals(9940, collect()); assertEquals(0, receive()); assertLedger("CHARGED", "PAID", "RECEIVED");
        assertEquals(1, jdbc.queryForObject("select count(*) from outp_charge where registration_id=? and status='REFUNDED'", Integer.class, registration));
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect", "receive"})
    void refundOwnsOrderThenActuallyWaitingWriterLosesWithObjectCodeAndNoLateWrite(String op) throws Exception {
        prepare(op); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>(); Object[] refunded = new Object[1];
        try {
            transaction().executeWithoutResult(tx -> {
                authenticate(cashier); assertEquals(0, refund()); refunded[0] = snapshot();
                var future = worker(pool, pid, tech, () -> operation(op)); futures.add(future); waiting(pid, future);
            });
            assertEquals(rejected(op), result(futures.getFirst())); assertEquals(refunded[0], snapshot());
        } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect", "receive"})
    void samplingOrReceivingOwnsOrderUntilCommitButWaitingRefundRemainsLegal(String op) throws Exception {
        prepare(op); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, operation(op)); var future = worker(pool, pid, cashier, this::refund); futures.add(future); waiting(pid, future);
            });
            assertEquals(0, result(futures.getFirst())); assertLedger("CREATED", "REFUNDED", op.equals("collect") ? "COLLECTED" : "RECEIVED");
        } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect", "receive"})
    void twoActualWaitingWritersHaveExactlyOneSuccessOneOriginalObjectErrorAndOneSample(String op) throws Exception {
        prepare(op); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, operation(op)); var future = worker(pool, pid, doctor, () -> operation(op)); futures.add(future); waiting(pid, future);
            });
            assertEquals(rejected(op), result(futures.getFirst())); assertLedger("CHARGED", "PAID", op.equals("collect") ? "COLLECTED" : "RECEIVED");
        } finally { close(pool); }
    }

    @ParameterizedTest
    @CsvSource({"collect,O", "receive,O", "receive,S"})
    void writerRechecksActualEmployeeAfterOrderOrSampleLockWait(String op, String locked) throws Exception {
        prepare(op); Object before = snapshot(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                if (locked.equals("O")) lockOrder(); else lockSample();
                var future = worker(pool, pid, tech, () -> operation(op)); futures.add(future); waiting(pid, future);
                jdbc.update("update sys_user set enabled=false where id=?", tech.id());
            });
            assertEquals(1005, result(futures.getFirst())); assertEquals(before, snapshot());
        } finally { close(pool); }
    }

    @Test
    void receiverOwnsStrongOrderBeforeActuallyWaitingOnSample() throws Exception {
        sample(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                lockSample(); var future = worker(pool, pid, tech, this::receive); futures.add(future); waiting(pid, future); assertWorkerOwnsStrongOrderLock();
            });
            assertEquals(0, result(futures.getFirst())); assertLedger("CHARGED", "PAID", "RECEIVED");
        } finally { close(pool); }
    }

    @Test
    void sampleMovedWhileReceiverWaitsCannotBorrowItsNewValidSourceOrWriteReceptionTime() throws Exception {
        sample(); Long replacement = jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,
                    unit,qty,unit_price,amount,status,doctor_id,charge_id)
                select registration_id,group_no || 'X','LAB',0,item_code,'另一模拟申请','次',1,0,0,'CHARGED',doctor_id,charge_id
                from outp_order where id=? returning id
                """, Long.class, order);
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>(); Object[] changed = new Object[1];
        try {
            transaction().executeWithoutResult(tx -> {
                lockSample(); var future = worker(pool, pid, tech, this::receive); futures.add(future); waiting(pid, future);
                jdbc.update("update lis_sample set order_id=? where barcode=?", replacement, barcode); changed[0] = snapshot();
            });
            assertEquals(9941, result(futures.getFirst())); assertEquals(changed[0], snapshot());
        } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect", "receive"})
    void rolledBackOwnerReleasesWaitingRefundAndLeavesNoSamplingOrReceivingWrite(String op) throws Exception {
        prepare(op); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, operation(op)); var future = worker(pool, pid, cashier, this::refund); futures.add(future); waiting(pid, future); tx.setRollbackOnly();
            });
            assertEquals(0, result(futures.getFirst())); assertLedger("CREATED", "REFUNDED", op.equals("collect") ? null : "COLLECTED");
        } finally { close(pool); }
    }

    @ParameterizedTest
    @CsvSource({"collect,refund", "receive,refund", "collect,type", "receive,type"})
    void managedOldSourceCannotPermitWritesAfterACommittedRefundOrTypeChange(String op, String change) throws Exception {
        prepare(op); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, tech, () -> {
                var entity = orders.findById(order).orElseThrow(); assertEquals("CHARGED", entity.getStatus()); assertEquals("LAB", entity.getOrderType());
                loaded.countDown(); await(proceed); return operation(op);
            });
            await(loaded);
            if (change.equals("refund")) { authenticate(cashier); assertEquals(0, refund()); }
            else jdbc.update("update outp_order set order_type='EXAM' where id=?", order);
            Object changed = snapshot(); proceed.countDown(); assertEquals(rejected(op), result(future)); assertEquals(changed, snapshot());
        } finally { proceed.countDown(); close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect", "receive"})
    void cachedMetadataDoesNotOverwriteConcurrentFinanceOrClinicalFields(String op) throws Exception {
        prepare(op); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, tech, () -> { orders.findById(order).orElseThrow(); loaded.countDown(); await(proceed); return operation(op); });
            await(loaded); jdbc.update("update outp_order set amount=8.25,unit_price=8.25,remark='新备注',clinical_summary='新摘要',review_status='REJECTED',review_note='新审方' where id=?", order);
            var expected = jdbc.queryForMap("select * from outp_order where id=?", order); proceed.countDown(); assertEquals(0, result(future));
            assertEquals(expected, jdbc.queryForMap("select * from outp_order where id=?", order));
        } finally { proceed.countDown(); close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect", "receive"})
    void pendingManagedInvalidSourceIsFlushedBeforeValidationAndComposedFailureCanRetry(String op) {
        prepare(op); Object before = snapshot();
        HipBizException failure = assertThrows(HipBizException.class, () -> transaction().executeWithoutResult(tx -> {
            orders.findById(order).orElseThrow().setOrderType("EXAM");
            int code = operation(op); if (code != 0) throw new HipBizException(code, "rollback composed source change");
        }));
        assertEquals(rejected(op), failure.code); assertEquals(before, snapshot()); assertEquals(0, operation(op));
        assertLedger("CHARGED", "PAID", op.equals("collect") ? "COLLECTED" : "RECEIVED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect", "receive"})
    void pendingManagedMetadataIsFlushedBeforeWriteWithoutChangingSourceStatus(String op) {
        prepare(op);
        transaction().executeWithoutResult(tx -> {
            var entity = orders.findById(order).orElseThrow(); entity.setRemark("组合事务备注"); entity.setClinicalSummary("组合事务摘要");
            assertEquals(0, operation(op)); em.flush();
        });
        var row = jdbc.queryForMap("select status,charge_id,remark,clinical_summary from outp_order where id=?", order);
        assertEquals("CHARGED", row.get("status")); assertEquals(charge, row.get("charge_id"));
        assertEquals("组合事务备注", row.get("remark")); assertEquals("组合事务摘要", row.get("clinical_summary"));
    }

    @Test
    void failedSampleInsertRollsBackAndRetryRetainsOnePhysicalSample() {
        jdbc.update("update sys_config set cfg_value='1' where cfg_key='lis_allow_substitute'"); config.evictAll(); Object before = snapshot();
        assertThrows(RuntimeException.class, () -> lis.collect(order, "替".repeat(65))); assertEquals(before, snapshot());
        assertEquals(0, collect()); assertLedger("CHARGED", "PAID", "COLLECTED");
    }

    @Test
    void failedReceiveStatementRollsBackStatusAndTimeThenSafeRetrySucceeds() {
        sample(); Object before = snapshot(); String constraint = "v115_fail_" + Long.toUnsignedString(System.nanoTime(), 36);
        jdbc.execute("alter table lis_sample add constraint " + constraint + " check(order_id <> " + order + " or status <> 'RECEIVED') not valid");
        try { assertThrows(RuntimeException.class, this::receive); assertEquals(before, snapshot()); }
        finally { jdbc.execute("alter table lis_sample drop constraint " + constraint); }
        assertEquals(0, receive()); assertLedger("CHARGED", "PAID", "RECEIVED");
    }
}
