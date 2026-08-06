package cn.hip.bureau.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 处罚告知/陈述申辩/听证（第41条：不得因陈述、申辩或申请听证而加重处罚） */
@Getter
@Setter
@Entity
@Table(name = "case_notice")
public class CaseNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private LocalDate notifiedAt;

    /** 拟作出行政处罚决定的事实、理由及依据 */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal proposedFine = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal proposedRecoup = BigDecimal.ZERO;

    @Column(nullable = false)
    private Boolean hearingEntitled = false;

    @Column(nullable = false)
    private Boolean hearingRequested = false;

    private LocalDate hearingHeldAt;

    @Column(columnDefinition = "text")
    private String statement;

    @Column(columnDefinition = "text")
    private String statementReview;
}
