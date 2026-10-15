package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.core.config.BusinessDates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Read-only permission tests use real persisted users and sources through the HTTP security chain. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles="ADMIN")
class V84CdrAccessTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PatientService patients;
    @Autowired RegistrationService registrations;
    @Autowired OutpScheduleRepository schedules;
    @Autowired OutpOrderRepository orders;
    private final String marker="V84"+System.nanoTime();

    private String doctor() {
        String name="v84"+System.nanoTime();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)", name,name);
        return name;
    }
    private Long uid(String name) {
        return jdbc.queryForObject("select id from sys_user where username=?",Long.class,name);
    }
    private Long patient() {
        Patient p=new Patient();p.setName(marker);p.setSex("U");return patients.register(p).getId();
    }
    private Long visit(Long patientId,String owner) {
        OutpSchedule s=new OutpSchedule();s.setDeptId(1L);s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);s.setCapacity(5);s.setDoctorId(owner==null?null:uid(owner));
        Long id=registrations.register(patientId,schedules.save(s).getId()).getId();
        em.flush();return id;
    }
    private Long doc(Long pid,String type,Long ref,Instant at) {
        return jdbc.queryForObject("insert into cdr_document(patient_id,doc_type,ref_id,title,doc_time,content,synced_at) values (?,?,?,?,?,'{\"private\":\"正文\"}',now()) returning id",
            Long.class,pid,type,ref,marker,java.sql.Timestamp.from(at));
    }
    private Long doc(Long pid,String type,Long ref) {return doc(pid,type,ref,Instant.now());}
    private JsonNode call(String path,String name,String role) throws Exception {
        var response=mvc.perform(get("/api/cdr/"+path).with(user(name).roles(role))).andReturn().getResponse();
        assertEquals(200,response.getStatus());
        return om.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    private JsonNode ok(String path,String name) throws Exception {
        JsonNode response=call(path,name,"DOCTOR_OUTP");assertEquals(0,response.path("code").asInt());return response.path("data");
    }
    private boolean contains(JsonNode rows,Long id) {
        for(JsonNode row:rows) if(row.path("id").asLong()==id) return true;return false;
    }
    private void denied(Long id,String name) throws Exception {
        JsonNode response=call("documents/"+id,name,"DOCTOR_OUTP");
        assertEquals(1005,response.path("code").asInt());assertTrue(response.path("data").isNull());
    }
    @Test void samePatientDoesNotGrantOtherVisitsThroughAnyOfTheThreeRoutes() throws Exception {
        String a=doctor(),b=doctor();Long pid=patient();
        Long mine=doc(pid,"OUTP_ENCOUNTER",visit(pid,a));
        Long hidden=doc(pid,"OUTP_ENCOUNTER",visit(pid,b));
        JsonNode rows=ok("patients/"+pid+"/documents",a);
        assertTrue(contains(rows,mine));assertFalse(contains(rows,hidden));
        assertFalse(rows.get(0).has("content"),"Timeline remains a metadata projection");
        JsonNode hits=ok("search?keyword="+marker,a);
        assertTrue(contains(hits,mine));assertFalse(contains(hits,hidden));
        assertFalse(contains(ok("search?keyword=%25",a),hidden),"Wildcard search must not bypass source permission");
        assertEquals(mine,ok("documents/"+mine,a).path("id").asLong());denied(hidden,a);
    }
    @Test void laboratoryAndInpatientSnapshotsFollowTheirRealSourceOwner() throws Exception {
        String a=doctor(),b=doctor();Long pid=patient(),rid=visit(pid,b);
        OutpOrder o=new OutpOrder();o.setRegistrationId(rid);o.setGroupNo(marker);o.setOrderType("LAB");
        o.setItemId(1L);o.setItemCode("V84");o.setItemName("V84检验");o.setUnit("次");o.setQty(1);
        o.setUnitPrice(BigDecimal.ZERO);o.setAmount(BigDecimal.ZERO);Long orderId=orders.saveAndFlush(o).getId();
        Long lab=doc(pid,"LAB_REPORT",orderId);
        Long admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status,admit_at) values (?,?,1,1,1,?,'DISCHARGED',now()) returning id",Long.class,marker,pid,uid(b));
        Long inp=doc(pid,"INP_SUMMARY",admission);
        for(Long id:new Long[]{lab,inp}) {denied(id,a);assertEquals(id,ok("documents/"+id,b).path("id").asLong());}
        assertTrue(ok("patients/"+pid+"/documents",a).isEmpty());
        assertTrue(ok("patients/"+pid+"/documents?docType=LAB_REPORT",a).isEmpty());
        assertTrue(contains(ok("patients/"+pid+"/documents?docType=LAB_REPORT",b),lab));
        jdbc.update("update outp_registration set doctor_id=? where id=?",uid(a),rid);
        jdbc.update("update inp_admission set doctor_id=? where id=?",uid(a),admission);
        for(Long id:new Long[]{lab,inp}) {denied(id,b);assertEquals(id,ok("documents/"+id,a).path("id").asLong());}
    }
    @Test void orphanMismatchUnknownAndLegacySnapshotsFailClosedForDoctors() throws Exception {
        String a=doctor();Long pid=patient(),otherPid=patient(),foreignVisit=visit(otherPid,a);
        Long[] hidden={doc(pid,"OUTP_ENCOUNTER",Long.MAX_VALUE),doc(pid,"OUTP_ENCOUNTER",foreignVisit),
            doc(pid,"LEGACY_OUTP",Long.MAX_VALUE),doc(pid,"UNKNOWN",Long.MAX_VALUE)};
        for(Long id:hidden) denied(id,a);
        assertTrue(ok("patients/"+pid+"/documents",a).isEmpty());
        assertTrue(ok("search?keyword="+marker,a).isEmpty());
    }
    @Test void privilegedReadersAndUnassignedSourcesRemainUsable() throws Exception {
        String a=doctor(),b=doctor();Long pid=patient();
        Long unassigned=doc(pid,"OUTP_ENCOUNTER",visit(pid,null));
        Long foreign=doc(pid,"OUTP_ENCOUNTER",visit(pid,b));Long legacy=doc(pid,"LEGACY_OUTP",Long.MAX_VALUE);
        assertEquals(unassigned,ok("documents/"+unassigned,a).path("id").asLong());
        for(String role:new String[]{"ADMIN","QUALITY"}) {
            JsonNode rows=call("patients/"+pid+"/documents","v84privileged",role).path("data");
            assertTrue(contains(rows,foreign));assertTrue(contains(rows,legacy));
            assertEquals(0,call("documents/"+legacy,"v84privileged",role).path("code").asInt());
        }
        assertTrue(ok("patients/"+pid+"/documents","v84MissingIdentity").isEmpty());
        denied(unassigned,"v84MissingIdentity");
        assertEquals(4040,call("documents/"+Long.MAX_VALUE,a,"DOCTOR_OUTP").path("code").asInt());
        assertEquals(403,mvc.perform(get("/api/cdr/documents/"+foreign).with(user("nurse").roles("NURSE"))).andReturn().getResponse().getStatus());
    }
    @Test void permissionsAreAppliedBeforeSearchAndTimelineLimits() throws Exception {
        String a=doctor(),b=doctor();Long pid=patient();
        Long own=doc(pid,"OUTP_ENCOUNTER",visit(pid,a),Instant.EPOCH);
        for(int i=0;i<205;i++) doc(pid,"OUTP_ENCOUNTER",visit(pid,b),Instant.now().plusSeconds(i));
        JsonNode timeline=ok("patients/"+pid+"/documents",a),search=ok("search?keyword="+marker,a);
        assertEquals(1,timeline.size());assertEquals(own,timeline.get(0).path("id").asLong());
        assertEquals(1,search.size());assertEquals(own,search.get(0).path("id").asLong());
    }
}
