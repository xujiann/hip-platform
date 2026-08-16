package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 1.0.9 A-2/A-3/A-4/A-5 回归：脱敏值不得回写、非 ADMIN 出口一律脱敏、打印与人事端点须限权 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase109PrivacyTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private String tokenFor(String username, String roleCode) {
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values (?, '$2a$10$abcdefghijklmnopqrstuv', ?, true)
                on conflict (username) do nothing
                """, username, username);
        Long uid = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
        Long rid = jdbc.queryForObject("select id from sys_role where code = ?", Long.class, roleCode);
        jdbc.update("insert into sys_user_role(user_id, role_id) values (?,?) on conflict do nothing", uid, rid);
        return "Bearer " + jwtService.issue(username);
    }

    private Long newPatient(String name, String phone) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        p.setPhone(phone);
        return patientService.register(p).getId();
    }

    /** A-2：脱敏串提交被拒——前端原样回存曾把真实手机号永久覆盖成 138****1111 */
    @Test
    void maskedValueIsRejectedOnUpdate() throws Exception {
        Long id = newPatient("脱敏回写109", "13800001111");
        String nurse = tokenFor("nurse109", "NURSE");

        mockMvc.perform(put("/api/patients/" + id)
                        .header("Authorization", nurse)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"138****1111\",\"address\":\"新住址\"}"))
                .andExpect(jsonPath("$.code").value(2004));

        entityManager.flush();
        assertEquals("13800001111",
                jdbc.queryForObject("select phone from empi_patient where id = ?", String.class, id),
                "真实手机号不得被脱敏串覆盖");
    }

    /** A-3：非 ADMIN 的 create/update 返回值同样脱敏——曾可用一个空 PUT 读到明文 */
    @Test
    void nonAdminNeverSeesPlaintextThroughWrite() throws Exception {
        Long id = newPatient("空PUT读明文109", "13900002222");
        String nurse = tokenFor("nurse109b", "NURSE");

        mockMvc.perform(put("/api/patients/" + id)
                        .header("Authorization", nurse)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.phone").value("139****2222"));
    }

    /** A-4：打印与日结含患者隐私与全院流水，须限权 */
    @Test
    void printAndDailyReportRequireRole() throws Exception {
        String operation = tokenFor("ops109", "OPERATION");   // 运营后勤不在打印白名单内
        mockMvc.perform(get("/api/print/charge/1").header("Authorization", operation))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/daily-settlement").header("Authorization", operation))
                .andExpect(status().isForbidden());
    }

    /** A-5：工资批量覆写曾对任意在职账号开放 */
    @Test
    void salaryImportRequiresAdmin() throws Exception {
        String nurse = tokenFor("nurse109c", "NURSE");
        // 读任意员工工资明细、批量覆写工资，护士账号一律 403
        mockMvc.perform(get("/api/hr/salaries?empNo=E001").header("Authorization", nurse))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/hr/employees").header("Authorization", nurse))
                .andExpect(status().isForbidden());
    }

    /** A-6：CDR 患者文档调阅返回病历原文，收费员不得读 */
    @Test
    void cdrDocumentsRequireClinicalRole() throws Exception {
        String cashier = tokenFor("cash109", "CASHIER");
        mockMvc.perform(get("/api/cdr/patients/2/documents").header("Authorization", cashier))
                .andExpect(status().isForbidden());
    }
}
