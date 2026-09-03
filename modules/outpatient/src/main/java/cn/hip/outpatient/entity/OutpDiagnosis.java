package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 门诊诊断（一次就诊多条，第一条为主诊断）。
 *
 * <p>v44 车道E（偏离表 977/982/983/1084）补 5 个可空字段。<b>既有 5 个字段
 * （id / registrationId / icdCode / icdName / primaryDiag）的类型、非空性与语义一律不动</b>——
 * primaryDiag 是病案首页与 DRG 区分主诊断的唯一依据，icdCode/icdName 被
 * {@code CdrSyncService} 与 {@code PrintReportController} 直接读取。
 * 新字段全部 nullable：只传既有 5 个字段的旧调用方（E2E、既有单测、seed 脚本）行为逐字不变。
 */
@Getter
@Setter
@Entity
@Table(name = "outp_diagnosis")
public class OutpDiagnosis {

    /** 确诊 */
    public static final String CERTAINTY_CONFIRMED = "CONFIRMED";
    /** 疑诊 */
    public static final String CERTAINTY_SUSPECTED = "SUSPECTED";
    /** 西医（ICD-10） */
    public static final String SYSTEM_ICD10 = "ICD10";
    /** 中医 */
    public static final String SYSTEM_TCM = "TCM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long registrationId;

    @Column(nullable = false, length = 16)
    private String icdCode;

    @Column(nullable = false, length = 128)
    private String icdName;

    @Column(nullable = false)
    private Boolean primaryDiag = false;

    /** v44/982 诊断前缀（如「疑似」「陈旧性」）；可空 */
    @Column(length = 32)
    private String prefix;

    /** v44/982 诊断后缀（如「急性发作期」「术后」）；可空 */
    @Column(length = 32)
    private String suffix;

    /** v44/983 确诊 CONFIRMED / 疑诊 SUSPECTED；可空，历史行为 null 且不回填 */
    @Column(length = 16)
    private String certainty;

    /**
     * v44/1084 诊断体系：ICD10 西医 / TCM 中医；可空。
     * 历史行为 null，读侧按西医解释即可，<b>但不做数据回填</b>。
     * 取 TCM 时本仓不提供中医编码字典（无权威码表，不编造），icdCode 写空串、诊断名走自由录入。
     */
    @Column(length = 8)
    private String diagSystem;

    /** v44/977 自定义临床诊断名称描述；与 icdName <b>并存不替代</b> */
    @Column(length = 128)
    private String customName;
}
