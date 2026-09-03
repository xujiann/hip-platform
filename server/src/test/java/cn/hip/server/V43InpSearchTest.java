package cn.hip.server;

import cn.hip.inpatient.entity.InpAdmission;
import cn.hip.inpatient.repository.AdmissionRepo;
import cn.hip.inpatient.repository.BedRepo;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpatientController;
import cn.hip.outpatient.web.OutpNurseStationController;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.repository.SysDeptRepository;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.repository.PatientRepository;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v43 车道D：住院患者与医嘱多维检索（2012★/2013★/2028★）+ 皮试结果回诊室（1050）。
 *
 * <p><b>本类第一条用例是契约保护，不是功能断言</b>：{@link #noParamsMeansExactlyTheOldBehaviour()}
 * 把「{@code GET /admissions} 不传任何参数 = v43 之前的旧行为」逐字段钉死。
 * 六个既有前端页面与 {@code e2elib.ensure_not_admitted} 都直接消费这个列表，
 * 给它加过滤参数是本车道风险最高的一处改动——先钉死旧行为再谈新功能。
 *
 * <p>钉死的口径有三层：
 * <ol>
 *   <li><b>行集与顺序</b>：与 {@code findByStatusOrderByIdDesc("IN_HOSPITAL")} 逐行同序；</li>
 *   <li><b>既有键的名称与顺序</b>：与本类内复刻的 {@code legacyDto}（= 控制器 toDto 的黄金副本）
 *       逐字相同——改 toDto 的键名或顺序会让本用例红；</li>
 *   <li><b>增量白名单</b>：只允许尾部追加 {@code doctorId}/{@code doctorName} 两个键
 *       （2013★ 要求列表能看见主管医生），多出第三个键即判失败。</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V43InpSearchTest {

    @Autowired InpatientController inpatientController;
    @Autowired OutpNurseStationController nurseStationController;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired AdmissionRepo admissionRepo;
    @Autowired BedRepo bedRepo;
    @Autowired PatientRepository patientRepository;
    @Autowired SysDeptRepository deptRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired cn.hip.outpatient.service.RegistrationService registrationService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;

    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    // ---------- 夹具 ----------

    /** 造账号并返回其 Authentication（mine=true 走 CurrentUserService，必须有真实 sys_user 行） */
    private Authentication userAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    private Long userId(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    /** 取 n 张空床（多病区检索用例需要跨病区的床） */
    private List<Map<String, Object>> freeBeds(int n) {
        var beds = jdbc.queryForList(
                "select id, ward_id from inp_bed where status = 'FREE' order by ward_id, id limit ?", n);
        assertEquals(n, beds.size(), "测试库空床不足 " + n + " 张");
        return beds;
    }

    private Long bedId(Map<String, Object> bed) {
        return ((Number) bed.get("id")).longValue();
    }

    private Long admit(String name, Long deptId, Long bedId, Long doctorId) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long id = inpatientService.admit(pid, deptId, bedId, doctorId, "J18.9", "肺炎",
                new BigDecimal("1000"), "CASH", null).getId();
        entityManager.flush();
        return id;
    }

    private <T> T ok(R<T> r) {
        assertEquals(0, r.getCode(), "期望成功，实际：" + r.getCode() + " " + r.getMessage());
        return r.getData();
    }

    private List<Map<String, Object>> search(Long deptId, Long wardId, String careLevel, Boolean transferred,
                                             Long doctorId, String keyword, Boolean mine, Authentication auth) {
        return ok(inpatientController.inHospital(deptId, wardId, careLevel, transferred,
                doctorId, keyword, mine, auth));
    }

    private List<Long> idsOf(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
    }

    // ---------- ① 契约保护：不传参 = 旧行为 ----------

    /**
     * v43 之前的 {@code inHospital()} 实现（黄金副本），逐字复刻自 InpatientController：
     * <pre>
     * admissionRepo.findByStatusOrderByIdDesc("IN_HOSPITAL").stream().map(this::toDto).toList()
     * </pre>
     * 复刻而不是调用控制器私有方法，是为了让「改了 toDto」这件事本身能被本用例抓到。
     */
    private Map<String, Object> legacyDto(InpAdmission a) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", a.getId());
        m.put("admissionNo", a.getAdmissionNo());
        m.put("patientId", a.getPatientId());
        patientRepository.findById(a.getPatientId()).ifPresent(p -> {
            m.put("patientNo", p.getPatientNo());
            m.put("patientName", p.getName());
            m.put("sex", p.getSex());
        });
        m.put("deptName", deptRepository.findById(a.getDeptId()).map(d -> d.getName()).orElse(""));
        m.put("wardName", deptRepository.findById(a.getWardId()).map(d -> d.getName()).orElse(""));
        m.put("bedId", a.getBedId());
        bedRepo.findById(a.getBedId()).ifPresent(b -> m.put("bedNo", b.getBedNo()));
        m.put("admitDiagName", a.getAdmitDiagName());
        m.put("dischargeDiagIcd", a.getDischargeDiagIcd());
        m.put("dischargeDiagName", a.getDischargeDiagName());
        m.put("status", a.getStatus());
        m.put("admitAt", a.getAdmitAt());
        return m;
    }

    /** ★ 本车道最重要的一条：所有参数都不传时，行集、顺序、既有键名/键序/取值必须与旧行为逐字一致 */
    @Test
    void noParamsMeansExactlyTheOldBehaviour() {
        var beds = freeBeds(2);
        userAuth("v43doc_legacy");
        admit("旧行为甲", 1L, bedId(beds.get(0)), null);                       // 无主管医生
        admit("旧行为乙", 2L, bedId(beds.get(1)), userId("v43doc_legacy"));    // 有主管医生
        entityManager.flush();
        entityManager.clear();

        var expected = admissionRepo.findByStatusOrderByIdDesc("IN_HOSPITAL").stream()
                .map(this::legacyDto).toList();
        var actual = search(null, null, null, null, null, null, null, admin);

        assertEquals(expected.size(), actual.size(), "不传参时行数必须与旧查询一致");
        assertFalse(expected.isEmpty(), "夹具应至少造出两条在院记录");

        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> exp = expected.get(i);
            var act = new LinkedHashMap<>(actual.get(i));

            var actKeys = new ArrayList<>(act.keySet());
            assertEquals(List.of("doctorId", "doctorName"),
                    actKeys.subList(actKeys.size() - 2, actKeys.size()),
                    "第 " + i + " 行：增量键只能是 doctorId/doctorName 且只能追加在尾部");
            act.remove("doctorId");
            act.remove("doctorName");

            assertEquals(new ArrayList<>(exp.keySet()), new ArrayList<>(act.keySet()),
                    "第 " + i + " 行：既有键的名称与顺序不得改变");
            assertEquals(exp, act, "第 " + i + " 行：既有键的取值不得改变");
        }
    }

    /** 显式传 false / 空串 / mine=false 同样算「无检索条件」，不得意外过滤掉任何行 */
    @Test
    void explicitEmptyParamsAreStillTheOldBehaviour() {
        admit("空条件", 1L, bedId(freeBeds(1).get(0)), null);
        entityManager.flush();

        var baseline = idsOf(search(null, null, null, null, null, null, null, admin));
        assertEquals(baseline, idsOf(search(null, null, "  ", null, null, "   ", false, admin)),
                "空白 careLevel/keyword 与 mine=false 必须等同于不传参");
    }

    // ---------- ② 各过滤条件单独与组合生效 ----------

    @Test
    void filtersByDeptWardCareLevelKeywordAndCombination() {
        var beds = freeBeds(3);
        Long a1 = admit("检索内科", 1L, bedId(beds.get(0)), null);
        Long a2 = admit("检索外科", 2L, bedId(beds.get(1)), null);
        Long a3 = admit("检索内科二", 1L, bedId(beds.get(2)), null);
        jdbc.update("update inp_admission set care_level = '一级' where id = ?", a1);
        jdbc.update("update inp_admission set care_level = '三级' where id in (?, ?)", a2, a3);
        entityManager.flush();
        entityManager.clear();

        // 科室
        var byDept = idsOf(search(1L, null, null, null, null, null, null, admin));
        assertTrue(byDept.containsAll(List.of(a1, a3)), "按科室应命中同科室两条");
        assertFalse(byDept.contains(a2), "按科室不得混入他科");

        // 病区
        Long ward1 = ((Number) beds.get(0).get("ward_id")).longValue();
        var byWard = idsOf(search(null, ward1, null, null, null, null, null, admin));
        assertTrue(byWard.contains(a1), "按病区应命中本病区患者");
        byWard.forEach(id -> assertEquals(ward1, jdbc.queryForObject(
                "select ward_id from inp_admission where id = ?", Long.class, id), "按病区不得混入他病区"));

        // 护理级别
        var byLevel = idsOf(search(null, null, "一级", null, null, null, null, admin));
        assertTrue(byLevel.contains(a1), "按护理级别应命中一级护理");
        assertFalse(byLevel.contains(a2), "按护理级别不得混入三级护理");

        // 关键词（患者姓名）
        String name1 = jdbc.queryForObject("""
                select p.name from inp_admission a join empi_patient p on p.id = a.patient_id where a.id = ?
                """, String.class, a1);
        var byName = idsOf(search(null, null, null, null, null, name1, null, admin));
        assertEquals(List.of(a1), byName, "按姓名应精确命中一条");

        // 关键词（住院号）
        String no2 = jdbc.queryForObject(
                "select admission_no from inp_admission where id = ?", String.class, a2);
        assertEquals(List.of(a2), idsOf(search(null, null, null, null, null, no2, null, admin)),
                "按住院号应精确命中一条");

        // 组合：科室 + 护理级别
        assertEquals(List.of(a1), idsOf(search(1L, null, "一级", null, null, null, null, admin)),
                "科室与护理级别组合应取交集");
        assertTrue(idsOf(search(2L, null, "一级", null, null, null, null, admin)).isEmpty(),
                "组合条件无交集时应返回空列表");
    }

    /** 是否转科：走真实转科路径落 inp_transfer_log，再按 transferred 双向过滤 */
    @Test
    void filtersByTransferred() {
        var beds = freeBeds(3);
        Long moved = admit("转过科的", 1L, bedId(beds.get(0)), null);
        Long stayed = admit("没转科的", 1L, bedId(beds.get(1)), null);
        Long targetBed = bedId(beds.get(2));
        inpatientService.transfer(moved, 2L, targetBed, "病情变化需专科处理", null);
        entityManager.flush();
        entityManager.clear();

        var transferred = idsOf(search(null, null, null, true, null, null, null, admin));
        assertTrue(transferred.contains(moved), "transferred=true 应命中转过科的患者");
        assertFalse(transferred.contains(stayed), "transferred=true 不得混入未转科患者");

        var notTransferred = idsOf(search(null, null, null, false, null, null, null, admin));
        assertTrue(notTransferred.contains(stayed), "transferred=false 应命中未转科患者");
        assertFalse(notTransferred.contains(moved), "transferred=false 不得混入转过科的患者");
    }

    // ---------- ③ 2013★ 主管医生：可写 + 我的病人 ----------

    @Test
    void mineReturnsOnlyPatientsAttendedByCurrentUser() {
        Authentication me = userAuth("v43doc_me");
        Authentication other = userAuth("v43doc_other");
        var beds = freeBeds(3);
        Long mineAdm = admit("我的病人", 1L, bedId(beds.get(0)), userId("v43doc_me"));
        Long othersAdm = admit("他的病人", 1L, bedId(beds.get(1)), userId("v43doc_other"));
        Long orphan = admit("没主管的", 1L, bedId(beds.get(2)), null);
        entityManager.flush();
        entityManager.clear();

        var mineRows = search(null, null, null, null, null, null, true, me);
        var mineIds = idsOf(mineRows);
        assertTrue(mineIds.contains(mineAdm), "mine=true 应命中本人主管的患者");
        assertFalse(mineIds.contains(othersAdm), "mine=true 不得混入他人主管的患者");
        assertFalse(mineIds.contains(orphan), "mine=true 不得混入无主管医生的患者");

        // 2013★：列表要能看见主管医生姓名（此前 doctor_id 建了表无任何读路径）
        var row = mineRows.stream().filter(r -> ((Number) r.get("id")).longValue() == mineAdm)
                .findFirst().orElseThrow();
        assertEquals(userId("v43doc_me"), row.get("doctorId"));
        assertEquals("v43doc_me医生", row.get("doctorName"), "列表行应带出主管医生姓名");

        // 换个人问同一句话，答案必须不同
        assertTrue(idsOf(search(null, null, null, null, null, null, true, other)).contains(othersAdm));
        // doctorId 显式过滤与 mine 等价
        assertEquals(idsOf(search(null, null, null, null, userId("v43doc_me"), null, null, admin)),
                idsOf(search(null, null, null, null, null, null, true, me)));
    }

    /** 主管医生可写：入院时未指定，入院后由独立端点补设，且只动 doctor_id 一列 */
    @Test
    void attendingDoctorCanBeSetAfterAdmission() {
        Authentication doc = userAuth("v43doc_set");
        Long admId = admit("补设主管", 1L, bedId(freeBeds(1).get(0)), null);
        entityManager.flush();
        var before = jdbc.queryForMap(
                "select dept_id, ward_id, bed_id, status, doctor_id from inp_admission where id = ?", admId);
        assertNull(before.get("doctor_id"), "夹具应是无主管医生的入院记录");

        assertEquals(0, inpatientController.setAttendingDoctor(admId,
                new InpatientController.AttendingDoctorReq(userId("v43doc_set"))).getCode());
        entityManager.flush();
        entityManager.clear();

        var after = jdbc.queryForMap(
                "select dept_id, ward_id, bed_id, status, doctor_id from inp_admission where id = ?", admId);
        assertEquals(userId("v43doc_set"), ((Number) after.get("doctor_id")).longValue());
        before.remove("doctor_id");
        after.remove("doctor_id");
        assertEquals(before, after, "设置主管医生不得触碰科室/病区/床位/状态");

        // 设完立刻能被 mine=true 检索到
        assertTrue(idsOf(search(null, null, null, null, null, null, true, doc)).contains(admId));
    }

    @Test
    void attendingDoctorRejectsUnknownDoctorAndUnknownAdmission() {
        Long admId = admit("主管校验", 1L, bedId(freeBeds(1).get(0)), null);
        entityManager.flush();
        assertEquals(4884, inpatientController.setAttendingDoctor(admId,
                new InpatientController.AttendingDoctorReq(null)).getCode(), "主管医生必填 → 4884");
        assertEquals(4884, inpatientController.setAttendingDoctor(admId,
                new InpatientController.AttendingDoctorReq(-1L)).getCode(), "非法 id → 4884");
        assertEquals(4884, inpatientController.setAttendingDoctor(admId,
                new InpatientController.AttendingDoctorReq(99_999_999L)).getCode(), "医生不存在 → 4884");
        assertEquals(9003, inpatientController.setAttendingDoctor(99_999_999L,
                new InpatientController.AttendingDoctorReq(userId("admin"))).getCode(),
                "住院记录不存在沿用既有 9003，不新造码");
    }

    /** 医生下拉字典：/api/system/users 是 ADMIN 专属，医生站取不到人员，故另开只读端点 */
    @Test
    void doctorDictionaryIsReadableForOrderingUi() {
        var docs = ok(inpatientController.doctors(null));
        assertFalse(docs.isEmpty(), "医生下拉字典不应为空（admin 兼任医生）");
        assertTrue(docs.get(0).containsKey("real_name"));
        assertFalse(docs.get(0).containsKey("password"), "字典不得外泄口令等账号字段");
        assertFalse(docs.get(0).containsKey("username"), "字典只回姓名/职称/科室，不回账号");
    }

    // ---------- ④ 错误码 ----------

    @Test
    void illegalCareLevelReturns4881AndOtherIllegalConditionsReturn4880() {
        assertEquals(4881, inpatientController.inHospital(null, null, "特护", null, null, null, null, admin)
                .getCode(), "护理级别取值非法 → 4881");
        assertEquals(4881, inpatientController.inHospital(null, null, "0级", null, null, null, null, admin)
                .getCode());

        assertEquals(4880, inpatientController.inHospital(0L, null, null, null, null, null, null, admin)
                .getCode(), "科室条件非法 → 4880");
        assertEquals(4880, inpatientController.inHospital(null, -3L, null, null, null, null, null, admin)
                .getCode(), "病区条件非法 → 4880");
        assertEquals(4880, inpatientController.inHospital(null, null, null, null, -1L, null, null, admin)
                .getCode(), "主管医生条件非法 → 4880");
        assertEquals(4880, inpatientController.inHospital(null, null, null, null, null,
                "x".repeat(65), null, admin).getCode(), "关键词过长 → 4880");

        // mine=true 但登录态解析不出用户（如接口机 token）
        Authentication ghost = new UsernamePasswordAuthenticationToken("查无此人_v43", null, List.of());
        assertEquals(4880, inpatientController.inHospital(null, null, null, null, null, null, true, ghost)
                .getCode(), "无法识别当前用户 → 4880");
        // mine 与 doctorId 互相矛盾
        assertEquals(4880, inpatientController.inHospital(null, null, null, null, 1L, null, true,
                userAuth("v43doc_conflict")).getCode(), "mine 与 doctorId 冲突 → 4880");
    }

    /** keyword 里的 SQL 元字符与 like 通配符都必须按字面处理（参数化 + 通配符转义） */
    @Test
    void keywordIsParameterisedAndWildcardsAreEscaped() {
        Long admId = admit("通配符", 1L, bedId(freeBeds(1).get(0)), null);
        entityManager.flush();

        // '%' 若未转义会退化成"匹配全部"
        assertTrue(idsOf(search(null, null, null, null, null, "%", null, admin)).isEmpty(),
                "% 必须按字面匹配，不得退化为匹配全部");
        assertTrue(idsOf(search(null, null, null, null, null, "_", null, admin)).isEmpty(),
                "_ 必须按字面匹配");
        // 注入尝试只会被当成普通关键词，不会报错也不会多返回
        assertTrue(idsOf(search(null, null, null, null, null, "' or 1=1 --", null, admin)).isEmpty(),
                "注入串只是查不到的关键词");
        assertTrue(idsOf(search(null, null, null, null, null, "通配符", null, admin)).contains(admId));
    }

    // ---------- ⑤ 2028★ 医嘱检索 ----------

    private Long orderFixture(String bedAwareName, String itemName) {
        var bed = freeBeds(1).get(0);
        Long admId = admit(bedAwareName, 1L, bedId(bed), null);
        jdbc.update("""
                insert into inp_order(admission_id, group_no, order_type, item_id, item_code, item_name,
                                      unit, qty, unit_price, amount, status)
                values (?, 'G43', 'DRUG', 1, 'D43', ?, '盒', 1, 1.00, 1.00, 'CREATED')
                """, admId, itemName);
        entityManager.flush();
        return admId;
    }

    @Test
    void ordersSearchHitsByBedNoPatientNameAndItemName() {
        String item = "v43检索专用注射液";
        Long admId = orderFixture("医嘱检索", item);
        var head = jdbc.queryForMap("""
                select b.bed_no, p.name as patient_name, a.ward_id
                from inp_admission a join empi_patient p on p.id = a.patient_id
                join inp_bed b on b.id = a.bed_id where a.id = ?
                """, admId);
        String bedNo = (String) head.get("bed_no");
        String patientName = (String) head.get("patient_name");

        // 按医嘱内容
        var byItem = ok(inpatientController.searchOrders(null, null, item, null, null, null, null));
        var items = itemsOf(byItem);
        assertEquals(1, items.size(), "按医嘱内容应命中一条");
        assertEquals(admId, ((Number) items.get(0).get("admission_id")).longValue());
        assertEquals(bedNo, items.get(0).get("bed_no"), "结果须带床号");
        assertEquals(patientName, items.get(0).get("patient_name"), "结果须带患者姓名");
        assertFalse((Boolean) byItem.get("truncated"));
        assertEquals(200, byItem.get("limit"));

        // 按患者姓名
        assertFalse(itemsOf(ok(inpatientController.searchOrders(
                null, patientName, null, null, null, null, null))).isEmpty(), "按患者姓名应命中");

        // 按床号（跨患者的病区级检索：不先选患者也能查）
        assertTrue(itemsOf(ok(inpatientController.searchOrders(bedNo, null, null, null, null, null, null)))
                .stream().anyMatch(r -> ((Number) r.get("admission_id")).longValue() == admId),
                "按床号应命中该床医嘱");

        // 组合 + 状态过滤
        assertEquals(1, itemsOf(ok(inpatientController.searchOrders(
                bedNo, patientName, item, null, null, "CREATED", null))).size(), "组合条件应取交集");
        assertTrue(itemsOf(ok(inpatientController.searchOrders(
                null, null, item, null, null, "EXECUTED", null))).isEmpty(), "状态过滤应生效");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    @Test
    void ordersSearchRequiresAtLeastOneConditionAndValidatesInput() {
        assertEquals(4880, inpatientController.searchOrders(null, null, null, null, null, null, null)
                .getCode(), "无任何条件 → 4880（等于全表扫描）");
        assertEquals(4880, inpatientController.searchOrders(null, null, "x", null, null, "DONE", null)
                .getCode(), "医嘱状态非法 → 4880");
        assertEquals(4880, inpatientController.searchOrders(null, "x".repeat(65), null, null, null, null, null)
                .getCode(), "条件过长 → 4880");
        assertEquals(4880, inpatientController.searchOrders(null, null, "x", 0L, null, null, null)
                .getCode(), "病区条件非法 → 4880");
    }

    /** 超限：照抄 mr-workqueue 的限 200 条 + truncated 标记纪律，不做翻页 */
    @Test
    void ordersSearchCapsAt200WithTruncatedFlag() {
        String item = "v43截断测试项目";
        Long admId = admit("截断", 1L, bedId(freeBeds(1).get(0)), null);
        jdbc.update("""
                insert into inp_order(admission_id, group_no, order_type, item_id, item_code, item_name,
                                      unit, qty, unit_price, amount, status)
                select ?, 'G43-' || g, 'TREAT', 1, 'T43', ?, '次', 1, 1.00, 1.00, 'CREATED'
                from generate_series(1, 205) g
                """, admId, item);
        entityManager.flush();

        var body = ok(inpatientController.searchOrders(null, null, item, null, null, null, null));
        assertEquals(200, itemsOf(body).size(), "结果应硬限 200 条");
        assertEquals(200, body.get("total"));
        assertEquals(Boolean.TRUE, body.get("truncated"), "超限须置 truncated 标记");
    }

    // ---------- ⑥ 1050 皮试结果回诊室 ----------

    private Long skinTestFixture(String patientName, String drug, String result) {
        Patient p = new Patient();
        p.setName(patientName + System.nanoTime());
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(1L);
        sch.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        sch.setFee(BigDecimal.ZERO);
        sch.setCapacity(9);
        sch = scheduleRepository.save(sch);
        Long regId = registrationService.register(pid, sch.getId()).getId();
        jdbc.update("insert into outp_skin_test(registration_id, drug_name, result, nurse_id, tested_at) "
                        + "values (?, ?, ?, (select id from sys_user where username = 'admin'), now())",
                regId, drug, result);
        entityManager.flush();
        return regId;
    }

    @Test
    @WithMockUser(roles = "DOCTOR_OUTP")
    void skinTestResultIsReadableByDoctorInConsultingRoom() {
        Long regId = skinTestFixture("皮试患者", "青霉素钠", "NEG");
        var rows = ok(nurseStationController.skinTestsForDoctor(regId));
        assertEquals(1, rows.size());
        var r = rows.get(0);
        assertEquals("青霉素钠", r.get("drug_name"), "返回皮试项目");
        assertEquals("NEG", r.get("result"));
        assertEquals("阴性", r.get("result_text"), "返回可直接展示的结果文案");
        assertEquals("青霉素类", r.get("category"), "返回皮试类别，医生开药时按类别判断");
        assertNotNull(r.get("tested_at"), "返回皮试时间");
        assertNotNull(r.get("nurse_name"), "返回执行人");
        assertEquals(Boolean.FALSE, r.get("positive"));
    }

    /** 只放开读、不放开写：医生仍不能登记皮试、不能改皮试结果、也拿不到全院皮试宽表 */
    @Test
    @WithMockUser(roles = "DOCTOR_OUTP")
    void doctorGetsReadOnlyAccessNotWriteAccess() {
        assertThrows(AccessDeniedException.class, () -> nurseStationController.createSkinTest(
                new OutpNurseStationController.SkinTestReq(1L, "青霉素"), null), "医生不得登记皮试");
        assertThrows(AccessDeniedException.class,
                () -> nurseStationController.recordResult(1L, "NEG", null), "医生不得录皮试结果");
        assertThrows(AccessDeniedException.class,
                () -> nurseStationController.skinTests(null), "医生不得取全院皮试宽表");
    }

    @Test
    void nurseKeepsFullSkinTestAccess() {
        Long regId = skinTestFixture("护士视角", "头孢曲松", "POS");
        assertFalse(ok(nurseStationController.skinTests(regId)).isEmpty(), "护士原有权限不受影响");
        var r = ok(nurseStationController.skinTestsForDoctor(regId)).get(0);
        assertEquals("阳性", r.get("result_text"));
        assertEquals(Boolean.TRUE, r.get("positive"));
        assertEquals("头孢类", r.get("category"));
    }
}
