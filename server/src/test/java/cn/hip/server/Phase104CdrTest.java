package cn.hip.server;

import cn.hip.cdr.service.CdrSyncService;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 1.0.4 CDR 数据正确性专项回归：updated_at 水位 / 退号同步 / 出院诊断编码 / 住院病历签名 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase104CdrTest {

    @Autowired CdrSyncService cdrSyncService;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired InpatientService inpatientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;

    private Long newRegistration(String patientName) {
        Patient p = new Patient();
        p.setName(patientName);
        p.setSex("U");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(LocalDate.now());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        return registrationService.register(pid, s.getId()).getId();
    }

    private String docContent(Long refId) {
        return jdbc.queryForObject(
                "select content from cdr_document where doc_type = 'OUTP_ENCOUNTER' and ref_id = ?",
                String.class, refId);
    }

    /** 「创建在前、变更在后」：就诊同步后病历晚到，增量必须刷新文档（原 created_at 水位抓不到） */
    @Test
    void incrementalPicksUpLateChildChange() {
        Long rid = newRegistration("水位甲104");
        doctorStationService.startVisit(rid, null);
        entityManager.flush();
        cdrSyncService.syncIncremental();
        entityManager.flush();
        assertFalse(docContent(rid).contains("晚到主诉104"));

        OutpEmr emr = new OutpEmr();
        emr.setChiefComplaint("晚到主诉104");
        doctorStationService.saveEmr(rid, emr, List.of(), null);
        entityManager.flush();
        cdrSyncService.syncIncremental();
        entityManager.flush();
        assertTrue(docContent(rid).contains("晚到主诉104"), "子表晚到变更应被增量同步刷新");
    }

    /** 退号后文档状态必须跟进（原 status<>'CANCELLED' 过滤导致永久停留在退号前） */
    @Test
    void cancelledRegistrationRefreshesDoc() {
        Long rid = newRegistration("水位乙104");
        entityManager.flush();
        cdrSyncService.syncIncremental();
        entityManager.flush();
        assertTrue(docContent(rid).contains("REGISTERED"));

        registrationService.cancel(rid);
        entityManager.flush();
        cdrSyncService.syncIncremental();
        entityManager.flush();
        assertTrue(docContent(rid).contains("CANCELLED"), "退号状态应同步进 CDR 文档");
    }

    /** 出院诊断补录 API 与编码率新口径 */
    @Test
    void dischargeDiagApiAndCodingRate() throws Exception {
        String token = "Bearer " + jwtService.issue("admin");
        mockMvc.perform(put("/api/inpatient/admissions/999999/discharge-diag")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icd\":\"J18.9\",\"name\":\"肺炎\"}"))
                .andExpect(jsonPath("$.code").value(9106));
        mockMvc.perform(put("/api/inpatient/admissions/1/discharge-diag")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icd\":\"\",\"name\":\"x\"}"))
                .andExpect(jsonPath("$.code").value(9105));
        mockMvc.perform(get("/api/mrstats/overview").header("Authorization", token))
                .andExpect(jsonPath("$.data.dischargeCodedRate").exists());
    }

    /** 住院病历签名：签名成功、重签拦截（9103）、出院诊断随住院流转持久化 */
    @Test
    void inpRecordSignAndDischargeDiagPersistence() throws Exception {
        Patient p = new Patient();
        p.setName("签名患者104");
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        var adm = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("1000"), "CASH", null);
        entityManager.flush();
        String token = "Bearer " + jwtService.issue("admin");

        mockMvc.perform(post("/api/inpatient/admissions/" + adm.getId() + "/records")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recordType\":\"ADMISSION\",\"title\":\"入院记录\",\"content\":\"签名测试内容\"}"))
                .andExpect(jsonPath("$.code").value(0));
        Long recordId = jdbc.queryForObject(
                "select id from inp_medical_record where admission_id = ? order by id desc limit 1",
                Long.class, adm.getId());

        mockMvc.perform(post("/api/inpatient/admissions/" + adm.getId() + "/records/" + recordId + "/sign")
                        .header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.signature").isNotEmpty());
        mockMvc.perform(post("/api/inpatient/admissions/" + adm.getId() + "/records/" + recordId + "/sign")
                        .header("Authorization", token))
                .andExpect(jsonPath("$.code").value(9103));

        mockMvc.perform(put("/api/inpatient/admissions/" + adm.getId() + "/discharge-diag")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icd\":\"J18.9\",\"name\":\"肺炎(出院)\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("J18.9", jdbc.queryForObject(
                "select discharge_diag_icd from inp_admission where id = ?", String.class, adm.getId()));
    }
}
