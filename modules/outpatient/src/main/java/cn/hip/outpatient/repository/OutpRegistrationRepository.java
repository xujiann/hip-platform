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

    /** v80 审阅修补二（R2-4）：患者历次就诊按就诊日期倒序、同日按挂号 id 倒序（按 id 倒序时后补挂的往次号会排在今天上面）。 */
    List<OutpRegistration> findTop50ByPatientIdOrderByVisitDateDescIdDesc(Long patientId);

    /**
     * v80 审阅修补二：患者级既往资料（诊断助手历史段、患者历次就诊）的对象级判据——口径「当日就诊队列共享、往次就诊归本人」。
     * 该患者存在一条<b>未退号</b>挂号满足其一即可：挂号归属是我；就诊日期是今天（当日队列全院共享，代班、科室号接诊前照常）；
     * 这次就诊的门诊病历是我写的（代班写过的往次就诊）。<b>不设「归属为空放行」</b>——那会让任何挂过科室号的患者对全院医生敞开。
     */
    @org.springframework.data.jpa.repository.Query(value = """
            select exists (
              select 1 from outp_registration r
              where r.patient_id = :patientId and r.status <> 'CANCELLED'
                and (r.doctor_id = :me or r.visit_date = :today
                     or exists (select 1 from outp_emr e where e.registration_id = r.id and e.doctor_id = :me)))
            """, nativeQuery = true)
    boolean patientReadableBy(@org.springframework.data.repository.query.Param("patientId") Long patientId,
                              @org.springframework.data.repository.query.Param("me") Long me,
                              @org.springframework.data.repository.query.Param("today") LocalDate today);

    Optional<OutpRegistration> findByScheduleIdAndPatientIdAndStatus(Long scheduleId, Long patientId, String status);

    /** 抢占叫号：并发叫号器只有一方拿到该患者 */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update OutpRegistration r set r.status = 'CALLED' where r.id = :id and r.status = 'REGISTERED'")
    int claimCall(@org.springframework.data.repository.query.Param("id") Long id);
}
