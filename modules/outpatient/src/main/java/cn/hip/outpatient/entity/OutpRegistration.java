package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 挂号记录（就诊的起点，后续门诊病历/处方/收费都挂在它下面） */
@Getter
@Setter
@Entity
@Table(name = "outp_registration")
public class OutpRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 当日该号源内的序号，叫号用 */
    @Column(nullable = false)
    private Integer regNo;

    @Column(nullable = false)
    private Long patientId;

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private Long deptId;

    private Long doctorId;

    @Column(nullable = false)
    private LocalDate visitDate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fee;

    /** REGISTERED 已挂号 / CANCELLED 已退号 / VISITED 已就诊 */
    @Column(nullable = false, length = 16)
    private String status = "REGISTERED";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant cancelledAt;
}
