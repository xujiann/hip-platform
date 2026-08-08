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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 1.0.6 P0 安全批回归：患者更新语义 / 端点权限 / 患者端防爆破 / 报表只读角色 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase106SecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private String admin() {
        return "Bearer " + jwtService.issue("admin");
    }

    /** P0-8：部分字段提交不得清空过敏史（合理用药拦截的数据源） */
    @Test
    void partialUpdateKeepsAllergyHistory() throws Exception {
        Patient p = new Patient();
        p.setName("部分更新106");
        p.setSex("M");
        p.setAllergyHistory("青霉素");
        p.setBloodType("A");
        Long id = patientService.register(p).getId();

        mockMvc.perform(put("/api/patients/" + id)
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"部分更新106改名\"}"))
                .andExpect(jsonPath("$.code").value(0));

        entityManager.flush();   // JPA 写入对 JdbcTemplate 可见
        var row = jdbc.queryForMap("select name, allergy_history, blood_type from empi_patient where id = ?", id);
        assertEquals("部分更新106改名", row.get("name"));
        assertEquals("青霉素", row.get("allergy_history"), "过敏史不得被静默清空");
        assertEquals("A", row.get("blood_type"));
    }

    /** P0-5：资金/库存端点须限角色——医生令牌不得退费、发药、盘点 */
    @Test
    void clinicalRoleCannotTouchMoneyOrStock() throws Exception {
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values ('doc106', '$2a$10$abcdefghijklmnopqrstuv', '权限测试医生', true)
                on conflict (username) do nothing
                """);
        Long uid = jdbc.queryForObject("select id from sys_user where username = 'doc106'", Long.class);
        Long roleId = jdbc.queryForObject("select id from sys_role where code = 'DOCTOR_OUTP'", Long.class);
        jdbc.update("insert into sys_user_role(user_id, role_id) values (?,?) on conflict do nothing", uid, roleId);
        String doctor = "Bearer " + jwtService.issue("doc106");

        mockMvc.perform(post("/api/outpatient/charges/1/refund").header("Authorization", doctor))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/outpatient/dispense/1").header("Authorization", doctor))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/stock-ins").header("Authorization", doctor))
                .andExpect(status().isForbidden());
    }

    /** P0-6：病历全文检索限临床与质控——收费员不得翻病历 */
    @Test
    void cashierCannotSearchMedicalRecords() throws Exception {
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values ('cash106', '$2a$10$abcdefghijklmnopqrstuv', '权限测试收费员', true)
                on conflict (username) do nothing
                """);
        Long uid = jdbc.queryForObject("select id from sys_user where username = 'cash106'", Long.class);
        Long roleId = jdbc.queryForObject("select id from sys_role where code = 'CASHIER'", Long.class);
        jdbc.update("insert into sys_user_role(user_id, role_id) values (?,?) on conflict do nothing", uid, roleId);

        mockMvc.perform(get("/api/cdr/search?keyword=x")
                        .header("Authorization", "Bearer " + jwtService.issue("cash106")))
                .andExpect(status().isForbidden());
    }

    /** P0-3：患者端登录失败累计到阈值即锁定 */
    @Test
    void portalLoginLocksAfterFiveFailures() throws Exception {
        Patient p = new Patient();
        p.setName("爆破测试106");
        p.setSex("F");
        p.setPhone("13900001234");
        String patientNo = patientService.register(p).getPatientNo();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/portal/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"patientNo\":\"" + patientNo + "\",\"phone\":\"00000000000\"}"))
                    .andExpect(jsonPath("$.code").value(9501));
        }
        // 第 6 次：即便密码正确也应被锁定拦截
        mockMvc.perform(post("/api/portal/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientNo\":\"" + patientNo + "\",\"phone\":\"13900001234\"}"))
                .andExpect(jsonPath("$.code").value(9503));

        Integer locked = jdbc.queryForObject(
                "select count(*) from portal_login_attempt where patient_no = ? and locked_until > now()",
                Integer.class, patientNo);
        assertEquals(1, locked);
    }

    /** P0-7：报表以只读角色执行——写序列/读系统表被数据库层拒绝 */
    @Test
    void reportRunsAsReadOnlyRole() throws Exception {
        Integer roleExists = jdbc.queryForObject(
                "select count(*) from pg_roles where rolname = 'hip_report_reader'", Integer.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(roleExists != null && roleExists > 0,
                "本库未创建 hip_report_reader（DBA 授权项），跳过");

        // 正则挡不住 setval，但只读角色会拒绝
        jdbc.update("insert into dg_report_def(name, sql_text) values ('106-setval', ?)",
                "select setval('outp_charge_id_seq', 1)");
        Long badId = jdbc.queryForObject(
                "select id from dg_report_def where name = '106-setval'", Long.class);
        mockMvc.perform(post("/api/datagov/reports/" + badId + "/run").header("Authorization", admin()))
                .andExpect(jsonPath("$.code").value(4637));

        // 正常业务表报表仍可运行
        jdbc.update("insert into dg_report_def(name, sql_text) values ('106-ok', ?)",
                "select count(*) as n from empi_patient");
        Long okId = jdbc.queryForObject("select id from dg_report_def where name = '106-ok'", Long.class);
        mockMvc.perform(post("/api/datagov/reports/" + okId + "/run").header("Authorization", admin()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rows[0].n").exists());
    }
}
