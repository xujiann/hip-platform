package cn.hip.server;

import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.QueueController;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** No enclosing test transaction: each race proves a PostgreSQL Lock wait and its committed result. */
@SpringBootTest
class V108QueueRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired RegistrationService registrations;
    @Autowired DoctorStationService doctors;
    @Autowired QueueController queue;
    @Autowired OutpRegistrationRepository repository;
    @Autowired EntityManager em;

    Long dept, patient, schedule, registration, user;
    String username;

    @BeforeEach
    void setup() {
        String tag = "Q108" + Long.toUnsignedString(System.nanoTime(), 36);
        username = tag;
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag, tag);
        user = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id",
                Long.class, username, username, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='DOCTOR_OUTP'", user);
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",
                Long.class, tag, tag);
        schedule = jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,fee) values (?,?::date,5,5) returning id",
                Long.class, dept, BusinessDates.today().toString());
        authenticate();
        registration = registrations.register(patient, schedule).getId();
    }

    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP"))));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            if (registration != null) {
                jdbc.update("delete from outp_call_log where registration_id=?", registration);
                jdbc.update("delete from outp_order where registration_id=?", registration);
                jdbc.update("delete from outp_registration where id=?", registration);
            }
            if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
            if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
            if (user != null) {
                jdbc.update("delete from sys_user_role where user_id=?", user);
                jdbc.update("delete from sys_user where id=?", user);
            }
            if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
        });
    }

    void state(String status) {
        jdbc.update("update outp_registration set status=? where id=?", status, registration);
    }

    void lockRegistration() {
        assertEquals(List.of(registration), jdbc.queryForList("select id from outp_registration where id=? for update",
                Long.class, registration));
    }

    int action(int kind) {
        return switch (kind) {
            case 0 -> queue.pass(registration).getCode();
            case 1 -> queue.recall(registration).getCode();
            case 2 -> queue.callNext(dept).getCode();
            default -> throw new IllegalArgumentException("Unknown queue action");
        };
    }

    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, IntSupplier operation) {
        return pool.submit(() -> {
            authenticate();
            try {
                return new TransactionTemplate(manager).execute(tx -> {
                    pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return operation.getAsInt();
                });
            } catch (HipBizException e) {
                return e.code;
            } catch (AccessDeniedException e) {
                return 1005;
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    void assertWaiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",
                    Boolean.class, pid.get()))) return;
            if (future.isDone()) fail("Queue writer finished without the required PostgreSQL Lock wait");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        fail("Queue writer must actually wait on the held registration row");
    }

    void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "All workers must finish before fixture cleanup");
    }

    void assertResult(String status, Long doctor, int booked, int logs, String orderStatus) {
        assertResult(status, doctor, booked, logs, orderStatus, BusinessDates.today());
    }

    void assertResult(String status, Long doctor, int booked, int logs, String orderStatus, LocalDate date) {
        var row = jdbc.queryForMap("select status,doctor_id,reg_no,patient_id,schedule_id,dept_id,visit_date,fee,cancelled_at from outp_registration where id=?",
                registration);
        assertEquals(status, row.get("status"));
        assertEquals(doctor, row.get("doctor_id"));
        assertEquals(1, ((Number) row.get("reg_no")).intValue());
        assertEquals(patient, row.get("patient_id"));
        assertEquals(schedule, row.get("schedule_id"));
        assertEquals(dept, row.get("dept_id"));
        assertEquals(date.toString(), row.get("visit_date").toString());
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) row.get("fee")));
        if ("CANCELLED".equals(status)) assertNotNull(row.get("cancelled_at"));
        else assertNull(row.get("cancelled_at"));
        assertEquals(booked, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(1, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(logs, jdbc.queryForObject("select count(*) from outp_call_log where registration_id=?", Integer.class, registration));
        assertEquals(logs, jdbc.queryForObject("select count(*) from outp_call_log where registration_id=? and dept_id=? and reg_no=1",
                Integer.class, registration, dept));
        assertEquals(1, jdbc.queryForObject("select count(*) from outp_order where registration_id=?", Integer.class, registration));
        assertEquals(orderStatus, jdbc.queryForObject("select status from outp_order where registration_id=?", String.class, registration));
        assertEquals(0, jdbc.queryForObject("select count(*) from outp_appointment where schedule_id=?", Integer.class, schedule));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passWaitsForVisitCommitOrRollbackWithoutOverwritingDoctor(boolean rollback) throws Exception {
        state("CALLED");
        var pool = Executors.newSingleThreadExecutor();
        var pid = new AtomicInteger();
        var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                doctors.startVisit(registration, user);
                em.flush();
                var result = worker(pool, pid, () -> action(0));
                results.add(result);
                assertWaiting(pid, result);
                if (rollback) tx.setRollbackOnly();
            });
            assertEquals(rollback ? 0 : 3302, results.getFirst().get(10, TimeUnit.SECONDS));
            assertResult(rollback ? "PASSED" : "VISITED", rollback ? null : user, 1, 0, "CREATED");
        } finally {
            shutdown(pool);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recallWaitsForEarlierRecallAndVisitAndRollsBackItsLog(boolean rollback) throws Exception {
        state("PASSED");
        var pool = Executors.newSingleThreadExecutor();
        var pid = new AtomicInteger();
        var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                assertEquals(0, action(1));
                doctors.startVisit(registration, user);
                em.flush();
                var result = worker(pool, pid, () -> action(1));
                results.add(result);
                assertWaiting(pid, result);
                if (rollback) tx.setRollbackOnly();
            });
            assertEquals(rollback ? 0 : 3303, results.getFirst().get(10, TimeUnit.SECONDS));
            assertResult(rollback ? "CALLED" : "VISITED", rollback ? null : user, 1, 1, "CREATED");
        } finally {
            shutdown(pool);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void twoWaitingIdenticalActionsHaveExactlyOneWinner(int kind) throws Exception {
        state(kind == 0 ? "CALLED" : kind == 1 ? "PASSED" : "REGISTERED");
        var pool = Executors.newFixedThreadPool(2);
        var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockRegistration();
                for (int i = 0; i < 2; i++) {
                    var pid = new AtomicInteger();
                    var result = worker(pool, pid, () -> action(kind));
                    results.add(result);
                    assertWaiting(pid, result);
                }
            });
            var codes = List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(1, codes.stream().filter(code -> code == 0).count());
            assertEquals(1, codes.stream().filter(code -> code == (kind == 1 ? 3303 : 3302)).count());
            assertResult(kind == 0 ? "PASSED" : "CALLED", null, 1, kind == 0 ? 0 : 1, "CREATED");
        } finally {
            shutdown(pool);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void callingAndCancellationSerializeWithCommitAndRollback(int kind) throws Exception {
        boolean callFirst = kind >= 2;
        boolean rollback = kind % 2 == 1;
        var pool = Executors.newSingleThreadExecutor();
        var pid = new AtomicInteger();
        var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                if (callFirst) assertEquals(0, action(2));
                else registrations.cancel(registration);
                var result = worker(pool, pid, () -> {
                    if (!callFirst) return action(2);
                    registrations.cancel(registration);
                    return 0;
                });
                results.add(result);
                assertWaiting(pid, result);
                if (rollback) tx.setRollbackOnly();
            });
            assertEquals(rollback ? 0 : callFirst ? 3005 : 3302, results.getFirst().get(10, TimeUnit.SECONDS));
            boolean cancelled = callFirst == rollback;
            assertResult(cancelled ? "CANCELLED" : "CALLED", null, cancelled ? 0 : 1,
                    cancelled ? 0 : 1, cancelled ? "CANCELLED" : "CREATED");
        } finally {
            shutdown(pool);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void eachWaitingQueueWriteRechecksEnabledOperatorBeforeMutation(int kind) throws Exception {
        state(kind == 0 ? "CALLED" : kind == 1 ? "PASSED" : "REGISTERED");
        var pool = Executors.newSingleThreadExecutor();
        var pid = new AtomicInteger();
        var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockRegistration();
                var result = worker(pool, pid, () -> action(kind));
                results.add(result);
                assertWaiting(pid, result);
                jdbc.update("update sys_user set enabled=false where id=?", user);
            });
            assertEquals(1005, results.getFirst().get(10, TimeUnit.SECONDS));
            assertResult(kind == 0 ? "CALLED" : kind == 1 ? "PASSED" : "REGISTERED", null, 1, 0, "CREATED");
        } finally {
            shutdown(pool);
        }
    }

    @Test
    void preloadedEntityCannotOverwriteVisitCommittedDuringTheWait() throws Exception {
        state("CALLED");
        var pool = Executors.newSingleThreadExecutor();
        var pid = new AtomicInteger();
        var loaded = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        try {
            var result = worker(pool, pid, () -> {
                assertEquals("CALLED", repository.findById(registration).orElseThrow().getStatus());
                loaded.countDown();
                try {
                    assertTrue(proceed.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return action(0);
            });
            assertTrue(loaded.await(10, TimeUnit.SECONDS));
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                doctors.startVisit(registration, user);
                em.flush();
                proceed.countDown();
                assertWaiting(pid, result);
            });
            assertEquals(3302, result.get(10, TimeUnit.SECONDS));
            assertResult("VISITED", user, 1, 0, "CREATED");
        } finally {
            proceed.countDown();
            shutdown(pool);
        }
    }

    @Test
    void waitingWriterRechecksTheCurrentObjectDate() throws Exception {
        state("CALLED");
        LocalDate yesterday = BusinessDates.today().minusDays(1);
        var pool = Executors.newSingleThreadExecutor();
        var pid = new AtomicInteger();
        var results = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                lockRegistration();
                jdbc.update("update outp_registration set visit_date=?::date where id=?", yesterday.toString(), registration);
                var result = worker(pool, pid, () -> action(0));
                results.add(result);
                assertWaiting(pid, result);
            });
            assertEquals(1005, results.getFirst().get(10, TimeUnit.SECONDS));
            assertResult("CALLED", null, 1, 0, "CREATED", yesterday);
        } finally {
            shutdown(pool);
        }
    }
}