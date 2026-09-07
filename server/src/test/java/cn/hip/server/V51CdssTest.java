package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * v51 车道 E：<b>CDSS 规则引擎扩充与盘点批次层的对账保障</b>。
 *
 * <h2>本类为什么先于实现车道存在</h2>
 * 承 {@code V49CaliberTest} / {@code V50PharmacyTest} 的做法：<b>判据先于实现</b>，
 * 红的位置就是真实缺口。本版的判据比前两版更需要先立，因为本版有一条
 * <b>写错比不写更危险</b>的规则——过敏。DDI 少一条是漏一次提醒，
 * 过敏判反了是过敏性休克或该用的药被误禁。
 *
 * <h2>六条不变式</h2>
 * <ol>
 *   <li><b>过敏拦截不得有假阴性</b>：患者对 X 过敏、开含 X 的药必须命中；同族交叉必须命中
 *       —— §1；
 *   <li><b>自由文本不得脚本解析</b>：{@code empi_patient.allergy_history} 不许被正则/关键词
 *       转成结构化过敏原 —— §2。<b>本版最重要的一条边界</b>；
 *   <li><b>不得内置来源不明的药学知识</b>：迁移种子空或带「示例」，代码里无硬编码药名配伍表
 *       —— §3；
 *   <li><b>gate 默认 warn，且 warn 真的出声</b>：warn 档放行但警告必须到达医生，
 *       off 档返回体与 v50 逐字同形 —— §4；
 *   <li><b>不得按性别年龄猜妊娠</b> —— §5；
 *   <li><b>盘点不得静默抹平批次层差异</b>（v50 署名欠账，本版必须还） —— §6。
 * </ol>
 * 外加 §7：<b>既有 CDSS 契约的防改坏网</b>——三类规则、4 个端点、码 4015/4017/4650 逐字钉死。
 *
 * <h2>三条工程口径（承 v50 车道 E）</h2>
 * <ol>
 *   <li><b>不焊死在别人的方法签名上</b>。跨车道一律走 <b>表 + HTTP(MockMvc) + 配置</b>。
 *       实现车道改类名/改签名不会让本类编译不过——否则「改签名」会变成「顺手把测试改绿」。
 *       写这个类时其它车道只交付了 V152/V153/V154 三份迁移、尚无一行 Java，
 *       本类照样能编译、能跑、能指出缺什么。
 *   <li><b>未交付一律显式 fail</b>，前缀 {@code [V51-未交付]} 可 grep。静默跳过 = 没有断言。
 *   <li><b>每条不变式尽量带一条「今天就能跑的腿」</b>。只会在未来变绿的网等于没有网。
 * </ol>
 *
 * <h2>本类刻意<b>不</b>断言的两件事（否则就是陷阱）</h2>
 * <ol>
 *   <li><b>不</b>断言「自由文本『阿莫西林过敏』必须拦住阿莫西林胶囊」。
 *       今天确实不拦（真·假阴性，见 {@link #freeTextKeywordGateErrsInBothDirections()} 实测），
 *       但把它写成硬断言，唯一能让它变绿的办法就是<b>再往关键词表里塞词</b>——
 *       那正是本版明令禁止的动作。合规出路只有人工核对工作台，故本类断言的是
 *       <b>工作台必须存在</b>（§2.3），不是「关键词表必须更全」。
 *   <li><b>不</b>断言「自由文本『青霉素皮试阴性』必须放行」。要在保留既有 4012 的前提下
 *       做到这一点，只能写一个懂否定语义的文本解析器——同样是禁止动作。
 *       故本类断言的是<b>关键词表不许再长</b>（§2.2），并把两个方向的实测结果
 *       写进 §2.3 的失败信息里当证据。
 * </ol>
 *
 * <h2>时间</h2>
 * 全部日期取 {@link BusinessDates#today()}，无裸 {@code LocalDate.now()} 与墙钟字面量；
 * 写库的时刻先 {@code truncatedTo(MICROS)}。本仓已因时区/微秒舍入炸过四次，
 * 本类须在 {@code -DargLine="-Duser.timezone=UTC"} 下同样为绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "admin", roles = {"ADMIN", "DOCTOR", "NURSE", "PHARMACIST"})
class V51CdssTest {

    private static final String UNDELIVERED = "[V51-未交付] ";

    /** 本版分给 CDSS 与盘点批次层的错误码大段（docs/错误码分段.md）。 */
    private static final int CODE_LO = 5600;
    private static final int CODE_HI = 5699;

    /** 过敏子段（5600–5619）。V152 迁移注释自陈 block 档用 5612。 */
    private static final int ALLERGY_LO = 5600;
    private static final int ALLERGY_HI = 5619;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired ConfigReader configReader;

    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.support.TestSeeds seeds;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    // ==================================================================================
    // §0 工具
    // ==================================================================================

    private static int seq = 0;

    private static String uniq(String prefix) {
        return prefix + (System.nanoTime() % 100000000L) + (seq++);
    }

    /** ConfigReader 是 30 秒进程内缓存、不随事务回滚——每个用例后必须显式失效，否则串味到别的用例。 */
    @AfterEach
    void evictConfig() {
        configReader.evictAll();
    }

    private void setCfg(String key, String value) {
        jdbc.update("insert into sys_config(cfg_key, cfg_value) values (?, ?) "
                + "on conflict (cfg_key) do update set cfg_value = excluded.cfg_value", key, value);
        configReader.evictAll();
    }

    private void flushIfInTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            em.flush();
            em.clear();
        }
    }

    private JsonNode api(String method, String path, Object body) {
        try {
            var req = "POST".equals(method)
                    ? post(path).contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body == null ? Map.of() : body))
                    : get(path);
            String json = mvc.perform(req).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (json == null || json.isBlank()) {
                return om.createObjectNode().put("code", -1).put("message", "空响应体");
            }
            return om.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("调用 " + method + " " + path + " 失败", e);
        }
    }

    private static int code(JsonNode body) {
        return body.path("code").asInt(-1);
    }

    private boolean tableExists(String table) {
        Long n = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?",
                Long.class, table);
        return n != null && n > 0;
    }

    private void requireTable(String table, String owner, String why) {
        assertTrue(tableExists(table), UNDELIVERED + "缺表 " + table + "（" + owner + "）：" + why);
    }

    /** 现取现认的端点集合，形如 {@code "POST /api/cdss/allergy/patients/{id}"}。 */
    private Set<String> mappedEndpoints() {
        Set<String> out = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> {
            Set<String> patterns = new LinkedHashSet<>();
            if (info.getPathPatternsCondition() != null) {
                patterns.addAll(info.getPathPatternsCondition().getPatternValues());
            }
            if (info.getPatternsCondition() != null) {
                patterns.addAll(info.getPatternsCondition().getPatterns());
            }
            var methods = info.getMethodsCondition().getMethods();
            for (String p : patterns) {
                if (methods.isEmpty()) out.add("* " + p);
                else methods.forEach(m -> out.add(m.name() + " " + p));
            }
        });
        return out;
    }

    private Set<String> endpointsMatching(String... fragments) {
        Set<String> out = new TreeSet<>();
        for (String e : mappedEndpoints()) {
            String low = e.toLowerCase(Locale.ROOT);
            for (String f : fragments) {
                if (low.contains(f)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    private Long anyUserId() {
        return jdbc.queryForObject("select id from sys_user order by id limit 1", Long.class);
    }

    /** 建一味本用例专用药（零库存、非抗菌药），名字唯一，不蹭种子。 */
    private Long newDrug(String name) {
        return jdbc.queryForObject("""
                insert into md_drug(code, name, spec, unit, price, stock, antibiotic, enabled)
                values (?, ?, '0.25g*24粒/盒', '盒', 10.00, 0, false, true)
                returning id
                """, Long.class, uniq("V51D"), name + uniq("-"));
    }

    private String drugName(Long drugId) {
        return jdbc.queryForObject("select name from md_drug where id = ?", String.class, drugId);
    }

    /** 走平台自己的链路造一张「已接诊」的挂号，可以开单。 */
    private Long visitedRegistration(String patientName, String allergyHistory, LocalDate birthDate) {
        Patient p = new Patient();
        p.setName(patientName + uniq(""));
        p.setSex("U");
        p.setAllergyHistory(allergyHistory);
        p.setBirthDate(birthDate);
        Long pid = patientService.register(p).getId();
        return visitedRegistrationFor(pid);
    }

    private Long visitedRegistrationFor(Long patientId) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(50);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(patientId, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    private Long patientOf(Long registrationId) {
        return jdbc.queryForObject("select patient_id from outp_registration where id = ?",
                Long.class, registrationId);
    }

    /** 开药（HTTP）：返回整个 {code,message,data} 响应体，供检查 code 与<b>返回体形状</b>。 */
    private JsonNode order(Long registrationId, Long drugId, Integer days) {
        var line = new LinkedHashMap<String, Object>();
        line.put("orderType", "DRUG");
        line.put("itemId", drugId);
        line.put("qty", 1);
        line.put("usageRoute", "口服");
        line.put("frequency", "tid");
        line.put("dosePerTime", "1粒");
        line.put("days", days);
        return api("POST", "/api/outpatient/doctor/" + registrationId + "/orders",
                Map.of("lines", List.of(line)));
    }

    /** 开药（Service 直调）：只在需要拿异常码而不关心返回体形状时用。 */
    private int orderCode(Long registrationId, Long drugId, Integer days) {
        try {
            doctorStationService.createOrders(registrationId,
                    List.of(new OrderLine("DRUG", drugId, 1, "口服", "tid", "1粒", days)), null);
            return 0;
        } catch (RegistrationService.BizException e) {
            return e.code;
        } catch (cn.hip.platform.core.common.HipBizException e) {
            return e.code;
        }
    }

    // ---------------- 源码扫描工具（边界断言用；不需要后端状态） ----------------

    /** 从测试工作目录（server 模块）向上找仓库根。 */
    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("modules")) && Files.isDirectory(p.resolve("platform"))) {
                return p;
            }
        }
        return fail("定位不到仓库根（从 " + System.getProperty("user.dir") + " 向上找 modules/ 与 platform/）");
    }

    private record SrcFile(String rel, String text) {}

    /**
     * 遍历真代码。跳过 {@code target/ node_modules/ .claude/worktrees/}——
     * 构建产物与他人工作区不是本仓代码，扫进来会得出假结论。
     */
    private static List<SrcFile> sources(String suffix, String... relDirs) {
        Path root = repoRoot();
        List<SrcFile> out = new ArrayList<>();
        for (String rel : relDirs) {
            Path base = root.resolve(rel);
            if (!Files.isDirectory(base)) continue;
            try (var s = Files.walk(base)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    String path = f.toString().replace('\\', '/');
                    if (path.contains("/target/") || path.contains("/node_modules/")
                            || path.contains("/.claude/") || path.contains("/worktrees/")) {
                        continue;
                    }
                    if (!path.endsWith(suffix)) continue;
                    out.add(new SrcFile(root.relativize(f).toString().replace('\\', '/'),
                            Files.readString(f, StandardCharsets.UTF_8)));
                }
            } catch (Exception e) {
                throw new IllegalStateException("扫描 " + base + " 失败", e);
            }
        }
        return out;
    }

    private static final String[] JAVA_DIRS = {
            "modules", "platform", "server/src/main/java", "datacenter", "bureau", "ai-service", "impl"};

    /**
     * 只认<b>真代码行</b>：注释里写「不做 X——按药名猜是危险的假实现」是<b>声明边界</b>，
     * 恰恰要鼓励；把它算成突破会逼后人删掉说明，反而让边界从可见变成不可见（v48 立、v50 沿用的口径）。
     */
    private static List<String> codeLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String s = line.strip();
            if (s.startsWith("*") || s.startsWith("//") || s.startsWith("/*") || s.startsWith("--")) continue;
            out.add(s);
        }
        return out;
    }

    /** 本版新增的迁移（V152 及以后）。药学知识与零回填两条纪律只约束新增段，不追溯既有迁移。 */
    private static List<SrcFile> newMigrations() {
        Pattern v = Pattern.compile("/V(\\d+)__");
        List<SrcFile> out = new ArrayList<>();
        for (SrcFile f : sources(".sql", "server/src/main/resources/db/migration")) {
            Matcher m = v.matcher("/" + f.rel().substring(f.rel().lastIndexOf('/') + 1));
            // **必须有上界**（v53 复核实测 D8）：原来只写 `>= 152`，
            // 等于给之后**每一版**迁移都埋了雷——v53 的 V157/V158/V159 一落盘，
            // 本类的两条断言立刻变红，而它们要管的本来只是 v51 自己那几条迁移：
            //   · 「错误码不得越过 5699」——v53 的段是 5700–5799，本就该越过；
            //   · 「种子不得含药学知识」——v53 的时限规则种子不是药学知识。
            // 一个版本的测试去审后续版本的产物，红的不是被审者而是审者。
            // 收成 152–156（v51 的 V152–V155 + 合版菜单 V156），后续版本各审各的。
            if (!m.find()) continue;
            int no = Integer.parseInt(m.group(1));
            if (no >= 152 && no <= 156) out.add(f);
        }
        return out;
    }

    // ==================================================================================
    // §1 不变式①：过敏拦截不得有假阴性
    //
    // 「假阴性」在这一节有两个形态，必须分开钉：
    //   ① 直接命中漏掉——患者对 X 过敏、开的药映射到 X，却没命中；
    //   ② 交叉命中漏掉——患者对青霉素过敏、开阿莫西林（同族），却没命中。
    // ② 是本版存在的理由：既有 DDI 那套 `drugName.contains(drug_a)` 子串匹配
    // 在 ② 上**必然漏**，「青霉素」与「阿莫西林」一个字都不重合。
    // ==================================================================================

    /**
     * 结构化过敏原命中：患者对 X 过敏 + X 映射到本院某药 ⇒ 开该药必须命中。
     *
     * <p>本用例<b>刻意不给患者任何自由文本过敏史</b>：那样一旦变绿，就一定是结构化链路
     * 真的通了，而不是既有 4012 关键词路径顺手拦下的。这两条路在返回码上必须分得开。
     */
    @Test
    void structuredAllergyHitsTheMappedDrug() {
        requireTable("cdss_allergen", "车道A", "过敏原字典");
        requireTable("cdss_allergen_drug", "车道A", "过敏原→院内药品显式映射");
        requireTable("cdss_patient_allergy", "车道A", "患者结构化过敏记录");

        Long drugId = newDrug("过敏命中测试药");
        Long rid = visitedRegistration("过敏结构化", null, BusinessDates.today().minusYears(40));
        Long allergenId = newAllergen("直接命中过敏原", "INGREDIENT");
        mapAllergenToDrug(allergenId, drugId, "INGREDIENT");
        recordPatientAllergy(patientOf(rid), allergenId, "SEVERE", "TEST");

        setCfg("cdss.gate.allergy", "block");
        int c = orderCode(rid, drugId, 3);

        assertTrue(c >= ALLERGY_LO && c <= ALLERGY_HI, UNDELIVERED + """
                【假阴性】患者已登记结构化过敏原、该过敏原已显式映射到本次所开药品，\
                gate=block，开单却返回 code=%d（0=放行）。
                期望：%d–%d 之间的过敏子段码（V152 迁移注释自陈 block 档用 5612）。
                这是本版存在的理由本身——「开了患者过敏的药，系统完全拦不住」。
                接入点在 DoctorStationService:715 `cdssService.checkPrescription(...)` 这一行之后，\
                **只增不改**：既有 4012/4015/4017 三条路径逐字不动。

                【最可能的反驳，先答掉】「前端会先调 POST /api/cdss/allergy/check」——**那不是闸**。
                前端调用绕得过去：任何 API 客户端、集成方、住院医嘱侧、脚本导入都不会替你调它。
                闸必须长在服务端的开单路径上；前端那一次调用只是为了让医生**提前**看见，不是拦截依据。
                有端点没接进开单链路，等于把一把锁挂在门边而没装到门上。

                【引擎自身的判定】%s"""
                .formatted(c, ALLERGY_LO, ALLERGY_HI,
                        engineVerdict(patientOf(rid), rid, drugId)));
    }

    /**
     * 交叉过敏族必须命中：青霉素过敏 → 阿莫西林（同族）必须出声。
     *
     * <p><b>命中 ≠ 拦截</b>。V152 迁移署名决定「交叉命中恒为警告、永不拦截」，理由是交叉族
     * 一旦配得宽（青霉素过敏拦下全部头孢）会拦掉大量临床合理处方。本用例因此只断言
     * <b>出声</b>：gate=block 下也要放行，但警告必须随返回体到达医生。
     * 悄悄放行是本条唯一的失败形态。
     */
    @Test
    void crossAllergyFamilyIsHitAndAlwaysWarnsNeverBlocks() {
        requireTable("cdss_allergen_group", "车道A", "交叉过敏族");
        requireTable("cdss_allergen_group_member", "车道A", "族成员");
        requireTable("cdss_allergen_cross", "车道A", "族间交叉风险");

        // 过敏原 A（患者过敏的那个）与过敏原 B（本次所开药映射到的那个）分属两族，两族之间登记交叉。
        Long drugId = newDrug("交叉过敏测试药");
        Long rid = visitedRegistration("过敏交叉", null, BusinessDates.today().minusYears(35));
        Long allergenA = newAllergen("交叉族甲过敏原", "CLASS");
        Long allergenB = newAllergen("交叉族乙过敏原", "INGREDIENT");
        mapAllergenToDrug(allergenB, drugId, "CLASS");
        recordPatientAllergy(patientOf(rid), allergenA, "MODERATE", "CLINICAL");
        Long groupA = newAllergenGroup("交叉族甲");
        Long groupB = newAllergenGroup("交叉族乙");
        addGroupMember(groupA, allergenA);
        addGroupMember(groupB, allergenB);
        linkCross(groupA, groupB, "HIGH");

        setCfg("cdss.gate.allergy", "block");
        JsonNode body = order(rid, drugId, 3);

        assertEquals(0, code(body), """
                【交叉过敏被当成拦截】gate=block 下交叉命中把处方拒了（code=%d）。
                V152 迁移署名决定：交叉命中在三档下**一律只警告不拦截**——
                交叉族配宽一格就会拦掉大量临床合理处方，医生的第一反应是找人把 gate 关掉，
                于是连直接命中的 warn 都一起没了。返回体：%s"""
                .formatted(code(body), trunc(body.toString())));

        assertTrue(mentionsWarning(body), UNDELIVERED + """
                【假阴性·交叉】患者对甲族过敏原过敏，本次所开药映射到乙族过敏原，甲乙两族已登记交叉，\
                开单返回体里**一个字的警告都没有**。
                这正是既有 DDI 那套 `drugName.contains(drug_a)` 子串匹配必然漏掉的形态：
                「青霉素」与「阿莫西林」一个字都不重合，子串匹配永远命中不了。
                交叉不拦截是对的，但**不出声就是漏拦**——医生看不到的提示等于不存在。

                【引擎自身的判定】%s
                实际返回体：%s"""
                .formatted(engineVerdict(patientOf(rid), rid, drugId), trunc(body.toString())));
    }

    /**
     * 【今天就能跑的腿】既有 4012 自由文本关键词拦截仍然生效。
     *
     * <p>这条<b>现在就绿</b>，证明这张网本身通电；同时它是既有链路的防改坏网：
     * 新过敏规则走新表新码，不许有人图省事把 4012 改掉。
     */
    @Test
    void legacyFreeTextAllergyBlockStillWorks() {
        Long rid = visitedRegistration("既有过敏", "青霉素过敏", null);
        Long amox = seeds.drug("阿莫西林").getId();
        assertEquals(4012, orderCode(rid, amox, 3),
                "既有码 4012（过敏禁忌拦截，DoctorStationService.checkRationalDrugUse）不许改动——"
                        + "新过敏规则走新表新码，既有链路逐字不动");
    }

    // ==================================================================================
    // §2 不变式②：自由文本不得脚本解析【本版最重要的一条边界】
    //
    // 「青霉素过敏」与「青霉素皮试阴性」文本相近而语义相反。解析错造成的不是漏一条提示，
    // 是**反向拦截**：该拦的不拦、不该拦的拦住。而假阳性泛滥的真正代价不是麻烦——
    // 医生一旦发现过敏提示经常是错的，就会养成无脑点「继续」的肌肉记忆，
    // 于是真警告也一起被点掉，唯一一道防线的可信度被提前消耗掉。
    // ==================================================================================

    /**
     * 全仓不得出现「把 {@code allergy_history} 自动转结构化过敏原」的代码路径。
     *
     * <p>判据是<b>文件级</b>：文件读了过敏史（{@code getAllergyHistory} / {@code allergy_history}），
     * 且文件里有对 allergy 变量做字符串切分/匹配的<b>真代码行</b>。
     *
     * <p>既有的那一处（{@code DoctorStationService.ALLERGY_CROSS} + {@code allergy.contains(...)}）
     * 登记在 {@link #LEGACY_FREETEXT_PARSERS} 里<b>钉死</b>：允许它存在（铁律「既有链路不许改坏」），
     * 但<b>不许再多一处，也不许它自己长大</b>——见 {@link #legacyKeywordTableMustNotGrow()}。
     */
    @Test
    void freeTextAllergyHistoryIsNeverScriptParsed() {
        // **大小写敏感**且接收者须以小写字母开头：`allergy.contains(...)` 是拿自由文本做匹配（要抓），
        // `ALLERGEN_TYPES.contains(...)` 是校验枚举取值域（正常代码，不能抓）。
        // 加 CASE_INSENSITIVE 时后者会被 `\ballerg\w*` 一起吃掉——本条第一次跑就是这么误报的。
        Pattern parse = Pattern.compile(
                "\\b(?:allerg[A-Za-z]*|getAllergyHistory\\(\\))\\s*\\.\\s*"
                        + "(contains|matches|split|indexOf|startsWith|endsWith|replaceAll)\\s*\\(");
        List<String> hits = new ArrayList<>();
        for (SrcFile f : sources(".java", JAVA_DIRS)) {
            if (!f.text().contains("getAllergyHistory") && !f.text().contains("allergy_history")) continue;
            for (String s : codeLines(f.text())) {
                boolean parsing = parse.matcher(s).find()
                        || (s.contains("allergy_history")
                            && (s.toLowerCase(Locale.ROOT).contains(" like ")
                                || s.toLowerCase(Locale.ROOT).contains("ilike")
                                || s.toLowerCase(Locale.ROOT).contains("regexp")
                                || s.contains("~")))
                        || (s.contains("Pattern.compile") && s.toLowerCase(Locale.ROOT).contains("allerg"));
                if (parsing && !LEGACY_FREETEXT_PARSERS.containsKey(f.rel())) {
                    hits.add(f.rel() + ": " + trunc(s));
                }
            }
        }
        assertTrue(hits.isEmpty(), """
                出现了**新的**自由文本过敏史解析路径：
                %s
                「青霉素过敏」与「青霉素皮试阴性」字符高度相似而语义相反，关键词/正则解析必然同时制造
                假阴性（"PCN 过敏" 漏掉）与假阳性（把皮试阴性判成过敏）。
                合规出路只有一条：**人工确认的迁移工作台**（cdss_allergy_text_review），
                列出原文 → 人逐条判断 → 落结构化过敏原，且原文永不被覆盖。
                既有的一处已登记在 LEGACY_FREETEXT_PARSERS 里钉死，**只许减不许增**。"""
                .formatted(String.join("\n", hits)));

        // 反向：登记表里的文件必须还在。文件被改名/删除而登记未更新，这张网就会静默失效。
        for (var e : LEGACY_FREETEXT_PARSERS.entrySet()) {
            assertTrue(Files.exists(repoRoot().resolve(e.getKey())),
                    "LEGACY_FREETEXT_PARSERS 登记的 " + e.getKey() + " 已不存在——"
                            + "登记表与真代码脱节会让本条断言静默失效，请同步更新登记表。备注：" + e.getValue());
        }
    }

    /**
     * 迁移里不得出现<b>任何</b>从 {@code allergy_history} 派生的写入。
     *
     * <p>这是「迁移零条 update」在本版的具体形态，也是最诱人的一步：
     * {@code insert into cdss_patient_allergy select ... where allergy_history like '%青霉素%'}。
     * 一条 SQL 就能把全院存量刷成结构化，看起来完成度极高，实际是把上面那两类错误
     * <b>一次性、批量、无签名地</b>灌进临床拦截依据里。
     */
    @Test
    void newMigrationsDeriveNothingFromFreeTextAllergyHistory() {
        List<String> hits = new ArrayList<>();
        for (SrcFile f : newMigrations()) {
            String flat = String.join(" ", codeLines(f.text())).toLowerCase(Locale.ROOT);
            for (String verb : List.of("insert into", "update ")) {
                int i = 0;
                while ((i = flat.indexOf(verb, i)) >= 0) {
                    int end = flat.indexOf(';', i);
                    String stmt = flat.substring(i, end < 0 ? flat.length() : end);
                    if (stmt.contains("allergy_history")) hits.add(f.rel() + ": " + trunc(stmt));
                    i += verb.length();
                }
            }
        }
        assertTrue(hits.isEmpty(), """
                V152+ 迁移里出现了引用 allergy_history 的写语句：
                %s
                自由文本过敏史**只能被读来给人看**，不能被任何脚本转成结构化拦截依据。
                原文归原文（empi_patient.allergy_history 一个字节不改），结构化归结构化，人工核对是唯一桥梁。"""
                .formatted(String.join("\n", hits)));
    }

    /**
     * 既有关键词表<b>不许再长</b>。
     *
     * <p>本版最可能出现的偷懒动作，是往 {@code ALLERGY_CROSS} 里再塞几组词把假阴性「补上」。
     * 那个方向<b>越走越危险</b>：词越多，撞上「皮试阴性」「否认…过敏」「家族史」的概率越高，
     * 假阳性随之上升，最后医生对所有过敏提示都免疫。故这张表的规模在此钉死。
     */
    @Test
    void legacyKeywordTableMustNotGrow() {
        for (var e : LEGACY_FREETEXT_PARSERS.entrySet()) {
            String text;
            try {
                text = Files.readString(repoRoot().resolve(e.getKey()), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new IllegalStateException("读不到 " + e.getKey(), ex);
            }
            long entries = codeLines(text).stream()
                    .filter(s -> s.contains("List.of(") && s.contains("\", List.of("))
                    .count();
            assertTrue(entries <= LEGACY_KEYWORD_ENTRY_CAP.get(e.getKey()),
                    ("%s 的关键词映射条目从 %d 涨到了 %d。\n"
                     + "往关键词表里加词是**最危险的补法**：词越多越容易撞上「皮试阴性」「否认药物过敏史」"
                     + "「家族史」这些语义相反的写法，假阳性上升到一定程度，医生对全部过敏提示免疫。\n"
                     + "要补假阴性只有一条合规路径：结构化过敏原 + 显式药品映射（cdss_allergen_drug），"
                     + "存量自由文本走人工核对工作台。")
                            .formatted(e.getKey(), LEGACY_KEYWORD_ENTRY_CAP.get(e.getKey()), entries));
        }
    }

    /**
     * 既然不许自动解析，就<b>必须</b>给一个人工核对的地方——否则「只堵不疏」。
     *
     * <p>本条的失败信息里带着两个方向的<b>实测</b>结果，它们是「工作台不是可选项」的证据：
     * 自由文本这道闸今天既漏（患者写明「阿莫西林过敏」，开阿莫西林胶囊照样放行）
     * 又误（患者写「青霉素皮试阴性」，开阿莫西林胶囊被 4012 拦下）。
     * 两个方向都错的闸不叫闸。
     */
    @Test
    void humanReviewWorkbenchExistsBecauseAutoParsingIsForbidden() {
        requireTable("cdss_allergy_text_review", "车道A", "自由文本过敏史人工核对处置记录");

        // 待办口径必须是「实时 join 算出来」的，不能预生成任务行——原文会变，预生成的任务行
        // 会把「已核对」的标记挂在旧原文上，让新写进去的过敏史看起来已经核对过。
        Long n = jdbc.queryForObject("select count(*) from cdss_allergy_text_review", Long.class);
        assertEquals(0L, n, "cdss_allergy_text_review 在迁移后必须为空表——"
                + "预生成待办行需要一条 insert...select 把存量患者刷进来，违反零回填纪律");

        Set<String> eps = endpointsMatching("allergy", "过敏");
        assertFalse(eps.isEmpty(), UNDELIVERED + """
                过敏模块**一个端点都没有**：结构化过敏原登记、药品映射维护、交叉族维护、\
                自由文本人工核对工作台，全部不可达。
                有表没入口等于没交付——表永远是空的，于是「表里没有违规行」这类断言全部恒真而毫无意义。

                【为什么工作台不是可选项——两个方向的实测】
                %s

                两个方向都错的闸不叫闸。合规解法只有：原文照旧给人看，机器只认人工确认过的结构化过敏原。"""
                .formatted(measureFreeTextGate()));

        Set<String> workbench = new TreeSet<>();
        for (String e : mappedEndpoints()) {
            String low = e.toLowerCase(Locale.ROOT);
            if ((low.contains("allerg") || low.contains("cdss"))
                    && (low.contains("worklist") || low.contains("review")
                        || low.contains("migration") || low.contains("workbench"))) {
                workbench.add(e);
            }
        }
        assertFalse(workbench.isEmpty(),
                UNDELIVERED + "缺**人工核对工作台**端点（待办列表 + 逐条处置）。"
                        + "已有的过敏端点：" + eps + "。"
                        + "禁止自动解析而不给人工入口，等于把存量患者的过敏史永久排除在审查之外——"
                        + "而 cdss_allergy_gate_log.unreviewed_text 那一列正是为这件事准备的。");
    }

    /** 两个方向的实测（只测量、不断言；结论进上一条的失败信息）。 */
    private String measureFreeTextGate() {
        StringBuilder sb = new StringBuilder();
        try {
            Long amox = seeds.drug("阿莫西林").getId();
            Long ridFn = visitedRegistration("假阴性实测", "阿莫西林过敏，皮疹", null);
            int fn = orderCode(ridFn, amox, 3);
            sb.append("  · 假阴性：过敏史原文「阿莫西林过敏，皮疹」，开 ")
                    .append(drugName(amox)).append(" → code=").append(fn)
                    .append(fn == 0 ? "（**放行**，该拦没拦：关键词表里没有「阿莫西林」这一项，"
                                      + "而 `allergy.contains(drugName)` 比的是全名「阿莫西林胶囊」，"
                                      + "与原文对不上）" : "（已拦）").append('\n');

            Long ridFp = visitedRegistration("假阳性实测", "青霉素皮试阴性（我院）", null);
            int fp = orderCode(ridFp, amox, 3);
            sb.append("  · 假阳性：过敏史原文「青霉素皮试阴性（我院）」，开 ")
                    .append(drugName(amox)).append(" → code=").append(fp)
                    .append(fp == 4012 ? "（**被拦**，不该拦却拦了：原文含「青霉素」三字即命中交叉表，"
                                         + "而「皮试阴性」恰恰说明可以用）" : "（已放行）");
        } catch (Exception e) {
            sb.append("  · 未能实测（").append(e.getClass().getSimpleName()).append("）");
        }
        return sb.toString();
    }

    /**
     * 【登记表】既有的自由文本关键词路径。<b>登记 = 允许存在，不等于允许扩散。</b>
     *
     * <p>这不是给缺陷发通行证：铁律「既有链路不许改坏」封住了直接删除的路，
     * 而修好它（识别否定语义）又必然要写一个更强的文本解析器——正是本版禁止的动作。
     * 两头都堵死时，唯一负责任的做法是<b>把它钉住、不让它长大</b>，同时把合规替代品
     * （结构化 + 工作台）建起来，等替代品覆盖率上来后再由主控署名决定怎么退役它。
     */
    private static final Map<String, String> LEGACY_FREETEXT_PARSERS = new LinkedHashMap<>();

    /** 各登记文件里允许的关键词映射条目数上限（当前实测值，只许减不许增）。 */
    private static final Map<String, Integer> LEGACY_KEYWORD_ENTRY_CAP = new LinkedHashMap<>();

    static {
        LEGACY_FREETEXT_PARSERS.put(
                "modules/outpatient/src/main/java/cn/hip/outpatient/service/DoctorStationService.java",
                "checkRationalDrugUse + ALLERGY_CROSS（4 组关键词）：allergy.contains(关键词) 命中即抛 4012。"
                        + "既漏（原文写「阿莫西林过敏」时不命中）又误（原文写「青霉素皮试阴性」时命中）。"
                        + "v51 不动它（既有链路 + RationalDrugRulesTest 已钉 4012），但**规模在此封顶**。");
        LEGACY_KEYWORD_ENTRY_CAP.put(
                "modules/outpatient/src/main/java/cn/hip/outpatient/service/DoctorStationService.java", 4);
    }

    // ==================================================================================
    // §3 不变式③：不得内置来源不明的药学知识
    //
    // 商用 CDSS 知识库内容属硬边界（与财政票据、HQMS、医保结算清单同级）。
    // 编出来的配伍禁忌**看起来很专业**，医生会信，然后出事。
    // ==================================================================================

    /**
     * 本版新增迁移里，规则/字典表的种子行必须<b>为空</b>，或<b>每一条都自陈是示例</b>。
     *
     * <p>判据取整条 {@code insert} 语句：语句里必须出现示例/占位/SAMPLE/EXAMPLE 之一。
     * 「上线前须由药剂科替换」这句话是给实施看的，它必须在<b>数据里</b>，不能只在注释里——
     * 注释不会随 {@code select * from} 出现在药剂科的屏幕上。
     */
    @Test
    void newMigrationSeedsCarryNoPharmacologyKnowledge() {
        List<String> bad = new ArrayList<>();
        for (SrcFile f : newMigrations()) {
            String flat = String.join(" ", codeLines(f.text()));
            int i = 0;
            String low = flat.toLowerCase(Locale.ROOT);
            while ((i = low.indexOf("insert into", i)) >= 0) {
                int end = flat.indexOf(';', i);
                String stmt = flat.substring(i, end < 0 ? flat.length() : end);
                String table = stmt.replaceFirst("(?i)insert\\s+into\\s+", "").split("[\\s(]")[0]
                        .toLowerCase(Locale.ROOT);
                i += "insert into".length();
                // sys_config 是 gate 与阈值，不是药学知识；菜单/权限同理
                if (table.startsWith("sys_") || table.startsWith("sec_") || table.startsWith("auth_")) continue;
                boolean declaresSample = stmt.contains("示例") || stmt.contains("占位")
                        || stmt.toUpperCase(Locale.ROOT).contains("SAMPLE")
                        || stmt.toUpperCase(Locale.ROOT).contains("EXAMPLE");
                if (!declaresSample) bad.add(f.rel() + " → " + table + ": " + trunc(stmt));
            }
        }
        assertTrue(bad.isEmpty(), """
                V152+ 迁移往规则/字典表里灌了**没有自陈是示例**的行：
                %s
                本版只做规则引擎，规则内容由药剂科按院内用药目录维护。
                种子最多给「空表 + 一条标注了示例的行」。编出来的配伍禁忌会直接误导处方——
                它看起来很专业，医生会信。"""
                .formatted(String.join("\n", bad)));
    }

    /** 示例行必须<b>不可能命中</b>：只标注「示例」而仍然生效，等于把假知识挂在生产链路上。 */
    @Test
    void sampleSeedRowsCannotPossiblyFire() {
        if (tableExists("cdss_allergen")) {
            Long live = jdbc.queryForObject(
                    "select count(*) from cdss_allergen where enabled = true and name like '%示例%'", Long.class);
            assertEquals(0L, live, "cdss_allergen 里存在 enabled=true 的「示例」过敏原——"
                    + "示例行必须 enabled=false，否则它会真的参与审查");
        }
        if (tableExists("cdss_allergen_drug")) {
            assertEquals(0L, (long) jdbc.queryForObject("select count(*) from cdss_allergen_drug", Long.class),
                    "cdss_allergen_drug（过敏原→药品映射）在迁移后必须是**空表**："
                            + "哪个药含哪个成分属院内用药目录范畴，预置一条就是替药剂科做药学判断");
        }
        if (tableExists("cdss_allergen_cross")) {
            assertEquals(0L, (long) jdbc.queryForObject("select count(*) from cdss_allergen_cross", Long.class),
                    "cdss_allergen_cross（族间交叉风险）在迁移后必须是**空表**："
                            + "「青霉素与头孢交叉率 X%」随文献与头孢代际大幅变动，编一个进去就是假证据");
        }
        if (tableExists("cdss_population_rule")) {
            Long live = jdbc.queryForObject(
                    "select count(*) from cdss_population_rule where enabled = true", Long.class);
            assertEquals(0L, live, "cdss_population_rule 里存在 enabled=true 的规则——"
                    + "妊娠/哺乳/肝肾禁忌属药学知识硬边界，迁移不得预置任何一条生效规则");
        }
    }

    /**
     * 代码里不得硬编码药名配伍/交叉表。
     *
     * <p>判据是「药名字面量 + 匹配或名单构造」的组合，不是「出现过药名」——
     * 注释里写「不做 X」是声明边界，要鼓励（v50 已立此口径）。
     * 既有两处（{@code DoctorStationService.ALLERGY_CROSS} / {@code OutpNurseStationController.SKIN_TEST_CATEGORIES}）
     * 登记在 {@link #LEGACY_HARDCODED_DRUG_KNOWLEDGE} 里钉死，<b>只许减不许增</b>。
     */
    @Test
    void noHardcodedDrugKnowledgeInNewCode() {
        String[] drugs = {"青霉素", "头孢", "磺胺", "阿司匹林", "华法林", "阿莫西林", "布洛芬", "左氧氟沙星",
                "甲硝唑", "地高辛", "二甲双胍", "胰岛素", "他汀", "克拉霉素", "红霉素", "呋塞米", "螺内酯",
                "卡马西平", "苯妥英", "利福平", "氨茶碱", "双硫仑", "藿香正气", "喹诺酮", "西林", "碘伏"};
        String[] ctx = {"contains(", "equals(", "matches(", "indexof(", "startswith(", "endswith(",
                " like ", "like '", "ilike", "regexp", "pattern.compile", "list.of(", "set.of(",
                "map.of(", "arrays.aslist", " in (", "switch ("};
        List<String> hits = new ArrayList<>();
        for (SrcFile f : sources(".java", JAVA_DIRS)) {
            if (LEGACY_HARDCODED_DRUG_KNOWLEDGE.containsKey(f.rel())) continue;
            for (String s : codeLines(f.text())) {
                boolean named = false;
                for (String d : drugs) {
                    if (s.contains(d)) { named = true; break; }
                }
                if (!named) continue;
                String low = s.toLowerCase(Locale.ROOT);
                for (String c : ctx) {
                    if (low.contains(c)) { hits.add(f.rel() + ": " + trunc(s)); break; }
                }
            }
        }
        assertTrue(hits.isEmpty(), """
                真代码里出现了「药名字面量 + 匹配/名单构造」的组合，说明有人把药学知识编进了代码：
                %s
                规则内容必须落在**可维护的数据结构**里由药剂科维护，不许硬编码进代码或迁移种子。
                硬编码的配伍表还有一个附带伤害：它不随院内用药目录更新，药剂科改不动、也看不见。"""
                .formatted(String.join("\n", hits)));
    }

    /**
     * 【登记表】既有的硬编码药学知识。同 {@link #LEGACY_FREETEXT_PARSERS}：登记 ≠ 发通行证，
     * 而是把它钉住不让它扩散。两处都在 v51 之前就存在，铁律封住了改动它们的路。
     */
    private static final Map<String, String> LEGACY_HARDCODED_DRUG_KNOWLEDGE = new LinkedHashMap<>();

    static {
        LEGACY_HARDCODED_DRUG_KNOWLEDGE.put(
                "modules/outpatient/src/main/java/cn/hip/outpatient/service/DoctorStationService.java",
                "ALLERGY_CROSS：青霉素→西林/青霉素、头孢、磺胺、阿司匹林→水杨酸。硬编码的交叉过敏判断。");
        LEGACY_HARDCODED_DRUG_KNOWLEDGE.put(
                "modules/outpatient/src/main/java/cn/hip/outpatient/web/OutpNurseStationController.java",
                "SKIN_TEST_CATEGORIES：青霉素类→青霉素/西林、头孢类→头孢。皮试类别按药名子串判定。");
    }

    /**
     * 既然不许硬编码、不许预置，就<b>必须</b>给药剂科录入口——同 v50「只堵不疏」那一课。
     *
     * <p>没有维护入口时，映射表恒空 ⇒ 过敏审查恒不命中 ⇒ §1 的全部断言即使写着也永远不会红。
     * <b>一条永远不会红的断言等于没有断言。</b>
     */
    @Test
    void ruleContentHasAMaintenanceWritePath() {
        requireTable("cdss_allergen_drug", "车道A", "过敏原→药品映射");
        Set<String> writers = new TreeSet<>();
        for (String e : mappedEndpoints()) {
            String low = e.toLowerCase(Locale.ROOT);
            boolean write = low.startsWith("post ") || low.startsWith("put ") || low.startsWith("delete ");
            if (write && (low.contains("allergen") || low.contains("allergy")
                    || low.contains("population") || low.contains("cdss"))) {
                writers.add(e);
            }
        }
        assertFalse(writers.isEmpty(), UNDELIVERED + """
                CDSS 新规则（过敏原字典 / 药品映射 / 交叉族 / 特殊人群规则）**没有任何写入端点**。
                后果不是「少个页面」，而是：映射表恒空 → 过敏审查恒不命中 →
                本类 §1 的断言即使写着也永远不会红。**一条永远不会红的断言等于没有断言。**
                既有 POST /api/cdss/ddi-rules（码 4650）就是现成的样板：一个 @PreAuthorize 的维护端点。
                当前全部 CDSS 写端点：%s""".formatted(endpointsMatching("cdss")));
    }

    // ==================================================================================
    // §4 不变式④：gate 默认 warn，且 warn 真的出声
    // ==================================================================================

    /**
     * 新增 gate 必须三态、默认 warn。坏配置回落 warn 而非 off——两种回落的代价不对称。
     *
     * <p>两条腿一起验：<b>迁移里的字面量</b>（那才是真正的「出厂值」，库里的值可能是谁改过的）
     * 与<b>库里的当前值</b>。只验后者时，一次 update 就能让本条变绿；只验前者时，
     * 迁移写对了但没落库（键名写错、on conflict 吃掉）也发现不了。
     */
    @Test
    void everyNewCdssGateDefaultsToWarn() {
        Pattern seed = Pattern.compile("\\(\\s*'([a-z0-9_.]*\\.gate\\.[a-z0-9_.]+)'\\s*,\\s*'([^']*)'");
        List<String> badSeeds = new ArrayList<>();
        Set<String> seededGates = new TreeSet<>();
        for (SrcFile f : newMigrations()) {
            Matcher m = seed.matcher(f.text());
            while (m.find()) {
                seededGates.add(m.group(1));
                if (!"warn".equals(m.group(2))) {
                    badSeeds.add(f.rel() + ": " + m.group(1) + " 出厂值 = '" + m.group(2) + "'");
                }
            }
        }
        assertTrue(badSeeds.isEmpty(), "新 gate 的**出厂值**（迁移字面量）不是 warn：\n"
                + String.join("\n", badSeeds));
        assertFalse(seededGates.isEmpty(), UNDELIVERED
                + "V152+ 迁移里一个 *.gate.* 都没种：本版新增的每个拦截点都要有自己的三态 gate。");

        List<Map<String, Object>> gates = jdbc.queryForList(
                "select cfg_key, cfg_value from sys_config where cfg_key like 'cdss.gate.%' order by cfg_key");
        assertFalse(gates.isEmpty(), UNDELIVERED
                + "sys_config 里一个 cdss.gate.* 都没有：本版新增的拦截点必须各有一个三态 gate。");

        for (Map<String, Object> g : gates) {
            assertEquals("warn", String.valueOf(g.get("cfg_value")),
                    "gate " + g.get("cfg_key") + " 的**出厂值**必须是 warn。"
                            + "过敏这类高危拦截也默认 warn——此前从无此校验，直接 block 会让存量处方大面积失败，"
                            + "医生的第一反应是找人把 gate 整个关掉，于是连 warn 都没了。");
        }
        assertTrue(gates.stream().anyMatch(g -> "cdss.gate.allergy".equals(g.get("cfg_key"))),
                UNDELIVERED + "缺 cdss.gate.allergy——过敏是本版最严重的缺口，它必须有自己的档位。"
                        + "现有：" + gates);
    }

    /**
     * 坏配置必须回落 <b>warn</b>，不是 off。
     *
     * <p>回落 off 会让一处配置笔误（"WARN"、"true"、"1"、"blocked"）<b>静默关掉整段临床校验</b>，
     * 而回落 warn 只是多几条提示。两种回落的代价差着一个数量级，取代价小的那个。
     */
    @Test
    void badGateValueFallsBackToWarnNotOff() {
        requireTable("cdss_allergen_drug", "车道A", "过敏原→药品映射");

        Long drugId = newDrug("坏档位测试药");
        Long rid = visitedRegistration("坏档位", null, BusinessDates.today().minusYears(30));
        Long allergenId = newAllergen("坏档位过敏原", "INGREDIENT");
        mapAllergenToDrug(allergenId, drugId, "INGREDIENT");
        recordPatientAllergy(patientOf(rid), allergenId, "SEVERE", "TEST");

        setCfg("cdss.gate.allergy", "blocked");     // 典型笔误：多了个 ed
        JsonNode body = order(rid, drugId, 3);

        assertEquals(0, code(body),
                "坏配置回落必须是 warn（放行 + 出声），不是 block（拒绝）。实际 code=" + code(body));
        assertTrue(mentionsWarning(body), UNDELIVERED + """
                gate 值写成笔误 'blocked' 后，命中的过敏**一声不吭地放行了**——这说明坏值回落到了 off。
                一处配置笔误静默关掉整段过敏校验，是本仓最怕的失败形态：没有报错、没有日志、
                没有人会发现，直到出事。回落必须落到 warn。实际返回体：%s"""
                .formatted(trunc(body.toString())));
    }

    /**
     * 【今天就能跑的腿】<b>warn 档必须真的把提示给到医生</b>。
     *
     * <p>本条用<b>既有</b>的疗程上限（CAUTION）跑：{@code CdssService} 命中 CAUTION 时
     * 只往 {@code cdss_alert} 写一行留痕，<b>返回体里一个字都没有</b>——
     * 医生开完单什么也看不见，提示躺在一张要另外去查的表里。
     * 那不叫 warn，那叫「记了个账」。
     *
     * <p>这也是本版所有新 gate 的 warn 档必须一起满足的口径，故先用既有链路把它钉出来。
     */
    @Test
    void warnGateMustReachTheDoctorNotJustTheAlertTable() {
        Long drugId = newDrug("疗程上限测试药");
        String name = drugName(drugId);
        jdbc.update("insert into cdss_dose_rule(drug_keyword, max_days, message) values (?, ?, ?)",
                name, 5, "本用例自建的疗程上限规则（不依赖种子内容）");

        Long rid = visitedRegistration("疗程提醒", null, BusinessDates.today().minusYears(45));
        JsonNode body = order(rid, drugId, 10);

        assertEquals(0, code(body), "疗程上限是 CAUTION 档，必须放行。实际：" + trunc(body.toString()));
        Long alerts = jdbc.queryForObject(
                "select count(*) from cdss_alert where registration_id = ? and rule_type = 'DOSE'",
                Long.class, rid);
        assertEquals(1L, alerts, "CAUTION 命中必须留痕（既有行为，不许改坏）");

        assertTrue(mentionsWarning(body), UNDELIVERED + """
                warn/CAUTION 档命中后，**返回体里一个字的提示都没有**：CdssService.alert() 只往
                cdss_alert 写了一行，医生开完单什么都看不见。
                主控口径原文：「warn 档必须**真的把提示给到医生**（返回体带 warnings），不能静默」。
                躺在一张要另外去查的表里的提示，等于没有提示——这不是 warn，这是记了个账。
                本条同时是本版三个新 gate（allergy/duplicate/population）warn 档的验收口径。
                实际返回体：%s""".formatted(trunc(body.toString())));
    }

    /**
     * off 档返回体与 v50 <b>逐字同形</b>。
     *
     * <p>本版接进开单校验时「只增不改」。最容易破坏的就是返回体形状：
     * 把 {@code data} 从订单<b>数组</b>包成 {@code {orders:[], warnings:[]}} 对象，
     * 前端与既有 e2e 会在 off 档（本该毫无变化的那一档）一起挂掉。
     */
    @Test
    void offGateKeepsTheLegacyResponseShapeVerbatim() {
        for (Map<String, Object> g : jdbc.queryForList(
                "select cfg_key from sys_config where cfg_key like 'cdss.gate.%'")) {
            setCfg(String.valueOf(g.get("cfg_key")), "off");
        }
        Long drugId = newDrug("off档形状药");
        Long rid = visitedRegistration("off档", null, BusinessDates.today().minusYears(28));
        JsonNode body = order(rid, drugId, 3);

        assertEquals(0, code(body), "off 档必须旁路全部新校验。实际：" + trunc(body.toString()));
        JsonNode data = body.path("data");
        assertTrue(data.isArray(), """
                off 档下 data 不再是**订单数组**（v50 逐字口径：R.ok(List<OutpOrder>)），而是 %s。
                本版接进开单校验的纪律是「只增不改」：新增的 warnings 只能作为**新增字段**随行下发，
                不能把既有 data 包一层。off 档是那个「本该毫无变化」的档位，它一变形，
                既有前端与 15 套 e2e 会在最不该出问题的地方一起挂。实际返回体：%s"""
                .formatted(data.getNodeType(), trunc(body.toString())));
        assertEquals(1, data.size(), "开了一行就该回一行");
        for (String k : List.of("id", "registrationId", "groupNo", "orderType", "itemId", "itemName",
                "qty", "unitPrice", "amount", "status")) {
            assertTrue(data.get(0).has(k), "off 档订单元素缺既有字段 " + k + "：" + trunc(data.toString()));
        }
        assertFalse(mentionsWarning(body),
                "off 档必须**完全旁路**，连提示都不该出现（那是 warn 档的事）：" + trunc(body.toString()));
    }

    // ==================================================================================
    // §5 不变式⑤：不得按性别年龄猜妊娠
    // ==================================================================================

    /**
     * 「育龄女性即可能妊娠」这类推断一律不许出现。
     *
     * <p>它同时制造两类错误：把所有 15–49 岁女性当孕妇（大面积假阳性 → 提示疲劳 → 真警告被点掉），
     * 又漏掉一切不在该区间的真孕妇。妊娠状态<b>只能来自人工申报</b>
     * （{@code cdss_population_status}，且必须记来源与失效日）。
     */
    @Test
    void pregnancyIsNeverInferredFromSexAndAge() {
        Pattern preg = Pattern.compile("妊娠|怀孕|孕妇|育龄|哺乳|pregnan|gestation|lactat",
                Pattern.CASE_INSENSITIVE);
        Pattern demo = Pattern.compile("getSex|\\bsex\\b|性别|\"F\"|'F'|女|getBirthDate|birth_date|birthDate"
                + "|getYears|childbear|\\bage\\b|年龄", Pattern.CASE_INSENSITIVE);
        List<String> hits = new ArrayList<>();
        for (SrcFile f : sources(".java", JAVA_DIRS)) {
            for (String s : codeLines(f.text())) {
                if (preg.matcher(s).find() && demo.matcher(s).find()) hits.add(f.rel() + ": " + trunc(s));
            }
        }
        assertTrue(hits.isEmpty(), """
                真代码里出现了「妊娠/哺乳 + 性别或年龄」的组合，疑似按人口学特征推定妊娠状态：
                %s
                「育龄女性即可能妊娠」两头都错：把全部 15–49 岁女性当孕妇（假阳性泛滥 → 提示疲劳 →
                真警告一起被点掉），又漏掉区间外的真孕妇。
                妊娠状态**只能来自人工申报**（cdss_population_status，带来源分级与失效日），
                查不到有效申报 = 未采集，与「已问过、不是」语义不同，不得互相顶替。"""
                .formatted(String.join("\n", hits)));

        if (tableExists("cdss_population_status")) {
            // 无失效日的妊娠标记会在产后继续拦截该患者的全部处方，且没人会想起来去清它。
            Long bad = jdbc.queryForObject("""
                    select count(*) from cdss_population_status
                     where status in ('PREGNANT','LACTATING') and valid_until is null
                    """, Long.class);
            assertEquals(0L, bad, "存在无失效日的妊娠/哺乳申报——「一次录入、永久误拦」比不记更糟");
        }
    }

    // ==================================================================================
    // §6 不变式⑥：盘点不得静默抹平批次层差异
    //
    // v50 车道 E 把这一条署名登记进 DECLARED_OUT_OF_SCOPE：
    //   「盘点会把汇总拉到实存、把差额永久抹平，而批次层的错账原地不动、无人知晓，
    //     表现为『看起来一直对得上、实际每次都靠盘点擦屁股』」。
    // 本版必须还这笔账。
    // ==================================================================================

    /**
     * 人为造出批次层与汇总的差异，跑一次盘点确认，断言差异<b>被报出来而不是被抹平</b>。
     *
     * <p>合格的收口有三种形态，任一即可：
     * <ol>
     *   <li>盘点<b>盘到批次层</b>：批次余额跟着调，并写一条带盘点单号的 ADJ 流水；
     *   <li>盘点<b>拒绝在有漂移时确认</b>（同 8008「账面已变化，请重新盘点」的纪律）；
     *   <li>盘点<b>确认时把批次层差异随返回体报出来</b>，让药师知道还有一本账没对。
     * </ol>
     * 唯一不合格的，是三样都没有——那正是今天的行为：汇总被拉到实存，批次层原地不动，
     * 盘点单上写着「已确认、净盈亏 -5」，没有任何地方提到批次账现在错了 5 盒。
     */
    @Test
    void stockTakeMustNotSilentlySmoothOverBatchLayerDivergence() {
        requireTable("pharm_stock", "车道A(v50)", "批次余额表");
        requireTable("pharm_stock_flow", "车道A(v50)", "批次流水表");

        Long drugId = newDrug("盘点批次层测试药");
        // 入库 100：总账与批次子账此刻都是 100（v50 已双写）
        JsonNode in = api("POST", "/api/pharm/stock/stock-in", Map.of(
                "drugId", drugId, "qty", 100, "batchNo", uniq("V51B"),
                "expireOn", BusinessDates.today().plusDays(400).toString(),
                "supplier", "对账用供应商"));
        assertEquals(0, code(in), "入库应成功：" + trunc(in.toString()));
        flushIfInTransaction();
        assertEquals(100, aggregateStock(drugId), "入库后总账应为 100");
        assertEquals(100, batchStock(drugId), "入库后批次子账应为 100");

        // 实盘 95（实物少 5 盒——不论少在哪个批次，账上必须有人回答这个问题）
        JsonNode take = api("POST", "/api/inventory/stock-take",
                Map.of("drugIds", List.of(drugId), "remark", "v51 批次层收敛验证"));
        assertEquals(0, code(take), "建盘点单应成功：" + trunc(take.toString()));
        long takeId = take.path("data").path("id").asLong();
        String takeNo = take.path("data").path("takeNo").asText();
        assertEquals(0, code(api("POST", "/api/inventory/stock-take/" + takeId + "/counts",
                Map.of("entries", List.of(Map.of("drugId", drugId, "actualQty", 95))))), "录实盘数应成功");
        JsonNode confirm = api("POST", "/api/inventory/stock-take/" + takeId + "/confirm", Map.of());
        flushIfInTransaction();

        int agg = aggregateStock(drugId);
        int batch = batchStock(drugId);
        Long adjFlows = jdbc.queryForObject("""
                select count(*) from pharm_stock_flow f join pharm_batch b on b.id = f.batch_id
                 where b.drug_id = ? and f.flow_type = 'ADJ'
                """, Long.class, drugId);
        String confirmBody = confirm.toString();
        // 「报出来」= 药师看得见。确认返回体、或盘点单详情页任一处说了这件事都算数；
        // 只往某张核对表里写一行而没人看得见，与 §4 的「记了个账」是同一种失败。
        String detailBody = api("GET", "/api/inventory/stock-take/" + takeId, null).toString();
        boolean reported = mentionsBatchDivergence(confirmBody) || mentionsBatchDivergence(detailBody);
        boolean refused = code(confirm) != 0;
        boolean converged = agg == batch && adjFlows > 0;

        assertTrue(converged || refused || reported, UNDELIVERED + """
                【盘点静默抹平批次层差异】入库 100 → 实盘 95 → 确认盘点后：
                  · 总账 md_drug.stock  = %d（已被拉到实存）
                  · 批次子账 Σpharm_stock.qty = %d（**原地不动**）
                  · 批次层 ADJ 流水 = %d 条
                  · 盘点确认返回体与盘点单详情页里都既没提 batch/批次，也没提 drift/漂移
                于是盘点单上写着「已确认、净盈亏 -5」，而批次账现在错了 %d 盒，**没有任何地方说这件事**。
                这正是 v50 车道 E 署名登记的欠账：「看起来一直对得上、实际每次都靠盘点擦屁股」——
                下一次召回时，会照着一本错了 %d 盒的批次账去下架真药。

                三条合格出路任选其一（本条不规定实现）：
                  ① 盘点盘到批次层：批次余额跟着调 + 写一条 ref_no=%s 的 ADJ 流水（口径已在
                     V50PharmacyTest.BATCH_FLOW_TYPES 登记为 "ADJ 盘点调整"，位子一直留着）；
                  ② 有漂移时**拒绝确认**，同 8008「账面已变化，请重新盘点」的纪律；
                  ③ 确认时把批次层差异**随返回体报出来**，让药师知道还有一本账没对。
                唯一不合格的是三样都没有。实际返回体：%s"""
                .formatted(agg, batch, adjFlows, batch - agg, batch - agg, takeNo, trunc(confirmBody)));
    }

    /** 【今天就能跑的腿】批次层自证端点确实会抓不一致——它是上一条 ③ 号出路的现成载体。 */
    @Test
    void batchReconcileEndpointStillDetectsMismatch() {
        JsonNode rec = api("GET", "/api/pharm/stock/reconcile", null);
        assertEquals(0, code(rec), "GET /api/pharm/stock/reconcile 必须可用（v50 既有端点）");
        for (String k : List.of("checked", "mismatched", "balanced", "rows")) {
            assertTrue(rec.path("data").has(k),
                    "对账端点返回体缺 " + k + "：" + trunc(rec.toString()));
        }
    }

    // ==================================================================================
    // §7 既有 CDSS 契约的防改坏网
    //
    // 新规则走新表新端点。既有三类规则与 4 个端点契约**逐字不动**，码 4015/4017/4650 原样。
    // ==================================================================================

    @Test
    void legacyCdssEndpointsAndTablesAreUnchanged() {
        for (String t : List.of("cdss_ddi_rule", "cdss_dose_rule", "cdss_age_rule",
                "cdss_suggestion", "cdss_alert")) {
            assertTrue(tableExists(t), "既有 CDSS 规则表 " + t + " 不许删改");
        }
        Set<String> eps = mappedEndpoints();
        for (String e : List.of("GET /api/cdss/rules", "POST /api/cdss/ddi-rules",
                "GET /api/cdss/alerts", "GET /api/cdss/suggestions")) {
            assertTrue(eps.contains(e), "既有 CDSS 端点 " + e + " 契约逐字不动。当前 /api/cdss 下："
                    + endpointsMatching("/api/cdss"));
        }
        JsonNode rules = api("GET", "/api/cdss/rules", null);
        assertEquals(0, code(rules), "GET /api/cdss/rules 应成功");
        for (String k : List.of("ddi", "dose", "age", "suggestions")) {
            assertTrue(rules.path("data").has(k), "规则库总览缺既有分组 " + k + "：" + trunc(rules.toString()));
        }
    }

    /** 既有 DDI FORBID 仍抛 4015（用本用例自建的规则跑，不依赖种子内容）。 */
    @Test
    void legacyDdiForbidStillThrows4015() {
        Long a = newDrug("DDI甲药");
        Long b = newDrug("DDI乙药");
        jdbc.update("insert into cdss_ddi_rule(drug_a, drug_b, severity, message) values (?,?,?,?)",
                drugName(a), drugName(b), "FORBID", "本用例自建的相互作用规则（不依赖种子内容）");
        Long rid = visitedRegistration("DDI回归", null, BusinessDates.today().minusYears(50));
        doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", a, 1, "口服", "qd", "1粒", 3)), null);
        assertEquals(4015, orderCode(rid, b, 3), "既有码 4015（CDSS 相互作用拦截）不许改动");
    }

    /** 既有年龄禁忌仍抛 4017。 */
    @Test
    void legacyAgeRuleStillThrows4017() {
        Long drugId = newDrug("年龄禁忌药");
        jdbc.update("insert into cdss_age_rule(drug_keyword, min_age, max_age, message) values (?,?,?,?)",
                drugName(drugId), 18, null, "本用例自建的年龄规则（不依赖种子内容）");
        Long rid = visitedRegistration("年龄回归", null, BusinessDates.today().minusYears(10));
        assertEquals(4017, orderCode(rid, drugId, 3), "既有码 4017（CDSS 年龄禁忌拦截）不许改动");
    }

    /** 既有 DDI 规则维护端点的参数校验码仍是 4650。 */
    @Test
    void legacyDdiRuleValidationStillReturns4650() {
        JsonNode r = api("POST", "/api/cdss/ddi-rules",
                Map.of("drugA", "甲", "drugB", "乙", "severity", "MAYBE", "message", "非法级别"));
        assertEquals(4650, code(r), "既有码 4650（级别只能为 FORBID/CAUTION）不许改动：" + trunc(r.toString()));
    }

    /** 本版新码必须全部落在分配的 5600–5699 段内（新旧混用会撞码，本仓已因此返工）。 */
    @Test
    void newErrorCodesStayInsideTheAssignedSegment() {
        Pattern p = Pattern.compile("\\b(5[0-9]{3})\\b");
        List<String> outside = new ArrayList<>();
        for (SrcFile f : newMigrations()) {
            for (String s : codeLines(f.text())) {
                Matcher m = p.matcher(s);
                while (m.find()) {
                    int c = Integer.parseInt(m.group(1));
                    // 只抓**越过上界**的。5400–5599 是 v50 药房已分配段，新迁移的注释里
                    // 引用它（「同 5460 拆零那一条」）属正常交叉说明，抓了全是噪音。
                    if (c > CODE_HI) outside.add(f.rel() + ": " + trunc(s));
                }
            }
        }
        assertTrue(outside.isEmpty(), "v51 新迁移引用了 " + CODE_LO + "–" + CODE_HI + " 段之外的 5xxx 码：\n"
                + String.join("\n", outside));
    }

    // ==================================================================================
    // §8 夹具：结构化过敏原的最小可用数据
    //
    // 全部走 JdbcTemplate 直插，**刻意不走实现车道的 Service**：本类要能在实现车道
    // 一行 Java 都还没写的时候就跑起来并指出缺什么。
    // ==================================================================================

    private Long newAllergen(String name, String drugLevel) {
        return jdbc.queryForObject("""
                insert into cdss_allergen(code, name, allergen_type, drug_level, enabled, created_by)
                values (?, ?, 'DRUG', ?, true, ?) returning id
                """, Long.class, uniq("V51AG"), name + uniq("-"), drugLevel, anyUserId());
    }

    private void mapAllergenToDrug(Long allergenId, Long drugId, String level) {
        jdbc.update("""
                insert into cdss_allergen_drug(allergen_id, drug_id, mapped_level, created_by)
                values (?, ?, ?, ?)
                """, allergenId, drugId, level, anyUserId());
    }

    private void recordPatientAllergy(Long patientId, Long allergenId, String severity, String source) {
        // 写入前截断到微秒：PG timestamptz 是微秒精度，纳秒会被舍入，
        // 事后用等值比较取这条记录就取不到（本仓已因此炸过）。
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                insert into cdss_patient_allergy
                    (patient_id, allergen_id, severity, source, confirmed_by, confirmed_at, status)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, patientId, allergenId, severity, source, anyUserId(), java.sql.Timestamp.from(at));
    }

    private Long newAllergenGroup(String name) {
        return jdbc.queryForObject("""
                insert into cdss_allergen_group(code, name, enabled, created_by)
                values (?, ?, true, ?) returning id
                """, Long.class, uniq("V51GP"), name + uniq("-"), anyUserId());
    }

    private void addGroupMember(Long groupId, Long allergenId) {
        jdbc.update("insert into cdss_allergen_group_member(group_id, allergen_id, created_by) values (?,?,?)",
                groupId, allergenId, anyUserId());
    }

    /** 无序对只存一行（lo &lt; hi），与 cdss_allergen_cross 的 chk_cdss_cross_order 同口径。 */
    private void linkCross(Long g1, Long g2, String risk) {
        long lo = Math.min(g1, g2);
        long hi = Math.max(g1, g2);
        jdbc.update("""
                insert into cdss_allergen_cross(group_id_lo, group_id_hi, risk_level, created_by)
                values (?, ?, ?, ?)
                """, lo, hi, risk, anyUserId());
    }

    private int aggregateStock(Long drugId) {
        flushIfInTransaction();
        Integer n = jdbc.queryForObject("select stock from md_drug where id = ?", Integer.class, drugId);
        return n == null ? 0 : n;
    }

    private int batchStock(Long drugId) {
        flushIfInTransaction();
        Long n = jdbc.queryForObject("""
                select coalesce(sum(s.qty), 0) from pharm_stock s
                  join pharm_batch b on b.id = s.batch_id
                 where b.drug_id = ?
                """, Long.class, drugId);
        return n == null ? 0 : n.intValue();
    }

    /**
     * 返回体里有没有<b>真的给到医生的提示</b>。
     *
     * <p>判据放宽到「出现 warn/warning/提示/警告 字样」而不是钉死某个字段名：
     * 本类不规定实现车道把 warnings 挂在哪一层（顶层数组、订单元素上的瞬态字段，
     * 都是本仓已有的做法——{@code OutpOrder.stockWarnAvailable} 就是后者）。
     * 钉死字段名会把「怎么实现」也一起规定了，那是越界。
     */
    private static boolean mentionsWarning(JsonNode body) {
        String s = body.toString();
        return s.contains("warning") || s.contains("Warning") || s.contains("warn")
                || s.contains("警告") || s.contains("提示");
    }

    /**
     * 规则引擎<b>自身</b>对这组药的判定（走审查端点，若其存在）。
     *
     * <p>用途是把「引擎不会判」和「引擎会判但没接进开单」<b>分开</b>——两者的修法完全不同，
     * 而失败信息里不说清楚，接手的人要重查一遍才知道该动哪儿。
     * 端点不存在时返回一句说明，不让本类因为探测失败而改变结论。
     */
    private String engineVerdict(Long patientId, Long registrationId, Long drugId) {
        if (!mappedEndpoints().contains("POST /api/cdss/allergy/check")) {
            return "（无 POST /api/cdss/allergy/check 端点，无法区分「引擎不会判」与「引擎会判但没接进开单」）";
        }
        try {
            JsonNode r = api("POST", "/api/cdss/allergy/check", Map.of(
                    "patientId", patientId, "registrationId", registrationId,
                    "drugIds", List.of(drugId)));
            boolean engineHits = code(r) != 0 || mentionsWarning(r);
            return engineHits
                    ? "（**引擎自身判得出来**：直接调 POST /api/cdss/allergy/check 有结论 → "
                      + "缺的只是「接进开单链路」这一步，不是规则逻辑）"
                    : "（引擎自身也没判出来：POST /api/cdss/allergy/check 无结论 → 先查规则逻辑，"
                      + "再谈接线。响应：" + trunc(r.toString()) + "）";
        } catch (RuntimeException e) {
            return "（探测审查端点失败：" + e.getClass().getSimpleName() + "）";
        }
    }

    /** 盘点相关返回体里有没有把「还有一本批次账没对」这件事说出来。 */
    private static boolean mentionsBatchDivergence(String s) {
        if (s == null) return false;
        return s.contains("batch") || s.contains("Batch") || s.contains("批次")
                || s.contains("drift") || s.contains("漂移");
    }

    private static String trunc(String s) {
        if (s == null) return "null";
        return s.length() <= 900 ? s : s.substring(0, 900) + "…（截断，共 " + s.length() + " 字符）";
    }

    /**
     * 夹具前置检查：{@code cdss_patient_allergy.confirmed_by} 是 <b>not null</b>
     *（「这条记录会拦处方，必须有人签字」），夹具靠 {@link #anyUserId()} 供签字人。
     * 库里没有任何 {@code sys_user} 时，§1 的全部用例会以外键异常失败——那个报错完全不指向根因。
     * 本条先红，把失败点提前到这里。
     */
    @Test
    void fixtureSanity() {
        assertNotNull(anyUserId(), "测试库里没有任何 sys_user——过敏记录的 confirmed_by 是 not null，夹具无法成立");
    }
}
