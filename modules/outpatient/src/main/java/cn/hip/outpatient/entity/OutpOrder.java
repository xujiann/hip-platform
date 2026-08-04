package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 门诊医嘱/费用明细：处方药品、检验、检查、治疗统一为订单行，
 * 是收费、发药、执行的共同数据源（费用不落两处账）。
 */
@Getter
@Setter
@Entity
@Table(name = "outp_order")
public class OutpOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long registrationId;

    /** 处方号（同一次开立的一组药品共号），非药品为申请单号 */
    @Column(nullable = false, length = 32)
    private String groupNo;

    /** DRUG 药品 / LAB 检验 / EXAM 检查 / TREAT 治疗 */
    @Column(nullable = false, length = 8)
    private String orderType;

    /** 药品 id 或收费项目 id */
    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false, length = 32)
    private String itemCode;

    @Column(nullable = false, length = 128)
    private String itemName;

    @Column(length = 64)
    private String spec;

    @Column(nullable = false, length = 8)
    private String unit;

    @Column(nullable = false)
    private Integer qty;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** 用法（口服/静滴…），药品用 */
    @Column(length = 32)
    private String usageRoute;

    /** 频次（bid/tid/qd…），药品用 */
    @Column(length = 16)
    private String frequency;

    /** 单次剂量描述，如 1粒 */
    @Column(length = 32)
    private String dosePerTime;

    /** 用药天数 */
    private Integer days;

    /** CREATED 已开立 / CHARGED 已收费 / DISPENSED 已发药 / EXECUTED 已执行 / CANCELLED 已作废 */
    @Column(nullable = false, length = 16)
    private String status = "CREATED";

    /** 结算单 id，收费后回填 */
    private Long chargeId;

    private Long doctorId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
