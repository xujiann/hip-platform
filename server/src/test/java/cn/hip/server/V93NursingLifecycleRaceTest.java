package cn.hip.server;

import cn.hip.qualitycare.web.NursingQualityController;
import cn.hip.qualitycare.web.RiskAssessController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

/** Actual commits; no outer test transaction can hide partial writes or fake lock contention. */
@SpringBootTest
class V93NursingLifecycleRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @Autowired NursingQualityController quality; @Autowired RiskAssessController risks;
    Long nurse,patient,admission;String name;
    enum Writer { LEGACY_LEVEL, REASON_LEVEL, RISK }
    @AfterEach void cleanup(){
        SecurityContextHolder.clearContext();jdbc.execute("alter table nur_care_level_log drop constraint if exists v93_log_failure_probe");
        if(admission!=null){jdbc.update("delete from nur_care_level_log where admission_id=?",admission);jdbc.update("delete from nur_risk_assess where admission_id=?",admission);jdbc.update("delete from inp_admission where id=?",admission);}
        if(patient!=null)jdbc.update("delete from empi_patient where id=?",patient);
        if(nurse!=null)jdbc.update("delete from sys_user where id=?",nurse);
    }
    void setup(){String suffix=Long.toString(System.nanoTime());name="v93race"+suffix;
        nurse=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,name,name);
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'V93虚构竞争','U') returning id",Long.class,"V93R"+suffix);
        admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,status,admit_at,care_level) select ?,?,1,ward_id,id,'IN_HOSPITAL',now(),'二级' from inp_bed order by id limit 1 returning id",Long.class,"V93R"+suffix,patient);
    }
    void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,List.of(new SimpleGrantedAuthority("ROLE_NURSE"))));}
    @Test void legacyLevelWaitsForDischarge()throws Exception{run(Writer.LEGACY_LEVEL);}
    @Test void reasonedLevelWaitsForDischarge()throws Exception{run(Writer.REASON_LEVEL);}
    @Test void riskAssessmentWaitsForDischarge()throws Exception{run(Writer.RISK);}
    @Test void failedLevelLogRollsBackTheLevelUpdate(){
        setup();auth();jdbc.execute("alter table nur_care_level_log add constraint v93_log_failure_probe check (reason is distinct from 'V93FAIL')");
        assertThrows(DataIntegrityViolationException.class,()->quality.changeCareLevel(admission,new NursingQualityController.CareLevelReq("特级","V93FAIL"),SecurityContextHolder.getContext().getAuthentication()));
        assertEquals("二级",jdbc.queryForObject("select care_level from inp_admission where id=?",String.class,admission));
        assertEquals(0,jdbc.queryForObject("select count(*) from nur_care_level_log where admission_id=?",Integer.class,admission));
    }
    void run(Writer kind)throws Exception{
        setup();CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);AtomicInteger pid=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
                jdbc.update("update inp_admission set status='DISCHARGED',discharged_at=now() where id=?",admission);
                held.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;
            }));
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var second=pool.submit(()->{
                auth();try{return new TransactionTemplate(manager).execute(tx->{
                    pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();
                    var me=SecurityContextHolder.getContext().getAuthentication();
                    int code=switch(kind){
                        case LEGACY_LEVEL -> quality.setCareLevel(admission,"特级",null,me).getCode();
                        case REASON_LEVEL -> quality.changeCareLevel(admission,new NursingQualityController.CareLevelReq("特级","V93"),me).getCode();
                        case RISK -> risks.assess(new RiskAssessController.AssessReq(admission,"BRADEN",12,"V93"),me).getCode();
                    };if(code!=0)tx.setRollbackOnly();return code;
                });}finally{SecurityContextHolder.clearContext();}
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));boolean waiting=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(System.nanoTime()<until){waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get()));if(waiting||second.isDone())break;Thread.sleep(30);}
            assertTrue(waiting,"The nursing writer must wait on the actual admission lifecycle row");release.countDown();assertTrue(first.get(10,TimeUnit.SECONDS));assertEquals(kind==Writer.RISK?4780:4805,second.get(10,TimeUnit.SECONDS));
            assertEquals("二级",jdbc.queryForObject("select care_level from inp_admission where id=?",String.class,admission));
            assertEquals(0,jdbc.queryForObject("select count(*) from nur_care_level_log where admission_id=?",Integer.class,admission));
            assertEquals(0,jdbc.queryForObject("select count(*) from nur_risk_assess where admission_id=?",Integer.class,admission));
        }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
