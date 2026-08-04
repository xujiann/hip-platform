package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 住院押金缴纳记录 */
@Getter
@Setter
@Entity
@Table(name = "inp_deposit")
public class InpDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long admissionId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 16)
    private String payMethod;

    private Long operatorId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
