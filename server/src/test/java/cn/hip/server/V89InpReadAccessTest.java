package cn.hip.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.hip.platform.core.config.BusinessDates;
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
class V89InpReadAccessTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;
    private String doctor() {
        String name="v89"+System.nanoTime();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",name,name);
        jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code='DOCTOR_OUTP'",name);
        return name;
    }
    private Long uid(String name) {return jdbc.queryForObject("select id from sys_user where username=?",Long.class,name);}
    private Long admission(Long owner) {
        String suffix="V89"+System.nanoTime();
        Long pid=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,suffix,suffix);
        Long bid=jdbc.queryForObject("select id from inp_bed where status='FREE' order by id limit 1",Long.class);
        Long id=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status,admit_at) select ?,?,1,ward_id,id,?,'IN_HOSPITAL',now() from inp_bed where id=? returning id",Long.class,suffix,pid,owner,bid);
        jdbc.update("update inp_bed set status='OCCUPIED',admission_id=? where id=?",id,bid);
        order(id,"TEMP"); Long longId=order(id,"LONG");
        jdbc.update("insert into inp_order_exec(order_id,exec_date,amount) values (?,?::date,1)",longId,BusinessDates.today().toString());
        jdbc.update("insert into inp_diagnosis(admission_id,icd,name) values (?,'I10','V89诊断')",id);
        return id;
    }
    private Long order(Long adm,String nature) {return jdbc.queryForObject("insert into inp_order(admission_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,order_nature) values (?,'V89','TREAT',1,'V89','V89权限项目','次',1,1,1,'CREATED',?) returning id",Long.class,adm,nature);}
    private JsonNode read(String path,String name,String role) throws Exception {
        var r=mvc.perform(get("/api/inpatient"+path).with(user(name).roles(role))).andReturn().getResponse();
        assertTrue(r.getStatus()==200||r.getStatus()==403,"HTTP "+r.getStatus()+" "+path);
        return om.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    private List<String> details(Long id) {
        String base="/admissions/"+id,date=BusinessDates.today().toString();
        return List.of(base+"/daily-fees?date="+date,base+"/print/daily-fee?date="+date,base+"/account",base+"/transfers",base+"/interim-settlements",base+"/emr-integrity",base+"/vitals",base+"/print/temp-sheet");
    }
    @Test void foreignDetailsDeniedWhileOwnerNurseAdminRemainUsable() throws Exception {
        String a=doctor(),b=doctor();Long id=admission(uid(a));
        for(String path:details(id)) {
            var denied=read(path,b,"DOCTOR_OUTP");assertEquals(1005,denied.path("code").asInt(),path);
            assertTrue(denied.path("data").isNull()||denied.path("data").isMissingNode());
            assertEquals(0,read(path,a,"DOCTOR_OUTP").path("code").asInt(),path);
            assertEquals(0,read(path,b,"NURSE").path("code").asInt(),path);
            assertEquals(0,read(path,"admin","ADMIN").path("code").asInt(),path);
        }
        jdbc.update("update inp_admission set doctor_id=? where id=?",uid(b),id);
        for(String path:details(id)) assertEquals(1005,read(path,a,"DOCTOR_OUTP").path("code").asInt(),path);
        for(String path:details(Long.MAX_VALUE)) {
            assertEquals(path.endsWith("/print/temp-sheet")?4820:9003,read(path,a,"DOCTOR_OUTP").path("code").asInt(),path);
        }
    }
    @Test void cashierAndCoderKeepOnlyTheirExistingResponsibilities() throws Exception {
        String a=doctor(),b=doctor();Long id=admission(uid(a));
        for(String path:details(id)) {
            int expected=path.contains("/print/daily-fee")||path.endsWith("/account")||path.endsWith("/interim-settlements")?0:1005;
            assertEquals(expected,read(path,b,"CASHIER").path("code").asInt(),path);
        }
        assertEquals(1005,read("/diagnoses?admissionId="+id,b,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(0,read("/diagnoses?admissionId="+id,b,"QUALITY").path("code").asInt());
        assertEquals(1005,read("/diagnoses?admissionId="+id,b,"NURSE").path("code").asInt());
    }
    @Test void listsSearchQueuesAndBedsHideForeignPatients() throws Exception {
        String a=doctor(),b=doctor();Long own=admission(uid(a)),foreign=admission(uid(b)),unassigned=admission(null);
        for(String path:List.of("/admissions","/admissions?keyword=V89","/admissions?doctorId="+uid(b),"/orders/search?keyword=V89","/orders/pending","/exec-lines?date="+BusinessDates.today())) {
            String text=read(path,a,"DOCTOR_OUTP").path("data").toString();
            String foreignNo=jdbc.queryForObject("select admission_no from inp_admission where id=?",String.class,foreign);
            assertFalse(text.contains(foreignNo),path);
            if(!path.contains("doctorId=")) {
                String ownNo=jdbc.queryForObject("select admission_no from inp_admission where id=?",String.class,own);
                String unassignedNo=jdbc.queryForObject("select admission_no from inp_admission where id=?",String.class,unassigned);
                assertTrue(text.contains(ownNo),path);assertTrue(text.contains(unassignedNo),path);
            }
            assertTrue(read(path,b,"NURSE").path("data").toString().contains(foreignNo),path);
        }
        Long ward=jdbc.queryForObject("select ward_id from inp_admission where id=?",Long.class,foreign);
        String foreignName=jdbc.queryForObject("select p.name from inp_admission a join empi_patient p on p.id=a.patient_id where a.id=?",String.class,foreign);
        assertFalse(read("/beds?wardId="+ward,a,"DOCTOR_OUTP").toString().contains(foreignName));
        assertTrue(read("/beds?wardId="+ward,b,"NURSE").toString().contains(foreignName));
    }
    @Test void scopeAppliesBeforeSearchLimitAndUnknownIdentityCannotUseUnassigned() throws Exception {
        String a=doctor(),b=doctor();Long own=admission(uid(a)),foreign=admission(uid(b)),unassigned=admission(null);
        for(int i=0;i<205;i++)order(foreign,"TEMP");
        var data=read("/orders/search?keyword=V89",a,"DOCTOR_OUTP").path("data");
        assertEquals(4,data.path("items").size());assertFalse(data.path("truncated").asBoolean());
        for(String path:details(unassigned)) {
            assertEquals(1005,read(path,"v89-missing","DOCTOR_OUTP").path("code").asInt(),path);
            assertEquals(0,read(path,a,"DOCTOR_OUTP").path("code").asInt(),path);
        }
        assertEquals(1005,read("/diagnoses?admissionId="+unassigned,"v89-missing","DOCTOR_OUTP").path("code").asInt());
        assertEquals(0,read("/orders/pending","v89-missing","DOCTOR_OUTP").path("data").size());
        assertEquals(0,read("/exec-lines?date="+BusinessDates.today(),"v89-missing","DOCTOR_OUTP").path("data").size());
        assertEquals(0,read("/admissions","v89-missing","DOCTOR_OUTP").path("data").size());
        assertEquals(0,read("/orders/search?keyword=V89","v89-missing","DOCTOR_OUTP").path("data").path("items").size());
    }
    @Test void samePatientDoesNotGrantAccessToAnotherAdmission() throws Exception {
        String a=doctor(),b=doctor();Long own=admission(uid(a));
        jdbc.update("update inp_admission set status='DISCHARGED' where id=?",own);
        Long other=admission(uid(b));
        jdbc.update("update inp_admission set patient_id=(select patient_id from inp_admission where id=?) where id=?",own,other);
        for(String path:details(other)) assertEquals(1005,read(path,a,"DOCTOR_OUTP").path("code").asInt(),path);
        assertEquals(1005,read("/diagnoses?admissionId="+other,a,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(0,read("/diagnoses?admissionId="+own,a,"DOCTOR_OUTP").path("code").asInt());
    }
    @Test void diagnosisUnknownIdentityIsDeniedAndCoderDuplicateDoesNotAbortTransaction() throws Exception {
        Long id=admission(null);
        var req=post("/api/inpatient/diagnoses").with(user("v89-missing").roles("DOCTOR_OUTP")).contentType("application/json").content(om.writeValueAsString(Map.of("admissionId",id,"icd","E11","name","V89")));
        var response=mvc.perform(req).andReturn().getResponse();
        assertEquals(9018,om.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("code").asInt());
        for(String icd:List.of("I10","E11")) {
            response=mvc.perform(post("/api/inpatient/diagnoses").with(user("admin").roles("QUALITY")).contentType("application/json").content(om.writeValueAsString(Map.of("admissionId",id,"icd",icd,"name","V89")))).andReturn().getResponse();
            assertEquals(icd.equals("I10")?9015:0,om.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("code").asInt());
        }
        assertEquals(2,jdbc.queryForObject("select count(*) from inp_diagnosis where admission_id=?",Integer.class,id));
    }
}
