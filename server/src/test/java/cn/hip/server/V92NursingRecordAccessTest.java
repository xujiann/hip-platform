package cn.hip.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class V92NursingRecordAccessTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;
    String person(){String n="v92"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);return n;}
    Long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    Long admission(){String n="V92"+System.nanoTime();Long p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,n,n);return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,status,admit_at) select ?,?,1,ward_id,id,'IN_HOSPITAL',now() from inp_bed order by id limit 1 returning id",Long.class,n,p);}
    Long record(Long adm,Long nurse){return jdbc.queryForObject("insert into nur_record(admission_id,record_kind,observation,nurse_id) values (?,'OBSERVE','original',?) returning id",Long.class,adm,nurse);}
    Map<String,Object> body(Long adm){return Map.of("admissionId",adm,"recordKind","OBSERVE","observation","changed");}
    JsonNode call(String method,String path,Object body,String name,String...roles)throws Exception{
        var req=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};req.with(user(name).roles(roles));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
        var r=mvc.perform(req).andReturn().getResponse();assertTrue(r.getStatus()==200||r.getStatus()==403,path+" HTTP "+r.getStatus());return om.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    @Test void qualityCanReadButCannotCreateUpdateOrSignEvenOwnRecord()throws Exception{
        String q=person();Long adm=admission(),id=record(adm,uid(q));
        for(String path:List.of("/api/nursing/records?admissionId="+adm,"/api/nursing/records/gate-check?admissionId="+adm,"/api/inpatient/admissions/"+adm+"/print/nursing-record","/api/inpatient/nursing/board")) assertEquals(0,call("GET",path,null,q,"QUALITY").path("code").asInt());
        assertEquals(1005,call("POST","/api/nursing/records",body(adm),q,"QUALITY").path("code").asInt());
        assertEquals(1005,call("PUT","/api/nursing/records/"+id,body(adm),q,"QUALITY").path("code").asInt());
        assertEquals(1005,call("POST","/api/nursing/records/"+id+"/sign",null,q,"QUALITY").path("code").asInt());
        assertEquals(1,jdbc.queryForObject("select count(*) from nur_record where admission_id=?",Integer.class,adm));
        assertEquals("original",jdbc.queryForObject("select observation from nur_record where id=?",String.class,id));
        assertNull(jdbc.queryForObject("select signature from nur_record where id=?",String.class,id));
    }
    @Test void otherNurseOrAdminCannotRewriteAnotherAuthorsDraftViaForgedAdmission()throws Exception{
        String a=person(),b=person();Long adm=admission(),otherAdm=admission(),id=record(adm,uid(a));
        for(String role:List.of("NURSE","ADMIN"))assertEquals(1005,call("PUT","/api/nursing/records/"+id,body(otherAdm),b,role).path("code").asInt());
        assertEquals("original",jdbc.queryForObject("select observation from nur_record where id=?",String.class,id));assertEquals(adm,jdbc.queryForObject("select admission_id from nur_record where id=?",Long.class,id));
    }
    @Test void unresolvedNurseCannotCreateOwnerlessRecord()throws Exception{
        Long adm=admission();assertEquals(1005,call("POST","/api/nursing/records",body(adm),"v92_missing_user","NURSE").path("code").asInt());
        assertEquals(0,jdbc.queryForObject("select count(*) from nur_record where admission_id=?",Integer.class,adm));
    }
    @Test void legacyOwnerlessDraftCannotBeClaimedByAdmin()throws Exception{
        Long adm=admission(),id=record(adm,null);assertEquals(1005,call("PUT","/api/nursing/records/"+id,body(adm),"admin","ADMIN").path("code").asInt());
        assertEquals(4804,call("POST","/api/nursing/records/"+id+"/sign",null,"admin","ADMIN").path("code").asInt());
    }
    @Test void authorWorkflowAndExistingCodesRemainUsableAcrossNursingRoles()throws Exception{
        String a=person(),b=person();Long adm=admission();
        for(String role:List.of("NURSE","ADMIN")){
            Long id=call("POST","/api/nursing/records",body(adm),a,role).path("data").path("id").asLong();assertTrue(id>0);
            assertEquals(0,call("PUT","/api/nursing/records/"+id,body(admission()),a,role).path("code").asInt());
            assertEquals(adm,jdbc.queryForObject("select admission_id from nur_record where id=?",Long.class,id));
            assertEquals(4804,call("POST","/api/nursing/records/"+id+"/sign",null,b,"NURSE").path("code").asInt());
            assertEquals(0,call("POST","/api/nursing/records/"+id+"/sign",null,a,role).path("code").asInt());
            assertEquals(4803,call("PUT","/api/nursing/records/"+id,body(adm),a,role).path("code").asInt());
            assertEquals(4808,call("POST","/api/nursing/records/"+id+"/sign",null,a,role).path("code").asInt());
        }
        assertEquals(0,call("POST","/api/nursing/records",body(adm),a,"QUALITY","NURSE").path("code").asInt());
        assertEquals(4800,call("PUT","/api/nursing/records/-1",body(adm),a,"NURSE").path("code").asInt());
        for(String role:List.of("DOCTOR_OUTP","TECHNICIAN","CASHIER"))assertEquals(1005,call("GET","/api/nursing/records?admissionId="+adm,null,a,role).path("code").asInt());
    }
}
