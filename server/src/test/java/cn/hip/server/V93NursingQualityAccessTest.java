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
class V93NursingQualityAccessTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;
    String person(){String n="v93"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);return n;}
    Long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    Long admission(){String n="V93"+System.nanoTime();Long p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,n,n);return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,status,admit_at) select ?,?,1,ward_id,id,'IN_HOSPITAL',now() from inp_bed order by id limit 1 returning id",Long.class,n,p);}
    Long record(Long adm,Long nurse){return jdbc.queryForObject("insert into nur_record(admission_id,record_kind,observation,nurse_id) values (?,'OBSERVE','original',?) returning id",Long.class,adm,nurse);}
    Map<String,Object> body(Long adm){return Map.of("admissionId",adm,"recordKind","OBSERVE","observation","changed");}
    JsonNode call(String method,String path,Object body,String name,String...roles)throws Exception{
        var req=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};req.with(user(name).roles(roles));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
        var r=mvc.perform(req).andReturn().getResponse();assertTrue(r.getStatus()==200||r.getStatus()==403,path+" HTTP "+r.getStatus());return om.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    int code(String method,String path,Object body,String name,String...roles)throws Exception{return call(method,path,body,name,roles).path("code").asInt();}
    Long plan(){return jdbc.queryForObject("insert into qc_check_plan(title,standard) values ('V93','V93') returning id",Long.class);}
    Long card(Long adm,String status){return jdbc.queryForObject("insert into idc_report_card(patient_id,disease_name,card_class,status,reporter) select patient_id,'V93','B',?,'admin' from inp_admission where id=? returning id",Long.class,status,adm);}
    @Test void qualityCannotPerformNursingWrites()throws Exception{
        String q=person();Long adm=admission();Long handover=jdbc.queryForObject("insert into shift_handover(dept_id,shift_type,summary,author) values (1,'DAY','V93','admin') returning id",Long.class);
        assertEquals(1005,code("PUT","/api/inpatient/admissions/"+adm+"/care-level?level=特级",null,q,"QUALITY"));
        assertEquals(1005,code("PUT","/api/inpatient/admissions/"+adm+"/care-level/change",Map.of("level","特级","reason","V93"),q,"QUALITY"));
        assertEquals(1005,code("POST","/api/nursing/risk-assess",Map.of("admissionId",adm,"assessType","BRADEN","score",12),q,"QUALITY"));
        assertEquals(1005,code("POST","/api/nursing/shifts",Map.of("deptId",1,"nurseName",q,"shiftDate","2026-10-18","shiftType","DAY"),q,"QUALITY"));
        assertEquals(1005,code("POST","/api/nursing/shift-types",Map.of("code","V93","name","V93","startTime","08:00","endTime","16:00"),q,"QUALITY"));
        assertEquals(1005,code("POST","/api/nursing/handovers",Map.of("deptId",1,"shiftType","DAY","summary","V93"),q,"QUALITY"));
        assertEquals(1005,code("PUT","/api/nursing/handovers/"+handover+"/receive",null,q,"QUALITY"));
        assertEquals(0,jdbc.queryForObject("select count(*) from nur_care_level_log where admission_id=?",Integer.class,adm));
        assertEquals(0,jdbc.queryForObject("select count(*) from nur_risk_assess where admission_id=?",Integer.class,adm));
        assertNull(jdbc.queryForObject("select receiver_id from shift_handover where id=?",Long.class,handover));
    }
    @Test void nurseCannotScoreApproveArchiveOrHandleQualityCases()throws Exception{
        String n=person();Long adm=admission(),plan=plan(),reported=card(adm,"REPORTED"),reviewed=card(adm,"REVIEWED");
        jdbc.update("update inp_admission set status='DISCHARGED',discharged_at=now() where id=?",adm);
        Long event=jdbc.queryForObject("insert into qc_adverse_event(type,level,occurred_on,description) values ('跌倒',3,current_date,'V93') returning id",Long.class);
        assertEquals(1005,code("POST","/api/nursing/qc-scores",Map.of("deptId",1,"item","V93","score",90),n,"NURSE"));
        assertEquals(1005,code("POST","/api/qc-check/plans",Map.of("title","V93","standard","V93"),n,"NURSE"));
        assertEquals(1005,code("POST","/api/qc-check/scores",Map.of("planId",plan,"target","V93","score",90),n,"NURSE"));
        assertEquals(1005,code("PUT","/api/infection/cards/"+reported+"/review?approve=true",null,n,"NURSE"));
        assertEquals(1005,code("PUT","/api/infection/cards/"+reviewed+"/submit",null,n,"NURSE"));
        assertEquals(1005,code("PUT","/api/inpatient/admissions/"+adm+"/archive",null,n,"NURSE"));
        assertEquals(1005,code("PUT","/api/quality/adverse-events/"+event+"/handle?note=V93",null,n,"NURSE"));
        assertEquals("REPORTED",jdbc.queryForObject("select status from idc_report_card where id=?",String.class,reported));
        assertEquals("NEW",jdbc.queryForObject("select status from qc_adverse_event where id=?",String.class,event));
        assertEquals(false,jdbc.queryForObject("select archived from inp_admission where id=?",Boolean.class,adm));
        assertEquals(0,jdbc.queryForObject("select count(*) from qc_check_score where plan_id=?",Integer.class,plan));
    }
    @Test void globalQualityReportsAndExportsRequireQualityDuty()throws Exception{
        String n=person();Long adm=admission();
        var paths=List.of("/api/quality/emr-timeliness","/api/quality/emr-timeliness/board","/api/quality/med-records","/api/quality/mr-workqueue","/api/inpatient/admissions/"+adm+"/front-page","/api/quality/mr-qc/summary/"+adm);
        for(String p:paths){assertEquals(1005,code("GET",p,null,n,"NURSE"),p);assertEquals(0,code("GET",p,null,n,"QUALITY"),p);}
        assertEquals(1005,code("GET","/api/quality/mr-qc/summary/"+adm,null,n,"DOCTOR"));
        for(String p:List.of("/api/quality/mr-qc/stats?from=2026-10-01&to=2026-10-18","/api/quality/mr-qc/stats.csv?from=2026-10-01&to=2026-10-18","/api/quality/mr-qc/deduct-items.csv?from=2026-10-01&to=2026-10-18"))
            for(String role:List.of("NURSE","DOCTOR_OUTP","TECHNICIAN","CASHIER"))assertEquals(1005,code("GET",p,null,n,role),p);
    }
    @Test void dischargedCasesCannotBeRegradedAndUnknownActorsCannotWriteClinicalData()throws Exception{
        String n=person();Long adm=admission();
        assertEquals(1005,code("PUT","/api/inpatient/admissions/"+adm+"/care-level?level=特级",null,"v93ghost","NURSE"));
        assertEquals(1005,code("POST","/api/nursing/risk-assess",Map.of("admissionId",adm,"assessType","MORSE","score",50),"v93ghost","NURSE"));
        assertEquals(1005,code("POST","/api/nursing/handovers",Map.of("deptId",1,"shiftType","DAY","summary","V93"),"v93ghost","NURSE"));
        jdbc.update("update inp_admission set status='DISCHARGED',discharged_at=now() where id=?",adm);
        assertEquals(4805,code("PUT","/api/inpatient/admissions/"+adm+"/care-level?level=特级",null,n,"NURSE"));
        assertEquals(4805,code("PUT","/api/inpatient/admissions/"+adm+"/care-level/change",Map.of("level","特级","reason","V93"),n,"NURSE"));
        assertEquals(4780,code("POST","/api/nursing/risk-assess",Map.of("admissionId",adm,"assessType","MORSE","score",50),n,"NURSE"));
        assertEquals(0,jdbc.queryForObject("select count(*) from nur_care_level_log where admission_id=?",Integer.class,adm));
    }
    @Test void nursingAndQualityPositivePathsAndFeedbackReadsRemainUsable()throws Exception{
        String n=person(),q=person();Long adm=admission();
        assertEquals(0,code("PUT","/api/inpatient/admissions/"+adm+"/care-level/change",Map.of("level","特级","reason","V93"),n,"NURSE"));
        assertEquals(0,code("POST","/api/nursing/risk-assess",Map.of("admissionId",adm,"assessType","BRADEN","score",12),n,"NURSE"));
        assertEquals(0,code("POST","/api/nursing/qc-scores",Map.of("deptId",1,"item","V93","score",90),q,"QUALITY"));
        assertEquals(0,code("POST","/api/qc-check/plans",Map.of("title","V93","standard","V93"),q,"QUALITY"));
        assertEquals(0,code("POST","/api/qc-check/scores",Map.of("planId",plan(),"target","V93","score",90),q,"QUALITY"));
        for(String role:List.of("NURSE","QUALITY"))for(String p:List.of("/api/nursing/qc-scores","/api/nursing/qc-scores/summary","/api/qc-check/plans","/api/nursing/shift-types","/api/nursing/handovers","/api/nursing/risk-assess?admissionId="+adm,"/api/nursing/risk-assess/alerts","/api/inpatient/nursing/board"))assertEquals(0,code("GET",p,null,n,role),p);
        assertEquals(0,code("POST","/api/nursing/risk-assess",Map.of("admissionId",adm,"assessType","MORSE","score",50),n,"QUALITY","NURSE"));
        assertEquals(0,code("POST","/api/nursing/qc-scores",Map.of("deptId",1,"item","V93","score",90),n,"QUALITY","NURSE"));
        for(String role:List.of("DOCTOR_OUTP","TECHNICIAN","CASHIER"))assertEquals(1005,code("GET","/api/nursing/risk-assess?admissionId="+adm,null,n,role));
    }
}
