package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpEmr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OutpEmrRepository extends JpaRepository<OutpEmr, Long> {

    Optional<OutpEmr> findByRegistrationId(Long registrationId);

    /** v37 历史就诊调阅：批量取多次就诊病历，避免逐条 N+1 */
    java.util.List<OutpEmr> findByRegistrationIdIn(java.util.Collection<Long> registrationIds);
}
