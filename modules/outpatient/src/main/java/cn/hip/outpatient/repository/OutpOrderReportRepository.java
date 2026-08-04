package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpOrderReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutpOrderReportRepository extends JpaRepository<OutpOrderReport, Long> {

    Optional<OutpOrderReport> findByOrderId(Long orderId);

    List<OutpOrderReport> findByOrderIdIn(List<Long> orderIds);
}
