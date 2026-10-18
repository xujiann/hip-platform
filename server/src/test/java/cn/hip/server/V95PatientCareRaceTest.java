package cn.hip.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc
class V95PatientCareRaceTest {
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager; @Autowired MockMvc mvc; @Autowired ObjectMapper om;
    Long actor,other,patient,admission,consult,followup,pkg,exam;String name,tag;
    void setup(){tag="V95R"+System.nanoTime();name=tag.toLowerCase();
        actor=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,2) returning id",Long.class,name,name);
        other=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,1) returning id",Long.class,name+"b",name+"b");
        for(Long id:List.of(actor,other))jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='DOCTOR_OUTP'",id);
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag);
        admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,status,admit_at,doctor_id) select ?,?,1,ward_id,id,'IN_HOSPITAL',now(),? from inp_bed order by id limit 1 returning id",Long.class,tag,patient,actor);
        consult=jdbc.queryForObject("insert into inp_consult(admission_id,to_dept_id,question) values (?,2,'V95') returning id",Long.class,admission);
        followup=jdbc.queryForObject("insert into pc_followup(patient_id,topic,due_date,creator_id) values (?,'V95',current_date,?) returning id",Long.class,patient,actor);
        pkg=jdbc.queryForObject("insert into pe_exam_package(name,price,items) values (?,1,'V95') returning id",Long.class,tag);
        exam=jdbc.queryForObject("insert into pe_exam_record(patient_id,package_id,registrar_id,examiner_id) values (?,?,?,?) returning id",Long.class,patient,pkg,actor,actor);
    }
    @AfterEach void cleanup(){
        if(exam!=null)jdbc.update("delete from pe_exam_record where id=?",exam);
        if(followup!=null)jdbc.update("delete from pc_followup where id=?",followup);
        if(consult!=null)jdbc.update("delete from inp_consult where id=?",consult);
        if(admission!=null)jdbc.update("delete from inp_admission where id=?",admission);
        if(patient!=null)jdbc.update("delete from empi_patient where id=?",patient);
        if(pkg!=null)jdbc.update("delete from pe_exam_package where id=?",pkg);
        for(Long id:Arrays.asList(actor,other))if(id!=null){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}
    }
    int request(String method,String path,Object body,String who,String role)throws Exception{
        var req=(method.equals("POST")?post(path):put(path)).with(user(who).roles(role));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));var response=mvc.perform(req).andReturn().getResponse();assertTrue(response.getStatus()==200||response.getStatus()==403);return om.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("code").asInt();
    }
    void race(Runnable firstAction,String method,String path,Object body,String role,int expected)throws Exception{
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);AtomicInteger pid=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->new TransactionTemplate(manager).execute(tx->{firstAction.run();held.countDown();try{assertTrue(release.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));assertTrue(held.await(10,TimeUnit.SECONDS));
            var second=pool.submit(()->new TransactionTemplate(manager).execute(tx->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();try{int code=request(method,path,body,name,role);if(code!=0)tx.setRollbackOnly();return code;}catch(Exception e){throw new RuntimeException(e);}}));
            assertTrue(started.await(5,TimeUnit.SECONDS));boolean waiting=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<until){waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get()));if(waiting||second.isDone())break;Thread.sleep(30);}
            assertTrue(waiting,"Must observe a real PostgreSQL row-lock wait before releasing the competing commit");release.countDown();assertTrue(first.get(10,TimeUnit.SECONDS));assertEquals(expected,second.get(10,TimeUnit.SECONDS));
        }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    @Test void consultRequestWaitsForAttendingHandoff()throws Exception{setup();race(()->jdbc.update("update inp_admission set doctor_id=? where id=?",other,admission),"POST","/api/inpatient/consults",Map.of("admissionId",admission,"toDeptId",2,"question","V95"),"DOCTOR_OUTP",9018);assertEquals(1,jdbc.queryForObject("select count(*) from inp_consult where admission_id=?",Integer.class,admission));}
    @Test void consultRequestWaitsForDischarge()throws Exception{setup();race(()->jdbc.update("update inp_admission set status='DISCHARGED',discharged_at=now() where id=?",admission),"POST","/api/inpatient/consults",Map.of("admissionId",admission,"toDeptId",2,"question","V95"),"DOCTOR_OUTP",9917);assertEquals(1,jdbc.queryForObject("select count(*) from inp_consult where admission_id=?",Integer.class,admission));}
    @Test void consultOpinionWaitsForCurrentDepartmentChange()throws Exception{setup();race(()->jdbc.update("update sys_user set dept_id=1 where id=?",actor),"PUT","/api/inpatient/consults/"+consult+"/complete?opinion=V95",null,"DOCTOR_OUTP",1005);assertEquals("REQUESTED",jdbc.queryForObject("select status from inp_consult where id=?",String.class,consult));}
    @Test void examConclusionWaitsForExaminerReassignment()throws Exception{setup();race(()->jdbc.update("update pe_exam_record set examiner_id=? where id=?",other,exam),"PUT","/api/exam/records/"+exam+"/complete?summary=V95",null,"DOCTOR_OUTP",1005);assertEquals("REGISTERED",jdbc.queryForObject("select status from pe_exam_record where id=?",String.class,exam));}
    @Test void examinerReassignmentWaitsForCompletedConclusion()throws Exception{setup();race(()->jdbc.update("update pe_exam_record set status='DONE',summary='first' where id=?",exam),"PUT","/api/exam/records/"+exam+"/examiner",Map.of("examinerId",other),"ADMIN",9905);assertEquals(actor,jdbc.queryForObject("select examiner_id from pe_exam_record where id=?",Long.class,exam));}
    @Test void secondFollowupCompletionDoesNotOverwriteTheFirst()throws Exception{setup();race(()->jdbc.update("update pc_followup set status='DONE',result_note='first',done_at=now() where id=?",followup),"PUT","/api/patientcare/followups/"+followup+"/done?note=second",null,"DOCTOR_OUTP",9901);assertEquals("first",jdbc.queryForObject("select result_note from pc_followup where id=?",String.class,followup));}
    @Test void secondConsultOpinionDoesNotOverwriteTheFirst()throws Exception{setup();race(()->jdbc.update("update inp_consult set status='DONE',opinion='first',done_at=now() where id=?",consult),"PUT","/api/inpatient/consults/"+consult+"/complete?opinion=second",null,"DOCTOR_OUTP",9903);assertEquals("first",jdbc.queryForObject("select opinion from inp_consult where id=?",String.class,consult));}
    @Test void secondExamConclusionDoesNotOverwriteTheFirst()throws Exception{setup();race(()->jdbc.update("update pe_exam_record set status='DONE',summary='first' where id=?",exam),"PUT","/api/exam/records/"+exam+"/complete?summary=second",null,"DOCTOR_OUTP",9905);assertEquals("first",jdbc.queryForObject("select summary from pe_exam_record where id=?",String.class,exam));}
    @Test void groupFailureRollsBackGroupEarlierPatientsAndRecords()throws Exception{setup();
        int code=request("POST","/api/exam/groups",Map.of("unitName",tag,"packageId",pkg,"examinerId",actor,"memberNames",List.of(tag+"ok","x".repeat(300))),"admin","ADMIN");assertNotEquals(0,code);
        assertEquals(0,jdbc.queryForObject("select count(*) from pe_group where unit_name=?",Integer.class,tag));assertEquals(0,jdbc.queryForObject("select count(*) from empi_patient where name=?",Integer.class,tag+"ok"));assertEquals(1,jdbc.queryForObject("select count(*) from pe_exam_record where package_id=?",Integer.class,pkg));
    }
}
