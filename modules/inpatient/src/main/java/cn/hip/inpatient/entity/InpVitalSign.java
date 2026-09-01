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

    // ===== v42 三测单纸面格位（V129 扩列，全部 nullable；历史行为 null 属正常） =====

    /**
     * 入量 ml（该次记录）。
     * 日汇总口径见 {@code GET /print/temp-sheet}——同日若有 ICU 记录则 ICU 优先，
     * 两表**绝不合并**（inp_icu_record 有独立写路径与 gcs/ventilator 独有语义）。
     */
    private Integer intakeMl;

    /** 出量 ml（该次记录）；日汇总口径同 {@link #intakeMl} */
    private Integer outputMl;

    /** 大便次数（当日） */
    private Integer stoolCount;

    /** 体重 kg */
    @Column(precision = 5, scale = 1)
    private BigDecimal weightKg;

    /** 身高 cm */
    private Integer heightCm;

    /** 体温测量部位 ORAL 口温 / AXILLARY 腋温 / RECTAL 肛温（取值校验推 v43，本版不设约束） */
    private String measureSite;

    /** 物理降温后体温 ℃——纸面三测单以虚线回连原体温点 */
    @Column(precision = 4, scale = 1)
    private BigDecimal tempAfterCooling;

    /** 未测原因（外出/拒测/手术中等）——三测单画「未测」而不断线 */
    private String notMeasuredReason;
}
