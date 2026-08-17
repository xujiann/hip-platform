package cn.hip.cdr.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * CDR 临床文档：业务库抽取后的患者维度文档快照（调阅/上报/科研的统一数据源）。
 * docType+refId 唯一，重复同步为覆盖更新。
 */
@Getter
@Setter
@Entity
@Table(name = "cdr_document",
        uniqueConstraints = @UniqueConstraint(columnNames = {"docType", "refId"}))
public class CdrDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long patientId;

    /** OUTP_ENCOUNTER 门诊就诊 / LAB_REPORT 检验报告 / INP_SUMMARY 住院摘要 */
    @Column(nullable = false, length = 24)
    private String docType;

    /** 源业务主键：挂号 id / 医嘱 id / 住院 id */
    @Column(nullable = false)
    private Long refId;

    @Column(nullable = false, length = 128)
    private String title;

    /** 文档发生时间（就诊日/报告日/入院日） */
    @Column(nullable = false)
    private Instant docTime;

    /** 结构化内容 JSON */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private Instant syncedAt = Instant.now();
}
