package cn.hip.server;

import cn.hip.inpatient.web.IcuRecordController;
import cn.hip.inpatient.service.InpatientWriteAccess;
import cn.hip.inpatient.service.InpatientService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class V99IcuRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @Autowired IcuRecordController icu; @Autowired InpatientWriteAccess admissions; @Autowired InpatientService inpatient;
    Long a,b,p,bed,adm;String an,bn;
    void auth(String n,String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(n,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();new TransactionTemplate(manager).executeWithoutResult(tx->{
        if(adm!=null){jdbc.update("delete from inp_icu_record where admission_id=?",adm);jdbc.update("delete from inp_settlement where admission_id=?",adm);jdbc.update("delete from inp_admission where id=?",adm);}
        if(bed!=null)jdbc.update("delete from inp_bed where id=?",bed);
        if(p!=null)jdbc.update("delete from empi_patient where id=?",p);
        for(Long u:Arrays.asList(a,b))if(u!=null){jdbc.update("delete from sys_user_role where user_id=?",u);jdbc.update("delete from sys_user where id=?",u);}
    });}
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6})
    void actualParentChangesAndReadsHoldTheirLocks(int action)throws Exception {
        String tag="V99R"+System.nanoTime();an=tag.toLowerCase()+"a";bn=tag.toLowerCase()+"b";
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            a=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,an,an);
            b=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,bn,bn);
            jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.id in (?,?) and r.code='DOCTOR_OUTP'",a,b);
            if(action==4)jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='NURSE'",a);
            p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag);
            bed=jdbc.queryForObject("insert into inp_bed(ward_id,bed_no,status) select ward_id,?,'FREE' from inp_bed order by id limit 1 returning id",Long.class,"R"+System.nanoTime()%10000000);
            adm=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id) select ?,?,1,ward_id,id,? from inp_bed where id=? returning id",Long.class,tag,p,a,bed);
            jdbc.update("update inp_bed set status='OCCUPIED',admission_id=? where id=?",adm,bed);
            jdbc.update("insert into inp_icu_record(admission_id,intake_ml,output_ml,note) values (?,700,300,'V99 original')",adm);
        });
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);AtomicInteger backend=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{auth(action==6?an:"admin",action==6?"DOCTOR_OUTP":"ADMIN");try{return new TransactionTemplate(manager).execute(tx->{
                if(action==6)assertEquals(1,icu.list(adm).getData().size());
                else if(action>=3)inpatient.discharge(adm,1L);
                else admissions.setAttending(adm,b);
                held.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;
            });}finally{SecurityContextHolder.clearContext();}});
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var second=pool.submit(()->{String role=action==4?"NURSE":action==5||action==6?"ADMIN":"DOCTOR_OUTP";auth(role.equals("ADMIN")?"admin":an,role);try{return new TransactionTemplate(manager).execute(tx->{
                backend.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();
                if(action==6){admissions.setAttending(adm,b);return 0;}
                if(action==1)return icu.list(adm).getCode();
                if(action==2)return icu.balance(adm).getCode();
                return icu.record(new IcuRecordController.IcuReq(adm,null,null,null,null,null,null,15,999,1,false,"V99 denied"),SecurityContextHolder.getContext().getAuthentication()).getCode();
            });}catch(HipBizException e){return e.code;}catch(AccessDeniedException e){return 1005;}finally{SecurityContextHolder.clearContext();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(System.nanoTime()<deadline){waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,backend.get()));if(waiting||second.isDone())break;Thread.sleep(20);}
            assertTrue(waiting,"ICU/parent change must actually wait on the admission lock");release.countDown();
            assertTrue(first.get(10,TimeUnit.SECONDS));int expected=action==6?0:action>=3?4570:action==0?9018:1005;assertEquals(expected,second.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("select count(*) from inp_icu_record where admission_id=?",Integer.class,adm));
            assertEquals("V99 original",jdbc.queryForObject("select note from inp_icu_record where admission_id=?",String.class,adm));
        }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
