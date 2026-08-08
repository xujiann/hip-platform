package cn.hip.inpatient.repository;

import cn.hip.inpatient.entity.InpOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepo extends JpaRepository<InpOrder, Long> {

    List<InpOrder> findByAdmissionIdOrderByIdAsc(Long admissionId);

    List<InpOrder> findByStatusOrderByIdAsc(String status);

    /** 抢占执行：受影响行数为 0 说明已被他人执行/作废——防两名护士各扣一次库存 */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update InpOrder o set o.status = 'EXECUTED', o.executorId = :executorId, o.executedAt = :now "
                    + "where o.id = :id and o.status = 'CREATED'")
    int claimExecute(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("executorId") Long executorId,
                     @org.springframework.data.repository.query.Param("now") java.time.Instant now);

    /** 作废未执行医嘱（原无此路径：出院时 9012 要求"执行或作废"，实际只能执行掉→多计费） */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update InpOrder o set o.status = 'CANCELLED' where o.id = :id and o.status = 'CREATED'")
    int cancelIfCreated(@org.springframework.data.repository.query.Param("id") Long id);
}
