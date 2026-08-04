package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpCriticalAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutpCriticalAlertRepository extends JpaRepository<OutpCriticalAlert, Long> {

    List<OutpCriticalAlert> findByStatusOrderByIdDesc(String status);

    List<OutpCriticalAlert> findTop50ByOrderByIdDesc();
}
