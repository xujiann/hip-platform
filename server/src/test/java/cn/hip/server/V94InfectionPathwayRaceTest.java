package cn.hip.server;

import cn.hip.qualitycare.web.InfectionPlusController;
import cn.hip.qualitycare.web.PatientCareController;
import cn.hip.inpatient.service.InpatientService.InpException;
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
import java.util.function.IntSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real commits and observed PG lock waits; neither sleeps nor an outer rollback can fake these races. */
@SpringBootTest
class V94InfectionPathwayRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @Autowired InfectionPlusController infection; @Autowired PatientCareController care;
    Long actor,other,patient,admission,template,enrollment,catheter;String name;
    void setup(){String n=Long.toString(System.nanoTime());name="v94race"+n;
        actor=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,name,name);
        other=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,name+"b",name+"b");
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'V94虚构竞争','U') returning id",Long.class,"V94R"+n);
        admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,status,admit_at,doctor_id) select ?,?,1,ward_id,id,'IN_HOSPITAL',now(),? from inp_bed order by id limit 1 returning id",Long.class,"V94R"+n,patient,actor);
        template=jdbc.queryForObject("insert into cp_pathway_template(name,items) values (?,'[]') returning id",Long.class,"V94R"+n);
        enrollment=jdbc.queryForObject("insert into cp_enrollment(admission_id,template_id) values (?,?) returning id",Long.class,admission,template);
        catheter=jdbc.queryForObject("insert into inf_catheter(admission_id,line_type) values (?,'VENT') returning id",Long.class,admission);
    }
    void auth(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();
        if(admission!=null){
            jdbc.update("delete from inf_catheter_assess where catheter_id in (select id from inf_catheter where admission_id=?)",admission);
            jdbc.update("delete from inf_catheter where admission_id=?",admission);
            jdbc.update("delete from qc_infection_case where admission_id=?",admission);
            jdbc.update("delete from cp_variance where enrollment_id in (select id from cp_enrollment where admission_id=?)",admission);
            jdbc.update("delete from cp_enrollment where admission_id=?",admission);
            jdbc.update("delete from inp_order_exec where order_id in (select id from inp_order where admission_id=?)",admission);
            jdbc.update("delete from inp_order where admission_id=?",admission);
            jdbc.update("delete from inp_admission where id=?",admission);
        }
        if(template!=null)jdbc.update("delete from cp_pathway_template where id=?",template);
        if(patient!=null)jdbc.update("delete from empi_patient where id=?",patient);
        if(actor!=null)jdbc.update("delete from sys_user where id=?",actor);
        if(other!=null)jdbc.update("delete from sys_user where id=?",other);
    }
    void race(Runnable firstAction,String role,IntSupplier secondAction,int expected)throws Exception{
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);AtomicInteger pid=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->new TransactionTemplate(manager).execute(tx->{firstAction.run();held.countDown();try{assertTrue(release.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var second=pool.submit(()->{auth(role);try{return new TransactionTemplate(manager).execute(tx->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();try{return secondAction.getAsInt();}catch(InpException e){tx.setRollbackOnly();return e.code;}});}finally{SecurityContextHolder.clearContext();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));boolean waiting=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<until){waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get()));if(waiting||second.isDone())break;Thread.sleep(30);}
            assertTrue(waiting,"Second writer must actually wait for the competing committed row");release.countDown();assertTrue(first.get(10,TimeUnit.SECONDS));assertEquals(expected,second.get(10,TimeUnit.SECONDS));
        }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    @Test void catheterRegistrationWaitsForDischarge()throws Exception{setup();race(()->jdbc.update("update inp_admission set status='DISCHARGED',discharged_at=now() where id=?",admission),"NURSE",()->infection.insertLine(new InfectionPlusController.LineReq(admission,"CVC","V94")).getCode(),4751);assertEquals(1,jdbc.queryForObject("select count(*) from inf_catheter where admission_id=?",Integer.class,admission));}
    @Test void registrationSerializesDuplicateActiveTypes()throws Exception{setup();race(()->{jdbc.queryForList("select id from inp_admission where id=? for update",admission);jdbc.update("insert into inf_catheter(admission_id,line_type) values (?,'CVC')",admission);},"NURSE",()->infection.insertLine(new InfectionPlusController.LineReq(admission,"CVC","V94")).getCode(),4752);assertEquals(1,jdbc.queryForObject("select count(*) from inf_catheter where admission_id=? and line_type='CVC'",Integer.class,admission));}
    @Test void assessmentWaitsForRemovalAndCreatesNothing()throws Exception{setup();race(()->jdbc.update("update inf_catheter set status='REMOVED',end_at=now() where id=?",catheter),"NURSE",()->infection.assess(catheter,true,"V94").getCode(),4753);assertEquals(0,jdbc.queryForObject("select count(*) from inf_catheter_assess where catheter_id=?",Integer.class,catheter));}
    @Test void varianceWaitsForNewAttending()throws Exception{setup();race(()->jdbc.update("update inp_admission set doctor_id=? where id=?",other,admission),"DOCTOR_OUTP",()->care.addVariance(enrollment,new PatientCareController.VarianceReq(1,"DELAY","V94")).getCode(),9018);assertEquals(0,jdbc.queryForObject("select count(*) from cp_variance where enrollment_id=?",Integer.class,enrollment));}
    @Test void completionWaitsForNewAttending()throws Exception{setup();race(()->jdbc.update("update inp_admission set doctor_id=? where id=?",other,admission),"DOCTOR_OUTP",()->care.completeEnrollment(enrollment).getCode(),9018);assertEquals("ACTIVE",jdbc.queryForObject("select status from cp_enrollment where id=?",String.class,enrollment));}
    @Test void varianceWaitsForCompletionAndCreatesNothing()throws Exception{setup();race(()->jdbc.update("update cp_enrollment set status='COMPLETED',ended_at=now() where id=?",enrollment),"DOCTOR_OUTP",()->care.addVariance(enrollment,new PatientCareController.VarianceReq(1,"DELAY","V94")).getCode(),9908);assertEquals(0,jdbc.queryForObject("select count(*) from cp_variance where enrollment_id=?",Integer.class,enrollment));}
    @Test void failedOrderCreationRollsBackEnrollmentAndEarlierOrders(){setup();auth("DOCTOR_OUTP");jdbc.update("delete from cp_enrollment where id=?",enrollment);
        Long charge=jdbc.queryForObject("select id from md_charge_item order by id limit 1",Long.class);
        jdbc.update("update cp_pathway_template set items=? where id=?","[{\"orderType\":\"TREAT\",\"itemId\":"+charge+",\"qty\":1},{\"orderType\":\"TREAT\",\"itemId\":-94,\"qty\":1}]",template);
        assertEquals(9007,care.applyPathway(template,admission,SecurityContextHolder.getContext().getAuthentication()).getCode());
        assertEquals(0,jdbc.queryForObject("select count(*) from cp_enrollment where admission_id=?",Integer.class,admission));
        assertEquals(0,jdbc.queryForObject("select count(*) from inp_order where admission_id=?",Integer.class,admission));
    }
    @Test void repeatedEnrollmentReturnsBusinessCodeWithoutAbortingTransaction(){setup();auth("DOCTOR_OUTP");
        assertEquals(9906,care.applyPathway(template,admission,SecurityContextHolder.getContext().getAuthentication()).getCode());
        assertEquals(1,jdbc.queryForObject("select count(*) from cp_enrollment where admission_id=?",Integer.class,admission));
    }
}
