package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpTriage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutpTriageRepository extends JpaRepository<OutpTriage, Long> {

    List<OutpTriage> findTop100ByOrderByLevelAscIdDesc();
    @org.springframework.data.jpa.repository.Query(value="select * from outp_triage where (:cross or doctor_id is null or doctor_id=:actor) order by level,id desc limit 100",nativeQuery=true)
    List<OutpTriage> visible(Long actor, boolean cross);
}
