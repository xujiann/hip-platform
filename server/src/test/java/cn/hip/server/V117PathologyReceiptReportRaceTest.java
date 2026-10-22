package cn.hip.server;

import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.platform.core.common.R;
import java.util.Map;
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
class V117PathologyReceiptReportRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ChargeService charges;
    @Autowired PathologyRegistryController registry;
    @Autowired PathologyController legacy;
    @Autowired OutpOrderRepository orders;
    @Autowired EntityManager em;
    @Autowired ConfigReader config;
    @Autowired PathologyReportController report;
    Long specimen; String barcode, gate; int specimenCounter; Staff firstDoctor;

    record Staff(Long id, String name, String role) {}
    final List<Staff> employees = new ArrayList<>();
    Staff tech, doctor, cashier;
    Long dept, patient, schedule, registration, order, charge, bed, admission, inpOrder;
    String tag, pattern;
    Object stockBefore;

    @BeforeEach
    void fixture() {
        SecurityContextHolder.clearContext(); tag = "P117" + Long.toUnsignedString(System.nanoTime(), 36);
        gate = jdbc.queryForObject("select cfg_value from sys_config where cfg_key='emr.gate.pathology.doublesign'",String.class);
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
        authenticate(cashier); charge = charges.settle(registration, "CASH", cashier.id()).getId(); authenticate(tech); firstDoctor=employee("f","DOCTOR_OUTP"); specimen=newSpecimen(order,null,1); barcode=barcodeOf(specimen);
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
    int attempt(IntSupplier operation) { try { return operation.getAsInt(); } catch (HipBizException e) { return e.code; } catch (AccessDeniedException | AuthenticationCredentialsNotFoundException e) { return 1005; } }
    TransactionTemplate transaction() { var tx = new TransactionTemplate(manager); tx.setTimeout(20); return tx; }
    Future<Integer> worker(ExecutorService pool, AtomicInteger pid, Staff actor, IntSupplier operation) {
        return pool.submit(() -> { authenticate(actor); try { return attempt(() -> transaction().execute(tx -> { pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return operation.getAsInt(); })); } finally { SecurityContextHolder.clearContext(); } });
    }
    int result(Future<Integer> future) throws Exception { try { return future.get(20, TimeUnit.SECONDS); } catch (ExecutionException e) { return fail("Expected readable business result, not SQL/constraint/runtime failure", e.getCause()); } }
    void waiting(AtomicInteger pid, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')", Boolean.class, pid.get()))) { System.out.println("V117 PG_LOCK observed pid=" + pid.get()); return; }
            if (future.isDone()) fail("Operation completed without required PostgreSQL Lock wait");
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Expected a real PostgreSQL Lock wait");
    }
    void await(CountDownLatch latch) { try { assertTrue(latch.await(12, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); } }
    void close(ExecutorService pool) throws Exception { pool.shutdown(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Workers finish before cleanup"); }
    void lockOrder() { jdbc.queryForList("select id from outp_order where id=? for update", order); }
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
            jdbc.update("delete from path_report where specimen_id in(select id from path_specimen where order_id in(select id from outp_order where registration_id=?) or inp_order_id=?)",registration,inpOrder==null?-1L:inpOrder);
            jdbc.update("delete from path_gross_revision where specimen_id in(select id from path_specimen where order_id in(select id from outp_order where registration_id=?) or inp_order_id=?)",registration,inpOrder==null?-1L:inpOrder);
            jdbc.update("delete from path_process where specimen_id in(select id from path_specimen where order_id in(select id from outp_order where registration_id=?) or inp_order_id=?)", registration, inpOrder == null ? -1L : inpOrder);
            jdbc.update("delete from path_specimen where order_id in(select id from outp_order where registration_id=?) or inp_order_id=?", registration, inpOrder == null ? -1L : inpOrder);
            jdbc.update("delete from outp_refund_approval where charge_id in(select id from outp_charge where registration_id=?)", registration);
            jdbc.update("delete from outp_order where registration_id=?", registration); jdbc.update("delete from outp_charge where registration_id=?", registration); jdbc.update("delete from outp_registration where id=?", registration); jdbc.update("delete from outp_schedule where id=?", schedule);
            if (inpOrder != null) jdbc.update("delete from inp_order where id=?", inpOrder); if (admission != null) jdbc.update("delete from inp_admission where id=?", admission); if (bed != null) jdbc.update("delete from inp_bed where id=?", bed);
            jdbc.update("delete from empi_patient where id=?", patient); for (var e : employees) { jdbc.update("delete from sys_user_role where user_id=?", e.id()); jdbc.update("delete from sys_user where id=?", e.id()); } jdbc.update("delete from sys_dept where id=?", dept);
            jdbc.update("update sys_config set cfg_value=? where cfg_key='path.no.pattern'", pattern);
            jdbc.update("update sys_config set cfg_value=? where cfg_key='emr.gate.pathology.doublesign'",gate);
        }); config.evictAll();
    }

    Long newSpecimen(Long outp, Long inp, int part) {
        return jdbc.queryForObject("""
                insert into path_specimen(order_id,inp_order_id,part_no,barcode,specimen_desc,status,
                  gross_finding,micro_finding) values (?,?,?,?,?,'COLLECTED',' 原大体 ','原镜下') returning id
                """,Long.class,outp,inp,part,tag+"s"+(++specimenCounter),"真实组织");
    }
    String barcodeOf(Long id) { return jdbc.queryForObject("select barcode from path_specimen where id=?",String.class,id); }
    boolean signing(String op) { return List.of("first","second","issue","supplement").contains(op); }
    Staff actor(String op) { return signing(op)?doctor:tech; }
    String node(String op) { return switch(op) {case "receive"->"RECEIVE";case "reject"->"REJECT";case "first"->"FIRST_SIGN";case "second"->"SECOND_SIGN";case "issue"->"ISSUE";case "supplement"->"SUPPLEMENT";default->null;}; }
    int changedCode(String op) { return switch(op){case "receive","reject"->5208;case "legacyReceive"->4551;case "diagnose"->4553;default->5261;}; }
    void ready(String op) { ready(specimen,op); }
    void ready(Long id,String op) {
        boolean diagnosed=signing(op), first=List.of("second","issue").contains(op), second=op.equals("issue");
        jdbc.update("""
                update path_specimen set status=?,received_at=case when ? then now() else null end,
                  diagnosis=?,diagnosed_at=case when ? then now() else null end,
                  pathologist_id=case when ? then ? else null end,
                  first_signer_id=case when ? then ? else null end,first_signed_at=case when ? then now() else null end,
                  second_signer_id=case when ? then ? else null end,second_signed_at=case when ? then now() else null end,
                  report_issued_at=null,rejected_at=null,rejected_by=null,reject_reason=null
                where id=?
                """,diagnosed?"DIAGNOSED":op.equals("diagnose")?"RECEIVED":"COLLECTED",diagnosed||op.equals("diagnose"),
                diagnosed?"原首次诊断":null,diagnosed,diagnosed,tech.id(),first,firstDoctor.id(),first,second,doctor.id(),second,id);
    }
    R<?> response(String op,Long id,String code,Authentication supplied) {
        return switch(op) {
            case "receive"->registry.receiveCheck(id,new PathologyRegistryController.ReceiveCheckReq(null,"与申请核对"),supplied);
            case "legacyReceive"->legacy.receive(code);
            case "reject"->registry.reject(id,new PathologyRegistryController.RejectReq("标本与申请不符",null),supplied);
            case "diagnose"->legacy.diagnose(code,new PathologyController.DiagnoseReq("修后大体","修后镜下","模拟病理诊断"),supplied);
            case "first"->report.firstSign(id,"初诊核对",supplied);
            case "second"->report.secondSign(id,"复诊核对",supplied);
            case "issue"->report.issue(id,supplied);
            case "supplement"->report.supplement(id,new PathologyReportController.SupplementReq("追加免疫结果","模拟追加"),supplied);
            default->throw new IllegalArgumentException(op);
        };
    }
    int operation(String op) { return response(op,specimen,barcode,auth()).getCode(); }
    int operation(String op,Long id) { return response(op,id,barcodeOf(id),auth()).getCode(); }
    Object facts() {
        return List.of(snapshot(),jdbc.queryForList("select * from path_report where specimen_id in(select id from path_specimen where order_id in(select id from outp_order where registration_id=?) or inp_order_id=?) order by id",registration,inpOrder==null?-1L:inpOrder),
                jdbc.queryForList("select * from path_gross_revision where specimen_id in(select id from path_specimen where order_id in(select id from outp_order where registration_id=?) or inp_order_id=?) order by id",registration,inpOrder==null?-1L:inpOrder));
    }
    void lockSpecimen() { jdbc.queryForList("select id from path_specimen where id=? for update",specimen); }
    void assertNode(String expected,Staff staff) {
        var rows=jdbc.queryForList("select * from path_process where specimen_id=? and node=?",specimen,expected);
        assertEquals(1,rows.size()); assertEquals(staff.id(),rows.getFirst().get("operator_id")); assertNotNull(rows.getFirst().get("occurred_at"));
    }
    void probeSourceStrongLock() {
        transaction().executeWithoutResult(tx->jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c->{
            var sp=c.setSavepoint();
            try(var p=c.prepareStatement("select id from outp_order where id=? for no key update nowait")){
                p.setLong(1,order); try(var ignored=p.executeQuery()){fail("A specimen FK KEY SHARE is insufficient: writer must strongly own O before waiting S");}
            } catch(java.sql.SQLException e){assertEquals("55P03",e.getSQLState());}
            finally{c.rollback(sp);c.releaseSavepoint(sp);} return null;
        }));
    }
    void probeSpecimenNotLockedBeforeSource() {
        assertDoesNotThrow(()->jdbc.queryForList("select id from path_specimen where id=? for no key update nowait",specimen),"Writer waiting for O must not already own S");
    }
    int refund() { charges.refund(charge,cashier.id()); return 0; }

    @ParameterizedTest
    @CsvSource({"receive,ADMIN","receive,TECHNICIAN","receive,DOCTOR_OUTP","legacyReceive,ADMIN","legacyReceive,TECHNICIAN","legacyReceive,DOCTOR_OUTP","reject,ADMIN","reject,TECHNICIAN","reject,DOCTOR_OUTP","diagnose,ADMIN","diagnose,TECHNICIAN","diagnose,DOCTOR_OUTP","first,ADMIN","first,DOCTOR_OUTP","second,ADMIN","second,DOCTOR_OUTP","issue,ADMIN","issue,DOCTOR_OUTP","supplement,ADMIN","supplement,DOCTOR_OUTP"})
    void originalDutiesCanCollaborateOnAnotherDoctorsHistoricalSpecimen(String op,String role) {
        ready(op); var colleague=employee("allowed"+role,role); authenticate(colleague); assertEquals(0,operation(op));
        assertLedger(op.equals("issue")?"EXECUTED":"CHARGED","PAID");
        if(node(op)!=null) assertNode(node(op),colleague);
        if(op.equals("legacyReceive")) assertEquals(0,jdbc.queryForObject("select count(*) from path_process where specimen_id=?",Integer.class,specimen));
        if(op.equals("diagnose")){var s=jdbc.queryForMap("select * from path_specimen where id=?",specimen);assertEquals("DIAGNOSED",s.get("status"));assertEquals(colleague.id(),s.get("pathologist_id"));assertNull(s.get("report_issued_at"));}
        if(op.equals("supplement")){assertEquals("原首次诊断",jdbc.queryForObject("select diagnosis from path_specimen where id=?",String.class,specimen));assertNull(jdbc.queryForObject("select report_issued_at from path_specimen where id=?",Object.class,specimen));}
    }

    @ParameterizedTest
    @CsvSource({"receive,disabled","legacyReceive,disabled","reject,disabled","diagnose,disabled","first,disabled","second,disabled","issue,disabled","supplement,disabled","receive,missing","legacyReceive,missing","reject,missing","diagnose,missing","first,missing","second,missing","issue,missing","supplement,missing","receive,wrong","legacyReceive,wrong","reject,wrong","diagnose,wrong","first,wrong","second,wrong","issue,wrong","supplement,wrong","receive,none","legacyReceive,none","reject,none","diagnose,none","first,none","second,none","issue,none","supplement,none"})
    void eachEntryRequiresOriginalDutyAndExistingEnabledActor(String op,String kind) {
        ready(op); Staff a=actor(op); authenticate(a);
        switch(kind){case "disabled"->jdbc.update("update sys_user set enabled=false where id=?",a.id());case "missing"->authenticate(new Staff(-1L,tag+"absent",a.role()));case "wrong"->authenticate(employee("wrong",signing(op)?"TECHNICIAN":"CASHIER"));case "none"->authenticate(null);default->fail();}
        Object before=facts(); assertEquals(1005,attempt(()->operation(op))); assertEquals(before,facts());
    }

    @ParameterizedTest
    @ValueSource(strings={"receive","reject","diagnose","first","second","issue","supplement"})
    void explicitAuthenticationCannotImpersonateEnabledColleague(String op) {
        ready(op);authenticate(actor(op));Object before=facts();assertEquals(1005,attempt(()->response(op,specimen,barcode,token(firstDoctor)).getCode()));assertEquals(before,facts());
    }

    @ParameterizedTest
    @ValueSource(strings={"receive","legacyReceive","reject","diagnose","first","second","issue","supplement"})
    void sourceWaitRechecksActorAndDoesNotGrabSpecimenFirst(String op) throws Exception {
        ready(op); Staff a=actor(op); Object before=facts();var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var futures=new ArrayList<Future<Integer>>();
        try{transaction().executeWithoutResult(tx->{lockOrder();futures.add(worker(pool,pid,a,()->operation(op)));waiting(pid,futures.getFirst());probeSpecimenNotLockedBeforeSource();jdbc.update("update sys_user set enabled=false where id=?",a.id());});assertEquals(1005,result(futures.getFirst()));assertEquals(before,facts());}finally{close(pool);}
    }

    @ParameterizedTest
    @ValueSource(strings={"receive","legacyReceive","reject","diagnose","first","second","issue","supplement"})
    void specimenWaitOwnsStrongSourceAndRechecksDisabledActor(String op) throws Exception {
        ready(op);Staff a=actor(op);Object before=facts();var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var futures=new ArrayList<Future<Integer>>();
        try{transaction().executeWithoutResult(tx->{lockSpecimen();futures.add(worker(pool,pid,a,()->operation(op)));waiting(pid,futures.getFirst());probeSourceStrongLock();jdbc.update("update sys_user set enabled=false where id=?",a.id());});assertEquals(1005,result(futures.getFirst()));assertEquals(before,facts());}finally{close(pool);}
    }

    @ParameterizedTest
    @CsvSource({"receive,receive,5208","receive,legacyReceive,4551","legacyReceive,receive,5208","reject,reject,5208","diagnose,reject,5208","reject,diagnose,4553","first,first,5261","second,second,5261","issue,issue,5261"})
    void competingWritesHaveExactlyOneSuccessAndOneReadableStateRefusal(String first,String next,int rejected) throws Exception {
        ready(first);if(first.equals("reject")&&next.equals("diagnose"))jdbc.update("update path_specimen set status='RECEIVED',received_at=now() where id=?",specimen);
        authenticate(actor(first));var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var futures=new ArrayList<Future<Integer>>();
        try{transaction().executeWithoutResult(tx->{assertEquals(0,operation(first));futures.add(worker(pool,pid,actor(next),()->operation(next)));waiting(pid,futures.getFirst());});assertEquals(rejected,result(futures.getFirst()));
            if(node(first)!=null) assertEquals(1,jdbc.queryForObject("select count(*) from path_process where specimen_id=? and node=?",Integer.class,specimen,node(first)));
            if(node(next)!=null&&!java.util.Objects.equals(node(first),node(next)))assertEquals(0,jdbc.queryForObject("select count(*) from path_process where specimen_id=? and node=?",Integer.class,specimen,node(next)));
            assertLedger(first.equals("issue")?"EXECUTED":"CHARGED","PAID");
        }finally{close(pool);}
    }

    @Test
    void lastTwoPartsIssueInBothTransactionsAndOnlyFinalOneCompletesSource() throws Exception {
        ready("issue");Long other=newSpecimen(order,null,2);ready(other,"issue");authenticate(doctor);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{transaction().executeWithoutResult(tx->{var first=report.issue(specimen,auth());assertEquals(0,first.getCode());assertEquals(false,first.getData().get("orderExecuted"));assertEquals(1L,first.getData().get("partsPending"));fs.add(worker(pool,pid,firstDoctor,()->operation("issue",other)));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));assertLedger("EXECUTED","PAID");assertEquals(2,jdbc.queryForObject("select count(*) from path_process where node='ISSUE' and specimen_id in(?,?)",Integer.class,specimen,other));}finally{close(pool);}
    }

    @Test
    void issueThenLastUnissuedPartRejectCompletesSourceWithoutDiscardingHistory() throws Exception {
        ready("issue");Long other=newSpecimen(order,null,2);authenticate(doctor);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{transaction().executeWithoutResult(tx->{assertEquals(0,operation("issue"));fs.add(worker(pool,pid,tech,()->operation("reject",other)));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));assertLedger("EXECUTED","PAID");assertEquals("COLLECTED",jdbc.queryForObject("select status from path_specimen where id=?",String.class,other));assertNotNull(jdbc.queryForObject("select rejected_at from path_specimen where id=?",Object.class,other));}finally{close(pool);}
    }

    @Test
    void newPartCannotSneakPastLastIssueWhileSourceLocked() throws Exception {
        ready("issue");authenticate(doctor);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{transaction().executeWithoutResult(tx->{assertEquals(0,operation("issue"));fs.add(worker(pool,pid,tech,()->registry.register(new RegisterReq(order,null,2,"ROUTINE","模拟部位二",null,null,null,null,false),auth()).getCode()));waiting(pid,fs.getFirst());});assertEquals(5201,result(fs.getFirst()));assertEquals(1,count());assertLedger("EXECUTED","PAID");}finally{close(pool);}
    }

    @Test
    void newPartFirstMakesWaitingIssueKeepWholeOrderUnexecuted() throws Exception {
        ready("issue");authenticate(tech);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{transaction().executeWithoutResult(tx->{assertEquals(0,registry.register(new RegisterReq(order,null,2,"ROUTINE","模拟部位二",null,null,null,null,false),auth()).getCode());fs.add(worker(pool,pid,doctor,()->operation("issue")));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));assertEquals(2,count());assertLedger("CHARGED","PAID");}finally{close(pool);}
    }

    @Test
    void waitingIssueReportsFreshDoubleSignFactsAfterSecondSignCommits() throws Exception {
        ready("second");authenticate(doctor);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();final Map<?,?>[] body=new Map<?,?>[1];
        try{transaction().executeWithoutResult(tx->{assertEquals(0,operation("second"));fs.add(worker(pool,pid,firstDoctor,()->{var r=report.issue(specimen,auth());body[0]=r.getData();return r.getCode();}));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));assertEquals(true,body[0].get("doubleSignComplete"));assertEquals(List.of(),body[0].get("warnings"));assertEquals("双签完整",jdbc.queryForObject("select remark from path_process where specimen_id=? and node='ISSUE'",String.class,specimen));assertLedger("EXECUTED","PAID");}finally{close(pool);}
    }

    @Test
    void competingSupplementsBothCommitContinuousSequenceAndKeepPrimaryUntouched() throws Exception {
        ready("supplement");authenticate(doctor);Object primary=jdbc.queryForMap("select * from path_specimen where id=?",specimen);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{transaction().executeWithoutResult(tx->{assertEquals(0,operation("supplement"));fs.add(worker(pool,pid,firstDoctor,()->operation("supplement")));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));assertEquals(primary,jdbc.queryForMap("select * from path_specimen where id=?",specimen));assertEquals(List.of(1,2),jdbc.queryForList("select seq_no from path_report where specimen_id=? order by seq_no",Integer.class,specimen));assertEquals(2,jdbc.queryForObject("select count(*) from path_process where specimen_id=? and node='SUPPLEMENT'",Integer.class,specimen));}finally{close(pool);}
    }

    @Test
    void waitingSupplementCannotAppendAfterCommittedRejectionOfLegacyCompatibilityRow() throws Exception {
        ready("supplement");jdbc.update("update path_specimen set status='RECEIVED' where id=?",specimen);authenticate(tech);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{transaction().executeWithoutResult(tx->{assertEquals(0,operation("reject"));fs.add(worker(pool,pid,doctor,()->operation("supplement")));waiting(pid,fs.getFirst());});assertEquals(5261,result(fs.getFirst()));assertEquals(0,jdbc.queryForObject("select count(*) from path_report where specimen_id=?",Integer.class,specimen));assertNode("REJECT",tech);}finally{close(pool);}
    }

    @ParameterizedTest
    @ValueSource(strings={"receive","legacyReceive","reject","diagnose","first","second","issue","supplement"})
    void sourceRebindingWhileActuallyWaitingCannotWriteNewPatientsObject(String op) throws Exception {
        ready(op);Long another=jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id)
                values (?,?,'TREAT',0,?,'其他申请','次',1,1,1,'CREATED',?) returning id
                """,Long.class,registration,tag+"b",tag+"b",doctor.id());
        var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] changed=new Object[1];
        try{transaction().executeWithoutResult(tx->{lockSpecimen();fs.add(worker(pool,pid,actor(op),()->operation(op)));waiting(pid,fs.getFirst());jdbc.update("update path_specimen set order_id=? where id=?",another,specimen);changed[0]=facts();});assertEquals(changedCode(op),result(fs.getFirst()));assertEquals(changed[0],facts());}finally{close(pool);}
    }

    @ParameterizedTest
    @ValueSource(strings={"legacyReceive","diagnose"})
    void barcodeRebindingCannotMakeOldEntryFollowMovedObject(String op) throws Exception {
        ready(op);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] changed=new Object[1];
        try{transaction().executeWithoutResult(tx->{lockSpecimen();fs.add(worker(pool,pid,actor(op),()->operation(op)));waiting(pid,fs.getFirst());jdbc.update("update path_specimen set barcode=? where id=?",barcode+"m",specimen);changed[0]=facts();});assertEquals(changedCode(op),result(fs.getFirst()));assertEquals(changed[0],facts());}finally{close(pool);}
    }

    @ParameterizedTest
    @ValueSource(strings={"receive","reject","first","second","issue","supplement"})
    void realProcessInsertFailureRollsBackAllClinicalAndFinancialEffectsThenCanRetry(String op) {
        ready(op);authenticate(actor(op));Object before=facts();String constraint="v117_fail_"+Long.toUnsignedString(System.nanoTime(),36);
        jdbc.execute("alter table path_process add constraint "+constraint+" check(specimen_id <> "+specimen+") not valid");
        try{assertThrows(RuntimeException.class,()->operation(op));assertEquals(before,facts());}finally{jdbc.execute("alter table path_process drop constraint "+constraint);}
        assertEquals(0,operation(op));assertNode(node(op),actor(op));assertLedger(op.equals("issue")?"EXECUTED":"CHARGED","PAID");
    }

    @Test
    void grossRevisionFailureRollsBackDiagnosisOriginalTextAuthorAndTimeThenRetriesOnce() {
        ready("diagnose");authenticate(tech);Object before=facts();String constraint="v117_gross_"+Long.toUnsignedString(System.nanoTime(),36);
        jdbc.execute("alter table path_gross_revision add constraint "+constraint+" check(specimen_id <> "+specimen+") not valid");
        try{assertThrows(RuntimeException.class,()->operation("diagnose"));assertEquals(before,facts());}finally{jdbc.execute("alter table path_gross_revision drop constraint "+constraint);}
        assertEquals(0,operation("diagnose"));var rows=jdbc.queryForList("select * from path_gross_revision where specimen_id=?",specimen);assertEquals(1,rows.size());assertEquals(" 原大体 ",rows.getFirst().get("old_text"));assertEquals("修后大体",rows.getFirst().get("new_text"));assertEquals(tech.id(),rows.getFirst().get("changed_by"));assertLedger("CHARGED","PAID");
    }

    @Test
    void executedUpdateFailureRollsBackIssueAndProcessThenRetryCompletesOriginalBill() {
        ready("issue");authenticate(doctor);Object before=facts();String constraint="v117_exec_"+Long.toUnsignedString(System.nanoTime(),36);
        jdbc.execute("alter table outp_order add constraint "+constraint+" check(id <> "+order+" or status <> 'EXECUTED') not valid");
        try{assertThrows(RuntimeException.class,()->operation("issue"));assertEquals(before,facts());}finally{jdbc.execute("alter table outp_order drop constraint "+constraint);}
        assertEquals(0,operation("issue"));assertNode("ISSUE",doctor);assertLedger("EXECUTED","PAID");
    }

    @Test
    void genuineUnpaidManualSpecimenStillAllowsDiagnosisSignaturesIssueAndSupplement() {
        jdbc.update("delete from path_specimen where id=?",specimen);authenticate(cashier);assertEquals(0,refund());authenticate(tech);
        var r=registry.registerManual(new ManualReq(order,null,1,"ROUTINE","实物组织",null,null,null,null,false,"送检科","送检医师","未收费实物"),auth());assertEquals(0,r.getCode());specimen=((Number)r.getData().get("id")).longValue();barcode=barcodeOf(specimen);
        assertEquals(5208,operation("receive"));assertEquals(0,operation("diagnose"));authenticate(firstDoctor);assertEquals(0,operation("first"));authenticate(doctor);assertEquals(0,operation("second"));var issued=report.issue(specimen,auth());assertEquals(0,issued.getCode());assertEquals(false,issued.getData().get("orderExecuted"));assertEquals(0,operation("supplement"));assertLedger("CREATED","REFUNDED");assertEquals(5,jdbc.queryForObject("select count(*) from path_process where specimen_id=?",Integer.class,specimen));
    }

    @Test
    void allRejectedPartsCanRefundWhileRejectedRecordsRetainOriginalStatuses() {
        Long other=newSpecimen(order,null,2);authenticate(tech);assertEquals(0,operation("reject"));assertEquals(0,operation("reject",other));assertEquals(List.of("COLLECTED","COLLECTED"),jdbc.queryForList("select status from path_specimen where order_id=? order by id",String.class,order));authenticate(cashier);assertEquals(0,refund());assertEquals(2,count());assertLedger("CREATED","REFUNDED");
    }

    @Test
    void inpatientSourceRemainsUnexecutedThroughReceiptDiagnosisDoubleSignIssueAndSupplement() {
        inpatient();Long id=newSpecimen(null,inpOrder,1);specimen=id;barcode=barcodeOf(id);authenticate(tech);assertEquals(0,operation("receive"));assertEquals(0,operation("diagnose"));authenticate(firstDoctor);assertEquals(0,operation("first"));authenticate(doctor);assertEquals(0,operation("second"));var r=report.issue(specimen,auth());assertEquals(0,r.getCode());assertEquals(false,r.getData().get("orderExecuted"));assertEquals(0L,r.getData().get("partsPending"));assertEquals(0,operation("supplement"));assertEquals("CREATED",jdbc.queryForObject("select status from inp_order where id=?",String.class,inpOrder));assertLedger("CHARGED","PAID");
    }

    @Test
    void blankDiagnosticFindingsKeepOriginalTextWithoutInventingRevision() {
        ready("diagnose");authenticate(tech);var r=legacy.diagnose(barcode,new PathologyController.DiagnoseReq("  ",null,"真实诊断"),auth());assertEquals(0,r.getCode());assertEquals(true,r.getData().get("grossKept"));assertEquals(true,r.getData().get("microKept"));assertEquals(false,r.getData().get("grossRevised"));assertEquals(" 原大体 ",jdbc.queryForObject("select gross_finding from path_specimen where id=?",String.class,specimen));assertEquals(0,jdbc.queryForObject("select count(*) from path_gross_revision where specimen_id=?",Integer.class,specimen));
    }

    @ParameterizedTest
    @CsvSource({"off,0,false,0","warn,0,false,1","block,5265,false,0"})
    void doubleSignGateKeepsObjectiveMissingFactsAndOriginalThreeTierPolicy(String tier,int expected,boolean complete,int warnings) {
        ready("supplement");jdbc.update("update sys_config set cfg_value=? where cfg_key='emr.gate.pathology.doublesign'",tier);config.evictAll();authenticate(doctor);Object before=facts();var r=report.issue(specimen,auth());assertEquals(expected,r.getCode());if(expected!=0){assertEquals(before,facts());return;}assertEquals(complete,r.getData().get("doubleSignComplete"));assertEquals(warnings,((List<?>)r.getData().get("warnings")).size());assertEquals("DIAGNOSED",jdbc.queryForObject("select status from path_specimen where id=?",String.class,specimen));
    }

    @Test
    void samePersonSecondSignAndSigningAfterIssueStillUseOriginalBusinessCodes() {
        ready("second");authenticate(firstDoctor);Object before=facts();assertEquals(5263,operation("second"));assertEquals(before,facts());authenticate(doctor);assertEquals(0,operation("second"));assertEquals(0,operation("issue"));before=facts();assertEquals(5261,operation("first"));assertEquals(5261,operation("second"));assertEquals(5261,operation("issue"));assertEquals(before,facts());
    }

    @ParameterizedTest
    @ValueSource(strings={"receive","diagnose","issue","supplement"})
    void pendingManagedSourceMetadataFlushIsVisibleBeforeWriteAndFinanceRemainsIntact(String op) {
        ready(op);authenticate(actor(op));transaction().executeWithoutResult(tx->{var o=orders.findById(order).orElseThrow();o.setRemark("组合新备注");o.setClinicalSummary("组合新摘要");assertEquals(0,operation(op));assertEquals("组合新备注",jdbc.queryForObject("select remark from outp_order where id=?",String.class,order));});
        var row=jdbc.queryForMap("select status,charge_id,remark,clinical_summary,amount from outp_order where id=?",order);assertEquals(op.equals("issue")?"EXECUTED":"CHARGED",row.get("status"));assertEquals(charge,row.get("charge_id"));assertEquals("组合新备注",row.get("remark"));assertEquals("组合新摘要",row.get("clinical_summary"));assertEquals(0,new BigDecimal("7.50").compareTo((BigDecimal)row.get("amount")));
    }
}

