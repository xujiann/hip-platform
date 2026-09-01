package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 住院病历记录：ADMISSION 入院记录 / FIRST_PROGRESS 首次病程 / PROGRESS 病程记录 /
 * ROUND 三级查房（v34） / PREOP 术前小结（v42 补录入入口） / DISCHARGE 出院小结。
 *
 * <p>record_type 刻意**不加数据库 CHECK 也不加写入白名单**（试点库历史脏类型会挡住 Flyway；
 * 写入侧白名单排 v43 单独成版）——EmrIntegrityService 按类型计数判缺项，故新增类型只需前端
 * 下拉能选到即可自救。
 */
@Getter
@Setter
@Entity
@Table(name = "inp_medical_record")
public class InpMedicalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long admissionId;

    @Column(nullable = false, length = 16)
    private String recordType;

    @Column(nullable = false, length = 100)
    private String title;

    /**
     * v42（V133）：varchar(4000) → text。模板渲染后的多段病历（主诉/现病史/既往史/体格检查/
     * 辅助检查/初步诊断）必然超 4000——门诊侧 outp_emr 同类五字段合计已 5512 字符。
     * 与 IntMessageLog.payload 同写法（columnDefinition 显式对齐 DDL，ddl-auto=validate 下不误判）。
     */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    private Long doctorId;

    /** v34 三级查房结构化（仅 record_type=ROUND 行使用）：级别/查房医师/查房意见/上级修正意见 */
    @Column(length = 16)
    private String roundLevel;

    private Long roundDoctorId;

    @Column(length = 2000)
    private String roundOpinion;

    @Column(length = 2000)
    private String superiorCorrection;

    /** 1.0.4：CA 签名（与门诊 outp_emr 齐平），签名后冻结 */
    @Column(length = 512)
    private String signature;

    private Instant signedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
