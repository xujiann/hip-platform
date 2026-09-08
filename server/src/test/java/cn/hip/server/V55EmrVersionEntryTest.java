package cn.hip.server;

import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.DoctorStationController;
import cn.hip.outpatient.web.DoctorStationController.SaveEmrRequest;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v55 车道 R2（偏离表 994）：<b>病历版本留痕的入口可达性</b>。
 *
 * <h3>这条测试补的是 {@code ReachabilityTest} 看不见的那一层</h3>
 * v54 的 {@code ReachabilityTest} 保证「控制器有前端引用 → 有菜单 → 有授权 → 有路由」，而 v53 交付的
 * 版本留痕页在那条链上每一环都是绿的——可它的<b>唯一</b>入口是一个空的数字输入框，要人手填
 * {@code outp_emr.id}，而全系统没有任何页面显示这个 id（v55 主控 grep 实证：除 emr-version 目录外命中 0）。
 * <b>控制器有前端引用 ≠ 用户走得到</b>。验收会上这与「没做」没有区别。
 *
 * <h3>本类钉住的是「医生手上有那个 id」这条链的每一节</h3>
 * <ol>
 *   <li>§1 <b>后端确实把 id 交到医生手上</b>：走真实写路径保存病历后，{@code PUT .../emr} 的返回体与
 *       {@code GET .../workspace} 的 {@code emr} 元素经 Jackson 序列化都带 {@code id}，且就是版本表用的那个
 *       {@code outp_emr.id}；拿它以 DOCTOR_OUTP 身份调 {@code GET /api/emr/versions/OUTP/{id}} 能拿到版本。
 *       将来谁把 {@code OutpEmr} 换成不带 id 的 DTO、或给 id 加 {@code @JsonIgnore}，这里当场变红——
 *       而那种改动不会让构建失败，只会让医生站的按钮永远不出现。</li>
 *   <li>§2 <b>前端确实接上了</b>：医生站把 {@code ws.emr.id} / 保存返回体的 id 交给 {@code /emr-version?emrType&emrId}，
 *       版本页读 {@code route.query}。配一条探针：把入口那几行从源码里抠掉，检测器必须点名。</li>
 *   <li>§3 <b>两道门都放医生过</b>：router 守卫按 {@code /auth/me} 的菜单 path 放行，故 DOCTOR_OUTP 必须持有
 *       {@code /emr-version} 菜单（V162）；接口侧 {@code EmrVersionController} 的类级 {@code @PreAuthorize}
 *       必须含 DOCTOR_OUTP。缺任一道，按钮点下去是「无该功能权限」被踢回首页。</li>
 *   <li>§4 <b>按钮发出的 query 键 = 版本页读的 query 键</b>。键名对不上不会让构建失败，只会让页面白给
 *       （本仓铁律：猜错契约页面就是空白）。</li>
 * </ol>
 *
 * <h3>刻意不做的事</h3>
 * 不改后端、不加端点：本车道只动 {@code DoctorStationView.vue} 的入口几行与 {@code emr-version/} 目录。
 * 「按患者/挂号检索到病历再看版本」需要 {@code EmrVersionController} 新增查询端点（QUALITY 没有医生站的
 * workspace 权限），已写进 cross_lane，不在本类断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "DOCTOR_OUTP")
class V55EmrVersionEntryTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired ObjectMapper objectMapper;
    @Autowired MockMvc mvc;

    @Autowired DoctorStationController doctorStationController;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired PatientService patientService;

    // ==================================================================================
    // §0 夹具（与 V53EmrVersionReconTest 同一套真实写路径；时间只用 BusinessDates.today()）
    // ==================================================================================

    private Long userId(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, title, enabled) "
                + "values (?, 'x', ?, ?, true) on conflict (username) do nothing",
                username, username + "医生", "主治医师");
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private Authentication auth(String username) {
        userId(username);
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    private Long newPatient() {
        Patient p = new Patient();
        p.setName("v55入口" + System.nanoTime());
        p.setSex("U");
        return patientService.register(p).getId();
    }

    /** 一次门诊就诊（已接诊），返回 registrationId */
    private Long visit() {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(newPatient(), s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    private static OutpEmr emrOf(String presentIllness) {
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint("咳嗽3天");
        e.setPresentIllness(presentIllness);
        e.setAdvice("对症治疗");
        return e;
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    private JsonNode json(Object o) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(o));
    }

    // ==================================================================================
    // §1 后端把 outp_emr.id 交到医生手上，且拿着它就能看到版本
    // ==================================================================================

    @Test
    void doctorStationHandsTheVersionPageItsEmrId() throws Exception {
        Long rid = visit();

        // 未写病历：workspace.emr 为 null → 页面按钮 v-if="currentEmrId && …" 不出现（此时也确实一版都没有）
        Map<String, Object> before = doctorStationController.workspace(rid).getData();
        assertTrue(before.containsKey("emr"), "workspace 返回体须有 emr 键（既有契约）");
        assertNull(before.get("emr"), "尚未保存病历时 emr 应为 null——按钮此时不该出现");

        // 真实写路径保存一版
        R<OutpEmr> saveResp = doctorStationController.saveEmr(
                rid, new SaveEmrRequest(emrOf("v55 首版正文"), List.of()), auth("v55doc"));
        assertEquals(0, saveResp.getCode(), "保存病历应成功：" + saveResp.getMessage());
        OutpEmr saved = saveResp.getData();
        assertNotNull(saved, "PUT …/emr 返回体 data 须是保存后的病历实体");
        assertNotNull(saved.getId(), "保存后的病历实体须带 id——医生站 saveEmr 读的是 resp.data.data.id");

        // 保存返回体经 Jackson 序列化后 data.id 在场且等于 outp_emr.id（前端首次保存后按钮即出现，靠的就是它）
        JsonNode saveJson = json(saveResp);
        assertEquals(saved.getId().longValue(), saveJson.path("data").path("id").asLong(-1),
                "PUT …/emr 序列化后 data.id 丢了或不等——医生站首次保存后「版本留痕」按钮将永远不出现。JSON=" + saveJson);

        // workspace 返回体 emr.id ——医生站 openPatient 读的是 ws.emr.id（进患者即可见按钮）
        flushClear();
        JsonNode wsJson = json(doctorStationController.workspace(rid));
        assertEquals(saved.getId().longValue(), wsJson.path("data").path("emr").path("id").asLong(-1),
                "GET …/workspace 序列化后 data.emr.id 丢了或不等——医生进患者后「版本留痕」按钮将不出现。JSON=" + wsJson);

        // 与版本表用的是同一个 id
        flushClear();
        Long dbId = jdbc.queryForObject("select id from outp_emr where registration_id = ?", Long.class, rid);
        assertEquals(dbId, saved.getId(), "返回体里的 id 必须就是 outp_emr.id（版本表 emr_type=OUTP 的 emr_id 指向它）");

        // 拿着这个 id，以 DOCTOR_OUTP 身份调按钮要跳去的那个后端：版本列表
        String body = mvc.perform(get("/api/emr/versions/OUTP/{id}", saved.getId())
                        .with(user("v55doc").roles("DOCTOR_OUTP")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode list = objectMapper.readTree(body);
        assertEquals(0, list.path("code").asInt(-1), "DOCTOR_OUTP 调版本列表应成功：" + body);
        assertEquals(saved.getId().longValue(), list.path("data").path("emrId").asLong(-1),
                "版本列表回显的 emrId 应就是医生站交过来的那个：" + body);
        assertTrue(list.path("data").path("total").asInt(0) >= 1,
                "走真实写路径保存一次至少应有一版（默认 gate=warn 落版本；为 0 说明留痕接缝或 gate 配置有问题）：" + body);
    }

    // ==================================================================================
    // §2 前端接线：医生站 → /emr-version?emrType&emrId → 版本页读 route.query
    // ==================================================================================

    private static final String DOCTOR_VIEW = "frontend/shell/src/views/outpatient/DoctorStationView.vue";
    private static final String VERSION_VIEW = "frontend/shell/src/views/outpatient/emr-version/EmrVersionView.vue";
    private static final String ROUTER = "frontend/shell/src/router/index.ts";

    /**
     * 「医生站接上了版本页入口」的判据：
     * 跳到 /emr-version、带 emrId、类型固定 OUTP（门诊站只写门诊病历）、id 取自 workspace 的 emr.id。
     */
    private static boolean entryWired(String vueText) {
        return vueText.contains("/emr-version")
                && vueText.contains("emrId")
                && Pattern.compile("emrType:\\s*'OUTP'").matcher(vueText).find()
                && Pattern.compile("ws\\.emr\\??\\.id").matcher(vueText).find();
    }

    @Test
    void frontendEntryIsWiredFromDoctorStationToVersionPage() {
        String doctor = read(DOCTOR_VIEW);
        assertTrue(entryWired(doctor), """
                【医生站没有版本留痕入口】%s 里找不到「跳 /emr-version、带 emrId=ws.emr.id、emrType='OUTP'」的接线。
                没有这一步，版本页的唯一入口就是手填 outp_emr.id，而全系统没有任何页面显示这个 id——功能等于没交付。
                """.formatted(DOCTOR_VIEW));

        String version = read(VERSION_VIEW);
        assertTrue(version.contains("useRoute") && version.contains("route.query.emrId")
                        && version.contains("route.query.emrType"),
                "【版本页不读带参】" + VERSION_VIEW + " 须 useRoute() 并读 route.query.emrType / route.query.emrId，"
                        + "否则医生站带着 id 跳过来，页面仍是一个空输入框");

        String router = read(ROUTER);
        assertTrue(Pattern.compile("path:\\s*'emr-version'").matcher(router).find(),
                "router 里须有 emr-version 路由（V162 菜单 180 的 path 是 /emr-version）");
        assertTrue(Pattern.compile("path:\\s*'outpatient/doctor'").matcher(router).find(),
                "router 里须有 outpatient/doctor 路由（医生站本身）");

        // 主控证据的反转：除 emr-version 目录外，全前端至少有一处页面把 emrId 交给 /emr-version
        Set<String> linkers = new TreeSet<>();
        for (SrcFile f : sources("frontend/shell/src")) {
            if (f.rel().contains("/emr-version/")) continue;
            if (f.text().contains("/emr-version") && f.text().contains("emrId")) linkers.add(f.rel());
        }
        assertTrue(linkers.contains(DOCTOR_VIEW),
                "除 emr-version 目录外，把 emrId 交给 /emr-version 的页面应至少包含医生站；实际：" + linkers);
    }

    /** 探针：把入口那几行抠掉，检测器必须点名——否则上面的绿不说明任何事。 */
    @Test
    void entryDetectorActuallyBites() {
        String doctor = read(DOCTOR_VIEW);
        String stripped = doctor.lines()
                .filter(l -> !l.contains("emr-version") && !l.contains("currentEmrId"))
                .collect(Collectors.joining("\n"));
        assertFalse(entryWired(stripped), "抠掉入口后检测器仍判「已接线」——检测器坏了");
        assertTrue(entryWired(doctor), "真实源码应判「已接线」");
    }

    // ==================================================================================
    // §3 两道门：菜单（router 守卫）与 @PreAuthorize（接口）都得放 DOCTOR_OUTP 过
    // ==================================================================================

    @Test
    void doctorRoleIsLetThroughBothGates() throws Exception {
        Integer granted = jdbc.queryForObject("""
                select count(*) from sys_role_menu rm
                  join sys_role r on r.id = rm.role_id
                  join sys_menu m on m.id = rm.menu_id
                 where r.code = 'DOCTOR_OUTP' and m.path = '/emr-version'
                """, Integer.class);
        assertTrue(granted != null && granted >= 1, """
                【菜单门】DOCTOR_OUTP 未持有 path='/emr-version' 的菜单（V162 应授 ADMIN/DOCTOR_OUTP/QUALITY）。
                router 守卫按 /auth/me 下发的菜单 path 放行：医生点「版本留痕」会被踢回首页并提示「无该功能权限」。
                """);

        Class<?> ctl = Class.forName("cn.hip.outpatient.web.EmrVersionController");
        PreAuthorize pre = ctl.getAnnotation(PreAuthorize.class);
        assertNotNull(pre, "EmrVersionController 须有类级 @PreAuthorize（v53 已有）");
        assertTrue(pre.value().contains("DOCTOR_OUTP"),
                "【接口门】EmrVersionController 类级 @PreAuthorize 不含 DOCTOR_OUTP：菜单点得进、接口 403。实际：" + pre.value());
    }

    // ==================================================================================
    // §4 按钮发出的 query 键 ⊆ 版本页读的 query 键（键名对不上 = 页面空白且构建不报错）
    // ==================================================================================

    @Test
    void buttonQueryKeysMatchWhatVersionPageReads() {
        String doctor = read(DOCTOR_VIEW);
        Matcher m = Pattern.compile("new URLSearchParams\\(\\{([^}]*)\\}\\)").matcher(doctor);
        Set<String> emitted = new TreeSet<>();
        while (m.find()) {
            if (!m.group(0).contains("emrId")) continue;   // 只看版本留痕那一处
            for (String kv : m.group(1).split(",")) {
                String k = kv.split(":")[0].trim();
                if (!k.isEmpty()) emitted.add(k);
            }
        }
        assertTrue(emitted.contains("emrType") && emitted.contains("emrId"),
                "医生站的版本留痕跳转须至少带 emrType 与 emrId，实际发出：" + emitted);

        String version = read(VERSION_VIEW);
        Matcher r = Pattern.compile("route\\.query\\.(\\w+)").matcher(version);
        Set<String> consumed = new TreeSet<>();
        while (r.find()) consumed.add(r.group(1));

        for (String k : emitted) {
            assertTrue(consumed.contains(k),
                    "医生站发出的 query 键 " + k + " 版本页没读（版本页读的是 " + consumed + "）——页面会白给，构建不会报错");
        }
    }

    // ==================================================================================
    // 源码读取（照抄 V53EmrVersionReconTest：跳过 target/ node_modules/ dist/ 与他人工作区）
    // ==================================================================================

    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("modules")) && Files.isDirectory(p.resolve("platform"))) {
                return p;
            }
        }
        return fail("定位不到仓库根（从 " + System.getProperty("user.dir") + " 向上找 modules/ 与 platform/）");
    }

    private static String read(String rel) {
        try {
            return Files.readString(repoRoot().resolve(rel), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fail("读不到 " + rel + "：" + e.getMessage());
        }
    }

    private record SrcFile(String rel, String text) {}

    private static List<SrcFile> sources(String relDir) {
        Path root = repoRoot();
        Path base = root.resolve(relDir);
        List<SrcFile> out = new ArrayList<>();
        try (var s = Files.walk(base)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String path = f.toString().replace('\\', '/');
                if (path.contains("/node_modules/") || path.contains("/dist/") || path.contains("/.claude/")
                        || path.contains("/worktrees/")) {
                    continue;
                }
                if (!(path.endsWith(".vue") || path.endsWith(".ts"))) continue;
                out.add(new SrcFile(root.relativize(f).toString().replace('\\', '/'),
                        Files.readString(f, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("扫描 " + base + " 失败", e);
        }
        return out;
    }
}
