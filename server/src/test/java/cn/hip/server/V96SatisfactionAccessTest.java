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

/** HTTP and persisted-data checks, not mocked SQL or a role-only permission matrix. */
@SpringBootTest @AutoConfigureMockMvc @Transactional
class V96SatisfactionAccessTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper om;
    static final String BASE="/api/patientcare/satisfaction";
    String person(String...roles){
        String n="v96"+System.nanoTime();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);
        for(String role:roles)jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code=?",n,role);
        return n;
    }
    long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    long count(){return jdbc.queryForObject("select count(*) from pc_satisfaction",Long.class);}
    JsonNode call(String method,String path,Object body,String who,String...roles)throws Exception{
        var req="POST".equals(method)?post(path):get(path);
        req.with(user(who).roles(roles));
        if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
        var response=mvc.perform(req).andReturn().getResponse();
        assertTrue(response.getStatus()==200||response.getStatus()==403,path+" HTTP "+response.getStatus());
        return om.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode stats(String who,String...roles)throws Exception{
        var response=call("GET",BASE+"/stats",null,who,roles);assertEquals(0,response.path("code").asInt());return response.path("data");
    }
    void submit(String who,int score,String comment,String...roles)throws Exception{
        assertEquals(0,call("POST",BASE,Map.of("score",score,"comment",comment,"source","PORTAL"),who,roles).path("code").asInt());
    }
    @Test void clinicianAggregatesAndCommentsContainOnlyTheirOwnSubmissions()throws Exception{
        String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");
        submit(a,2,"V96 own2","DOCTOR_OUTP");submit(a,4,"V96 own4","DOCTOR_OUTP");submit(b,1,"V96 foreign","DOCTOR_OUTP");
        jdbc.update("insert into pc_satisfaction(score,comment) values (5,'V96 legacy')");
        var result=stats(a,"DOCTOR_OUTP");
        assertEquals(2,result.path("count").asInt());assertEquals(3,result.path("avgScore").asDouble());assertEquals(2,result.path("recent").size());
        assertFalse(result.toString().contains("V96 foreign"));assertFalse(result.toString().contains("V96 legacy"));
    }
    @Test void nursePeersAndSamePatientDoNotGrantEachOtherAccess()throws Exception{
        String a=person("NURSE"),b=person("NURSE");
        Long p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,"V96"+System.nanoTime(),"V96虚构患者");
        for(String who:List.of(a,b))assertEquals(0,call("POST",BASE,Map.of("patientId",p,"score",5,"comment",who),who,"NURSE").path("code").asInt());
        assertEquals(1,stats(a,"NURSE").path("count").asInt());assertEquals(a,stats(a,"NURSE").path("recent").get(0).path("comment").asText());
        assertEquals(0,stats(person("DOCTOR_OUTP"),"DOCTOR_OUTP").path("count").asInt());
    }
    @Test void qualityReadsAllIncludingLegacyButCannotSubmit()throws Exception{
        String q=person("QUALITY");long before=count();
        assertEquals(1005,call("POST",BASE,Map.of("score",5,"comment","V96 forbidden"),q,"QUALITY").path("code").asInt());
        assertEquals(before,count());
        jdbc.update("insert into pc_satisfaction(score,comment) values (3,'V96 legacy')");
        assertEquals(before+1,stats(q,"QUALITY").path("count").asLong());assertTrue(stats(q,"QUALITY").toString().contains("V96 legacy"));
    }
    @Test void unknownAndDisabledActorsCannotReadOrSubmitEvenWithAdministrativeAuthority()throws Exception{
        String disabled=person("ADMIN");jdbc.update("update sys_user set enabled=false where username=?",disabled);long before=count();
        for(String name:List.of(disabled,"v96ghost"))for(String role:List.of("ADMIN","DOCTOR_OUTP","NURSE","QUALITY")){
            assertEquals(1005,call("GET",BASE+"/stats",null,name,role).path("code").asInt());
            assertEquals(1005,call("POST",BASE,Map.of("score",4),name,role).path("code").asInt());
        }
        assertEquals(before,count());
    }
    @Test void ownershipCannotBeForgedAndAnonymousPatientRemainsSupported()throws Exception{
        String a=person("DOCTOR_OUTP"),b=person("NURSE");
        assertEquals(0,call("POST",BASE,Map.of("score",5,"comment","V96 attributed","creatorId",uid(b),"creator_id",uid(b),"source","PORTAL"),a,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(0,stats(b,"NURSE").path("count").asInt());assertEquals(1,stats(a,"DOCTOR_OUTP").path("count").asInt());
        var row=jdbc.queryForMap("select patient_id,source,creator_id from pc_satisfaction where comment='V96 attributed'");
        assertNull(row.get("patient_id"));assertEquals("PORTAL",row.get("source"));assertEquals(uid(a),((Number)row.get("creator_id")).longValue());
    }
    @Test void recentLimitDoesNotDiscardOlderAuthorizedRowsAndFiltersCannotExpandScope()throws Exception{
        String a=person("NURSE"),b=person("DOCTOR_OUTP");
        submit(a,5,"V96 visible","NURSE");for(int i=0;i<22;i++)submit(b,1,"V96 hidden"+i,"DOCTOR_OUTP");
        var response=call("GET",BASE+"/stats?creatorId="+uid(b)+"&source=PORTAL&patientId=1",null,a,"NURSE");
        assertEquals(1,response.path("data").path("count").asInt());assertEquals(5,response.path("data").path("avgScore").asDouble());assertEquals("V96 visible",response.path("data").path("recent").get(0).path("comment").asText());
        var result=stats(b,"DOCTOR_OUTP");assertEquals(22,result.path("count").asInt());assertEquals(20,result.path("recent").size());assertEquals("V96 hidden21",result.path("recent").get(0).path("comment").asText());
    }
    @Test void administratorAndMixedRolesRetainOnlyTheirActualCapabilities()throws Exception{
        String mixed=person("QUALITY","NURSE"),a=person("DOCTOR_OUTP");
        submit(a,3,"V96 physician","DOCTOR_OUTP");submit(mixed,4,"V96 mixed","QUALITY","NURSE");submit("admin",5,"V96 admin","ADMIN");
        assertEquals(count(),stats(mixed,"QUALITY","NURSE").path("count").asLong());assertEquals(count(),stats("admin","ADMIN").path("count").asLong());
        for(String role:List.of("CASHIER","TECHNICIAN","INTERFACE")){
            String n=person(role);long before=count();assertEquals(1005,call("GET",BASE+"/stats",null,n,role).path("code").asInt());assertEquals(1005,call("POST",BASE,Map.of("score",5),n,role).path("code").asInt());assertEquals(before,count());
        }
        assertEquals(0,stats(person("NURSE"),"NURSE").path("avgScore").asDouble());
    }
    @Test void invalidScoresRetainExistingBusinessCodeAndNeverWrite()throws Exception{
        String a=person("NURSE");long before=count();
        for(Map<String,Object> body:List.<Map<String,Object>>of(Map.of(),Map.of("score",0),Map.of("score",6)))assertEquals(9902,call("POST",BASE,body,a,"NURSE").path("code").asInt());
        assertEquals(before,count());submit(a,1,"V96 lower","NURSE");submit(a,5,"V96 upper","NURSE");
    }
}
