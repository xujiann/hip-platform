package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpTriage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutpTriageRepository extends JpaRepository<OutpTriage, Long> {

    List<OutpTriage> findTop100ByOrderByLevelAscIdDesc();
}
