package cn.hip.server;

import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.EmrVersionService;
import cn.hip.outpatient.service.EmrVersionService.RecordResult;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.EmrVersionController;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v53 车道 V1：病历修订留痕与版本管理。
 *
 * <p>本类<b>不测</b>「写一版能读回来」这类同义反复。本版的判据是<b>「能不能证明」</b>，
 * 真正会让举证失败的是六类静默失败，每一类单独立案：
 * <ol>
 *   <li><b>伪造初版</b>——历史病历被系统「补」上一条从未存在过的 v1。测的是：不补，且读路径零写入。</li>
 *   <li><b>warn 档连累医生</b>——留痕写失败把整个事务判死，医生连病历都存不进去。
 *       PG 里一条约束违例就让后续所有语句 25P02，没有 savepoint 这条根本做不到。</li>
 *   <li><b>并发撞版本号把异常抛给医生</b>——唯一键会拒，问题是拒了之后医生看见什么。</li>
 *   <li><b>快照被截断</b>——截断的快照拿去举证等于伪证。</li>
 *   <li><b>差异看不出改了什么</b>——两段文本丢给人自己比，等于没做对比。</li>
 *   <li><b>留下可篡改/可回滚的入口</b>——能改的留痕在法庭上没有价值。用反射钉死。</li>
 * </ol>
 *
 * <p>夹具刻意<b>不建 outp_emr 行</b>（除两个确实需要主表的用例）：{@code emr_version.emr_id}
 * 按 V157 的设计<b>没有外键</b>——留痕不该随主表行被级联删掉。用合成 id 正好把这个设计点也覆盖到。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V53EmrVersionTest {

    @Autowired EmrVersionService service;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired DoctorStationService doctorStationService;

    /** 合成病历 id：每个用例一个，互不干扰（emr_version 对 emr_id 无外键，见类注释） */
    private static final AtomicLong SYNTH = new AtomicLong(900_000_000L);

    private long synth() {
        return SYNTH.incrementAndGet();
    }

    @AfterEach
    void restoreConfig() {
        // ConfigReader 有 30 秒缓存：测试改过 sys_config 后必须失效，否则脏值会漏进下一个用例。
        // 事务回滚只还原库里的行，还原不了进程内的缓存。
        setCfg(EmrVersionService.GATE_KEY, "warn");
        setCfg(EmrVersionService.DEDUP_KEY, "on");
        setCfg(EmrVersionService.MAX_CHARS_KEY, "1000000");
    }

    private void setCfg(String key, String value) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, key);
        configReader.evict(key);
    }

    private static Map<String, String> outp(String cc, String pi, String ph, String pe, String ad) {
        var m = new LinkedHashMap<String, String>();
        m.put("chiefComplaint", cc);
        m.put("presentIllness", pi);
        m.put("pastHistory", ph);
        m.put("physicalExam", pe);
        m.put("advice", ad);
        return m;
    }

    private long countVersions(long emrId) {
        Long n = jdbc.queryForObject(
                "select count(*) from emr_version where emr_type = 'OUTP' and emr_id = ?", Long.class, emrId);
        return n == null ? 0 : n;
    }

    // ==================================================================
    // 一、留痕本身：版本号、内容、时刻口径
    // ==================================================================

    @Test
    void versionsAccumulateAndCarryTheDisciplinedTimestamps() {
        long emrId = synth();
        LocalDate before = BusinessDates.today();

        var r1 = service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("咳嗽3天", "受凉后起病", null, "咽红", null), EmrVersionService.MANUAL, null);
        var r2 = service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("咳嗽5天", "受凉后起病，加重2天", null, "咽红", "对症"), EmrVersionService.MANUAL, null);
        var r3 = service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("咳嗽5天", "受凉后起病，加重2天", "否认高血压", "咽红", "对症"),
                EmrVersionService.SUBMIT, null);

        LocalDate after = BusinessDates.today();

        assertTrue(r1.recorded(), "第一版应落库：" + r1.warnings());
        assertEquals(1, r1.versionNo());
        assertEquals(2, r2.versionNo());
        assertEquals(3, r3.versionNo());
        assertNotEquals(r1.contentHash(), r2.contentHash(), "内容不同则摘要必须不同");

        var rows = jdbc.queryForList("""
                select version_no, source, content, content_len, content_hash, saved_by, saved_at,
                       saved_on::text as saved_on
                from emr_version where emr_type = 'OUTP' and emr_id = ? order by version_no
                """, emrId);
        assertEquals(3, rows.size());

        for (var row : rows) {
            String content = (String) row.get("content");
            assertNotNull(content, "快照永远写出规范化 JSON，即便字段全空——「当时有哪些字段」本身也是证据");
            assertEquals(content.length(), ((Number) row.get("content_len")).intValue(),
                    "content_len 必须等于所存快照的字符数，否则容量统计与超限判定两套口径");
            assertNull(row.get("saved_by"), "无登录上下文时如实落 null，绝不回填成当班医生");

            var at = ((java.sql.Timestamp) row.get("saved_at")).toInstant();
            assertEquals(0, at.getNano() % 1000,
                    "saved_at 必须已 truncatedTo(MICROS)：PG timestamptz 只存微秒且四舍五入，"
                            + "不截断会读出比写入晚最多 500ns 的值（本仓已炸过四次）");

            // saved_on 走业务时区（Asia/Shanghai），不是 date(saved_at)——跨 00:00–08:00 两者差一天
            LocalDate on = LocalDate.parse((String) row.get("saved_on"));
            assertTrue(!on.isBefore(before) && !on.isAfter(after),
                    "saved_on 应为 BusinessDates.today()，实得 " + on);
        }

        // 快照键按字典序（规范化，供 content_hash 稳定）
        String first = (String) rows.get(0).get("content");
        assertTrue(first.indexOf("\"advice\"") < first.indexOf("\"chiefComplaint\""),
                "规范化 JSON 的键必须按字典序，否则同样的内容会算出不同的 content_hash：" + first);
    }

    /** Java 侧与 SQL 侧摘要必须逐字节同源——两边同源这件事要被断言，不能靠口头约定。 */
    @Test
    void javaAndSqlDigestAgree() {
        long emrId = synth();
        service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("发热伴寒战", "四十度、乏力、纳差", "否认糖尿病", "T39.8℃", "血常规＋CRP"),
                EmrVersionService.MANUAL, null);

        var row = jdbc.queryForMap("""
                select content, content_hash,
                       encode(sha256(convert_to(content, 'UTF8')), 'hex') as sql_hash
                from emr_version where emr_type = 'OUTP' and emr_id = ?
                """, emrId);
        assertEquals(row.get("sql_hash"), row.get("content_hash"),
                "Java 与 PG 两侧的 SHA-256 必须一致（都对 UTF-8 字节做摘要再小写 hex）");
        assertEquals(row.get("sql_hash"), EmrVersionService.sha256Hex((String) row.get("content")));
    }

    // ==================================================================
    // 二、去重：治自动保存刷屏，但不许把签名那一版吞掉
    // ==================================================================

    @Test
    void dedupSuppressesIdenticalAutoSaveButNeverTheSubmit() {
        long emrId = synth();
        var fields = outp("头痛1周", "间断胀痛", null, "神清", null);

        var v1 = service.recordVersion(EmrVersionService.OUTP, emrId, fields, EmrVersionService.MANUAL, null);
        var v2 = service.recordVersion(EmrVersionService.OUTP, emrId, fields, EmrVersionService.AUTO, null);
        var v3 = service.recordVersion(EmrVersionService.OUTP, emrId, fields, EmrVersionService.AUTO, null);

        assertEquals(EmrVersionService.ST_RECORDED, v1.status());
        assertEquals(EmrVersionService.ST_DEDUPED, v2.status(),
                "内容逐字节没变的自动保存不该落新版——医生泡杯茶回来刷出 20 条一样的版本，"
                        + "那不是留痕，是把真正的修改淹掉");
        assertEquals(EmrVersionService.ST_DEDUPED, v3.status());
        assertEquals(1, v2.versionNo(), "被去重时应回指现存的那一版，而不是回 null");
        assertEquals(1, countVersions(emrId));

        // SUBMIT 绕开去重：「签的是哪一版」是本功能最要紧的一条事实，不能被去重吞掉。
        // 这不是伪造——签名时刻的内容确实是这样，这一版真实存在过。
        var submit = service.recordVersion(EmrVersionService.OUTP, emrId, fields, EmrVersionService.SUBMIT, null);
        assertEquals(EmrVersionService.ST_RECORDED, submit.status(),
                "内容与上一版相同也必须落 SUBMIT 版：否则签名冻结的那一版在版本表里没有对应行");
        assertEquals(2, submit.versionNo());
        assertEquals(2, countVersions(emrId));
    }

    @Test
    void dedupOffLandsEveryCall() {
        setCfg(EmrVersionService.DEDUP_KEY, "off");
        long emrId = synth();
        var fields = outp("腹痛", "脐周隐痛", null, null, null);
        service.recordVersion(EmrVersionService.OUTP, emrId, fields, EmrVersionService.AUTO, null);
        var second = service.recordVersion(EmrVersionService.OUTP, emrId, fields, EmrVersionService.AUTO, null);
        assertEquals(EmrVersionService.ST_RECORDED, second.status());
        assertEquals(2, countVersions(emrId));
    }

    /** 坏配置不得静默关掉功能：gate 与 dedup 的坏值都必须回落到「更安全」的一侧。 */
    @Test
    void badConfigFallsBackToTheSafeSide() {
        setCfg(EmrVersionService.GATE_KEY, "blocked");
        assertEquals("warn", service.gate(), "gate 坏值回落 warn，不回落 off——笔误不该静默关掉一条法定留痕");
        setCfg(EmrVersionService.GATE_KEY, "TRUE");
        assertEquals("warn", service.gate());
        setCfg(EmrVersionService.MAX_CHARS_KEY, "0");
        assertEquals(1_000_000, service.maxContentChars(), "上限 0 会让每一次留痕都超限，必须回落默认");
        setCfg(EmrVersionService.MAX_CHARS_KEY, "abc");
        assertEquals(1_000_000, service.maxContentChars());
    }

    // ==================================================================
    // 三、零回填：历史病历就是没有版本，系统不许替它编一个
    // ==================================================================

    @Test
    void historicalEmrHasNoFabricatedInitialVersionAndReadsNeverWrite() {
        // 走真实写路径建一份病历。DoctorStationService **本车道没接**，故它不会产生任何版本行——
        // 这正好模拟「版本留痕上线之前书写的病历」。
        OutpEmr emr = existingEmr("陈旧主诉", "陈旧现病史");
        assertNotNull(emr.getId());

        var listed = service.list(EmrVersionService.OUTP, emr.getId(), null, null, false);
        assertTrue(listed.ok());
        assertEquals(0L, listed.body().get("total"));
        assertTrue(((List<?>) listed.body().get("items")).isEmpty());
        String notice = (String) listed.body().get("notice");
        assertNotNull(notice, "空列表必须带一句可以拿去举证的说明");
        assertTrue(notice.contains("不伪造初版"), "说明里要讲清楚为什么是空的：" + notice);

        // 三条读路径全跑一遍，之后版本表仍须是空的——读路径一行都不许写。
        service.get(EmrVersionService.OUTP, emr.getId(), 1);
        service.compare(EmrVersionService.OUTP, emr.getId(), 1, 2);
        service.compareWithCurrent(EmrVersionService.OUTP, emr.getId(), 1);
        assertEquals(0, countVersions(emr.getId()),
                "任何读路径都不得凭当前内容补一条「初版」——那会让举证材料里出现一条从未真实存在过的版本");
    }

    // ==================================================================
    // 四、gate 三态：warn 档绝不能连累医生那一次保存
    // ==================================================================

    @Test
    void gateOffRecordsNothingAndSaysTheComplianceClaimIsVoid() {
        setCfg(EmrVersionService.GATE_KEY, "off");
        long emrId = synth();
        var r = service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("测试", null, null, null, null), EmrVersionService.MANUAL, null);
        assertEquals(EmrVersionService.ST_SKIPPED_OFF, r.status());
        assertEquals(0, countVersions(emrId));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("合规声明")),
                "off 档必须把「合规声明在这一档下不成立」明说出来，而不是静静地什么都不做");

        var settings = service.settings();
        assertEquals(Boolean.FALSE, settings.get("complianceClaimHolds"));
    }

    /**
     * <b>本类最要紧的一个用例。</b>
     *
     * <p>留痕写失败（saved_by 指向不存在的用户 → 外键违例）时，warn 档必须：
     * ① 不抛异常；② 把事务从 aborted 状态救回来，让调用方后续的写照常成功。
     *
     * <p>没有 savepoint 这一段是跑不通的，且失败方式极具误导性：PG 里一条语句触发约束违例后
     * 整个事务进入 aborted，<b>后续任何语句一律 25P02 current transaction is aborted</b>——
     * 医生看到的不是「留痕失败」，而是「病历保存失败」，而且报的错跟病历毫无关系。
     */
    @Test
    void warnGateSwallowsWriteFailureWithoutPoisoningTheCallerTransaction() {
        long emrId = synth();
        Long ghostUser = 999_000_111L;
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_user where id = ?", Integer.class, ghostUser),
                "夹具前提：该用户 id 必须不存在");

        var r = service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("留痕会失败", null, null, null, null), EmrVersionService.MANUAL, ghostUser);

        assertEquals(EmrVersionService.ST_FAILED, r.status(), "warn 档留痕失败应返回 FAILED，而不是抛异常");
        assertEquals(0, countVersions(emrId));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("gate=warn")),
                "失败必须被说出来，否则 warn 与 off 无异：" + r.warnings());

        // 事务还活着——这一条就是「不连累医生那一次保存」的全部含义
        assertDoesNotThrow(() -> jdbc.queryForObject("select 1", Integer.class),
                "留痕失败后事务必须仍可用（savepoint 已回滚），否则医生的病历也一并存不进去");
        var ok = service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("这一次能写进去", null, null, null, null), EmrVersionService.MANUAL, null);
        assertTrue(ok.recorded(), "同一事务里后续的留痕仍须成功");
        assertEquals(1, ok.versionNo());
    }

    @Test
    void blockGateFailsTheWholeSaveWith5705() {
        setCfg(EmrVersionService.GATE_KEY, "block");
        long emrId = synth();
        var e = assertThrows(HipBizException.class, () -> service.recordVersion(
                EmrVersionService.OUTP, emrId, outp("x", null, null, null, null),
                EmrVersionService.MANUAL, 999_000_222L));
        assertEquals(5705, e.code, "block 档的语义是「没有痕迹就不许留下内容」");
        // 抛之前已回滚到 savepoint，事务仍可用（否则连错误响应都发不出去）
        assertDoesNotThrow(() -> jdbc.queryForObject("select 1", Integer.class));
    }

    @Test
    void badArgumentsAreRejectedOrDegradedByGate() {
        long emrId = synth();
        // warn 档：入参非法也不抛，但要记 ERROR + 回 SKIPPED_INVALID，不能装作成功
        var r = service.recordVersion("MRI", emrId, outp("x", null, null, null, null),
                EmrVersionService.MANUAL, null);
        assertEquals(EmrVersionService.ST_SKIPPED_INVALID, r.status());
        assertTrue(r.warnings().get(0).startsWith("5700"));

        var src = service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("x", null, null, null, null), "DRAFT", null);
        assertEquals(EmrVersionService.ST_SKIPPED_INVALID, src.status(),
                "来源标错等于把「自动保存」说成「医生亲手点了保存」，不能默默当 MANUAL");
        assertTrue(src.warnings().get(0).startsWith("5702"));

        var nullId = service.recordVersion(EmrVersionService.OUTP, null,
                outp("x", null, null, null, null), EmrVersionService.MANUAL, null);
        assertEquals(EmrVersionService.ST_SKIPPED_INVALID, nullId.status());
        assertTrue(nullId.warnings().get(0).startsWith("5701"));

        // block 档：同样的入参非法直接抛，档位语义一致
        setCfg(EmrVersionService.GATE_KEY, "block");
        var e = assertThrows(HipBizException.class, () -> service.recordVersion(
                "MRI", emrId, outp("x", null, null, null, null), EmrVersionService.MANUAL, null));
        assertEquals(5700, e.code);
    }

    // ==================================================================
    // 五、超长快照：永不截断
    // ==================================================================

    @Test
    void oversizedSnapshotIsWrittenInFullNeverTruncated() {
        setCfg(EmrVersionService.MAX_CHARS_KEY, "50");
        long emrId = synth();
        String longText = "反复咳嗽咳痰十余年，加重伴发热三天，外院抗感染治疗效果欠佳".repeat(8);

        var r = service.recordVersion(EmrVersionService.OUTP, emrId,
                outp(longText, null, null, null, null), EmrVersionService.MANUAL, null);

        assertTrue(r.recorded(), "接缝档超限也要照写，宁可表长胖也不能让内容失真");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("超出上限")),
                "超限必须提示出来：" + r.warnings());

        String stored = jdbc.queryForObject(
                "select content from emr_version where emr_type = 'OUTP' and emr_id = ?", String.class, emrId);
        assertTrue(stored.contains(longText),
                "快照必须完整落库、一个字都不许截——截断后的快照拿去举证等于伪证");
        assertEquals(stored.length(), r.contentLen());

        // strict 入口是另一套语义：宁可拒绝也不写超限快照
        var e = assertThrows(HipBizException.class, () -> service.recordVersionStrict(
                EmrVersionService.OUTP, synth(), outp(longText, null, null, null, null),
                EmrVersionService.MANUAL, null));
        assertEquals(5704, e.code);
    }

    // ==================================================================
    // 六、结构化差异：说得出「哪个字段、从什么改成什么」
    // ==================================================================

    @Test
    void diffIsPerFieldAndNamesWhatChanged() {
        long emrId = synth();
        service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("咳嗽3天", "受凉后起病", "否认高血压", "咽部充血", null),
                EmrVersionService.MANUAL, null);
        service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("咳嗽5天", "受凉后起病", null, "咽部充血", "阿莫西林口服"),
                EmrVersionService.MANUAL, null);

        var r = service.compare(EmrVersionService.OUTP, emrId, 1, 2);
        assertTrue(r.ok(), r.message());

        @SuppressWarnings("unchecked")
        var diffs = (List<Map<String, Object>>) r.body().get("diffs");
        var byField = new LinkedHashMap<String, Map<String, Object>>();
        diffs.forEach(d -> byField.put((String) d.get("field"), d));

        // 顺序按临床阅读顺序，不是字典序——字典序读起来是乱的
        assertEquals(List.of("chiefComplaint", "presentIllness", "pastHistory", "physicalExam", "advice"),
                diffs.stream().map(d -> (String) d.get("field")).toList());

        var cc = byField.get("chiefComplaint");
        assertEquals(EmrVersionService.D_MODIFIED, cc.get("status"));
        assertEquals("主诉", cc.get("label"), "差异要给中文字段名，前端不该再维护第二份字段字典");
        assertEquals("咳嗽3天", cc.get("from"));
        assertEquals("咳嗽5天", cc.get("to"));
        assertEquals(2, cc.get("firstDiffAt"), "要指出第几个字开始不同");
        assertEquals("3", cc.get("fromChanged"), "差异片段应剥掉公共前后缀，直接给出改动那一小段");
        assertEquals("5", cc.get("toChanged"));

        assertEquals(EmrVersionService.D_REMOVED, byField.get("pastHistory").get("status"));
        assertEquals("否认高血压", byField.get("pastHistory").get("from"));
        assertEquals(EmrVersionService.D_ADDED, byField.get("advice").get("status"));
        assertEquals("阿莫西林口服", byField.get("advice").get("to"));

        var pe = byField.get("physicalExam");
        assertEquals(EmrVersionService.D_UNCHANGED, pe.get("status"));
        assertNull(pe.get("from"), "没变的字段不回正文——住院病历几千字，回两遍等于把没变的内容传两次");
        assertNull(pe.get("to"));
        assertEquals(4, pe.get("fromLen"), "但长度要给，前端才画得出「这段没动」");

        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) r.body().get("summary");
        assertEquals(1, summary.get("modified"));
        assertEquals(1, summary.get("added"));
        assertEquals(1, summary.get("removed"));
        assertEquals(2, summary.get("unchanged"));
        assertEquals(Boolean.FALSE, summary.get("identical"));
        assertEquals(List.of("chiefComplaint", "pastHistory", "advice"), summary.get("changedFields"));
    }

    /** 代理对不得被劈成半个——差异片段是给人看的，不能自己先变成乱码。 */
    @Test
    void diffDoesNotSplitSurrogatePairs() {
        String emoji = "💉"; // 💉 U+1F489，一个代理对
        var a = Map.of("chiefComplaint", "用药" + emoji + "后好转");
        var b = Map.of("chiefComplaint", "用药后好转");
        var d = EmrVersionService.diff(EmrVersionService.OUTP, a, b).get(0);
        assertEquals(EmrVersionService.D_MODIFIED, d.status());
        assertEquals(emoji, d.fromChanged(), "差异片段应恰好是那个完整的代理对，不能是半个");
        assertEquals("", d.toChanged());
        assertEquals(2, d.firstDiffAt());
    }

    @Test
    void compareWithCurrentReadsTheLiveRowAndWritesNothing() {
        OutpEmr emr = existingEmr("初诊主诉", "初诊现病史");
        service.recordVersion(EmrVersionService.OUTP, emr.getId(),
                EmrVersionService.outpFields(emr), EmrVersionService.SUBMIT, null);

        // 绕过留痕接缝直接改主表——正是「签完又改了」那个场景
        jdbc.update("update outp_emr set chief_complaint = ? where id = ?", "被改过的主诉", emr.getId());

        var r = service.compareWithCurrent(EmrVersionService.OUTP, emr.getId(), 1);
        assertTrue(r.ok(), r.message());

        @SuppressWarnings("unchecked")
        var to = (Map<String, Object>) r.body().get("to");
        assertNull(to.get("versionNo"), "当前正文不是一个版本，versionNo 必须为 null");
        assertEquals("CURRENT", to.get("label"));

        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) r.body().get("summary");
        assertEquals(List.of("chiefComplaint"), summary.get("changedFields"));
        assertNotNull(r.body().get("notice"));

        assertEquals(1, countVersions(emr.getId()),
                "与当前正文对比绝不能顺手把当前内容记成一版——那是伪造一次并不存在的保存动作");
    }

    // ==================================================================
    // 七、列表：不返全文，但要看得出改了多少
    // ==================================================================

    @Test
    void listOmitsContentAndCanOptIntoChangedFieldNames() {
        long emrId = synth();
        service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("发热", "两天", null, null, null), EmrVersionService.MANUAL, null);
        service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("发热伴咳嗽", "两天", null, null, null), EmrVersionService.MANUAL, null);

        var plain = service.list(EmrVersionService.OUTP, emrId, null, null, false);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) plain.body().get("items");
        assertEquals(2, items.size());
        assertEquals(2, items.get(0).get("versionNo"), "列表倒序，最新的在最前");
        assertFalse(items.get(0).containsKey("content"),
                "列表页绝不返 content 全文——一页 50 版 × 几千字，每次翻页都要从 TOAST 里拉几 MB");
        assertFalse(items.get(0).containsKey("changedFields"), "默认不算 changedFields（要读正文，开销就是上一条说的那个）");
        assertNotNull(items.get(0).get("deltaLen"), "但要给出字数增减，这个不用读正文");
        assertNull(items.get(1).get("deltaLen"), "第一版没有上一版可比，deltaLen 应为 null 而不是 0");
        assertEquals(Boolean.TRUE, items.get(1).get("firstVersion"));

        var rich = service.list(EmrVersionService.OUTP, emrId, null, null, true);
        @SuppressWarnings("unchecked")
        var richItems = (List<Map<String, Object>>) rich.body().get("items");
        assertEquals(List.of("chiefComplaint"), richItems.get(0).get("changedFields"));
        assertEquals(List.of("chiefComplaint", "presentIllness"), richItems.get(1).get("changedFields"),
                "第一版相对「什么都没有」，改的就是它写了的那几个字段（未填的不算）");
        assertFalse(richItems.get(0).containsKey("content"), "即便开了 changedFields 也只回字段名，不回正文");
    }

    @Test
    void readApisRejectBadInputWithTheirOwnCodes() {
        long emrId = synth();
        assertEquals(5700, service.list("MRI", emrId, null, null, false).code());
        assertEquals(5701, service.list(EmrVersionService.OUTP, 0L, null, null, false).code());
        assertEquals(5706, service.list(EmrVersionService.OUTP, emrId, 0, null, false).code());
        assertEquals(5706, service.list(EmrVersionService.OUTP, emrId, 201, null, false).code());
        assertEquals(5706, service.list(EmrVersionService.OUTP, emrId, null, -1, false).code());
        assertEquals(5706, service.get(EmrVersionService.OUTP, emrId, 0).code());
        assertEquals(5703, service.get(EmrVersionService.OUTP, emrId, 7).code());
        assertEquals(5703, service.compare(EmrVersionService.OUTP, emrId, 1, 2).code());
        // 主表行不存在（合成 id 本来就没有对应的 outp_emr）→ 5707，与「版本不存在」区分开
        service.recordVersion(EmrVersionService.OUTP, emrId,
                outp("x", null, null, null, null), EmrVersionService.MANUAL, null);
        assertEquals(5707, service.compareWithCurrent(EmrVersionService.OUTP, emrId, 1).code());
    }

    // ==================================================================
    // 八、并发：唯一键会拒，问题是拒了之后医生看见什么
    // ==================================================================

    /**
     * 八个线程同时给<b>同一份病历</b>留痕。要求：版本号恰好是 1..8、无重号、无空洞，
     * 且<b>没有任何一次调用把异常抛出来</b>。
     *
     * <p>本用例必须<b>脱离测试事务</b>（{@code NOT_SUPPORTED}）：并发线程各用自己的连接，
     * 看不见本测试未提交的行，在事务里跑等于八个线程都从 max=0 起步，测的是假的。
     * 代价是行会真正落库，故 finally 里按合成 id 清理。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRecordsNeverCollideOnVersionNo() throws Exception {
        long emrId = 987_654_321L;
        jdbc.update("delete from emr_version where emr_type = 'OUTP' and emr_id = ?", emrId);
        int n = 8;
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        var errors = Collections.synchronizedList(new ArrayList<Throwable>());
        var results = Collections.synchronizedList(new ArrayList<RecordResult>());
        try {
            for (int i = 0; i < n; i++) {
                final int k = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(service.recordVersion(EmrVersionService.OUTP, emrId,
                                outp("并发主诉-" + k, "第 " + k + " 次保存", null, null, null),
                                EmrVersionService.MANUAL, null));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发留痕超时");

            assertTrue(errors.isEmpty(), "并发留痕不得把异常抛给调用方（医生），实得：" + errors);
            assertEquals(n, results.size());
            assertTrue(results.stream().allMatch(RecordResult::recorded),
                    "内容各不相同，八次都应落版本：" + results);

            var nos = jdbc.queryForList(
                    "select version_no from emr_version where emr_type = 'OUTP' and emr_id = ? order by version_no",
                    Integer.class, emrId);
            assertEquals(java.util.stream.IntStream.rangeClosed(1, n).boxed().toList(), nos,
                    "版本号必须是 1..N 连续无重号——撞号即证明串行化失效");
        } finally {
            // 测试清理，走的是直连 SQL；应用层没有、也不该有任何删除版本的入口（见下一个用例）
            jdbc.update("delete from emr_version where emr_type = 'OUTP' and emr_id = ?", emrId);
            pool.shutdownNow();
        }
    }

    // ==================================================================
    // 九、铁律：没有写端点、没有回滚
    // ==================================================================

    /**
     * 版本端点<b>只能有 GET</b>。可被篡改的留痕在法庭上没有价值，
     * 而「我们不会去调它」是靠不住的——入口不存在才靠得住。
     * 将来任何人往控制器里加一个写映射，这个用例当场变红。
     */
    @Test
    void controllerExposesNoMutationEndpoint() {
        for (Method m : EmrVersionController.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            assertNull(m.getAnnotation(PostMapping.class), "不得有 @PostMapping：" + m.getName());
            assertNull(m.getAnnotation(PutMapping.class), "不得有 @PutMapping：" + m.getName());
            assertNull(m.getAnnotation(DeleteMapping.class), "不得有 @DeleteMapping：" + m.getName());
            assertNull(m.getAnnotation(PatchMapping.class), "不得有 @PatchMapping：" + m.getName());
            RequestMapping rm = m.getAnnotation(RequestMapping.class);
            if (rm != null) {
                for (RequestMethod method : rm.method()) {
                    assertEquals(RequestMethod.GET, method, "@RequestMapping 只允许 GET：" + m.getName());
                }
            }
        }
        // 服务层同样不得出现回滚/恢复类方法：回滚会让「当前正文的责任人是谁」变得不清楚
        for (Method m : EmrVersionService.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("restore") || name.contains("rollback") || name.contains("revert"),
                    "法定病历只回看不回滚，服务层不得出现恢复类方法：" + m.getName());
            assertFalse(name.startsWith("update") || name.startsWith("delete") || name.startsWith("remove"),
                    "版本不可篡改，服务层不得出现改删版本的方法：" + m.getName());
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 建一份真实的门诊病历（走既有写路径，故不会产生任何版本行）。 */
    private OutpEmr existingEmr(String chiefComplaint, String presentIllness) {
        Patient p = new Patient();
        p.setName("版本留痕测试");
        p.setSex("M");
        p.setBirthDate(BusinessDates.today().minusYears(40));
        Long patientId = patientService.register(p).getId();

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long regId = registrationService.register(patientId, s.getId()).getId();

        OutpEmr data = new OutpEmr();
        data.setChiefComplaint(chiefComplaint);
        data.setPresentIllness(presentIllness);
        data.setPhysicalExam("查体无殊");
        OutpEmr saved = doctorStationService.saveEmr(regId, data, List.of(), null);

        // **模拟「版本留痕上线之前书写的病历」**：主控已把留痕接进 saveEmr（v53 合版），
        // 故走真实写路径会产生一版——那一版是**真实保存动作**的痕迹，不是伪造，留着是对的。
        // 但本类要验的恰恰是「上线前的旧病历不该有版本行」，所以这里把它清掉，
        // 让夹具回到「这份病历从来没被留痕系统见过」的状态。
        //
        // **这不是把测试改绿**：断言一个字没动（空列表 + notice 含「不伪造初版」+ 读路径零写入），
        // 改的只是夹具的构造方式——原注释写的是「DoctorStationService 本车道没接」，
        // 那是写测试时的现状，接上之后前提就变了。
        jdbc.update("delete from emr_version where emr_type = ? and emr_id = ?",
                EmrVersionService.OUTP, saved.getId());
        return saved;
    }
}
