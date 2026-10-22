package cn.hip.server;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DispenseService;
import cn.hip.outpatient.web.OutpNurseStationController;
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
 * Real committed transactions, never an outer @Transactional test. Each lock-order case observes
 * pg_stat_activity.wait_event_type='Lock' before releasing its owner. This service/controller test
 * uses real fixture employees with explicit contexts, and does not claim to exercise JWT parsing.
 * Return-first deliberately permits subsequent CHARGED nursing start under the existing policy.
 */
@SpringBootTest
class V112ReturnInfusionRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired DispenseService pharmacy;
    @Autowired ChargeService charges;
    @Autowired OutpNurseStationController nursing;
    @Autowired OutpOrderRepository orders;
    @Autowired EntityManager entityManager;

    record Staff(Long id, String name, String role) {}
    final List<Staff> staff = new ArrayList<>();
    final List<Long> drugs = new ArrayList<>();
    Staff pharmacist, colleague, admin, nurse, cashier;
    Long dept, patient, schedule, registration, drug, order, charge, infusion, firstBatch, secondBatch;
    String tag;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext();
        tag = "R112" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag, tag);
        pharmacist = employee("p", "PHARMACIST"); colleague = employee("q", "PHARMACIST");
        admin = employee("a", "ADMIN"); nurse = employee("n", "NURSE"); cashier = employee("c", "CASHIER");
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
        drug = newDrug("d", 100);
        order = newOrder(drug, tag, "CREATED");
        authenticate(cashier);
        charge = charges.settle(registration, "CASH", cashier.id()).getId();
        firstBatch = newBatch("FIRST", 1, 365);
        secondBatch = newBatch("SECOND", 99, 730);
        infusion = jdbc.queryForObject("insert into outp_infusion(order_id) values (?) returning id", Long.class, order);
        authenticate(pharmacist);
    }

    Staff employee(String suffix, String role) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled)
                values (?,'x',?,?,true) returning id
                """, Long.class, name, name, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        Staff actor = new Staff(id, name, role); staff.add(actor); return actor;
    }
    Long newDrug(String suffix, int stock) {
        Long id = jdbc.queryForObject("""
                insert into md_drug(code,name,spec,unit,dose_form,price,stock,antibiotic,abx_level,drug_class,enabled)
                values (?,?,'10ml','支','注射剂',7.50,?,false,0,'W',true) returning id
                """, Long.class, tag + suffix, "测试药" + tag + suffix, stock);
        drugs.add(id); return id;
    }
    Long newOrder(Long itemId, String group, String status) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,
                                       unit,qty,unit_price,amount,usage_route,days,status)
                values (?,?,'DRUG',?,?,?,'支',3,7.50,22.50,'静脉滴注',2,?) returning id
                """, Long.class, registration, group, itemId, tag, "测试药" + tag, status);
    }
    Long newBatch(String suffix, int qty, int days) {
        Long id = jdbc.queryForObject("""
                insert into pharm_batch(drug_id,batch_no,expire_on) values (?,?,?::date) returning id
                """, Long.class, drug, tag + suffix, BusinessDates.today().plusDays(days).toString());
        jdbc.update("insert into pharm_stock(batch_id,location_code,qty) values (?,'OUTP_PHARM',?)", id, qty);
        return id;
    }
    Authentication authenticate(Staff actor) {
        if (actor == null) { SecurityContextHolder.clearContext(); return null; }
        Authentication auth = new UsernamePasswordAuthenticationToken(actor.name(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + actor.role())));
        SecurityContextHolder.getContext().setAuthentication(auth); return auth;
    }
    void dispensed() {
        // Fixture setup is a deliberately trusted internal call, independent of the new actor guard.
        authenticate(null); assertEquals(1, pharmacy.dispense(registration).size()); authenticate(pharmacist);
        assertInventory("DISPENSED", 97, 0, 97, 0);
    }
    int returnDrug() { pharmacy.returnDrug(order, pharmacist.id()); return 0; }
    int dispense() { pharmacy.dispense(registration); return 0; }
    int start() {
        return nursing.start(infusion, SecurityContextHolder.getContext().getAuthentication()).getCode();
    }
    int finish() { return nursing.finish(infusion).getCode(); }
    int business(IntSupplier action) {
        try { return action.getAsInt(); }
        catch (HipBizException e) { return e.code; }
        catch (AccessDeniedException e) { return 1005; }
    }
    TransactionTemplate transaction() {
        var tx = new TransactionTemplate(manager); tx.setTimeout(20); return tx;
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
        try { return future.get(20, TimeUnit.SECONDS); }
        catch (ExecutionException e) { return fail("Unexpected runtime/SQL failure instead of business outcome", e.getCause()); }
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
    void close(ExecutorService pool) throws Exception {
        pool.shutdown(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Workers must finish before cleanup");
    }
    void lock(int level) {
        String sql = switch (level) {
            case 0 -> "select id from outp_order where id=? for update";
            case 1 -> "select id from md_drug where id=? for update";
            case 2 -> "select batch_id from pharm_stock where batch_id=? and location_code='OUTP_PHARM' for update";
            default -> throw new IllegalArgumentException();
        };
        assertEquals(1, jdbc.queryForList(sql, level == 0 ? order : level == 1 ? drug : firstBatch).size());
    }
    void assertWorkerOwns(String table, Long id) {
        assertTrue(List.of("outp_order", "md_drug").contains(table));
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("select id from " + table + " where id=? for update nowait")) {
                statement.setLong(1, id);
                try (var ignored = statement.executeQuery()) {
                    fail("Stock waiter must already own its preceding " + table + " lock");
                }
            } catch (java.sql.SQLException expected) {
                assertEquals("55P03", expected.getSQLState(), "Only the intentional NOWAIT miss is expected");
            } finally {
                connection.rollback(savepoint); connection.releaseSavepoint(savepoint);
            }
            return null;
        });
    }
    Object snapshot() {
        return List.of(
                jdbc.queryForList("select * from outp_order where registration_id=? order by id", registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id", registration),
                jdbc.queryForList("select i.* from outp_infusion i join outp_order o on o.id=i.order_id where o.registration_id=? order by i.id", registration),
                jdbc.queryForList("select c.* from outp_infusion_check c join outp_infusion i on i.id=c.infusion_id join outp_order o on o.id=i.order_id where o.registration_id=? order by c.id", registration),
                jdbc.queryForList("select id,stock from md_drug where id in(select item_id from outp_order where registration_id=?) order by id", registration),
                jdbc.queryForList("select s.* from pharm_stock s join pharm_batch b on b.id=s.batch_id where b.drug_id=? order by s.id", drug),
                jdbc.queryForList("select * from pharm_dispense_batch where order_id in(select id from outp_order where registration_id=?) order by id", registration),
                jdbc.queryForList("select * from inv_transaction where drug_id=? order by id", drug),
                jdbc.queryForList("select f.* from pharm_stock_flow f join pharm_batch b on b.id=f.batch_id where b.drug_id=? order by f.id", drug));
    }
    void assertInventory(String status, int aggregate, int first, int second, int returns) {
        assertEquals(status, jdbc.queryForObject("select status from outp_order where id=?", String.class, order));
        assertEquals(aggregate, jdbc.queryForObject("select stock from md_drug where id=?", Integer.class, drug));
        assertEquals(first, jdbc.queryForObject("select qty from pharm_stock where batch_id=?", Integer.class, firstBatch));
        assertEquals(second, jdbc.queryForObject("select qty from pharm_stock where batch_id=?", Integer.class, secondBatch));
        assertEquals(returns, jdbc.queryForObject("select count(*) from inv_transaction where drug_id=? and type='RET'", Integer.class, drug));
        assertEquals(returns * 2, jdbc.queryForObject("select count(*) from pharm_stock_flow where batch_id in(?,?) and flow_type='RET'", Integer.class, firstBatch, secondBatch));
        assertEquals(returns * 3, jdbc.queryForObject("select coalesce(sum(returned_qty),0) from pharm_dispense_batch where order_id=?", Integer.class, order));
        assertEquals("PAID", jdbc.queryForObject("select status from outp_charge where id=?", String.class, charge));
        assertEquals(charge, jdbc.queryForObject("select charge_id from outp_order where id=?", Long.class, order));
        assertEquals(1, jdbc.queryForObject("select booked from outp_schedule where id=?", Integer.class, schedule));
    }
    void assertActor(String type, Long actor) {
        var total = jdbc.queryForList("select operator_id,qty,stock_after,ref_no from inv_transaction where drug_id=? and type=?", drug, type);
        assertEquals(1, total.size()); assertEquals(actor, total.getFirst().get("operator_id"));
        assertEquals(type.equals("OUT") ? -3 : 3, ((Number) total.getFirst().get("qty")).intValue());
        assertEquals(type.equals("OUT") ? 97 : 100, ((Number) total.getFirst().get("stock_after")).intValue());
        assertEquals(tag, total.getFirst().get("ref_no"));
        var batch = jdbc.queryForList("select operator_id,qty_delta from pharm_stock_flow where batch_id in(?,?) and flow_type=? order by batch_id", firstBatch, secondBatch, type);
        assertEquals(2, batch.size());
        for (var row : batch) assertEquals(actor, row.get("operator_id"));
        assertEquals(type.equals("OUT") ? -3 : 3, batch.stream().mapToInt(r -> ((Number) r.get("qty_delta")).intValue()).sum());
    }
    void aggregateOnly() {
        jdbc.update("delete from pharm_stock where batch_id in(?,?)", firstBatch, secondBatch);
        jdbc.update("delete from pharm_batch where id in(?,?)", firstBatch, secondBatch);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        transaction().executeWithoutResult(tx -> {
            if (registration != null) {
                jdbc.update("delete from outp_infusion_check where infusion_id in(select i.id from outp_infusion i join outp_order o on o.id=i.order_id where o.registration_id=?)", registration);
                jdbc.update("delete from outp_infusion where order_id in(select id from outp_order where registration_id=?)", registration);
                jdbc.update("delete from outp_skin_test where registration_id=?", registration);
                jdbc.update("delete from pharm_dispense_batch where order_id in(select id from outp_order where registration_id=?)", registration);
                jdbc.update("delete from outp_order where registration_id=?", registration);
                jdbc.update("delete from outp_charge where registration_id=?", registration);
                jdbc.update("delete from outp_registration where id=?", registration);
            }
            if (schedule != null) jdbc.update("delete from outp_schedule where id=?", schedule);
            if (patient != null) jdbc.update("delete from empi_patient where id=?", patient);
            for (Long item : drugs) {
                jdbc.update("delete from pharm_stock_flow where batch_id in(select id from pharm_batch where drug_id=?)", item);
                jdbc.update("delete from pharm_stock where batch_id in(select id from pharm_batch where drug_id=?)", item);
                jdbc.update("delete from pharm_batch where drug_id=?", item);
                jdbc.update("delete from inv_transaction where drug_id=?", item);
                jdbc.update("delete from md_drug where id=?", item);
            }
            for (Staff actor : staff) {
                jdbc.update("delete from sys_user_role where user_id=?", actor.id());
                jdbc.update("delete from sys_user where id=?", actor.id());
            }
            if (dept != null) jdbc.update("delete from sys_dept where id=?", dept);
        });
    }

    @Test
    void pendingReturnRestoresOnlyTheOriginalTwoBatchesAndLeavesCareUnchanged() {
        dispensed(); Object pending = jdbc.queryForMap("select * from outp_infusion where id=?", infusion);
        assertEquals(0, returnDrug()); assertInventory("CHARGED", 100, 1, 99, 1); assertActor("RET", pharmacist.id());
        assertEquals(pending, jdbc.queryForMap("select * from outp_infusion where id=?", infusion));
        assertEquals(List.of(1, 2), jdbc.queryForList("select qty from pharm_dispense_batch where order_id=? order by batch_id", Integer.class, order));
    }

    @Test
    void trustedAggregateOnlyLegacyReturnDoesNotInventBatchHistory() {
        aggregateOnly(); authenticate(null); assertEquals(0, dispense());
        pharmacy.returnDrug(order, colleague.id());
        assertEquals(100, jdbc.queryForObject("select stock from md_drug where id=?", Integer.class, drug));
        assertEquals(0, jdbc.queryForObject("select count(*) from pharm_dispense_batch where order_id=?", Integer.class, order));
        assertEquals(0, jdbc.queryForObject("select count(*) from pharm_batch where drug_id=?", Integer.class, drug));
        assertEquals(colleague.id(), jdbc.queryForObject("select operator_id from inv_transaction where drug_id=? and type='RET'", Long.class, drug));
    }

    @Test
    void noAllocationHistoryRestoresAggregateWithoutGuessingTheCurrentAvailableBatch() {
        dispensed(); jdbc.update("delete from pharm_dispense_batch where order_id=?", order);
        assertEquals(0, returnDrug());
        assertEquals(100, jdbc.queryForObject("select stock from md_drug where id=?", Integer.class, drug));
        assertEquals(List.of(0, 97), jdbc.queryForList("select qty from pharm_stock where batch_id in(?,?) order by batch_id", Integer.class, firstBatch, secondBatch));
        assertEquals(0, jdbc.queryForObject("select count(*) from pharm_stock_flow where batch_id in(?,?) and flow_type='RET'", Integer.class, firstBatch, secondBatch));
    }

    @ParameterizedTest @ValueSource(strings={"RUNNING", "DONE"})
    void startedOrCompletedInfusionRejectsReturnWithoutAnyStockOrLedgerWrite(String state) {
        dispensed(); authenticate(nurse); assertEquals(0, start()); if (state.equals("DONE")) assertEquals(0, finish());
        authenticate(pharmacist); Object before = snapshot();
        assertEquals(6004, business(this::returnDrug)); assertEquals(before, snapshot()); assertInventory("DISPENSED", 97, 0, 97, 0);
    }

    @Test
    void unrelatedInfusionInTheSameVisitDoesNotBlockThisOrdersReturn() {
        dispensed(); Long other = newOrder(drug, tag + "other", "CHARGED");
        Long otherInfusion = jdbc.queryForObject("insert into outp_infusion(order_id,status,nurse_id,started_at) values (?,'RUNNING',?,now()) returning id", Long.class, other, nurse.id());
        var care = jdbc.queryForMap("select * from outp_infusion where id=?", otherInfusion);
        assertEquals(0, returnDrug()); assertInventory("CHARGED", 100, 1, 99, 1);
        assertEquals(care, jdbc.queryForMap("select * from outp_infusion where id=?", otherInfusion));
    }

    @ParameterizedTest @ValueSource(strings={"CREATED", "CHARGED", "CANCELLED", "EXECUTED"})
    void nonDispensedSourceKeeps6004AndHasNoSideEffects(String status) {
        jdbc.update("update outp_order set status=? where id=?", status, order); Object before = snapshot();
        assertEquals(6004, business(this::returnDrug)); assertEquals(before, snapshot());
    }

    @Test
    void nonDrugAndMissingSourceKeepTheExistingBusinessCodes() {
        jdbc.update("update outp_order set order_type='LAB',status='DISPENSED' where id=?", order); Object before = snapshot();
        assertEquals(6004, business(this::returnDrug));
        assertEquals(6006, business(() -> { pharmacy.returnDrug(Long.MAX_VALUE, pharmacist.id()); return 0; }));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest @ValueSource(strings={"NURSE", "CASHIER", "TECHNICIAN", "DOCTOR_OUTP"})
    void authenticatedNonPharmacyServiceCallerCannotDispenseOrReturn(String role) {
        Staff wrong = employee("wrong", role); Object charged = snapshot(); authenticate(wrong);
        assertEquals(1005, business(this::dispense)); assertEquals(charged, snapshot());
        dispensed(); authenticate(wrong); Object before = snapshot();
        assertEquals(1005, business(() -> { pharmacy.returnDrug(order, wrong.id()); return 0; })); assertEquals(before, snapshot());
    }

    @Test
    void disabledAndUnknownPharmacistCannotCallEitherServiceWrite() {
        jdbc.update("update sys_user set enabled=false where id=?", pharmacist.id()); Object before = snapshot();
        assertEquals(1005, business(this::dispense)); assertEquals(before, snapshot());
        Staff missing = new Staff(-1L, tag + "absent", "PHARMACIST"); authenticate(missing);
        assertEquals(1005, business(this::dispense)); assertEquals(before, snapshot());
        dispensed(); authenticate(pharmacist); before = snapshot();
        assertEquals(1005, business(this::returnDrug)); assertEquals(before, snapshot());
        authenticate(missing); assertEquals(1005, business(() -> { pharmacy.returnDrug(order, null); return 0; })); assertEquals(before, snapshot());
    }

    @Test
    void explicitOtherOperatorCannotBeUsedForReturnAttribution() {
        dispensed(); Object before = snapshot();
        assertEquals(1005, business(() -> { pharmacy.returnDrug(order, colleague.id()); return 0; })); assertEquals(before, snapshot());
    }

    @ParameterizedTest @ValueSource(strings={"ADMIN", "PHARMACIST"})
    void actualEnabledPharmacyActorOwnsAllOutAndReturnFlowsEvenWithNullReturnArgument(String role) {
        Staff actor = role.equals("ADMIN") ? admin : pharmacist; authenticate(actor);
        assertEquals(0, dispense()); assertActor("OUT", actor.id());
        pharmacy.returnDrug(order, null); assertActor("RET", actor.id()); assertInventory("CHARGED", 100, 1, 99, 1);
    }

    @Test
    void trustedInternalReturnPreservesItsExplicitOperatorAndNullCompatibility() {
        dispensed(); authenticate(null); pharmacy.returnDrug(order, colleague.id()); assertActor("RET", colleague.id());
        assertEquals(0, dispense()); pharmacy.returnDrug(order, null);
        var rows = jdbc.queryForList("select operator_id from inv_transaction where drug_id=? and type='RET' order by id", drug);
        assertEquals(2, rows.size()); assertEquals(colleague.id(), rows.getFirst().get("operator_id")); assertNull(rows.getLast().get("operator_id"));
        assertEquals(100, jdbc.queryForObject("select stock from md_drug where id=?", Integer.class, drug));
    }

    @Test
    void startCommittedFirstMakesWaitingReturnRecheckTheNewInfusionFact() throws Exception {
        dispensed(); authenticate(nurse); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger();
        var tasks = new ArrayList<Future<Integer>>(); final Object[] before = new Object[1];
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, start()); before[0] = snapshot();
                var task = worker(pool, pid, pharmacist, this::returnDrug); tasks.add(task); waiting(pid, task);
            });
            assertEquals(6004, result(tasks.getFirst())); assertEquals(before[0], snapshot()); assertInventory("DISPENSED", 97, 0, 97, 0);
        } finally { close(pool); }
    }

    @Test
    void returnCommittedFirstStillAllowsTheWaitingNurseToStartAChargedDrug() throws Exception {
        dispensed(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, returnDrug());
                var task = worker(pool, pid, nurse, this::start); tasks.add(task); waiting(pid, task);
            });
            assertEquals(0, result(tasks.getFirst()), "Existing CHARGED nursing policy permits this serial outcome");
            assertEquals("RUNNING", jdbc.queryForObject("select status from outp_infusion where id=?", String.class, infusion));
            assertEquals(nurse.id(), jdbc.queryForObject("select nurse_id from outp_infusion where id=?", Long.class, infusion));
            assertInventory("CHARGED", 100, 1, 99, 1);
        } finally { close(pool); }
    }

    @Test
    void twoReturnsHaveExactlyOneWinnerAndOne6004WithOneRestoreOnly() throws Exception {
        dispensed(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, returnDrug());
                var task = worker(pool, pid, colleague, () -> { pharmacy.returnDrug(order, colleague.id()); return 0; }); tasks.add(task); waiting(pid, task);
            });
            assertEquals(6004, result(tasks.getFirst())); assertInventory("CHARGED", 100, 1, 99, 1); assertActor("RET", pharmacist.id());
            Object before = snapshot(); assertEquals(6004, business(this::returnDrug)); assertEquals(before, snapshot());
        } finally { close(pool); }
    }

    @ParameterizedTest @CsvSource({"true,0", "true,1", "true,2", "false,0", "false,1", "false,2"})
    void bothWritesRecheckTheActorAfterOrderAggregateAndBatchWaits(boolean returning, int lockLevel) throws Exception {
        if (returning) dispensed(); Object before = snapshot();
        var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                lock(lockLevel);
                var task = worker(pool, pid, pharmacist, returning ? this::returnDrug : this::dispense); tasks.add(task); waiting(pid, task);
                if (lockLevel > 0) assertWorkerOwns("outp_order", order);
                if (lockLevel > 1) assertWorkerOwns("md_drug", drug);
                jdbc.update("update sys_user set enabled=false where id=?", pharmacist.id());
            });
            assertEquals(1005, result(tasks.getFirst())); assertEquals(before, snapshot(), "Revocation after a real wait rolls back every prior write");
        } finally { close(pool); }
    }

    @ParameterizedTest @ValueSource(strings={"LAB", "DRUG"})
    void composedTransactionUsesFreshSourceTypeQuantityAndItemInsteadOfAPreloadedEntity(String currentType) throws Exception {
        // Controlled transaction-composition fixture, not a claim that HTTP permits editing dispensed metadata.
        aggregateOnly(); authenticate(null); assertEquals(0, dispense()); authenticate(pharmacist);
        Long replacement = newDrug("replacement", 70);
        var loaded = new CountDownLatch(1); var proceed = new CountDownLatch(1); var pool = Executors.newSingleThreadExecutor();
        Future<Integer> task = pool.submit(() -> {
            authenticate(pharmacist);
            try {
                return business(() -> transaction().execute(tx -> {
                    var cached = orders.findById(order).orElseThrow(); assertEquals(drug, cached.getItemId());
                    loaded.countDown();
                    try { assertTrue(proceed.await(12, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    return returnDrug();
                }));
            } finally { SecurityContextHolder.clearContext(); }
        });
        try {
            assertTrue(loaded.await(12, TimeUnit.SECONDS));
            jdbc.update("update outp_order set item_id=?,qty=4,amount=30,order_type=? where id=?", replacement, currentType, order);
            Object before = snapshot(); proceed.countDown();
            assertEquals(currentType.equals("LAB") ? 6004 : 0, result(task));
            if (currentType.equals("LAB")) assertEquals(before, snapshot());
            else {
                assertEquals(97, jdbc.queryForObject("select stock from md_drug where id=?", Integer.class, drug));
                assertEquals(74, jdbc.queryForObject("select stock from md_drug where id=?", Integer.class, replacement));
                assertEquals(4, jdbc.queryForObject("select qty from inv_transaction where drug_id=? and type='RET'", Integer.class, replacement));
                assertEquals("CHARGED", jdbc.queryForObject("select status from outp_order where id=?", String.class, order));
            }
        } finally { proceed.countDown(); close(pool); }
    }

    @Test
    void dirtyManagedSourceIsFlushedBeforeRefreshRatherThanLosingItsPendingFields() {
        dispensed(); transaction().executeWithoutResult(tx -> {
            OutpOrder managed = orders.findById(order).orElseThrow(); managed.setRemark("同事务待保存" + tag);
            assertEquals(0, returnDrug()); entityManager.flush();
        });
        assertEquals("同事务待保存" + tag, jdbc.queryForObject("select remark from outp_order where id=?", String.class, order));
        assertInventory("CHARGED", 100, 1, 99, 1);
    }

    @Test
    void failureInTheSecondBatchRollsBackFirstBatchAggregateClaimAndLedgersThenRetryWorks() {
        dispensed();
        jdbc.update("update pharm_dispense_batch set location_code='bad space' where order_id=? and batch_id=?", order, secondBatch);
        Object before = snapshot(); assertEquals(5412, business(this::returnDrug)); assertEquals(before, snapshot());
        jdbc.update("update pharm_dispense_batch set location_code='OUTP_PHARM' where order_id=? and batch_id=?", order, secondBatch);
        assertEquals(0, returnDrug()); assertInventory("CHARGED", 100, 1, 99, 1); assertActor("RET", pharmacist.id());
    }

    @Test
    void rolledBackReturnLetsWaitingReturnWinExactlyOnceAndLeavesNoLoserLedger() throws Exception {
        dispensed(); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, returnDrug());
                var task = worker(pool, pid, colleague, () -> { pharmacy.returnDrug(order, colleague.id()); return 0; }); tasks.add(task); waiting(pid, task);
                tx.setRollbackOnly();
            });
            assertEquals(0, result(tasks.getFirst())); assertInventory("CHARGED", 100, 1, 99, 1); assertActor("RET", colleague.id());
        } finally { close(pool); }
    }

    @Test
    void rolledBackStartLeavesPendingCareAndAllowsTheWaitingReturn() throws Exception {
        dispensed(); authenticate(nurse); var pool = Executors.newSingleThreadExecutor(); var pid = new AtomicInteger(); var tasks = new ArrayList<Future<Integer>>();
        try {
            transaction().executeWithoutResult(tx -> {
                assertEquals(0, start());
                var task = worker(pool, pid, pharmacist, this::returnDrug); tasks.add(task); waiting(pid, task); tx.setRollbackOnly();
            });
            assertEquals(0, result(tasks.getFirst())); assertInventory("CHARGED", 100, 1, 99, 1);
            var pending = jdbc.queryForMap("select * from outp_infusion where id=?", infusion);
            assertEquals("PENDING", pending.get("status")); assertNull(pending.get("started_at")); assertNull(pending.get("nurse_id"));
        } finally { close(pool); }
    }
}
