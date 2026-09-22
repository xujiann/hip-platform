package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OutpRegistrationRepository extends JpaRepository<OutpRegistration, Long> {

    List<OutpRegistration> findByVisitDateOrderByIdDesc(LocalDate visitDate);

    /** v70 包 B：跨日期检索（965★）。零条件时**不走这条**，旧路径原样保留。 */
    List<OutpRegistration> findByVisitDateBetweenOrderByIdDesc(LocalDate from, LocalDate to);

    /** v70 包 B：跨日期 + 按接诊医生过滤（2033★「我的患者」）。 */
    List<OutpRegistration> findByVisitDateBetweenAndDoctorIdOrderByIdDesc(
            LocalDate from, LocalDate to, Long doctorId);

    List<OutpRegistration> findTop50ByPatientIdOrderByIdDesc(Long patientId);

    Optional<OutpRegistration> findByScheduleIdAndPatientIdAndStatus(Long scheduleId, Long patientId, String status);

    /** 抢占叫号：并发叫号器只有一方拿到该患者 */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update OutpRegistration r set r.status = 'CALLED' where r.id = :id and r.status = 'REGISTERED'")
    int claimCall(@org.springframework.data.repository.query.Param("id") Long id);
}
