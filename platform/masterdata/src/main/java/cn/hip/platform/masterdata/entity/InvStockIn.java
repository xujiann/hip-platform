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

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
