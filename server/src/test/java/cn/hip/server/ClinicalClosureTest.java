package cn.hip.server;

import cn.hip.inpatient.entity.InpSettlement;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpatientController;
import cn.hip.outpatient.web.ErRescueController;
import cn.hip.qualitycare.web.EmrCopyController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上线检查单·车道B「临床收尾三项」回归：
 *   ① 住院中间结算（INTERIM）与出院结算（FINAL）口径不算重；
 *   ② 病历复印（病案室受理→登记→出复印件，全程留痕 + 复印件水印/登记号）；
 *   ③ 急诊抢救记录（独立于住院 ICU）。
 * 风格同 InpatientClosureTest：@Transactional 内 flush 让 jdbc 可见；服务层抛错的守卫用例单独隔离
 * （抛错会把参与中的测试事务标记 rollback-only，不能与"抛错后仍需成功提交"的步骤混在同一 @Test）。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "QUALITY", "CASHIER"})
class ClinicalClosureTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired InpatientService inpatientService;
    @Autowired InpatientController inpatientController;
    @Autowired ErRescueController erRescueController;
    @Autowired EmrCopyController emrCopyController;
    @Autowired cn.hip.qualitycare.web.DrgController drgController;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private final Authentication auth = new UsernamePasswordAuthenticationToken("tester", "x");

    private Long newPatient(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        return patientService.register(p).getId();
    }

    private Long freeBed() {
        return jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
    }

    private Long executedOrder(Long admId, int qty) {
        var drug = seeds.anyDrug();
        var o = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", drug.getId(), qty, "口服", "qd", "1粒")), null).get(0);
        inpatientService.execute(o.getId(), null);
        return o.getId();
    }

    // ==================== 收尾① 住院中间结算：与出院结算口径不算重 ====================

    /**
     * 核心不算重验证（纯成功路径，无抛错以免污染事务）：
     * 两张中间结算各只结自己那段新增费用（不重叠）；出院结算总额=全部已执行医嘱台账现算，
     * 未被中间结算行抬高——Σ中间 = 出院总额 是子集关系，而非在出院总额之上再加。
     */
    @Test
    void interimSettlementDoesNotDoubleCountWithFinal() {
        Long pid = newPatient("中间结算C1");
        Long bed = freeBed();
        Long admId = inpatientService.admit(pid, 1L, bed, null, "J18.9", "肺炎",
                new BigDecimal("1000"), "CASH", null).getId();
        BigDecimal price = seeds.anyDrug().getPrice();

        // 第一笔已发生费用 qty=1 → price
        executedOrder(admId, 1);
        entityManager.flush();
        InpSettlement it1 = inpatientService.interimSettle(admId, null, "CASH");
        entityManager.flush();
        assertEquals("INTERIM", it1.getSettleType(), "中间结算类型 INTERIM");
        assertEquals(0, it1.getTotalAmount().compareTo(price), "首张中间结算金额=已发生 price");
        assertEquals(0, it1.getBalance().compareTo(new BigDecimal("1000").subtract(price)),
                "中间结算 balance=押金-已发生（与 /account 同口径）");
        // 与 /account 端点口径一致
        assertEquals(0, ((BigDecimal) inpatientController.account(admId).getData().get("balance"))
                .compareTo(new BigDecimal("1000").subtract(price)), "中间结算 balance 与 /account 一致");

        // 第二笔 qty=2 → 2*price
        executedOrder(admId, 2);
        entityManager.flush();
        InpSettlement it2 = inpatientService.interimSettle(admId, null, "CASH");
        entityManager.flush();
        // 关键①：第二张只认领新增 o2（2*price），不含已被 it1 认领的 o1——两张中间结算不重叠
        assertEquals(0, it2.getTotalAmount().compareTo(price.multiply(BigDecimal.valueOf(2))),
                "第二张中间结算只结新增费用 2*price，不与第一张重叠");

        // 中间结算历史端点应有两条
        assertEquals(2, inpatientController.interimSettlements(admId).getData().size(), "历次中间结算两条");

        // 出院结算：总额=全部已执行医嘱=3*price（关键②：discharge 按台账现算，绝不叠加中间结算行）
        InpSettlement fin = inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        assertEquals("FINAL", fin.getSettleType(), "出院结算类型 FINAL");
        assertEquals(0, fin.getTotalAmount().compareTo(price.multiply(BigDecimal.valueOf(3))),
                "出院总额=全账单 3*price，未被中间结算抬高（不算重）");
        assertEquals(0, it1.getTotalAmount().add(it2.getTotalAmount()).compareTo(fin.getTotalAmount()),
                "Σ中间结算(price+2price)=出院总额(3price)，是子集关系而非叠加");
    }

    /** 守卫用例（均抛错，独立隔离）：无新增费用 9031、医保通道 9032。 */
    @Test
    void interimSettlementGuards() {
        Long pid = newPatient("中间结算守卫");
        Long bed = freeBed();
        Long admId = inpatientService.admit(pid, 1L, bed, null, "J18.9", "肺炎",
                new BigDecimal("100"), "CASH", null).getId();
        executedOrder(admId, 1);
        entityManager.flush();
        // 先成功结一张（此时事务干净）
        inpatientService.interimSettle(admId, null, "CASH");
        entityManager.flush();
        // 再集中做抛错断言（rollback-only 无害：测试事务整体回滚，且后续不需成功提交）
        assertEquals(9031, assertThrows(InpatientService.InpException.class,
                () -> inpatientService.interimSettle(admId, null, "CASH")).code, "无新增已发生费用 9031");
        assertEquals(9032, assertThrows(InpatientService.InpException.class,
                () -> inpatientService.interimSettle(admId, null, "YB")).code, "中间结算不走医保通道 9032");
    }

    /** 守卫用例：已出院不能中间结算 9030（需先成功出院，故单独隔离）。 */
    @Test
    void interimSettlementRejectedAfterDischarge() {
        Long pid = newPatient("中间结算出院后");
        Long bed = freeBed();
        Long admId = inpatientService.admit(pid, 1L, bed, null, "J18.9", "肺炎",
                new BigDecimal("100"), "CASH", null).getId();
        executedOrder(admId, 1);
        inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        assertEquals(9030, assertThrows(InpatientService.InpException.class,
                () -> inpatientService.interimSettle(admId, null, "CASH")).code, "已出院 9030");
    }

    // ==================== 收尾② 病历复印 ====================

    @Test
    void emrCopyRequestLifecycle() {
        Long pid = newPatient("复印C2");
        Long bed = freeBed();
        Long admId = inpatientService.admit(pid, 1L, bed, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
        jdbc.update("""
                insert into inp_medical_record(admission_id, record_type, title, content)
                values (?, 'ADMISSION', '入院记录', '主诉：发热咳嗽3天。')
                """, admId);
        entityManager.flush();

        // 必填校验：申请人空 → 9813；患者不存在 → 9810
        assertEquals(9813, emrCopyController.apply(new EmrCopyController.ApplyReq(
                pid, admId, "", "SELF", "X", "全部病历", "医保报销", 1), auth).getCode());
        assertEquals(9810, emrCopyController.apply(new EmrCopyController.ApplyReq(
                999999999L, null, "王五", "SELF", "X", "全部病历", "医保报销", 1), auth).getCode());

        // 受理
        var ap = emrCopyController.apply(new EmrCopyController.ApplyReq(
                pid, admId, "患者本人", "SELF", "11010119900101XXXX", "全部病历", "医保报销", 2), auth);
        assertEquals(0, ap.getCode());
        Long cid = ((Number) ap.getData().get("id")).longValue();
        entityManager.flush();

        // 未登记不出件 → 9812
        assertEquals(9812, emrCopyController.document(cid).getCode(), "未登记不出复印件");
        // 登记 → 生成复印登记号
        var rg = emrCopyController.register(cid, auth);
        assertEquals(0, rg.getCode());
        String regNo = (String) rg.getData().get("regNo");
        assertNotNull(regNo, "登记应生成复印登记号");
        assertTrue(regNo.startsWith("FY"), "登记号前缀 FY");
        entityManager.flush();

        // 出复印件数据集：水印 + 登记号 + 病历正文 + 申请留痕
        var docR = emrCopyController.document(cid);
        assertEquals(0, docR.getCode());
        Map<String, Object> doc = docR.getData();
        assertEquals("复印件", doc.get("watermark"), "复印件水印文案");
        @SuppressWarnings("unchecked")
        var reqMeta = (Map<String, Object>) doc.get("request");
        assertEquals(regNo, reqMeta.get("reg_no"), "复印件带登记号（法定留痕）");
        assertEquals("全部病历", reqMeta.get("copy_scope"));
        assertEquals("医保报销", reqMeta.get("purpose"));
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) doc.get("records");
        assertFalse(records.isEmpty(), "复印件含病历正文");

        // 出件
        assertEquals(0, emrCopyController.issue(cid, auth).getCode());
        entityManager.flush();
        assertEquals("ISSUED", jdbc.queryForObject(
                "select status from emr_copy_request where id = ?", String.class, cid));
        // 已出件再登记 → 9812
        assertEquals(9812, emrCopyController.register(cid, auth).getCode(), "非受理态不可再登记");
    }

    // ==================== 收尾③ 急诊抢救记录 ====================

    @Test
    void erRescueRecordLifecycle() {
        Long triageId = jdbc.queryForObject("""
                insert into outp_triage(patient_name, level, chief_complaint)
                values ('抢救C3', 1, '胸痛待查') returning id
                """, Long.class);

        // GCS 越界 → 4567
        assertEquals(4567, erRescueController.record(new ErRescueController.RescueReq(
                null, triageId, 36.5, 110, 22, 90, 60, 92, 20, 500, 300,
                "心肺复苏", "张医生/李护士", "ONGOING", null), auth).getCode());

        // 正常录入（缺省转归 ONGOING，患者名由分诊带出）
        var r = erRescueController.record(new ErRescueController.RescueReq(
                null, triageId, 36.5, 110, 22, 90, 60, 92, 8, 500, 300,
                "心肺复苏、气管插管", "张医生/李护士", null, "室颤"), auth);
        assertEquals(0, r.getCode());
        Long rid = ((Number) r.getData().get("id")).longValue();
        entityManager.flush();
        Map<String, Object> row = jdbc.queryForMap("select * from er_rescue_record where id = ?", rid);
        assertEquals("抢救C3", row.get("patient_name"), "患者名由分诊带出");
        assertEquals("ONGOING", row.get("outcome"), "缺省转归进行中");
        assertEquals(8, ((Number) row.get("gcs")).intValue(), "GCS 落库");
        assertEquals("张医生/李护士", row.get("participants"), "参与人员法定留痕");

        // 结束：非法/进行中转归 → 4568；正常 SUCCESS 落结束时间
        assertEquals(4568, erRescueController.end(rid, new ErRescueController.EndReq("ONGOING", null)).getCode());
        assertEquals(0, erRescueController.end(rid, new ErRescueController.EndReq("SUCCESS", "复苏成功")).getCode());
        entityManager.flush();
        Map<String, Object> after = jdbc.queryForMap(
                "select outcome, rescue_end from er_rescue_record where id = ?", rid);
        assertEquals("SUCCESS", after.get("outcome"));
        assertNotNull(after.get("rescue_end"), "结束时间落库");
        // 重复结束 → 4566
        assertEquals(4566, erRescueController.end(rid, new ErRescueController.EndReq("DEATH", null)).getCode());

        // observationId 入口：由留观记录反查 triage_id
        Long obsId = jdbc.queryForObject(
                "insert into er_observation(triage_id, bed_no) values (?, 'L09') returning id", Long.class, triageId);
        var r2 = erRescueController.record(new ErRescueController.RescueReq(
                obsId, null, null, null, null, null, null, null, 10, null, null,
                "留观中抢救", "王医生", "ONGOING", null), auth);
        assertEquals(0, r2.getCode());
        Long rid2 = ((Number) r2.getData().get("id")).longValue();
        Map<String, Object> row2 = jdbc.queryForMap(
                "select triage_id, observation_id from er_rescue_record where id = ?", rid2);
        assertEquals(triageId, ((Number) row2.get("triage_id")).longValue(), "留观入口反查出 triage_id");
        assertEquals(obsId, ((Number) row2.get("observation_id")).longValue(), "关联留观记录");
    }

    /**
     * 跨模块回归（v30）：中间结算引入 INTERIM 后，一个 admission 会有多张 PAID
     * （FINAL + 若干 INTERIM）。凡 join inp_settlement 且未收 FINAL 的消费方都会
     * 产生重复行——DRG 入组对同一病例插两次 drg_case 撞唯一约束 4090（本轮实测暴露）。
     * 本例锁住"有中间结算的出院病例 DRG 分组不重复"这一不变量。
     */
    @Test
    void interimSettlementDoesNotBreakDrgGrouping() {
        Long pid = newPatient("中间结算DRG");
        Long bed = freeBed();
        Long admId = inpatientService.admit(pid, 1L, bed, null, "J15.9", "细菌性肺炎",
                new BigDecimal("1000"), "CASH", null).getId();
        executedOrder(admId, 1);
        entityManager.flush();
        inpatientService.interimSettle(admId, null, "CASH");   // 制造一张 INTERIM PAID
        entityManager.flush();
        inpatientService.discharge(admId, null, "CASH");        // 再来一张 FINAL PAID
        entityManager.flush();

        // 该 admission 现有 2 张 PAID（1 INTERIM + 1 FINAL）
        assertEquals(2, (long) jdbc.queryForObject(
                "select count(*) from inp_settlement where admission_id = ? and status = 'PAID'",
                Long.class, admId));

        // DRG 全量入组不得因重复行报 4090；该病例恰好入组一次
        var grouped = drgController.groupAll();
        assertEquals(0, grouped.getCode(), "有中间结算的病例 DRG 分组不应撞 4090：" + grouped.getMessage());
        assertEquals(1, (long) jdbc.queryForObject(
                "select count(*) from drg_case where admission_id = ?", Long.class, admId),
                "DRG 入组对同一病例恰好一条，不因多张 PAID 重复");
    }
}
