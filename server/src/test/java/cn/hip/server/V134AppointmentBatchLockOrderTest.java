package cn.hip.server;

import cn.hip.outpatient.entity.OutpCharge;
import cn.hip.outpatient.service.ChargeService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Two real JWT request transactions and two external lock-only holders.
 * The relay adapts to either old reverse first-row waiting or sorted low-row waiting;
 * it never requires the fixed implementation to acquire high before low. No mock JDBC,
 * subclass or artificial controller barrier. Old deadlock is a real SQLState40P01
 * HTTP failure, not an observer failure. Controlled SQL fixtures are private history. */
@SpringBootTest
@AutoConfigureMockMvc
class V134AppointmentBatchLockOrderTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired ChargeService charges;
    @Autowired cn.hip.medtech.web.AppointmentController appointments;
    String tag; Long first,second; Long dept,staffDept,patient,schedule,registration,order,secondOrder,bill,secondBill; int serial;
    Staff actor,cashier,doctor,holderStaff,peer; final List<Staff> staff=new ArrayList<>();
    java.time.LocalDate date;
    record Staff(Long id,String name,String token){}
    record Outcome(int http,JsonNode body,String sqlState,String sqlDetail){int code(){return body.get("code").asInt();}JsonNode data(){return body.get("data");}}
    @BeforeEach void fixture(){
        SecurityContextHolder.clearContext();tag="V134"+Long.toUnsignedString(System.nanoTime(),36);date=BusinessDates.today().plusDays(500);
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        staffDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag+"S",tag+"异科");
        actor=employee("actor","TECHNICIAN");cashier=employee("cashier","CASHIER");doctor=employee("doctor","DOCTOR_OUTP");holderStaff=employee("holder","ADMIN");peer=employee("peer","NURSE");
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag+"患者");
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,1,1,0) returning id",Long.class,dept);
        registration=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status) values (1,?,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,patient,schedule,dept,doctor.id());
        order=newOrder("首单超声","EXAM","CHARGED");secondOrder=newOrder("独立心电","EXAM","CHARGED");
        bill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status,cashier_id) values (?,?,7.50,'CASH','PAID',?) returning id",Long.class,tag,registration,cashier.id());
        secondBill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status,cashier_id) values (?,?,7.50,'CASH','PAID',?) returning id",Long.class,tag+"S",registration,cashier.id());
        jdbc.update("update outp_order set charge_id=? where id=?",bill,order);jdbc.update("update outp_order set charge_id=? where id=?",secondBill,secondOrder);
        initializeAppointments();
    }
    Staff employee(String suffix,String role){String name=tag+suffix;Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,?) returning id",Long.class,name,name,staffDept);var s=new Staff(id,name,jwt.issue(name));staff.add(s);assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role));return s;}
    void role(Staff s,String code){jdbc.update("delete from sys_user_role where user_id=?",s.id());if(code!=null)assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",s.id(),code));}
    Long newOrder(String label,String type,String state){String suffix="_"+(++serial);return jdbc.queryForObject("""
            insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id,
                clinical_summary,exam_purpose,notice,urgent,remark,review_status,review_note,reviewer_id)
            values (?,?,?,0,?,?,'次',1,7.50,7.50,?,?,'原摘要','原目的','原注意',true,'原备注','APPROVED','原审方',?) returning id
            """,Long.class,registration,tag+suffix,type,tag+suffix,label,state,doctor.id(),doctor.id());}
    void roles(Staff who,String... duties){jdbc.update("delete from sys_user_role where user_id=?",who.id());for(String duty:duties)assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",who.id(),duty));}
    Map<String,List<Map<String,Object>>> facts(){var out=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String[] t:new String[][]{{"patients","empi_patient","id="+patient},{"registration","outp_registration","id="+registration},{"schedule","outp_schedule","id="+schedule},{"orders","outp_order","registration_id="+registration},{"bills","outp_charge","registration_id="+registration},{"exams","ris_exam","order_id in(select id from outp_order where registration_id="+registration+")"},{"samples","lis_sample","order_id in(select id from outp_order where registration_id="+registration+")"},{"results","outp_lab_result","order_id in(select id from outp_order where registration_id="+registration+")"},{"alerts","outp_critical_alert","registration_id="+registration},{"cdr","cdr_document","patient_id="+patient},{"reports","outp_order_report","order_id in(select id from outp_order where registration_id="+registration+")"},{"diagnoses","outp_diagnosis","registration_id="+registration},{"micro","lab_micro_result","order_id in(select id from outp_order where registration_id="+registration+")"},{"shares","report_share","true"},{"stock","pharm_stock","true"},{"stockFlow","pharm_stock_flow","true"},{"payOrders","pay_order","true"},{"refundApprovals","outp_refund_approval","true"},{"printLogs","fin_print_log","true"}})out.put(t[0],jdbc.queryForList("select * from "+t[1]+" where "+t[2]+" order by id"));
        out.put("appointments",jdbc.queryForList("select * from med_appointment order by id"));out.put("queueCalls",jdbc.queryForList("select * from queue_call_log order by id"));out.put("infusions",jdbc.queryForList("select * from outp_infusion order by id"));out.put("infusionChecks",jdbc.queryForList("select * from outp_infusion_check order by id"));out.put("ast",jdbc.queryForList("select * from lab_micro_ast order by id"));out.put("iqc",jdbc.queryForList("select * from lab_qc_record order by id"));out.put("config",jdbc.queryForList("select * from sys_config order by cfg_key"));return out;}
    Map<String,List<Map<String,Object>>> copy(Map<String,List<Map<String,Object>>> source){var out=new LinkedHashMap<String,List<Map<String,Object>>>();source.forEach((k,v)->{var rows=new ArrayList<Map<String,Object>>();v.forEach(r->rows.add(new LinkedHashMap<>(r)));out.put(k,rows);});return out;}
    Map<String,Object> row(Map<String,List<Map<String,Object>>> f,String table,Long id){return f.get(table).stream().filter(r->id.equals(((Number)r.get("id")).longValue())).findFirst().orElseThrow();}
    void holderAuth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(holderStaff.name(),null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
    void initializeAppointments(){holderAuth();try{for(Long source:List.of(order,secondOrder)){var result=appointments.book(new cn.hip.medtech.web.AppointmentController.BookReq(source,date.toString(),"PM"));assertEquals(0,result.getCode());assertTrue(((Number)result.getData().get("seqNo")).intValue()>0);}}finally{SecurityContextHolder.clearContext();}first=jdbc.queryForObject("select id from med_appointment where order_id=?",Long.class,order);second=jdbc.queryForObject("select id from med_appointment where order_id=?",Long.class,secondOrder);assertNotEquals(first,second);}
    Outcome request(List<Long> ids,String token,boolean omitIds)throws Exception{SecurityContextHolder.clearContext();try{var body=new LinkedHashMap<String,Object>();if(!omitIds)body.put("ids",ids);var req=post("/api/appointments/batch-cancel").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));if(token!=null)req.header("Authorization","Bearer "+token);var result=mvc.perform(req).andReturn();var response=result.getResponse();String state=null,detail=null;for(Throwable cause=result.getResolvedException();cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql){state=sql.getSQLState();detail=sql.getMessage();}return new Outcome(response.getStatus(),mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)),state,detail);}finally{SecurityContextHolder.clearContext();}}
    Outcome request(List<Long> ids,String token)throws Exception{return request(ids,token,false);}
    List<Object> authFacts(){return List.of(jdbc.queryForList("select * from sys_user where username like ? order by id",tag+"%"),jdbc.queryForList("select ur.* from sys_user_role ur join sys_user u on u.id=ur.user_id where u.username like ? order by ur.user_id,ur.role_id",tag+"%"));}
    TransactionTemplate tx(){var tx=new TransactionTemplate(manager);tx.setTimeout(40);return tx;}
    int pid(Object value){return ((Number)value).intValue();}
    void pause(){try{Thread.sleep(15);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}}
    final class HeldRow implements AutoCloseable {
        final AtomicInteger pid=new AtomicInteger();final CountDownLatch ready=new CountDownLatch(1),release=new CountDownLatch(1);
        final ExecutorService pool=Executors.newSingleThreadExecutor();final Future<?> task;final Long id;
        HeldRow(Long id){this.id=id;task=pool.submit(()->tx().executeWithoutResult(t->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));assertEquals(List.of(id),jdbc.queryForList("select id from med_appointment where id=? for update",Long.class,id));System.out.println("V134 HOLDER_READY id="+id+" pid="+pid.get());ready.countDown();try{assertTrue(release.await(30,TimeUnit.SECONDS),"MONITOR: owned row holder deadline");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}}));}
        void await()throws Exception{assertTrue(ready.await(12,TimeUnit.SECONDS),"MONITOR: holder must actually own row before request");assertTrue(pid.get()>0);assertFalse(task.isDone());}
        void commit()throws Exception{release.countDown();task.get(15,TimeUnit.SECONDS);}
        @Override public void close()throws Exception{release.countDown();try{task.get(20,TimeUnit.SECONDS);}finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}}
    }
    Map<String,Object> initialWait(Future<Outcome> future,int excluded,int lowHolder,int highHolder,String stage){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){jdbc.execute("select pg_stat_clear_snapshot()");var rows=jdbc.queryForList("""
        select pid,query,query_start,wait_event_type,wait_event,backend_xid::text as xid,pg_blocking_pids(pid)::text as blockers,
          ?=any(pg_blocking_pids(pid)) as low_holder,?=any(pg_blocking_pids(pid)) as high_holder,
          ?=any(pg_blocking_pids(pid)) as prior_request
        from pg_stat_activity where datname=current_database() and pid<>? and pid<>pg_backend_pid()
          and wait_event_type='Lock' and lower(query) like '%update med_appointment set status%'
          and (?=any(pg_blocking_pids(pid)) or ?=any(pg_blocking_pids(pid)) or ?=any(pg_blocking_pids(pid)))
        """,lowHolder,highHolder,excluded,excluded,lowHolder,highHolder,excluded);if(!rows.isEmpty()){assertEquals(1,rows.size());System.out.println("V134 PG_LOCK stage="+stage+" observed="+rows.getFirst());return rows.getFirst();}if(future.isDone())fail("MONITOR: request completed before required named-holder Lock "+stage);pause();}return fail("MONITOR: request did not reach named-holder Lock "+stage);}
    Map<String,Object> relay(int b,int c,int highHolder,Future<?> left,Future<?> right){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){jdbc.execute("select pg_stat_clear_snapshot()");var rows=jdbc.queryForList("""
        select b.pid as b_pid,b.query as b_query,b.wait_event_type as b_wait,b.backend_xid::text as b_xid,
          pg_blocking_pids(b.pid)::text as b_blockers,c.pid as c_pid,c.query as c_query,c.wait_event_type as c_wait,
          pg_blocking_pids(c.pid)::text as c_blockers,a.id as low_id,a.status as low_visible_status,a.xmax::text as low_xmax,
          ?=any(pg_blocking_pids(b.pid)) as b_high_holder,?=any(pg_blocking_pids(b.pid)) as b_waits_c,
          ?=any(pg_blocking_pids(c.pid)) as c_high_holder,?=any(pg_blocking_pids(c.pid)) as c_waits_b
        from pg_stat_activity b cross join pg_stat_activity c cross join med_appointment a
        where b.pid=? and c.pid=? and a.id=?
        """,highHolder,c,highHolder,b,b,c,first);if(rows.size()==1){var x=rows.getFirst();boolean high=Boolean.TRUE.equals(x.get("b_high_holder"))||Boolean.TRUE.equals(x.get("b_waits_c"));boolean peerGraph=Boolean.TRUE.equals(x.get("c_high_holder"))||Boolean.TRUE.equals(x.get("c_waits_b"));if("Lock".equals(x.get("b_wait"))&&"Lock".equals(x.get("c_wait"))&&x.get("b_xid")!=null&&x.get("b_xid").equals(x.get("low_xmax"))&&"BOOKED".equals(x.get("low_visible_status"))&&high&&peerGraph){assertTrue(x.get("b_query").toString().toLowerCase().contains("update med_appointment set status"));assertTrue(x.get("c_query").toString().toLowerCase().contains("update med_appointment set status"));System.out.println("V134 PG_LOCK stage=relay-low-already-updated observed="+x);return x;}}if(left.isDone()||right.isDone())fail("MONITOR: a request ended before the relay low-row/real-second-wait proof");pause();}return fail("MONITOR: relay must prove low.xmax=B.xid and both actual waits before high is released");}
    void optionalCycle(int b,int c,Future<?> left,Future<?> right){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!left.isDone()&&!right.isDone()&&System.nanoTime()<end){jdbc.execute("select pg_stat_clear_snapshot()");var rows=jdbc.queryForList("""
        select b.pid as b_pid,b.query as b_query,pg_blocking_pids(b.pid)::text as b_blockers,
          c.pid as c_pid,c.query as c_query,pg_blocking_pids(c.pid)::text as c_blockers
        from pg_stat_activity b cross join pg_stat_activity c where b.pid=? and c.pid=?
          and b.wait_event_type='Lock' and c.wait_event_type='Lock'
          and ?=any(pg_blocking_pids(b.pid)) and ?=any(pg_blocking_pids(c.pid))
        """,b,c,c,b);if(!rows.isEmpty()){assertEquals(1,rows.size());System.out.println("V134 ACTUAL_CYCLE "+rows.getFirst());return;}pause();}}
    Outcome finish(Future<Outcome> future)throws Exception{try{return future.get(20,TimeUnit.SECONDS);}catch(ExecutionException ex){return fail("Expected an actual HTTP result, not a harness/SQL/observer error",ex.getCause());}}
    int count(Outcome result){return result.code()==0?result.data().path("cancelled").asInt():0;}
    Map<String,List<Map<String,Object>>> cancelModel(Map<String,List<Map<String,Object>>> before,List<Long> ids){var expected=copy(before);for(Long id:ids){if(id==null)continue;for(var a:expected.get("appointments"))if(id.equals(((Number)a.get("id")).longValue())&&"BOOKED".equals(a.get("status")))a.put("status","CANCELLED");}return expected;}
    int expectedCount(Map<String,List<Map<String,Object>>> before,List<Long> ids){var expected=cancelModel(before,ids);int changed=0;for(int i=0;i<before.get("appointments").size();i++)if(!before.get("appointments").get(i).equals(expected.get("appointments").get(i)))changed++;return changed;}
    void accepted(Outcome result,Map<String,List<Map<String,Object>>> before,List<Long> ids){var keys=new HashSet<String>();result.data().fieldNames().forEachRemaining(keys::add);assertAll(()->assertEquals(200,result.http(),result.body().toString()),()->assertEquals(0,result.code(),result.body().toString()),()->assertEquals(Set.of("cancelled"),keys),()->assertEquals(expectedCount(before,ids),result.data().path("cancelled").asInt()),()->assertNull(result.sqlState()),()->assertEquals(cancelModel(before,ids),facts(),"Only eligible A.status changes; complete appointment and other business columns stay exact"));}
    List<Long> forward(String shape){return switch(shape){case "normal"->List.of(first,second);case "duplicate"->List.of(first,first,second,second);case "null-missing"->Arrays.asList(null,-1L,first,first,second,Long.MAX_VALUE);default->throw new AssertionError(shape);};}
    List<Long> reverse(String shape){return switch(shape){case "normal"->List.of(second,first);case "duplicate"->List.of(second,second,first,first);case "null-missing"->Arrays.asList(second,null,Long.MAX_VALUE,first,second,-1L);default->throw new AssertionError(shape);};}
    void contention(String duty,String shape,boolean reversed)throws Exception{roles(actor,duty);roles(peer,duty.equals("ADMIN")?"TECHNICIAN":duty.equals("TECHNICIAN")?"NURSE":"ADMIN");assertTrue(first<second);var before=facts();var auth=authFacts();List<Long> bInput=forward(shape),cInput=reversed?reverse(shape):forward(shape);var requests=Executors.newFixedThreadPool(2);try(var low=new HeldRow(first);var high=new HeldRow(second)){low.await();high.await();var b=requests.submit(()->request(bInput,actor.token()));var firstWait=initialWait(b,-1,low.pid.get(),high.pid.get(),"first-forward");assertEquals(true,firstWait.get("low_holder"));int bPid=pid(firstWait.get("pid"));var c=requests.submit(()->request(cInput,peer.token()));var cWait=initialWait(c,bPid,low.pid.get(),high.pid.get(),"second-adaptive");int cPid=pid(cWait.get("pid"));assertNotEquals(bPid,cPid);low.commit();relay(bPid,cPid,high.pid.get(),b,c);high.commit();optionalCycle(bPid,cPid,b,c);var left=finish(b);var right=finish(c);for(var result:List.of(left,right))if("40P01".equals(result.sqlState())){assertNotNull(result.sqlDetail());assertTrue(result.sqlDetail().contains(Integer.toString(bPid))&&result.sqlDetail().contains(Integer.toString(cPid)),"Actual PG deadlock detail must identify the two observed request backends");System.out.println("V134 ACTUAL_DEADLOCK sqlstate=40P01 b="+bPid+" c="+cPid+" http="+result.http()+" code="+result.code()+" detail="+result.sqlDetail());}System.out.println("V134 BUSINESS b="+left.http()+"/"+left.code()+" c="+right.http()+"/"+right.code()+" counts="+count(left)+","+count(right));var expected=cancelModel(before,List.of(first,second));assertAll(()->assertEquals(200,left.http(),left.body().toString()),()->assertEquals(0,left.code(),left.body().toString()),()->assertEquals(200,right.http(),right.body().toString()),()->assertEquals(0,right.code(),right.body().toString()),()->assertNull(left.sqlState()),()->assertNull(right.sqlState()),()->assertEquals(2,count(left)+count(right),"Across both successful original transactions each A cancels exactly once"),()->assertEquals(expected,facts(),"No partial or extra business effect survives"),()->assertEquals(auth,authFacts()));}finally{requests.shutdown();assertTrue(requests.awaitTermination(30,TimeUnit.SECONDS));}}

    @ParameterizedTest @CsvSource({"ADMIN,normal","TECHNICIAN,normal","NURSE,normal","ADMIN,duplicate","TECHNICIAN,duplicate","NURSE,duplicate","ADMIN,null-missing","TECHNICIAN,null-missing","NURSE,null-missing"})
    void reverseOverlappingBatchesHaveTwoOrdinaryResultsInsteadOfPgDeadlock(String duty,String shape)throws Exception{contention(duty,shape,true);}
    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","NURSE"})
    void sameAscendingInputsAlreadySerializeAndUseTheSameAdaptiveRelay(String duty)throws Exception{contention(duty,"normal",false);}
    @ParameterizedTest @CsvSource({"ADMIN,empty","TECHNICIAN,empty","NURSE,empty","ADMIN,nulls","TECHNICIAN,nulls","NURSE,nulls","ADMIN,duplicate","TECHNICIAN,duplicate","NURSE,duplicate","ADMIN,missing","TECHNICIAN,missing","NURSE,missing","ADMIN,mixed","TECHNICIAN,mixed","NURSE,mixed","ADMIN,terminal","TECHNICIAN,terminal","NURSE,terminal","ADMIN,partial","TECHNICIAN,partial","NURSE,partial","ADMIN,reverse","TECHNICIAN,reverse","NURSE,reverse"})
    void originalCountsNullElementsAndHistoryRemainForAllThreeDuties(String duty,String shape)throws Exception{roles(actor,duty);List<Long> ids;switch(shape){case "empty"->ids=List.of();case "nulls"->ids=Arrays.asList(null,null);case "duplicate"->ids=List.of(second,first,second,first);case "missing"->ids=List.of(-1L,Long.MAX_VALUE);case "mixed"->ids=Arrays.asList(second,null,first,second,-1L);case "terminal"->{jdbc.update("update med_appointment set status='CALLED' where id=?",first);jdbc.update("update med_appointment set status='DONE' where id=?",second);ids=Arrays.asList(null,second,first,-1L);}case "partial"->{jdbc.update("update med_appointment set status='CANCELLED' where id=?",second);ids=Arrays.asList(second,null,first,second,first);}case "reverse"->ids=List.of(second,first);default->throw new AssertionError(shape);}var before=facts();var auth=authFacts();accepted(request(ids,actor.token()),before,ids);assertEquals(auth,authFacts());}
    @Test void directImmutableCallerInputRemainsUsable()throws Exception{List<Long> ids=List.of(second,first);var before=facts();holderAuth();try{var answer=appointments.batchCancel(new cn.hip.medtech.web.AppointmentController.BatchCancelReq(ids));assertEquals(0,answer.getCode());assertEquals(Map.of("cancelled",2),answer.getData());}finally{SecurityContextHolder.clearContext();}assertEquals(List.of(second,first),ids);assertEquals(cancelModel(before,ids),facts());}
    @Test void directMutableCallerInputIsNotReorderedAsASideEffect()throws Exception{List<Long> ids=new ArrayList<>(Arrays.asList(second,null,first,second));var original=new ArrayList<>(ids);var before=facts();holderAuth();try{var answer=appointments.batchCancel(new cn.hip.medtech.web.AppointmentController.BatchCancelReq(ids));assertEquals(0,answer.getCode());assertEquals(Map.of("cancelled",2),answer.getData());}finally{SecurityContextHolder.clearContext();}assertEquals(original,ids);assertEquals(cancelModel(before,ids),facts());}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void nullOrMissingIdsKeepTheOriginal5000EnvelopeWithoutBusinessChanges(boolean omit)throws Exception{var before=facts();var auth=authFacts();var result=request(null,actor.token(),omit);assertEquals(500,result.http());assertEquals(5000,result.code());assertTrue(result.data().isNull());assertNull(result.sqlState());assertEquals(before,facts());assertEquals(auth,authFacts());}
    @ParameterizedTest @ValueSource(strings={"CASHIER","DOCTOR_OUTP","anonymous","disabled","PORTAL"})
    void originalEmployeeAndThreeDutyEntryGuardsRemain(String identity)throws Exception{String token=actor.token();int http=403,code=1005;if(identity.equals("anonymous")){token=null;http=401;code=1000;}else if(identity.equals("disabled")){jdbc.update("update sys_user set enabled=false where id=?",actor.id());http=401;code=1000;}else if(identity.equals("PORTAL"))token=jwt.issue("portal:"+patient);else roles(actor,identity);var before=facts();var auth=authFacts();var result=request(List.of(second,first),token);assertEquals(http,result.http());assertEquals(code,result.code());assertTrue(result.data().isNull());assertEquals(before,facts());assertEquals(auth,authFacts());}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(registration==null)return;tx().executeWithoutResult(t->{jdbc.update("delete from med_appointment where order_id in(select id from outp_order where registration_id=?)",registration);jdbc.update("delete from outp_order where registration_id=?",registration);jdbc.update("delete from outp_charge where registration_id=?",registration);jdbc.update("delete from outp_registration where id=?",registration);jdbc.update("delete from outp_schedule where id=?",schedule);jdbc.update("delete from empi_patient where id=?",patient);for(Staff s:staff){jdbc.update("delete from sys_user_role where user_id=?",s.id());jdbc.update("delete from sys_user where id=?",s.id());}jdbc.update("delete from sys_dept where id in (?,?)",dept,staffDept);});staff.clear();}
}
