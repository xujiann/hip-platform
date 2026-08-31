package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 住院病历记录：ADMISSION 入院记录 / PROGRESS 病程记录 / DISCHARGE 出院小结 / ROUND 三级查房（v34） */
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

    @Column(nullable = false, length = 4000)
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
