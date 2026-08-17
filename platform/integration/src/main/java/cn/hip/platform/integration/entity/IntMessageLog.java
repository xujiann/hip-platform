package cn.hip.platform.integration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 集成消息日志：所有进出站消息统一留痕（审计与重发的基础） */
@Getter
@Setter
@Entity
@Table(name = "int_message_log")
public class IntMessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** IN 进站 / OUT 出站 */
    @Column(nullable = false, length = 4)
    private String direction;

    /** 通道：HL7_LIS / YB / WX … */
    @Column(nullable = false, length = 16)
    private String channel;

    /** 业务关联号：申请单号/结算单号等 */
    @Column(length = 64)
    private String refNo;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    /** OK / FAIL */
    @Column(nullable = false, length = 8)
    private String status;

    @Column(length = 512)
    private String error;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
