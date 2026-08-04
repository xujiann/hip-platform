package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OutpRegistrationRepository extends JpaRepository<OutpRegistration, Long> {

    List<OutpRegistration> findByVisitDateOrderByIdDesc(LocalDate visitDate);

    List<OutpRegistration> findTop50ByPatientIdOrderByIdDesc(Long patientId);

    Optional<OutpRegistration> findByScheduleIdAndPatientIdAndStatus(Long scheduleId, Long patientId, String status);
}
