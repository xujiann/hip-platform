package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.InvStockTake;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvStockTakeRepository extends JpaRepository<InvStockTake, Long> {

    List<InvStockTake> findTop50ByOrderByIdDesc();

    /**
     * 抢占式状态跃迁（第七轮审阅 P2-3）：从 DRAFT 条件更新到目标状态，返回受影响行数。
     * confirm 与 cancel 并发同一草稿单时，读-判-写会让作废单也动了库存——与全仓抢占纪律不一致。
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update InvStockTake t set t.status = :to "
            + "where t.id = :id and t.status = 'DRAFT'")
    int claimStatus(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("to") String to);
}
