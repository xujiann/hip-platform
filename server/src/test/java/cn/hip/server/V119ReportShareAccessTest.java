package cn.hip.server;

import cn.hip.medtech.web.ReportShareController;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.Masking;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real committed PG transactions, without a test-level transaction. Every coordinated wait
 * observes pg_stat_activity Lock before its owner can commit. These are method-security and
 * transaction tests; JWT transport is tested separately. Managed entities are deliberately
 * composed service calls, not a claim about HTTP request metadata or an external clock mock.
 */
@SpringBootTest
class V119ReportShareAccessTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ReportShareController shares;
    @Autowired OutpOrderRepository orders;
    @Autowired EntityManager em;

    record Staff(Long id, String name, String role) {}
    final List<Staff> employees = new ArrayList<>();
    Staff tech;
    Long dept, patient, otherPatient, schedule, registration, otherRegistration, order, otherOrder, exam, otherExam;
    String tag, link;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext();
        tag = "R119" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag, tag);
        tech = employee("t", "TECHNICIAN");
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'张模拟患者','U') returning id", Long.class, tag);
        otherPatient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'李另一患者','U') returning id", Long.class, tag + "p2");
        schedule = jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,2,2,0) returning id", Long.class, dept);
        registration = registration(patient, 1); otherRegistration = registration(otherPatient, 2);
        order = order(registration, "原检查"); otherOrder = order(otherRegistration, "另一检查");
        paidBill(registration, order); paidBill(otherRegistration, otherOrder);
        exam = exam(order, "原所见", "原结论"); otherExam = exam(otherOrder, "另一患者所见", "另一患者结论");
        link = tag + "token";
        jdbc.update("insert into report_share(token,exam_id,expire_at) values (?,?,clock_timestamp()+interval '1 hour')", link, exam);
        authenticate(tech);
    }

    Staff employee(String suffix, String role) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id", Long.class, name, name, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        var actor = new Staff(id, name, role); employees.add(actor); return actor;
    }
    Long registration(Long p, int no) {
        return jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status) values (?,?,?,?,?,current_date-1,0,'VISITED') returning id", Long.class, no, p, schedule, dept, tech.id());
    }
    Long order(Long r, String item) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id,remark)
                values (?,?,'EXAM',0,?,?,'次',1,7.50,7.50,'CHARGED',?,'原备注') returning id
                """, Long.class, r, tag, tag + r, item, tech.id());
    }
    Long exam(Long o, String findings, String impression) {
        return jdbc.queryForObject("""
                insert into ris_exam(order_id,status,findings,impression,reporter_id,verifier_id,reported_at,verified_at,modality)
                values (?,'VERIFIED',?,?,?,?,now(),now(),'CT') returning id
                """, Long.class, o, findings, impression, tech.id(), tech.id());
    }
    void paidBill(Long r, Long o) {
        Long bill = jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status,cashier_id) values (?,?,7.50,'CASH','PAID',?) returning id", Long.class, tag + r, r, tech.id());
        jdbc.update("update outp_order set charge_id=? where id=?", bill, o);
    }
    void authenticate(Staff actor) {
        if (actor == null) { SecurityContextHolder.clearContext(); return; }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.name(), null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()))));
    }
    TransactionTemplate tx() { var tx = new TransactionTemplate(manager); tx.setTimeout(20); return tx; }
    int attempt(IntSupplier operation) {
        try { return operation.getAsInt(); }
        catch (HipBizException e) { return e.code; }
        catch (AccessDeniedException | AuthenticationCredentialsNotFoundException e) { return 1005; }
    }
    int share() { return shares.share(exam, 60).getCode(); }
    int revoke() { return shares.revoke(exam).getCode(); }
    int view() { return shares.view(link).getCode(); }
    int operation(String op) { return switch (op) { case "share" -> share(); case "revoke" -> revoke(); case "view" -> view(); default -> throw new IllegalArgumentException(op); }; }
    long count() { return jdbc.queryForObject("select access_cnt::bigint from report_share where token=?", Long.class, link); }
    Object clinical() {
        return List.of(jdbc.queryForList("select * from outp_order where registration_id in (?,?) order by id", registration, otherRegistration),
                jdbc.queryForList("select * from ris_exam where order_id in (?,?) order by id", order, otherOrder),
                jdbc.queryForList("select * from outp_registration where id in (?,?) order by id", registration, otherRegistration),
                jdbc.queryForList("select * from outp_charge where registration_id in (?,?) order by id", registration, otherRegistration),
                jdbc.queryForList("select * from outp_schedule where id=?", schedule),
                jdbc.queryForList("select * from empi_patient where id in (?,?) order by id", patient, otherPatient));
    }
    Object facts() { return List.of(clinical(), jdbc.queryForList("select * from report_share where token like ? or exam_id in (?,?) order by id", tag + "%", exam, otherExam)); }
    void lock(String table) {
        String sql = switch (table) { case "O" -> "select id from outp_order where id=? for update"; case "E" -> "select id from ris_exam where id=? for update"; case "S" -> "select id from report_share where token=? for update"; default -> throw new IllegalArgumentException(table); };
        assertEquals(1, jdbc.queryForList(sql, table.equals("O") ? order : table.equals("E") ? exam : link).size());
    }
    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, Staff actor, IntSupplier operation) {
        return pool.submit(() -> {
            authenticate(actor);
            try { return attempt(() -> tx().execute(t -> { pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return operation.getAsInt(); })); }
            finally { SecurityContextHolder.clearContext(); }
        });
    }
    int result(Future<Integer> future) throws Exception {
        try { return future.get(20, TimeUnit.SECONDS); }
        catch (ExecutionException e) { return fail("Unexpected runtime or SQL failure instead of a readable outcome", e.getCause()); }
    }
    void waiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')", Boolean.class, pid.get()))) {
                System.out.println("V119 PG_LOCK observed pid=" + pid.get()); return;
            }
            if (future.isDone()) fail("Operation completed without the required PostgreSQL Lock wait");
            try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Expected a real PostgreSQL Lock wait");
    }
    void close(ExecutorService pool) throws Exception { pool.shutdown(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Workers finish before cleanup"); }
    void assertStrongOrderLock() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("select id from outp_order where id=? for no key update nowait")) {
                statement.setLong(1, order);
                try (var ignored = statement.executeQuery()) { fail("Worker must hold a strong O lock before waiting for E, not only an FK KEY SHARE"); }
            } catch (java.sql.SQLException e) { assertEquals("55P03", e.getSQLState()); }
            finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
            return null;
        });
    }
    void crossExpiry() {
        jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from(expire_at-clock_timestamp())))::double precision+0.08) from report_share where token=?", Object.class, link);
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("select expire_at < clock_timestamp() from report_share where token=?", Boolean.class, link)));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        tx().executeWithoutResult(t -> {
            jdbc.update("delete from report_share where token like ? or exam_id in (?,?)", tag + "%", exam, otherExam);
            jdbc.update("delete from ris_exam where id in (?,?)", exam, otherExam);
            jdbc.update("delete from outp_order where registration_id in (?,?)", registration, otherRegistration);
            jdbc.update("delete from outp_charge where registration_id in (?,?)", registration, otherRegistration);
            jdbc.update("delete from outp_registration where id in (?,?)", registration, otherRegistration);
            jdbc.update("delete from outp_schedule where id=?", schedule);
            jdbc.update("delete from empi_patient where id in (?,?)", patient, otherPatient);
            for (var staff : employees) { jdbc.update("delete from sys_user_role where user_id=?", staff.id()); jdbc.update("delete from sys_user where id=?", staff.id()); }
            jdbc.update("delete from sys_dept where id=?", dept);
        });
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,CHARGED", "TECHNICIAN,CHARGED", "DOCTOR_OUTP,CHARGED", "ADMIN,EXECUTED", "TECHNICIAN,EXECUTED", "DOCTOR_OUTP,EXECUTED"})
    void threeDutiesRetainHistoricalChargedAndExecutedSharingAndAnonymousMaskedRead(String role, String status) {
        authenticate(employee("legal" + role, role)); jdbc.update("update outp_order set status=? where id=?", status, order); Object original = clinical();
        var shared = shares.share(exam, 4320); assertEquals(0, shared.getCode());
        var data = shared.getData(); assertEquals(java.util.Set.of("token", "url", "expireMinutes"), data.keySet());
        String token = (String) data.get("token"); assertEquals("/api/share/" + token, data.get("url")); assertEquals(4320, data.get("expireMinutes"));
        assertEquals(32, token.length());
        var duration = jdbc.queryForObject("select extract(epoch from(expire_at-created_at))::bigint from report_share where token=?", Long.class, token);
        assertTrue(duration >= 259200 && duration <= 259201, "Default 72h is retained");
        authenticate(null); var viewed = shares.view(token); assertEquals(0, viewed.getCode());
        assertEquals(java.util.Set.of("patientName", "itemName", "modality", "findings", "impression", "verifiedAt"), viewed.getData().keySet());
        assertEquals(Masking.name("张模拟患者"), viewed.getData().get("patientName")); assertEquals("原检查", viewed.getData().get("itemName"));
        assertEquals("CT", viewed.getData().get("modality")); assertEquals("原所见", viewed.getData().get("findings")); assertEquals("原结论", viewed.getData().get("impression"));
        assertEquals(1, jdbc.queryForObject("select access_cnt from report_share where token=?", Integer.class, token)); assertEquals(original, clinical());
        authenticate(tech); var revoked = shares.revoke(exam); assertEquals(0, revoked.getCode()); assertEquals(Map.of("revoked", 2), revoked.getData());
        authenticate(null); assertEquals(4662, shares.view(token).getCode()); assertEquals(original, clinical());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "missing", "wrong-role", "none", "unauthenticated"})
    void shareAndRevokeRequireActualEnabledEmployeeAndAuthenticatedClinicalDuty(String kind) {
        switch (kind) {
            case "disabled" -> jdbc.update("update sys_user set enabled=false where id=?", tech.id());
            case "missing" -> authenticate(new Staff(-1L, tag + "ghost", "TECHNICIAN"));
            case "wrong-role" -> authenticate(employee("n", "NURSE"));
            case "none" -> authenticate(null);
            case "unauthenticated" -> SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(tech.name(), null));
            default -> fail("Unknown fixture kind");
        }
        Object original = facts(); assertEquals(1005, attempt(this::share)); assertEquals(original, facts());
        assertEquals(1005, attempt(this::revoke)); assertEquals(original, facts());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 43201, Integer.MAX_VALUE})
    void invalidExpiryPreserves4663AndWritesNothing(int minutes) { Object original = facts(); assertEquals(4663, shares.share(exam, minutes).getCode()); assertEquals(original, facts()); }

    @Test
    void maximumThirtyDaysRemainsLegalAndMissingObjectsKeepOriginalCodes() {
        assertEquals(0, shares.share(exam, 43200).getCode()); Object original = facts();
        assertEquals(4660, shares.share(-1L, 60).getCode()); assertEquals(Map.of("revoked", 0), shares.revoke(-1L).getData());
        authenticate(null); assertEquals(4661, shares.view(tag + "missing").getCode()); assertEquals(original, facts());
        jdbc.update("update report_share set expire_at=clock_timestamp()-interval '1 second' where token=?", link);
        Object expired = facts(); assertEquals(4662, view()); assertEquals(expired, facts());
    }

    @ParameterizedTest
    @CsvSource({"EXAM,CREATED", "EXAM,CANCELLED", "EXAM,DISPENSED", "LAB,CHARGED", "TREAT,CHARGED", "DRUG,CHARGED"})
    void historicalVerifiedReportCannotPublishOrExposeAnIneligibleSource(String type, String status) {
        jdbc.update("update outp_order set order_type=?,status=? where id=?", type, status, order); Object original = facts();
        assertEquals(4660, share()); assertEquals(original, facts()); authenticate(null); assertEquals(4660, view()); assertEquals(original, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REGISTERED", "ARRIVED", "REPORTED"})
    void unverifiedReportCannotCreateOrReadAnExistingLink(String status) {
        jdbc.update("update ris_exam set status=? where id=?", status, exam); Object original = facts();
        assertEquals(4660, share()); assertEquals(original, facts()); authenticate(null); assertEquals(4660, view()); assertEquals(original, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "report"})
    void revokeRetainsCleanupForAnUnpublishableHistoricalReport(String kind) {
        if (kind.equals("source")) jdbc.update("update outp_order set status='CREATED' where id=?", order); else jdbc.update("update ris_exam set status='REPORTED' where id=?", exam);
        Object original = clinical(); assertEquals(Map.of("revoked", 1), shares.revoke(exam).getData()); assertEquals(original, clinical());
        assertEquals(Map.of("revoked", 0), shares.revoke(exam).getData()); assertEquals(0, count());
    }

    @ParameterizedTest
    @CsvSource({"share,O", "share,E", "revoke,O", "revoke,E", "revoke,S"})
    void writesRecheckEmployeeAfterEveryActualLockWait(String op, String held) throws Exception {
        Object original = facts(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            tx().executeWithoutResult(t -> { lock(held); var future = worker(pool, pid, tech, () -> operation(op)); futures.add(future); waiting(pid, future); jdbc.update("update sys_user set enabled=false where id=?", tech.id()); });
            assertEquals(1005, result(futures.getFirst())); assertEquals(original, facts());
        } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"share", "revoke", "view"})
    void allEntrypointsHoldStrongSourceBeforeWaitingOnExam(String op) throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            tx().executeWithoutResult(t -> { lock("E"); var future = worker(pool, pid, op.equals("view") ? null : tech, () -> operation(op)); futures.add(future); waiting(pid, future); assertStrongOrderLock(); });
            assertEquals(0, result(futures.getFirst()));
        } finally { close(pool); }
    }

    @ParameterizedTest
    @CsvSource({"share,O,source", "share,E,report", "view,O,source", "view,E,report"})
    void lateSourceOrReportWithdrawalIsReadAfterWaitAndNeverCountsOrCreatesALink(String op, String held, String changed) throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>(); Object[] committed = new Object[1];
        try {
            tx().executeWithoutResult(t -> {
                lock(held); var future = worker(pool, pid, op.equals("view") ? null : tech, () -> operation(op)); futures.add(future); waiting(pid, future);
                if (changed.equals("source")) jdbc.update("update outp_order set status='CREATED' where id=?", order); else jdbc.update("update ris_exam set status='REPORTED' where id=?", exam);
                committed[0] = facts();
            });
            assertEquals(4660, result(futures.getFirst())); assertEquals(committed[0], facts());
        } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"share", "view", "revoke"})
    void originalExamCannotFollowAnOrderRebindingIntoAnotherPatient(String op) throws Exception {
        // Free O2, then move E1 while the request is waiting on its original O1.
        jdbc.update("delete from ris_exam where id=?", otherExam); otherExam = null;
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>(); Object[] committed = new Object[1];
        try {
            tx().executeWithoutResult(t -> {
                lock("O"); var future = worker(pool, pid, op.equals("view") ? null : tech, () -> operation(op)); futures.add(future); waiting(pid, future);
                jdbc.update("update ris_exam set order_id=? where id=?", otherOrder, exam); committed[0] = facts();
            });
            int expected = op.equals("share") ? 4660 : op.equals("view") ? 4661 : 0;
            assertEquals(expected, result(futures.getFirst())); assertEquals(committed[0], facts(), "Do not publish, count or revoke links for the new patient binding");
        } finally { close(pool); }
    }

    @Test
    void anonymousTokenCannotFollowShareRebindingWhileWaitingForTheOriginalSource() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>(); Object[] committed = new Object[1];
        try {
            tx().executeWithoutResult(t -> {
                lock("O"); var future = worker(pool, pid, null, this::view); futures.add(future); waiting(pid, future);
                jdbc.update("update report_share set exam_id=? where token=?", otherExam, link); committed[0] = facts();
            });
            assertEquals(4661, result(futures.getFirst())); assertEquals(committed[0], facts());
        } finally { close(pool); }
    }

    @Test
    void anonymousReadRechecksActualDatabaseTimeAfterShareWaitCrossesExpiry() throws Exception {
        jdbc.update("update report_share set expire_at=clock_timestamp()+interval '1 second' where token=?", link);
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        try {
            tx().executeWithoutResult(t -> { lock("S"); var future = worker(pool, pid, null, this::view); futures.add(future); waiting(pid, future); crossExpiry(); });
            Object expired = facts(); assertEquals(4662, result(futures.getFirst())); assertEquals(expired, facts()); assertEquals(0, count());
        } finally { close(pool); }
    }

    @Test
    void revokeDoesNotCountOrRewriteLinkThatExpiredDuringActualShareWait() throws Exception {
        jdbc.update("update report_share set expire_at=clock_timestamp()+interval '1 second' where token=?", link);
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        var revoked = new AtomicInteger(-1);
        try {
            tx().executeWithoutResult(t -> { lock("S"); var future = worker(pool, pid, tech, () -> { var r = shares.revoke(exam); if (r.getCode() == 0) revoked.set(((Number) r.getData().get("revoked")).intValue()); return r.getCode(); }); futures.add(future); waiting(pid, future); crossExpiry(); });
            Object expired = facts(); assertEquals(0, result(futures.getFirst())); assertEquals(0, revoked.get()); assertEquals(expired, facts());
        } finally { close(pool); }
    }

    @Test
    void newLinkExpiryStartsAfterExamLockRatherThanAtTheWaitingTransactionsStart() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>(); var released = new Instant[1];
        try {
            tx().executeWithoutResult(t -> {
                lock("E"); var future = worker(pool, pid, tech, this::share); futures.add(future); waiting(pid, future);
                jdbc.queryForObject("select pg_sleep(0.35)", Object.class); released[0] = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
            });
            assertEquals(0, result(futures.getFirst()));
            Instant expires = jdbc.queryForObject("select expire_at from report_share where exam_id=? and token<>?", Timestamp.class, exam, link).toInstant();
            assertFalse(expires.isBefore(released[0].plusSeconds(3600)), "A lock wait must not consume the newly promised TTL");
        } finally { close(pool); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoke-first", "view-first"})
    void revokeAndReadSerializeBothOrdersWithExactAccessCount(String order) throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var futures = new ArrayList<Future<Integer>>();
        boolean revokeFirst = order.equals("revoke-first");
        try {
            tx().executeWithoutResult(t -> {
                if (revokeFirst) assertEquals(Map.of("revoked", 1), shares.revoke(exam).getData()); else assertEquals(0, view());
                var future = worker(pool, pid, revokeFirst ? null : tech, revokeFirst ? this::view : this::revoke); futures.add(future); waiting(pid, future);
            });
            assertEquals(revokeFirst ? 4662 : 0, result(futures.getFirst())); assertEquals(revokeFirst ? 0 : 1, count());
            authenticate(null); assertEquals(4662, view()); assertEquals(revokeFirst ? 0 : 1, count());
        } finally { close(pool); }
    }

    @Test
    void sixActualWaitingAnonymousReadsCountExactlyOnceEachAfterTheOwnerRead() throws Exception {
        Object original = clinical(); var pool = Executors.newFixedThreadPool(6); var futures = new ArrayList<Future<Integer>>();
        try {
            tx().executeWithoutResult(t -> {
                assertEquals(0, view());
                for (int i = 0; i < 6; i++) { var pid = new AtomicInteger(); var future = worker(pool, pid, null, this::view); futures.add(future); waiting(pid, future); }
            });
            int success = 0, failed = 0; for (var future : futures) { if (result(future) == 0) success++; else failed++; }
            assertEquals(6, success); assertEquals(0, failed); assertEquals(7, count()); assertEquals(original, clinical());
        } finally { close(pool); }
    }

    @Test
    void failedShareInsertReallyRollsBackAndTheSameActorCanRetry() {
        Object original = facts(); String constraint = "v119_insert_" + tag.toLowerCase();
        jdbc.execute("alter table report_share add constraint " + constraint + " check(exam_id<>" + exam + " or token='" + link + "') not valid");
        try { assertThrows(RuntimeException.class, this::share); assertEquals(original, facts()); }
        finally { jdbc.execute("alter table report_share drop constraint " + constraint); }
        assertEquals(0, share()); assertEquals(2, jdbc.queryForObject("select count(*) from report_share where exam_id=?", Integer.class, exam));
    }

    @Test
    void failedAnonymousCountRollsBackWithoutAVisitAndCanRetryExactlyOnce() {
        Object original = facts(); String constraint = "v119_count_" + tag.toLowerCase();
        jdbc.execute("alter table report_share add constraint " + constraint + " check(token<>'" + link + "' or access_cnt=0) not valid");
        authenticate(null);
        try { assertThrows(RuntimeException.class, this::view); assertEquals(original, facts()); }
        finally { jdbc.execute("alter table report_share drop constraint " + constraint); }
        assertEquals(0, view()); assertEquals(1, count());
    }

    @Test
    void failedRevokeStatementRollsBackAllLinksAndCanRetry() {
        assertEquals(0, share()); Object original = facts(); String constraint = "v119_revoke_" + tag.toLowerCase();
        jdbc.execute("alter table report_share add constraint " + constraint + " check(exam_id<>" + exam + " or expire_at>created_at+interval '1 minute') not valid");
        try { assertThrows(RuntimeException.class, this::revoke); assertEquals(original, facts()); }
        finally { jdbc.execute("alter table report_share drop constraint " + constraint); }
        assertEquals(Map.of("revoked", 2), shares.revoke(exam).getData());
    }

    @Test
    void composedManagedSourceChangeIsFlushedBeforeSharingAndCallerCanRollBackItsOwnChange() {
        Object original = facts();
        var rejected = assertThrows(HipBizException.class, () -> tx().executeWithoutResult(t -> {
            orders.findById(order).orElseThrow().setOrderType("LAB");
            int code = share(); assertEquals(4660, code); throw new HipBizException(code, "caller rolls back its own pending source update");
        }));
        assertEquals(4660, rejected.code); assertEquals(original, facts()); assertEquals(0, share());
    }

    @Test
    void composedManagedMetadataIsFlushedWithoutBeingLostOrExecutingTheSource() {
        tx().executeWithoutResult(t -> {
            orders.findById(order).orElseThrow().setRemark("组合事务新备注"); assertEquals(0, share());
            assertEquals("组合事务新备注", jdbc.queryForObject("select remark from outp_order where id=?", String.class, order)); em.flush();
        });
        assertEquals("CHARGED", jdbc.queryForObject("select status from outp_order where id=?", String.class, order));
        assertEquals("组合事务新备注", jdbc.queryForObject("select remark from outp_order where id=?", String.class, order));
    }
}
