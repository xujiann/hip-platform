package cn.hip.server;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.DoctorStationController;
import cn.hip.outpatient.web.DoctorStationController.FavoriteRequest;
import cn.hip.outpatient.web.DoctorStationController.SaveEmrRequest;
import cn.hip.outpatient.web.DoctorStationController.SpecialDiseaseRequest;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v44 车道E：门诊诊断域完整化（偏离表 977★/979★/982★/983★/984★/1084★）。
 *
 * <p>{@code outp_diagnosis} 自 V5:15 建表起只有 5 列，而上述 6 条在投标应答里全部答"已实现"。
 * 本版补列（前缀/后缀/确诊疑诊/中西医/自定义名称）+ 诊断助手三源 + 常用诊断 + 特殊病种院内登记。
 *
 * <p><b>本类的头号价值是 §① 契约保护</b>：{@link #legacyFiveFieldSaveContractUnchanged()}
 * 钉死"只传既有 5 个字段保存诊断"时的行为与返回体——新列一律 null（历史行严禁回填伪造），
 * 下游 {@code CdrSyncService:129} 与 {@code PrintReportController:225} 那两条按列名点名的
 * SELECT 结果逐字不变。诊断是病案首页与 DRG 区分主诊断（primary_diag）的唯一依据，
 * 这条用例是"加列不破契约"的回归护栏。
 *
 * <p>§② 新字段往返与取值校验（4033/4034）；§③ 诊断助手三段；§④ 常用诊断累加与删除；
 * §⑤ 中医诊断的诚实边界（不编造中医编码）；§⑥ 特殊病种<b>院内登记</b>（不含医保报送，4035）。
 *
 * <p>抛 BizException 的用例各自独立成 @Test——服务层抛错会把测试事务标记 rollback-only，
 * 不能与"抛错后仍需成功提交"的步骤混在同一个 @Test（同 ClinicalClosureTest / V43EmrSignTest 的约定）。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V44DiagnosisTest {

    @Autowired DoctorStationController doctorStationController;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired ObjectMapper objectMapper;

    /** v44 新增的 5 个列名（数据库侧）——契约保护逐列断言 null 用 */
    private static final List<String> NEW_COLUMNS =
            List.of("prefix", "suffix", "certainty", "diag_system", "custom_name");

    /** 一次就诊：患者 id + 挂号 id */
    private record Visit(Long patientId, Long registrationId) {}

    private Authentication userAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    private Long userId(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private Long newPatient() {
        Patient p = new Patient();
        p.setName("v44诊断" + System.nanoTime());
        p.setSex("U");
        return patientService.register(p).getId();
    }

    private Long visitFor(Long patientId, LocalDate date) {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(date);
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(patientId, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    private Visit visit() {
        Long pid = newPatient();
        return new Visit(pid, visitFor(pid, BusinessDates.today()));
    }

    private static OutpEmr emrOf(String chiefComplaint) {
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint(chiefComplaint);
        e.setPresentIllness("受凉后起病 2 天");
        e.setAdvice("对症治疗");
        return e;
    }

    /** 只带既有 5 个字段中可由调用方赋值的三个——旧调用方（E2E / 既有单测 / seed 脚本）的形态 */
    private static OutpDiagnosis legacyDiag(String code, String name) {
        OutpDiagnosis d = new OutpDiagnosis();
        d.setIcdCode(code);
        d.setIcdName(name);
        return d;
    }

    private List<Map<String, Object>> diagRows(Long registrationId) {
        em.flush();
        return jdbc.queryForList(
                "select * from outp_diagnosis where registration_id = ? order by id", registrationId);
    }

    private List<Map<String, Object>> workspaceDiagnoses(Long registrationId) {
        var ws = doctorStationController.workspace(registrationId).getData();
        return objectMapper.convertValue(ws.get("diagnoses"),
                new TypeReference<List<Map<String, Object>>>() {});
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> assistSegment(Map<String, Object> assist, String key) {
        return (List<Map<String, Object>>) assist.get(key);
    }

    private static boolean hasName(List<Map<String, Object>> rows, String name) {
        return rows.stream().anyMatch(r -> name.equals(r.get("icdName")));
    }

    // ==================== ① 契约保护：加列不得改动既有写入与读取 ====================

    /**
     * <b>本类最重要的一条</b>。只传既有 5 个字段保存诊断时：
     *
     * <ol>
     *   <li>落库的既有 5 列逐字不变，primary_diag 仍按列表顺序定主次（病案首页与 DRG 的依据）；</li>
     *   <li>v44 新增 5 列<b>一律为 null</b>——不默认 'ICD10'、不默认 'CONFIRMED'、不拿 icd_name
     *       去填 custom_name。历史数据里医生到底写的确诊还是疑诊、中医还是西医，查不出来就空着，
     *       <b>回填即伪造</b>；</li>
     *   <li>下游两条按列名点名的 SELECT（CdrSyncService:129 / PrintReportController:225）
     *       原文照抄跑一遍，行数、顺序、取值不变；</li>
     *   <li>workspace 返回体的既有 5 个键取值逐字不变，新增 5 个键存在且为 null，
     *       键集合被完整钉死——将来谁再往实体上加字段，这条先红。</li>
     * </ol>
     */
    @Test
    void legacyFiveFieldSaveContractUnchanged() {
        Authentication doc = userAuth("v44doc_contract");
        Visit v = visit();

        var saved = doctorStationController.saveEmr(v.registrationId(), new SaveEmrRequest(
                emrOf("咳嗽3天"),
                List.of(legacyDiag("J06.900", "急性上呼吸道感染"), legacyDiag("R05.X00", "咳嗽"))), doc);
        assertEquals(0, saved.getCode(), saved.getMessage());

        // ① + ② 落库形态
        var rows = diagRows(v.registrationId());
        assertEquals(2, rows.size());
        assertEquals("J06.900", rows.get(0).get("icd_code"));
        assertEquals("急性上呼吸道感染", rows.get(0).get("icd_name"));
        assertEquals(true, rows.get(0).get("primary_diag"), "第一条仍是主诊断");
        assertEquals("R05.X00", rows.get(1).get("icd_code"));
        assertEquals(false, rows.get(1).get("primary_diag"));
        for (var row : rows) {
            for (String col : NEW_COLUMNS) {
                assertNull(row.get(col), "只传既有 5 字段时新列必须为 null，严禁回填：" + col);
            }
        }

        // ③ 下游读取 SQL 原文照抄（改动这两条 SQL 的行为等于改 CDR 同步与处方笺诊断栏）
        var cdrRows = jdbc.queryForList(
                "select registration_id, icd_code, icd_name, primary_diag from outp_diagnosis"
                        + " where registration_id in (?) order by primary_diag desc",
                v.registrationId());
        assertEquals(2, cdrRows.size(), "CdrSyncService 的诊断抽取行数不变");
        assertEquals("J06.900", cdrRows.get(0).get("icd_code"), "主诊断仍排第一");
        var printRows = jdbc.queryForList(
                "select icd_code, icd_name, primary_diag from outp_diagnosis"
                        + " where registration_id = ? order by primary_diag desc, id",
                v.registrationId());
        assertEquals(List.of("J06.900", "R05.X00"),
                printRows.stream().map(m -> m.get("icd_code")).toList(),
                "PrintReportController 处方笺诊断栏的行序不变");

        // ④ workspace 返回体
        var body = workspaceDiagnoses(v.registrationId());
        assertEquals(2, body.size());
        var first = body.get(0);
        assertEquals(Set.of("id", "registrationId", "icdCode", "icdName", "primaryDiag",
                        "prefix", "suffix", "certainty", "diagSystem", "customName"),
                first.keySet(), "诊断返回体键集合被钉死：既有 5 键 + v44 新增 5 键，不得再多");
        assertEquals("J06.900", first.get("icdCode"));
        assertEquals("急性上呼吸道感染", first.get("icdName"));
        assertEquals(Boolean.TRUE, first.get("primaryDiag"));
        assertNotNull(first.get("id"));
        assertEquals(v.registrationId().longValue(), ((Number) first.get("registrationId")).longValue());
        for (String k : List.of("prefix", "suffix", "certainty", "diagSystem", "customName")) {
            assertTrue(first.containsKey(k), "新键须存在（前端按可空读）：" + k);
            assertNull(first.get(k), "旧调用方不传时新键必须是 null：" + k);
        }
    }

    // ==================== ② 新字段往返与取值校验 ====================

    /** 982 前后缀 / 983 确诊疑诊 / 1084 中西医 / 977 自定义名称：一次写全，读回逐项对上 */
    @Test
    void newFieldsRoundTrip() {
        Authentication doc = userAuth("v44doc_roundtrip");
        Visit v = visit();

        OutpDiagnosis d = legacyDiag("I10.X00", "原发性高血压");
        d.setPrefix("陈旧性");
        d.setSuffix("急性发作期");
        d.setCertainty(OutpDiagnosis.CERTAINTY_SUSPECTED);
        d.setDiagSystem(OutpDiagnosis.SYSTEM_ICD10);
        d.setCustomName("高血压3级（很高危）");

        assertEquals(0, doctorStationController.saveEmr(v.registrationId(),
                new SaveEmrRequest(emrOf("头晕1周"), List.of(d)), doc).getCode());

        var row = diagRows(v.registrationId()).get(0);
        assertEquals("陈旧性", row.get("prefix"));
        assertEquals("急性发作期", row.get("suffix"));
        assertEquals("SUSPECTED", row.get("certainty"));
        assertEquals("ICD10", row.get("diag_system"));
        assertEquals("高血压3级（很高危）", row.get("custom_name"));
        // 977 的原话是"ICD-10 标准编码**及**自定义名称描述"——两者并存，自定义名不得顶掉标准名
        assertEquals("原发性高血压", row.get("icd_name"), "自定义描述不得替换 icd_name（下游读的是它）");

        var body = workspaceDiagnoses(v.registrationId()).get(0);
        assertEquals("SUSPECTED", body.get("certainty"));
        assertEquals("高血压3级（很高危）", body.get("customName"));
    }

    /** 983：确诊/疑诊标记取值非法 → 4033，且零副作用（被拒时诊断一条都不落库） */
    @Test
    void illegalCertaintyRejectedWith4033() {
        Authentication doc = userAuth("v44doc_cert");
        Visit v = visit();
        OutpDiagnosis d = legacyDiag("J02.900", "急性咽炎");
        d.setCertainty("MAYBE");

        var r = doctorStationController.saveEmr(v.registrationId(),
                new SaveEmrRequest(emrOf("咽痛2天"), List.of(d)), doc);
        assertEquals(4033, r.getCode(), r.getMessage());
        assertEquals(0, count("select count(*) from outp_diagnosis where registration_id = ?",
                v.registrationId()), "校验是写入前置，被拒时不得落任何行");
    }

    /** 1084：诊断体系取值非法 → 4034 */
    @Test
    void illegalDiagSystemRejectedWith4034() {
        Authentication doc = userAuth("v44doc_sys");
        Visit v = visit();
        OutpDiagnosis d = legacyDiag("J02.900", "急性咽炎");
        d.setDiagSystem("WESTERN");

        var r = doctorStationController.saveEmr(v.registrationId(),
                new SaveEmrRequest(emrOf("咽痛2天"), List.of(d)), doc);
        assertEquals(4034, r.getCode(), r.getMessage());
    }

    /** 空串等同未填：不能让"清空下拉框"变成 4033/4034（前端清空后回传的是空串） */
    @Test
    void blankNewFieldsTreatedAsUnset() {
        Authentication doc = userAuth("v44doc_blank");
        Visit v = visit();
        OutpDiagnosis d = legacyDiag("R51.X00", "头痛");
        d.setCertainty("");
        d.setDiagSystem("");

        assertEquals(0, doctorStationController.saveEmr(v.registrationId(),
                new SaveEmrRequest(emrOf("头痛1天"), List.of(d)), doc).getCode());
        var row = diagRows(v.registrationId()).get(0);
        assertEquals("", row.get("certainty"), "空串原样落库，不做任何回填");
    }

    // ==================== ③ 诊断助手三段（979） ====================

    /**
     * 979：历史 / 常用 / 高频三段各自命中。
     *
     * <p>三段都是聚合查询、共享同一个测试库，故只断言"本用例造的数据在里面"，
     * 不断言绝对条数——同 tools E2E 的相对断言纪律。
     * frequent 是<b>全院</b>聚合且限 20 条，绝对榜单里挤不进新造的一条，
     * 故用 keyword 过滤后断言，并顺带证明它统计的是<b>真实出现次数</b>而非硬编码清单。
     */
    @Test
    void assistReturnsHistoryFavoriteAndFrequent() {
        Authentication doc = userAuth("v44doc_assist");
        Long uid = userId("v44doc_assist");
        Long pid = newPatient();
        String histName = "v44历史诊断" + System.nanoTime();
        String favName = "v44常用诊断" + System.nanoTime();

        // 十天前 + 今天两次就诊，同一条诊断——高频段应统计成 2 次
        Long oldRid = visitFor(pid, BusinessDates.today().minusDays(10));
        assertEquals(0, doctorStationController.saveEmr(oldRid,
                new SaveEmrRequest(emrOf("既往就诊"), List.of(legacyDiag("Z00.001", histName))), doc).getCode());
        Long newRid = visitFor(pid, BusinessDates.today());
        assertEquals(0, doctorStationController.saveEmr(newRid,
                new SaveEmrRequest(emrOf("本次就诊"), List.of(legacyDiag("Z00.001", histName))), doc).getCode());
        // 一条纯手工加星的常用诊断（未出现在该患者历史里，用于区分 favorite 与 history）
        doctorStationService.upsertFavorite(uid, "Z00.002", favName, "ICD10");
        em.flush();

        var assist = doctorStationService.diagnosisAssist(pid, null, uid);
        var history = assistSegment(assist, "history");
        var favorite = assistSegment(assist, "favorite");
        assertTrue(hasName(history, histName), "history 段应含该患者的既往诊断");
        assertEquals(1, history.stream().filter(m -> histName.equals(m.get("icdName"))).count(),
                "history 应按编码+名称去重，两次就诊的同一诊断只出一条");
        assertTrue(hasName(favorite, favName), "favorite 段应含本医生的常用诊断");
        assertFalse(hasName(history, favName), "history 只应是该患者自己的既往诊断");
        assertTrue(history.size() <= 20 && favorite.size() <= 20
                        && assistSegment(assist, "frequent").size() <= 20, "三段各限 20 条");

        // keyword 过滤三段同时生效；frequent 段按真实数据聚合出 2 次
        var filtered = doctorStationService.diagnosisAssist(pid, histName, uid);
        var frequent = assistSegment(filtered, "frequent");
        assertTrue(hasName(frequent, histName), "frequent 段应按真实开方数据聚合出该诊断");
        assertEquals(2L, ((Number) frequent.stream()
                .filter(m -> histName.equals(m.get("icdName"))).findFirst().orElseThrow()
                .get("useCount")).longValue(), "高频次数是真实统计值，不是硬编码清单");
        assertFalse(hasName(assistSegment(filtered, "favorite"), favName),
                "keyword 应过滤掉不匹配的常用诊断");
    }

    // ==================== ④ 常用诊断累加与删除（979 + 1084 存储常用诊断） ====================

    /** 保存诊断时自动累加使用次数（upsert），删除端点生效 */
    @Test
    void favoriteAccumulatesOnSaveAndCanBeDeleted() {
        Authentication doc = userAuth("v44doc_fav");
        Long uid = userId("v44doc_fav");
        String name = "v44累加诊断" + System.nanoTime();
        String code = "Z00.911";
        Visit v1 = visit();
        Visit v2 = visit();

        doctorStationController.saveEmr(v1.registrationId(),
                new SaveEmrRequest(emrOf("首诊"), List.of(legacyDiag(code, name))), doc);
        doctorStationController.saveEmr(v2.registrationId(),
                new SaveEmrRequest(emrOf("复诊"), List.of(legacyDiag(code, name))), doc);
        em.flush();

        String useCountSql = "select use_count from outp_diagnosis_favorite"
                + " where user_id = ? and icd_code = ?";
        assertEquals(2, count(useCountSql, uid, code), "同一诊断保存两次，常用诊断使用次数应累加到 2");

        // 手工加星走同一条 upsert，再 +1
        assertEquals(0, doctorStationController.addFavorite(
                new FavoriteRequest(code, name, "ICD10"), doc).getCode());
        assertEquals(3, count(useCountSql, uid, code));

        Long favId = jdbc.queryForObject(
                "select id from outp_diagnosis_favorite where user_id = ? and icd_code = ?",
                Long.class, uid, code);
        assertEquals(0, doctorStationController.deleteFavorite(favId, doc).getCode());
        assertEquals(0, count("select count(*) from outp_diagnosis_favorite where id = ?", favId),
                "删除常用诊断应生效");
    }

    /** 常用诊断是医生个人数据：不得删掉别人的 */
    @Test
    void favoriteDeleteIsScopedToOwner() {
        Authentication mine = userAuth("v44doc_own");
        Authentication other = userAuth("v44doc_other");
        Long myId = userId("v44doc_own");
        String name = "v44私有常用" + System.nanoTime();
        doctorStationService.upsertFavorite(myId, "Z00.003", name, "ICD10");

        Long favId = jdbc.queryForObject(
                "select id from outp_diagnosis_favorite where user_id = ? and icd_code = 'Z00.003'",
                Long.class, myId);
        doctorStationController.deleteFavorite(favId, other);
        assertEquals(1, count("select count(*) from outp_diagnosis_favorite where id = ?", favId),
                "别人不能删我的常用诊断");
        doctorStationController.deleteFavorite(favId, mine);
        assertEquals(0, count("select count(*) from outp_diagnosis_favorite where id = ?", favId));
    }

    // ==================== ⑤ 中医诊断的诚实边界（1084） ====================

    /**
     * 1084 兼容中西医诊断录入。<b>本仓不预置中医诊断码表，也不编造中医编码</b>：
     * diag_system='TCM' 时 icd_code 存空串（既有列非空约束不动），诊断名走自由录入，
     * 前后缀照样可用。本条同时钉死"中医诊断也能进个人常用库"（1084 的"存储常用诊断"）。
     */
    @Test
    void tcmDiagnosisStoredWithoutFabricatedCode() {
        Authentication doc = userAuth("v44doc_tcm");
        Long uid = userId("v44doc_tcm");
        Visit v = visit();
        String tcm = "风寒感冒" + System.nanoTime() % 100000;

        OutpDiagnosis d = legacyDiag("", tcm);
        d.setDiagSystem(OutpDiagnosis.SYSTEM_TCM);
        d.setCustomName(tcm + "（风寒束表证）");
        d.setSuffix("初起");
        assertEquals(0, doctorStationController.saveEmr(v.registrationId(),
                new SaveEmrRequest(emrOf("恶寒无汗2天"), List.of(d)), doc).getCode());

        var row = diagRows(v.registrationId()).get(0);
        assertEquals("TCM", row.get("diag_system"));
        assertEquals("", row.get("icd_code"), "中医诊断不编造编码：icd_code 存空串而非假码");
        assertEquals(tcm, row.get("icd_name"));
        assertEquals("初起", row.get("suffix"));
        assertEquals(true, row.get("primary_diag"), "中医诊断同样按顺序定主次");

        // 无编码的常用诊断按名称去重（部分唯一索引），不会被空编码挤成一条
        em.flush();
        var fav = jdbc.queryForList(
                "select icd_code, icd_name, use_count from outp_diagnosis_favorite"
                        + " where user_id = ? and icd_code is null", uid);
        assertEquals(1, fav.size());
        assertEquals(tcm, fav.get(0).get("icd_name"), "中医诊断也进个人常用库（1084 存储常用诊断）");
    }

    // ==================== ⑥ 医保特殊病种「院内登记」（984） ====================

    /**
     * 984 的边界：本平台做的是<b>院内登记</b>（记下已认定的特殊病种与院内认定有效期，
     * 供诊断/开单提示），<b>不含向医保经办机构备案报送</b>——那要走当地医保接口，属外部条件。
     * 本条顺带钉死表里<b>没有</b>报送/审批状态列：留一个永远停在"待报送"的状态比不留更像假实现。
     */
    @Test
    void specialDiseaseIsInHouseRegistrationOnly() {
        Authentication doc = userAuth("v44doc_sd");
        Visit v = visit();
        String disease = "慢性肾功能衰竭（透析）" + System.nanoTime() % 1000;

        var r = doctorStationController.addSpecialDisease(new SpecialDiseaseRequest(
                v.patientId(), "MB-001", disease, "职工医保",
                BusinessDates.today().minusMonths(1), BusinessDates.today().plusMonths(11), "门特"), doc);
        assertEquals(0, r.getCode(), r.getMessage());

        var active = doctorStationController.specialDiseases(v.patientId(), true).getData();
        assertEquals(1, active.size());
        assertEquals(disease, active.get(0).get("diseaseName"));
        assertEquals("职工医保", active.get(0).get("insuranceType"));

        // 已过期的登记不进"有效期内"列表（开单提示只提示还在有效期的）
        doctorStationController.addSpecialDisease(new SpecialDiseaseRequest(
                v.patientId(), null, "已过期病种", null,
                BusinessDates.today().minusYears(2), BusinessDates.today().minusYears(1), null), doc);
        assertEquals(1, doctorStationController.specialDiseases(v.patientId(), true).getData().size(),
                "过期登记不应出现在有效期内列表");
        assertEquals(2, doctorStationController.specialDiseases(v.patientId(), false).getData().size(),
                "全量列表仍应看得到过期登记");

        // 边界声明的结构化证据：本表刻意不含任何"报送/备案状态"列
        var columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'outp_special_disease'")
                .stream().map(m -> String.valueOf(m.get("column_name"))).toList();
        assertFalse(columns.stream().anyMatch(c -> c.contains("report") || c.contains("filing")
                        || c.contains("approve") || c.contains("submit")),
                "特殊病种是院内登记，不得出现假的医保报送/备案状态列：" + columns);

        Long id = ((Number) doctorStationController.specialDiseases(v.patientId(), true)
                .getData().get(0).get("id")).longValue();
        assertEquals(0, doctorStationController.deleteSpecialDisease(id).getCode());
        assertEquals(0, doctorStationController.specialDiseases(v.patientId(), true).getData().size());
    }

    /** 984：登记信息不全 → 4035 */
    @Test
    void specialDiseaseMissingRequiredFieldsRejectedWith4035() {
        Authentication doc = userAuth("v44doc_sd2");
        Visit v = visit();
        assertEquals(4035, doctorStationController.addSpecialDisease(new SpecialDiseaseRequest(
                v.patientId(), null, "  ", null, BusinessDates.today(), null, null), doc).getCode(),
                "病种名称必填");
    }

    /** 984：有效期止早于起 → 4035（沿用同一码，不为同类语义另造同义码） */
    @Test
    void specialDiseaseInvalidDateRangeRejectedWith4035() {
        Authentication doc = userAuth("v44doc_sd3");
        Visit v = visit();
        assertEquals(4035, doctorStationController.addSpecialDisease(new SpecialDiseaseRequest(
                v.patientId(), null, "糖尿病", null,
                BusinessDates.today(), BusinessDates.today().minusDays(1), null), doc).getCode());
    }

    // ==================== ⑦ 下游不破：诊断读取路径回归 ====================

    /**
     * 加列后，历史就诊调阅（{@code /patient/{id}/history}）的诊断三键仍是
     * icdCode / icdName / primaryDiag——前端历史抽屉与 E2E 都按这三键读。
     */
    @Test
    void patientHistoryDiagnosisShapeUnchanged() {
        Authentication doc = userAuth("v44doc_hist");
        Visit v = visit();
        OutpDiagnosis d = legacyDiag("E11.900", "2型糖尿病");
        d.setCertainty(OutpDiagnosis.CERTAINTY_CONFIRMED);
        d.setDiagSystem(OutpDiagnosis.SYSTEM_ICD10);
        doctorStationController.saveEmr(v.registrationId(),
                new SaveEmrRequest(emrOf("多饮多尿"), List.of(d)), doc);
        em.flush();

        var history = doctorStationController.patientHistory(v.patientId()).getData();
        assertFalse(history.isEmpty());
        @SuppressWarnings("unchecked")
        var diags = (List<Map<String, Object>>) history.get(0).get("diagnoses");
        assertEquals(1, diags.size());
        assertEquals(Set.of("icdCode", "icdName", "primaryDiag"), diags.get(0).keySet(),
                "历史调阅的诊断三键形状不得因加列而变");
        assertEquals("E11.900", diags.get(0).get("icdCode"));
    }
}
