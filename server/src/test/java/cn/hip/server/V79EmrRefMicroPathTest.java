package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.EmrRefController;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * v79 车道A：临床资料引用扩到微生物与病理（992★，1019★ 病理项）+ 开单自由文本长度后端校验。
 *
 * <p>§① MICRO：只取<b>标本已发布</b>的微生物培养结果，带药敏行（raw.ast 结构化 + text 可插入正文），
 * 未发布（仍是 RECEIVED）的不出现。
 * <p>§② PATH：只取 <b>report_issued_at 非空且未拒收</b>的病理标本（与患者端 / 院内「已签发」同口径），
 * 写完诊断未签发的与已拒收的都不出现；补充报告随正文带出；住院来源（inp_order_id）也取得到。
 * <p>§③ 时间区间：门诊来源按就诊日期、住院病理按签发时间，起止各一刀。
 * <p>§④ 跨患者不串 + 空态（count=0、snippet 空串、items 空）。
 * <p>§⑤ createOrders 九个自由文本列超长 → 4000「第 N 行〈字段〉超过 M 字（当前 K 字）」，
 * 零落库、组号序列不动；恰好等于上限（含生僻字代理对按一个字计）放行。
 *
 * <p>时间夹具一律相对 {@link BusinessDates#today()} 取、窗口两侧留 5 天以上余量
 * （测试时间字面量纪律：不贴零点、不贴时区边界）。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V79EmrRefMicroPathTest {

    @Autowired EmrRefController emrRefController;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    // ==================== 夹具 ====================

    private Authentication doctorAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    private Long userId(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private Long newPatient(String tag) {
        Patient p = new Patient();
        p.setName("v79" + tag + System.nanoTime() % 100000);
        p.setSex("F");
        p.setBirthDate(LocalDate.of(1975, 6, 1));
        return patientService.register(p).getId();
    }

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

    private Long order(Long registrationId, String type, String itemName) {
        jdbc.update("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code,
                                       item_name, unit, qty, unit_price, amount)
                values (?, ?, ?, 1, 'X', ?, '次', 1, 10.00, 10.00)
                """, registrationId, "G79" + System.nanoTime(), type, itemName);
        return jdbc.queryForObject("select max(id) from outp_order where registration_id = ?",
                Long.class, registrationId);
    }

    /** 一份微生物结果：标本 + 培养 + 药敏；published=false 时标本停在 RECEIVED（未发布） */
    private Long micro(Long orderId, String organism, boolean published, String... astLines) {
        jdbc.update("""
                insert into lis_sample(order_id, barcode, status, received_at, published_at)
                values (?, ?, ?, now(), case when ? then now() end)
                """, orderId, "BC79" + System.nanoTime(), published ? "PUBLISHED" : "RECEIVED", published);
        Long sampleId = jdbc.queryForObject("select id from lis_sample where order_id = ?", Long.class, orderId);
        Long microId = jdbc.queryForObject("""
                insert into lab_micro_result(sample_id, order_id, specimen, organism, colony_count, gram)
                values (?, ?, '痰', ?, '+++', 'NEG') returning id
                """, Long.class, sampleId, orderId, organism);
        for (String a : astLines) {
            String[] f = a.split("\\|");   // 抗菌药|MIC|SIR
            jdbc.update("insert into lab_micro_ast(micro_id, antibiotic, method, mic_value, sir) values (?,?,?,?,?)",
                    microId, f[0], "MIC", f[1], f[2]);
        }
        return microId;
    }

    /** 门诊病理标本。issued=签发；rejected=拒收 */
    private Long pathOutp(Long orderId, String diagnosis, boolean issued, boolean rejected) {
        return jdbc.queryForObject("""
                insert into path_specimen(order_id, barcode, path_no, status, gross_finding, micro_finding,
                                          diagnosis, diagnosed_at, report_issued_at, rejected_at, reject_reason)
                values (?, ?, ?, 'DIAGNOSED', '灰白组织一块 1.0×0.8cm', '腺体排列规则', ?, now(),
                        case when ? then now() end, case when ? then now() end, case when ? then '未固定' end)
                returning id
                """, Long.class, orderId, "PB79" + System.nanoTime(), "P79-" + System.nanoTime(),
                diagnosis, issued, rejected, rejected);
    }

    private Long admit(Long patientId, Long doctorId) {
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(patientId, 1L, bedId, doctorId, "K29.7", "胃炎",
                new BigDecimal("500"), "CASH", null).getId();
    }

    /** 住院病理标本（inp_order_id 来源），签发时间 = now() - daysAgo 天 */
    private Long pathInp(Long admissionId, String diagnosis, int daysAgo) {
        Long inpOrderId = jdbc.queryForObject("""
                insert into inp_order(admission_id, group_no, order_type, item_id, item_code, item_name,
                                      unit, qty, unit_price, amount)
                values (?, ?, 'EXAM', 1, 'X', '住院病理检查', '次', 1, 10.00, 10.00) returning id
                """, Long.class, admissionId, "GI79" + System.nanoTime());
        return jdbc.queryForObject("""
                insert into path_specimen(inp_order_id, barcode, path_no, status, diagnosis, diagnosed_at,
                                          report_issued_at)
                values (?, ?, ?, 'DIAGNOSED', ?, now() - make_interval(days => ?), now() - make_interval(days => ?))
                returning id
                """, Long.class, inpOrderId, "PI79" + System.nanoTime(), "P79I-" + System.nanoTime(),
                diagnosis, daysAgo, daysAgo);
    }

    private Map<String, Object> ref(Long registrationId, String kind, LocalDate from, LocalDate to,
                                    Authentication auth) {
        var r = emrRefController.ref(registrationId, null, kind, from, to, auth);
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

    private static void assertEmpty(Map<String, Object> seg, String kind) {
        assertEquals(kind, seg.get("kind"));
        assertEquals(0, seg.get("count"), kind + " 空态 count 应为 0");
        assertEquals("", seg.get("snippet"), kind + " 空态不生成空壳标题");
        assertTrue(items(seg).isEmpty());
        assertEquals(Boolean.FALSE, seg.get("truncated"));
    }

    // ==================== ① MICRO ====================

    @Test
    void microCarriesPublishedCultureWithAstAndSkipsUnpublished() {
        Authentication doc = doctorAuth("v79doc_micro");
        Long pid = newPatient("微");
        Long rid = visitFor(pid, userId("v79doc_micro"));
        micro(order(rid, "LAB", "痰培养+药敏"), "大肠埃希菌", true, "头孢曲松|1|S", "氨苄西林|32|R");
        micro(order(rid, "LAB", "血培养"), "未发布金黄色葡萄球菌", false, "万古霉素|1|S");

        var seg = ref(rid, "MICRO", null, null, doc);
        assertEquals("MICRO", seg.get("kind"));
        assertEquals(1, seg.get("count"), "只取已发布的：" + texts(seg));
        var it = items(seg).get(0);
        String text = String.valueOf(it.get("text"));
        assertTrue(text.contains("痰培养+药敏"), text);
        assertTrue(text.contains("培养：大肠埃希菌"), text);
        assertTrue(text.contains("革兰阴性"), text);
        assertTrue(text.contains("菌落计数 +++"), text);
        assertTrue(text.contains("头孢曲松 MIC 1 敏感(S)"), text);
        assertTrue(text.contains("氨苄西林 MIC 32 耐药(R)"), text);
        assertFalse(texts(seg).contains("未发布金黄色葡萄球菌"), "未发布的微生物结果不得被引用");
        assertEquals(Boolean.TRUE, it.get("currentVisit"));
        @SuppressWarnings("unchecked")
        var raw = (Map<String, Object>) it.get("raw");
        assertEquals(2, ((List<?>) raw.get("ast")).size(), "raw 带结构化药敏行");
        assertTrue(String.valueOf(seg.get("snippet")).startsWith("【微生物培养与药敏】"));
    }

    @Test
    void unknownKindMessageListsTheTwoNewKinds() {
        Authentication doc = doctorAuth("v79doc_kind");
        Long rid = visitFor(newPatient("类"), userId("v79doc_kind"));
        var r = emrRefController.ref(rid, null, "MULTIMEDIA", null, null, doc);
        assertEquals(4000, r.getCode());
        assertTrue(r.getMessage().contains("MICRO") && r.getMessage().contains("PATH"), r.getMessage());
        // 小写也认（与既有四类同一归一）
        assertEquals(0, emrRefController.ref(rid, null, "micro", null, null, doc).getCode());
        assertEquals(0, emrRefController.ref(rid, null, "path", null, null, doc).getCode());
    }

    // ==================== ② PATH ====================

    @Test
    void pathTakesOnlyIssuedNotRejectedAndCarriesSupplements() {
        Authentication doc = doctorAuth("v79doc_path");
        Long pid = newPatient("病");
        Long rid = visitFor(pid, userId("v79doc_path"));
        Long issued = pathOutp(order(rid, "EXAM", "胃镜活检病理"), "（胃窦）慢性浅表性胃炎", true, false);
        jdbc.update("""
                insert into path_report(specimen_id, seq_no, content, reason, signed_at)
                values (?, 1, '免疫组化：HP(+)', '免疫组化回报', now())
                """, issued);
        pathOutp(order(rid, "EXAM", "结肠活检病理"), "未签发草稿诊断", false, false);
        pathOutp(order(rid, "EXAM", "乳腺穿刺病理"), "已拒收标本诊断", true, true);

        var seg = ref(rid, "PATH", null, null, doc);
        assertEquals(1, seg.get("count"), "只取已签发且未拒收：" + texts(seg));
        String text = texts(seg);
        assertTrue(text.contains("胃镜活检病理"), text);
        assertTrue(text.contains("病理诊断：（胃窦）慢性浅表性胃炎"), text);
        assertTrue(text.contains("大体所见：灰白组织一块"), text);
        assertTrue(text.contains("镜下所见：腺体排列规则"), text);
        assertTrue(text.contains("补充报告#1：免疫组化：HP(+)"), "补充报告随正文带出：" + text);
        assertFalse(text.contains("未签发草稿诊断"), "写完诊断未签发的不得被引用");
        assertFalse(text.contains("已拒收标本诊断"), "拒收标本不得被引用");
        var it = items(seg).get(0);
        assertEquals("OUTP", it.get("source"));
        assertEquals(Boolean.TRUE, it.get("currentVisit"));
        assertTrue(String.valueOf(seg.get("snippet")).startsWith("【病理报告】"));
    }

    @Test
    void pathAlsoReadsInpatientSourceForTheSamePatient() {
        Authentication doc = doctorAuth("v79doc_pinp");
        Long uid = userId("v79doc_pinp");
        Long pid = newPatient("住");
        Long aid = admit(pid, uid);
        pathInp(aid, "住院来源病理诊断", 1);
        Long rid = visitFor(pid, uid);

        var fromOutp = ref(rid, "PATH", null, null, doc);
        assertTrue(texts(fromOutp).contains("住院来源病理诊断"), "按患者跨就诊，住院病理也引得到");
        assertEquals("INP", items(fromOutp).get(0).get("source"));
        assertEquals(Boolean.FALSE, items(fromOutp).get(0).get("currentVisit"));

        var r = emrRefController.ref(null, aid, "PATH", null, null, doc);
        assertEquals(0, r.getCode(), r.getMessage());
        assertEquals(Boolean.TRUE, items(r.getData()).get(0).get("currentVisit"), "住院侧本次住院标「本次」");
    }

    // ==================== ③ 时间区间 ====================

    @Test
    void dateWindowFiltersMicroAndPath() {
        Authentication doc = doctorAuth("v79doc_win");
        Long uid = userId("v79doc_win");
        Long pid = newPatient("窗");
        LocalDate today = BusinessDates.today();

        Long oldRid = visitFor(pid, uid);
        micro(order(oldRid, "LAB", "旧痰培养"), "旧肺炎克雷伯菌", true);
        pathOutp(order(oldRid, "EXAM", "旧活检"), "旧门诊病理诊断", true, false);
        // 先把接诊改状态的 JPA 脏实体刷下去，否则后续 flush 会整行回写、把这里改的就诊日期盖回今天
        em.flush();
        jdbc.update("update outp_registration set visit_date = ? where id = ?", today.minusDays(40), oldRid);
        em.clear();
        Long aid = admit(pid, uid);
        pathInp(aid, "旧住院病理诊断", 40);

        Long newRid = visitFor(pid, uid);
        micro(order(newRid, "LAB", "新痰培养"), "新铜绿假单胞菌", true);
        pathOutp(order(newRid, "EXAM", "新活检"), "新门诊病理诊断", true, false);

        // 近 10 天：只有新的
        var mNear = texts(ref(newRid, "MICRO", today.minusDays(10), today, doc));
        assertTrue(mNear.contains("新铜绿假单胞菌") && !mNear.contains("旧肺炎克雷伯菌"), mNear);
        var pNear = texts(ref(newRid, "PATH", today.minusDays(10), today, doc));
        assertTrue(pNear.contains("新门诊病理诊断"), pNear);
        assertFalse(pNear.contains("旧门诊病理诊断") || pNear.contains("旧住院病理诊断"), pNear);

        // 50～30 天前：只有旧的（门诊按就诊日期、住院按签发时间）
        var mOld = texts(ref(newRid, "MICRO", today.minusDays(50), today.minusDays(30), doc));
        assertTrue(mOld.contains("旧肺炎克雷伯菌") && !mOld.contains("新铜绿假单胞菌"), mOld);
        var pOld = texts(ref(newRid, "PATH", today.minusDays(50), today.minusDays(30), doc));
        assertTrue(pOld.contains("旧门诊病理诊断") && pOld.contains("旧住院病理诊断"), pOld);
        assertFalse(pOld.contains("新门诊病理诊断"), pOld);

        // 只给起点 / 只给终点
        assertTrue(texts(ref(newRid, "MICRO", today.minusDays(10), null, doc)).contains("新铜绿假单胞菌"));
        assertFalse(texts(ref(newRid, "MICRO", today.minusDays(10), null, doc)).contains("旧肺炎克雷伯菌"));
        assertTrue(texts(ref(newRid, "PATH", null, today.minusDays(30), doc)).contains("旧住院病理诊断"));
        assertFalse(texts(ref(newRid, "PATH", null, today.minusDays(30), doc)).contains("新门诊病理诊断"));

        // 不传区间：两边都在
        var all = texts(ref(newRid, "PATH", null, null, doc));
        assertTrue(all.contains("旧住院病理诊断") && all.contains("新门诊病理诊断"), all);
    }

    // ==================== ④ 跨患者不串 + 空态 ====================

    @Test
    void otherPatientsResultsNeverLeakAndEmptyStateIsHonest() {
        Authentication doc = doctorAuth("v79doc_iso");
        Long uid = userId("v79doc_iso");
        Long other = newPatient("他");
        Long otherRid = visitFor(other, uid);
        micro(order(otherRid, "LAB", "他人痰培养"), "他人鲍曼不动杆菌", true, "亚胺培南|16|R");
        pathOutp(order(otherRid, "EXAM", "他人活检"), "他人病理诊断", true, false);
        pathInp(admit(other, uid), "他人住院病理诊断", 1);

        Long me = newPatient("我");
        Long myRid = visitFor(me, uid);
        assertEmpty(ref(myRid, "MICRO", null, null, doc), "MICRO");
        assertEmpty(ref(myRid, "PATH", null, null, doc), "PATH");

        // 对照组：他人自己的就诊能看到（证明夹具真的造出了数据，不是恒空）
        assertEquals(1, ref(otherRid, "MICRO", null, null, doc).get("count"));
        assertEquals(2, ref(otherRid, "PATH", null, null, doc).get("count"));
    }

    @Test
    void referenceKindsMicroPathAreReadOnly() {
        Authentication doc = doctorAuth("v79doc_ro");
        Long rid = visitFor(newPatient("只"), userId("v79doc_ro"));
        micro(order(rid, "LAB", "只读痰培养"), "只读菌", true, "头孢他啶|2|S");
        pathOutp(order(rid, "EXAM", "只读活检"), "只读诊断", true, false);
        String snap = "select (select count(*) from lab_micro_result) || '/' || (select count(*) from lab_micro_ast)"
                + " || '/' || (select count(*) from lis_sample where published_at is not null)"
                + " || '/' || (select count(*) from path_specimen where report_issued_at is not null)"
                + " || '/' || (select count(*) from path_report) || '/' || (select count(*) from outp_emr)";
        String before = jdbc.queryForObject(snap, String.class);
        ref(rid, "MICRO", null, null, doc);
        ref(rid, "PATH", null, null, doc);
        assertEquals(before, jdbc.queryForObject(snap, String.class), "引用带出不得写任何表");
    }

    // ==================== ⑤ createOrders 长度校验 ====================

    private Long doctorId() {
        return jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
    }

    private long groupSeq() {
        return jdbc.queryForObject("select last_value from outp_order_group_seq", Long.class);
    }

    private long orderCount(Long rid) {
        return jdbc.queryForObject("select count(*) from outp_order where registration_id = ?", Long.class, rid);
    }

    /** 九个自由文本字段：名称、屏上叫法、实体列宽（OutpOrder @Column(length)） */
    private static final List<String[]> TEXT_FIELDS = List.of(
            new String[]{"usageRoute", "用法", "32"},
            new String[]{"frequency", "频次", "16"},
            new String[]{"dosePerTime", "单次量", "32"},
            new String[]{"remark", "备注", "200"},
            new String[]{"clinicalSummary", "临床摘要", "500"},
            new String[]{"examPurpose", "检查目的", "200"},
            new String[]{"notice", "注意事项", "200"},
            new String[]{"specimenType", "标本类型", "32"},
            new String[]{"samplingSite", "采样部位", "32"});

    /** 用法/频次/单次量只在药品行落库，这三项用 DRUG 行验；其余用 LAB 行 */
    private static final java.util.Set<String> DRUG_ONLY = java.util.Set.of("usageRoute", "frequency", "dosePerTime");

    /** 造一行，指定字段填 value，其余为 null */
    private static OrderLine line(String type, Long itemId, Map<String, String> v) {
        return new OrderLine(type, itemId, 1, v.get("usageRoute"), v.get("frequency"), v.get("dosePerTime"), null,
                v.get("remark"), false, v.get("clinicalSummary"), v.get("examPurpose"), v.get("notice"),
                v.get("specimenType"), v.get("samplingSite"));
    }

    @Test
    void entityWidthsMatchThisTestsTable() throws Exception {
        // 用例里写死的上限与实体注解逐个对一遍：实体改了列宽，这里先红，提醒同步文案与前端 maxlength
        for (String[] f : TEXT_FIELDS) {
            int len = OutpOrder.class.getDeclaredField(f[0])
                    .getAnnotation(jakarta.persistence.Column.class).length();
            assertEquals(Integer.parseInt(f[2]), len, f[0]);
        }
    }

    @Test
    void eachOverlongFieldIs4000NamingRowAndFieldWithZeroSideEffects() {
        Long rid = visitFor(newPatient("长"), doctorId());
        Long labId = seeds.chargeItem("长度校验测试检验", "LAB").getId();
        Long drugId = seeds.drug("长度校验测试药").getId();
        for (String[] f : TEXT_FIELDS) {
            int max = Integer.parseInt(f[2]);
            long seqBefore = groupSeq();
            long ordersBefore = orderCount(rid);
            boolean drugOnly = DRUG_ONLY.contains(f[0]);
            var lines = List.of(
                    line("LAB", labId, Map.of()),                                         // 第 1 行合法
                    line(drugOnly ? "DRUG" : "LAB", drugOnly ? drugId : labId,
                            Map.of(f[0], "字".repeat(max + 1))));                         // 第 2 行超 1 字
            var ex = assertThrows(HipBizException.class,
                    () -> doctorStationService.createOrders(rid, lines, doctorId()), f[0]);
            assertEquals(4000, ex.code, f[0] + "：" + ex.getMessage());
            assertEquals("请求参数不正确：第 2 行" + f[1] + "超过 " + max + " 字（当前 " + (max + 1) + " 字）",
                    ex.getMessage());
            assertEquals(ordersBefore, orderCount(rid), f[0] + "：合法的第 1 行也不得先落库");
            assertEquals(seqBefore, groupSeq(), f[0] + "：被拒的开单不得消耗组号序列");
        }
    }

    /**
     * 恰好等于上限放行：LAB 行六个字段 + DRUG 行三个字段全部顶格，一次开出、逐列按库内 char_length 核对。
     * 另验：非药品行带超长「用法」不拒——该列只在药品行落库，被丢弃的输入不该让整单失败（行为与本版前一致）。
     */
    @Test
    void exactlyAtLimitPassesAndSurrogatePairCountsAsOneChar() {
        Long rid = visitFor(newPatient("界"), doctorId());
        Long labId = seeds.chargeItem("长度上限测试检验", "LAB").getId();
        Long drugId = seeds.drug("长度上限测试药").getId();
        var lab = new java.util.HashMap<String, String>();
        var drug = new java.util.HashMap<String, String>();
        for (String[] f : TEXT_FIELDS) {
            (DRUG_ONLY.contains(f[0]) ? drug : lab).put(f[0], "字".repeat(Integer.parseInt(f[2])));
        }
        // 生僻字 𠀀（U+20000）在 Java 里是两个 char，库里 varchar 算一个字：32 个须放行
        lab.put("specimenType", "𠀀".repeat(32));
        lab.put("usageRoute", "字".repeat(100));   // 非药品行：不落库、不核
        var created = assertDoesNotThrow(() -> doctorStationService.createOrders(rid,
                List.of(line("LAB", labId, lab), line("DRUG", drugId, drug)), doctorId()));
        assertEquals(2, created.size());
        // IDENTITY 主键：save 当场 insert，库里已是这两行（列宽超了此处之前就会先炸）
        var l = jdbc.queryForMap("""
                select char_length(clinical_summary) cs, char_length(specimen_type) st, char_length(sampling_site) ss,
                       char_length(remark) rm, char_length(exam_purpose) ep, char_length(notice) nt, usage_route
                from outp_order where id = ?
                """, created.get(0).getId());
        assertEquals(500, ((Number) l.get("cs")).intValue());
        assertEquals(32, ((Number) l.get("st")).intValue());
        assertEquals(32, ((Number) l.get("ss")).intValue());
        assertEquals(200, ((Number) l.get("rm")).intValue());
        assertEquals(200, ((Number) l.get("ep")).intValue());
        assertEquals(200, ((Number) l.get("nt")).intValue());
        assertNull(l.get("usage_route"), "非药品行的用法不落库（既有行为）");
        var d = jdbc.queryForMap("""
                select char_length(usage_route) ur, char_length(frequency) fq, char_length(dose_per_time) dp
                from outp_order where id = ?
                """, created.get(1).getId());
        assertEquals(32, ((Number) d.get("ur")).intValue());
        assertEquals(16, ((Number) d.get("fq")).intValue());
        assertEquals(32, ((Number) d.get("dp")).intValue());
    }
}
