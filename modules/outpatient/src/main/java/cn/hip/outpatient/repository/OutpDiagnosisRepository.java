package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpDiagnosis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutpDiagnosisRepository extends JpaRepository<OutpDiagnosis, Long> {

    List<OutpDiagnosis> findByRegistrationIdOrderByPrimaryDiagDescIdAsc(Long registrationId);

    /** v37 历史就诊调阅：批量取多次就诊诊断，避免逐条 N+1 */
    List<OutpDiagnosis> findByRegistrationIdIn(java.util.Collection<Long> registrationIds);

    void deleteByRegistrationId(Long registrationId);
}
