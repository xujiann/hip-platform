package cn.hip.bureau.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 案件（医保局令第4号）。
 * 状态机：INVESTIGATING ⇄ SUSPENDED；→ TERMINATED（终态）；
 * → REPORTED（调查终结）→ NOTIFIED（已告知）→ DECIDED（已决定）→ DELIVERED（已送达）→ CLOSED（结案）。
 * 简易程序（SUMMARY）：立案后直接决定。
 */
@Getter
@Setter
@Entity
@Table(name = "case_file")
public class CaseFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String caseNo;

    /** 调查阶段：主体+涉嫌+案由+案；决定后去"涉嫌" */
    @Column(nullable = false, length = 255)
    private String name;

    private Long clueId;

    @Column(nullable = false)
    private Long causeId;

    /** NORMAL 普通程序 / SUMMARY 简易程序 */
    @Column(nullable = false, length = 16)
    private String procedureType = "NORMAL";

    @Column(nullable = false, length = 128)
    private String partyName;

    @Column(nullable = false, length = 16)
    private String partyType;

    @Column(length = 64)
    private String partyCreditNo;

    @Column(length = 255)
    private String partyAddress;

    @Column(length = 64)
    private String partyLegalRep;

    @Column(length = 64)
    private String partyContact;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(precision = 14, scale = 2)
    private BigDecimal amountInvolved = BigDecimal.ZERO;

    @Column(nullable = false, length = 16)
    private String status = "INVESTIGATING";

    @Column(nullable = false)
    private LocalDate filedAt;

    /** 办案期限（立案之日起 90 日，第45条；延期后顺延） */
    @Column(nullable = false)
    private LocalDate deadlineAt;

    /** 已批准延长天数：首次 ≤30，集体讨论再延累计 ≤90 */
    @Column(nullable = false)
    private Integer extensionDays = 0;

    @Column(length = 255)
    private String suspendReason;

    private LocalDate suspendedAt;

    @Column(length = 255)
    private String terminateReason;

    private LocalDate terminatedAt;

    private LocalDate reportedAt;

    private LocalDate decidedAt;

    private LocalDate deliveredAt;

    private LocalDate closedAt;

    /** EXECUTED 执行完毕 / COURT 法院受理强执 / NO_NEED 无须执行 / OTHER */
    @Column(length = 16)
    private String closeReason;

    @Column(length = 64)
    private String archiveNo;

    /** 暂缓/分期缴纳已批准（第54条） */
    @Column(nullable = false)
    private Boolean deferApproved = false;

    /** 已申请法院强制执行（第55条） */
    @Column(nullable = false)
    private Boolean courtEnforceApplied = false;

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
