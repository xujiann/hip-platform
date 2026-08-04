package cn.hip.platform.empi.repository;

import cn.hip.platform.empi.entity.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByPatientNo(String patientNo);

    Optional<Patient> findByIdTypeAndIdNo(String idType, String idNo);

    @Query("""
            from Patient p where p.active = true and
            (p.name like %:kw% or p.patientNo = :kw or p.idNo = :kw or p.phone = :kw)
            """)
    Page<Patient> search(@Param("kw") String keyword, Pageable pageable);
}
