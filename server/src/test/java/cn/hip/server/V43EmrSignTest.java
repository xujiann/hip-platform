package cn.hip.server;

import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.DoctorStationController;
import cn.hip.outpatient.web.DoctorStationController.AmendRequest;
import cn.hip.outpatient.web.DoctorStationController.SaveEmrRequest;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v43 车道A：门诊病历签名入口（偏离表 991★）。
 *
 * <p><b>本类要钉死的价值是「路径可达」而不是「端点存在」</b>：
 * {@code POST /{registrationId}/emr/sign} 自 1.0 起就在，
 * 但前端从未有过签名按钮——而「已签名才显示」的补正区块（4016/4018/4019 一整条法定留痕链）
 * 依赖签名态，于是补正在正常演示路径上<b>永远走不到</b>。
 * §① 就是把「写病历 → 签名 → 补正可用」这条链整条走通并断言落库，
 * 使前端一旦再次丢掉签名入口，回归用例先红。
 *
 * <p>§② 是本版新补的两条签名校验（4022 空病历 / 4023 非书写医师）与既有 4010/4008 的守卫，
 * 各自独立成 @Test：服务层抛 BizException 会把参与中的测试事务标记 rollback-only，
 * 不能与「抛错后仍需成功提交」的步骤混在同一个 @Test（同 ClinicalClosureTest 的约定）。
 *
 * <p><b>契约保护</b>：{@link #signSuccessBodyShapeUnchanged()} 钉死签名成功返回体仍是
 * {signature, signedAt} 两键——e2e-phase48 与 e2e-emr-closure 都直接消费 {@code sig['signature']}。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V43EmrSignTest {

    @Autowired DoctorStationController doctorStationController;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    /** 造账号并返回其 Authentication（4023「仅书写医师本人可签名」要求有真实 sys_user 行） */
    private Authentication userAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    /** 建患者 + 排班 + 挂号 + 接诊，返回 registrationId */
    private Long visitedRegistration() {
        Patient p = new Patient();
        p.setName("签名用例" + System.nanoTime());
        p.setSex("U");
        Long pid = patientService.register(p).getId();

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);

        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    private static OutpEmr emrOf(String chiefComplaint) {
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint(chiefComplaint);
        e.setPresentIllness("受凉后起病 2 天");
        e.setAdvice("对症治疗");
        return e;
    }

    private Map<String, Object> emrRow(Long registrationId) {
        entityManager.flush();
        return jdbc.queryForMap("select * from outp_emr where registration_id = ?", registrationId);
    }

    // ==================== ① 核心回归：签名把补正这条路打通 ====================

    /**
     * 本条的价值所在——纯成功路径，全程不抛错：
     * 写病历 → 签名（signature/signed_at 落库）→ <b>补正接口此时才可用</b> → 补正留痕可查。
     *
     * <p>此前后两段之间断链：界面没有签名按钮，医生永远签不了名，
     * 于是 amendEmr 只会返 4018「未签名无需补正」，整个补正功能在正常路径上不可达。
     */
    @Test
    void signMakesTheAmendPathReachable() {
        Authentication doc = userAuth("v43doc_a");
        Long rid = visitedRegistration();

        assertEquals(0, doctorStationController.saveEmr(rid,
                new SaveEmrRequest(emrOf("咽痛发热2天"), List.of()), doc).getCode());

        var signed = doctorStationController.signEmr(rid, doc);
        assertEquals(0, signed.getCode(), signed.getMessage());

        var row = emrRow(rid);
        assertNotNull(row.get("signature"), "签名值必须落库");
        assertNotNull(row.get("signed_at"), "签名时间必须落库");

        // 签名前这一步返 4018；签名后才通——这正是本条要证明的「路径可达」
        var amend = doctorStationController.amendEmr(rid,
                new AmendRequest("主诉应为「咽痛发热3天」", "录入笔误"), doc);
        assertEquals(0, amend.getCode(), amend.getMessage());

        var amendments = doctorStationController.amendments(rid).getData();
        assertEquals(1, amendments.size(), "补正应留痕一条");
        assertEquals("录入笔误", amendments.get(0).get("reason"));
        assertNotNull(amendments.get(0).get("amended_by"), "补正人必须留痕");
    }

    /** 契约保护：签名成功返回体仍是 {signature, signedAt}（两个 E2E 直接消费 signature 键） */
    @Test
    void signSuccessBodyShapeUnchanged() {
        Authentication doc = userAuth("v43doc_b");
        Long rid = visitedRegistration();
        doctorStationController.saveEmr(rid, new SaveEmrRequest(emrOf("头痛1天"), List.of()), doc);

        var r = doctorStationController.signEmr(rid, doc);
        assertEquals(0, r.getCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getData();
        assertEquals(java.util.Set.of("signature", "signedAt"), body.keySet(), "返回体形状不得变动");
        assertNotNull(body.get("signature"));
    }

    /** 前端冻结态的后端依据：workspace 附带签名人姓名，emr 本体形状不动 */
    @Test
    void workspaceCarriesSignerNameForFrozenView() {
        Authentication doc = userAuth("v43doc_c");
        Long rid = visitedRegistration();
        doctorStationController.saveEmr(rid, new SaveEmrRequest(emrOf("咳嗽3天"), List.of()), doc);
        doctorStationController.signEmr(rid, doc);

        var ws = doctorStationController.workspace(rid).getData();
        assertEquals("v43doc_c医生", ws.get("emrSignerName"), "冻结态要能显示谁签的");
        assertNotNull(ws.get("emr"));
        assertTrue(ws.containsKey("diagnoses") && ws.containsKey("orders"), "既有三键不得丢");
    }

    /** 队列上的诊毕未签标志（前端「未签」红标与切换患者提示的数据来源） */
    @Test
    void worklistFlagsWrittenButUnsignedEmr() {
        Authentication doc = userAuth("v43doc_d");
        Long rid = visitedRegistration();
        doctorStationController.saveEmr(rid, new SaveEmrRequest(emrOf("腹痛半日"), List.of()), doc);
        entityManager.flush();

        var mine = doctorStationController.worklist(BusinessDates.today()).getData().stream()
                .filter(m -> rid.equals(m.get("registrationId"))).findFirst().orElseThrow();
        assertEquals(true, mine.get("emrWritten"));
        assertEquals(false, mine.get("emrSigned"), "写了没签必须标未签");
    }

    // ==================== ② 签名校验（各自独立，抛错不与成功步骤同事务） ====================

    /** 重复签名被拒（既有 4010，本版未改码） */
    @Test
    void repeatedSignRejected() {
        Authentication doc = userAuth("v43doc_e");
        Long rid = visitedRegistration();
        doctorStationController.saveEmr(rid, new SaveEmrRequest(emrOf("发热1天"), List.of()), doc);
        assertEquals(0, doctorStationController.signEmr(rid, doc).getCode());

        assertEquals(4010, doctorStationController.signEmr(rid, doc).getCode(), "已签名不可重复签名");
    }

    /** 非书写医师本人签名被拒（v43 新增 4023） */
    @Test
    void nonAuthorDoctorCannotSign() {
        Authentication author = userAuth("v43doc_f");
        Authentication other = userAuth("v43doc_g");
        Long rid = visitedRegistration();
        doctorStationController.saveEmr(rid, new SaveEmrRequest(emrOf("胸闷2天"), List.of()), author);

        var r = doctorStationController.signEmr(rid, other);
        assertEquals(4023, r.getCode(), "别人写的病历不能替签");
        assertNull(emrRow(rid).get("signature"), "被拒时不得落任何签名值");
    }

    /** 空病历签名被拒（v43 新增 4022） */
    @Test
    void blankEmrCannotBeSigned() {
        Authentication doc = userAuth("v43doc_h");
        Long rid = visitedRegistration();
        // 五段正文全空（含只有空白字符）也算空——签白纸不成立
        OutpEmr blank = new OutpEmr();
        blank.setChiefComplaint("   ");
        assertEquals(0, doctorStationController.saveEmr(rid,
                new SaveEmrRequest(blank, List.of()), doc).getCode());

        assertEquals(4022, doctorStationController.signEmr(rid, doc).getCode(), "空病历不可签名");
    }

    /** 病历不存在时签名被拒（既有 4009，本版未改码） */
    @Test
    void signWithoutEmrRejected() {
        Authentication doc = userAuth("v43doc_i");
        Long rid = visitedRegistration();
        assertEquals(4009, doctorStationController.signEmr(rid, doc).getCode());
    }

    /** 签名即冻结原文（既有 4008，签名入口上线后这条守卫才真正会被触发） */
    @Test
    void signedEmrIsFrozenForEdit() {
        Authentication doc = userAuth("v43doc_j");
        Long rid = visitedRegistration();
        doctorStationController.saveEmr(rid, new SaveEmrRequest(emrOf("眩晕3天"), List.of()), doc);
        assertEquals(0, doctorStationController.signEmr(rid, doc).getCode());

        assertEquals(4008, doctorStationController.saveEmr(rid,
                new SaveEmrRequest(emrOf("篡改"), List.of()), doc).getCode(), "签名后原文不可改");
    }
}
