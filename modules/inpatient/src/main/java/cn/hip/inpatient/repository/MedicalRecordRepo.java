package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpMedicalRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MedicalRecordRepo extends JpaRepository<InpMedicalRecord, Long> {

    List<InpMedicalRecord> findByAdmissionIdOrderByIdDesc(Long admissionId);
}
