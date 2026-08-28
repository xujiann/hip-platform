package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.InvStockIn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface InvStockInRepository extends JpaRepository<InvStockIn, Long> {

    List<InvStockIn> findTop50ByOrderByIdDesc();

    /** 待验收列表（入库验收页） */
    List<InvStockIn> findByAcceptStatusOrderByIdDesc(String acceptStatus);

    /**
     * 验收通过：条件更新（仅当仍为待验收才置 ACCEPTED），返回受影响行数。
     * 用条件更新而非读-改-写：验收页双击或多人同时验收同一单时，只有第一次影响 1 行、
     * 真正加库存，其余影响 0 行——防止一次入库被重复入账（库存虚增）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InvStockIn s set s.acceptStatus = 'ACCEPTED', s.acceptedBy = :operatorId, s.acceptedAt = :now
            where s.id = :id and s.acceptStatus = 'PENDING_ACCEPT'
            """)
    int markAccepted(@Param("id") Long id, @Param("operatorId") Long operatorId, @Param("now") Instant now);

    /** 拒收：同样条件更新，只有待验收才能拒收 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InvStockIn s set s.acceptStatus = 'REJECTED', s.acceptedBy = :operatorId,
                   s.acceptedAt = :now, s.rejectReason = :reason
            where s.id = :id and s.acceptStatus = 'PENDING_ACCEPT'
            """)
    int markRejected(@Param("id") Long id, @Param("operatorId") Long operatorId,
                     @Param("now") Instant now, @Param("reason") String reason);

    /** 某药品全部批次入库（按效期升序，供效期预警 FEFO 估算分摊；效期为空排最后） */
    @Query("select s from InvStockIn s where s.drugId = :drugId and s.acceptStatus = 'ACCEPTED' "
            + "order by case when s.expireDate is null then 1 else 0 end, s.expireDate asc, s.id asc")
    List<InvStockIn> findAcceptedBatchesByDrugFefo(@Param("drugId") Long drugId);

    /** 效期在阈值内（含已过期）的已验收批次，供预警扫描（drugId 去重前的候选集） */
    @Query("select s from InvStockIn s where s.acceptStatus = 'ACCEPTED' "
            + "and s.expireDate is not null and s.expireDate <= :threshold order by s.expireDate asc")
    List<InvStockIn> findAcceptedNearExpiry(@Param("threshold") java.time.LocalDate threshold);
}
