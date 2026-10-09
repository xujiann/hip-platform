package cn.hip.server;

import cn.hip.inpatient.web.InpEmrController;
import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.DoctorStationController;
import cn.hip.outpatient.web.EmrRefController;
import cn.hip.outpatient.web.EmrVersionController;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.core.web.SysConfigController;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v79 审阅修补（甲组 D3/D4/D5、乙组 D2/N10/D4）。
 *
 * <p>§① 甲 D3（992/1019 阻断）：引用资料「检查」只取<b>已审核（VERIFIED）</b>的 RIS 报告——
 * 没出报告（REGISTERED）与写了报告未审核（REPORTED）的不得以「检查报告」之名进病历；
 * 病理医嘱被 RIS 队列自动登记出的 REGISTERED 行随之不再在检查页签重复出现（病理页签照常有）。
 * <p>§② 甲 D4（994）：同一次保存产生的正文版本与诊断版本取<b>同一个时钟</b>（数据库事务时刻），
 * 版本页两块的时间不再倒序。
 * <p>§③ 甲 D5（992）：门诊病历暂存按 {@code OutpEmr} 五段列宽逐段核长度，超长 4000 点名段落，
 * 零副作用（不落病历、不落版本、已有正文不被改）；恰好顶格与代理对按码点计放行。
 * <p>§④ 乙 D2/N10（2457 阻断）：住院未签名记录可直接修改正文（PUT），修改落一版 MANUAL；
 * 已签名 9103、跨住院 9102、空正文 9101；未签名点补正的 9108 指向真实存在的「修改」。
 * <p>§⑤ 乙 D4（1082/2458）：版本列表带回患者 id/姓名，供版本页把复制来源记成该患者；
 * 患者 360 与版本页正文区挂上复制来源登记（源码扫描）。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V79ReviewFixTest {

    @Autowired EmrRefController emrRefController;
    @Autowired EmrVersionController emrVersionController;
    @Autowired MedTechController medTechController;
    @Autowired InpEmrController inpEmrController;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired cn.hip.inpatient.service.InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired SysConfigController sysConfigController;
    @Autowired ConfigReader configReader;
    @Autowired DoctorStationController doctorStationController;

    /** 测试方法论 ⑤：经配置端点改过的键，事务回滚后缓存里不能留着测试里的值 */
    @AfterEach
    void evictConfigCache() {
        configReader.evictAll();
    }

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
        p.setName("修" + tag + System.nanoTime() % 100000);
        p.setSex("M");
        p.setBirthDate(LocalDate.of(1970, 3, 1));
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

    private Long order(Long registrationId, String type, String itemName, String status) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code,
                                       item_name, unit, qty, unit_price, amount, status)
                values (?, ?, ?, 1, 'X', ?, '次', 1, 10.00, 10.00, ?) returning id
                """, Long.class, registrationId, "GF79" + System.nanoTime(), type, itemName, status);
    }

    private void ris(Long orderId, String status, String findings, String impression) {
        jdbc.update("""
                insert into ris_exam(order_id, status, findings, impression, reported_at, verified_at)
                values (?, ?, ?, ?, case when ? <> 'REGISTERED' then now() end,
                        case when ? = 'VERIFIED' then now() end)
                """, orderId, status, findings, impression, status, status);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> seg) {
        return (List<Map<String, Object>>) seg.get("items");
    }

    private static String texts(Map<String, Object> seg) {
        return String.join("\n", items(seg).stream().map(i -> String.valueOf(i.get("text"))).toList());
    }

    private Map<String, Object> ref(Long rid, String kind, Authentication auth) {
        var r = emrRefController.ref(rid, null, kind, null, null, auth);
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    private static OutpEmr emr(String chief, String present) {
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint(chief);
        e.setPresentIllness(present);
        return e;
    }

    private static OutpDiagnosis diag(String code, String name) {
        OutpDiagnosis d = new OutpDiagnosis();
        d.setIcdCode(code);
        d.setIcdName(name);
        return d;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // ==================== ① 甲 D3：检查只取已审核报告 ====================

    @Test
    void examTakesOnlyVerifiedRisReports() {
        Authentication doc = doctorAuth("v79fix_exam");
        Long rid = visitFor(newPatient("查"), userId("v79fix_exam"));
        ris(order(rid, "EXAM", "腹部彩超", "CHARGED"), "REGISTERED", null, null);
        ris(order(rid, "EXAM", "胸部正位片", "CHARGED"), "REPORTED", "未审核所见", "未审核印象");
        ris(order(rid, "EXAM", "头颅CT平扫", "CHARGED"), "VERIFIED", "脑实质未见异常密度", "颅内未见明显异常");

        var seg = ref(rid, "EXAM", doc);
        String all = texts(seg);
        assertEquals(1, seg.get("count"), "只取已审核的检查报告：" + all);
        assertTrue(all.contains("头颅CT平扫") && all.contains("印象：颅内未见明显异常"), all);
        assertFalse(all.contains("腹部彩超"), "没出报告（REGISTERED）的检查不得以「检查报告」之名进病历：" + all);
        assertFalse(all.contains("未审核"), "写了报告未审核（REPORTED）的不得被引用：" + all);
        assertFalse(String.valueOf(seg.get("snippet")).contains("未审核"), "整段插入同口径");
    }

    /**
     * 病理医嘱也是 EXAM 类型：RIS 队列 GET 会把已收费的病理医嘱自动登记成 ris_exam（REGISTERED）。
     * 修前它以光秃秃的医嘱名出现在「检查」页签，和「病理」页签的真报告重复；修后只在病理页签出现。
     */
    @Test
    void pathologyOrderAutoRegisteredByRisQueueNoLongerDuplicatesInExam() {
        Authentication doc = doctorAuth("v79fix_path");
        Long rid = visitFor(newPatient("病"), userId("v79fix_path"));
        Long pathOrder = order(rid, "EXAM", "组织病理学检查(活检)", "CHARGED");
        jdbc.update("""
                insert into path_specimen(order_id, barcode, path_no, status, diagnosis, diagnosed_at, report_issued_at)
                values (?, ?, ?, 'DIAGNOSED', '慢性浅表性胃炎', now(), now())
                """, pathOrder, "PBF79" + System.nanoTime(), "PF79-" + System.nanoTime());

        assertEquals(0, medTechController.risWorklist(null).getCode());   // 真实路径：队列自动登记
        assertEquals(1, count("select count(*) from ris_exam where order_id = ? and status = 'REGISTERED'", pathOrder),
                "前提：病理医嘱确被 RIS 队列自动登记");

        assertFalse(texts(ref(rid, "EXAM", doc)).contains("组织病理学检查"), "病理医嘱不得在检查页签重复出现");
        assertTrue(texts(ref(rid, "PATH", doc)).contains("慢性浅表性胃炎"), "病理页签照常取到已签发报告");
    }

    // ==================== ② 甲 D4：正文版本与诊断版本同一时钟 ====================

    @Test
    void bodyVersionAndDiagnosisVersionOfOneSaveShareTheDatabaseClock() {
        Long docId = userId("admin");
        Long rid = visitFor(newPatient("钟"), docId);
        Timestamp txNow = jdbc.queryForObject("select now()", Timestamp.class);

        OutpEmr saved = doctorStationService.saveEmr(rid, emr("咳嗽", "起病三天"),
                List.of(diag("J06.9", "急性上呼吸道感染")), docId, null, null);
        em.flush();

        Timestamp savedAt = jdbc.queryForObject(
                "select saved_at from emr_version where emr_type = 'OUTP' and emr_id = ? and version_no = 1",
                Timestamp.class, saved.getId());
        Timestamp changedAt = jdbc.queryForObject(
                "select changed_at from outp_diagnosis_version where registration_id = ? and version_no = 1",
                Timestamp.class, rid);
        assertNotNull(savedAt);
        assertNotNull(changedAt);
        assertFalse(savedAt.toInstant().isAfter(changedAt.toInstant()),
                "同一次保存：正文版本 " + savedAt.toInstant() + " 不得晚于诊断版本 " + changedAt.toInstant()
                        + "——两块用了两个时钟，版本页会倒序");
        assertEquals(changedAt.toInstant(), savedAt.toInstant(), "两侧都取数据库事务时刻，应逐微秒相等");
        assertEquals(txNow.toInstant(), savedAt.toInstant(), "正文版本时刻取数据库 now()，不是应用服务器时钟");
    }

    // ==================== ③ 甲 D5：五段列宽逐段校验 ====================

    @Test
    void overlongSegmentIsRejectedWith4000NamingTheSegmentAndLeavesNoTrace() {
        Long docId = userId("admin");
        Long rid = visitFor(newPatient("长"), docId);
        String[][] cases = {
                {"chiefComplaint", "主诉", "512"},
                {"presentIllness", "现病史", "2000"},
                {"pastHistory", "既往史", "1000"},
                {"physicalExam", "体格检查", "1000"},
                {"advice", "处理意见", "1000"},
        };
        for (String[] c : cases) {
            int max = Integer.parseInt(c[2]);
            OutpEmr e = emr("咳嗽", "起病三天");
            String over = "字".repeat(max + 1);
            switch (c[0]) {
                case "chiefComplaint" -> e.setChiefComplaint(over);
                case "presentIllness" -> e.setPresentIllness(over);
                case "pastHistory" -> e.setPastHistory(over);
                case "physicalExam" -> e.setPhysicalExam(over);
                default -> e.setAdvice(over);
            }
            var ex = assertThrows(HipBizException.class,
                    () -> doctorStationService.saveEmr(rid, e, List.of(diag("J06.9", "上感")), docId, null, null), c[0]);
            assertEquals(4000, ex.code, c[0] + "：" + ex.getMessage());
            assertEquals("请求参数不正确：" + c[1] + "超过 " + max + " 字（当前 " + (max + 1) + " 字）", ex.getMessage());
        }
        em.flush();
        assertEquals(0, count("select count(*) from outp_emr where registration_id = ?", rid), "零副作用：不落病历");
        assertEquals(0, count("select count(*) from outp_diagnosis where registration_id = ?", rid), "零副作用：不落诊断");
        assertEquals(0, count("select count(*) from outp_diagnosis_version where registration_id = ?", rid));
    }

    @Test
    void overlongSecondSaveLeavesExistingBodyAndVersionsUntouched() {
        Long docId = userId("admin");
        Long rid = visitFor(newPatient("留"), docId);
        OutpEmr first = doctorStationService.saveEmr(rid, emr("咳嗽", "起病三天"), List.of(), docId, null, null);
        em.flush();
        em.clear();

        var ex = assertThrows(HipBizException.class, () -> doctorStationService.saveEmr(rid,
                emr("咳嗽", "起病三天" + "引".repeat(2000)), List.of(), docId, null, null));
        assertEquals(4000, ex.code);
        assertTrue(ex.getMessage().contains("现病史超过 2000 字（当前 2004 字）"), ex.getMessage());
        em.flush();
        em.clear();
        assertEquals("起病三天", jdbc.queryForObject(
                "select present_illness from outp_emr where registration_id = ?", String.class, rid));
        assertEquals(1, count("select count(*) from emr_version where emr_type = 'OUTP' and emr_id = ?", first.getId()));
    }

    @Test
    void exactlyAtLimitPassesAndSurrogatePairCountsAsOneChar() {
        Long docId = userId("admin");
        Long rid = visitFor(newPatient("顶"), docId);
        String rare = new String(Character.toChars(0x20000));   // 𠀀：UTF-16 两个 char，一个码点
        OutpEmr e = emr("字".repeat(511) + rare, rare.repeat(2000));
        e.setPastHistory("史".repeat(1000));
        e.setPhysicalExam("查".repeat(1000));
        e.setAdvice("嘱".repeat(1000));
        doctorStationService.saveEmr(rid, e, List.of(), docId, null, null);
        em.flush();
        assertEquals(2000, count("select char_length(present_illness) from outp_emr where registration_id = ?", rid));
        assertEquals(512, count("select char_length(chief_complaint) from outp_emr where registration_id = ?", rid));
    }

    // ==================== ④ 乙 D2/N10：住院未签名记录可修改 ====================

    private Long admit(String tag) {
        Long pid = newPatient(tag);
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' order by id limit 1", Long.class);
        assertNotNull(bedId, "测试库需要至少一张空床");
        Long id = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("1000"), "CASH", null).getId();
        em.flush();
        return id;
    }

    private int inpVersions(Long recordId) {
        return (int) count("select count(*) from emr_version where emr_type = 'INP' and emr_id = ?", recordId);
    }

    @Test
    void unsignedInpRecordCanBeEditedAndEachEditLeavesAManualVersion() {
        Authentication doc = doctorAuth("v79fix_inp_a");
        Long admId = admit("改");
        var saved = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "病程记录", "患者今日体温37.5℃"), doc);
        assertEquals(0, saved.getCode(), saved.getMessage());
        Long recordId = saved.getData().getId();
        em.flush();
        assertEquals(1, inpVersions(recordId));

        Authentication editor = doctorAuth("v79fix_inp_b");
        var r = inpEmrController.updateRecord(admId, recordId,
                new InpEmrController.UpdateRecordRequest(null, "患者今日体温37.5℃，咳嗽较前减轻，继续抗感染治疗。", null),
                editor);
        assertEquals(0, r.getCode(), r.getMessage());
        em.flush();
        em.clear();

        assertEquals("患者今日体温37.5℃，咳嗽较前减轻，继续抗感染治疗。", jdbc.queryForObject(
                "select content from inp_medical_record where id = ?", String.class, recordId));
        assertEquals("病程记录", jdbc.queryForObject(
                "select title from inp_medical_record where id = ?", String.class, recordId), "标题不传则不动");
        assertEquals(2, inpVersions(recordId), "修改未签名记录要多落一版");
        var v2 = jdbc.queryForMap("""
                select source, saved_by, content from emr_version
                where emr_type = 'INP' and emr_id = ? and version_no = 2
                """, recordId);
        assertEquals("MANUAL", v2.get("source"), "修改即一次手动保存，沿用既有 MANUAL");
        assertEquals(userId("v79fix_inp_b"), ((Number) v2.get("saved_by")).longValue(), "版本记修改人");
        assertTrue(String.valueOf(v2.get("content")).contains("继续抗感染治疗"));

        // 同内容再改一次：去重开着，不刷出一版一模一样的版本
        assertEquals(0, inpEmrController.updateRecord(admId, recordId,
                new InpEmrController.UpdateRecordRequest(null, "患者今日体温37.5℃，咳嗽较前减轻，继续抗感染治疗。", null),
                editor).getCode());
        em.flush();
        assertEquals(2, inpVersions(recordId));
    }

    @Test
    void roundRecordEditKeepsOpinionAndContentInStep() {
        Authentication doc = doctorAuth("v79fix_inp_r");
        Long admId = admit("房");
        Long recordId = inpEmrController.addRound(admId,
                new InpEmrController.RoundRequest("ATTENDING", "查房意见：继续观察", null, null), doc).getData().getId();
        em.flush();
        var r = inpEmrController.updateRecord(admId, recordId,
                new InpEmrController.UpdateRecordRequest("主治查房（改）", "查房意见：加用雾化", "同意"), doc);
        assertEquals(0, r.getCode(), r.getMessage());
        em.flush();
        em.clear();
        var row = jdbc.queryForMap("select title, content, round_opinion, superior_correction "
                + "from inp_medical_record where id = ?", recordId);
        assertEquals("主治查房（改）", row.get("title"));
        assertEquals("查房意见：加用雾化", row.get("content"));
        assertEquals("查房意见：加用雾化", row.get("round_opinion"), "查房记录正文即查房意见，两列须同步");
        assertEquals("同意", row.get("superior_correction"));
        assertEquals(2, inpVersions(recordId));
    }

    @Test
    void signedInpRecordCannotBeEditedAndPointsToAmendment() {
        Authentication doc = doctorAuth("v79fix_inp_s");
        Long admId = admit("签");
        Long recordId = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "病程记录", "原文"), doc).getData().getId();
        em.flush();
        assertEquals(0, inpEmrController.signRecord(admId, recordId, doc).getCode());
        em.flush();

        var r = inpEmrController.updateRecord(admId, recordId,
                new InpEmrController.UpdateRecordRequest(null, "改后的原文", null), doc);
        assertEquals(9103, r.getCode(), "已签名冻结沿用既有 9103");
        assertTrue(r.getMessage().contains("补正"), "提示须指向补正：" + r.getMessage());
        em.flush();
        em.clear();
        assertEquals("原文", jdbc.queryForObject("select content from inp_medical_record where id = ?",
                String.class, recordId));
        assertEquals(2, inpVersions(recordId), "被拒的修改不落版本（新建一版 + 签名一版）");
    }

    @Test
    void editIsScopedToTheAdmissionInThePathAndRejectsBlankContent() {
        Authentication doc = doctorAuth("v79fix_inp_x");
        Long admA = admit("甲");
        Long admB = admit("乙");
        Long recA = inpEmrController.addRecord(admA,
                new InpEmrController.SaveRecordRequest("PROGRESS", "病程记录", "甲的原文"), doc).getData().getId();
        em.flush();

        var cross = inpEmrController.updateRecord(admB, recA,
                new InpEmrController.UpdateRecordRequest(null, "串改", null), doc);
        assertEquals(9102, cross.getCode(), "经乙的住院路径改甲的记录 = 记录不存在（沿用 9102）");
        assertEquals(9102, inpEmrController.updateRecord(admA, -1L,
                new InpEmrController.UpdateRecordRequest(null, "x", null), doc).getCode());
        assertEquals(9101, inpEmrController.updateRecord(admA, recA,
                new InpEmrController.UpdateRecordRequest(null, "  ", null), doc).getCode());
        assertEquals(4000, inpEmrController.updateRecord(admA, recA,
                new InpEmrController.UpdateRecordRequest("题".repeat(101), "正文", null), doc).getCode(),
                "标题超列宽 100 → 4000，不撞库");
        em.flush();
        em.clear();
        assertEquals("甲的原文", jdbc.queryForObject("select content from inp_medical_record where id = ?",
                String.class, recA));
        assertEquals(1, inpVersions(recA));
    }

    @Test
    void amendOnUnsignedRecordPointsToTheRealEditEntry() {
        Authentication doc = doctorAuth("v79fix_inp_m");
        Long admId = admit("补");
        Long recordId = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "病程记录", "原文"), doc).getData().getId();
        em.flush();
        var r = inpEmrController.amendRecord(admId, recordId, new InpEmrController.AmendRequest("补", "因"), doc);
        assertEquals(9108, r.getCode());
        assertTrue(r.getMessage().contains("「修改」"), "9108 须指向时间线上真实存在的「修改」：" + r.getMessage());
    }

    // ==================== ⑤ 乙 D4：版本页带患者上下文、只读页登记复制来源 ====================

    @Test
    void versionListCarriesPatientForCopySource() {
        Long docId = userId("admin");
        Long pid = newPatient("源");
        Long rid = visitFor(pid, docId);
        OutpEmr saved = doctorStationService.saveEmr(rid, emr("咳嗽", "起病三天"), List.of(), docId, null, null);
        em.flush();
        String name = jdbc.queryForObject("select name from empi_patient where id = ?", String.class, pid);

        var outp = emrVersionController.list("OUTP", saved.getId(), null, null, false);
        assertEquals(0, outp.getCode(), outp.getMessage());
        assertEquals(pid, ((Number) outp.getData().get("patientId")).longValue());
        assertEquals(name, outp.getData().get("patientName"));

        Authentication doc = doctorAuth("v79fix_src");
        Long admId = admit("源住");
        Long inpPid = jdbc.queryForObject("select patient_id from inp_admission where id = ?", Long.class, admId);
        Long recordId = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "病程记录", "住院原文"), doc).getData().getId();
        em.flush();
        var inp = emrVersionController.list("INP", recordId, null, null, false);
        assertEquals(inpPid, ((Number) inp.getData().get("patientId")).longValue());
        assertNotNull(inp.getData().get("patientName"));

        var none = emrVersionController.list("OUTP", 987654321L, null, null, false);
        assertEquals(0, none.getCode());
        assertNull(none.getData().get("patientId"), "病历行不存在：患者为 null，不猜");
    }

    @Test
    void readOnlyEmrPagesRegisterCopySourceAndInpTimelineHasEditEntry() {
        String p360 = read("frontend/shell/src/views/cdr/Patient360View.vue");
        assertTrue(p360.contains("useEmrPasteGuard") && p360.contains("@copy=\"onCopy\""),
                "患者 360 正文区须挂复制来源登记");
        String ver = read("frontend/shell/src/views/outpatient/emr-version/EmrVersionView.vue");
        assertTrue(ver.contains("useEmrPasteGuard") && ver.contains("@copy=\"onCopy\"") && ver.contains("patientId"),
                "版本页正文区须挂复制来源登记并带该页患者");
        String inp = read("frontend/shell/src/views/inpatient/InpDoctorView.vue");
        assertTrue(inp.contains("client.put(") && inp.contains("startEdit"), "住院时间线须有走 PUT 的「修改」入口");
    }

    // ==================== ⑥ 审阅修补（三）：七条三方反驳材料点名的修补 ====================

    /**
     * B3-2：「提交」先暂存会把书写医师改成提交人（saveEmr 落的 doctorId 即书写人，v43 起的既定口径），
     * 于是他人打开别人的草稿点提交就签成了。后端 4023 对「直接签他人病历」照拦（这里钉住）；
     * 前端提交路径只在「工作区无病历 / 书写医师就是本人」时才先暂存，否则直签、让后端回 4023（源码扫描）。
     */
    @Test
    void nonAuthorSignStill4023AndSubmitPresavesOnlyOwnDraft() {
        Authentication author = doctorAuth("v79fix3_author");
        Authentication other = doctorAuth("v79fix3_other");
        Long rid = visitFor(newPatient("签他"), userId("v79fix3_author"));
        assertEquals(0, doctorStationController.saveEmr(rid,
                new DoctorStationController.SaveEmrRequest(emr("胸闷", "两天"), List.of()), author).getCode());
        em.flush();

        var r = doctorStationController.signEmr(rid, other);
        assertEquals(4023, r.getCode(), "非书写医师直接签名须 4023");
        em.flush();
        em.clear();
        var row = jdbc.queryForMap("select signature, doctor_id from outp_emr where registration_id = ?", rid);
        assertNull(row.get("signature"));
        assertEquals(userId("v79fix3_author"), ((Number) row.get("doctor_id")).longValue(), "被拒的签名不改书写医师");

        String sign = signEmrSource();
        assertTrue(sign.contains("presaveBeforeSign("), "提交前先暂存须先判书写医师是不是本人：\n" + sign);
        String util = read("frontend/shell/src/utils/emr-submit.ts");
        assertTrue(util.contains("export function presaveBeforeSign"), "判定抽成纯函数（有 vitest）");
    }

    /** B3-3：自动套用的默认模板一字未改，提交须拦下（修前有 4009 兜底，先暂存后没了） */
    @Test
    void submitRejectsUntouchedAutoTemplateAndDisablesButtonBeforePresave() {
        String sign = signEmrSource();
        assertTrue(sign.contains("untouchedDefaultTemplate("), "未改动的自动套用模板不得一键签名：\n" + sign);
        assertTrue(read("frontend/shell/src/utils/default-template.ts").contains("export function untouchedDefaultTemplate"));
        // B3 记录项：签名按钮的 loading 要在先暂存之前就置上，往返期间不能再点一次
        int busy = sign.indexOf("signing.value = true");
        int save = sign.indexOf("saveEmr()");
        assertTrue(busy >= 0 && save >= 0 && busy < save, "signing 须在暂存之前置真：\n" + sign);
    }

    /** B3-1 / A3-2e：三态键取值精确匹配小写 off|warn|block，与直比型读取端同口径；emr.version.gate 纳入 */
    @Test
    void triStateConfigKeysAcceptOnlyExactLowercase() {
        for (String key : List.of("emr.copy.cross_patient", "emr.gate.discharge", "emr.version.gate")) {
            for (String bad : List.of("BLOCK", "Block", " block", "block ", "　block", "", "xyz")) {
                assertEquals(1402, sysConfigController.update(key, bad).getCode(),
                        key + "=[" + bad + "] 须拒绝（直比型读取端会把它读成 warn）");
            }
            for (String good : List.of("off", "warn", "block")) {
                assertEquals(0, sysConfigController.update(key, good).getCode(), key + "=" + good);
                assertEquals(good, jdbc.queryForObject("select cfg_value from sys_config where cfg_key = ?",
                        String.class, key), "原样写库");
            }
        }
    }

    /** A3-1c：医技执行站对检查医嘱的文本结果没有审核环节，条目标题如实标出；取数不收紧 */
    @Test
    void execStationExamTextIsMarkedUnreviewed() {
        Authentication doc = doctorAuth("v79fix3_exec");
        Long rid = visitFor(newPatient("执"), userId("v79fix3_exec"));
        Long orderId = order(rid, "EXAM", "常规心电图", "EXECUTED");
        jdbc.update("insert into outp_order_report(order_id, result_text) values (?, ?)", orderId, "窦性心律，大致正常心电图");

        var seg = ref(rid, "EXAM", doc);
        assertEquals(1, seg.get("count"), "执行站文本照常列出（不改既有可见性）");
        var it = items(seg).get(0);
        assertTrue(String.valueOf(it.get("title")).contains("（执行站录入，未经审核）"), String.valueOf(it.get("title")));
        assertTrue(String.valueOf(it.get("text")).contains("窦性心律"));
        String drawer = read("frontend/shell/src/components/EmrRefDrawer.vue");
        assertTrue(drawer.contains("后者无审核环节"), "检查页签空态须如实说出执行站一支无审核");
        assertFalse(drawer.contains("未出报告或未审核的不在引用范围内"), "旧空态与执行站一支相反");
    }

    /** A3-2g：「当前正文与最后一版不一致」只在确实不一致时附；原因补上 warn 档的留痕写入失败 */
    @Test
    void compareCurrentNoticeSaysMismatchOnlyWhenCurrentDiffersFromLastVersion() {
        Long docId = userId("admin");
        Long rid = visitFor(newPatient("比"), docId);
        Long emrId = doctorStationService.saveEmr(rid, emr("咳嗽", "起病三天"), List.of(), docId, null, null).getId();
        em.flush();
        doctorStationService.saveEmr(rid, emr("咳嗽", "起病五天"), List.of(), docId, null, null);
        em.flush();

        var same = emrVersionController.compareCurrent("OUTP", emrId, 2);
        assertEquals(0, same.getCode(), same.getMessage());
        assertEquals(Boolean.TRUE, summaryOf(same.getData()).get("identical"));
        assertFalse(String.valueOf(same.getData().get("notice")).contains("不一致"),
                "两侧逐字段一致时不得同屏说「不一致」：" + same.getData().get("notice"));

        var fromV1 = emrVersionController.compareCurrent("OUTP", emrId, 1);
        assertEquals(Boolean.FALSE, summaryOf(fromV1.getData()).get("identical"), "v1 与当前（= v2）确有差异");
        assertFalse(String.valueOf(fromV1.getData().get("notice")).contains("不一致"),
                "当前正文与最后一版（v2）一致，左侧是更早的版本——差异是留痕里有记录的修改，不是接缝外改动");

        jdbc.update("update outp_emr set advice = '接缝外的改动' where id = ?", emrId);
        var drift = emrVersionController.compareCurrent("OUTP", emrId, 2);
        String notice = String.valueOf(drift.getData().get("notice"));
        assertTrue(notice.contains("不一致"), notice);
        assertTrue(notice.contains("留痕写入失败"), "原因须含 warn 档的写入失败：" + notice);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summaryOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("summary");
    }

    /** A3-2f / A2：住院空版本提示补上「签名前修改」这一写入动作 */
    @Test
    void inpEmptyNoticeNamesEditBeforeSign() {
        var none = emrVersionController.list("INP", 987654321L, null, null, false);
        assertEquals(0, none.getCode());
        String notice = String.valueOf(none.getData().get("notice"));
        assertTrue(notice.contains("签名前修改"), notice);
    }

    /** A3-2b：saved_on 与 saved_at 同取数据库时钟（上海日），同一行不再两个钟 */
    @Test
    void savedOnComesFromTheSameDatabaseClockAsSavedAt() {
        Long docId = userId("admin");
        Long rid = visitFor(newPatient("日"), docId);
        Long emrId = doctorStationService.saveEmr(rid, emr("咳嗽", "起病三天"), List.of(), docId, null, null).getId();
        em.flush();
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                select saved_on = (saved_at at time zone 'Asia/Shanghai')::date from emr_version
                where emr_type = 'OUTP' and emr_id = ? and version_no = 1
                """, Boolean.class, emrId), "业务日须是 saved_at 那一刻的上海日");
        String svc = read("modules/outpatient/src/main/java/cn/hip/outpatient/service/EmrVersionService.java");
        assertTrue(svc.contains("(now() at time zone 'Asia/Shanghai')::date"),
                "saved_on 在 SQL 里取库时钟的上海日（应用时钟与库时钟可偏数秒，午夜前后两列会差一天）");
        String view = read("frontend/shell/src/views/outpatient/emr-version/EmrVersionView.vue");
        assertTrue(view.contains("sourceLabel("), "版本页来源列显示中文");
    }

    /** A2-U3：接诊队列跨日期模式要能区分不同日期的同名就诊——返回体追加 visitDate */
    @Test
    void worklistRowsCarryVisitDate() {
        Long rid = visitFor(newPatient("队"), userId("admin"));
        LocalDate today = BusinessDates.today();
        for (var r : List.of(doctorStationController.worklist(today, null, null, null, null, null),
                doctorStationController.worklist(null, today, today, null, null, null))) {
            assertEquals(0, r.getCode(), r.getMessage());
            var row = r.getData().stream().filter(m -> rid.equals(m.get("registrationId"))).findFirst().orElseThrow();
            assertEquals(today.toString(), String.valueOf(row.get("visitDate")));
        }
        String ds = read("frontend/shell/src/views/outpatient/DoctorStationView.vue");
        assertTrue(ds.contains("label=\"就诊日期\""), "跨日期勾选时显示就诊日期列");
    }

    /** B1-E1/E2、B2：住院放行档显示档位；医生站两个抽屉里复制的本患者资料登记来源 */
    @Test
    void inpShowsOffTierAndDoctorStationDrawersRegisterCopySource() {
        String inp = read("frontend/shell/src/views/inpatient/InpDoctorView.vue");
        assertFalse(inp.contains("v-if=\"copyMode !== 'off'\""), "住院放行档也须显示档位");
        assertTrue(inp.contains("'放行'"), "住院三档标签与门诊同");
        String ds = read("frontend/shell/src/views/outpatient/DoctorStationView.vue");
        int h = ds.indexOf("title=\"历史就诊\"");
        int hEnd = ds.indexOf("</el-drawer>", h);
        assertTrue(h > 0 && ds.substring(h, hEnd).contains("@copy=\"onCopy\""), "「历史就诊」抽屉正文须登记复制来源");
        int d = ds.indexOf("<EmrRefDrawer");
        assertTrue(d > 0 && ds.substring(d, ds.indexOf("/>", d)).contains("@copy=\"onCopy\""), "引用抽屉须把复制事件交给 onCopy");
        String drawer = read("frontend/shell/src/components/EmrRefDrawer.vue");
        assertTrue(drawer.contains("emit('copy'") && drawer.contains("tab.value === 'HISTORY'"),
                "引用抽屉「历史病历」页签正文复制时上抛");
    }

    private static String signEmrSource() {
        String ds = read("frontend/shell/src/views/outpatient/DoctorStationView.vue");
        int a = ds.indexOf("async function signEmr()");
        int b = ds.indexOf("function warnIfLeavingUnsigned", a);
        assertTrue(a > 0 && b > a, "找不到 signEmr");
        return ds.substring(a, b);
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
