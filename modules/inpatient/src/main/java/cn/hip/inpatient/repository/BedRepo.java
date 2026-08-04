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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InpBed b set b.status = 'FREE', b.admissionId = null where b.id = :bedId")
    int release(@Param("bedId") Long bedId);
}
