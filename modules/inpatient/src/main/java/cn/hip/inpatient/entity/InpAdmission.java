package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 住院登记（一次住院） */
@Getter
@Setter
@Entity
@Table(name = "inp_admission")
public class InpAdmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 住院号，如 ZY20260804-000001 */
    @Column(nullable = false, unique = true, length = 32)
    private String admissionNo;

    @Column(nullable = false)
    private Long patientId;

    /** 收治科室（临床科室） */
    @Column(nullable = false)
    private Long deptId;

    @Column(nullable = false)
    private Long wardId;

    @Column(nullable = false)
    private Long bedId;

    /** 主管医生 */
    private Long doctorId;

    @Column(length = 16)
    private String admitDiagIcd;

    @Column(length = 128)
    private String admitDiagName;

    /** IN_HOSPITAL 在院 / DISCHARGED 已出院 */
    @Column(nullable = false, length = 16)
    private String status = "IN_HOSPITAL";

    @Column(nullable = false, updatable = false)
    private Instant admitAt = Instant.now();

    private Instant dischargedAt;
}
