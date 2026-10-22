package cn.hip.server;

import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyRegistryController;
import cn.hip.medtech.web.PathologyRegistryController.RegisterReq;
import cn.hip.medtech.web.PathologyRegistryController.ManualReq;
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
 * Committed PostgreSQL controller/service compositions, deliberately no outer test transaction.
 * Lock races observe pg_stat_activity rather than infer competition from thread timing. JDBC
 * NOWAIT proves the source lock is strong: a specimen FK KEY SHARE alone cannot block it.
 * Contexts here are simulated controller invocations; real JWT parsing is covered in HTTP E2E.
 * Managed-entity cases are compositions, not a claim that HTTP can edit charged metadata.
 */
@SpringBootTest
class V116PathologyRegistrationRefundRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ChargeService charges;
    @Autowired PathologyRegistryController registry;
    @Autowired PathologyController legacy;
    @Autowired OutpOrderRepository orders;
    @Autowired EntityManager em;
    @Autowired ConfigReader config;

    record Staff(Long id, String name, String role) {}
    final List<Staff> employees = new ArrayList<>();
    Staff tech, doctor, cashier;
    Long dept, patient, schedule, registration, order, charge, bed, admission, inpOrder;
    String tag, pattern;
    Object stockBefore;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext(); tag = "P116" + Long.toUnsignedString(System.nanoTime(), 36);
        pattern = jdbc.queryForObject("select cfg_value from sys_config where cfg_key='path.no.pattern'", String.class); stockBefore = stock();
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
                values (?,?,'TREAT',0,?,'普通病理夹具','次',1,7.50,7.50,'CREATED',?,'APPROVED','原审方',?,'原备注','原摘要') returning id
                """, Long.class, registration, tag, tag, doctor.id(), doctor.id());
        authenticate(cashier); charge = charges.settle(registration, "CASH", cashier.id()).getId(); authenticate(tech);
    }

    Staff employee(String suffix, String role) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id", Long.class, name, name, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        var staff = new Staff(id, name, role); employees.add(staff); return staff;
    }
    Authentication auth() { return SecurityContextHolder.getContext().getAuthentication(); }
    Authentication token(Staff actor) { return new UsernamePasswordAuthenticationToken(actor.name(), null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()))); }
    void authenticate(Staff actor) { if (actor == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(token(actor)); }
    RegisterReq req(Long outp, Long inp, int part) { return new RegisterReq(outp, inp, part, "ROUTINE", "真实组织描述", "模拟部位", "模拟诊断", "中性福尔马林", null, true); }
    ManualReq manualReq(Long outp, Long inp, int part) { return new ManualReq(outp, inp, part, "ROUTINE", "实物组织描述", "模拟部位", "模拟诊断", "中性福尔马林", null, true, "送检科", "送检医师", "实物已送达"); }
    int operation(String op) { return operation(op, 1); }
    int operation(String op, int part) { return switch (op) {
        case "old" -> legacy.collect(order, "真实组织描述").getCode();
        case "manual" -> registry.registerManual(manualReq(order, null, part), auth()).getCode();
        default -> registry.register(req(order, null, part), auth()).getCode();
    }; }
    int inp(String op) { return op.equals("manual") ? registry.registerManual(manualReq(null, inpOrder, 1), auth()).getCode() : registry.register(req(null, inpOrder, 1), auth()).getCode(); }
    int objectCode(String op) { return op.equals("old") ? 4550 : 5201; }
    int duplicateCode(String op) { return op.equals("old") ? 4550 : 5202; }
    int refund() { charges.refund(charge, cashier.id()); return 0; }
    int attempt(IntSupplier operation) { try { return operation.getAsInt(); } catch (HipBizException e) { return e.code; } catch (AccessDeniedException | AuthenticationCredentialsNotFoundException e) { return 1005; } }
    TransactionTemplate transaction() { var tx = new TransactionTemplate(manager); tx.setTimeout(20); return tx; }
    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, Staff actor, IntSupplier operation) {
        return pool.submit(() -> { authenticate(actor); try { return attempt(() -> transaction().execute(tx -> { pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return operation.getAsInt(); })); } finally { SecurityContextHolder.clearContext(); } });
    }
    int result(Future<Integer> future) throws Exception { try { return future.get(20, TimeUnit.SECONDS); } catch (ExecutionException e) { return fail("Expected readable business result, not SQL/constraint/runtime failure", e.getCause()); } }
    void waiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')", Boolean.class, pid.get()))) { System.out.println("V116 PG_LOCK observed pid=" + pid.get()); return; }
            if (future.isDone()) fail("Operation completed without required PostgreSQL Lock wait");
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Expected a real PostgreSQL Lock wait");
    }
    void await(CountDownLatch latch) { try { assertTrue(latch.await(12, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); } }
    void close(ExecutorService pool) throws Exception { pool.shutdown(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Workers finish before cleanup"); }
    void lockOrder() { jdbc.queryForList("select id from outp_order where id=? for update", order); }
    void lockAdvisory() { jdbc.query("select pg_advisory_xact_lock(5200, ?)", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null, BusinessDates.today().getYear() * 10); }
    void strongOrderLock() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("select id from outp_order where id=? for no key update nowait")) {
                statement.setLong(1, order); try (var ignored = statement.executeQuery()) { fail("Registrant must own strong O before advisory wait; FK KEY SHARE is insufficient"); }
            } catch (java.sql.SQLException expected) { assertEquals("55P03", expected.getSQLState()); }
            finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
            return null;
        });
    }
    Object stock() { return List.of(jdbc.queryForList("select id,stock from md_drug order by id"), jdbc.queryForList("select * from pharm_batch order by id"), jdbc.queryForList("select * from inv_transaction order by id"), jdbc.queryForList("select * from pharm_stock order by id"), jdbc.queryForList("select * from pharm_stock_flow order by id")); }
    Object snapshot() { return List.of(jdbc.queryForList("select * from outp_order where registration_id=? order by id", registration), jdbc.queryForList("select * from outp_charge where registration_id=? order by id", registration), jdbc.queryForList("select s.* from path_specimen s left join outp_order o on o.id=s.order_id where o.registration_id=? or s.inp_order_id=? order by s.id", registration, inpOrder == null ? -1L : inpOrder), jdbc.queryForList("select p.* from path_process p join path_specimen s on s.id=p.specimen_id left join outp_order o on o.id=s.order_id where o.registration_id=? or s.inp_order_id=? order by p.id", registration, inpOrder == null ? -1L : inpOrder), jdbc.queryForList("select * from outp_registration where id=?", registration), stock()); }
    int count() { return jdbc.queryForObject("select count(*) from path_specimen where order_id=?", Integer.class, order); }
    void assertLedger(String source, String bill) {
        var o = jdbc.queryForMap("select status,charge_id,amount,qty,doctor_id,review_status,review_note,reviewer_id,remark,clinical_summary from outp_order where id=?", order);
        assertEquals(source, o.get("status")); assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) o.get("amount"))); assertEquals(1, o.get("qty")); assertEquals(doctor.id(), o.get("doctor_id")); assertEquals("APPROVED", o.get("review_status")); assertEquals("原审方", o.get("review_note")); assertEquals(doctor.id(), o.get("reviewer_id")); assertEquals("原备注", o.get("remark")); assertEquals("原摘要", o.get("clinical_summary"));
        var c = jdbc.queryForMap("select status,total_amount,pay_method,cashier_id,refund_by,refunded_at from outp_charge where id=?", charge);
        assertEquals(bill, c.get("status")); assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) c.get("total_amount"))); assertEquals("CASH", c.get("pay_method")); assertEquals(cashier.id(), c.get("cashier_id"));
        if (bill.equals("REFUNDED")) { assertNull(o.get("charge_id")); assertEquals(cashier.id(), c.get("refund_by")); assertNotNull(c.get("refunded_at")); } else { assertEquals(charge, o.get("charge_id")); assertNull(c.get("refund_by")); assertNull(c.get("refunded_at")); }
        assertEquals(0, jdbc.queryForObject("select count(*) from outp_critical_alert where registration_id=?", Integer.class, registration)); assertEquals(1, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule)); assertEquals(1, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule)); assertEquals(stockBefore, stock());
    }
    void inpatient() {
        bed = jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id", Long.class, dept, tag);
        admission = jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id) values (?,?,?,?,?,?) returning id", Long.class, tag, patient, dept, dept, bed, doctor.id());
        inpOrder = jdbc.queryForObject("insert into inp_order(admission_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id) values (?,?,'TREAT',0,?,'模拟病理','次',1,7.50,7.50,'CREATED',?) returning id", Long.class, admission, tag, tag, doctor.id());
    }
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        transaction().executeWithoutResult(tx -> {
            jdbc.update("delete from path_process where specimen_id in(select id from path_specimen where order_id in(select id from outp_order where registration_id=?) or inp_order_id=?)", registration, inpOrder == null ? -1L : inpOrder);
            jdbc.update("delete from path_specimen where order_id in(select id from outp_order where registration_id=?) or inp_order_id=?", registration, inpOrder == null ? -1L : inpOrder);
            jdbc.update("delete from outp_refund_approval where charge_id in(select id from outp_charge where registration_id=?)", registration);
            jdbc.update("delete from outp_order where registration_id=?", registration); jdbc.update("delete from outp_charge where registration_id=?", registration); jdbc.update("delete from outp_registration where id=?", registration); jdbc.update("delete from outp_schedule where id=?", schedule);
            if (inpOrder != null) jdbc.update("delete from inp_order where id=?", inpOrder); if (admission != null) jdbc.update("delete from inp_admission where id=?", admission); if (bed != null) jdbc.update("delete from inp_bed where id=?", bed);
            jdbc.update("delete from empi_patient where id=?", patient); for (var e : employees) { jdbc.update("delete from sys_user_role where user_id=?", e.id()); jdbc.update("delete from sys_user where id=?", e.id()); } jdbc.update("delete from sys_dept where id=?", dept);
            jdbc.update("update sys_config set cfg_value=? where cfg_key='path.no.pattern'", pattern);
        }); config.evictAll();
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,new", "TECHNICIAN,new", "DOCTOR_OUTP,new", "ADMIN,manual", "TECHNICIAN,manual", "DOCTOR_OUTP,manual", "ADMIN,old", "TECHNICIAN,old", "DOCTOR_OUTP,old"})
    void originalThreeDutiesCanRegisterAnotherDoctorsYesterdayVisit(String role, String op) {
        var actor = employee(role + op, role); authenticate(actor); assertEquals(0, operation(op)); assertEquals(1, count()); assertLedger("CHARGED", "PAID");
        var s = jdbc.queryForMap("select status,received_at,path_no,specimen_desc from path_specimen where order_id=?", order);
        assertEquals(op.equals("manual") ? "RECEIVED" : "COLLECTED", s.get("status")); assertEquals(op.equals("manual"), s.get("received_at") != null); assertNotNull(s.get("specimen_desc"));
        if (!op.equals("old")) assertNotNull(s.get("path_no"));
        var p = jdbc.queryForList("select p.* from path_process p join path_specimen s on s.id=p.specimen_id where s.order_id=?", order); assertEquals(op.equals("manual") ? 1 : 0, p.size());
        if (op.equals("manual")) { assertEquals(actor.id(), p.getFirst().get("operator_id")); assertEquals("RECEIVE", p.getFirst().get("node")); assertTrue(p.getFirst().get("remark").toString().contains("实物已送达")); }
    }

    @ParameterizedTest
    @CsvSource({"new,disabled", "old,disabled", "manual,disabled", "new,missing", "old,missing", "manual,missing", "new,nurse", "old,nurse", "manual,nurse", "new,none", "old,none", "manual,none"})
    void everyEntryRequiresARealEnabledEmployeeAndOriginalDuty(String op, String kind) {
        switch (kind) { case "disabled" -> jdbc.update("update sys_user set enabled=false where id=?", tech.id()); case "missing" -> authenticate(new Staff(-1L, tag + "ghost", "TECHNICIAN")); case "nurse" -> authenticate(employee("n", "NURSE")); case "none" -> authenticate(null); default -> fail(); }
        Object before = snapshot(); assertEquals(1005, attempt(() -> operation(op))); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "manual"})
    void suppliedIdentityCannotImpersonateAnOtherwiseEnabledColleague(String op) {
        Object before = snapshot(); int code = attempt(() -> op.equals("manual") ? registry.registerManual(manualReq(order, null, 1), token(doctor)).getCode() : registry.register(req(order, null, 1), token(doctor)).getCode()); assertEquals(1005, code); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @CsvSource({"new,CREATED", "new,CANCELLED", "new,EXECUTED", "old,CREATED", "old,CANCELLED", "old,EXECUTED"})
    void ordinaryOutpatientRegistrationKeepsChargedOnlySourceRule(String op, String status) {
        jdbc.update("update outp_order set status=? where id=?", status, order); Object before = snapshot(); assertEquals(objectCode(op), operation(op)); assertEquals(before, snapshot());
    }

    @Test
    void manualUnpaidPhysicalSpecimenStillRegistersAndBlocksLaterRefundWhenCharged() {
        authenticate(cashier); assertEquals(0, refund()); authenticate(tech); assertEquals(0, operation("manual")); assertLedger("CREATED", "REFUNDED");
        var s = jdbc.queryForMap("select id,status,received_at from path_specimen where order_id=?", order); assertEquals("RECEIVED", s.get("status")); assertNotNull(s.get("received_at")); assertEquals(5208, registry.receiveCheck(((Number)s.get("id")).longValue(), null, auth()).getCode());
        authenticate(cashier); charge = charges.settle(registration, "CASH", cashier.id()).getId(); Object before = snapshot(); assertEquals(5005, attempt(this::refund)); assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "manual"})
    void inpatientNonCancelledSourceRemainsLegalAndCancelledSourceRejects(String op) {
        inpatient(); jdbc.update("update inp_order set status='CANCELLED' where id=?", inpOrder); Object before = snapshot(); assertEquals(5201, inp(op)); assertEquals(before, snapshot()); jdbc.update("update inp_order set status='CREATED' where id=?", inpOrder); assertEquals(0, inp(op)); assertEquals("CREATED", jdbc.queryForObject("select status from inp_order where id=?", String.class, inpOrder));
    }

    @Test
    void multiplePartsRejectedResubmissionAndLegacyAnyHistoryRuleRemainDistinct() {
        assertEquals(0, operation("new", 1)); assertEquals(0, operation("new", 2)); Object before = snapshot(); assertEquals(5202, operation("new", 1)); assertEquals(4550, operation("old")); assertEquals(before, snapshot());
        jdbc.update("update path_specimen set rejected_at=now(),reject_reason='模拟拒收' where order_id=? and part_no=1", order); assertEquals(0, operation("new", 1)); assertEquals(3, count()); assertEquals(2, jdbc.queryForObject("select count(*) from path_specimen where order_id=? and rejected_at is null", Integer.class, order));
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "old"})
    void refundFirstMakesActuallyWaitingRegistrantRejectAndLeavesNoPhysicalHistory(String op) throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>(); Object[] refunded=new Object[1];
        try { transaction().executeWithoutResult(tx -> { authenticate(cashier); assertEquals(0, refund()); refunded[0]=snapshot(); futures.add(worker(pool,pid,tech,()->operation(op))); waiting(pid,futures.getFirst()); }); assertEquals(objectCode(op),result(futures.getFirst())); assertEquals(refunded[0],snapshot()); assertEquals(0,count()); assertLedger("CREATED","REFUNDED"); } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "old", "manual"})
    void registrationFirstMakesActuallyWaitingRefundRejectAndPreservesPaidLedger(String op) throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>(); Object[] registered=new Object[1];
        try { transaction().executeWithoutResult(tx -> { assertEquals(0,operation(op)); registered[0]=snapshot(); futures.add(worker(pool,pid,cashier,this::refund)); waiting(pid,futures.getFirst()); }); assertEquals(5005,result(futures.getFirst())); assertEquals(registered[0],snapshot()); assertEquals(1,count()); assertLedger("CHARGED","PAID"); } finally { close(pool); }
    }

    @ParameterizedTest
    @CsvSource({"new,new", "new,old", "old,new", "manual,new", "new,manual", "old,old"})
    void competingSamePartAcrossEntriesHasOneSuccessOneReadableDuplicate(String first,String second) throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>();
        try { transaction().executeWithoutResult(tx -> { assertEquals(0,operation(first)); futures.add(worker(pool,pid,doctor,()->operation(second))); waiting(pid,futures.getFirst()); }); assertEquals(duplicateCode(second),result(futures.getFirst())); assertEquals(1,count()); assertLedger("CHARGED","PAID"); } finally { close(pool); }
    }

    @Test
    void waitingDifferentPartRetainsTwoSuccessfulPhysicalPartsAndDistinctPathNumbers() throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>();
        try { transaction().executeWithoutResult(tx -> { assertEquals(0,operation("new",1)); futures.add(worker(pool,pid,doctor,()->operation("new",2))); waiting(pid,futures.getFirst()); }); assertEquals(0,result(futures.getFirst())); assertEquals(2,count()); assertEquals(2,jdbc.queryForObject("select count(distinct path_no) from path_specimen where order_id=?",Integer.class,order)); } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "old", "manual"})
    void sourceLockWaitRechecksDisabledEmployee(String op) throws Exception {
        Object before=snapshot(); var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>();
        try { transaction().executeWithoutResult(tx -> { lockOrder(); futures.add(worker(pool,pid,tech,()->operation(op))); waiting(pid,futures.getFirst()); jdbc.update("update sys_user set enabled=false where id=?",tech.id()); }); assertEquals(1005,result(futures.getFirst())); assertEquals(before,snapshot()); } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "manual"})
    void advisoryWaitRechecksDisabledEmployeeBeforeAnySpecimenAllocation(String op) throws Exception {
        Object before=snapshot(); var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>();
        try { transaction().executeWithoutResult(tx -> { lockAdvisory(); futures.add(worker(pool,pid,tech,()->operation(op))); waiting(pid,futures.getFirst()); jdbc.update("update sys_user set enabled=false where id=?",tech.id()); }); assertEquals(1005,result(futures.getFirst())); assertEquals(before,snapshot()); } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "manual"})
    void registrantOwnsStrongSourceBeforeActuallyWaitingOnYearAndTypeAdvisory(String op) throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>();
        try { transaction().executeWithoutResult(tx -> { lockAdvisory(); futures.add(worker(pool,pid,tech,()->operation(op))); waiting(pid,futures.getFirst()); strongOrderLock(); }); assertEquals(0,result(futures.getFirst())); assertEquals(1,count()); assertLedger("CHARGED","PAID"); } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "manual"})
    void inpatientRegistrantRechecksCancellationAfterActualSourceLockWait(String op) throws Exception {
        inpatient(); var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>(); Object[] changed=new Object[1];
        try { transaction().executeWithoutResult(tx -> { jdbc.queryForList("select id from inp_order where id=? for update",inpOrder); futures.add(worker(pool,pid,tech,()->inp(op))); waiting(pid,futures.getFirst()); jdbc.update("update inp_order set status='CANCELLED' where id=?",inpOrder); changed[0]=snapshot(); }); assertEquals(5201,result(futures.getFirst())); assertEquals(changed[0],snapshot()); assertEquals("CANCELLED",jdbc.queryForObject("select status from inp_order where id=?",String.class,inpOrder)); } finally { close(pool); }
    }

    @Test
    void manualRescueStillWorksAfterActuallyWaitingForRefundToCommit() throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>();
        try { transaction().executeWithoutResult(tx -> { authenticate(cashier); refund(); futures.add(worker(pool,pid,tech,()->operation("manual"))); waiting(pid,futures.getFirst()); }); assertEquals(0,result(futures.getFirst())); assertEquals(1,count()); assertLedger("CREATED","REFUNDED"); assertEquals("RECEIVED",jdbc.queryForObject("select status from path_specimen where order_id=?",String.class,order)); } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "old"})
    void rolledBackRegistrationReleasesWaitingRefundWithNoInventedSpecimen(String op) throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var futures=new ArrayList<Future<Integer>>();
        try { transaction().executeWithoutResult(tx -> { assertEquals(0,operation(op)); futures.add(worker(pool,pid,cashier,this::refund)); waiting(pid,futures.getFirst()); tx.setRollbackOnly(); }); assertEquals(0,result(futures.getFirst())); assertEquals(0,count()); assertLedger("CREATED","REFUNDED"); } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "old"})
    void managedOldSourceCannotPermitRegistrationAfterCommittedRefund(String op) throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var loaded=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        try { var f=worker(pool,pid,tech,()->{ assertEquals("CHARGED",orders.findById(order).orElseThrow().getStatus()); loaded.countDown(); await(proceed); return operation(op); }); await(loaded); authenticate(cashier); refund(); Object before=snapshot(); proceed.countDown(); assertEquals(objectCode(op),result(f)); assertEquals(before,snapshot()); } finally { proceed.countDown(); close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "old"})
    void pendingManagedInvalidStatusIsFlushedAndRealCompositionRollbackCanRetry(String op) {
        Object before=snapshot(); var failure=assertThrows(HipBizException.class,()->transaction().executeWithoutResult(tx->{ orders.findById(order).orElseThrow().setStatus("CANCELLED"); int code=operation(op); if(code!=0) throw new HipBizException(code,"rollback composed invalid source"); })); assertEquals(objectCode(op),failure.code); assertEquals(before,snapshot()); assertEquals(0,operation(op));
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "old", "manual"})
    void pendingMetadataFlushPreservesClinicalFieldsAndFinance(String op) {
        transaction().executeWithoutResult(tx->{ var e=orders.findById(order).orElseThrow(); e.setRemark("组合事务备注"); e.setClinicalSummary("组合事务摘要"); assertEquals(0,operation(op)); assertEquals("组合事务备注",jdbc.queryForObject("select remark from outp_order where id=?",String.class,order)); em.flush(); });
        var o=jdbc.queryForMap("select status,charge_id,remark,clinical_summary from outp_order where id=?",order); assertEquals("CHARGED",o.get("status")); assertEquals(charge,o.get("charge_id")); assertEquals("组合事务备注",o.get("remark")); assertEquals("组合事务摘要",o.get("clinical_summary"));
    }

    @ParameterizedTest
    @ValueSource(strings={"new", "old", "manual"})
    void cachedMetadataNeverOverwritesConcurrentFinanceOrClinicalUpdate(String op) throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger(); var loaded=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        try { var f=worker(pool,pid,tech,()->{ orders.findById(order).orElseThrow(); loaded.countDown(); await(proceed); return operation(op); }); await(loaded); jdbc.update("update outp_order set amount=8.25,unit_price=8.25,remark='新备注',clinical_summary='新摘要',review_status='REJECTED',review_note='新审方' where id=?",order); var expected=jdbc.queryForMap("select * from outp_order where id=?",order); proceed.countDown(); assertEquals(0,result(f)); assertEquals(expected,jdbc.queryForMap("select * from outp_order where id=?",order)); } finally { proceed.countDown(); close(pool); }
    }

    @Test
    void malformedNumberRuleLeavesNoWritesAndCorrectedRuleCanRetry() {
        jdbc.update("update sys_config set cfg_value='broken-{yyyy}-{type}' where cfg_key='path.no.pattern'"); config.evictAll(); Object before=snapshot(); assertEquals(5205,operation("new")); assertEquals(before,snapshot()); jdbc.update("update sys_config set cfg_value=? where cfg_key='path.no.pattern'",pattern); config.evictAll(); assertEquals(0,operation("new")); assertEquals(1,count());
    }

    @Test
    void failedManualReceiveInsertRollsBackSpecimenNumberAndActorThenRetryHasOneRealReceive() {
        String constraint="v116_fail_"+Long.toUnsignedString(System.nanoTime(),36); Object before=snapshot(); String[] expectedNumber=new String[1];
        transaction().executeWithoutResult(tx -> { var response=registry.registerManual(manualReq(order,null,1),auth()); assertEquals(0,response.getCode()); expectedNumber[0]=response.getData().get("pathNo").toString(); tx.setRollbackOnly(); }); assertEquals(before,snapshot());
        jdbc.execute("alter table path_process add constraint "+constraint+" check(operator_id <> "+tech.id()+") not valid");
        try { assertThrows(RuntimeException.class,()->operation("manual")); assertEquals(before,snapshot()); } finally { jdbc.execute("alter table path_process drop constraint "+constraint); }
        var retried=registry.registerManual(manualReq(order,null,1),auth()); assertEquals(0,retried.getCode()); assertEquals(expectedNumber[0],retried.getData().get("pathNo")); assertEquals(1,count()); var p=jdbc.queryForList("select p.* from path_process p join path_specimen s on s.id=p.specimen_id where s.order_id=?",order); assertEquals(1,p.size()); assertEquals(tech.id(),p.getFirst().get("operator_id")); assertEquals("RECEIVE",p.getFirst().get("node")); assertLedger("CHARGED","PAID");
    }
}
