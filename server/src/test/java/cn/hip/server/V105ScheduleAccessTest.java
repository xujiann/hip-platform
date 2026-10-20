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
class V105ScheduleAccessTest {
 @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper om; @Autowired jakarta.persistence.EntityManager em;
 static final String BASE="/api/outpatient/schedules";
 String day(){return cn.hip.platform.core.config.BusinessDates.today().toString();}
 String actor(){String n="v105"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);return n;}
 long doctor(boolean enabled,boolean qualified){String n=actor();long id=jdbc.queryForObject("select id from sys_user where username=?",Long.class,n);jdbc.update("update sys_user set enabled=? where id=?",enabled,id);if(qualified)jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='DOCTOR_OUTP'",id);return id;}
 long dept(boolean enabled,String type){String n="V105"+System.nanoTime();return jdbc.queryForObject("insert into sys_dept(name,code,type,enabled) values (?,?,?,?) returning id",Long.class,n,n,type,enabled);}
 Map<String,Object> body(){return new LinkedHashMap<>(Map.of("deptId",1,"scheduleDate",day(),"capacity",5));}
 JsonNode call(String method,String path,Object body,String who,String...roles)throws Exception {
  em.flush();em.clear();SecurityContextHolder.clearContext();try {
   var req=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};req.with(user(who).roles(roles));
   if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
   var res=mvc.perform(req).andReturn().getResponse();assertTrue(res.getStatus()==200||res.getStatus()==403,"HTTP "+res.getStatus());
   var result=om.readTree(res.getContentAsString(StandardCharsets.UTF_8));if(result.path("code").asInt()==0)em.flush();return result;
  }finally{SecurityContextHolder.clearContext();}
 }
 JsonNode ok(String method,String path,Object body,String who,String...roles)throws Exception {var r=call(method,path,body,who,roles);assertEquals(0,r.path("code").asInt(),r.toString());return r.path("data");}
 long schedule()throws Exception{return ok("POST",BASE,body(),"admin","ADMIN").path("id").asLong();}
 int total(){return jdbc.queryForObject("select count(*) from outp_schedule",Integer.class);}
 @Test void disabledAndUnknownAdministratorsCannotCreateToggleOrReadManagementList()throws Exception {
  long s=schedule();String disabled=actor();jdbc.update("update sys_user set enabled=false where username=?",disabled);int before=total();
  for(String who:List.of(disabled,"v105ghost")){
   assertEquals(1005,call("POST",BASE,body(),who,"ADMIN").path("code").asInt());
   assertEquals(1005,call("PUT",BASE+"/"+s+"/enabled?enabled=false",null,who,"ADMIN").path("code").asInt());
   assertEquals(1005,call("GET",BASE+"?date="+day()+"&includeDisabled=true",null,who,"ADMIN").path("code").asInt());
  }assertEquals(before,total());assertTrue(jdbc.queryForObject("select enabled from outp_schedule where id=?",Boolean.class,s));
 }
 @Test void otherRolesRetainSharedCatalogButCannotManageSchedules()throws Exception {
  long s=schedule();String who=actor();
  for(String role:List.of("CASHIER","DOCTOR_OUTP","NURSE","QUALITY","TECHNICIAN","PHARMACIST","OPERATION","PORTAL")){
   if(role.equals("PORTAL"))assertEquals(1005,call("GET",BASE+"?date="+day(),null,who,role).path("code").asInt());
   else ok("GET",BASE+"?date="+day(),null,who,role);
   assertEquals(1005,call("POST",BASE,body(),who,role).path("code").asInt());assertEquals(1005,call("PUT",BASE+"/"+s+"/enabled?enabled=false",null,who,role).path("code").asInt());
   assertEquals(1005,call("GET",BASE+"?date="+day()+"&includeDisabled=true",null,who,role).path("code").asInt());
  }
 }
 @Test void createIgnoresEverySystemFieldAndUsesServerDefaults()throws Exception {
  long existing=schedule();var b=body();b.put("id",existing);b.put("booked",4);b.put("enabled",false);b.put("createdAt","1970-01-01T00:00:00Z");
  var result=ok("POST",BASE,b,actor(),"ADMIN");long id=result.path("id").asLong();assertNotEquals(existing,id);assertEquals(0,result.path("booked").asInt());assertTrue(result.path("enabled").asBoolean());
  assertEquals("FULL",result.path("shift").asText());assertEquals("GENERAL",result.path("regType").asText());assertEquals(0,result.path("fee").decimalValue().signum());
  assertTrue(jdbc.queryForObject("select created_at>'2026-01-01'::timestamptz from outp_schedule where id=?",Boolean.class,id));
 }
 @Test void realEnabledClinicalDepartmentIsRequired()throws Exception {
  int before=total();for(long d:List.of(-1L,dept(false,"CLINICAL"),dept(true,"ADMIN"))){var b=body();b.put("deptId",d);assertEquals(3103,call("POST",BASE,b,"admin","ADMIN").path("code").asInt());}assertEquals(before,total());
 }
 @Test void assignedDoctorMustExistBeEnabledAndHaveOutpatientRole()throws Exception {
  int before=total();for(long d:List.of(-1L,doctor(false,true),doctor(true,false))){var b=body();b.put("doctorId",d);assertEquals(3104,call("POST",BASE,b,"admin","ADMIN").path("code").asInt());}assertEquals(before,total());
 }
 @Test void invalidBusinessValuesReturn3101WithoutInsertion()throws Exception {
  int before=total();for(var invalid:List.of(Map.of("capacity",0),Map.of("shift","BAD"),Map.of("regType","OTHER"),Map.of("fee",-1),Map.of("fee",0.001),Map.of("fee",100000000))){var b=body();b.putAll(invalid);assertEquals(3101,call("POST",BASE,b,"admin","ADMIN").path("code").asInt());}assertEquals(before,total());
 }
 @Test void managementListCanRestoreDisabledScheduleWhileSharedCatalogStaysEnabledOnly()throws Exception {
  long s=schedule();ok("PUT",BASE+"/"+s+"/enabled?enabled=false",null,"admin","ADMIN");
  assertFalse(ok("GET",BASE+"?date="+day(),null,actor(),"CASHIER").toString().contains("\"id\":"+s+","));
  var rows=ok("GET",BASE+"?date="+day()+"&includeDisabled=true",null,actor(),"ADMIN");assertTrue(rows.toString().contains("\"id\":"+s+","));
  ok("PUT",BASE+"/"+s+"/enabled?enabled=true",null,"admin","ADMIN");assertTrue(jdbc.queryForObject("select enabled from outp_schedule where id=?",Boolean.class,s));
 }
 @Test void disableCanStopInvalidOldAssignmentButReenableMustValidateReferences()throws Exception {
  long d=dept(true,"CLINICAL"),doc=doctor(true,true);var b=body();b.put("deptId",d);b.put("doctorId",doc);long s=ok("POST",BASE,b,"admin","ADMIN").path("id").asLong();
  jdbc.update("update sys_dept set enabled=false where id=?",d);ok("PUT",BASE+"/"+s+"/enabled?enabled=false",null,"admin","ADMIN");assertEquals(3103,call("PUT",BASE+"/"+s+"/enabled?enabled=true",null,"admin","ADMIN").path("code").asInt());
  jdbc.update("update sys_dept set enabled=true where id=?",d);jdbc.update("update sys_user set enabled=false where id=?",doc);assertEquals(3104,call("PUT",BASE+"/"+s+"/enabled?enabled=true",null,"admin","ADMIN").path("code").asInt());assertFalse(jdbc.queryForObject("select enabled from outp_schedule where id=?",Boolean.class,s));
 }
 @Test void legitimateDoctorScheduleAndResponseKeysRemainCompatible()throws Exception {
  var b=body();b.put("doctorId",doctor(true,true));b.put("shift","AM");b.put("regType","EXPERT");b.put("fee",10.50);var row=ok("POST",BASE,b,"admin","ADMIN");
  assertEquals("AM",row.path("shift").asText());assertEquals(0,new java.math.BigDecimal("10.50").compareTo(row.path("fee").decimalValue()));
  var keys=new HashSet<String>();row.fieldNames().forEachRemaining(keys::add);assertEquals(Set.of("id","deptId","deptName","doctorId","doctorName","scheduleDate","shift","regType","fee","capacity","booked","remaining","enabled"),keys);
 }
 @Test void originalMissingAndRequiredCodesStayUsable()throws Exception {
  assertEquals(3101,call("POST",BASE,Map.of("deptId",1),"admin","ADMIN").path("code").asInt());assertEquals(3102,call("PUT",BASE+"/-1/enabled?enabled=false",null,"admin","ADMIN").path("code").asInt());
 }
}
