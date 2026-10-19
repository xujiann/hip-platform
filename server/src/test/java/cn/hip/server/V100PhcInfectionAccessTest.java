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
    @Test void recorderIsRealAndAllListFormsFilterBeforeCap()throws Exception {
        String a=person("NURSE"),b=person("NURSE"),q=person("QUALITY");Long p=patient();
        var body=new HashMap<String,Object>(phc(p,"V100本人"));body.put("recorderId",uid(b));
        assertEquals(0,call("POST","/api/phc/records",body,a,"NURSE").path("code").asInt());
        jdbc.update("insert into phc_record(patient_id,record_type,content,recorder_id) select ?,'EDUCATION','V100他人',? from generate_series(1,105)",p,uid(b));
        for(String path:List.of("/api/phc/records","/api/phc/records?patientId="+p)){
            var rows=call("GET",path,null,a,"NURSE").path("data");assertEquals(1,rows.size());assertEquals(uid(a),rows.get(0).path("recorder_id").asLong());assertEquals(a,rows.get(0).path("recorder_name").asText());
        }
        assertEquals(100,call("GET","/api/phc/records",null,q,"QUALITY").path("data").size());
        assertEquals(106,call("GET","/api/phc/records?patientId="+p,null,q,"QUALITY").path("data").size());
        assertEquals(1,call("GET","/api/phc/stats",null,a,"NURSE").path("data").get(0).path("cnt").asInt());
        assertEquals(106,call("GET","/api/phc/stats",null,a,"NURSE","QUALITY").path("data").get(0).path("cnt").asInt());
        assertEquals(0,call("POST","/api/phc/records",phc(p,"兼岗"),a,"NURSE","QUALITY").path("code").asInt());
        assertEquals(0,call("POST","/api/phc/records",phc(p,"管理员"),"admin","ADMIN").path("code").asInt());
    }
    @Test void preventiveValidationRejectsWithoutPartialRows()throws Exception {
        String n=person("NURSE");Long p=patient();long before=jdbc.queryForObject("select count(*) from phc_record",Long.class);
        for(var bad:List.of(Map.of("content",""),Map.of("content","x".repeat(501)),Map.of("occurredDate","bad"),Map.of("occurredDate",cn.hip.platform.core.config.BusinessDates.today().plusDays(1).toString()))){
            var body=new HashMap<String,Object>(phc(p,"V100"));body.putAll(bad);assertEquals(4612,call("POST","/api/phc/records",body,n,"NURSE").path("code").asInt());
        }
        var bad=new HashMap<String,Object>(phc(p,"V100"));bad.remove("recordType");assertEquals(4610,call("POST","/api/phc/records",bad,n,"NURSE").path("code").asInt());
        jdbc.update("update empi_patient set active=false where id=?",p);assertEquals(4611,call("POST","/api/phc/records",phc(p,"V100"),n,"NURSE").path("code").asInt());
        assertEquals(before,jdbc.queryForObject("select count(*) from phc_record",Long.class));
    }
    @Test void retrospectiveInfectionUsesActualAdmissionDatesAndRealReporter()throws Exception {
        String n=person("NURSE"),q=person("QUALITY");Long a=admission();jdbc.update("update inp_admission set admit_at=now()-interval '10 days',status='DISCHARGED',discharged_at=now()-interval '3 days' where id=?",a);
        var body=new HashMap<String,Object>(infection(a));body.put("confirmedOn",cn.hip.platform.core.config.BusinessDates.today().minusDays(5).toString());body.put("reporterId",uid(q));body.put("note","V100追溯");
        assertEquals(0,call("POST","/api/quality/infections",body,n,"NURSE").path("code").asInt());
        var row=call("GET","/api/quality/infections",null,q,"QUALITY").path("data").path("cases").get(0);assertEquals(uid(n),row.path("reporter_id").asLong());assertEquals(n,row.path("reporter_name").asText());
        for(String date:List.of("bad",cn.hip.platform.core.config.BusinessDates.today().toString(),cn.hip.platform.core.config.BusinessDates.today().minusDays(11).toString())){body.put("confirmedOn",date);assertEquals(9806,call("POST","/api/quality/infections",body,n,"NURSE").path("code").asInt());}
        body.put("confirmedOn",cn.hip.platform.core.config.BusinessDates.today().minusDays(5).toString());body.put("admissionId",-1);assertEquals(9003,call("POST","/api/quality/infections",body,n,"NURSE").path("code").asInt());
        assertEquals(1,jdbc.queryForObject("select count(*) from qc_infection_case where admission_id=?",Integer.class,a));
    }
    @Test void infectionFieldValidationAndForbiddenRolesAreStable()throws Exception {
        String n=person("NURSE");Long a=admission();
        for(var bad:List.of(Map.of("site",""),Map.of("site","x".repeat(65)),Map.of("pathogen","x".repeat(65)),Map.of("note","x".repeat(501)),Map.of("confirmedOn",cn.hip.platform.core.config.BusinessDates.today().plusDays(1).toString()))){var body=new HashMap<String,Object>(infection(a));body.putAll(bad);assertEquals(9806,call("POST","/api/quality/infections",body,n,"NURSE").path("code").asInt());}
        assertEquals(0,jdbc.queryForObject("select count(*) from qc_infection_case where admission_id=?",Integer.class,a));
        for(String role:List.of("DOCTOR_OUTP","CASHIER","TECHNICIAN","OPERATION")){
            for(String path:List.of("/api/phc/records","/api/phc/stats","/api/quality/infections"))assertEquals(1005,call("GET",path,null,n,role).path("code").asInt());
            assertEquals(1005,call("POST","/api/phc/records",phc(patient(),"越岗"),n,role).path("code").asInt());assertEquals(1005,call("POST","/api/quality/infections",infection(a),n,role).path("code").asInt());
        }
    }
    @Test void catheterGeneratedCasesAlsoHaveAnActualReporter()throws Exception {
        String q=person("QUALITY");Long a=admission();Long c=jdbc.queryForObject("insert into inf_catheter(admission_id,line_type) values (?,'VENT') returning id",Long.class,a);
        var res=mvc.perform(put("/api/infection-plus/catheters/"+c+"/infect").with(user(q).roles("QUALITY"))).andReturn().getResponse();assertEquals(0,om.readTree(res.getContentAsString(StandardCharsets.UTF_8)).path("code").asInt());
        assertEquals(uid(q),jdbc.queryForObject("select reporter_id from qc_infection_case where admission_id=?",Long.class,a));
    }
}
