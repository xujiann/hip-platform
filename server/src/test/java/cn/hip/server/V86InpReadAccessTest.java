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
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class V86InpReadAccessTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper om;
    private String doctor() {
        String name="v86"+System.nanoTime();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",name,name);
        return name;
    }
    private Long uid(String name) { return jdbc.queryForObject("select id from sys_user where username=?",Long.class,name); }
    private Long patient() {
        return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'V86虚构患者','U') returning id",Long.class,"V86"+System.nanoTime());
    }
    private Long admission(Long patient, Long owner) {
        return jdbc.queryForObject("""
            insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status,admit_at)
            select ?,?,1,ward_id,id,?,'IN_HOSPITAL',now() from inp_bed order by id limit 1 returning id
            """,Long.class,"V86"+System.nanoTime(),patient,owner);
    }
    private Long record(Long adm) {
        Long id=jdbc.queryForObject("""
            insert into inp_medical_record(admission_id,record_type,title,content,round_level,round_opinion)
            values (?,'ROUND','V86','隐私正文','RESIDENT','隐私查房') returning id
            """,Long.class,adm);
        jdbc.update("insert into emr_amendment(emr_type,emr_id,amend_text,reason) values ('INP',?,'隐私补正','测试')",id);
        return id;
    }
    private List<String> paths(Long adm,Long record) {
        String b="/api/inpatient/admissions/"+adm;
        return List.of(b+"/records",b+"/records/rounds",b+"/records/"+record+"/amendments",b+"/workspace",b+"/print/discharge-summary");
    }
    private JsonNode read(String path,String name,String role) throws Exception {
        var res=mvc.perform(get(path).with(user(name).roles(role))).andReturn().getResponse();
        assertTrue(res.getStatus()==200 || res.getStatus()==403, "unexpected HTTP status: "+res.getStatus());
        return om.readTree(res.getContentAsString(StandardCharsets.UTF_8));
    }
    private void codes(Long adm,Long rid,String name,String role,int expected) throws Exception {
        for(String path:paths(adm,rid)) {
            var body=read(path,name,role);
            assertEquals(expected,body.path("code").asInt(),path);
            if(expected!=0) assertTrue(body.path("data").isNull() || body.path("data").isMissingNode(),path);
        }
    }
    @Test void foreignDoctorDeniedEvenForSamePatientWhileOwnerNurseAndAdminCanRead() throws Exception {
        String a=doctor(),b=doctor(); Long p=patient(), own=admission(p,uid(a));
        jdbc.update("update inp_admission set status='DISCHARGED' where id=?",own);
        Long foreign=admission(p,uid(b));
        Long ownRecord=record(own), foreignRecord=record(foreign);
        codes(foreign,foreignRecord,a,"DOCTOR_OUTP",1005);
        codes(own,ownRecord,a,"DOCTOR_OUTP",0);
        codes(foreign,foreignRecord,a,"NURSE",0);
        codes(foreign,foreignRecord,"admin","ADMIN",0);
        assertEquals("隐私补正",read(paths(own,ownRecord).get(2),a,"DOCTOR_OUTP").path("data").get(0).path("amend_text").asText());
    }
    @Test void attendingChangeTakesEffectAndUnassignedDoesNotAuthorizeUnknownIdentity() throws Exception {
        String a=doctor(),b=doctor(); Long adm=admission(patient(),uid(a)),rid=record(adm);
        codes(adm,rid,a,"DOCTOR_OUTP",0);
        jdbc.update("update inp_admission set doctor_id=? where id=?",uid(b),adm);
        codes(adm,rid,a,"DOCTOR_OUTP",1005);
        codes(adm,rid,b,"DOCTOR_OUTP",0);
        jdbc.update("update inp_admission set doctor_id=null where id=?",adm);
        codes(adm,rid,a,"DOCTOR_OUTP",0);
        codes(adm,rid,"v86-nonexistent-user","DOCTOR_OUTP",1005);
    }
    @Test void amendmentRecordMustBelongToPathEvenForAdminAndNurse() throws Exception {
        String a=doctor();Long one=admission(patient(),uid(a)),two=admission(patient(),uid(a)),rid=record(two);
        String path="/api/inpatient/admissions/"+one+"/records/"+rid+"/amendments";
        for(String role:List.of("ADMIN","NURSE","DOCTOR_OUTP")) {
            var response=read(path,a,role);
            assertEquals(9102,response.path("code").asInt());
            assertTrue(response.path("data").isNull() || response.path("data").isMissingNode());
        }
        assertEquals(9102,read("/api/inpatient/admissions/"+one+"/records/9223372036854775807/amendments",a,"ADMIN").path("code").asInt());
    }
}
