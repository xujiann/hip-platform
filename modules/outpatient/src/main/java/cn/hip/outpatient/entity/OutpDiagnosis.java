package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 门诊诊断（一次就诊多条，第一条为主诊断） */
@Getter
@Setter
@Entity
@Table(name = "outp_diagnosis")
public class OutpDiagnosis {

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
}
