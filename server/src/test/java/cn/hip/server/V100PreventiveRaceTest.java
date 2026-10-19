package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.qualitycare.web.NursingQualityController;
import cn.hip.qualitycare.web.PhcController;
import cn.hip.platform.core.config.BusinessDates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real committed transactions; each test proves a PG row-lock wait. */
@SpringBootTest
class V100PreventiveRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @Autowired NursingQualityController quality; @Autowired PhcController phc; @Autowired InpatientService inpatient;
    Long actor,patient,bed,admission; String username;
    void auth(String n,String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(n,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();new TransactionTemplate(manager).executeWithoutResult(tx->{
        if(patient!=null)jdbc.update("delete from phc_record where patient_id=?",patient);
        if(admission!=null){jdbc.update("delete from qc_infection_case where admission_id=?",admission);jdbc.update("delete from inp_settlement where admission_id=?",admission);jdbc.update("delete from inp_admission where id=?",admission);}
        if(bed!=null)jdbc.update("delete from inp_bed where id=?",bed);
        if(patient!=null)jdbc.update("delete from empi_patient where id=?",patient);
        if(actor!=null){jdbc.update("delete from sys_user_role where user_id=?",actor);jdbc.update("delete from sys_user where id=?",actor);}
    });}
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void blockedRequestsRevalidateCommittedParentAndActor(int action)throws Exception {
        String tag="V100R"+System.nanoTime();username=tag.toLowerCase();
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            actor=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,username,username);
            jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='NURSE'",actor);
            patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag);
            bed=jdbc.queryForObject("insert into inp_bed(ward_id,bed_no,status) select ward_id,?,'FREE' from inp_bed order by id limit 1 returning id",Long.class,"R"+System.nanoTime()%10000000);
            admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id) select ?,?,1,ward_id,id from inp_bed where id=? returning id",Long.class,tag,patient,bed);
            jdbc.update("update inp_bed set status='OCCUPIED',admission_id=? where id=?",admission,bed);
        });
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var backend=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{auth("admin","ADMIN");try{return new TransactionTemplate(manager).execute(tx->{
                if(action==0)inpatient.discharge(admission,1L);
                else if(action==1){jdbc.queryForList("select id from inp_admission where id=? for update",admission);jdbc.update("update sys_user set enabled=false where id=?",actor);}
                else jdbc.update("update empi_patient set active=false where id=?",patient);
                held.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;
            });}finally{SecurityContextHolder.clearContext();}});
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var second=pool.submit(()->{auth(username,"NURSE");try{return new TransactionTemplate(manager).execute(tx->{
                backend.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();
                if(action==2)return phc.add(new PhcController.PhcReq(patient,"EDUCATION","V100 denied",null)).getCode();
                return quality.reportInfection(new NursingQualityController.InfectionReq(admission,"呼吸道",null,BusinessDates.today().toString(),"V100 retrospective")).getCode();
            });}catch(AccessDeniedException ex){return 1005;}finally{SecurityContextHolder.clearContext();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(System.nanoTime()<deadline){waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,backend.get()));if(waiting||second.isDone())break;Thread.sleep(20);}
            assertTrue(waiting,"report must actually wait for the parent transaction");release.countDown();assertTrue(first.get(10,TimeUnit.SECONDS));
            assertEquals(action==0?0:action==1?1005:4611,second.get(10,TimeUnit.SECONDS));
            assertEquals(action==0?1:0,jdbc.queryForObject("select count(*) from qc_infection_case where admission_id=?",Integer.class,admission));
            assertEquals(0,jdbc.queryForObject("select count(*) from phc_record where patient_id=?",Integer.class,patient));
            if(action==0){assertEquals("DISCHARGED",jdbc.queryForObject("select status from inp_admission where id=?",String.class,admission));assertEquals(actor,jdbc.queryForObject("select reporter_id from qc_infection_case where admission_id=?",Long.class,admission));}
        }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
