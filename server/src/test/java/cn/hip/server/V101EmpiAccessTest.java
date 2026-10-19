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
class V101EmpiAccessTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper om;
    String person(String role){String n="v101"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code=?",n,role);return n;}
    Long patient(){String n="V101"+System.nanoTime();return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex,phone,insurance_type,blood_type,allergy_history) values (?,?,'U','13800001111','YB_STAFF','A','原过敏史') returning id",Long.class,n,n);}
    JsonNode call(String method,String path,Object body,String name,String...roles)throws Exception{var req="POST".equals(method)?post(path):"PUT".equals(method)?put(path):get(path);req.with(user(name).roles(roles));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));var res=mvc.perform(req).andReturn().getResponse();assertTrue(res.getStatus()==200||res.getStatus()==403,"HTTP "+res.getStatus());return om.readTree(res.getContentAsString(StandardCharsets.UTF_8));}
    @Test void registrationCannotTurnAClientIdIntoAnUnauthorizedUpdate()throws Exception {
        String n=person("NURSE");Long p=patient();var before=jdbc.queryForMap("select * from empi_patient where id=?",p);
        var res=call("POST","/api/patients",Map.of("id",p,"patientNo","FORGED","name","V101冒用建档","sex","M","phone","13900002222","active",false,"createdAt","1970-01-01T00:00:00Z"),n,"NURSE");
        assertEquals(0,res.path("code").asInt());assertNotEquals(p,res.path("data").path("id").asLong());assertEquals(before,jdbc.queryForMap("select * from empi_patient where id=?",p));
    }
    @Test void qualityAndTechnicianCannotRegisterOrRewriteClinicalData()throws Exception {
        Long p=patient();for(String role:List.of("QUALITY","TECHNICIAN")){String who=person(role);assertEquals(1005,call("POST","/api/patients",Map.of("name","V101越岗","sex","U"),who,role).path("code").asInt());assertEquals(1005,call("PUT","/api/patients/"+p,Map.of("allergyHistory","清除"),who,role).path("code").asInt());assertEquals(0,call("GET","/api/patients",null,who,role).path("code").asInt());}
    }
    @Test void cashierCannotSetClinicalRiskFieldsThroughEitherWritePath()throws Exception {
        String who=person("CASHIER");Long p=patient();assertEquals(1005,call("PUT","/api/patients/"+p,Map.of("allergyHistory","无","bloodType","O"),who,"CASHIER").path("code").asInt());assertEquals(1005,call("POST","/api/patients",Map.of("name","V101收费","allergyHistory","无"),who,"CASHIER").path("code").asInt());assertEquals("原过敏史",jdbc.queryForObject("select allergy_history from empi_patient where id=?",String.class,p));
    }
    @Test void addressPatchDoesNotResetInsuranceFromAnEntityDefault()throws Exception {
        String who=person("NURSE");Long p=patient();assertEquals(0,call("PUT","/api/patients/"+p,Map.of("address","V101新地址"),who,"NURSE").path("code").asInt());assertEquals("YB_STAFF",jdbc.queryForObject("select insurance_type from empi_patient where id=?",String.class,p));
    }
    @Test void existingMaskedAndIdentityRulesStayUsable()throws Exception {
        String who=person("NURSE");Long p=patient();assertEquals(2004,call("PUT","/api/patients/"+p,Map.of("phone","138****1111"),who,"NURSE").path("code").asInt());assertEquals(2005,call("PUT","/api/patients/"+p,Map.of("phone","13900002222"),who,"NURSE").path("code").asInt());var res=call("PUT","/api/patients/"+p,Map.of("bloodType","AB"),who,"NURSE");assertEquals(0,res.path("code").asInt());assertEquals("138****1111",res.path("data").path("phone").asText());
    }
}
