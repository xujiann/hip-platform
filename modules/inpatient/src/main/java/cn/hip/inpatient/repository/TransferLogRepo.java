package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpTransferLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferLogRepo extends JpaRepository<InpTransferLog, Long> {

    List<InpTransferLog> findByAdmissionIdOrderByIdAsc(Long admissionId);
}
