package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementRepo extends JpaRepository<InpSettlement, Long> {

    Optional<InpSettlement> findByAdmissionId(Long admissionId);
}
