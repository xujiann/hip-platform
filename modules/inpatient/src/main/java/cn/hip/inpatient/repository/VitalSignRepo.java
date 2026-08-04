package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpVitalSign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VitalSignRepo extends JpaRepository<InpVitalSign, Long> {

    List<InpVitalSign> findByAdmissionIdOrderByMeasuredAtAsc(Long admissionId);
}
