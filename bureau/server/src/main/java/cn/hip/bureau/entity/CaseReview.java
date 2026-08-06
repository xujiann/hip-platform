package cn.hip.bureau.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 法制审核（第37-40条：触发情形内未经审核或未通过不得作出决定；10个工作日） */
@Getter
@Setter
@Entity
@Table(name = "case_review")
public class CaseReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false, length = 255)
    private String requiredReason;

    @Column(nullable = false)
    private LocalDate submittedAt;

    @Column(nullable = false)
    private LocalDate deadlineAt;

    @Column(length = 64)
    private String reviewer;

    /** AGREE 同意 / CONTINUE 继续调查 / CHANGE 变更 / CORRECT 纠正 / OTHER */
    @Column(length = 24)
    private String opinionType;

    @Column(columnDefinition = "text")
    private String opinion;

    private LocalDate reviewedAt;

    private Boolean passed;
}
