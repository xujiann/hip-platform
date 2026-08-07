package cn.hip.server;

import cn.hip.platform.masterdata.repository.DrugItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退药库存回补并发回归：restoreStock 为数据库端原子累加（update ... set stock = stock + ?），
 * 多线程同时回补不丢更新。原读-改-写实现在此场景下会丢失部分回补量。
 * 注意：本类不加 @Transactional——各线程需各自提交事务才能形成真实并发写。
 */
@SpringBootTest
class StockRestoreConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired DrugItemRepository drugRepository;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void concurrentRestoreStockLosesNoUpdate() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long drugId = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode("阿莫西林")
                .get(0).getId();
        int before = drugRepository.findById(drugId).orElseThrow().getStock();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return tx.execute(s -> drugRepository.restoreStock(drugId, 1));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<Integer> f : futures) {
                assertEquals(1, f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(before + THREADS,
                    drugRepository.findById(drugId).orElseThrow().getStock());
        } finally {
            pool.shutdownNow();
            // 还原测试数据：扣回加上去的量
            tx.execute(s -> drugRepository.deductStock(drugId, THREADS));
        }
    }
}
