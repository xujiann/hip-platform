package cn.hip.server;

import cn.hip.inpatient.service.CountersignService;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpEmrController;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.DoctorStationController;
import cn.hip.outpatient.web.DoctorStationController.SaveEmrRequest;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * v53 车道 V4：<b>病历修订留痕的对账保障与跨车道接缝验收</b>。
 *
 * <h3>本类的判据是「能不能证明」，不是「功能好不好用」</h3>
 * 《电子病历应用管理规范》第二十四条要求病历修改留痕、可追溯。医疗纠纷举证时，
 * 「病历被改过而系统证明不了改了什么」等同于举证不能。所以本类断言的不是「版本列表长得好看」，
 * 而是<b>一份留痕材料拿到法庭上还站不站得住</b>：
 * <ol>
 *   <li><b>完整</b>：走<b>真实写路径</b>保存 N 次就有 N 版，序号连续无洞，每一版与当时保存的
 *       逐字相同（§1）；</li>
 *   <li><b>不可篡改</b>：<b>全仓</b>不存在任何 update/delete {@code emr_version} 的代码路径，
 *       端点里也没有（§2）。可被篡改的留痕在法庭上没有价值——对方律师只需问一句
 *       「这条记录能改吗」，能改，整份材料就废了；</li>
 *   <li><b>不含伪证</b>：三条迁移零 update、零回填，历史病历<b>没有</b>凭空多出一条「初版」（§3）；</li>
 *   <li><b>责任链清楚</b>：只回看不回滚，全仓没有任何「恢复到某一版」的端点或方法（§4）；</li>
 *   <li><b>审签说得清签的是哪一版</b>，签完再改能被发现（§5）；审签人 ≠ 书写人连绕过服务层
 *       直连改库都拒（§6）；</li>
 *   <li>既有链路零改动（§7），时限锚点口径必须自陈（§8）。</li>
 * </ol>
 *
 * <h3>与 {@code V53EmrVersionTest}（车道 V1 自测）的分工——两者不可互相替代</h3>
 * V1 的那一套测的是 {@code EmrVersionService} <b>自己</b>：用合成 emr_id 直调 {@code recordOutp}，
 * 覆盖 gate 三档、去重、并发、超长、差异算法。<b>它刻意不走真实写路径</b>（夹具连 outp_emr 行都不建）。
 * 本类补的正是它按分工测不到的那一半：
 * <ul>
 *   <li>§1 走 {@code DoctorStationController.saveEmr} 的<b>真实写路径</b>——
 *       这是「留痕接缝到底接上没有」的唯一证据。V1 全绿而本类 §1 红，说明服务写得对、<b>接缝没接</b>，
 *       而那种状态下生产环境一版都不会落；</li>
 *   <li>§2/§4 扫的是<b>全仓</b>与<b>全部 HTTP 映射</b>，不是 V1 一个类的反射自查——
 *       篡改路径最可能出现在别的车道后来加的代码里；</li>
 *   <li>§5 是 V1 ↔ V2 的<b>跨车道接缝</b>，两条车道各自自测都绿也照样能对不上（实测确实对不上）。</li>
 * </ul>
 *
 * <h3>为什么本类不 import 车道 V1 / V3 的任何类</h3>
 * 三条车道并行。若本类按类型 {@code @Autowired EmrVersionService}，某条车道未交付时整个
 * <b>测试模块编译失败</b>——那不是「红」，那是把别人的活儿一起阻断。故本类一律走表结构、SQL、
 * 端点枚举与源码扫描：这些东西在各车道落地前后都存在，断言不必改一个字。
 * 本类落在独立文件里也是同一个道理：车道 V1 已把它自己的用例写进了
 * {@code V53EmrVersionTest.java}，两套并存、互不覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class V53EmrVersionReconTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired ObjectMapper objectMapper;
    @Autowired MockMvc mvc;
    @Autowired ConfigReader configReader;

    @Autowired DoctorStationController doctorStationController;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired InpEmrController inpEmrController;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired CountersignService countersignService;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /** ConfigReader 是 30 秒进程内缓存、不随事务回滚——读过配置的用例后必须显式失效，否则串味。 */
    @AfterEach
    void evictConfig() {
        configReader.evictAll();
    }

    // ==================================================================================
    // §0 工具
    // ==================================================================================

    private static int seq = 0;

    private static String uniq(String prefix) {
        return prefix + (System.nanoTime() % 100000000L) + (seq++);
    }

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
        p.setName("v53留痕" + System.nanoTime());
        p.setSex("U");
        return patientService.register(p).getId();
    }

    /** 一次门诊就诊，返回 registrationId */
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

    /** 一次住院，返回 admissionId */
    private Long admit() {
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(newPatient(), 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
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

    private Long outpEmrId(Long registrationId) {
        flushClear();
        return jdbc.queryForObject("select id from outp_emr where registration_id = ?", Long.class, registrationId);
    }

    private List<Map<String, Object>> versionsOf(String emrType, Long emrId) {
        flushClear();
        return jdbc.queryForList("""
                select version_no, source, content, content_len, content_hash, saved_by, saved_at, saved_on
                from emr_version where emr_type = ? and emr_id = ? order by version_no
                """, emrType, emrId);
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = current_schema() and table_name = ?
                """, Integer.class, table);
        return n != null && n > 0;
    }

    private Set<String> columnsOf(String table) {
        return new TreeSet<>(jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = current_schema() and table_name = ?
                """, String.class, table));
    }

    // ---------------- 源码扫描（不需要后端状态） ----------------

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
     * 遍历真代码。跳过 {@code target/ node_modules/ dist/ .claude/ worktrees/}——构建产物与他人工作区
     * 不是本仓代码，扫进来会得出假结论。<b>也跳过 src/test</b>：本文件自己就写着
     * 「update emr_version」这个待查模式，扫进来会自摆乌龙。
     * <p><b>按仓库相对路径判跳过，不看绝对路径</b>（v58 车道 D）：仓库本身可能被 checkout 在
     * {@code …/.claude/worktrees/<x>/} 下（并行车道就是这么跑的），按绝对路径判会让每个源文件都命中
     * {@code /.claude/}，扫描恒空——依赖它的断言在 worktree 里恒绿。
     */
    private static List<SrcFile> sources(String suffix, String... relDirs) {
        Path root = repoRoot();
        List<SrcFile> out = new ArrayList<>();
        for (String rel : relDirs) {
            Path base = root.resolve(rel);
            if (!Files.isDirectory(base)) continue;
            try (var s = Files.walk(base)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    String path = root.relativize(f).toString().replace('\\', '/');
                    if (excluded(path)) continue;
                    if (!path.endsWith(suffix)) continue;
                    out.add(new SrcFile(path, Files.readString(f, StandardCharsets.UTF_8)));
                }
            } catch (Exception e) {
                throw new IllegalStateException("扫描 " + base + " 失败", e);
            }
        }
        return out;
    }

    /**
     * {@code rel} 为相对仓库根、'/' 分隔的路径。只看相对路径——仓库自身所在的目录名不参与判断。
     * 本类额外排除 {@code src/test/}（理由见 {@link #sources}）。
     */
    static boolean excluded(String rel) {
        return rel.startsWith(".claude/")
                || rel.startsWith("worktrees/") || rel.contains("/worktrees/")
                || rel.contains("/target/") || rel.contains("/node_modules/") || rel.contains("/dist/")
                || rel.contains("/src/test/");
    }

    /**
     * 自证（v58 车道 D）：排除逻辑只认仓库相对路径。修复前在 {@code …/.claude/worktrees/<x>/} 里
     * 同形态的 sources() 实测返回 0，本类的源码扫描断言随之恒绿。活的对照组用 {@code >} 而不是等于。
     */
    @Test
    void sourceScanExclusionDetectorActuallyBites() {
        assertTrue(excluded(".claude/worktrees/x/frontend/shell/src/App.vue"), "仓库内 .claude/ 下的文件必须排除");
        assertFalse(excluded("frontend/shell/src/App.vue"),
                "真源码的相对路径不得被排除——按绝对路径判时它曾因仓库目录含 /.claude/ 被整仓排掉");
        assertTrue(excluded("frontend/shell/node_modules/x.js"), "node_modules/ 必须排除");
        assertTrue(excluded("server/target/classes/x"), "target/ 必须排除");
        assertTrue(excluded("server/src/test/java/cn/hip/server/X.java"), "本类约定：src/test/ 也排除（自摆乌龙防护）");
        assertFalse(excluded("server/src/main/java/cn/hip/server/X.java"), "src/main 真代码不得被 src/test 规则误伤");
        int vue = sources(".vue", "frontend/shell/src").size();
        assertTrue(vue > 100, "活的对照组：frontend/shell/src 下应扫到 >100 个 .vue，实得 " + vue
                + "——为 0 即排除逻辑又把整仓扫空了");
    }

    private static final String[] JAVA_DIRS = {
            "modules", "platform", "server/src/main/java", "datacenter", "bureau", "ai-service", "impl"};

    private static final String MIGRATION_DIR = "server/src/main/resources/db/migration";

    /**
     * 只认<b>真代码行</b>：注释里写「本表不提供恢复到某一版」是<b>声明边界</b>，恰恰要鼓励；
     * 把它算成突破会逼后人删掉说明，反而让边界从可见变成不可见（v48 立、v50/v51 沿用的口径）。
     */
    private static List<String> codeLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String s = line.strip();
            if (s.startsWith("*") || s.startsWith("//") || s.startsWith("/*") || s.startsWith("--")) continue;
            // 行尾注释也剥掉：`emr_type varchar(8), -- 不做 update emr_version` 不该算命中
            int c = s.indexOf("--");
            if (c >= 0 && !s.substring(0, c).contains("'")) s = s.substring(0, c);
            int j = s.indexOf("//");
            if (j >= 0 && !s.substring(0, j).contains("\"")) s = s.substring(0, j);
            if (!s.isBlank()) out.add(s.strip());
        }
        return out;
    }

    /** 把一个文件压成一行小写文本，便于跨行匹配被文本块折行的 SQL。 */
    private static String flatCode(String text) {
        return String.join(" ", codeLines(text)).toLowerCase(Locale.ROOT);
    }

    /**
     * SQL 文件专用：把<b>字符串字面量</b>整段挖掉再扫。
     *
     * <p>不这么做会得出假结论——V159 的 {@code unavailable_reason} 里<b>逐字引用</b>了
     * {@code MedTechController:355} 的那条 `update inp_surgery set …`（那是在说明「为什么手术记录
     * 时限算不出来」的证据）。它是一段<b>被引用的文字</b>，不是一条被执行的语句；
     * 把它算成「迁移里有 update」，等于逼后人删掉证据说明，正好把可核实的东西变成不可核实的。
     * Java 文件不做这个处理：那里的 SQL 恰恰全在字符串字面量里。
     */
    private static String flatSqlStatements(String text) {
        String flat = flatCode(text);
        // '…' 内部的 '' 是转义单引号，一并吃掉
        return flat.replaceAll("'(?:[^']|'')*'", "''");
    }

    /** 本版新增的三条迁移（V157/V158/V159）。零回填纪律只约束新增段，不追溯既有迁移。 */
    private static List<SrcFile> v53Migrations() {
        List<SrcFile> out = new ArrayList<>();
        for (SrcFile f : sources(".sql", MIGRATION_DIR)) {
            Matcher m = Pattern.compile("V(\\d+)__").matcher(f.rel().substring(f.rel().lastIndexOf('/') + 1));
            // **上界 160**：v53 自己的迁移是 V157–V160（V160 是合版加的审签书写人触发器）。
            // 原来写的是 `>= 157 && < 10000`，实际是无上界——那会让 v53 的纪律检查
            // 去审 v54 及之后每一版的迁移，重演 v51 打红 v53 的那一幕
            // （「一个版本的测试去审后续版本的产物，红的不是被审者而是审者」）。
            int no = m.find() ? Integer.parseInt(m.group(1)) : -1;
            if (no >= 157 && no <= 160) {
                out.add(f);
            }
        }
        return out;
    }

    private static String trunc(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + " …";
    }

    // ---------------- 端点枚举 ----------------

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

    // ==================================================================================
    // §1 走真实写路径：保存 N 次就有 N 版，完整、连续、逐字
    // ==================================================================================

    /**
     * <b>本类第一号断言，也是整条车道唯一能证明「接缝真的接上了」的用例。</b>
     * 门诊病历经 {@code DoctorStationController.saveEmr} 连存三次，
     * 版本表里必须有三条，序号 1/2/3 连续无洞，且每一条与当时保存的正文<b>逐字相同</b>。
     *
     * <p>为什么必须走真实端点而不是直调 {@code recordOutp}：车道 V1 的自测已经证明
     * 「调了就会落一版」，那是服务本身对不对；本条问的是<b>「医生按保存键的时候，那个调用发生了没有」</b>。
     * 这两件事可以一个绿一个红——而生产上只有后者算数。
     * {@code DoctorStationService:137} 的 {@code emrRepository.save(emr)} 是整行覆盖，
     * 接缝没接上时，医生保存第二次，第一次写的内容就永久消失了，库里查不到、日志里也没有。
     *
     * <p>顺带钉死四件本仓被炸过的事：{@code content_len} 与快照长度自洽（统计不必碰 TOAST 大字段）、
     * {@code content_hash} 是 64 位小写 hex、{@code saved_by} 如实落书写人（不猜、不回填成当班医生）、
     * {@code saved_on} 走 {@code BusinessDates.today()} 而非 {@code date(saved_at)}
     * （会话时区与业务时区是两套口径，夜班跨 00:00–08:00 会差一天，1.1.9 付过学费）。
     */
    @Test
    void realSavePathProducesOneVersionPerSaveVerbatim() {
        assertTrue(tableExists("emr_version"), "V157 未落库：emr_version 表不存在");
        Authentication doc = auth(uniq("v53docA"));
        Long docId = userId(doc.getName());
        Long rid = visit();

        String v1 = "受凉后起病2天，咳嗽咳痰，无发热。";
        String v2 = "受凉后起病2天，咳嗽咳痰，无发热。今日晨起体温37.8度。";
        String v3 = "受凉后起病2天，咳嗽咳痰。体温37.8度，查体双肺呼吸音粗，未闻及干湿罗音。";
        for (String s : List.of(v1, v2, v3)) {
            var r = doctorStationController.saveEmr(rid, new SaveEmrRequest(emrOf(s), List.of()), doc);
            assertEquals(0, r.getCode(), r.getMessage());
        }

        Long emrId = outpEmrId(rid);
        var rows = versionsOf("OUTP", emrId);
        assertEquals(3, rows.size(), """
                门诊病历经**真实写路径**连存 3 次，emr_version 里只有 %d 条（emr_type='OUTP', emr_id=%d）。

                这就是本版立项的那个缺口本身：DoctorStationService:137 的 emrRepository.save(emr)
                是**整行覆盖**，前两次写的正文已经永久消失，库里查不到、日志里也没有。
                《电子病历应用管理规范》第二十四条要求病历修改留痕、可追溯——本条红即合规声明不成立。

                注意：车道 V1 的 V53EmrVersionTest 全绿**不代表本条会绿**。那一套直调
                EmrVersionService.recordOutp，证明的是「调了就会落一版」；本条问的是
                **「医生按保存键的时候，那个调用发生了没有」**。此刻的答案是「没有」——
                留痕接缝还没接进 DoctorStationService。

                按铁律 3，该接缝由**主控**统一加（车道 V1/V4 都不许改 DoctorStationService）。
                具体加在哪一行、参数从哪儿取，见本车道 cross_lane。""".formatted(rows.size(), emrId));

        // ① 序号 1..N 连续无洞。有洞意味着某一次保存的留痕丢了却无人知晓——
        //    举证时「第 2 版呢」答不上来，比没有留痕更难解释。
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, ((Number) rows.get(i).get("version_no")).intValue(),
                    "版本号必须从 1 起连续递增、无洞，实际第 " + (i + 1) + " 行是 "
                            + rows.get(i).get("version_no"));
        }

        // ② 每一版与当时保存的正文**逐字相同**。只存 diff 或做归一化改写都会让这条红——
        //    律师问「第 2 版当时写的是什么」，拿一串 @@ -3,7 +3,9 @@ 出来是答不上来的。
        List<String> expected = List.of(v1, v2, v3);
        for (int i = 0; i < rows.size(); i++) {
            String content = (String) rows.get(i).get("content");
            assertNotNull(content, "第 " + (i + 1) + " 版快照为 null——空快照等于没有留痕");
            assertTrue(contentCarries(content, expected.get(i)),
                    ("第 %d 版快照里找不到当时保存的原文。\n期望逐字包含：%s\n实际快照：%s\n"
                     + "快照必须是**完整全文**、自解释、单独拎一行出来就能读（V157:84-87 的决定）。")
                            .formatted(i + 1, expected.get(i), trunc(content)));
            assertEquals(content.length(), ((Number) rows.get(i).get("content_len")).intValue(),
                    "content_len 必须与快照字符数自洽——它存在的意义是让容量统计不必碰 TOAST 出来的大字段");
            Object hash = rows.get(i).get("content_hash");
            if (hash != null) {
                assertTrue(((String) hash).matches("^[0-9a-f]{64}$"),
                        "content_hash 必须是小写 hex 的 SHA-256，实际：" + hash);
            }
            assertNotNull(rows.get(i).get("saved_by"),
                    "本用例是带登录上下文的真实写路径，saved_by 不该为 null——"
                            + "为 null 说明接缝没把 doctorId 传进去，举证时「这一版是谁写的」就答不上来");
            assertEquals(docId.longValue(), ((Number) rows.get(i).get("saved_by")).longValue(),
                    "saved_by 必须如实落书写人。取不到就落 null，**绝不回填成当班医生**——"
                            + "猜错了就是把责任安在别人头上");
            assertEquals(BusinessDates.today(), ((java.sql.Date) rows.get(i).get("saved_on")).toLocalDate(),
                    "saved_on 必须走 BusinessDates.today()（Asia/Shanghai），不是 date(saved_at)——"
                            + "会话时区与业务时区是两套口径，夜班跨 00:00–08:00 会差一天");
        }

        // ③ 三版内容各不相同（去重开着也不该把真实修改吃掉）
        assertEquals(3, rows.stream().map(r -> (String) r.get("content")).distinct().count(),
                "三次保存内容各不相同，去重（emr.version.dedup）不得把真实修改吃掉");
    }

    /**
     * 内容<b>不变</b>时不该刷版本（{@code emr.version.dedup=on} 默认开）：
     * 自动保存每 30 秒一次，医生泡杯茶回来能刷出 20 条一模一样的版本，那不是留痕，那是噪音。
     *
     * <p>这条与上一条是一对：上一条防「该留的没留」，本条防「留成噪音把真修改淹掉」。
     * 同样走真实写路径——去重是在接缝之后生效的，直调服务测不出接缝把 source 传错的情形。
     */
    @Test
    void identicalResaveThroughRealPathDoesNotCreateNoiseVersions() {
        assertTrue(tableExists("emr_version"), "V157 未落库：emr_version 表不存在");
        assertEquals("on", configReader.get("emr.version.dedup", "on"),
                "emr.version.dedup 的出厂默认必须是 on（V157:169）");

        Authentication doc = auth(uniq("v53docB"));
        Long rid = visit();
        String same = "患者一般情况可，无发热，继续观察。";
        for (int i = 0; i < 3; i++) {
            var r = doctorStationController.saveEmr(rid, new SaveEmrRequest(emrOf(same), List.of()), doc);
            assertEquals(0, r.getCode(), r.getMessage());
        }
        var rows = versionsOf("OUTP", outpEmrId(rid));
        assertFalse(rows.isEmpty(), """
                三次保存一版都没落——这是「留痕接缝未接入」，不是「去重太狠」。
                先修 realSavePathProducesOneVersionPerSaveVerbatim，本条才谈得上有意义：
                版本表整个是空的时候，「没有噪音版本」是假绿。""");
        assertEquals(1, rows.size(), """
                三次内容完全相同的保存产生了 %d 条版本，去重没生效（emr.version.dedup=on）。
                前端自动保存每 30 秒一次，无差异版本会把真正的修改整片淹掉——
                版本列表一旦变成噪音，举证时「哪一版是真改动」就得靠人肉翻，那等于没有留痕。"""
                .formatted(rows.size()));
    }

    /** 快照里能不能逐字读出原文：JSON 对象形态就逐值比，其余形态退化为整串包含。 */
    private boolean contentCarries(String snapshot, String expected) {
        try {
            Map<String, Object> m = objectMapper.readValue(snapshot, new TypeReference<>() {});
            for (Object v : m.values()) {
                if (expected.equals(v)) return true;
            }
        } catch (Exception ignored) {
            // 非 JSON 形态：退化为整串包含（本断言只关心「原文能不能逐字读出来」）
        }
        return snapshot.contains(expected);
    }

    // ==================================================================================
    // §2 版本内容不可篡改：静态扫 + 端点枚举 + JPA 仓库，三管齐下
    // ==================================================================================

    /**
     * <b>全仓不得存在任何 update / delete / truncate {@code emr_version} 的 SQL。</b>
     *
     * <p><b>可被篡改的留痕在法庭上没有价值。</b>对方律师只需问一句「这条记录后来能不能改」，
     * 答「能」，整份留痕材料的证明力就归零——它退化成「一份可以事后编造的表格」。
     * 所以这条不是代码洁癖，它是本版全部价值的前提。
     *
     * <p>扫的是<b>全仓</b>而不是某一个类：篡改路径最可能出现在<b>别的车道后来加的代码</b>里，
     * 一个类的自查（哪怕用反射）只能证明那个类干净。
     */
    @Test
    void noCodePathAnywhereUpdatesOrDeletesEmrVersion() {
        List<Pattern> tamper = List.of(
                Pattern.compile("\\bupdate\\s+emr_version\\b"),
                Pattern.compile("\\bdelete\\s+from\\s+emr_version\\b"),
                Pattern.compile("\\btruncate\\s+(table\\s+)?emr_version\\b"),
                Pattern.compile("\\bdrop\\s+table\\s+(if\\s+exists\\s+)?emr_version\\b"));

        List<SrcFile> all = new ArrayList<>(sources(".java", JAVA_DIRS));
        all.addAll(sources(".sql", MIGRATION_DIR));
        all.addAll(sources(".xml", "server/src/main/resources", "modules", "platform"));

        List<String> hits = new ArrayList<>();
        for (SrcFile f : all) {
            String flat = f.rel().endsWith(".sql") ? flatSqlStatements(f.text()) : flatCode(f.text());
            for (Pattern p : tamper) {
                Matcher m = p.matcher(flat);
                if (m.find()) {
                    int from = Math.max(0, m.start() - 60);
                    hits.add(f.rel() + ": …" + trunc(flat.substring(from, Math.min(flat.length(), m.end() + 60))));
                }
            }
        }
        assertTrue(hits.isEmpty(), """
                出现了能改写病历版本留痕的 SQL：
                %s

                **可被篡改的留痕在法庭上没有价值。**举证材料里的每一版，其证明力全部来自
                「它写进去之后再也没被动过」。一旦仓里存在一条 update/delete 路径，
                对方律师只要指出这条路径存在，整份材料就退化成「一份可以事后编造的表格」——
                此时有没有留痕在证据法上是一样的。
                版本表只增不改不删；写错了就再存一版（那是 version_no+1，责任链完整），不许回头改。"""
                .formatted(String.join("\n", hits)));
    }

    /**
     * {@code emr_version} 不得挂在 Spring Data 仓库上。
     *
     * <p>{@code JpaRepository} / {@code CrudRepository} 天生带 {@code save(entity)} 与
     * {@code delete(entity)}：只要有一个仓库接口指向映射到 {@code emr_version} 的实体，
     * 上一条 SQL 扫描就会被绕过——篡改路径变成一次 {@code repo.save(existing)}，
     * <b>一行 SQL 都不出现</b>。写侧应当只用 {@code JdbcTemplate} 的 insert。
     */
    @Test
    void emrVersionIsNotExposedThroughAMutableJpaRepository() {
        Pattern table = Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*\"emr_version\"");
        Set<String> entities = new TreeSet<>();
        for (SrcFile f : sources(".java", JAVA_DIRS)) {
            if (!table.matcher(f.text()).find()) continue;
            Matcher c = Pattern.compile("(?:class|record)\\s+(\\w+)").matcher(f.text());
            if (c.find()) entities.add(c.group(1));
        }
        if (entities.isEmpty()) return;   // 没有实体映射即无此风险，直接通过

        List<String> hits = new ArrayList<>();
        for (SrcFile f : sources(".java", JAVA_DIRS)) {
            for (String e : entities) {
                Pattern repo = Pattern.compile(
                        "interface\\s+\\w+\\s+extends\\s+[\\w.]*(Jpa|Crud|PagingAndSorting)Repository\\s*<\\s*"
                                + e + "\\b");
                if (repo.matcher(f.text()).find()) hits.add(f.rel() + " → " + e);
            }
        }
        assertTrue(hits.isEmpty(), """
                病历版本实体被挂到了可变的 Spring Data 仓库上：
                %s

                JpaRepository/CrudRepository 自带 save(entity) 与 delete(entity)：
                只要这个仓库存在，篡改一条历史版本就只需要 `repo.save(existingVersion)` 一行，
                **一句 SQL 都不出现**，noCodePathAnywhereUpdatesOrDeletesEmrVersion 那张网整个被绕过。
                版本写侧只用 JdbcTemplate 的 insert；读侧用 queryForList。"""
                .formatted(String.join("\n", hits)));
    }

    /**
     * 端点层同款：<b>全部 HTTP 映射</b>里不得存在任何能改写版本的入口（PUT / PATCH / DELETE）。
     *
     * <p>静态 SQL 扫描防的是「代码里能改」，本条防的是「网上能改」——两张网必须都在：
     * 一个 {@code @PutMapping} 打到 JPA 实体上时，前一条扫不出任何 SQL。
     * 枚举的是 {@code RequestMappingHandlerMapping} 的全量映射，不是某一个控制器的反射自查：
     * 版本相关的写端点完全可能被<b>别的控制器</b>加出来。
     */
    @Test
    void noHttpEndpointAnywhereCanMutateAVersion() {
        Set<String> suspicious = new TreeSet<>();
        for (String e : mappedEndpoints()) {
            String low = e.toLowerCase(Locale.ROOT);
            boolean mutating = low.startsWith("put ") || low.startsWith("patch ") || low.startsWith("delete ");
            boolean aboutVersions = low.contains("version") && (low.contains("emr") || low.contains("record"));
            if (mutating && aboutVersions) suspicious.add(e);
        }
        assertTrue(suspicious.isEmpty(), """
                出现了能改写病历版本的 HTTP 端点：
                %s

                版本留痕只增不改不删——**没有任何合法业务需要修改一条已落库的版本**。
                写错了就再存一版（version_no+1，责任人是这一次的书写人，责任链完整）。
                需要给某一版加注释/标记时，加在**另一张表**上，不要动版本行本身。"""
                .formatted(String.join("\n", suspicious)));
    }

    // ==================================================================================
    // §3 不伪造历史：迁移零 update、历史病历不得凭空多出一条「初版」
    // ==================================================================================

    /**
     * V157/V158/V159 三条迁移必须<b>零条 update、零条派生 insert</b>。
     *
     * <p>最诱人的一步是在 V157 末尾写一句
     * {@code insert into emr_version(...) select id, 1, chief_complaint||... from outp_emr}。
     * 一条 SQL 就能让全院存量病历「都有版本了」，完成度看起来极高——
     * <b>而那不是补数据，那是造证</b>：举证材料里会出现一条 version_no=1、内容等于今天的当前值、
     * saved_at 等于今天时间戳的版本，它从未真实存在过。
     * 历史病历没有版本记录，那是事实：当时系统就没采集。<b>宁可空着，不可假算。</b>
     */
    @Test
    void v53MigrationsBackfillNothing() {
        Map<String, Set<String>> allowedInsertTargets = Map.of(
                "V157__emr_version.sql", Set.of("sys_config"),
                "V158__emr_countersign.sql", Set.of("sys_config"),
                "V159__emr_timeliness.sql", Set.of("sys_config", "emr_timeliness_rule"));

        var files = v53Migrations();
        assertFalse(files.isEmpty(), "扫不到 V157+ 迁移——地基没落盘，本版无从谈起");

        List<String> hits = new ArrayList<>();
        for (SrcFile f : files) {
            String name = f.rel().substring(f.rel().lastIndexOf('/') + 1);
            String flat = flatSqlStatements(f.text());

            // ① 零条 update（"updated_at" 不匹配 \bupdate\b，列名不会误伤）
            // **只认真正的回填语句 `update <表> set`**，不认光秃秃的 update 关键字：
            // v53 合版加的 V160 触发器声明里有 `before insert or update on emr_countersign`，
            // 那是**触发器时机**不是数据写入，裸 update 关键字会把它误判成回填。
            // 误报比漏报更伤：一份天天误报的纪律检查，很快就没人看了。
            Matcher u = Pattern.compile("\\bupdate\\s+[a-z_][a-z0-9_]*\\s+set\\b").matcher(flat);
            while (u.find()) {
                hits.add(name + " 出现 update：…" + trunc(flat.substring(
                        Math.max(0, u.start() - 60), Math.min(flat.length(), u.end() + 80))));
            }

            // ② insert 只准打进本文件自己的新表 / sys_config
            Set<String> allowed = allowedInsertTargets.getOrDefault(name, Set.of("sys_config"));
            Matcher in = Pattern.compile("\\binsert\\s+into\\s+([a-z_][a-z0-9_]*)").matcher(flat);
            while (in.find()) {
                if (!allowed.contains(in.group(1))) {
                    hits.add(name + " 往 " + in.group(1) + " 里插数据——本版对既有表零写入");
                }
            }

            // ③ 零条派生 insert（insert … select 就是回填的形状）
            Matcher der = Pattern.compile("\\binsert\\s+into\\s+[a-z_][a-z0-9_]*[^;]{0,4000}?\\bselect\\b")
                    .matcher(flat);
            if (der.find()) {
                hits.add(name + " 出现 `insert … select` 派生写入：…" + trunc(der.group()));
            }
        }
        assertTrue(hits.isEmpty(), """
                v53 迁移破了「零 update、零回填」纪律：
                %s

                尤其是**不拿病历的当前内容伪造一条「初版」**：那会让举证材料里出现一条
                从未真实存在过的版本（version_no=1、内容等于今天的值、saved_at 等于今天），
                **比没有留痕更糟**——没有留痕只是证明不了，伪造的留痕是伪证。
                历史病历没有版本记录是事实，如实返回空数组，前端显示「本份病历在版本留痕上线前书写，
                无历史版本」。这句话是真的，可以拿去举证。"""
                .formatted(String.join("\n", hits)));
    }

    /**
     * 运行期同款：一份<b>绕过写路径直接落库</b>的「历史病历」，版本列表必须是空的，
     * 不得被任何补偿逻辑凭空补出一条「初版」。
     *
     * <p>这条与上一条互补：上一条防迁移里的批量造证，本条防服务层的「读时补一条」——
     * 那种写法更隐蔽（迁移干干净净，版本却凭空出现），也更危险。
     */
    @Test
    void preV53RecordHasNoFabricatedInitialVersion() {
        assertTrue(tableExists("emr_version"), "V157 未落库：emr_version 表不存在");
        Long rid = visit();
        // 绕过 DoctorStationService，直连落一条「版本留痕上线之前就存在」的门诊病历
        jdbc.update("""
                insert into outp_emr(registration_id, chief_complaint, present_illness, advice, updated_at)
                values (?, '历史主诉', '这份病历在版本留痕上线之前书写', '历史处置', now())
                """, rid);
        Long emrId = outpEmrId(rid);

        Integer n = jdbc.queryForObject(
                "select count(*) from emr_version where emr_type = 'OUTP' and emr_id = ?", Integer.class, emrId);
        assertEquals(0, n == null ? -1 : n.intValue(), """
                一份从未经过 v53 写路径的历史病历，版本表里却有 %d 条记录。
                这就是「造证」：那条版本的内容等于今天的当前值、时间戳等于今天，它从未真实存在过。
                正确做法是如实返回空——「本份病历在版本留痕上线前书写，无历史版本」是真话，
                可以写进举证材料；一条编出来的初版不能。""".formatted(n));
    }

    // ==================================================================================
    // §4 只回看不回滚
    // ==================================================================================

    /**
     * <b>全仓</b>不得存在任何「恢复 / 还原到某一版」的端点、方法或伏笔列。
     *
     * <p>回滚会让「当前版本的责任人是谁」变得不清楚：A 写了 v3，B 一键回滚到 v1，
     * 当前正文是 A 一小时前的字、责任却记在 B 头上，还是记在 A 头上？<b>法定病历不做这个。</b>
     * 需要改回去，由医师本人重新书写并保存，落成 v4——责任链完整。
     */
    @Test
    void thereIsNoWayAnywhereToRestoreOrRollBackToAVersion() {
        Set<String> endpoints = new TreeSet<>();
        for (String e : mappedEndpoints()) {
            String low = e.toLowerCase(Locale.ROOT);
            if (!(low.contains("version") || low.contains("emr"))) continue;
            if (low.contains("restore") || low.contains("revert") || low.contains("rollback")
                    || low.contains("recover") || low.contains("undo")) {
                endpoints.add(e);
            }
        }
        assertTrue(endpoints.isEmpty(), """
                出现了「恢复/还原到某一版」的端点：
                %s
                回滚会让「当前版本的责任人是谁」说不清：A 写了 v3，B 一键回滚到 v1，
                当前正文是 A 一小时前的字，责任记谁头上？法定病历不做这个。
                需要改回去 → 由医师本人重新书写并保存，落成 v4，责任链完整。""".formatted(endpoints));

        // 方法名层面（端点没暴露但服务层留了口子，下一版就会有人接上去）
        Pattern m = Pattern.compile(
                "\\b(restore|revert|rollback|recover)(Version|Emr|Record|To)\\w*\\s*\\(");
        List<String> code = new ArrayList<>();
        for (SrcFile f : sources(".java", JAVA_DIRS)) {
            if (!f.text().contains("emr_version") && !f.text().contains("EmrVersion")) continue;
            for (String line : codeLines(f.text())) {
                if (m.matcher(line).find()) code.add(f.rel() + ": " + trunc(line));
            }
        }
        assertTrue(code.isEmpty(), """
                版本相关代码里出现了回滚方法：
                %s
                「服务层先备着、端点以后再暴露」是本条要挡的主要形态——
                口子一旦留下，下一版一定有人把它接到按钮上。""".formatted(String.join("\n", code)));

        // 表结构层面：V157 明说不留 restored_from 之类的列，防止后来人「顺手做一下」
        var cols = columnsOf("emr_version");
        assertEquals(Set.of("id", "emr_type", "emr_id", "version_no", "source", "content",
                        "content_len", "content_hash", "saved_by", "saved_at", "saved_on"),
                cols, """
                emr_version 的列集合被改动了（实际：%s）。
                本条把列集合钉死，是为了挡住 restored_from / reverted_by / is_current 这类
                「顺手做一下回滚」的伏笔列——表里一旦有这样一列，回滚功能就只差一个按钮。
                真要加列（例如将来做归档），请连同本断言一起改，并在 commit 里说明为什么。"""
                        .formatted(cols));
    }

    // ==================================================================================
    // §5 审签绑定版本：车道 V1 ↔ V2 的跨车道接缝
    // ==================================================================================

    /** 造一条住院病历，返回 recordId。作者为 {@code authorAuth} 所指用户。 */
    private Long inpRecord(Long admissionId, String type, String content, Authentication authorAuth) {
        var r = inpEmrController.addRecord(admissionId,
                new InpEmrController.SaveRecordRequest(type, "入院记录", content), authorAuth);
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData().getId();
    }

    /**
     * <b>审签必须说得清签的是哪一版，并且「签完再改」当场可发现。</b>
     *
     * <p>这是 V2 交付物的核心承诺：{@code content_sha256} 存的是审签当时那份正文的摘要，
     * 重算当前正文与之比对即为 {@code stale}。没有这一列，审签只是一个时间戳，证明不了签的是什么。
     *
     * <p>顺带钉死一件接口契约上的坑：审签返回体<b>外层驼峰、数组元素蛇形</b>
     * （{@code countersigns[].content_sha256} / {@code version_source} / {@code stale}），
     * 因为数组是 {@code jdbc.queryForList} 直出；而车道 V1 的版本列表
     * （{@code GET /api/emr/versions/{emrType}/{emrId}}）里 {@code items[]} 却是<b>驼峰</b>
     * （{@code versionNo}/{@code contentLen}/{@code savedBy}）。同一个版本的两条相邻接口，
     * 数组元素命名相反——前端按一套读另一套会全 undefined，故此处显式钉死，别靠记忆。
     */
    @Test
    void countersignBindsToDigestAndDetectsPostSignEdit() {
        Authentication author = auth(uniq("v53author"));
        Long authorId = userId(author.getName());
        Long supId = userId(uniq("v53superior"));

        Long admId = admit();
        String original = "患者因发热咳嗽收入院，查体双肺呼吸音粗。";
        Long recId = inpRecord(admId, "ADMISSION", original, author);

        var signed = countersignService.countersign(recId, "同意，补充查体描述", supId);
        assertTrue(signed.ok(), "上级审签应成功，实际 " + signed.code() + " " + signed.message());
        assertEquals(CountersignService.sha256Hex(original), signed.body().get("contentSha256"),
                "落库摘要必须是审签当时那份正文的 SHA-256——它是「签的是哪一版」的唯一证据");
        assertEquals(authorId, signed.body().get("authorId"));
        assertEquals(supId, signed.body().get("countersignerId"));
        assertTrue(Set.of("VERSION_TABLE", "NO_VERSION_ROW", "DIGEST_ONLY")
                        .contains(signed.body().get("versionSource")),
                "version_source 只有三态（V158:99-101），实际：" + signed.body().get("versionSource"));

        // 未绑到具体版本时，warnings 必须**明说**——让签字的人知道自己这一签绑到了什么粒度
        if (!"VERSION_TABLE".equals(signed.body().get("versionSource"))) {
            @SuppressWarnings("unchecked")
            var warns = (List<String>) signed.body().get("warnings");
            assertFalse(warns == null || warns.isEmpty(),
                    "version_source=" + signed.body().get("versionSource")
                            + " 时必须给出 warning 说明本次审签只绑定正文摘要，不能静默降级");
        }

        // 签完之后正文没变 → 审签有效
        var before = countersignService.listForRecord(recId);
        assertEquals(1, before.size());
        assertEquals(Boolean.FALSE, before.get(0).get("stale"), "正文未变时审签不应为 stale");
        assertEquals(64, String.valueOf(before.get(0).get("content_sha256")).length(),
                "审签数组元素是 jdbc.queryForList 直出的**蛇形**键（content_sha256），不是 contentSha256");

        // 【签完再改】—— 本节的关键一跳
        jdbc.update("update inp_medical_record set content = ? where id = ?",
                original + "（住院医于上级签字后追加：患者诉夜间盗汗）", recId);
        flushClear();

        var after = countersignService.listForRecord(recId);
        assertEquals(Boolean.TRUE, after.get(0).get("stale"), """
                上级签完之后正文被改过，这条审签却仍显示有效。
                「签过了」与「签的是现在这一版」是两件事：只查前者，被改过的病历会静默漏掉，
                而那恰恰是纠纷里最需要看见的一类——上级签字之后正文被动过。""");

        var findings = countersignService.findings(admId);
        assertTrue(findings.stream().anyMatch(f ->
                        CountersignService.CODE_COUNTERSIGN_STALE.equals(f.code()) && recId.equals(f.recordId())),
                "签后修改必须落成 COUNTERSIGN_STALE 缺项，实际：" + findings);

        // gate 三态：任何档位下 findings 都照算——gate 只决定拿事实怎么办，不决定事实是什么
        var verdict = countersignService.verdict(admId);
        assertFalse(verdict.findings().isEmpty(),
                "gate=" + verdict.gate() + " 档下 findings 仍必须是真的（off 档若跳过计算，"
                        + "返回体里的「审签完整」会在缺签时谎报 true）");
    }

    /**
     * 审签在<b>没有版本行</b>时必须落 {@code NO_VERSION_ROW}（表在、这份病历没版本），
     * 而不是 {@code DIGEST_ONLY}（表都不在）。三态各自对应一种客观情形，混用就等于自陈不实。
     *
     * <p>V157 已落盘，{@code emr_version} 表<b>存在</b>——所以此刻的正确答案是 NO_VERSION_ROW。
     */
    @Test
    void countersignVersionSourceMustNotMisreportWhichStateItIsIn() {
        assertTrue(tableExists("emr_version"), "V157 未落库：emr_version 表不存在");
        Authentication author = auth(uniq("v53authorB"));
        Long admId = admit();
        Long recId = inpRecord(admId, "ADMISSION", "入院当日记录。", author);

        var ref = countersignService.resolveVersion(recId);
        assertEquals("NO_VERSION_ROW", ref.source(), """
                emr_version 表已经建好（V157 已落盘），这份病历只是还没有版本行，
                正确的自陈是 NO_VERSION_ROW（版本表在，这份病历没版本记录），实际却是 %s。

                DIGEST_ONLY 的语义是「版本表尚未就位」——在 V157 已落库的情况下报 DIGEST_ONLY
                是**自陈不实**：举证时它会让人以为平台压根没有版本能力，而事实是有能力、这份病历没赶上。
                三态各自对应一种客观情形（V158:99-101），混用一次，这三个值就再也不能当证据用。

                根因见 countersignVersionSoftReferenceMustResolveToV157Schema。""".formatted(ref.source()));
    }

    /**
     * <b>V2 的版本软引用必须真的指向 V157 的表结构。</b>本条是本类的头号跨车道对账。
     *
     * <p>V158 的三个寻址配置默认值是
     * {@code version_table=emr_version} / {@code version_fk_column=record_id} /
     * {@code version_no_column=version_no}；而 V157 建出来的列是
     * {@code emr_type / emr_id / version_no}——<b>没有 record_id 这一列</b>。
     * {@code CountersignService.resolveVersion} 用 {@code column_name in (fk, no)}
     * 查到的列数是 1 &lt; 2，于是<b>永远</b>回落 DIGEST_ONLY，V1 交付之后也不会自动点亮。
     * 两条车道各自的自测都绿，接缝却是断的——这正是本车道存在的理由。
     *
     * <p>还有第二处：软引用的查询是 {@code where <fk> = ? order by <no> desc limit 1}，
     * <b>不带 emr_type</b>。而 {@code emr_version} 是多态表（OUTP 指 {@code outp_emr.id}、
     * INP 指 {@code inp_medical_record.id}），只按 id 找，一份 id=5 的门诊病历的版本会被绑给
     * id=5 的住院病历——那是把甲的版本号写进乙的审签记录，<b>比不绑更坏</b>。
     */
    @Test
    void countersignVersionSoftReferenceMustResolveToV157Schema() {
        assertTrue(tableExists("emr_version"), "V157 未落库：emr_version 表不存在");

        // ① 配置指的列必须真的存在于版本表上
        String table = configReader.get("emr.countersign.version_table", "emr_version");
        String fk = configReader.get("emr.countersign.version_fk_column", "record_id");
        String no = configReader.get("emr.countersign.version_no_column", "version_no");
        var cols = columnsOf(table);
        assertTrue(cols.contains(fk), """
                V158 的软引用配置 emr.countersign.version_fk_column = '%s'，
                而 V157 建出来的 %s 实际列是 %s——**没有这一列**。

                后果（非推测，CountersignService.resolveVersion 逐行可核）：
                  `select count(*) from information_schema.columns where … column_name in ('%s','%s')`
                  查到 1 列 < 2 → 直接 return DIGEST_ONLY。
                即：**审签永远绑不上版本号，V1 交付之后也不会自动点亮**，
                而 V158 的注释写的是「V1 落地后无需改一行代码，配置对上即自动点亮」。

                修法二选一（都不用改代码，但必须有人真的去做，否则这条接缝就一直是断的）：
                  a) 把 sys_config 的 emr.countersign.version_fk_column 改成 'emr_id'；
                  b) 或在 V160 里 update 该 cfg_value（那是改配置不是回填业务数据，不违反零回填）。
                另：无论走哪条，都还有 ② 的多态问题要一起解决。""".formatted(fk, table, cols, fk, no));
        assertTrue(cols.contains(no), "配置 version_no_column='" + no + "' 不在 " + table + " 的列里：" + cols);

        // ② 端到端：有版本行时必须绑上，且必须按 emr_type 分流
        Authentication author = auth(uniq("v53authorC"));
        Long supId = userId(uniq("v53superiorC"));
        Long admId = admit();
        String content = "入院记录正文，用于验证审签绑定版本。";
        Long recId = inpRecord(admId, "ADMISSION", content, author);

        // 同一个 id 上故意造出「门诊版本」干扰项：多态表只按 id 找就会绑错
        jdbc.update("""
                insert into emr_version(emr_type, emr_id, version_no, source, content, content_len, saved_on)
                values ('INP', ?, 1, 'MANUAL', ?, ?, ?)
                """, recId, content, content.length(), java.sql.Date.valueOf(BusinessDates.today()));
        jdbc.update("""
                insert into emr_version(emr_type, emr_id, version_no, source, content, content_len, saved_on)
                values ('OUTP', ?, 99, 'MANUAL', '这是门诊病历的版本，绝不能被住院审签绑走', 24, ?)
                """, recId, java.sql.Date.valueOf(BusinessDates.today()));
        flushClear();

        var signed = countersignService.countersign(recId, "同意", supId);
        assertTrue(signed.ok(), "审签应成功：" + signed.code() + " " + signed.message());
        assertEquals("VERSION_TABLE", signed.body().get("versionSource"),
                "该病历在 emr_version 里已有版本行，version_source 必须是 VERSION_TABLE");
        assertEquals(1, signed.body().get("versionNo"), """
                审签绑到了 version_no=%s。emr_version 是**多态表**（emr_type=OUTP 指 outp_emr.id、
                INP 指 inp_medical_record.id），而软引用的查询是
                `where <fk> = ? order by <no> desc limit 1`，**不带 emr_type**——
                于是一份 id 相同的门诊病历的第 99 版被绑进了住院病历的审签记录。
                那是把甲的版本号写进乙的举证材料，比不绑更坏：不绑只是信息少，绑错是错误信息。
                修法：软引用查询必须带上 emr_type（或给 V158 再加一个
                emr.countersign.version_type_column + 值 的配置）。""".formatted(signed.body().get("versionNo")));
    }

    // ==================================================================================
    // §6 审签人 ≠ 书写人：连绕过服务层直连改库都必须被拒
    // ==================================================================================

    /**
     * <b>兜底约束真的在。</b>服务层的 5722 挡的是走接口的人；
     * 本条挡的是拿 psql 直连改库的人——DBA、数据修复脚本、下一个自己写 insert 的车道。
     *
     * <p>为什么非要在 DB 层再挡一次：自签自审是「上级审签」这件事在证据法上唯一的硬边界。
     * 一条 {@code author_id == countersigner_id} 的记录，看上去与真审签一模一样，
     * 举证时却什么都证明不了——而它一旦落库就无法与真记录区分。
     * 服务层校验会被绕过（脚本、直连、下一个新写的写路径都不经过它），DB 约束不会。
     */
    @Test
    void selfCountersignIsRejectedEvenWhenBypassingTheServiceLayer() {
        Authentication author = auth(uniq("v53authorD"));
        Long authorId = userId(author.getName());
        Long admId = admit();
        String content = "自签校验用记录。";
        Long recId = inpRecord(admId, "ADMISSION", content, author);

        // 服务层：5722
        var svc = countersignService.countersign(recId, "我自己签", authorId);
        assertEquals(5722, svc.code(),
                "审签人与书写人为同一人必须返 5722（与 gate 无关，三档下一律生效），实际 "
                        + svc.code() + " " + svc.message());

        // DB 层：直连 insert 同样被拒。用 savepoint 隔离——PG 在约束冲突后会把整个事务置为
        // aborted，不回滚到 savepoint 的话，本用例之后的任何一条 SQL 都会连坐报错。
        jdbc.execute("savepoint sp_self_countersign");
        String sha = CountersignService.sha256Hex(content);
        Exception caught = null;
        try {
            jdbc.update("""
                    insert into emr_countersign
                        (record_id, author_id, countersigner_id, countersigned_at,
                         content_sha256, content_len, version_source)
                    values (?, ?, ?, now(), ?, ?, 'DIGEST_ONLY')
                    """, recId, authorId, authorId, sha, content.length());
        } catch (Exception e) {
            caught = e;
        } finally {
            jdbc.execute("rollback to savepoint sp_self_countersign");
        }
        assertNotNull(caught, """
                绕过服务层直连 insert 一条「自己审自己」的审签记录，数据库放行了。
                V158 的 chk_emr_countersign_not_self 没有生效（或已被移除）。
                服务层的 5722 只挡走接口的人；DBA、数据修复脚本、下一个新写的写路径都不经过它。
                一条 author_id = countersigner_id 的记录落库后与真审签在形态上无法区分，
                举证时却什么都证明不了——这类记录必须在库层面进不来。""");
        // v53 合版后**有两道**数据库层防线，命中哪一道都算数：
        //   ① V158 的 CHECK `chk_emr_countersign_not_self`（author_id <> countersigner_id）；
        //   ② V160 的触发器 `trg_emr_countersign_author`——CHECK 只能比同行两列，
        //      比不到 inp_medical_record.doctor_id，于是**谎报 author_id** 就能把自签
        //      伪装成他签（复核实测过这条绕过路径）。触发器把 author_id 与病历实际
        //      书写人对账后兜底，因此它会**先于** CHECK 命中直白自签这一例。
        // 本条要钉的是「绕过服务层也挡得住」，不是「由哪一道挡住」——
        // 写死某一道的名字会让防线加强反而把测试打红。
        String msg = String.valueOf(caught.getMessage());
        assertTrue(msg.contains("chk_emr_countersign_not_self")
                        || msg.contains("审签人不得是该病历的书写人本人")
                        || msg.contains("与病历实际书写人")
                        || msg.contains("trg_emr_countersign_author"),
                "绕过服务层的自签必须被数据库层拒绝（CHECK 或触发器任一道），实际异常：" + msg);
    }

    // ==================================================================================
    // §7 既有链路零改动
    // ==================================================================================

    /**
     * 门诊病历保存的<b>返回体形状与既有取值逐字与 v52 相同</b>。
     *
     * <p>{@code DoctorStationService.saveEmr} 是核心写路径，本版要在它旁边加一条留痕接缝。
     * 「只加一条记录」能不能成立，唯一的证据就是本条：键集合一个不多一个不少、
     * 五段正文逐字符原样落库、v45 的两个侧车列在不传 fields 时仍为 null。
     * 留痕接缝若顺手改了返回体（哪怕只多一个 versionNo 键），前端与 E2E 会在别处以
     * 完全不指向根因的方式崩掉。<b>版本信息走独立端点，不塞进这个返回体。</b>
     */
    @Test
    void legacyOutpSaveContractIsByteForByteUnchanged() {
        Authentication doc = auth(uniq("v53docC"));
        Long rid = visit();
        String pi = "受凉后起病2天，咳嗽咳痰，无发热。";

        var saved = doctorStationController.saveEmr(rid, new SaveEmrRequest(emrOf(pi), List.of()), doc);
        assertEquals(0, saved.getCode(), saved.getMessage());

        var body = objectMapper.convertValue(saved.getData(), new TypeReference<Map<String, Object>>() {});
        assertEquals(Set.of("id", "registrationId", "chiefComplaint", "presentIllness", "pastHistory",
                        "physicalExam", "advice", "doctorId", "contentJson", "templateId",
                        "signature", "signedAt", "updatedAt"),
                body.keySet(), """
                门诊病历保存返回体的键集合被改动了（实际：%s）。
                v53 的纪律是「留痕只能是加一条记录」——既有返回体一个字节不动。
                版本号/版本数这类信息请走**独立端点**（车道 V1 的 /api/emr/versions/…），
                不要塞进这个返回体：V45StructuredEmrTest 与前端 OutpDoctorView 都按这个键集合读，
                多一个键就是一次静默的契约变更。""".formatted(new TreeSet<>(body.keySet())));
        assertEquals(pi, body.get("presentIllness"));
        assertNull(body.get("contentJson"), "不传 fields 时 contentJson 必须仍为 null");
        assertNull(body.get("templateId"), "不传 fields 时 templateId 必须仍为 null");

        flushClear();
        var row = jdbc.queryForList("select * from outp_emr where registration_id = ?", rid).get(0);
        assertEquals(pi, row.get("present_illness"), "正文必须逐字符原样落库，留痕接缝不得改写正文");
        assertFalse(String.valueOf(row.get("present_illness")).contains("【版本"),
                "留痕接缝绝不能往正文里追加任何标记——正文是被 CA 签名的那份东西");
    }

    /** 住院病历既有的两个错误码与正文落库逐字不变（9129 类型非法 / 9101 正文为空）。 */
    @Test
    void legacyInpAddRecordContractIsUnchanged() {
        Authentication doc = auth(uniq("v53docD"));
        Long admId = admit();

        var bad = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("ADMISSION_NOTE", "入院记录", "x"), doc);
        assertEquals(9129, bad.getCode(), "病历类型非法仍必须是 9129（v47 立的码），实际 " + bad.getCode());

        var blank = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "病程记录", "  "), doc);
        assertEquals(9101, blank.getCode(), "正文为空仍必须是 9101，实际 " + blank.getCode());

        String content = "患者一般情况可，无发热，继续观察。";
        Long recId = inpRecord(admId, "PROGRESS", content, doc);
        flushClear();
        assertEquals(content, jdbc.queryForObject(
                "select content from inp_medical_record where id = ?", String.class, recId),
                "住院病历正文必须逐字符原样落库");
    }

    /** v53 不得占用别人的错误码段：5700–5799 是本版的，别的段一个都不许新开。 */
    @Test
    void v53UsesOnlyItsOwnErrorCodeSegment() {
        Pattern code = Pattern.compile("\\b(57\\d{2})\\b");
        Set<String> outOfRange = new TreeSet<>();
        for (SrcFile f : sources(".java", JAVA_DIRS)) {
            if (!f.text().contains("emr_version") && !f.text().contains("emr_countersign")
                    && !f.text().contains("emr_timeliness")) {
                continue;
            }
            for (String line : codeLines(f.text())) {
                Matcher m = code.matcher(line);
                while (m.find()) {
                    int c = Integer.parseInt(m.group(1));
                    if (c < 5700 || c > 5799) outOfRange.add(f.rel() + ": " + c);
                }
            }
        }
        assertTrue(outOfRange.isEmpty(), "v53 用到了 5700–5799 之外的新码：" + outOfRange);
    }

    // ==================================================================================
    // §8 时限锚点口径：算不出来就说算不出来，未核对就标「待确认」
    // ==================================================================================

    /**
     * 时限规则字典的自陈必须完整：<b>每条规则都说得清用的是哪个锚点</b>，
     * 算不出来的规则说得清为什么算不出来，且一律不启用。
     *
     * <p>为什么「不可用」比「返回 0」重要：一个看着像真的 0，会让管理者把
     * 「我们没有能力统计」误读成「我们没有超时」。那不是少给了一个数，那是给了一个反向的结论。
     */
    @Test
    void timelinessRulesDeclareTheirAnchorsAndUnavailabilityHonestly() {
        assertTrue(tableExists("emr_timeliness_rule"), "V159 未落库：emr_timeliness_rule 表不存在");
        var rules = jdbc.queryForList("""
                select rule_code, rule_name, start_anchor, start_anchor_cn, end_anchor, end_anchor_cn,
                       limit_minutes, warn_ratio_pct, source_doc, source_article, source_effective_from,
                       source_verified, available, unavailable_reason, missing_fields, enabled
                from emr_timeliness_rule order by rule_code
                """);
        assertFalse(rules.isEmpty(), "V159 的规则种子一条都没有——时限质控没有口径可对");

        List<String> bad = new ArrayList<>();
        for (var r : rules) {
            String c = (String) r.get("rule_code");
            if (isBlank(r.get("start_anchor")) || isBlank(r.get("start_anchor_cn"))) {
                bad.add(c + " 缺起点锚点（机读列名与中文说明必须同时给：只给中文，对账的人回不到 SQL 去核实；"
                        + "只给列名，看报表的病案科人员读不懂）");
            }
            if (isBlank(r.get("source_doc")) || r.get("source_effective_from") == null) {
                bad.add(c + " 缺法定出处或生效日期——考核被质疑时拿不出依据");
            }
            boolean available = Boolean.TRUE.equals(r.get("available"));
            boolean enabled = Boolean.TRUE.equals(r.get("enabled"));
            if (!available && isBlank(r.get("unavailable_reason"))) {
                bad.add(c + " available=false 却没写为什么——「不可用」就成了不可核实的断言");
            }
            if (!available && enabled) {
                bad.add(c + " 算不出来却仍启用——清单里会出现一条永远空的规则，纯误导");
            }
            if (available && isBlank(r.get("end_anchor"))) {
                bad.add(c + " 标为可用却没有终点锚点——没有终点就算不出时长，"
                        + "这类规则要么补锚点、要么如实标 available=false");
            }
        }
        assertTrue(bad.isEmpty(), "时限规则字典自陈不完整：\n" + String.join("\n", bad));

        // 出厂默认必须是「未经原文核对」——种子里的条文号是按现行规范填的默认值，不是核过的事实
        Integer unverified = jdbc.queryForObject(
                "select count(*) from emr_timeliness_rule where source_verified = false", Integer.class);
        assertTrue(unverified != null && unverified > 0, """
                一条 source_verified=false 的规则都没有。V159 的种子出厂默认全部 false 是**刻意的**：
                条文号与阈值是按现行规范填写的默认值，**未经病案科取原文逐字核对**。
                把它们默认标成已核对，等于让一批没人核过的数字直接具备对外考核与举证资格。
                若本条红，请先确认是不是有人把种子的默认值改成了 true。""");
    }

    /**
     * <b>接口必须把口径写在脸上。</b>时限规则字典的返回体要说清「这条用的是哪个锚点」，
     * 且未核对原文的规则要带「待确认」一类的标注——想漏看都难。
     */
    @Test
    void timelinessApiStatesWhichAnchorItUsedAndFlagsUnverifiedRules() throws Exception {
        // v34 早有 /api/quality/emr-timeliness 与 /board 两条（NursingQualityController:188/279）——
        // 那是既有的超时率报表，不是 v53 车道 V3 的**规则字典出口**。拿它们冒充 V3 交付会让本条假绿，
        // 故按「规则字典」的形状收窄：rules / catalog / indicators。
        var v34 = endpointsMatching("quality/emr-timeliness");
        var gettable = endpointsMatching("timeliness").stream()
                .filter(e -> e.startsWith("GET "))
                .map(e -> e.substring(4))
                .filter(p -> !p.contains("{"))
                .filter(p -> {
                    String low = p.toLowerCase(Locale.ROOT);
                    return low.contains("rules") || low.contains("catalog") || low.contains("indicators");
                })
                .sorted()
                .toList();
        assertFalse(gettable.isEmpty(), """
                找不到 v53 车道 V3 的时限**规则字典**出口（rules / catalog / indicators 三种形状都没有）。
                已枚举全部 %d 条 HTTP 映射；命中的 timeliness 端点只有 v34 既有的那几条：%s
                ——那是超时率报表，不是本版的规则字典，不能拿来冒充交付。

                V159 的规则字典躺在库里没有出口，风险很具体：阈值可以被改
                （emr_timeliness_rule_log 记着改动），而「上个月超时率 3%%，为什么现在重算是 11%%」
                这个问题，只有接口把「本次用的是哪条规则、哪个锚点、阈值多少、这条核过原文没有」
                一并回出来才答得上。"""
                .formatted(mappedEndpoints().size(), v34.isEmpty() ? "（无）" : v34));

        String path = gettable.get(0);
        var res = mvc.perform(get(path)).andReturn();
        assertEquals(200, res.getResponse().getStatus(), path + " 返回 " + res.getResponse().getStatus());
        String bodyText = res.getResponse().getContentAsString();

        // ① 必须写明锚点：拿库里真实的一个锚点值去对，避免断言退化成「有 anchor 这个词」
        String anchor = jdbc.queryForObject(
                "select start_anchor from emr_timeliness_rule where available = true order by rule_code limit 1",
                String.class);
        assertTrue(bodyText.contains(anchor), """
                %s 的返回体里找不到锚点 '%s'。
                时限指标必须自陈用的是哪个时间锚点——不写明的话，两个人拿同一个数字会得出不同结论，
                而对账时谁也回不到 SQL 去核实（这正是 v49 麻醉质控「算得出但对不上账」的复发形态）。"""
                .formatted(path, anchor));

        // ② 未核对原文的规则必须带「待确认」一类标注
        assertTrue(bodyText.contains("待确认") || bodyText.contains("未核对")
                        || bodyText.contains("sourceVerified") || bodyText.contains("source_verified"),
                """
                %s 的返回体里没有任何「原文是否已核对」的标注。
                V159 的种子 source_verified 全部为 false——条文号与阈值是按现行规范填写的默认值，
                **未经病案科取原文逐字核对**。不标出来，这批数字就会被直接拿去对外考核或举证，
                而它们此刻还不具备那个资格。""".formatted(path));
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }
}
