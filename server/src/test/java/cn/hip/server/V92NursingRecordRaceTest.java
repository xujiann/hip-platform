package cn.hip.server;

import cn.hip.qualitycare.web.NursingRecordController;
import cn.hip.qualitycare.web.NursingRecordController.RecordReq;
import cn.hip.platform.integration.signature.SignatureAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

/** Real committed transactions; PostgreSQL must report a lock wait before the first is released. */
@SpringBootTest
class V92NursingRecordRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @Autowired NursingRecordController records; @Autowired SignatureAdapter signatures;
    Long nurse,patient,admission,record;String name;
    enum Race { SIGN_THEN_UPDATE, UPDATE_THEN_SIGN, SIGN_THEN_SIGN, DISCHARGE_THEN_ADD }
    @AfterEach void cleanup(){
        SecurityContextHolder.clearContext();
        if(admission!=null){jdbc.update("delete from nur_record where admission_id=?",admission);jdbc.update("delete from inp_admission where id=?",admission);}
        if(patient!=null)jdbc.update("delete from empi_patient where id=?",patient);
        if(nurse!=null)jdbc.update("delete from sys_user where id=?",nurse);
    }
    void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,List.of(new SimpleGrantedAuthority("ROLE_NURSE"))));}
    RecordReq changed(){return new RecordReq(admission,"OBSERVE",null,"changed","care","effect",null);}
    @Test void signingFreezesBeforeWaitingUpdate()throws Exception{run(Race.SIGN_THEN_UPDATE);}
    @Test void signingWaitsForUpdateAndSignsCommittedContent()throws Exception{run(Race.UPDATE_THEN_SIGN);}
    @Test void waitingSecondSignatureReturnsAlreadySigned()throws Exception{run(Race.SIGN_THEN_SIGN);}
    @Test void newRecordWaitsForDischargeAndDoesNotInsert()throws Exception{run(Race.DISCHARGE_THEN_ADD);}
    void run(Race race)throws Exception{
        String suffix=Long.toString(System.nanoTime());name="v92race"+suffix;
        nurse=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,name,name);
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'V92虚构竞争','U') returning id",Long.class,"V92R"+suffix);
        admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,status,admit_at) select ?,?,1,ward_id,id,'IN_HOSPITAL',now() from inp_bed order by id limit 1 returning id",Long.class,"V92R"+suffix,patient);
        record=jdbc.queryForObject("insert into nur_record(admission_id,record_kind,observation,measure,effect,nurse_id) values (?,'OBSERVE','original','care','effect',?) returning id",Long.class,admission,nurse);
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);
        AtomicInteger pid=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->{
                auth();try{return new TransactionTemplate(manager).execute(tx->{
                    if(race==Race.DISCHARGE_THEN_ADD)jdbc.update("update inp_admission set status='DISCHARGED',discharged_at=now() where id=?",admission);
                    else if(race==Race.UPDATE_THEN_SIGN)assertEquals(0,records.update(record,changed()).getCode());
                    else assertEquals(0,records.sign(record,SecurityContextHolder.getContext().getAuthentication()).getCode());
                    held.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;
                });}finally{SecurityContextHolder.clearContext();}
            });
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var second=pool.submit(()->{
                auth();try{return new TransactionTemplate(manager).execute(tx->{
                    pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();
                    int code=switch(race){
                        case SIGN_THEN_UPDATE -> records.update(record,changed()).getCode();
                        case UPDATE_THEN_SIGN,SIGN_THEN_SIGN -> records.sign(record,SecurityContextHolder.getContext().getAuthentication()).getCode();
                        case DISCHARGE_THEN_ADD -> records.add(changed(),SecurityContextHolder.getContext().getAuthentication()).getCode();
                    };if(code!=0)tx.setRollbackOnly();return code;
                });}finally{SecurityContextHolder.clearContext();}
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));boolean waiting=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(System.nanoTime()<until){
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get()));
                if(waiting||second.isDone())break;Thread.sleep(30);
            }
            assertTrue(waiting,"The second operation must contend on the actual lifecycle/record row");release.countDown();assertTrue(first.get(10,TimeUnit.SECONDS));
            assertEquals(switch(race){case SIGN_THEN_UPDATE->4803;case SIGN_THEN_SIGN->4808;case DISCHARGE_THEN_ADD->4805;case UPDATE_THEN_SIGN->0;},second.get(10,TimeUnit.SECONDS));
            var row=jdbc.queryForMap("select observation,signature from nur_record where id=?",record);
            if(race==Race.UPDATE_THEN_SIGN){assertEquals("changed",row.get("observation"));assertTrue(signatures.verify("changed|care|effect",(String)row.get("signature")));assertFalse(signatures.verify("original|care|effect",(String)row.get("signature")));}
            else {assertEquals("original",row.get("observation"));if(race!=Race.DISCHARGE_THEN_ADD)assertTrue(signatures.verify("original|care|effect",(String)row.get("signature")));}
            assertEquals(1,jdbc.queryForObject("select count(*) from nur_record where admission_id=?",Integer.class,admission));
        }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
