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
class V103AppointmentAccessTest {
 @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper om;
 @Autowired jakarta.persistence.EntityManager em;
 String actor(){String n="v103"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);return n;}
 long patient(boolean active){String n="V103"+System.nanoTime();return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex,active) values (?,?,'U',?) returning id",Long.class,n,n,active);}
 long schedule(){return jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,fee,capacity,booked) values (1,current_date,5,5,0) returning id",Long.class);}
 long slot(long s){return jdbc.queryForObject("insert into outp_schedule_slot(schedule_id,time_begin,time_end,capacity) values (?,'08:00','08:30',5) returning id",Long.class,s);}
 long appointment(long s,long sl,long p){jdbc.update("update outp_schedule set booked=booked+1 where id=?",s);jdbc.update("update outp_schedule_slot set booked=booked+1 where id=?",sl);return jdbc.queryForObject("insert into outp_appointment(schedule_id,slot_id,patient_id,appt_no) values (?,?,?,1) returning id",Long.class,s,sl,p);}
 JsonNode call(String method,String path,Object body,String who,String...roles)throws Exception {
  SecurityContextHolder.clearContext();try {
   var req=method.equals("POST")?post(path):get(path);req.with(user(who).roles(roles));
   if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
   var res=mvc.perform(req).andReturn().getResponse();assertTrue(res.getStatus()==200||res.getStatus()==403,"HTTP "+res.getStatus());
   em.flush();return om.readTree(res.getContentAsString(StandardCharsets.UTF_8));
  } finally {SecurityContextHolder.clearContext();}
 }
 JsonNode ok(String method,String path,Object body,String who,String...roles)throws Exception {var r=call(method,path,body,who,roles);assertEquals(0,r.path("code").asInt(),r.toString());return r.path("data");}
 void counts(long s,long sl,int expected){assertEquals(expected,jdbc.queryForObject("select booked from outp_schedule where id=?",Integer.class,s));assertEquals(expected,jdbc.queryForObject("select booked from outp_schedule_slot where id=?",Integer.class,sl));}
 Object lines(){return Map.of("slots",List.of(Map.of("timeBegin","09:00","timeEnd","09:30","capacity",1)));}
 @Test void disabledAndUnknownStaffCannotUseAnyAppointmentEntry()throws Exception {
  String disabled=actor();jdbc.update("update sys_user set enabled=false where username=?",disabled);
  long s=schedule(),sl=slot(s),a=appointment(s,sl,patient(true));
  for(String who:List.of(disabled,"v103ghost")){
   assertEquals(1005,call("GET","/api/outpatient/schedules/"+s+"/slots",null,who,"ADMIN").path("code").asInt());
   assertEquals(1005,call("GET","/api/outpatient/appointments?scheduleId="+s,null,who,"ADMIN").path("code").asInt());
   assertEquals(1005,call("POST","/api/outpatient/schedules/"+s+"/slots",lines(),who,"ADMIN").path("code").asInt());
   assertEquals(1005,call("POST","/api/outpatient/appointments",Map.of("slotId",sl,"patientId",patient(true)),who,"ADMIN").path("code").asInt());
   assertEquals(1005,call("POST","/api/outpatient/appointments/"+a+"/checkin",null,who,"ADMIN").path("code").asInt());
   assertEquals(1005,call("POST","/api/outpatient/appointments/"+a+"/cancel",null,who,"ADMIN").path("code").asInt());
  }counts(s,sl,1);assertEquals("BOOKED",jdbc.queryForObject("select status from outp_appointment where id=?",String.class,a));
 }
 @Test void inactivePatientCannotBeBookedAndCapacityDoesNotMove()throws Exception {long s=schedule(),sl=slot(s);assertEquals(3115,call("POST","/api/outpatient/appointments",Map.of("slotId",sl,"patientId",patient(false)),actor(),"CASHIER").path("code").asInt());counts(s,sl,0);}
 @Test void missingPatientHasAnObjectErrorInsteadOfDuplicateError()throws Exception {long s=schedule(),sl=slot(s);assertEquals(3115,call("POST","/api/outpatient/appointments",Map.of("slotId",sl,"patientId",-1),actor(),"CASHIER").path("code").asInt());counts(s,sl,0);}
 @Test void inactivePatientCannotCheckInButCanCancelForRelease()throws Exception {
  long s=schedule(),sl=slot(s),p=patient(false),a=appointment(s,sl,p);String who=actor();
  assertEquals(3115,call("POST","/api/outpatient/appointments/"+a+"/checkin",null,who,"CASHIER").path("code").asInt());
  assertEquals("BOOKED",jdbc.queryForObject("select status from outp_appointment where id=?",String.class,a));assertEquals(0,jdbc.queryForObject("select count(*) from outp_registration where patient_id=?",Integer.class,p));
  ok("POST","/api/outpatient/appointments/"+a+"/cancel",null,who,"CASHIER");counts(s,sl,0);
 }
 @Test void sourceLengthIsValidatedBeforeOccupyingEitherPool()throws Exception {long s=schedule(),sl=slot(s);assertEquals(3116,call("POST","/api/outpatient/appointments",Map.of("slotId",sl,"patientId",patient(true),"source","x".repeat(17)),actor(),"ADMIN").path("code").asInt());counts(s,sl,0);}
 @Test void invalidSlotTimesAreBusinessErrorsWithoutPartialInsertion()throws Exception {
  long s=schedule();var invalid=Map.of("slots",List.of(Map.of("timeBegin","09:00","timeEnd","09:30","capacity",1),Map.of("timeBegin","bad","timeEnd","10:00","capacity",1)));
  assertEquals(3120,call("POST","/api/outpatient/schedules/"+s+"/slots",invalid,"admin","ADMIN").path("code").asInt());assertEquals(0,jdbc.queryForObject("select count(*) from outp_schedule_slot where schedule_id=?",Integer.class,s));
 }
 @Test void cashierCannotManageSlotsAndOtherRolesCannotReadOrWriteDesk()throws Exception {
  long s=schedule(),sl=slot(s),p=patient(true),a=appointment(s,sl,p);String who=actor();
  assertEquals(1005,call("POST","/api/outpatient/schedules/"+s+"/slots",lines(),who,"CASHIER").path("code").asInt());
  for(String role:List.of("DOCTOR_OUTP","NURSE","QUALITY","TECHNICIAN","PHARMACIST","OPERATION","PORTAL")){
   assertEquals(1005,call("GET","/api/outpatient/appointments?scheduleId="+s,null,who,role).path("code").asInt());
   assertEquals(1005,call("GET","/api/outpatient/schedules/"+s+"/slots",null,who,role).path("code").asInt());
   assertEquals(1005,call("POST","/api/outpatient/appointments",Map.of("slotId",sl,"patientId",p),who,role).path("code").asInt());
   for(String op:List.of("cancel","checkin"))assertEquals(1005,call("POST","/api/outpatient/appointments/"+a+"/"+op,null,who,role).path("code").asInt());
  }counts(s,sl,1);
 }
 @Test void enabledCashiersCanCollaborateAcrossPatientsWithoutInventedCreatorOwnership()throws Exception {
  long s=schedule(),sl=slot(s),p=patient(true);String one=actor(),two=actor();
  long a=ok("POST","/api/outpatient/appointments",Map.of("slotId",sl,"patientId",p,"source","窗口"),one,"CASHIER").path("id").asLong();
  assertEquals(1,ok("GET","/api/outpatient/appointments?scheduleId="+s,null,two,"CASHIER").size());
  var reg=ok("POST","/api/outpatient/appointments/"+a+"/checkin",null,two,"CASHIER");assertTrue(reg.path("registrationId").asLong()>0);counts(s,sl,1);
  assertEquals(3114,call("POST","/api/outpatient/appointments/"+a+"/cancel",null,one,"CASHIER").path("code").asInt());
 }
 @Test void adminSlotManagementAndExistingMissingCodesStayUsable()throws Exception {
  long s=schedule();ok("POST","/api/outpatient/schedules/"+s+"/slots",lines(),"admin","ADMIN");assertEquals(1,ok("GET","/api/outpatient/schedules/"+s+"/slots",null,"admin","ADMIN").size());
  String who=actor();assertEquals(3110,call("POST","/api/outpatient/appointments",Map.of("slotId",-1,"patientId",patient(true)),who,"CASHIER").path("code").asInt());
  for(String op:List.of("checkin","cancel"))assertEquals(3113,call("POST","/api/outpatient/appointments/-1/"+op,null,who,"CASHIER").path("code").asInt());
 }
 @Test void portalIdentityAndCancellationRemainBoundToTheTokenPatient()throws Exception {
  long s=schedule(),sl=slot(s),p=patient(true),other=patient(true);String principal="portal:"+p;
  long a=ok("POST","/api/portal/appointments",Map.of("slotId",sl,"patientId",other),principal,"PORTAL").path("id").asLong();
  assertEquals(p,jdbc.queryForObject("select patient_id from outp_appointment where id=?",Long.class,a));
  assertEquals(9506,call("POST","/api/portal/my/appointments/"+a+"/cancel",null,"portal:"+other,"PORTAL").path("code").asInt());
  counts(s,sl,1);ok("POST","/api/portal/my/appointments/"+a+"/cancel",null,principal,"PORTAL");counts(s,sl,0);
 }
}
