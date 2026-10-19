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
class V98EmergencyPrivacyTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper om;
    String person(String role) {
        String n="v98"+System.nanoTime();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);
        jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code=?",n,role);
        return n;
    }
    Long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    Long triage(){return jdbc.queryForObject("insert into outp_triage(patient_name,level,chief_complaint) values ('V98虚构',2,'V98私密主诉') returning id",Long.class);}
    Long observation(Long t){return jdbc.queryForObject("insert into er_observation(triage_id,bed_no) values (?,?) returning id",Long.class,t,"V98"+System.nanoTime()%10000000);}
    Long rescue(Long t){return jdbc.queryForObject("insert into er_rescue_record(triage_id,measures,outcome) values (?,'V98私密抢救','ONGOING') returning id",Long.class,t);}
    JsonNode call(String method,String path,Object body,String name,String... roles)throws Exception {
        var req=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};
        req.with(user(name).roles(roles));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
        var response=mvc.perform(req).andReturn().getResponse();
        assertTrue(response.getStatus()==200||response.getStatus()==403,"HTTP "+response.getStatus());
        return om.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    int code(String method,String path,Object body,String name,String... roles)throws Exception{return call(method,path,body,name,roles).path("code").asInt();}
    Map<String,Object> report(boolean anon){return Map.of("type","跌倒","level",2,"occurredOn",cn.hip.platform.core.config.BusinessDates.today().toString(),"description","V98虚构描述","anonymous",anon);}
    @Test void anonymousAndOtherNursesReportsAreNotShared()throws Exception {
        String a=person("NURSE"),b=person("NURSE");
        assertEquals(0,code("POST","/api/quality/adverse-events",report(false),a,"NURSE"));
        Long own=jdbc.queryForObject("select max(id) from qc_adverse_event",Long.class);
        assertEquals(0,code("POST","/api/quality/adverse-events",report(false),b,"NURSE"));
        assertEquals(0,code("POST","/api/quality/adverse-events",report(true),a,"NURSE"));
        Long anon=jdbc.queryForObject("select max(id) from qc_adverse_event",Long.class);
        assertNull(jdbc.queryForObject("select reporter_id from qc_adverse_event where id=?",Long.class,anon));
        var rows=call("GET","/api/quality/adverse-events",null,a,"NURSE").path("data");
        assertEquals(1,rows.size());assertEquals(own,rows.get(0).path("id").asLong());
    }
    @Test void anonymousSubmissionStillRequiresRealEnabledActor()throws Exception {
        String disabled=person("NURSE");jdbc.update("update sys_user set enabled=false where username=?",disabled);
        long before=jdbc.queryForObject("select count(*) from qc_adverse_event",Long.class);
        for(String n:List.of(disabled,"v98ghost"))assertEquals(1005,code("POST","/api/quality/adverse-events",report(true),n,"NURSE"));
        assertEquals(before,jdbc.queryForObject("select count(*) from qc_adverse_event",Long.class));
    }
    @Test void ghostEmergencyActorsCannotReadOrWrite()throws Exception {
        Long t=triage(),o=observation(t);
        assertEquals(1005,code("GET","/api/outpatient/er-observation",null,"v98ghost","ADMIN"));
        assertEquals(1005,code("POST","/api/outpatient/er-observation/"+o+"/notes?note=denied",null,"v98ghost","NURSE"));
        assertEquals(0,jdbc.queryForObject("select count(*) from er_observation_note where observation_id=?",Integer.class,o));
    }
    @Test void nursesCanDocumentButCannotDecideClinicalOutcomes()throws Exception {
        String n=person("NURSE");Long t=triage(),o=observation(t),r=rescue(t);
        assertEquals(0,code("POST","/api/outpatient/er-observation/"+o+"/notes?note=V98",null,n,"NURSE"));
        assertEquals(1005,code("PUT","/api/outpatient/er-observation/"+o+"/end?outcome=ADMITTED",null,n,"NURSE"));
        assertEquals(1005,code("PUT","/api/outpatient/er-rescue/"+r+"/end",Map.of("outcome","DEATH"),n,"NURSE"));
        assertEquals("IN",jdbc.queryForObject("select status from er_observation where id=?",String.class,o));
        assertNull(jdbc.queryForObject("select rescue_end from er_rescue_record where id=?",Object.class,r));
    }
}
