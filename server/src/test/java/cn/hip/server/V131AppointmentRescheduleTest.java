package cn.hip.server;

import cn.hip.medtech.web.AppointmentController;
import cn.hip.outpatient.web.QueueCenterController;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** No enclosing test transaction. B is a real JWT request; each race observes a
 * PostgreSQL UPDATE Lock on the named A before A is released. Private SQL fixtures
 * are controlled test history. The old bug changes terminal appointment scheduling
 * fields, not its terminal status. Financial/clinical columns are never excused. */
@SpringBootTest
@AutoConfigureMockMvc
class V131AppointmentRescheduleTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired AppointmentController appointments;
    @Autowired QueueCenterController queue;

    record Staff(Long id, String name, String token) {}
    record Outcome(int http, JsonNode body) {
        int code() { return body.path("code").asInt(); }
        JsonNode data() { return body.path("data"); }
    }
    String tag; Long dept, staffDept, patient, schedule, registration, order, secondOrder, bill, appointment;
    Staff actor, holderStaff; final List<Staff> staff = new ArrayList<>();
    LocalDate originalDate, targetDate;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        tag = "V131" + Long.toUnsignedString(System.nanoTime(), 36);
        originalDate = BusinessDates.today(); targetDate = originalDate.plusDays(400);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag, tag);
        staffDept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id", Long.class, tag+"S", tag+"异科");
        actor = employee("actor", "TECHNICIAN"); holderStaff = employee("holder", "ADMIN");
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id", Long.class, tag, tag+"患者");
        schedule = jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date,5,1,1,0) returning id", Long.class, dept);
        registration = jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (1,?,?,?,current_date,0,'VISITED') returning id", Long.class, patient, schedule, dept);
        order = newOrder("目标超声", "EXAM"); secondOrder = newOrder("另一心电", "EXAM");
        bill = jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status,cashier_id) values (?,?,15.00,'CASH','PAID',?) returning id", Long.class, tag, registration, holderStaff.id());
        jdbc.update("update outp_order set charge_id=? where registration_id=?", bill, registration);
        authenticate(holderStaff, "ADMIN");
        try {
            var result = appointments.book(new AppointmentController.BookReq(order, originalDate.toString(), "AM"));
            assertEquals(0, result.getCode(), "Fixture book must succeed");
            appointment = jdbc.queryForObject("select id from med_appointment where order_id=?", Long.class, order);
            assertTrue(((Number)result.getData().get("seqNo")).intValue()>0);
        } finally { SecurityContextHolder.clearContext(); }
    }
    Staff employee(String suffix, String duty) {
        String name=tag+suffix;
        Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,?) returning id",Long.class,name,name,staffDept);
        var s=new Staff(id,name,jwt.issue(name)); staff.add(s); roles(s,duty); return s;
    }
    void roles(Staff who, String... duties) {
        jdbc.update("delete from sys_user_role where user_id=?",who.id());
        for(String duty:duties) assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",who.id(),duty));
    }
    Long newOrder(String label,String kind) {
        return jdbc.queryForObject("""
            insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id,
                clinical_summary,exam_purpose,notice,urgent,remark,review_status,review_note,reviewer_id)
            values (?,?,?,0,?,?,'次',1,7.50,7.50,'CHARGED',?,'原摘要','原目的','原注意',true,'原备注','APPROVED','原审方',?) returning id
            """,Long.class,registration,tag,kind,tag+label,label,holderStaff.id(),holderStaff.id());
    }
    void authenticate(Staff who,String duty) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(who.name(),null,List.of(new SimpleGrantedAuthority("ROLE_"+duty))));
    }
    Outcome request(Long id, String date, String period, String token) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req=put("/api/appointments/"+id+"/reschedule").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("slotDate",date,"period",period)));
            if(token!=null) req.header("Authorization","Bearer "+token);
            var response=mvc.perform(req).andReturn().getResponse();
            return new Outcome(response.getStatus(),mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        } finally { SecurityContextHolder.clearContext(); }
    }
    Outcome reschedule() throws Exception { return request(appointment,targetDate.toString(),"PM",actor.token()); }
    Map<String,List<Map<String,Object>>> facts() {
        var out=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String[] t:new String[][]{
                {"appointments","med_appointment","true"},{"queueCalls","queue_call_log","true"},
                {"patients","empi_patient","id="+patient},{"registration","outp_registration","id="+registration},
                {"schedule","outp_schedule","id="+schedule},{"orders","outp_order","registration_id="+registration},
                {"bills","outp_charge","registration_id="+registration},{"exams","ris_exam","order_id in(select id from outp_order where registration_id="+registration+")"},
                {"samples","lis_sample","order_id in(select id from outp_order where registration_id="+registration+")"},
                {"results","outp_lab_result","order_id in(select id from outp_order where registration_id="+registration+")"},
                {"reports","outp_order_report","order_id in(select id from outp_order where registration_id="+registration+")"},
                {"diagnoses","outp_diagnosis","registration_id="+registration},{"alerts","outp_critical_alert","registration_id="+registration},
                {"cdr","cdr_document","patient_id="+patient},{"infusions","outp_infusion","order_id in(select id from outp_order where registration_id="+registration+")"},
                {"micro","lab_micro_result","order_id in(select id from outp_order where registration_id="+registration+")"},
                {"ast","lab_micro_ast","true"},{"iqc","lab_qc_record","true"},{"shares","report_share","true"},
                {"stock","pharm_stock","true"},{"stockFlow","pharm_stock_flow","true"},{"payOrders","pay_order","true"},
                {"refundApprovals","outp_refund_approval","true"},{"printLogs","fin_print_log","true"}})
            out.put(t[0],jdbc.queryForList("select * from "+t[1]+" where "+t[2]+" order by id"));
        out.put("config",jdbc.queryForList("select * from sys_config order by cfg_key")); return out;
    }
    Map<String,List<Map<String,Object>>> copy(Map<String,List<Map<String,Object>>> source) {
        var out=new LinkedHashMap<String,List<Map<String,Object>>>();
        source.forEach((k,v)->{var rows=new ArrayList<Map<String,Object>>();v.forEach(row->rows.add(new LinkedHashMap<>(row)));out.put(k,rows);});return out;
    }
    Map<String,Object> row(Map<String,List<Map<String,Object>>> source,String key,Long id) {
        return source.get(key).stream().filter(x->id.equals(((Number)x.get("id")).longValue())).findFirst().orElseThrow();
    }
    Object pgNow() { return jdbc.queryForObject("select now()",Object.class); }
    Instant instant(Object value) { return value instanceof Timestamp t?t.toInstant():((java.time.OffsetDateTime)value).toInstant(); }
    List<Object> authFacts() {
        return List.of(jdbc.queryForList("select * from sys_user where username like ? order by id",tag+"%"),
                jdbc.queryForList("select ur.* from sys_user_role ur join sys_user u on u.id=ur.user_id where u.username like ? order by ur.user_id,ur.role_id",tag+"%"));
    }
    TransactionTemplate tx() { var tx=new TransactionTemplate(manager);tx.setTimeout(25);return tx; }
    void lockAppointment() { assertEquals(List.of(appointment),jdbc.queryForList("select id from med_appointment where id=? for update",Long.class,appointment)); }
    void waitForHolder(int holder,Future<?> result,String stage) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<end) {
            jdbc.execute("select pg_stat_clear_snapshot()");
            var rows=jdbc.queryForList("""
                select pid,query_start,wait_event_type,wait_event,pg_blocking_pids(pid) as blockers,query
                from pg_stat_activity where pid<>pg_backend_pid() and wait_event_type='Lock'
                  and ?=any(pg_blocking_pids(pid)) and lower(query) like '%update med_appointment set slot_date%'
                """,holder);
            if(!rows.isEmpty()) { assertEquals(1,rows.size());System.out.println("V131 PG_LOCK stage="+stage+" holder="+holder+" waiters="+rows);return; }
            if(result.isDone()) fail("MONITOR: request ended without the named-holder UPDATE Lock: "+stage);
            try {Thread.sleep(20);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}
        }
        fail("MONITOR: missing named-holder UPDATE Lock: "+stage);
    }
    Outcome finish(Future<Outcome> result)throws Exception {
        try{return result.get(20,TimeUnit.SECONDS);}catch(ExecutionException ex){return fail("Expected an HTTP result, not a fixture/SQL/monitor error",ex.getCause());}
    }
    void close(ExecutorService pool)throws Exception {pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    int targetNext() {return jdbc.queryForObject("select coalesce(max(seq_no),0)+1 from med_appointment where slot_date=?::date and period='PM'",Integer.class,targetDate.toString());}
    void accepted(Outcome response,Map<String,List<Map<String,Object>>> before,int seq) {
        var expected=copy(before);var a=row(expected,"appointments",appointment);
        a.put("slot_date",java.sql.Date.valueOf(targetDate));a.put("period","PM");a.put("seq_no",seq);
        assertAll(()->assertEquals(200,response.http(),response.body().toString()),()->assertEquals(0,response.code(),response.body().toString()),
                ()->assertEquals(Set.of("seqNo"),keys(response.data())),()->assertEquals(seq,response.data().path("seqNo").asInt()),
                ()->assertEquals(expected,facts(),"Only date/period/seq of this BOOKED appointment may change; every other column is exact"));
    }
    Set<String> keys(JsonNode node) {var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    void rejected(Outcome response,int http,int code,Map<String,List<Map<String,Object>>> expected) {
        System.out.println("V131 BUSINESS expected="+http+"/"+code+" actual="+response.http()+"/"+response.code());
        assertAll(()->assertEquals(http,response.http(),response.body().toString()),()->assertEquals(code,response.code(),response.body().toString()),
                ()->assertTrue(response.data().isNull()),()->assertEquals(expected,facts(),"No scheduling or clinical/financial column may change"));
    }
    Map<String,List<Map<String,Object>>> terminal(String action,Map<String,List<Map<String,Object>>> before) {
        var expected=copy(before);authenticate(holderStaff,"ADMIN");
        try {
            switch(action) {
                case "done" -> {assertEquals(0,appointments.done(appointment).getCode());row(expected,"appointments",appointment).put("status","DONE");}
                case "cancel" -> {var response=appointments.batchCancel(new AppointmentController.BatchCancelReq(List.of(appointment)));assertEquals(0,response.getCode());assertEquals(1,response.getData().get("cancelled"));row(expected,"appointments",appointment).put("status","CANCELLED");}
                case "call" -> {
                    var waiting=jdbc.queryForList("select a.id from med_appointment a join outp_order o on o.id=a.order_id where o.order_type='EXAM' and a.status='BOOKED' and a.slot_date=current_date order by a.period,a.seq_no limit 1",Long.class);
                    assertEquals(List.of(appointment),waiting,"Fixture must be the actual next EXAM call");
                    var response=queue.callNext("EXAM");assertEquals(0,response.getCode());assertEquals(appointment.toString(),response.getData().get("biz_key"));
                    row(expected,"appointments",appointment).put("status","CALLED");
                    var added=jdbc.queryForList("select * from queue_call_log where biz_type='EXAM' and biz_key=? order by id",appointment.toString());assertEquals(1,added.size());
                    var log=new LinkedHashMap<String,Object>();log.put("id",added.getFirst().get("id"));assertTrue(((Number)log.get("id")).longValue()>0);
                    log.put("biz_type","EXAM");log.put("biz_key",appointment.toString());log.put("patient_name",tag+"患者");log.put("called_at",pgNow());
                    assertEquals(log,added.getFirst());expected.get("queueCalls").add(log);expected.get("queueCalls").sort(Comparator.comparingLong(x->((Number)x.get("id")).longValue()));
                }
                default -> throw new AssertionError(action);
            }
        } finally {SecurityContextHolder.clearContext();}
        assertEquals(expected,facts(),"Allocate the real holder action completely before releasing it");return expected;
    }

    @ParameterizedTest @CsvSource({"done,false","cancel,false","call,false","done,true","cancel,true","call,true"})
    void committedTerminalActionPreventsChangingItsSchedulingFieldsAndRollbackStillAllowsIt(String action,boolean rollback)throws Exception {
        var before=facts();int seq=targetNext();var expected=new AtomicReference<Map<String,List<Map<String,Object>>>>();
        var pool=Executors.newSingleThreadExecutor();var result=new ArrayList<Future<Outcome>>();
        try {
            tx().executeWithoutResult(t->{expected.set(terminal(action,before));int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                result.add(pool.submit(this::reschedule));waitForHolder(holder,result.getFirst(),"terminal:"+action+":"+(rollback?"rollback":"commit"));if(rollback)t.setRollbackOnly();});
            var response=finish(result.getFirst());
            if(rollback) accepted(response,before,seq);else rejected(response,200,4513,expected.get());
        } finally {close(pool);}
    }
    @ParameterizedTest @CsvSource({"ADMIN,disable","TECHNICIAN,revoke","NURSE,replace"})
    void actualWaitingRequestRejectsCurrentDisabledOrLostDutyAndRollsBackItsUpdate(String duty,String action)throws Exception {
        roles(actor,duty);var before=facts();var expectedAuth=new AtomicReference<List<Object>>();var pool=Executors.newSingleThreadExecutor();var result=new ArrayList<Future<Outcome>>();
        try {
            tx().executeWithoutResult(t->{lockAppointment();int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);result.add(pool.submit(this::reschedule));waitForHolder(holder,result.getFirst(),"identity:"+duty+":"+action);
                if(action.equals("disable"))jdbc.update("update sys_user set enabled=false where id=?",actor.id());else if(action.equals("revoke"))roles(actor);else roles(actor,"QUALITY");expectedAuth.set(authFacts());});
            rejected(finish(result.getFirst()),403,1005,before);assertEquals(expectedAuth.get(),authFacts(),"Only the holder changes employee facts");
            jdbc.update("update sys_user set enabled=true where id=?",actor.id());roles(actor,duty);int seq=targetNext();accepted(reschedule(),before,seq);
        } finally {close(pool);}
    }
    @ParameterizedTest @CsvSource({"ADMIN,TECHNICIAN","TECHNICIAN,NURSE","NURSE,ADMIN"})
    void allowedDutyChangesDuringTheWaitRemainCompatible(String from,String to)throws Exception {
        roles(actor,from);var before=facts();int seq=targetNext();var expectedAuth=new AtomicReference<List<Object>>();var pool=Executors.newSingleThreadExecutor();var result=new ArrayList<Future<Outcome>>();
        try {tx().executeWithoutResult(t->{lockAppointment();int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);result.add(pool.submit(this::reschedule));waitForHolder(holder,result.getFirst(),"role-change:"+from+":"+to);roles(actor,to);expectedAuth.set(authFacts());});accepted(finish(result.getFirst()),before,seq);assertEquals(expectedAuth.get(),authFacts());}finally{close(pool);}
    }
    @ParameterizedTest @CsvSource({"ADMIN","TECHNICIAN","NURSE"})
    void rolledBackRevocationDoesNotRejectAnOtherwiseEligibleEmployee(String duty)throws Exception {
        roles(actor,duty);var before=facts();var auth=authFacts();int seq=targetNext();var pool=Executors.newSingleThreadExecutor();var result=new ArrayList<Future<Outcome>>();
        try {tx().executeWithoutResult(t->{lockAppointment();int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);result.add(pool.submit(this::reschedule));waitForHolder(holder,result.getFirst(),"identity-rollback:"+duty);roles(actor,"QUALITY");t.setRollbackOnly();});accepted(finish(result.getFirst()),before,seq);assertEquals(auth,authFacts());}finally{close(pool);}
    }
    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","NURSE"})
    void originalThreeCrossDepartmentDutiesKeepExactSeqNoAndAllOtherColumns(String duty)throws Exception {
        roles(actor,duty);var before=facts();int seq=targetNext();accepted(reschedule(),before,seq);
    }
    @Test void multipleDutiesRetainTheRemainingAllowedDutyAfterWait()throws Exception {
        roles(actor,"QUALITY","TECHNICIAN","NURSE");var before=facts();int seq=targetNext();var pool=Executors.newSingleThreadExecutor();var result=new ArrayList<Future<Outcome>>();
        try {tx().executeWithoutResult(t->{lockAppointment();int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);result.add(pool.submit(this::reschedule));waitForHolder(holder,result.getFirst(),"multirole:remaining-nurse");roles(actor,"QUALITY","NURSE");});accepted(finish(result.getFirst()),before,seq);}finally{close(pool);}
    }
    @ParameterizedTest @ValueSource(strings={"CALLED","DONE","CANCELLED"})
    void alreadyTerminalObjectsReturnOriginal4513WithoutChangingAnyField(String status)throws Exception {
        jdbc.update("update med_appointment set status=? where id=?",status,appointment);var before=facts();rejected(reschedule(),200,4513,before);
    }
    @Test void missingObjectReturnsOriginal4513()throws Exception {var before=facts();rejected(request(-1L,targetDate.toString(),"PM",actor.token()),200,4513,before);}
    @ParameterizedTest @ValueSource(strings={"","am","NIGHT"})
    void originalInvalidPeriodPrecedesObjectLookup(String period)throws Exception {var before=facts();rejected(request(-1L,targetDate.toString(),period,actor.token()),200,4510,before);}
    @Test void targetMaximumAndRepeatedSameSlotReschedulingKeepOriginalRules()throws Exception {
        authenticate(holderStaff,"ADMIN");try{assertEquals(0,appointments.book(new AppointmentController.BookReq(secondOrder,targetDate.toString(),"PM")).getCode());}finally{SecurityContextHolder.clearContext();}
        var before=facts();int seq=targetNext();accepted(reschedule(),before,seq);var first=facts();accepted(reschedule(),first,seq+1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void targetSequenceUniqueConflictReturns4514OrSucceedsAfterRollback(boolean rollback)throws Exception {
        var before=facts();var expected=new AtomicReference<Map<String,List<Map<String,Object>>>>();var pool=Executors.newSingleThreadExecutor();var result=new ArrayList<Future<Outcome>>();
        try {
            tx().executeWithoutResult(t->{authenticate(holderStaff,"ADMIN");try {var response=appointments.book(new AppointmentController.BookReq(secondOrder,targetDate.toString(),"PM"));assertEquals(0,response.getCode());
                var added=jdbc.queryForMap("select * from med_appointment where order_id=?",secondOrder);var a=new LinkedHashMap<String,Object>();a.put("id",added.get("id"));a.put("order_id",secondOrder);a.put("slot_date",java.sql.Date.valueOf(targetDate));a.put("period","PM");a.put("seq_no",1);a.put("status","BOOKED");a.put("created_at",pgNow());assertEquals(a,added);
                expected.set(copy(before));expected.get().get("appointments").add(a);expected.get().get("appointments").sort(Comparator.comparingLong(x->((Number)x.get("id")).longValue()));assertEquals(expected.get(),facts());
            }finally{SecurityContextHolder.clearContext();}
                int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);result.add(pool.submit(this::reschedule));waitForHolder(holder,result.getFirst(),"unique:"+(rollback?"rollback":"commit"));if(rollback)t.setRollbackOnly();});
            var response=finish(result.getFirst());if(rollback)accepted(response,before,1);else rejected(response,200,4514,expected.get());
        } finally {close(pool);}
    }
    @ParameterizedTest @ValueSource(strings={"CASHIER","DOCTOR_OUTP","anonymous","disabled","PORTAL"})
    void entryEmployeeNamespaceAndOriginalRolesRemainGuarded(String identity)throws Exception {
        String token=actor.token();int http=403,code=1005;
        if(identity.equals("anonymous")){token=null;http=401;code=1000;}
        else if(identity.equals("disabled")){jdbc.update("update sys_user set enabled=false where id=?",actor.id());http=401;code=1000;}
        else if(identity.equals("PORTAL"))token=jwt.issue("portal:"+patient);else roles(actor,identity);
        var before=facts();rejected(request(appointment,targetDate.toString(),"PM",token),http,code,before);
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();if(registration==null)return;
        tx().executeWithoutResult(t->{jdbc.update("delete from queue_call_log where biz_type in ('EXAM','LAB') and biz_key in(select id::varchar from med_appointment where order_id in(select id from outp_order where registration_id=?))",registration);
            jdbc.update("delete from med_appointment where order_id in(select id from outp_order where registration_id=?)",registration);
            jdbc.update("delete from outp_order where registration_id=?",registration);jdbc.update("delete from outp_charge where registration_id=?",registration);
            jdbc.update("delete from outp_registration where id=?",registration);jdbc.update("delete from outp_schedule where id=?",schedule);jdbc.update("delete from empi_patient where id=?",patient);
            for(Staff s:staff){jdbc.update("delete from sys_user_role where user_id=?",s.id());jdbc.update("delete from sys_user where id=?",s.id());}jdbc.update("delete from sys_dept where id in (?,?)",dept,staffDept);});staff.clear();
    }
}
