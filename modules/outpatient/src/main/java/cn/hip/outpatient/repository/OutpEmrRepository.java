package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpEmr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OutpEmrRepository extends JpaRepository<OutpEmr, Long> {

    Optional<OutpEmr> findByRegistrationId(Long registrationId);
}
