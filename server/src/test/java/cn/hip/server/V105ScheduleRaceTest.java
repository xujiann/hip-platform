package cn.hip.server;

import cn.hip.outpatient.service.*;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.web.OutpScheduleController;
import cn.hip.platform.core.common.HipBizException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real commits and observed PG waits: a toggle must never restore an old booked value. */
@SpringBootTest @AutoConfigureMockMvc
class V105ScheduleRaceTest {
 @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager; @Autowired RegistrationService registrations;
 @Autowired AppointmentService appointments; @Autowired OutpScheduleController desk; @Autowired OutpScheduleRepository schedules;
 @Autowired MockMvc mvc; @Autowired ObjectMapper om;
 Long p,s,sl,a,r,u,d,doc;String username;
 void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
 void fixture(int action){
  String tag="V105R"+System.nanoTime();username=tag.toLowerCase();
  u=jdbc.queryForObject("insert into sys_user(username,password,real_name) values (?,'x',?) returning id",Long.class,username,username);
  doc=jdbc.queryForObject("insert into sys_user(username,password,real_name) values (?,'x',?) returning id",Long.class,username+"d",tag);
  jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='DOCTOR_OUTP'",doc);
  d=jdbc.queryForObject("insert into sys_dept(name,code,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
  p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag);
  s=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,fee) values (?,current_date,5,0,5) returning id",Long.class,d);
  sl=jdbc.queryForObject("insert into outp_schedule_slot(schedule_id,time_begin,time_end,capacity,booked) values (?,'08:00','08:30',5,0) returning id",Long.class,s);
  if(action==1||action==4)r=registrations.register(p,s).getId();
  if(action==3){var b=appointments.book(sl,p,"测试");assertEquals(0,b.code());a=b.apptId();}
  if(action==4){jdbc.update("update outp_schedule_slot set booked=1 where id=?",sl);a=jdbc.queryForObject("insert into outp_appointment(schedule_id,slot_id,patient_id,appt_no,status,registration_id) values (?,?,?,1,'CHECKED_IN',?) returning id",Long.class,s,sl,p,r);}
  if(action==1||action==3||action==4)jdbc.update("update outp_schedule set enabled=false where id=?",s);
 }
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(d!=null){
  jdbc.update("delete from outp_order where registration_id in(select id from outp_registration where dept_id=?)",d);
  jdbc.update("delete from outp_appointment where schedule_id in(select id from outp_schedule where dept_id=?)",d);
  jdbc.update("delete from outp_registration where dept_id=?",d);jdbc.update("delete from outp_schedule_slot where schedule_id in(select id from outp_schedule where dept_id=?)",d);jdbc.update("delete from outp_schedule where dept_id=?",d);
 }if(p!=null)jdbc.update("delete from empi_patient where id=?",p);if(doc!=null){jdbc.update("delete from sys_user_role where user_id=?",doc);jdbc.update("delete from sys_user where id=?",doc);}if(u!=null)jdbc.update("delete from sys_user where id=?",u);if(d!=null)jdbc.update("delete from sys_dept where id=?",d);}
 boolean waits(AtomicInteger pid,Future<?> f){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(pid.get()>0&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get())))return true;if(f.isDone())return false;try{Thread.sleep(20);}catch(InterruptedException e){throw new RuntimeException(e);}}return false;}
 Future<Integer> worker(ExecutorService pool,AtomicInteger pid,IntSupplier op){return pool.submit(()->{auth();try{return new TransactionTemplate(manager).execute(tx->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));int code=op.getAsInt();if(code!=0)tx.setRollbackOnly();return code;});}catch(HipBizException e){return e.code;}catch(AccessDeniedException e){return 1005;}finally{SecurityContextHolder.clearContext();}});}
 int create(){try{
  var b=Map.of("deptId",d,"doctorId",doc,"scheduleDate",cn.hip.platform.core.config.BusinessDates.today().toString(),"capacity",5);
  var response=mvc.perform(post("/api/outpatient/schedules").with(user(username).roles("ADMIN")).contentType("application/json").content(om.writeValueAsString(b))).andReturn().getResponse();return om.readTree(response.getContentAsByteArray()).path("code").asInt();
 }catch(Exception e){throw new RuntimeException(e);}}
 @ParameterizedTest @ValueSource(ints={0,1,2,3,4})
 void toggleAfterConcurrentOccupancyOrReturnNeverOverwritesBookkeeping(int action)throws Exception {
  fixture(action);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var result=new ArrayList<Future<Integer>>();boolean enabled=action==1||action==3||action==4;
  try {
   new TransactionTemplate(manager).execute(tx->{
    switch(action){case 0->registrations.register(p,s);case 1,4->registrations.cancel(r);case 2->assertEquals(0,appointments.book(sl,p,"测试").code());case 3->assertEquals(0,appointments.cancel(a).code());default->throw new AssertionError();}
    var f=worker(pool,pid,()->desk.setEnabled(s,enabled).getCode());result.add(f);assertTrue(waits(pid,f),"Expected real schedule-row contention");return true;
   });
   assertEquals(0,result.get(0).get(10,TimeUnit.SECONDS));int expected=action==0||action==2?1:0;
   assertEquals(expected,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));assertEquals(action==2?1:0,jdbc.queryForObject("select booked from outp_schedule_slot where id=?",Integer.class,sl));
   var row=jdbc.queryForMap("select enabled,capacity,fee from outp_schedule where id=?",s);assertEquals(enabled,row.get("enabled"));assertEquals(5,row.get("capacity"));assertEquals(0,new java.math.BigDecimal("5").compareTo((java.math.BigDecimal)row.get("fee")));
   if(r!=null)assertEquals("CANCELLED",jdbc.queryForObject("select status from outp_order where registration_id=?",String.class,r));
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void staffDisabledWhileWaitingForScheduleCannotToggle()throws Exception {
  fixture(0);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var results=new ArrayList<Future<Integer>>();
  try {new TransactionTemplate(manager).execute(tx->{jdbc.queryForList("select id from outp_schedule where id=? for update",s);jdbc.update("update sys_user set enabled=false where id=?",u);var f=worker(pool,pid,()->desk.setEnabled(s,false).getCode());results.add(f);assertTrue(waits(pid,f));return true;});assertEquals(1005,results.get(0).get(10,TimeUnit.SECONDS));assertTrue(jdbc.queryForObject("select enabled from outp_schedule where id=?",Boolean.class,s));}
  finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @ParameterizedTest @ValueSource(ints={0,1,2})
 void creationRechecksDepartmentDoctorAndAdministratorAfterWaiting(int action)throws Exception {
  fixture(0);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var results=new ArrayList<Future<Integer>>();
  try {new TransactionTemplate(manager).execute(tx->{
   if(action==0){jdbc.queryForList("select id from sys_dept where id=? for update",d);jdbc.update("update sys_dept set enabled=false where id=?",d);}
   else{jdbc.queryForList("select id from sys_user where id=? for update",doc);jdbc.update("update sys_user set enabled=false where id=?",action==1?doc:u);}
   var f=worker(pool,pid,this::create);results.add(f);assertTrue(waits(pid,f));return true;
  });assertEquals(action==0?3103:action==1?3104:1005,results.get(0).get(10,TimeUnit.SECONDS));assertEquals(1,jdbc.queryForObject("select count(*) from outp_schedule where dept_id=?",Integer.class,d));}
  finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void preloadedJpaScheduleCannotSupplyAStaleBookedValue()throws Exception {
  fixture(0);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var loaded=new CountDownLatch(1);var go=new CountDownLatch(1);
  try {
   var f=worker(pool,pid,()->{schedules.findById(s).orElseThrow();loaded.countDown();try{assertTrue(go.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return desk.setEnabled(s,false).getCode();});assertTrue(loaded.await(10,TimeUnit.SECONDS));
   new TransactionTemplate(manager).execute(tx->{jdbc.update("update outp_schedule set booked=1 where id=?",s);go.countDown();assertTrue(waits(pid,f));return true;});assertEquals(0,f.get(10,TimeUnit.SECONDS));assertEquals(1,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));
  }finally{go.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void failedReenableReallyLeavesDisabledRowUnchanged() {
  fixture(0);jdbc.update("update outp_schedule set enabled=false,doctor_id=? where id=?",doc,s);jdbc.update("update sys_user set enabled=false where id=?",doc);auth();assertEquals(3104,desk.setEnabled(s,true).getCode());assertFalse(jdbc.queryForObject("select enabled from outp_schedule where id=?",Boolean.class,s));assertEquals(0,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));
 }
}
