package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.ConsentController;
import cn.hip.inpatient.web.ConsentController.ConsentReq;
import cn.hip.medtech.web.MedTechController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** v34：知情同意书双签流转 + 手术/输血同意 gate（试点期可配 warn/block）。 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class ConsentFlowTest {

    @Autowired ConsentController consentController;
    @Autowired MedTechController medTechController;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private final Authentication admin =
            new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    @AfterEach
    void evict() {
        configReader.evictAll();   // 直写 sys_config 会给缓存下毒，测试后清（方法论⑤）
    }

    private Long admit() {
        Patient p = new Patient();
        p.setName("同意书" + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎", new BigDecimal("500"), "CASH", null).getId();
    }

    @SuppressWarnings("unchecked")
    private Long createConsent(Long admId, String type) {
        var r = consentController.create(new ConsentReq(admId, null, type, null, "同意内容", null, null, null, null), admin);
        assertEquals(0, r.getCode());
        return ((Number) r.getData().get("id")).longValue();
    }

    @Test
    void consentDoubleSignFlow() {
        Long admId = admit();
        Long cid = createConsent(admId, "SURGERY");
        // DRAFT → 患者签 → PATIENT_SIGNED
        assertEquals(0, consentController.patientSign(cid, new ConsentController.PatientSignReq("患者张三")).getCode());
        assertEquals("PATIENT_SIGNED", jdbc.queryForObject("select status from emr_consent where id=?", String.class, cid));
        // 医师 CA 签 → SIGNED
        assertEquals(0, consentController.doctorSign(cid, admin).getCode());
        var row = jdbc.queryForMap("select status, doctor_sign from emr_consent where id=?", cid);
        assertEquals("SIGNED", row.get("status"));
        assertNotNull(row.get("doctor_sign"), "医师 CA 签名值应写入");
    }

    @Test
    void consentValidation() {
        Long admId = admit();
        // 类型非法 9110
        assertEquals(9110, consentController.create(
                new ConsentReq(admId, null, "NOPE", null, "x", null, null, null, null), admin).getCode());
        // 内容空 9111
        assertEquals(9111, consentController.create(
                new ConsentReq(admId, null, "SURGERY", null, "  ", null, null, null, null), admin).getCode());
        // 授权委托缺委托信息 9113
        assertEquals(9113, consentController.create(
                new ConsentReq(admId, null, "PROXY", null, "委托", null, null, null, null), admin).getCode());
        // 未患者签先医师签 → 9114
        Long cid = createConsent(admId, "SURGERY");
        assertEquals(9114, consentController.doctorSign(cid, admin).getCode());
    }

    @Test
    void surgeryGateWarnByDefaultBlockWhenConfigured() {
        Long admId = admit();
        // 默认 warn：无同意书也放行（返回 success + warning），不挡历史流
        var warn = medTechController.requestSurgery(
                new MedTechController.SurgeryReq(admId, "阑尾切除术", "全麻", null, "47.0"), admin);
        assertEquals(0, warn.getCode());
        assertTrue(warn.getData() != null && warn.getData().containsKey("warning"), "warn 模式应带 warning");

        // 收紧为 block：无有效手术同意书 → 9116
        jdbc.update("update sys_config set cfg_value='block' where cfg_key='emr.gate.consent.surgery'");
        configReader.evict("emr.gate.consent.surgery");
        assertEquals(9116, medTechController.requestSurgery(
                new MedTechController.SurgeryReq(admId, "阑尾切除术", "全麻", null, "47.0"), admin).getCode());

        // 补一份 SIGNED 手术同意书后放行
        Long cid = createConsent(admId, "SURGERY");
        consentController.patientSign(cid, new ConsentController.PatientSignReq("患者"));
        consentController.doctorSign(cid, admin);
        em.flush();
        assertEquals(0, medTechController.requestSurgery(
                new MedTechController.SurgeryReq(admId, "阑尾切除术", "全麻", null, "47.0"), admin).getCode(),
                "有有效同意书应放行");
    }
}
