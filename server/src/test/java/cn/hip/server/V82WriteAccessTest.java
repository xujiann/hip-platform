package cn.hip.server;

import cn.hip.outpatient.entity.*;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class V82WriteAccessTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired DoctorStationService service;
    @Autowired RegistrationService registrations;
    @Autowired OutpScheduleRepository schedules;
    @Autowired PatientService patients;
    String a, b;
    Long aid, bid;

    @BeforeEach void doctors() {
        a = "v82a" + System.nanoTime(); b = "v82b" + System.nanoTime();
        aid = newUser(a); bid = newUser(b);
    }
    private Long newUser(String name) {
        return jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,1,true) returning id", Long.class, name, name);
    }
    private Long patient() {
        Patient p = new Patient(); p.setName("V82" + System.nanoTime()); p.setSex("M");
        return patients.register(p).getId();
    }
    private Long visit(boolean past, boolean written) {
        OutpSchedule s = new OutpSchedule(); s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO); s.setCapacity(10); s = schedules.save(s);
        Long id = registrations.register(patient(), s.getId()).getId();
        service.startVisit(id, aid);
        if (written) {
            OutpEmr e = new OutpEmr(); e.setChiefComplaint("原正文"); e.setPresentIllness("原现病史");
            service.saveEmr(id, e, List.of(), aid);
        }
        em.flush();
        if (past) jdbc.update("update outp_registration set visit_date = current_date - 1 where id = ?", id);
        em.clear(); return id;
    }
    private int request(String method, String url, Object body, String who, String role) throws Exception {
        var req = switch (method) { case "POST" -> post(url); case "PUT" -> put(url); default -> get(url); };
        req.with(user(who).roles(role));
        if (body != null) req.contentType("application/json").content(om.writeValueAsString(body));
        String raw = mvc.perform(req).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(raw).path("code").asInt();
    }
    private Map<String,Object> emrBody() {
        return Map.of("emr",Map.of("chiefComplaint","越权覆盖","presentIllness","篡改"),"diagnoses",List.of());
    }

    @Test void historicalForeignVisitRejectsEveryWriteBeforeSideEffects() throws Exception {
        Long id = visit(true,true);
        Long order = jdbc.queryForObject("""
            insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status)
            values (?,'V82','LAB',1,'V82','权限夹具','次',1,0,0,'CREATED') returning id
            """,Long.class,id);
        String base = "/api/outpatient/doctor/"+id;
        assertEquals(4036,request("POST",base+"/start",Map.of(),b,"DOCTOR_OUTP"));
        assertEquals(4036,request("PUT",base+"/emr",emrBody(),b,"DOCTOR_OUTP"));
        assertEquals(4036,request("POST",base+"/emr/sign",Map.of(),b,"DOCTOR_OUTP"));
        assertEquals(4036,request("POST",base+"/emr/amend",Map.of("amendText","坏","reason","坏"),b,"DOCTOR_OUTP"));
        assertEquals(4036,request("POST",base+"/orders",Map.of("lines",List.of()),b,"DOCTOR_OUTP"));
        assertEquals(4036,request("PUT","/api/outpatient/doctor/orders/"+order+"/cancel",null,b,"DOCTOR_OUTP"));
        assertEquals("原正文",jdbc.queryForObject("select chief_complaint from outp_emr where registration_id=?",String.class,id));
        assertEquals(aid,jdbc.queryForObject("select doctor_id from outp_emr where registration_id=?",Long.class,id));
        assertEquals("CREATED",jdbc.queryForObject("select status from outp_order where id=?",String.class,order));
        assertEquals(0,jdbc.queryForObject("select count(*) from emr_amendment where emr_type='OUTP' and emr_id=(select id from outp_emr where registration_id=?)",Integer.class,id));
    }

    @Test void sharedTodayVisitDoesNotPermitTakingOverAnExistingDraft() throws Exception {
        Long id=visit(false,true);
        assertEquals(4023,request("PUT","/api/outpatient/doctor/"+id+"/emr",emrBody(),b,"DOCTOR_OUTP"));
        assertEquals(aid,jdbc.queryForObject("select doctor_id from outp_emr where registration_id=?",Long.class,id));
        assertEquals(0,request("PUT","/api/outpatient/doctor/"+id+"/emr",emrBody(),a,"DOCTOR_OUTP"));
        assertEquals(0,request("PUT","/api/outpatient/doctor/"+id+"/emr",emrBody(),"admin","ADMIN"));
        assertEquals(aid,jdbc.queryForObject("select doctor_id from outp_emr where registration_id=?",Long.class,id),"管理员修改也不能改写原书写人");
        assertEquals(0,request("POST","/api/outpatient/doctor/"+id+"/emr/sign",Map.of(),a,"DOCTOR_OUTP"));
        assertEquals(4008,request("PUT","/api/outpatient/doctor/"+id+"/emr",emrBody(),a,"DOCTOR_OUTP"));
        assertEquals(4023,request("POST","/api/outpatient/doctor/"+id+"/emr/amend",Map.of("amendText","他人补正","reason","测试"),b,"DOCTOR_OUTP"));
    }

    @Test void coveringDoctorMayWriteTheFirstDraftOfTodaysSharedVisit() throws Exception {
        Long id=visit(false,false);
        assertEquals(0,request("PUT","/api/outpatient/doctor/"+id+"/emr",emrBody(),b,"DOCTOR_OUTP"));
        assertEquals(bid,jdbc.queryForObject("select doctor_id from outp_emr where registration_id=?",Long.class,id));
    }

    @Test void editingLegacyUnknownAuthorDoesNotInventItsOriginalWriter() throws Exception {
        Long id=visit(false,true);
        jdbc.update("update outp_emr set doctor_id=null where registration_id=?",id);
        em.clear();
        assertEquals(0,request("PUT","/api/outpatient/doctor/"+id+"/emr",emrBody(),"admin","ADMIN"));
        assertNull(jdbc.queryForObject("select doctor_id from outp_emr where registration_id=?",Long.class,id));
    }

    private Long admission() {
        Long dept=jdbc.queryForObject("select ward_id from inp_bed order by id limit 1",Long.class);
        Long bed=jdbc.queryForObject("select id from inp_bed order by id limit 1",Long.class);
        return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status,admit_at) values (?,?,1,?,?,?,'IN_HOSPITAL',now()) returning id",Long.class,"V82"+System.nanoTime(),patient(),dept,bed,aid);
    }
    private Long record(Long adm) {
        return jdbc.queryForObject("insert into inp_medical_record(admission_id,record_type,title,content,doctor_id) values (?,'PROGRESS','V82','原住院正文',?) returning id",Long.class,adm,aid);
    }

    @Test void inpatientRecordRejectsOtherWriterAndNurseButAllowsItsAuthor() throws Exception {
        Long adm=admission(), rid=record(adm);
        String base="/api/inpatient/admissions/"+adm+"/records/"+rid;
        assertEquals(9130,request("PUT",base,Map.of("content","越权正文"),b,"DOCTOR_OUTP"));
        assertEquals(9130,request("POST",base+"/sign",Map.of(),b,"DOCTOR_OUTP"));
        assertNotEquals(0,request("PUT",base,Map.of("content","护士覆盖"),b,"NURSE"));
        assertEquals("原住院正文",jdbc.queryForObject("select content from inp_medical_record where id=?",String.class,rid));
        assertEquals(0,request("PUT",base,Map.of("content","本人修改"),a,"DOCTOR_OUTP"));
        assertEquals(0,request("POST",base+"/sign",Map.of(),a,"DOCTOR_OUTP"));
        assertEquals(9130,request("POST",base+"/amend",Map.of("amendText","越权补正","reason","测试"),b,"DOCTOR_OUTP"));
        assertEquals(9103,request("PUT",base,Map.of("content","签后修改"),a,"DOCTOR_OUTP"));
        assertEquals("本人修改",jdbc.queryForObject("select content from inp_medical_record where id=?",String.class,rid));
    }

    @Test void admissionWriterGateDoesNotBlockNursingVitalReads() throws Exception {
        Long adm=admission(); String base="/api/inpatient/admissions/"+adm;
        assertEquals(9130,request("POST",base+"/records",Map.of("content","其他主管病历"),b,"DOCTOR_OUTP"));
        assertNotEquals(0,request("POST",base+"/records",Map.of("content","护士病历"),b,"NURSE"));
        assertEquals(0,request("GET",base+"/vitals",null,b,"NURSE"));
        assertEquals(0,request("POST",base+"/vitals",Map.of("temperature",36.5,"pulse",80),b,"NURSE"));
        assertEquals(0,request("POST",base+"/records",Map.of("content","主管新建"),a,"DOCTOR_OUTP"));
    }
}
