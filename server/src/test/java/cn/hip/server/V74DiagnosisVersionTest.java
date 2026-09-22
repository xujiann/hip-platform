package cn.hip.server;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v74：门诊诊断修改留痕。
 *
 * <p><b>缺陷</b>：保存病历时诊断是物理删除后整批重插，而版本快照只含五段正文、
 * 全仓无诊断历史表、审计日志不记请求体——医生把诊断由 A 改成 B，<b>A 三处皆无痕</b>。
 *
 * <p><b>为什么必须修</b>：序 981★ 已对外答「诊断数据自动进入本次就诊病历」，
 * 那么 994★「病历内容历次更新痕迹保留」就得覆盖它。这是我方两条应答之间的矛盾。
 *
 * <p>本类的核心断言是<b>「改完还能查到旧值」</b>——只断言「写了一行」不足以证明留痕有用。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V74DiagnosisVersionTest {

    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    /** 方法论⑤：直写过配置的测试类必须收尾清缓存。 */
    @AfterEach
    void evictConfigCache() {
        configReader.evictAll();
    }

    private Long visit() {
        Patient p = new Patient();
        p.setName("V74诊断" + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    private static OutpEmr emrOf(String chief) {
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint(chief);
        e.setPresentIllness("起病两天");
        return e;
    }

    private static OutpDiagnosis diag(String code, String name) {
        OutpDiagnosis d = new OutpDiagnosis();
        d.setIcdCode(code);
        d.setIcdName(name);
        return d;
    }

    private List<Map<String, Object>> versionsOf(Long registrationId) {
        return jdbc.queryForList(
                "select version_no, content from outp_diagnosis_version "
                        + "where registration_id = ? order by version_no", registrationId);
    }

    // ==================== ① 缺陷本身：改完要能查到旧值 ====================

    /**
     * 诊断由 A 改成 B 之后，<b>A 仍然查得到</b>。
     *
     * <p>这一条就是缺陷本身——此前 A 在库里（被物理删除）、日志里（不记请求体）、
     * 版本表里（只含五段正文）三处都没有。
     */
    @Test
    void changingADiagnosisLeavesTheOldValueRecoverable() {
        Long rid = visit();
        doctorStationService.saveEmr(rid, emrOf("咳嗽"), List.of(diag("J18.9", "肺炎")), null);
        em.flush();
        doctorStationService.saveEmr(rid, emrOf("咳嗽"), List.of(diag("J20.9", "急性支气管炎")), null);
        em.flush();
        em.clear();

        var versions = versionsOf(rid);
        assertEquals(2, versions.size(), "改了一次诊断应有两版：改前与改后");
        assertTrue(String.valueOf(versions.get(0).get("content")).contains("肺炎"),
                "第 1 版必须留着被改掉的那个诊断——这正是此前查不到的东西：" + versions.get(0));
        assertTrue(String.valueOf(versions.get(1).get("content")).contains("急性支气管炎"),
                "第 2 版是改后的诊断：" + versions.get(1));

        // 当前库里只剩新诊断（既有的删重插行为不变）
        assertEquals(List.of("J20.9"), jdbc.queryForList(
                "select icd_code from outp_diagnosis where registration_id = ?", String.class, rid));
    }

    /** 多条诊断与主诊断次序一并留痕（主诊断是哪条，改动后也要能追溯）。 */
    @Test
    void primaryDiagnosisOrderIsPartOfTheSnapshot() {
        Long rid = visit();
        doctorStationService.saveEmr(rid, emrOf("发热"),
                List.of(diag("J18.9", "肺炎"), diag("E11.9", "2型糖尿病")), null);
        em.flush();
        doctorStationService.saveEmr(rid, emrOf("发热"),
                List.of(diag("E11.9", "2型糖尿病"), diag("J18.9", "肺炎")), null);
        em.flush();
        em.clear();

        var versions = versionsOf(rid);
        assertEquals(2, versions.size(), "主诊断换人也是一次真实修改，必须落新版");
        assertNotEquals(versions.get(0).get("content"), versions.get(1).get("content"),
                "两版内容应不同（主诊断次序变了）");
    }

    // ==================== ② 不该产生的噪音 ====================

    /**
     * 只改正文、没动诊断时<b>不落新版</b>。
     *
     * <p>不去重的话，医生每保存一次正文就刷一条诊断版本，真正的诊断修改会被淹掉——
     * 那不是留痕，是噪音（病历版本表立的就是这条规矩，此处同口径）。
     */
    @Test
    void savingWithoutTouchingDiagnosesDoesNotCreateNoise() {
        Long rid = visit();
        var same = List.of(diag("J18.9", "肺炎"));
        doctorStationService.saveEmr(rid, emrOf("咳嗽"), same, null);
        em.flush();
        doctorStationService.saveEmr(rid, emrOf("咳嗽加重"), List.of(diag("J18.9", "肺炎")), null);
        em.flush();
        em.clear();

        assertEquals(1, versionsOf(rid).size(), "诊断没变就不该落第二版");
    }

    /** 留痕关闭时不落版本，且病历照常保存（与病历版本接缝同一个开关、同一口径）。 */
    @Test
    void versioningOffMeansNoRowsButSaveStillSucceeds() {
        Long rid = visit();
        jdbc.update("insert into sys_config(cfg_key, cfg_value, remark) values ('emr.version.gate','off','v74 用例') "
                + "on conflict (cfg_key) do update set cfg_value = 'off'");
        configReader.evictAll();   // 方法论⑤：不 evict 会读到缓存里的 warn，这条用例就假绿了

        assertDoesNotThrow(() -> doctorStationService.saveEmr(
                rid, emrOf("关闭留痕"), List.of(diag("J18.9", "肺炎")), null));
        em.flush();
        em.clear();
        assertEquals(0, versionsOf(rid).size(), "gate=off 时不落诊断版本");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from outp_diagnosis where registration_id = ?", Integer.class, rid),
                "但病历与诊断本身照常保存");
    }

    /** 没有诊断的就诊不落版本——空诊断不是一次「修改」。 */
    @Test
    void savingWithNoDiagnosesRecordsNothing() {
        Long rid = visit();
        doctorStationService.saveEmr(rid, emrOf("仅正文"), List.of(), null);
        em.flush();
        em.clear();
        assertEquals(0, versionsOf(rid).size(), "一条诊断都没有时不必留痕");
    }

    /**
     * <b>留痕真失败时，病历与诊断照常保存。</b>
     *
     * <p>方法论②：改失败路径必须验证**修复后的失败路径**。只验 gate=off 不算——
     * 那条在任何 SQL 之前就返回了，根本走不到会出错的地方。
     *
     * <p>这条用例抓住过真问题：初版实现漏了 savepoint，留痕一出错 PG 就把整个事务判 aborted，
     * 医生的病历跟着一起存不进去。PG 里 Java 层 catch 住异常**救不回**已 aborted 的事务。
     *
     * <p>制造失败的方式是在事务内把表改名（PG 支持事务内 DDL，用例结束随事务回滚）——
     * 比 mock 更接近真实故障：表真的不可写了。
     */
    @Test
    void aRealVersioningFailureDoesNotBlockTheRecordSave() {
        Long rid = visit();
        jdbc.execute("alter table outp_diagnosis_version rename to outp_diagnosis_version_v74tmp");
        try {
            assertDoesNotThrow(() -> doctorStationService.saveEmr(
                    rid, emrOf("留痕故障"), List.of(diag("J18.9", "肺炎")), null),
                    "留痕写不进去时，病历保存不得被连累");
            em.flush();
        } finally {
            jdbc.execute("alter table outp_diagnosis_version_v74tmp rename to outp_diagnosis_version");
        }
        em.clear();

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from outp_emr where registration_id = ?", Integer.class, rid),
                "病历必须已落库");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from outp_diagnosis where registration_id = ?", Integer.class, rid),
                "诊断也必须已落库");
        assertEquals(0, versionsOf(rid).size(), "留痕失败时自然没有版本行，但这不影响上面两项");
    }
}