package cn.hip.platform.masterdata.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** 药品入库单 */
@Getter
@Setter
@Entity
@Table(name = "inv_stock_in")
public class InvStockIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String inNo;

    @Column(nullable = false)
    private Long drugId;

    @Column(nullable = false)
    private Integer qty;

    @Column(length = 32)
    private String batchNo;

    private LocalDate expireDate;

    @Column(length = 128)
    private String supplier;

    private Long operatorId;

    /** 验收状态：PENDING_ACCEPT 待验收 / ACCEPTED 已验收入账 / REJECTED 已拒收（新入库默认待验收，验收前不加库存） */
    @Column(nullable = false, length = 16)
    private String acceptStatus = "PENDING_ACCEPT";

    /** 采购单号：与采购勾稽为可选，有则关联，无则允许直接入库验收 */
    @Column(length = 32)
    private String purchaseNo;

    /** 验收人 */
    private Long acceptedBy;

    /** 验收/拒收时间 */
    private Instant acceptedAt;

    /** 拒收原因 */
    @Column(length = 255)
    private String rejectReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
