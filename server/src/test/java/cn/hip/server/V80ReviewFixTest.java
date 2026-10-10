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
import cn.hip.platform.core.config.BusinessDates;
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
import java.time.LocalDate;
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
 *       主诉与处理意见。（初版）ADMIN 全看；其余须本人至少有一条该患者未退号的挂号，否则 4036 且不带数据。
 *       只传 keyword 的常用 / 高频部分不受影响。</li>
 *   <li><b>D8</b> 患者历次就诊的诊断只回编码 / 名称 / 主诊断，抽屉里疑诊读作确诊——追加前缀、后缀、确诊疑诊、自定义描述、诊断体系五键，既有三键不动。</li>
 *   <li><b>D3 / D4</b> 文案：协定处方「药师可建档」「已开处方可追溯旧版」两句不实（不改权限）。</li>
 *   <li><b>D2 / D5</b> 医生站撤组与单行移除：纯逻辑在 {@code utils/rx-template-apply.ts}（vitest 对拍），这里只钉页面接线。</li>
 *   <li><b>审阅修补二</b>（两位复核反驳者 N-1 / R2-1 / N-2 / N-3 及反驳者三 N-1）：D1 只看挂号归属，可被「跨日期队列 + 逐次工作区」绕过，
 *       又把代班接诊人与科室号接诊前挡在外面。统一口径「当日就诊队列共享、往次就诊归本人」：
 *       就诊级（工作区、补正历史）当日 / 归属为空 / 归属是我 / 我写过这次病历，任一成立放行；
 *       患者级（诊断助手历史段、历次就诊）该患者有一条未退号挂号满足归属是我 / 当日 / 我写过病历，不设归属为空放行。
 *       同轮：历史诊断空体系按西医归组（R2-3）、历次就诊按就诊日期倒序（R2-4）、诊断前后缀与自定义描述超长 4000 点名（N-4）。
 *       「他人不可读」的用例一律用昨天的就诊造——今天的就诊按口径全院可读。</li>
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

    private static LocalDate today() {
        return BusinessDates.today();
    }

    private static LocalDate yesterday() {
        return BusinessDates.today().minusDays(1);
    }

    /** 挂 doctor 名下的号（排班带医生，挂号即归属该医生）；doctor 为空则挂科室号（归属为空）。就诊日期按 date */
    private Long registerOn(Long pid, String doctor, LocalDate date) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        if (doctor != null) s.setDoctorId(idOf(doctor));
        s.setScheduleDate(date);
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        return registrationService.register(pid, s.getId()).getId();
    }

    /**
     * owner 名下挂号 → writer 接诊 → writer 暂存病历（诊断按给定）。writer 与 owner 不同即「代班」：
     * startVisit 不改写已有归属，挂号仍记在 owner 名下，病历书写人是 writer。
     */
    private Long visit(Long pid, String owner, String writer, LocalDate date, List<OutpDiagnosis> diags) {
        Long rid = registerOn(pid, owner, date);
        Long writerId = idOf(writer);
        doctorStationService.startVisit(rid, writerId);
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint("V80F咽痛两天");
        e.setAdvice("V80F对症处理");
        doctorStationService.saveEmr(rid, e, diags, writerId, null, null);
        em.flush();
        em.clear();
        return rid;
    }

    /**
     * owner 名下<b>昨天</b>的就诊，owner 本人接诊并书写。v80 审阅修补二起当日就诊队列全院共享，
     * 「他人不可读」的用例必须用往次就诊造（同 tools/bootstrap-demo.py 张三往次就诊挂前一天的做法）。
     */
    private Long visitBy(Long pid, String owner, List<OutpDiagnosis> diags) {
        return visit(pid, owner, owner, yesterday(), diags);
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

        // b 名下有一条该患者的（往次）挂号但已退号 → 仍不算接诊过
        Long ridB = registerOn(pid, b, yesterday());
        jdbc.update("update outp_registration set status = 'CANCELLED' where id = ?", ridB);
        em.clear();
        assertEquals(4036, getAs(historyUrl, b, "DOCTOR_OUTP").get("code").asInt(), "退号不授予查阅权");
        assertEquals(4036, getAs(assistUrl, b, "DOCTOR_OUTP").get("code").asInt(), "退号不授予查阅权");

        // 未退号（已挂号、尚未接诊）即可——本人名下的号，接诊前先看既往本就是常规动作
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

    // ==================== D2 / D5：协定处方整组撤回只撤本批、检验行不可单行移除 ====================

    @Test
    void d2_applyStampsBatchAndDropUsesIt() {
        String ds = read("frontend/shell/src/views/outpatient/DoctorStationView.vue");
        int a = ds.indexOf("async function applyTemplate(");
        int b = ds.indexOf("const rxLines = ref", a);
        assertTrue(a > 0 && b > a, "找不到 applyTemplate / dropAgreedGroup");
        String src = ds.substring(a, b);
        assertTrue(src.contains("stampTemplateLines("), "套用时须给每行打上来源模板与批次：\n" + src);
        assertTrue(src.contains("dropTemplateBatch("), "撤组须按批次撤：\n" + src);
        assertFalse(src.contains("l.tplId === row.tplId"), "旧过滤（tplId 恒 undefined → 撤掉全部协定行）须删除");
    }

    @Test
    void d5_lockedLabLinesHaveNoSingleRowRemove() {
        String ds = read("frontend/shell/src/views/outpatient/DoctorStationView.vue");
        int i = ds.indexOf("labLines.splice($index, 1)");
        assertTrue(i > 0, "找不到检查检验表的移除按钮");
        String line = ds.substring(ds.lastIndexOf('\n', i), ds.indexOf('\n', i));
        assertTrue(line.contains("v-if=\"!row.locked\""), "协定处方来源的检验/检查/治疗行不得单行移除：" + line);
        String after = ds.substring(i, ds.indexOf("</template>", i));
        assertTrue(after.contains("dropAgreedGroup(row)"), "锁定行改为整组撤回");
    }

    // ==================== D3 / D4：协定处方文案说实话 ====================

    @Test
    void d3_doctorCreatingAgreedIsToldOnlyTheAdminCanFileIt() {
        String doc = newDoctor("H");
        Authentication auth = new UsernamePasswordAuthenticationToken(doc, null, List.of());
        Long drug = jdbc.queryForObject("select id from md_drug where enabled order by id limit 1", Long.class);
        R<Long> r = rxTemplateController.create(new TemplateReq("V80F医生建协定", "DEPT", 1L, "AGREED", null,
                List.of(new TemplateLine("DRUG", drug, 1, "口服", "tid", "1片", 3, 0))), auth);
        assertEquals(4060, r.getCode());
        assertFalse(r.getMessage().contains("药师"), "药师无科室，只能建个人协定处方、医生看不到——提示不得说药师可建档：" + r.getMessage());
        assertTrue(r.getMessage().contains("系统管理员"), r.getMessage());
    }

    @Test
    void d3_pharmacistAgreedTemplateIsPersonalAndInvisibleToDoctors() {
        // 权限不改（本轮范围外）——把实情钉住，文案据此写
        String pharm = newUser("P", null, "PHARMACIST");
        String doc = newDoctor("I");
        Authentication pa = new UsernamePasswordAuthenticationToken(pharm, null, List.of());
        Authentication da = new UsernamePasswordAuthenticationToken(doc, null, List.of());
        Long drug = jdbc.queryForObject("select id from md_drug where enabled order by id limit 1", Long.class);
        R<Long> r = rxTemplateController.create(new TemplateReq("V80F药师个人协定", "PERSONAL", null, "AGREED", null,
                List.of(new TemplateLine("DRUG", drug, 1, "口服", "tid", "1片", 3, 0))), pa);
        assertEquals(0, r.getCode(), r.getMessage());
        Long id = r.getData();
        List<Map<String, Object>> seen = rxTemplateController.list(null, null, false, da).getData();
        assertTrue(seen.stream().noneMatch(m -> id.equals(((Number) m.get("id")).longValue())), "药师个人协定处方医生不可见");
    }

    @Test
    void d4_noClaimThatIssuedPrescriptionsTraceBackToTemplateVersion() {
        for (String rel : List.of("frontend/shell/src/views/outpatient/RxTemplateView.vue",
                "modules/outpatient/src/main/java/cn/hip/outpatient/service/RxTemplateService.java",
                "modules/outpatient/src/main/java/cn/hip/outpatient/web/RxTemplateController.java")) {
            String s = read(rel);
            for (String claim : List.of("追溯", "追得到", "哪一版", "仍能解释")) {
                assertFalse(s.contains(claim), rel + " 仍声称已开处方可追溯模板版本（outp_order 不记模板 id，模板可删可下架）：「" + claim + "」");
            }
            assertFalse(s.contains("管理员/药师"), rel + " 仍说药师可建协定处方");
        }
    }

    // ==================== 审阅修补二：当日就诊队列共享、往次就诊归本人（N-1 / R2-1 / N-2 / 反驳三 N-1） ====================

    private static String wsUrl(Long rid) {
        return "/api/outpatient/doctor/" + rid + "/workspace";
    }

    private static String amendUrl(Long rid) {
        return "/api/outpatient/doctor/" + rid + "/emr/amendments";
    }

    private static String histUrl(Long pid) {
        return "/api/outpatient/doctor/patient/" + pid + "/history";
    }

    private static String assistUrl(Long pid) {
        return "/api/outpatient/doctor/diagnosis-assist?patientId=" + pid;
    }

    @Test
    void r2_othersPastVisitWorkspaceAndAmendmentsAreRefused() throws Exception {
        // N-1：队列可跨 92 天列出他人往次挂号，逐个打开工作区即读到主诉、现病史、诊断——D1 被绕过
        String a = newDoctor("J"), b = newDoctor("K");
        Long pid = newPatient();
        Long rid = visitBy(pid, a, List.of(diag("J02.900", "急性咽炎")));

        for (String url : List.of(wsUrl(rid), amendUrl(rid))) {
            JsonNode denied = getAs(url, b, "DOCTOR_OUTP");
            assertEquals(4036, denied.get("code").asInt(), "他人往次就诊须 4036：" + url + " → " + denied);
            assertTrue(noData(denied), "被拒时不得带回任何数据：" + denied);
            assertFalse(denied.toString().contains("V80F咽痛两天"), "被拒时不得带回主诉");
            assertTrue(denied.get("message").asText().contains("非当日就诊且非本人接诊"), denied.toString());
        }
        // 本人接诊的往次就诊照常
        JsonNode mine = getAs(wsUrl(rid), a, "DOCTOR_OUTP");
        assertEquals(0, mine.get("code").asInt(), mine.toString());
        assertEquals("V80F咽痛两天", mine.at("/data/emr/chiefComplaint").asText());
        assertEquals(0, getAs(amendUrl(rid), a, "DOCTOR_OUTP").get("code").asInt());
        // 管理员照常
        assertEquals("V80F咽痛两天", getAs(wsUrl(rid), "v80fadmin", "ADMIN").at("/data/emr/chiefComplaint").asText());
        assertEquals(0, getAs(amendUrl(rid), "v80fadmin", "ADMIN").get("code").asInt());
    }

    @Test
    void r2_todaysQueueIsSharedSoASubstituteReadsPatientHistory() throws Exception {
        // R2-1：代班医生 B 接诊挂在 A 名下的当日号并写病历——此前读该患者诊断助手历史与历次就诊都是 4036
        String a = newDoctor("L"), b = newDoctor("M"), c = newDoctor("N");
        Long pid = newPatient();
        Long past = visitBy(pid, a, List.of(diag("J02.900", "急性咽炎")));   // A 的往次就诊
        Long rid = visit(pid, a, b, today(), List.of(diag("J06.900", "急性上呼吸道感染")));
        assertEquals(idOf(a), jdbc.queryForObject("select doctor_id from outp_registration where id = ?", Long.class, rid),
                "前提：startVisit 不改写已有归属，挂号仍记在 A 名下");

        for (String who : List.of(b, c)) {   // c 没碰过这次就诊：当日队列全院共享，同样可读
            for (String url : List.of(wsUrl(rid), amendUrl(rid), histUrl(pid), assistUrl(pid))) {
                JsonNode r = getAs(url, who, "DOCTOR_OUTP");
                assertEquals(0, r.get("code").asInt(), who + " 读 " + url + " → " + r);
            }
        }
        JsonNode hist = getAs(histUrl(pid), b, "DOCTOR_OUTP");
        assertEquals(2, hist.get("data").size(), "患者级放行后历次就诊全给（含 A 的往次）：" + hist);
        // 患者级放行不等于就诊级放行：A 的往次就诊工作区，B 仍须 4036
        assertEquals(4036, getAs(wsUrl(past), b, "DOCTOR_OUTP").get("code").asInt());
    }

    @Test
    void r2_substituteWhoWroteAPastVisitKeepsAccess() throws Exception {
        // R2-1 的往次形态：B 代班写过病历的那次就诊已成往次，挂号仍在 A 名下——B 写过就能读
        String a = newDoctor("O"), b = newDoctor("P"), c = newDoctor("Q");
        Long pid = newPatient();
        Long rid = visit(pid, a, b, yesterday(), List.of(diag("J02.900", "急性咽炎")));

        for (String url : List.of(wsUrl(rid), amendUrl(rid), histUrl(pid), assistUrl(pid))) {
            assertEquals(0, getAs(url, b, "DOCTOR_OUTP").get("code").asInt(), "书写医生读 " + url);
            assertEquals(0, getAs(url, a, "DOCTOR_OUTP").get("code").asInt(), "归属医生读 " + url);
            assertEquals(4036, getAs(url, c, "DOCTOR_OUTP").get("code").asInt(), "既非归属又非书写的医生读 " + url);
        }
    }

    @Test
    void r2_deptRegistrationTodayIsReadableBeforeAcceptance() throws Exception {
        // 反驳三 N-1：科室号（doctor_id 为空）接诊前，本人队列里的这位患者历史就诊 / 历史诊断一律 4036
        String a = newDoctor("R"), other = newDoctor("S");
        Long pid = newPatient();
        visitBy(pid, other, List.of(diag("J02.900", "急性咽炎")));   // 患者有他人往次就诊
        Long rid = registerOn(pid, null, today());                   // 今天挂科室号，尚未接诊
        em.flush();
        em.clear();
        for (String url : List.of(wsUrl(rid), histUrl(pid), assistUrl(pid))) {
            JsonNode r = getAs(url, a, "DOCTOR_OUTP");
            assertEquals(0, r.get("code").asInt(), "当日科室号接诊前须可读：" + url + " → " + r);
        }
    }

    @Test
    void r2_patientWithOnlyOthersOrUnownedPastRegistrationsIsRefused() throws Exception {
        // 患者级不设「归属为空放行」：往次科室号（从未接诊）不授予患者级查阅权；就诊级归属为空照放行（只看这一次）
        String a = newDoctor("T"), b = newDoctor("U");
        Long pid = newPatient();
        visitBy(pid, a, List.of(diag("J02.900", "急性咽炎")));
        Long unowned = registerOn(pid, null, yesterday());
        em.flush();
        em.clear();
        for (String url : List.of(histUrl(pid), assistUrl(pid))) {
            JsonNode r = getAs(url, b, "DOCTOR_OUTP");
            assertEquals(4036, r.get("code").asInt(), url + " → " + r);
            assertTrue(noData(r));
            assertTrue(r.get("message").asText().contains("该患者非当日就诊，且本人未接诊过其往次就诊"), r.toString());
        }
        assertEquals(0, getAs(wsUrl(unowned), b, "DOCTOR_OUTP").get("code").asInt(), "就诊级：归属为空的那一次可读");
        assertEquals(0, getAs(histUrl(pid), "v80fadmin", "ADMIN").get("code").asInt());
    }

    @Test
    void r2_assistHistoryTreatsBlankSystemAsWestern() throws Exception {
        // R2-3：bootstrap 写入的诊断 diag_system 为空，医生站暂存写 ICD10——同一诊断在历史段里出现两条
        String a = newDoctor("V");
        Long pid = newPatient();
        visit(pid, a, a, yesterday(), List.of(diag("N39.000", "泌尿道感染")));
        OutpDiagnosis west = diag("N39.000", "泌尿道感染");
        west.setDiagSystem(OutpDiagnosis.SYSTEM_ICD10);
        OutpDiagnosis tcm = diag("", "淋证");
        tcm.setDiagSystem(OutpDiagnosis.SYSTEM_TCM);
        visit(pid, a, a, today(), List.of(west, tcm));

        JsonNode r = getAs(assistUrl(pid), a, "DOCTOR_OUTP");
        assertEquals(0, r.get("code").asInt(), r.toString());
        int n39 = 0;
        for (JsonNode h : r.at("/data/history")) {
            if ("N39.000".equals(h.get("icdCode").asText())) {
                n39++;
                assertEquals("ICD10", h.get("diagSystem").asText(), "空体系按西医归组并回 ICD10：" + h);
            }
        }
        assertEquals(1, n39, "空体系与 ICD10 须归为一组：" + r.at("/data/history"));
        assertEquals(2, r.at("/data/history").size(), "中医诊断仍单独一组：" + r.at("/data/history"));
    }

    @Test
    void r2_patientHistoryIsOrderedByVisitDateDescThenIdDesc() throws Exception {
        // R2-4：按挂号 id 倒序时，先挂今天、后补挂昨天的那次排在今天上面
        String a = newDoctor("W");
        Long pid = newPatient();
        Long t = visit(pid, a, a, today(), List.of(diag("J02.900", "急性咽炎")));
        Long y1 = visit(pid, a, a, yesterday(), List.of(diag("J02.900", "急性咽炎")));
        Long y2 = visit(pid, a, a, yesterday(), List.of(diag("J02.900", "急性咽炎")));
        JsonNode r = getAs(histUrl(pid), a, "DOCTOR_OUTP");
        assertEquals(0, r.get("code").asInt(), r.toString());
        List<Long> got = new java.util.ArrayList<>();
        r.get("data").forEach(h -> got.add(h.get("registrationId").asLong()));
        assertEquals(List.of(t, y2, y1), got, "就诊日期倒序、同日按 id 倒序：" + r);
    }

    @Test
    void r2_overlongDiagnosisQualifierIsRejectedWith4000NamingRowAndField() {
        // N-4：前缀超 32 字整次暂存以 4091「编码长度超限」失败，不说是哪一栏
        String a = newDoctor("X");
        Long pid = newPatient();
        Long rid = registerOn(pid, a, today());
        Long aid = idOf(a);
        doctorStationService.startVisit(rid, aid);
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint("V80F超长");
        Object[][] cases = {{"prefix", 32, "前缀"}, {"suffix", 32, "后缀"}, {"customName", 128, "自定义描述"}};
        for (Object[] c : cases) {
            int max = (Integer) c[1];
            OutpDiagnosis ok = diag("J02.900", "急性咽炎");
            OutpDiagnosis bad = diag("J06.900", "急性上呼吸道感染");
            String atMax = "长".repeat(max), over = "长".repeat(max + 1);
            switch ((String) c[0]) {
                case "prefix" -> { ok.setPrefix(atMax); bad.setPrefix(over); }
                case "suffix" -> { ok.setSuffix(atMax); bad.setSuffix(over); }
                default -> { ok.setCustomName(atMax); bad.setCustomName(over); }
            }
            var ex = assertThrows(RegistrationService.BizException.class,
                    () -> doctorStationService.saveEmr(rid, e, List.of(ok, bad), aid, null, null), (String) c[0]);
            assertEquals(4000, ex.code, ex.getMessage());
            assertTrue(ex.getMessage().contains("第 2 条诊断") && ex.getMessage().contains((String) c[2])
                    && ex.getMessage().contains(max + " 字"), ex.getMessage());
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from outp_diagnosis where registration_id = ?", Long.class, rid),
                "预检在任何写库之前：被拒时不得落诊断");
    }

    @Test
    void r2_frontendHandles4036AndFormatsDiagnosisHintAndCapsQualifierInputs() {
        String ds = read("frontend/shell/src/views/outpatient/DoctorStationView.vue");
        // R2-2：历史就诊遇 4036 打开抽屉显示说明，不弹红字
        int a = ds.indexOf("async function openHistory(");
        String fn = ds.substring(a, ds.indexOf("\n}", a));
        assertTrue(fn.contains("__silentCodes: [4036]") && fn.contains("historyDenied"), fn);
        // 工作区遇 4036 有明确提示，不白屏、不残留上一位患者的内容
        int o = ds.indexOf("async function openPatient(");
        String op = ds.substring(o, ds.indexOf("\n}", o));
        assertTrue(op.contains("__silentCodes: [4036]") && op.contains("visitDenied"), op);
        // N-6：开单行诊断提示按打印口径（前后缀、疑诊），不再只印标准名
        assertFalse(ds.contains("{{ d.icdName }}"), "开单行诊断提示须带前后缀与疑诊");
        // N-4：三个输入框 maxlength 与库列宽一致（V135：prefix/suffix varchar(32)、custom_name varchar(128)）
        assertTrue(ds.contains("v-model=\"row.prefix\" size=\"small\" maxlength=\"32\""), "前缀 maxlength 32");
        assertTrue(ds.contains("v-model=\"row.suffix\" size=\"small\" maxlength=\"32\""), "后缀 maxlength 32");
        assertTrue(ds.contains("v-model=\"row.customName\" size=\"small\" maxlength=\"128\""), "自定义描述 maxlength 128");
    }

    @Test
    void r2_rxCategoryLabelMatchesDoctorStationEditability() {
        // 反驳者三：「处方模板（可套用后再改）」不实——医生站套用后药品行参数是只读列，只能移除 / 另行加药
        String tv = read("frontend/shell/src/views/outpatient/RxTemplateView.vue");
        int i = tv.indexOf("<el-radio value=\"RX\">");
        assertTrue(i > 0, "找不到处方模板类别单选");
        String label = tv.substring(i, tv.indexOf("</el-radio>", i));
        assertFalse(label.contains("再改") || label.contains("可改"), "类别文案不得声称套用后可改：" + label);
        String ds = read("frontend/shell/src/views/outpatient/DoctorStationView.vue");
        int a = ds.indexOf("<el-table :data=\"rxLines\"");
        String table = ds.substring(a, ds.indexOf("</el-table>", a));
        for (String col : List.of("prop=\"dosePerTime\"", "prop=\"frequency\"", "prop=\"usageRoute\"", "prop=\"days\"", "prop=\"qty\"")) {
            assertTrue(table.contains(col), "前提：药品参数为只读列 " + col + "（若改为可编辑，类别文案可恢复「可改」）");
        }
        assertTrue(table.contains("rxLines.splice($index, 1)"), "非锁定行可逐行移除");
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
