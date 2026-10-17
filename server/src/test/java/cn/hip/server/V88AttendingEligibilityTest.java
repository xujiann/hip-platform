package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientWriteAccess;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc @Transactional @WithMockUser(roles="ADMIN")
class V88AttendingEligibilityTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper om;
    @Autowired InpatientService service;
    @Autowired InpatientWriteAccess access;
    @Autowired JdbcTemplate jdbc;
    @Autowired PatientService patients;
    @Autowired EntityManager em;
    private Long account(boolean enabled,String... roles) {
        String name="v88"+System.nanoTime();
        Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,?) returning id",Long.class,name,name,enabled);
        for(String role:roles) jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role);
        return id;
    }
    private Long bed() {return jdbc.queryForObject("select id from inp_bed where status='FREE' order by id limit 1",Long.class);}
    private Long patient() {Patient p=new Patient();p.setName("V88虚构"+System.nanoTime());p.setSex("U");return patients.register(p).getId();}
    private Long admit(Long owner) {return service.admit(patient(),1L,bed(),owner,null,null,new BigDecimal("100"),"CASH",null).getId();}
    private Map<String,Object> state() {
        em.flush();
        return Map.of("admissions",jdbc.queryForList("select * from inp_admission order by id"),
            "beds",jdbc.queryForList("select * from inp_bed order by id"),
            "deposits",jdbc.queryForList("select * from inp_deposit order by id"));
    }
    @Test void admissionRejectsIneligibleTargetsBeforeAnyWrite() {
        List<Long> invalid=List.of(account(true,"NURSE"),account(true,"CASHIER"),account(true),account(false,"DOCTOR_OUTP"),Long.MAX_VALUE,0L,-1L);
        Long pid=patient(),bid=bed();
        for(Long target:invalid) {
            var before=state();
            var error=assertThrows(InpatientService.InpException.class,()->service.admit(pid,1L,bid,target,null,null,new BigDecimal("100"),"CASH",null));
            assertEquals(4884,error.code);assertEquals(before,state());
        }
    }
    @Test void handoffRejectsIneligibleTargetsWithoutChangingAdmission() {
        Long id=admit(account(true,"DOCTOR_OUTP"));em.flush();
        List<Long> invalid=new ArrayList<>(List.of(account(true,"NURSE"),account(true,"CASHIER"),account(true),account(false,"ADMIN"),Long.MAX_VALUE,0L,-1L));
        invalid.add(null);
        for(Long target:invalid) {
            var before=state();
            var error=assertThrows(InpatientService.InpException.class,()->access.setAttending(id,target));
            assertEquals(4884,error.code);assertEquals(before,state());
        }
    }
    @Test void httpRejectsNonPhysicianForAdminDoctorAndNurseWithoutSideEffects() throws Exception {
        Long target=account(true,"NURSE"),pid=patient(),bid=bed();
        for(String role:List.of("ADMIN","DOCTOR_OUTP","NURSE")) {
            var before=state();
            var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/inpatient/admissions")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles(role))
                .contentType("application/json").content(om.writeValueAsString(Map.of("patientId",pid,"deptId",1,"bedId",bid,"doctorId",target,"deposit",100))))
                .andReturn().getResponse();
            assertEquals(200,response.getStatus());
            assertEquals(4884,om.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("code").asInt());
            assertEquals(before,state());
        }
        Long id=admit(account(true,"DOCTOR_OUTP"));var before=state();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/inpatient/admissions/"+id+"/attending-doctor")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
            .contentType("application/json").content(om.writeValueAsString(Map.of("doctorId",target))))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus());
        assertEquals(4884,om.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("code").asInt());
        assertEquals(before,state());
    }
    @Test void roleRevocationIsRecheckedOnEveryAssignment() {
        Long owner=account(true,"DOCTOR_OUTP"),target=account(true,"DOCTOR_OUTP");
        Long id=admit(owner);em.flush();
        access.setAttending(id,target);
        jdbc.update("delete from sys_user_role where user_id=?",target);
        var before=state();
        assertEquals(4884,assertThrows(InpatientService.InpException.class,()->access.setAttending(id,target)).code);
        assertEquals(before,state());
        Long pid=patient(),bid=bed();before=state();
        assertEquals(4884,assertThrows(InpatientService.InpException.class,()->service.admit(pid,1L,bid,target,null,null,BigDecimal.TEN,"CASH",null)).code);
        assertEquals(before,state());
    }
    @Test void eligibleAndUnassignedAdmissionRemainAvailable() {
        for(Long owner:List.of(account(true,"DOCTOR_OUTP"),account(true,"ADMIN"),account(true,"NURSE","DOCTOR_OUTP"))) {
            Long id=admit(owner);em.flush();
            assertEquals(owner,jdbc.queryForObject("select doctor_id from inp_admission where id=?",Long.class,id));
            Long next=account(true,"DOCTOR_OUTP");access.setAttending(id,next);
            assertEquals(next,jdbc.queryForObject("select doctor_id from inp_admission where id=?",Long.class,id));
        }
        Long id=admit(null);em.flush();
        assertNull(jdbc.queryForObject("select doctor_id from inp_admission where id=?",Long.class,id));
    }
}
