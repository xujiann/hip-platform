package cn.hip.hrp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 固定资产台账 */
@Getter
@Setter
@Entity
@Table(name = "hrp_asset")
public class HrpAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String assetNo;

    @Column(nullable = false, length = 128)
    private String name;

    /** 医疗设备/办公设备/房屋建筑/信息化设备 */
    @Column(nullable = false, length = 32)
    private String category;

    private Long deptId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private LocalDate purchaseDate;

    /** 折旧年限 */
    @Column(nullable = false)
    private Integer usefulYears = 5;

    /** IN_USE 在用 / REPAIR 维修 / SCRAPPED 报废 */
    @Column(nullable = false, length = 16)
    private String status = "IN_USE";

    @Column(length = 255)
    private String remark;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** 直线折旧后净值（残值率 5%） */
    @Transient
    public BigDecimal getNetValue() {
        long months = ChronoUnit.MONTHS.between(purchaseDate, LocalDate.now());
        BigDecimal salvage = price.multiply(new BigDecimal("0.05"));
        BigDecimal monthly = price.subtract(salvage)
                .divide(BigDecimal.valueOf(usefulYears * 12L), 2, RoundingMode.HALF_UP);
        BigDecimal depreciated = monthly.multiply(BigDecimal.valueOf(Math.max(0, months)));
        BigDecimal net = price.subtract(depreciated);
        return net.compareTo(salvage) < 0 ? salvage : net;
    }
}
