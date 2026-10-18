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
class V91BloodSurgeryAccessTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;
    String doctor(){String n="v91"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);return n;}
    Long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    Long admission(Long owner){String n="V91"+System.nanoTime();Long p=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,n,n);return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status,admit_at) select ?,?,1,ward_id,id,?,'IN_HOSPITAL',now() from inp_bed order by id limit 1 returning id",Long.class,n,p,owner);}
    Long surgery(Long adm){return jdbc.queryForObject("insert into inp_surgery(admission_id,procedure_name,status,scheduled_at) values (?,'V91手术','REQUESTED',now()) returning id",Long.class,adm);}
    Long blood(Long adm,String status){return jdbc.queryForObject("insert into blood_apply(admission_id,product,volume_ml,reason,status) values (?,'RBC',200,'V91',?) returning id",Long.class,adm,status);}
    JsonNode call(String m,String path,Object body,String name,String role)throws Exception{var req=switch(m){case "POST"->post(path);case "PUT"->put(path);case "DELETE"->delete(path);default->get(path);};req.with(user(name).roles(role));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));var r=mvc.perform(req).andReturn().getResponse();assertTrue(r.getStatus()==200||r.getStatus()==403,path+" HTTP "+r.getStatus());return om.readTree(r.getContentAsString(StandardCharsets.UTF_8));}
    int code(String m,String p,Object b,String n,String role)throws Exception{return call(m,p,b,n,role).path("code").asInt();}
    @Test void foreignDoctorCannotRequestCompleteCancelOrWriteExecutionRecords()throws Exception{
        String a=doctor(),b=doctor();Long adm=admission(uid(a)),s=surgery(adm);
        assertEquals(9018,code("POST","/api/inpatient/blood/applies",Map.of("admissionId",adm,"product","RBC","volumeMl",200),b,"DOCTOR_OUTP"));
        assertEquals(9018,code("POST","/api/inpatient/surgeries",Map.of("admissionId",adm,"procedureName","V91"),b,"DOCTOR_OUTP"));
        for(var req:List.of(Map.entry("complete",Map.of("opNote","x","anesNote","x")),Map.entry("cancel",Map.of("stage","APPLY","reason","x")),Map.entry("timepoint",Map.of("stage","IN_ROOM")),Map.entry("op-info",Map.of("roomNo","OR91"))))assertEquals(9018,code("PUT","/api/inpatient/surgeries/"+s+"/"+req.getKey(),req.getValue(),b,"DOCTOR_OUTP"),req.getKey());
        assertEquals(9018,code("POST","/api/surgery/intraop/tubes",Map.of("surgeryId",s,"tubeType","尿管"),b,"DOCTOR_OUTP"));
        assertEquals(9018,code("POST","/api/surgery/intraop/transfusions",Map.of("surgeryId",s,"productType","RBC","volumeMl",200),b,"DOCTOR_OUTP"));
        assertEquals(9018,code("POST","/api/surgery/intraop/events",Map.of("surgeryId",s,"eventType","RESCUE"),b,"DOCTOR_OUTP"));
        assertEquals(9018,code("POST","/api/anes/records",Map.of("surgeryId",s,"phase","INTRA","hr",80),b,"DOCTOR_OUTP"));
        assertEquals("REQUESTED",jdbc.queryForObject("select status from inp_surgery where id=?",String.class,s));
        assertEquals(0,jdbc.queryForObject("select count(*) from blood_apply where admission_id=?",Integer.class,adm));
        for(String t:List.of("surg_tube","surg_transfusion","surg_event","anes_record"))assertEquals(0,jdbc.queryForObject("select count(*) from "+t+" where surgery_id=?",Integer.class,s));
    }
    @Test void readsAndDefaultListsAreScopedBeforeTheLimit()throws Exception{
        String a=doctor(),b=doctor();Long own=admission(uid(a)),other=admission(uid(b)),s=surgery(other);Long mine=surgery(own),bl=blood(own,"APPLIED");for(int i=0;i<105;i++){surgery(other);blood(other,"APPLIED");}
        for(String path:List.of("/api/inpatient/surgeries/"+s+"/detail","/api/surgery/intraop/summary?surgeryId="+s,"/api/surgery/intraop/tubes?surgeryId="+s,"/api/surgery/intraop/transfusions?surgeryId="+s,"/api/surgery/intraop/events?surgeryId="+s,"/api/anes/records?surgeryId="+s,"/api/anes/records/pacu-status?surgeryId="+s))assertEquals(1005,code("GET",path,null,a,"DOCTOR_OUTP"),path);
        for(String path:List.of("/api/inpatient/surgeries","/api/inpatient/blood/applies")){var rows=call("GET",path,null,a,"DOCTOR_OUTP").path("data");assertTrue(rows.findValues("id").stream().anyMatch(n->n.asLong()==(path.contains("blood")?bl:mine)));assertFalse(rows.findValues("admission_id").stream().anyMatch(n->n.asLong()==other));}
        String board=call("GET","/api/inpatient/surgeries/room-board",null,a,"DOCTOR_OUTP").path("data").toString();assertFalse(board.contains("\"admission_id\":"+other+","));
    }
    @Test void bloodDutiesAreSeparatedAndNurseCanStillRecordTransfusion()throws Exception{
        String a=doctor();Long adm=admission(uid(a)),applied=blood(adm,"APPLIED"),approved=blood(adm,"APPROVED"),issued=blood(adm,"ISSUED");
        for(String role:List.of("DOCTOR_OUTP","NURSE")){assertEquals(1005,code("PUT","/api/inpatient/blood/applies/"+applied+"/review?approve=true",null,a,role));assertEquals(1005,code("PUT","/api/inpatient/blood/applies/"+approved+"/issue",null,a,role));}
        assertEquals(1005,code("POST","/api/inpatient/blood/applies",Map.of("admissionId",adm,"product","RBC","volumeMl",200),a,"NURSE"));
        assertEquals(1005,code("PUT","/api/inpatient/blood/applies/"+issued+"/transfuse?note=V91",null,a,"DOCTOR_OUTP"));
        assertEquals(0,code("PUT","/api/inpatient/blood/applies/"+issued+"/transfuse?note=V91",null,a,"NURSE"));
        assertEquals(0,code("PUT","/api/inpatient/blood/applies/"+applied+"/review?approve=true",null,"admin","ADMIN"));assertEquals(0,code("PUT","/api/inpatient/blood/applies/"+approved+"/issue",null,"admin","ADMIN"));
        assertEquals(1005,code("GET","/api/inpatient/blood/applies",null,a,"TECHNICIAN"));
        assertEquals(0,code("POST","/api/inpatient/blood/applies",Map.of("admissionId",adm,"product","RBC","volumeMl",200),a,"DOCTOR_OUTP"));
    }
    @Test void nursingAndTechniciansKeepOperationalSurgeryDutiesButNotMedicalDecisions()throws Exception{
        String a=doctor(),b=doctor();Long adm=admission(uid(a)),s=surgery(adm);
        for(String role:List.of("NURSE","TECHNICIAN")){
            assertEquals(1005,code("POST","/api/inpatient/surgeries",Map.of("admissionId",adm,"procedureName","V91"),b,role));
            assertEquals(1005,code("PUT","/api/inpatient/surgeries/"+s+"/complete",Map.of("opNote","x"),b,role));
            assertEquals(1005,code("PUT","/api/inpatient/surgeries/"+s+"/cancel",Map.of("stage","APPLY","reason","x"),b,role));
            assertEquals(1005,code("PUT","/api/inpatient/surgeries/"+s+"/op-info",Map.of("roomNo","UNAUTHORIZED","asaGrade","II"),b,role));
            assertEquals(0,code("PUT","/api/inpatient/surgeries/"+s+"/op-info",Map.of("roomNo","OR91"),b,role));
            assertEquals(0,code("PUT","/api/inpatient/surgeries/"+s+"/timepoint",Map.of("stage","IN_ROOM"),b,role));
            assertEquals(0,code("POST","/api/surgery/intraop/tubes",Map.of("surgeryId",s,"tubeType","尿管"),b,role));
            assertEquals(0,code("GET","/api/surgery/intraop/summary?surgeryId="+s,null,b,role));
        }
        assertNull(jdbc.queryForObject("select asa_grade from inp_surgery where id=?",String.class,s));
        assertEquals(0,code("POST","/api/anes/records",Map.of("surgeryId",s,"phase","INTRA","hr",80),b,"TECHNICIAN"));
        assertEquals(1005,code("POST","/api/anes/records",Map.of("surgeryId",s,"phase","INTRA","hr",80),b,"NURSE"));
    }
    @Test void childIdsCannotBeReauthorizedByForgedBodySurgeryId()throws Exception{
        String a=doctor(),b=doctor();Long own=surgery(admission(uid(a))),foreign=surgery(admission(uid(b)));
        Long tube=jdbc.queryForObject("insert into surg_tube(surgery_id,tube_type,inserted_at) values (?,'尿管',now()) returning id",Long.class,foreign);
        Long blood=jdbc.queryForObject("insert into surg_transfusion(surgery_id,product_type,volume_ml,is_auto,transfused_at) values (?,'RBC',200,false,now()) returning id",Long.class,foreign);
        Long event=jdbc.queryForObject("insert into surg_event(surgery_id,event_type,event_time) values (?,'RESCUE',now()) returning id",Long.class,foreign);
        for(var row:List.of(Map.entry("tubes",tube),Map.entry("transfusions",blood),Map.entry("events",event))){Map<String,Object> body=new HashMap<>(Map.of("surgeryId",own,"tubeType","尿管","productType","RBC","volumeMl",300,"eventType","RESCUE"));assertEquals(9018,code("PUT","/api/surgery/intraop/"+row.getKey()+"/"+row.getValue(),body,a,"DOCTOR_OUTP"));assertEquals(9018,code("DELETE","/api/surgery/intraop/"+row.getKey()+"/"+row.getValue(),null,a,"DOCTOR_OUTP"));assertEquals(1,jdbc.queryForObject("select count(*) from "+Map.of("tubes","surg_tube","transfusions","surg_transfusion","events","surg_event").get(row.getKey())+" where id=?",Integer.class,row.getValue()));}
    }
    @Test void unassignedRequiresRealDoctorAndHandoffChangesAccess()throws Exception{
        String a=doctor(),b=doctor();Long adm=admission(null),s=surgery(adm);
        assertEquals(1005,code("GET","/api/inpatient/surgeries/"+s+"/detail",null,"v91-missing","DOCTOR_OUTP"));
        assertEquals(9018,code("PUT","/api/inpatient/surgeries/"+s+"/complete",Map.of("opNote","x"),"v91-missing","DOCTOR_OUTP"));
        assertEquals(0,code("GET","/api/inpatient/surgeries/"+s+"/detail",null,a,"DOCTOR_OUTP"));
        jdbc.update("update inp_admission set doctor_id=? where id=?",uid(b),adm);
        assertEquals(1005,code("GET","/api/inpatient/surgeries/"+s+"/detail",null,a,"DOCTOR_OUTP"));
        assertEquals(0,code("PUT","/api/inpatient/surgeries/"+s+"/complete",Map.of("opNote","x","anesNote","x"),b,"DOCTOR_OUTP"));
    }
    @Test void existingMenusFollowScopedDutiesWithoutGrantingTechniciansBloodAccess(){
        for(String role:List.of("DOCTOR_OUTP","NURSE","TECHNICIAN"))assertEquals(1,jdbc.queryForObject("select count(*) from sys_role_menu rm join sys_role r on r.id=rm.role_id where r.code=? and rm.menu_id=40",Integer.class,role));
        assertEquals(1,jdbc.queryForObject("select count(*) from sys_role_menu rm join sys_role r on r.id=rm.role_id where r.code='DOCTOR_OUTP' and rm.menu_id=45",Integer.class));
        for(String role:List.of("TECHNICIAN","QUALITY","CASHIER"))assertEquals(0,jdbc.queryForObject("select count(*) from sys_role_menu rm join sys_role r on r.id=rm.role_id where r.code=? and rm.menu_id=45",Integer.class,role));
    }

}
