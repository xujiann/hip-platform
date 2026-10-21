package cn.hip.server;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.repository.OutpEmrRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.integration.signature.SignatureAdapter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
 * Real committed transactions, no enclosing test transaction. Every race observes a PostgreSQL Lock wait.
 * The preload cases deliberately exercise a composed service transaction: production HTTP has OSIV off,
 * so controller prechecks alone do not naturally preload the service persistence context. cancelOrder,
 * however, itself reads its order before waiting and therefore has a natural HTTP stale-entity window.
 */
@SpringBootTest
class V110DoctorWriteRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired DoctorStationService doctors;
    @Autowired ChargeService charges;
    @Autowired OutpRegistrationRepository registrations;
    @Autowired OutpEmrRepository emrs;
    @Autowired EntityManager em;

    Long dept, patient, schedule, registration, user, other, item, order, record;
    String username, otherName;
    AtomicInteger signatures;
    SignatureAdapter signer;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext();
        String tag = "D110" + Long.toUnsignedString(System.nanoTime(), 36);
        username = tag; otherName = tag + "b";
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag, tag);
        user = newUser(username); other = newUser(otherName);
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",
                Long.class, tag, tag);
        schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee)
                values (?,?::date,5,1,1,0) returning id
                """, Long.class, dept, BusinessDates.today().toString());
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status)
                values (1,?,?,?,?,?::date,0,'VISITED') returning id
                """, Long.class, patient, schedule, dept, user, BusinessDates.today().toString());
        item = jdbc.queryForObject("""
                insert into md_charge_item(code,name,category,unit,price,exec_dept_id)
                values (?,?,'TREAT','次',7.50,?) returning id
                """, Long.class, tag, tag, dept);
        order = jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,
                                       unit,qty,unit_price,amount,status,doctor_id)
                values (?,?,'TREAT',?,?,?,'次',1,7.50,7.50,'CREATED',?) returning id
                """, Long.class, registration, tag, item, tag, tag, user);
        record = jdbc.queryForObject("""
                insert into outp_emr(registration_id,chief_complaint,present_illness,doctor_id)
                values (?,'原主诉','原现病史',?) returning id
                """, Long.class, registration, user);
        jdbc.update("""
                insert into outp_diagnosis(registration_id,icd_code,icd_name,primary_diag)
                values (?,'J00','原诊断',true)
                """, registration);
        signatures = new AtomicInteger();
        signer = new SignatureAdapter() {
            @Override public SignResult sign(String content, Long signerId) {
                return new SignResult(true, "V110-SIGN-" + signatures.incrementAndGet(), null);
            }
            @Override public boolean verify(String content, String signature) { return true; }
        };
        authenticate("DOCTOR_OUTP");
    }

    Long newUser(String name) {
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, name, name, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='DOCTOR_OUTP'", id);
        return id;
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            if (record != null) {
                jdbc.update("delete from emr_amendment where emr_type='OUTP' and emr_id=?", record);
                jdbc.update("delete from emr_version where emr_type='OUTP' and emr_id=?", record);
            }
            if (registration != null) {
                // The lifecycle test replaces the initial synthetic record with a new first draft.
                jdbc.update("delete from emr_amendment where emr_type='OUTP' and emr_id in(select id from outp_emr where registration_id=?)", registration);
                jdbc.update("delete from emr_version where emr_type='OUTP' and emr_id in(select id from outp_emr where registration_id=?)", registration);
                jdbc.update("delete from outp_diagnosis_version where registration_id=?", registration);
                jdbc.update("delete from outp_diagnosis where registration_id=?", registration);
                jdbc.update("delete from outp_emr where registration_id=?", registration);
                jdbc.update("delete from outp_order where registration_id=?", registration);
                jdbc.update("delete from outp_charge where registration_id=?", registration);
                jdbc.update("delete from outp_registration where id=?", registration);
            }
            if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
            if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
            if (item != null) jdbc.update("delete from md_charge_item where id=?", item);
            for (Long id : new Long[]{user, other}) if (id != null) {
                jdbc.update("delete from outp_diagnosis_favorite where user_id=?", id);
                jdbc.update("delete from sys_user_role where user_id=?", id);
                jdbc.update("delete from sys_user where id=?", id);
            }
            if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
        });
    }

    void authenticate(String role) { authenticate(username, role); }
    void authenticate(String name, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    void lockRegistration() {
        assertEquals(List.of(registration), jdbc.queryForList("select id from outp_registration where id=? for update",
                Long.class, registration));
    }
    void lockOrder() {
        assertEquals(List.of(order), jdbc.queryForList("select id from outp_order where id=? for update", Long.class, order));
    }
    void prepare(int kind) {
        jdbc.update("update outp_registration set status=? where id=?", kind == 0 ? "REGISTERED" : "VISITED", registration);
        jdbc.update("update outp_emr set signature=?,signed_at=case when ? then now() else null end where id=?",
                kind == 3 ? "FIXTURE-SIGNED" : null, kind == 3, record);
    }
    OutpEmr replacement() {
        OutpEmr data = new OutpEmr(); data.setChiefComplaint("新主诉"); data.setPresentIllness("新现病史"); return data;
    }
    List<OutpDiagnosis> diagnoses() {
        OutpDiagnosis diagnosis = new OutpDiagnosis(); diagnosis.setIcdCode("J01"); diagnosis.setIcdName("新诊断");
        return List.of(diagnosis);
    }
    List<DoctorStationService.OrderLine> lines() {
        return List.of(new DoctorStationService.OrderLine("TREAT", item, 1, null, null, null, null));
    }
    int action(int kind) {
        switch (kind) {
            case 0 -> doctors.startVisit(registration, user);
            case 1 -> doctors.saveEmr(registration, replacement(), diagnoses(), user);
            case 2 -> doctors.signEmr(registration, user, signer);
            case 3 -> doctors.amendEmr(registration, "补正内容", "补正原因", user);
            case 4 -> doctors.createOrders(registration, lines(), user);
            case 5 -> doctors.cancelOrder(order);
            default -> throw new IllegalArgumentException("Unknown doctor action");
        }
        return 0;
    }
    int code(IntSupplier operation) {
        try { return operation.getAsInt(); }
        catch (HipBizException e) { return e.code; }
        catch (AccessDeniedException e) { return 1005; }
    }
    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, String role, IntSupplier operation) {
        return pool.submit(() -> {
            authenticate(role);
            try {
                return code(() -> new TransactionTemplate(manager).execute(tx -> {
                    pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return operation.getAsInt();
                }));
            } finally { SecurityContextHolder.clearContext(); }
        });
    }
    void waiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",
                    Boolean.class, pid.get()))) return;
            if (future.isDone()) fail("Doctor writer completed without the required PostgreSQL Lock wait");
            pause(20);
        }
        fail("Expected a real PostgreSQL Lock wait");
    }
    void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdown(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "Workers finish before fixture cleanup");
    }
    Object snapshot() {
        return List.of(
                jdbc.queryForList("select * from outp_registration where id=?", registration),
                jdbc.queryForList("select * from outp_emr where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_diagnosis where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_order where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id", registration),
                jdbc.queryForList("select * from emr_version where emr_type='OUTP' and emr_id=? order by id", record),
                jdbc.queryForList("select * from outp_diagnosis_version where registration_id=? order by id", registration),
                jdbc.queryForList("select * from emr_amendment where emr_type='OUTP' and emr_id=? order by id", record),
                jdbc.queryForList("select booked,last_issued_no from outp_schedule where id=?", schedule));
    }
    void assertMoney(String status, boolean paid) {
        var row = jdbc.queryForMap("select status,charge_id,amount,qty,doctor_id,item_id from outp_order where id=?", order);
        assertEquals(status, row.get("status"));
        assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) row.get("amount")));
        assertEquals(1, ((Number) row.get("qty")).intValue());
        assertEquals(user, row.get("doctor_id")); assertEquals(item, row.get("item_id"));
        if (paid) {
            assertNotNull(row.get("charge_id"));
            var charge = jdbc.queryForMap("select status,total_amount from outp_charge where id=?", row.get("charge_id"));
            assertEquals("PAID", charge.get("status"));
            assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) charge.get("total_amount")));
        } else assertNull(row.get("charge_id"));
        assertEquals(paid ? 1 : 0, jdbc.queryForObject("select count(*) from outp_charge where registration_id=?", Integer.class, registration));
        assertEquals(1, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(1, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule));
    }

    @ParameterizedTest
    @CsvSource({"0,DOCTOR_OUTP", "1,DOCTOR_OUTP", "2,DOCTOR_OUTP", "3,DOCTOR_OUTP", "4,DOCTOR_OUTP", "5,DOCTOR_OUTP",
            "0,ADMIN", "1,ADMIN", "2,ADMIN", "3,ADMIN", "4,ADMIN", "5,ADMIN"})
    void everyWaitingWriteRechecksEnabledDoctorAndAdministrator(int kind, String role) throws Exception {
        prepare(kind); Object before = snapshot();
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockRegistration();
                var future = worker(pool, pid, role, () -> action(kind)); results.add(future); waiting(pid, future);
                jdbc.update("update sys_user set enabled=false where id=?", user);
            });
            assertEquals(1005, results.getFirst().get(10, TimeUnit.SECONDS));
            assertEquals(before, snapshot(), "Rejected request cannot alter clinical, version, fee or number records");
            assertEquals(0, signatures.get(), "Rejected signer cannot reach its adapter");
        } finally { shutdown(pool); }
    }

    @Test
    void coveringDoctorCanCompleteTheExistingSharedTodayLifecycle() {
        jdbc.update("update outp_registration set status='REGISTERED',doctor_id=? where id=?", other, registration);
        jdbc.update("delete from outp_emr where id=?", record);
        assertEquals("VISITED", doctors.startVisit(registration, user).getStatus());
        assertEquals(other, jdbc.queryForObject("select doctor_id from outp_registration where id=?", Long.class, registration));
        var saved = doctors.saveEmr(registration, replacement(), diagnoses(), user);
        assertEquals(user, saved.getDoctorId()); assertEquals(1, doctors.createOrders(registration, lines(), user).size());
        doctors.cancelOrder(order);
        assertNotNull(doctors.signEmr(registration, user, signer).getSignature());
        doctors.amendEmr(registration, "本人补正", "测试", user);
        assertEquals(1, jdbc.queryForObject("select count(*) from emr_amendment where emr_type='OUTP' and emr_id=?", Integer.class, saved.getId()));
        assertEquals("新诊断", jdbc.queryForObject("select icd_name from outp_diagnosis where registration_id=?", String.class, registration));
    }

    @Test
    void historicalOwnerAuthorAndUnassignedVisitRemainWritable() {
        jdbc.update("update outp_registration set visit_date=?::date where id=?", BusinessDates.today().minusDays(1).toString(), registration);
        for (Long owner : new Long[]{user, other, null}) {
            jdbc.update("update outp_registration set doctor_id=? where id=?", owner, registration);
            assertEquals(1, doctors.createOrders(registration, lines(), user).size());
        }
        assertEquals(4, jdbc.queryForObject("select count(*) from outp_order where registration_id=?", Integer.class, registration));
        assertEquals(user, jdbc.queryForObject("select doctor_id from outp_emr where id=?", Long.class, record));
    }

    @Test
    void administratorEditingDoesNotTakeAuthorshipOrSignAnotherWriter() {
        jdbc.update("update outp_emr set doctor_id=? where id=?", other, record); authenticate("ADMIN");
        doctors.saveEmr(registration, replacement(), diagnoses(), user);
        assertEquals(other, jdbc.queryForObject("select doctor_id from outp_emr where id=?", Long.class, record));
        assertEquals(4023, code(() -> { doctors.signEmr(registration, user, signer); return 0; }));
        assertEquals(0, signatures.get());
        authenticate(otherName, "DOCTOR_OUTP"); doctors.signEmr(registration, other, signer);
        authenticate("ADMIN"); doctors.amendEmr(registration, "管理员补正", "测试", user);
        assertEquals(other, jdbc.queryForObject("select doctor_id from outp_emr where id=?", Long.class, record));
    }

    @Test
    void passedVisitStartKeepsItsLegacyNoOpAndTrustedCallsRemainUsable() {
        jdbc.update("update outp_registration set status='PASSED' where id=?", registration);
        assertEquals("PASSED", doctors.startVisit(registration, user).getStatus());
        SecurityContextHolder.clearContext();
        assertEquals("PASSED", doctors.startVisit(registration, null).getStatus());
        jdbc.update("update outp_registration set status='VISITED' where id=?", registration);
        assertEquals(1, doctors.createOrders(registration, lines(), null).size());
    }

    @Test
    void authenticatedActorCannotAttributeAWriteToAnotherUser() {
        Object before = snapshot();
        assertEquals(4036, code(() -> { doctors.createOrders(registration, lines(), other); return 0; }));
        assertEquals(before, snapshot());
    }

    @Test
    void preloadedRegistrationCannotReviveCancellationCommittedDuringItsWait() throws Exception {
        jdbc.update("update outp_registration set status='REGISTERED' where id=?", registration);
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, "DOCTOR_OUTP", () -> {
                assertEquals("REGISTERED", registrations.findById(registration).orElseThrow().getStatus());
                loaded.countDown(); await(proceed); return action(0);
            }); await(loaded);
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.update("update outp_registration set status='CANCELLED',cancelled_at=now() where id=?", registration);
                proceed.countDown(); waiting(pid, future);
            });
            assertEquals(4002, future.get(10, TimeUnit.SECONDS));
            assertEquals("CANCELLED", jdbc.queryForObject("select status from outp_registration where id=?", String.class, registration));
            assertNotNull(jdbc.queryForObject("select cancelled_at from outp_registration where id=?", Object.class, registration));
        } finally { proceed.countDown(); shutdown(pool); }
    }

    @Test
    void preloadedVisitAndEmrCannotRetainHistoricalAccessAfterTheirWait() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, "DOCTOR_OUTP", () -> {
                registrations.findById(registration).orElseThrow(); emrs.findByRegistrationId(registration).orElseThrow();
                loaded.countDown(); await(proceed); return action(4);
            }); await(loaded);
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.update("update outp_registration set visit_date=?::date,doctor_id=? where id=?",
                        BusinessDates.today().minusDays(1).toString(), other, registration);
                jdbc.update("update outp_emr set doctor_id=? where id=?", other, record);
                proceed.countDown(); waiting(pid, future);
            });
            Object before = snapshot(); assertEquals(4036, future.get(10, TimeUnit.SECONDS));
            assertEquals(before, snapshot());
        } finally { proceed.countDown(); shutdown(pool); }
    }

    @Test
    void preloadedUnsignedDraftCannotOverwriteARealSignatureCommittedDuringItsWait() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try {
            var future = worker(pool, pid, "DOCTOR_OUTP", () -> {
                assertNull(emrs.findByRegistrationId(registration).orElseThrow().getSignature());
                loaded.countDown(); await(proceed); return action(1);
            }); await(loaded);
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                doctors.signEmr(registration, user, signer); em.flush();
                proceed.countDown(); waiting(pid, future);
            });
            assertEquals(4008, future.get(10, TimeUnit.SECONDS)); assertEquals(1, signatures.get());
            assertEquals("V110-SIGN-1", jdbc.queryForObject("select signature from outp_emr where id=?", String.class, record));
            assertEquals("原主诉", jdbc.queryForObject("select chief_complaint from outp_emr where id=?", String.class, record));
            assertEquals("原诊断", jdbc.queryForObject("select icd_name from outp_diagnosis where registration_id=?", String.class, registration));
            assertEquals(1, jdbc.queryForObject("select count(*) from emr_version where emr_type='OUTP' and emr_id=?", Integer.class, record));
            assertEquals(0, jdbc.queryForObject("select count(*) from outp_diagnosis_version where registration_id=?", Integer.class, registration));
        } finally { proceed.countDown(); shutdown(pool); }
    }

    @Test
    void realChargingCommittedFirstCannotBeOverwrittenByWaitingCancellation() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockRegistration(); charges.settle(registration, "CASH", user);
                jdbc.update("update outp_order set review_status='APPROVED',review_note='并发审方',reviewer_id=? where id=?", other, order);
                var future = worker(pool, pid, "DOCTOR_OUTP", () -> action(5)); results.add(future); waiting(pid, future);
            });
            assertEquals(4007, results.getFirst().get(10, TimeUnit.SECONDS)); assertMoney("CHARGED", true);
            assertEquals("并发审方", jdbc.queryForObject("select review_note from outp_order where id=?", String.class, order));
        } finally { shutdown(pool); }
    }

    @Test
    void realCancellationCommittedFirstRejectsAndRollsBackConcurrentCharging() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                doctors.cancelOrder(order); em.flush();
                var future = worker(pool, pid, "CASHIER", () -> { charges.settle(registration, "CASH", user); return 0; });
                results.add(future); waiting(pid, future);
            });
            assertEquals(5008, results.getFirst().get(10, TimeUnit.SECONDS)); assertMoney("CANCELLED", false);
        } finally { shutdown(pool); }
    }

    @Test
    void competingCancellationsHaveExactlyOneSuccessAndOneBusinessLoser() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockRegistration();
                for (int i = 0; i < 2; i++) {
                    var pid = new AtomicInteger(); var future = worker(pool, pid, "DOCTOR_OUTP", () -> action(5));
                    results.add(future); waiting(pid, future);
                }
            });
            var codes = List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(1, codes.stream().filter(c -> c == 0).count());
            assertEquals(1, codes.stream().filter(c -> c == 4007).count()); assertMoney("CANCELLED", false);
        } finally { shutdown(pool); }
    }

    @Test
    void cancellationRollbackReleasesTheOrderForOneRealCharge() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                doctors.cancelOrder(order); em.flush();
                var future = worker(pool, pid, "CASHIER", () -> { charges.settle(registration, "CASH", user); return 0; });
                results.add(future); waiting(pid, future); tx.setRollbackOnly();
            });
            assertEquals(0, results.getFirst().get(10, TimeUnit.SECONDS)); assertMoney("CHARGED", true);
        } finally { shutdown(pool); }
    }

    @Test
    void orderLockWaitAlsoRechecksTheActualEnabledActor() throws Exception {
        Object before = snapshot();
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockOrder();
                var future = worker(pool, pid, "DOCTOR_OUTP", () -> action(5)); results.add(future); waiting(pid, future);
                jdbc.update("update sys_user set enabled=false where id=?", user);
            });
            assertEquals(1005, results.getFirst().get(10, TimeUnit.SECONDS)); assertEquals(before, snapshot());
        } finally { shutdown(pool); }
    }

    @Test
    void cancellationOnlyChangesStatusAndPreservesReviewCommittedDuringTheOrderWait() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockOrder();
                jdbc.update("update outp_order set review_status='APPROVED',review_note='审方结论保留',reviewer_id=? where id=?", other, order);
                var future = worker(pool, pid, "DOCTOR_OUTP", () -> action(5)); results.add(future); waiting(pid, future);
            });
            assertEquals(0, results.getFirst().get(10, TimeUnit.SECONDS)); assertMoney("CANCELLED", false);
            var row = jdbc.queryForMap("select review_status,review_note,reviewer_id from outp_order where id=?", order);
            assertEquals(Map.of("review_status", "APPROVED", "review_note", "审方结论保留", "reviewer_id", other), row);
        } finally { shutdown(pool); }
    }

    @Test
    void createdOrderWithAnExistingChargeAssociationCannotBeCancelled() {
        Long charge = jdbc.queryForObject("""
                insert into outp_charge(registration_id,charge_no,total_amount,pay_method,cashier_id)
                values (?, ?, 7.50, 'CASH', ?) returning id
                """, Long.class, registration, username + "charge", user);
        jdbc.update("update outp_order set charge_id=? where id=?", charge, order);
        Object before = snapshot(); assertEquals(4007, code(() -> action(5))); assertEquals(before, snapshot());
    }
}
