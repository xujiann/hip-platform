package cn.hip.server;

import cn.hip.inpatient.service.EmrIntegrityService;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.qualitycare.web.MrQcController;
import cn.hip.qualitycare.web.MrQcController.ItemReq;
import cn.hip.qualitycare.web.MrQcController.SheetItemReq;
import cn.hip.qualitycare.web.MrQcController.SubmitReq;
import org.junit.jupiter.api.AfterEach;
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
 * v42 车道3：病案终末质控评分与甲乙丙评级回归。
 *
 * <p>最关键的一条不是评分流程，而是 {@link #legacyCheckTextsAreByteIdentical()}：
 * 本轮把 {@code EmrIntegrityService} 的判定抽成带码结构（供评分单自动预填），
 * 而 {@code check()} 的 {@code List<String>} 中文文案有 3 处生产调用 + 4 处既有断言 +
 * 前端 tooltip + E2E 契约在消费——文案漂一个字都是线上断链。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V42MrQcTest {

    @Autowired MrQcController mrQc;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired EmrIntegrityService emrIntegrityService;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    private final Authentication auth = new UsernamePasswordAuthenticationToken("tester", "x");

    @AfterEach
    void evict() {
        configReader.evictAll();
    }

    // ---------------------------------------------------------------- 造数

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
    }

    private void rec(Long admId, String type, boolean signed) {
        jdbc.update("insert into inp_medical_record(admission_id, record_type, title, content, signature) "
                + "values (?,?,?,?,?)", admId, type, type, "内容", signed ? "SIG" : null);
    }

    /** 病历齐全 + 已出院：自动预填后零缺项，用于精确控制扣分做评级边界断言 */
    private Long completeDischarged(String name) {
        Long admId = admit(name);
        rec(admId, "ADMISSION", false);
        rec(admId, "PROGRESS", false);
        rec(admId, "DISCHARGE", true);
        inpatientService.discharge(admId, null, "CASH");
        return admId;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> sheet) {
        return (List<Map<String, Object>>) sheet.get("items");
    }

    private static BigDecimal num(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
    }

    // ---------------------------------------------------------------- 1. 文案不变（最高优先级）

    /**
     * {@code check()} 返回的中文文案逐字未变。本轮只在其下方抽了一层 {@code findings()}，
     * 任何一条文案的改动都会同时打断出院 gate 提示、归档 gate 提示、病案室队列 tooltip 与 E2E。
     */
    @Test
    void legacyCheckTextsAreByteIdentical() {
        Long admId = admit("文案契约");
        // 空病历：缺入院记录 / 缺出院小结 / 病程记录不足（默认阈值 1）
        assertEquals(List.of("缺入院记录", "缺出院小结", "病程记录不足（少于 1 条）"),
                emrIntegrityService.check(admId));

        // 出院小结存在但未签名 → 文案切到"出院小结未 CA 签名"（原分支语义不变）
        rec(admId, "ADMISSION", false);
        rec(admId, "PROGRESS", false);
        rec(admId, "DISCHARGE", false);
        assertEquals(List.of("出院小结未 CA 签名"), emrIntegrityService.check(admId));

        // 手术病例三项文案
        jdbc.update("insert into inp_surgery(admission_id, procedure_name, status) values (?, '阑尾切除术', 'SCHEDULED')",
                admId);
        assertEquals(List.of("出院小结未 CA 签名", "缺手术记录", "缺术前小结", "缺手术知情同意书"),
                emrIntegrityService.check(admId));

        // checkDetailed 与 check 严格同序同文案，只多一列稳定机读码
        var detailed = emrIntegrityService.checkDetailed(admId);
        assertEquals(emrIntegrityService.check(admId), detailed.stream().map(EmrIntegrityService.Finding::text).toList());
        assertEquals(List.of("DISCHARGE_UNSIGNED", "MISS_OP_NOTE", "MISS_PREOP", "MISS_SURGERY_CONSENT"),
                detailed.stream().map(EmrIntegrityService.Finding::code).toList());
    }

    // ---------------------------------------------------------------- 2. 字典 CRUD 与错误码

    @Test
    void dictionaryCrudAndErrorCodes() {
        // 种子 30 条全部启用
        var seeded = mrQc.items(null, true).getData();
        assertTrue(seeded.size() >= 30, "扣分项种子应 >= 30 条，实际 " + seeded.size());
        assertTrue(seeded.stream().anyMatch(i -> "ADM01".equals(i.get("code"))));

        String code = "TST" + (System.nanoTime() % 100000);
        var created = mrQc.createItem(new ItemReq(code, "首页填写", "测试扣分项",
                new BigDecimal("4"), null, true, 99));
        assertEquals(0, created.getCode());
        Long id = ((Number) created.getData().get("id")).longValue();

        // 4846 编码重复
        assertEquals(4846, mrQc.createItem(new ItemReq(code, "首页填写", "重复码",
                new BigDecimal("4"), null, true, 99)).getCode());
        // 4843 分值越界（<=0 与 >100 两侧）
        assertEquals(4843, mrQc.createItem(new ItemReq(code + "A", "首页填写", "零分",
                BigDecimal.ZERO, null, true, 1)).getCode());
        assertEquals(4843, mrQc.createItem(new ItemReq(code + "B", "首页填写", "超界",
                new BigDecimal("100.01"), null, true, 1)).getCode());
        // 4842 更新不存在的项
        assertEquals(4842, mrQc.updateItem(-1L, new ItemReq(null, null, "改名",
                null, null, null, null)).getCode());
        assertEquals(4842, mrQc.disableItem(-1L).getCode());

        // 更新 + 停用（软删）
        assertEquals(0, mrQc.updateItem(id, new ItemReq(null, null, "测试扣分项改名",
                new BigDecimal("6"), null, null, null)).getCode());
        assertEquals(0, mrQc.disableItem(id).getCode());
        assertEquals(Boolean.FALSE,
                jdbc.queryForObject("select enabled from mr_qc_item where id = ?", Boolean.class, id));
        // 停用项不出现在启用列表，但行仍在（历史评分单要能回溯）
        assertTrue(mrQc.items(null, true).getData().stream().noneMatch(i -> code.equals(i.get("code"))));
        assertTrue(mrQc.items(null, null).getData().stream().anyMatch(i -> code.equals(i.get("code"))));
    }

    // ---------------------------------------------------------------- 3. 自动预填

    @Test
    void prefillTurnsMissingRecordsIntoAutoDeductions() {
        Long admId = admit("预填缺项");
        inpatientService.discharge(admId, null, "CASH");   // 默认 warn：不完整也能出院

        var sheet = mrQc.prefill(admId).getData();
        assertNotNull(sheet);
        var items = itemsOf(sheet);
        // 缺入院记录 → ADM01(10)、缺出院小结 → PRG06(10)、病程不足 → PRG01(8)
        assertEquals(3, items.size(), items.toString());
        assertTrue(items.stream().allMatch(i -> "AUTO".equals(i.get("source"))));
        assertEquals(List.of("ADM01", "PRG01", "PRG06"),
                items.stream().map(i -> String.valueOf(i.get("item_code"))).sorted().toList());
        assertEquals(0, num(sheet.get("total_deduct")).compareTo(new BigDecimal("28")));
        assertEquals(0, num(sheet.get("final_score")).compareTo(new BigDecimal("72")));
        assertEquals("DRAFT", sheet.get("status"));
        assertNull(sheet.get("grade"), "草稿态不定级");
        // 未映射到字典的缺项如实回报（本例全部有映射）
        assertEquals(List.of(), sheet.get("unmappedFindings"));

        // 补齐病历后重跑预填：AUTO 行整体重建，扣分归零
        rec(admId, "ADMISSION", false);
        rec(admId, "PROGRESS", false);
        rec(admId, "DISCHARGE", true);
        var again = mrQc.prefill(admId).getData();
        assertEquals(0, itemsOf(again).size());
        assertEquals(0, num(again.get("final_score")).compareTo(new BigDecimal("100")));

        // 人工加项在重跑预填后必须保留（质控员的判断不该被刷新抹掉）
        assertEquals(0, mrQc.addItem(admId, new SheetItemReq("FRP02", null, "主诊断选错")).getCode());
        var third = mrQc.prefill(admId).getData();
        assertEquals(1, itemsOf(third).size());
        assertEquals("MANUAL", itemsOf(third).get(0).get("source"));
    }

    /** 加项/删项的错误码路径：4840 无评分单、4842 项不存在或已停用、4843 分值越界 */
    @Test
    void manualItemErrorCodes() {
        Long admId = completeDischarged("人工加项");
        // 未建单先加项 → 4840
        assertEquals(4840, mrQc.addItem(admId, new SheetItemReq("FRP02", null, null)).getCode());
        assertEquals(4840, mrQc.sheet(admId).getCode());
        assertEquals(4840, mrQc.submit(admId, null, auth).getCode());

        mrQc.prefill(admId);
        assertEquals(4842, mrQc.addItem(admId, new SheetItemReq("NOT_EXIST", null, null)).getCode());
        assertEquals(4843, mrQc.addItem(admId, new SheetItemReq("FRP02", new BigDecimal("120"), null)).getCode());

        // 停用的字典项不可再加（历史明细不受影响）
        jdbc.update("update mr_qc_item set enabled = false where code = 'FRP04'");
        assertEquals(4842, mrQc.addItem(admId, new SheetItemReq("FRP04", null, null)).getCode());

        // 正常加项 → 删项 → 得分回到 100
        assertEquals(0, mrQc.addItem(admId, new SheetItemReq("FRP02", null, "主诊断选错")).getCode());
        var after = mrQc.removeItem(admId, "FRP02").getData();
        assertEquals(0, itemsOf(after).size());
        assertEquals(0, num(after.get("final_score")).compareTo(new BigDecimal("100")));
    }

    // ---------------------------------------------------------------- 4. 甲乙丙评级边界

    /** 阈值 a=90 / b=80：90 甲、89 乙、80 乙、79 丙（含两侧边界） */
    @Test
    void gradeBoundariesAtConfiguredThresholds() {
        assertEquals("甲", scoreWithDeduct("边界甲", "10"));   // 100-10 = 90 == a_min
        assertEquals("乙", scoreWithDeduct("边界乙上", "11"));  // 89
        assertEquals("乙", scoreWithDeduct("边界乙下", "20"));  // 80 == b_min
        assertEquals("丙", scoreWithDeduct("边界丙", "21"));    // 79
    }

    /** 扣分超过基础分：得分下限 0，不出现负分（负分在甲乙丙里没有语义，只会污染均分） */
    @Test
    void finalScoreNeverGoesNegative() {
        Long admId = completeDischarged("超扣");
        mrQc.prefill(admId);
        mrQc.addItem(admId, new SheetItemReq("ADM01", new BigDecimal("100"), null));
        mrQc.addItem(admId, new SheetItemReq("PRG01", new BigDecimal("50"), null));
        var sheet = mrQc.submit(admId, new SubmitReq("超扣测试"), auth).getData();
        assertEquals(0, num(sheet.get("final_score")).compareTo(BigDecimal.ZERO));
        assertEquals("丙", sheet.get("grade"));
    }

    private String scoreWithDeduct(String name, String deduct) {
        Long admId = completeDischarged(name);
        mrQc.prefill(admId);
        assertEquals(0, mrQc.addItem(admId, new SheetItemReq("FRP02", new BigDecimal(deduct), "边界")).getCode());
        var sheet = mrQc.submit(admId, new SubmitReq("边界用例"), auth).getData();
        assertEquals("SUBMITTED", sheet.get("status"));
        assertNotNull(sheet.get("reviewed_at"));
        return String.valueOf(sheet.get("grade"));
    }

    // ---------------------------------------------------------------- 5. 状态与配置错误码

    /** 4841：已提交不可再评（预填/加项/删项/再提交四条路径全挡） */
    @Test
    void submittedSheetCannotBeReScored() {
        Long admId = completeDischarged("已提交");
        mrQc.prefill(admId);
        assertEquals(0, mrQc.submit(admId, new SubmitReq("首评"), auth).getCode());

        assertEquals(4841, mrQc.prefill(admId).getCode());
        assertEquals(4841, mrQc.addItem(admId, new SheetItemReq("FRP02", null, null)).getCode());
        assertEquals(4841, mrQc.removeItem(admId, "FRP02").getCode());
        assertEquals(4841, mrQc.submit(admId, new SubmitReq("复评"), auth).getCode());
    }

    /** 4844：未出院不可终末评分——终末质控的前提是病案已终结 */
    @Test
    void inHospitalAdmissionCannotBeTerminallyScored() {
        Long admId = admit("在院");
        assertEquals(4844, mrQc.prefill(admId).getCode());
        assertEquals(4844, mrQc.prefill(-1L).getCode(), "住院记录不存在同走 4844");
        // 出院后可评；评分单建好后把住院态改回在院（模拟撤销出院），提交仍须被 4844 挡住
        inpatientService.discharge(admId, null, "CASH");
        assertEquals(0, mrQc.prefill(admId).getCode());
        jdbc.update("update inp_admission set status = 'IN_HOSPITAL' where id = ?", admId);
        assertEquals(4844, mrQc.submit(admId, null, auth).getCode());
    }

    /** 4845：阈值配置非法时报错，而不是静默回落默认值按另一套标准打分 */
    @Test
    void illegalGradeThresholdIsRejected() {
        Long admId = completeDischarged("阈值非法");
        mrQc.prefill(admId);

        jdbc.update("update sys_config set cfg_value = '95' where cfg_key = 'mr.qc.grade_b_min'");
        configReader.evict("mr.qc.grade_b_min");
        assertEquals(4845, mrQc.submit(admId, null, auth).getCode(), "乙级线 >= 甲级线 应报 4845");

        jdbc.update("update sys_config set cfg_value = 'abc' where cfg_key = 'mr.qc.grade_a_min'");
        configReader.evict("mr.qc.grade_a_min");
        assertEquals(4845, mrQc.submit(admId, null, auth).getCode(), "非数字应报 4845");

        // 改回合法值即可提交
        jdbc.update("update sys_config set cfg_value = '90' where cfg_key = 'mr.qc.grade_a_min'");
        jdbc.update("update sys_config set cfg_value = '80' where cfg_key = 'mr.qc.grade_b_min'");
        configReader.evictAll();
        assertEquals(0, mrQc.submit(admId, null, auth).getCode());
    }

    // ---------------------------------------------------------------- 6. 摘要与统计口径

    /** 序号 7：病案首页质控汇总 —— 未评分不报错（首页对所有病案都要能打开） */
    @Test
    void summaryNeverFailsForUnscoredAdmission() {
        Long admId = admit("首页摘要");
        var unscored = mrQc.summary(admId).getData();
        assertEquals(0, mrQc.summary(admId).getCode());
        assertEquals(Boolean.FALSE, unscored.get("scored"));
        assertFalse(((List<?>) unscored.get("autoFindings")).isEmpty(), "未评分时给出自动判定缺项预览");

        inpatientService.discharge(admId, null, "CASH");
        mrQc.prefill(admId);
        mrQc.submit(admId, new SubmitReq("摘要用例"), auth);
        var scored = mrQc.summary(admId).getData();
        assertEquals(Boolean.TRUE, scored.get("scored"));
        assertNotNull(scored.get("grade"));
        assertFalse(itemsOf(scored).isEmpty(), "汇总须带扣分项明细");
        assertNotNull(scored.get("archivedAtCaveat"), "归档时间口径须随摘要返回");
    }

    /** 2647 + 序号 22：分类汇总 / 科室维度 / TOP10 / 扣分原因 / CSV 导出，以及口径诚实标注 */
    @Test
    void statsCoversGradesDeptsTopListsAndCaveats() {
        Long admId = completeDischarged("统计");
        mrQc.prefill(admId);
        mrQc.addItem(admId, new SheetItemReq("FRP02", new BigDecimal("25"), "主诊断选错"));
        var sheet = mrQc.submit(admId, new SubmitReq("统计用例"), auth).getData();
        assertEquals("丙", sheet.get("grade"));

        var stats = mrQc.stats(12, null, null).getData();
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) stats.get("totals");
        assertTrue(((Number) totals.get("sheets")).intValue() >= 1);
        assertTrue(((Number) totals.get("grade_c")).intValue() >= 1);
        assertFalse(((List<?>) stats.get("byMonthDept")).isEmpty());
        assertFalse(((List<?>) stats.get("deptRank")).isEmpty());
        assertFalse(((List<?>) stats.get("topSheets")).isEmpty());

        @SuppressWarnings("unchecked")
        var top = (List<Map<String, Object>>) stats.get("topDeductItems");
        assertTrue(top.stream().anyMatch(r -> "FRP02".equals(r.get("item_code"))), top.toString());
        assertTrue(top.stream().anyMatch(r -> String.valueOf(r.get("item_name")).contains("主要诊断选择错误")));

        // 「按评分类型分类汇总」：运行质控不落库，份数恒 0 且显式标注 persisted=false，
        // 绝不用环节质控的现算值冒充历史评分
        @SuppressWarnings("unchecked")
        var byType = (List<Map<String, Object>>) stats.get("byScoreType");
        var running = byType.stream().filter(r -> "RUNNING".equals(r.get("scoreType"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, running.get("persisted"));
        assertEquals(0, ((Number) running.get("sheets")).intValue());
        var terminal = byType.stream().filter(r -> "TERMINAL".equals(r.get("scoreType"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, terminal.get("persisted"));

        // 口径诚实标注必须随端点返回（页面 alert 与此同源）
        assertEquals(Boolean.FALSE, stats.get("runningQcPersisted"));
        assertTrue(String.valueOf(stats.get("archivedAtCaveat")).contains("archived_at"));
        assertTrue(String.valueOf(stats.get("monthCaveat")).contains("reviewed_at"));

        // CSV 导出（序号 22 明文要导出）：BOM + 表头 + 口径尾注
        String csv = mrQc.statsCsv(12, null, null);
        assertTrue(csv.startsWith("﻿月份,科室,评分份数,甲级,乙级,丙级,甲级率(%),平均分"), csv.substring(0, 40));
        assertTrue(csv.contains("archived_at"), "CSV 须带口径尾注");
        assertTrue(mrQc.deductItemsCsv(12, null, null).contains("FRP02"));
    }

    /** 待评队列：已出院且未提交评分单的病案，且不做逐条完整性 check（无 N+1） */
    @Test
    void pendingQueueListsDischargedUnscoredOnly() {
        Long admId = completeDischarged("待评队列");
        var q = mrQc.pending(null).getData();
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) q.get("items");
        assertTrue(items.stream().anyMatch(r -> admId.equals(((Number) r.get("admission_id")).longValue())),
                "已出院未评分应在待评队列");
        assertNotNull(q.get("archivedAtCaveat"));

        mrQc.prefill(admId);
        mrQc.submit(admId, new SubmitReq("清队列"), auth);
        @SuppressWarnings("unchecked")
        var after = (List<Map<String, Object>>) mrQc.pending(null).getData().get("items");
        assertTrue(after.stream().noneMatch(r -> admId.equals(((Number) r.get("admission_id")).longValue())),
                "已提交应移出待评队列");
    }
}
