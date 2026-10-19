package cn.hip.server;

import cn.hip.outpatient.web.*;
import org.junit.jupiter.api.*;
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

/** Committed clinical calls, not a transactional test; every case observes an actual lock wait. */
@SpringBootTest
class V98EmergencyRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @Autowired TriageController triages; @Autowired ErObservationController observations; @Autowired ErRescueController rescues;
    Long a,b,t,o,r;String an,bn;
    void auth(String name,String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    @AfterEach void cleanup(){
        SecurityContextHolder.clearContext();
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            if(t!=null){jdbc.update("delete from er_rescue_record where triage_id=?",t);jdbc.update("delete from er_observation_note where observation_id in(select id from er_observation where triage_id=?)",t);jdbc.update("delete from er_observation where triage_id=?",t);jdbc.update("delete from outp_triage where id=?",t);}
            for(Long id:Arrays.asList(a,b))if(id!=null){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}
        });
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8})
    void waitingWritesRecheckOwnershipAndObservationState(int action)throws Exception {
        String tag="v98r"+System.nanoTime();an=tag+"a";bn=tag+"b";
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            a=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,an,an);
            b=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,bn,bn);
            jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.id in (?,?) and r.code='DOCTOR_OUTP'",a,b);
            t=jdbc.queryForObject("insert into outp_triage(patient_name,level,chief_complaint,doctor_id) values ('V98 race',1,'V98',?) returning id",Long.class,action==7?null:a);
            if(action!=0)o=jdbc.queryForObject("insert into er_observation(triage_id,bed_no) values (?,?) returning id",Long.class,t,"R"+System.nanoTime()%10000000);
            r=jdbc.queryForObject("insert into er_rescue_record(triage_id,observation_id,outcome) values (?,?,'ONGOING') returning id",Long.class,t,o);
        });
        CountDownLatch changed=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);
        AtomicInteger backend=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{auth(action==7?bn:"admin",action==7?"DOCTOR_OUTP":"ADMIN");try{return new TransactionTemplate(manager).execute(tx->{
                if(action==8)assertEquals(0,observations.end(o,"DISCHARGED").getCode());
                else if(action==7)assertEquals(0,triages.setStatus(t,"IN_TREATMENT").getCode());
                else assertEquals(0,triages.assignDoctor(t,new TriageController.DoctorReq(b)).getCode());
                changed.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;
            });}finally{SecurityContextHolder.clearContext();}});
            assertTrue(changed.await(10,TimeUnit.SECONDS));
            var second=pool.submit(()->{auth(an,"DOCTOR_OUTP");try{return new TransactionTemplate(manager).execute(tx->{
                backend.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();
                return switch(action){
                    case 0->observations.start(new ErObservationController.StartReq(t,"R"+System.nanoTime()%10000000)).getCode();
                    case 1,7,8->observations.addNote(o,"V98 denied",SecurityContextHolder.getContext().getAuthentication()).getCode();
                    case 2->observations.end(o,"ADMITTED").getCode();
                    case 3,4->rescues.record(new ErRescueController.RescueReq(action==4?o:null,t,null,null,null,null,null,null,null,null,null,"V98 denied",null,null,null),SecurityContextHolder.getContext().getAuthentication()).getCode();
                    case 5->rescues.end(r,new ErRescueController.EndReq("DEATH","V98 denied")).getCode();
                    default->triages.setStatus(t,"DISCHARGED").getCode();
                };
            });}catch(AccessDeniedException e){return 1005;}finally{SecurityContextHolder.clearContext();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(System.nanoTime()<deadline){waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,backend.get()));if(waiting||second.isDone())break;Thread.sleep(20);}
            assertTrue(waiting,"Emergency write must wait for the actual assignment/claim/end transaction");release.countDown();
            assertTrue(first.get(10,TimeUnit.SECONDS));assertEquals(action==8?4563:1005,second.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("select count(*) from er_rescue_record where triage_id=?",Integer.class,t));
            assertEquals(action==0?0:1,jdbc.queryForObject("select count(*) from er_observation where triage_id=?",Integer.class,t));
            assertEquals(0,jdbc.queryForObject("select count(*) from er_observation_note where observation_id in(select id from er_observation where triage_id=?)",Integer.class,t));
            assertNull(jdbc.queryForObject("select rescue_end from er_rescue_record where id=?",Object.class,r));
            assertEquals(action==7?"IN_TREATMENT":"TRIAGED",jdbc.queryForObject("select status from outp_triage where id=?",String.class,t));
        } finally {release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
