package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 危急值告警（HH/LL 结果自动生成，须专人确认处理并可追溯） */
@Getter
@Setter
@Entity
@Table(name = "outp_critical_alert")
public class OutpCriticalAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long registrationId;

    @Column(nullable = false, length = 512)
    private String content;

    /** NEW 待处理 / HANDLED 已处理 */
    @Column(nullable = false, length = 16)
    private String status = "NEW";

    private Long handlerId;

    private Instant handledAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
