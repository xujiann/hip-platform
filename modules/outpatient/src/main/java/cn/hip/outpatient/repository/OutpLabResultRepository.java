package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpLabResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutpLabResultRepository extends JpaRepository<OutpLabResult, Long> {

    List<OutpLabResult> findByOrderIdOrderByIdAsc(Long orderId);
}
