package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpAdmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdmissionRepo extends JpaRepository<InpAdmission, Long> {

    List<InpAdmission> findByStatusOrderByIdDesc(String status);
}
