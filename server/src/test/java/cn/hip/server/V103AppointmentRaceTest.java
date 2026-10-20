package cn.hip.server;

import cn.hip.outpatient.web.OutpAppointmentController;
import cn.hip.platform.core.common.HipBizException;
import org.junit.jupiter.api.*;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** No outer test transaction: competing operations commit or roll back for real. */
@SpringBootTest
class V103AppointmentRaceTest {
 @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager; @Autowired OutpAppointmentController desk;
 Long p,s,sl,a,u;String username;
 void auth(String name,String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
 void fixture(boolean booked){
  String tag="V103R"+System.nanoTime();username=tag.toLowerCase();
  u=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,username,username);
  p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag);
  s=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,fee) values (1,current_date,5,?,5) returning id",Long.class,booked?1:0);
  sl=jdbc.queryForObject("insert into outp_schedule_slot(schedule_id,time_begin,time_end,capacity,booked) values (?,'08:00','08:30',5,?) returning id",Long.class,s,booked?1:0);
  if(booked)a=jdbc.queryForObject("insert into outp_appointment(schedule_id,slot_id,patient_id,appt_no) values (?,?,?,1) returning id",Long.class,s,sl,p);
 }
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(s!=null){jdbc.update("delete from outp_order where registration_id in(select id from outp_registration where schedule_id=?)",s);jdbc.update("update outp_appointment set registration_id=null where schedule_id=?",s);jdbc.update("delete from outp_registration where schedule_id=?",s);jdbc.update("delete from outp_appointment where schedule_id=?",s);jdbc.update("delete from outp_schedule_slot where schedule_id=?",s);jdbc.update("delete from outp_schedule where id=?",s);}if(p!=null)jdbc.update("delete from empi_patient where id=?",p);if(u!=null)jdbc.update("delete from sys_user where id=?",u);}
 boolean waitLock(AtomicInteger pid,Future<?> future)throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
  while(System.nanoTime()<end){if(pid.get()>0&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get())))return true;if(future.isDone())return false;Thread.sleep(20);}return false;
 }
 @ParameterizedTest @ValueSource(ints={0,1,2,3})
 void blockedWritesRecheckPatientAndEnabledStaff(int action)throws Exception {
  fixture(action==1||action==2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pid=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
  try {
   var first=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
    if(action==0){jdbc.queryForList("select id from empi_patient where id=? for update",p);jdbc.update("update empi_patient set active=false where id=?",p);}
    else if(action==3){jdbc.queryForList("select id from outp_schedule_slot where id=? for update",sl);jdbc.update("update sys_user set enabled=false where id=?",u);}
    else {jdbc.queryForList("select id from outp_appointment where id=? for update",a);if(action==1)jdbc.update("update sys_user set enabled=false where id=?",u);else jdbc.update("update empi_patient set active=false where id=?",p);}
    held.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new RuntimeException(ex);}return true;
   }));
   assertTrue(held.await(10,TimeUnit.SECONDS));
   var second=pool.submit(()->{auth(username,"CASHIER");try{return new TransactionTemplate(manager).execute(tx->{
    pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
    if(action==0||action==3)return desk.book(new OutpAppointmentController.ApptReq(sl,p,"窗口")).getCode();
    return action==1?desk.cancel(a).getCode():desk.checkin(a).getCode();
   });}catch(HipBizException ex){return ex.code;}catch(AccessDeniedException ex){return 1005;}finally{SecurityContextHolder.clearContext();}});
   assertTrue(waitLock(pid,second),"Expected actual PG lock wait");release.countDown();assertTrue(first.get(10,TimeUnit.SECONDS));assertEquals(action==0||action==2?3115:1005,second.get(10,TimeUnit.SECONDS));
   assertEquals(action==1||action==2?1:0,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));
   assertEquals(action==1||action==2?1:0,jdbc.queryForObject("select booked from outp_schedule_slot where id=?",Integer.class,sl));
   assertEquals(0,jdbc.queryForObject("select count(*) from outp_registration where schedule_id=?",Integer.class,s));
   if(a!=null)assertEquals("BOOKED",jdbc.queryForObject("select status from outp_appointment where id=?",String.class,a));
  }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void checkinAndCancelReallyCompeteAndExactlyOneWins()throws Exception {
  fixture(true);var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(3);var p1=new AtomicInteger();var p2=new AtomicInteger();
  try {
   var blocker=pool.submit(()->new TransactionTemplate(manager).execute(tx->{jdbc.queryForList("select id from outp_appointment where id=? for update",a);held.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new RuntimeException(ex);}return true;}));assertTrue(held.await(10,TimeUnit.SECONDS));
   var check=pool.submit(()->{auth(username,"CASHIER");try{return new TransactionTemplate(manager).execute(tx->{p1.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return desk.checkin(a).getCode();});}finally{SecurityContextHolder.clearContext();}});
   var cancel=pool.submit(()->{auth(username,"CASHIER");try{return new TransactionTemplate(manager).execute(tx->{p2.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return desk.cancel(a).getCode();});}finally{SecurityContextHolder.clearContext();}});
   assertTrue(waitLock(p1,check));assertTrue(waitLock(p2,cancel));release.countDown();assertTrue(blocker.get(10,TimeUnit.SECONDS));
   var results=List.of(check.get(10,TimeUnit.SECONDS),cancel.get(10,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(x->x==0).count());assertEquals(1,results.stream().filter(x->x==3114).count());
   boolean checked="CHECKED_IN".equals(jdbc.queryForObject("select status from outp_appointment where id=?",String.class,a));
   assertEquals(checked?1:0,jdbc.queryForObject("select count(*) from outp_registration where schedule_id=?",Integer.class,s));
   assertEquals(checked?1:0,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));assertEquals(checked?1:0,jdbc.queryForObject("select booked from outp_schedule_slot where id=?",Integer.class,sl));
  }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void slotDefinitionsSerializeTheScheduleCapacityBudget()throws Exception {
  fixture(false);jdbc.update("delete from outp_schedule_slot where id=?",sl);sl=null;
  var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pid=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
  try {
   var first=pool.submit(()->{auth("admin","ADMIN");try{return new TransactionTemplate(manager).execute(tx->{assertEquals(0,desk.createSlots(s,new OutpAppointmentController.SlotsReq(List.of(new OutpAppointmentController.SlotLine("08:00","08:30",3)))).getCode());held.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new RuntimeException(ex);}return true;});}finally{SecurityContextHolder.clearContext();}});
   assertTrue(held.await(10,TimeUnit.SECONDS));var second=pool.submit(()->{auth("admin","ADMIN");try{return new TransactionTemplate(manager).execute(tx->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return desk.createSlots(s,new OutpAppointmentController.SlotsReq(List.of(new OutpAppointmentController.SlotLine("09:00","09:30",3)))).getCode();});}finally{SecurityContextHolder.clearContext();}});
   assertTrue(waitLock(pid,second));release.countDown();assertTrue(first.get(10,TimeUnit.SECONDS));assertEquals(3120,second.get(10,TimeUnit.SECONDS));assertEquals(3,jdbc.queryForObject("select sum(capacity) from outp_schedule_slot where schedule_id=?",Integer.class,s));
  }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void failedRegistrationReallyRollsBackAppointmentCheckin() {
  fixture(true);jdbc.update("insert into outp_registration(patient_id,schedule_id,dept_id,visit_date,reg_no,fee) values (?,?,1,current_date,99,5)",p,s);
  auth(username,"CASHIER");var ex=assertThrows(HipBizException.class,()->desk.checkin(a));assertEquals(3002,ex.code);
  var row=jdbc.queryForMap("select status,registration_id from outp_appointment where id=?",a);assertEquals("BOOKED",row.get("status"));assertNull(row.get("registration_id"));
  assertEquals(1,jdbc.queryForObject("select count(*) from outp_registration where schedule_id=?",Integer.class,s));assertEquals(0,jdbc.queryForObject("select count(*) from outp_order where registration_id in(select id from outp_registration where schedule_id=?)",Integer.class,s));
  assertEquals(1,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));
 }
}
