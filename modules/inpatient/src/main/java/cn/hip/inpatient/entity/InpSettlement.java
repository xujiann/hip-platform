package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 出院结算单 */
@Getter
@Setter
@Entity
@Table(name = "inp_settlement")
public class InpSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String settleNo;

    /** 唯一性由部分索引 uq_inp_settlement_active 保证（status<>'CANCELLED'）——冲销后可重新结算 */
    @Column(nullable = false)
    private Long admissionId;

    /** 费用总额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** 押金总额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal depositAmount;

    /** 押金 - 费用：正数应退、负数应补 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance;

    private Long cashierId;

    /** 结清方式：CASH / YB 医保（差额部分） */
    @Column(nullable = false, length = 16)
    private String payMethod = "CASH";

    /** 医保结算号（YB 单，通道返回后回填） */
    @Column(length = 64)
    private String ybSettleNo;

    /** PAID 有效 / CANCELLED 已冲销（出院召回） */
    @Column(nullable = false, length = 16)
    private String status = "PAID";

    /**
     * 结算类型：FINAL 出院结算（一次入院唯一，唯一索引 uq_inp_settlement_active 保证）/
     * INTERIM 住院中间结算（可多张，不受唯一约束）。缺省 FINAL——discharge() 不设此值即为 FINAL，
     * 历史行为零变化；中间结算金额恒为出院总额子集，收入确认只认 FINAL，二者不相加（V90 注释）。
     */
    @Column(nullable = false, length = 16)
    private String settleType = "FINAL";

    /** 冲销时间（账务按冲销日归集，不改写结算日历史报表） */
    private Instant refundedAt;

    /** 冲销操作员 */
    private Long refundBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
