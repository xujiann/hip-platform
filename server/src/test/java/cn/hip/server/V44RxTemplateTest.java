package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.outpatient.service.RxTemplateService.TemplateLine;
import cn.hip.outpatient.service.RxTemplateService.TemplateReq;
import cn.hip.outpatient.web.RxTemplateController;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v44 车道F 回归：处方模板与协定处方（技术偏离表 999★ 模板+模板编辑 / 1000★ 协定处方）。
 *
 * <p>立项依据：{@code grep 处方模板|协定处方|rx_template} <b>全仓零命中</b>，
 * 而 999/1000 两条都已答"平台已实现"——本类是这两条的诚信兑现证据。
 *
 * <p><b>本类最要紧的两组断言，不是"能建模板"：</b>
 * <ol>
 *   <li><b>§⑤ 纪律用例：模板明细必须能逐字段映射到开单行。</b>
 *       用 {@code information_schema} 比对 {@code rx_template_line} 与 {@code outp_order}
 *       的六个开单列（类型+长度），再用<b>反射</b>遍历 {@code OrderLine} 的每个记录分量，
 *       断言「取明细」返回体里都有同名键，并把返回体<b>反射构造成 OrderLine</b> 直接开单。
 *       模板存的字段名一旦与开单对不上，套用时前端就得手工转换，转换表迟早与后端漂移；
 *       这条用例就是把那种漂移在 CI 里当场拍死。</li>
 *   <li><b>§⑥ 模板不是绕过用药安全的后门。</b>套用后仍走原
 *       {@code DoctorStationService.createOrders}：模板里的药被停用，照样撞 8016；
 *       模板里同一个药重复，照样撞 4013。本车道<b>没有</b>、也不许有任何批量开单端点，
 *       用例顺带断言"列模板/取明细"这两个只读动作一行 {@code outp_order} 都不会产生。</li>
 * </ol>
 *
 * <p>§②③ 把三级作用范围的可见性/可改性<b>连同越权路径</b>钉死：
 * 个人模板 ADMIN 也看不到（个人模板是医生自己的用药习惯草稿，不是全院资产）；
 * 科室模板本科室可见、创建者与 ADMIN 可改；全院模板人人可见、仅 ADMIN 可改。
 *
 * <p>§④ 协定处方改明细返 4064（ADMIN 亦然）——要改就停用旧版另建新版，
 * 否则已按旧版开出的处方来源会凭空变脸，与 emr_template「复制为新模板」同源的取舍。
 *
 * <p><b>约定</b>：错误路径各自独立成 @Test。服务层抛 BizException 会把参与中的测试事务
 * 标记 rollback-only（同 V43EmrSignTest 的约定），不与"抛错后仍需继续写"的步骤混在一个用例里。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "DOCTOR_OUTP", "PHARMACIST"})
class V44RxTemplateTest {

    @Autowired RxTemplateController controller;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    // ---------------- 夹具 ----------------

    private static String uniq(String prefix) {
        return prefix + System.nanoTime() % 100000000L;
    }

    private Long newDept() {
        String code = uniq("V44D");
        return jdbc.queryForObject("""
                insert into sys_dept(parent_id, name, code, type, sort_no)
                values (null, ?, ?, 'CLINIC', 999) returning id
                """, Long.class, "V44模板科" + code, code);
    }

    /** 造一个带科室与角色的账号，返回 {id, Authentication} */
    private record TestUser(Long id, Authentication auth) {}

    private TestUser newUser(Long deptId, String... roleCodes) {
        String username = uniq("v44u");
        // dept_id 可为 null（4063「账号未配科室也建不了科室模板」那条用例要用），
        // 分两条 SQL 写，避免把 null 走进 setObject 的类型推断
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

    /**
     * 当次唯一的测试药：abx_level=0、antibiotic=false、药名不含任何过敏交叉词——
     * 保证套用后开单时 4012 过敏 / 4014 抗菌药分级不会先命中，
     * 否则 §⑥ 断言的就不是本车道的东西了。
     */
    private Long newDrug(boolean enabled) {
        String code = uniq("V44DG");
        return jdbc.queryForObject("""
                insert into md_drug(code, name, spec, unit, dose_form, price, stock,
                                    antibiotic, abx_level, drug_class, enabled)
                values (?, ?, '10mg*12片/盒', '盒', '片剂', 12.00, 500, false, 0, 'W', ?)
                returning id
                """, Long.class, code, "V44模板药" + code, enabled);
    }

    private Long newChargeItem(String category) {
        String code = uniq("V44CI");
        return jdbc.queryForObject("""
                insert into md_charge_item(code, name, category, unit, price, enabled)
                values (?, ?, ?, '次', 30.00, true) returning id
                """, Long.class, code, "V44模板项目" + code, category);
    }

    private Long visitedRegistration(Long deptId) {
        Patient p = new Patient();
        p.setName("V44模板患者");
        p.setSex("U");
        Long pid = patientService.register(p).getId();   // 无过敏史：不会触发 4012

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(deptId);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(9);
        s = scheduleRepository.save(s);

        Long regId = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(regId, null);
        return regId;
    }

    private TemplateLine drugLine(Long drugId, int sortNo) {
        return new TemplateLine("DRUG", drugId, 2, "口服", "bid", "1片", 3, sortNo);
    }

    /** 建一张模板并断言成功，返回 id */
    private Long createOk(Authentication auth, TemplateReq req) {
        R<Long> r = controller.create(req, auth);
        assertEquals(0, r.getCode(), "建模板应成功，实际：" + r.getMessage());
        return r.getData();
    }

    private List<Map<String, Object>> listOf(Authentication auth, boolean includeDisabled) {
        R<List<Map<String, Object>>> r = controller.list(null, null, includeDisabled, auth);
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    private boolean listContains(List<Map<String, Object>> rows, Long id) {
        return rows.stream().anyMatch(m -> ((Number) m.get("id")).longValue() == id);
    }

    // ================= ① CRUD + 停用/启用 =================

    /**
     * 建 → 改（改名 + 换明细）→ 停用 → 启用 的整条链，全部落库可验。
     *
     * <p><b>emr_template 的教训在此兑现</b>：v42 才发现它只有 POST/GET、连 enabled 列都没有，
     * 「编辑模板」和「停用模板」这两件每天要做的事一年来只能靠"复制为新模板"绕。
     * 本车道建表即带 enabled、PUT 与 disable/enable 同批交付；本用例就是那个不留尾巴的证据。
     */
    @Test
    void crudAndDisableEnable() {
        Long deptId = newDept();
        TestUser doc = newUser(deptId, "DOCTOR_OUTP");
        Long d1 = newDrug(true);
        Long d2 = newDrug(true);

        Long id = createOk(doc.auth(), new TemplateReq("V44上感一号", "PERSONAL", null, "RX",
                "个人常用", List.of(drugLine(d1, 0))));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from rx_template_line where template_id = ?", Integer.class, id));
        assertTrue(jdbc.queryForObject("select enabled from rx_template where id = ?", Boolean.class, id),
                "建表即带 enabled，新模板默认启用");

        // 改：名称 + 明细整组替换
        R<Void> upd = controller.update(id, new TemplateReq("V44上感一号(改)", null, null, null,
                "改过了", List.of(drugLine(d1, 0), drugLine(d2, 1))), doc.auth());
        assertEquals(0, upd.getCode(), upd.getMessage());
        assertEquals("V44上感一号(改)",
                jdbc.queryForObject("select name from rx_template where id = ?", String.class, id));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from rx_template_line where template_id = ?", Integer.class, id));

        // 停用：软开关，明细不删；且不再出现在"可套用"列表里
        assertEquals(0, controller.disable(id, doc.auth()).getCode());
        assertFalse(jdbc.queryForObject("select enabled from rx_template where id = ?", Boolean.class, id));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from rx_template_line where template_id = ?", Integer.class, id),
                "停用是软开关，明细必须留着——历史处方要能解释当时照的是哪张模板");
        assertFalse(listContains(listOf(doc.auth(), false), id), "停用后不应再出现在套用列表");
        assertTrue(listContains(listOf(doc.auth(), true), id), "维护页 includeDisabled=true 仍要看得到");

        // 启用
        assertEquals(0, controller.enable(id, doc.auth()).getCode());
        assertTrue(listContains(listOf(doc.auth(), false), id));
    }

    /** 停用后按 id 直接取明细也要拒（否则"停用"只是列表里藏起来，套用照样能绕过去） */
    @Test
    void disabledTemplateCannotBeApplied() {
        Long deptId = newDept();
        TestUser doc = newUser(deptId, "DOCTOR_OUTP");
        Long id = createOk(doc.auth(), new TemplateReq("V44停用模板", "PERSONAL", null, "RX", null,
                List.of(drugLine(newDrug(true), 0))));
        assertEquals(0, controller.disable(id, doc.auth()).getCode());

        R<List<Map<String, Object>>> r = controller.lines(id, false, doc.auth());
        assertEquals(4060, r.getCode(), "已停用模板不可套用");
        // 维护页查看（forEdit=true）仍可看，否则停用后连改都改不了
        assertEquals(0, controller.lines(id, true, doc.auth()).getCode());
    }

    /** 删除：模板与明细一并清（on delete cascade） */
    @Test
    void deleteCascadesLines() {
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        Long id = createOk(doc.auth(), new TemplateReq("V44待删模板", "PERSONAL", null, "RX", null,
                List.of(drugLine(newDrug(true), 0))));
        assertEquals(0, controller.remove(id, doc.auth()).getCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from rx_template where id = ?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from rx_template_line where template_id = ?", Integer.class, id));
    }

    // ================= ② 三级作用范围：可见性 =================

    /**
     * 个人只有本人可见（ADMIN 也不行）；科室本科室可见；全院人人可见。
     *
     * <p>ADMIN 刻意<b>看不到</b>别人的个人模板：那是医生自己的用药习惯草稿，
     * 「全院模板仅 ADMIN 可改」保护的是全院口径，不是给管理员一把翻看私人草稿的钥匙。
     */
    @Test
    void visibilityAcrossThreeScopes() {
        Long deptA = newDept();
        Long deptB = newDept();
        TestUser docA1 = newUser(deptA, "DOCTOR_OUTP");
        TestUser docA2 = newUser(deptA, "DOCTOR_OUTP");
        TestUser docB = newUser(deptB, "DOCTOR_OUTP");
        TestUser admin = newUser(deptB, "ADMIN");
        Long drug = newDrug(true);

        Long personal = createOk(docA1.auth(), new TemplateReq("V44个人模板", "PERSONAL", null, "RX", null,
                List.of(drugLine(drug, 0))));
        Long dept = createOk(docA1.auth(), new TemplateReq("V44科室模板", "DEPT", deptA, "RX", null,
                List.of(drugLine(drug, 0))));
        Long hospital = createOk(admin.auth(), new TemplateReq("V44全院模板", "HOSPITAL", null, "RX", null,
                List.of(drugLine(drug, 0))));

        var seenByA1 = listOf(docA1.auth(), false);
        assertTrue(listContains(seenByA1, personal));
        assertTrue(listContains(seenByA1, dept));
        assertTrue(listContains(seenByA1, hospital));

        var seenByA2 = listOf(docA2.auth(), false);
        assertFalse(listContains(seenByA2, personal), "别人的个人模板不可见");
        assertTrue(listContains(seenByA2, dept), "同科室医生可见科室模板");
        assertTrue(listContains(seenByA2, hospital));

        var seenByB = listOf(docB.auth(), false);
        assertFalse(listContains(seenByB, personal));
        assertFalse(listContains(seenByB, dept), "他科医生不可见本科室模板");
        assertTrue(listContains(seenByB, hospital));

        var seenByAdmin = listOf(admin.auth(), false);
        assertFalse(listContains(seenByAdmin, personal), "ADMIN 也看不到别人的个人模板");
        assertTrue(listContains(seenByAdmin, dept), "ADMIN 要能维护各科室模板，故可见");

        // 列表返回的 editable 标志必须与后端实际可改性一致（前端据此显隐按钮，不许摆死按钮）
        assertEquals(Boolean.TRUE, rowOf(seenByA1, dept).get("editable"), "创建者可改科室模板");
        assertEquals(Boolean.FALSE, rowOf(seenByA2, dept).get("editable"), "同科室非创建者不可改");
        assertEquals(Boolean.FALSE, rowOf(seenByA1, hospital).get("editable"), "普通医生不可改全院模板");
        assertEquals(Boolean.TRUE, rowOf(seenByAdmin, hospital).get("editable"));
    }

    private Map<String, Object> rowOf(List<Map<String, Object>> rows, Long id) {
        return rows.stream().filter(m -> ((Number) m.get("id")).longValue() == id).findFirst()
                .orElseThrow(() -> new AssertionError("列表里没有模板 #" + id));
    }

    /** 取明细同样受可见性约束：他人的个人模板按 id 直取也拿不到（不能靠前端不显示来"保密"） */
    @Test
    void othersPersonalTemplateLinesRejected() {
        Long deptA = newDept();
        TestUser docA1 = newUser(deptA, "DOCTOR_OUTP");
        TestUser docA2 = newUser(deptA, "DOCTOR_OUTP");
        Long id = createOk(docA1.auth(), new TemplateReq("V44私有模板", "PERSONAL", null, "RX", null,
                List.of(drugLine(newDrug(true), 0))));
        assertEquals(4060, controller.lines(id, false, docA2.auth()).getCode());
    }

    // ================= ③ 三级作用范围：可改性（越权路径） =================

    @Test
    void deptTemplateNotEditableByOtherDoctorInSameDept() {
        Long deptA = newDept();
        TestUser owner = newUser(deptA, "DOCTOR_OUTP");
        TestUser peer = newUser(deptA, "DOCTOR_OUTP");
        Long id = createOk(owner.auth(), new TemplateReq("V44科室模板", "DEPT", deptA, "RX", null,
                List.of(drugLine(newDrug(true), 0))));

        R<Void> r = controller.update(id, new TemplateReq("被别人改了", null, null, null, null, null), peer.auth());
        assertEquals(4060, r.getCode(), "同科室但非创建者不可改科室模板");
        assertEquals("V44科室模板",
                jdbc.queryForObject("select name from rx_template where id = ?", String.class, id));
    }

    @Test
    void deptTemplateEditableByAdmin() {
        Long deptA = newDept();
        TestUser owner = newUser(deptA, "DOCTOR_OUTP");
        TestUser admin = newUser(newDept(), "ADMIN");
        Long id = createOk(owner.auth(), new TemplateReq("V44科室模板", "DEPT", deptA, "RX", null,
                List.of(drugLine(newDrug(true), 0))));

        R<Void> r = controller.update(id, new TemplateReq("V44科室模板(管理员改)", null, null, null, null, null),
                admin.auth());
        assertEquals(0, r.getCode(), r.getMessage());
    }

    @Test
    void hospitalTemplateNotEditableByDoctor() {
        TestUser admin = newUser(newDept(), "ADMIN");
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        Long id = createOk(admin.auth(), new TemplateReq("V44全院模板", "HOSPITAL", null, "RX", null,
                List.of(drugLine(newDrug(true), 0))));

        assertEquals(4060, controller.update(id,
                new TemplateReq("医生想改全院模板", null, null, null, null, null), doc.auth()).getCode());
        assertEquals(4060, controller.disable(id, doc.auth()).getCode(), "越权停用同样要拒");
        assertTrue(jdbc.queryForObject("select enabled from rx_template where id = ?", Boolean.class, id));
    }

    @Test
    void personalTemplateNotEditableByAdmin() {
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        TestUser admin = newUser(newDept(), "ADMIN");
        Long id = createOk(doc.auth(), new TemplateReq("V44个人模板", "PERSONAL", null, "RX", null,
                List.of(drugLine(newDrug(true), 0))));
        assertEquals(4060, controller.update(id,
                new TemplateReq("管理员想改个人模板", null, null, null, null, null), admin.auth()).getCode());
    }

    @Test
    void doctorCannotCreateHospitalTemplate() {
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        R<Long> r = controller.create(new TemplateReq("V44越权全院", "HOSPITAL", null, "RX", null,
                List.of(drugLine(newDrug(true), 0))), doc.auth());
        assertEquals(4060, r.getCode(), "全院模板只能由 ADMIN 建");
    }

    @Test
    void doctorCannotCreateDeptTemplateForOtherDept() {
        Long deptA = newDept();
        Long deptB = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        R<Long> r = controller.create(new TemplateReq("V44越权他科", "DEPT", deptB, "RX", null,
                List.of(drugLine(newDrug(true), 0))), doc.auth());
        assertEquals(4060, r.getCode(), "只能维护本科室的科室模板");
    }

    // ================= ④ 协定处方（1000★） =================

    /**
     * 协定处方明细任何人都不可就地改（ADMIN 亦然）：4064。
     *
     * <p>要改就停用旧版另建新版——已按旧版开出的处方必须能追溯到当时那一版，
     * 就地改写会让历史处方的来源凭空变脸（与 emr_template「复制为新模板」同源的取舍）。
     * 头部信息（名称/备注）仍可改，否则连写错一个字都要重建一张。
     */
    @Test
    void agreedTemplateLinesImmutable() {
        // 刻意用「药师建的**科室级**协定处方」：全院模板按既定权限口径仅 ADMIN 可建可改，
        // 药师能自建的最大范围就是本科室——这样 admin 对本模板是**有**可改权限的，
        // 下面「ADMIN 改明细照样 4064」才证明得了 4064 不是被"没权限"顺带挡掉的。
        Long dept = newDept();
        TestUser pharm = newUser(dept, "PHARMACIST");
        TestUser admin = newUser(newDept(), "ADMIN");
        Long d1 = newDrug(true);
        Long d2 = newDrug(true);
        Long id = createOk(pharm.auth(), new TemplateReq("V44协定处方·术后镇痛", "DEPT", dept, "AGREED",
                "药事委员会 2026 第一版", List.of(drugLine(d1, 0))));
        // 前置证明：admin 对这张模板确有可改权限（改头成功），故下一步的 4064 只可能来自协定处方规则
        assertEquals(0, controller.update(id, new TemplateReq("V44协定处方·术后镇痛", null, null, null,
                "管理员改得动头部", null), admin.auth()).getCode());

        // 药师本人改明细 → 4064
        assertEquals(4064, controller.update(id, new TemplateReq("V44协定处方·术后镇痛", null, null, null,
                null, List.of(drugLine(d1, 0), drugLine(d2, 1))), pharm.auth()).getCode());
        // ADMIN 改明细同样 4064（"使用者不可改"不是靠权限大小豁免的）
        assertEquals(4064, controller.update(id, new TemplateReq("V44协定处方·术后镇痛", null, null, null,
                null, List.of(drugLine(d2, 0))), admin.auth()).getCode());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from rx_template_line where template_id = ?", Integer.class, id));
        assertEquals(d1.longValue(), jdbc.queryForObject(
                "select item_id from rx_template_line where template_id = ?", Long.class, id).longValue());
    }

    /** 只改头（lines 传 null）不受 4064 约束——否则协定处方连写错一个字都改不了 */
    @Test
    void agreedTemplateHeaderStillEditable() {
        TestUser admin = newUser(newDept(), "ADMIN");
        Long id = createOk(admin.auth(), new TemplateReq("V44协定处方", "HOSPITAL", null, "AGREED", null,
                List.of(drugLine(newDrug(true), 0))));
        R<Void> r = controller.update(id, new TemplateReq("V44协定处方（第二版）", null, null, null,
                "改了名与备注", null), admin.auth());
        assertEquals(0, r.getCode(), r.getMessage());
        assertEquals("V44协定处方（第二版）",
                jdbc.queryForObject("select name from rx_template where id = ?", String.class, id));
    }

    /** 协定处方套用时整组带入：每行 locked=true，前端据此禁用逐行编辑 */
    @Test
    void agreedTemplateLinesAreLockedOnRead() {
        TestUser admin = newUser(newDept(), "ADMIN");
        Long id = createOk(admin.auth(), new TemplateReq("V44协定处方", "HOSPITAL", null, "AGREED", null,
                List.of(drugLine(newDrug(true), 0), drugLine(newDrug(true), 1))));
        var lines = controller.lines(id, false, admin.auth()).getData();
        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(l -> Boolean.TRUE.equals(l.get("locked"))));
        assertEquals(Boolean.TRUE, rowOf(listOf(admin.auth(), false), id).get("linesLocked"));
    }

    /** 协定处方是药事管理动作，普通门诊医生不能建档 */
    @Test
    void doctorCannotCreateAgreedTemplate() {
        Long dept = newDept();
        TestUser doc = newUser(dept, "DOCTOR_OUTP");
        R<Long> r = controller.create(new TemplateReq("V44医生建协定", "DEPT", dept, "AGREED", null,
                List.of(drugLine(newDrug(true), 0))), doc.auth());
        assertEquals(4060, r.getCode());
    }

    // ================= ⑤ 校验：4060–4063 / 4069 =================

    @Test
    void code4060_templateNotFound() {
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        assertEquals(4060, controller.lines(99999999L, false, doc.auth()).getCode());
    }

    @Test
    void code4061_nameRequired() {
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        assertEquals(4061, controller.create(new TemplateReq("   ", "PERSONAL", null, "RX", null,
                List.of(drugLine(newDrug(true), 0))), doc.auth()).getCode());
    }

    @Test
    void code4062_linesRequired() {
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        assertEquals(4062, controller.create(new TemplateReq("V44空明细", "PERSONAL", null, "RX", null,
                List.of()), doc.auth()).getCode());
        assertEquals(4062, controller.create(new TemplateReq("V44空明细", "PERSONAL", null, "RX", null,
                null), doc.auth()).getCode());
    }

    /** 明细行不成立（项目不存在 / 类型非白名单 / 数量非正）同归 4062——纸面上都是"这张模板的明细不成立" */
    @Test
    void code4062_badLine() {
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        assertEquals(4062, controller.create(new TemplateReq("V44坏行", "PERSONAL", null, "RX", null,
                List.of(new TemplateLine("DRUG", 99999999L, 1, null, null, null, null, 0))), doc.auth()).getCode(),
                "项目不存在");
        assertEquals(4062, controller.create(new TemplateReq("V44坏行", "PERSONAL", null, "RX", null,
                List.of(new TemplateLine("XXX", newDrug(true), 1, null, null, null, null, 0))), doc.auth()).getCode(),
                "医嘱类型非白名单");
        assertEquals(4062, controller.create(new TemplateReq("V44坏行", "PERSONAL", null, "RX", null,
                List.of(new TemplateLine("DRUG", newDrug(true), 0, null, null, null, null, 0))), doc.auth()).getCode(),
                "数量非正");
    }

    @Test
    void code4063_badScope() {
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        assertEquals(4063, controller.create(new TemplateReq("V44坏范围", "WORLD", null, "RX", null,
                List.of(drugLine(newDrug(true), 0))), doc.auth()).getCode());
    }

    /** 科室模板落不到科室（既没传、账号也没配科室）→ 同属"作用范围不成立"，仍是 4063 */
    @Test
    void code4063_deptScopeWithoutDept() {
        TestUser doc = newUser(null, "DOCTOR_OUTP");
        assertEquals(4063, controller.create(new TemplateReq("V44无科室", "DEPT", null, "RX", null,
                List.of(drugLine(newDrug(true), 0))), doc.auth()).getCode());
    }

    @Test
    void code4069_badCategory() {
        TestUser doc = newUser(newDept(), "DOCTOR_OUTP");
        assertEquals(4069, controller.create(new TemplateReq("V44坏类别", "PERSONAL", null, "PROTOCOL", null,
                List.of(drugLine(newDrug(true), 0))), doc.auth()).getCode());
    }

    // ================= ⑥ 纪律用例：明细 → 开单行逐字段映射 =================

    /**
     * <b>建表层的逐字段映射</b>：{@code rx_template_line} 与 {@code outp_order} 的六个开单列
     * 必须同名、同类型、同长度。
     *
     * <p>模板存的字段名/宽度一旦与开单对不上，套用时前端就得手工转换（或悄悄截断），
     * 转换表迟早与后端漂移；这条用例把漂移拦在 CI，而不是等到医生开单时才发现。
     */
    @Test
    void templateLineColumnsMatchOrderColumns() {
        List<String> shared = List.of("order_type", "item_id", "qty",
                "usage_route", "frequency", "dose_per_time", "days");
        for (String col : shared) {
            Map<String, Object> tpl = colMeta("rx_template_line", col);
            Map<String, Object> ord = colMeta("outp_order", col);
            assertNotNull(tpl, "rx_template_line 缺列 " + col);
            assertNotNull(ord, "outp_order 缺列 " + col);
            assertEquals(ord.get("data_type"), tpl.get("data_type"), col + " 类型必须与 outp_order 一致");
            assertEquals(ord.get("character_maximum_length"), tpl.get("character_maximum_length"),
                    col + " 长度必须与 outp_order 一致");
        }
    }

    private Map<String, Object> colMeta(String table, String column) {
        var rows = jdbc.queryForList("""
                select data_type, character_maximum_length from information_schema.columns
                where table_name = ? and column_name = ?
                """, table, column);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * <b>返回体层的逐字段映射 + 端到端套用</b>：
     * 反射遍历 {@code DoctorStationService.OrderLine} 的每个记录分量，断言「取明细」返回体
     * 都有同名键；再把返回体<b>反射构造成 OrderLine</b> 原样交给<b>既有</b>开单端点开出来，
     * 逐字段比对落库的 outp_order。
     *
     * <p>这条用例同时证明了本车道的硬约束：<b>套用只是填充开单表单</b>——
     * 开的还是那条 {@code createOrders}，皮试/重复用药/抗菌药分级/CDSS/停用药预检一条没绕。
     * 用例里没有、也不许有任何"模板批量开单"的端点。
     */
    @Test
    void templateLinesMapFieldByFieldIntoOrderLine() throws Exception {
        Long deptId = newDept();
        TestUser doc = newUser(deptId, "DOCTOR_OUTP");
        Long drug = newDrug(true);
        Long lab = newChargeItem("LAB");
        Long id = createOk(doc.auth(), new TemplateReq("V44套用模板", "PERSONAL", null, "RX", null,
                List.of(drugLine(drug, 0), new TemplateLine("LAB", lab, 1, null, null, null, null, 1))));

        long ordersBefore = jdbc.queryForObject("select count(*) from outp_order", Long.class);
        var lines = controller.lines(id, false, doc.auth()).getData();
        assertEquals(2, lines.size());
        assertEquals(ordersBefore, jdbc.queryForObject("select count(*) from outp_order", Long.class),
                "「列模板/取明细」是纯只读，一行 outp_order 都不该产生");

        // —— 反射断言：OrderLine 的**核心开单分量**在返回体里都有同名键，并原样构造出 OrderLine ——
        //
        // 为什么只断言核心 7 分量、而不是 OrderLine 的全部分量：
        // v44 合版把 OrderLine 从 7 扩到 14（remark/urgent/clinicalSummary/examPurpose/notice/
        // specimenType/samplingSite），这 7 个是**逐患者逐次填写**的，模板刻意不承载：
        //   * clinicalSummary（临床摘要）是本患者本次的病情描述，**预置即错**——
        //     把上一个患者的病情摘要带进下一张申请单，是比不填更糟的数据事故；
        //   * urgent/specimenType/samplingSite/examPurpose/notice 预置**有一定价值**
        //     （检验模板预设标本类型确实省事），但那要给 rx_template_line 加列并改前端，
        //     属独立增量，列为 v45 候选；本版不做，也不在此假装已做。
        // 本用例保护的是「取明细返回体能直接喂给 createOrders、前端无需转换表」这条契约——
        // 核心 7 分量就是这条契约的全部内容；新分量由前端传 null、医生按患者填写。
        Set<String> CORE = Set.of("orderType", "itemId", "qty",
                "usageRoute", "frequency", "dosePerTime", "days");
        RecordComponent[] comps = DoctorStationService.OrderLine.class.getRecordComponents();
        assertTrue(Arrays.stream(comps).map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet())
                        .containsAll(CORE),
                "OrderLine 的核心开单分量被改名或删除了——模板套用契约会断，请同步本用例与 rx_template_line");
        // 必须按分量数精确取**规范构造器**：v44 给 OrderLine 加了 7 参兼容构造器
        // （保 78 个既有调用点不改），getDeclaredConstructors()[0] 可能取到那个短的。
        var ctor = Arrays.stream(DoctorStationService.OrderLine.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == comps.length)
                .findFirst().orElseThrow(() -> new AssertionError("找不到 OrderLine 的规范构造器"));
        List<DoctorStationService.OrderLine> orderLines = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                RecordComponent c = comps[i];
                if (!CORE.contains(c.getName())) {
                    // v44 新分量：模板不承载，构造 OrderLine 时留 null（见上方说明）
                    args[i] = null;
                    continue;
                }
                assertTrue(line.containsKey(c.getName()),
                        "取明细返回体缺少 OrderLine 核心分量「" + c.getName() + "」——套用时前端就得手工转换了");
                Object v = line.get(c.getName());
                if (v instanceof Number n) {
                    v = c.getType() == Long.class ? (Object) n.longValue() : (Object) n.intValue();
                }
                assertTrue(v == null || c.getType().isInstance(v),
                        c.getName() + " 类型与 OrderLine 分量不兼容：" + v);
                args[i] = v;
            }
            orderLines.add((DoctorStationService.OrderLine) ctor.newInstance(args));
        }

        // —— 端到端：把模板行原样交给既有开单端点 ——
        Long regId = visitedRegistration(deptId);
        var created = doctorStationService.createOrders(regId, orderLines, doc.id());
        assertEquals(2, created.size());
        var rx = created.stream().filter(o -> "DRUG".equals(o.getOrderType())).findFirst().orElseThrow();
        assertEquals(drug, rx.getItemId());
        assertEquals(2, rx.getQty());
        assertEquals("口服", rx.getUsageRoute());
        assertEquals("bid", rx.getFrequency());
        assertEquals("1片", rx.getDosePerTime());
        assertEquals(3, rx.getDays());
        assertTrue(created.stream().anyMatch(o -> "LAB".equals(o.getOrderType()) && lab.equals(o.getItemId())));
    }

    /**
     * <b>模板不是绕过用药安全的后门</b>：模板里的药事后被停用，套用后开单照样撞 8016。
     *
     * <p>模板侧刻意<b>不</b>在保存时拦"已停用"——那既救不了"存进模板之后才被停用"的绝大多数情形，
     * 又会让医生连一张只是暂时缺药的老模板都改不动。真正的拦截点始终在开单端点。
     */
    @Test
    void appliedTemplateStillHitsDisabledDrugGuard() {
        Long deptId = newDept();
        TestUser doc = newUser(deptId, "DOCTOR_OUTP");
        Long drug = newDrug(true);
        Long id = createOk(doc.auth(), new TemplateReq("V44停用药模板", "PERSONAL", null, "RX", null,
                List.of(drugLine(drug, 0))));

        // 模板建好之后药品才被停用（真实场景）
        jdbc.update("update md_drug set enabled = false, disable_reason = '召回' where id = ?", drug);
        entityManager.flush();
        entityManager.clear();

        var lines = controller.lines(id, false, doc.auth()).getData();
        assertEquals(Boolean.FALSE, lines.get(0).get("itemEnabled"), "读侧要提前提示已停用，避免填完才被打回");

        Long regId = visitedRegistration(deptId);
        var ol = new DoctorStationService.OrderLine("DRUG", drug, 2, "口服", "bid", "1片", 3);
        BizException ex = assertThrows(BizException.class,
                () -> doctorStationService.createOrders(regId, List.of(ol), doc.id()));
        assertEquals(8016, ex.code, "套用模板不得绕过 v43 停用药预检");
    }

    /** 模板里的重复用药同样由既有 4013 拦——模板不会让重复医嘱变得合法 */
    @Test
    void appliedTemplateStillHitsDuplicateDrugGuard() {
        Long deptId = newDept();
        TestUser doc = newUser(deptId, "DOCTOR_OUTP");
        Long drug = newDrug(true);
        createOk(doc.auth(), new TemplateReq("V44重复行模板", "PERSONAL", null, "RX", null,
                List.of(drugLine(drug, 0), drugLine(drug, 1))));

        Long regId = visitedRegistration(deptId);
        var ol = new DoctorStationService.OrderLine("DRUG", drug, 2, "口服", "bid", "1片", 3);
        BizException ex = assertThrows(BizException.class,
                () -> doctorStationService.createOrders(regId, List.of(ol, ol), doc.id()));
        assertEquals(4013, ex.code);
    }
}
