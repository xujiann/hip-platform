package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpAdmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdmissionRepo extends JpaRepository<InpAdmission, Long> {

    List<InpAdmission> findByStatusOrderByIdDesc(String status);

    /**
     * 抢占出院：把 IN_HOSPITAL 置为 DISCHARGED，受影响行数即"本次结算是否抢到"。
     * 结算读取费用/押金快照与置位之间若无抢占，期间并发缴的押金既不进结算也不退还。
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update InpAdmission a set a.status = 'DISCHARGED', a.dischargedAt = :now "
                    + "where a.id = :id and a.status = 'IN_HOSPITAL'")
    int claimDischarge(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
