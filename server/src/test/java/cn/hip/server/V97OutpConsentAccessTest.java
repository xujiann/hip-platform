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
class V97OutpConsentAccessTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper om;
    static final String BASE="/api/emr/consents";
    String person(String role){String n="v97"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code=?",n,role);return n;}
    Long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    Long patient(){String n="V97"+System.nanoTime();return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,n,n);}
    Long visit(Long p,String owner,int days){Long s=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity) values (1,current_date+?,200) returning id",Long.class,days);return jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status) values (1,?,?,1,?,current_date+?,0,'REGISTERED') returning id",Long.class,p,s,owner==null?null:uid(owner),days);}
    Long consent(Long reg,String status){return jdbc.queryForObject("insert into emr_consent(registration_id,consent_type,title,content,status) values (?,'SELF_PAY','V97','V97私密全文',?) returning id",Long.class,reg,status);}
    JsonNode call(String method,String path,Object body,String name,String...roles)throws Exception{var req=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};req.with(user(name).roles(roles));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));var response=mvc.perform(req).andReturn().getResponse();assertTrue(response.getStatus()==200||response.getStatus()==403,"HTTP "+response.getStatus());return om.readTree(response.getContentAsString(StandardCharsets.UTF_8));}
    int code(String method,String path,Object body,String name,String...roles)throws Exception{return call(method,path,body,name,roles).path("code").asInt();}
    Map<String,Object> draft(Long reg){return Map.of("registrationId",reg,"consentType","SELF_PAY","content","V97");}
    @Test void foreignHistoricalDetailIsDeniedEvenForSamePatient()throws Exception{String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long p=patient(),own=visit(p,a,-2),other=visit(p,b,-2),c=consent(other,"DRAFT");consent(own,"DRAFT");assertEquals(1005,code("GET",BASE+"/"+c,null,a,"DOCTOR_OUTP"));assertEquals(0,call("GET",BASE+"?registrationId="+other,null,a,"DOCTOR_OUTP").path("data").size());}
    @Test void foreignCreateIsDeniedWithoutInsert()throws Exception{String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long reg=visit(patient(),b,-1);long before=jdbc.queryForObject("select count(*) from emr_consent",Long.class);assertEquals(4036,code("POST",BASE,draft(reg),a,"DOCTOR_OUTP"));assertEquals(before,jdbc.queryForObject("select count(*) from emr_consent",Long.class));}
    @Test void foreignPatientSignCannotBeRedirectedWithBodyParent()throws Exception{String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long c=consent(visit(patient(),b,-1),"DRAFT"),own=visit(patient(),a,-1);assertEquals(4036,code("POST",BASE+"/"+c+"/patient-sign",Map.of("patientSign","V97 denied","registrationId",own),a,"DOCTOR_OUTP"));assertEquals("DRAFT",jdbc.queryForObject("select status from emr_consent where id=?",String.class,c));assertNull(jdbc.queryForObject("select patient_sign from emr_consent where id=?",String.class,c));}
    @Test void foreignPhysicianSignatureLeavesAllSignatureFieldsUntouched()throws Exception{String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long c=consent(visit(patient(),b,-1),"PATIENT_SIGNED");assertEquals(4036,code("POST",BASE+"/"+c+"/doctor-sign",Map.of(),a,"DOCTOR_OUTP"));assertEquals("PATIENT_SIGNED",jdbc.queryForObject("select status from emr_consent where id=?",String.class,c));assertNull(jdbc.queryForObject("select doctor_sign from emr_consent where id=?",String.class,c));assertNull(jdbc.queryForObject("select doctor_id from emr_consent where id=?",Long.class,c));}
    @Test void foreignRevokeCannotUseDocumentCreatorAsPermission()throws Exception{String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long c=consent(visit(patient(),b,-1),"DRAFT");jdbc.update("update emr_consent set created_by=? where id=?",uid(a),c);assertEquals(4036,code("PUT",BASE+"/"+c+"/revoke",null,a,"DOCTOR_OUTP"));assertNull(jdbc.queryForObject("select revoked_at from emr_consent where id=?",Object.class,c));}
    @Test void defaultListsFilterBeforeLimitAndReevaluateOwnership()throws Exception{String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long reg=visit(patient(),a,-1),other=visit(patient(),b,-1),visible=consent(reg,"DRAFT");jdbc.update("insert into emr_consent(registration_id,consent_type,title,content) select ?,'SELF_PAY','V97bulk','V97foreign' from generate_series(1,105)",other);var rows=call("GET",BASE,null,a,"DOCTOR_OUTP").path("data");assertEquals(1,rows.size());assertEquals(visible,rows.get(0).path("id").asLong());jdbc.update("update outp_registration set doctor_id=? where id=?",uid(b),reg);assertEquals(1005,code("GET",BASE+"/"+visible,null,a,"DOCTOR_OUTP"));assertEquals(0,code("GET",BASE+"/"+visible,null,b,"DOCTOR_OUTP"));}
    @Test void unknownAndDisabledIdentitiesCannotExploitSharedVisits()throws Exception{String a=person("DOCTOR_OUTP");jdbc.update("update sys_user set enabled=false where username=?",a);Long reg=visit(patient(),null,0),c=consent(reg,"DRAFT");for(String who:List.of(a,"v97ghost"))for(String role:List.of("ADMIN","NURSE","DOCTOR_OUTP")){assertEquals(1005,code("GET",BASE+"/"+c,null,who,role));assertEquals(1005,code("POST",BASE,draft(reg),who,role));}}
    @Test void sharedTodayUnassignedAuthorAndNurseAdminWorkflowsRemainUsable()throws Exception{
        String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP"),n=person("NURSE");
        for(Long reg:List.of(visit(patient(),b,0),visit(patient(),null,-2),visit(patient(),a,-2))){Long c=consent(reg,"DRAFT");assertEquals(0,code("GET",BASE+"/"+c,null,a,"DOCTOR_OUTP"));assertEquals(0,code("POST",BASE+"/"+c+"/patient-sign",Map.of("patientSign","V97虚构"),n,"NURSE"));assertEquals(1005,code("POST",BASE+"/"+c+"/doctor-sign",Map.of(),n,"NURSE"));assertEquals(0,code("POST",BASE+"/"+c+"/doctor-sign",Map.of(),a,"DOCTOR_OUTP"));assertEquals(9114,code("PUT",BASE+"/"+c+"/revoke",null,n,"NURSE"));assertEquals(0,code("PUT",BASE+"/"+c+"/revoke",null,"admin","ADMIN"));}
        Long reg=visit(patient(),b,-2);jdbc.update("insert into outp_emr(registration_id,doctor_id) select id,? from outp_registration where id=?",uid(a),reg);assertEquals(0,code("POST",BASE,draft(reg),a,"DOCTOR_OUTP"));
    }
    @Test void selectorFiltersBeforeLimitAndMissingParentsKeepBusinessErrors()throws Exception{
        String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long own=visit(patient(),a,-2);
        for(int i=0;i<105;i++)visit(patient(),b,-2);
        String date=cn.hip.platform.core.config.BusinessDates.today().minusDays(2).toString();
        var rows=call("GET",BASE+"/registrations?date="+date,null,a,"DOCTOR_OUTP").path("data");assertEquals(1,rows.size());assertEquals(own,rows.get(0).path("id").asLong());
        assertEquals(Set.of("id","reg_no","patient_name","patient_no","visit_date","doctor_id","emr_doctor_id"),om.convertValue(rows.get(0),Map.class).keySet());
        assertEquals(1005,code("GET",BASE+"/registrations",null,a,"QUALITY"));
        assertEquals(4001,code("POST",BASE,draft(-97L),a,"DOCTOR_OUTP"));assertEquals(4001,code("GET",BASE+"?registrationId=-97",null,a,"DOCTOR_OUTP"));assertEquals(9112,code("GET",BASE+"/-97",null,a,"DOCTOR_OUTP"));
    }
    @Test void mixedNursingRoleAllowsCoordinationButNotForeignPhysicianSignature()throws Exception{
        String a=person("DOCTOR_OUTP"),b=person("DOCTOR_OUTP");Long reg=visit(patient(),b,-2),c=consent(reg,"DRAFT");
        assertEquals(0,code("GET",BASE+"/"+c,null,a,"DOCTOR_OUTP","NURSE"));
        assertEquals(0,code("POST",BASE+"/"+c+"/patient-sign",Map.of("patientSign","V97"),a,"DOCTOR_OUTP","NURSE"));
        assertEquals(4036,code("POST",BASE+"/"+c+"/doctor-sign",Map.of(),a,"DOCTOR_OUTP","NURSE"));
        assertEquals(0,code("POST",BASE+"/"+c+"/doctor-sign",Map.of(),b,"DOCTOR_OUTP"));
    }
}
