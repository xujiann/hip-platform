package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.DoctorStationController.SaveEmrRequest;
import cn.hip.outpatient.web.EmrRefController;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v45 车道J：临床资料引用带出（992★/1092★）+ 跨患者复制粘贴管控（1082★）。
 *
 * <p><b>本类的头号价值是 §③ 只读证明</b>：{@link #referenceEndpointIsStrictlyReadOnly()}
 * 把「引用带出不写任何东西」钉死——四种 kind 各调一遍前后，{@code outp_emr} 与
 * {@code inp_medical_record} 的<b>行数与全文逐字不变</b>。992/1092 是"抄一段给医生看"，
 * 它一旦顺手写病历就从便利功能变成数据污染源；这条用例是那道防线。
 *
 * <p>§① 四种 kind 的返回体齐全（两份：可插入正文的 text + 原始结构化 raw）；
 * §② 限条数与 truncated 生效（LAB/EXAM 限 200、HISTORY 限 20）；
 * §④ {@code emr.copy.cross_patient} 默认 <b>warn</b> 而非 block；
 * §⑤ 越权：查非本人接诊的就诊被拒（<b>4036</b>）。
 *
 * <p><b>码位说明</b>：任务书原本给本车道预留 4029，但车道 I 已把 4029 用在
 * {@code DoctorStationService.saveEmr}（结构化内容渲染进现病史超长）。4024–4029 整段已被车道 I
 * 占满，故本车道改用门诊段里实测空置的 <b>4036</b>（4030–4039 是 v44 诊断段，实测只用掉
 * 4033/4034/4035，4036–4039 空置）。合版时请在 {@code docs/错误码分段.md} 登记。
 *
 * <p><b>本车道刻意没有写路径用例</b>——因为本车道没有写路径。1082 的管控是
 * <b>前端行为 + 配置开关</b>，block 档也只在前端拒绝粘贴动作，<b>保存端点一行未改</b>，
 * 所以后端能测的只有"配置读得对不对"，不存在"保存时被拦住"这种用例。
 * 谁将来把这个 gate 挂到写路径上，请先改这段注释再改代码。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V45EmrRefTest {

    @Autowired EmrRefController emrRefController;
    @Autowired cn.hip.outpatient.web.DoctorStationController doctorStationController;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    /** 每种 kind 的返回体必备键（顺序无关，但一个都不能少） */
    private static final Set<String> ENVELOPE_KEYS = Set.of(
            "kind", "patientId", "registrationId", "admissionId",
            "limit", "truncated", "count", "snippet", "items");

    @AfterEach
    void evict() {
        configReader.evictAll();
    }

    // ==================== 夹具 ====================

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        // 刻意**不给** ROLE_ADMIN/ROLE_QUALITY：横切角色会跳过对象级归属校验，测不出越权
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    private Long userId(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private Long newPatient() {
        Patient p = new Patient();
        p.setName("v45引用" + System.nanoTime());
        p.setSex("M");
        p.setBirthDate(LocalDate.of(1980, 5, 1));
        p.setAllergyHistory("青霉素");
        p.setBloodType("O");
        return patientService.register(p).getId();
    }

    /** 建一次门诊就诊；doctorId 为空则归属不采集（对象级校验放行） */
    private Long visitFor(Long patientId, Long doctorId) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(50);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(patientId, s.getId()).getId();
        doctorStationService.startVisit(rid, doctorId);
        return rid;
    }

    private void writeEmr(Long registrationId, String chiefComplaint, Authentication auth) {
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint(chiefComplaint);
        e.setPresentIllness("受凉后起病 3 天，无咯血");
        e.setPastHistory("高血压 5 年");
        e.setAdvice("对症治疗，3 日后复诊");
        var r = doctorStationController.saveEmr(registrationId, new SaveEmrRequest(e, List.of()), auth);
        assertEquals(0, r.getCode(), r.getMessage());
    }

    /** 直接落一条门诊医嘱（引用段的挂载点），返回 order id */
    private Long order(Long registrationId, String type, String itemName) {
        jdbc.update("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code,
                                       item_name, unit, qty, unit_price, amount)
                values (?, ?, ?, 1, 'X', ?, '次', 1, 10.00, 10.00)
                """, registrationId, "G" + System.nanoTime(), type, itemName);
        return jdbc.queryForObject("select max(id) from outp_order where registration_id = ?",
                Long.class, registrationId);
    }

    private void labResult(Long orderId, String item, String value, String abnormalFlag) {
        jdbc.update("""
                insert into outp_lab_result(order_id, item_code, item_name, result_value, unit,
                                            ref_range, abnormal_flag)
                values (?, 'WBC', ?, ?, '10^9/L', '4.0-10.0', ?)
                """, orderId, item, value, abnormalFlag);
    }

    private void risExam(Long orderId, String findings, String impression) {
        jdbc.update("""
                insert into ris_exam(order_id, status, findings, impression, reported_at)
                values (?, 'REPORTED', ?, ?, now())
                """, orderId, findings, impression);
    }

    private void orderReport(Long orderId, String text) {
        jdbc.update("insert into outp_order_report(order_id, result_text) values (?, ?)", orderId, text);
    }

    private Long admit(Long patientId, Long doctorId) {
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(patientId, 1L, bedId, doctorId, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
    }

    private void inpRecord(Long admissionId, String type, String title, String content) {
        jdbc.update("insert into inp_medical_record(admission_id, record_type, title, content) "
                + "values (?,?,?,?)", admissionId, type, title, content);
    }

    private Map<String, Object> ref(Long registrationId, String kind, Authentication auth) {
        var r = emrRefController.ref(registrationId, null, kind, auth);
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> seg) {
        return (List<Map<String, Object>>) seg.get("items");
    }

    private static String texts(Map<String, Object> seg) {
        return String.join("\n", items(seg).stream().map(i -> String.valueOf(i.get("text"))).toList());
    }

    /** 每条 item 必须<b>两份都给</b>：可插入正文的 text + 原始结构化 raw */
    private static void assertTwoForms(Map<String, Object> seg) {
        for (var it : items(seg)) {
            assertNotNull(it.get("refId"), "item 缺 refId");
            assertNotNull(it.get("title"), "item 缺 title");
            String text = String.valueOf(it.get("text"));
            assertFalse(text.isBlank(), "item 的可插入文本片段不能为空：" + it);
            assertInstanceOf(Map.class, it.get("raw"), "item 缺原始结构化数据 raw：" + it);
        }
    }

    private static void assertEnvelope(Map<String, Object> seg, String kind) {
        assertTrue(seg.keySet().containsAll(ENVELOPE_KEYS),
                "返回体缺键：" + ENVELOPE_KEYS.stream().filter(k -> !seg.containsKey(k)).toList());
        assertEquals(kind, seg.get("kind"));
        assertEquals(items(seg).size(), seg.get("count"));
        assertTwoForms(seg);
    }

    // ==================== ① 四种 kind 的返回体齐全 ====================

    /**
     * BASIC（1092★ 新建病历自动带出）：姓名/性别/年龄/门诊号/过敏史齐全，
     * 且额外给一份 {@code prefill} 供前端直接预填表单（医生可改，不锁死）。
     *
     * <p><b>刻意断言不含身份证号与手机号</b>：引用带出是写病历用的，不是患者档案导出口。
     */
    @Test
    @SuppressWarnings("unchecked")
    void basicKindCarriesPrefillAndNoIdentityLeak() {
        Authentication doc = doctorAuth("v45doc_basic");
        Long pid = newPatient();
        Long rid = visitFor(pid, userId("v45doc_basic"));

        var seg = ref(rid, "BASIC", doc);
        assertEnvelope(seg, "BASIC");
        assertEquals(pid, seg.get("patientId"));
        assertEquals(rid, seg.get("registrationId"));
        assertNull(seg.get("admissionId"));
        assertFalse((Boolean) seg.get("truncated"));

        String all = texts(seg);
        assertTrue(all.contains("姓名："), "缺姓名：" + all);
        assertTrue(all.contains("性别：男"), "缺性别：" + all);
        assertTrue(all.contains("年龄："), "缺年龄：" + all);
        assertTrue(all.contains("过敏史：青霉素"), "缺过敏史：" + all);
        assertTrue(all.contains("门诊号："), "缺门诊号：" + all);
        assertTrue(String.valueOf(seg.get("snippet")).startsWith("【基本资料】"), "段级片段缺标题");

        var prefill = (Map<String, Object>) seg.get("prefill");
        assertNotNull(prefill, "1092 自动带出必须给 prefill");
        assertEquals(pid, prefill.get("patientId"));
        assertNotNull(prefill.get("patientName"));
        assertNotNull(prefill.get("patientNo"));
        // 年龄按业务日算，不写死数字（写死会在明年过年时无声变红）
        assertEquals(PatientService.ageOf(LocalDate.of(1980, 5, 1)), prefill.get("age"));
        assertEquals("青霉素", prefill.get("allergyHistory"));

        // 隐私边界：证件号/手机号一律不进引用带出
        assertFalse(prefill.containsKey("idNo") || prefill.containsKey("phone"),
                "引用带出不得携带证件号/手机号：" + prefill.keySet());
        assertFalse(all.contains("身份证") || all.contains("手机"), "引用文本不得携带证件号/手机号");
    }

    /** LAB：结构化检验结果与医技文本结果各出一条，异常标记透传 */
    @Test
    void labKindCarriesStructuredResultAndAbnormalFlag() {
        Authentication doc = doctorAuth("v45doc_lab");
        Long pid = newPatient();
        Long rid = visitFor(pid, userId("v45doc_lab"));
        Long labOrder = order(rid, "LAB", "血常规");
        labResult(labOrder, "白细胞", "12.30", "H");
        labResult(labOrder, "血红蛋白", "138", null);
        Long labOrder2 = order(rid, "LAB", "尿常规");
        orderReport(labOrder2, "尿蛋白阴性，镜检未见异常");

        var seg = ref(rid, "LAB", doc);
        assertEnvelope(seg, "LAB");
        assertEquals(EmrRefController.ROW_LIMIT, seg.get("limit"));
        assertEquals(3, seg.get("count"));

        String all = texts(seg);
        assertTrue(all.contains("白细胞 12.30 10^9/L（参考 4.0-10.0）［异常 H］"), "异常标记未透传：" + all);
        assertTrue(all.contains("血红蛋白 138"), "正常项缺失：" + all);
        assertTrue(all.contains("尿常规：尿蛋白阴性"), "医技文本结果缺失：" + all);

        var abnormal = items(seg).stream().filter(i -> Boolean.TRUE.equals(i.get("abnormal"))).toList();
        assertEquals(1, abnormal.size(), "只有白细胞这一条带 abnormal_flag");
        assertTrue(items(seg).stream().allMatch(i -> Boolean.TRUE.equals(i.get("currentVisit"))),
                "本次就诊开的单，currentVisit 应为 true");
    }

    /** EXAM：RIS 报告的所见/印象 + 医技文本结果 */
    @Test
    void examKindCarriesFindingsAndImpression() {
        Authentication doc = doctorAuth("v45doc_exam");
        Long pid = newPatient();
        Long rid = visitFor(pid, userId("v45doc_exam"));
        Long examOrder = order(rid, "EXAM", "胸部正位片");
        risExam(examOrder, "右下肺片状密度增高影", "考虑肺炎");
        Long examOrder2 = order(rid, "EXAM", "腹部超声");
        orderReport(examOrder2, "肝胆胰脾未见明显异常");

        var seg = ref(rid, "EXAM", doc);
        assertEnvelope(seg, "EXAM");
        assertEquals(2, seg.get("count"));
        String all = texts(seg);
        assertTrue(all.contains("所见：右下肺片状密度增高影"), "缺所见：" + all);
        assertTrue(all.contains("印象：考虑肺炎"), "缺印象：" + all);
        assertTrue(all.contains("腹部超声：肝胆胰脾未见明显异常"), "缺医技文本结果：" + all);
    }

    /**
     * HISTORY：门诊既往病历 + 住院病历合并倒序；<b>剔除本次就诊自身</b>
     * （把正在写的这份再引用进来是自我复制）。
     */
    @Test
    void historyKindMergesOutpAndInpAndExcludesCurrentEncounter() {
        Authentication doc = doctorAuth("v45doc_hist");
        Long docId = userId("v45doc_hist");
        Long pid = newPatient();

        Long past = visitFor(pid, docId);
        writeEmr(past, "既往主诉·发热", doc);
        Long admId = admit(pid, docId);
        inpRecord(admId, "ADMISSION", "入院记录", "因肺炎收住入院");
        Long current = visitFor(pid, docId);
        writeEmr(current, "本次主诉·咳嗽", doc);
        em.flush();

        var seg = ref(current, "HISTORY", doc);
        assertEnvelope(seg, "HISTORY");
        assertEquals(EmrRefController.HISTORY_LIMIT, seg.get("limit"));

        String all = texts(seg);
        assertTrue(all.contains("既往主诉·发热"), "缺门诊既往病历：" + all);
        assertTrue(all.contains("因肺炎收住入院"), "缺住院病历：" + all);
        assertFalse(all.contains("本次主诉·咳嗽"), "本次就诊自身不得出现在引用结果里：" + all);

        var sources = items(seg).stream().map(i -> String.valueOf(i.get("source"))).toList();
        assertTrue(sources.contains("OUTP") && sources.contains("INP"), "门诊/住院两侧都要有：" + sources);
    }

    /** 住院侧同款接入：以 admissionId 定位就诊，返回体与门诊侧同形 */
    @Test
    void inpatientSideUsesAdmissionIdAndReturnsSameShape() {
        Authentication doc = doctorAuth("v45doc_inp");
        Long docId = userId("v45doc_inp");
        Long pid = newPatient();
        Long past = visitFor(pid, docId);
        writeEmr(past, "住院前门诊主诉", doc);
        Long admId = admit(pid, docId);
        inpRecord(admId, "PROGRESS", "病程记录", "体温渐降");
        em.flush();

        var basic = emrRefController.ref(null, admId, "BASIC", doc).getData();
        assertEnvelope(basic, "BASIC");
        assertEquals(admId, basic.get("admissionId"));
        assertNull(basic.get("registrationId"));
        assertTrue(texts(basic).contains("住院号："), "住院侧 BASIC 应带住院号：" + texts(basic));

        var hist = emrRefController.ref(null, admId, "HISTORY", doc).getData();
        assertEnvelope(hist, "HISTORY");
        assertTrue(texts(hist).contains("住院前门诊主诉"), "住院侧应引用得到门诊既往病历");
        assertFalse(texts(hist).contains("体温渐降"), "本次住院自身的记录不得出现在引用结果里");
    }

    /** 参数校验：kind 非法 / 两个 id 都传或都不传 → 4000（不额外占业务码） */
    @Test
    void badParamsRejected() {
        Authentication doc = doctorAuth("v45doc_param");
        Long pid = newPatient();
        Long rid = visitFor(pid, userId("v45doc_param"));
        assertEquals(4000, emrRefController.ref(rid, null, "MULTIMEDIA", doc).getCode());
        assertEquals(4000, emrRefController.ref(null, null, "BASIC", doc).getCode());
        assertEquals(4000, emrRefController.ref(rid, 1L, "BASIC", doc).getCode());
        assertEquals(4000, emrRefController.ref(-1L, null, "BASIC", doc).getCode(), "挂号不存在");
    }

    // ==================== ② 限条数与 truncated ====================

    /**
     * LAB 限 {@value EmrRefController#ROW_LIMIT} 条 + truncated 标记（照抄 mr-workqueue 纪律：
     * 硬限 + 标记，不做翻页）。造 {@code ROW_LIMIT + 5} 条明细，断言只回 200 条且 truncated=true。
     */
    @Test
    void labRowLimitAndTruncatedFlag() {
        Authentication doc = doctorAuth("v45doc_limit");
        Long pid = newPatient();
        Long rid = visitFor(pid, userId("v45doc_limit"));
        Long labOrder = order(rid, "LAB", "生化全套");
        for (int i = 0; i < EmrRefController.ROW_LIMIT + 5; i++) {
            labResult(labOrder, "项目" + i, String.valueOf(i), null);
        }

        var seg = ref(rid, "LAB", doc);
        assertEquals(EmrRefController.ROW_LIMIT, seg.get("count"), "超上限只回 200 条");
        assertEquals(Boolean.TRUE, seg.get("truncated"), "超上限须置 truncated");
        assertEquals(EmrRefController.ROW_LIMIT, items(seg).size());
    }

    /** HISTORY 限最近 {@value EmrRefController#HISTORY_LIMIT} 次 */
    @Test
    void historyLimitedToRecentVisits() {
        Authentication doc = doctorAuth("v45doc_histlimit");
        Long docId = userId("v45doc_histlimit");
        Long pid = newPatient();
        for (int i = 0; i < EmrRefController.HISTORY_LIMIT + 3; i++) {
            Long rid = visitFor(pid, docId);
            writeEmr(rid, "第" + i + "次主诉", doc);
        }
        Long current = visitFor(pid, docId);
        em.flush();

        var seg = ref(current, "HISTORY", doc);
        assertEquals(EmrRefController.HISTORY_LIMIT, seg.get("count"), "历史病历最多回 20 次");
        assertEquals(Boolean.TRUE, seg.get("truncated"));
    }

    // ==================== ③ 只读证明（本类最重要的一条） ====================

    /**
     * <b>引用端点是纯只读的。</b>四种 kind（门诊侧 + 住院侧）各调一遍，前后对比
     * {@code outp_emr} 与 {@code inp_medical_record} 的<b>行数与全文快照</b>——一个字节都不许变。
     *
     * <p>为什么这条最重要：992/1092 的语义是"把已有资料抄一段给医生看"。一旦引用端点
     * 顺手写了病历（比如"帮医生把带出的基本信息存进去"），法定病历里就会出现
     * <b>医生没写过、也没签过名的内容</b>。这不是便利，是数据污染。
     */
    @Test
    void referenceEndpointIsStrictlyReadOnly() {
        Authentication doc = doctorAuth("v45doc_ro");
        Long docId = userId("v45doc_ro");
        Long pid = newPatient();
        Long past = visitFor(pid, docId);
        writeEmr(past, "只读证明·既往", doc);
        Long rid = visitFor(pid, docId);
        writeEmr(rid, "只读证明·本次", doc);
        Long labOrder = order(rid, "LAB", "血常规");
        labResult(labOrder, "白细胞", "9.9", null);
        Long examOrder = order(rid, "EXAM", "胸片");
        risExam(examOrder, "未见异常", "未见异常");
        Long admId = admit(pid, docId);
        inpRecord(admId, "ADMISSION", "入院记录", "只读证明·住院");
        em.flush();

        String outpBefore = emrSnapshot();
        String inpBefore = recordSnapshot();
        long outpRowsBefore = count("select count(*) from outp_emr");
        long inpRowsBefore = count("select count(*) from inp_medical_record");

        for (String kind : List.of("BASIC", "LAB", "EXAM", "HISTORY")) {
            assertEquals(0, emrRefController.ref(rid, null, kind, doc).getCode());
            assertEquals(0, emrRefController.ref(null, admId, kind, doc).getCode());
        }
        emrRefController.copyPolicy();
        em.flush();

        assertEquals(outpRowsBefore, count("select count(*) from outp_emr"), "引用端点新增/删除了门诊病历行");
        assertEquals(inpRowsBefore, count("select count(*) from inp_medical_record"),
                "引用端点新增/删除了住院病历行");
        assertEquals(outpBefore, emrSnapshot(), "引用端点改动了门诊病历正文/签名/时间戳");
        assertEquals(inpBefore, recordSnapshot(), "引用端点改动了住院病历正文/签名/时间戳");
    }

    /** 门诊病历全文快照：正文五段 + 签名 + 更新时间，任一漂移即红 */
    private String emrSnapshot() {
        return jdbc.queryForList("""
                select id, registration_id, chief_complaint, present_illness, past_history,
                       physical_exam, advice, doctor_id, signature, signed_at, updated_at
                from outp_emr order by id
                """).toString();
    }

    private String recordSnapshot() {
        return jdbc.queryForList("""
                select id, admission_id, record_type, title, content, doctor_id, signature, created_at
                from inp_medical_record order by id
                """).toString();
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    // ==================== ④ 1082★ 复制粘贴管控：默认 warn ====================

    /**
     * {@code emr.copy.cross_patient} 默认 <b>warn</b>，<b>不是 block</b>。
     *
     * <p>直接 block 会挡住合理的模板套用与同患者续写，且本平台此前从无此限制，
     * 上来就硬拦是运行时打扰（全仓 gate 纪律：默认 warn 运行时零打扰）。
     * 这条用例的意义就是防止谁"顺手把默认改严一点"。
     */
    @Test
    void copyGateDefaultsToWarnNotBlock() {
        // 本车道无迁移：键不落种子，键缺失时靠 ConfigReader 回落默认值（这正是首次上线的形态）
        jdbc.update("delete from sys_config where cfg_key = ?", EmrRefController.COPY_GATE_KEY);
        configReader.evictAll();

        var policy = emrRefController.copyPolicy().getData();
        assertEquals(EmrRefController.COPY_GATE_KEY, policy.get("key"));
        assertEquals("warn", policy.get("mode"), "默认必须是 warn");
        assertNotEquals("block", policy.get("mode"), "默认不得是 block（会挡住合理的模板套用与续写）");
        assertEquals("warn", policy.get("defaultMode"));
        assertEquals("warn", configReader.get(EmrRefController.COPY_GATE_KEY, "warn"));

        // 诚实边界必须随配置一起下发（前端直接显示，避免院方以为这是"全面防复制"）
        String note = String.valueOf(policy.get("scopeNote"));
        assertTrue(note.contains("本系统内") && note.contains("外部"),
                "必须写明只能管住本系统内的复制粘贴：" + note);
    }

    /** 三态可切换；写错值回落 warn 而不是 off——坏配置不能把管控静默关掉 */
    @Test
    void copyGateThreeStatesAndBadValueFallsBackToWarn() {
        for (String mode : List.of("off", "warn", "block")) {
            setGate(mode);
            assertEquals(mode, emrRefController.copyPolicy().getData().get("mode"));
        }
        setGate("BLOCK");   // 大小写不敏感
        assertEquals("block", emrRefController.copyPolicy().getData().get("mode"));
        setGate("是的要拦");
        assertEquals("warn", emrRefController.copyPolicy().getData().get("mode"),
                "非法值回落 warn，不得回落 off");
    }

    private void setGate(String value) {
        jdbc.update("""
                insert into sys_config(cfg_key, cfg_value) values (?, ?)
                on conflict (cfg_key) do update set cfg_value = excluded.cfg_value
                """, EmrRefController.COPY_GATE_KEY, value);
        configReader.evictAll();
    }

    // ==================== ⑤ 越权 ====================

    /**
     * 越权：查非本人接诊的就诊被拒（4036）。口径复用 {@code DiagnosisController#add}——
     * ADMIN/QUALITY 横切；仅具 DOCTOR_OUTP 的医生只能查归属本人的就诊。
     *
     * <p>引用端点一次返回该患者的既往病历正文与检验检查全文，<b>越权读取即隐私事故</b>。
     */
    @Test
    void crossDoctorReferenceRejected() {
        Authentication owner = doctorAuth("v45doc_owner");
        Authentication other = doctorAuth("v45doc_other");
        Long pid = newPatient();
        Long rid = visitFor(pid, userId("v45doc_owner"));
        writeEmr(rid, "越权用例·主诉", owner);
        em.flush();

        for (String kind : List.of("BASIC", "LAB", "EXAM", "HISTORY")) {
            var denied = emrRefController.ref(rid, null, kind, other);
            assertEquals(4036, denied.getCode(), kind + " 段未拦住越权读取");
            assertNull(denied.getData(), "被拒时不得回带任何患者数据");
            assertEquals(0, emrRefController.ref(rid, null, kind, owner).getCode(),
                    kind + " 段把本人也拦住了");
        }
    }

    /** 住院侧同款归属校验（主管医生本人可读，他人被拒） */
    @Test
    void crossDoctorInpatientReferenceRejected() {
        Authentication owner = doctorAuth("v45doc_inpowner");
        Authentication other = doctorAuth("v45doc_inpother");
        Long pid = newPatient();
        Long admId = admit(pid, userId("v45doc_inpowner"));
        inpRecord(admId, "ADMISSION", "入院记录", "越权用例·住院");
        em.flush();

        assertEquals(4036, emrRefController.ref(null, admId, "BASIC", other).getCode());
        assertEquals(0, emrRefController.ref(null, admId, "BASIC", owner).getCode());
    }

    /**
     * 归属未采集（doctor_id 为 null）时放行——不能强推一个从未采集的归属，
     * 否则误拒历史/测试数据（同 {@code DiagnosisController#add} 的口径原文）。
     */
    @Test
    void unownedEncounterIsReadable() {
        Authentication anyone = doctorAuth("v45doc_unowned");
        Long pid = newPatient();
        Long rid = visitFor(pid, null);
        assertNull(jdbc.queryForObject("select doctor_id from outp_registration where id = ?",
                Long.class, rid), "夹具前提：本次挂号未采集归属医生");
        assertEquals(0, emrRefController.ref(rid, null, "BASIC", anyone).getCode());
    }
}
