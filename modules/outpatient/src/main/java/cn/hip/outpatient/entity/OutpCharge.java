package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 门诊结算单（一次收费一张，含多条订单行） */
@Getter
@Setter
@Entity
@Table(name = "outp_charge")
public class OutpCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 结算单号，如 SJ20260804-000001 */
    @Column(nullable = false, unique = true, length = 32)
    private String chargeNo;

    @Column(nullable = false)
    private Long registrationId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** CASH 现金 / WECHAT 微信 / ALIPAY 支付宝 / YB 医保 */
    @Column(nullable = false, length = 16)
    private String payMethod;

    /** PAID 已支付 / REFUNDED 已退费 */
    @Column(nullable = false, length = 16)
    private String status = "PAID";

    /** 收费员 */
    private Long cashierId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
