package cn.hip.server;

import cn.hip.outpatient.service.*;
import cn.hip.outpatient.web.OutpAppointmentController;
import cn.hip.platform.core.common.HipBizException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real commits, observed waits and exact winner/loser counts across both issuance paths. */
@SpringBootTest
class V107RegistrationEligibilityTest {
 @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
 @Autowired RegistrationService regs; @Autowired AppointmentService appts; @Autowired OutpAppointmentController desk;
 Long s,sl,sl2,p; final List<Long> patients=new ArrayList<>();
 @BeforeEach void setup(){
  s=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,fee) values (1,current_date,20,5) returning id",Long.class);
  sl=slot("08:00","08:30");sl2=slot("09:00","09:30");p=patient();auth();
 }
 Long slot(String a,String b){return jdbc.queryForObject("insert into outp_schedule_slot(schedule_id,time_begin,time_end,capacity) values (?,?::time,?::time,10) returning id",Long.class,s,a,b);}
 Long patient(){String t="V107"+System.nanoTime();Long v=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,t,t);patients.add(v);return v;}
 void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();jdbc.update("delete from outp_order where registration_id in(select id from outp_registration where schedule_id=?)",s);jdbc.update("delete from outp_appointment where schedule_id=?",s);jdbc.update("delete from outp_registration where schedule_id=?",s);jdbc.update("delete from outp_schedule_slot where schedule_id=?",s);jdbc.update("delete from outp_schedule where id=?",s);for(Long v:patients)jdbc.update("delete from empi_patient where id=?",v);}
 int reg(Long who){regs.register(who,s);return 0;}
 int book(Long who,Long slot){return appts.book(slot,who,null).code();}
 void pools(int total,int slot,int cursor){assertEquals(total,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));assertEquals(slot,jdbc.queryForObject("select sum(booked) from outp_schedule_slot where schedule_id=?",Integer.class,s));assertEquals(cursor,jdbc.queryForObject("select last_issued_no from outp_schedule where id=?",Integer.class,s));}
 @Test void bookedAppointmentRejectsWalkInWithoutIssuingNumberOrFee(){
  assertEquals(0,book(p,sl));assertEquals(3002,assertThrows(HipBizException.class,()->reg(p)).code);pools(1,1,1);
  assertEquals(0,jdbc.queryForObject("select count(*) from outp_registration where schedule_id=?",Integer.class,s));
 }
 @ParameterizedTest @ValueSource(strings={"REGISTERED","CALLED","PASSED"})
 void pendingRegistrationRejectsBothNewEntryPaths(String status){
  var r=regs.register(p,s);jdbc.update("update outp_registration set status=? where id=?",status,r.getId());
  assertEquals(3112,book(p,sl));assertEquals(3002,assertThrows(HipBizException.class,()->reg(p)).code);pools(1,0,1);
  assertEquals(1,jdbc.queryForObject("select count(*) from outp_order where registration_id=?",Integer.class,r.getId()));
 }
 @ParameterizedTest @ValueSource(strings={"VISITED","CANCELLED"})
 void preserveExistingRevisitAndCancelledRegistrationEligibility(String status){
  var r=regs.register(p,s);if(status.equals("CANCELLED"))regs.cancel(r.getId());else jdbc.update("update outp_registration set status='VISITED' where id=?",r.getId());
  var a=appts.book(sl,p,null);assertTrue(a.ok());assertEquals(2,a.apptNo());appts.cancel(a.apptId());
  assertEquals(3,regs.register(p,s).getRegNo());pools(status.equals("VISITED")?2:1,0,3);
 }
 @Test void cancelledAppointmentCanBecomeWalkInAndThenAppointmentAgain(){
  var a=appts.book(sl,p,null);assertTrue(appts.cancel(a.apptId()).ok());var r=regs.register(p,s);assertEquals(2,r.getRegNo());regs.cancel(r.getId());assertEquals(3,appts.book(sl,p,null).apptNo());pools(1,1,3);
 }
 @Test void checkinRetainsItsOwnEligibilityButAuditHistoryDoesNotBlockAfterReturn(){
  var a=appts.book(sl,p,null);var checked=desk.checkin(a.apptId());assertEquals(0,checked.getCode());assertEquals(1,((Number)checked.getData().get("regNo")).intValue());
  assertEquals(3112,book(p,sl2));assertEquals(3002,assertThrows(HipBizException.class,()->reg(p)).code);pools(1,1,1);
  regs.cancel(((Number)checked.getData().get("registrationId")).longValue());assertEquals(2,regs.register(p,s).getRegNo());pools(1,0,2);
  assertEquals("CHECKED_IN",jdbc.queryForObject("select status from outp_appointment where id=?",String.class,a.apptId()));
 }
 @Test void legacyCalledRegistrationConflictRollsBackCheckinCompletely(){
  var a=appts.book(sl,p,null);jdbc.update("insert into outp_registration(schedule_id,patient_id,dept_id,visit_date,reg_no,fee,status) values (?,?,1,current_date,99,5,'CALLED')",s,p);
  assertEquals(3002,assertThrows(HipBizException.class,()->desk.checkin(a.apptId())).code);
  var row=jdbc.queryForMap("select status,registration_id from outp_appointment where id=?",a.apptId());assertEquals("BOOKED",row.get("status"));assertNull(row.get("registration_id"));pools(1,1,1);
  assertEquals(0,jdbc.queryForObject("select count(*) from outp_order where registration_id in(select id from outp_registration where schedule_id=?)",Integer.class,s));
 }
 boolean waits(AtomicInteger pid,Future<?> f){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<end){if(pid.get()>0&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get())))return true;if(f.isDone())return false;try{Thread.sleep(20);}catch(InterruptedException e){throw new RuntimeException(e);}}return false;}
 Future<Integer> worker(ExecutorService pool,AtomicInteger pid,IntSupplier op){return pool.submit(()->{auth();try{return new TransactionTemplate(manager).execute(tx->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return op.getAsInt();});}catch(HipBizException e){return e.code;}catch(org.springframework.security.access.AccessDeniedException e){return 1005;}finally{SecurityContextHolder.clearContext();}});}
 @ParameterizedTest @ValueSource(ints={0,1,2,3})
 void mixedEntriesSerializeWithCommitAndRollback(int kind)throws Exception{
  boolean firstBook=kind%2==0,rollback=kind>=2;var pool=Executors.newSingleThreadExecutor();var fs=new ArrayList<Future<Integer>>();var pid=new AtomicInteger();
  try{new TransactionTemplate(manager).execute(tx->{assertEquals(0,firstBook?book(p,sl):reg(p));var f=worker(pool,pid,()->firstBook?reg(p):book(p,sl));fs.add(f);assertTrue(waits(pid,f));if(rollback)tx.setRollbackOnly();return true;});
   int result=fs.getFirst().get(10,TimeUnit.SECONDS);assertEquals(rollback?0:firstBook?3002:3112,result);
   int slot=(rollback?!firstBook:firstBook)?1:0;pools(1,slot,1);
   assertEquals(slot,jdbc.queryForObject("select count(*) from outp_appointment where schedule_id=?",Integer.class,s));assertEquals(1-slot,jdbc.queryForObject("select count(*) from outp_registration where schedule_id=?",Integer.class,s));
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @ParameterizedTest @ValueSource(ints={0,1})
 void checkinSerializesAgainstNewBookingAndWalkIn(int kind)throws Exception{
  var a=appts.book(sl,p,null);var pool=Executors.newSingleThreadExecutor();var fs=new ArrayList<Future<Integer>>();var pid=new AtomicInteger();
  try{new TransactionTemplate(manager).execute(tx->{assertEquals(0,desk.checkin(a.apptId()).getCode());var f=worker(pool,pid,()->kind==0?book(p,sl2):reg(p));fs.add(f);assertTrue(waits(pid,f));return true;});assertEquals(kind==0?3112:3002,fs.getFirst().get(10,TimeUnit.SECONDS));pools(1,1,1);
   assertEquals(1,jdbc.queryForObject("select count(*) from outp_registration where schedule_id=?",Integer.class,s));
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void differentPatientsRemainEligibleDuringAnUncommittedBooking()throws Exception{
  Long other=patient();var pool=Executors.newSingleThreadExecutor();var fs=new ArrayList<Future<Integer>>();var pid=new AtomicInteger();
  try{new TransactionTemplate(manager).execute(tx->{assertEquals(0,book(p,sl));var f=worker(pool,pid,()->reg(other));fs.add(f);assertTrue(waits(pid,f));return true;});assertEquals(0,fs.getFirst().get(10,TimeUnit.SECONDS));pools(2,1,2);
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void samePatientDifferentScheduleDoesNotWaitForEligibilityKey()throws Exception{
  Long other=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity) values (1,current_date,10) returning id",Long.class);
  var pool=Executors.newSingleThreadExecutor();
  try{new TransactionTemplate(manager).execute(tx->{assertEquals(0,book(p,sl));var f=pool.submit(()->regs.register(p,other).getRegNo());try{assertEquals(1,f.get(3,TimeUnit.SECONDS));}catch(Exception e){fail("another schedule must not wait for this eligibility key",e);}return true;});pools(1,1,1);
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));jdbc.update("delete from outp_order where registration_id in(select id from outp_registration where schedule_id=?)",other);jdbc.update("delete from outp_registration where schedule_id=?",other);jdbc.update("delete from outp_schedule where id=?",other);}
 }
 @ParameterizedTest @ValueSource(ints={0,1})
 void cancellationDoesNotDeadlockNewIssuanceAndRetrySeesCommittedReturn(int kind)throws Exception{
  Long id=kind==0?appts.book(sl,p,null).apptId():regs.register(p,s).getId();var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();
  try{new TransactionTemplate(manager).execute(tx->{if(kind==0)appts.cancel(id);else regs.cancel(id);
   var f=worker(pool,pid,()->kind==0?reg(p):book(p,sl));try{assertEquals(kind==0?3002:3112,f.get(3,TimeUnit.SECONDS));}catch(Exception e){fail("read old pending state and reject without waiting on cancellation rows",e);}return true;});
   assertEquals(0,kind==0?reg(p):book(p,sl));pools(1,kind==0?0:1,2);
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void deskRechecksEnabledOperatorAfterEligibilityWait()throws Exception{
  String username="v107cash"+System.nanoTime();Long u=jdbc.queryForObject("insert into sys_user(username,password,real_name) values (?,'x',?) returning id",Long.class,username,username);
  var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
  try{new TransactionTemplate(manager).execute(tx->{reg(p);var f=worker(pool,pid,()->{SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username,null,List.of(new SimpleGrantedAuthority("ROLE_CASHIER"))));return appts.bookFromDesk(sl,p,null).code();});fs.add(f);assertTrue(waits(pid,f));jdbc.update("update sys_user set enabled=false where id=?",u);return true;});assertEquals(1005,fs.getFirst().get(10,TimeUnit.SECONDS));pools(1,0,1);
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));jdbc.update("delete from sys_user where id=?",u);}
 }
}
