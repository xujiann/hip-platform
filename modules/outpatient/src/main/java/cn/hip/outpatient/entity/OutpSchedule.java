package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 门诊排班（号源）：某科室/医生在某日某时段可挂的号 */
@Getter
@Setter
@Entity
@Table(name = "outp_schedule")
public class OutpSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deptId;

    /** 出诊医生；普通号可为空（按科室挂） */
    private Long doctorId;

    @Column(nullable = false)
    private LocalDate scheduleDate;

    /** 时段：AM 上午 / PM 下午 / FULL 全天 */
    @Column(nullable = false, length = 4)
    private String shift = "FULL";

    /** 号别：GENERAL 普通号 / EXPERT 专家号 */
    @Column(nullable = false, length = 16)
    private String regType = "GENERAL";

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fee = BigDecimal.ZERO;

    /** 号源总量 */
    @Column(nullable = false)
    private Integer capacity;

    /** 已挂数量（并发安全地在 SQL 里自增） */
    @Column(nullable = false)
    private Integer booked = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
