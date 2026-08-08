package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.DrugItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DrugItemRepository extends JpaRepository<DrugItem, Long> {

    List<DrugItem> findTop20ByEnabledTrueAndNameContainingOrderByCode(String keyword);

    /** 并发安全扣库存：库存足够才扣，返回受影响行数 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DrugItem d set d.stock = d.stock - :qty where d.id = :id and d.stock >= :qty")
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /** 并发安全回补库存（退药），返回受影响行数 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DrugItem d set d.stock = d.stock + :qty where d.id = :id")
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);

    /** 盘点：条件更新 + 判定行数——读-改-写会覆盖并发发药的扣减（流水与实存对不上） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DrugItem d set d.stock = :newStock where d.id = :id and d.stock = :expected")
    int adjustStock(@Param("id") Long id, @Param("expected") int expected, @Param("newStock") int newStock);

    java.util.List<DrugItem> findByEnabledTrueAndStockLessThan(int threshold);
}
