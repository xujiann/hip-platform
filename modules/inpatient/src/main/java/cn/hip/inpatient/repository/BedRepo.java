package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpBed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BedRepo extends JpaRepository<InpBed, Long> {

    List<InpBed> findByWardIdOrderByBedNo(Long wardId);

    /** 原子占床：只有空床才占 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InpBed b set b.status = 'OCCUPIED', b.admissionId = :admissionId where b.id = :bedId and b.status = 'FREE'")
    int occupy(@Param("bedId") Long bedId, @Param("admissionId") Long admissionId);

    /**
     * 释放床位：**必须校验占用者**——无条件释放会在两种时序下出事：
     * 并发转床各自释放旧床导致床位泄漏；慢事务用陈旧 bedId 释放已被新患者占用的床导致两人同床。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InpBed b set b.status = 'FREE', b.admissionId = null "
            + "where b.id = :bedId and b.admissionId = :admissionId")
    int release(@Param("bedId") Long bedId, @Param("admissionId") Long admissionId);
}
