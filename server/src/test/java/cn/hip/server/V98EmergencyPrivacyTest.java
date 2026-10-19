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
    void owner(Long t,String who){jdbc.update("update outp_triage set doctor_id=? where id=?",who==null?null:uid(who),t);}
    @Test void foreignDoctorCannotReadOrWriteActualEmergencyParent()throws Exception {
        String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long t=triage(),o=observation(t),r=rescue(t),own=triage();owner(t,b);owner(own,a);
        jdbc.update("insert into er_observation_note(observation_id,note) values (?,'V98全文')",o);
        assertEquals(1005,code("GET","/api/outpatient/er-observation/"+o+"/notes",null,a,"DOCTOR_OUTP"));
        assertEquals(1005,code("POST","/api/outpatient/er-observation",Map.of("triageId",t,"bedNo","V98denied"),a,"DOCTOR_OUTP"));
        assertEquals(1005,code("POST","/api/outpatient/er-observation/"+o+"/notes?note=denied",Map.of("triageId",own),a,"DOCTOR_OUTP"));
        assertEquals(1005,code("POST","/api/outpatient/er-rescue",Map.of("observationId",o,"triageId",own),a,"DOCTOR_OUTP"));
        assertEquals(1005,code("POST","/api/outpatient/er-rescue",Map.of("triageId",t),a,"DOCTOR_OUTP"));
        assertEquals(1005,code("PUT","/api/outpatient/er-rescue/"+r+"/end",Map.of("outcome","SUCCESS","triageId",own),a,"DOCTOR_OUTP"));
        assertEquals(1005,code("PUT","/api/outpatient/er-observation/"+o+"/end?outcome=DISCHARGED",null,a,"DOCTOR_OUTP"));
        assertEquals(1,jdbc.queryForObject("select count(*) from er_rescue_record where triage_id=?",Integer.class,t));
        assertEquals(1,jdbc.queryForObject("select count(*) from er_observation_note where observation_id=?",Integer.class,o));
        assertNull(jdbc.queryForObject("select rescue_end from er_rescue_record where id=?",Object.class,r));
    }
    @Test void allListsFilterBeforeLimitAndOwnershipChangesImmediately()throws Exception {
        String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long t=triage(),other=triage(),o=observation(t),r=rescue(t);owner(t,a);owner(other,b);
        jdbc.update("insert into er_rescue_record(triage_id,measures) select ?,'V98secret' from generate_series(1,105)",other);
        jdbc.update("insert into er_observation(triage_id,bed_no,status) select ?, 'V98bulk'||x, 'OUT' from generate_series(1,105) x",other);
        for(int i=0;i<105;i++)owner(triage(),b);
        var tri=call("GET","/api/outpatient/triage",null,a,"DOCTOR_OUTP").path("data");assertEquals(1,tri.size());assertEquals(uid(a),tri.get(0).path("doctorId").asLong());
        var obs=call("GET","/api/outpatient/er-observation",null,a,"DOCTOR_OUTP").path("data");assertEquals(1,obs.size());assertEquals(o,obs.get(0).path("id").asLong());
        var rows=call("GET","/api/outpatient/er-rescue",null,a,"DOCTOR_OUTP").path("data");assertEquals(1,rows.size());assertEquals(r,rows.get(0).path("id").asLong());
        assertEquals(0,call("GET","/api/outpatient/er-rescue?triageId="+other,null,a,"DOCTOR_OUTP").path("data").size());
        owner(t,b);assertEquals(1005,code("GET","/api/outpatient/er-observation/"+o+"/notes",null,a,"DOCTOR_OUTP"));
        assertEquals(0,code("PUT","/api/outpatient/er-rescue/"+r+"/end",Map.of("outcome","SUCCESS"),b,"DOCTOR_OUTP"));
    }
    @Test void claimAndCoordinatorAssignmentValidateRealDoctorsAndIgnoreCreationForgery()throws Exception {
        String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP"),n=person("NURSE");Long t=triage();
        assertEquals(0,code("PUT","/api/outpatient/triage/"+t+"/status?status=IN_TREATMENT",null,a,"DOCTOR_OUTP"));
        assertEquals(uid(a),jdbc.queryForObject("select doctor_id from outp_triage where id=?",Long.class,t));
        assertEquals(1005,code("PUT","/api/outpatient/triage/"+t+"/status?status=IN_TREATMENT",null,b,"DOCTOR_OUTP"));
        assertEquals(1005,code("PUT","/api/outpatient/triage/"+t+"/doctor",Map.of("doctorId",uid(b)),a,"DOCTOR_OUTP"));
        assertEquals(9706,code("PUT","/api/outpatient/triage/"+t+"/doctor",Map.of("doctorId",uid(n)),n,"NURSE"));
        assertEquals(0,code("PUT","/api/outpatient/triage/"+t+"/doctor",Map.of("doctorId",uid(b)),n,"NURSE"));
        assertEquals(1005,code("PUT","/api/outpatient/triage/"+t+"/status?status=DISCHARGED",null,a,"DOCTOR_OUTP"));
        var created=call("POST","/api/outpatient/triage",Map.of("patientName","V98","level",1,"chiefComplaint","V98","doctorId",uid(b),"nurseId",uid(b),"status","DISCHARGED"),a,"DOCTOR_OUTP").path("data");
        assertEquals(uid(a),created.path("doctorId").asLong());assertEquals(uid(a),created.path("nurseId").asLong());assertEquals("TRIAGED",created.path("status").asText());
        var doctors=call("GET","/api/outpatient/triage/doctors",null,n,"NURSE").path("data");assertTrue(doctors.findValues("id").stream().anyMatch(x->x.asLong()==uid(b)));assertFalse(doctors.findValues("id").stream().anyMatch(x->x.asLong()==uid(n)));
    }
    @Test void nursingCoordinationDoesNotGrantForeignPhysicianDecisions()throws Exception {
        String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP"),n=person("NURSE");Long t=triage(),o=observation(t),r=rescue(t);owner(t,b);
        assertEquals(0,code("POST","/api/outpatient/er-rescue",Map.of("observationId",o),n,"NURSE"));
        assertEquals(1005,code("POST","/api/outpatient/er-rescue",Map.of("observationId",o,"outcome","DEATH"),n,"NURSE"));
        assertEquals(0,code("GET","/api/outpatient/er-observation/"+o+"/notes",null,a,"NURSE","DOCTOR_OUTP"));
        assertEquals(1005,code("PUT","/api/outpatient/er-observation/"+o+"/end?outcome=DISCHARGED",null,a,"NURSE","DOCTOR_OUTP"));
        assertEquals(1005,code("POST","/api/outpatient/er-rescue",Map.of("triageId",t,"outcome","SUCCESS"),a,"NURSE","DOCTOR_OUTP"));
        assertEquals(0,code("PUT","/api/outpatient/er-rescue/"+r+"/end",Map.of("outcome","SUCCESS"),b,"DOCTOR_OUTP"));
        assertEquals(4566,code("PUT","/api/outpatient/er-rescue/"+r+"/end",Map.of("outcome","DEATH"),b,"DOCTOR_OUTP"));
        assertEquals(0,code("PUT","/api/outpatient/er-observation/"+o+"/end?outcome=DISCHARGED",null,b,"DOCTOR_OUTP"));
        assertEquals(4563,code("POST","/api/outpatient/er-observation/"+o+"/notes?note=denied",null,n,"NURSE"));
        assertEquals(0,code("POST","/api/outpatient/er-rescue",Map.of("observationId",o),n,"NURSE"));
    }
    @Test void anonymousLegacyRowsAndReporterFieldsStayPrivateEvenWhenMalformed()throws Exception {
        String n=person("NURSE"),q=person("QUALITY");Long u=uid(n);
        jdbc.update("insert into qc_adverse_event(type,level,occurred_on,description,anonymous,reporter_id) values ('其他',1,current_date,'V98旧匿名',true,?),('其他',1,current_date,'V98旧无主',false,null)",u);
        assertEquals(0,call("GET","/api/quality/adverse-events",null,n,"NURSE").path("data").size());
        assertEquals(0,code("POST","/api/quality/adverse-events",report(false),n,"NURSE"));Long own=jdbc.queryForObject("select max(id) from qc_adverse_event",Long.class);
        jdbc.update("insert into qc_adverse_event(type,level,occurred_on,description,anonymous) select '其他',1,current_date,'V98bulk',true from generate_series(1,105)");
        var mine=call("GET","/api/quality/adverse-events",null,n,"NURSE").path("data");assertEquals(1,mine.size());assertEquals(own,mine.get(0).path("id").asLong());
        assertEquals(100,call("GET","/api/quality/adverse-events",null,q,"QUALITY").path("data").size());
        assertEquals(1005,code("PUT","/api/quality/adverse-events/"+own+"/handle?note=denied",null,n,"NURSE"));
        assertEquals(0,code("PUT","/api/quality/adverse-events/"+own+"/handle?note=V98",null,q,"QUALITY"));
        assertEquals(9805,code("PUT","/api/quality/adverse-events/"+own+"/handle?note=V98",null,q,"QUALITY"));
        for(String key:List.of("reporter_id","handler_id","created_at"))assertFalse(mine.get(0).has(key));
    }
    @Test void invalidActorsRolesParentsAndInputsPreserveExistingErrors()throws Exception {
        String n=person("NURSE"),a=person("DOCTOR_OUTP");
        jdbc.update("update sys_user set enabled=false where username=?",a);
        for(String path:List.of("/api/outpatient/triage","/api/outpatient/er-observation","/api/outpatient/er-rescue")) {
            assertEquals(1005,code("GET",path,null,a,"DOCTOR_OUTP"));assertEquals(1005,code("GET",path,null,n,"QUALITY"));
        }
        assertEquals(4560,code("POST","/api/outpatient/er-observation",Map.of("triageId",-98,"bedNo","V98"),n,"NURSE"));
        assertEquals(4565,code("POST","/api/outpatient/er-rescue",Map.of("observationId",-98),n,"NURSE"));
        assertEquals(0,call("GET","/api/outpatient/er-observation/-98/notes",null,n,"NURSE").path("data").size());
        var bad=new HashMap<>(report(true));bad.put("deptId",-98);assertEquals(9804,code("POST","/api/quality/adverse-events",bad,n,"NURSE"));
        bad.remove("deptId");bad.put("description"," ");assertEquals(9804,code("POST","/api/quality/adverse-events",bad,n,"NURSE"));
    }
}
