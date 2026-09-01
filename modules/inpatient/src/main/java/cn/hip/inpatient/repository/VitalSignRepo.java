package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpVitalSign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface VitalSignRepo extends JpaRepository<InpVitalSign, Long> {

    /**
     * 全量按时间正序——住院期全程曲线（医生站 InpDoctorView / GET /vitals 契约）。
     * v42 不动：新增的周窗口查询另立方法，避免动既有拉取的返回顺序与条数。
     */
    List<InpVitalSign> findByAdmissionIdOrderByMeasuredAtAsc(Long admissionId);

    /**
     * v42：体温单按住院周取窗口内格点（半开区间 [from, to)——闭区间会把次日 00:00:00
     * 整点的那次测量重复算进相邻两周）。
     */
    List<InpVitalSign> findByAdmissionIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
            Long admissionId, Instant from, Instant to);
}
