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
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class V90ConsentAccessTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;
    private String doctor() {
        String n="v90"+System.nanoTime();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);
        jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code='DOCTOR_OUTP'",n);
        return n;
    }
    private Long uid(String n) {return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    private Long admission(Long owner) {
        String s="V90"+System.nanoTime();
        Long p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,s,s);
        return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status,admit_at) select ?,?,1,ward_id,id,?,'IN_HOSPITAL',now() from inp_bed order by id limit 1 returning id",Long.class,s,p,owner);
    }
    private Long consent(Long adm,String status) {return jdbc.queryForObject("insert into emr_consent(admission_id,consent_type,title,content,status) values (?,'SURGERY','V90','V90私密全文',?) returning id",Long.class,adm,status);}
    private JsonNode call(String method,String path,Object body,String n,String role) throws Exception {
        var req=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};
        req.with(user(n).roles(role));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
        var r=mvc.perform(req).andReturn().getResponse();assertTrue(r.getStatus()==200||r.getStatus()==403,r.getContentAsString());
        return om.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    @Test void foreignDoctorCannotReadCreateSignOrRevoke() throws Exception {
        String a=doctor(),b=doctor();Long adm=admission(uid(a)),draft=consent(adm,"DRAFT"),pending=consent(adm,"PATIENT_SIGNED");
        assertEquals(1005,call("GET","/api/emr/consents/"+draft,null,b,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(1005,call("GET","/api/emr/consents?admissionId="+adm,null,b,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(9018,call("POST","/api/emr/consents",Map.of("admissionId",adm,"consentType","SURGERY","content","x"),b,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(9018,call("POST","/api/emr/consents/"+draft+"/patient-sign",Map.of("patientSign","x"),b,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(9018,call("POST","/api/emr/consents/"+pending+"/doctor-sign",Map.of(),b,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(9018,call("PUT","/api/emr/consents/"+draft+"/revoke",null,b,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(2,jdbc.queryForObject("select count(*) from emr_consent where admission_id=?",Integer.class,adm));
        assertNull(jdbc.queryForObject("select patient_sign from emr_consent where id=?",String.class,draft));
        assertNull(jdbc.queryForObject("select doctor_sign from emr_consent where id=?",String.class,pending));
    }
    @Test void ownerNurseAdminAndUnassignedRemainUsableButNurseCannotDoctorSign() throws Exception {
        String a=doctor();Long adm=admission(uid(a));
        for(String role:new String[]{"DOCTOR_OUTP","NURSE","ADMIN"}) {
            Long c=consent(adm,"DRAFT");
            assertEquals(0,call("GET","/api/emr/consents/"+c,null,a,role).path("code").asInt());
            assertEquals(0,call("POST","/api/emr/consents/"+c+"/patient-sign",Map.of("patientSign","虚构患者"),a,role).path("code").asInt());
            assertEquals(role.equals("NURSE")?1005:0,call("POST","/api/emr/consents/"+c+"/doctor-sign",Map.of(),a,role).path("code").asInt());
        }
        Long c=consent(admission(null),"DRAFT");
        assertEquals(0,call("GET","/api/emr/consents/"+c,null,a,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(1005,call("GET","/api/emr/consents/"+c,null,"v90-missing","DOCTOR_OUTP").path("code").asInt());
        assertEquals(9018,call("POST","/api/emr/consents/"+c+"/patient-sign",Map.of("patientSign","x"),"v90-missing","DOCTOR_OUTP").path("code").asInt());
    }
    @Test void defaultListFiltersBeforeLimitAndHandoffTakesEffect() throws Exception {
        String a=doctor(),b=doctor();Long own=admission(uid(a)),other=admission(uid(b)),c=consent(own,"DRAFT");
        for(int i=0;i<105;i++)consent(other,"DRAFT");
        var data=call("GET","/api/emr/consents",null,a,"DOCTOR_OUTP").path("data");
        assertTrue(data.findValues("id").stream().anyMatch(n->n.asLong()==c));
        assertTrue(data.findValues("admission_id").stream().noneMatch(n->n.asLong()==other));
        jdbc.update("update inp_admission set doctor_id=? where id=?",uid(b),own);
        assertEquals(1005,call("GET","/api/emr/consents/"+c,null,a,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(0,call("GET","/api/emr/consents/"+c,null,b,"DOCTOR_OUTP").path("code").asInt());
    }
    @Test void dualScopeCreationIsRejectedAndMissingConsentKeepsCode() throws Exception {
        String a=doctor();Long adm=admission(uid(a));
        assertEquals(9110,call("POST","/api/emr/consents",Map.of("admissionId",adm,"registrationId",1,"consentType","SURGERY","content","x"),a,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(9112,call("GET","/api/emr/consents/9223372036854775807",null,a,"DOCTOR_OUTP").path("code").asInt());
    }
    @Test void outpatientCompatibilityAndHistoricalDualScopeCannotBypassAdmission() throws Exception {
        String a=doctor(),b=doctor();Long own=admission(uid(a)),foreign=admission(uid(b));
        Long patient=jdbc.queryForObject("select patient_id from inp_admission where id=?",Long.class,own);
        Long schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity) values (1,current_date,10) returning id",Long.class);
        Long reg=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (1,?,?,1,current_date,0,'REGISTERED') returning id",Long.class,patient,schedule);
        Long outpatient=jdbc.queryForObject("insert into emr_consent(registration_id,consent_type,title,content) values (?,'SELF_PAY','V90门诊','V90') returning id",Long.class,reg);
        Long mixed=consent(foreign,"DRAFT");jdbc.update("update emr_consent set registration_id=? where id=?",reg,mixed);
        var rows=call("GET","/api/emr/consents?registrationId="+reg,null,a,"DOCTOR_OUTP").path("data");
        assertEquals(1,rows.size());assertEquals(outpatient.longValue(),rows.get(0).path("id").asLong());
        assertEquals(1005,call("GET","/api/emr/consents/"+mixed,null,a,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(9018,call("POST","/api/emr/consents/"+mixed+"/patient-sign",Map.of("patientSign","x"),a,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(0,call("GET","/api/emr/consents/"+outpatient,null,a,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(0,call("POST","/api/emr/consents",Map.of("registrationId",reg,"consentType","SELF_PAY","content","门诊兼容"),a,"DOCTOR_OUTP").path("code").asInt());
        jdbc.update("update inp_admission set patient_id=? where id=?",patient,foreign);
        assertEquals(1005,call("GET","/api/emr/consents/"+mixed,null,a,"DOCTOR_OUTP").path("code").asInt());
    }

}
