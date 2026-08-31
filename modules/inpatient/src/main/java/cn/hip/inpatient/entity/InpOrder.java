package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 住院医嘱行：记账式（开立即计费，护士执行改变状态；药品执行时扣库存） */
@Getter
@Setter
@Entity
@Table(name = "inp_order")
public class InpOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long admissionId;

    @Column(nullable = false, length = 32)
    private String groupNo;

    /** DRUG / LAB / EXAM / TREAT */
    @Column(nullable = false, length = 8)
    private String orderType;

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

    @Column(length = 32)
    private String usageRoute;

    @Column(length = 16)
    private String frequency;

    @Column(length = 32)
    private String dosePerTime;

    /** CREATED 已开立 / EXECUTED 已执行 / CANCELLED 已作废 */
    @Column(nullable = false, length = 16)
    private String status = "CREATED";

    /**
     * v39：TEMP 临时（缺省，行为与历史逐字节一致）/ LONG 长期。
     * LONG 口径：amount=累计已执行金额、首次执行即 EXECUTED——下游读 "EXECUTED 的 amount" 零改动。
     */
    @Column(nullable = false, length = 8)
    private String orderNature = "TEMP";

    /** 停嘱时刻（仅 LONG；停嘱后不再生成/执行执行行，费用固化可参与中间结算） */
    private Instant stopAt;

    private Long stopDoctorId;

    private Long doctorId;

    private Long executorId;

    private Instant executedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
