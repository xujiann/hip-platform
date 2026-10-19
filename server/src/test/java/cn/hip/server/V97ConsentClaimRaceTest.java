package cn.hip.server;
import cn.hip.inpatient.web.ConsentController;
import cn.hip.outpatient.service.DoctorStationService;
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
import static org.junit.jupiter.api.Assertions.*;

/** Real committed transactions; every case must observe an actual PostgreSQL lock wait. */
@SpringBootTest
class V97ConsentClaimRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @Autowired ConsentController consents; @Autowired DoctorStationService doctors;
    Long a,b,p,s,r,c;String an,bn;
    void auth(String name){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP"))));}
    @AfterEach void cleanup(){
        SecurityContextHolder.clearContext();
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            if(r!=null){jdbc.update("delete from emr_consent where registration_id=?",r);jdbc.update("delete from outp_emr where registration_id=?",r);jdbc.update("delete from outp_registration where id=?",r);}
            if(s!=null)jdbc.update("delete from outp_schedule where id=?",s);
            if(p!=null)jdbc.update("delete from empi_patient where id=?",p);
            for(Long u:Arrays.asList(a,b))if(u!=null){jdbc.update("delete from sys_user_role where user_id=?",u);jdbc.update("delete from sys_user where id=?",u);}
        });
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void actualVisitClaimInvalidatesWaitingConsentWrites(int action)throws Exception{race(action,false);}
    @Test void authorRevocationWhileWaitingUsesAFreshStatementSnapshot()throws Exception{race(2,true);}
    void race(int action,boolean authorChange)throws Exception{
        String tag="v97r"+System.nanoTime();an=tag+"a";bn=tag+"b";
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            a=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,an,an);
            b=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,bn,bn);
            jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.id in (?,?) and r.code='DOCTOR_OUTP'",a,b);
            p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag);
            s=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity) values (1,current_date-2,10) returning id",Long.class);
            r=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status) values (1,?,?,1,?,current_date-2,0,'REGISTERED') returning id",Long.class,p,s,authorChange?b:null);
            if(authorChange)jdbc.update("insert into outp_emr(registration_id,doctor_id) values (?,?)",r,a);
            c=jdbc.queryForObject("insert into emr_consent(registration_id,consent_type,title,content,status) values (?,'SELF_PAY','V97','V97 race',?) returning id",Long.class,r,action==2?"PATIENT_SIGNED":"DRAFT");
        });
        CountDownLatch changed=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);
        AtomicInteger backend=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try{
            var claim=pool.submit(()->{auth(bn);try{return new TransactionTemplate(manager).execute(tx->{
                if(authorChange){jdbc.queryForList("select id from outp_registration where id=? for update",r);jdbc.update("update outp_emr set doctor_id=? where registration_id=?",b,r);}
                else{doctors.startVisit(r,b);jdbc.queryForObject("select doctor_id from outp_registration where id=?",Long.class,r);}
                changed.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;
            });}finally{SecurityContextHolder.clearContext();}});
            assertTrue(changed.await(10,TimeUnit.SECONDS));
            var writer=pool.submit(()->{auth(an);try{return new TransactionTemplate(manager).execute(tx->{
                backend.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();
                int code=switch(action){case 0->consents.create(new ConsentController.ConsentReq(null,r,"SELF_PAY","V97","V97 denied",null,null,null,null),SecurityContextHolder.getContext().getAuthentication()).getCode();case 1->consents.patientSign(c,new ConsentController.PatientSignReq("V97 denied")).getCode();case 2->consents.doctorSign(c,SecurityContextHolder.getContext().getAuthentication()).getCode();default->consents.revoke(c,SecurityContextHolder.getContext().getAuthentication()).getCode();};
                if(code!=0)tx.setRollbackOnly();return code;
            });}catch(HipBizException e){return e.code;}finally{SecurityContextHolder.clearContext();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));boolean waiting=false;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(System.nanoTime()<end){waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,backend.get()));if(waiting||writer.isDone())break;Thread.sleep(20);}
            assertTrue(waiting,"Consent writer must actually wait for the visit claim/author change");release.countDown();
            assertTrue(claim.get(10,TimeUnit.SECONDS));assertEquals(4036,writer.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("select count(*) from emr_consent where registration_id=?",Integer.class,r));
            var row=jdbc.queryForMap("select status,patient_sign,doctor_sign,doctor_id,revoked_at from emr_consent where id=?",c);
            assertEquals(action==2?"PATIENT_SIGNED":"DRAFT",row.get("status"));
            for(String key:List.of("patient_sign","doctor_sign","doctor_id","revoked_at"))assertNull(row.get(key));
        }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
