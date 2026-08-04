package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 检验结果行（LIS 回传落库） */
@Getter
@Setter
@Entity
@Table(name = "outp_lab_result")
public class OutpLabResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(length = 32)
    private String itemCode;

    @Column(nullable = false, length = 128)
    private String itemName;

    @Column(nullable = false, length = 64)
    private String resultValue;

    @Column(length = 16)
    private String unit;

    @Column(length = 64)
    private String refRange;

    /** N/H/L/HH/LL */
    @Column(length = 4)
    private String abnormalFlag;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
