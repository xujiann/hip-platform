package cn.hip.server;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RxTemplateService.TemplateLine;
import cn.hip.outpatient.service.RxTemplateService.TemplateReq;
import cn.hip.outpatient.web.RxTemplateController;
import cn.hip.platform.core.common.R;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * v80 审阅修补（v80 复核·方案三审计 D1–D5、D8）。
 *
 * <ul>
 *   <li><b>D1</b> 诊断助手（带 patientId）与患者历次就诊只有类级角色门槛，任意门诊医生按 patientId 读任意患者的历史诊断、
 *       主诉与处理意见。口径与 v80 版本端点一致：ADMIN / QUALITY 全看；其余须本人至少有一条该患者未退号的挂号，否则 4036 且不带数据。
 *       只传 keyword 的常用 / 高频部分不受影响。</li>
 *   <li><b>D8</b> 患者历次就诊的诊断只回编码 / 名称 / 主诊断，抽屉里疑诊读作确诊——追加前缀、后缀、确诊疑诊、自定义描述、诊断体系五键，既有三键不动。</li>
 *   <li><b>D3 / D4</b> 文案：协定处方「药师可建档」「已开处方可追溯旧版」两句不实（不改权限）。</li>
 *   <li><b>D2 / D5</b> 医生站撤组与单行移除：纯逻辑在 {@code utils/rx-template-apply.ts}（vitest 对拍），这里只钉页面接线。</li>
 * </ul>
 *
 * <p>MockMvc 走完整鉴权链；医生是真实落库账号（{@code currentUserService.idOf} 按用户名查库）。
 * 全部 {@code @Transactional}：只断言返回码与返回体，不测回滚语义（方法论④）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class V80ReviewFixTest {

    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired RxTemplateController rxTemplateController;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    // ---------------- 夹具 ----------------

    private String newUser(String tag, Long deptId, String role) {
        String username = "v80f" + tag + System.nanoTime() % 100000;
        Long id = deptId == null
                ? jdbc.queryForObject(
                        "insert into sys_user(username, password, real_name, enabled) values (?, 'x', ?, true) returning id",
                        Long.class, username, username + "用户")
                : jdbc.queryForObject(
                        "insert into sys_user(username, password, real_name, dept_id, enabled) values (?, 'x', ?, ?, true) returning id",
                        Long.class, username, username + "用户", deptId);
        jdbc.update("insert into sys_user_role(user_id, role_id) select ?, r.id from sys_role r where r.code = ?", id, role);
        return username;
    }

    private String newDoctor(String tag) {
        return newUser(tag, 1L, "DOCTOR_OUTP");
    }

    private Long idOf(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private Long newPatient() {
        Patient p = new Patient();
        p.setName("V80F" + System.nanoTime() % 100000);
        p.setSex("M");
        return patientService.register(p).getId();
    }

    /** 挂 doctor 名下的号（排班带医生，挂号即归属该医生）；doctor 为空则挂科室号（归属为空） */
    private Long registerFor(Long pid, String doctor) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        if (doctor != null) s.setDoctorId(idOf(doctor));
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        return registrationService.register(pid, s.getId()).getId();
    }

    /** owner 名下挂号 → owner 接诊 → owner 暂存病历（诊断按给定） */
    private Long visitBy(Long pid, String owner, List<OutpDiagnosis> diags) {
        Long rid = registerFor(pid, owner);
        Long ownerId = idOf(owner);
        doctorStationService.startVisit(rid, ownerId);
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint("V80F咽痛两天");
        e.setAdvice("V80F对症处理");
        doctorStationService.saveEmr(rid, e, diags, ownerId, null, null);
        em.flush();
        em.clear();
        return rid;
    }

    private static OutpDiagnosis diag(String code, String name) {
        OutpDiagnosis d = new OutpDiagnosis();
        d.setIcdCode(code);
        d.setIcdName(name);
        return d;
    }

    private JsonNode getAs(String url, String username, String role) throws Exception {
        String body = mvc.perform(get(url).with(user(username).roles(role)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(body);
    }

    private static boolean noData(JsonNode r) {
        return r.get("data") == null || r.get("data").isNull();
    }

    // ==================== D1：诊断助手历史段 / 患者历次就诊的对象级权限 ====================

    @Test
    void d1_nonTreatingDoctorIsRefusedOnPatientHistoryAndAssistHistory() throws Exception {
        String a = newDoctor("A"), b = newDoctor("B");
        Long pid = newPatient();
        visitBy(pid, a, List.of(diag("J02.900", "急性咽炎")));
        String assistUrl = "/api/outpatient/doctor/diagnosis-assist?patientId=" + pid;
        String historyUrl = "/api/outpatient/doctor/patient/" + pid + "/history";

        for (String url : List.of(assistUrl, historyUrl)) {
            JsonNode denied = getAs(url, b, "DOCTOR_OUTP");
            assertEquals(4036, denied.get("code").asInt(), "非接诊医生须 4036：" + url + " → " + denied);
            assertTrue(noData(denied), "被拒时不得带回任何数据：" + url + " → " + denied);
            assertFalse(denied.toString().contains("V80F咽痛两天"), "被拒时不得带回主诉");
        }

        // 接诊医生本人照常：历史诊断、主诉、处理意见都在
        JsonNode mineAssist = getAs(assistUrl, a, "DOCTOR_OUTP");
        assertEquals(0, mineAssist.get("code").asInt(), mineAssist.toString());
        assertEquals("J02.900", mineAssist.at("/data/history/0/icdCode").asText());
        JsonNode mineHist = getAs(historyUrl, a, "DOCTOR_OUTP");
        assertEquals(0, mineHist.get("code").asInt(), mineHist.toString());
        assertEquals("V80F咽痛两天", mineHist.at("/data/0/chiefComplaint").asText());

        // 管理员照常
        assertEquals(0, getAs(assistUrl, "v80fadmin", "ADMIN").get("code").asInt());
        assertEquals(0, getAs(historyUrl, "v80fadmin", "ADMIN").get("code").asInt());
    }

    @Test
    void d1_keywordOnlyAssistIsNotPatientDataAndStaysOpen() throws Exception {
        String a = newDoctor("C"), b = newDoctor("D");
        Long pid = newPatient();
        visitBy(pid, a, List.of(diag("J02.900", "急性咽炎")));
        // 只传 keyword（不传 patientId）：常用按登录人、高频按全院聚合，不是患者数据——非接诊医生照常取
        JsonNode r = getAs("/api/outpatient/doctor/diagnosis-assist?keyword=" + java.net.URLEncoder.encode("咽炎", StandardCharsets.UTF_8),
                b, "DOCTOR_OUTP");
        assertEquals(0, r.get("code").asInt(), r.toString());
        assertTrue(r.at("/data/history").isArray() && r.at("/data/history").isEmpty(), "不传 patientId 时历史段为空");
        assertTrue(r.at("/data/favorite").isArray());
        assertTrue(r.at("/data/frequent").isArray());
    }

    @Test
    void d1_onlyNonCancelledRegistrationsUnderMyNameGrantAccess() throws Exception {
        String a = newDoctor("E"), b = newDoctor("F");
        Long pid = newPatient();
        visitBy(pid, a, List.of(diag("J02.900", "急性咽炎")));
        String historyUrl = "/api/outpatient/doctor/patient/" + pid + "/history";
        String assistUrl = "/api/outpatient/doctor/diagnosis-assist?patientId=" + pid;

        // b 名下有一条该患者的挂号但已退号 → 仍不算接诊过
        Long ridB = registerFor(pid, b);
        jdbc.update("update outp_registration set status = 'CANCELLED' where id = ?", ridB);
        em.clear();
        assertEquals(4036, getAs(historyUrl, b, "DOCTOR_OUTP").get("code").asInt(), "退号不授予查阅权");
        assertEquals(4036, getAs(assistUrl, b, "DOCTOR_OUTP").get("code").asInt(), "退号不授予查阅权");

        // 未退号（已挂号、尚未接诊）即可——医生站点开自己队列里的患者先看既往，本就是接诊前的动作
        jdbc.update("update outp_registration set status = 'REGISTERED' where id = ?", ridB);
        em.clear();
        assertEquals(0, getAs(historyUrl, b, "DOCTOR_OUTP").get("code").asInt());
        assertEquals(0, getAs(assistUrl, b, "DOCTOR_OUTP").get("code").asInt());
    }

    // ==================== D8：患者历次就诊带出前缀 / 后缀 / 疑诊 / 自定义描述 / 体系 ====================

    @Test
    void d8_patientHistoryAppendsQualifierKeysAndKeepsTheOldThree() throws Exception {
        String a = newDoctor("G");
        Long pid = newPatient();
        OutpDiagnosis west = diag("J02.900", "急性咽炎");
        west.setPrimaryDiag(true);
        west.setPrefix("复发性");
        west.setSuffix("伴发热");
        west.setCertainty(OutpDiagnosis.CERTAINTY_SUSPECTED);
        west.setCustomName("咽痛待查");
        west.setDiagSystem(OutpDiagnosis.SYSTEM_ICD10);
        OutpDiagnosis tcm = diag("", "感冒（风寒束表证）");
        tcm.setDiagSystem(OutpDiagnosis.SYSTEM_TCM);
        visitBy(pid, a, List.of(west, tcm));

        JsonNode r = getAs("/api/outpatient/doctor/patient/" + pid + "/history", a, "DOCTOR_OUTP");
        assertEquals(0, r.get("code").asInt(), r.toString());
        JsonNode diags = r.at("/data/0/diagnoses");
        assertEquals(2, diags.size(), diags.toString());
        JsonNode w = null, t = null;
        for (JsonNode d : diags) {
            if ("J02.900".equals(d.get("icdCode").asText())) w = d; else t = d;
        }
        assertNotNull(w, diags.toString());
        assertNotNull(t, diags.toString());
        // 既有三键原样
        assertEquals("急性咽炎", w.get("icdName").asText());
        assertTrue(w.get("primaryDiag").asBoolean());
        assertEquals("", t.get("icdCode").asText(), "中医诊断编码仍回空串（既有口径）");
        // 追加五键
        assertEquals("复发性", w.path("prefix").asText(null), diags.toString());
        assertEquals("伴发热", w.path("suffix").asText(null));
        assertEquals("SUSPECTED", w.path("certainty").asText(null));
        assertEquals("咽痛待查", w.path("customName").asText(null));
        assertEquals("ICD10", w.path("diagSystem").asText(null));
        assertEquals("TCM", t.path("diagSystem").asText(null));
        assertTrue(t.has("certainty") && t.get("certainty").isNull(), "未标确诊/疑诊的回 null，不默认确诊：" + t);
    }

    @Test
    void d8_historyDrawerFormatsDiagnosesWithThePrintHelper() {
        String ds = read("frontend/shell/src/views/outpatient/DoctorStationView.vue");
        int a = ds.indexOf("<el-drawer v-model=\"historyVisible\"");
        int b = ds.indexOf("</el-drawer>", a);
        assertTrue(a > 0 && b > a, "找不到历史就诊抽屉");
        String drawer = ds.substring(a, b);
        assertTrue(drawer.contains("formatHistoryDiagnosis("), "历史就诊抽屉须按打印口径拼诊断（前缀/后缀/疑诊/中医）：\n" + drawer);
        assertFalse(drawer.contains("{{ d.icdName || d.icdCode }}"), "不得再只印标准名");
    }

    // ---------------- 源码读取（仓库相对路径；worktree 下同样有效） ----------------

    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("modules")) && Files.isDirectory(p.resolve("platform"))) {
                return p;
            }
        }
        return fail("定位不到仓库根");
    }

    private static String read(String rel) {
        try {
            return Files.readString(repoRoot().resolve(rel), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fail("读不到 " + rel + "：" + e.getMessage());
        }
    }
}
