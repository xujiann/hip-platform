package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OutpScheduleRepository extends JpaRepository<OutpSchedule, Long> {

    List<OutpSchedule> findByScheduleDateAndEnabledTrueOrderByDeptIdAscIdAsc(LocalDate date);

    List<OutpSchedule> findByScheduleDateAndDeptIdAndEnabledTrueOrderByIdAsc(LocalDate date, Long deptId);

    List<OutpSchedule> findByScheduleDateOrderByDeptIdAscIdAsc(LocalDate date);

    List<OutpSchedule> findByScheduleDateAndDeptIdOrderByIdAsc(LocalDate date, Long deptId);

    /** 退号释放号源 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutpSchedule s set s.booked = s.booked - 1 where s.id = :id and s.booked > 0")
    int releaseSlot(@Param("id") Long id);
}
