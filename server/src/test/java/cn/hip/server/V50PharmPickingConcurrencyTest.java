package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.PharmPickService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v50 车道B 并发回归：摆药单的两道防重必须在<b>真并发</b>下成立。
 *
 * <p><b>为什么必须多线程测</b>：同表同行的「开始摆药」靠 PG 的行锁天然串行，单线程调两次
 * 也能看出条件更新落空；但<b>建单</b>是两次不同行的 insert，行锁保护不到——
 * 挡住它的是 {@code uq_pharm_picking_live_reg} 与 {@code uq_pharm_pick_line_live}
 * 两个部分唯一索引。读-判-写在两个药师同时点「建摆药单」时会各建一张，
 * 同一批药被配两遍，患者拿到双份药。这条只有真并发能测出来。
 *
 * <p><b>本类不加 {@code @Transactional}</b>——各线程需各自提交事务才能形成真实并发写，
 * 故夹具走 {@link TransactionTemplate} 显式提交，并在 {@link #cleanUp()} 里按外键顺序删净。
 * （照 {@code StockRestoreConcurrencyTest} 的写法。）
 */
@SpringBootTest
@WithMockUser(username = "admin", roles = {"ADMIN", "PHARMACIST"})
class V50PharmPickingConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired PharmPickService service;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private Long patientId;
    private Long scheduleId;
    private Long regId;
    private Long uid;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        uid = jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);

        tx.executeWithoutResult(s -> {
            Patient p = new Patient();
            p.setName("摆药并发测试");
            p.setSex("U");
            patientId = patientService.register(p).getId();

            OutpSchedule sc = new OutpSchedule();
            sc.setDeptId(1L);
            sc.setScheduleDate(BusinessDates.today());
            sc.setFee(BigDecimal.ZERO);
            sc.setCapacity(99);
            scheduleId = scheduleRepository.save(sc).getId();

            regId = registrationService.register(patientId, scheduleId).getId();
            doctorStationService.startVisit(regId, null);
            doctorStationService.createOrders(regId, List.of(
                    new OrderLine("DRUG", seeds.drug("阿莫西林").getId(), 2, "口服", "tid", "1粒", 3),
                    new OrderLine("DRUG", seeds.drug("摆药并发用药").getId(), 1, "口服", "qd", "1片", 3)),
                    null);
        });
        // 绕开 ChargeService：本类测的是并发建单，不测收费
        tx.executeWithoutResult(s ->
                jdbc.update("update outp_order set status = 'CHARGED' where registration_id = ?", regId));
    }

    @AfterEach
    void cleanUp() {
        // 本类不靠事务回滚，必须自己删净（按外键顺序），否则会污染其它用例的全库口径查询
        tx.executeWithoutResult(s -> {
            jdbc.update("delete from pharm_picking_line where picking_id in "
                    + "(select id from pharm_picking_order where registration_id = ?)", regId);
            jdbc.update("delete from pharm_picking_order where registration_id = ?", regId);
            jdbc.update("delete from outp_order where registration_id = ?", regId);
            jdbc.update("delete from outp_registration where id = ?", regId);
            jdbc.update("delete from outp_schedule where id = ?", scheduleId);
            jdbc.update("delete from empi_patient where id = ?", patientId);
        });
    }

    /**
     * 两个药师同时点「建摆药单」：恰好一个成功，其余全部 5421。
     * <b>失败的样子是 2 张单</b>——同一批药被配两遍，患者拿双份药。
     */
    @Test
    void concurrentCreateYieldsExactlyOnePickingOrder() throws Exception {
        var codes = runConcurrently(() -> service.create(regId, null, uid));

        assertEquals(1, codes.stream().filter(c -> c == 0).count(),
                "恰好一个线程建单成功，实际结果码：" + codes);
        assertTrue(codes.stream().filter(c -> c != 0).allMatch(c -> c == 5421),
                "落败的必须是 5421（已有在途摆药单），实际：" + codes);

        assertEquals(1L, count("select count(*) from pharm_picking_order where registration_id = ?", regId),
                "全库只能留下一张摆药单");
        assertEquals(2L, count("select count(*) from pharm_picking_line l "
                + "join pharm_picking_order h on h.id = l.picking_id where h.registration_id = ?", regId),
                "两条医嘱各一条明细，落败线程的明细必须随事务一起回滚");
    }

    /** 两个药师同时点「开始摆药」：恰好一个成功，其余 5423，摆药人只能有一个。 */
    @Test
    void concurrentStartYieldsExactlyOnePicker() throws Exception {
        Long pickingId = tx.execute(s ->
                ((Number) service.create(regId, null, uid).get("id")).longValue());

        var codes = runConcurrently(() -> service.start(pickingId, uid));

        assertEquals(1, codes.stream().filter(c -> c == 0).count(),
                "恰好一个线程抢到摆药，实际结果码：" + codes);
        assertTrue(codes.stream().filter(c -> c != 0).allMatch(c -> c == 5423),
                "落败的必须是 5423（状态不允许），实际：" + codes);
        assertEquals("PICKING", jdbc.queryForObject(
                "select status from pharm_picking_order where id = ?", String.class, pickingId));
    }

    /** 同一条明细被两个人同时确认：恰好一个成功，其余 5427。实摆量不能被写两遍。 */
    @Test
    void concurrentPickOnSameLineYieldsExactlyOneConfirmation() throws Exception {
        Long pickingId = tx.execute(s ->
                ((Number) service.create(regId, null, uid).get("id")).longValue());
        tx.executeWithoutResult(s -> service.start(pickingId, uid));
        Long lineId = jdbc.queryForObject(
                "select id from pharm_picking_line where picking_id = ? order by id limit 1",
                Long.class, pickingId);

        var codes = runConcurrently(() -> service.pickLine(pickingId, lineId, 1, null, uid));

        assertEquals(1, codes.stream().filter(c -> c == 0).count(),
                "恰好一个线程确认成功，实际结果码：" + codes);
        assertTrue(codes.stream().filter(c -> c != 0).allMatch(c -> c == 5427),
                "落败的必须是 5427（明细状态不符），实际：" + codes);
    }

    // ================= 工具 =================

    /**
     * 把同一个动作交给 {@value #THREADS} 个线程同时执行（{@code CountDownLatch} 齐发），
     * 返回各自的业务码（0 = 成功）。每个线程各起一个事务，形成真实并发写。
     */
    private List<Integer> runConcurrently(Callable<?> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger unexpected = new AtomicInteger();
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        // 每个线程一个独立事务：TransactionTemplate 是线程安全的
                        tx.execute(s -> {
                            try {
                                action.call();
                            } catch (RuntimeException re) {
                                throw re;
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                            return null;
                        });
                        return 0;
                    } catch (HipBizException e) {
                        return e.code;
                    } catch (RuntimeException e) {
                        unexpected.incrementAndGet();
                        return -1;
                    }
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            go.countDown();
            var codes = new ArrayList<Integer>();
            for (Future<Integer> f : futures) {
                codes.add(f.get(60, TimeUnit.SECONDS));
            }
            assertEquals(0, unexpected.get(),
                    "出现了非业务异常（并发下应当是 5421/5423/5427 之一，不是 500）：" + codes);
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
