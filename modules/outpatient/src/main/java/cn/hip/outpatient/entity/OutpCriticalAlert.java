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

    /** NEW 待确认 / HANDLED 已确认（接收确认+处置后进入终态） */
    @Column(nullable = false, length = 16)
    private String status = "NEW";

    private Long handlerId;

    private Instant handledAt;

    /** v33：双通道来源 LAB/RIS */
    @Column(nullable = false, length = 8)
    private String source = "LAB";

    /** v33：应通知的开单医师（闭环的"通知到人"）——LAB 取 order.doctorId，RIS 同理 */
    private Long notifyToUserId;

    private Instant notifiedAt;

    /** v33：应接收确认时限（生成时刻 + critical_ack_deadline_minutes），超时进超期看板 */
    private Instant deadlineAt;

    /** v33：接收确认人（开单医师本人）与时刻、处置措施留痕 */
    private Long ackBy;

    private Instant ackAt;

    @Column(length = 512)
    private String disposition;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
