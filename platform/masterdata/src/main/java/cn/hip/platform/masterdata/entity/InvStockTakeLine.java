package cn.hip.platform.masterdata.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 盘点行：账面数（加行时快照）+ 实盘数（录入后），盈亏 = 实盘 - 账面 */
@Getter
@Setter
@Entity
@Table(name = "inv_stock_take_line")
public class InvStockTakeLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long takeId;

    @Column(nullable = false)
    private Long drugId;

    /** 账面数：加行时对 md_drug.stock 的快照，确认时作为条件更新的期望值 */
    @Column(nullable = false)
    private Integer bookQty;

    /** 实盘数：录入前为 null */
    private Integer actualQty;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
