package cn.hip.server;

import cn.hip.inpatient.service.ArrearsService;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.inpatient.web.ArrearsController;
import cn.hip.inpatient.web.InpatientController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v41 住院欠费挂账台账：欠费出院 → 自动挂账 → 催缴 → 补缴 → 结清/核销 的闭环回归。
 *
 * <p>最关键的一条不是流程本身，而是 {@link #arrearsPaymentNeverTouchesDepositOrSettlement()}：
 * 补缴是「收欠款」不是「补押金」。若补缴混进 inp_deposit，/account 与中间结算的余额口径
 * （押金合计 - 已发生费用）会凭空变正、欠费在账面上自愈；若回写 inp_settlement，
 * 已出院患者的结算历史快照会被下游派生账污染。本测试对两者逐字段前后比对。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V41ArrearsTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired InpatientService inpatientService;
    @Autowired ArrearsService arrearsService;
    @Autowired ArrearsController arrearsController;
    @Autowired InpatientController inpatientController;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    /** 控制器端点的 Authentication 入参（CurrentUserService 按 username 查库，测试用户不在库则 operatorId=null） */
    private final Authentication auth = new UsernamePasswordAuthenticationToken("tester", "x");

    /** 造一个「押金 1 份、费用 3 份」的欠费出院，欠费额 = 2 份药价。返回 admissionId。 */
    private Long owingDischarge(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        BigDecimal price = seeds.anyDrug().getPrice();
        Long admId = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎", price, "CASH", null).getId();
        var order = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", seeds.anyDrug().getId(), 3, "口服", "qd", "1粒")),
                null).get(0);
        inpatientService.execute(order.getId(), null);
        entityManager.flush();
        inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        return admId;
    }

    private BigDecimal unit() {
        return seeds.anyDrug().getPrice();
    }

    private Map<String, Object> arrearsRow(Long admId) {
        var rows = jdbc.queryForList("select * from inp_arrears where admission_id = ?", admId);
        assertEquals(1, rows.size(), "一次入院至多一条欠费挂账");
        return rows.get(0);
    }

    private Long arrearsId(Long admId) {
        return ((Number) arrearsRow(admId).get("id")).longValue();
    }

    // ===== ① 出院结算自动挂账 =====

    @Test
    void owingDischargeAutoCreatesArrearsLedger() {
        Long admId = owingDischarge("欠费挂账");
        var row = arrearsRow(admId);
        assertEquals("OPEN", row.get("status"));
        assertEquals(0, ((BigDecimal) row.get("amount")).compareTo(unit().multiply(BigDecimal.valueOf(2))),
                "欠费额 = 费用(3份) - 押金(1份)");
        assertNotNull(row.get("settle_id"), "挂账须引用出院结算单");

        // 台账清单口径：欠费额/已补/剩余/催缴次数
        var list = arrearsService.list("OPEN").stream()
                .filter(r -> admId.equals(((Number) r.get("admission_id")).longValue())).toList();
        assertEquals(1, list.size());
        assertEquals(0, ((BigDecimal) list.get(0).get("paid_amount")).compareTo(BigDecimal.ZERO));
        assertEquals(0, ((BigDecimal) list.get(0).get("remain_amount"))
                .compareTo(unit().multiply(BigDecimal.valueOf(2))));
        assertEquals(0L, ((Number) list.get(0).get("dunning_count")).longValue());
        assertNotNull(list.get(0).get("patient_name"));
        assertNotNull(list.get(0).get("admission_no"));
    }

    /** 不欠费的出院不挂账（挂账只对 balance<0，不给正常出院制造噪音台账） */
    @Test
    void solventDischargeCreatesNoArrears() {
        Patient p = new Patient();
        p.setName("不欠费出院" + System.nanoTime());
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        Long admId = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("5000"), "CASH", null).getId();
        inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from inp_arrears where admission_id = ?", Integer.class, admId));
    }

    // ===== ② 补缴：PARTIAL → CLEARED，超额/非正数拒绝 =====

    @Test
    void partialThenFullPaymentMovesStatusOpenPartialCleared() {
        Long admId = owingDischarge("补缴流程");
        Long id = arrearsId(admId);
        BigDecimal owed = unit().multiply(BigDecimal.valueOf(2));

        // 部分补缴 → PARTIAL
        var r1 = arrearsService.pay(id, unit(), "CASH", null);
        assertEquals("PARTIAL", r1.get("status"));
        assertEquals(0, ((BigDecimal) r1.get("remainAmount")).compareTo(unit()));
        assertEquals("PARTIAL", jdbc.queryForObject(
                "select status from inp_arrears where id = ?", String.class, id));

        // 超额拒绝（剩余仅 1 份，试补 2 份）
        var over = assertThrows(InpException.class, () -> arrearsService.pay(id, owed, "CASH", null));
        assertEquals(9037, over.code);

        // 金额 ≤ 0 拒绝
        assertEquals(9036, assertThrows(InpException.class,
                () -> arrearsService.pay(id, BigDecimal.ZERO, "CASH", null)).code);
        assertEquals(9036, assertThrows(InpException.class,
                () -> arrearsService.pay(id, unit().negate(), "CASH", null)).code);
        assertEquals(9036, assertThrows(InpException.class,
                () -> arrearsService.pay(id, null, "CASH", null)).code);

        // 补足 → CLEARED
        var r2 = arrearsService.pay(id, unit(), "WECHAT", null);
        assertEquals("CLEARED", r2.get("status"));
        assertEquals(0, ((BigDecimal) r2.get("remainAmount")).compareTo(BigDecimal.ZERO));

        // 已结清不能再补缴 / 再催缴 / 再核销
        assertEquals(9038, assertThrows(InpException.class,
                () -> arrearsService.pay(id, unit(), "CASH", null)).code);
        assertEquals(9038, assertThrows(InpException.class,
                () -> arrearsService.dun(id, "PHONE", "已结清还催", null)).code);
        assertEquals(9038, assertThrows(InpException.class,
                () -> arrearsService.writeOff(id, "已结清还核销", null)).code);

        // 两笔流水都在，且金额合计等于欠费额
        assertEquals(0, jdbc.queryForObject(
                        "select coalesce(sum(amount),0) from inp_arrears_payment where arrears_id = ?",
                        BigDecimal.class, id).compareTo(owed));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from inp_arrears_payment where arrears_id = ?", Integer.class, id));
    }

    @Test
    void unknownArrearsRejected() {
        assertEquals(9035, assertThrows(InpException.class,
                () -> arrearsService.pay(-1L, BigDecimal.ONE, "CASH", null)).code);
        assertEquals(9035, assertThrows(InpException.class,
                () -> arrearsService.dun(-1L, "PHONE", null, null)).code);
        assertEquals(9035, assertThrows(InpException.class,
                () -> arrearsService.writeOff(-1L, "理由", null)).code);
        assertEquals(9035, arrearsController.detail(-1L).getCode());
    }

    // ===== ③ 催缴登记 =====

    @Test
    void dunningIsLoggedAndCounted() {
        Long admId = owingDischarge("催缴登记");
        Long id = arrearsId(admId);

        assertEquals(1L, ((Number) arrearsService.dun(id, "PHONE", "联系患者本人", null)
                .get("dunningCount")).longValue());
        assertEquals(2L, ((Number) arrearsService.dun(id, "SMS", "发送催缴短信", null)
                .get("dunningCount")).longValue());
        // 非法方式拒绝
        assertEquals(9039, assertThrows(InpException.class,
                () -> arrearsService.dun(id, "微信语音", "非法方式", null)).code);
        assertEquals(9039, assertThrows(InpException.class,
                () -> arrearsService.dun(id, null, null, null)).code);

        var row = arrearsService.list(null).stream()
                .filter(r -> id.equals(((Number) r.get("id")).longValue())).findFirst().orElseThrow();
        assertEquals(2L, ((Number) row.get("dunning_count")).longValue());
        // 催缴不改欠费状态（催了≠收到钱）
        assertEquals("OPEN", row.get("status"));

        @SuppressWarnings("unchecked")
        var dunnings = (List<Map<String, Object>>) arrearsService.detail(id).get("dunnings");
        assertEquals(2, dunnings.size());
    }

    // ===== ④ 核销：仅 ADMIN =====

    @Test
    void writeOffRequiresReasonAndMarksLedger() {
        Long admId = owingDischarge("核销原因");
        Long id = arrearsId(admId);
        assertEquals(9040, assertThrows(InpException.class,
                () -> arrearsService.writeOff(id, "  ", null)).code);
        assertEquals(9040, assertThrows(InpException.class,
                () -> arrearsService.writeOff(id, null, null)).code);

        arrearsService.writeOff(id, "多次催缴无果，经院办批准核销", null);
        var row = arrearsRow(admId);
        assertEquals("WRITTEN_OFF", row.get("status"));
        assertEquals("多次催缴无果，经院办批准核销", row.get("write_off_reason"));
        assertNotNull(row.get("written_off_at"));
        // 已核销不可再核销/补缴
        assertEquals(9038, assertThrows(InpException.class,
                () -> arrearsService.writeOff(id, "再核销一次", null)).code);
        assertEquals(9038, assertThrows(InpException.class,
                () -> arrearsService.pay(id, unit(), "CASH", null)).code);
    }

    /** 收费员可查台账/补缴/催缴，但核销必须 ADMIN（方法级 @PreAuthorize） */
    @Test
    @WithMockUser(roles = "CASHIER")
    void writeOffIsAdminOnlyWhilePaymentIsCashierWork() {
        Long admId = owingDischarge("核销越权");
        Long id = arrearsId(admId);

        // 收费员本职：看台账、补缴、催缴都放行
        assertEquals(0, arrearsController.list("OPEN").getCode());
        assertEquals(0, arrearsController.pay(id,
                new ArrearsController.PayRequest(unit(), "CASH"), auth).getCode());
        assertEquals(0, arrearsController.dun(id,
                new ArrearsController.DunRequest("PHONE", "电话催缴"), auth).getCode());

        // 核销是把应收变损失：收费员被方法级 @PreAuthorize 挡下
        assertThrows(AccessDeniedException.class, () -> arrearsController.writeOff(id,
                new ArrearsController.WriteOffRequest("收费员想核销"), auth));
        assertEquals("PARTIAL", jdbc.queryForObject(
                "select status from inp_arrears where id = ?", String.class, id));
    }

    @Test
    void writeOffAllowedForAdminThroughController() {
        Long admId = owingDischarge("核销放行");
        Long id = arrearsId(admId);
        assertEquals(0, arrearsController.writeOff(id,
                new ArrearsController.WriteOffRequest("患者死亡且无遗产可执行"), auth).getCode());
        assertEquals("WRITTEN_OFF", jdbc.queryForObject(
                "select status from inp_arrears where id = ?", String.class, id));
    }

    // ===== ⑤ 口径隔离（本任务最容易错的地方）=====

    /**
     * 补缴与押金/结算口径的物理隔离：补缴前后
     * ① inp_deposit 的行数与金额一字不动；
     * ② inp_settlement 的 total_amount / deposit_amount / balance 一字不动；
     * ③ /account 端点的 depositTotal / executedAmount / balance / owed 一字不动。
     * 只有 inp_arrears_payment 增加流水、inp_arrears.status 推进。
     */
    @Test
    void arrearsPaymentNeverTouchesDepositOrSettlement() {
        Long admId = owingDischarge("口径隔离");
        Long id = arrearsId(admId);

        int depositRows0 = jdbc.queryForObject(
                "select count(*) from inp_deposit where admission_id = ?", Integer.class, admId);
        BigDecimal depositSum0 = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from inp_deposit where admission_id = ?",
                BigDecimal.class, admId);
        var settle0 = jdbc.queryForMap("""
                select total_amount, deposit_amount, balance, status, settle_no
                from inp_settlement where admission_id = ? and settle_type = 'FINAL'
                """, admId);
        var acc0 = inpatientController.account(admId).getData();

        // 全额补缴（欠款收清）
        arrearsService.pay(id, unit(), "CASH", null);
        arrearsService.pay(id, unit(), "CARD", null);
        entityManager.flush();
        assertEquals("CLEARED", jdbc.queryForObject(
                "select status from inp_arrears where id = ?", String.class, id));

        // ① 押金表零触碰——补缴是收欠款，不是补押金
        assertEquals(depositRows0, jdbc.queryForObject(
                "select count(*) from inp_deposit where admission_id = ?", Integer.class, admId),
                "补缴不得新增押金行");
        assertEquals(0, depositSum0.compareTo(jdbc.queryForObject(
                "select coalesce(sum(amount),0) from inp_deposit where admission_id = ?",
                BigDecimal.class, admId)), "补缴不得改变押金合计");

        // ② 结算行零触碰——已出院的结算是既成快照，欠费台账是它的下游派生账
        var settle1 = jdbc.queryForMap("""
                select total_amount, deposit_amount, balance, status, settle_no
                from inp_settlement where admission_id = ? and settle_type = 'FINAL'
                """, admId);
        assertEquals(0, ((BigDecimal) settle0.get("total_amount"))
                .compareTo((BigDecimal) settle1.get("total_amount")));
        assertEquals(0, ((BigDecimal) settle0.get("deposit_amount"))
                .compareTo((BigDecimal) settle1.get("deposit_amount")));
        assertEquals(0, ((BigDecimal) settle0.get("balance"))
                .compareTo((BigDecimal) settle1.get("balance")), "结算余额口径不得被补缴改写");
        assertEquals(settle0.get("status"), settle1.get("status"));
        assertEquals(settle0.get("settle_no"), settle1.get("settle_no"));
        assertEquals(0, ((BigDecimal) settle1.get("balance")).compareTo(unit().multiply(BigDecimal.valueOf(-2))),
                "结算余额仍是 押金-费用 = -2 份，与已收欠款无关");

        // ③ /account 账户口径零漂移（押金合计 - 已发生费用），欠费不因收到欠款而"自愈"
        var acc1 = inpatientController.account(admId).getData();
        assertEquals(0, ((BigDecimal) acc0.get("depositTotal")).compareTo((BigDecimal) acc1.get("depositTotal")));
        assertEquals(0, ((BigDecimal) acc0.get("executedAmount")).compareTo((BigDecimal) acc1.get("executedAmount")));
        assertEquals(0, ((BigDecimal) acc0.get("balance")).compareTo((BigDecimal) acc1.get("balance")));
        assertEquals(acc0.get("owed"), acc1.get("owed"));

        // ④ 补缴只落自己的流水表
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from inp_arrears_payment where arrears_id = ?", Integer.class, id));
    }

    // ===== ⑥ 冲销召回后重结算：更新而非重复挂账 =====

    @Test
    void cancelAndResettleUpdatesLedgerInsteadOfDuplicating() {
        Long admId = owingDischarge("召回重结算");
        Long id = arrearsId(admId);
        arrearsService.pay(id, unit(), "CASH", null);   // 先补一半

        // 出院召回 → 重新结算（费用/押金未变，欠费额应一致）
        inpatientService.cancelSettlement(admId, null, null);
        entityManager.flush();
        inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();

        var row = arrearsRow(admId);   // 内含"至多一条"断言
        assertEquals(id, ((Number) row.get("id")).longValue(), "应更新原挂账行，不得另起新行");
        assertEquals(0, ((BigDecimal) row.get("amount")).compareTo(unit().multiply(BigDecimal.valueOf(2))));
        assertEquals("PARTIAL", row.get("status"), "已补缴的一半必须保留，状态按已补额重算");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from inp_arrears_payment where arrears_id = ?", Integer.class, id),
                "补缴流水不得因重结算丢失或翻倍");
    }

    /** 召回期间补齐押金后重结算已不欠费：撤掉这条无任何人工痕迹的自动挂账 */
    @Test
    void resettleWithoutArrearsRemovesUntouchedAutoLedger() {
        Long admId = owingDischarge("召回补押金");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from inp_arrears where admission_id = ?", Integer.class, admId));

        inpatientService.cancelSettlement(admId, null, null);
        entityManager.flush();
        inpatientService.addDeposit(admId, unit().multiply(BigDecimal.valueOf(5)), "CASH", null);
        entityManager.flush();
        inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();

        assertEquals(0, jdbc.queryForObject(
                "select count(*) from inp_arrears where admission_id = ?", Integer.class, admId),
                "已不欠费且无补缴/催缴痕迹的自动挂账应撤销");
    }
}
