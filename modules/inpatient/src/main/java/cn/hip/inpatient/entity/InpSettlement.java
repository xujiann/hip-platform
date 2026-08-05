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

    @Column(nullable = false, unique = true)
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

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
