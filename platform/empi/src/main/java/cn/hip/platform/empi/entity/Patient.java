package cn.hip.platform.empi.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 患者主索引（EMPI）：全院唯一患者档案。
 * 所有业务模块通过 patient.id 关联患者，不冗余患者基本信息。
 */
@Getter
@Setter
@Entity
@Table(name = "empi_patient")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 院内患者编号（建档时生成，条码/就诊卡用），如 P00000123 */
    @Column(nullable = false, unique = true, length = 20)
    private String patientNo;

    @Column(nullable = false, length = 64)
    private String name;

    /** 性别：M 男 / F 女 / U 未知 */
    @Column(nullable = false, length = 1)
    private String sex;

    private LocalDate birthDate;

    /** 证件类型：ID_CARD 身份证 / PASSPORT 护照 / OTHER */
    @Column(length = 16)
    private String idType;

    @Column(length = 32)
    private String idNo;

    @Column(length = 32)
    private String phone;

    @Column(length = 255)
    private String address;

    /** 医保类型：SELF 自费 / YB_STAFF 职工医保 / YB_RESIDENT 居民医保 */
    @Column(length = 16)
    private String insuranceType = "SELF";

    @Column(length = 8)
    private String bloodType;

    /** 过敏史（简要文本，结构化过敏在 EMR 模块） */
    @Column(length = 512)
    private String allergyHistory;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
