package cn.hip.server;

import cn.hip.outpatient.service.AppointmentService;
import cn.hip.outpatient.service.RegistrationService;
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

/** No outer transaction: receipts and rollbacks are read after real commits. */
@SpringBootTest
class V106OutpatientNumberTest {
 @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
 @Autowired RegistrationService registrations; @Autowired AppointmentService appointments;
 @Autowired OutpAppointmentController desk;
 Long s,sl,sl2; final List<Long> patients=new ArrayList<>();
 @BeforeEach void setup(){
  s=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,fee) values (1,current_date,20,5) returning id",Long.class);
  sl=slot("08:00","08:30");sl2=slot("09:00","09:30");auth();
 }
 Long slot(String begin,String end){return jdbc.queryForObject("insert into outp_schedule_slot(schedule_id,time_begin,time_end,capacity) values (?,?::time,?::time,10) returning id",Long.class,s,begin,end);}
 Long patient(){String t="V106"+System.nanoTime();Long p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,t,t);patients.add(p);return p;}
 void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();
  jdbc.update("delete from outp_order where registration_id in(select id from outp_registration where schedule_id=?)",s);
  jdbc.update("delete from outp_appointment where schedule_id=?",s);jdbc.update("delete from outp_registration where schedule_id=?",s);
  jdbc.update("delete from outp_schedule_slot where schedule_id=?",s);jdbc.update("delete from outp_schedule where id=?",s);
  for(Long p:patients)jdbc.update("delete from empi_patient where id=?",p);
 }
 void pools(int total,int slots){assertEquals(total,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));assertEquals(slots,jdbc.queryForObject("select sum(booked) from outp_schedule_slot where schedule_id=?",Integer.class,s));}
 @Test void oldSequentialSharedPoolAndCheckinFeeContract(){
  var r=registrations.register(patient(),s);var a=appointments.book(sl,patient(),"测试");assertEquals(1,r.getRegNo());assertEquals(2,a.apptNo());
  var checked=desk.checkin(a.apptId());assertEquals(0,checked.getCode());assertEquals(2,((Number)checked.getData().get("regNo")).intValue());pools(2,1);
  assertEquals(2,jdbc.queryForObject("select count(*) from outp_order where registration_id in(select id from outp_registration where schedule_id=?) and order_type='REG' and amount=5",Integer.class,s));
 }
 @ParameterizedTest @ValueSource(ints={0,1,2})
 void returningCapacityNeverReusesAnyIssuedNumber(int kind){
  var first=registrations.register(patient(),s);var second=appointments.book(sl,patient(),null);
  if(kind==0)registrations.cancel(first.getId());
  if(kind==1)assertTrue(appointments.cancel(second.apptId()).ok());
  if(kind==2){var c=desk.checkin(second.apptId());assertEquals(0,c.getCode());registrations.cancel(((Number)c.getData().get("registrationId")).longValue());}
  var next=registrations.register(patient(),s);assertEquals(3,next.getRegNo(),"returned capacity is not an unused ticket number");
  var last=appointments.book(sl,patient(),null);assertEquals(4,last.apptNo());pools(3,kind==0?2:1);
 }
 @Test void fullAndDisabledRejectionsDoNotIssueTickets(){
  jdbc.update("update outp_schedule set capacity=1 where id=?",s);
  var a=appointments.book(sl,patient(),null);assertEquals(1,a.apptNo());
  assertEquals(3003,assertThrows(HipBizException.class,()->registrations.register(patient(),s)).code);
  assertEquals(3111,assertThrows(HipBizException.class,()->appointments.book(sl2,patient(),null)).code);pools(1,1);
  appointments.cancel(a.apptId());jdbc.update("update outp_schedule set enabled=false where id=?",s);
  assertEquals(3111,assertThrows(HipBizException.class,()->appointments.book(sl,patient(),null)).code);pools(0,0);
  jdbc.update("update outp_schedule set enabled=true where id=?",s);assertEquals(2,registrations.register(patient(),s).getRegNo());
 }
 @Test void rolledBackTicketAndFeeAreNotCommitted(){
  Long p=patient();assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(tx->{registrations.register(p,s);throw new IllegalStateException("rollback");}));
  pools(0,0);assertEquals(0,jdbc.queryForObject("select count(*) from outp_registration where schedule_id=?",Integer.class,s));assertEquals(1,registrations.register(p,s).getRegNo());
 }
 boolean waits(AtomicInteger pid,Future<?> future){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(pid.get()>0&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get())))return true;if(future.isDone())return false;try{Thread.sleep(20);}catch(InterruptedException e){throw new RuntimeException(e);}}return false;}
 Future<Integer> worker(ExecutorService pool,AtomicInteger pid,IntSupplier op){return pool.submit(()->{auth();try{return new TransactionTemplate(manager).execute(tx->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return op.getAsInt();});}catch(HipBizException e){return -e.code;}finally{SecurityContextHolder.clearContext();}});}
 @ParameterizedTest @ValueSource(ints={0,1})
 void mixedIssuanceWaitsForReturnAndNeverDuplicates(int kind)throws Exception{
  var r=registrations.register(patient(),s);var a=appointments.book(sl,patient(),null);Long p=patient();var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();List<Future<Integer>> fs=new ArrayList<>();
  try{new TransactionTemplate(manager).execute(tx->{if(kind==0)registrations.cancel(r.getId());else appointments.cancel(a.apptId());
   var f=worker(pool,pid,()->kind==0?appointments.book(sl2,p,null).apptNo():registrations.register(p,s).getRegNo());fs.add(f);assertTrue(waits(pid,f));return true;});
   assertEquals(3,fs.getFirst().get(10,TimeUnit.SECONDS));pools(2,kind==0?2:0);
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @ParameterizedTest @ValueSource(ints={0,1})
 void simultaneousDuplicateLoserRollsBackCapacityAndNumber(int kind)throws Exception{
  Long p=patient();Long other=patient();var pool=Executors.newFixedThreadPool(2);var fs=new ArrayList<Future<Integer>>();
  try{new TransactionTemplate(manager).execute(tx->{jdbc.queryForObject("select id from outp_schedule where id=? for update",Long.class,s);
   for(int i=0;i<2;i++){Long target=i==0?sl:sl2;var pid=new AtomicInteger();var f=worker(pool,pid,()->{if(kind==0)return registrations.register(p,s).getRegNo();var result=appointments.book(target,p,null);return result.ok()?result.apptNo():-result.code();});fs.add(f);assertTrue(waits(pid,f),"both must really wait before the holder releases its lock");}return true;});
   var values=List.of(fs.get(0).get(10,TimeUnit.SECONDS),fs.get(1).get(10,TimeUnit.SECONDS));assertEquals(1,values.stream().filter(x->x==1).count());assertEquals(1,values.stream().filter(x->x==-(kind==0?3002:3112)).count());pools(1,kind);
   assertEquals(2,registrations.register(other,s).getRegNo());pools(2,kind);
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void exhaustedIntegerCursorRejectsWithoutPartialOccupancy(){
  jdbc.update("update outp_schedule set last_issued_no=2147483646 where id=?",s);
  assertEquals(Integer.MAX_VALUE,registrations.register(patient(),s).getRegNo());
  assertEquals(3003,assertThrows(HipBizException.class,()->registrations.register(patient(),s)).code);
  assertEquals(3111,assertThrows(HipBizException.class,()->appointments.book(sl,patient(),null)).code);pools(1,0);
  assertEquals(Integer.MAX_VALUE,jdbc.queryForObject("select last_issued_no from outp_schedule where id=?",Integer.class,s));
 }
 @Test void lastCapacityMixedCompetitionHasOneWinnerAndOneBusinessRejection()throws Exception{
  jdbc.update("update outp_schedule set capacity=1 where id=?",s);Long p=patient(),q=patient();var pool=Executors.newFixedThreadPool(2);var fs=new ArrayList<Future<Integer>>();
  try{new TransactionTemplate(manager).execute(tx->{jdbc.queryForObject("select id from outp_schedule where id=? for update",Long.class,s);
   for(int i=0;i<2;i++){boolean walk=i==0;var pid=new AtomicInteger();var f=worker(pool,pid,()->walk?registrations.register(p,s).getRegNo():appointments.book(sl,q,null).apptNo());fs.add(f);assertTrue(waits(pid,f));}return true;});
   var values=List.of(fs.get(0).get(10,TimeUnit.SECONDS),fs.get(1).get(10,TimeUnit.SECONDS));assertEquals(1,values.stream().filter(x->x==1).count());assertEquals(1,values.stream().filter(x->x==-3003||x==-3111).count());
   pools(1,values.get(1)==1?1:0);assertEquals(1,jdbc.queryForObject("select last_issued_no from outp_schedule where id=?",Integer.class,s));
  }finally{pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
 }
 @Test void actualMigrationSeedsAllHistoricalStatusesWithoutRewritingReceipts(){
  new TransactionTemplate(manager).execute(tx->{
   String schema="v106_migration_"+System.nanoTime();jdbc.execute("create schema "+schema);jdbc.execute("set local search_path to "+schema);
   jdbc.execute("create table outp_schedule(id bigint primary key,booked integer)");
   jdbc.execute("create table outp_registration(schedule_id bigint,reg_no integer,status text)");
   jdbc.execute("create table outp_appointment(schedule_id bigint,appt_no integer,status text)");
   jdbc.execute("insert into outp_schedule values (1,0),(2,15),(3,0),(4,0)");
   jdbc.execute("insert into outp_registration values (1,9,'CANCELLED'),(1,3,'REGISTERED'),(1,3,'VISITED'),(4,2147483647,'CANCELLED')");
   jdbc.execute("insert into outp_appointment values (1,12,'CANCELLED'),(1,11,'CHECKED_IN'),(2,10,'BOOKED')");
   jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ClassPathResource("db/migration/V184__outp_schedule_issued_number.sql"));return null;});
   assertEquals(List.of(12,15,0,Integer.MAX_VALUE),jdbc.queryForList("select last_issued_no from outp_schedule order by id",Integer.class));
   assertEquals(List.of(9,3,3,Integer.MAX_VALUE),jdbc.queryForList("select reg_no from outp_registration order by schedule_id,reg_no desc",Integer.class));
   assertEquals(15,jdbc.queryForObject("select booked from outp_schedule where id=2",Integer.class));
   tx.setRollbackOnly();return true;
  });
 }
}
