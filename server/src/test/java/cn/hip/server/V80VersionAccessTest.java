package cn.hip.server;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * v80：病历版本读出端点对象级权限（v79 甲组审计 R3）+ 诊断助手过滤停用诊断（993★ 第三轮遗留 / 979★）。
 *
 * <p>版本端点此前只有类级角色门槛：门诊医生能读任何患者的病历全文与诊断历史，列表还带回患者姓名。
 * 现口径与引用抽屉 {@code EmrRefController#canRead} 相同——ADMIN / QUALITY 全看，归属为空放行，其余须本人，否则 4036。
 * 用 MockMvc 走完整鉴权链；两个医生是真实落库的账号（{@code currentUserService.idOf} 按用户名查库）。
 *
 * <p>全部 {@code @Transactional}：只断言返回码与返回体，不测回滚语义（方法论④）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class V80VersionAccessTest {

    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String newDoctor(String tag) {
        String username = "v80" + tag + System.nanoTime() % 100000;
        Long id = jdbc.queryForObject(
                "insert into sys_user(username, password, real_name, dept_id, enabled) values (?, 'x', ?, 1, true) returning id",
                Long.class, username, username + "医生");
        jdbc.update("insert into sys_user_role(user_id, role_id) select ?, r.id from sys_role r where r.code = 'DOCTOR_OUTP'", id);
        return username;
    }

    private Long idOf(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    /** 挂号 → 由 owner 接诊 → owner 暂存病历（含一条诊断）；返回挂号 id */
    private Long visitBy(String owner, String icdCode, String icdName) {
        Patient p = new Patient();
        p.setName("V80" + System.nanoTime() % 100000);
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        Long ownerId = idOf(owner);
        doctorStationService.startVisit(rid, ownerId);
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint("咳嗽三天");
        e.setPresentIllness("受凉后咳嗽");
        OutpDiagnosis d = new OutpDiagnosis();
        d.setIcdCode(icdCode);
        d.setIcdName(icdName);
        doctorStationService.saveEmr(rid, e, List.of(d), ownerId, null, null);
        em.flush();
        em.clear();
        return rid;
    }

    private JsonNode getAs(String url, String username, String role) throws Exception {
        String body = mvc.perform(get(url).with(user(username).roles(role)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(body);
    }

    private Long emrIdOf(Long rid) {
        return jdbc.queryForObject("select id from outp_emr where registration_id = ?", Long.class, rid);
    }

    @Test
    void nonOwnerDoctorIsRefusedOnEveryOutpatientVersionEndpoint() throws Exception {
        String a = newDoctor("A"), b = newDoctor("B");
        Long rid = visitBy(a, "J06.900", "急性上呼吸道感染");
        Long emrId = emrIdOf(rid);
        List<String> urls = List.of(
                "/api/emr/versions/OUTP/" + emrId,
                "/api/emr/versions/OUTP/" + emrId + "/versions/1",
                "/api/emr/versions/OUTP/" + emrId + "/compare?from=1&to=1",
                "/api/emr/versions/OUTP/" + emrId + "/compare-current?from=1",
                "/api/emr/versions/diagnoses/" + rid);
        for (String url : urls) {
            JsonNode denied = getAs(url, b, "DOCTOR_OUTP");
            assertEquals(4036, denied.get("code").asInt(), "他人接诊的就诊须 4036：" + url + " → " + denied);
            assertTrue(denied.get("data") == null || denied.get("data").isNull(), "被拒时不得带回任何数据：" + url);
        }
        // 本人与质控、管理员照常可读
        assertEquals(0, getAs("/api/emr/versions/OUTP/" + emrId, a, "DOCTOR_OUTP").get("code").asInt());
        assertEquals(0, getAs("/api/emr/versions/diagnoses/" + rid, a, "DOCTOR_OUTP").get("code").asInt());
        assertEquals(0, getAs("/api/emr/versions/OUTP/" + emrId, "v80quality", "QUALITY").get("code").asInt());
        assertEquals(0, getAs("/api/emr/versions/diagnoses/" + rid, "v80admin", "ADMIN").get("code").asInt());
    }

    @Test
    void ownerlessEncounterAndMissingRecordKeepPreviousBehaviour() throws Exception {
        String b = newDoctor("C");
        // 归属为空（startVisit 传 null，V79 等既有用例的造数方式）→ 放行，与引用抽屉同口径
        Patient p = new Patient();
        p.setName("V80无主" + System.nanoTime() % 100000);
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        assertEquals(0, getAs("/api/emr/versions/diagnoses/" + rid, b, "DOCTOR_OUTP").get("code").asInt());
        // 不存在的病历不在权限这一层判，返回体与 v79 一致（不因新校验变成 4036）
        assertNotEquals(4036, getAs("/api/emr/versions/OUTP/987654321", b, "DOCTOR_OUTP").get("code").asInt());
    }

    @Test
    void inpatientRecordFollowsAttendingDoctor() throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select m.id as rec_id, a.doctor_id from inp_medical_record m join inp_admission a on a.id = m.admission_id
                where a.doctor_id is not null order by m.id desc limit 1
                """);
        Assumptions.assumeFalse(rows.isEmpty(), "用例库无带主管医生的住院病历，跳过（E2E 另覆盖）");
        Long recId = ((Number) rows.get(0).get("rec_id")).longValue();
        Long attending = ((Number) rows.get(0).get("doctor_id")).longValue();
        String attendingName = jdbc.queryForObject("select username from sys_user where id = ?", String.class, attending);
        String other = newDoctor("D");
        assertEquals(4036, getAs("/api/emr/versions/INP/" + recId, other, "DOCTOR_OUTP").get("code").asInt());
        assertNotEquals(4036, getAs("/api/emr/versions/INP/" + recId, attendingName, "DOCTOR_OUTP").get("code").asInt());
    }

    @Test
    void diagnosisAssistHidesDisabledCodesButKeepsUncodedDiagnoses() {
        String a = newDoctor("E");
        String code = "V80Z" + System.nanoTime() % 1000;
        jdbc.update("insert into md_icd10(code, name, pinyin) values (?, 'V80停用验证诊断', 'VTYYZZD')", code);
        Long rid = visitBy(a, code, "V80停用验证诊断");
        Long pid = jdbc.queryForObject("select patient_id from outp_registration where id = ?", Long.class, rid);
        Long aid = idOf(a);

        var before = doctorStationService.diagnosisAssist(pid, null, aid);
        assertTrue(contains(before, "history", code), "启用时历史页签可见");
        assertTrue(contains(before, "favorite", code), "启用时常用页签可见（保存病历自动累加）");
        assertTrue(contains(before, "frequent", code), "启用时高频页签可见");

        jdbc.update("update md_icd10 set enabled = false where code = ?", code);
        var after = doctorStationService.diagnosisAssist(pid, null, aid);
        for (String tab : List.of("history", "favorite", "frequent")) {
            assertFalse(contains(after, tab, code), "停用后 " + tab + " 页签不得再推荐：" + after.get(tab));
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean contains(Map<String, Object> assist, String tab, String code) {
        return ((List<Map<String, Object>>) assist.get(tab)).stream().anyMatch(r -> code.equals(r.get("icdCode")));
    }
}
