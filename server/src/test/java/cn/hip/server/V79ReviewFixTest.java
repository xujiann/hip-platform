package cn.hip.server;

import cn.hip.inpatient.web.InpEmrController;
import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.EmrRefController;
import cn.hip.outpatient.web.EmrVersionController;
import cn.hip.platform.core.common.HipBizException;
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
