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
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
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

    // ============ v69 包 A 前置：全文检索的契约锁定 ============
    // 前端要把这个端点接到检索框上，接线前先把它的行为钉住。
    // 此前该端点只有 e2e-phase1821 走过一次（断言「命中数 > 0」），无 JUnit。

    /** 直插一份 CDR 文档（不走同步链路，检索断言要的是可控的标题与正文）。 */
    private Long cdrDoc(Long patientId, String docType, long refId, String title,
                        String content, String docTime) {
        return jdbc.queryForObject("""
                insert into cdr_document(patient_id, doc_type, ref_id, title, doc_time, content)
                values (?, ?, ?, ?, ?::timestamptz, ?) returning id
                """, Long.class, patientId, docType, refId, title, docTime, content);
    }

    private Long newPatientId(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("U");
        return patientService.register(p).getId();
    }

    /** 检索命中标题与正文两侧，且按文档时间倒序。 */
    @Test
    void fullTextSearchMatchesTitleAndContentNewestFirst() throws Exception {
        Long pid = newPatientId("V69检索甲");
        cdrDoc(pid, "OUTP_ENCOUNTER", 990001L, "V69标题含关键词肺炎",
                "正文无关", "2026-01-01T10:00:00+08:00");
        cdrDoc(pid, "OUTP_ENCOUNTER", 990002L, "V69标题无关",
                "正文含关键词肺炎的描述", "2026-02-01T10:00:00+08:00");
        cdrDoc(pid, "OUTP_ENCOUNTER", 990003L, "V69都不含",
                "也不含", "2026-03-01T10:00:00+08:00");
        entityManager.flush();

        mockMvc.perform(get("/api/cdr/search").param("keyword", "关键词肺炎"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].refId").value(990002))   // 2 月，更晚
                .andExpect(jsonPath("$.data[1].refId").value(990001))   // 1 月
                .andExpect(jsonPath("$.data[0].title").value("V69标题无关"))
                .andExpect(jsonPath("$.data[0].patientId").value(pid))
                .andExpect(jsonPath("$.data[0].docType").value("OUTP_ENCOUNTER"));
    }

    /**
     * <b>接线前必须知道的两条行为</b>（本用例不是在主张它们正确，是把现状钉住）：
     *
     * <p>① <b>空关键字匹配全部</b>——{@code like '%%'} 恒真。前端检索框清空后点检索，
     * 会拿回最近 50 份文档而不是空结果。前端必须自己挡住空串，不能依赖后端。
     *
     * <p>② <b>关键字里的 % 与 _ 是通配符，未转义</b>——用户输入单个 {@code %} 等于匹配全部。
     * 这不是注入（走的是绑定参数），但会让检索结果与用户预期不符。
     */
    @Test
    void blankKeywordAndWildcardCharsMatchEverything_frontendMustGuard() throws Exception {
        Long pid = newPatientId("V69检索乙");
        cdrDoc(pid, "OUTP_ENCOUNTER", 990011L, "V69甲文档", "甲正文", "2026-01-01T10:00:00+08:00");
        cdrDoc(pid, "OUTP_ENCOUNTER", 990012L, "V69乙文档", "乙正文", "2026-01-02T10:00:00+08:00");
        entityManager.flush();

        int blankHits = hits("");
        int pctHits = hits("%");
        int realHits = hits("V69甲文档");

        assertTrue(blankHits >= 2, "空关键字匹配全部（现状，前端须自行挡住空串）：" + blankHits);
        assertTrue(pctHits >= 2, "% 是未转义的通配符，匹配全部（现状）：" + pctHits);
        assertEquals(1, realHits, "真实关键字只命中一份");
    }

    /** 单次返回上限 50（端点内硬编码的分页大小），前端不得假定拿到的是全集。 */
    @Test
    void searchReturnsAtMostFiftyRowsPerCall() throws Exception {
        Long pid = newPatientId("V69检索丙");
        for (int i = 0; i < 55; i++) {
            cdrDoc(pid, "OUTP_ENCOUNTER", 991000L + i, "V69分页上限样本" + i,
                    "同一批", String.format("2026-01-%02dT10:00:00+08:00", (i % 28) + 1));
        }
        entityManager.flush();
        assertEquals(50, hits("V69分页上限样本"), "端点单次最多回 50 条，超出部分取不到");
    }

    private int hits(String keyword) throws Exception {
        String json = mockMvc.perform(get("/api/cdr/search").param("keyword", keyword))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.data.length()");
    }
}
