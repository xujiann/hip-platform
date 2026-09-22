package cn.hip.server;

import cn.hip.inpatient.service.CountersignService;
import cn.hip.inpatient.web.InpEmrController;
import cn.hip.outpatient.service.EmrVersionService;
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
 * v73：住院病历版本留痕的写入方。
 *
 * <p><b>缺陷是「读方存在、写方不存在」</b>：{@code EmrVersionService} 完整支持住院侧
 * （INP 常量、INP_FIELDS、独占锁命名空间、按值传参入口都在，类注释还写着「住院接缝由主控
 * 在 InpEmrController 侧调用」），但那个接缝从没被接上；而 {@code CountersignService}
 * 已经在读 {@code emr_type='INP'} 的版本行，于是住院审签永远落到 NO_VERSION_ROW。
 *
 * <p><b>它不报错，只是永远走空分支</b>——所以必须用「写完能读到」这条端到端断言来钉，
 * 只断言「写入方被调用了」不足以证明链路通。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V73InpEmrVersionTest {

    @Autowired InpEmrController inpEmrController;
    @Autowired CountersignService countersignService;
    @Autowired cn.hip.inpatient.service.InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;

    private Authentication docAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject(
                "select id from inp_bed where status = 'FREE' order by id limit 1", Long.class);
        assertNotNull(bedId, "测试库需要至少一张空床");
        Long id = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("1000"), "CASH", null).getId();
        em.flush();
        return id;
    }

    /** 列名是 emr_id 不是 record_id：emr_version 是多态表，(emr_type, emr_id) 共同定位。
     *  审签侧的列名走配置（V158 已种 emr_id），此处直查表故用真实列名。 */
    private int versionRows(Long recordId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from emr_version where emr_type = 'INP' and emr_id = ?",
                Integer.class, recordId);
        return n == null ? 0 : n;
    }

    /** 方法论⑤：直写过配置的测试类必须收尾清缓存，否则毒留给后面的测试类。 */
    @org.junit.jupiter.api.AfterEach
    void evictConfigCache() {
        configReader.evictAll();
    }

    // ==================== ① 写入方（本版补上的那一条） ====================

    /**
     * 新增住院病历后，<b>版本表里要有这一版，而且审签取得到它</b>。
     *
     * <p>端到端断言：写入方接上 ⇒ 读方的 NO_VERSION_ROW 分支不再被命中。
     * 这是本版的核心——缺陷本身就是「两端各自正确、中间没接上」。
     */
    @Test
    void savingAnInpatientRecordLeavesAVersionRowThatCountersignCanResolve() {
        Authentication doc = docAuth("v73doc_a");
        Long admId = admit("V73留痕");

        var saved = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("ADMISSION", "V73入院记录", "主诉：发热三天"), doc);
        assertEquals(0, saved.getCode(), saved.getMessage());
        Long recordId = saved.getData().getId();
        em.flush();
        em.clear();

        assertEquals(1, versionRows(recordId), "保存住院病历后应落一版——这正是此前缺的那一步");

        var ref = countersignService.resolveVersion(recordId, "主诉：发热三天");
        assertNotEquals("NO_VERSION_ROW", ref.source(),
                "写入方接上后，审签不应再落到「没有版本记录」分支：" + ref.warnings());
        assertNotNull(ref.versionId(), "审签应能绑定到具体版本行");
        assertEquals(1, ref.versionNo(), "首版版本号为 1");
    }

    /** 三级查房也落版本（它是另一条独立的写入路径，不能只接主路径）。 */
    @Test
    void roundRecordAlsoLeavesAVersionRow() {
        Authentication doc = docAuth("v73doc_b");
        Long admId = admit("V73查房");

        var saved = inpEmrController.addRound(admId,
                new InpEmrController.RoundRequest("ATTENDING", "查房意见：继续观察", null, null), doc);
        assertEquals(0, saved.getCode(), saved.getMessage());
        em.flush();
        em.clear();

        assertEquals(1, versionRows(saved.getData().getId()), "三级查房同样要留痕");
    }

    /** 签名再落一版，来源标记为 SUBMIT——与门诊侧同口径。 */
    @Test
    void signingAddsASubmitVersionLikeTheOutpatientSide() {
        Authentication doc = docAuth("v73doc_c");
        Long admId = admit("V73签名");

        Long recordId = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "V73病程", "病情平稳"), doc).getData().getId();
        em.flush();
        assertEquals(0, inpEmrController.signRecord(admId, recordId, doc).getCode());
        em.flush();
        em.clear();

        assertEquals(2, versionRows(recordId), "保存一版 + 签名一版");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select source from emr_version where emr_type = 'INP' and emr_id = ? order by version_no",
                recordId);
        assertEquals(EmrVersionService.MANUAL, rows.get(0).get("source"));
        assertEquals(EmrVersionService.SUBMIT, rows.get(1).get("source"), "签名那一版来源须为 SUBMIT");
    }

    // ==================== ② 不该被牵动的既有行为 ====================

    /**
     * 补正路径**不**叠版本行：补正是签名后的独立留痕机制，自己已完整留痕，
     * 门诊侧也没有为补正落版本。本版不得顺手给它加上。
     */
    @Test
    void amendDoesNotAddAVersionRow() {
        Authentication doc = docAuth("v73doc_d");
        Long admId = admit("V73补正");
        Long recordId = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "V73待补正", "原文"), doc).getData().getId();
        em.flush();
        inpEmrController.signRecord(admId, recordId, doc);
        em.flush();
        int before = versionRows(recordId);

        assertEquals(0, inpEmrController.amendRecord(admId, recordId,
                new InpEmrController.AmendRequest("更正：原文有笔误", "录入笔误"), doc).getCode());
        em.flush();
        em.clear();

        assertEquals(before, versionRows(recordId), "补正不得再叠一层版本行");
    }

    /** 留痕失败不得连累病历保存——gate 默认 warn，保存照常成功。 */
    @Test
    void recordSaveStillSucceedsEvenIfVersioningIsOff() {
        Authentication doc = docAuth("v73doc_e");
        Long admId = admit("V73关闭留痕");
        jdbc.update("insert into sys_config(cfg_key, cfg_value, remark) values ('emr.version.gate','off','v73 用例') "
                + "on conflict (cfg_key) do update set cfg_value = 'off'");
        // 方法论⑤：事务内直写 sys_config 会被 ConfigReader 的缓存挡住（缓存里还是 warn），
        // 不 evict 的话这条用例会假绿——写的是 off、跑的还是 warn。
        configReader.evictAll();

        var saved = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "V73关闭", "正文"), doc);
        assertEquals(0, saved.getCode(), "留痕关闭时病历照常保存：" + saved.getMessage());
        em.flush();
        em.clear();
        assertEquals(0, versionRows(saved.getData().getId()), "gate=off 时不落版本行");
    }
}
