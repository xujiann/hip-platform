package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 急诊预检分诊：I 濒危 / II 危重 / III 急症 / IV 非急症 */
@Getter
@Setter
@Entity
@Table(name = "outp_triage")
public class OutpTriage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 已建档患者可关联；无名氏可空 */
    private Long patientId;

    @Column(nullable = false, length = 64)
    private String patientName;

    /** 分级 1-4（1 最重） */
    @Column(nullable = false)
    private Integer level;

    @Column(nullable = false, length = 255)
    private String chiefComplaint;

    @Column(precision = 4, scale = 1)
    private BigDecimal temperature;
    private Integer pulse;
    private Integer respiration;
    private Integer sbp;
    private Integer dbp;
    private Integer spo2;

    /** 分诊去向科室 */
    private Long destDeptId;

    /** TRIAGED 已分诊 / IN_TREATMENT 救治中 / ADMITTED 收住院 / DISCHARGED 离院 */
    @Column(nullable = false, length = 16)
    private String status = "TRIAGED";

    private Long nurseId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
