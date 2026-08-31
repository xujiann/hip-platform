package cn.hip.server;

import cn.hip.inpatient.service.EmrIntegrityService;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.inpatient.web.InpatientController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.qualitycare.web.NursingQualityController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v35：出院/归档病历完整性 gate（默认 warn 零打断；block 硬拦 9124/9820）。 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class EmrIntegrityGateTest {

    @Autowired InpatientService inpatientService;
    @Autowired NursingQualityController nursingQualityController;
    @Autowired InpatientController inpatientController;
    @Autowired EmrIntegrityService emrIntegrityService;
    @Autowired PatientService patientService;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

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

    private void rec(Long admId, String type, boolean signed) {
        jdbc.update("insert into inp_medical_record(admission_id, record_type, title, content, signature) "
                + "values (?,?,?,?,?)", admId, type, type, "内容", signed ? "SIG" : null);
    }

    @Test
    void warnPassesIncompleteAndEndpointListsMissing() {
        Long admId = admit("完整性warn");
        // 只读预检：不完整、列缺项
        var rep = inpatientController.emrIntegrity(admId).getData();
        assertEquals(Boolean.FALSE, rep.get("complete"));
        @SuppressWarnings("unchecked")
        var missing = (List<String>) rep.get("missing");
        assertTrue(missing.contains("缺入院记录") && missing.contains("缺出院小结"));
        assertEquals("warn", rep.get("dischargeGate"));
        // 默认 warn：不完整也能出院（零打断）
        assertEquals("PAID", inpatientService.discharge(admId, null, "CASH").getStatus());
    }

    @Test
    void blockDischargeRejectsIncompleteAllowsComplete() {
        jdbc.update("update sys_config set cfg_value='block' where cfg_key='emr.gate.discharge'");
        configReader.evict("emr.gate.discharge");

        Long bad = admit("完整性block");
        var e = assertThrows(InpException.class, () -> inpatientService.discharge(bad, null, "CASH"));
        assertEquals(9124, e.code);

        // 补齐入院记录/出院小结(已签)/病程 → 放行
        Long good = admit("完整性ok");
        rec(good, "ADMISSION", false);
        rec(good, "PROGRESS", false);
        rec(good, "DISCHARGE", true);
        assertTrue(emrIntegrityService.check(good).isEmpty(), "补齐后应完整");
        assertEquals("PAID", inpatientService.discharge(good, null, "CASH").getStatus());
    }

    @Test
    void blockArchiveRejectsIncomplete() {
        Long admId = admit("归档block");
        inpatientService.discharge(admId, null, "CASH");   // warn 出院
        jdbc.update("update sys_config set cfg_value='block' where cfg_key='emr.gate.archive'");
        configReader.evict("emr.gate.archive");
        // 不完整 → 9820
        assertEquals(9820, nursingQualityController.archive(admId).getCode());
        // 补齐 → 归档成功
        rec(admId, "ADMISSION", false);
        rec(admId, "PROGRESS", false);
        rec(admId, "DISCHARGE", true);
        assertEquals(0, nursingQualityController.archive(admId).getCode());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select archived from inp_admission where id=?", Boolean.class, admId));
    }

    @Test
    void surgeryCaseRequiresOpNotePreopConsent() {
        Long admId = admit("手术病例");
        rec(admId, "ADMISSION", false);
        rec(admId, "PROGRESS", false);
        rec(admId, "DISCHARGE", true);
        // 无手术：完整
        assertTrue(emrIntegrityService.check(admId).isEmpty());
        // 加一台未完成手术（无 op_note）
        jdbc.update("insert into inp_surgery(admission_id, procedure_name, status) values (?, '阑尾切除术', 'SCHEDULED')", admId);
        var miss = emrIntegrityService.check(admId);
        assertTrue(miss.contains("缺手术记录"), miss.toString());
        assertTrue(miss.contains("缺术前小结"), miss.toString());
        assertTrue(miss.contains("缺手术知情同意书"), miss.toString());
    }
}
