package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementRepo extends JpaRepository<InpSettlement, Long> {

    Optional<InpSettlement> findByAdmissionId(Long admissionId);

    Optional<InpSettlement> findByAdmissionIdAndStatus(Long admissionId, String status);

    /** 抢占冲销：只有 PAID 单能被冲销一次，受影响行数即"本次是否抢到" */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update InpSettlement s set s.status = 'CANCELLED', s.refundedAt = :now, s.refundBy = :operatorId "
                    + "where s.id = :id and s.status = 'PAID'")
    int claimCancel(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                    @org.springframework.data.repository.query.Param("operatorId") Long operatorId);
}
