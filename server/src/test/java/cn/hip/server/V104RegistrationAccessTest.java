package cn.hip.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class V104RegistrationAccessTest {
 @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper om; @Autowired jakarta.persistence.EntityManager em;
 static final String BASE="/api/outpatient/registrations";
 String actor(){String n="v104"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);return n;}
 long patient(boolean active){String n="V104"+System.nanoTime();return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex,active) values (?,?,'U',?) returning id",Long.class,n,n,active);}
 long schedule(){return jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,fee,capacity,booked) values (1,current_date,5,5,0) returning id",Long.class);}
 long slot(long s){return jdbc.queryForObject("insert into outp_schedule_slot(schedule_id,time_begin,time_end,capacity) values (?,'08:00','08:30',5) returning id",Long.class,s);}
 JsonNode call(String method,String path,Object body,String who,String...roles)throws Exception {
  em.flush();em.clear();SecurityContextHolder.clearContext();try {
   var req=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};req.with(user(who).roles(roles));
   if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
   var res=mvc.perform(req).andReturn().getResponse();assertTrue(res.getStatus()==200||res.getStatus()==403,"HTTP "+res.getStatus());
   var result=om.readTree(res.getContentAsString(StandardCharsets.UTF_8));if(result.path("code").asInt()==0)em.flush();return result;
  }finally{SecurityContextHolder.clearContext();}
 }
 JsonNode ok(String method,String path,Object body,String who,String...roles)throws Exception {var r=call(method,path,body,who,roles);assertEquals(0,r.path("code").asInt(),r.toString());return r.path("data");}
 long register(long p,long s,String who)throws Exception{return ok("POST",BASE,Map.of("patientId",p,"scheduleId",s),who,"CASHIER").path("id").asLong();}
 long checked(long p,long s,long sl,String who)throws Exception {
  long a=ok("POST","/api/outpatient/appointments",Map.of("patientId",p,"slotId",sl),who,"CASHIER").path("id").asLong();
  return ok("POST","/api/outpatient/appointments/"+a+"/checkin",null,who,"CASHIER").path("registrationId").asLong();
 }
 int count(String table,long id){return jdbc.queryForObject("select booked from "+table+" where id=?",Integer.class,id);}
 @Test void disabledAndUnknownStaffCannotReadRegisterOrCancel()throws Exception {
  long s=schedule(),p=patient(true);String good=actor(),disabled=actor();long r=register(p,s,good);jdbc.update("update sys_user set enabled=false where username=?",disabled);
  for(String who:List.of(disabled,"v104ghost"))for(String role:List.of("ADMIN","CASHIER")){
   assertEquals(1005,call("GET",BASE+"?date="+cn.hip.platform.core.config.BusinessDates.today(),null,who,role).path("code").asInt());
   assertEquals(1005,call("POST",BASE,Map.of("patientId",patient(true),"scheduleId",s),who,role).path("code").asInt());
   assertEquals(1005,call("PUT",BASE+"/"+r+"/cancel",null,who,role).path("code").asInt());
  }assertEquals(1,count("outp_schedule",s));assertEquals("REGISTERED",jdbc.queryForObject("select status from outp_registration where id=?",String.class,r));
 }
 @Test void inactivePatientCannotRegisterAndCapacityDoesNotMove()throws Exception {long s=schedule();assertEquals(3007,call("POST",BASE,Map.of("patientId",patient(false),"scheduleId",s),actor(),"CASHIER").path("code").asInt());assertEquals(0,count("outp_schedule",s));}
 @Test void missingPatientHasAnObjectErrorBeforeAnyInsert()throws Exception {long s=schedule();assertEquals(3007,call("POST",BASE,Map.of("patientId",-1,"scheduleId",s),actor(),"ADMIN").path("code").asInt());assertEquals(0,count("outp_schedule",s));}
 @Test void cashiersCollaborateAndCanCancelAnInactivePatientsExistingRegistration()throws Exception {
  long s=schedule(),sl=slot(s),p=patient(true);String one=actor(),two=actor();long r=register(p,s,one);
  assertTrue(ok("GET",BASE+"?date="+cn.hip.platform.core.config.BusinessDates.today(),null,two,"CASHIER").toString().contains("\"id\":"+r));
  jdbc.update("update empi_patient set active=false where id=?",p);ok("PUT",BASE+"/"+r+"/cancel",null,two,"CASHIER");
  assertEquals(0,count("outp_schedule",s));assertEquals(0,count("outp_schedule_slot",sl));assertEquals("CANCELLED",jdbc.queryForObject("select status from outp_order where registration_id=?",String.class,r));
 }
 @Test void checkedInCancellationReleasesBothPoolsExactlyOnceAndKeepsAuditLink()throws Exception {
  long s=schedule(),sl=slot(s);String who=actor();long r=checked(patient(true),s,sl,who);assertEquals(1,count("outp_schedule_slot",sl));
  ok("PUT",BASE+"/"+r+"/cancel",null,who,"CASHIER");assertEquals(0,count("outp_schedule",s));assertEquals(0,count("outp_schedule_slot",sl));
  assertEquals("CHECKED_IN",jdbc.queryForObject("select status from outp_appointment where registration_id=?",String.class,r));
  assertEquals(3005,call("PUT",BASE+"/"+r+"/cancel",null,who,"CASHIER").path("code").asInt());assertEquals(0,count("outp_schedule_slot",sl));
 }
 @Test void chargedAndVisitedRegistrationsDoNotReleaseAnyPool()throws Exception {
  long s=schedule(),sl=slot(s);String who=actor();long r=checked(patient(true),s,sl,who);
  jdbc.update("update outp_order set status='CHARGED' where registration_id=?",r);
  assertEquals(3006,call("PUT",BASE+"/"+r+"/cancel",null,who,"CASHIER").path("code").asInt());
  jdbc.update("update outp_registration set status='VISITED' where id=?",r);em.clear();assertEquals(3005,call("PUT",BASE+"/"+r+"/cancel",null,who,"CASHIER").path("code").asInt());
  assertEquals(1,count("outp_schedule",s));assertEquals(1,count("outp_schedule_slot",sl));
 }
 @Test void mismatchedAssociationIsRejectedWithoutPartialCancellation()throws Exception {
  long s=schedule(),sl=slot(s);String who=actor();long r=checked(patient(true),s,sl,who);
  jdbc.update("update outp_appointment set patient_id=? where registration_id=?",patient(true),r);
  assertEquals(3008,call("PUT",BASE+"/"+r+"/cancel",null,who,"CASHIER").path("code").asInt());
  assertEquals("REGISTERED",jdbc.queryForObject("select status from outp_registration where id=?",String.class,r));assertEquals("CREATED",jdbc.queryForObject("select status from outp_order where registration_id=?",String.class,r));assertEquals(1,count("outp_schedule_slot",sl));
 }
 @Test void otherRolesCannotReadOrWriteTheDesk()throws Exception {
  long s=schedule(),p=patient(true);String who=actor();long r=register(p,s,who);
  for(String role:List.of("DOCTOR_OUTP","NURSE","QUALITY","TECHNICIAN","PHARMACIST","OPERATION","PORTAL")){
   assertEquals(1005,call("GET",BASE+"?date="+cn.hip.platform.core.config.BusinessDates.today(),null,who,role).path("code").asInt());
   assertEquals(1005,call("POST",BASE,Map.of("patientId",p,"scheduleId",s),who,role).path("code").asInt());
   assertEquals(1005,call("PUT",BASE+"/"+r+"/cancel",null,who,role).path("code").asInt());
  }assertEquals(1,count("outp_schedule",s));
 }
 @Test void portalRegisterUsesOnlyTokenPatientAndRejectsInactiveTokenPatient()throws Exception {
  long s=schedule(),p=patient(true),other=patient(true);ok("POST","/api/portal/register",Map.of("scheduleId",s,"patientId",other),"portal:"+p,"PORTAL");
  assertEquals(p,jdbc.queryForObject("select patient_id from outp_registration where schedule_id=?",Long.class,s));
  long inactive=patient(false),s2=schedule();assertEquals(3007,call("POST","/api/portal/register",Map.of("scheduleId",s2),"portal:"+inactive,"PORTAL").path("code").asInt());assertEquals(0,count("outp_schedule",s2));
 }
 @Test void originalMissingAndDuplicateCodesRemainUsable()throws Exception {
  String who=actor();long p=patient(true),s=schedule();assertEquals(3001,call("POST",BASE,Map.of("patientId",p,"scheduleId",-1),who,"ADMIN").path("code").asInt());
  assertEquals(3004,call("PUT",BASE+"/-1/cancel",null,who,"ADMIN").path("code").asInt());register(p,s,who);assertEquals(3002,call("POST",BASE,Map.of("patientId",p,"scheduleId",s),who,"ADMIN").path("code").asInt());assertEquals(1,count("outp_schedule",s));
 }
}
