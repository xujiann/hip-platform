package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 住院病历记录：ADMISSION 入院记录 / PROGRESS 病程记录 / DISCHARGE 出院小结 */
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

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
