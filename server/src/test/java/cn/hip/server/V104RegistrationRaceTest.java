package cn.hip.server;

import cn.hip.outpatient.service.*;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
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
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real committed transactions; each race asserts an observed PostgreSQL lock wait. */
@SpringBootTest
class V104RegistrationRaceTest {
 @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager; @Autowired RegistrationService service;
 @Autowired ChargeService charges; @Autowired DoctorStationService doctor; @Autowired OutpRegistrationRepository registrations;
 @Autowired jakarta.persistence.EntityManager em;
 Long p,s,sl,a,r,u;String username;
 void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username,null,List.of(new SimpleGrantedAuthority("ROLE_CASHIER"))));}
 void fixture(boolean registered){
  String tag="V104R"+System.nanoTime();username=tag.toLowerCase();
  u=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,username,username);
  p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag);
  s=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,fee) values (1,current_date,5,0,5) returning id",Long.class);
  sl=jdbc.queryForObject("insert into outp_schedule_slot(schedule_id,time_begin,time_end,capacity,booked) values (?,'08:00','08:30',5,0) returning id",Long.class,s);
  if(registered){
   r=service.register(p,s).getId();jdbc.update("update outp_schedule_slot set booked=1 where id=?",sl);
   a=jdbc.queryForObject("insert into outp_appointment(schedule_id,slot_id,patient_id,appt_no,status,registration_id) values (?,?,?,1,'CHECKED_IN',?) returning id",Long.class,s,sl,p,r);
  }
 }
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(s!=null){
  jdbc.update("delete from outp_order where registration_id in(select id from outp_registration where schedule_id=?)",s);
  jdbc.update("delete from outp_charge where registration_id in(select id from outp_registration where schedule_id=?)",s);
  jdbc.update("delete from outp_appointment where schedule_id=?",s);jdbc.update("delete from outp_registration where schedule_id=?",s);
  jdbc.update("delete from outp_schedule_slot where schedule_id=?",s);jdbc.update("delete from outp_schedule where id=?",s);
 }if(p!=null)jdbc.update("delete from empi_patient where id=?",p);if(u!=null)jdbc.update("delete from sys_user where id=?",u);}
 void counts(int n){assertEquals(n,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));assertEquals(n,jdbc.queryForObject("select booked from outp_schedule_slot where id=?",Integer.class,sl));}
 boolean waits(AtomicInteger pid,Future<?> f){
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
  while(System.nanoTime()<end){if(pid.get()>0&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get())))return true;if(f.isDone())return false;try{Thread.sleep(20);}catch(InterruptedException e){throw new RuntimeException(e);}}return false;
 }
 Future<Integer> worker(ExecutorService pool,AtomicInteger pid,Supplier<?> operation){return pool.submit(()->{auth();try{
  return new TransactionTemplate(manager).execute(tx->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));operation.get();return 0;});
 }catch(HipBizException e){return e.code;}catch(AccessDeniedException e){return 1005;}finally{SecurityContextHolder.clearContext();}});}
 @ParameterizedTest @ValueSource(ints={0,1,2,3})
 void blockedDeskWritesRecheckTheirPatientAndStaff(int action)throws Exception {
  fixture(action>=2);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var result=new ArrayList<Future<Integer>>();
  try {
   new TransactionTemplate(manager).execute(tx->{
    if(action==0){jdbc.queryForList("select id from empi_patient where id=? for update",p);jdbc.update("update empi_patient set active=false where id=?",p);}
    else if(action==1){jdbc.queryForList("select id from outp_schedule where id=? for update",s);jdbc.update("update sys_user set enabled=false where id=?",u);}
    else if(action==2){jdbc.queryForList("select id from outp_registration where id=? for update",r);jdbc.update("update sys_user set enabled=false where id=?",u);}
    else {jdbc.queryForList("select id from outp_schedule_slot where id=? for update",sl);jdbc.update("update sys_user set enabled=false where id=?",u);}
    var f=worker(pool,pid,()->action<2?service.registerFromDesk(p,s):service.cancelFromDesk(r));result.add(f);assertTrue(waits(pid,f),"Expected real lock wait");return true;
   });
   assertEquals(action==0?3007:1005,result.get(0).get(10,TimeUnit.SECONDS));counts(action>=2?1:0);
   assertEquals(action>=2?1:0,jdbc.queryForObject("select count(*) from outp_registration where schedule_id=?",Integer.class,s));
   if(r!=null){assertEquals("REGISTERED",jdbc.queryForObject("select status from outp_registration where id=?",String.class,r));assertEquals("CREATED",jdbc.queryForObject("select status from outp_order where registration_id=?",String.class,r));}
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void simultaneousCancellationReturnsBothPoolsOnlyOnce()throws Exception {
  fixture(true);var pool=Executors.newFixedThreadPool(2);var results=new ArrayList<Future<Integer>>();
  try {
   new TransactionTemplate(manager).execute(tx->{jdbc.queryForList("select id from outp_registration where id=? for update",r);
    for(int i=0;i<2;i++){var pid=new AtomicInteger();var f=worker(pool,pid,()->service.cancelFromDesk(r));results.add(f);assertTrue(waits(pid,f));}return true;});
   var codes=List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS));assertEquals(1,codes.stream().filter(c->c==0).count());assertEquals(1,codes.stream().filter(c->c==3005).count());counts(0);
   assertEquals("CANCELLED",jdbc.queryForObject("select status from outp_order where registration_id=?",String.class,r));assertEquals("CHECKED_IN",jdbc.queryForObject("select status from outp_appointment where id=?",String.class,a));
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @ParameterizedTest @ValueSource(ints={0,1})
 void chargingAndCancellationHaveAnAtomicWinner(int chargeFirst)throws Exception {
  fixture(true);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var results=new ArrayList<Future<Integer>>();
  try {
   new TransactionTemplate(manager).execute(tx->{
    if(chargeFirst==1)charges.settle(r,"CASH",u);else service.cancel(r);
    var f=worker(pool,pid,()->chargeFirst==1?service.cancelFromDesk(r):charges.settle(r,"CASH",u));results.add(f);assertTrue(waits(pid,f));return true;
   });
   assertEquals(chargeFirst==1?3006:5008,results.get(0).get(10,TimeUnit.SECONDS));counts(chargeFirst);
   assertEquals(chargeFirst==1?"REGISTERED":"CANCELLED",jdbc.queryForObject("select status from outp_registration where id=?",String.class,r));
   assertEquals(chargeFirst==1?"CHARGED":"CANCELLED",jdbc.queryForObject("select status from outp_order where registration_id=?",String.class,r));
   assertEquals(chargeFirst,jdbc.queryForObject("select count(*) from outp_charge where registration_id=?",Integer.class,r));
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @ParameterizedTest @ValueSource(ints={0,1})
 void visitAndCancellationSerializeOnRegistration(int visitFirst)throws Exception {
  fixture(true);var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var results=new ArrayList<Future<Integer>>();
  try {
   new TransactionTemplate(manager).execute(tx->{
    if(visitFirst==1){doctor.startVisit(r,u);em.flush();}else service.cancel(r);
    var f=worker(pool,pid,()->visitFirst==1?service.cancelFromDesk(r):doctor.startVisit(r,u));results.add(f);assertTrue(waits(pid,f));return true;
   });
   assertEquals(visitFirst==1?3005:4002,results.get(0).get(10,TimeUnit.SECONDS));counts(visitFirst);
   assertEquals(visitFirst==1?"VISITED":"CANCELLED",jdbc.queryForObject("select status from outp_registration where id=?",String.class,r));
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void invalidPoolOrDuplicateLinkLeavesAllRecordsUnchanged() {
  fixture(true);jdbc.update("update outp_schedule_slot set booked=0 where id=?",sl);auth();
  assertEquals(3008,assertThrows(HipBizException.class,()->service.cancelFromDesk(r)).code);
  assertEquals("REGISTERED",jdbc.queryForObject("select status from outp_registration where id=?",String.class,r));assertEquals("CREATED",jdbc.queryForObject("select status from outp_order where registration_id=?",String.class,r));
  jdbc.update("update outp_schedule_slot set booked=1 where id=?",sl);
  jdbc.update("insert into outp_appointment(schedule_id,slot_id,patient_id,appt_no,status,registration_id) values (?,?,?,2,'CHECKED_IN',?)",s,sl,p,r);
  assertEquals(3008,assertThrows(HipBizException.class,()->service.cancelFromDesk(r)).code);counts(1);assertEquals("REGISTERED",jdbc.queryForObject("select status from outp_registration where id=?",String.class,r));
 }
 @Test void preloadedJpaEntityIsRefreshedAfterTheLockWait()throws Exception {
  fixture(true);var pool=Executors.newSingleThreadExecutor();var loaded=new CountDownLatch(1);var go=new CountDownLatch(1);var pid=new AtomicInteger();Future<Integer> f=null;
  try {
   f=worker(pool,pid,()->{registrations.findById(r).orElseThrow();loaded.countDown();try{assertTrue(go.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return service.cancelFromDesk(r);});assertTrue(loaded.await(10,TimeUnit.SECONDS));var target=f;
   new TransactionTemplate(manager).execute(tx->{jdbc.update("update outp_registration set status='VISITED' where id=?",r);go.countDown();assertTrue(waits(pid,target));return true;});
   assertEquals(3005,f.get(10,TimeUnit.SECONDS));counts(1);assertEquals("VISITED",jdbc.queryForObject("select status from outp_registration where id=?",String.class,r));
  }finally{go.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
}
