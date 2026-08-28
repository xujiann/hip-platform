package cn.hip.platform.masterdata.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 库存流水：IN 入库 / OUT 发药或病区执行 / RET 退药回补 / ADJ 盘点调整 / STOCKTAKE 盘点单确认 */
@Getter
@Setter
@Entity
@Table(name = "inv_transaction")
public class InvTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long drugId;

    @Column(nullable = false, length = 16)
    private String type;

    /** 正数入、负数出 */
    @Column(nullable = false)
    private Integer qty;

    /** 变动后库存 */
    @Column(nullable = false)
    private Integer stockAfter;

    /** 关联单号：入库单号/处方号/调整原因 */
    @Column(length = 64)
    private String refNo;

    private Long operatorId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
