package cn.hip.server;

import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DispenseService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件更新抢占点的**真并发**实证（1.2.8 v26）。
 *
 * <p>历轮审阅在这些点上反复出事：双结算（P1-7）、双发药（P1-9）、退费与发药竞态（P1-8）、
 * 双执行扣两次库存（P1-6）。修复用的是"条件更新 + 判定行数"，但该模式**在真并发下
 * 是否真的只让一方成功，此前从未在多线程真提交模式下验证过**——
 * 按 docs/测试方法论.md 第 ④ 条，@Transactional 单线程测不出这个。
 *
 * <p>**本类不加 @Transactional**：各线程需各自提交事务才能形成真实并发写。
 */
@SpringBootTest
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase128ClaimConcurrencyTest {

    private static final int THREADS = 6;

    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired DispenseService dispenseService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired cn.hip.outpatient.repository.OutpChargeRepository chargeRepository;

    /** 并发跑同一动作，返回 {成功数, 失败数}；失败必须是可读业务异常而非裸错误 */
    private int[] raceCount(Runnable action) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        tx.execute(s -> {
                            action.run();
                            return null;
                        });
                        return true;
                    } catch (Exception e) {
                        Throwable root = e;
                        while (root.getCause() != null) {
                            root = root.getCause();
                        }
                        boolean biz = e instanceof cn.hip.platform.core.common.HipBizException
                                || root instanceof cn.hip.platform.core.common.HipBizException
                                || e.getClass().getName().contains("Duplicate")
                                || root.getClass().getName().contains("Duplicate")
                                || e.getClass().getName().contains("CannotAcquireLock")
                                || root.getClass().getName().contains("Deadlock");
                        assertTrue(biz, "抢占失败应为业务异常或锁冲突，实际：" + root);
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            go.countDown();
            int ok = 0;
            int fail = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(60, TimeUnit.SECONDS)) {
                    ok++;
                } else {
                    fail++;
                }
            }
            System.out.println("[并发实证] 成功=" + ok + " 抢占失败=" + fail);
            return new int[]{ok, fail};
        } finally {
            pool.shutdownNow();
        }
    }

    private Long newVisitWithDrugOrder(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(1L);
        sch.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        sch.setFee(BigDecimal.ZERO);
        sch.setCapacity(50);
        sch = scheduleRepository.save(sch);
        Long regId = registrationService.register(pid, sch.getId()).getId();
        doctorStationService.startVisit(regId, null);
        doctorStationService.createOrders(regId, List.of(new DoctorStationService.OrderLine(
                "DRUG", seeds.drug("并发测试药").getId(), 1, "口服", "qd", "1粒", null)), null);
        return regId;
    }

    /** claimCharge：并发结算同一就诊必须恰好出一张 PAID 单（双倍扣款是 P1-7） */
    @Test
    void concurrentSettleProducesExactlyOneCharge() throws Exception {
        Long regId = newVisitWithDrugOrder("并发结算128");
        int[] r = raceCount(() -> chargeService.settle(regId, "CASH", null));
        assertEquals(1, r[0], "并发结算只能有一方成功，实际成功 " + r[0]);
        assertEquals(THREADS - 1, r[1], "其余线程必须都被抢占拦下（若为 0 说明没形成真竞争）");
        Integer charges = jdbc.queryForObject(
                "select count(*) from outp_charge where registration_id = ?", Integer.class, regId);
        assertEquals(1, charges, "终态必须只有一张结算单");
    }

    /** claimDispense：并发发药只扣一次库存（双发药是 P1-9） */
    @Test
    void concurrentDispenseDeductsStockOnce() throws Exception {
        Long regId = newVisitWithDrugOrder("并发发药128");
        chargeService.settle(regId, "CASH", null);
        Long drugId = seeds.drug("并发测试药").getId();
        int before = seeds.stockOf(drugId);

        int[] r = raceCount(() -> dispenseService.dispense(regId));
        assertEquals(1, r[0], "并发发药只能有一方成功，实际成功 " + r[0]);
        assertEquals(THREADS - 1, r[1], "其余线程必须都被抢占拦下");
        assertEquals(before - 1, seeds.stockOf(drugId), "库存只能被扣一次（P1-9 曾扣两次）");
        Integer dispensed = jdbc.queryForObject(
                "select count(*) from outp_order where registration_id = ? and status = 'DISPENSED'",
                Integer.class, regId);
        assertEquals(1, dispensed);
    }

    /**
     * claimRefund：并发退费只成功一次（重复冲销医保额度是 P1-14）。
     *
     * <p><b>本用例是"本地绿、CI 红"的实例</b>：1.2.8 首版此处只抢占明细行，单据本身是读-判-写。
     * 本地时序下先行线程已提交、后来者读到 REFUNDED 而被 5003 挡住，看起来完全正常；
     * CI 上六线程同时读到 PAID，**六方全部放行**。故此处**跑三轮**——
     * 单轮并发天然带时序侥幸，轮次是把偶发变必现的最省事手段。
     */
    @Test
    void concurrentRefundSucceedsOnce() throws Exception {
        for (int round = 1; round <= 3; round++) {
            Long regId = newVisitWithDrugOrder("并发退费128-" + round);
            final var charge = chargeService.settle(regId, "CASH", null);
            int[] r = raceCount(() -> chargeService.refund(charge.getId(), null));
            assertEquals(1, r[0], "并发退费只能有一方成功（第 " + round + " 轮），实际成功 " + r[0]);
            assertEquals(THREADS - 1, r[1], "其余线程必须都被抢占拦下（第 " + round + " 轮）");
            String status = jdbc.queryForObject(
                    "select status from outp_charge where id = ?", String.class, charge.getId());
            assertEquals("REFUNDED", status);
        }
    }

    /**
     * 抢占语义的**确定性**证明（1.2.9）：不依赖时序，任何机器上恒定可复现。
     *
     * <p>上面几个用例靠多线程撞出竞争，而竞争强度随机器而变——退费缺陷正是
     * 本地六线程恒绿、CI 上六方全放行。故防线本身还需要一个不靠时序的锁：
     * 条件更新对已退单据必须返回 0 行。此断言在缺该防线的版本上根本编译不过。
     */
    @Test
    void claimRefundRejectsAlreadyRefundedDeterministically() {
        Long regId = newVisitWithDrugOrder("抢占语义128");
        var charge = chargeService.settle(regId, "CASH", null);
        chargeService.refund(charge.getId(), null);

        // 本类刻意不带 @Transactional（各线程需各自提交），而 @Modifying 需要事务，故显式包一层
        int again = new TransactionTemplate(txManager).execute(
                st -> chargeRepository.claimRefund(charge.getId(), java.time.Instant.now(), null));
        assertEquals(0, again, "已退单据再抢占必须 0 行——这是防重复冲正医保额度的根防线");

        var ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> chargeService.refund(charge.getId(), null));
        assertTrue(String.valueOf(ex.getMessage()).contains("已退费"),
                "二次退费应给出可读提示，实际：" + ex.getMessage());
    }

    /**
     * 跨业务竞态（v26-B）：退费与发药同时发生——历史事故形态是"钱已退、药也发了"（P1-8）。
     * 两者必须互斥：要么退成功药没发，要么药发了退不掉。
     */
    @Test
    void refundAndDispenseAreMutuallyExclusive() throws Exception {
        Long regId = newVisitWithDrugOrder("退发竞态128");
        var charge = chargeService.settle(regId, "CASH", null);
        Long drugId = seeds.drug("并发测试药").getId();
        int before = seeds.stockOf(drugId);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<Boolean> refundTask = pool.submit(() -> {
                go.await();
                try {
                    tx.execute(s -> chargeService.refund(charge.getId(), null));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });
            Future<Boolean> dispenseTask = pool.submit(() -> {
                go.await();
                try {
                    tx.execute(s -> dispenseService.dispense(regId));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });
            go.countDown();
            boolean refunded = refundTask.get(60, TimeUnit.SECONDS);
            boolean dispensed = dispenseTask.get(60, TimeUnit.SECONDS);

            assertFalse(refunded && dispensed, "钱已退且药已发是历史事故 P1-8，两者必须互斥");
            String status = jdbc.queryForObject(
                    "select status from outp_charge where id = ?", String.class, charge.getId());
            int after = seeds.stockOf(drugId);
            if (refunded) {
                assertEquals("REFUNDED", status);
                assertEquals(before, after, "退费成功则库存不应被扣");
            } else {
                assertEquals("PAID", status, "发药成功则结算单必须仍是 PAID");
                assertEquals(before - 1, after);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
