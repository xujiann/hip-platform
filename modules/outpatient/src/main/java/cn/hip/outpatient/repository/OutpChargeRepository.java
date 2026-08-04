package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpCharge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutpChargeRepository extends JpaRepository<OutpCharge, Long> {

    List<OutpCharge> findByRegistrationIdOrderByIdDesc(Long registrationId);

    List<OutpCharge> findTop50ByOrderByIdDesc();
}
