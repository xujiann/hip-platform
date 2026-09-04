package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.medtech.service.EmrTemplateService.FromRecordReq;
import cn.hip.medtech.service.EmrTemplateService.TemplateReq;
import cn.hip.medtech.web.MedTechController;
import cn.hip.medtech.web.MedTechController.EmrTemplateReq;
import cn.hip.medtech.web.MedTechController.GrantReq;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v45 车道H 回归：病历模板体系地基（技术偏离表 987★ 常见病模板 / 988★ 科室默认模板 /
 * 1073★ 四级作用范围 / 1078★ 模板授权 / 1079★ 按既往病历建新病历 / 1095★ 病历存为模板）。
 *
 * <p><b>本类最要紧的一组断言不是"能建模板"，是 §① 契约保护。</b>
 * {@code emr_template} 的既有两个端点（{@code POST /api/emr-templates} 与
 * {@code GET /api/emr-templates?type=&deptId=}）自 v17/v38 起被
 * {@code RisView.vue:67}（type=RIS）、住院医生站模板下拉（{@code InpDoctorView.vue:579}）、
 * v42 维护页、{@code e2e-phase1316.py}、{@code e2e-outp-appt.py}、{@code V38RisExpTest}、
 * {@code V42EmrTemplateTest} 七处消费，连方法签名都被单测按 1 参/2 参写死。
 * 本版给 {@code emr_template} 加了 7 列、加了授权表、加了 8 个端点，
 * §① 就是那句"一个参数、一行取数口径都没动"的证据：
 * <b>既有 5 个键（id/dept_id/name/content/template_type）逐键逐值不变、排序不变、
 * 既有行的返回体投影完全相同</b>。
 *
 * <p><b>唯一一处刻意的行为变化，也在 §① 里明写并钉死</b>：已停用的模板不再从既有 GET 返回。
 * 这正是本版补 {@code enabled} 的全部意义——v44 建 rx_template 时把它写成了建表纪律
 * （"停用了还能被套用等于没做"）。影响面为零：{@code enabled} 默认 true，升级前每一行都是启用态。
 *
 * <p>其余各节：§② 四级可见性矩阵（含越权 4066）、§③ 新建即自动授权（1078 参数原话）、
 * §④ 科室默认模板（含<b>两条并发插入验证部分唯一索引真的生效</b>，不是只测应用层）、
 * §⑤ 存为模板 / 按既往病历取正文（非本患者 4068）、§⑥ PUT 与停用启用全路径（欠了三版的账）。
 *
 * <p><b>约定</b>：错误路径各自独立成 {@code @Test}（同 V43EmrSignTest / V44RxTemplateTest）。
 * 服务层 {@code @Transactional} 方法抛异常会把参与中的测试事务标记 rollback-only，
 * 不能与"抛错后还要继续写"的步骤混在一个用例里。只读方法（{@code use}/{@code listVisible}/
 * {@code priorRecordContent}）不带事务，抛错不污染，可以与后续断言同处一个用例。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "DOCTOR_OUTP", "QUALITY"})
class V45TemplateScopeTest {

    @Autowired MedTechController medTech;
    @Autowired InpatientService inpatientService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    // ================= 夹具 =================

    private static String uniq(String prefix) {
        return prefix + System.nanoTime() % 100000000L;
    }

    private Long newDept() {
        String code = uniq("V45D");
        return jdbc.queryForObject("""
                insert into sys_dept(parent_id, name, code, type, sort_no)
                values (null, ?, ?, 'CLINICAL', 999) returning id
                """, Long.class, "V45模板科" + code, code);
    }

    private record TestUser(Long id, Authentication auth) {}

    private TestUser newUser(Long deptId, String... roleCodes) {
        String username = uniq("v45u");
        Long id = deptId == null
                ? jdbc.queryForObject("""
                        insert into sys_user(username, password, real_name, enabled)
                        values (?, 'x', ?, true) returning id
                        """, Long.class, username, username + "医生")
                : jdbc.queryForObject("""
                        insert into sys_user(username, password, real_name, dept_id, enabled)
                        values (?, 'x', ?, ?, true) returning id
                        """, Long.class, username, username + "医生", deptId);
        for (String code : roleCodes) {
            jdbc.update("""
                    insert into sys_user_role(user_id, role_id)
                    select ?, r.id from sys_role r where r.code = ?
                    """, id, code);
        }
        return new TestUser(id, new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    private Long createScoped(Authentication auth, TemplateReq req) {
        var r = medTech.createScopedTemplate(req, auth);
        assertEquals(0, r.getCode(), "建模板应成功，实际：" + r.getMessage());
        return r.getData();
    }

    private List<Map<String, Object>> visible(Authentication auth) {
        return medTech.visibleTemplates(null, null, null, null, false, auth).getData();
    }

    private static boolean contains(List<Map<String, Object>> rows, Long id) {
        return rows.stream().anyMatch(m -> ((Number) m.get("id")).longValue() == id);
    }

    /**
     * 断言一次业务异常的错误码。
     *
     * <p>本组端点<b>不在控制器里 catch</b>：{@code HipBizException} 由
     * {@code GlobalExceptionHandler} 统一翻成 {@code R.fail(code, message)}（1.1.9 起的范式），
     * 单测直接调控制器方法时它就原样抛出来，用例按 {@code e.code} 断言。
     */
    private static void assertBiz(int code, org.junit.jupiter.api.function.Executable call) {
        HipBizException e = assertThrows(HipBizException.class, call);
        assertEquals(code, e.code, "错误码应为 " + code + "，实际 " + e.code + "：" + e.getMessage());
    }

    // ================= ① 契约保护（最重要） =================

    /**
     * <b>既有两个端点逐字不变。</b>
     *
     * <p>断言四件事：
     * <ol>
     *   <li>既有 4 列的<b>物理定义</b>没被顺手改宽改窄（id/dept_id/name/content 的类型与长度，
     *       以及 dept_id 仍然可空——GLOBAL/HOSPITAL 范围时它就是 null，
     *       而既有 GET 的过滤口径正是"本科室 <b>或</b> dept_id is null"）；</li>
     *   <li>POST 的请求 record 分量、返回体（code=0、data=null）不变；</li>
     *   <li>GET 的取数口径不变：本科室 + 全院通用带出、别科不串入、别的 template_type 不串入、
     *       {@code order by id} 升序，且每行的既有 5 个键<b>逐键逐值</b>与写进去的一致；</li>
     *   <li>新加的 7 列对既有调用方只是"多几个键"，且默认值不改变任何既有行为
     *       （enabled=true / is_default=false / scope 按 dept_id 推定）。</li>
     * </ol>
     */
    @Test
    void legacyEndpointsKeepTheirContractByteForByte() {
        // ---- (1) 既有列的物理定义 ----
        assertEquals("bigint", columnType("emr_template", "id"));
        assertEquals("bigint", columnType("emr_template", "dept_id"));
        assertEquals("character varying", columnType("emr_template", "name"));
        assertEquals(64, columnLength("emr_template", "name"));
        assertEquals("character varying", columnType("emr_template", "content"));
        assertEquals(4000, columnLength("emr_template", "content"),
                "content 仍是 varchar(4000)：v44 放宽的是 inp_medical_record.content，不是模板正文");
        assertEquals("YES", jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'emr_template' and column_name = 'dept_id'
                """, String.class), "dept_id 必须保持可空——全院通用模板与全局模板都靠它为 null");

        Long mine = jdbc.queryForObject("select id from sys_dept where code = 'OUTP_IM'", Long.class);
        Long other = jdbc.queryForObject("select id from sys_dept where code = 'OUTP_SURG'", Long.class);

        // 建新模板之前先拍一张既有行的快照（只投影既有 5 个键）
        List<Map<String, Object>> before = legacyProjection(mine, "EMR");

        // ---- (2) POST：请求体与返回体照旧 ----
        var posted = medTech.createTemplate(new EmrTemplateReq(
                mine, "V45入院记录·本科室", "主诉：\n现病史：\n既往史：", "EMR"));
        assertEquals(0, posted.getCode(), "既有 POST 返回体不变：code=0");
        assertNull(posted.getData(), "既有 POST 返回体不变：data 仍为 null（R<Void>）");
        medTech.createTemplate(new EmrTemplateReq(null, "V45病程记录·全院通用", "今日查房：\n处理：", "EMR"));
        medTech.createTemplate(new EmrTemplateReq(other, "V45他科模板", "不该出现在本科室下拉", "EMR"));
        medTech.createTemplate(new EmrTemplateReq(mine, "V45胸片报告", "两肺纹理清晰", "RIS"));

        // ---- (3) GET：取数口径与返回体逐键逐值 ----
        List<Map<String, Object>> rows = medTech.templates(mine, "EMR").getData();
        List<String> names = rows.stream().map(t -> String.valueOf(t.get("name"))).toList();
        assertTrue(names.contains("V45入院记录·本科室"), names.toString());
        assertTrue(names.contains("V45病程记录·全院通用"),
                "科室过滤必须带出全院通用模板（dept_id is null）：" + names);
        assertFalse(names.contains("V45他科模板"), "别科模板不得串入：" + names);
        assertFalse(names.contains("V45胸片报告"), "RIS 报告模板不得串入病历模板下拉：" + names);
        assertTrue(rows.stream().allMatch(t -> "EMR".equals(t.get("template_type"))));

        Map<String, Object> mineRow = rows.stream()
                .filter(t -> "V45入院记录·本科室".equals(t.get("name"))).findFirst().orElseThrow();
        // 既有 5 个键：逐键存在 + 逐值与写进去的一致（键名是 snake_case，前端与 E2E 都按这个取）
        assertTrue(mineRow.containsKey("id"));
        assertEquals(mine.longValue(), ((Number) mineRow.get("dept_id")).longValue());
        assertEquals("V45入院记录·本科室", mineRow.get("name"));
        assertEquals("主诉：\n现病史：\n既往史：", mineRow.get("content"));
        assertEquals("EMR", mineRow.get("template_type"));

        // order by id 升序照旧
        List<Long> ids = rows.stream().map(t -> ((Number) t.get("id")).longValue()).toList();
        assertEquals(ids.stream().sorted().toList(), ids, "既有 GET 的排序仍是 order by id 升序");

        // ---- (4) 新列对既有调用方只是"多几个键"，默认值不改变既有行为 ----
        assertEquals(Boolean.TRUE, mineRow.get("enabled"), "新模板默认启用（第三次踩坑前补上的那一列）");
        assertEquals(Boolean.FALSE, mineRow.get("is_default"));
        assertEquals("DEPT", mineRow.get("scope"),
                "既有 POST 带 deptId ⇒ 按既有语义推定为科室模板（这正是既有 GET 的过滤口径）");
        Map<String, Object> hospitalRow = rows.stream()
                .filter(t -> "V45病程记录·全院通用".equals(t.get("name"))).findFirst().orElseThrow();
        assertNull(hospitalRow.get("dept_id"));
        assertEquals("HOSPITAL", hospitalRow.get("scope"), "既有 POST 不带 deptId ⇒ 全院通用");

        // ---- 既有行的投影一字不动 ----
        List<Map<String, Object>> after = legacyProjection(mine, "EMR");
        assertTrue(after.containsAll(before),
                "本版新增的列与端点不得改动任何既有行的既有 5 个键");
    }

    /**
     * <b>本版唯一一处刻意的行为变化，就在这里钉死</b>：已停用的模板不再从既有 GET 返回。
     *
     * <p>不加这一句，"停用"就只是维护页上的一个标记——RisView 与住院医生站的下拉照样把停用模板
     * 端给医生（v44 建 rx_template 时把这条写成了建表纪律）。影响面为零：enabled 默认 true，
     * 升级前每一行都是启用态；<b>本用例顺带断言"停用一张模板不会扰动其余任何一行"</b>。
     */
    @Test
    void disabledTemplateDropsOutOfLegacyGetAndNothingElseMoves() {
        Long deptId = newDept();
        TestUser admin = newUser(deptId, "ADMIN");
        Long keep = createScoped(admin.auth(),
                new TemplateReq("V45留着的", "留着的正文", "EMR", "DEPT", deptId, "PROGRESS"));
        Long gone = createScoped(admin.auth(),
                new TemplateReq("V45要停用的", "要停用的正文", "EMR", "DEPT", deptId, "ROUND"));

        List<Map<String, Object>> before = legacyProjection(deptId, "EMR");
        assertTrue(contains(medTech.templates(deptId, "EMR").getData(), gone));

        assertEquals(0, medTech.disableTemplate(gone, admin.auth()).getCode());

        List<Map<String, Object>> after = legacyProjection(deptId, "EMR");
        assertFalse(contains(medTech.templates(deptId, "EMR").getData(), gone),
                "停用后既有 GET 不得再返回它——否则'停用'等于没做");
        assertTrue(contains(medTech.templates(deptId, "EMR").getData(), keep),
                "同科室其它模板不受影响");
        assertEquals(before.size() - 1, after.size(), "停用一张，且只少这一张");
        assertTrue(before.containsAll(after), "其余行的既有 5 个键一字不动");
        // 软开关：行还在，历史病历仍能追溯当时照的是哪一张
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from emr_template where id = ?", Integer.class, gone),
                "停用是软开关，不删行");
    }

    private List<Map<String, Object>> legacyProjection(Long deptId, String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : medTech.templates(deptId, type).getData()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("id", "dept_id", "name", "content", "template_type")) {
                m.put(k, r.get(k));
            }
            out.add(m);
        }
        return out;
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_name = ? and column_name = ?
                """, String.class, table, column);
    }

    private Integer columnLength(String table, String column) {
        return jdbc.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_name = ? and column_name = ?
                """, Integer.class, table, column);
    }

    // ================= ② 四级可见性矩阵（1073★） =================

    /**
     * GLOBAL 人人可见 / HOSPITAL 人人可见 / DEPT 本科室 + 被授权科室 / PERSONAL 本人 + 被授权个人。
     * <b>管理员刻意看不到别人的个人模板</b>——个人模板是医生自己的书写习惯草稿，
     * "全院模板仅管理员可改"保护的是全院口径，不是给管理员一把翻看私人草稿的钥匙。
     *
     * <p>越权路径走只读的 {@code GET /api/emr-templates/{id}}（不带事务，抛错不污染测试事务）。
     */
    @Test
    void fourLevelScopeVisibilityMatrix() {
        Long deptA = newDept();
        Long deptB = newDept();
        TestUser admin = newUser(deptA, "ADMIN");
        TestUser docA1 = newUser(deptA, "DOCTOR_OUTP");
        TestUser docA2 = newUser(deptA, "DOCTOR_OUTP");
        TestUser docB = newUser(deptB, "DOCTOR_OUTP");

        Long global = createScoped(admin.auth(),
                new TemplateReq("V45全局模板", "全局正文", "EMR", "GLOBAL", null, null));
        Long hospital = createScoped(admin.auth(),
                new TemplateReq("V45全院模板", "全院正文", "EMR", "HOSPITAL", null, null));
        Long deptTpl = createScoped(docA1.auth(),
                new TemplateReq("V45A科模板", "A科正文", "EMR", "DEPT", deptA, "ADMISSION"));
        Long personal = createScoped(docA1.auth(),
                new TemplateReq("V45A1个人模板", "个人正文", "EMR", "PERSONAL", null, null));

        // —— 建者本人：四张全见
        var a1 = visible(docA1.auth());
        assertTrue(contains(a1, global) && contains(a1, hospital)
                && contains(a1, deptTpl) && contains(a1, personal), "建者本人应看到全部四张");

        // —— 同科室他人：全局/全院/本科室可见，别人的个人模板不可见
        var a2 = visible(docA2.auth());
        assertTrue(contains(a2, global), "GLOBAL 人人可见");
        assertTrue(contains(a2, hospital), "HOSPITAL 人人可见");
        assertTrue(contains(a2, deptTpl), "DEPT 本科室可见");
        assertFalse(contains(a2, personal), "PERSONAL 只对本人可见");

        // —— 他科医生：只见全局/全院
        var b = visible(docB.auth());
        assertTrue(contains(b, global) && contains(b, hospital));
        assertFalse(contains(b, deptTpl), "DEPT 对未授权的他科不可见");
        assertFalse(contains(b, personal));

        // —— 管理员：见全部科室模板，但**看不到别人的个人模板**
        var ad = visible(admin.auth());
        assertTrue(contains(ad, deptTpl), "管理员可见任意科室模板");
        assertFalse(contains(ad, personal), "管理员也看不到别人的个人模板");

        // —— 越权使用：他科医生按 id 取科室模板 / 个人模板，一律 4066（不区分"不存在"与"无权"）
        assertBiz(4066, () -> medTech.useTemplate(deptTpl, docB.auth()));
        assertBiz(4066, () -> medTech.useTemplate(personal, docB.auth()));
        assertBiz(4066, () -> medTech.useTemplate(personal, admin.auth()));

        // —— 授权（1078）叠加：授权到科室 B 后，B 科医生看得见也用得了
        assertEquals(0, medTech.grantTemplate(deptTpl, new GrantReq("DEPT", deptB), docA1.auth()).getCode());
        assertTrue(contains(visible(docB.auth()), deptTpl), "被授权科室应看得到");
        assertEquals(0, medTech.useTemplate(deptTpl, docB.auth()).getCode());

        // —— 授权到个人：个人模板授权给 docB 之后 docB 可用，A2 仍不可见
        assertEquals(0, medTech.grantTemplate(personal, new GrantReq("USER", docB.id()), docA1.auth()).getCode());
        assertTrue(contains(visible(docB.auth()), personal), "被授权个人应看得到");
        assertFalse(contains(visible(docA2.auth()), personal), "未被授权的同科室他人仍不可见");

        // —— 列表口径与按 id 判权口径必须同构：能列出来的就一定取得到
        for (Map<String, Object> row : visible(docB.auth())) {
            Long id = ((Number) row.get("id")).longValue();
            assertEquals(0, medTech.useTemplate(id, docB.auth()).getCode(),
                    "列表里出现的模板必须取得到（visibleWhere 与 canSee 同构）");
        }
    }

    /** 授权<b>只放大可见范围，不放大维护权限</b>：被授权科室的医生能用，改不动。 */
    @Test
    void grantWidensVisibilityButNeverEditRights() {
        Long deptA = newDept();
        Long deptB = newDept();
        TestUser docA = newUser(deptA, "DOCTOR_OUTP");
        TestUser docB = newUser(deptB, "DOCTOR_OUTP");
        Long tpl = createScoped(docA.auth(),
                new TemplateReq("V45可授权科室模板", "正文", "EMR", "DEPT", deptA, "PROGRESS"));
        medTech.grantTemplate(tpl, new GrantReq("DEPT", deptB), docA.auth());
        assertEquals(0, medTech.useTemplate(tpl, docB.auth()).getCode(), "被授权者可用");

        assertBiz(4066, () -> medTech.updateTemplate(tpl,
                new TemplateReq("V45被授权者改名", "改后的正文", "EMR", "DEPT", deptA, "PROGRESS"),
                docB.auth()));
    }

    /** 全局/全院是院级口径：非管理员建不了（这是"无权"不是"取值非法"，故 4066） */
    @Test
    void nonAdminCannotCreateHospitalWideTemplate() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        assertBiz(4066, () -> medTech.createScopedTemplate(
                new TemplateReq("V45越权全院", "正文", "EMR", "HOSPITAL", null, null), doc.auth()));
    }

    /** 作用范围取值非法 ⇒ 4065（取值不成立，与"无权"分开） */
    @Test
    void illegalScopeValueIsRejected() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        assertBiz(4065, () -> medTech.createScopedTemplate(
                new TemplateReq("V45非法范围", "正文", "EMR", "WARD", null, null), doc.auth()));
    }

    /** 科室模板必须落到一个科室：账号也没配科室时 4065 */
    @Test
    void deptScopeWithoutAnyDeptIsRejected() {
        TestUser doc = newUser(null, "DOCTOR_OUTP");
        assertBiz(4065, () -> medTech.createScopedTemplate(
                new TemplateReq("V45无科室的科室模板", "正文", "EMR", "DEPT", null, null), doc.auth()));
    }

    /** 授权对象不存在 ⇒ 4065 */
    @Test
    void grantToUnknownDeptIsRejected() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        Long tpl = createScoped(doc.auth(),
                new TemplateReq("V45待授权", "正文", "EMR", "DEPT", deptA, null));
        assertBiz(4065, () -> medTech.grantTemplate(tpl, new GrantReq("DEPT", 99999999L), doc.auth()));
    }

    // ================= ③ 新建即自动授权（1078★） =================

    /**
     * 参数原话：<b>「需要授权的模板在新建的时候自动完成授权给构建科室或构建人」</b>。
     * 科室模板自动写 (DEPT, 所属科室)、个人模板自动写 (USER, 创建人)；
     * 全局/全院<b>不写</b>——它们的可见范围本就是全体，一条"授权给全院"的行没有对象也没有语义。
     * 自动写的那条<b>不允许撤销</b>（撤了模板对自己都不可见，是一条谁都想不到的自伤路径）。
     */
    @Test
    void creatingATemplateAutoGrantsItsOwnDeptOrOwner() {
        Long deptA = newDept();
        TestUser admin = newUser(deptA, "ADMIN");
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");

        Long deptTpl = createScoped(doc.auth(),
                new TemplateReq("V45自动授权科室", "正文", "EMR", "DEPT", deptA, "PROGRESS"));
        var g1 = medTech.templateGrants(deptTpl, doc.auth()).getData();
        assertEquals(1, g1.size(), "科室模板建好即有一条自动授权：" + g1);
        assertEquals("DEPT", g1.get(0).get("grantee_type"));
        assertEquals(deptA.longValue(), ((Number) g1.get(0).get("grantee_id")).longValue());

        Long personal = createScoped(doc.auth(),
                new TemplateReq("V45自动授权个人", "正文", "EMR", "PERSONAL", null, null));
        var g2 = medTech.templateGrants(personal, doc.auth()).getData();
        assertEquals(1, g2.size(), "个人模板建好即有一条自动授权给构建人：" + g2);
        assertEquals("USER", g2.get(0).get("grantee_type"));
        assertEquals(doc.id().longValue(), ((Number) g2.get(0).get("grantee_id")).longValue());

        Long hospital = createScoped(admin.auth(),
                new TemplateReq("V45全院不写授权", "正文", "EMR", "HOSPITAL", null, null));
        assertEquals(0, medTech.templateGrants(hospital, admin.auth()).getData().size(),
                "全院模板不写授权行：可见范围就是全体，'授权给全院'没有对象也没有语义");

        // 重复授予幂等——"再授权一次"业务上就是无操作，不该报错也不该多一行
        assertEquals(0, medTech.grantTemplate(deptTpl, new GrantReq("DEPT", deptA), doc.auth()).getCode());
        assertEquals(1, medTech.templateGrants(deptTpl, doc.auth()).getData().size());

        // 既有 POST 建的科室模板同样自动授权（老通道也补上这一条，否则老通道建的模板成了孤儿）
        medTech.createTemplate(new EmrTemplateReq(deptA, "V45老通道科室模板", "正文", "EMR"));
        Long legacyId = jdbc.queryForObject(
                "select id from emr_template where name = 'V45老通道科室模板' order by id desc limit 1", Long.class);
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from emr_template_grant where template_id = ? and grantee_type = 'DEPT'",
                Integer.class, legacyId));
    }

    /** 自动授予的那一条撤不掉（撤销后模板对自身科室不可见 ⇒ 孤儿模板） */
    @Test
    void revokingTheAutoGrantIsRefused() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        Long tpl = createScoped(doc.auth(),
                new TemplateReq("V45自伤授权", "正文", "EMR", "DEPT", deptA, null));
        Long grantId = ((Number) medTech.templateGrants(tpl, doc.auth()).getData().get(0).get("id")).longValue();
        assertBiz(4065, () -> medTech.revokeTemplateGrant(tpl, grantId, doc.auth()));
    }

    // ================= ④ 科室默认模板（988★） =================

    /** 设为默认 → 按 deptId + recordType 取到它 → 取消默认 → 取不到 */
    @Test
    void deptDefaultTemplateSetAndFetchAndUnset() {
        Long deptA = newDept();
        TestUser admin = newUser(deptA, "ADMIN");
        Long tpl = createScoped(admin.auth(),
                new TemplateReq("V45入院默认", "入院记录正文", "EMR", "DEPT", deptA, "ADMISSION"));

        assertNull(medTech.defaultTemplate(deptA, "ADMISSION", admin.auth()).getData(),
                "没设默认时返回 null 而不是报错（绝大多数科室大多数类型本来就没设）");

        assertEquals(0, medTech.setTemplateDefault(tpl, false, admin.auth()).getCode());
        var def = medTech.defaultTemplate(deptA, "ADMISSION", admin.auth()).getData();
        assertNotNull(def);
        assertEquals(tpl.longValue(), ((Number) def.get("id")).longValue());
        assertEquals("入院记录正文", def.get("content"));
        assertNull(medTech.defaultTemplate(deptA, "PROGRESS", admin.auth()).getData(),
                "默认模板按 科室 + 病历类型 取，别的类型取不到");

        assertEquals(0, medTech.unsetTemplateDefault(tpl, admin.auth()).getCode());
        assertNull(medTech.defaultTemplate(deptA, "ADMISSION", admin.auth()).getData());
    }

    /** 同科室同病历类型第二张要设默认 ⇒ 4067；带 replace=true 则一次事务内换位，无并发窗口 */
    @Test
    void secondDefaultForSameDeptAndRecordTypeConflicts() {
        Long deptA = newDept();
        TestUser admin = newUser(deptA, "ADMIN");
        Long first = createScoped(admin.auth(),
                new TemplateReq("V45默认一号", "正文一", "EMR", "DEPT", deptA, "ADMISSION"));
        Long second = createScoped(admin.auth(),
                new TemplateReq("V45默认二号", "正文二", "EMR", "DEPT", deptA, "ADMISSION"));
        medTech.setTemplateDefault(first, false, admin.auth());

        assertBiz(4067, () -> medTech.setTemplateDefault(second, false, admin.auth()));
    }

    /** replace=true：让位与占位在同一事务里完成 */
    @Test
    void replacingTheDefaultSwapsInOneTransaction() {
        Long deptA = newDept();
        TestUser admin = newUser(deptA, "ADMIN");
        Long first = createScoped(admin.auth(),
                new TemplateReq("V45默认甲", "正文甲", "EMR", "DEPT", deptA, "PROGRESS"));
        Long second = createScoped(admin.auth(),
                new TemplateReq("V45默认乙", "正文乙", "EMR", "DEPT", deptA, "PROGRESS"));
        medTech.setTemplateDefault(first, false, admin.auth());
        assertEquals(0, medTech.setTemplateDefault(second, true, admin.auth()).getCode());

        var def = medTech.defaultTemplate(deptA, "PROGRESS", admin.auth()).getData();
        assertEquals(second.longValue(), ((Number) def.get("id")).longValue());
        assertEquals(1, (int) jdbc.queryForObject("""
                select count(*) from emr_template
                where dept_id = ? and record_type = 'PROGRESS' and is_default and enabled
                """, Integer.class, deptA), "换位之后仍然只有一张默认");
    }

    /** 没绑科室或没绑病历类型的模板设不了默认（988 的取数口径就是 deptId + recordType） */
    @Test
    void defaultRequiresBothDeptAndRecordType() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        Long noType = createScoped(doc.auth(),
                new TemplateReq("V45没绑类型", "正文", "EMR", "DEPT", deptA, null));
        assertBiz(4067, () -> medTech.setTemplateDefault(noType, false, doc.auth()));
    }

    /**
     * <b>科室默认模板的唯一性由数据库的部分唯一索引保证，不是应用层读-判-写。</b>
     *
     * <p>本用例<b>不走服务层</b>，直接用两条<b>真并发的 INSERT</b>（各自独立事务、同一起跑线）
     * 去撞 {@code uq_emr_tpl_default}：一条成功、一条 23505，库里最终只剩一张默认。
     * 只测应用层判重是测不出这条的——读-判-写在并发下两条都会通过，之后医生站取默认模板
     * 随机取到其中一张，现场根本查不出所以然。
     *
     * <p>本方法<b>不参与测试事务</b>（{@code NOT_SUPPORTED}）：各线程要各自提交才构成真实并发写，
     * 因此自己建的数据自己在 finally 里收拾。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deptDefaultUniquenessIsEnforcedByThePartialIndexUnderConcurrency() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String code = uniq("V45C");
        Long deptId = tx.execute(s -> jdbc.queryForObject("""
                insert into sys_dept(parent_id, name, code, type, sort_no)
                values (null, ?, ?, 'CLINICAL', 999) returning id
                """, Long.class, "V45并发科" + code, code));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String name = "V45并发默认" + code + "-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        tx.execute(s -> jdbc.update("""
                                insert into emr_template(dept_id, name, content, template_type,
                                                         scope, record_type, is_default, enabled)
                                values (?, ?, '并发正文', 'EMR', 'DEPT', 'ADMISSION', true, true)
                                """, deptId, name));
                        return "OK";
                    } catch (DataIntegrityViolationException e) {
                        return "DUP";
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream().filter("OK"::equals).count(),
                    "两条并发插入只能有一条成功，实际：" + results);
            assertEquals(1, results.stream().filter("DUP"::equals).count(),
                    "另一条必须被数据库的部分唯一索引挡下（23505），实际：" + results);
            assertEquals(1, (int) jdbc.queryForObject("""
                    select count(*) from emr_template
                    where dept_id = ? and record_type = 'ADMISSION' and is_default and enabled
                    """, Integer.class, deptId), "库里最终只剩一张默认模板");

            // 谓词是 `is_default and enabled`：停用后默认位自动让出来，另一张即可占位
            tx.execute(s -> jdbc.update("""
                    update emr_template set enabled = false where dept_id = ? and is_default
                    """, deptId));
            assertDoesNotThrow(() -> tx.execute(s -> jdbc.update("""
                    insert into emr_template(dept_id, name, content, template_type,
                                             scope, record_type, is_default, enabled)
                    values (?, ?, '让位后的正文', 'EMR', 'DEPT', 'ADMISSION', true, true)
                    """, deptId, "V45让位后" + code)),
                    "停用的模板不再占默认位（索引谓词含 enabled）");
        } finally {
            pool.shutdownNow();
            tx.execute(s -> jdbc.update("delete from emr_template where dept_id = ?", deptId));
            tx.execute(s -> jdbc.update("delete from sys_dept where id = ?", deptId));
        }
    }

    // ================= ⑤ 存为模板（1095★）/ 按既往病历取正文（1079★） =================

    /**
     * 住院病历存为模板 + 该患者既往病历清单 + 取正文。
     * <b>全程只读病历、只写模板</b>：本车道没有、也不许有任何新的写病历路径。
     */
    @Test
    void saveInpatientRecordAsTemplateAndListPriorRecords() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        Long patientId = newPatient("V45既往");
        Long admissionId = admit(patientId);
        Long recordId = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id, record_type, title, content)
                values (?, 'ADMISSION', 'V45入院记录', ?) returning id
                """, Long.class, admissionId, "主诉：发热三天\n查体：T 38.6℃");

        var prior = medTech.priorRecords(patientId, null).getData();
        assertTrue(prior.stream().anyMatch(m -> ((Number) m.get("record_id")).longValue() == recordId),
                "既往病历清单应含刚写的这条：" + prior);
        assertTrue(prior.stream().allMatch(m -> "INP".equals(m.get("source"))));

        var content = medTech.priorRecordContent(patientId, "INP", recordId).getData();
        assertEquals("主诉：发热三天\n查体：T 38.6℃", content.get("content"));
        assertEquals("ADMISSION", content.get("recordType"));

        var saved = medTech.saveRecordAsTemplate(new FromRecordReq(
                "INP", recordId, patientId, "V45由病历生成的模板", "PERSONAL", null, null), doc.auth()).getData();
        Long tplId = ((Number) saved.get("id")).longValue();
        assertEquals(Boolean.FALSE, saved.get("truncated"));
        assertEquals("主诉：发热三天\n查体：T 38.6℃", jdbc.queryForObject(
                "select content from emr_template where id = ?", String.class, tplId),
                "模板正文与来源病历逐字一致");
        assertEquals("ADMISSION", jdbc.queryForObject(
                "select record_type from emr_template where id = ?", String.class, tplId),
                "不指定病历类型时沿用来源病历的类型");
        assertEquals(1, medTech.templateGrants(tplId, doc.auth()).getData().size(),
                "存为模板同样走新建即自动授权");
    }

    /** 门诊病历五段拼成一份可套用的正文（union 的 OUTP 分支 + concat_ws 拼接） */
    @Test
    void outpatientEmrIsAssembledIntoOneTemplateBody() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        Long patientId = newPatient("V45门诊既往");
        Long regId = newRegistration(patientId, deptA);
        Long emrId = jdbc.queryForObject("""
                insert into outp_emr(registration_id, chief_complaint, present_illness, advice)
                values (?, '咳嗽两天', '无发热', '多饮水') returning id
                """, Long.class, regId);

        var prior = medTech.priorRecords(patientId, null).getData();
        assertTrue(prior.stream().anyMatch(m -> "OUTP".equals(m.get("source"))
                && ((Number) m.get("record_id")).longValue() == emrId), "既往清单应含门诊病历：" + prior);

        String body = String.valueOf(medTech.priorRecordContent(patientId, "OUTP", emrId).getData().get("content"));
        assertTrue(body.contains("主诉：咳嗽两天"), body);
        assertTrue(body.contains("现病史：无发热"), body);
        assertTrue(body.contains("处理意见：多饮水"), body);
        assertFalse(body.contains("既往史："), "空段不拼进去（concat_ws 跳过 null）：" + body);

        var saved = medTech.saveRecordAsTemplate(new FromRecordReq(
                "OUTP", emrId, patientId, "V45门诊存为模板", "PERSONAL", null, null), doc.auth()).getData();
        assertEquals("OUTP", saved.get("recordType"));
    }

    /**
     * <b>{@code patientId} 是归属校验参数，不是过滤条件。</b>
     * 少了这一句，任何人拿一个自增 id 就能把别的患者的病历抄进自己面前的病历里——那是串档。
     */
    @Test
    void priorRecordOfAnotherPatientIsRejected() {
        Long p1 = newPatient("V45甲");
        Long p2 = newPatient("V45乙");
        Long recordId = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id, record_type, title, content)
                values (?, 'PROGRESS', 'V45病程', '甲患者的病程记录') returning id
                """, Long.class, admit(p1));

        assertEquals(0, medTech.priorRecordContent(p1, "INP", recordId).getCode());
        assertBiz(4068, () -> medTech.priorRecordContent(p2, "INP", recordId));
        assertBiz(4068, () -> medTech.priorRecordContent(p1, "INP", 99999999L));
        assertBiz(4068, () -> medTech.priorRecordContent(p1, "PACS", recordId));
    }

    /** 存为模板同样做归属校验：拿别人的病历存模板会把正文发给全科室看 */
    @Test
    void saveAsTemplateAlsoChecksPatientOwnership() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        Long p1 = newPatient("V45丙");
        Long p2 = newPatient("V45丁");
        Long recordId = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id, record_type, title, content)
                values (?, 'PROGRESS', 'V45病程', '丙患者的病程记录') returning id
                """, Long.class, admit(p1));
        assertBiz(4068, () -> medTech.saveRecordAsTemplate(
                new FromRecordReq("INP", recordId, p2, "V45偷来的模板", "PERSONAL", null, null), doc.auth()));
    }

    /**
     * 超长病历存为模板：截断到 4000，并<b>显式回 truncated=true</b>。
     * emr_template.content 仍是 varchar(4000)（本版刻意不动列宽）——
     * 医生存了一份被砍掉一半的模板却毫不知情，比存不进去更糟。
     */
    @Test
    void overlongRecordIsTruncatedButNeverSilently() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        Long patientId = newPatient("V45超长");
        String longText = "术前小结·模板渲染段落。".repeat(600);   // 7200 字符
        assertTrue(longText.length() > 4000);
        Long recordId = jdbc.queryForObject("""
                insert into inp_medical_record(admission_id, record_type, title, content)
                values (?, 'PREOP', 'V45超长病历', ?) returning id
                """, Long.class, admit(patientId), longText);

        var saved = medTech.saveRecordAsTemplate(new FromRecordReq(
                "INP", recordId, patientId, "V45超长模板", "PERSONAL", null, null), doc.auth()).getData();
        assertEquals(Boolean.TRUE, saved.get("truncated"), "截断必须显式告知调用方");
        assertEquals(4000, saved.get("contentLength"));
        assertEquals(longText.length(), saved.get("sourceLength"));
    }

    // ================= ⑥ PUT / 停用 / 启用（欠了三个版本的账） =================

    /**
     * 建 → 改（改名 + 改正文 + 改作用范围）→ 停用 → 启用 的整条链。
     *
     * <p>v42 发现 emr_template 没有 enabled 列、"编辑与停用做不了"，v43 又确认一次；
     * 到本版是<b>第三次</b>面对同一个坑。本用例就是那笔账还清了的证据——
     * 且改作用范围时会<b>补一条自动授权</b>（否则个人模板改成科室模板后本科室反而看不到它）。
     */
    @Test
    void updateDisableEnableFullPath() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        Long id = createScoped(doc.auth(),
                new TemplateReq("V45初版", "初版正文", "EMR", "PERSONAL", null, null));
        assertTrue(jdbc.queryForObject("select enabled from emr_template where id = ?", Boolean.class, id),
                "新模板默认启用");

        assertEquals(0, medTech.updateTemplate(id,
                new TemplateReq("V45改名后", "改后的正文", "EMR", "DEPT", deptA, "PROGRESS"),
                doc.auth()).getCode());
        Map<String, Object> row = jdbc.queryForMap("select * from emr_template where id = ?", id);
        assertEquals("V45改名后", row.get("name"));
        assertEquals("改后的正文", row.get("content"));
        assertEquals("DEPT", row.get("scope"));
        assertEquals(deptA.longValue(), ((Number) row.get("dept_id")).longValue());
        assertEquals("PROGRESS", row.get("record_type"));
        assertTrue(medTech.templateGrants(id, doc.auth()).getData().stream()
                        .anyMatch(g -> "DEPT".equals(g.get("grantee_type"))),
                "改成科室模板后要补一条科室授权，否则本科室反而看不到它");

        assertEquals(0, medTech.disableTemplate(id, doc.auth()).getCode());
        assertFalse(jdbc.queryForObject("select enabled from emr_template where id = ?", Boolean.class, id));
        assertFalse(contains(visible(doc.auth()), id), "停用后默认列表不再出现");
        assertTrue(contains(medTech.visibleTemplates(null, null, null, null, true, doc.auth()).getData(), id),
                "维护页传 includeDisabled=true 仍要看得到，否则没法把它启回来");

        assertEquals(0, medTech.enableTemplate(id, doc.auth()).getCode());
        assertTrue(contains(visible(doc.auth()), id));

        // 停用后按 id 套用一律 4066（只读路径，抛错不污染测试事务）
        medTech.disableTemplate(id, doc.auth());
        assertBiz(4066, () -> medTech.useTemplate(id, doc.auth()));
    }

    /** 名称必填沿用 4061、正文为空沿用 4062（与 RxTemplateService 逐字同义，不另造同义码） */
    @Test
    void blankNameIsRejectedWithTheExistingCode() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        assertBiz(4061, () -> medTech.createScopedTemplate(
                new TemplateReq("   ", "正文", "EMR", "PERSONAL", null, null), doc.auth()));
    }

    @Test
    void blankContentIsRejectedWithTheExistingCode() {
        Long deptA = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        assertBiz(4062, () -> medTech.createScopedTemplate(
                new TemplateReq("V45空正文", "  ", "EMR", "PERSONAL", null, null), doc.auth()));
    }

    // ================= 病历夹具 =================

    private Long newPatient(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime() % 100000L);
        p.setSex("U");
        return patientService.register(p).getId();
    }

    private Long admit(Long patientId) {
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(patientId, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
    }

    private Long newRegistration(Long patientId, Long deptId) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(deptId);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(9);
        s = scheduleRepository.save(s);
        return registrationService.register(patientId, s.getId()).getId();
    }
}
