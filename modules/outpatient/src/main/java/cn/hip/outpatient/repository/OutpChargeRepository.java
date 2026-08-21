package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpCharge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutpChargeRepository extends JpaRepository<OutpCharge, Long> {

    List<OutpCharge> findByRegistrationIdOrderByIdDesc(Long registrationId);

    List<OutpCharge> findTop50ByOrderByIdDesc();

    /**
     * 抢占退费：把 PAID 单据本身条件更新为 REFUNDED，0 行即他方已退。
     *
     * <p>此前门诊退费只抢占明细行（claimRefund(ids)），单据本身是读-判-写——
     * 并发时六方都读到 PAID 便都放行，YB 单会重复冲正医保额度。
     * 住院侧 {@code SettlementRepo.claimCancel} 一直是这个写法，门诊侧漏了（1.2.9 由 CI 抓出）。
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update OutpCharge c set c.status = 'REFUNDED', c.refundedAt = :now, c.refundBy = :operatorId "
                    + "where c.id = :id and c.status = 'PAID'")
    int claimRefund(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                    @org.springframework.data.repository.query.Param("operatorId") Long operatorId);
}
