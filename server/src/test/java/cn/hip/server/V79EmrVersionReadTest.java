package cn.hip.server;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * v79 车道 C（偏离表 994★）：<b>留痕要读得出来，空态要只说能证真的话</b>。
 *
 * <h3>补的两个缺口（v79 选题摸底 / v68 复核打回点）</h3>
 * <ol>
 *   <li><b>诊断留痕只写不读</b>：v74 起 {@code outp_diagnosis_version} 每次诊断变化落一版，但全仓没有任何
 *       查询端点或页面——医生把诊断由 A 改成 B，A 在库里，可除了 DBA 谁也看不到。留痕读不出来等于没留。</li>
 *   <li><b>空版本列表把原因说死</b>：提示写「版本留痕上线之前书写的病历本就没有采集到历史版本」，
 *       而触发条件只是「版本数为零」。gate=off 时今天新写的病历、留痕写入失败的病历，同样零版本——
 *       那句话在这两个可达库态下是假的（v68 复核原话：「一屏之内两处互相否定」）。</li>
 * </ol>
 *
 * <h3>本类钉住的</h3>
 * §1 诊断改两次后按挂号读出两版：倒序、每版诊断明细（前缀/后缀/疑诊/中医标记照写入时的快照）、
 * 操作人姓名、与上一版的增删；§2 跨就诊不串；§3 挂号不存在 4001、id 非法 4000；
 * §4 空态文案（诊断与正文两处）在「留痕上线之后写的、零版本」这个反例库态下求值，不得出现无法证真的断言；
 * §5 删光诊断也是一次修改；§6 前端接线（版本页读诊断块、住院医生站入口/状态标签/粘贴管控、审签提示）源码扫描。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class V79EmrVersionReadTest {

    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    /** 无法证真的那类断言：把「为什么没有版本」说成某一个确定原因。 */
    private static final List<String> UNPROVABLE = List.of("上线之前", "上线前书写", "本就没有");

    /** 方法论⑤：本类有用例直写 emr.version.gate，收尾必须清缓存。 */
    @AfterEach
    void evictConfigCache() {
        configReader.evictAll();
    }

    // ------------------------------------------------------------------ 夹具

    private Long visit(String tag) {
        Patient p = new Patient();
        p.setName("V79" + tag + System.nanoTime() % 100000);
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
        return rid;
    }

    private static OutpEmr emrOf(String chief) {
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint(chief);
        e.setPresentIllness("起病三天");
        return e;
    }

    private static OutpDiagnosis diag(String code, String name) {
        OutpDiagnosis d = new OutpDiagnosis();
        d.setIcdCode(code);
        d.setIcdName(name);
        return d;
    }

    private Long adminId() {
        return jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
    }

    private String adminName() {
        return jdbc.queryForObject("select real_name from sys_user where username = 'admin'", String.class);
    }

    private void save(Long rid, List<OutpDiagnosis> ds, Long by) {
        doctorStationService.saveEmr(rid, emrOf("咳嗽"), ds, by, null, null);
        em.flush();
        em.clear();
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mvc.perform(get(url).with(user("v79doc").roles("DOCTOR_OUTP")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(body);
    }

    private JsonNode diagVersions(Long rid) throws Exception {
        return getJson("/api/emr/versions/diagnoses/" + rid);
    }

    private static List<String> texts(JsonNode arr) {
        var out = new ArrayList<String>();
        if (arr != null) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    // ================================================================== §1 读出

    /**
     * 诊断改两次（A → B+中医 → B）后读出三版：<b>倒序</b>、每版明细是写入时的快照、操作人是保存人、
     * 相邻两版的增删点得出来。核心断言是「改掉的 A 读得出来」——只断言「有几行」证明不了读出有用。
     */
    @Test
    void diagnosisVersionsReadBackNewestFirstWithSnapshotAndOperator() throws Exception {
        Long rid = visit("读出");
        Long me = adminId();

        OutpDiagnosis a = diag("J18.9", "肺炎");
        a.setPrefix("疑似");
        a.setSuffix("急性期");
        a.setCertainty(OutpDiagnosis.CERTAINTY_SUSPECTED);
        a.setDiagSystem(OutpDiagnosis.SYSTEM_ICD10);
        save(rid, List.of(a), me);

        OutpDiagnosis b = diag("J20.9", "急性支气管炎");
        b.setCertainty(OutpDiagnosis.CERTAINTY_CONFIRMED);
        OutpDiagnosis tcm = diag("", "咳嗽病");
        tcm.setDiagSystem(OutpDiagnosis.SYSTEM_TCM);
        tcm.setCustomName("风寒袭肺证");
        save(rid, List.of(b, tcm), me);

        OutpDiagnosis b3 = diag("J20.9", "急性支气管炎");
        b3.setCertainty(OutpDiagnosis.CERTAINTY_CONFIRMED);
        save(rid, List.of(b3), null);   // 去掉中医诊断；无登录上下文的一次保存

        JsonNode r = diagVersions(rid);
        assertEquals(0, r.path("code").asInt(), "读出应成功：" + r);
        JsonNode d = r.path("data");
        assertEquals(rid.longValue(), d.path("registrationId").asLong());
        assertEquals(3, d.path("total").asInt(), "A → B+中医 → B 应有三版：" + d);
        JsonNode items = d.path("items");
        assertEquals(3, items.size());
        assertEquals(List.of(3, 2, 1), List.of(items.get(0).path("versionNo").asInt(),
                items.get(1).path("versionNo").asInt(), items.get(2).path("versionNo").asInt()),
                "按版本倒序：最新一版在最上面");

        // 第 1 版：被改掉的 A，连同前缀/后缀/疑诊/体系照原样读得出来
        JsonNode v1 = items.get(2);
        assertEquals(me.longValue(), v1.path("changedBy").asLong());
        assertEquals(adminName(), v1.path("changedByName").asText(), "操作人姓名取自 sys_user");
        assertFalse(v1.path("changedAt").isNull() || v1.path("changedAt").asText().isBlank(), "须有时间");
        JsonNode a1 = v1.path("diagnoses").get(0);
        assertEquals("J18.9", a1.path("icdCode").asText());
        assertEquals("肺炎", a1.path("icdName").asText());
        assertEquals("疑似", a1.path("prefix").asText());
        assertEquals("急性期", a1.path("suffix").asText());
        assertEquals("SUSPECTED", a1.path("certainty").asText());
        assertEquals("ICD10", a1.path("diagSystem").asText());
        assertTrue(a1.path("primary").asBoolean(), "第一条即主诊断");
        assertTrue(v1.path("changes").isNull(), "首版没有上一版，不比");

        // 第 2 版：B（主）+ 中医诊断，且点得出「加了谁、去了谁」
        JsonNode v2 = items.get(1);
        assertEquals(2, v2.path("diagnoses").size());
        assertEquals("急性支气管炎", v2.path("diagnoses").get(0).path("icdName").asText());
        assertTrue(v2.path("diagnoses").get(0).path("primary").asBoolean());
        JsonNode t2 = v2.path("diagnoses").get(1);
        assertEquals("TCM", t2.path("diagSystem").asText());
        assertEquals("风寒袭肺证", t2.path("customName").asText());
        assertFalse(t2.path("primary").asBoolean());
        List<String> added2 = texts(v2.path("changes").path("added"));
        List<String> removed2 = texts(v2.path("changes").path("removed"));
        assertEquals(2, added2.size(), "第 2 版新增 B 与中医诊断：" + added2);
        assertEquals(1, removed2.size(), "第 2 版去掉了 A：" + removed2);
        assertTrue(removed2.get(0).contains("肺炎") && removed2.get(0).contains("疑似"),
                "去掉的那条要按写入时的全貌点名（含前缀）：" + removed2);
        assertTrue(v2.path("changes").path("primaryChanged").asBoolean(), "主诊断由 A 换成 B");

        // 第 3 版：去掉中医诊断；这次保存无登录上下文——如实回 null，不猜
        JsonNode v3 = items.get(0);
        assertTrue(v3.path("changedBy").isNull(), "无登录上下文的保存如实回 null：" + v3);
        assertEquals(List.of(), texts(v3.path("changes").path("added")));
        assertEquals(1, texts(v3.path("changes").path("removed")).size());
        assertFalse(v3.path("changes").path("primaryChanged").asBoolean());
        assertTrue(d.path("notice").isMissingNode() || d.path("notice").isNull(), "有版本时不挂空态提示");
    }

    // ================================================================== §2 跨就诊不串

    @Test
    void anotherVisitsDiagnosesNeverLeakIn() throws Exception {
        Long r1 = visit("甲");
        Long r2 = visit("乙");
        save(r1, List.of(diag("I10", "高血压")), adminId());
        save(r2, List.of(diag("E11.9", "2型糖尿病")), adminId());
        save(r2, List.of(diag("E11.9", "2型糖尿病"), diag("I10", "高血压")), adminId());

        JsonNode d1 = diagVersions(r1).path("data");
        assertEquals(1, d1.path("total").asInt(), "r1 只改过一次：" + d1);
        assertFalse(d1.toString().contains("糖尿病"), "r2 的诊断不得出现在 r1 的读出里：" + d1);
        JsonNode d2 = diagVersions(r2).path("data");
        assertEquals(2, d2.path("total").asInt());
        d2.path("items").forEach(v -> assertEquals(r2.longValue(), v.path("registrationId").asLong()));
    }

    // ================================================================== §3 错误码

    @Test
    void unknownRegistrationIs4001AndBadIdIs4000() throws Exception {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from outp_registration", Long.class);
        JsonNode r = diagVersions(max + 100000);
        assertEquals(4001, r.path("code").asInt(), "挂号不存在复用 4001：" + r);
        assertEquals(4000, diagVersions(0L).path("code").asInt(), "id 非法走通用 4000");
    }

    // ================================================================== §4 空态只说能证真的话

    /**
     * <b>反例库态</b>：留痕上线之后、gate=off 时写的病历——正文与诊断都在，版本一条都没有。
     * v68 复核正是拿这个库态判那句「上线之前书写」为假。两处空态文案在这里求值：
     * 不得出现「上线之前」一类确定原因；要说出「暂无」这一事实、会产生版本的动作、以及此刻 gate=off 这件可求值的事；
     * V53 的契约「不伪造初版」照留。
     */
    @Test
    void emptyStateNoticesHoldInTheCounterexampleState() throws Exception {
        Long rid = visit("空态");
        jdbc.update("insert into sys_config(cfg_key, cfg_value, remark) values ('emr.version.gate','off','v79 用例') "
                + "on conflict (cfg_key) do update set cfg_value = 'off'");
        configReader.evictAll();
        save(rid, List.of(diag("J00", "急性鼻咽炎")), adminId());
        Long emrId = jdbc.queryForObject("select id from outp_emr where registration_id = ?", Long.class, rid);

        // 正文版本列表
        JsonNode emr = getJson("/api/emr/versions/OUTP/" + emrId).path("data");
        assertEquals(0, emr.path("total").asInt(), "gate=off 下保存不落版本——这正是反例库态：" + emr);
        assertEquals(rid.longValue(), emr.path("registrationId").asLong(),
                "门诊列表须带回挂号 id，版本页才能接着读诊断变更");
        String n1 = emr.path("notice").asText();
        assertNoUnprovableClaim(n1);
        assertTrue(n1.contains("暂无"), "只陈述事实：" + n1);
        assertTrue(n1.contains("保存") && n1.contains("签名"), "要说出会产生版本的动作：" + n1);
        assertTrue(n1.contains("off"), "此刻 gate=off 是可求值的事实，应当说出：" + n1);
        assertTrue(n1.contains("不伪造初版"), "V53 契约照留：" + n1);

        // 诊断版本列表
        JsonNode dg = diagVersions(rid).path("data");
        assertEquals(0, dg.path("total").asInt());
        String n2 = dg.path("notice").asText();
        assertNoUnprovableClaim(n2);
        assertTrue(n2.contains("暂无") && n2.contains("off"), n2);

        // 活对照：gate 回 warn 后同一病历再保存一次，读出就有了——证明上面那句「暂无」不是读路径坏了
        jdbc.update("update sys_config set cfg_value = 'warn' where cfg_key = 'emr.version.gate'");
        configReader.evictAll();
        save(rid, List.of(diag("J00", "急性鼻咽炎")), adminId());
        JsonNode emr2 = getJson("/api/emr/versions/OUTP/" + emrId).path("data");
        assertEquals(1, emr2.path("total").asInt(), "warn 档保存后应落一版：" + emr2);
        assertTrue(emr2.path("notice").isMissingNode() || emr2.path("notice").isNull());
        assertEquals(1, diagVersions(rid).path("data").path("total").asInt());
        // gate 不是 off 时，空态里不得再说「当前 off」（换一份零版本的住院/门诊病历求值）
        Long r3 = visit("空态二");
        JsonNode dg3 = diagVersions(r3).path("data");
        assertEquals(0, dg3.path("total").asInt());
        assertFalse(dg3.path("notice").asText().contains("=off"), "gate=warn 时不得声称 off：" + dg3);
        assertNoUnprovableClaim(dg3.path("notice").asText());
    }

    private static void assertNoUnprovableClaim(String notice) {
        assertNotNull(notice);
        assertFalse(notice.isBlank(), "零版本时须有一句说明");
        for (String bad : UNPROVABLE) {
            assertFalse(notice.contains(bad), "空态文案出现无法证真的断言「" + bad + "」：" + notice);
        }
    }

    // ================================================================== §5 删光诊断也是修改

    /** A → 无诊断：诊断被删光是一次真实修改（此前 record() 对空集合直接返回，删光 A 不留任何痕）。 */
    @Test
    void removingAllDiagnosesIsRecordedAsAVersion() throws Exception {
        Long rid = visit("删光");
        save(rid, List.of(diag("K29.7", "胃炎")), adminId());
        save(rid, List.of(), adminId());
        save(rid, List.of(), adminId());   // 再存一次空：同内容不另落

        JsonNode d = diagVersions(rid).path("data");
        assertEquals(2, d.path("total").asInt(), "胃炎 → 无诊断 应两版：" + d);
        JsonNode v2 = d.path("items").get(0);
        assertEquals(0, v2.path("diagnoses").size());
        assertEquals(1, texts(v2.path("changes").path("removed")).size());
        assertTrue(texts(v2.path("changes").path("removed")).get(0).contains("胃炎"));
    }

    // ================================================================== §6 前端接线（源码扫描，仓库相对路径）

    @Test
    void frontendIsWired() {
        String view = read("frontend/shell/src/views/outpatient/emr-version/EmrVersionView.vue");
        assertTrue(view.contains("/emr/versions/diagnoses/") && view.contains("诊断变更"),
                "版本页须有「诊断变更」块并调诊断版本读出端点");

        String inp = read("frontend/shell/src/views/inpatient/InpDoctorView.vue");
        assertTrue(inp.contains("/emr-version") && inp.matches("(?s).*emrType:\\s*'INP'.*"),
                "住院医生站须有带 emrType=INP 直达 /emr-version 的入口");
        assertTrue(inp.contains("useEmrPasteGuard") && inp.contains("@paste") && inp.contains("@copy"),
                "住院病历编辑区须挂 useEmrPasteGuard（copy/paste）");
        assertTrue(inp.contains("暂存（未签名）") && inp.contains("已提交（已签名）"),
                "住院时间线须有暂存/已提交标签");

        String cs = read("frontend/shell/src/views/inpatient/countersign/countersignCommon.ts");
        int at = cs.indexOf("NO_VERSION_ROW:");
        assertTrue(at >= 0);
        String block = cs.substring(at, cs.indexOf("},", at));
        for (String bad : UNPROVABLE) {
            assertFalse(block.contains(bad), "审签「版本表无此病历的版本行」说明不得断言原因「" + bad + "」：" + block);
        }
    }

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
