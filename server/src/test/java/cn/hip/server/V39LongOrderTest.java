package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.inpatient.service.InpatientService.OrderLine;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v39：住院医嘱长期/临时模型（隔离末版）。
 * 核心口径断言：LONG amount=累计执行金额、执行过即 EXECUTED —— 出院汇总/中间结算等
 * 下游读 "EXECUTED 的 amount" 的既有口径零改动天然正确；未停嘱 LONG 不参与中间结算认领。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V39LongOrderTest {

    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private Long admit() {
        Patient p = new Patient();
        p.setName("长嘱" + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎", new BigDecimal("2000"), "CASH", null).getId();
    }

    private List<Long> execIdsOf(Long orderId) {
        em.flush();
        return jdbc.queryForList("select id from inp_order_exec where order_id = ? and status = 'PENDING' order by seq_no",
                Long.class, orderId);
    }

    @Test
    void longOrderAccruesAmountPerExecution() {
        Long admId = admit();
        Long drugId = seeds.drug("长嘱药A" + System.nanoTime() % 10000).getId();
        var order = inpatientService.createOrders(admId,
                List.of(new OrderLine("DRUG", drugId, 2, "口服", "bid", "1粒", "LONG")), null).get(0);
        em.flush();
        BigDecimal unit = order.getUnitPrice();
        // 开立：amount=0、bid 当日 2 行执行行
        assertEquals(0, order.getAmount().compareTo(BigDecimal.ZERO), "LONG 开立不计费");
        var lines = execIdsOf(order.getId());
        assertEquals(2, lines.size(), "bid 应生成当日 2 行");

        // 执行第 1 行：amount=单次（unit×qty=2），状态 EXECUTED
        inpatientService.executeExecLine(lines.get(0), null);
        em.clear();
        var row1 = jdbc.queryForMap("select amount, status from inp_order where id = ?", order.getId());
        assertEquals(0, ((BigDecimal) row1.get("amount")).compareTo(unit.multiply(new BigDecimal("2"))));
        assertEquals("EXECUTED", row1.get("status"));

        // 执行第 2 行：amount 累计 ×2
        inpatientService.executeExecLine(lines.get(1), null);
        var row2 = jdbc.queryForMap("select amount from inp_order where id = ?", order.getId());
        assertEquals(0, ((BigDecimal) row2.get("amount")).compareTo(unit.multiply(new BigDecimal("4"))),
                "两次执行费用累计");
        // 重复执行同一行 → 9126
        assertEquals(9126, assertThrows(InpException.class,
                () -> inpatientService.executeExecLine(lines.get(1), null)).code);

        // 出院汇总口径：EXECUTED.amount 已含累计，零改动正确
        var settle = inpatientService.discharge(admId, null, "CASH");
        assertEquals(0, settle.getTotalAmount().compareTo(unit.multiply(new BigDecimal("4"))),
                "出院总额=LONG 累计执行金额（下游口径零改动）");
    }

    @Test
    void longOrderRejectsSingleExecutePath() {
        Long admId = admit();
        Long drugId = seeds.drug("长嘱药B" + System.nanoTime() % 10000).getId();
        var order = inpatientService.createOrders(admId,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "qd", "1粒", "LONG")), null).get(0);
        em.flush();
        assertEquals(9125, assertThrows(InpException.class,
                () -> inpatientService.execute(order.getId(), null)).code, "LONG 不走单次执行");
    }

    @Test
    void stopOrderFreezesAndCancelsNeverExecuted() {
        Long admId = admit();
        Long drugId = seeds.drug("长嘱药C" + System.nanoTime() % 10000).getId();
        // 停嘱仅限 LONG
        var temp = inpatientService.createOrders(admId,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "qd", "1粒", null)), null).get(0);
        em.flush();
        assertEquals(9128, assertThrows(InpException.class,
                () -> inpatientService.stopOrder(temp.getId(), null)).code);

        // 从未执行的 LONG：停嘱→行 SKIPPED + 医嘱 CANCELLED（不拦出院）
        var never = inpatientService.createOrders(admId,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "bid", "1粒", "LONG")), null).get(0);
        em.flush();
        inpatientService.stopOrder(never.getId(), null);
        assertEquals("CANCELLED", jdbc.queryForObject("select status from inp_order where id=?", String.class, never.getId()));
        assertEquals(0, (int) jdbc.queryForObject(
                "select count(*) from inp_order_exec where order_id=? and status='PENDING'", Integer.class, never.getId()));
        // 重复停嘱 → 9127；停嘱后执行行不能再执行
        assertEquals(9127, assertThrows(InpException.class, () -> inpatientService.stopOrder(never.getId(), null)).code);

        // 执行过一次的 LONG：停嘱后保持 EXECUTED、费用固化
        var used = inpatientService.createOrders(admId,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "bid", "1粒", "LONG")), null).get(0);
        em.flush();
        var lines = execIdsOf(used.getId());
        inpatientService.executeExecLine(lines.get(0), null);
        inpatientService.stopOrder(used.getId(), null);
        var row = jdbc.queryForMap("select status, stop_at, amount from inp_order where id = ?", used.getId());
        assertEquals("EXECUTED", row.get("status"));
        assertNotNull(row.get("stop_at"));
        // 剩余 PENDING 行已 SKIPPED，执行报 9126（行态）或 9127（停嘱）
        var e = assertThrows(InpException.class, () -> inpatientService.executeExecLine(lines.get(1), null));
        assertTrue(e.code == 9126 || e.code == 9127);
    }

    @Test
    void interimSettleExcludesActiveLongIncludesStopped() {
        Long admId = admit();
        Long drugId = seeds.drug("长嘱药D" + System.nanoTime() % 10000).getId();
        // TEMP 一条执行掉（可认领）+ LONG 一条执行一次（未停嘱不可认领）
        var temp = inpatientService.createOrders(admId,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "qd", "1粒", null)), null).get(0);
        var lng = inpatientService.createOrders(admId,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "qd", "1粒", "LONG")), null).get(0);
        em.flush();
        inpatientService.execute(temp.getId(), null);
        inpatientService.executeExecLine(execIdsOf(lng.getId()).get(0), null);
        em.flush();

        var interim = inpatientService.interimSettle(admId, null, "CASH");
        em.flush();
        // 中间结算只含 TEMP（未停嘱 LONG 费用未固化不认领）
        assertEquals(0, interim.getTotalAmount().compareTo(temp.getAmount()),
                "中间结算不含未停嘱 LONG 的费用");
        assertNull(jdbc.queryForObject("select interim_settle_id from inp_order where id=?", Long.class, lng.getId()),
                "未停嘱 LONG 不得被认领");

        // 停嘱固化后：第二次中间结算可认领 LONG
        inpatientService.stopOrder(lng.getId(), null);
        var interim2 = inpatientService.interimSettle(admId, null, "CASH");
        em.flush();
        assertNotNull(jdbc.queryForObject("select interim_settle_id from inp_order where id=?", Long.class, lng.getId()),
                "停嘱固化后可认领");
        assertEquals(0, interim2.getTotalAmount().compareTo(
                jdbc.queryForObject("select amount from inp_order where id=?", BigDecimal.class, lng.getId())));
    }

    @Test
    void dailyGenerationIsIdempotent() {
        Long admId = admit();
        Long drugId = seeds.drug("长嘱药E" + System.nanoTime() % 10000).getId();
        var lng = inpatientService.createOrders(admId,
                List.of(new OrderLine("DRUG", drugId, 1, "口服", "tid", "1粒", "LONG")), null).get(0);
        em.flush();
        assertEquals(3, execIdsOf(lng.getId()).size(), "tid 当日 3 行");
        // 重跑当日生成：幂等不重复
        inpatientService.generateDailyExecLines(cn.hip.platform.core.config.BusinessDates.today());
        assertEquals(3, (int) jdbc.queryForObject(
                "select count(*) from inp_order_exec where order_id=?", Integer.class, lng.getId()));
        // 生成明日行
        inpatientService.generateDailyExecLines(cn.hip.platform.core.config.BusinessDates.today().plusDays(1));
        assertEquals(6, (int) jdbc.queryForObject(
                "select count(*) from inp_order_exec where order_id=?", Integer.class, lng.getId()));
    }
}
