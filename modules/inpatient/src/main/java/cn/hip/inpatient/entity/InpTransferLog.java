package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 转科转床记录 */
@Getter
@Setter
@Entity
@Table(name = "inp_transfer_log")
public class InpTransferLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long admissionId;

    @Column(nullable = false)
    private Long fromDeptId;

    @Column(nullable = false)
    private Long toDeptId;

    @Column(nullable = false)
    private Long fromBedId;

    @Column(nullable = false)
    private Long toBedId;

    private Long operatorId;

    /** 转科原因（收尾环·阻塞3）：UI 采集，可空以兼容历史/接口机记录 */
    @Column(length = 200)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
