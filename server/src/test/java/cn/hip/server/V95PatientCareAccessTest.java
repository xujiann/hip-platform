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
class V95PatientCareAccessTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;
    String person(String role,Long dept){String n="v95"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,?)",n,n,dept);jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code=?",n,role);return n;}
    Long uid(String n){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);}
    Long patient(){String n="V95"+System.nanoTime();return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,n,n);}
    Long admission(String owner){return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,status,admit_at,doctor_id) select ?,?,1,ward_id,id,'IN_HOSPITAL',now(),? from inp_bed order by id limit 1 returning id",Long.class,"V95"+System.nanoTime(),patient(),owner==null?null:uid(owner));}
    Long followup(String owner){return jdbc.queryForObject("insert into pc_followup(patient_id,topic,due_date,creator_id) values (?,'V95',current_date,?) returning id",Long.class,patient(),owner==null?null:uid(owner));}
    Long consult(Long adm,Long dept){return jdbc.queryForObject("insert into inp_consult(admission_id,to_dept_id,question) values (?,?,'V95') returning id",Long.class,adm,dept);}
    Long pkg(){return jdbc.queryForObject("insert into pe_exam_package(name,price,items) values ('V95',1,'V95') returning id",Long.class);}
    JsonNode call(String method,String path,Object body,String who,String...roles)throws Exception{
        var req=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};req.with(user(who).roles(roles));if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));var r=mvc.perform(req).andReturn().getResponse();assertTrue(r.getStatus()==200||r.getStatus()==403,path+" HTTP "+r.getStatus());return om.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }
    int code(String method,String path,Object body,String who,String...roles)throws Exception{return call(method,path,body,who,roles).path("code").asInt();}
    @Test void followupOwnersAreIsolatedAndHistoricalNullIsNotClaimed()throws Exception{
        String a=person("DOCTOR_OUTP",1L),b=person("DOCTOR_OUTP",2L);Long own=followup(a),foreign=followup(b),legacy=followup(null);
        assertEquals(1005,code("PUT","/api/patientcare/followups/"+foreign+"/done?note=V95",null,a,"DOCTOR_OUTP"));
        assertEquals(1005,code("PUT","/api/patientcare/followups/"+legacy+"/done?note=V95",null,a,"DOCTOR_OUTP"));
        var rows=call("GET","/api/patientcare/followups",null,a,"DOCTOR_OUTP").path("data");assertEquals(1,rows.size());assertEquals(own,rows.get(0).path("id").asLong());
        assertEquals(0,code("PUT","/api/patientcare/followups/"+own+"/done?note=V95",null,a,"DOCTOR_OUTP"));
    }
    @Test void qualityIsReadOnlyAndUnknownActorsCannotCreateFollowups()throws Exception{
        String q=person("QUALITY",1L);Long id=followup(q);var body=Map.of("patientId",patient(),"topic","V95","dueDate","2026-10-19");
        assertEquals(1005,code("POST","/api/patientcare/followups",body,q,"QUALITY"));
        assertEquals(1005,code("PUT","/api/patientcare/followups/"+id+"/done?note=V95",null,q,"QUALITY"));
        assertEquals(1005,code("POST","/api/patientcare/followups",body,"v95ghost","NURSE"));
        assertEquals(0,code("GET","/api/patientcare/followups",null,q,"QUALITY"));
    }
    @Test void consultRequestsRequireTheCurrentAttendingAndPhysicianRole()throws Exception{
        String a=person("DOCTOR_OUTP",1L),b=person("DOCTOR_OUTP",2L);Long adm=admission(a);var body=Map.of("admissionId",adm,"toDeptId",2,"question","V95");
        assertEquals(9018,code("POST","/api/inpatient/consults",body,b,"DOCTOR_OUTP"));
        for(String role:List.of("NURSE","QUALITY","CASHIER","TECHNICIAN"))assertEquals(1005,code("POST","/api/inpatient/consults",body,a,role));
        assertEquals(0,code("POST","/api/inpatient/consults",body,a,"DOCTOR_OUTP"));
    }
    @Test void consultOpinionsBelongToInvitedDepartmentRatherThanTheRequestingDoctor()throws Exception{
        String a=person("DOCTOR_OUTP",1L),b=person("DOCTOR_OUTP",2L);Long id=consult(admission(a),2L);
        assertEquals(1005,code("PUT","/api/inpatient/consults/"+id+"/complete?opinion=V95",null,a,"DOCTOR_OUTP"));
        for(String role:List.of("NURSE","QUALITY"))assertEquals(1005,code("PUT","/api/inpatient/consults/"+id+"/complete?opinion=V95",null,b,role));
        assertEquals(0,code("PUT","/api/inpatient/consults/"+id+"/complete?opinion=V95",null,b,"DOCTOR_OUTP"));
        assertEquals(uid(b),jdbc.queryForObject("select consultant_id from inp_consult where id=?",Long.class,id));
    }
    @Test void consultListsFilterBothAttendingAndInvitedDepartmentBeforeTheLimit()throws Exception{
        String a=person("DOCTOR_OUTP",1L),b=person("DOCTOR_OUTP",2L);Long own=consult(admission(a),2L),invited=consult(admission(b),1L),hidden=consult(admission(b),2L);
        Set<Long> ids=new HashSet<>();call("GET","/api/inpatient/consults",null,a,"DOCTOR_OUTP").path("data").forEach(x->ids.add(x.path("id").asLong()));assertTrue(ids.containsAll(List.of(own,invited)));assertFalse(ids.contains(hidden));
    }
    @Test void qualityAndNursesCannotWriteExamConclusionsAndLegacyRowsAreUnassigned()throws Exception{
        String a=person("DOCTOR_OUTP",1L);Long id=jdbc.queryForObject("insert into pe_exam_record(patient_id,package_id) values (?,?) returning id",Long.class,patient(),pkg());
        for(String role:List.of("NURSE","QUALITY","DOCTOR_OUTP"))assertEquals(1005,code("PUT","/api/exam/records/"+id+"/complete?summary=V95",null,a,role));
        assertEquals(0,code("PUT","/api/exam/records/"+id+"/complete?summary=V95",null,"admin","ADMIN"));
    }
    @Test void examListsAndGroupRecordsDoNotLeakUnassignedPatientsToDoctors()throws Exception{
        String a=person("DOCTOR_OUTP",1L),b=person("DOCTOR_OUTP",2L);Long p=patient(),pkg=pkg();
        assertEquals(0,code("POST","/api/exam/records?patientId="+p+"&packageId="+pkg,null,a,"DOCTOR_OUTP"));
        assertEquals(0,call("GET","/api/exam/records",null,b,"DOCTOR_OUTP").path("data").size());
        var group=call("POST","/api/exam/groups",Map.of("unitName","V95","packageId",pkg,"memberNames",List.of("V95甲"),"examinerId",uid(a)),"admin","ADMIN");
        assertEquals(0,group.path("code").asInt());Long id=group.path("data").path("groupId").asLong();
        assertEquals(1005,code("GET","/api/exam/groups/"+id+"/records",null,b,"DOCTOR_OUTP"));
        assertEquals(0,call("GET","/api/exam/groups",null,b,"DOCTOR_OUTP").path("data").size());
    }
    @Test void taskAndConsultFilteringOccursBeforeTheirLimits()throws Exception{
        String a=person("DOCTOR_OUTP",1L),b=person("DOCTOR_OUTP",2L);Long own=followup(a),p=patient(),adm=admission(b),visible=consult(admission(a),2L);
        jdbc.update("insert into pc_followup(patient_id,topic,due_date,creator_id) select ?,'V95bulk',current_date-1,? from generate_series(1,205)",p,uid(b));
        jdbc.update("insert into inp_consult(admission_id,to_dept_id,question) select ?,2,'V95bulk' from generate_series(1,105)",adm);
        var rows=call("GET","/api/patientcare/followups",null,a,"DOCTOR_OUTP").path("data");assertEquals(1,rows.size());assertEquals(own,rows.get(0).path("id").asLong());
        var cons=call("GET","/api/inpatient/consults",null,a,"DOCTOR_OUTP").path("data");assertEquals(1,cons.size());assertEquals(visible,cons.get(0).path("id").asLong());
    }
    @Test void assignedExaminerCanFinishButRegistrarOrMixedNursingRoleCannotBypassReassignment()throws Exception{
        String a=person("DOCTOR_OUTP",1L),b=person("DOCTOR_OUTP",2L);Long patient=patient(),pkg=pkg();
        assertEquals(0,code("POST","/api/exam/records?patientId="+patient+"&packageId="+pkg,null,a,"DOCTOR_OUTP"));
        Long id=jdbc.queryForObject("select id from pe_exam_record where patient_id=?",Long.class,patient);
        assertEquals(1005,code("PUT","/api/exam/records/"+id+"/examiner",Map.of("examinerId",uid(b)),a,"DOCTOR_OUTP"));
        assertEquals(0,code("PUT","/api/exam/records/"+id+"/examiner",Map.of("examinerId",uid(b)),"admin","ADMIN"));
        assertEquals(1005,code("PUT","/api/exam/records/"+id+"/complete?summary=V95",Map.of("patientId",patient()),a,"DOCTOR_OUTP","NURSE"));
        assertEquals(0,code("PUT","/api/exam/records/"+id+"/complete?summary=V95",Map.of("patientId",patient()),b,"DOCTOR_OUTP"));
        assertEquals(patient,jdbc.queryForObject("select patient_id from pe_exam_record where id=?",Long.class,id));
        assertEquals(9905,code("PUT","/api/exam/records/"+id+"/examiner",Map.of("examinerId",uid(a)),"admin","ADMIN"));
    }
    @Test void groupAggregatesIncludeOnlyVisibleMembersAndNurseOwnershipDoesNotGrantConclusions()throws Exception{
        String a=person("DOCTOR_OUTP",1L),b=person("DOCTOR_OUTP",2L),n=person("NURSE",1L),other=person("NURSE",1L);Long pkg=pkg();
        var result=call("POST","/api/exam/groups",Map.of("unitName","V95","packageId",pkg,"examinerId",uid(a),"memberNames",List.of("V95甲","V95乙")),n,"NURSE");assertEquals(0,result.path("code").asInt());Long group=result.path("data").path("groupId").asLong();
        Long first=jdbc.queryForObject("select min(id) from pe_exam_record where group_id=?",Long.class,group);
        jdbc.update("update pe_exam_record set examiner_id=?,status='DONE',summary='V95 private',abnormal=true where id=?",uid(b),first);
        var mine=call("GET","/api/exam/groups",null,a,"DOCTOR_OUTP").path("data");assertEquals(1,mine.size());assertEquals(1,mine.get(0).path("members").asInt());assertEquals(0,mine.get(0).path("abnormal_cnt").asInt());
        assertEquals(1,call("GET","/api/exam/groups/"+group+"/records",null,a,"DOCTOR_OUTP").path("data").size());
        assertEquals(2,call("GET","/api/exam/groups/"+group+"/records",null,n,"NURSE").path("data").size());
        assertEquals(1005,code("GET","/api/exam/groups/"+group+"/records",null,other,"NURSE"));
        Long pending=jdbc.queryForObject("select max(id) from pe_exam_record where group_id=?",Long.class,group);
        assertEquals(1005,code("PUT","/api/exam/records/"+pending+"/complete?summary=V95",null,n,"NURSE"));
        assertEquals(0,code("GET","/api/exam/groups/"+group+"/records",null,n,"QUALITY"));
    }
    @Test void targetDoctorsDepartmentsAndRealActorsAreValidated()throws Exception{
        String a=person("DOCTOR_OUTP",1L),n=person("NURSE",1L),q=person("QUALITY",1L);Long pkg=pkg(),p=patient(),adm=admission(a),c=consult(adm,2L);
        assertEquals(9916,code("POST","/api/exam/records?patientId="+p+"&packageId="+pkg+"&examinerId="+uid(n),null,n,"NURSE"));
        assertEquals(1005,code("POST","/api/exam/records?patientId="+p+"&packageId="+pkg,null,q,"QUALITY"));
        assertEquals(9918,code("POST","/api/inpatient/consults",Map.of("admissionId",adm,"toDeptId",-95,"question","V95"),a,"DOCTOR_OUTP"));
        assertEquals(1005,code("PUT","/api/inpatient/consults/"+c+"/complete?opinion=V95",null,"v95ghost","ADMIN"));
        assertEquals(0,code("POST","/api/exam/records?patientId="+p+"&packageId="+pkg,null,a,"DOCTOR_OUTP"));
        Long id=jdbc.queryForObject("select id from pe_exam_record where patient_id=?",Long.class,p);
        assertEquals(1005,code("PUT","/api/exam/records/"+id+"/complete?summary=V95",null,"v95ghost","ADMIN"));
    }
}
