package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutpOrderRepository extends JpaRepository<OutpOrder, Long> {

    List<OutpOrder> findByRegistrationIdOrderByIdAsc(Long registrationId);

    List<OutpOrder> findByRegistrationIdAndStatusOrderByIdAsc(Long registrationId, String status);

    List<OutpOrder> findByRegistrationIdAndOrderTypeAndStatusOrderByIdAsc(Long registrationId, String orderType, String status);

    List<OutpOrder> findByChargeId(Long chargeId);

    List<OutpOrder> findByGroupNo(String groupNo);

    /** 医技执行队列：已收费的检验/检查/治疗 */
    @Query("from OutpOrder o where o.status = 'CHARGED' and o.orderType in ('LAB', 'EXAM', 'TREAT') order by o.id")
    List<OutpOrder> chargedExecutables();

    /** 有已收费待发药药品订单的挂号 id 列表（发药工作队列） */
    @Query("select distinct o.registrationId from OutpOrder o where o.orderType = 'DRUG' and o.status = 'CHARGED'")
    List<Long> registrationIdsWithChargedDrugs();

    /** 有未收费订单的挂号 id 列表（收费工作队列） */
    @Query("select distinct o.registrationId from OutpOrder o where o.status = 'CREATED'")
    List<Long> registrationIdsWithUnchargedOrders();
}
