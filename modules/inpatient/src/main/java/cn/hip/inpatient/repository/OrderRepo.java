package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepo extends JpaRepository<InpOrder, Long> {

    List<InpOrder> findByAdmissionIdOrderByIdAsc(Long admissionId);

    List<InpOrder> findByStatusOrderByIdAsc(String status);
}
