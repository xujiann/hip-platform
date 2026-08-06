package cn.hip.bureau.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 处理决定（第43条：处罚/不予处罚/不成立/移送其他部门/移送司法机关） */
@Getter
@Setter
@Entity
@Table(name = "case_decision")
public class CaseDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long caseId;

    /** PUNISH / NO_PUNISH / NOT_ESTABLISHED / TRANSFER_ADMIN / TRANSFER_JUDICIAL */
    @Column(nullable = false, length = 24)
    private String decisionType;

    @Column(unique = true, length = 64)
    private String decisionNo;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal fineAmount = BigDecimal.ZERO;

    /** 责令退回基金（退回原基金财政专户，第53条） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal recoupAmount = BigDecimal.ZERO;

    /** 没收违法所得（上缴国库） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal confiscateAmount = BigDecimal.ZERO;

    @Column(length = 512)
    private String otherMeasures;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private LocalDate decidedAt;

    /** 处罚决定公开（第46条） */
    @Column(nullable = false)
    private Boolean published = false;
}
