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
class V94InfectionPathwayAccessTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;
    String person(){String n="v94"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);return n;}
    Long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    Long admission(String owner){String n="V94"+System.nanoTime();Long p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,n,n);return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,status,admit_at,doctor_id) select ?,?,1,ward_id,id,'IN_HOSPITAL',now(),? from inp_bed order by id limit 1 returning id",Long.class,n,p,owner==null?null:uid(owner));}
    Long template(){return jdbc.queryForObject("insert into cp_pathway_template(name,items) values (?,'[]') returning id",Long.class,"V94"+System.nanoTime());}
    Long enrollment(Long adm){return jdbc.queryForObject("insert into cp_enrollment(admission_id,template_id) values (?,?) returning id",Long.class,adm,template());}
    Long catheter(Long adm){return jdbc.queryForObject("insert into inf_catheter(admission_id,line_type) values (?,'VENT') returning id",Long.class,adm);}
    JsonNode call(String method,String path,Object body,String who,String...roles)throws Exception{
        var req=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};req.with(user(who).roles(roles));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
        var r=mvc.perform(req).andReturn().getResponse();assertTrue(r.getStatus()==200||r.getStatus()==403,path+" HTTP "+r.getStatus());return om.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    int code(String method,String path,Object body,String who,String...roles)throws Exception{return call(method,path,body,who,roles).path("code").asInt();}
    Map<String,Object> variance(){return Map.of("dayNo",1,"varType","DELAY","reason","V94");}
    @Test void foreignDoctorCannotChangeAnEnrollmentOrCreateOrders()throws Exception{
        String owner=person(),other=person();Long adm=admission(owner),id=enrollment(adm),tpl=template();
        assertEquals(9018,code("POST","/api/pathways/enrollments/"+id+"/variances",variance(),other,"DOCTOR_OUTP"));
        assertEquals(9018,code("PUT","/api/pathways/enrollments/"+id+"/complete",null,other,"DOCTOR_OUTP"));
        assertEquals(9018,code("POST","/api/pathways/"+tpl+"/apply/"+adm,null,other,"DOCTOR_OUTP"));
        assertEquals(1,jdbc.queryForObject("select count(*) from cp_enrollment where admission_id=?",Integer.class,adm));
        assertEquals(0,jdbc.queryForObject("select count(*) from cp_variance where enrollment_id=?",Integer.class,id));
        assertEquals("ACTIVE",jdbc.queryForObject("select status from cp_enrollment where id=?",String.class,id));
    }
    @Test void nursingAndQualityCannotMakePhysicianPathwayDecisions()throws Exception{
        String who=person();Long adm=admission(null),id=enrollment(adm);
        for(String role:List.of("NURSE","QUALITY","TECHNICIAN","CASHIER")){
            assertEquals(1005,code("POST","/api/pathways/enrollments/"+id+"/variances",variance(),who,role));
            assertEquals(1005,code("PUT","/api/pathways/enrollments/"+id+"/complete",null,who,role));
            assertEquals(1005,code("POST","/api/pathways/"+template()+"/apply/"+adm,null,who,role));
        }
    }
    @Test void doctorListsAndAggregatesAreFilteredBeforeLimitsAndGrouping()throws Exception{
        String a=person(),b=person();Long own=enrollment(admission(a)),free=enrollment(admission(null)),foreign=enrollment(admission(b));
        var mine=call("GET","/api/pathways/enrollments",null,a,"DOCTOR_OUTP").path("data");
        Set<Long> ids=new HashSet<>();mine.forEach(x->ids.add(x.path("id").asLong()));assertTrue(ids.containsAll(List.of(own,free)));assertFalse(ids.contains(foreign));
        var stats=call("GET","/api/pathways/variance-stats",null,a,"DOCTOR_OUTP").path("data");
        String foreignName=jdbc.queryForObject("select t.name from cp_enrollment e join cp_pathway_template t on t.id=e.template_id where e.id=?",String.class,foreign);
        assertFalse(stats.toString().contains(foreignName));
        assertEquals(0,call("GET","/api/pathways/enrollments",null,"v94ghost","DOCTOR_OUTP").path("data").size());
        for(String role:List.of("NURSE","QUALITY","ADMIN")){
            Set<Long> all=new HashSet<>();call("GET","/api/pathways/enrollments",null,a,role).path("data").forEach(x->all.add(x.path("id").asLong()));assertTrue(all.containsAll(List.of(own,free,foreign)));
        }
    }
    @Test void currentOwnerUnassignedAndAdminRetainPathwayActions()throws Exception{
        String a=person(),b=person();Long adm=admission(a),id=enrollment(adm);
        assertEquals(0,code("POST","/api/pathways/enrollments/"+id+"/variances",variance(),a,"DOCTOR_OUTP"));
        jdbc.update("update inp_admission set doctor_id=? where id=?",uid(b),adm);
        assertEquals(9018,code("PUT","/api/pathways/enrollments/"+id+"/complete",null,a,"DOCTOR_OUTP"));
        assertEquals(0,code("PUT","/api/pathways/enrollments/"+id+"/complete",null,b,"DOCTOR_OUTP"));
        assertEquals(9908,code("POST","/api/pathways/enrollments/"+id+"/variances",variance(),b,"DOCTOR_OUTP"));
        assertEquals(0,code("PUT","/api/pathways/enrollments/"+enrollment(admission(null))+"/complete",null,a,"DOCTOR_OUTP"));
        assertEquals(0,code("PUT","/api/pathways/enrollments/"+enrollment(admission(b))+"/complete",null,a,"ADMIN"));
    }
    @Test void qualityMonitorsButCannotPerformBedsideCatheterWrites()throws Exception{
        String q=person();Long adm=admission(null),id=catheter(adm);
        assertEquals(1005,code("POST","/api/infection-plus/catheters",Map.of("admissionId",adm,"lineType","CVC"),q,"QUALITY"));
        assertEquals(1005,code("POST","/api/infection-plus/catheters/"+id+"/assess?keepLine=true",null,q,"QUALITY"));
        assertEquals(1005,code("PUT","/api/infection-plus/catheters/"+id+"/remove",null,q,"QUALITY"));
        for(String p:List.of("catheters","rate","prevalence"))assertEquals(0,code("GET","/api/infection-plus/"+p,null,q,"QUALITY"));
        assertEquals(0,code("PUT","/api/infection-plus/catheters/"+id+"/infect",null,q,"QUALITY"));
    }
    @Test void catheterLifecycleAndRealIdentityAreEnforced()throws Exception{
        String n=person();Long adm=admission(null),id=catheter(adm);
        assertEquals(1005,code("POST","/api/infection-plus/catheters",Map.of("admissionId",adm,"lineType","CVC"),"v94ghost","NURSE"));
        assertEquals(1005,code("PUT","/api/infection-plus/catheters/"+id+"/remove",null,"v94ghost","NURSE"));
        assertEquals(0,code("POST","/api/infection-plus/catheters/"+id+"/assess?keepLine=true",null,n,"NURSE"));
        assertEquals(0,code("PUT","/api/infection-plus/catheters/"+id+"/remove",null,n,"NURSE"));
        assertEquals(4753,code("POST","/api/infection-plus/catheters/"+id+"/assess?keepLine=true",null,n,"NURSE"));
        jdbc.update("update inp_admission set status='DISCHARGED',discharged_at=now() where id=?",adm);
        assertEquals(4751,code("POST","/api/infection-plus/catheters",Map.of("admissionId",adm,"lineType","CVC"),n,"NURSE"));
    }
}
