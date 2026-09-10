package cn.hip.outpatient.repository;

import cn.hip.outpatient.entity.OutpOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutpOrderRepository extends JpaRepository<OutpOrder, Long> {

    List<OutpOrder> findByRegistrationIdOrderByIdAsc(Long registrationId);

    List<OutpOrder> findByRegistrationIdAndStatusOrderByIdAsc(Long registrationId, String status);

    List<OutpOrder> findByRegistrationIdAndOrderTypeAndStatusOrderByIdAsc(Long registrationId, String orderType, String status);

    List<OutpOrder> findByChargeId(Long chargeId);

    List<OutpOrder> findByGroupNo(String groupNo);

    /** 审方队列：未审核的在途处方 */
    @Query("from OutpOrder o where o.orderType = 'DRUG' and o.status = 'CREATED' and o.reviewStatus is null order by o.id")
    List<OutpOrder> findPendingReviewDrugs();

    /**
     * 医技执行队列：已收费的检验/检查/治疗——<b>排除已登记病理标本（未拒收）的申请</b>。
     *
     * <p>v59 审阅补：EXECUTED 挪到正式签发后（2576-③）以后，病理申请在「已取材 / 已写诊断、未签发」窗口内
     * 仍是 CHARGED，按 {@code status='CHARGED' and orderType in (...)} 会一直列在执行站待执行队列直到签发，
     * 技师可在那里抢先置 EXECUTED，随后签发的 {@code update … and status='CHARGED'} 命中 0 行、orderExecuted=false。
     * 口径：登记了病理标本（{@code path_specimen.order_id} = 该申请 且 {@code rejected_at is null}）即视为
     * 已进入病理科流程，由病理科签发后自动置执行；拒收后重新出现在队列里。
     * 改为 native：path_specimen 无 JPA 实体（全仓 JdbcTemplate 直写），JPQL 关联不到它；列名按 outp_order 实体
     * 默认 snake_case 映射，排序不变。
     */
    @Query(value = "select o.* from outp_order o where o.status = 'CHARGED' and o.order_type in ('LAB', 'EXAM', 'TREAT') "
            + "and not exists (select 1 from path_specimen s where s.order_id = o.id and s.rejected_at is null) "
            + "order by o.id", nativeQuery = true)
    List<OutpOrder> chargedExecutables();

    /**
     * 该申请是否已登记病理标本（未拒收）。v59 审阅补：退费守卫（5005）与执行站守卫（7004）共用的只读判定；
     * 已拒收（{@code rejected_at is not null}）的标本不算——拒收后重送是临床常态（V145），拒收即退出病理科流程。
     */
    @Query(value = "select exists(select 1 from path_specimen s where s.order_id = :orderId and s.rejected_at is null)", nativeQuery = true)
    boolean hasRegisteredSpecimen(@Param("orderId") Long orderId);

    /** 有已收费待发药药品订单的挂号 id 列表（发药工作队列） */
    @Query("select distinct o.registrationId from OutpOrder o where o.orderType = 'DRUG' and o.status = 'CHARGED'")
    List<Long> registrationIdsWithChargedDrugs();

    /** 有未收费订单的挂号 id 列表（收费工作队列） */
    @Query("select distinct o.registrationId from OutpOrder o where o.status = 'CREATED'")
    List<Long> registrationIdsWithUnchargedOrders();

    /** 抢占收费：把 CREATED 挂到结算单上，受影响行数不足即有并发结算/作废，整单回滚 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutpOrder o set o.status = 'CHARGED', o.chargeId = :chargeId "
            + "where o.id in :ids and o.status = 'CREATED'")
    int claimCharge(@Param("ids") java.util.List<Long> ids, @Param("chargeId") Long chargeId);

    /** 抢占退费：把 CHARGED 退回 CREATED（并发发药会先把行改成 DISPENSED，此时行数不足即拒绝） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutpOrder o set o.status = 'CREATED', o.chargeId = null "
            + "where o.id in :ids and o.status = 'CHARGED'")
    int claimRefund(@Param("ids") java.util.List<Long> ids);

    /** 抢占发药：并发双发药时只有一方拿到行，另一方行数为 0 直接拒绝，避免库存双扣 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutpOrder o set o.status = 'DISPENSED' where o.id in :ids and o.status = 'CHARGED'")
    int claimDispense(@Param("ids") java.util.List<Long> ids);

    /** 抢占审方通过：并发双药师只有一方写入 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutpOrder o set o.reviewStatus = 'APPROVED', o.reviewerId = :reviewerId, "
            + "o.reviewNote = :note where o.id = :id and o.reviewStatus is null")
    int claimApprove(@Param("id") Long id, @Param("reviewerId") Long reviewerId, @Param("note") String note);

    /**
     * 抢占审方拒绝并作废：须仍未收费（status='CREATED'）——否则会出现
     * "已收费却 CANCELLED"（患者为作废药付了钱）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutpOrder o set o.reviewStatus = 'REJECTED', o.status = 'CANCELLED', "
            + "o.reviewerId = :reviewerId, o.reviewNote = :note "
            + "where o.id = :id and o.reviewStatus is null and o.status = 'CREATED'")
    int claimReject(@Param("id") Long id, @Param("reviewerId") Long reviewerId, @Param("note") String note);

    /** 抢占退药：并发/重复退药只有一方拿到行，避免库存被回补两次 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutpOrder o set o.status = 'CHARGED' where o.id = :id and o.status = 'DISPENSED'")
    int claimReturn(@Param("id") Long id);
}
