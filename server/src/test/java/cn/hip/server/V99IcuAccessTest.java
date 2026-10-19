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
class V99IcuAccessTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper om;
    static final String BASE="/api/inpatient/icu-records";
    String person(String role){String n="v99"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code=?",n,role);return n;}
    Long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    Long admission(String who){String n="V99"+System.nanoTime();Long p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,n,n);return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id) select ?,?,1,ward_id,id,? from inp_bed order by id limit 1 returning id",Long.class,n,p,who==null?null:uid(who));}
    Long seed(Long a){return jdbc.queryForObject("insert into inp_icu_record(admission_id,intake_ml,output_ml,note) values (?,700,300,'V99私密观察') returning id",Long.class,a);}
    JsonNode call(String method,String path,Object body,String name,String... roles)throws Exception{var req="POST".equals(method)?post(path):get(path);req.with(user(name).roles(roles));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));var res=mvc.perform(req).andReturn().getResponse();assertTrue(res.getStatus()==200||res.getStatus()==403,"HTTP "+res.getStatus());return om.readTree(res.getContentAsString(StandardCharsets.UTF_8));}
    int code(String method,String path,Object body,String who,String...roles)throws Exception{return call(method,path,body,who,roles).path("code").asInt();}
    @Test void foreignDoctorCannotReadIcuText()throws Exception{String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long adm=admission(b);seed(adm);assertEquals(1005,code("GET",BASE+"?admissionId="+adm,null,a,"DOCTOR_OUTP"));}
    @Test void foreignDoctorCannotReadFluidAggregate()throws Exception{String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long adm=admission(b);seed(adm);assertEquals(1005,code("GET",BASE+"/balance?admissionId="+adm,null,a,"DOCTOR_OUTP"));}
    @Test void foreignWriteCannotUseForgedRecorderOrAnotherAdmission()throws Exception{String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long other=admission(b);long before=jdbc.queryForObject("select count(*) from inp_icu_record",Long.class);assertEquals(9018,code("POST",BASE,Map.of("admissionId",other,"note","denied","recorderId",uid(b)),a,"DOCTOR_OUTP"));assertEquals(before,jdbc.queryForObject("select count(*) from inp_icu_record",Long.class));}
    @Test void ghostsCannotUseSharedOrNursingScopes()throws Exception{Long adm=admission(null);for(String role:List.of("ADMIN","NURSE","DOCTOR_OUTP")){assertEquals(1005,code("GET",BASE+"?admissionId="+adm,null,"v99ghost",role));assertEquals(1005,code("POST",BASE,Map.of("admissionId",adm),"v99ghost",role));}}
    @Test void existingNursingWorkflowAndGcsValidationStayUsable()throws Exception{String a=person("DOCTOR_OUTP"),n=person("NURSE");Long adm=admission(a);assertEquals(4571,code("POST",BASE,Map.of("admissionId",adm,"gcs",20),n,"NURSE"));assertEquals(0,code("POST",BASE,Map.of("admissionId",adm,"gcs",15,"intakeMl",700,"outputMl",300),n,"NURSE"));var rows=call("GET",BASE+"?admissionId="+adm,null,a,"DOCTOR_OUTP").path("data");assertEquals(1,rows.size());assertEquals(uid(n),rows.get(0).path("recorder_id").asLong());assertEquals(400,call("GET",BASE+"/balance?admissionId="+adm,null,a,"DOCTOR_OUTP").path("data").path("balance").asInt());}
}
