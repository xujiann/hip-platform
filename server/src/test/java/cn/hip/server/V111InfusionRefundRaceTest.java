package cn.hip.server;

import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.web.OutpNurseStationController;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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
 * No enclosing test transaction: every participant commits or rolls back its own transaction.
 * Races explicitly observe pg_stat_activity.wait_event_type=Lock. Context identities and injected
 * Authentication arguments name real enabled fixture employees. No HTTP/JWT claim is made here.
 * Drug quantity/days do not create extra infusion sessions, dispense stock or imply a bottle model.
 */
@SpringBootTest
class V111InfusionRefundRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired OutpNurseStationController nursing;
    @Autowired ChargeService charges;

    record Staff(Long id, String name, String role) {}
    Staff nurse, colleague, admin, cashier;
    Long dept, patient, schedule, registration, drug, order, charge, infusion;
    String tag;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext();
        tag = "I111" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag, tag);
        nurse = employee("n", "NURSE"); colleague = employee("b", "NURSE");
        admin = employee("a", "ADMIN"); cashier = employee("c", "CASHIER");
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",
                Long.class, tag, tag);
        schedule = jdbc.queryForObject("""
                insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee)
                values (?,?::date,5,1,1,0) returning id
                """, Long.class, dept, BusinessDates.today().toString());
        registration = jdbc.queryForObject("""
                insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status)
                values (1,?,?,?,?::date,0,'VISITED') returning id
                """, Long.class, patient, schedule, dept, BusinessDates.today().toString());
        drug = jdbc.queryForObject("""
                insert into md_drug(code,name,spec,unit,dose_form,price,stock,antibiotic,abx_level,drug_class,enabled)
                values (?,?,'10ml','支','注射剂',7.50,99,false,0,'W',true) returning id
                """, Long.class, tag, "测试药" + tag);
        order = jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,
                                       unit,qty,unit_price,amount,usage_route,days,status)
                values (?,?,'DRUG',?,?,?,'支',2,7.50,15.00,'静脉滴注',2,'CREATED') returning id
                """, Long.class, registration, tag, drug, tag, "测试药" + tag);
        authenticate(cashier);
        charge = charges.settle(registration, "CASH", cashier.id()).getId();
        infusion = jdbc.queryForObject("insert into outp_infusion(order_id) values (?) returning id", Long.class, order);
        authenticate(nurse);
    }

    Staff employee(String suffix, String role) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, name, name, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        return new Staff(id, name, role);
    }
    Authentication authenticate(Staff actor) {
        Authentication auth = new UsernamePasswordAuthenticationToken(actor.name(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + actor.role())));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }
    Authentication authentication() { return SecurityContextHolder.getContext().getAuthentication(); }
    int create() { return nursing.createInfusion(order).getCode(); }
    int start() { return nursing.start(infusion, authentication()).getCode(); }
    int check() { return nursing.addCheck(infusion, "滴速正常" + tag, authentication()).getCode(); }
    int finish() { return nursing.finish(infusion).getCode(); }
    int refund() { charges.refund(charge, cashier.id()); return 0; }
    int action(int kind) {
        return switch (kind) {
            case 0 -> create(); case 1 -> start(); case 2 -> check(); case 3 -> finish();
            default -> throw new IllegalArgumentException("Unknown nursing action");
        };
    }
    void noInfusion() {
        jdbc.update("delete from outp_infusion where order_id=?", order);
    }
    void running() {
        authenticate(nurse); assertEquals(0, start());
    }
    void prepare(int kind) {
        if (kind == 0) noInfusion();
        if (kind == 2 || kind == 3) running();
    }
    int business(IntSupplier operation) {
        try { return operation.getAsInt(); }
        catch (HipBizException e) { return e.code; }
        catch (AccessDeniedException e) { return 1005; }
    }
    TransactionTemplate transaction() {
        var tx = new TransactionTemplate(manager); tx.setTimeout(15); return tx;
    }
    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, Staff actor, IntSupplier action) {
        return pool.submit(() -> {
            authenticate(actor);
            try {
                return business(() -> transaction().execute(tx -> {
                    pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return action.getAsInt();
                }));
            } finally { SecurityContextHolder.clearContext(); }
        });
    }
    int result(Future<Integer> future) throws Exception {
        try { return future.get(15, TimeUnit.SECONDS); }
        catch (ExecutionException e) { return fail("Unexpected runtime/SQL failure instead of a readable business result", e.getCause()); }
    }
    void waiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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
    void close(ExecutorService pool) throws Exception {
        pool.shutdown(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Workers finish before fixture cleanup");
    }
    void lockOrder() {
        assertEquals(List.of(order), jdbc.queryForList("select id from outp_order where id=? for update", Long.class, order));
    }
    void lockInfusion() {
        assertEquals(List.of(infusion), jdbc.queryForList("select id from outp_infusion where id=? for update", Long.class, infusion));
    }
    /** A waiter at I must already hold O; a savepoint keeps the intentional NOWAIT miss from aborting this transaction. */
    void assertWaitingWorkerOwnsSourceOrder() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("select id from outp_order where id=? for update nowait")) {
                statement.setLong(1, order);
                try (var ignored = statement.executeQuery()) {
                    fail("Nursing waiter at infusion row must already own the source order lock (O then I)");
                }
            } catch (java.sql.SQLException expected) {
                assertEquals("55P03", expected.getSQLState(), "Only the intentional lock-not-available response is expected");
            } finally {
                connection.rollback(savepoint); connection.releaseSavepoint(savepoint);
            }
            return null;
        });
    }
    Map<String,Object> infusionRow() { return jdbc.queryForMap("select * from outp_infusion where id=?", infusion); }
    Object snapshot() {
        return List.of(
                jdbc.queryForList("select * from outp_order where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_infusion where order_id=? order by id", order),
                jdbc.queryForList("select c.* from outp_infusion_check c join outp_infusion i on i.id=c.infusion_id where i.order_id=? order by c.id", order),
                jdbc.queryForList("select stock from md_drug where id=?", drug),
                jdbc.queryForList("select booked,last_issued_no from outp_schedule where id=?", schedule));
    }
    void money(String orderStatus, String chargeStatus, boolean linked) {
        var o = jdbc.queryForMap("select status,charge_id,qty,days,amount,item_id from outp_order where id=?", order);
        assertEquals(orderStatus, o.get("status"));
        assertEquals(linked ? charge : null, o.get("charge_id"));
        assertEquals(2, ((Number) o.get("qty")).intValue()); assertEquals(2, ((Number) o.get("days")).intValue());
        assertEquals(drug, o.get("item_id"));
        assertEquals(0, new BigDecimal("15.00").compareTo((BigDecimal) o.get("amount")));
        var c = jdbc.queryForMap("select status,total_amount from outp_charge where id=?", charge);
        assertEquals(chargeStatus, c.get("status"));
        assertEquals(0, new BigDecimal("15.00").compareTo((BigDecimal) c.get("total_amount")));
        assertEquals(1, jdbc.queryForObject("select count(*) from outp_charge where registration_id=?", Integer.class, registration));
        assertEquals(99, jdbc.queryForObject("select stock from md_drug where id=?", Integer.class, drug));
        assertEquals(1, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule));
        assertEquals(1, jdbc.queryForObject("select last_issued_no from outp_schedule where id=?", Integer.class, schedule));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        transaction().executeWithoutResult(tx -> {
            if (registration != null) {
                jdbc.update("delete from outp_infusion_check where infusion_id in(select i.id from outp_infusion i join outp_order o on o.id=i.order_id where o.registration_id=?)", registration);
                jdbc.update("delete from outp_infusion where order_id in(select id from outp_order where registration_id=?)", registration);
                jdbc.update("delete from outp_skin_test where registration_id=?", registration);
                jdbc.update("delete from outp_order where registration_id=?", registration);
                jdbc.update("delete from outp_charge where registration_id=?", registration);
                jdbc.update("delete from outp_registration where id=?", registration);
            }
            if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
            if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
            if (drug != null) jdbc.update("delete from md_drug where id=?", drug);
            for (Staff actor : new Staff[]{nurse, colleague, admin, cashier}) if (actor != null) {
                jdbc.update("delete from sys_user_role where user_id=?", actor.id());
                jdbc.update("delete from sys_user where id=?", actor.id());
            }
            if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
        });
    }

    @ParameterizedTest @ValueSource(strings={"ADMIN", "NURSE"})
    void enabledAdministratorAndNurseKeepTheWholeInfusionLifecycle(String role) {
        Staff actor = role.equals("ADMIN") ? admin : nurse; authenticate(actor); noInfusion();
        assertTrue(nursing.candidates().getData().stream().anyMatch(r -> order.equals(((Number) r.get("order_id")).longValue())));
        assertEquals(0, create());
        infusion = jdbc.queryForObject("select id from outp_infusion where order_id=?", Long.class, order);
        var listed = nursing.infusions().getData().stream().filter(r -> infusion.equals(((Number) r.get("id")).longValue())).findFirst().orElseThrow();
        assertEquals("CHARGED", listed.get("order_status"));
        assertEquals(0, start()); var started = infusionRow();
        assertEquals("RUNNING", started.get("status")); assertEquals(actor.id(), started.get("nurse_id"));
        assertNotNull(started.get("started_at")); assertNull(started.get("finished_at"));
        assertEquals(0, check()); assertEquals(0, finish());
        var done = infusionRow(); assertEquals("DONE", done.get("status")); assertNotNull(done.get("finished_at"));
        assertEquals(started.get("started_at"), done.get("started_at")); assertEquals(actor.id(), done.get("nurse_id"));
        assertEquals(actor.id(), jdbc.queryForObject("select nurse_id from outp_infusion_check where infusion_id=?", Long.class, infusion));
        assertEquals(1, jdbc.queryForObject("select count(*) from outp_infusion where order_id=?", Integer.class, order));
        money("CHARGED", "PAID", true);
    }

    @Test
    void differentNursesCanCollaborateOnADispensedDrugWithoutChangingItsStockOrStatus() {
        jdbc.update("update outp_order set status='DISPENSED' where id=?", order);
        authenticate(nurse); assertEquals(0, start()); var first = infusionRow();
        authenticate(colleague); assertEquals(0, check()); assertEquals(0, finish());
        assertEquals(nurse.id(), infusionRow().get("nurse_id"));
        assertEquals(first.get("started_at"), infusionRow().get("started_at"));
        assertEquals(colleague.id(), jdbc.queryForObject("select nurse_id from outp_infusion_check where infusion_id=?", Long.class, infusion));
        money("DISPENSED", "PAID", true);
    }

    @Test
    void creationKeepsTheExistingPaidDrugScopeWithoutReinterpretingFreeTextRoute() {
        noInfusion(); jdbc.update("update outp_order set usage_route='口服' where id=?", order);
        authenticate(nurse); assertEquals(0, create());
        assertEquals(1, jdbc.queryForObject("select count(*) from outp_infusion where order_id=? and status='PENDING'", Integer.class, order));
        money("CHARGED", "PAID", true);
    }

    @Test
    void sameVisitSameClassAnyNegativeSkinResultAndNoSkinDrugRulesStayUnchanged() {
        jdbc.update("update outp_order set item_name='阿莫西林注射液' where id=?", order);
        jdbc.update("insert into outp_skin_test(registration_id,drug_name,result,nurse_id) values (?,'头孢曲松','NEG',?)", registration, nurse.id());
        Object before = snapshot(); assertEquals(4502, start()); assertEquals(before, snapshot());
        jdbc.update("insert into outp_skin_test(registration_id,drug_name,result,nurse_id) values (?,'青霉素','NEG',?)", registration, nurse.id());
        jdbc.update("insert into outp_skin_test(registration_id,drug_name,result,nurse_id) values (?,'青霉素','POS',?)", registration, colleague.id());
        assertEquals(0, start(), "Existing policy accepts any same-class negative, not only the latest record");
        assertEquals("RUNNING", infusionRow().get("status")); money("CHARGED", "PAID", true);
    }

    @Test
    void pendingInfusionMayBeRefundedButCannotStartUntilItsSourceIsPaidAgain() {
        var pending = infusionRow(); authenticate(cashier); assertEquals(0, refund());
        assertEquals(pending, infusionRow(), "Refund preserves the pending clinical record");
        Object before = snapshot(); authenticate(nurse); assertEquals(4501, start()); assertEquals(before, snapshot());
        var listed = nursing.infusions().getData().stream().filter(r -> infusion.equals(((Number) r.get("id")).longValue())).findFirst().orElseThrow();
        assertEquals("PENDING", listed.get("status")); assertEquals("CREATED", listed.get("order_status"));
        money("CREATED", "REFUNDED", false);
    }

    @ParameterizedTest @ValueSource(strings={"RUNNING", "DONE"})
    void runningOrCompletedInfusionRejectsRefundWithoutChangingAnyMoneyOrCareRecord(String state) {
        running(); if (state.equals("DONE")) assertEquals(0, finish());
        Object before = snapshot(); authenticate(cashier);
        assertEquals(5005, business(this::refund)); assertEquals(before, snapshot()); money("CHARGED", "PAID", true);
    }

    @Test
    void dispensedRefundKeepsTheOriginal5004PriorityEvenWhenInfusionIsRunning() {
        jdbc.update("update outp_order set status='DISPENSED' where id=?", order); running();
        Object before = snapshot(); authenticate(cashier);
        assertEquals(5004, business(this::refund)); assertEquals(before, snapshot()); money("DISPENSED", "PAID", true);
    }

    @ParameterizedTest @ValueSource(ints={0,1})
    void refundCommittedFirstBlocksCreationOrStartAndWaitingNurseUsesTheNewSourceState(int kind) throws Exception {
        if (kind == 0) noInfusion();
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        try {
            authenticate(cashier);
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, refund()); lockOrder();
                var task = worker(pool, pid, nurse, () -> action(kind)); tasks.add(task); waiting(pid, task);
            });
            assertEquals(4501, result(tasks.getFirst())); money("CREATED", "REFUNDED", false);
            if (kind == 0) assertEquals(0, jdbc.queryForObject("select count(*) from outp_infusion where order_id=?", Integer.class, order));
            else { assertEquals("PENDING", infusionRow().get("status")); assertNull(infusionRow().get("started_at")); }
        } finally { close(pool); }
    }

    @Test
    void startCommittedFirstBlocksRefundAndItsWinnerKeepsTheExactMoneyAndCareState() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        final Object[] before = new Object[1];
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, start()); before[0] = snapshot();
                var task = worker(pool, pid, cashier, this::refund); tasks.add(task); waiting(pid, task);
            });
            assertEquals(5005, result(tasks.getFirst())); assertEquals(before[0], snapshot()); money("CHARGED", "PAID", true);
        } finally { close(pool); }
    }

    @Test
    void competingCreatesHaveOneWinnerAndOne4501InsteadOfAUniqueConstraintFailure() throws Exception {
        noInfusion(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                lockOrder(); assertEquals(0, create());
                var task = worker(pool, pid, colleague, this::create); tasks.add(task); waiting(pid, task);
            });
            assertEquals(4501, result(tasks.getFirst()));
            assertEquals(1, jdbc.queryForObject("select count(*) from outp_infusion where order_id=?", Integer.class, order));
            money("CHARGED", "PAID", true);
        } finally { close(pool); }
    }

    @Test
    void competingStartsHaveExactlyOneWinnerWhoseNurseAndTimestampArePreserved() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        final Object[] first = new Object[1];
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, start()); first[0] = infusionRow();
                var task = worker(pool, pid, colleague, this::start); tasks.add(task); waiting(pid, task);
            });
            assertEquals(4501, result(tasks.getFirst())); assertEquals(first[0], infusionRow());
            assertEquals(nurse.id(), infusionRow().get("nurse_id")); money("CHARGED", "PAID", true);
        } finally { close(pool); }
    }

    @Test
    void lateStartCannotTurnAnAlreadyCompletedInfusionBackIntoRunning() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        final Object[] done = new Object[1];
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, start());
                var task = worker(pool, pid, colleague, this::start); tasks.add(task); waiting(pid, task);
                assertEquals(0, finish()); done[0] = infusionRow();
            });
            assertEquals(4501, result(tasks.getFirst())); assertEquals(done[0], infusionRow());
            assertEquals("DONE", infusionRow().get("status")); money("CHARGED", "PAID", true);
        } finally { close(pool); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void checkAndFinishHaveTheirRealSerialOutcomesInBothLockOrders(boolean finishFirst) throws Exception {
        running(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, finishFirst ? finish() : check());
                var task = worker(pool, pid, colleague, () -> finishFirst ? check() : finish());
                tasks.add(task); waiting(pid, task);
            });
            assertEquals(finishFirst ? 4501 : 0, result(tasks.getFirst()));
            assertEquals("DONE", infusionRow().get("status"));
            assertEquals(finishFirst ? 0 : 1, jdbc.queryForObject("select count(*) from outp_infusion_check where infusion_id=?", Integer.class, infusion));
            money("CHARGED", "PAID", true);
        } finally { close(pool); }
    }

    @Test
    void rolledBackStartReleasesTheSourceForOneRealRefundAndLeavesTheInfusionPending() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, start());
                var task = worker(pool, pid, cashier, this::refund); tasks.add(task); waiting(pid, task); tx.setRollbackOnly();
            });
            assertEquals(0, result(tasks.getFirst())); assertEquals("PENDING", infusionRow().get("status"));
            assertNull(infusionRow().get("started_at")); assertNull(infusionRow().get("nurse_id"));
            money("CREATED", "REFUNDED", false);
        } finally { close(pool); }
    }

    @Test
    void rolledBackFinishAllowsTheWaitingNurseToRecordOneCheckWhileStillRunning() throws Exception {
        running(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, finish());
                var task = worker(pool, pid, colleague, this::check); tasks.add(task); waiting(pid, task); tx.setRollbackOnly();
            });
            assertEquals(0, result(tasks.getFirst())); assertEquals("RUNNING", infusionRow().get("status"));
            assertNull(infusionRow().get("finished_at"));
            assertEquals(1, jdbc.queryForObject("select count(*) from outp_infusion_check where infusion_id=?", Integer.class, infusion));
            assertEquals(colleague.id(), jdbc.queryForObject("select nurse_id from outp_infusion_check where infusion_id=?", Long.class, infusion));
            money("CHARGED", "PAID", true);
        } finally { close(pool); }
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void everyNursingWriteRechecksTheEnabledActorAfterItsOrderWait(int kind) throws Exception {
        prepare(kind); Object before = snapshot();
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                lockOrder(); var task = worker(pool, pid, colleague, () -> action(kind)); tasks.add(task); waiting(pid, task);
                jdbc.update("update sys_user set enabled=false where id=?", colleague.id());
            });
            assertEquals(1005, result(tasks.getFirst())); assertEquals(before, snapshot());
        } finally { close(pool); }
    }

    @ParameterizedTest @ValueSource(ints={1,3})
    void actorIsAlsoRecheckedAfterARealInfusionRowWait(int kind) throws Exception {
        prepare(kind); Object before = snapshot();
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                lockInfusion(); var task = worker(pool, pid, colleague, () -> action(kind)); tasks.add(task); waiting(pid, task);
                assertWaitingWorkerOwnsSourceOrder();
                jdbc.update("update sys_user set enabled=false where id=?", colleague.id());
            });
            assertEquals(1005, result(tasks.getFirst())); assertEquals(before, snapshot());
        } finally { close(pool); }
    }

    @Test
    void blankAndOverlongCheckNotesAre4000WithoutRecordingOrChangingCareState() {
        running(); Object before = snapshot();
        for (String note : List.of("", " ", "长".repeat(501))) {
            assertEquals(4000, nursing.addCheck(infusion, note, authentication()).getCode());
            assertEquals(before, snapshot());
        }
        assertEquals(0, nursing.addCheck(infusion, "长".repeat(500), authentication()).getCode());
        assertEquals(500, jdbc.queryForObject("select char_length(note) from outp_infusion_check where infusion_id=?", Integer.class, infusion));
        money("CHARGED", "PAID", true);
    }

    @Test
    void historicalRefundedButRunningInfusionCanStillBeCheckedAndFinishedWithoutRepairingItsMoney() {
        authenticate(cashier); assertEquals(0, refund());
        // Controlled historical bad-state fixture. New start would be rejected; no production endpoint is loosened.
        jdbc.update("update outp_infusion set status='RUNNING',nurse_id=?,started_at=now() where id=?", nurse.id(), infusion);
        var o = jdbc.queryForMap("select * from outp_order where id=?", order);
        var c = jdbc.queryForMap("select * from outp_charge where id=?", charge);
        authenticate(colleague); assertEquals(0, check()); assertEquals(0, finish());
        assertEquals("DONE", infusionRow().get("status"));
        assertEquals(o, jdbc.queryForMap("select * from outp_order where id=?", order));
        assertEquals(c, jdbc.queryForMap("select * from outp_charge where id=?", charge));
        money("CREATED", "REFUNDED", false);
    }
}
