package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpDeposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepositRepo extends JpaRepository<InpDeposit, Long> {

    List<InpDeposit> findByAdmissionIdOrderByIdAsc(Long admissionId);
}
