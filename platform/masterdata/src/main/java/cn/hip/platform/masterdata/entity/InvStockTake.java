package cn.hip.platform.masterdata.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 库存盘点单头：账实对账，确认后按盈亏调库并写 STOCKTAKE 流水 */
@Getter
@Setter
@Entity
@Table(name = "inv_stock_take")
public class InvStockTake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String takeNo;

    /** DRAFT 草稿 / CONFIRMED 已确认 / CANCELLED 已作废 */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(length = 255)
    private String remark;

    private Long operatorId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant confirmedAt;
}
