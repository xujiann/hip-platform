package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 检查检验/治疗执行结果（结构化报告后续对接 LIS/PACS，先支持文本结果） */
@Getter
@Setter
@Entity
@Table(name = "outp_order_report")
public class OutpOrderReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long orderId;

    @Column(length = 2000)
    private String resultText;

    /** 执行人 */
    private Long executorId;

    @Column(nullable = false)
    private Instant executedAt = Instant.now();
}
