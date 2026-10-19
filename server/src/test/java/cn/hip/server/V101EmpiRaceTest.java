package cn.hip.server;

import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.core.common.HipBizException;
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

@SpringBootTest
class V101EmpiRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager; @Autowired PatientService patients;
    Long actor,patient;String username;
    void auth(String n,String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(n,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    Patient patch(){Patient p=new Patient();p.setInsuranceType(null);return p;}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(patient!=null)jdbc.update("delete from empi_patient where id=?",patient);if(actor!=null){jdbc.update("delete from sys_user_role where user_id=?",actor);jdbc.update("delete from sys_user where id=?",actor);}}
    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void actualApiUpdatesSerializeIdentityAndRecheckEnabledActor(int action)throws Exception {
        String tag="V101R"+System.nanoTime();username=tag.toLowerCase();String role=action==2?"CASHIER":"NURSE";
        actor=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,username,username);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",actor,role);
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex,phone,insurance_type,blood_type,allergy_history) values (?,?,'U','13800001111','YB_STAFF','A','V101 original') returning id",Long.class,tag,tag);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var backend=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{auth("admin","ADMIN");try{return new TransactionTemplate(manager).execute(tx->{
                var data=patch();if(action<2)data.setPhone("13900002222");else if(action==2)data.setBloodType("AB");else data.setAddress("V101 committed");
                patients.updateFromApi(patient,data);
                if(action==3)jdbc.update("update sys_user set enabled=false where id=?",actor);
                held.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;
            });}finally{SecurityContextHolder.clearContext();}});
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var second=pool.submit(()->{auth(username,role);try{return new TransactionTemplate(manager).execute(tx->{
                backend.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();
                var data=patch();data.setAddress("V101 second");if(action==1)data.setPhone("13800001111");
                patients.updateFromApi(patient,data);return 0;
            });}catch(HipBizException e){return e.code;}catch(AccessDeniedException e){return 1005;}finally{SecurityContextHolder.clearContext();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(System.nanoTime()<deadline){waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,backend.get()));if(waiting||second.isDone())break;Thread.sleep(20);}
            assertTrue(waiting,"API update must really wait on the patient lock");release.countDown();assertTrue(first.get(10,TimeUnit.SECONDS));assertEquals(action==1?2005:action==3?1005:0,second.get(10,TimeUnit.SECONDS));
            var row=jdbc.queryForMap("select phone,address,blood_type,allergy_history,insurance_type from empi_patient where id=?",patient);
            assertEquals(action<2?"13900002222":"13800001111",row.get("phone"));assertEquals(action==2?"AB":"A",row.get("blood_type"));assertEquals("V101 original",row.get("allergy_history"));assertEquals("YB_STAFF",row.get("insurance_type"));assertEquals(action==1?null:action==3?"V101 committed":"V101 second",row.get("address"));
        }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
