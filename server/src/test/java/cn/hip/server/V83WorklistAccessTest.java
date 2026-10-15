package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.repository.OutpEmrRepository;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
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
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real persisted users and HTTP security chain; read-only assertions require no commit semantics. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class V83WorklistAccessTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PatientService patients;
    @Autowired RegistrationService registrations;
    @Autowired OutpScheduleRepository schedules;
    @Autowired OutpEmrRepository records;
    private final LocalDate today = BusinessDates.today();

    private String doctor() {
        String name = "v83" + System.nanoTime();
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?, 'x', ?,true) returning id", Long.class, name, name);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='DOCTOR_OUTP'",id);
        return name;
    }
    private Long uid(String name) {
        return jdbc.queryForObject("select id from sys_user where username=?",Long.class,name);
    }
    private Long visit(LocalDate date, String owner, String writer) {
        Patient p = new Patient(); p.setName("V83隐私" + System.nanoTime()); p.setSex("U");
        p.setAllergyHistory("V83敏感过敏史");
        Long pid=patients.register(p).getId();
        OutpSchedule s=new OutpSchedule(); s.setDeptId(1L); s.setScheduleDate(date);
        s.setFee(BigDecimal.ZERO); s.setCapacity(5); s.setDoctorId(owner == null ? null : uid(owner));
        Long rid=registrations.register(pid,schedules.save(s).getId()).getId();
        if(writer != null) {
            OutpEmr e=new OutpEmr(); e.setRegistrationId(rid); e.setDoctorId(uid(writer));
            e.setChiefComplaint("V83隐私正文"); records.save(e);
        }
        em.flush(); em.clear(); return rid;
    }
    private JsonNode call(String query,String name,String role) throws Exception {
        var response=mvc.perform(get("/api/outpatient/doctor/"+query).with(user(name).roles(role))).andReturn().getResponse();
        assertEquals(200,response.getStatus());
        JsonNode body=om.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(0,body.path("code").asInt(),body.toString()); return body.path("data");
    }
    private boolean contains(JsonNode rows,Long id) {
        for(JsonNode row:rows) if(row.path("registrationId").asLong()==id) return true;
        return false;
    }
    @Test void historicalDateAndExplicitOtherDoctorCannotExposeDeniedRows() throws Exception {
        String me=doctor(),other=doctor(); Long hidden=visit(today.minusDays(1),other,other);
        var workspace=mvc.perform(get("/api/outpatient/doctor/"+hidden+"/workspace").with(user(me).roles("DOCTOR_OUTP")))
            .andReturn().getResponse();
        assertEquals(4036,om.readTree(workspace.getContentAsString(StandardCharsets.UTF_8)).path("code").asInt());
        assertFalse(contains(call("worklist?date="+today.minusDays(1),me,"DOCTOR_OUTP"),hidden));
        assertFalse(contains(call("worklist?from="+today.minusDays(1)+"&to="+today+"&doctorId="+uid(other),me,"DOCTOR_OUTP"),hidden));
    }
    @Test void mixedRangePreservesTodaySharingOwnHistoryAndOrder() throws Exception {
        String me=doctor(),other=doctor();
        Long own=visit(today.minusDays(1),me,me), hidden=visit(today.minusDays(1),other,other);
        Long shared=visit(today,other,other);
        JsonNode rows=call("worklist?from="+today.minusDays(1)+"&to="+today,me,"DOCTOR_OUTP");
        assertTrue(contains(rows,own)); assertTrue(contains(rows,shared)); assertFalse(contains(rows,hidden));
        long previous=Long.MAX_VALUE;
        for(JsonNode row: rows) {long id=row.path("registrationId").asLong();assertTrue(id<previous);previous=id;}
        assertTrue(contains(call("worklist?date="+today,me,"DOCTOR_OUTP"),shared));
        assertFalse(contains(call("worklist?date="+today+"&mine=true",me,"DOCTOR_OUTP"),shared));
    }
    @Test void recordAuthorAndUnassignedHistoryStayVisible() throws Exception {
        String me=doctor(),other=doctor();
        Long authored=visit(today.minusDays(1),other,me),unassigned=visit(today.minusDays(1),null,null);
        JsonNode rows=call("worklist?date="+today.minusDays(1),me,"DOCTOR_OUTP");
        assertTrue(contains(rows,authored)); assertTrue(contains(rows,unassigned));
        for(Long id: new Long[]{authored,unassigned}) call(id+"/workspace",me,"DOCTOR_OUTP");
    }
    @Test void adminStillSeesOtherDoctorsHistoryAndFutureRowsAreRestricted() throws Exception {
        String me=doctor(),other=doctor(); Long history=visit(today.minusDays(1),other,other);
        Long future=visit(today.plusDays(1),other,null);
        assertTrue(contains(call("worklist?date="+today.minusDays(1),"admin","ADMIN"),history));
        assertFalse(contains(call("worklist?date="+today.plusDays(1),me,"DOCTOR_OUTP"),future));
        assertTrue(contains(call("worklist?date="+today.plusDays(1),"admin","ADMIN"),future));
    }
}
