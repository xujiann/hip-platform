package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 生命体征记录（体温单数据源） */
@Getter
@Setter
@Entity
@Table(name = "inp_vital_sign")
public class InpVitalSign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long admissionId;

    @Column(nullable = false)
    private Instant measuredAt = Instant.now();

    /** 体温 ℃ */
    @Column(precision = 4, scale = 1)
    private BigDecimal temperature;

    /** 脉搏 次/分 */
    private Integer pulse;

    /** 呼吸 次/分 */
    private Integer respiration;

    /** 收缩压/舒张压 mmHg */
    private Integer sbp;
    private Integer dbp;

    /** 血氧饱和度 % */
    private Integer spo2;

    private Long recorderId;
}
