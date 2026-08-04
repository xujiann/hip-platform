package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 门诊病历（每次挂号一份） */
@Getter
@Setter
@Entity
@Table(name = "outp_emr")
public class OutpEmr {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long registrationId;

    /** 主诉 */
    @Column(length = 512)
    private String chiefComplaint;

    /** 现病史 */
    @Column(length = 2000)
    private String presentIllness;

    /** 既往史 */
    @Column(length = 1000)
    private String pastHistory;

    /** 体格检查 */
    @Column(length = 1000)
    private String physicalExam;

    /** 处理意见 */
    @Column(length = 1000)
    private String advice;

    /** 书写医生 */
    private Long doctorId;

    /** 电子签名值（签名后病历冻结） */
    @Column(length = 128)
    private String signature;

    private Instant signedAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
