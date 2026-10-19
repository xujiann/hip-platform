package cn.hip.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class V100PhcInfectionAccessTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper om;
    String person(String role){String n="v100"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code=?",n,role);return n;}
    Long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    Long patient(){String n="V100"+System.nanoTime();return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,n,n);}
    Long admission(){return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id) select ?,?,1,ward_id,id from inp_bed order by id limit 1 returning id",Long.class,"V100"+System.nanoTime(),patient());}
    JsonNode call(String method,String path,Object body,String name,String... roles)throws Exception{var req="POST".equals(method)?post(path):get(path);req.with(user(name).roles(roles));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));var res=mvc.perform(req).andReturn().getResponse();assertTrue(res.getStatus()==200||res.getStatus()==403,"HTTP "+res.getStatus());return om.readTree(res.getContentAsString(StandardCharsets.UTF_8));}
    Map<String,Object> phc(Long p,String text){return Map.of("patientId",p,"recordType","EDUCATION","content",text);}
    Map<String,Object> infection(Long a){return Map.of("admissionId",a,"site","呼吸道","confirmedOn",cn.hip.platform.core.config.BusinessDates.today().toString());}
    @Test void qualityMayReadButCannotPerformPreventiveCare()throws Exception{String q=person("QUALITY");long before=jdbc.queryForObject("select count(*) from phc_record",Long.class);assertEquals(1005,call("POST","/api/phc/records",phc(patient(),"质控越岗"),q,"QUALITY").path("code").asInt());assertEquals(before,jdbc.queryForObject("select count(*) from phc_record",Long.class));assertEquals(0,call("GET","/api/phc/stats",null,q,"QUALITY").path("code").asInt());}
    @Test void nurseCannotReadAnotherNursesRecordsOrLegacyNullOwner()throws Exception{String a=person("NURSE"),b=person("NURSE");Long p=patient();assertEquals(0,call("POST","/api/phc/records",phc(p,"他人保健文本"),b,"NURSE").path("code").asInt());jdbc.update("insert into phc_record(patient_id,record_type,content) values (?,'EDUCATION','历史未归属')",p);assertEquals(0,call("GET","/api/phc/records?patientId="+p,null,a,"NURSE").path("data").size());}
    @Test void statsCannotRevealAnotherNursesCounts()throws Exception{String a=person("NURSE"),b=person("NURSE");call("POST","/api/phc/records",phc(patient(),"他人统计"),b,"NURSE");assertEquals(0,call("GET","/api/phc/stats",null,a,"NURSE").path("data").size());}
    @Test void unknownAndDisabledActorsCannotUseEitherDomain()throws Exception{String n=person("NURSE");jdbc.update("update sys_user set enabled=false where username=?",n);Long a=admission();for(String who:List.of("v100ghost",n))for(String role:List.of("ADMIN","QUALITY","NURSE")){assertEquals(1005,call("GET","/api/phc/records",null,who,role).path("code").asInt());assertEquals(1005,call("GET","/api/phc/stats",null,who,role).path("code").asInt());assertEquals(1005,call("GET","/api/quality/infections",null,who,role).path("code").asInt());assertEquals(1005,call("POST","/api/quality/infections",infection(a),who,role).path("code").asInt());}}
    @Test void existingAdminAndNursingPositivePathsStayUsable()throws Exception{String n=person("NURSE");assertEquals(0,call("POST","/api/phc/records",phc(patient(),"正常宣教"),n,"NURSE").path("code").asInt());assertEquals(0,call("POST","/api/quality/infections",infection(admission()),n,"NURSE").path("code").asInt());assertEquals(0,call("GET","/api/quality/infections",null,"admin","ADMIN").path("code").asInt());}
}
