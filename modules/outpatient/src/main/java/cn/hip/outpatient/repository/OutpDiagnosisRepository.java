package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpDiagnosis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutpDiagnosisRepository extends JpaRepository<OutpDiagnosis, Long> {

    List<OutpDiagnosis> findByRegistrationIdOrderByPrimaryDiagDescIdAsc(Long registrationId);

    void deleteByRegistrationId(Long registrationId);
}
