package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpatientController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real commits and pg_stat_activity prove the writer waits on the ownership transaction. */
@SpringBootTest
class V90ConsentOwnershipRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @Autowired InpatientService service; @Autowired InpatientController controller;
    @Autowired cn.hip.inpatient.web.ConsentController consents;
    Long consent;
    Long patient,admission,aid,bid;String a,b;
    @AfterEach void cleanup() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            if(admission!=null) {jdbc.update("delete from emr_consent where admission_id=?",admission);jdbc.update("delete from inp_order where admission_id=?",admission);jdbc.update("delete from inp_admission where id=?",admission);}
            if(patient!=null)jdbc.update("delete from empi_patient where id=?",patient);
            if(aid!=null) {jdbc.update("delete from sys_user_role where user_id=?",aid);jdbc.update("delete from sys_user where id=?",aid);}
            if(bid!=null) {jdbc.update("delete from sys_user_role where user_id=?",bid);jdbc.update("delete from sys_user where id=?",bid);}
        });
    }
    private void auth(String name,String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    @Test void formerOwnerWaitsForHandoffAndIsDeniedAfterItCommits() throws Exception {
        String suffix=String.valueOf(System.nanoTime());a="v90ra"+suffix;b="v90rb"+suffix;
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            aid=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,a,a);
            bid=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,b,b);
            jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.id in (?,?) and r.code='DOCTOR_OUTP'",aid,bid);
            patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'V90归属竞争','U') returning id",Long.class,"V90R"+suffix);
            var bed=jdbc.queryForMap("select id,ward_id from inp_bed order by id limit 1");
            admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status,admit_at) values (?,?,1,?,?,?,'IN_HOSPITAL',now()) returning id",Long.class,"V90R"+suffix,patient,bed.get("ward_id"),bed.get("id"),aid);
        });
        consent=jdbc.queryForObject("insert into emr_consent(admission_id,consent_type,title,content,status) values (?,'SURGERY','V90','V90竞争签署','PATIENT_SIGNED') returning id",Long.class,admission);
        CountDownLatch handedOff=new CountDownLatch(1),release=new CountDownLatch(1),writerStarted=new CountDownLatch(1);
        AtomicInteger writerPid=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var handoff=pool.submit(() -> {
                auth("admin","ADMIN");
                try {return new TransactionTemplate(manager).execute(tx -> {
                    assertEquals(0,controller.setAttendingDoctor(admission,new InpatientController.AttendingDoctorReq(bid)).getCode());
                    handedOff.countDown();try {assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;
                });} finally {SecurityContextHolder.clearContext();}
            });
            assertTrue(handedOff.await(10,TimeUnit.SECONDS));
            var writer=pool.submit(() -> {
                auth(a,"DOCTOR_OUTP");
                try {return new TransactionTemplate(manager).execute(tx -> {
                    writerPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));writerStarted.countDown();
                    int code=consents.doctorSign(consent,SecurityContextHolder.getContext().getAuthentication()).getCode();
                    // This outer transaction only exposes the backend PID for the lock assertion.
                    // The controller catches the service rejection; roll back our wrapper explicitly as HTTP would.
                    if(code!=0)tx.setRollbackOnly();
                    return code;
                });} catch(InpatientService.InpException e){return e.code;}
                finally {SecurityContextHolder.clearContext();}
            });
            assertTrue(writerStarted.await(5,TimeUnit.SECONDS));
            boolean waiting=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(System.nanoTime()<until) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,writerPid.get()));
                if(waiting)break;if(writer.isDone())break;Thread.sleep(30);
            }
            assertTrue(waiting,"Writer must actually contend on the ownership lock");release.countDown();
            assertTrue(handoff.get(10,TimeUnit.SECONDS));assertEquals(9018,writer.get(10,TimeUnit.SECONDS));
            assertEquals(bid,jdbc.queryForObject("select doctor_id from inp_admission where id=?",Long.class,admission));
            assertEquals("PATIENT_SIGNED",jdbc.queryForObject("select status from emr_consent where id=?",String.class,consent));
            assertNull(jdbc.queryForObject("select doctor_sign from emr_consent where id=?",String.class,consent));
        } finally {release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }

}
