package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpEmrController;
import cn.hip.inpatient.web.InpEmrController.RoundRequest;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.qualitycare.web.NursingQualityController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v34：三级查房结构化记录 + 病历时限质控扩展（查房时限）。 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class InpWardRoundTest {

    @Autowired InpEmrController emrController;
    @Autowired NursingQualityController qualityController;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    @AfterEach
    void evict() {
        configReader.evictAll();
    }

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎", new BigDecimal("500"), "CASH", null).getId();
    }

    @Test
    void wardRoundStructuredRecordAndListing() {
        Long admId = admit("查房");
        assertEquals(0, emrController.addRound(admId, new RoundRequest("CHIEF", "病情平稳，继续治疗", "同意住院医意见", null), admin).getCode());
        assertEquals(0, emrController.addRound(admId, new RoundRequest("ATTENDING", "调整抗生素", null, null), admin).getCode());
        assertEquals(0, emrController.addRound(admId, new RoundRequest("RESIDENT", "患者诉好转", null, null), admin).getCode());
        em.flush();

        var rounds = emrController.rounds(admId, null).getData();
        assertEquals(3, rounds.size(), "三级查房各一条");
        assertEquals(1, emrController.rounds(admId, "CHIEF").getData().size(), "按级别过滤");

        // 泛型读取兼容：ROUND 记录出现在病历列表中（未破坏既有 record_type 读取）
        assertTrue(emrController.records(admId).getData().stream()
                .anyMatch(r -> "ROUND".equals(r.getRecordType())), "查房记录应纳入病历列表");
    }

    @Test
    void wardRoundValidation() {
        Long admId = admit("查房校验");
        assertEquals(9119, emrController.addRound(admId, new RoundRequest("BOSS", "x", null, null), admin).getCode());
        assertEquals(9120, emrController.addRound(admId, new RoundRequest("CHIEF", "  ", null, null), admin).getCode());
    }

    @Test
    void emrTimelinessExposesNewKeysAndRoundCheck() {
        var data = qualityController.emrTimeliness().getData();
        // 新键存在 + 原键保留（向后兼容）
        for (String k : List.of("missingAdmissionRecord", "missingDischargeSummary", "unsignedOutpEmrCount",
                "missingFirstProgress", "missingRound", "roundCheckEnabled", "progressContinuityDefect",
                "rescueLateRecord", "defectTotal", "defectBreakdown")) {
            assertTrue(data.containsKey(k), "缺键 " + k);
        }
        // 默认 roundCheck 关闭
        assertEquals(Boolean.FALSE, data.get("roundCheckEnabled"));

        // 造一个入院>48h 且无查房的在院患者，开查房核查后应进 missingRound；补查房后消失
        Long admId = admit("查房时限");
        jdbc.update("update inp_admission set admit_at = now() - interval '49 hours' where id = ?", admId);
        String admNo = jdbc.queryForObject("select admission_no from inp_admission where id=?", String.class, admId);
        jdbc.update("update sys_config set cfg_value='on' where cfg_key='emr.timeliness.round_check.enabled'");
        configReader.evict("emr.timeliness.round_check.enabled");

        @SuppressWarnings("unchecked")
        var missing = (List<Map<String, Object>>) qualityController.emrTimeliness().getData().get("missingRound");
        assertTrue(missing.stream().anyMatch(m -> admNo.equals(m.get("admission_no"))), "无查房的在院患者应进 missingRound");

        emrController.addRound(admId, new RoundRequest("ATTENDING", "查房意见", null, null), admin);
        em.flush();
        @SuppressWarnings("unchecked")
        var missing2 = (List<Map<String, Object>>) qualityController.emrTimeliness().getData().get("missingRound");
        assertFalse(missing2.stream().anyMatch(m -> admNo.equals(m.get("admission_no"))), "补查房后应消失");
    }
}
